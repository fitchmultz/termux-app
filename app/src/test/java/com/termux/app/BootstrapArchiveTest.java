package com.termux.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class BootstrapArchiveTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void deliversExecutableScriptsAndGitHooksWithoutStartingServicesOrBroadeningFiles() throws Exception {
        File root = temporary.newFolder("staging");
        File marker = new File(root, "started");
        String shell = new File("/system/bin/sh").exists() ? "/system/bin/sh" : "/bin/sh";
        Map<String, String> files = fixture("var/service/sshd/run\nshare/git-core/templates/hooks/fold.sample\n");
        files.put("var/service/sshd/run", "#!" + shell + "\nprintf started > \"$MARKER\"\n");
        files.put("share/git-core/templates/hooks/fold.sample", "#!" + shell + "\nprintf hook-delivered\n");
        Map<File, String> links = BootstrapArchive.extract(new ByteArrayInputStream(zip(files)), root);
        assertEquals("../var/service/sshd/run", links.get(new File(root, "bin/service-link")));
        assertFalse("Extraction must not run services", marker.exists());
        assertTrue(new File(root, "var/service/sshd/down").isFile());
        assertFalse("A bin/ path alone must not grant execute", new File(root, "bin/non-executable").canExecute());
        assertFalse(new File(root, "share/doc/readme").canExecute());
        ProcessBuilder service = new ProcessBuilder(new File(root, "var/service/sshd/run").getPath());
        service.environment().put("MARKER", marker.getPath());
        assertEquals(0, service.start().waitFor());
        assertEquals("started", Files.readString(marker.toPath()));
        assertTrue("Explicit execution must not enable autostart", new File(root, "var/service/sshd/down").isFile());

        File repository = temporary.newFolder("repository");
        ProcessBuilder git = new ProcessBuilder("git", "init", "--quiet", "--template=" +
            new File(root, "share/git-core/templates").getPath(), repository.getPath()).redirectErrorStream(true);
        git.environment().put("HOME", temporary.newFolder("home").getPath());
        git.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        Process process = git.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, process.waitFor());
        File hook = new File(repository, ".git/hooks/fold.sample");
        Process hookProcess = new ProcessBuilder(hook.getPath()).start();
        assertEquals("hook-delivered", new String(hookProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(0, hookProcess.waitFor());
    }

    @Test public void rejectsMissingOrUnsafeExecutableMetadataBeforeLinksAreInstalled() throws Exception {
        String[][] cases = {{null, "No EXECUTABLES.txt"}, {"../outside\n", "Bootstrap path escapes staging"},
            {"bin/service-link\n", "Invalid executable file"}, {"missing\n", "Invalid executable file"}};
        for (String[] test : cases) {
            Map<String, String> files = fixture(test[0]);
            File root = temporary.newFolder();
            try {
                BootstrapArchive.extract(new ByteArrayInputStream(zip(files)), root);
                fail("Missing/unsafe executable metadata must fail: " + test[0]);
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains(test[1]));
                assertFalse(new File(root, "bin/service-link").exists());
            }
        }
    }

    private static Map<String, String> fixture(String executable) {
        Map<String, String> files = new LinkedHashMap<>();
        if (executable != null) files.put("EXECUTABLES.txt", executable);
        files.put("SYMLINKS.txt", "../var/service/sshd/run←./bin/service-link\n");
        files.put("var/service/sshd/run", "not executed by extraction");
        files.put("var/service/sshd/down", "");
        files.put("bin/non-executable", "not executable");
        files.put("share/doc/readme", "documentation");
        return files;
    }

    private static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
