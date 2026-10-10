// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import org.chromium.brave_shields.mojom.FilterListAndroidHandler;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.shields.FilterListServiceFactory;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.mojo_base.mojom.Value;

import java.util.Map;

/** Owns optional-list preferences and the connection behind their native TV panel. */
final class TvFilterPanel {
    private static final int PAGE_SIZE = 8;
    private final BraveActivity mActivity;
    private final Tab mTab;
    private final Runnable mOnClose;
    private @Nullable FilterListAndroidHandler mHandler;
    private @Nullable AlertDialog mDialog;
    private Value[] mFilters = new Value[0];
    private int mStart;
    private boolean mLoading;
    private boolean mDestroyed;

    TvFilterPanel(BraveActivity activity, Tab tab, Runnable onClose) {
        mActivity = activity;
        mTab = tab;
        mOnClose = onClose;
        mHandler =
                FilterListServiceFactory.getInstance()
                        .getFilterListAndroidHandler(
                                tab.getProfile().getOriginalProfile(),
                                error -> {
                                    if (!isUsable()) return;
                                    if (mHandler != null) mHandler.close();
                                    mHandler = null;
                                    showMessage(R.string.brave_tv_filters_failed);
                                });
    }

    private boolean isUsable() {
        return !mDestroyed
                && !mActivity.isFinishing()
                && !mActivity.isDestroyed()
                && !mTab.isDestroyed()
                && mActivity.getActivityTab() == mTab;
    }

    void show() {
        refresh(-1);
    }

    void destroy() {
        if (mDestroyed) return;
        mDestroyed = true;
        if (mDialog != null) mDialog.dismiss();
        mDialog = null;
        if (mHandler != null) mHandler.close();
        mHandler = null;
    }

    private void show(TvBrowserPanel panel) {
        if (!isUsable()) return;
        if (mDialog != null) mDialog.dismiss();
        mDialog = panel.show(mOnClose);
    }

    private void showMessage(int message) {
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(R.string.content_filters_title),
                        mActivity.getString(message),
                        R.string.brave_tv_settings);
        panel.addAction(R.string.brave_tv_settings, true, mOnClose);
        show(panel);
    }

    private void refresh(int preferred) {
        if (!isUsable()) return;
        if (mHandler == null) {
            showMessage(R.string.brave_tv_filters_failed);
            return;
        }
        mLoading = true;
        showMessage(R.string.brave_tv_loading_filters);
        mHandler.getFilterLists(
                lists -> {
                    if (!isUsable() || mHandler == null) return;
                    mLoading = false;
                    mFilters = lists.storage;
                    showList(preferred);
                });
    }

    private void showList(int preferred) {
        if (!isUsable()) return;
        mStart = Math.min(mStart, Math.max(0, ((mFilters.length - 1) / PAGE_SIZE) * PAGE_SIZE));
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(R.string.content_filters_title),
                        mActivity.getString(
                                mFilters.length == 0
                                        ? R.string.brave_tv_no_filters
                                        : R.string.brave_tv_filters_scope),
                        preferred + 1);
        int end = Math.min(mStart + PAGE_SIZE, mFilters.length);
        for (int i = mStart; i < end; i++) {
            int index = i;
            Map<String, Value> list = mFilters[i].getDictionaryValue().storage;
            String title = list.get("title").getStringValue();
            String uuid = list.get("uuid").getStringValue();
            boolean enabled = list.get("enabled").getBoolValue();
            panel.addItem(
                    mActivity.getString(
                            enabled ? R.string.brave_tv_filter_on : R.string.brave_tv_filter_off,
                            title),
                    index + 1,
                    () -> {
                        if (!isUsable() || mHandler == null || mLoading) return;
                        mHandler.enableFilter(uuid, !enabled);
                        refresh(index);
                    });
        }
        panel.addAction(
                R.string.brave_tv_previous_filters,
                mStart > 0,
                () -> {
                    mStart -= PAGE_SIZE;
                    showList(-1);
                });
        panel.addAction(
                R.string.brave_tv_more_filters,
                end < mFilters.length,
                () -> {
                    mStart = end;
                    showList(-1);
                });
        panel.addAction(R.string.brave_tv_settings, true, mOnClose);
        show(panel);
    }
}
