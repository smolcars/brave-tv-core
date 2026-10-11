// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.history.BrowsingHistoryBridge;
import org.chromium.chrome.browser.history.HistoryItem;
import org.chromium.chrome.browser.history.HistoryProvider;
import org.chromium.chrome.browser.preferences.Pref;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tv.presentation.TvPresentation;
import org.chromium.components.user_prefs.UserPrefs;
import org.chromium.content_public.browser.LoadUrlParams;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Owns a normal-session history query and its native TV dialogs. */
final class TvHistoryPanel implements HistoryProvider.BrowsingHistoryObserver {
    private static final int PAGE_SIZE = 8;

    private final BraveActivity mActivity;
    private final Tab mTab;
    private final Runnable mOnClose;
    private final BrowsingHistoryBridge mHistory;
    private final List<HistoryItem> mItems = new ArrayList<>();
    private final List<TvPresentation.Action> mRows = new ArrayList<>();
    private @Nullable HistoryItem mFocusedItem;
    private boolean mSeekingFocus;
    private int mFocusedIndex;
    private int mWindowStart;
    private int mNextIndex;
    private @Nullable AlertDialog mDialog;
    private int mStart;
    private boolean mHasMore;
    private boolean mQuerying;
    private boolean mRemoving;
    private boolean mReloadPending;
    private boolean mDestroyed;

    TvHistoryPanel(BraveActivity activity, Tab tab, Runnable onClose) {
        mActivity = activity;
        mTab = tab;
        mOnClose = onClose;
        mHistory =
                new BrowsingHistoryBridge(tab.getProfile()) {
                    @Override
                    public void onRemoveComplete() {
                        super.onRemoveComplete();
                        if (!isUsable()) return;
                        mRemoving = false;
                        mFocusedItem = null;
                        refresh();
                    }

                    @Override
                    public void onRemoveFailed() {
                        super.onRemoveFailed();
                        if (!isUsable()) return;
                        mRemoving = false;
                        showList(mActivity.getString(R.string.brave_tv_history_remove_failed));
                    }
                };
        mHistory.setObserver(this);
    }

    private boolean isUsable() {
        return !mDestroyed
                && !mActivity.isFinishing()
                && !mActivity.isDestroyed()
                && !mTab.isDestroyed()
                && !mTab.isIncognito()
                && mActivity.getActivityTab() == mTab;
    }

    void show() {
        refresh();
    }

    void destroy() {
        if (mDestroyed) return;
        mDestroyed = true;
        if (mDialog != null) mDialog.dismiss();
        mDialog = null;
        mHistory.destroy();
        mItems.clear();
        mRows.clear();
        mFocusedItem = null;
    }

    private void show(TvBrowserPanel panel, Runnable onCancel) {
        if (!isUsable()) return;
        if (mDialog != null) mDialog.dismiss();
        mDialog = panel.show(onCancel);
    }

    private void showBusy(int message) {
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(R.string.brave_tv_history),
                        mActivity.getString(message),
                        R.string.brave_tv_controls);
        panel.addAction(R.string.brave_tv_controls, true, mOnClose);
        show(panel, mOnClose);
    }

    private void refresh() {
        if (!isUsable()) return;
        if (mQuerying || mRemoving) {
            mReloadPending = true;
            return;
        }
        mReloadPending = false;
        rememberFocus();
        mSeekingFocus = mFocusedItem != null;
        restartQuery();
    }

    private void rememberFocus() {
        for (int i = 0; i < mRows.size(); i++) {
            if (!mRows.get(i).hasFocus()) continue;
            mFocusedIndex = mStart + i;
            mFocusedItem = mItems.get(mFocusedIndex - mWindowStart);
            return;
        }
    }

    private void restartQuery() {
        mItems.clear();
        mRows.clear();
        mWindowStart = mNextIndex = 0;
        mHasMore = false;
        mQuerying = true;
        showBusy(R.string.brave_tv_loading_history);
        mHistory.queryHistory("", null);
    }

    @Override
    public void onQueryHistoryComplete(List<HistoryItem> items, boolean hasMorePotentialMatches) {
        if (!isUsable()) return;
        mQuerying = false;
        if (mReloadPending) {
            refresh();
            return;
        }
        // Native queries return at most150 items. Retain only the page boundary.
        int keep = Math.min(PAGE_SIZE * 2, mItems.size());
        mItems.subList(0, mItems.size() - keep).clear();
        mItems.addAll(items);
        mNextIndex += items.size();
        mWindowStart = mNextIndex - mItems.size();
        mHasMore = hasMorePotentialMatches;
        if (mSeekingFocus && mFocusedItem != null) {
            for (int i = 0; i < mItems.size(); i++) {
                HistoryItem item = mItems.get(i);
                if (item.getUrl().equals(mFocusedItem.getUrl())
                        && item.getTimestamp() == mFocusedItem.getTimestamp()) {
                    mFocusedIndex = mWindowStart + i;
                    mStart = (mFocusedIndex / PAGE_SIZE) * PAGE_SIZE;
                    mSeekingFocus = false;
                    break;
                }
            }
            if (mSeekingFocus && !mHasMore) {
                mSeekingFocus = false;
                mFocusedItem = null;
                if (mStart < mWindowStart) {
                    restartQuery();
                    return;
                }
            }
        }
        ensurePage();
    }

    @Override
    public void onHistoryDeleted() {
        refresh();
    }

    @Override
    public void hasOtherFormsOfBrowsingData(boolean hasOtherForms) {}

    @Override
    public void onQueryAppsComplete(List<String> items) {}

    private void ensurePage() {
        if (!isUsable() || mQuerying || mRemoving) return;
        if (mSeekingFocus || (mStart + PAGE_SIZE > mNextIndex && mHasMore)) {
            mQuerying = true;
            mRows.clear();
            showBusy(R.string.brave_tv_loading_history);
            mHistory.queryHistoryContinuation();
            return;
        }
        if (!mHasMore && mStart >= mNextIndex) {
            mStart = Math.max(0, ((mNextIndex - 1) / PAGE_SIZE) * PAGE_SIZE);
        }
        if (mStart < mWindowStart) {
            restartQuery();
            return;
        }
        showList("");
    }

    private void page(int start) {
        if (!isUsable() || mQuerying || mRemoving) return;
        mFocusedItem = null;
        mSeekingFocus = false;
        mFocusedIndex = mStart = start;
        ensurePage();
    }

    private void showList(String error) {
        if (!isUsable()) return;
        int end = Math.min(mStart + PAGE_SIZE, mNextIndex);
        int focused = Math.max(mStart, Math.min(mFocusedIndex, end - 1));
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(R.string.brave_tv_history),
                        error.isEmpty() && mItems.isEmpty()
                                ? mActivity.getString(R.string.brave_tv_empty_history)
                                : error,
                        end > mStart ? focused - mStart + 1 : R.string.brave_tv_controls);
        mRows.clear();
        for (int i = mStart; i < end; i++) {
            int index = i;
            HistoryItem item = mItems.get(i - mWindowStart);
            mRows.add(
                    panel.addItem(
                            item.getTitle() + "\n" + item.getDomain(),
                            i - mStart + 1,
                            () -> {
                                mFocusedItem = item;
                                mFocusedIndex = index;
                                showItem(item);
                            }));
        }
        panel.addAction(
                R.string.brave_tv_previous_history,
                mStart > 0,
                () -> page(Math.max(0, mStart - PAGE_SIZE)));
        panel.addAction(
                R.string.brave_tv_more_history, end < mNextIndex || mHasMore, () -> page(end));
        panel.addAction(R.string.brave_tv_controls, true, mOnClose);
        show(panel, mOnClose);
    }

    private boolean canRemove() {
        return isUsable()
                && !mRemoving
                && UserPrefs.get(mTab.getProfile()).getBoolean(Pref.ALLOW_DELETING_BROWSER_HISTORY);
    }

    private String visitSummary(HistoryItem item) {
        return DateFormat.getDateTimeInstance().format(new Date(item.getTimestamp()))
                + "\n"
                + item.getDomain();
    }

    private void showItem(HistoryItem item) {
        if (!isUsable()) return;
        mRows.clear();
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        item.getTitle(),
                        visitSummary(item),
                        R.string.brave_tv_open_history);
        panel.addAction(
                R.string.brave_tv_open_history,
                true,
                () -> {
                    if (!isUsable()) return;
                    destroy();
                    mTab.loadUrl(new LoadUrlParams(item.getUrl()));
                });
        panel.addAction(R.string.brave_tv_remove_history, canRemove(), () -> confirmRemoval(item));
        panel.addAction(R.string.brave_tv_history, true, () -> showList(""));
        show(panel, () -> showList(""));
    }

    private void confirmRemoval(HistoryItem item) {
        if (!canRemove()) return;
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(R.string.brave_tv_remove_history_question)
                                + " "
                                + item.getTitle(),
                        visitSummary(item),
                        R.string.cancel);
        panel.addAction(R.string.cancel, true, () -> showItem(item));
        panel.addAction(
                R.string.brave_tv_remove_history,
                true,
                () -> {
                    if (!canRemove()) return;
                    mRemoving = true;
                    showBusy(R.string.brave_tv_removing_history);
                    mHistory.markItemForRemoval(item);
                    mHistory.removeItems();
                });
        show(panel, () -> showItem(item));
    }
}
