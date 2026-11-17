package com.termux.view;

import androidx.annotation.Nullable;

import java.text.BreakIterator;

/**
 * Splits a line of terminal text into the pieces a screen reader moves through when reading by
 * character or by word. Plain Java, so it can be tested without a device.
 *
 * <p>A character is what a user sees as one character, so an accented letter or an emoji made of
 * several code points is one step. A word is a run of text between spaces, so a path, an option
 * like {@code --help} or an address like {@code 192.168.1.1} is one word, with its punctuation,
 * as on a Linux console screen reader. Reading by character reaches every punctuation mark.
 */
final class LineTextSegments {

    private LineTextSegments() { }

    /**
     * @return the start and end of the next character or word at or after {@code offset}, or null
     * at the end of the text
     */
    @Nullable
    static int[] following(String text, int offset, boolean word) {
        int start = Math.max(0, offset);
        if (word) {
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        }
        if (start >= text.length()) return null;

        int end;
        if (word) {
            end = start;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
        } else {
            BreakIterator characters = BreakIterator.getCharacterInstance();
            characters.setText(text);
            end = characters.following(start);
            if (end == BreakIterator.DONE) end = text.length();
        }
        return new int[] {start, end};
    }

    /**
     * @return the start and end of the character or word ending at or before {@code offset}, or null
     * at the start of the text
     */
    @Nullable
    static int[] preceding(String text, int offset, boolean word) {
        int end = Math.min(text.length(), offset);
        if (word) {
            while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        }
        if (end <= 0) return null;

        int start;
        if (word) {
            start = end;
            while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) start--;
        } else {
            BreakIterator characters = BreakIterator.getCharacterInstance();
            characters.setText(text);
            start = characters.preceding(end);
            if (start == BreakIterator.DONE) start = 0;
        }
        return new int[] {start, end};
    }

}
