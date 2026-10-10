// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import android.content.res.Configuration;
import android.view.View;

import androidx.annotation.Nullable;

import org.chromium.base.ContextUtils;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tv.presentation.TvPresentation;
import org.chromium.chrome.browser.ui.native_page.BasicNativePage;
import org.chromium.chrome.browser.ui.native_page.NativePage;
import org.chromium.chrome.browser.ui.native_page.NativePageHost;
import org.chromium.content_public.browser.LoadUrlParams;

import java.util.ArrayList;
import java.util.List;

/** A native home with no feed, promotions, analytics, or web renderer. */
public final class TvHomePage extends BasicNativePage {
    private final BraveActivity mActivity;
    private final Tab mTab;
    private final View mContent;
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
        List<TvPresentation.Action> links = new ArrayList<>();
        if (!tab.isIncognito() && TvPhoneRemote.available()) {
            links.add(action(R.string.brave_tv_phone, activity::showTvPhoneRemote));
        }
        links.add(
                action(
                        R.string.brave_tv_source,
                        () -> open("https://github.com/smolcars/brave-tv")));
        links.add(action(R.string.brave_tv_licenses, () -> open("chrome://credits")));
        mContent =
                TvPresentation.home(
                        activity,
                        activity.getString(
                                tab.isIncognito()
                                        ? R.string.brave_tv_private_home
                                        : R.string.brave_tv_home_heading),
                        activity.getString(
                                tab.isIncognito()
                                        ? R.string.brave_tv_private_home_description
                                        : R.string.brave_tv_home_tagline),
                        activity.getString(R.string.brave_tv_attribution),
                        List.of(
                                action(
                                        R.string.brave_tv_address,
                                        () ->
                                                activity.onMenuOrKeyboardAction(
                                                        R.id.focus_url_bar, false)),
                                action(R.string.brave_tv_controls, activity::showTvBrowserControls),
                                action(R.string.brave_tv_bookmarks, activity::showTvBookmarks)),
                        links);
        initWithView(mContent);
    }

    private TvPresentation.Action action(int label, Runnable callback) {
        return new TvPresentation.Action(
                mActivity.getString(label),
                true,
                () -> {
                    if (!mDestroyed
                            && !mActivity.isFinishing()
                            && !mActivity.isDestroyed()
                            && !mTab.isDestroyed()
                            && mActivity.getActivityTab() == mTab) {
                        callback.run();
                    }
                });
    }

    private void open(String url) {
        mHost.loadUrl(new LoadUrlParams(url), mTab.isIncognito());
    }

    void focusAddress() {
        if (!mDestroyed) TvPresentation.focusHome(mContent);
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
        TvPresentation.dispose(mContent);
        super.destroy();
    }
}
