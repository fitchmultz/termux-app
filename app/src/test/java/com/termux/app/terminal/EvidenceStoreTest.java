package com.termux.app.terminal;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.ClipData;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class EvidenceStoreTest {
    @Test
    public void sessionDraftsRemainSeparateAndRecoverableAfterProcessRecreation() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("evidence-drafts", 0).edit().clear().commit();
        EvidenceStore store = new EvidenceStore(context);
        store.get("a").text = "Explain this screenshot";
        store.get("a").label = "Agent A";
        store.get("a").files.add("/private/evidence/capture.png");
        store.get("b").text = "npm test";
        assertTrue(store.save("a"));
        assertTrue(store.save("b"));
        EvidenceStore restored = new EvidenceStore(context);
        assertEquals("Explain this screenshot\n/private/evidence/capture.png", restored.get("a").payload());
        assertEquals("npm test", restored.get("b").payload());
        assertEquals(2, restored.savedHandles().size());
        restored.get("b").text = "";
        restored.save("b");
        assertEquals(1, restored.savedHandles().size());
        assertTrue(EvidenceDraft.needsBracketedPaste(restored.get("a").payload()));
        assertTrue(EvidenceDraft.needsBracketedPaste("a\rb"));
        assertFalse(EvidenceDraft.needsBracketedPaste("npm test"));
        assertEquals(restored.get("a").payload(), EvidenceDraft.fromJson(restored.get("a").toJson()).payload());
    }

    @Test
    public void ungrantedFileUrisAndOversizedDraftsFailWithoutChangingTheOriginal() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("evidence-drafts", 0).edit().clear().commit();
        EvidenceStore store = new EvidenceStore(context);
        store.get("a").text = "keep me";
        ArrayList<Uri> files = new ArrayList<>();
        files.add(Uri.parse("file:///private/secret"));
        store.importEvidence("a", "A", "new text", files);
        assertEquals("keep me", store.get("a").text);
        assertTrue(store.get("a").files.isEmpty());
        assertFalse(store.isImporting());
        assertNotNull(store.takeNotice());
        String oversized = new String(new char[EvidenceDraft.MAX_TEXT + 1]).replace('\0', 'x');
        store.importEvidence("a", "A", oversized, new ArrayList<>());
        assertEquals("keep me", store.get("a").text);
        assertFalse(store.isImporting());
    }

    @Test
    public void realImportCompletesWithoutAViewAndPublishesTheDraftAtomically() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("evidence-drafts", 0).edit().clear().commit();
        java.io.File directory = new java.io.File(context.getCacheDir(), "evidence");
        EvidenceStore store = new EvidenceStore(context, directory);
        store.get("a").text = "original";
        store.save("a");
        Uri uri = Uri.parse("content://pictures/photo");
        byte[] content = new byte[]{1, 2, 3};
        org.robolectric.Shadows.shadowOf(context.getContentResolver()).registerInputStream(uri, new java.io.ByteArrayInputStream(content));
        ArrayList<Uri> files = new ArrayList<>();
        files.add(uri);
        store.importEvidence("a", "Agent", "shared note", files);
        assertTrue(store.isImporting());
        waitForImport(store);
        EvidenceStore restored = new EvidenceStore(context, directory);
        assertEquals("original\n\nshared note", restored.get("a").text);
        assertEquals(1, restored.get("a").files.size());
        assertArrayEquals(content, java.nio.file.Files.readAllBytes(new java.io.File(restored.get("a").files.get(0)).toPath()));
        assertFalse(context.getSharedPreferences("evidence-drafts", 0).contains("import_pending"));
        assertNull(restored.takeNotice());
        assertNotNull(store.takeNotice());
    }

    @Test
    public void aFailedBatchDoesNotPublishAnEarlierSuccessfulFile() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("evidence-drafts", 0).edit().clear().commit();
        java.io.File directory = new java.io.File(context.getCacheDir(), "failed-evidence");
        EvidenceStore store = new EvidenceStore(context, directory);
        store.get("a").text = "original";
        Uri first = Uri.parse("content://pictures/first"), second = Uri.parse("content://pictures/second");
        org.robolectric.Shadows.shadowOf(context.getContentResolver()).registerInputStream(first, new java.io.ByteArrayInputStream(new byte[]{1}));
        org.robolectric.Shadows.shadowOf(context.getContentResolver()).registerInputStream(second, new java.io.InputStream() {
            public int read() throws java.io.IOException { throw new java.io.IOException("synthetic read failure"); }
        });
        ArrayList<Uri> files = new ArrayList<>();
        files.add(first);
        files.add(second);
        store.importEvidence("a", "Agent", "shared note", files);
        waitForImport(store);
        assertEquals("original", store.get("a").text);
        assertTrue(store.get("a").files.isEmpty());
        assertEquals(0, directory.list().length);
        assertNotNull(store.takeNotice());
    }

    private void waitForImport(EvidenceStore store) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (store.isImporting() && System.nanoTime() < deadline) {
            Thread.sleep(10);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
        assertFalse("Import did not finish", store.isImporting());
    }

    @Test
    public void duplicateShareUrisAreNotImportedTwiceAndInterruptedWorkIsReported() {
        Uri uri = Uri.parse("content://pictures/photo");
        Intent intent = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri);
        intent.setClipData(ClipData.newRawUri("photo", uri));
        assertEquals(1, EvidenceDock.uris(intent).size());
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("evidence-drafts", 0).edit().clear().putBoolean("import_pending", true).commit();
        EvidenceStore store = new EvidenceStore(context);
        assertFalse(store.isImporting());
        assertNotNull(store.takeNotice());
        assertNull(store.takeNotice());
    }
}
