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

    static volatile int     rgbFmt         = NativeCompressor.NONE;
    static volatile int     rgbaFmt        = NativeCompressor.NONE;
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
     * Called from patched glTexImage2D.
     * LwjglGL20 declares the last param as java.nio.Buffer, so we accept Buffer
     * and cast to ByteBuffer (libGDX always passes a ByteBuffer in practice).
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

        ByteBuffer src = (ByteBuffer) pixels;

        /* Skip RGBA compression for font atlases.
         * Font atlases (FreeType-generated at runtime) are mostly empty:
         * ~70-90% of pixels have alpha==0. Game art textures (card atlases,
         * backgrounds, portraits) have very few fully-transparent pixels.
         * Sample up to 2048 evenly-spaced pixels; if >40% are fully
         * transparent skip DXT5 — the texture is almost certainly a font
         * atlas and DXT5 would produce visible glyph artifacts. */
        if (hasAlpha) {
            int total   = width * height;
            int step    = Math.max(1, total / 2048);
            int base    = src.position();
            int sampled = 0, transparent = 0;
            for (int i = 0; i < total; i += step) {
                int alpha = src.get(base + i * 4 + 3) & 0xFF;
                if (alpha == 0) transparent++;
                sampled++;
            }
            if (transparent > sampled * 70 / 100) return false;
        }

        /* Pad dimensions to 4-pixel block boundary if needed */
        int w = (width  + 3) & ~3;
        int h = (height + 3) & ~3;
        if (w != width || h != height) {
            int ch = hasAlpha ? 4 : 3;
            ByteBuffer padded = ByteBuffer.allocateDirect(w * h * ch);
            int base = src.position();
            for (int row = 0; row < h; row++) {
                int sr = Math.min(row, height - 1);
                for (int col = 0; col < w; col++) {
                    int sc = Math.min(col, width - 1);
                    int si = base + (sr * width + sc) * ch;
                    for (int c = 0; c < ch; c++) padded.put(src.get(si + c));
                }
            }
            padded.rewind();
            src = padded;
        }

        int compSize = NativeCompressor.nGetCompressedSize(w, h, compFmt);
        ByteBuffer dst = ByteBuffer.allocateDirect(compSize);
        NativeCompressor.nCompress(src, dst, w, h, compFmt);
        callCompressedTexImage2D(target, level, compFmt, width, height, border, compSize, dst);
        return true;
    }

    private static void callCompressedTexImage2D(int target, int level, int internalFormat,
                                                  int width, int height, int border,
                                                  int imageSize, ByteBuffer data) {
        try {
            /* Try LWJGL 2 signature with explicit imageSize first */
            Class<?> gl13 = Class.forName("org.lwjgl.opengl.GL13");
            try {
                gl13.getMethod("glCompressedTexImage2D",
                        int.class, int.class, int.class, int.class, int.class,
                        int.class, int.class, ByteBuffer.class)
                    .invoke(null, target, level, internalFormat,
                            width, height, border, imageSize, data);
            } catch (NoSuchMethodException e) {
                /* LWJGL 2 generated variant — no explicit imageSize */
                gl13.getMethod("glCompressedTexImage2D",
                        int.class, int.class, int.class, int.class, int.class,
                        int.class, ByteBuffer.class)
                    .invoke(null, target, level, internalFormat,
                            width, height, border, data);
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

        private byte[] patchClass(byte[] classBytes, String className, ClassLoader loader) {
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
     *
     * Control flow (correct stackmap-safe pattern):
     *   LOAD params
     *   INVOKESTATIC tryCompressAndUpload  -> boolean on stack
     *   IFEQ  fallthrough   // if false  -> jump to original body
     *   RETURN              // if true   -> compressed, done
     *   fallthrough:
     *   ... original bytecode ...
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
