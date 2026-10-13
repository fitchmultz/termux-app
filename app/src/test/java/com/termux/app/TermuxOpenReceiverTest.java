package com.termux.app;

import android.net.Uri;

import com.termux.app.terminal.EvidenceDockTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Locale;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 37}, application = EvidenceDockTest.TestApplication.class)
public class TermuxOpenReceiverTest {
    @Test
    public void fileSharingMimeTypesHandleRootUrisAndIgnoreUserLocale() {
        TermuxOpenReceiver.ContentProvider provider = new TermuxOpenReceiver.ContentProvider();
        assertNull(provider.getType(Uri.parse("content://com.termux.files/")));
        assertNull(provider.getType(Uri.parse("content://com.termux.files/no-extension")));
        assertEquals("text/plain", provider.getType(Uri.parse("content://com.termux.files/report.txt")));

        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals("image/gif", provider.getType(Uri.parse("content://com.termux.files/image.GIF")));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
