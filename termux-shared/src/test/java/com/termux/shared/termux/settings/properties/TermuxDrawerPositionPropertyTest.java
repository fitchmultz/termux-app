package com.termux.shared.termux.settings.properties;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TermuxDrawerPositionPropertyTest {

    @Test
    public void drawerPositionPropertyIsKnownAndDefaultsToStart() {
        String key = TermuxPropertyConstants.KEY_TERMINAL_SESSION_DRAWER_POSITION;

        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(key));
        assertEquals(TermuxPropertyConstants.IVALUE_TERMINAL_SESSION_DRAWER_POSITION_START,
            TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, null));
    }

    @Test
    public void drawerPositionAcceptsLogicalStartAndEndCaseInsensitively() {
        String key = TermuxPropertyConstants.KEY_TERMINAL_SESSION_DRAWER_POSITION;

        assertEquals(TermuxPropertyConstants.IVALUE_TERMINAL_SESSION_DRAWER_POSITION_START,
            TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, "START"));
        assertEquals(TermuxPropertyConstants.IVALUE_TERMINAL_SESSION_DRAWER_POSITION_END,
            TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, "end"));
    }
}
