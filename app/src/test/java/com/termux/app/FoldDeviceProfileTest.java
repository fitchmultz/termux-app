package com.termux.app;

import com.termux.shared.termux.extrakeys.ExtraKeyButton;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FoldDeviceProfileTest {

    @Test
    public void defaultExtraKeysParseAsTwoRowsOfSix() throws Exception {
        ExtraKeysInfo info = new ExtraKeysInfo(
            TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS,
            TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE,
            ExtraKeysConstants.CONTROL_CHARS_ALIASES);

        ExtraKeyButton[][] matrix = info.getMatrix();
        assertEquals(2, matrix.length);
        assertEquals(6, matrix[0].length);
        assertEquals(6, matrix[1].length);
        assertEquals("DRAWER", matrix[0][5].getKey());
        assertEquals("KEYBOARD", matrix[1][5].getKey());
        assertEquals("TEXTBAR", matrix[1][5].getPopup().getKey());
    }
}
