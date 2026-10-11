// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import android.text.format.Formatter;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;

import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.tv.presentation.TvPresentation;
import org.chromium.components.browser_ui.util.DownloadUtils;
import org.chromium.components.offline_items_collection.ContentId;
import org.chromium.components.offline_items_collection.LaunchLocation;
import org.chromium.components.offline_items_collection.LegacyHelpers;
import org.chromium.components.offline_items_collection.OfflineContentProvider;
import org.chromium.components.offline_items_collection.OfflineItem;
import org.chromium.components.offline_items_collection.OfflineItemState;
import org.chromium.components.offline_items_collection.OpenParams;
import org.chromium.components.offline_items_collection.UpdateDelta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** UI-thread presentation; the existing provider owns normal-download storage and opening. */
final class TvDownloadsPanel implements OfflineContentProvider.Observer {
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    static final int PAGE_SIZE = 8;

    private final BraveActivity mActivity;
    private final TvBrowserSession mSession;
    private final TvBrowserSession.Context mContext;
    private final OfflineContentProvider mProvider;
    private final Consumer<OfflineItem> mRemoveHistory;
    private final Runnable mOnClose;
    private final Map<ContentId, TvPresentation.Action> mRows = new LinkedHashMap<>();
    private final Map<Integer, TvPresentation.Action> mDetailActions = new LinkedHashMap<>();
    private @Nullable AlertDialog mDialog;
    private @Nullable ContentId mSelected;
    private @Nullable TvPresentation.Description mStatus;
    private @Nullable TvPresentation.Action mRefreshAction;
    private int mStart;
    private int mRequest;
    private boolean mDestroyed;

    /**
     * Registers with a provider whose callbacks run on the UI thread. The caller owns the session
     * and must destroy this panel on close, pause or owner change. removeHistory removes only the
     * native record, preserving its file; onClose must dispose the panel before restoring focus.
     */
    TvDownloadsPanel(
            BraveActivity activity,
            TvBrowserSession session,
            OfflineContentProvider provider,
            Consumer<OfflineItem> removeHistory,
            Runnable onClose) {
        mActivity = activity;
        mSession = session;
        mContext = session.capture();
        mProvider = provider;
        mRemoveHistory = removeHistory;
        mOnClose = onClose;
        mProvider.addObserver(this);
    }

    private boolean isUsable() {
        return !mDestroyed
                && !mActivity.isFinishing()
                && !mActivity.isDestroyed()
                && mContext.tab != null
                && !mContext.tab.isIncognito()
                && mSession.isCurrentDocument(mContext);
    }

    void onBrowserStateChanged() {
        if (!isUsable()) destroy();
    }

    void show() {
        refresh();
    }

    void destroy() {
        if (mDestroyed) return;
        mDestroyed = true;
        mRequest++;
        mProvider.removeObserver(this);
        if (mDialog != null) mDialog.dismiss();
        mDialog = null;
        mRows.clear();
        mDetailActions.clear();
        mSelected = null;
        mStatus = null;
        mRefreshAction = null;
    }

    private void show(TvBrowserPanel panel, Runnable onBack) {
        if (!isUsable()) return;
        if (mDialog != null) mDialog.dismiss();
        mDialog = panel.show(onBack);
    }

    private void busy() {
        mRows.clear();
        mDetailActions.clear();
        mSelected = null;
        mStatus = null;
        mRefreshAction = null;
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(R.string.brave_tv_downloads),
                        mActivity.getString(R.string.brave_tv_loading_downloads),
                        R.string.brave_tv_controls);
        panel.addAction(R.string.brave_tv_controls, true, mOnClose);
        show(panel, mOnClose);
    }

    // The native provider has no paged query. Retain only this page, never a second full cache.
    private void refresh() {
        if (!isUsable()) return;
        int request = ++mRequest;
        busy();
        mProvider.getAllItems(
                items -> {
                    if (!isUsable() || request != mRequest) return;
                    items.removeIf(item -> !isNormalDownload(item));
                    items.sort(
                            Comparator.comparingLong((OfflineItem item) -> item.creationTimeMs)
                                    .reversed()
                                    .thenComparing(item -> item.id.id));
                    mStart =
                            Math.min(
                                    mStart,
                                    Math.max(0, ((items.size() - 1) / PAGE_SIZE) * PAGE_SIZE));
                    int end = Math.min(mStart + PAGE_SIZE, items.size());
                    List<OfflineItem> page = new ArrayList<>();
                    for (int i = mStart; i < end; i++) page.add(items.get(i).clone());
                    showList(page, end < items.size());
                });
    }

    private static boolean isNormalDownload(@Nullable OfflineItem item) {
        return item != null
                && !item.isOffTheRecord
                && !item.isTransient
                && item.id != null
                && item.id.id != null
                && !item.id.id.isEmpty()
                && LegacyHelpers.LEGACY_DOWNLOAD_NAMESPACE.equals(item.id.namespace);
    }

    private void showList(List<OfflineItem> items, boolean hasMore) {
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(R.string.brave_tv_downloads),
                        items.isEmpty()
                                ? mActivity.getString(R.string.brave_tv_empty_downloads)
                                : "",
                        0);
        for (OfflineItem item : items) {
            ContentId id = item.id;
            TvPresentation.Action row = panel.addItem(summary(item), 0, () -> loadItem(id));
            mRows.put(id, row);
        }
        panel.addAction(
                R.string.brave_tv_previous_downloads,
                mStart > 0,
                () -> {
                    mStart -= PAGE_SIZE;
                    refresh();
                });
        panel.addAction(
                R.string.brave_tv_more_downloads,
                hasMore,
                () -> {
                    mStart += PAGE_SIZE;
                    refresh();
                });
        mRefreshAction = panel.addAction(R.string.brave_tv_refresh_downloads, true, this::refresh);
        panel.addAction(R.string.brave_tv_controls, true, mOnClose);
        show(panel, mOnClose);
    }

    private String status(OfflineItem item) {
        if (item.externallyRemoved) return mActivity.getString(R.string.brave_tv_download_missing);
        if (isDangerous(item)) return mActivity.getString(R.string.brave_tv_download_blocked);
        int label =
                switch (item.state) {
                    case OfflineItemState.COMPLETE -> R.string.brave_tv_download_complete;
                    case OfflineItemState.IN_PROGRESS -> R.string.brave_tv_download_in_progress;
                    case OfflineItemState.PAUSED -> R.string.brave_tv_download_paused;
                    case OfflineItemState.PENDING -> R.string.brave_tv_download_pending;
                    case OfflineItemState.CANCELLED -> R.string.brave_tv_download_cancelled;
                    default -> R.string.brave_tv_download_failed;
                };
        return mActivity.getString(label)
                + " · "
                + Formatter.formatShortFileSize(mActivity, Math.max(0, item.receivedBytes));
    }

    private String summary(OfflineItem item) {
        return item.title + "\n" + status(item);
    }

    private static boolean isDangerous(OfflineItem item) {
        return item.isDangerous
                || DownloadUtils.shouldDisplayDownloadAsDangerous(item.dangerType, item.state);
    }

    private static boolean active(OfflineItem item) {
        return item.state == OfflineItemState.IN_PROGRESS
                || item.state == OfflineItemState.PENDING
                || item.state == OfflineItemState.PAUSED
                || (item.state == OfflineItemState.INTERRUPTED && item.isResumable);
    }

    private boolean available(int action, OfflineItem item) {
        if (!isNormalDownload(item)) return false;
        if (action == R.string.brave_tv_open_download)
            return item.state == OfflineItemState.COMPLETE
                    && item.isOpenable
                    && !item.externallyRemoved
                    && !isDangerous(item);
        if (action == R.string.brave_tv_pause_download)
            return !isDangerous(item) && item.state == OfflineItemState.IN_PROGRESS;
        if (action == R.string.brave_tv_resume_download)
            return !isDangerous(item)
                    && item.isResumable
                    && (item.state == OfflineItemState.PAUSED
                            || item.state == OfflineItemState.INTERRUPTED);
        if (action == R.string.brave_tv_cancel_download) return active(item);
        return !active(item);
    }

    private void loadItem(ContentId id) {
        lookup(id, item -> showItem(item, ""));
    }

    private void lookup(ContentId id, Consumer<OfflineItem> onResult) {
        if (!isUsable()) return;
        int request = ++mRequest;
        busy();
        mProvider.getItemById(
                id,
                item -> {
                    if (!isUsable() || request != mRequest) return;
                    if (!isNormalDownload(item) || !id.equals(item.id)) {
                        TvBrowserPanel panel =
                                new TvBrowserPanel(
                                        mActivity,
                                        mActivity.getString(R.string.brave_tv_downloads),
                                        mActivity.getString(R.string.brave_tv_download_unavailable),
                                        R.string.brave_tv_refresh_downloads);
                        panel.addAction(R.string.brave_tv_refresh_downloads, true, this::refresh);
                        panel.addAction(R.string.brave_tv_controls, true, mOnClose);
                        show(panel, this::refresh);
                        return;
                    }
                    onResult.accept(item.clone());
                });
    }

    private void showItem(OfflineItem item, String error) {
        mSelected = item.id;
        TvBrowserPanel panel =
                new TvBrowserPanel(mActivity, item.title, error, R.string.brave_tv_open_download);
        mStatus = panel.addDescription(status(item));
        for (int action :
                new int[] {
                    R.string.brave_tv_open_download,
                    R.string.brave_tv_pause_download,
                    R.string.brave_tv_resume_download,
                    R.string.brave_tv_cancel_download,
                    R.string.brave_tv_remove_download,
                    R.string.brave_tv_delete_download
                }) {
            mDetailActions.put(
                    action,
                    panel.addAction(
                            action,
                            available(action, item),
                            () -> {
                                if (action == R.string.brave_tv_cancel_download
                                        || action == R.string.brave_tv_remove_download
                                        || action == R.string.brave_tv_delete_download) {
                                    confirm(item.id, action);
                                } else {
                                    perform(item.id, action);
                                }
                            }));
        }
        mRefreshAction = panel.addAction(R.string.brave_tv_downloads, true, this::refresh);
        show(panel, this::refresh);
    }

    private void confirm(ContentId id, int action) {
        lookup(
                id,
                item -> {
                    if (!available(action, item)) {
                        showItem(item, mActivity.getString(R.string.brave_tv_download_changed));
                        return;
                    }
                    int description =
                            action == R.string.brave_tv_remove_download
                                    ? R.string.brave_tv_remove_download_scope
                                    : action == R.string.brave_tv_delete_download
                                            ? R.string.brave_tv_delete_download_scope
                                            : R.string.brave_tv_cancel_download_scope;
                    TvBrowserPanel panel =
                            new TvBrowserPanel(
                                    mActivity,
                                    mActivity.getString(action),
                                    mActivity.getString(description, item.title),
                                    R.string.cancel);
                    panel.addAction(R.string.cancel, true, () -> loadItem(id));
                    panel.addAction(action, true, () -> perform(id, action));
                    show(panel, () -> loadItem(id));
                });
    }

    private void perform(ContentId id, int action) {
        lookup(
                id,
                item -> {
                    if (!available(action, item)) {
                        showItem(item, mActivity.getString(R.string.brave_tv_download_changed));
                        return;
                    }
                    if (action == R.string.brave_tv_open_download) {
                        mProvider.openItem(new OpenParams(LaunchLocation.DOWNLOAD_HOME), id);
                        mOnClose.run();
                    } else {
                        if (action == R.string.brave_tv_pause_download) mProvider.pauseDownload(id);
                        if (action == R.string.brave_tv_resume_download)
                            mProvider.resumeDownload(id);
                        if (action == R.string.brave_tv_cancel_download)
                            mProvider.cancelDownload(id);
                        if (action == R.string.brave_tv_delete_download) mProvider.removeItem(id);
                        if (action == R.string.brave_tv_remove_download)
                            mRemoveHistory.accept(item);
                        refresh();
                    }
                });
    }

    @Override
    public void onItemsAdded(List<OfflineItem> items) {}

    @Override
    public void onItemRemoved(ContentId id) {
        if (!isUsable()) return;
        TvPresentation.Action row = mRows.get(id);
        if (row != null) {
            if (row.hasFocus() && mRefreshAction != null) mRefreshAction.requestFocus();
            row.setEnabled(false);
            row.setLabel(mActivity.getString(R.string.brave_tv_download_unavailable));
        }
        if (id.equals(mSelected)) {
            for (TvPresentation.Action action : mDetailActions.values()) {
                if (action.hasFocus() && mRefreshAction != null) mRefreshAction.requestFocus();
                action.setEnabled(false);
            }
            if (mStatus != null)
                mStatus.setText(mActivity.getString(R.string.brave_tv_download_unavailable));
        }
    }

    @Override
    public void onItemUpdated(OfflineItem item, UpdateDelta delta) {
        if (!isUsable()) return;
        if (!isNormalDownload(item)) {
            onItemRemoved(item.id);
            return;
        }
        TvPresentation.Action row = mRows.get(item.id);
        if (row != null) row.setLabel(summary(item));
        if (!item.id.equals(mSelected)) return;
        if (mStatus != null) mStatus.setText(status(item));
        for (Map.Entry<Integer, TvPresentation.Action> entry : mDetailActions.entrySet()) {
            boolean enabled = available(entry.getKey(), item);
            TvPresentation.Action action = entry.getValue();
            if (!enabled && action.hasFocus() && mRefreshAction != null)
                mRefreshAction.requestFocus();
            action.setEnabled(enabled);
        }
    }
}
