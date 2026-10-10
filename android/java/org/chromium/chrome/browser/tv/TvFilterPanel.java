// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import org.chromium.brave_shields.mojom.FilterListAndroidHandler;
import org.chromium.brave_shields.mojom.FilterUpdateResult;
import org.chromium.brave_shields.mojom.FilterUpdateStatus;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.lifetime.ApplicationLifetime;
import org.chromium.chrome.browser.shields.FilterListServiceFactory;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.mojo_base.mojom.Value;

import java.text.DateFormat;
import java.util.Date;
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
    private int mUpdateGeneration;

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
        refresh(0);
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
        show(panel, mOnClose);
    }

    private void show(TvBrowserPanel panel, Runnable onClose) {
        if (!isUsable()) return;
        if (mDialog != null) mDialog.dismiss();
        mDialog = panel.show(onClose);
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
                        preferred);
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
                        refresh(index + 1);
                    });
        }
        panel.addItem(
                        mActivity.getString(R.string.brave_tv_filter_updates),
                        R.string.brave_tv_filter_updates,
                        () -> loadUpdateStatus(false))
                .setIcon(R.drawable.ic_refresh_24dp);
        panel.addAction(
                R.string.brave_tv_previous_filters,
                mStart > 0,
                () -> {
                    mStart -= PAGE_SIZE;
                    showList(0);
                });
        panel.addAction(
                R.string.brave_tv_more_filters,
                end < mFilters.length,
                () -> {
                    mStart = end;
                    showList(0);
                });
        panel.addAction(R.string.brave_tv_settings, true, mOnClose);
        show(panel);
    }

    private void returnToFilters() {
        ++mUpdateGeneration;
        showList(R.string.brave_tv_filter_updates);
    }

    private void loadUpdateStatus(boolean check) {
        if (!isUsable()) return;
        int generation = ++mUpdateGeneration;
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(R.string.brave_tv_filter_updates),
                        mActivity.getString(R.string.brave_tv_update_checking),
                        R.string.content_filters_title);
        panel.addAction(R.string.content_filters_title, true, this::returnToFilters);
        show(panel, this::returnToFilters);
        if (mHandler == null) {
            showUpdateStatus(null, generation);
        } else if (check) {
            mHandler.checkIndependentUpdate(status -> showUpdateStatus(status, generation));
        } else {
            mHandler.getIndependentUpdateStatus(status -> showUpdateStatus(status, generation));
        }
    }

    private void showUpdateStatus(@Nullable FilterUpdateStatus status, int generation) {
        if (!isUsable() || generation != mUpdateGeneration) return;
        int message = R.string.brave_tv_update_automatic;
        if (status == null) {
            message = R.string.brave_tv_update_unavailable;
        } else {
            switch (status.result) {
                case FilterUpdateResult.CURRENT:
                    message =
                            status.restartRequired
                                    ? R.string.brave_tv_update_staged
                                    : R.string.brave_tv_update_current;
                    break;
                case FilterUpdateResult.STAGED:
                    message = R.string.brave_tv_update_staged;
                    break;
                case FilterUpdateResult.DOWNLOAD_FAILED:
                    message = R.string.brave_tv_update_download_failed;
                    break;
                case FilterUpdateResult.REJECTED:
                    message = R.string.brave_tv_update_rejected;
                    break;
                case FilterUpdateResult.SAVE_FAILED:
                    message = R.string.brave_tv_update_save_failed;
                    break;
                default:
                    break;
            }
        }
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(R.string.brave_tv_filter_updates),
                        mActivity.getString(message),
                        R.string.brave_tv_check_updates);
        if (status != null) {
            panel.addDescription(
                    status.active == null
                            ? mActivity.getString(R.string.brave_tv_update_packaged)
                            : mActivity.getString(
                                    R.string.brave_tv_update_active,
                                    DateFormat.getDateInstance()
                                            .format(new Date(status.active.publishedUnixMs))));
            panel.addDescription(
                    status.lastAttemptUnixMs == null
                            ? mActivity.getString(R.string.brave_tv_update_never_checked)
                            : mActivity.getString(
                                    R.string.brave_tv_update_last_attempt,
                                    DateFormat.getDateTimeInstance()
                                            .format(new Date(status.lastAttemptUnixMs))));
            if (status.restartRequired && message != R.string.brave_tv_update_staged) {
                panel.addDescription(mActivity.getString(R.string.brave_tv_update_staged));
            }
        }
        panel.addAction(
                R.string.brave_tv_check_updates, status != null, () -> loadUpdateStatus(true));
        if (status != null && status.restartRequired) {
            panel.addAction(R.string.brave_tv_restart_browser, true, this::confirmRestart);
        }
        panel.addAction(R.string.content_filters_title, true, this::returnToFilters);
        show(panel, this::returnToFilters);
    }

    private void confirmRestart() {
        if (!isUsable()) return;
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(R.string.brave_tv_restart_browser),
                        mActivity.getString(R.string.brave_tv_restart_scope),
                        R.string.cancel);
        panel.addAction(R.string.cancel, true, () -> loadUpdateStatus(false));
        panel.addAction(
                R.string.brave_tv_restart_browser,
                true,
                () -> {
                    if (isUsable()) ApplicationLifetime.terminate(true);
                });
        show(panel, () -> loadUpdateStatus(false));
    }
}
