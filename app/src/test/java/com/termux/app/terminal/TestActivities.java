package com.termux.app.terminal;

import com.termux.app.TermuxActivity;

import org.robolectric.util.ReflectionHelpers;

/**
 * Helpers for Robolectric tests that need an activity in a given lifecycle without running it.
 *
 * <p>The activity's lifecycle is one value, so a test that makes it visible must move that value
 * rather than set a separate flag that no longer exists.
 */
final class TestActivities {

    private TestActivities() { }

    /** Between onStart() and onStop(). */
    static void markStarted(TermuxActivity activity) {
        setLifecycle(activity, "STARTED");
    }

    /** Between onStop() and onStart(). */
    static void markCreated(TermuxActivity activity) {
        setLifecycle(activity, "CREATED");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setLifecycle(TermuxActivity activity, String name) {
        Object current = ReflectionHelpers.getField(activity, "mLifecycle");
        ReflectionHelpers.setField(activity, "mLifecycle", Enum.valueOf((Class) current.getClass(), name));
    }
}
