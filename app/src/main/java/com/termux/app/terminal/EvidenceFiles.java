package com.termux.app.terminal;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class EvidenceFiles {
    public static final long MAX_FILE_BYTES = 32L * 1024 * 1024;

    private EvidenceFiles() {}

    public static File importFile(ContentResolver resolver, Uri uri, File directory) throws IOException {
        if (!"content".equals(uri.getScheme())) throw new IOException("Only granted content URIs can be imported");
        String name = "attachment";
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && cursor.getString(column) != null) name = cursor.getString(column);
            }
        }
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create evidence directory");
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("Cannot open attachment");
            return copy(input, name, directory);
        }
    }

    static File copy(InputStream input, String name, File directory) throws IOException {
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.length() > 80) name = name.substring(name.length() - 80);
        File file = File.createTempFile("evidence-", "-" + name, directory);
        boolean complete = false;
        try (FileOutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_FILE_BYTES || Thread.currentThread().isInterrupted()) throw new IOException("Attachment exceeds limit or import cancelled");
                output.write(buffer, 0, read);
            }
            complete = true;
            return file;
        } finally {
            if (!complete) file.delete();
        }
    }
}
