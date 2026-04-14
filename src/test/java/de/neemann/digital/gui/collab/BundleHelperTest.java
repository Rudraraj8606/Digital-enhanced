/*
 * Copyright (c) 2024 Digital Contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.collab;

import junit.framework.TestCase;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;

/**
 * Tests for BundleHelper — zip/unzip of .dig files for collab sharing.
 */
public class BundleHelperTest extends TestCase {

    /** Creating a bundle from a directory with .dig files returns a non-null Base64 string. */
    public void testCreateBundleProducesBase64() throws Exception {
        File dir = Files.createTempDirectory("bundle-test-create").toFile();
        try {
            // Write two dummy .dig files
            writeFile(new File(dir, "gate1.dig"), "<circuit/>");
            writeFile(new File(dir, "gate2.dig"), "<circuit/>");

            String bundle = BundleHelper.createBundle(dir);
            assertNotNull("Bundle should not be null", bundle);
            assertTrue("Bundle should be non-empty", bundle.length() > 0);
        } finally {
            deleteDir(dir);
        }
    }

    /** Creating a bundle from an empty directory returns null. */
    public void testCreateBundleEmptyDirReturnsNull() throws Exception {
        File dir = Files.createTempDirectory("bundle-test-empty").toFile();
        try {
            String bundle = BundleHelper.createBundle(dir);
            assertNull("Bundle from empty dir should be null", bundle);
        } finally {
            deleteDir(dir);
        }
    }

    /** Extracted bundle contains the same files that were bundled. */
    public void testRoundTrip() throws Exception {
        File srcDir = Files.createTempDirectory("bundle-test-src").toFile();
        File extractDir = null;
        try {
            writeFile(new File(srcDir, "adder.dig"), "<circuit name=\"adder\"/>");
            writeFile(new File(srcDir, "mux.dig"),   "<circuit name=\"mux\"/>");

            String bundle = BundleHelper.createBundle(srcDir);
            assertNotNull(bundle);

            extractDir = BundleHelper.extractBundle(bundle, "TESTCD");
            assertTrue("adder.dig should be extracted", new File(extractDir, "adder.dig").exists());
            assertTrue("mux.dig should be extracted",   new File(extractDir, "mux.dig").exists());
        } finally {
            deleteDir(srcDir);
            if (extractDir != null) BundleHelper.deleteBundle(extractDir);
        }
    }

    /** Path-traversal entries (with / or \) must be silently rejected. */
    public void testPathTraversalRejected() throws Exception {
        // Build a zip that contains a path-traversal entry manually
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("../../evil.dig"));
            zos.write("<circuit/>".getBytes());
            zos.closeEntry();
        }
        String base64 = java.util.Base64.getEncoder().encodeToString(baos.toByteArray());

        File extractDir = BundleHelper.extractBundle(base64, "EVILCD");
        try {
            // The evil file must NOT appear in the extract dir
            File evil = new File(extractDir.getParentFile().getParentFile(), "evil.dig");
            assertFalse("Path-traversal file must not be created", evil.exists());
            // Extract dir itself should be empty
            File[] files = extractDir.listFiles();
            assertTrue("Extract dir should be empty", files == null || files.length == 0);
        } finally {
            BundleHelper.deleteBundle(extractDir);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void writeFile(File f, String content) throws Exception {
        try (FileWriter fw = new FileWriter(f)) { fw.write(content); }
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
}
