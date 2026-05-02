package com.texcompress;

import java.io.*;
import java.nio.file.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * Standalone JAR patcher for the texture compression mod.
 *
 * Patches a game JAR by:
 *   1. Applying the same ASM bytecode transformation that GdxGL20Transformer does
 *      to LwjglGL20 and Lwjgl3GL20 (injects tryCompressAndUpload call into glTexImage2D).
 *   2. Adding TextureCompressAgent.class and NativeCompressor.class into the JAR so
 *      that tryCompressAndUpload and the native loader are available at runtime.
 *
 * Usage:
 *   java -jar patcher.jar [path/to/game.jar]
 *
 * Default JAR path: $XDG_DATA_HOME/desktoppatched.jar
 */
public class Patcher {

    private static final String[] PATCH_TARGETS = {
        "com/badlogic/gdx/backends/lwjgl/LwjglGL20.class",
        "com/badlogic/gdx/backends/lwjgl3/Lwjgl3GL20.class",
    };

    private static final String[] OUR_CLASSES = {
        "com/texcompress/TextureCompressAgent.class",
        "com/texcompress/NativeCompressor.class",
    };

    public static void main(String[] args) throws IOException {
        String jarPath;
        if (args.length > 0) {
            jarPath = args[0];
        } else {
            String xdg = System.getenv("XDG_DATA_HOME");
            if (xdg == null) xdg = "/mnt/mmc/ports/slaythespire";
            jarPath = xdg + "/desktoppatched.jar";
        }

        File inputFile = new File(jarPath);
        if (!inputFile.exists()) {
            System.err.println("[Patcher] ERROR: Input JAR not found: " + jarPath);
            System.exit(1);
        }

        File tmpFile = new File(jarPath + ".tmp");
        System.out.println("[Patcher] Patching: " + jarPath);

        boolean[] patched = new boolean[PATCH_TARGETS.length];

        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(inputFile)));
             ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmpFile)))) {

            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                boolean handled = false;

                // Check if this entry is one of our own classes — skip it (we'll re-add fresh)
                for (String ourClass : OUR_CLASSES) {
                    if (name.equals(ourClass)) {
                        System.out.println("[Patcher] Skipping existing entry (will re-add): " + name);
                        handled = true;
                        break;
                    }
                }

                if (!handled) {
                    // Check if this is a patch target
                    for (int i = 0; i < PATCH_TARGETS.length; i++) {
                        if (name.equals(PATCH_TARGETS[i])) {
                            byte[] original = readAllBytes(zin);
                            String className = name.replace(".class", "");
                            byte[] patched2 = TextureCompressAgent.GdxGL20Transformer.patchClass(
                                    original, className, Patcher.class.getClassLoader());
                            if (patched2 != null) {
                                System.out.println("[Patcher] Patched: " + name);
                                writeEntry(zout, name, patched2);
                                patched[i] = true;
                            } else {
                                System.err.println("[Patcher] WARNING: Patch failed for " + name + ", copying original.");
                                writeEntry(zout, name, original);
                            }
                            handled = true;
                            break;
                        }
                    }
                }

                if (!handled) {
                    // Copy entry unchanged
                    ZipEntry outEntry = new ZipEntry(name);
                    zout.putNextEntry(outEntry);
                    copyStream(zin, zout);
                    zout.closeEntry();
                }

                zin.closeEntry();
            }

            // Warn about missing patch targets
            for (int i = 0; i < PATCH_TARGETS.length; i++) {
                if (!patched[i]) {
                    System.out.println("[Patcher] WARNING: Target class not found in JAR: " + PATCH_TARGETS[i]);
                }
            }

            // Add our classes into the JAR
            ClassLoader cl = TextureCompressAgent.class.getClassLoader();
            for (String ourClass : OUR_CLASSES) {
                InputStream is = TextureCompressAgent.class.getResourceAsStream("/" + ourClass);
                if (is == null) {
                    // Also try via the class loader directly
                    is = cl.getResourceAsStream(ourClass);
                }
                if (is == null) {
                    System.err.println("[Patcher] WARNING: Could not find resource to embed: " + ourClass);
                    continue;
                }
                byte[] classBytes = readAllBytes(is);
                is.close();
                writeEntry(zout, ourClass, classBytes);
                System.out.println("[Patcher] Added: " + ourClass);
            }
        }

        // Rename tmp over original
        Path src = tmpFile.toPath();
        Path dst = inputFile.toPath();
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        System.out.println("[Patcher] Done. Output: " + jarPath);
    }

    private static void writeEntry(ZipOutputStream zout, String name, byte[] data) throws IOException {
        ZipEntry outEntry = new ZipEntry(name);
        zout.putNextEntry(outEntry);
        zout.write(data);
        zout.closeEntry();
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        copyStream(in, buf);
        return buf.toByteArray();
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
    }
}
