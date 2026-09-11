package com.termux.app.terminal;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;

public class EvidenceFilesTest {
    @Test
    public void importedNamesCannotEscapeAndFailedCopiesDoNotLeavePartialFiles() throws Exception {
        File directory = Files.createTempDirectory("evidence-test").toFile();
        try {
            File first = EvidenceFiles.copy(new ByteArrayInputStream(new byte[]{1, 2, 3}), "../../capture.png", directory);
            File second = EvidenceFiles.copy(new ByteArrayInputStream(new byte[]{4}), "../../capture.png", directory);
            assertEquals(directory.getCanonicalFile(), first.getCanonicalFile().getParentFile());
            assertNotEquals(first, second);
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(first.toPath()));
            String[] before = directory.list();
            try {
                EvidenceFiles.copy(new InputStream() {
                    long remaining = EvidenceFiles.MAX_FILE_BYTES + 1;
                    public int read() { return remaining-- > 0 ? 0 : -1; }
                    public int read(byte[] bytes) {
                        if (remaining == 0) return -1;
                        int count = (int) Math.min(remaining, bytes.length);
                        remaining -= count;
                        return count;
                    }
                }, "oversized.png", directory);
                fail("Oversized imports must fail");
            } catch (IOException expected) { }
            String[] after = directory.list();
            Arrays.sort(before);
            Arrays.sort(after);
            assertArrayEquals(before, after);
        } finally {
            for (File file : directory.listFiles()) file.delete();
            directory.delete();
        }
    }
}
