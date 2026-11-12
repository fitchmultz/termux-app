package com.termux.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TermuxServiceCompletionPolicyTest {

    @Test
    public void requestedSigkillIsRemovedWithoutHidingUnexpectedFailure() {
        assertTrue(TermuxService.shouldRemoveFinishedSession(true, false, 1, -9, false));
        assertTrue(TermuxService.shouldRemoveFinishedSession(true, true, 1, -9, false));
        assertFalse(TermuxService.shouldRemoveFinishedSession(false, false, 1, -9, false));
        assertTrue(TermuxService.shouldRemoveFinishedSession(false, false, 1, 0, false));
        assertTrue(TermuxService.shouldRemoveFinishedSession(false, false, 1, 130, false));
        assertTrue(TermuxService.shouldRemoveFinishedSession(false, false, 1, -9, true));
        assertFalse(TermuxService.shouldRemoveFinishedSession(false, true, 1, 0, false));
        assertTrue(TermuxService.shouldRemoveFinishedSession(false, true, 2, -9, false));
    }
}
