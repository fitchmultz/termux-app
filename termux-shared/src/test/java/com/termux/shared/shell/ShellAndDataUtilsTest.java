package com.termux.shared.shell;

import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.TermuxShellUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ShellAndDataUtilsTest {

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testArgumentTokenizerTrailingBackslashInDoubleQuotes() {
        assertEquals(Collections.singletonList("abc\\"), ArgumentTokenizer.tokenize("\"abc\\"));
        assertEquals(Arrays.asList("one", "two\\"), ArgumentTokenizer.tokenize("one \"two\\"));
    }

    @Test
    public void testSetupShellCommandArgumentsPreservesShebangFlags() throws Exception {
        File envScript = tempFolder.newFile("env_script.py");
        Files.writeString(envScript.toPath(), "#!/usr/bin/env python3 -u\nprint(1)\n", StandardCharsets.UTF_8);
        assertArrayEquals(
            new String[]{TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/env", "python3", "-u", envScript.getAbsolutePath(), "--arg"},
            TermuxShellUtils.setupShellCommandArguments(envScript.getAbsolutePath(), new String[]{"--arg"}));

        File bashScript = tempFolder.newFile("strict.sh");
        Files.writeString(bashScript.toPath(), "#!/bin/bash -eu\necho ok\n", StandardCharsets.UTF_8);
        assertArrayEquals(
            new String[]{TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", "-eu", bashScript.getAbsolutePath()},
            TermuxShellUtils.setupShellCommandArguments(bashScript.getAbsolutePath(), null));
    }

    @Test
    public void testTruncatedCommandOutputUsesUtf8ByteBudgetWithoutSplittingCodePoints() {
        // Each '🚀' (U+1F680) is 2 UTF-16 chars and 4 UTF-8 bytes.
        String rockets = "🚀🚀🚀"; // 12 UTF-8 bytes
        assertEquals(rockets, DataUtils.getTruncatedCommandOutput(rockets, 12, true, false, false));

        String fromEnd = DataUtils.getTruncatedCommandOutput(rockets, 9, true, false, false);
        assertEquals("🚀🚀", fromEnd);
        assertTrue(fromEnd.getBytes(StandardCharsets.UTF_8).length <= 9);

        String fromStart = DataUtils.getTruncatedCommandOutput(rockets, 9, false, false, false);
        assertEquals("🚀🚀", fromStart);
        assertTrue(fromStart.getBytes(StandardCharsets.UTF_8).length <= 9);

        assertEquals("🚀🚀🚀🚀🚀", DataUtils.getTruncatedCommandOutput("🚀🚀🚀🚀🚀", 20, true, false, true));
        String withPrefix = DataUtils.getTruncatedCommandOutput("🚀🚀🚀🚀🚀🚀", 20, true, false, true);
        assertEquals("(truncated) 🚀🚀", withPrefix);
        assertEquals(20, withPrefix.getBytes(StandardCharsets.UTF_8).length);

        // Short string within maxLength with addPrefix=true must remain untruncated.
        assertEquals("abc", DataUtils.getTruncatedCommandOutput("abc", 5, true, false, true));
        // Over-budget string when maxLength cannot even fit prefix must never exceed maxLength.
        assertEquals("", DataUtils.getTruncatedCommandOutput("abcdefghij", 5, true, false, true));
    }
}
