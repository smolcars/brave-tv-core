// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.
package org.chromium.content.browser.input;

import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.Nullable;

import org.jni_zero.NativeMethods;

import org.chromium.base.ThreadUtils;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.blink_public.web.WebTextInputFlags;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.KeyboardVisibilityDelegate;
import org.chromium.ui.base.ime.TextInputType;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * UI-thread-only access to Chromium InputConnection operations. An accepted edit is asynchronous:
 * {@link #ready()} stays false until a native queue barrier and renderer state confirm it. Focus,
 * viewport/document invalidation, destruction or a two-second timeout cancels pending work; callers
 * must refresh identity/state before submitting another edit and must never replay a rejected edit.
 */
public final class TvTextInput {
    private static final WeakHashMap<ImeAdapterImpl, TvTextInput> ACTIVE = new WeakHashMap<>();
    private final ImeAdapterImpl mAdapter;
    private final WebContents mContents;
    private long mOperationId;
    private long mRevision = 1;
    private long mVersion = 1;
    private long mReplacementVersion = 1;
    private boolean mEligible;
    private boolean mAwaitingState = true;
    private String mText = "";
    private int mStart;
    private int mEnd;
    private int mCompositionStart = -1;
    private int mCompositionEnd = -1;
    private int mType;
    private int mFlags;
    private int mMode;
    private int mAction;
    private int mEditorAction = EditorInfo.IME_ACTION_NONE;
    private @Nullable PendingEdit mPendingEdit;
    private boolean mNativeCompleted;
    private boolean mPhoneTyping;

    private enum Phase {
        SELECT_ALL,
        TEXT,
        SELECTION,
        RESULT,
        ACTION,
        PREPARE,
        DRAIN
    }

    private static final class PendingEdit {
        final String originalText;
        final String text;
        final int start;
        final int end;
        final int compositionStart;
        final int compositionEnd;
        final long deadline = SystemClock.uptimeMillis() + 2000;
        Phase phase;

        PendingEdit(
                String originalText,
                String text,
                int start,
                int end,
                int compositionStart,
                int compositionEnd,
                Phase phase) {
            this.originalText = originalText;
            this.text = text;
            this.start = start;
            this.end = end;
            this.compositionStart = compositionStart;
            this.compositionEnd = compositionEnd;
            this.phase = phase;
        }
    }

    public TvTextInput(WebContents contents) {
        ThreadUtils.assertOnUiThread();
        mContents = contents;
        mAdapter = ImeAdapterImpl.fromWebContents(contents);
        ACTIVE.put(mAdapter, this);
        mAdapter.requestTextInputStateUpdate();
    }

    public void destroy() {
        releasePhoneTyping();
        ACTIVE.remove(mAdapter);
        invalidate();
    }

    public long revision() {
        return mRevision;
    }

    public boolean eligible() {
        return mEligible;
    }

    public long version() {
        return mVersion;
    }

    /**
     * Advances on eligibility, text or selection changes, excluding composition-only updates. Use
     * only for whole-field replacement with current browser/field identity and a ready, eligible
     * field.
     */
    public long replacementVersion() {
        return mReplacementVersion;
    }

    public boolean ready() {
        return !mAwaitingState && (!mPhoneTyping || !mAdapter.hasInputConnection());
    }

    public boolean phoneTyping() {
        return mPhoneTyping;
    }

    /** A physical controller or lost browser owner returns the field to the platform IME. */
    public void releasePhoneTyping() {
        ThreadUtils.assertOnUiThread();
        if (!mPhoneTyping) return;
        mPhoneTyping = false;
        if (!mContents.isDestroyed()) mAdapter.restartInput();
    }

    // Signature-checked hooks gate only physical input creation/display, preserving EditorInfo.
    public static boolean phoneTyping(ImeAdapterImpl adapter) {
        ThreadUtils.assertOnUiThread();
        TvTextInput input = ACTIVE.get(adapter);
        return input != null && input.mPhoneTyping;
    }

    public static boolean allowPhysicalInput(ImeAdapterImpl adapter, boolean editable) {
        return editable && !phoneTyping(adapter);
    }

    private boolean beginPhoneTyping() {
        if (mPhoneTyping) return true;
        if (mContents.isIncognito()) return false;
        var delegate = mContents.getViewAndroidDelegate();
        if (delegate == null || delegate.getContainerView() == null) return false;
        mPhoneTyping = true;
        // Keep renderer focus/type intact; the normal creation path detaches and unblocks the IME.
        mAdapter.onCreateInputConnection(new EditorInfo());
        mAdapter.restartInput();
        mAdapter.cancelRequestToScrollFocusedEditableNodeIntoView();
        KeyboardVisibilityDelegate.getInstance().hideKeyboard(delegate.getContainerView());
        return true;
    }

    public String text() {
        return eligible() ? mText : "";
    }

    public int start() {
        return mStart;
    }

    public int end() {
        return mEnd;
    }

    public boolean composing() {
        return mCompositionStart >= 0;
    }

    public boolean multiline() {
        return mType == TextInputType.TEXT_AREA || mType == TextInputType.CONTENT_EDITABLE;
    }

    /** A primary Enter action; default Next/Previous must not replace website submission. */
    public String action() {
        switch (mEditorAction) {
            case EditorInfo.IME_ACTION_SEARCH:
                return "search";
            case EditorInfo.IME_ACTION_GO:
                return "go";
            case EditorInfo.IME_ACTION_SEND:
                return "send";
            case EditorInfo.IME_ACTION_DONE:
                return "done";
            default:
                return "enter";
        }
    }

    public void invalidate() {
        ThreadUtils.assertOnUiThread();
        mOperationId++;
        mRevision++;
        mEligible = false;
        mAwaitingState = true;
        mText = "";
        mCompositionStart = -1;
        mCompositionEnd = -1;
        mPendingEdit = null;
    }

    /** Reacquires native field state after the owner has cancelled an invalid browser context. */
    public void requestStateUpdate() {
        ThreadUtils.assertOnUiThread();
        mAdapter.requestTextInputStateUpdate();
    }

    // Called by a signature-checked bytecode hook, including equal-type focus changes.
    public static boolean focusChanged(ImeAdapterImpl adapter) {
        TvTextInput input = ACTIVE.get(adapter);
        if (input != null) {
            boolean wasPhoneTyping = input.mPhoneTyping;
            // The focus hook schedules a restart with the next field's state. Reset and
            // destruction already own their connection teardown and ignore the result.
            input.mPhoneTyping = false;
            input.invalidate();
            return wasPhoneTyping;
        }
        return false;
    }

    public static void stateChanged(
            ImeAdapterImpl adapter,
            int type,
            int flags,
            int mode,
            int action,
            String text,
            int start,
            int end,
            int compositionStart,
            int compositionEnd) {
        ThreadUtils.assertOnUiThread();
        TvTextInput input = ACTIVE.get(adapter);
        if (input == null) return;
        boolean eligible =
                (type == TextInputType.TEXT
                                || type == TextInputType.TEXT_AREA
                                || type == TextInputType.CONTENT_EDITABLE
                                || type == TextInputType.SEARCH)
                        && (flags
                                        & (WebTextInputFlags.HAS_BEEN_PASSWORD_FIELD
                                                | WebTextInputFlags.HAS_BEEN_CUSTOM_PASSWORD))
                                == 0
                        && text.length() <= 2048
                        && boundary(text, start)
                        && boundary(text, end);
        boolean configurationChanged =
                input.mType != type
                        || input.mFlags != flags
                        || input.mMode != mode
                        || input.mAction != action;
        boolean eligibilityChanged = input.mEligible != eligible;
        if (!eligible) input.releasePhoneTyping();
        if (eligibilityChanged || configurationChanged) {
            input.mRevision++;
            if (input.mPendingEdit != null) input.mPendingEdit.phase = Phase.DRAIN;
        }
        boolean replacementChanged =
                configurationChanged
                        || input.mEligible != eligible
                        || !input.mText.equals(text)
                        || input.mStart != start
                        || input.mEnd != end;
        if (replacementChanged) input.mReplacementVersion++;
        if (replacementChanged
                || input.mCompositionStart != compositionStart
                || input.mCompositionEnd != compositionEnd) input.mVersion++;
        input.mEligible = eligible;
        input.mType = type;
        input.mFlags = flags;
        input.mMode = mode;
        input.mAction = action;
        if (eligible && (configurationChanged || eligibilityChanged)) {
            EditorInfo info = new EditorInfo();
            ImeUtils.computeEditorInfo(type, flags, mode, action, start, end, text, info);
            input.mEditorAction = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        }
        input.mAwaitingState = false;
        input.mText = eligible ? text : "";
        input.mStart = eligible ? start : 0;
        input.mEnd = eligible ? end : 0;
        input.mCompositionStart = eligible ? compositionStart : -1;
        input.mCompositionEnd = eligible ? compositionEnd : -1;
        input.advanceEdit();
    }

    private void advanceEdit() {
        PendingEdit edit = mPendingEdit;
        if (edit == null) return;
        if (edit.phase != Phase.RESULT
                && edit.phase != Phase.ACTION
                && edit.phase != Phase.PREPARE
                && edit.phase != Phase.DRAIN
                && !mText.equals(edit.originalText)
                && !mText.equals(edit.text)) {
            // Discard remaining phases; drain the native operation already submitted.
            edit.phase = Phase.DRAIN;
            mAwaitingState = true;
            if (mNativeCompleted) {
                invalidate();
                mAdapter.requestTextInputStateUpdate();
            }
            return;
        }
        mAwaitingState = true;
        if (!mNativeCompleted) return;
        if (edit.phase == Phase.SELECT_ALL
                && mText.equals(edit.originalText)
                && mStart == 0
                && mEnd == mText.length()) {
            edit.phase = Phase.TEXT;
            if (!mAdapter.sendCompositionToNative(edit.text, 1, false, 0)) {
                invalidate();
                mAdapter.requestTextInputStateUpdate();
                return;
            }
            if (!awaitNativeInput()) {
                invalidate();
                mAdapter.requestTextInputStateUpdate();
            }
        } else if (edit.phase == Phase.TEXT && matchesResult(edit, false)) {
            edit.phase = Phase.SELECTION;
            if (mStart == edit.start && mEnd == edit.end) {
                mPendingEdit = null;
                mAwaitingState = false;
            } else {
                if (!mAdapter.setEditableSelectionOffsets(edit.start, edit.end)) {
                    invalidate();
                    mAdapter.requestTextInputStateUpdate();
                    return;
                }
                if (!awaitNativeInput()) {
                    invalidate();
                    mAdapter.requestTextInputStateUpdate();
                }
            }
        } else if (edit.phase == Phase.SELECTION && matchesResult(edit, true)) {
            mPendingEdit = null;
            mAwaitingState = false;
        } else if ((edit.phase == Phase.RESULT || edit.phase == Phase.PREPARE)
                && matchesResult(edit, true)) {
            mPendingEdit = null;
            mAwaitingState = false;
        } else if (edit.phase == Phase.ACTION) {
            // Site handlers may change text, focus or navigation, or do nothing.
            mPendingEdit = null;
            mAwaitingState = false;
        } else if (edit.phase == Phase.DRAIN) {
            invalidate();
            mAdapter.requestTextInputStateUpdate();
        }
    }

    private boolean matchesResult(PendingEdit edit, boolean selection) {
        return mText.equals(edit.text)
                && mCompositionStart == edit.compositionStart
                && mCompositionEnd == edit.compositionEnd
                && (!selection || (mStart == edit.start && mEnd == edit.end));
    }

    private boolean awaitNativeInput() {
        PendingEdit edit = mPendingEdit;
        assert edit != null;
        mNativeCompleted = false;
        mAwaitingState = true;
        long operationId = ++mOperationId;
        WeakReference<TvTextInput> owner = new WeakReference<>(this);
        PostTask.postDelayedTask(
                TaskTraits.UI_DEFAULT,
                () -> {
                    TvTextInput input = owner.get();
                    if (input == null
                            || input.mOperationId != operationId
                            || input.mPendingEdit == null) return;
                    if (input.mPendingEdit.phase == Phase.PREPARE) input.releasePhoneTyping();
                    input.invalidate();
                    input.mAdapter.requestTextInputStateUpdate();
                },
                Math.max(0, edit.deadline - SystemClock.uptimeMillis()));
        return TvTextInputJni.get()
                .awaitInput(
                        mContents,
                        () -> {
                            TvTextInput input = owner.get();
                            if (input == null
                                    || input.mOperationId != operationId
                                    || input.mPendingEdit == null) return;
                            ThreadUtils.assertOnUiThread();
                            input.mNativeCompleted = true;
                            input.mAdapter.requestTextInputStateUpdate();
                        });
    }

    /** Returns acceptance only; completion is reported through refreshed state and ready(). */
    public boolean edit(
            long revision, String operation, String text, int start, int end, boolean composing) {
        ThreadUtils.assertOnUiThread();
        if (!eligible()
                || !ready()
                || !mAdapter.isValid()
                || revision != mRevision
                || text.length() > 2048) return false;
        boolean result;
        switch (operation) {
            case "prepare":
                if (composing() || composing || !beginPhoneTyping()) return false;
                mPendingEdit = new PendingEdit(mText, mText, mStart, mEnd, -1, -1, Phase.PREPARE);
                result = true;
                break;
            case "enter":
                if (composing() || composing) return false;
                mPendingEdit = new PendingEdit(mText, mText, mStart, mEnd, -1, -1, Phase.ACTION);
                if (action().equals("enter")) {
                    mAdapter.sendSyntheticKeyPress(
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE);
                    result = true;
                } else {
                    result = mAdapter.performEditorAction(mEditorAction);
                }
                break;
            case "replace":
                if (!validUtf16(text) || !boundary(text, start) || !boundary(text, end))
                    return false;
                mPendingEdit =
                        new PendingEdit(
                                mText,
                                text,
                                start,
                                end,
                                composing && !text.isEmpty() ? 0 : -1,
                                composing && !text.isEmpty() ? text.length() : -1,
                                composing ? Phase.SELECT_ALL : Phase.TEXT);
                if (composing) {
                    mAdapter.finishComposingText();
                    result = mAdapter.setEditableSelectionOffsets(0, mText.length());
                } else {
                    // Selection and commits use different native IPC paths. Replace atomically.
                    result = mAdapter.replaceText(0, mText.length(), text, 1);
                }
                break;
            case "commit":
            case "compose":
                if (!validUtf16(text)) return false;
                int from = mCompositionStart >= 0 ? mCompositionStart : Math.min(mStart, mEnd);
                int to = mCompositionStart >= 0 ? mCompositionEnd : Math.max(mStart, mEnd);
                if (!boundary(mText, from) || !boundary(mText, to) || from > to) return false;
                String expected = mText.substring(0, from) + text + mText.substring(to);
                if (expected.length() > 2048) return false;
                int caret = from + text.length();
                boolean compose = operation.equals("compose") && !text.isEmpty();
                mPendingEdit =
                        new PendingEdit(
                                mText,
                                expected,
                                caret,
                                caret,
                                compose ? from : -1,
                                compose ? caret : -1,
                                Phase.RESULT);
                result = mAdapter.sendCompositionToNative(text, 1, operation.equals("commit"), 0);
                break;
            case "finish":
                if (!beginPhoneTyping()) return false;
                mPendingEdit = new PendingEdit(mText, mText, mStart, mEnd, -1, -1, Phase.RESULT);
                result = mAdapter.finishComposingText();
                break;
            case "select":
                if (!boundary(mText, start) || !boundary(mText, end)) return false;
                mPendingEdit =
                        new PendingEdit(
                                mText,
                                mText,
                                Math.min(start, end),
                                Math.max(start, end),
                                mCompositionStart,
                                mCompositionEnd,
                                Phase.SELECTION);
                result = mAdapter.setEditableSelectionOffsets(start, end);
                break;
            case "delete":
                if (start < 0 || end < 0 || start > 2048 || end > 2048) return false;
                int left = Math.min(mStart, mEnd);
                int right = Math.max(mStart, mEnd);
                int before =
                        mText.offsetByCodePoints(
                                left, -Math.min(start, mText.codePointCount(0, left)));
                int after =
                        mText.offsetByCodePoints(
                                right, Math.min(end, mText.codePointCount(right, mText.length())));
                // Chromium deletes around a selected range and preserves the selected text.
                String deleted =
                        mText.substring(0, before)
                                + mText.substring(left, right)
                                + mText.substring(after);
                int compStart = deletedOffset(mCompositionStart, before, left, right, after);
                int compEnd = deletedOffset(mCompositionEnd, before, left, right, after);
                if (compStart == compEnd) compStart = compEnd = -1;
                mPendingEdit =
                        new PendingEdit(
                                mText,
                                deleted,
                                before,
                                before + right - left,
                                compStart,
                                compEnd,
                                Phase.RESULT);
                result = mAdapter.deleteSurroundingTextInCodePoints(start, end);
                break;
            default:
                return false;
        }
        result = result && awaitNativeInput();
        mAwaitingState = true;
        if (!result) {
            if (operation.equals("prepare")) releasePhoneTyping();
            invalidate();
            mAdapter.requestTextInputStateUpdate();
        }
        return result;
    }

    private static int deletedOffset(int offset, int before, int left, int right, int after) {
        if (offset < 0) return -1;
        return offset
                - Math.max(0, Math.min(offset, left) - before)
                - Math.max(0, Math.min(offset, after) - right);
    }

    @NativeMethods
    interface Natives {
        boolean awaitInput(WebContents contents, Runnable completion);
    }

    private static boolean boundary(String text, int offset) {
        return offset >= 0
                && offset <= text.length()
                && !(offset > 0
                        && offset < text.length()
                        && Character.isHighSurrogate(text.charAt(offset - 1))
                        && Character.isLowSurrogate(text.charAt(offset)));
    }

    private static boolean validUtf16(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
}
