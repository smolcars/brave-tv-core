// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tv.presentation.TvPresentation;

import java.util.ArrayList;
import java.util.List;

/** Native dialog ownership with bounded Compose TV content; finish entries before show. */
final class TvBrowserPanel {
    private final BraveActivity mActivity;
    private final String mTitle;
    private final String mSubtitle;
    private final int mPreferredAction;
    private final List<TvPresentation.PanelEntry> mEntries = new ArrayList<>();
    private @Nullable List<TvPresentation.Action> mRow;
    private boolean mWide;
    private boolean mPrivateContent;
    private boolean mRestorePreferredFocus;
    private boolean mFocusRestored;
    private @Nullable Runnable mOnDismiss;

    TvBrowserPanel(BraveActivity activity, String title, String subtitle, int preferredAction) {
        mActivity = activity;
        mTitle = title;
        mSubtitle = subtitle;
        mPreferredAction = preferredAction;
    }

    void protectPrivateContent() {
        mPrivateContent = true;
    }

    void setOnDismiss(Runnable onDismiss) {
        mOnDismiss = onDismiss;
    }

    void restorePreferredFocus() {
        mRestorePreferredFocus = true;
    }

    boolean hasRestoredFocus() {
        return mFocusRestored;
    }

    void useWideLayout() {
        mWide = true;
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    TvPresentation.Description addDescription(String text) {
        return addDescription(text, false);
    }

    TvPresentation.Description addDescription(String text, boolean announceChanges) {
        mRow = null;
        TvPresentation.Description entry = new TvPresentation.Description(text, announceChanges);
        mEntries.add(entry);
        return entry;
    }

    TvPresentation.Action addAction(int label, boolean enabled, Runnable action) {
        TvPresentation.Action entry =
                new TvPresentation.Action(mActivity.getString(label), enabled, action, label);
        if (mRow == null || mRow.size() == (mWide ? 3 : 2)) {
            mRow = new ArrayList<>();
            mEntries.add(new TvPresentation.ActionRow(mRow));
        }
        mRow.add(entry);
        return entry;
    }

    TvPresentation.Action addItem(String label, int key, Runnable action) {
        mRow = null;
        TvPresentation.Action entry = new TvPresentation.Action(label, true, action, key);
        mEntries.add(new TvPresentation.ActionRow(List.of(entry)));
        return entry;
    }

    void addContent(View view, int heightDp) {
        mRow = null;
        mEntries.add(new TvPresentation.NativeContent(view, heightDp));
    }

    AlertDialog show(Runnable onCancel) {
        mFocusRestored = false;
        View content =
                TvPresentation.panel(
                        mActivity,
                        mTitle,
                        mSubtitle,
                        mActivity.getString(R.string.brave_tv_panel_back_hint),
                        List.copyOf(mEntries),
                        mPreferredAction,
                        mRestorePreferredFocus,
                        () -> mFocusRestored = true);
        AlertDialog dialog =
                new AlertDialog.Builder(mActivity)
                        .setView(content)
                        .setOnCancelListener(ignored -> onCancel.run())
                        .create();
        dialog.setOnDismissListener(
                ignored -> {
                    mFocusRestored = false;
                    TvPresentation.dispose(content);
                    if (mOnDismiss != null) mOnDismiss.run();
                });
        dialog.setCanceledOnTouchOutside(false);
        Window window = dialog.getWindow();
        if (window != null) {
            Tab tab = mActivity.getActivityTab();
            if (mPrivateContent || (tab != null && tab.isIncognito()))
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            var attributes = window.getAttributes();
            attributes.x = dp(24);
            attributes.dimAmount = 0.55f;
            window.setAttributes(attributes);
        }
        dialog.show();
        if (window != null) {
            var metrics = mActivity.getResources().getDisplayMetrics();
            int width = Math.min(dp(mWide ? 740 : 540), Math.round(metrics.widthPixels * 0.92f));
            window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }
}
