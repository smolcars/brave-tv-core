// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import android.content.res.Configuration;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

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
        mContent.setGravity(Gravity.CENTER_VERTICAL);
        mContent.setPadding(dp(48), dp(24), dp(48), dp(24));
        mContent.setBackgroundColor(MaterialColors.getColor(mContent, R.attr.colorSurface));
        addText(
                tab.isIncognito() ? R.string.brave_tv_private_home : R.string.brave_tv_home_tagline,
                28);
        if (tab.isIncognito()) addText(R.string.brave_tv_private_home_description, 16);
        MaterialButton address =
                addButton(
                        R.string.brave_tv_address,
                        () -> activity.onMenuOrKeyboardAction(R.id.focus_url_bar, false));
        addButton(R.string.brave_tv_controls, activity::showTvBrowserControls);
        addText(R.string.brave_tv_attribution, 16);
        addButton(R.string.brave_tv_source, () -> open("https://github.com/smolcars/brave-tv"));
        addButton(R.string.brave_tv_licenses, () -> open("chrome://credits"));
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

    private void addText(int text, int size) {
        TextView view = new TextView(mActivity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(MaterialColors.getColor(mContent, R.attr.colorOnSurface));
        view.setPadding(dp(8), dp(12), dp(8), dp(12));
        mContent.addView(view);
    }

    private MaterialButton addButton(int label, Runnable action) {
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
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        mContent.addView(button, params);
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
