package com.termux.terminal;

import java.io.UnsupportedEncodingException;

public class UnicodeInputTest extends TerminalTestCase {

	public void testIllFormedUtf8SuccessorByteNotConsumed() throws Exception {
		// The Unicode Standard Version 6.2 – Core Specification (http://www.unicode.org/versions/Unicode6.2.0/ch03.pdf):
		// "If the converter encounters an ill-formed UTF-8 code unit sequence which starts with a valid first byte, but which does not
		// continue with valid successor bytes (see Table 3-7), it must not consume the successor bytes as part of the ill-formed
		// subsequence whenever those successor bytes themselves constitute part of a well-formed UTF-8 code unit subsequence."
		withTerminalSized(5, 5);
		mTerminal.append(new byte[]{(byte) 0b11101111, (byte) 'a'}, 2);
		assertLineIs(0, ((char) TerminalEmulator.UNICODE_REPLACEMENT_CHAR) + "a   ");

		// https://code.google.com/p/chromium/issues/detail?id=212704
		byte[] input = new byte[]{
				(byte) 0x61, (byte) 0xF1,
				(byte) 0x80, (byte) 0x80,
				(byte) 0xe1, (byte) 0x80,
				(byte) 0xc2, (byte) 0x62,
				(byte) 0x80, (byte) 0x63,
				(byte) 0x80, (byte) 0xbf,
				(byte) 0x64
		};
		withTerminalSized(10, 2);
		mTerminal.append(input, input.length);
		assertLinesAre("a\uFFFD\uFFFD\uFFFDb\uFFFDc\uFFFD\uFFFDd", "          ");

		// Surrogate pairs.
		withTerminalSized(5, 2);
		input = new byte[]{
				(byte) 0xed, (byte) 0xa0,
				(byte) 0x80, (byte) 0xed,
				(byte) 0xad, (byte) 0xbf,
				(byte) 0xed, (byte) 0xae,
				(byte) 0x80, (byte) 0xed,
				(byte) 0xbf, (byte) 0xbf
		};
		mTerminal.append(input, input.length);
		assertLinesAre("\uFFFD\uFFFD\uFFFD\uFFFD ", "     ");

		// https://bugzilla.mozilla.org/show_bug.cgi?id=746900: "with this patch 0xe0 0x80 is decoded as two U+FFFDs,
		// but 0xe0 0xa0 is decoded as a single U+FFFD, and this is correct according to the "Best Practices", but IE
		// and Chrome (Version 22.0.1229.94) decode both of them as two U+FFFDs. Opera 12.11 decodes both of them as
		// one U+FFFD".
		withTerminalSized(5, 2);
		input = new byte[]{(byte) 0xe0, (byte) 0xa0, ' '};
		mTerminal.append(input, input.length);
		assertLinesAre("\uFFFD    ", "     ");

		// withTerminalSized(5, 2);
		// input = new byte[]{(byte) 0xe0, (byte) 0x80, 'a'};
		// mTerminal.append(input, input.length);
		// assertLinesAre("\uFFFD\uFFFDa  ", "     ");
	}

	public void testUnassignedCodePoint() throws UnsupportedEncodingException {
		withTerminalSized(3, 3);
		// UTF-8 for U+C2541, an unassigned code point:
		byte[] b = new byte[]{(byte) 0xf3, (byte) 0x82, (byte) 0x95, (byte) 0x81};
		mTerminal.append(b, b.length);
		enterString("Y");
		assertEquals(1, Character.charCount(TerminalEmulator.UNICODE_REPLACEMENT_CHAR));
		assertLineStartsWith(0, TerminalEmulator.UNICODE_REPLACEMENT_CHAR, (int) 'Y', ' ');
	}

	public void testStuff() {
		withTerminalSized(80, 24);
		byte[] b = new byte[]{(byte) 0xf3, (byte) 0x82, (byte) 0x95, (byte) 0x81, (byte) 0x61, (byte) 0x38, (byte) 0xe7, (byte) 0x8f,
				(byte) 0xae, (byte) 0xc2, (byte) 0x9f, (byte) 0xe8, (byte) 0xa0, (byte) 0x9f, (byte) 0xe8, (byte) 0x8c, (byte) 0xa4,
				(byte) 0xed, (byte) 0x93, (byte) 0x89, (byte) 0xef, (byte) 0xbf, (byte) 0xbd, (byte) 0x42, (byte) 0xc2, (byte) 0x9b,
				(byte) 0xe6, (byte) 0x87, (byte) 0x89, (byte) 0x5a};
		mTerminal.append(b, b.length);
	}

	public void testSimpleCombining() throws Exception {
		withTerminalSized(3, 2).enterString(" a\u0302 ").assertLinesAre(" a\u0302 ", "   ");
	}

	public void testCombiningCharacterInFirstColumn() throws Exception {
		withTerminalSized(5, 3).enterString("test\r\nhi\r\n").assertLinesAre("test ", "hi   ", "     ");

		// U+0302 is COMBINING CIRCUMFLEX ACCENT. Test case from mosh (http://mosh.mit.edu/).
		withTerminalSized(5, 5).enterString("test\r\nabc\r\n\u0302\r\ndef\r\n");
		assertLinesAre("test ", "abc  ", " \u0302    ", "def  ", "     ");
	}

	public void testCombiningCharacterInLastColumn() throws Exception {
		withTerminalSized(3, 2).enterString("  a\u0302").assertLinesAre("  a\u0302", "   ");
		withTerminalSized(3, 2).enterString("  à̲").assertLinesAre("  à̲", "   ");
		withTerminalSized(3, 2).enterString("Aà̲F").assertLinesAre("Aà̲F", "   ");
	}

	public void testWideRowWithSupplementaryCombiningCharacters() {
		// Each cell has a G clef and 15 combining tremolos: 32 UTF-16 chars per cell.
		StringBuilder text = new StringBuilder();
		for (int column = 0; column < 1024; column++) {
			text.appendCodePoint(0x1D11E);
			for (int mark = 0; mark < 15; mark++) text.appendCodePoint(0x1D167);
		}
		withTerminalSized(1024, 2).enterString(text.toString());
		assertEquals(text.toString(), mTerminal.getSelectedText(0, 0, 1023, 0));
	}

	public void testWideCharacterInLastColumn() throws Exception {
		withTerminalSized(3, 2).enterString("  枝\u0302").assertLinesAre("   ", "枝\u0302 ");

		withTerminalSized(3, 2).enterString(" 枝").assertLinesAre(" 枝", "   ").assertCursorAt(0, 2);
		enterString("a").assertLinesAre(" 枝", "a  ");
	}

	public void testWideCharacterDeletion() throws Exception {
		// CSI Ps D Cursor Backward Ps Times
		withTerminalSized(3, 2).enterString("枝\033[Da").assertLinesAre(" a ", "   ");
		withTerminalSized(3, 2).enterString("枝\033[2Da").assertLinesAre("a  ", "   ");
		withTerminalSized(3, 2).enterString("枝\033[2D枝").assertLinesAre("枝 ", "   ");
		withTerminalSized(3, 2).enterString("枝\033[1D枝").assertLinesAre(" 枝", "   ");
		withTerminalSized(5, 2).enterString(" 枝 \033[Da").assertLinesAre(" 枝a ", "     ");
		withTerminalSized(5, 2).enterString("a \033[D\u0302").assertLinesAre("a\u0302    ", "     ");
		withTerminalSized(5, 2).enterString("枝 \033[D\u0302").assertLinesAre("枝\u0302   ", "     ");
		enterString("Z").assertLinesAre("枝\u0302Z  ", "     ");
		enterString("\033[D ").assertLinesAre("枝\u0302   ", "     ");
		// Go back two columns, standing at the second half of the wide character:
		enterString("\033[2DU").assertLinesAre(" U   ", "     ");
	}

	public void testWideCharOverwriting() {
		withTerminalSized(3, 2).enterString("abc\033[3D枝").assertLinesAre("枝c", "   ");
		withTerminalSized(6, 2).enterString("枝枝枝\033[Habcde").assertLinesAre("abcde ", "      ").assertCursorAt(0, 5);
		withTerminalSized(6, 2).enterString("a\u0302bcde\033[Hxyz").assertLinesAre("xyzde ", "      ").assertCursorAt(0, 3);
	}

	public void testEmojiVariationSelectorWideningWrapInsertAndReflow() {
		withTerminalSized(5, 2).enterString("a❤️b").assertLinesAre("a❤️b ", "     ").assertCursorAt(0, 4);
		// Widening into the right margin must set pending autowrap so the next char wraps cleanly.
		withTerminalSized(5, 2).enterString("abc❤️d").assertLinesAre("abc❤️", "d    ").assertCursorAt(1, 1);
		// ASCII fast-path keycap base followed by UTF-8 VS16 must also widen and advance the cursor.
		withTerminalSized(5, 2).enterString("1\uFE0Fb").assertLinesAre("1\uFE0Fb  ", "     ").assertCursorAt(0, 3);
		// Insert mode must shift existing right-hand text by the widened second cell too.
		withTerminalSized(5, 2).enterString("ab\033[G\033[4h❤️").assertLinesAre("❤️ab ", "     ").assertCursorAt(0, 2);
		// Reflow across narrow/wide resizes (fold/unfold) must preserve widened emoji intact.
		withTerminalSized(5, 4).enterString("ab❤️c").resize(2, 4).assertLinesAre("ab", "❤️", "c ", "  ");
		resize(5, 4).assertLinesAre("ab❤️c", "     ", "     ", "     ");
		// Last column without room drops VS16 safely.
		withTerminalSized(3, 2).enterString("ab❤️").assertLinesAre("ab❤", "   ");
		// Autowrap off in the final column must target the final cell (dropping VS16), not widen the preceding cell.
		withTerminalSized(2, 1).enterString("\033[?7l1❤️").assertLinesAre("1❤");
		// DECSLRM right margin narrower than the screen must drop VS16 at mRightMargin - 1 without crossing the margin.
		withTerminalSized(8, 2)
			.enterString("\033[1;6HOUT\033[?69h\033[1;5s\033[1;1Habcd\u2764\uFE0F")
			.assertLinesAre("abcd\u2764OUT", "        ");
		// Cursor movement breaks variation-sequence continuity and must not widen out from under the cursor.
		withTerminalSized(5, 2).enterString("1 \033[D\uFE0F").assertLinesAre("1    ", "     ").assertCursorAt(0, 1);
		// Vertical scrolls (CSI S / CSI T) break variation-sequence continuity and must not widen scrolled-in cells.
		withTerminalSized(4, 2)
			.enterString("\033[2;1H\u2764X\033[1;1H\u2764\033[S\uFE0F")
			.assertLinesAre("\u2764X  ", "    ")
			.assertCursorAt(0, 1);
		withTerminalSized(4, 3)
			.enterString("\033[1;1H\u2764\033[S\uFE0F")
			.assertLinesAre(" \uFE0F   ", "    ", "    ")
			.assertCursorAt(0, 1);
		// A no-op tab at the right margin must preserve pending autowrap and last-cell combining state.
		withTerminalSized(3, 2).enterString("abc\tX").assertLinesAre("abc", "X  ").assertCursorAt(1, 1);
		withTerminalSized(3, 2).enterString("abc\t\u0301").assertLinesAre("abc\u0301", "   ").assertCursorAt(0, 2);
		// Reverse index (ESC M) after an autowrap-off last-column write clears last-cell combining state.
		withTerminalSized(3, 2).enterString("\033[?7l\033[2;1Habc\033M\u0301").assertLinesAre("  \u0301 ", "abc");
		// Scrolls and out-of-region line inserts must keep pending-wrap and last-printed-cell state paired.
		withTerminalSized(3, 2).enterString("abc\033[S\u0301").assertLinesAre("   \u0301", "   ");
		withTerminalSized(5, 5)
			.enterString("\033[1;2r\033[4;1Habcde\033[L\u0301")
			.assertLinesAre("     ", "     ", "     ", "abcde\u0301", "     ")
			.assertCursorAt(3, 4);
		// Malformed unpaired high surrogate (both mid-row and at end-of-row) must become U+FFFD on resize().
		withTerminalSized(4, 2).enterString("abcd");
		TerminalRow malformed = mTerminal.getScreen().allocateFullLineIfNecessary(mTerminal.getScreen().externalToInternalRow(0));
		malformed.mText[0] = '\uD83D';
		malformed.mText[3] = '\uD83D';
		mTerminal.resize(5, 2, INITIAL_CELL_WIDTH_PIXELS, INITIAL_CELL_HEIGHT_PIXELS);
		TerminalRow resizedRow = mTerminal.getScreen().allocateFullLineIfNecessary(mTerminal.getScreen().externalToInternalRow(0));
		assertEquals('\uFFFD', resizedRow.mText[0]);
		assertEquals('b', resizedRow.mText[1]);
		assertEquals('c', resizedRow.mText[2]);
		assertEquals('\uFFFD', resizedRow.mText[3]);
	}

	public void testOverlongUtf8Encoding() throws Exception {
		// U+0020 should be encoded as 0x20, 0xc0 0xa0 is an overlong encoding
		// so should be replaced with the replacement char U+FFFD.
		withTerminalSized(5, 5).mTerminal.append(new byte[]{(byte) 0xc0, (byte) 0xa0, 'Y'}, 3);
		assertLineIs(0, "\uFFFDY   ");
	}

	public void testWideCharacterWithoutWrapping() throws Exception {
		// With wraparound disabled. The behaviour when a wide character is output with cursor in
		// the last column when autowrap is disabled is not obvious, but we expect the wide
		// character to be ignored here.
		withTerminalSized(3, 3).enterString("\033[?7l").enterString("枝枝枝").assertLinesAre("枝 ", "   ", "   ");
		enterString("a枝").assertLinesAre("枝a", "   ", "   ");
	}

}
