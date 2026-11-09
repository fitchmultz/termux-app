package com.termux.terminal;

import junit.framework.TestCase;

/** Deterministic work accounting, not a machine-dependent benchmark or a transcript quota. */
public class WordLookupTest extends TestCase {
    private static final class CountingBuffer extends TerminalBuffer {
        long selectedRows;

        CountingBuffer(int columns, int totalRows, int screenRows) {
            super(columns, totalRows, screenRows);
        }

        @Override public String getSelectedText(int x1, int y1, int x2, int y2,
                                               boolean joinBackLines, boolean joinFullLines) {
            selectedRows += Math.max(0, Math.min(y2, mScreenRows - 1)
                - Math.max(y1, -getActiveTranscriptRows()) + 1);
            return super.getSelectedText(x1, y1, x2, y2, joinBackLines, joinFullLines);
        }
    }

    public void testWrappedTranscriptWordLookupScansEachBoundaryOnlyOnce() {
        int columns = 80, screenRows = 50, historyRows = 2000;
        CountingBuffer buffer = new CountingBuffer(columns, historyRows + screenRows, screenRows);
        for (int row = 0; row < screenRows; row++) {
            buffer.blockSet(0, row, columns, 1, 'x', TextStyle.NORMAL);
            buffer.setLineWrap(row);
        }
        for (int row = 0; row < historyRows; row++) {
            buffer.scrollDownOneLine(0, screenRows, TextStyle.NORMAL);
            buffer.blockSet(0, screenRows - 1, columns, 1, 'x', TextStyle.NORMAL);
            buffer.setLineWrap(screenRows - 1);
        }
        assertEquals(historyRows, buffer.getActiveTranscriptRows());
        // Visible tap exercises upward expansion; a scrolled-back tap exercises downward.
        for (int tappedRow : new int[]{25, -historyRows + 10}) {
            buffer.selectedRows = 0;
            String word = buffer.getWordAtLocation(40, tappedRow);
            assertEquals((historyRows + screenRows) * columns, word.length());
            // At most two rows per boundary, then one complete final serialization.
            assertTrue("Lookup rescanned " + buffer.selectedRows + " rows",
                buffer.selectedRows <= 3L * buffer.getActiveRows());
        }
    }
}
