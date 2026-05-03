package com.texcompress;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Java agent that intercepts libGDX's glTexImage2D calls and transparently
 * replaces them with glCompressedTexImage2D when a supported format is available.
 *
 * Usage:
 *   -javaagent:/path/to/texcompress-agent.jar=native=/path/to/libtexcompress.so
 */
public class TextureCompressAgent {

    static {
        String dir = System.getenv("XDG_DATA_HOME");
        if (dir == null) dir = "/mnt/mmc/ports/slaythespire";
        String path = System.getProperty("texcompress.native", dir + "/libtexcompress.so");
        try {
            NativeCompressor.loadLibrary(path);
            System.out.println("[TexCompress] Native library loaded: " + path);
        } catch (Throwable t) {
            System.err.println("[TexCompress] Failed to load native lib: " + t);
        }
    }

    static volatile int     rgbFmt         = NativeCompressor.NONE;
    static volatile int     rgbaFmt        = NativeCompressor.NONE;
    static volatile int     punchFmt       = NativeCompressor.NONE;
    static volatile boolean formatDetected = false;

    private static volatile int logCount = 0;
    private static final int LOG_LIMIT = 20;

    private static String fmtName(int fmt) {
        switch (fmt) {
            case 0x83F0: return "DXT1/BC1-RGB";
            case 0x83F1: return "DXT1/BC1-RGBA";
            case 0x83F2: return "DXT3/BC2";
            case 0x83F3: return "DXT5/BC3";
            case 0x9274: return "ETC2-RGB";
            case 0x9276: return "ETC2-PUNCH";
            case 0x9278: return "ETC2-RGBA";
            case 0x93B0: return "ASTC-4x4";
            default:     return "0x" + Integer.toHexString(fmt);
        }
    }

    /* texcompress.scale=2 halves each dimension before compressing (16x VRAM reduction for RGBA).
     * texcompress.scale=4 quarters dimensions (64x reduction). Default=1 (no downscale). */
    private static final int SCALE = Integer.getInteger("texcompress.scale", 1);

    public static void premain(String args, Instrumentation inst) {
        agentmain(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        String nativePath = parseNativePath(args);
        if (nativePath == null) {
            System.err.println("[TexCompress] ERROR: pass native=/path/to/libtexcompress.so");
            return;
        }
        try {
            NativeCompressor.loadLibrary(nativePath);
            System.out.println("[TexCompress] Native library loaded: " + nativePath);
        } catch (Throwable t) {
            System.err.println("[TexCompress] Failed to load native lib: " + t);
            return;
        }
        inst.addTransformer(new GdxGL20Transformer(), true);
        System.out.println("[TexCompress] Agent installed — will compress textures on upload.");
    }

    private static String parseNativePath(String args) {
        if (args == null) return null;
        for (String part : args.split(",")) {
            part = part.trim();
            if (part.startsWith("native=")) return part.substring(7);
        }
        return null;
    }

    static void ensureFormatDetected() {
        if (formatDetected) return;
        synchronized (TextureCompressAgent.class) {
            if (formatDetected) return;
            rgbFmt  = NativeCompressor.nDetectFormat(false);
            rgbaFmt = NativeCompressor.nDetectFormat(true);
            /* Punch-through is available wherever ETC2 is (same GLES3 mandatory set).
             * For DXT we skip punch-through (no DXT punch-through format exists). */
            if (rgbaFmt == NativeCompressor.GL_COMPRESSED_RGBA8_ETC2_EAC)
                punchFmt = NativeCompressor.GL_COMPRESSED_RGB8_PUNCHTHROUGH_ALPHA1_ETC2;
            formatDetected = true;
            if (rgbFmt != NativeCompressor.NONE) {
                String punch = punchFmt != NativeCompressor.NONE ? " + punch-through" : "";
                System.out.println("[TexCompress] Using format 0x" + Integer.toHexString(rgbFmt)
                        + " (RGB) / 0x" + Integer.toHexString(rgbaFmt) + " (RGBA)" + punch);
            } else {
                System.out.println("[TexCompress] No compressed format available — passthrough.");
            }
        }
    }

    /**
     * Called from patched glTexImage2D.
     * Returns true if compressed upload succeeded — caller should skip original call.
     */
    public static boolean tryCompressAndUpload(int target, int level, int internalFormat,
                                                int width, int height, int border,
                                                int format, int type, Buffer pixels) {
        if (pixels == null || level != 0) return false;
        if (!(pixels instanceof ByteBuffer)) return false;

        ensureFormatDetected();

        final int GL_UNSIGNED_BYTE = 0x1401;
        final int GL_RGB           = 0x1907;
        final int GL_RGBA          = 0x1908;
        if (type != GL_UNSIGNED_BYTE) return false;
        if (format != GL_RGB && format != GL_RGBA) return false;

        boolean hasAlpha = (format == GL_RGBA);
        int compFmt = hasAlpha ? rgbaFmt : rgbFmt;
        if (compFmt == NativeCompressor.NONE) return false;

        /* Skip font atlases: libGDX FreeType generates RGBA textures where every
         * non-transparent pixel is greyscale (R=G=B — pure white glyph, alpha varies).
         * Real game textures have colour. Sample every 8th pixel for speed. */
        if (hasAlpha && width == height) {
            ByteBuffer probe = (ByteBuffer) pixels;
            int base = probe.position();
            int total = 0, grey = 0;
            for (int i = 0; i < width * height; i += 8) {
                int a = probe.get(base + i * 4 + 3) & 0xFF;
                if (a < 16) continue; /* transparent pixels carry no colour info */
                total++;
                int r = probe.get(base + i * 4    ) & 0xFF;
                int g = probe.get(base + i * 4 + 1) & 0xFF;
                int b = probe.get(base + i * 4 + 2) & 0xFF;
                if (Math.abs(r - g) <= 8 && Math.abs(g - b) <= 8) grey++;
            }
            /* >95 % greyscale non-transparent pixels → font atlas → skip */
            if (total == 0 || grey * 100 / total >= 95) return false;
        }

        ByteBuffer src = (ByteBuffer) pixels;
        int ch = hasAlpha ? 4 : 3;

        /* Optional box-filter downsample (-Dtexcompress.scale=2 or 4) */
        int outW = width, outH = height;
        if (SCALE > 1 && width >= SCALE * 4 && height >= SCALE * 4) {
            outW = width  / SCALE;
            outH = height / SCALE;
            ByteBuffer down = ByteBuffer.allocateDirect(outW * outH * ch);
            int base = src.position();
            int s2 = SCALE * SCALE;
            for (int row = 0; row < outH; row++) {
                for (int col = 0; col < outW; col++) {
                    int[] sum = new int[ch];
                    for (int dy = 0; dy < SCALE; dy++) {
                        int sy = Math.min(row * SCALE + dy, height - 1);
                        for (int dx = 0; dx < SCALE; dx++) {
                            int sx = Math.min(col * SCALE + dx, width - 1);
                            int si = base + (sy * width + sx) * ch;
                            for (int c = 0; c < ch; c++) sum[c] += src.get(si + c) & 0xFF;
                        }
                    }
                    for (int c = 0; c < ch; c++) down.put((byte)(sum[c] / s2));
                }
            }
            down.rewind();
            src = down;
        }

        /* Pad dimensions to 4-pixel block boundary if needed */
        int w = (outW + 3) & ~3;
        int h = (outH + 3) & ~3;

        /* Upgrade RGBA→punch-through (4 bpp) when alpha is binary (0 or 255 only).
         * Sprites with hard-edged transparency qualify; gradients do not. */
        if (hasAlpha && punchFmt != NativeCompressor.NONE) {
            int base = src.position();
            boolean binary = true;
            boolean hasTransparent = false;
            for (int i = 0; i < outW * outH; i++) {
                int a = src.get(base + i * 4 + 3) & 0xFF;
                if (a != 0 && a != 255) { binary = false; break; }
                if (a == 0) hasTransparent = true;
            }
            if (binary && hasTransparent) {
                compFmt = punchFmt;
            }
        }

        /* If the RGBA texture is fully opaque, strip the alpha channel and
         * compress as RGB (4 bpp) instead of RGBA (8 bpp) — zero quality loss. */
        boolean strippedAlpha = false;
        if (hasAlpha && compFmt == rgbaFmt && rgbFmt != NativeCompressor.NONE) {
            boolean allOpaque = true;
            int base = src.position();
            int pixelCount = outW * outH;
            for (int i = 0; i < pixelCount; i++) {
                if ((src.get(base + i * 4 + 3) & 0xFF) != 255) { allOpaque = false; break; }
            }
            if (allOpaque) {
                ByteBuffer rgb = ByteBuffer.allocateDirect(w * h * 3);
                for (int row = 0; row < h; row++) {
                    int sr = Math.min(row, outH - 1);
                    for (int col = 0; col < w; col++) {
                        int sc = Math.min(col, outW - 1);
                        int si = base + (sr * outW + sc) * 4;
                        rgb.put(src.get(si)).put(src.get(si + 1)).put(src.get(si + 2));
                    }
                }
                rgb.rewind();
                src = rgb;
                compFmt = rgbFmt;
                ch = 3;
                hasAlpha = false;
                strippedAlpha = true;
                /* src is already padded — skip the normal pad step below */
                w = (outW + 3) & ~3;
                h = (outH + 3) & ~3;
            }
        }

        if (!strippedAlpha && (w != outW || h != outH)) {
            ByteBuffer padded = ByteBuffer.allocateDirect(w * h * ch);
            int base = src.position();
            for (int row = 0; row < h; row++) {
                int sr = Math.min(row, outH - 1);
                for (int col = 0; col < w; col++) {
                    int sc = Math.min(col, outW - 1);
                    int si = base + (sr * outW + sc) * ch;
                    for (int c = 0; c < ch; c++) padded.put(src.get(si + c));
                }
            }
            padded.rewind();
            src = padded;
        }

        int compSize = NativeCompressor.nGetCompressedSize(w, h, compFmt);
        ByteBuffer dst = ByteBuffer.allocateDirect(compSize);
        NativeCompressor.nCompress(src, dst, w, h, compFmt);

        boolean verbose = logCount < LOG_LIMIT;
        if (verbose) {
            int n = ++logCount;
            String scaleNote = (SCALE > 1) ? " (downscaled from " + width + "x" + height + ")" : "";
            String alphaNote = strippedAlpha ? " (RGBA→RGB: all opaque)"
                             : compFmt == punchFmt ? " (RGBA→PUNCH: binary alpha)" : "";
            System.out.println("[TexCompress] #" + n + " uploading " + outW + "x" + outH
                    + scaleNote + alphaNote + " as " + fmtName(compFmt) + " (" + compSize + " bytes)");
            if (n == LOG_LIMIT)
                System.out.println("[TexCompress] (further uploads will be silent)");
        }

        callCompressedTexImage2D(target, level, compFmt, outW, outH, border, compSize, dst, verbose);
        return true;
    }

    private static void callCompressedTexImage2D(int target, int level, int internalFormat,
                                                  int width, int height, int border,
                                                  int imageSize, ByteBuffer data, boolean verbose) {
        try {
            /* Agent classes are loaded by the bootstrap/system classloader and cannot
             * see application jars. Use the thread context classloader (set by the game)
             * which has the full classpath including LWJGL. */
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = ClassLoader.getSystemClassLoader();

            /* Try LWJGL 2 GL13 first, then GL11 (some stripped builds move it there) */
            Class<?> glClass = null;
            String foundIn = null;
            for (String name : new String[]{"org.lwjgl.opengl.GL13", "org.lwjgl.opengl.GL11"}) {
                try { glClass = Class.forName(name, true, cl); foundIn = name; break; }
                catch (ClassNotFoundException ignored) {}
            }
            if (glClass == null) {
                System.err.println("[TexCompress] glCompressedTexImage2D: GL13/GL11 not found via " + cl);
                return;
            }

            try {
                glClass.getMethod("glCompressedTexImage2D",
                        int.class, int.class, int.class, int.class, int.class,
                        int.class, int.class, ByteBuffer.class)
                    .invoke(null, target, level, internalFormat,
                            width, height, border, imageSize, data);
                if (verbose) System.out.println("[TexCompress]   -> OK via " + foundIn + " (8-arg)");
            } catch (NoSuchMethodException e) {
                /* LWJGL 2 generated variant — no explicit imageSize */
                glClass.getMethod("glCompressedTexImage2D",
                        int.class, int.class, int.class, int.class, int.class,
                        int.class, ByteBuffer.class)
                    .invoke(null, target, level, internalFormat,
                            width, height, border, data);
                if (verbose) System.out.println("[TexCompress]   -> OK via " + foundIn + " (7-arg)");
            }
        } catch (Exception e) {
            System.err.println("[TexCompress] glCompressedTexImage2D call failed: " + e);
        }
    }

    /* -----------------------------------------------------------------------
     * ASM transformer
     * ----------------------------------------------------------------------- */

    static class GdxGL20Transformer implements ClassFileTransformer {

        private static final String[] TARGET_CLASSES = {
            "com/badlogic/gdx/backends/lwjgl/LwjglGL20",
            "com/badlogic/gdx/backends/lwjgl3/Lwjgl3GL20",
        };

        @Override
        public byte[] transform(ClassLoader loader, String className,
                                Class<?> classBeingRedefined,
                                ProtectionDomain domain, byte[] classBytes) {
            for (String target : TARGET_CLASSES) {
                if (target.equals(className)) {
                    return patchClass(classBytes, className, loader);
                }
            }
            return null;
        }

        static byte[] patchClass(byte[] classBytes, String className, ClassLoader loader) {
            try {
                ClassReader cr = new ClassReader(classBytes);

                /* COMPUTE_FRAMES regenerates all stack map tables from scratch.
                 * Override getCommonSuperClass to avoid ClassLoader failures
                 * during agent premain before all classes are loaded. */
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
                    @Override
                    protected String getCommonSuperClass(String type1, String type2) {
                        try {
                            return super.getCommonSuperClass(type1, type2);
                        } catch (Throwable t) {
                            return "java/lang/Object";
                        }
                    }
                };

                ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc,
                                                     String sig, String[] ex) {
                        MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                        if ("glTexImage2D".equals(name)) {
                            System.out.println("[TexCompress] Patching glTexImage2D in " + className);
                            return new TexImage2DInterceptor(mv);
                        }
                        return mv;
                    }
                };

                /* EXPAND_FRAMES so ASM sees full frame info before rewriting */
                cr.accept(cv, ClassReader.EXPAND_FRAMES);
                return cw.toByteArray();
            } catch (Throwable t) {
                System.err.println("[TexCompress] Failed to patch " + className + ": " + t);
                return null;
            }
        }
    }

    /**
     * Prepends glTexImage2D with:
     *
     *   if (tryCompressAndUpload(target,level,...,pixels)) return;
     *   // original method body
     */
    static class TexImage2DInterceptor extends MethodVisitor {

        TexImage2DInterceptor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();

            /* glTexImage2D(int,int,int,int,int,int,int,int,Buffer)V
             * slot 0=this  1=target  2=level  3=internalFormat
             *      4=width 5=height  6=border 7=format  8=type  9=pixels */
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitVarInsn(Opcodes.ILOAD, 5);
            mv.visitVarInsn(Opcodes.ILOAD, 6);
            mv.visitVarInsn(Opcodes.ILOAD, 7);
            mv.visitVarInsn(Opcodes.ILOAD, 8);
            mv.visitVarInsn(Opcodes.ALOAD, 9);

            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/texcompress/TextureCompressAgent",
                    "tryCompressAndUpload",
                    "(IIIIIIIILjava/nio/Buffer;)Z",
                    false);

            /* IFEQ fallthrough: if result==0 (false) jump past RETURN */
            Label fallthrough = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, fallthrough);
            mv.visitInsn(Opcodes.RETURN);   /* compression succeeded — skip original */
            mv.visitLabel(fallthrough);     /* original body starts here */
        }
    }
}
