package com.termux.view;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.TtsSpan;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen reader support for {@link TerminalView}.
 *
 * <ul>
 * <li>Each line on screen is its own accessibility item, so a screen reader can move through the
 * terminal a line at a time, explore it by touch, and show it a line at a time on a braille
 * display. A line that wrapped across several rows is one item. Blank lines are left out.
 * Scrolling the terminal (the screen reader's scroll actions) pages through the scrollback.</li>
 * <li>New output is spoken as it arrives, as worked out by {@link TerminalChangeReader}. This can
 * be turned off with a custom action on the terminal.</li>
 * </ul>
 *
 * <p>Everything here runs on the main thread, as the emulator is only changed there.
 */
@SuppressWarnings("deprecation") // The obtain() factories are needed for API levels below 30.
final class TerminalAccessibilityHelper extends AccessibilityNodeProvider {

    private static final int INVALID_ID = Integer.MIN_VALUE;
    /** Keeps line ids non-negative, so they never clash with {@link #HOST_VIEW_ID} or {@link #INVALID_ID}. */
    private static final int ID_MASK = 0x3FFFFFFF;

    /** Wait this long after the last screen update before working out what changed. */
    private static final long SETTLE_MS = 100;
    /** But never wait longer than this while output keeps arriving. */
    private static final long MAX_WAIT_MS = 400;

    private static final String PREFS_NAME = "terminal_view_accessibility";
    private static final String PREF_AUTO_READ = "auto_read";

    /** One line of the terminal as an accessibility item: one row, or several rows if it wrapped. */
    private static final class LineItem {
        final int id;
        /** First and last row, as external row numbers of the emulator's screen buffer. */
        final int startRow, endRow;
        final String text;

        LineItem(int id, int startRow, int endRow, String text) {
            this.id = id;
            this.startRow = startRow;
            this.endRow = endRow;
            this.text = text;
        }
    }

    private final TerminalView mView;
    @Nullable private final AccessibilityManager mManager;

    private final ArrayList<LineItem> mItems = new ArrayList<>();
    private boolean mItemsDirty = true;
    private int mItemsTopRow;
    @Nullable private TerminalEmulator mItemsEmulator;

    /**
     * Rows the main screen has scrolled since this helper was made. Added to a row number, it
     * gives an id that stays with the line as output pushes it up the screen, so accessibility
     * focus stays on the line being read.
     */
    private long mScrolledTotal;
    private int mFocusedId = INVALID_ID;
    private int mHoveredId = INVALID_ID;

    private final TerminalChangeReader mChangeReader;
    private boolean mFlushPending;
    private long mFirstPendingTime;

    @Nullable private Boolean mAutoRead;

    private final Rect mTempRect = new Rect();
    private final int[] mTempLocation = new int[2];
    private final Runnable mFlush = this::flush;

    TerminalAccessibilityHelper(TerminalView view) {
        mView = view;
        Context context = view.getContext();
        mManager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        mChangeReader = new TerminalChangeReader(context.getString(R.string.terminal_accessibility_space),
            context.getString(R.string.terminal_accessibility_blank),
            context.getString(R.string.terminal_accessibility_output_trimmed));
    }

    /** Whether an accessibility service is running, so that this helper should do anything. */
    boolean isActive() {
        return mManager != null && mManager.isEnabled();
    }

    // ---------------------------------------------------------------------------------------
    // Hooks called by TerminalView.

    /** Called before the emulator's scroll counter is cleared. */
    void onScreenUpdated(int scrolledRows, boolean alternateBuffer) {
        mItemsDirty = true;
        if (!alternateBuffer) mScrolledTotal += scrolledRows;
        if (!isActive()) {
            mChangeReader.reset();
            return;
        }
        if (!alternateBuffer) mChangeReader.onScrolled(scrolledRows);
        scheduleFlush();
    }

    void onTopRowChanged() {
        mItemsDirty = true;
        if (isActive()) scheduleFlush();
    }

    /** The view now shows another session. Take its screen as it is, without reading it. */
    void onSessionChanged() {
        mItemsDirty = true;
        mChangeReader.reset();
        mFocusedId = INVALID_ID;
        mHoveredId = INVALID_ID;
        if (isActive()) scheduleFlush();
    }

    /** Rows or columns changed, e.g. the soft keyboard opened. Take the new screen without reading it. */
    void onSizeChanged() {
        mItemsDirty = true;
        mChangeReader.reset();
        if (isActive()) scheduleFlush();
    }

    void onDetached() {
        mView.removeCallbacks(mFlush);
        mFlushPending = false;
    }

    /**
     * A key was pressed or a character typed.
     *
     * @param codePoint the character typed, or -1 for keys that are not characters
     * @param modified whether Ctrl or Alt was held, so the character was not typed as text
     */
    void onUserInput(int eventSource, int codePoint, boolean modified) {
        mChangeReader.onUserInput(SystemClock.uptimeMillis(), codePoint, modified,
            eventSource > TerminalView.KEY_EVENT_SOURCE_SOFT_KEYBOARD);
    }

    /** @return whether the event was handled for a line, so the view should not handle it too. */
    boolean dispatchHoverEvent(MotionEvent event) {
        if (!isActive() || !mManager.isTouchExplorationEnabled()) return false;
        switch (event.getAction()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE: {
                int id = idAt(event.getY());
                updateHovered(id);
                return id != INVALID_ID;
            }
            case MotionEvent.ACTION_HOVER_EXIT:
                if (mHoveredId == INVALID_ID) return false;
                updateHovered(INVALID_ID);
                return true;
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------------------------------
    // AccessibilityNodeProvider

    @Override
    public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
        if (virtualViewId == HOST_VIEW_ID) return createHostNode();
        LineItem item = findItem(virtualViewId);
        return item == null ? null : createLineNode(item);
    }

    @Override
    public AccessibilityNodeInfo findFocus(int focus) {
        if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && mFocusedId != INVALID_ID) {
            LineItem item = findItem(mFocusedId);
            if (item != null) return createLineNode(item);
        }
        return null;
    }

    @Override
    public boolean performAction(int virtualViewId, int action, Bundle arguments) {
        if (virtualViewId == HOST_VIEW_ID) return performHostAction(action, arguments);

        LineItem item = findItem(virtualViewId);
        if (item == null) return false;
        switch (action) {
            case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                return setAccessibilityFocus(item.id);
            case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                return clearAccessibilityFocus(item.id);
            case AccessibilityNodeInfo.ACTION_CLICK:
                return mView.onAccessibilityClick(item.startRow);
            case AccessibilityNodeInfo.ACTION_FOCUS:
                // Keyboard input always goes to the terminal itself.
                return mView.requestFocus();
        }
        if (action == R.id.terminal_accessibility_action_copy_line) {
            copyToClipboard(item.text);
            return true;
        }
        if (action == R.id.terminal_accessibility_action_auto_read) {
            toggleAutoRead();
            return true;
        }
        return false;
    }

    private AccessibilityNodeInfo createHostNode() {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(mView);
        mView.onInitializeAccessibilityNodeInfo(info);
        // A scrolling container, so screen readers page through the scrollback when moving past
        // the first or last line.
        info.setClassName(ScrollView.class.getName());
        for (LineItem item : items()) info.addChild(mView, item.id);

        boolean canScrollBack = canScroll(-1), canScrollForward = canScroll(1);
        info.setScrollable(canScrollBack || canScrollForward);
        if (canScrollBack) info.addAction(AccessibilityAction.ACTION_SCROLL_BACKWARD);
        if (canScrollForward) info.addAction(AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(autoReadAction());
        return info;
    }

    private AccessibilityNodeInfo createLineNode(LineItem item) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setSource(mView, item.id);
        node.setParent(mView);
        node.setPackageName(mView.getContext().getPackageName());
        node.setClassName(TextView.class.getName());
        node.setText(item.text);
        node.setEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            node.setScreenReaderFocusable(true);
        } else {
            // Before API 28 this is how screen readers know to stop on each line on its own.
            node.setFocusable(true);
        }

        Rect bounds = mTempRect;
        boundsInView(item, bounds);
        node.setVisibleToUser(!bounds.isEmpty());
        node.setBoundsInParent(bounds);
        mView.getLocationOnScreen(mTempLocation);
        bounds.offset(mTempLocation[0], mTempLocation[1]);
        node.setBoundsInScreen(bounds);

        boolean focused = item.id == mFocusedId;
        node.setAccessibilityFocused(focused);
        node.addAction(focused ? AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            : AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
        node.addAction(AccessibilityAction.ACTION_CLICK);
        node.addAction(new AccessibilityAction(R.id.terminal_accessibility_action_copy_line,
            mView.getContext().getString(R.string.terminal_accessibility_copy_line)));
        // Also on each line, as screen readers move between the lines and not the terminal itself.
        node.addAction(autoReadAction());
        return node;
    }

    private AccessibilityAction autoReadAction() {
        return new AccessibilityAction(R.id.terminal_accessibility_action_auto_read,
            mView.getContext().getString(isAutoRead() ? R.string.terminal_accessibility_auto_read_turn_off
                : R.string.terminal_accessibility_auto_read_turn_on));
    }

    private void toggleAutoRead() {
        boolean autoRead = !isAutoRead();
        setAutoRead(autoRead);
        announce(mView.getContext().getString(autoRead ? R.string.terminal_accessibility_auto_read_on
            : R.string.terminal_accessibility_auto_read_off));
    }

    private boolean performHostAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return scrollPage(-1);
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return scrollPage(1);
        if (action == R.id.terminal_accessibility_action_auto_read) {
            toggleAutoRead();
            return true;
        }
        // This does not come back to the provider, so it cannot loop.
        return mView.performAccessibilityAction(action, arguments);
    }

    private boolean setAccessibilityFocus(int id) {
        if (!isActive() || mFocusedId == id) return false;
        if (mFocusedId != INVALID_ID) clearAccessibilityFocus(mFocusedId);
        mFocusedId = id;
        mView.invalidate();
        sendLineEvent(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
        return true;
    }

    private boolean clearAccessibilityFocus(int id) {
        if (mFocusedId != id) return false;
        mFocusedId = INVALID_ID;
        mView.invalidate();
        sendLineEvent(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
        return true;
    }

    private boolean canScroll(int direction) {
        TerminalEmulator emulator = mView.mEmulator;
        // The alternate screen has no scrollback.
        if (emulator == null || emulator.isAlternateBufferActive()) return false;
        int topRow = mView.getTopRow();
        return direction < 0 ? topRow > -emulator.getScreen().getActiveTranscriptRows() : topRow < 0;
    }

    private boolean scrollPage(int direction) {
        if (!canScroll(direction)) return false;
        TerminalEmulator emulator = mView.mEmulator;
        int page = Math.max(1, emulator.mRows - 1);
        int topRow = mView.getTopRow() + direction * page;
        topRow = Math.max(-emulator.getScreen().getActiveTranscriptRows(), Math.min(0, topRow));
        mView.setTopRow(topRow);
        mView.invalidate();
        sendHostEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED);
        return true;
    }

    private void copyToClipboard(String text) {
        Context context = mView.getContext();
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text));
        announce(context.getString(R.string.terminal_accessibility_line_copied));
    }

    // ---------------------------------------------------------------------------------------
    // Lines on screen

    private List<LineItem> items() {
        TerminalEmulator emulator = mView.mEmulator;
        int topRow = mView.getTopRow();
        if (!mItemsDirty && emulator == mItemsEmulator && topRow == mItemsTopRow) return mItems;

        mItems.clear();
        mItemsDirty = false;
        mItemsEmulator = emulator;
        mItemsTopRow = topRow;
        if (emulator == null) return mItems;

        TerminalBuffer screen = emulator.getScreen();
        int columns = emulator.mColumns;
        int endRow = Math.min(topRow + emulator.mRows, emulator.mRows); // exclusive
        int row = topRow;
        while (row < endRow) {
            int start = row;
            while (row < endRow - 1 && screen.getLineWrap(row)) row++;
            String text = screen.getSelectedText(0, start, columns, row);
            if (!TerminalChangeReader.isBlank(text)) mItems.add(new LineItem(idForRow(start), start, row, text));
            row++;
        }
        return mItems;
    }

    @Nullable
    private LineItem findItem(int id) {
        if (id == INVALID_ID) return null;
        for (LineItem item : items()) {
            if (item.id == id) return item;
        }
        return null;
    }

    private int idForRow(int row) {
        return (int) ((mScrolledTotal + row) & ID_MASK);
    }

    private int idAt(float y) {
        TerminalRenderer renderer = mView.mRenderer;
        if (renderer == null || mView.mEmulator == null || renderer.mFontLineSpacing <= 0) return INVALID_ID;
        int screenRow = (int) Math.floor((y - renderer.mFontLineSpacingAndAscent) / renderer.mFontLineSpacing);
        int row = mView.getTopRow() + Math.max(0, screenRow);
        for (LineItem item : items()) {
            if (row >= item.startRow && row <= item.endRow) return item.id;
        }
        return INVALID_ID;
    }

    private void boundsInView(LineItem item, Rect out) {
        TerminalRenderer renderer = mView.mRenderer;
        if (renderer == null) {
            out.setEmpty();
            return;
        }
        int topRow = mView.getTopRow();
        int top = renderer.mFontLineSpacingAndAscent + (item.startRow - topRow) * renderer.mFontLineSpacing;
        int bottom = renderer.mFontLineSpacingAndAscent + (item.endRow - topRow + 1) * renderer.mFontLineSpacing;
        out.set(0, Math.max(0, top), mView.getWidth(), Math.min(mView.getHeight(), bottom));
        if (out.top >= out.bottom) out.setEmpty();
    }

    private void updateHovered(int id) {
        if (mHoveredId == id) return;
        int previous = mHoveredId;
        mHoveredId = id;
        if (id != INVALID_ID) sendLineEvent(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
        if (previous != INVALID_ID) sendLineEvent(previous, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
    }

    // ---------------------------------------------------------------------------------------
    // Speaking new output

    private void scheduleFlush() {
        long now = SystemClock.uptimeMillis();
        if (!mFlushPending) {
            mFlushPending = true;
            mFirstPendingTime = now;
        }
        mView.removeCallbacks(mFlush);
        mView.postDelayed(mFlush, Math.min(SETTLE_MS, Math.max(0, mFirstPendingTime + MAX_WAIT_MS - now)));
    }

    private void flush() {
        mFlushPending = false;
        TerminalEmulator emulator = mView.mEmulator;
        if (emulator == null || !isActive()) {
            mChangeReader.reset();
            return;
        }
        sendContentChanged();
        // Checked even when not reading, so turning reading on does not read old changes.
        TerminalChangeReader.Speech speech = mChangeReader.check(emulator, SystemClock.uptimeMillis());
        if (speech != null && isAutoRead()) announce(speakable(speech));
    }

    /** A lone character is marked to be said as such, rather than as a word or not at all. */
    private static CharSequence speakable(TerminalChangeReader.Speech speech) {
        if (!speech.character) return speech.text;
        SpannableString spannable = new SpannableString(speech.text);
        spannable.setSpan(new TtsSpan.VerbatimBuilder(speech.text).build(), 0, speech.text.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spannable;
    }

    private boolean isAutoRead() {
        if (mAutoRead == null) {
            mAutoRead = prefs().getBoolean(PREF_AUTO_READ, true);
        }
        return mAutoRead;
    }

    private void setAutoRead(boolean autoRead) {
        mAutoRead = autoRead;
        prefs().edit().putBoolean(PREF_AUTO_READ, autoRead).apply();
    }

    private SharedPreferences prefs() {
        return mView.getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------------------------------
    // Events

    private void announce(CharSequence text) {
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
        mView.onInitializeAccessibilityEvent(event);
        event.getText().add(text);
        event.setContentDescription(null);
        sendEvent(event);
    }

    private void sendContentChanged() {
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        mView.onInitializeAccessibilityEvent(event);
        event.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        sendEvent(event);
    }

    private void sendHostEvent(int type) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        mView.onInitializeAccessibilityEvent(event);
        sendEvent(event);
    }

    private void sendLineEvent(int id, int type) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName(mView.getContext().getPackageName());
        event.setClassName(TextView.class.getName());
        event.setEnabled(true);
        LineItem item = findItem(id);
        if (item != null) event.getText().add(item.text);
        event.setSource(mView, id);
        sendEvent(event);
    }

    private void sendEvent(AccessibilityEvent event) {
        ViewParent parent = mView.getParent();
        if (parent == null || !isActive()) {
            event.recycle();
            return;
        }
        parent.requestSendAccessibilityEvent(mView, event);
    }
}
