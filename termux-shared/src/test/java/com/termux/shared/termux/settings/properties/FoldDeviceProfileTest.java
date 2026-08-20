package com.termux.shared.termux.settings.properties;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class FoldDeviceProfileTest {

    @Test
    public void samsungCharacterInputIsEnabledByDefault() {
        String key = TermuxPropertyConstants.KEY_ENFORCE_CHAR_BASED_INPUT;
        assertTrue(TermuxPropertyConstants.TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key));
        assertTrue((boolean) TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, null));
    }

    @Test
    public void foldExtraKeysIncludeSessionInputAndNavigationControls() {
        String keys = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS;
        assertTrue(keys.contains("DRAWER"));
        assertTrue(keys.contains("KEYBOARD"));
        assertTrue(keys.contains("TEXTBAR"));
        assertTrue(keys.contains("CTRL ALT DOWN"));
        assertTrue(keys.contains("PGUP"));
        assertTrue(keys.contains("PGDN"));
    }
}
