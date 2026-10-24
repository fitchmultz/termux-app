package com.termux.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Extracts a verified bootstrap into staging; no prefix replacement or package scripts run here. */
final class BootstrapArchive {
    static Map<File, String> extract(InputStream input, File destination) throws IOException {
        File root = destination.getCanonicalFile();
        Map<File, String> symlinks = new LinkedHashMap<>();
        Set<File> files = new HashSet<>();
        Set<String> entries = new HashSet<>();
        List<String> executables = null;
        byte[] buffer = new byte[8096];
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entries.add(entry.getName())) throw new IOException("Duplicate bootstrap entry: " + entry.getName());
                if (entry.getName().equals("EXECUTABLES.txt")) {
                    executables = lines(zip);
                } else if (entry.getName().equals("SYMLINKS.txt")) {
                    for (String line : lines(zip)) {
                        String[] parts = line.split("←", -1);
                        if (parts.length != 2 || parts[0].isEmpty()) throw new IOException("Malformed symlink: " + line);
                        File path = child(root, parts[1]);
                        if (symlinks.put(path, parts[0]) != null) throw new IOException("Duplicate symlink: " + path);
                        directory(path.getParentFile());
                    }
                } else {
                    File path = child(root, entry.getName());
                    directory(entry.isDirectory() ? path : path.getParentFile());
                    if (!entry.isDirectory()) {
                        if (!files.add(path)) throw new IOException("Duplicate bootstrap file: " + path);
                        try (FileOutputStream output = new FileOutputStream(path)) {
                            int count;
                            while ((count = zip.read(buffer)) != -1) output.write(buffer, 0, count);
                        }
                    }
                }
            }
        }
        if (symlinks.isEmpty()) throw new IOException("No SYMLINKS.txt encountered");
        if (executables == null) throw new IOException("No EXECUTABLES.txt encountered");
        Set<File> marked = new HashSet<>();
        for (String name : executables) {
            File path = child(root, name);
            if (!files.contains(path) || !marked.add(path)) throw new IOException("Invalid executable file: " + name);
            // New files have no execute bits. Restore only the package's owner-execute bit,
            // not broad directory-based heuristics or group/other execute permissions.
            if (!path.setExecutable(true, true)) throw new IOException("Cannot make executable: " + path);
        }
        for (File path : files) rejectSymlinkAncestor(root, path, symlinks);
        for (File path : symlinks.keySet()) {
            if (files.contains(path)) throw new IOException("Symlink replaces regular file: " + path);
            rejectSymlinkAncestor(root, path, symlinks);
        }
        return symlinks;
    }

    private static List<String> lines(InputStream input) throws IOException {
        List<String> result = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) result.add(line);
        return result;
    }

    private static File child(File root, String name) throws IOException {
        File file = new File(root, name).getCanonicalFile();
        if (name.isEmpty() || name.startsWith("/") || !file.getPath().startsWith(root.getPath() + File.separator))
            throw new IOException("Bootstrap path escapes staging: " + name);
        return file;
    }

    private static void directory(File path) throws IOException {
        if (!path.isDirectory() && !path.mkdirs()) throw new IOException("Cannot create directory: " + path);
    }

    private static void rejectSymlinkAncestor(File root, File path, Map<File, String> symlinks) throws IOException {
        for (File parent = path.getParentFile(); !parent.equals(root); parent = parent.getParentFile()) {
            if (symlinks.containsKey(parent)) throw new IOException("Bootstrap path traverses symlink: " + path);
        }
    }
}
