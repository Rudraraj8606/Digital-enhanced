/*
 * Copyright (c) 2024 Digital Contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.collab;

import java.io.*;
import java.nio.file.Files;
import java.util.Base64;
import java.util.zip.*;

/**
 * Zips/unzips all .dig files in a directory for sharing via the relay server.
 * This ensures collaborators have the same custom components without needing
 * to manually share files.
 */
public final class BundleHelper {

    private BundleHelper() {}

    /**
     * Zip all .dig files in the given directory and return as a Base64 string.
     * Returns null (and logs) if the directory is null or empty.
     */
    public static String createBundle(File directory) throws IOException {
        if (directory == null || !directory.isDirectory()) return null;
        File[] digFiles = directory.listFiles(f -> f.isFile() && f.getName().endsWith(".dig"));
        if (digFiles == null || digFiles.length == 0) return null;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (File f : digFiles) {
                zos.putNextEntry(new ZipEntry(f.getName()));
                Files.copy(f.toPath(), zos);
                zos.closeEntry();
            }
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    /**
     * Decode a Base64 bundle and extract its .dig files into a temp directory.
     *
     * @param base64Zip  Base64-encoded ZIP string from {@link #createBundle}
     * @param roomCode   used to name the temp directory uniquely
     * @return the temp directory containing the extracted .dig files
     */
    public static File extractBundle(String base64Zip, String roomCode) throws IOException {
        byte[] zipBytes = Base64.getDecoder().decode(base64Zip);
        // Use a stable temp path so repeated joins don't create new dirs
        File tempDir = new File(System.getProperty("java.io.tmpdir"),
                "digital-collab-" + roomCode.replaceAll("[^A-Z0-9]", ""));
        tempDir.mkdirs();

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                // Security: reject path-traversal entries
                if (!name.endsWith(".dig") || name.contains("/") || name.contains("\\")) {
                    zis.closeEntry();
                    continue;
                }
                File outFile = new File(tempDir, name);
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                }
                zis.closeEntry();
            }
        }
        return tempDir;
    }

    /** Delete a temp directory created by {@link #extractBundle}. */
    public static void deleteBundle(File tempDir) {
        if (tempDir == null || !tempDir.exists()) return;
        File[] files = tempDir.listFiles();
        if (files != null) for (File f : files) f.delete();
        tempDir.delete();
    }
}
