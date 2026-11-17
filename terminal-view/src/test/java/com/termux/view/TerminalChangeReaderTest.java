package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/** What {@link TerminalChangeReader} says as a shell session goes on. */
public class TerminalChangeReaderTest {

    private static final int COLUMNS = 20, ROWS = 5, TRANSCRIPT_ROWS = 300;

    private static final class NullOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) { }
        @Override public void titleChanged(String oldTitle, String newTitle) { }
        @Override public void onCopyTextToClipboard(String text) { }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { }
        @Override public void onColorsChanged() { }
    }

    private TerminalEmulator mEmulator;
    private TerminalChangeReader mReader;
    private long mTime;

    @Before
    public void setUp() {
        mEmulator = new TerminalEmulator(new NullOutput(), COLUMNS, ROWS, 10, 20, TRANSCRIPT_ROWS, null);
        mReader = new TerminalChangeReader("space", "blank", "Earlier output skipped.");
        mTime = 100_000;
        output("$ ");
        assertNull("The first check only takes the screen", check());
    }

    /** The program writes to the terminal. */
    private void output(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        mEmulator.append(bytes, bytes.length);
    }

    /** A key is pressed: a character, or -1 for other keys like arrows and backspace. */
    private void key(int codePoint, boolean hardware) {
        mReader.onUserInput(mTime, codePoint, codePoint < 0, hardware);
    }

    private void type(String text, boolean hardware) {
        for (int i = 0; i < text.length(); i++) key(text.charAt(i), hardware);
    }

    /** What TerminalView does when the screen updates, then a check. */
    private TerminalChangeReader.Speech check() {
        mReader.onScrolled(mEmulator.getScrollCounter());
        mEmulator.clearScrollCounter();
        return mReader.check(mEmulator, mTime);
    }

    private void assertSays(String expected) {
        TerminalChangeReader.Speech speech = check();
        assertNotNull("Expected \"" + expected + "\" but nothing was said", speech);
        assertEquals(expected, speech.text);
    }

    private void assertQuiet() {
        TerminalChangeReader.Speech speech = check();
        assertNull("Expected nothing but got " + speech, speech);
    }

    @Test
    public void onScreenKeyboardEchoIsLeftToTheScreenReader() {
        type("ls", false);
        output("ls");
        assertQuiet();
    }

    @Test
    public void physicalKeyboardEchoIsReadAsCharacters() {
        type("l", true);
        output("l");
        TerminalChangeReader.Speech speech = check();
        assertNotNull(speech);
        assertEquals("l", speech.text);
        assertTrue(speech.character);
    }

    @Test
    public void echoArrivingInPiecesIsNotReadTwice() {
        type("ls", false);
        output("l");
        assertQuiet();
        output("s");
        assertQuiet();
    }

    @Test
    public void typingPastTheEdgeOfTheScreenStaysQuiet() {
        output("echo 0123456789"); // 17 of 20 columns
        check();
        type("abcd", false);
        output("abcd"); // Wraps onto the next row.
        assertQuiet();
    }

    @Test
    public void typingPastTheEdgeOnAPhysicalKeyboardReadsJustTheCharacter() {
        output("echo 0123456789abc"); // All 20 columns
        check();
        type("d", true);
        output("d");
        assertSays("d");
    }

    @Test
    public void commandOutputAndNextPromptAreRead() {
        type("ls", false);
        output("ls");
        check();
        key(-1, true); // Enter
        output("\r\nalpha\r\nbeta\r\n$ ");
        assertSays("alpha\nbeta\n$");
    }

    @Test
    public void outputThatScrollsPastIsStillRead() {
        key(-1, true);
        StringBuilder text = new StringBuilder("\r\n");
        for (int i = 1; i <= 12; i++) text.append("line ").append(i).append("\r\n");
        output(text.append("$ ").toString());
        StringBuilder expected = new StringBuilder();
        for (int i = 1; i <= 12; i++) expected.append("line ").append(i).append('\n');
        assertSays(expected.append("$").toString());
    }

    @Test
    public void outputArrivingLaterWithoutAKeyIsRead() {
        mTime += 60_000;
        output("\r\nPING reply\r\n");
        assertSays("PING reply");
    }

    @Test
    public void wrappedLinesAreReadAsOne() {
        key(-1, true);
        output("\r\n0123456789abcdefghijKLMNO\r\n$ ");
        assertSays("0123456789abcdefghijKLMNO\n$");
    }

    @Test
    public void backspaceReadsTheDeletedCharacter() {
        output("ls");
        check();
        key(-1, true);
        output("\b \b");
        TerminalChangeReader.Speech speech = check();
        assertNotNull(speech);
        assertEquals("s", speech.text);
        assertTrue(speech.character);
    }

    @Test
    public void historyEntryIsReadWithoutThePrompt() {
        key(-1, true); // Up arrow
        output("cat notes.txt");
        assertSays("cat notes.txt");
    }

    @Test
    public void historyEntryReplacingAnotherReadsTheNewPart() {
        output("ls -l");
        check();
        key(-1, true);
        output("\r$ cat foo\033[K");
        assertSays("cat foo");
    }

    @Test
    public void leftArrowReadsTheCharacterUnderTheCursor() {
        output("cat");
        check();
        key(-1, true);
        output("\b");
        TerminalChangeReader.Speech speech = check();
        assertNotNull(speech);
        assertEquals("t", speech.text);
        assertTrue(speech.character);
    }

    @Test
    public void cursorPastTheEndReadsBlank() {
        output("cat\b");
        check();
        key(-1, true);
        output("\033[C"); // Right
        assertSays("blank");
    }

    @Test
    public void spaceInsideTheTextIsSaidAsSpace() {
        output("ls -l\b\b");
        check();
        key(-1, true);
        output("\b");
        assertSays("space");
    }

    @Test
    public void spaceTypedOnPhysicalKeyboardAtTheEndIsSaid() {
        output("ls");
        check();
        type(" ", true);
        output(" ");
        assertSays("space");
    }

    @Test
    public void hiddenPasswordIsNeverRead() {
        output("\r\nPassword: ");
        check();
        type("secret", true);
        assertQuiet();
        key(-1, true); // Enter
        output("\r\nWelcome\r\n$ ");
        assertSays("Welcome\n$");
    }

    @Test
    public void textAddedToALineOnItsOwnIsRead() {
        output("\r\nReading lists... ");
        check();
        mTime += 60_000;
        output("Done");
        assertSays("Done");
    }

    @Test
    public void progressRewrittenInPlaceIsReadNowAndThen() {
        output("\r\n");
        check();
        mTime += 60_000;
        output("\rProgress 10%");
        assertSays("Progress 10%");
        mTime += 500;
        output("\rProgress 20%");
        assertSays("Progress 20%");
        mTime += 500;
        output("\rProgress 30%");
        assertQuiet();
        mTime += TerminalChangeReader.REWRITE_INTERVAL_MS;
        output("\rProgress 90%");
        assertSays("Progress 90%");
    }

    @Test
    public void fullScreenProgramIsReadWhenItStartsThenOnlyAfterKeys() {
        key(-1, true);
        output("\033[?1049h\033[H\033[2JTop of file\r\nSecond line");
        assertSays("Top of file\nSecond line");

        // Redraws on its own, like htop, are not read.
        mTime += 60_000;
        output("\033[1;1HClock 12:01");
        assertQuiet();

        // But a key that changes the screen is.
        key('j', true);
        output("\033[3;1HNext page");
        assertSays("Next page");

        // Moving the cursor to another line reads that line.
        key(-1, true);
        output("\033[1;1H");
        assertSays("Clock 12:01");

        // Leaving reads the line with the cursor.
        key(-1, true);
        output("\033[?1049l");
        assertSays("$");
    }

    @Test
    public void floodOfOutputKeepsTheNewest() {
        mEmulator = new TerminalEmulator(new NullOutput(), 80, ROWS, 10, 20, TRANSCRIPT_ROWS, null);
        check();
        key(-1, true);
        StringBuilder text = new StringBuilder("\r\n");
        for (int i = 0; i < 150; i++) text.append(String.format("%03d", i)).append(" the quick brown fox jumps over the lazy dog\r\n");
        output(text.append("$ ").toString());
        TerminalChangeReader.Speech speech = check();
        assertNotNull(speech);
        assertTrue(speech.text.startsWith("Earlier output skipped.\n"));
        assertTrue(speech.text.endsWith("149 the quick brown fox jumps over the lazy dog\n$"));
        assertTrue(speech.text.length() <= TerminalChangeReader.MAX_CHARS + 40);
        assertFalse(speech.text.contains("000 the quick"));
    }

    @Test
    public void newEmulatorIsTakenWithoutReading() {
        mEmulator = new TerminalEmulator(new NullOutput(), COLUMNS, ROWS, 10, 20, TRANSCRIPT_ROWS, null);
        output("other session $ ");
        assertQuiet();
    }

    @Test
    public void clearScreenReadsThePrompt() {
        output("\r\nold output\r\n$ ");
        check();
        key(-1, true); // Ctrl+L
        output("\033[H\033[2J$ ");
        assertSays("$");
    }
}
