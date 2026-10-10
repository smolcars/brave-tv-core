// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.chromium.chrome.R;

/** A bounded, scrollable TV panel with native focus and themed Material actions. */
final class TvBrowserPanel {
    private final Activity mActivity;
    private final LinearLayout mRoot;
    private final LinearLayout mContent;
    private final TextView mTitle;
    private final ScrollView mScroll;
    private final int mPreferredAction;
    private @Nullable LinearLayout mRow;
    private @Nullable View mFirstAction;
    private @Nullable View mPreferredView;
    private @Nullable View mPrimaryAction;
    private boolean mPrimaryDownSet;
    private boolean mWide;

    TvBrowserPanel(Activity activity, String title, String subtitle, int preferredAction) {
        mActivity = activity;
        mPreferredAction = preferredAction;
        mRoot = new LinearLayout(activity);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setPadding(dp(24), dp(24), dp(24), dp(16));
        mTitle = addText(title, 30, true);
        if (!subtitle.isEmpty()) addText(subtitle, 15, false);
        mContent = new LinearLayout(activity);
        mContent.setOrientation(LinearLayout.VERTICAL);
        mContent.setPadding(0, dp(12), 0, dp(8));
        mScroll = new ScrollView(activity);
        mScroll.setClipToPadding(false);
        mScroll.addView(mContent);
        mRoot.addView(
                mScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        TextView hint = addText(activity.getString(R.string.brave_tv_panel_back_hint), 13, false);
        hint.setPadding(dp(4), dp(12), dp(4), 0);
    }

    void useWideLayout() {
        mWide = true;
        mTitle.setTextSize(26);
        mRoot.setPadding(dp(24), dp(20), dp(24), dp(12));
        mContent.setPadding(0, dp(8), 0, dp(4));
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    private TextView addText(String text, int size, boolean heading) {
        TextView view = new TextView(mActivity);
        view.setText(text);
        view.setTextSize(size);
        int surface = MaterialColors.getColor(mRoot, R.attr.colorSurface);
        int foreground = MaterialColors.getColor(mRoot, R.attr.colorOnSurface);
        view.setTextColor(heading ? foreground : ColorUtils.blendARGB(surface, foreground, 0.7f));
        view.setMaxLines(heading ? 2 : 4);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setPadding(dp(6), 0, dp(6), dp(8));
        if (heading) {
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            view.setLetterSpacing(-0.02f);
            view.setAccessibilityHeading(true);
        }
        mRoot.addView(view);
        return view;
    }

    MaterialButton addAction(int label, boolean enabled, Runnable action) {
        MaterialButton button = makeButton(mActivity.getString(label), label, enabled, action);
        if (mWide) button.setMinHeight(dp(48));
        if (mRow == null || mRow.getChildCount() == (mWide ? 3 : 2)) {
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
        return button;
    }

    MaterialButton addItem(String label, int key, Runnable action) {
        mRow = null;
        MaterialButton button = makeButton(label, key, true, action);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
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
        return button;
    }

    private MaterialButton makeButton(String label, int key, boolean enabled, Runnable action) {
        MaterialButton button = createButton(mActivity, label, enabled, action);
        if (enabled && mFirstAction == null) mFirstAction = button;
        if (enabled && mPreferredAction != 0 && key == mPreferredAction) mPreferredView = button;
        return button;
    }

    static MaterialButton createButton(
            Activity activity, String label, boolean enabled, Runnable action) {
        MaterialButton button = new MaterialButton(activity);
        button.setId(View.generateViewId());
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(17);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        float density = activity.getResources().getDisplayMetrics().density;
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPaddingRelative(
                Math.round(16 * density),
                Math.round(8 * density),
                Math.round(16 * density),
                Math.round(8 * density));
        button.setIconSize(Math.round(22 * density));
        button.setIconPadding(Math.round(14 * density));
        button.setIconGravity(MaterialButton.ICON_GRAVITY_START);
        button.setMinHeight(Math.round(56 * activity.getResources().getDisplayMetrics().density));
        button.setMaxLines(2);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setCornerRadius(
                Math.round(12 * activity.getResources().getDisplayMetrics().density));
        button.setStrokeWidth(Math.round(2 * density));
        int primary = MaterialColors.getColor(button, R.attr.colorPrimary);
        int surface = MaterialColors.getColor(button, R.attr.colorSurface);
        int onPrimary = MaterialColors.getColor(button, R.attr.colorOnPrimary);
        int onSurface = MaterialColors.getColor(button, R.attr.colorOnSurface);
        int[][] states = {{android.R.attr.state_focused}, {}};
        button.setBackgroundTintList(
                new ColorStateList(
                        states,
                        new int[] {primary, ColorUtils.blendARGB(surface, primary, 0.07f)}));
        button.setTextColor(new ColorStateList(states, new int[] {onPrimary, onSurface}));
        button.setStrokeColor(new ColorStateList(states, new int[] {onPrimary, Color.TRANSPARENT}));
        button.setIconTint(new ColorStateList(states, new int[] {onPrimary, onSurface}));
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setEnabled(enabled);
        button.setFocusableInTouchMode(enabled);
        button.setAlpha(enabled ? 1f : 0.4f);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    AlertDialog show(Runnable onCancel) {
        int surface = MaterialColors.getColor(mRoot, R.attr.colorSurface);
        int primary = MaterialColors.getColor(mRoot, R.attr.colorPrimary);
        GradientDrawable background =
                new GradientDrawable(
                        GradientDrawable.Orientation.TR_BL,
                        new int[] {ColorUtils.blendARGB(surface, primary, 0.1f), surface});
        background.setCornerRadius(dp(24));
        AlertDialog dialog =
                new MaterialAlertDialogBuilder(mActivity)
                        .setBackgroundInsetTop(0)
                        .setBackgroundInsetBottom(0)
                        .setBackgroundInsetStart(0)
                        .setBackgroundInsetEnd(0)
                        .setBackground(background)
                        .setView(mRoot)
                        .setOnCancelListener(ignored -> onCancel.run())
                        .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            var metrics = mActivity.getResources().getDisplayMetrics();
            window.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            var attributes = window.getAttributes();
            attributes.x = dp(24);
            attributes.dimAmount = 0.55f;
            window.setAttributes(attributes);
            int width = Math.min(dp(mWide ? 740 : 460), Math.round(metrics.widthPixels * 0.92f));
            mRoot.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(
                            Math.round(metrics.heightPixels * 0.9f), View.MeasureSpec.AT_MOST));
            window.setLayout(width, mRoot.getMeasuredHeight());
        }
        View focus = mPreferredView != null ? mPreferredView : mFirstAction;
        if (focus != null) focus.requestFocus();
        return dialog;
    }
}
