// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.
package org.chromium.content.browser.input;

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
    }

    // Called by a signature-checked bytecode hook, including equal-type focus changes.
    public static void focusChanged(ImeAdapterImpl adapter) {
        TvTextInput input = ACTIVE.get(adapter);
        if (input != null) input.invalidate();
    }

    public static void stateChanged(
            ImeAdapterImpl adapter, int type, int flags, String text, int start, int end) {
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
        if (input.mEligible != eligible) input.mRevision++;
        if (input.mEligible != eligible
                || !input.mText.equals(text)
                || input.mStart != start
                || input.mEnd != end) input.mVersion++;
        input.mEligible = eligible;
        input.mAwaitingState = false;
        input.mText = eligible ? text : "";
        input.mStart = eligible ? start : 0;
        input.mEnd = eligible ? end : 0;
    }

    public boolean edit(
            long revision, String operation, String text, int start, int end, boolean composing) {
        if (!eligible() || !ready() || revision != mRevision || text.length() > 2048) return false;
        boolean result;
        switch (operation) {
            case "replace":
                if (!validUtf16(text) || !boundary(text, start) || !boundary(text, end))
                    return false;
                // Use the native IME operations, including a real composing range.
                mAdapter.finishComposingText();
                mAdapter.setEditableSelectionOffsets(0, mText.length());
                result = mAdapter.sendCompositionToNative(text, 1, !composing, 0);
                if (!composing) mAdapter.setEditableSelectionOffsets(start, end);
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
