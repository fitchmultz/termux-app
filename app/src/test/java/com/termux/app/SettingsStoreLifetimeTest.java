package com.termux.app;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.app.fragments.settings.TermuxPreferencesFragment;
import com.termux.app.fragments.settings.termux.DebuggingPreferencesFragment;
import com.termux.app.fragments.settings.termux.TerminalIOPreferencesFragment;
import com.termux.app.fragments.settings.termux.TerminalViewPreferencesFragment;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = android.app.Application.class)
public class SettingsStoreLifetimeTest {
    public static class Host extends AppCompatActivity {
        @Override
        protected void onCreate(Bundle state) {
            setTheme(androidx.appcompat.R.style.Theme_AppCompat);
            super.onCreate(state);
        }
    }

    private PreferenceDataStore visit(Host host, PreferenceFragmentCompat fragment) {
        host.getSupportFragmentManager().beginTransaction()
            .replace(android.R.id.content, fragment).commitNow();
        return fragment.getPreferenceManager().getPreferenceDataStore();
    }

    @Test
    public void returningToSettingsCreatesANewStoreWithoutLosingPreferences() {
        try (ActivityController<Host> controller = Robolectric.buildActivity(Host.class).setup()) {
            Host host = controller.get();
            PreferenceDataStore input = visit(host, new TerminalIOPreferencesFragment());
            input.putBoolean("ime_composing_enabled", true);
            PreferenceDataStore view = visit(host, new TerminalViewPreferencesFragment());
            PreferenceDataStore debugging = visit(host, new DebuggingPreferencesFragment());
            PreferenceDataStore general = visit(host, new TermuxPreferencesFragment());

            PreferenceDataStore replacement = visit(host, new TerminalIOPreferencesFragment());
            assertNotSame(input, replacement);
            assertTrue(replacement.getBoolean("ime_composing_enabled", false));
            assertNotSame(view, visit(host, new TerminalViewPreferencesFragment()));
            assertNotSame(debugging, visit(host, new DebuggingPreferencesFragment()));
            assertNotSame(general, visit(host, new TermuxPreferencesFragment()));
        }
    }
}
