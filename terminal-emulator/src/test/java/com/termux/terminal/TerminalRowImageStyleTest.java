package com.termux.terminal;

import junit.framework.TestCase;

/** Shared row regressions: bitmap payloads must not style collateral wide-character blanks. */
public class TerminalRowImageStyleTest extends TestCase {
    private static final int COLUMNS = 6;
    private static final int[] WIDE = {'漢', 0x2070E}; // BMP and surrogate-pair wide characters.
    private static final long OLD_TEXT = TextStyle.encode(0xff123456, 0xff654321,
        TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE);
    private static final long OTHER_TEXT = TextStyle.encode(2, 4, TextStyle.CHARACTER_ATTRIBUTE_ITALIC);
    private static final long NEW_TEXT = TextStyle.encode(3, 5, TextStyle.CHARACTER_ATTRIBUTE_INVERSE);

    private static void assertText(TerminalRow row, String expected) {
        assertEquals(expected, new String(row.mText, 0, row.getSpaceUsed()));
    }

    private static void assertOnlyBitmap(TerminalRow row, int column, long expectedStyle) {
        assertTrue(row.mHasTerminalBitmap);
        for (int col = 0; col < COLUMNS; col++) {
            if (col == column) {
                assertEquals(expectedStyle, row.getStyle(col));
            } else {
                assertFalse("Unexpected image payload at " + col, TextStyle.isTerminalBitmap(row.getStyle(col)));
            }
        }
    }

    private static void assertSingleWidthColumns(TerminalRow row) {
        assertEquals(COLUMNS, row.getSpaceUsed());
        for (int col = 0; col <= COLUMNS; col++) assertEquals(col, row.findStartOfColumn(col));
    }

    public void testReportedSixColumnSecondHalfRepro() {
        TerminalRow row = new TerminalRow(COLUMNS, TextStyle.NORMAL);
        row.setChar(0, '漢', TextStyle.NORMAL);
        long image = TextStyle.encodeTerminalBitmap(7, 0, 0);
        row.setChar(1, '+', image);
        assertText(row, " +    ");
        assertEquals(TextStyle.NORMAL, row.getStyle(0));
        assertOnlyBitmap(row, 1, image);
        assertSingleWidthColumns(row);
    }

    public void testSecondHalfKeepsOldTextStyleAndDropsWholeWideCombiningCluster() {
        for (int wide : WIDE) {
            assertEquals(2, WcWidth.width(wide));
            for (int first : new int[]{0, COLUMNS - 2}) {
                TerminalRow row = new TerminalRow(COLUMNS, TextStyle.NORMAL);
                row.setChar(first, wide, OLD_TEXT);
                row.setChar(first, 0x0308, OLD_TEXT);
                long image = TextStyle.encodeTerminalBitmap(65535, 123, 456);
                row.setChar(first + 1, '+', image);
                assertEquals(' ', row.mText[row.findStartOfColumn(first)]);
                assertEquals('+', row.mText[row.findStartOfColumn(first + 1)]);
                assertEquals(OLD_TEXT, row.getStyle(first));
                assertOnlyBitmap(row, first + 1, image);
                assertSingleWidthColumns(row);
            }
        }
    }

    public void testFirstHalfGeneratedBlankKeepsTextOrNormalizesStaleBitmap() {
        for (int wide : WIDE) {
            for (long tailStyle : new long[]{OTHER_TEXT, TextStyle.encodeTerminalBitmap(11, 8, 9)}) {
                TerminalRow row = new TerminalRow(COLUMNS, TextStyle.NORMAL);
                row.setChar(1, 'x', tailStyle);
                row.setChar(0, wide, OLD_TEXT); // The covered cell retains its previous style.
                long image = TextStyle.encodeTerminalBitmap(7, 0, 0);
                row.setChar(0, '+', image);
                assertText(row, "+     ");
                assertEquals(TextStyle.isTerminalBitmap(tailStyle) ? TextStyle.NORMAL : tailStyle, row.getStyle(1));
                assertOnlyBitmap(row, 0, image);
                assertSingleWidthColumns(row);
            }
        }
    }

    public void testMultipleSlicesStartingAtSecondHalfHaveOnlyTheirIntendedCoordinates() {
        for (int wide : WIDE) {
            TerminalRow row = new TerminalRow(COLUMNS, TextStyle.NORMAL);
            row.setChar(0, wide, OLD_TEXT);
            row.setChar(2, wide, OTHER_TEXT);
            row.setChar(1, '+', TextStyle.encodeTerminalBitmap(7, 0, 0));
            row.setChar(2, '+', TextStyle.encodeTerminalBitmap(7, 1, 0));
            assertText(row, " ++   ");
            assertEquals(OLD_TEXT, row.getStyle(0));
            assertEquals(TextStyle.NORMAL, row.getStyle(3));
            assertTrue(row.mHasTerminalBitmap);
            for (int col = 0; col < COLUMNS; col++) {
                if (col == 1 || col == 2) {
                    assertEquals(TextStyle.encodeTerminalBitmap(7, col - 1, 0), row.getStyle(col));
                } else {
                    assertFalse(TextStyle.isTerminalBitmap(row.getStyle(col)));
                }
            }
            assertSingleWidthColumns(row);
        }
    }

    /** Production images use '+', but the other shared recursive cleanup must also stay text-only. */
    public void testBitmapStyleWideOverlapNormalizesBothIncidentalSides() {
        for (int wide : WIDE) {
            for (long tailStyle : new long[]{OTHER_TEXT, TextStyle.encodeTerminalBitmap(11, 8, 9)}) {
                TerminalRow row = new TerminalRow(COLUMNS, TextStyle.NORMAL);
                row.setChar(0, wide, OLD_TEXT);
                row.setChar(3, 'x', tailStyle);
                row.setChar(2, wide, OLD_TEXT);
                long image = TextStyle.encodeTerminalBitmap(7, 5, 6);
                row.setChar(1, wide, image);
                assertText(row, " " + new String(Character.toChars(wide)) + "   ");
                assertEquals(OLD_TEXT, row.getStyle(0));
                assertEquals(OLD_TEXT, row.getStyle(2));
                assertEquals(TextStyle.isTerminalBitmap(tailStyle) ? TextStyle.NORMAL : tailStyle, row.getStyle(3));
                assertOnlyBitmap(row, 1, image);
                assertEquals(1, row.findStartOfColumn(1));
                assertEquals(1, row.findStartOfColumn(2));
                assertEquals(1 + Character.charCount(wide), row.findStartOfColumn(3));
            }
        }
    }

    public void testCopyIntervalUsesTheSameSafeWideCleanup() {
        TerminalRow source = new TerminalRow(COLUMNS, TextStyle.NORMAL);
        long image = TextStyle.encodeTerminalBitmap(7, 5, 6);
        source.setChar(0, '+', image);
        TerminalRow destination = new TerminalRow(COLUMNS, TextStyle.NORMAL);
        destination.setChar(0, '漢', OLD_TEXT);
        destination.copyInterval(source, 0, 1, 1);
        assertText(destination, " +    ");
        assertEquals(OLD_TEXT, destination.getStyle(0));
        assertOnlyBitmap(destination, 1, image);
        assertSingleWidthColumns(destination);
    }

    public void testOrdinaryTextWideCleanupStyleSemanticsAreUnchanged() {
        for (int wide : WIDE) {
            TerminalRow secondHalf = new TerminalRow(COLUMNS, TextStyle.NORMAL);
            secondHalf.setChar(0, wide, OLD_TEXT);
            secondHalf.setChar(1, 'x', NEW_TEXT);
            assertText(secondHalf, " x    ");
            assertEquals(NEW_TEXT, secondHalf.getStyle(0)); // Existing recursive clear uses incoming text style.
            assertEquals(NEW_TEXT, secondHalf.getStyle(1));

            TerminalRow firstHalf = new TerminalRow(COLUMNS, TextStyle.NORMAL);
            firstHalf.setChar(1, 'y', OTHER_TEXT);
            firstHalf.setChar(0, wide, OLD_TEXT);
            firstHalf.setChar(0, 'x', NEW_TEXT);
            assertText(firstHalf, "x     ");
            assertEquals(NEW_TEXT, firstHalf.getStyle(0));
            assertEquals(OTHER_TEXT, firstHalf.getStyle(1)); // Generated trailing blank keeps its old style.

            TerminalRow overlap = new TerminalRow(COLUMNS, TextStyle.NORMAL);
            overlap.setChar(0, wide, OLD_TEXT);
            overlap.setChar(3, 'y', OTHER_TEXT);
            overlap.setChar(2, wide, OLD_TEXT);
            overlap.setChar(1, wide, NEW_TEXT);
            assertText(overlap, " " + new String(Character.toChars(wide)) + "   ");
            assertEquals(NEW_TEXT, overlap.getStyle(0));
            assertEquals(NEW_TEXT, overlap.getStyle(1));
            assertEquals(NEW_TEXT, overlap.getStyle(2));
            assertEquals(OTHER_TEXT, overlap.getStyle(3));
            assertFalse(overlap.mHasTerminalBitmap);
        }
    }
}
