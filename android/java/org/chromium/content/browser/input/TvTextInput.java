// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.
package org.chromium.content.browser.input;

import androidx.annotation.Nullable;

import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.blink_public.web.WebTextInputFlags;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.base.ime.TextInputType;

import java.util.WeakHashMap;

/** Scoped access to the same UI-thread operations used by Chromium InputConnection. */
public final class TvTextInput {
    private static final WeakHashMap<ImeAdapterImpl, TvTextInput> ACTIVE = new WeakHashMap<>();
    private final ImeAdapterImpl mAdapter;
    private long mRevision = 1;
    private long mVersion = 1;
    private boolean mEligible;
    private boolean mAwaitingState = true;
    private String mText = "";
    private int mStart;
    private int mEnd;
    private @Nullable PendingEdit mPendingEdit;

    private enum Phase {
        SELECT_ALL,
        TEXT,
        SELECTION
    }

    private static final class PendingEdit {
        final String originalText;
        final String text;
        final int start;
        final int end;
        final boolean composing;
        Phase phase;

        PendingEdit(
                String originalText,
                String text,
                int start,
                int end,
                boolean composing,
                Phase phase) {
            this.originalText = originalText;
            this.text = text;
            this.start = start;
            this.end = end;
            this.composing = composing;
            this.phase = phase;
        }
    }

    public TvTextInput(WebContents contents) {
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
            ImeAdapterImpl adapter,
            int type,
            int flags,
            String text,
            int start,
            int end,
            boolean replyToRequest) {
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
        input.advanceEdit(replyToRequest);
    }

    private void advanceEdit(boolean replyToRequest) {
        PendingEdit edit = mPendingEdit;
        if (edit == null) return;
        if (!mText.equals(edit.originalText) && !mText.equals(edit.text)) {
            mPendingEdit = null;
            return;
        }
        mAwaitingState = true;
        if (edit.phase == Phase.SELECT_ALL
                && mText.equals(edit.originalText)
                && mStart == 0
                && mEnd == mText.length()) {
            edit.phase = Phase.TEXT;
            if (!mAdapter.sendCompositionToNative(edit.text, 1, false, 0)) {
                mPendingEdit = null;
            }
            mAdapter.requestTextInputStateUpdate();
        } else if (edit.phase == Phase.TEXT && replyToRequest && mText.equals(edit.text)) {
            if (edit.composing) {
                mPendingEdit = null;
                mAwaitingState = false;
            } else {
                edit.phase = Phase.SELECTION;
                if (mStart == edit.start && mEnd == edit.end) {
                    mPendingEdit = null;
                    mAwaitingState = false;
                } else {
                    if (!mAdapter.setEditableSelectionOffsets(edit.start, edit.end)) {
                        mPendingEdit = null;
                    }
                    mAdapter.requestTextInputStateUpdate();
                }
            }
        } else if (edit.phase == Phase.SELECTION
                && mText.equals(edit.text)
                && mStart == edit.start
                && mEnd == edit.end) {
            mPendingEdit = null;
            mAwaitingState = false;
        }
    }

    private void awaitEdit(PendingEdit edit) {
        mPendingEdit = edit;
        mAwaitingState = true;
        PostTask.postDelayedTask(
                TaskTraits.UI_DEFAULT,
                () -> {
                    if (mPendingEdit != edit) return;
                    // A renderer that stops acknowledging input must not retain or replay this
                    // edit.
                    mPendingEdit = null;
                    mAdapter.requestTextInputStateUpdate();
                },
                2000);
    }

    public boolean edit(
            long revision, String operation, String text, int start, int end, boolean composing) {
        if (!eligible() || !ready() || revision != mRevision || text.length() > 2048) return false;
        boolean result;
        switch (operation) {
            case "replace":
                if (!validUtf16(text) || !boundary(text, start) || !boundary(text, end))
                    return false;
                awaitEdit(
                        new PendingEdit(
                                mText,
                                text,
                                start,
                                end,
                                composing,
                                composing ? Phase.SELECT_ALL : Phase.TEXT));
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
                awaitEdit(
                        new PendingEdit(
                                mText,
                                mText,
                                Math.min(start, end),
                                Math.max(start, end),
                                false,
                                Phase.SELECTION));
                result = mAdapter.setEditableSelectionOffsets(start, end);
                break;
            case "delete":
                if (start < 0 || end < 0 || start > 2048 || end > 2048) return false;
                result = mAdapter.deleteSurroundingTextInCodePoints(start, end);
                break;
            default:
                return false;
        }
        // Wait for renderer confirmation before exporting state or accepting more edits.
        mAwaitingState = true;
        if (!result) mPendingEdit = null;
        mAdapter.requestTextInputStateUpdate();
        return result;
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
