package com.termux.shared.termux.settings.properties;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TermuxToolbarPropertyTest {

    @Test
    public void simultaneousTextInputPropertyIsKnownAndDefaultsToTrue() {
        String key = TermuxPropertyConstants.KEY_SHOW_TERMINAL_TOOLBAR_TEXT_INPUT;

        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(key));
        assertTrue(TermuxPropertyConstants.TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key));
        assertTrue((boolean) TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, null));
        assertFalse((boolean) TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, "false"));
        assertTrue((boolean) TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, "true"));
    }
}
