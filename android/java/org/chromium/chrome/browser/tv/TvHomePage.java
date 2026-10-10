// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import org.chromium.base.ContextUtils;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.ui.native_page.BasicNativePage;
import org.chromium.chrome.browser.ui.native_page.NativePage;
import org.chromium.chrome.browser.ui.native_page.NativePageHost;
import org.chromium.content_public.browser.LoadUrlParams;

/** A native home with no feed, promotions, analytics, or web renderer. */
public final class TvHomePage extends BasicNativePage {
    private final BraveActivity mActivity;
    private final Tab mTab;
    private final LinearLayout mContent;
    private boolean mDestroyed;

    public static @Nullable NativePage createIfTelevision(NativePageHost host, Tab tab) {
        if ((host.getContext().getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_TYPE_MASK)
                != Configuration.UI_MODE_TYPE_TELEVISION) return null;
        if (!(ContextUtils.activityFromContext(host.getContext())
                instanceof BraveActivity activity)) {
            return null;
        }
        return new TvHomePage(host, tab, activity);
    }

    private TvHomePage(NativePageHost host, Tab tab, BraveActivity activity) {
        super(host);
        mActivity = activity;
        mTab = tab;
        mContent = new LinearLayout(activity);
        mContent.setOrientation(LinearLayout.VERTICAL);
        mContent.setPadding(dp(52), dp(36), dp(52), dp(24));
        int surface = MaterialColors.getColor(mContent, R.attr.colorSurface);
        int primary = MaterialColors.getColor(mContent, R.attr.colorPrimary);
        mContent.setBackground(
                new GradientDrawable(
                        GradientDrawable.Orientation.TR_BL,
                        new int[] {ColorUtils.blendARGB(surface, primary, 0.16f), surface}));
        TextView heading =
                addText(
                        tab.isIncognito()
                                ? R.string.brave_tv_private_home
                                : R.string.brave_tv_home_heading,
                        36,
                        false);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setLetterSpacing(-0.02f);
        addText(
                tab.isIncognito()
                        ? R.string.brave_tv_private_home_description
                        : R.string.brave_tv_home_tagline,
                18,
                true);

        LinearLayout actions = new LinearLayout(activity);
        actions.setBaselineAligned(false);
        actions.setPadding(0, dp(24), 0, dp(8));
        mContent.addView(actions);
        MaterialButton address =
                addButton(
                        actions,
                        R.string.brave_tv_address,
                        R.drawable.ic_search,
                        false,
                        2,
                        () -> activity.onMenuOrKeyboardAction(R.id.focus_url_bar, false));
        MaterialButton controls =
                addButton(
                        actions,
                        R.string.brave_tv_controls,
                        R.drawable.ic_tune,
                        false,
                        1,
                        activity::showTvBrowserControls);
        MaterialButton bookmarks =
                addButton(
                        actions,
                        R.string.brave_tv_bookmarks,
                        R.drawable.ic_bookmark_menu_view,
                        false,
                        1,
                        activity::showTvBookmarks);
        View space = new View(activity);
        space.setMinimumHeight(dp(24));
        mContent.addView(space, new LinearLayout.LayoutParams(1, 0, 1));
        addText(R.string.brave_tv_attribution, 14, true);
        LinearLayout links = new LinearLayout(activity);
        links.setPadding(0, dp(8), 0, 0);
        mContent.addView(links);
        MaterialButton source =
                addButton(
                        links,
                        R.string.brave_tv_source,
                        R.drawable.ic_code,
                        true,
                        1,
                        () -> open("https://github.com/smolcars/brave-tv"));
        MaterialButton licenses =
                addButton(
                        links,
                        R.string.brave_tv_licenses,
                        R.drawable.ic_info_outline,
                        true,
                        1,
                        () -> open("chrome://credits"));
        address.setNextFocusDownId(source.getId());
        controls.setNextFocusDownId(licenses.getId());
        bookmarks.setNextFocusDownId(licenses.getId());
        source.setNextFocusUpId(address.getId());
        licenses.setNextFocusUpId(controls.getId());
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(mContent);
        // Chromium focuses the root after attachment; wait for the buttons to be laid out.
        scroll.setOnFocusChangeListener(
                (view, focused) -> {
                    if (!focused) return;
                    address.post(
                            () -> {
                                if (!mDestroyed
                                        && !activity.isFinishing()
                                        && !activity.isDestroyed()
                                        && scroll.isAttachedToWindow()
                                        && scroll.isFocused()) {
                                    address.requestFocus();
                                }
                            });
                });
        initWithView(scroll);
        address.requestFocus();
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    private TextView addText(int text, int size, boolean secondary) {
        TextView view = new TextView(mActivity);
        view.setText(text);
        view.setTextSize(size);
        int foreground = MaterialColors.getColor(mContent, R.attr.colorOnSurface);
        int surface = MaterialColors.getColor(mContent, R.attr.colorSurface);
        view.setTextColor(
                secondary ? ColorUtils.blendARGB(surface, foreground, 0.75f) : foreground);
        view.setPadding(dp(4), 0, dp(4), dp(10));
        mContent.addView(view);
        return view;
    }

    private MaterialButton addButton(
            LinearLayout row, int label, int icon, boolean compact, float weight, Runnable action) {
        MaterialButton button =
                TvBrowserPanel.createButton(
                        mActivity,
                        mActivity.getString(label),
                        true,
                        () -> {
                            if (!mDestroyed
                                    && !mActivity.isFinishing()
                                    && !mActivity.isDestroyed()
                                    && !mTab.isDestroyed()
                                    && mActivity.getActivityTab() == mTab) {
                                action.run();
                            }
                        });
        int primary = MaterialColors.getColor(button, R.attr.colorPrimary);
        int surface = MaterialColors.getColor(button, R.attr.colorSurface);
        int onPrimary = MaterialColors.getColor(button, R.attr.colorOnPrimary);
        int onSurface = MaterialColors.getColor(button, R.attr.colorOnSurface);
        int[][] states = {{android.R.attr.state_focused}, {}};
        button.setBackgroundTintList(
                new ColorStateList(
                        states,
                        new int[] {primary, ColorUtils.blendARGB(surface, primary, 0.07f)}));
        button.setStrokeColor(
                new ColorStateList(
                        states,
                        new int[] {onPrimary, ColorUtils.blendARGB(surface, primary, 0.12f)}));
        button.setIconTint(new ColorStateList(states, new int[] {onPrimary, onSurface}));
        button.setIconResource(icon);
        button.setIconSize(dp(compact ? 20 : 28));
        button.setIconPadding(dp(12));
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setTextSize(compact ? 15 : 20);
        button.setMinHeight(dp(compact ? 52 : 88));
        button.setCornerRadius(dp(compact ? 12 : 18));
        button.setPaddingRelative(dp(compact ? 16 : 22), dp(8), dp(16), dp(8));
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        row.addView(button, params);
        return button;
    }

    private void open(String url) {
        mHost.loadUrl(new LoadUrlParams(url), mTab.isIncognito());
    }

    @Override
    public String getTitle() {
        return mActivity.getString(
                mTab.isIncognito() ? R.string.brave_tv_private_home : R.string.brave_tv_home);
    }

    @Override
    public String getHost() {
        return "newtab";
    }

    @Override
    public void destroy() {
        mDestroyed = true;
        super.destroy();
    }
}
