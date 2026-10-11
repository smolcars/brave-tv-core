// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.
package org.chromium.content.browser.input;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.jni_zero.NativeMethods;

import org.chromium.base.ThreadUtils;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.blink_public.web.WebTextInputFlags;
import org.chromium.content_public.browser.WebContents;
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
    private boolean mEligible;
    private boolean mAwaitingState = true;
    private String mText = "";
    private int mStart;
    private int mEnd;
    private @Nullable PendingEdit mPendingEdit;
    private boolean mNativeCompleted;

    private enum Phase {
        SELECT_ALL,
        TEXT,
        SELECTION,
        STATE
    }

    private static final class PendingEdit {
        final String originalText;
        final String text;
        final int start;
        final int end;
        final long deadline = SystemClock.uptimeMillis() + 2000;
        Phase phase;

        PendingEdit(String originalText, String text, int start, int end, Phase phase) {
            this.originalText = originalText;
            this.text = text;
            this.start = start;
            this.end = end;
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

    public boolean ready() {
        return !mAwaitingState;
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

    public void invalidate() {
        ThreadUtils.assertOnUiThread();
        mOperationId++;
        mRevision++;
        mEligible = false;
        mAwaitingState = true;
        mText = "";
        mPendingEdit = null;
    }

    // Called by a signature-checked bytecode hook, including equal-type focus changes.
    public static void focusChanged(ImeAdapterImpl adapter) {
        TvTextInput input = ACTIVE.get(adapter);
        if (input != null) input.invalidate();
    }

    public static void stateChanged(
            ImeAdapterImpl adapter, int type, int flags, String text, int start, int end) {
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
                        && text.length() <= 2048;
        if (input.mEligible != eligible) {
            input.mRevision++;
            input.mPendingEdit = null;
        }
        if (input.mEligible != eligible
                || !input.mText.equals(text)
                || input.mStart != start
                || input.mEnd != end) input.mVersion++;
        input.mEligible = eligible;
        input.mAwaitingState = false;
        input.mText = eligible ? text : "";
        input.mStart = eligible ? start : 0;
        input.mEnd = eligible ? end : 0;
        input.advanceEdit();
    }

    private void advanceEdit() {
        PendingEdit edit = mPendingEdit;
        if (edit == null) return;
        if (edit.phase != Phase.STATE
                && !mText.equals(edit.originalText)
                && !mText.equals(edit.text)) {
            // Discard remaining phases; drain the native operation already submitted.
            mPendingEdit = mNativeCompleted ? null : new PendingEdit("", "", 0, 0, Phase.STATE);
            mAwaitingState = !mNativeCompleted;
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
        } else if (edit.phase == Phase.TEXT && mText.equals(edit.text)) {
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
        } else if (edit.phase == Phase.SELECTION
                && mText.equals(edit.text)
                && mStart == edit.start
                && mEnd == edit.end) {
            mPendingEdit = null;
            mAwaitingState = false;
        } else if (edit.phase == Phase.STATE) {
            mPendingEdit = null;
            mAwaitingState = false;
        }
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
        if (!eligible() || !ready() || revision != mRevision || text.length() > 2048) return false;
        boolean result;
        switch (operation) {
            case "replace":
                if (!validUtf16(text) || !boundary(text, start) || !boundary(text, end))
                    return false;
                mPendingEdit =
                        new PendingEdit(
                                mText, text, start, end, composing ? Phase.SELECT_ALL : Phase.TEXT);
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
                result = mAdapter.sendCompositionToNative(text, 1, operation.equals("commit"), 0);
                break;
            case "finish":
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
                                Phase.SELECTION);
                result = mAdapter.setEditableSelectionOffsets(start, end);
                break;
            case "delete":
                if (start < 0 || end < 0 || start > 2048 || end > 2048) return false;
                result = mAdapter.deleteSurroundingTextInCodePoints(start, end);
                break;
            default:
                return false;
        }
        if (mPendingEdit == null) {
            mPendingEdit = new PendingEdit(mText, "", 0, 0, Phase.STATE);
        }
        result = result && awaitNativeInput();
        mAwaitingState = true;
        if (!result) {
            invalidate();
            mAdapter.requestTextInputStateUpdate();
        }
        return result;
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
