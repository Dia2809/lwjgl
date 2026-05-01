package com.texcompress;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.ByteBuffer;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Java agent that intercepts libGDX's glTexImage2D calls and transparently
 * replaces them with glCompressedTexImage2D when a supported format is available.
 *
 * Usage (add to game launch options in Steam):
 *   -javaagent:/path/to/texcompress-agent.jar=native=/path/to/libtexcompress.so
 *
 * The agent patches the two libGDX GL20 backend classes that call glTexImage2D:
 *   com/badlogic/gdx/backends/lwjgl/LwjglGL20
 *   com/badlogic/gdx/backends/lwjgl3/Lwjgl3GL20    (if LWJGL3 backend present)
 */
public class TextureCompressAgent {

    /* Detected format cache — filled on first real texture upload */
    static volatile int rgbFmt  = NativeCompressor.NONE;
    static volatile int rgbaFmt = NativeCompressor.NONE;
    static volatile boolean formatDetected = false;

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

    /** Called lazily on the first glTexImage2D to detect GPU support. */
    static void ensureFormatDetected() {
        if (formatDetected) return;
        synchronized (TextureCompressAgent.class) {
            if (formatDetected) return;
            rgbFmt  = NativeCompressor.nDetectFormat(false);
            rgbaFmt = NativeCompressor.nDetectFormat(true);
            formatDetected = true;
            if (rgbFmt != NativeCompressor.NONE) {
                System.out.println("[TexCompress] Using format 0x" + Integer.toHexString(rgbFmt)
                        + " (RGB) / 0x" + Integer.toHexString(rgbaFmt) + " (RGBA)");
            } else {
                System.out.println("[TexCompress] No compressed format available — passthrough.");
            }
        }
    }

    /**
     * Intercept point called from the instrumented glTexImage2D.
     * Returns true if the texture was uploaded as compressed (caller must skip original call).
     */
    public static boolean tryCompressAndUpload(int target, int level, int internalFormat,
                                                int width, int height, int border,
                                                int format, int type, ByteBuffer pixels) {
        if (pixels == null || level != 0) return false;

        ensureFormatDetected();

        /* Only compress base level, RGBA/RGB, UNSIGNED_BYTE sources */
        final int GL_UNSIGNED_BYTE = 0x1401;
        final int GL_RGB  = 0x1907;
        final int GL_RGBA = 0x1908;
        if (type != GL_UNSIGNED_BYTE) return false;
        boolean hasAlpha = (format == GL_RGBA);
        if (format != GL_RGB && format != GL_RGBA) return false;

        int compFmt = hasAlpha ? rgbaFmt : rgbFmt;
        if (compFmt == NativeCompressor.NONE) return false;

        /* Width and height must be multiples of 4 for block compression */
        int w = (width  + 3) & ~3;
        int h = (height + 3) & ~3;

        /* If the buffer doesn't cover a padded size, pad it */
        ByteBuffer src = pixels;
        int expectedBytes = width * height * (hasAlpha ? 4 : 3);
        if (pixels.remaining() < expectedBytes) return false;

        if (w != width || h != height) {
            /* Rare: pad to block boundary in a new buffer */
            int channels = hasAlpha ? 4 : 3;
            ByteBuffer padded = ByteBuffer.allocateDirect(w * h * channels);
            for (int row = 0; row < h; row++) {
                int srcRow = Math.min(row, height - 1);
                for (int col = 0; col < w; col++) {
                    int srcCol = Math.min(col, width - 1);
                    int si = (srcRow * width + srcCol) * channels;
                    for (int c = 0; c < channels; c++)
                        padded.put(pixels.get(pixels.position() + si + c));
                }
            }
            padded.rewind();
            src = padded;
        }

        int compSize = NativeCompressor.nGetCompressedSize(w, h, compFmt);
        ByteBuffer dst = ByteBuffer.allocateDirect(compSize);
        NativeCompressor.nCompress(src, dst, w, h, compFmt);

        /* Call glCompressedTexImage2D directly via LWJGL */
        callCompressedTexImage2D(target, level, compFmt, width, height, border, compSize, dst);
        return true;
    }

    /** Reflectively calls org.lwjgl.opengl.GL13.glCompressedTexImage2D */
    private static void callCompressedTexImage2D(int target, int level, int internalFormat,
                                                  int width, int height, int border,
                                                  int imageSize, ByteBuffer data) {
        try {
            Class<?> gl13 = Class.forName("org.lwjgl.opengl.GL13");
            gl13.getMethod("glCompressedTexImage2D",
                    int.class, int.class, int.class, int.class, int.class,
                    int.class, int.class, ByteBuffer.class)
                .invoke(null, target, level, internalFormat, width, height,
                        border, imageSize, data);
        } catch (Exception e) {
            /* Fallback: try without explicit imageSize (LWJGL 2 generated signature) */
            try {
                Class<?> gl13 = Class.forName("org.lwjgl.opengl.GL13");
                gl13.getMethod("glCompressedTexImage2D",
                        int.class, int.class, int.class, int.class, int.class,
                        int.class, ByteBuffer.class)
                    .invoke(null, target, level, internalFormat,
                            width, height, border, data);
            } catch (Exception e2) {
                System.err.println("[TexCompress] glCompressedTexImage2D call failed: " + e2);
            }
        }
    }

    /* -----------------------------------------------------------------------
     * ASM transformer: patches glTexImage2D in libGDX's GL backend classes
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
                    return patchClass(classBytes, className);
                }
            }
            return null;
        }

        private byte[] patchClass(byte[] classBytes, String className) {
            try {
                ClassReader  cr = new ClassReader(classBytes);
                ClassWriter  cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
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
                cr.accept(cv, 0);
                return cw.toByteArray();
            } catch (Throwable t) {
                System.err.println("[TexCompress] Failed to patch " + className + ": " + t);
                return null;
            }
        }
    }

    /**
     * Replaces the body of glTexImage2D with:
     *
     *   if (!TextureCompressAgent.tryCompressAndUpload(target,level,internalFormat,
     *           width,height,border,format,type,pixels)) {
     *       // original glTexImage2D native call
     *   }
     */
    static class TexImage2DInterceptor extends MethodVisitor {

        TexImage2DInterceptor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            /* Push all parameters onto stack for our intercept method.
             * glTexImage2D(int target, int level, int internalFormat,
             *              int width, int height, int border,
             *              int format, int type, ByteBuffer pixels)
             * Local slots (instance method): 0=this, 1..9 = the above params */
            mv.visitVarInsn(Opcodes.ILOAD, 1); // target
            mv.visitVarInsn(Opcodes.ILOAD, 2); // level
            mv.visitVarInsn(Opcodes.ILOAD, 3); // internalFormat
            mv.visitVarInsn(Opcodes.ILOAD, 4); // width
            mv.visitVarInsn(Opcodes.ILOAD, 5); // height
            mv.visitVarInsn(Opcodes.ILOAD, 6); // border
            mv.visitVarInsn(Opcodes.ILOAD, 7); // format
            mv.visitVarInsn(Opcodes.ILOAD, 8); // type
            mv.visitVarInsn(Opcodes.ALOAD, 9); // pixels (ByteBuffer)

            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/texcompress/TextureCompressAgent",
                    "tryCompressAndUpload",
                    "(IIIIIIIILjava/nio/ByteBuffer;)Z",
                    false);

            /* if tryCompressAndUpload returned true, skip the original call */
            org.objectweb.asm.Label skipLabel = new org.objectweb.asm.Label();
            mv.visitJumpInsn(Opcodes.IFNE, skipLabel);
            /* fall through to original native call */

            /* We need to store the skip target — visitMaxs/visitEnd will
             * be emitted by the super visitor after our injected code.
             * Store the label so visitInsn(RETURN) can place it. */
            this.skipLabel = skipLabel;
        }

        private org.objectweb.asm.Label skipLabel = null;

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.RETURN && skipLabel != null) {
                super.visitInsn(opcode);
                mv.visitLabel(skipLabel);
                /* Method returns void, so just fall off here */
                skipLabel = null;
                return;
            }
            super.visitInsn(opcode);
        }
    }
}
