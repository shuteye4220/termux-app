package com.termux.view;

import androidx.annotation.Nullable;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out what a screen reader should say when the terminal screen changes, the way Speakup or
 * Fenrir do on a Linux console. Plain Java, so it can be tested without a device.
 *
 * <p>Compares the screen with a {@link Snapshot} taken at the last check:
 * <ul>
 * <li>New and changed lines are read. Rows that scrolled up are matched to where they went.</li>
 * <li>When only the line with the cursor changed, only what was inserted or deleted is read, so
 * editing a command reads the edit, and up arrow reads the history entry. The echo of typing on
 * the on-screen keyboard is skipped, as the screen reader already speaks those keys.</li>
 * <li>When nothing changed after a key press but the cursor moved, the new line or the character
 * under the cursor is read.</li>
 * <li>In full-screen programs (the alternate screen) only changes soon after a key press are
 * read, so programs that redraw on their own, like htop, stay quiet.</li>
 * </ul>
 */
final class TerminalChangeReader {

    /** Changes this soon after a key press are treated as caused by it. */
    static final long INPUT_WINDOW_MS = 1500;
    /** A line rewritten in place on its own, like a progress bar, is read at most this often. */
    static final long REWRITE_INTERVAL_MS = 3000;
    /** Longest text to read. When more output than this arrives at once, the oldest is dropped. */
    static final int MAX_CHARS = 4000;
    /** How far back into the scrollback to look for output that scrolled past in one burst. */
    static final int MAX_SCAN_ROWS = 200;
    /** Most typed characters remembered while waiting for their echo. */
    static final int MAX_TYPED_CHARS = 1024;

    /** The screen as it was at a check, to compare the next check against. */
    static final class Snapshot {
        final TerminalEmulator emulator;
        final int columns;
        final String[] rows;
        final boolean[] wraps;
        final int cursorRow, cursorCol;
        final boolean alternateBuffer;

        Snapshot(TerminalEmulator emulator) {
            this.emulator = emulator;
            TerminalBuffer screen = emulator.getScreen();
            columns = emulator.mColumns;
            int rowCount = emulator.mRows;
            rows = new String[rowCount];
            wraps = new boolean[rowCount];
            for (int row = 0; row < rowCount; row++) {
                rows[row] = rowText(screen, row, columns);
                wraps[row] = screen.getLineWrap(row);
            }
            cursorRow = emulator.getCursorRow();
            cursorCol = emulator.getCursorCol();
            alternateBuffer = emulator.isAlternateBufferActive();
        }
    }

    /** What to say. */
    static final class Speech {
        final String text;
        /** Whether the text is a single character, to be said as such rather than as a word or a pause. */
        final boolean character;

        Speech(String text, boolean character) {
            this.text = text;
            this.character = character;
        }

        @Override
        public String toString() {
            return character ? "'" + text + "'" : text;
        }
    }

    private final String mSpace, mBlank, mOutputTrimmed;

    @Nullable private Snapshot mSnapshot;
    /** Rows scrolled since {@link #mSnapshot} was taken. */
    private int mPendingScroll;
    private long mLastInputTime = -INPUT_WINDOW_MS;
    /** Printable characters typed whose echo has not been seen yet. */
    private final StringBuilder mTyped = new StringBuilder();
    /** Whether any of {@link #mTyped} came from a physical keyboard, which screen readers do not echo. */
    private boolean mTypedOnHardware;
    private long mLastRewriteTime = -REWRITE_INTERVAL_MS;

    /**
     * @param space said for a space under the cursor or typed
     * @param blank said for an empty line, or the cursor past the end of the text
     * @param outputTrimmed said before output that was cut short
     */
    TerminalChangeReader(String space, String blank, String outputTrimmed) {
        mSpace = space;
        mBlank = blank;
        mOutputTrimmed = outputTrimmed;
    }

    /** Forget the screen, so the next check takes it as it is without reading it. */
    void reset() {
        mSnapshot = null;
        mPendingScroll = 0;
        mTyped.setLength(0);
        mTypedOnHardware = false;
    }

    /** The screen scrolled up by this many rows. */
    void onScrolled(int rows) {
        mPendingScroll += rows;
    }

    /**
     * A key was pressed or a character typed.
     *
     * @param codePoint the character typed, or -1 for keys that are not characters
     * @param modified whether Ctrl or Alt was held, so the character was not typed as text
     * @param hardware whether it came from a physical keyboard
     */
    void onUserInput(long time, int codePoint, boolean modified, boolean hardware) {
        mLastInputTime = time;
        if (codePoint < 0x20 || codePoint == 0x7F || modified || mTyped.length() >= MAX_TYPED_CHARS) return;
        mTyped.appendCodePoint(codePoint);
        if (hardware) mTypedOnHardware = true;
    }

    /**
     * Compares the screen with the last check and takes it as the new one.
     *
     * @return what to say, or null to stay quiet
     */
    @Nullable
    Speech check(TerminalEmulator emulator, long time) {
        Snapshot old = mSnapshot;
        Snapshot now = new Snapshot(emulator);
        int scrolled = mPendingScroll;
        String typed = mTyped.toString();
        boolean typedOnHardware = mTypedOnHardware;
        mSnapshot = now;
        mPendingScroll = 0;
        mTyped.setLength(0);
        mTypedOnHardware = false;

        if (old == null || old.emulator != emulator || old.columns != now.columns || old.rows.length != now.rows.length) {
            return null;
        }
        return describe(emulator.getScreen(), old, now, scrolled, typed, typedOnHardware, time);
    }

    @Nullable
    private Speech describe(TerminalBuffer screen, Snapshot old, Snapshot now, int scrolled,
                            String typed, boolean typedOnHardware, long time) {
        boolean afterInput = time - mLastInputTime <= INPUT_WINDOW_MS;
        int rows = now.rows.length;
        int columns = now.columns;

        if (now.alternateBuffer != old.alternateBuffer) {
            // Starting a full-screen program reads its first screen. Leaving one reads the prompt.
            if (!now.alternateBuffer) return lineAt(screen, now.cursorRow, rows, columns);
            List<Integer> all = new ArrayList<>(rows);
            for (int row = 0; row < rows; row++) all.add(row);
            return joinRows(screen, now, all);
        }
        if (now.alternateBuffer) {
            if (!afterInput) return null;
            // Full-screen programs redraw in place, and the scroll count is not reliable there.
            scrolled = 0;
        }

        // Old row r is now at row r - scrolled, maybe up in the scrollback after a burst of
        // output. Rows from rows - scrolled on are new.
        int firstRow = Math.max(-scrolled, -Math.min(screen.getActiveTranscriptRows(), MAX_SCAN_ROWS));
        // The line with the cursor, with any rows it wraps across, so typing past the edge of
        // the screen is still an edit of one line.
        int lineStart = now.cursorRow, lineEnd = now.cursorRow;
        while (lineStart > firstRow && wraps(screen, now, lineStart - 1)) lineStart--;
        while (lineEnd < rows - 1 && now.wraps[lineEnd]) lineEnd++;

        List<Integer> changed = new ArrayList<>();
        boolean otherRowsChanged = false, cursorLineChanged = false;
        for (int row = firstRow; row < rows; row++) {
            String newText = row >= 0 ? now.rows[row] : rowText(screen, row, columns);
            int oldRow = row + scrolled;
            String oldText = oldRow >= 0 && oldRow < rows ? old.rows[oldRow] : null;
            if (oldText == null ? isBlank(newText) : oldText.equals(newText)) continue;
            changed.add(row);
            if (row >= lineStart && row <= lineEnd) cursorLineChanged = true;
            else if (!isBlank(newText)) otherRowsChanged = true;
        }

        // New output, or a screen redrawn by a key press.
        if (otherRowsChanged) return joinRows(screen, now, changed);

        if (cursorLineChanged) {
            StringBuilder oldText = new StringBuilder(), newText = new StringBuilder();
            for (int row = lineStart; row <= lineEnd; row++) {
                newText.append(row >= 0 ? now.rows[row] : rowText(screen, row, columns));
                int oldRow = row + scrolled;
                if (oldRow >= 0 && oldRow < rows) oldText.append(old.rows[oldRow]);
            }
            return describeLineEdit(oldText.toString(), newText.toString(), afterInput, typed, typedOnHardware, time);
        }

        // Nothing changed, but maybe the cursor moved, e.g. with arrow keys.
        if (!afterInput) return null;
        boolean cursorRowMoved = now.cursorRow != old.cursorRow - scrolled;
        boolean cursorMoved = cursorRowMoved || now.cursorCol != old.cursorCol;
        if (!typed.isEmpty()) {
            // A space typed at the end of a line moves the cursor without changing the text.
            if (cursorMoved && isBlank(typed)) return typedOnHardware ? speakable(typed) : null;
            // Otherwise the echo may still be on its way, as over a slow SSH connection. Typed
            // text is only ever read once it shows on screen, so hidden passwords stay hidden.
            keepTyped(typed, typedOnHardware);
            return null;
        }
        if (cursorRowMoved) return lineAt(screen, now.cursorRow, rows, columns);
        if (cursorMoved) return characterAtCursor(screen, now);
        return null;
    }

    /** The line with the cursor changed, and nothing else did. */
    @Nullable
    private Speech describeLineEdit(String oldText, String newText, boolean afterInput, String typed,
                                    boolean typedOnHardware, long time) {
        int max = Math.min(oldText.length(), newText.length());
        int prefix = 0;
        while (prefix < max && oldText.charAt(prefix) == newText.charAt(prefix)) prefix++;
        if (prefix > 0 && Character.isHighSurrogate(newText.charAt(prefix - 1))) prefix--;
        int suffix = 0;
        while (suffix < max - prefix
            && oldText.charAt(oldText.length() - 1 - suffix) == newText.charAt(newText.length() - 1 - suffix)) suffix++;
        if (suffix > 0 && Character.isLowSurrogate(newText.charAt(newText.length() - suffix))) suffix--;
        String inserted = newText.substring(prefix, newText.length() - suffix);
        String deleted = oldText.substring(prefix, oldText.length() - suffix);

        if (!inserted.isEmpty()) {
            // Spaces at the end of a line are not kept, so "$ " then "$ ls" inserts " ls".
            String echo = typed.startsWith(inserted) ? inserted : stripLeadingSpaces(inserted);
            if (!typed.isEmpty() && !echo.isEmpty() && typed.startsWith(echo)) {
                // Echo of typing, maybe only part of it so far.
                if (afterInput) keepTyped(typed.substring(echo.length()), typedOnHardware);
                return typedOnHardware ? speakable(inserted) : null;
            }
            if (!afterInput && !deleted.isEmpty()) {
                // Rewritten in place on its own, like a progress bar. Read it now and then.
                if (time - mLastRewriteTime < REWRITE_INTERVAL_MS) return null;
                mLastRewriteTime = time;
                return isBlank(newText) ? null : new Speech(newText.trim(), false);
            }
            // Added to, like "Done" after "Reading package lists...", or replaced after a key,
            // like a history entry or a tab completion.
            return speakable(inserted);
        }
        return afterInput && !deleted.isEmpty() ? speakable(deleted) : null;
    }

    /** Puts back typed text whose echo has not shown up yet, ahead of anything typed since. */
    private void keepTyped(String typed, boolean typedOnHardware) {
        if (typed.isEmpty() || mTyped.length() + typed.length() > MAX_TYPED_CHARS) return;
        mTyped.insert(0, typed);
        mTypedOnHardware |= typedOnHardware;
    }

    /** The changed rows as text, a line per line, with wrapped rows joined. */
    @Nullable
    private Speech joinRows(TerminalBuffer screen, Snapshot now, List<Integer> changedRows) {
        StringBuilder text = new StringBuilder();
        StringBuilder line = new StringBuilder();
        int previous = Integer.MIN_VALUE;
        for (int row : changedRows) {
            boolean continuation = previous == row - 1 && wraps(screen, now, previous);
            if (!continuation) {
                appendLine(text, line);
                line.setLength(0);
            }
            line.append(row >= 0 ? now.rows[row] : rowText(screen, row, now.columns));
            previous = row;
        }
        appendLine(text, line);
        if (text.length() == 0) return null;
        if (text.length() <= MAX_CHARS) return new Speech(text.toString(), false);

        // Keep the newest output, starting at the beginning of a line.
        int cut = text.length() - MAX_CHARS;
        int newline = text.indexOf("\n", cut);
        if (newline >= 0 && newline < text.length() - 1) cut = newline + 1;
        return new Speech(mOutputTrimmed + "\n" + text.substring(cut), false);
    }

    private static void appendLine(StringBuilder text, CharSequence line) {
        if (isBlank(line)) return;
        if (text.length() > 0) text.append('\n');
        text.append(line);
    }

    private static boolean wraps(TerminalBuffer screen, Snapshot now, int row) {
        return row >= 0 ? now.wraps[row] : screen.getLineWrap(row);
    }

    /** The whole line containing a row, with the rows it wrapped across joined. */
    private Speech lineAt(TerminalBuffer screen, int row, int rows, int columns) {
        int firstRow = -Math.min(screen.getActiveTranscriptRows(), MAX_SCAN_ROWS);
        int start = row;
        while (start > firstRow && screen.getLineWrap(start - 1)) start--;
        int end = row;
        while (end < rows - 1 && screen.getLineWrap(end)) end++;
        String text = screen.getSelectedText(0, start, columns, end);
        return new Speech(isBlank(text) ? mBlank : text, false);
    }

    private Speech characterAtCursor(TerminalBuffer screen, Snapshot now) {
        int row = now.cursorRow, column = now.cursorCol;
        String character = screen.getSelectedText(column, row, column, row);
        if (!isBlank(character)) return speakable(character);
        // A space inside the text, or the empty part of the line after it.
        boolean textFollows = !isBlank(screen.getSelectedText(column, row, now.columns, row));
        return new Speech(textFollows ? mSpace : mBlank, false);
    }

    @Nullable
    private Speech speakable(String text) {
        if (isBlank(text)) return text.length() == 1 ? new Speech(mSpace, false) : null;
        String trimmed = text.trim();
        return new Speech(trimmed, trimmed.codePointCount(0, trimmed.length()) == 1);
    }

    private static String stripLeadingSpaces(String text) {
        int start = 0;
        while (start < text.length() && text.charAt(start) == ' ') start++;
        return text.substring(start);
    }

    static String rowText(TerminalBuffer screen, int row, int columns) {
        return screen.getSelectedText(0, row, columns, row);
    }

    static boolean isBlank(@Nullable CharSequence text) {
        if (text == null) return true;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) return false;
        }
        return true;
    }

}
