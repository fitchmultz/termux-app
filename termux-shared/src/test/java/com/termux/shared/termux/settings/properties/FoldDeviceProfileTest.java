package com.termux.shared.termux.settings.properties;

import com.termux.shared.termux.extrakeys.ExtraKeysConstants;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FoldDeviceProfileTest {

    @Test
    public void foldPropertySchemaRegistersDefaultsExactlyOnce() {
        String characterInput = TermuxPropertyConstants.KEY_ENFORCE_CHAR_BASED_INPUT;
        String textInput = TermuxPropertyConstants.KEY_SHOW_TERMINAL_TOOLBAR_TEXT_INPUT;
        String drawer = TermuxPropertyConstants.KEY_TERMINAL_SESSION_DRAWER_POSITION;

        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(characterInput));
        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(textInput));
        assertTrue(TermuxPropertyConstants.TERMUX_APP_PROPERTIES_LIST.contains(drawer));
        assertTrue(TermuxPropertyConstants.TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(characterInput));
        assertTrue(TermuxPropertyConstants.TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(textInput));
        assertFalse(TermuxPropertyConstants.TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(characterInput));
        assertFalse(TermuxPropertyConstants.TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(textInput));
        assertEquals("▤", ExtraKeysConstants.EXTRA_KEY_DISPLAY_MAPS.WELL_KNOWN_CHARACTERS_DISPLAY.get(
            ExtraKeysConstants.ACTION_TEXTBAR));
    }

    @Test
    public void samsungCharacterInputIsEnabledByDefault() {
        String key = TermuxPropertyConstants.KEY_ENFORCE_CHAR_BASED_INPUT;
        assertTrue(TermuxPropertyConstants.TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key));
        assertTrue((boolean) TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(null, key, null));
    }

    @Test
    public void foldExtraKeysIncludeSessionInputAndNavigationControls() {
        String keys = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS;
        assertTrue(keys.contains(ExtraKeysConstants.ACTION_DRAWER));
        assertTrue(keys.contains(ExtraKeysConstants.ACTION_KEYBOARD));
        assertTrue(keys.contains(ExtraKeysConstants.ACTION_TEXTBAR));
        assertTrue(keys.contains("CTRL ALT DOWN"));
        assertTrue(keys.contains("PGUP"));
        assertTrue(keys.contains("PGDN"));
    }
}
