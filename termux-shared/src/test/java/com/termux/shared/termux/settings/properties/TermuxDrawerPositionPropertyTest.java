package com.termux.shared.termux.settings.properties;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TermuxDrawerPositionPropertyTest {

    @Test
    public void drawerPositionPropertyIsKnownAndDefaultsToEnd() {
        String key = TermuxPropertyConstants.KEY_TERMINAL_SESSION_DRAWER_POSITION;

        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(key));
        assertEquals(TermuxPropertyConstants.IVALUE_TERMINAL_SESSION_DRAWER_POSITION_END,
            TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, null));
        assertEquals(TerminalSessionDrawerPosition.END,
            TerminalSessionDrawerPosition.fromInternalValue(null));
        assertEquals(TerminalSessionDrawerPosition.END,
            TerminalSessionDrawerPosition.fromInternalValue("invalid"));
    }

    @Test
    public void drawerPositionAcceptsLogicalStartAndEndCaseInsensitively() {
        String key = TermuxPropertyConstants.KEY_TERMINAL_SESSION_DRAWER_POSITION;

        assertEquals(TermuxPropertyConstants.IVALUE_TERMINAL_SESSION_DRAWER_POSITION_START,
            TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, "START"));
        assertEquals(TermuxPropertyConstants.IVALUE_TERMINAL_SESSION_DRAWER_POSITION_END,
            TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, "end"));
        assertEquals(TerminalSessionDrawerPosition.START,
            TerminalSessionDrawerPosition.fromInternalValue(
                TermuxPropertyConstants.IVALUE_TERMINAL_SESSION_DRAWER_POSITION_START));
        assertEquals(TerminalSessionDrawerPosition.END,
            TerminalSessionDrawerPosition.fromInternalValue(
                TermuxPropertyConstants.IVALUE_TERMINAL_SESSION_DRAWER_POSITION_END));
    }
}
