package com.termux.app.terminal;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TermuxTerminalSessionActivityClientTest {

    @Test
    public void requestedSigkillIsRemovedWithoutHidingUnexpectedFailure() {
        assertTrue(TermuxTerminalSessionActivityClient.shouldRemoveFinishedSession(true, false, 1, -9, false));
        assertTrue(TermuxTerminalSessionActivityClient.shouldRemoveFinishedSession(true, true, 1, -9, false));
        assertFalse(TermuxTerminalSessionActivityClient.shouldRemoveFinishedSession(false, false, 1, -9, false));
        assertTrue(TermuxTerminalSessionActivityClient.shouldRemoveFinishedSession(false, false, 1, 0, false));
        assertTrue(TermuxTerminalSessionActivityClient.shouldRemoveFinishedSession(false, false, 1, 130, false));
        assertTrue(TermuxTerminalSessionActivityClient.shouldRemoveFinishedSession(false, false, 1, -9, true));
        assertFalse(TermuxTerminalSessionActivityClient.shouldRemoveFinishedSession(false, true, 1, 0, false));
        assertTrue(TermuxTerminalSessionActivityClient.shouldRemoveFinishedSession(false, true, 2, -9, false));
    }
}
