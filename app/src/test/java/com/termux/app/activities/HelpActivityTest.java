package com.termux.app.activities;

import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.termux.shared.termux.TermuxConstants;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowWebView;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = android.app.Application.class)
public class HelpActivityTest {
    @Test
    public void destroyReleasesBothLoadingAndDisplayedWebViews() {
        for (boolean loaded : new boolean[]{false, true}) {
            ActivityController<HelpActivity> controller = Robolectric.buildActivity(HelpActivity.class).setup();
            HelpActivity activity = controller.get();
            WebView webView = activity.mWebView;
            ShadowWebView shadow = shadowOf(webView);
            WebViewClient client = shadow.getWebViewClient();
            if (loaded) {
                client.onPageFinished(webView, TermuxConstants.TERMUX_WIKI_URL);
                assertNotNull(webView.getParent());
            }
            controller.pause().stop().destroy();
            assertNull(activity.mWebView);
            assertNull(webView.getParent());
            assertTrue(shadow.wasDestroyCalled());
            client.onPageFinished(webView, TermuxConstants.TERMUX_WIKI_URL);
            assertNull(webView.getParent());
            assertTrue(client.shouldOverrideUrlLoading(webView, TermuxConstants.TERMUX_WIKI_URL));
        }
    }
}
