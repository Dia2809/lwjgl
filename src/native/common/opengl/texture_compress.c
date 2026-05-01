/*
 * texture_compress.c
 *
 * On-the-fly software texture compression (DXT1/DXT5 and ETC2 RGB8/RGBA8).
 * Detected at runtime from GL_EXTENSIONS; falls back to uncompressed if
 * neither S3TC nor ETC2 is available.
 *
 * VRAM savings vs uncompressed:
 *   DXT1  (RGB) : 4 bpp  vs 24 bpp  -> ~6x
 *   DXT5  (RGBA): 8 bpp  vs 32 bpp  -> ~4x
 *   ETC2  (RGB) : 4 bpp  vs 24 bpp  -> ~6x
 *   ETC2  (RGBA): 8 bpp  vs 32 bpp  -> ~4x
 *
 * JNI class: org.lwjgl.examples.spaceinvaders.TextureCompressor
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "extgl.h"

/* --------------------------------------------------------------------------
 * GL constants (avoid pulling in full GL headers here)
 * -------------------------------------------------------------------------- */
#ifndef GL_EXTENSIONS
#define GL_EXTENSIONS 0x1F03
#endif

#define TC_GL_COMPRESSED_RGB_S3TC_DXT1_EXT   0x83F0
#define TC_GL_COMPRESSED_RGBA_S3TC_DXT5_EXT  0x83F3
#define TC_GL_COMPRESSED_RGB8_ETC2            0x9274
#define TC_GL_COMPRESSED_RGBA8_ETC2_EAC       0x9278
#define TC_FORMAT_NONE                        -1

/* --------------------------------------------------------------------------
 * Helpers
 * -------------------------------------------------------------------------- */

static int tc_clamp(int v) {
    return v < 0 ? 0 : (v > 255 ? 255 : v);
}

/* Write unsigned short little-endian */
static void write_le16(unsigned char *p, unsigned short v) {
    p[0] = (unsigned char)(v & 0xFF);
    p[1] = (unsigned char)(v >> 8);
}

/* Write unsigned int little-endian */
static void write_le32(unsigned char *p, unsigned int v) {
    p[0] = (unsigned char)(v        & 0xFF);
    p[1] = (unsigned char)((v >> 8) & 0xFF);
    p[2] = (unsigned char)((v >>16) & 0xFF);
    p[3] = (unsigned char)((v >>24) & 0xFF);
}

/* ==========================================================================
 * DXT1 (BC1) – RGB, 4 bits/pixel
 *
 * Block layout (8 bytes, little-endian):
 *   uint16 color0, color1  (RGB565)
 *   uint32 indices          (2 bpp, pixel 0 at bits [1:0], row-major)
 *
 * color0 > color1 -> 4-colour opaque mode (always used here).
 * ========================================================================== */

static unsigned short pack565(int r, int g, int b) {
    return (unsigned short)(((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3));
}

static void unpack565(unsigned short c, int *r, int *g, int *b) {
    *r = ((c >> 11) & 0x1F) * 255 / 31;
    *g = ((c >>  5) & 0x3F) * 255 / 63;
    *b = ( c        & 0x1F) * 255 / 31;
}

static void encode_dxt1_block(unsigned char *out,
                               const int r[16], const int g[16], const int b[16]) {
    int i, j;
    int minR = 255, minG = 255, minB = 255;
    int maxR = 0,   maxG = 0,   maxB = 0;
    unsigned short c0, c1;
    int pr[4], pg[4], pb[4];
    unsigned int indices = 0;

    for (i = 0; i < 16; i++) {
        if (r[i] < minR) minR = r[i];  if (r[i] > maxR) maxR = r[i];
        if (g[i] < minG) minG = g[i];  if (g[i] > maxG) maxG = g[i];
        if (b[i] < minB) minB = b[i];  if (b[i] > maxB) maxB = b[i];
    }

    c0 = pack565(maxR, maxG, maxB);
    c1 = pack565(minR, minG, minB);

    /* Guarantee c0 > c1 for 4-colour opaque mode */
    if (c0 < c1) { unsigned short t = c0; c0 = c1; c1 = t; }
    if (c0 == c1) { if (c0 > 0) c1--; else c0++; }

    unpack565(c0, &pr[0], &pg[0], &pb[0]);
    unpack565(c1, &pr[1], &pg[1], &pb[1]);
    pr[2] = (2*pr[0] + pr[1] + 1) / 3;
    pg[2] = (2*pg[0] + pg[1] + 1) / 3;
    pb[2] = (2*pb[0] + pb[1] + 1) / 3;
    pr[3] = (pr[0] + 2*pr[1] + 1) / 3;
    pg[3] = (pg[0] + 2*pg[1] + 1) / 3;
    pb[3] = (pb[0] + 2*pb[1] + 1) / 3;

    for (i = 0; i < 16; i++) {
        int best = 0, best_err = 0x7FFFFFFF;
        for (j = 0; j < 4; j++) {
            int dr = r[i]-pr[j], dg = g[i]-pg[j], db = b[i]-pb[j];
            int err = dr*dr + dg*dg + db*db;
            if (err < best_err) { best_err = err; best = j; }
        }
        indices |= ((unsigned int)best << (i * 2));
    }

    write_le16(out,     c0);
    write_le16(out + 2, c1);
    write_le32(out + 4, indices);
}

/* Compress RGB8 (stride=3) or RGBA8 (stride=4) source to DXT1.
 * out must have ((w+3)/4) * ((h+3)/4) * 8 bytes. */
static void compress_dxt1(const unsigned char *src, int w, int h,
                           int stride, unsigned char *out) {
    int bx, by, px, py;
    int bw = (w + 3) / 4;
    int bh = (h + 3) / 4;

    for (by = 0; by < bh; by++) {
        for (bx = 0; bx < bw; bx++) {
            int r[16], g[16], b[16];
            for (py = 0; py < 4; py++) {
                int sy = by*4 + py < h ? by*4 + py : h-1;
                for (px = 0; px < 4; px++) {
                    int sx  = bx*4 + px < w ? bx*4 + px : w-1;
                    int off = (sy*w + sx) * stride;
                    int i   = py*4 + px;
                    r[i] = src[off];
                    g[i] = src[off+1];
                    b[i] = src[off+2];
                }
            }
            encode_dxt1_block(out, r, g, b);
            out += 8;
        }
    }
}

/* ==========================================================================
 * BC4 – single 8-bit channel block (alpha portion of DXT5)
 *
 * Layout (8 bytes):
 *   byte 0: a0 (endpoint, >= a1)
 *   byte 1: a1 (endpoint)
 *   bytes 2-7: 16 × 3-bit indices, pixel 0 at bits[2:0], little-endian
 * ========================================================================== */

static void encode_bc4_block(unsigned char *out, const int a[16]) {
    int i, j;
    int minA = 255, maxA = 0;
    int pa[8];
    unsigned long long bits = 0;

    for (i = 0; i < 16; i++) {
        if (a[i] < minA) minA = a[i];
        if (a[i] > maxA) maxA = a[i];
    }

    out[0] = (unsigned char)maxA;
    out[1] = (unsigned char)minA;

    /* 8-interpolant mode (a0 > a1) */
    pa[0] = maxA;
    pa[1] = minA;
    if (maxA > minA) {
        for (i = 2; i < 8; i++)
            pa[i] = ((8-i)*maxA + (i-1)*minA) / 7;
    } else {
        for (i = 2; i < 6; i++) pa[i] = (maxA + minA) / 2;
        pa[6] = 0;
        pa[7] = 255;
    }

    /* Pixel 0 index in bits[2:0], little-endian across 6 bytes */
    for (i = 0; i < 16; i++) {
        int best = 0, best_err = 0x7FFFFFFF;
        for (j = 0; j < 8; j++) {
            int d = a[i] - pa[j];
            int err = d * d;
            if (err < best_err) { best_err = err; best = j; }
        }
        bits |= ((unsigned long long)best << (i * 3));
    }

    for (i = 0; i < 6; i++) {
        out[2+i] = (unsigned char)(bits & 0xFF);
        bits >>= 8;
    }
}

/* Compress RGBA8 source to DXT5.
 * out must have ((w+3)/4) * ((h+3)/4) * 16 bytes. */
static void compress_dxt5(const unsigned char *src, int w, int h,
                           unsigned char *out) {
    int bx, by, px, py;
    int bw = (w + 3) / 4;
    int bh = (h + 3) / 4;

    for (by = 0; by < bh; by++) {
        for (bx = 0; bx < bw; bx++) {
            int r[16], g[16], b[16], a[16];
            for (py = 0; py < 4; py++) {
                int sy = by*4 + py < h ? by*4 + py : h-1;
                for (px = 0; px < 4; px++) {
                    int sx  = bx*4 + px < w ? bx*4 + px : w-1;
                    int off = (sy*w + sx) * 4;
                    int i   = py*4 + px;
                    r[i] = src[off];
                    g[i] = src[off+1];
                    b[i] = src[off+2];
                    a[i] = src[off+3];
                }
            }
            encode_bc4_block(out,     a);    /* 8 bytes: alpha */
            encode_dxt1_block(out+8, r, g, b); /* 8 bytes: RGB  */
            out += 16;
        }
    }
}

/* ==========================================================================
 * ETC2 RGB8 – 4 bits/pixel  (individual mode, compatible with GLES3)
 *
 * Block layout (8 bytes, big-endian):
 *   byte 0: R0[3:0] R1[3:0]
 *   byte 1: G0[3:0] G1[3:0]
 *   byte 2: B0[3:0] B1[3:0]
 *   byte 3: table0[2:0] table1[2:0] diff=0 flip=0
 *   bytes 4-5: pixel MSBs  (bit 15-p for ETC column-major pixel p)
 *   bytes 6-7: pixel LSBs
 *
 * With flip=0: sub-block 0 = columns 0-1 (ETC pixels 0-7),
 *              sub-block 1 = columns 2-3 (ETC pixels 8-15).
 *
 * ETC column-major pixel p -> row-major array index: (p%4)*4 + (p/4)
 *
 * Modifier index -> (msb,lsb):
 *   0 (most neg) -> (1,1)
 *   1            -> (1,0)
 *   2            -> (0,0)
 *   3 (most pos) -> (0,1)
 * ========================================================================== */

/* ETC1/ETC2 modifier table: {small_positive, large_positive} per row */
static const int ETC_MOD[8][2] = {
    { 2,  8}, { 5, 17}, { 9, 29}, {13, 42},
    {18, 60}, {24, 80}, {33,106}, {47,183}
};

static int etc_modifier(int t, int idx) {
    switch (idx) {
        case 0: return -ETC_MOD[t][1];
        case 1: return -ETC_MOD[t][0];
        case 2: return  ETC_MOD[t][0];
        default: return ETC_MOD[t][1];
    }
}

/* ETC column-major pixel index -> row-major array index */
static int etc_to_array(int p) {
    return (p % 4) * 4 + (p / 4);
}

static void encode_etc2_rgb_block(unsigned char *out,
                                   const int r[16], const int g[16],
                                   const int b[16]) {
    int p, i, t;
    int sr, sg, sb;
    int er0, eg0, eb0, er1, eg1, eb1;
    int r0, g0, b0, r1, g1, b1;
    int table0, table1;
    int best_err, err;
    unsigned int msb = 0, lsb = 0;

    /* Average colour of each sub-block */
    sr = sg = sb = 0;
    for (p = 0; p < 8; p++) {
        int ap = etc_to_array(p);
        sr += r[ap]; sg += g[ap]; sb += b[ap];
    }
    er0 = sr / 8;  eg0 = sg / 8;  eb0 = sb / 8;

    sr = sg = sb = 0;
    for (p = 8; p < 16; p++) {
        int ap = etc_to_array(p);
        sr += r[ap]; sg += g[ap]; sb += b[ap];
    }
    er1 = sr / 8;  eg1 = sg / 8;  eb1 = sb / 8;

    /* Quantise to 4-bit per channel */
    r0 = er0 >> 4;  g0 = eg0 >> 4;  b0 = eb0 >> 4;
    r1 = er1 >> 4;  g1 = eg1 >> 4;  b1 = eb1 >> 4;

    /* Expand back: nibble replicated (0xN -> 0xNN) */
    er0 = (r0<<4)|r0;  eg0 = (g0<<4)|g0;  eb0 = (b0<<4)|b0;
    er1 = (r1<<4)|r1;  eg1 = (g1<<4)|g1;  eb1 = (b1<<4)|b1;

    /* Best modifier table for sub-block 0 */
    table0 = 0;
    best_err = 0x7FFFFFFF;
    for (t = 0; t < 8; t++) {
        err = 0;
        for (p = 0; p < 8; p++) {
            int ap = etc_to_array(p);
            int min_pe = 0x7FFFFFFF;
            for (i = 0; i < 4; i++) {
                int m  = etc_modifier(t, i);
                int dr = r[ap] - tc_clamp(er0+m);
                int dg = g[ap] - tc_clamp(eg0+m);
                int db = b[ap] - tc_clamp(eb0+m);
                int e  = dr*dr + dg*dg + db*db;
                if (e < min_pe) min_pe = e;
            }
            err += min_pe;
        }
        if (err < best_err) { best_err = err; table0 = t; }
    }

    /* Best modifier table for sub-block 1 */
    table1 = 0;
    best_err = 0x7FFFFFFF;
    for (t = 0; t < 8; t++) {
        err = 0;
        for (p = 8; p < 16; p++) {
            int ap = etc_to_array(p);
            int min_pe = 0x7FFFFFFF;
            for (i = 0; i < 4; i++) {
                int m  = etc_modifier(t, i);
                int dr = r[ap] - tc_clamp(er1+m);
                int dg = g[ap] - tc_clamp(eg1+m);
                int db = b[ap] - tc_clamp(eb1+m);
                int e  = dr*dr + dg*dg + db*db;
                if (e < min_pe) min_pe = e;
            }
            err += min_pe;
        }
        if (err < best_err) { best_err = err; table1 = t; }
    }

    /* Header */
    out[0] = (unsigned char)((r0 << 4) | r1);
    out[1] = (unsigned char)((g0 << 4) | g1);
    out[2] = (unsigned char)((b0 << 4) | b1);
    out[3] = (unsigned char)((table0 << 5) | (table1 << 2)); /* diff=0, flip=0 */

    /* Pixel indices */
    for (p = 0; p < 16; p++) {
        int ap  = etc_to_array(p);
        int er  = (p < 8) ? er0 : er1;
        int eg  = (p < 8) ? eg0 : eg1;
        int eb  = (p < 8) ? eb0 : eb1;
        int tab = (p < 8) ? table0 : table1;
        int best_idx = 0;
        int shift;

        best_err = 0x7FFFFFFF;
        for (i = 0; i < 4; i++) {
            int m  = etc_modifier(tab, i);
            int dr = r[ap] - tc_clamp(er+m);
            int dg = g[ap] - tc_clamp(eg+m);
            int db = b[ap] - tc_clamp(eb+m);
            int e  = dr*dr + dg*dg + db*db;
            if (e < best_err) { best_err = e; best_idx = i; }
        }

        /* (msb,lsb): 0->(1,1), 1->(1,0), 2->(0,0), 3->(0,1) */
        shift = 15 - p;
        if (best_idx == 0 || best_idx == 1) msb |= (1u << shift);
        if (best_idx == 0 || best_idx == 3) lsb |= (1u << shift);
    }

    /* Big-endian 16-bit words */
    out[4] = (unsigned char)(msb >> 8);
    out[5] = (unsigned char)(msb & 0xFF);
    out[6] = (unsigned char)(lsb >> 8);
    out[7] = (unsigned char)(lsb & 0xFF);
}

static void compress_etc2_rgb(const unsigned char *src, int w, int h,
                               unsigned char *out) {
    int bx, by, px, py;
    int bw = (w + 3) / 4;
    int bh = (h + 3) / 4;

    for (by = 0; by < bh; by++) {
        for (bx = 0; bx < bw; bx++) {
            int r[16], g[16], b[16];
            for (py = 0; py < 4; py++) {
                int sy = by*4 + py < h ? by*4 + py : h-1;
                for (px = 0; px < 4; px++) {
                    int sx  = bx*4 + px < w ? bx*4 + px : w-1;
                    int off = (sy*w + sx) * 3;
                    int i   = py*4 + px;
                    r[i] = src[off];
                    g[i] = src[off+1];
                    b[i] = src[off+2];
                }
            }
            encode_etc2_rgb_block(out, r, g, b);
            out += 8;
        }
    }
}

/* ==========================================================================
 * EAC alpha block – used as the first 8 bytes of ETC2_RGBA8
 *
 * Layout (8 bytes, big-endian):
 *   byte 0: base_codeword  (0-255)
 *   byte 1: multiplier[7:4]  table_index[3:0]
 *   bytes 2-7: 16 x 3-bit indices (column-major, MSB first)
 *
 * Decoded:  clamp( (base*8 + 4 + EAC_MOD[tbl][idx]*mult) >> 3, 0, 255 )
 * ========================================================================== */

static const int EAC_MOD[16][8] = {
    {-3,-6,-9,-15, 2, 5, 8,14}, {-3,-7,-10,-13, 2, 6, 9,12},
    {-2,-5,-8,-13, 1, 4, 7,12}, {-2,-4, -6,-13, 1, 3, 5,12},
    {-3,-6,-8,-12, 2, 5, 7,11}, {-3,-7, -9,-11, 2, 6, 8,10},
    {-4,-7,-8,-11, 3, 6, 7,10}, {-3,-5, -8,-11, 2, 4, 7,10},
    {-2,-6,-8,-10, 1, 5, 7, 9}, {-2,-5, -8,-10, 1, 4, 7, 9},
    {-2,-4,-8,-10, 1, 3, 7, 9}, {-2,-5, -7,-10, 1, 4, 6, 9},
    {-3,-4,-7,-10, 2, 3, 6, 9}, {-1,-2, -3,-10, 0, 1, 2, 9},
    {-4,-6,-8, -9, 3, 5, 7, 8}, {-3,-5, -7, -9, 2, 4, 6, 8}
};

static void encode_eac_block(unsigned char *out, const int a[16]) {
    /* column-major pixel order for EAC (same mapping as ETC2 RGB) */
    int p, i, t, mult;
    int minA = 255, maxA = 0;
    int best_base = 128, best_mult = 1, best_table = 0;
    int best_err = 0x7FFFFFFF;
    unsigned long long bits;

    for (p = 0; p < 16; p++) {
        int v = a[etc_to_array(p)];
        if (v < minA) minA = v;
        if (v > maxA) maxA = v;
    }

    /* Search a handful of (table, multiplier) pairs.
     * Multiplier is chosen to cover ~half the block's alpha range, then
     * a few nearby values are tried.  4 diverse tables are sampled. */
    {
        int range   = maxA - minA;
        int base_cw = (minA + maxA) / 2;
        /* candidate multipliers centred on the analytic estimate */
        int mults[5];
        int nm = 0;
        int m_est = range / 14 + 1;   /* table rows have max modifier ~15 */
        int m;
        for (m = m_est - 1; m <= m_est + 3 && nm < 5; m++) {
            if (m >= 1 && m <= 15) mults[nm++] = m;
        }
        if (nm == 0) { mults[0] = 1; nm = 1; }

        /* 4 tables with varying spread */
        const int try_tables[4] = {0, 3, 7, 14};
        int ti;

        for (ti = 0; ti < 4; ti++) {
            t = try_tables[ti];
            for (i = 0; i < nm; i++) {
                int err = 0;
                mult = mults[i];
                for (p = 0; p < 16; p++) {
                    int v = a[etc_to_array(p)];
                    int min_pe = 0x7FFFFFFF;
                    int j;
                    for (j = 0; j < 8; j++) {
                        int raw     = base_cw * 8 + 4 + EAC_MOD[t][j] * mult;
                        int decoded = tc_clamp(raw >> 3);
                        int d = v - decoded;
                        int e = d * d;
                        if (e < min_pe) min_pe = e;
                    }
                    err += min_pe;
                }
                if (err < best_err) {
                    best_err = err;
                    best_base  = base_cw;
                    best_mult  = mult;
                    best_table = t;
                }
                if (err == 0) goto eac_found;
            }
        }
    }
eac_found:
    out[0] = (unsigned char)best_base;
    out[1] = (unsigned char)((best_mult << 4) | best_table);

    /* Encode 16 x 3-bit indices (column-major, MSB first -> big-endian) */
    bits = 0;
    for (p = 0; p < 16; p++) {
        int v = a[etc_to_array(p)];
        int best_idx = 0, best_pe = 0x7FFFFFFF;
        int j;
        for (j = 0; j < 8; j++) {
            int raw     = best_base * 8 + 4 + EAC_MOD[best_table][j] * best_mult;
            int decoded = tc_clamp(raw >> 3);
            int d = v - decoded;
            int e = d * d;
            if (e < best_pe) { best_pe = e; best_idx = j; }
        }
        bits = (bits << 3) | (unsigned long long)best_idx;
    }

    /* Write 6 bytes big-endian (pixel 0 index in out[2] bits[7:5]) */
    for (i = 5; i >= 0; i--) {
        out[2 + i] = (unsigned char)(bits & 0xFF);
        bits >>= 8;
    }
}

static void compress_etc2_rgba(const unsigned char *src, int w, int h,
                                unsigned char *out) {
    int bx, by, px, py;
    int bw = (w + 3) / 4;
    int bh = (h + 3) / 4;

    for (by = 0; by < bh; by++) {
        for (bx = 0; bx < bw; bx++) {
            int r[16], g[16], b[16], a[16];
            for (py = 0; py < 4; py++) {
                int sy = by*4 + py < h ? by*4 + py : h-1;
                for (px = 0; px < 4; px++) {
                    int sx  = bx*4 + px < w ? bx*4 + px : w-1;
                    int off = (sy*w + sx) * 4;
                    int i   = py*4 + px;
                    r[i] = src[off];
                    g[i] = src[off+1];
                    b[i] = src[off+2];
                    a[i] = src[off+3];
                }
            }
            encode_eac_block(out,      a);         /* 8 bytes: alpha */
            encode_etc2_rgb_block(out+8, r, g, b); /* 8 bytes: RGB   */
            out += 16;
        }
    }
}

/* ==========================================================================
 * Format detection
 * ========================================================================== */

static int detect_format(int want_rgba) {
    const char *ext = (const char *)glGetString(GL_EXTENSIONS);
    if (!ext) return TC_FORMAT_NONE;

    if (strstr(ext, "GL_EXT_texture_compression_s3tc") ||
        strstr(ext, "GL_NV_texture_compression_s3tc")) {
        return want_rgba ? TC_GL_COMPRESSED_RGBA_S3TC_DXT5_EXT
                         : TC_GL_COMPRESSED_RGB_S3TC_DXT1_EXT;
    }
    if (strstr(ext, "GL_ARB_ES3_compatibility")        ||
        strstr(ext, "GL_OES_compressed_ETC2_RGB8_texture")) {
        return want_rgba ? TC_GL_COMPRESSED_RGBA8_ETC2_EAC
                         : TC_GL_COMPRESSED_RGB8_ETC2;
    }
    return TC_FORMAT_NONE;
}

/* ==========================================================================
 * JNI entry points
 * Class: org.lwjgl.examples.spaceinvaders.TextureCompressor
 * ========================================================================== */

JNIEXPORT jint JNICALL
Java_org_lwjgl_examples_spaceinvaders_TextureCompressor_nDetectFormat(
        JNIEnv *env, jclass cls, jboolean has_alpha) {
    (void)env; (void)cls;
    return detect_format(has_alpha ? 1 : 0);
}

JNIEXPORT jint JNICALL
Java_org_lwjgl_examples_spaceinvaders_TextureCompressor_nGetCompressedSize(
        JNIEnv *env, jclass cls, jint width, jint height, jint format) {
    int bw = (width  + 3) / 4;
    int bh = (height + 3) / 4;
    (void)env; (void)cls;
    if (format == TC_GL_COMPRESSED_RGBA_S3TC_DXT5_EXT ||
        format == TC_GL_COMPRESSED_RGBA8_ETC2_EAC)
        return bw * bh * 16;
    return bw * bh * 8;
}

/*
 * nCompress(ByteBuffer src, ByteBuffer dst, int width, int height, int format)
 *
 * src: direct ByteBuffer with raw RGB8 or RGBA8 pixel data (POT dimensions)
 * dst: direct ByteBuffer pre-allocated by Java (size = nGetCompressedSize)
 */
JNIEXPORT void JNICALL
Java_org_lwjgl_examples_spaceinvaders_TextureCompressor_nCompress(
        JNIEnv *env, jclass cls,
        jobject src_buf, jobject dst_buf,
        jint width, jint height, jint format) {
    const unsigned char *src;
    unsigned char *dst;
    (void)cls;

    src = (const unsigned char *)(*env)->GetDirectBufferAddress(env, src_buf);
    dst = (unsigned char *)(*env)->GetDirectBufferAddress(env, dst_buf);
    if (!src || !dst) return;

    switch (format) {
        case TC_GL_COMPRESSED_RGB_S3TC_DXT1_EXT:
            compress_dxt1(src, width, height, 3, dst);
            break;
        case TC_GL_COMPRESSED_RGBA_S3TC_DXT5_EXT:
            compress_dxt5(src, width, height, dst);
            break;
        case TC_GL_COMPRESSED_RGB8_ETC2:
            compress_etc2_rgb(src, width, height, dst);
            break;
        case TC_GL_COMPRESSED_RGBA8_ETC2_EAC:
            compress_etc2_rgba(src, width, height, dst);
            break;
        default:
            break;
    }
}
