// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import android.content.res.Configuration;
import android.view.View;

import androidx.annotation.Nullable;

import org.chromium.base.CallbackController;
import org.chromium.base.ContextUtils;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.bookmarks.BookmarkModel;
import org.chromium.chrome.browser.bookmarks.BookmarkModelObserver;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabObserver;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tabmodel.TabModelObserver;
import org.chromium.chrome.browser.tabmodel.TabModelUtils;
import org.chromium.chrome.browser.tv.presentation.TvPresentation;
import org.chromium.chrome.browser.ui.native_page.BasicNativePage;
import org.chromium.chrome.browser.ui.native_page.NativePage;
import org.chromium.chrome.browser.ui.native_page.NativePageHost;
import org.chromium.components.bookmarks.BookmarkId;
import org.chromium.components.bookmarks.BookmarkItem;
import org.chromium.content_public.browser.LoadUrlParams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A native home with no feed, promotions, analytics, or web renderer. */
public final class TvHomePage extends BasicNativePage {
    private final BraveActivity mActivity;
    private final Tab mTab;
    private final View mContent;
    private final TvPresentation.HomeState mState;
    private final Map<BookmarkId, SavedPage> mFavorites = new HashMap<>();
    private final Map<Integer, TvPresentation.Action> mRecentActions = new HashMap<>();
    private final List<Tab> mObservedTabs = new ArrayList<>();
    private @Nullable BookmarkModel mBookmarks;
    private @Nullable TabModel mNormalTabs;
    private @Nullable CallbackController mLoadingCallbacks;
    private boolean mDestroyed;
    private final BookmarkModelObserver mBookmarkObserver =
            new BookmarkModelObserver() {
                @Override
                public void bookmarkModelChanged() {
                    refreshFavorites();
                }
            };
    private final TabModelObserver mTabModelObserver =
            new TabModelObserver() {
                @Override
                public void didAddTab(Tab tab, int type, int creationState, boolean selected) {
                    refreshRecentTabs();
                }

                @Override
                public void didSelectTab(Tab tab, int type, int lastId) {
                    refreshRecentTabs();
                }

                @Override
                public void tabRemoved(Tab tab) {
                    refreshRecentTabs();
                }
            };
    private final TabObserver mRecentTabObserver =
            new TabObserver() {
                @Override
                public void onTitleUpdated(Tab tab) {
                    refreshRecentTabs();
                }

                @Override
                public void onUrlUpdated(Tab tab) {
                    refreshRecentTabs();
                }

                @Override
                public void onContentChanged(Tab tab) {
                    refreshRecentTabs();
                }
            };

    private static final class SavedPage {
        final TvPresentation.Action action;
        String url;

        SavedPage(TvPresentation.Action action, String url) {
            this.action = action;
            this.url = url;
        }
    }

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
        mState =
                new TvPresentation.HomeState(
                        !tab.isIncognito(),
                        activity.getString(R.string.brave_tv_home_favorites),
                        activity.getString(R.string.brave_tv_home_empty_favorites),
                        activity.getString(R.string.brave_tv_home_recent_tabs),
                        activity.getString(R.string.brave_tv_home_empty_recent_tabs),
                        activity.getString(R.string.brave_tv_loading_bookmarks));
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
                                action(R.string.brave_tv_tabs, activity::showTvTabs),
                                action(R.string.brave_tv_bookmarks, activity::showTvBookmarks),
                                action(
                                        R.string.brave_tv_settings,
                                        () ->
                                                activity.onMenuOrKeyboardAction(
                                                        R.id.preferences_id, false))),
                        links,
                        mState);
        mContent.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View view) {
                        // NativePage assignment can follow attachment in the same UI task.
                        view.post(TvHomePage.this::startObserving);
                    }

                    @Override
                    public void onViewDetachedFromWindow(View view) {
                        stopObserving();
                    }
                });
        initWithView(mContent);
    }

    private boolean isCurrentHome() {
        return !mDestroyed
                && !mActivity.isFinishing()
                && !mActivity.isDestroyed()
                && !mTab.isDestroyed()
                && mActivity.getActivityTab() == mTab
                && mTab.getNativePage() == this
                && mContent.isAttachedToWindow();
    }

    private void startObserving() {
        if (!isCurrentHome() || mTab.isIncognito() || mBookmarks != null) return;
        mBookmarks = BookmarkModel.getForProfile(mTab.getProfile().getOriginalProfile());
        mBookmarks.addObserver(mBookmarkObserver);
        if (!mBookmarks.isBookmarkModelLoaded()) {
            mLoadingCallbacks = new CallbackController();
            mBookmarks.finishLoadingBookmarkModel(
                    mLoadingCallbacks.makeCancelable(this::refreshFavorites));
        }
        if (mActivity.areTabModelsInitialized()) {
            mNormalTabs = mActivity.getTabModelSelector().getModel(false);
            mNormalTabs.addObserver(mTabModelObserver);
        }
        refreshFavorites();
        refreshRecentTabs();
    }

    private void stopObserving() {
        if (mBookmarks != null) mBookmarks.removeObserver(mBookmarkObserver);
        if (mNormalTabs != null) mNormalTabs.removeObserver(mTabModelObserver);
        if (mLoadingCallbacks != null) mLoadingCallbacks.destroy();
        for (Tab tab : mObservedTabs) tab.removeObserver(mRecentTabObserver);
        mObservedTabs.clear();
        mBookmarks = null;
        mNormalTabs = null;
        mLoadingCallbacks = null;
    }

    private void refreshFavorites() {
        if (!isCurrentHome() || mBookmarks == null) return;
        if (!mBookmarks.isBookmarkModelLoaded()) {
            mState.setFavorites(List.of(), true);
            return;
        }
        BookmarkId folder = mBookmarks.getDefaultBookmarkFolder();
        List<TvPresentation.Action> actions = new ArrayList<>();
        List<BookmarkId> ids = new ArrayList<>();
        if (folder != null) {
            // Home is a bounded preview; the bookmark panel retains the entire folder.
            int end = Math.min(64, mBookmarks.getChildCount(folder));
            for (int i = 0; i < end; i++) {
                BookmarkId id = mBookmarks.getChildAt(folder, i);
                if (id == null) continue;
                BookmarkItem item = mBookmarks.getBookmarkById(id);
                if (item == null || item.isFolder() || !item.getUrl().isValid()) continue;
                String url = item.getUrl().getSpec();
                SavedPage saved = mFavorites.get(id);
                if (saved == null) {
                    saved =
                            new SavedPage(
                                    new TvPresentation.Action("", true, () -> openFavorite(id)),
                                    url);
                    mFavorites.put(id, saved);
                }
                saved.url = url;
                saved.action.setLabel(item.getTitle() + "\n" + item.getUrlForDisplay());
                ids.add(id);
                actions.add(saved.action);
                if (actions.size() == 6) break;
            }
        }
        mFavorites.keySet().retainAll(ids);
        mState.setFavorites(List.copyOf(actions), false);
    }

    private void openFavorite(BookmarkId id) {
        if (!isCurrentHome() || mBookmarks == null) return;
        SavedPage saved = mFavorites.get(id);
        BookmarkItem current = mBookmarks.getBookmarkById(id);
        if (saved == null
                || current == null
                || current.isFolder()
                || !current.getUrl().getSpec().equals(saved.url)) {
            refreshFavorites();
            return;
        }
        open(saved.url);
    }

    private void refreshRecentTabs() {
        if (!isCurrentHome() || mNormalTabs == null) return;
        List<Tab> recent = new ArrayList<>();
        for (int i = 0; i < mNormalTabs.getCount(); i++) {
            Tab tab = mNormalTabs.getTabAt(i);
            if (tab == null
                    || tab == mTab
                    || tab.isDestroyed()
                    || tab.isNativePage()
                    || !tab.getUrl().isValid()) continue;
            int rank = 0;
            while (rank < recent.size()
                    && recent.get(rank).getTimestampMillis() >= tab.getTimestampMillis()) rank++;
            if (rank >= 4) continue;
            recent.add(rank, tab);
            if (recent.size() > 4) recent.remove(4);
        }
        for (Tab tab : mObservedTabs) tab.removeObserver(mRecentTabObserver);
        mObservedTabs.clear();
        List<Integer> ids = new ArrayList<>();
        List<TvPresentation.Action> actions = new ArrayList<>();
        for (Tab tab : recent) {
            int id = tab.getId();
            TvPresentation.Action action = mRecentActions.get(id);
            if (action == null) {
                action = new TvPresentation.Action("", true, () -> selectRecentTab(id));
                mRecentActions.put(id, action);
            }
            action.setLabel(tab.getTitle() + "\n" + tab.getUrl().getHost());
            ids.add(id);
            actions.add(action);
            tab.addObserver(mRecentTabObserver);
            mObservedTabs.add(tab);
        }
        mRecentActions.keySet().retainAll(ids);
        mState.setRecentTabs(List.copyOf(actions));
    }

    private void selectRecentTab(int id) {
        if (!isCurrentHome() || mNormalTabs == null) return;
        Tab tab = mNormalTabs.getTabById(id);
        if (tab == null || tab.isDestroyed() || tab.isIncognito()) {
            refreshRecentTabs();
            return;
        }
        int index = TabModelUtils.getTabIndexById(mNormalTabs, id);
        if (index == TabModel.INVALID_TAB_INDEX) {
            refreshRecentTabs();
            return;
        }
        TabModelUtils.setIndex(mNormalTabs, index);
    }

    private TvPresentation.Action action(int label, Runnable callback) {
        return new TvPresentation.Action(
                mActivity.getString(label),
                true,
                () -> {
                    if (isCurrentHome()) {
                        callback.run();
                    }
                });
    }

    private void open(String url) {
        mHost.loadUrl(new LoadUrlParams(url), mTab.isIncognito());
    }

    void focusAddress() {
        if (!mDestroyed) {
            startObserving();
            TvPresentation.focusHome(mContent);
        }
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
        stopObserving();
        mState.setFavorites(List.of(), false);
        mState.setRecentTabs(List.of());
        mFavorites.clear();
        mRecentActions.clear();
        TvPresentation.dispose(mContent);
        super.destroy();
    }
}
