package com.termux.app.api.file;

import com.termux.app.api.file.FileReceiverActivity;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class FileReceiverActivityTest {

    private static final class ReceivedInput extends InputStream {
        int closes;
        boolean fail;
        int remaining = 3;
        @Override public int read() throws IOException {
            if (fail) throw new IOException("Provider read failed");
            return remaining-- > 0 ? 42 : -1;
        }
        @Override public void close() { closes++; }
    }

    public static class ReceivingActivity extends FileReceiverActivity {
        final List<ReceivedInput> opened = new ArrayList<>();
        @Override void handleContentUri(android.net.Uri uri, String title) {
            ReceivedInput input = new ReceivedInput();
            opened.add(input);
            promptNameAndSave(input, title);
        }
    }

    @Test
    @Config(sdk = 28, application = android.app.Application.class)
    public void resumingKeepsOnePromptAndANewIntentReplacesItsOwnedInput() {
        android.content.Intent first = new android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse("content://received/first"));
        org.robolectric.android.controller.ActivityController<ReceivingActivity> controller =
            Robolectric.buildActivity(ReceivingActivity.class, first).create().start().resume();
        ReceivingActivity activity = controller.get();
        android.app.AlertDialog firstDialog = ShadowAlertDialog.getLatestAlertDialog();
        controller.pause().resume();
        Assert.assertEquals(1, activity.opened.size());
        Assert.assertSame(firstDialog, ShadowAlertDialog.getLatestAlertDialog());
        Assert.assertEquals(0, activity.opened.get(0).closes);

        android.content.Intent next = new android.content.Intent(first)
            .putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse("content://received/next"));
        controller.pause().newIntent(next).resume();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        Assert.assertEquals(2, activity.opened.size());
        Assert.assertEquals(next, activity.getIntent());
        Assert.assertFalse(firstDialog.isShowing());
        Assert.assertNotSame(firstDialog, ShadowAlertDialog.getLatestAlertDialog());
        Assert.assertEquals(1, activity.opened.get(0).closes);
        Assert.assertEquals(0, activity.opened.get(1).closes);
        Assert.assertFalse(activity.isFinishing());
        controller.pause().stop().destroy();
        Assert.assertEquals(1, activity.opened.get(1).closes);
    }

    @Test
    @Config(sdk = 28, application = android.app.Application.class)
    public void receivedInputsCloseOnSaveValidationFailureAndReadFailure() {
        FileReceiverActivity activity = Robolectric.buildActivity(FileReceiverActivity.class).create().get();
        ReceivedInput invalid = new ReceivedInput();
        Assert.assertNull(activity.saveStreamWithName(invalid, ""));
        Assert.assertEquals(1, invalid.closes);

        String name = "ownership-" + java.util.UUID.randomUUID();
        ReceivedInput saved = new ReceivedInput();
        File output = activity.saveStreamWithName(saved, name);
        Assert.assertNotNull(output);
        Assert.assertEquals(3, output.length());
        Assert.assertEquals(1, saved.closes);
        Assert.assertTrue(output.delete());

        ReceivedInput failed = new ReceivedInput();
        failed.fail = true;
        Assert.assertNull(activity.saveStreamWithName(failed, name));
        Assert.assertEquals(1, failed.closes);
        new File(FileReceiverActivity.TERMUX_RECEIVEDIR, name).delete();
    }

    @Test
    @Config(sdk = 28, application = android.app.Application.class)
    public void pendingInputsCloseOnceOnCancelDismissAndDestruction() {
        org.robolectric.android.controller.ActivityController<FileReceiverActivity> controller =
            Robolectric.buildActivity(FileReceiverActivity.class).create();
        FileReceiverActivity activity = controller.get();
        ReceivedInput cancelled = new ReceivedInput();
        activity.promptNameAndSave(cancelled, "cancel");
        ShadowAlertDialog.getLatestAlertDialog().getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        Assert.assertEquals(1, cancelled.closes);
        controller.destroy();
        Assert.assertEquals(1, cancelled.closes);

        controller = Robolectric.buildActivity(FileReceiverActivity.class).create();
        activity = controller.get();
        ReceivedInput destroyed = new ReceivedInput();
        activity.promptNameAndSave(destroyed, "destroy");
        android.app.AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        controller.destroy();
        dialog.dismiss();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        Assert.assertEquals(1, destroyed.closes);
    }

    @Test
    public void testIsSharedTextAnUrl() {
        List<String> validUrls = new ArrayList<>();
        validUrls.add("http://example.com");
        validUrls.add("https://example.com");
        validUrls.add("https://example.com/path/parameter=foo");
        validUrls.add("magnet:?xt=urn:btih:d540fc48eb12f2833163eed6421d449dd8f1ce1f&dn=Ubuntu+desktop+19.04+%2864bit%29&tr=udp%3A%2F%2Ftracker.openbittorrent.com%3A80&tr=udp%3A%2F%2Ftracker.publicbt.com%3A80&tr=udp%3A%2F%2Ftracker.ccc.de%3A80");
        for (String url : validUrls) {
            Assert.assertTrue(FileReceiverActivity.isSharedTextAnUrl(url));
        }

        List<String> invalidUrls = new ArrayList<>();
        invalidUrls.add("a test with example.com");
        invalidUrls.add("");
        invalidUrls.add(null);
        for (String url : invalidUrls) {
            Assert.assertFalse(FileReceiverActivity.isSharedTextAnUrl(url));
        }
    }

}
