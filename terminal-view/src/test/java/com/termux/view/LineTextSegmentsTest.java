package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Reading a line by character and by word, forward and back, as a screen reader steps through it. */
public class LineTextSegmentsTest {

    /** Every piece, stepping forward from the start the way the screen reader does. */
    private static List<String> forward(String text, boolean word) {
        List<String> pieces = new ArrayList<>();
        int position = 0;
        int[] range;
        while ((range = LineTextSegments.following(text, position, word)) != null) {
            pieces.add(text.substring(range[0], range[1]));
            position = range[1];
        }
        return pieces;
    }

    /** Every piece, stepping back from the end. */
    private static List<String> backward(String text, boolean word) {
        List<String> pieces = new ArrayList<>();
        int position = text.length();
        int[] range;
        while ((range = LineTextSegments.preceding(text, position, word)) != null) {
            pieces.add(0, text.substring(range[0], range[1]));
            position = range[0];
        }
        return pieces;
    }

    private static List<String> list(String... pieces) {
        List<String> list = new ArrayList<>();
        for (String piece : pieces) list.add(piece);
        return list;
    }

    @Test
    public void charactersIncludeEveryPunctuationMarkAndSpace() {
        List<String> expected = list("l", "s", " ", "-", "l", "a", " ", "~", "/", ".", "s", "s", "h", ";");
        assertEquals(expected, forward("ls -la ~/.ssh;", false));
        assertEquals(expected, backward("ls -la ~/.ssh;", false));
    }

    @Test
    public void wordsAreSplitAtSpacesAndKeepTheirPunctuation() {
        List<String> expected = list("$", "ssh", "-p", "2222", "root@192.168.1.10:/etc/fstab");
        String line = "$ ssh  -p 2222 root@192.168.1.10:/etc/fstab";
        assertEquals(expected, forward(line, true));
        assertEquals(expected, backward(line, true));
    }

    @Test
    public void leadingAndTrailingSpacesAreSkippedByWord() {
        assertEquals(list("drwxr-xr-x", "home"), forward("   drwxr-xr-x   home   ", true));
        assertEquals(list("drwxr-xr-x", "home"), backward("   drwxr-xr-x   home   ", true));
    }

    @Test
    public void accentsAndEmojiAreOneCharacter() {
        String text = "é👍!"; // e with a combining acute accent, thumbs up, !
        assertEquals(list("é", "👍", "!"), forward(text, false));
        assertEquals(list("é", "👍", "!"), backward(text, false));
    }

    @Test
    public void endsOfTheLineStopTheStep() {
        assertNull(LineTextSegments.following("abc", 3, false));
        assertNull(LineTextSegments.preceding("abc", 0, false));
        assertNull(LineTextSegments.following("abc   ", 3, true));
        assertNull(LineTextSegments.preceding("   abc", 3, true));
        assertNull(LineTextSegments.following("", 0, false));
    }

    @Test
    public void positionPastTheEndOfAShortenedLineIsSafe() {
        int[] range = LineTextSegments.preceding("ab", 10, false);
        assertEquals(1, range[0]);
        assertEquals(2, range[1]);
        assertNull(LineTextSegments.following("ab", 10, true));
    }

    @Test
    public void changingDirectionRereadsThePieceJustRead() {
        // As in TextView: after reading "b" forward, back reads "b" again.
        String text = "abc";
        int[] range = LineTextSegments.following(text, 0, false); // a
        range = LineTextSegments.following(text, range[1], false); // b
        int position = range[1];
        range = LineTextSegments.preceding(text, position, false);
        assertEquals("b", text.substring(range[0], range[1]));
    }
}
