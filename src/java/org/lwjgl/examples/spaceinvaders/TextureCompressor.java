/*
 * Copyright (c) 2002-2008 LWJGL Project
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'LWJGL' nor the names of
 *   its contributors may be used to endorse or promote products derived
 *   from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.lwjgl.examples.spaceinvaders;

import java.nio.ByteBuffer;

import org.lwjgl.BufferUtils;

/**
 * On-the-fly GPU texture compression.
 *
 * Detects available compressed formats at runtime (DXT1/DXT5 via
 * GL_EXT_texture_compression_s3tc, or ETC2 via GL_ARB_ES3_compatibility)
 * and exposes them through the native library already loaded by LWJGL.
 *
 * All compression work is done in native C (texture_compress.c).
 * VRAM savings: ~6x for RGB textures, ~4x for RGBA textures.
 */
public final class TextureCompressor {

    public static final int NONE                              = -1;
    public static final int GL_COMPRESSED_RGB_S3TC_DXT1_EXT  = 0x83F0;
    public static final int GL_COMPRESSED_RGBA_S3TC_DXT5_EXT = 0x83F3;
    public static final int GL_COMPRESSED_RGB8_ETC2           = 0x9274;
    public static final int GL_COMPRESSED_RGBA8_ETC2_EAC      = 0x9278;

    private static int rgbFormat  = NONE;
    private static int rgbaFormat = NONE;
    private static boolean initialised = false;

    private TextureCompressor() {}

    /** Call once after the GL context is created. */
    public static void init() {
        if (initialised) return;
        initialised = true;
        try {
            rgbFormat  = nDetectFormat(false);
            rgbaFormat = nDetectFormat(true);
        } catch (UnsatisfiedLinkError e) {
            /* Native library not yet available; compression disabled. */
        }
    }

    public static int getRGBFormat()    { return rgbFormat;  }
    public static int getRGBAFormat()   { return rgbaFormat; }
    public static boolean isSupported() { return rgbFormat != NONE; }

    /**
     * Compress raw pixel data to the best available compressed format.
     *
     * @param src      direct ByteBuffer containing RGB8 (!hasAlpha) or RGBA8 pixels
     * @param width    texture width  (must be a multiple of 4)
     * @param height   texture height (must be a multiple of 4)
     * @param hasAlpha true if src contains 4 bytes/pixel (RGBA), false for 3 (RGB)
     * @return direct ByteBuffer with compressed data, or null if unsupported
     */
    public static ByteBuffer compress(ByteBuffer src, int width, int height,
                                      boolean hasAlpha) {
        int fmt = hasAlpha ? rgbaFormat : rgbFormat;
        if (fmt == NONE) return null;

        int size = nGetCompressedSize(width, height, fmt);
        ByteBuffer dst = BufferUtils.createByteBuffer(size);
        nCompress(src, dst, width, height, fmt);
        return dst;
    }

    /* ---- native methods implemented in texture_compress.c ---------------- */

    private static native int  nDetectFormat(boolean hasAlpha);
    private static native int  nGetCompressedSize(int width, int height, int format);
    private static native void nCompress(ByteBuffer src, ByteBuffer dst,
                                         int width, int height, int format);
}
