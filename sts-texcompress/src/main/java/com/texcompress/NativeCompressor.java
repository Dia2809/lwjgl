package com.texcompress;

import java.nio.ByteBuffer;

/**
 * JNI bridge to the C compressor (libtexcompress.so).
 * Loaded explicitly by the agent at startup.
 */
public final class NativeCompressor {

    public static final int NONE                                          = -1;
    public static final int GL_COMPRESSED_RGB_S3TC_DXT1_EXT              = 0x83F0;
    public static final int GL_COMPRESSED_RGBA_S3TC_DXT5_EXT             = 0x83F3;
    public static final int GL_COMPRESSED_RGB8_ETC2                      = 0x9274;
    public static final int GL_COMPRESSED_RGB8_PUNCHTHROUGH_ALPHA1_ETC2  = 0x9276;
    public static final int GL_COMPRESSED_RGBA8_ETC2_EAC                 = 0x9278;

    private static volatile boolean loaded = false;

    public static void loadLibrary(String path) {
        if (loaded) return;
        /* System.load() requires an absolute path — resolve relative paths */
        java.io.File f = new java.io.File(path);
        System.load(f.getAbsolutePath());
        loaded = true;
    }

    public static boolean isLoaded() { return loaded; }

    /** Returns the best available compressed format for the given channel count. */
    public static native int  nDetectFormat(boolean hasAlpha);
    public static native int  nGetCompressedSize(int width, int height, int format);
    /** Fills dst (pre-allocated direct ByteBuffer) with compressed pixel data from src. */
    public static native void nCompress(ByteBuffer src, ByteBuffer dst,
                                        int width, int height, int format);
    /** xxHash64 of src[offset .. offset+length-1] with the given seed. */
    public static native long nHash(ByteBuffer src, int offset, int length, long seed);
}
