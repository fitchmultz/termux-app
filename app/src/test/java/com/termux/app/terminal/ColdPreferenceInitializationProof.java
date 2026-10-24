package com.termux.app.terminal;

import android.content.Context;

import com.termux.app.TermuxApplication;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Temporary public-API stress diagnostic, not a deterministic scheduling ablation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = ColdPreferenceInitializationProof.TestApplication.class)
public class ColdPreferenceInitializationProof {
    public static class TestApplication extends TermuxApplication {
        @Override public void onCreate() { }
    }

    private static final String NAME = TermuxConstants.TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION;
    private static final String KEY = TermuxPreferenceConstants.TERMUX_APP.KEY_KEEP_SCREEN_ON;
    private final Set<Thread> ownedLoaders = new HashSet<>();
    private Context context;

    @Test
    public void compareTwoHundredColdAndTwoHundredPrewarmedInitializations() throws Exception {
        context = RuntimeEnvironment.getApplication();
        File path = new File(context.getDataDir(), "shared_prefs/" + NAME + ".xml");
        String xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map>" +
            "<boolean name=\"" + KEY + "\" value=\"false\"/>" +
            "<string name=\"diagnostic_padding\">" + "x".repeat(65536) + "</string></map>";
        int coldTrials = 0, warmTrials = 0, coldLosses = 0, warmLosses = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        try {
            for (boolean prewarm : new boolean[] { false, true }) {
                for (int trial = 0; trial < 200; trial++) {
                    assertTrue("Bounded stress deadline exceeded", System.nanoTime() < deadline);
                    context.deleteSharedPreferences(NAME);
                    Files.createDirectories(path.toPath().getParent());
                    Files.writeString(path.toPath(), xml);
                    Set<Thread> before = Thread.getAllStackTraces().keySet();
                    TermuxAppSharedPreferences preferences;
                    try {
                        if (prewarm) {
                            assertTrue(context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                                .putBoolean(KEY, true).commit());
                        }
                        preferences = TermuxAppSharedPreferences.build(context);
                        assertNotNull(preferences);
                        if (!prewarm) preferences.setKeepScreenOn(true);
                    } finally {
                        for (Thread thread : Thread.getAllStackTraces().keySet()) {
                            if (!before.contains(thread) && thread.getName().equals("SharedPreferencesImpl-load"))
                                ownedLoaders.add(thread);
                        }
                        settleOwnedLoaders();
                    }
                    if (prewarm) {
                        warmTrials++;
                        if (!preferences.shouldKeepScreenOn()) warmLosses++;
                    } else {
                        coldTrials++;
                        if (!preferences.shouldKeepScreenOn()) coldLosses++;
                    }
                }
            }
        } finally {
            System.out.println("PUBLIC-API STRESS only (not deterministic ablation/test-first): cold trials=" +
                coldTrials + ", lost initial true=" + coldLosses + "; prewarmed trials=" + warmTrials +
                ", lost initial true=" + warmLosses);
        }
        assertTrue("INCONCLUSIVE: no cold loss observed in this one bounded stress run", coldLosses > 0);
        assertEquals("Prewarming did not stabilize the initial preference", 0, warmLosses);
    }

    private void settleOwnedLoaders() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (Thread thread : ownedLoaders) {
            if (!thread.isAlive()) continue;
            long remaining = deadline - System.nanoTime();
            assertTrue("Owned preference loader did not settle", remaining > 0);
            TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
            assertFalse("Owned preference loader exceeded its bounded join", thread.isAlive());
        }
    }

    @After public void cleanup() throws Exception {
        settleOwnedLoaders();
        if (context != null) context.deleteSharedPreferences(NAME);
    }
}
