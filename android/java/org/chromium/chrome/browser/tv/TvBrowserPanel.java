// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.chromium.chrome.R;

/** A bounded, scrollable TV panel with native focus and themed Material actions. */
final class TvBrowserPanel {
    private final Activity mActivity;
    private final LinearLayout mContent;
    private final ScrollView mScroll;
    private final int mPreferredAction;
    private @Nullable LinearLayout mRow;
    private @Nullable View mFirstAction;
    private @Nullable View mPreferredView;
    private @Nullable View mPrimaryAction;
    private boolean mPrimaryDownSet;

    TvBrowserPanel(Activity activity, String title, String subtitle, int preferredAction) {
        mActivity = activity;
        mPreferredAction = preferredAction;
        mContent = new LinearLayout(activity);
        mContent.setOrientation(LinearLayout.VERTICAL);
        mContent.setPadding(dp(24), dp(16), dp(24), dp(16));
        addText(title, 26, true);
        if (!subtitle.isEmpty()) addText(subtitle, 16, false);
        mScroll = new ScrollView(activity);
        mScroll.setFillViewport(true);
        mScroll.addView(mContent);
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    private void addText(String text, int size, boolean heading) {
        TextView view = new TextView(mActivity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(MaterialColors.getColor(mContent, R.attr.colorOnSurface));
        view.setMaxLines(2);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setPadding(dp(6), 0, dp(6), dp(8));
        if (heading) {
            view.setTypeface(null, Typeface.BOLD);
            view.setAccessibilityHeading(true);
        }
        mContent.addView(view);
    }

    void addAction(int label, boolean enabled, Runnable action) {
        MaterialButton button = makeButton(mActivity.getString(label), label, enabled, action);
        if (mRow == null || mRow.getChildCount() == 3) {
            mRow = new LinearLayout(mActivity);
            mContent.addView(mRow);
        }
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        mRow.addView(button, params);
        if (enabled && mPrimaryAction != null && !mPrimaryDownSet) {
            mPrimaryAction.setNextFocusDownId(button.getId());
            mPrimaryDownSet = true;
        }
    }

    void addItem(String label, int key, Runnable action) {
        mRow = null;
        MaterialButton button = makeButton(label, key, true, action);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        mContent.addView(button, params);
        if (mFirstAction == button) {
            mPrimaryAction = button;
        } else {
            // A list uses native vertical focus; only a single primary item leads into a grid.
            mPrimaryAction = null;
        }
    }

    private MaterialButton makeButton(String label, int key, boolean enabled, Runnable action) {
        MaterialButton button = createButton(mActivity, label, enabled, action);
        if (enabled && mFirstAction == null) mFirstAction = button;
        if (enabled && key == mPreferredAction) mPreferredView = button;
        return button;
    }

    static MaterialButton createButton(
            Activity activity, String label, boolean enabled, Runnable action) {
        MaterialButton button = new MaterialButton(activity);
        button.setId(View.generateViewId());
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(18);
        button.setMinHeight(Math.round(56 * activity.getResources().getDisplayMetrics().density));
        button.setMaxLines(2);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setCornerRadius(
                Math.round(12 * activity.getResources().getDisplayMetrics().density));
        button.setStrokeWidth(Math.round(3 * activity.getResources().getDisplayMetrics().density));
        int primary = MaterialColors.getColor(button, R.attr.colorPrimary);
        int surface = MaterialColors.getColor(button, R.attr.colorSurface);
        int onPrimary = MaterialColors.getColor(button, R.attr.colorOnPrimary);
        int onSurface = MaterialColors.getColor(button, R.attr.colorOnSurface);
        int[][] states = {{android.R.attr.state_focused}, {}};
        button.setBackgroundTintList(new ColorStateList(states, new int[] {primary, surface}));
        button.setTextColor(new ColorStateList(states, new int[] {onPrimary, onSurface}));
        button.setStrokeColor(new ColorStateList(states, new int[] {onPrimary, primary}));
        button.setEnabled(enabled);
        button.setFocusableInTouchMode(enabled);
        button.setAlpha(enabled ? 1f : 0.4f);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    AlertDialog show(Runnable onCancel) {
        AlertDialog dialog =
                new MaterialAlertDialogBuilder(mActivity)
                        .setBackgroundInsetTop(0)
                        .setBackgroundInsetBottom(0)
                        .setView(mScroll)
                        .setOnCancelListener(ignored -> onCancel.run())
                        .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            var metrics = mActivity.getResources().getDisplayMetrics();
            window.setLayout(
                    Math.round(metrics.widthPixels * 0.9f),
                    Math.round(metrics.heightPixels * 0.86f));
        }
        View focus = mPreferredView != null ? mPreferredView : mFirstAction;
        if (focus != null) focus.requestFocus();
        return dialog;
    }
}
