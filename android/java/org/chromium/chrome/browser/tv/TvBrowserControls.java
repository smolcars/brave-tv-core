// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.CallbackController;
import org.chromium.base.supplier.NonNullObservableSupplier;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.back_press.BackPressManager;
import org.chromium.chrome.browser.bookmarks.BookmarkModel;
import org.chromium.chrome.browser.browsing_data.BrowsingDataBridge;
import org.chromium.chrome.browser.browsing_data.BrowsingDataType;
import org.chromium.chrome.browser.browsing_data.TimePeriod;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.chrome.browser.preferences.Pref;
import org.chromium.chrome.browser.preferences.website.BraveShieldsContentSettings;
import org.chromium.chrome.browser.search_engines.TemplateUrlServiceFactory;
import org.chromium.chrome.browser.search_engines.settings.BraveSearchEngineAdapter;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tabmodel.TabModelUtils;
import org.chromium.components.bookmarks.BookmarkId;
import org.chromium.components.bookmarks.BookmarkItem;
import org.chromium.components.browser_ui.widget.gesture.BackPressHandler;
import org.chromium.components.embedder_support.util.UrlUtilities;
import org.chromium.components.embedder_support.view.ContentView;
import org.chromium.components.search_engines.TemplateUrl;
import org.chromium.components.search_engines.TemplateUrlService;
import org.chromium.components.user_prefs.UserPrefs;
import org.chromium.content_public.browser.LoadUrlParams;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.base.PageTransition;
import org.chromium.ui.modaldialog.ModalDialogManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** TV-only adapter around the existing browser UI and the active Chromium content view. */
public final class TvBrowserControls implements TvRemoteInput.Target {
    private final BraveActivity mActivity;
    private final BackPressManager mBackPressManager;
    private final NonNullObservableSupplier<Boolean> mScrimVisible;
    private final BooleanSupplier mPrivateTabsLocked;
    private final TvRemoteInput mInput;
    private final Cursor mCursor;
    private final View mCursorView;
    private final OnBackPressedCallback mBackCallback;
    private @Nullable ContentView mPage;
    private @Nullable AlertDialog mDialog;
    private @Nullable CallbackController mBookmarkCallbacks;
    private @Nullable CallbackController mSearchCallbacks;
    private @Nullable TvHistoryPanel mHistoryPanel;
    private @Nullable TvFilterPanel mFilterPanel;
    private @Nullable TvMediaPanel mMediaPanel;
    private final @Nullable ModalDialogManager mModalDialogs;
    private @Nullable ContentView mModalReturnPage;
    private final ModalDialogManager.ModalDialogManagerObserver mModalObserver =
            new ModalDialogManager.ModalDialogManagerObserver() {
                @Override
                public void onDialogShown(View dialogView) {
                    if (!isAlive()) return;
                    mModalReturnPage = currentPage();
                    detachCursor();
                    dialogView.post(() -> focusNativeDialog(dialogView));
                }

                @Override
                public void onLastDialogDismissed() {
                    ContentView page = mModalReturnPage;
                    mModalReturnPage = null;
                    if (isAlive()
                            && mActivity.hasWindowFocus()
                            && page != null
                            && page == currentPage()
                            && page.isAttachedToWindow()) {
                        page.requestFocus();
                    }
                }
            };
    private @Nullable Runnable mOnDataCleared;
    private boolean mClearingData;
    private int mLastAction = R.string.brave_tv_address;
    private int mLastSettingsAction = R.string.brave_tv_search_engine;
    private String mPressedUrl = "";
    private boolean mDestroyed;
    private final View.OnAttachStateChangeListener mPageAttachmentListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {}

                @Override
                public void onViewDetachedFromWindow(View view) {
                    if (isAlive() && view == mPage) detachCursor();
                }
            };

    public TvBrowserControls(
            BraveActivity activity,
            BackPressManager backPressManager,
            NonNullObservableSupplier<Boolean> scrimVisible,
            BooleanSupplier privateTabsLocked) {
        mActivity = activity;
        mBackPressManager = backPressManager;
        mScrimVisible = scrimVisible;
        mPrivateTabsLocked = privateTabsLocked;
        float density = activity.getResources().getDisplayMetrics().density;
        mInput = new TvRemoteInput(this, Math.max(1, Math.round(16 * density)));
        mCursor = new Cursor(density);
        mCursorView = new View(activity);
        mCursorView.setBackground(mCursor);
        mCursorView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mModalDialogs = activity.getModalDialogManager();
        if (mModalDialogs != null) mModalDialogs.addObserver(mModalObserver);
        mBackCallback =
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (!isAlive()) return;
                        if (!keyboardVisible() && !nativeUiOwnsInput(true)) {
                            if (!mInput.back()) showControls();
                            return;
                        }
                        dispatchNativeBack();
                    }
                };
        activity.getOnBackPressedDispatcher().addCallback(activity, mBackCallback);
    }

    private void dispatchNativeBack() {
        if (!isAlive()) return;
        mBackCallback.setEnabled(false);
        try {
            mActivity.getOnBackPressedDispatcher().onBackPressed();
        } finally {
            if (isAlive()) mBackCallback.setEnabled(true);
        }
    }

    private boolean isAlive() {
        return !mDestroyed && !mActivity.isFinishing() && !mActivity.isDestroyed();
    }

    private void focusNativeDialog(View dialog) {
        if (!isAlive() || !dialog.isAttachedToWindow() || !dialog.isShown()) return;
        View focused = dialog.findFocus();
        if (focused != null
                && focused != dialog
                && focused.isEnabled()
                && (focused.isClickable() || focused.onCheckIsTextEditor())) return;
        ArrayList<View> controls = new ArrayList<>();
        dialog.addFocusables(controls, View.FOCUS_FORWARD, View.FOCUSABLES_ALL);
        View preferred = null;
        for (View control : controls) {
            if (control == dialog
                    || !control.isEnabled()
                    || !control.isShown()
                    || (!control.isClickable() && !control.onCheckIsTextEditor())) continue;
            control.setFocusableInTouchMode(true);
            if (control.onCheckIsTextEditor()) {
                preferred = control;
                break;
            }
            if (preferred == null || control.getId() == R.id.negative_button) preferred = control;
        }
        if (preferred != null) preferred.requestFocus();
    }

    private boolean keyboardVisible() {
        WindowInsetsCompat insets =
                ViewCompat.getRootWindowInsets(mActivity.getWindow().getDecorView());
        return insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
    }

    private boolean nativeUiOwnsInput(boolean backToControls) {
        ContentView page = currentPage();
        Tab tab = mActivity.getActivityTab();
        boolean idleNewTabPage =
                backToControls
                        && tab != null
                        && tab.isNativePage()
                        && UrlUtilities.isNtpUrl(tab.getUrl());
        if ((page == null && !idleNewTabPage) || mScrimVisible.get()) return true;
        if (mActivity.getModalDialogManager() != null
                && mActivity.getModalDialogManager().isShowing()) return true;
        View focus = mActivity.getCurrentFocus();
        if (focus != null && page != null && !page.hasFocus()) return true;
        // CloseWatcher can outrank Find; inspect the native UI independently.
        View findToolbar = mActivity.findViewById(R.id.find_toolbar);
        if (findToolbar != null && findToolbar.isShown()) return true;
        for (@BackPressHandler.Type int type = 0;
                type < BackPressHandler.Type.TAB_HISTORY;
                type++) {
            // Web CloseWatcher must not trap the remote inside a page.
            if (type != BackPressHandler.Type.CLOSE_WATCHER
                    && mBackPressManager.isBackPressHandlerConsumingBackEvent(type)) return true;
        }
        return false;
    }

    private @Nullable ContentView currentPage() {
        Tab tab = mActivity.getActivityTab();
        return tab == null || tab.isNativePage() ? null : tab.getContentView();
    }

    private String currentUrl() {
        Tab tab = mActivity.getActivityTab();
        return tab == null ? "" : tab.getUrl().getSpec();
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!isAlive()) return false;
        InputDevice device = event.getDevice();
        if (!event.hasNoModifiers()
                || (device != null
                        && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC)
                || keyboardVisible()
                || nativeUiOwnsInput(false)) {
            mInput.cancelPress();
            return false;
        }
        TvRemoteInput.Key key;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                key = TvRemoteInput.Key.UP;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                key = TvRemoteInput.Key.DOWN;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                key = TvRemoteInput.Key.LEFT;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                key = TvRemoteInput.Key.RIGHT;
                break;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                key = TvRemoteInput.Key.SELECT;
                break;
            default:
                return false;
        }
        if (mPage != currentPage()) {
            mInput.back();
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN && event.getAction() != KeyEvent.ACTION_UP) {
            return false;
        }
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (key == TvRemoteInput.Key.SELECT && down && event.getRepeatCount() == 0) {
            mPressedUrl = currentUrl();
        }
        return mInput.onKey(
                key,
                down,
                event.getRepeatCount(),
                event.isCanceled()
                        || (!mPressedUrl.equals(currentUrl()) && key == TvRemoteInput.Key.SELECT));
    }

    public void onWindowFocusChanged(boolean focused) {
        if (!focused) mInput.cancelPress();
    }

    public void onPause() {
        mModalReturnPage = null;
        dismissDialog();
        detachCursor();
    }

    private boolean canAccessTabs(boolean incognito) {
        if (!incognito || !mPrivateTabsLocked.getAsBoolean()) return true;
        dismissDialog();
        if (mActivity.areTabModelsInitialized()) {
            // Selecting the model invokes the existing native unlock flow.
            mActivity.getTabModelSelector().selectModel(true);
        }
        return false;
    }

    @Override
    public void showControls() {
        if (!isAlive()) return;
        if (mHistoryPanel != null || mFilterPanel != null || mMediaPanel != null) dismissDialog();
        detachCursor();
        if (mDialog != null && mDialog.isShowing()) return;
        Tab tab = mActivity.getActivityTab();
        if (!canAccessTabs(tab != null && tab.isIncognito())) return;
        String url = currentUrl();
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(
                                tab != null && tab.isIncognito()
                                        ? R.string.brave_tv_private_controls
                                        : R.string.brave_tv_controls),
                        tab == null || tab.isNativePage() ? "" : url,
                        mLastAction);
        panel.addItem(
                mActivity.getString(R.string.brave_tv_address),
                R.string.brave_tv_address,
                () -> runControl(R.string.brave_tv_address, tab, url));
        addControl(panel, R.string.brave_tv_pointer, currentPage() != null, tab, url);
        addControl(panel, R.string.brave_tv_scroll, currentPage() != null, tab, url);
        addControl(panel, R.string.brave_tv_tabs, mActivity.areTabModelsInitialized(), tab, url);
        addControl(panel, R.string.brave_tv_back, tab != null && tab.canGoBack(), tab, url);
        addControl(panel, R.string.brave_tv_forward, tab != null && tab.canGoForward(), tab, url);
        addControl(
                panel,
                tab != null && tab.isLoading() ? R.string.brave_tv_stop : R.string.brave_tv_reload,
                tab != null && !tab.isNativePage(),
                tab,
                url);
        addControl(panel, R.string.brave_tv_bookmarks, tab != null, tab, url);
        addControl(panel, R.string.brave_tv_history, tab != null && !tab.isIncognito(), tab, url);
        addControl(panel, R.string.brave_tv_settings, true, tab, url);
        addControl(panel, R.string.brave_tv_shields, isWebTab(tab), tab, url);
        addControl(panel, R.string.brave_tv_close_tab, tab != null, tab, url);
        addControl(panel, R.string.brave_tv_home, tab != null, tab, url);
        addControl(
                panel,
                R.string.brave_tv_playback,
                tab != null && !tab.isNativePage() && tab.getWebContents() != null,
                tab,
                url);
        mDialog = panel.show(this::dispatchNativeBack);
    }

    private void addControl(
            TvBrowserPanel panel, int action, boolean enabled, @Nullable Tab tab, String url) {
        panel.addAction(action, enabled, () -> runControl(action, tab, url));
    }

    private void dismissDialog() {
        mOnDataCleared = null;
        if (mMediaPanel != null) {
            mMediaPanel.destroy();
            mMediaPanel = null;
        }
        if (mFilterPanel != null) {
            mFilterPanel.destroy();
            mFilterPanel = null;
        }
        if (mHistoryPanel != null) {
            mHistoryPanel.destroy();
            mHistoryPanel = null;
        }
        if (mSearchCallbacks != null) {
            mSearchCallbacks.destroy();
            mSearchCallbacks = null;
        }
        if (mBookmarkCallbacks != null) {
            mBookmarkCallbacks.destroy();
            mBookmarkCallbacks = null;
        }
        if (mDialog != null) mDialog.dismiss();
        mDialog = null;
    }

    private void runControl(int action, @Nullable Tab tab, String url) {
        if (!isAlive()) return;
        mLastAction = action;
        dismissDialog();
        // The page can navigate or a tab can close while the native panel is open.
        if (tab != mActivity.getActivityTab() || !url.equals(currentUrl())) {
            showControls();
            return;
        }
        if (!canAccessTabs(tab != null && tab.isIncognito())) return;
        performAction(action);
    }

    private boolean isWebTab(@Nullable Tab tab) {
        return tab != null
                && !tab.isDestroyed()
                && ("https".equals(tab.getUrl().getScheme())
                        || "http".equals(tab.getUrl().getScheme()));
    }

    private void returnToControls() {
        if (!isAlive()) return;
        dismissDialog();
        showControls();
    }

    public void showTabs() {
        if (!isAlive()) return;
        mLastAction = R.string.brave_tv_tabs;
        Tab tab = mActivity.getActivityTab();
        showTabs(tab != null && tab.isIncognito(), 0);
    }

    private void showTabs(boolean incognito, int start) {
        if (!isAlive() || !mActivity.areTabModelsInitialized()) return;
        dismissDialog();
        if (!canAccessTabs(incognito)) return;
        TabModel model = mActivity.getTabModelSelector().getModel(incognito);
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(
                                incognito
                                        ? R.string.brave_tv_private_tabs
                                        : R.string.brave_tv_tabs),
                        "",
                        R.string.brave_tv_new_tab);
        panel.addAction(
                R.string.brave_tv_new_tab,
                true,
                () -> {
                    if (!isAlive() || !canAccessTabs(incognito)) return;
                    dismissDialog();
                    mActivity.onMenuOrKeyboardAction(
                            incognito ? R.id.new_incognito_tab_menu_id : R.id.new_tab_menu_id,
                            false);
                    mLastAction = R.string.brave_tv_address;
                });
        panel.addAction(
                incognito ? R.string.brave_tv_normal_tabs : R.string.brave_tv_private_tabs,
                true,
                () -> showTabs(!incognito, 0));
        panel.addAction(R.string.brave_tv_controls, true, this::returnToControls);
        int first = Math.max(0, Math.min(start, Math.max(0, model.getCount() - 1)));
        int end = Math.min(first + 8, model.getCount());
        for (int i = first; i < end; i++) {
            Tab tab = model.getTabAt(i);
            if (tab == null) continue;
            int tabId = tab.getId();
            String title = tab.getTitle();
            if (title.isEmpty()) title = mActivity.getString(R.string.brave_tv_new_tab);
            if (tab == mActivity.getActivityTab())
                title = mActivity.getString(R.string.brave_tv_current_tab, title);
            panel.addItem(
                    title,
                    tabId,
                    () -> {
                        if (!isAlive() || !mActivity.areTabModelsInitialized()) return;
                        if (!canAccessTabs(incognito)) return;
                        TabModel currentModel = mActivity.getTabModelSelector().getModel(incognito);
                        int index = TabModelUtils.getTabIndexById(currentModel, tabId);
                        if (index == TabModel.INVALID_TAB_INDEX) {
                            showTabs(incognito, first);
                            return;
                        }
                        dismissDialog();
                        mActivity.getTabModelSelector().selectModel(incognito);
                        TabModelUtils.setIndex(currentModel, index);
                        if (tab.getNativePage() instanceof TvHomePage home) {
                            // Re-selecting the active tab does not run Chromium's attachment focus.
                            home.getView().requestFocus();
                        } else if (!UrlUtilities.isNtpUrl(tab.getUrl())) {
                            showControls();
                        }
                    });
        }
        panel.addAction(
                R.string.brave_tv_previous,
                first > 0,
                () -> showTabs(incognito, Math.max(0, first - 8)));
        panel.addAction(
                R.string.brave_tv_next, end < model.getCount(), () -> showTabs(incognito, end));
        mDialog = panel.show(this::returnToControls);
    }

    private boolean tabContextValid(Tab tab, String url) {
        return isAlive()
                && tab == mActivity.getActivityTab()
                && !tab.isDestroyed()
                && url.equals(currentUrl())
                && canAccessTabs(tab.isIncognito());
    }

    public void showBookmarks() {
        if (!isAlive()) return;
        Tab tab = mActivity.getActivityTab();
        if (tab == null || tab.isDestroyed() || !canAccessTabs(tab.isIncognito())) return;
        mLastAction = R.string.brave_tv_bookmarks;
        showBookmarks(tab, currentUrl(), null, 0);
    }

    private void showBookmarks(Tab tab, String url, @Nullable BookmarkId folderId, int start) {
        if (!tabContextValid(tab, url)) return;
        dismissDialog();
        BookmarkModel model = BookmarkModel.getForProfile(tab.getProfile().getOriginalProfile());
        if (!model.isBookmarkModelLoaded()) {
            TvBrowserPanel loading =
                    new TvBrowserPanel(
                            mActivity,
                            mActivity.getString(R.string.brave_tv_bookmarks),
                            mActivity.getString(R.string.brave_tv_loading_bookmarks),
                            0);
            loading.addAction(R.string.brave_tv_controls, true, this::returnToControls);
            mDialog = loading.show(this::returnToControls);
            mBookmarkCallbacks = new CallbackController();
            model.finishLoadingBookmarkModel(
                    mBookmarkCallbacks.makeCancelable(
                            () -> {
                                if (tabContextValid(tab, url))
                                    showBookmarks(tab, url, folderId, start);
                            }));
            return;
        }
        BookmarkId root = model.getRootFolderId();
        BookmarkItem folder = model.getBookmarkById(folderId == null ? root : folderId);
        if (folder == null || !folder.isFolder()) {
            returnToControls();
            return;
        }
        BookmarkId id = folder.getId();
        boolean atRoot = id.equals(root);
        List<BookmarkId> children = atRoot ? model.getTopLevelFolderIds() : model.getChildIds(id);
        Runnable back =
                atRoot
                        ? this::returnToControls
                        : () -> showBookmarks(tab, url, folder.getParentId(), 0);
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        atRoot
                                ? mActivity.getString(R.string.brave_tv_bookmarks)
                                : folder.getTitle(),
                        children.isEmpty()
                                ? mActivity.getString(R.string.brave_tv_empty_bookmarks)
                                : "",
                        R.string.brave_tv_save_bookmark);
        panel.addAction(
                R.string.brave_tv_save_bookmark,
                isWebTab(tab)
                        && model.isEditBookmarksEnabled()
                        && model.getUserBookmarkIdForTab(tab) == null,
                () -> saveBookmark(tab, url, model));
        panel.addAction(R.string.brave_tv_parent_folder, !atRoot, back);
        panel.addAction(R.string.brave_tv_controls, true, this::returnToControls);
        int first = Math.max(0, Math.min(start, Math.max(0, children.size() - 1)));
        int end = Math.min(first + 8, children.size());
        for (int i = first; i < end; i++) {
            BookmarkId childId = children.get(i);
            BookmarkItem item = model.getBookmarkById(childId);
            if (item == null) continue;
            String title = item.getTitle().isEmpty() ? item.getUrlForDisplay() : item.getTitle();
            panel.addItem(
                    item.isFolder()
                            ? mActivity.getString(R.string.brave_tv_bookmark_folder, title)
                            : title,
                    i,
                    () -> showBookmark(tab, url, model, childId, id, first));
        }
        panel.addAction(
                R.string.brave_tv_previous_bookmarks,
                first > 0,
                () -> showBookmarks(tab, url, id, Math.max(0, first - 8)));
        panel.addAction(
                R.string.brave_tv_more_bookmarks,
                end < children.size(),
                () -> showBookmarks(tab, url, id, end));
        mDialog = panel.show(back);
    }

    private void saveBookmark(Tab tab, String url, BookmarkModel model) {
        if (!tabContextValid(tab, url) || !isWebTab(tab) || !model.isEditBookmarksEnabled()) return;
        BookmarkId folder = model.getDefaultBookmarkFolder();
        BookmarkItem parent = model.getBookmarkById(folder);
        if (folder == null || parent == null || parent.isManaged()) return;
        if (model.getUserBookmarkIdForTab(tab) == null) {
            model.addBookmark(
                    folder,
                    model.getChildIds(folder).size(),
                    tab.getTitle().isEmpty() ? url : tab.getTitle(),
                    tab.getUrl());
        }
        showBookmarks(tab, url, folder, 0);
    }

    private void showBookmark(
            Tab tab, String url, BookmarkModel model, BookmarkId id, BookmarkId folder, int start) {
        if (!tabContextValid(tab, url)) return;
        BookmarkItem item = model.getBookmarkById(id);
        if (item == null) {
            showBookmarks(tab, url, folder, start);
            return;
        }
        if (item.isFolder()) {
            showBookmarks(tab, url, id, 0);
            return;
        }
        dismissDialog();
        Runnable back = () -> showBookmarks(tab, url, folder, start);
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        item.getTitle(),
                        item.getUrlForDisplay(),
                        R.string.brave_tv_open_bookmark);
        panel.addAction(
                R.string.brave_tv_open_bookmark,
                true,
                () -> {
                    if (!tabContextValid(tab, url)) return;
                    BookmarkItem current = model.getBookmarkById(id);
                    if (current == null || current.isFolder()) {
                        back.run();
                        return;
                    }
                    dismissDialog();
                    tab.loadUrl(new LoadUrlParams(current.getUrl(), PageTransition.AUTO_BOOKMARK));
                });
        panel.addAction(
                R.string.brave_tv_remove_bookmark,
                model.isEditBookmarksEnabled() && item.isEditable() && !item.isManaged(),
                () -> confirmBookmarkRemoval(tab, url, model, id, folder, start));
        panel.addAction(R.string.brave_tv_bookmarks, true, back);
        mDialog = panel.show(back);
    }

    private void confirmBookmarkRemoval(
            Tab tab, String url, BookmarkModel model, BookmarkId id, BookmarkId folder, int start) {
        if (!tabContextValid(tab, url)) return;
        BookmarkItem item = model.getBookmarkById(id);
        if (item == null
                || item.isFolder()
                || !item.isEditable()
                || item.isManaged()
                || !model.isEditBookmarksEnabled()) return;
        dismissDialog();
        Runnable cancel = () -> showBookmark(tab, url, model, id, folder, start);
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(R.string.brave_tv_remove_bookmark_question),
                        item.getTitle(),
                        R.string.cancel);
        panel.addAction(R.string.cancel, true, cancel);
        panel.addAction(
                R.string.brave_tv_remove_bookmark,
                true,
                () -> {
                    if (!tabContextValid(tab, url)) return;
                    BookmarkItem current = model.getBookmarkById(id);
                    if (current != null
                            && !current.isFolder()
                            && current.isEditable()
                            && !current.isManaged()
                            && model.isEditBookmarksEnabled()) {
                        model.deleteBookmarks(id);
                    }
                    showBookmarks(tab, url, folder, start);
                });
        mDialog = panel.show(cancel);
    }

    public void showShields() {
        Tab tab = mActivity.getActivityTab();
        if (!isAlive() || !isWebTab(tab) || tab == null) return;
        if (!canAccessTabs(tab.isIncognito())) return;
        dismissDialog();
        detachCursor();
        String url = tab.getUrl().getSpec();
        boolean enabled =
                BraveShieldsContentSettings.getShields(
                        tab.getProfile(),
                        url,
                        BraveShieldsContentSettings.RESOURCE_IDENTIFIER_BRAVE_SHIELDS);
        String mode =
                BraveShieldsContentSettings.getShieldsValue(
                        tab.getProfile(),
                        url,
                        BraveShieldsContentSettings.RESOURCE_IDENTIFIER_TRACKERS);
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity, mActivity.getString(R.string.brave_tv_shields), url, 0);
        panel.addItem(
                mActivity.getString(
                        enabled ? R.string.brave_tv_shields_up : R.string.brave_tv_shields_down),
                R.string.brave_tv_shields,
                () -> {
                    if (!isAlive()
                            || tab != mActivity.getActivityTab()
                            || !url.equals(currentUrl())) return;
                    BraveShieldsContentSettings.setShields(
                            tab.getProfile(),
                            url,
                            BraveShieldsContentSettings.RESOURCE_IDENTIFIER_BRAVE_SHIELDS,
                            !enabled,
                            true);
                    dismissDialog();
                    tab.reload();
                    showShields();
                });
        addShieldMode(
                panel,
                tab,
                url,
                mode,
                R.string.brave_tv_standard,
                BraveShieldsContentSettings.DEFAULT);
        addShieldMode(
                panel,
                tab,
                url,
                mode,
                R.string.brave_tv_aggressive,
                BraveShieldsContentSettings.BLOCK_RESOURCE);
        panel.addAction(R.string.brave_tv_controls, true, this::returnToControls);
        mDialog = panel.show(this::returnToControls);
    }

    private void addShieldMode(
            TvBrowserPanel panel, Tab tab, String url, String currentMode, int label, String mode) {
        panel.addAction(
                label,
                !mode.equals(currentMode),
                () -> {
                    if (!isAlive()
                            || tab != mActivity.getActivityTab()
                            || !url.equals(currentUrl())) return;
                    BraveShieldsContentSettings.setShieldsValue(
                            tab.getProfile(),
                            url,
                            BraveShieldsContentSettings.RESOURCE_IDENTIFIER_TRACKERS,
                            mode,
                            true);
                    dismissDialog();
                    tab.reload();
                    showShields();
                });
    }

    public void showSettings() {
        if (!isAlive()) return;
        dismissDialog();
        Tab tab = mActivity.getActivityTab();
        if (tab == null || !canAccessTabs(tab.isIncognito())) return;
        String url = currentUrl();
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(R.string.brave_tv_settings),
                        "",
                        mLastSettingsAction);
        panel.addItem(
                mActivity.getString(R.string.brave_tv_search_engine),
                R.string.brave_tv_search_engine,
                () -> showSearchEngines(tab, url));
        panel.addItem(
                mActivity.getString(R.string.content_filters_title),
                R.string.content_filters_title,
                () -> {
                    if (!tabContextValid(tab, url)) return;
                    dismissDialog();
                    mLastSettingsAction = R.string.content_filters_title;
                    mFilterPanel = new TvFilterPanel(mActivity, tab, this::showSettings);
                    mFilterPanel.show();
                });
        if (!tab.isIncognito()) {
            panel.addItem(
                    mActivity.getString(R.string.brave_tv_clear_data),
                    R.string.brave_tv_clear_data,
                    () -> showClearData(tab, url, false));
        }
        panel.addItem(
                mActivity.getString(R.string.brave_tv_licenses),
                R.string.brave_tv_licenses,
                () -> {
                    if (!tabContextValid(tab, url)) return;
                    dismissDialog();
                    mLastSettingsAction = R.string.brave_tv_licenses;
                    tab.loadUrl(new LoadUrlParams("chrome://credits"));
                });
        panel.addAction(R.string.brave_tv_controls, true, this::returnToControls);
        mDialog = panel.show(this::returnToControls);
    }

    private boolean canClearData(Tab tab, String url, int type) {
        return tabContextValid(tab, url)
                && !tab.isIncognito()
                && !mClearingData
                && (type != BrowsingDataType.HISTORY
                        || UserPrefs.get(tab.getProfile())
                                .getBoolean(Pref.ALLOW_DELETING_BROWSER_HISTORY));
    }

    private void showClearData(Tab tab, String url, boolean completed) {
        if (!tabContextValid(tab, url) || tab.isIncognito()) return;
        mLastSettingsAction = R.string.brave_tv_clear_data;
        dismissDialog();
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(R.string.brave_tv_clear_data),
                        mActivity.getString(
                                mClearingData
                                        ? R.string.brave_tv_clearing_data
                                        : completed
                                                ? R.string.brave_tv_cleared_data
                                                : R.string.brave_tv_clear_data_scope),
                        R.string.brave_tv_settings);
        panel.addAction(
                R.string.brave_tv_clear_history,
                canClearData(tab, url, BrowsingDataType.HISTORY),
                () ->
                        confirmClearData(
                                tab,
                                url,
                                BrowsingDataType.HISTORY,
                                R.string.brave_tv_clear_history,
                                R.string.brave_tv_clear_history_scope));
        panel.addAction(
                R.string.brave_tv_clear_cookies,
                canClearData(tab, url, BrowsingDataType.SITE_DATA),
                () ->
                        confirmClearData(
                                tab,
                                url,
                                BrowsingDataType.SITE_DATA,
                                R.string.brave_tv_clear_cookies,
                                R.string.brave_tv_clear_cookies_scope));
        panel.addAction(
                R.string.brave_tv_clear_cache,
                canClearData(tab, url, BrowsingDataType.CACHE),
                () ->
                        confirmClearData(
                                tab,
                                url,
                                BrowsingDataType.CACHE,
                                R.string.brave_tv_clear_cache,
                                R.string.brave_tv_clear_cache_scope));
        panel.addAction(R.string.brave_tv_settings, true, this::showSettings);
        mDialog = panel.show(this::showSettings);
        if (mClearingData) mOnDataCleared = () -> showClearData(tab, url, true);
    }

    private void confirmClearData(Tab tab, String url, int type, int title, int scope) {
        if (!canClearData(tab, url, type)) return;
        dismissDialog();
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(title),
                        mActivity.getString(scope),
                        R.string.cancel);
        panel.addAction(R.string.cancel, true, () -> showClearData(tab, url, false));
        panel.addAction(
                R.string.brave_tv_clear_confirm,
                true,
                () -> {
                    if (!canClearData(tab, url, type)) return;
                    mClearingData = true;
                    showClearData(tab, url, false);
                    BrowsingDataBridge.getForProfile(tab.getProfile())
                            .clearBrowsingData(
                                    () -> {
                                        mClearingData = false;
                                        Runnable onComplete = mOnDataCleared;
                                        mOnDataCleared = null;
                                        if (onComplete != null) onComplete.run();
                                    },
                                    new int[] {type},
                                    TimePeriod.ALL_TIME);
                });
        mDialog = panel.show(() -> showClearData(tab, url, false));
    }

    private void showSearchEngines(Tab tab, String url) {
        if (!tabContextValid(tab, url)) return;
        mLastSettingsAction = R.string.brave_tv_search_engine;
        dismissDialog();
        TemplateUrlService service = TemplateUrlServiceFactory.getForProfile(tab.getProfile());
        if (service == null) {
            showSettings();
            return;
        }
        if (!service.isLoaded()) {
            TvBrowserPanel loading =
                    new TvBrowserPanel(
                            mActivity,
                            mActivity.getString(R.string.brave_tv_search_engine),
                            mActivity.getString(R.string.brave_tv_loading_search),
                            0);
            loading.addAction(R.string.brave_tv_settings, true, this::showSettings);
            mDialog = loading.show(this::showSettings);
            mSearchCallbacks = new CallbackController();
            service.runWhenLoaded(
                    mSearchCallbacks.makeCancelable(() -> showSearchEngines(tab, url)));
            return;
        }
        TemplateUrl selected = service.getDefaultSearchEngineTemplateUrl();
        String selectedKeyword = selected == null ? "" : selected.getKeyword();
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(R.string.brave_tv_search_engine),
                        mActivity.getString(
                                tab.isIncognito()
                                        ? R.string.brave_tv_private_search
                                        : R.string.brave_tv_normal_search),
                        R.string.brave_tv_selected_engine);
        if (!service.isDefaultSearchManaged()) {
            for (TemplateUrl engine : service.getTemplateUrls()) {
                if (!engine.getIsPrepopulatedOrProgramProvided()
                        && !engine.getKeyword().equals(selectedKeyword)) continue;
                String keyword = engine.getKeyword();
                String name = engine.getShortName();
                String label =
                        keyword.equals(selectedKeyword)
                                ? mActivity.getString(R.string.brave_tv_selected_engine, name)
                                : name;
                panel.addItem(
                        label,
                        keyword.equals(selectedKeyword) ? R.string.brave_tv_selected_engine : 0,
                        () -> {
                            if (!tabContextValid(tab, url) || service.isDefaultSearchManaged())
                                return;
                            // Template pointers can change while the chooser is open.
                            for (TemplateUrl current : service.getTemplateUrls()) {
                                if (!keyword.equals(current.getKeyword())) continue;
                                String currentName = current.getShortName();
                                service.setSearchEngine(keyword);
                                BraveSearchEngineAdapter.setDSEPrefs(currentName, tab.getProfile());
                                ChromeSharedPreferences.getInstance()
                                        .writeBoolean(
                                                BravePreferenceKeys.DEFAULT_SEARCH_ENGINE_CHANGED,
                                                true);
                                showSearchEngines(tab, url);
                                return;
                            }
                            showSearchEngines(tab, url);
                        });
            }
        } else {
            panel.addItem(
                    mActivity.getString(R.string.brave_tv_managed_search), 0, this::showSettings);
        }
        panel.addAction(R.string.brave_tv_settings, true, this::showSettings);
        mDialog = panel.show(this::showSettings);
    }

    private void performAction(int action) {
        if (!isAlive()) return;
        if (action == R.string.brave_tv_pointer || action == R.string.brave_tv_scroll) {
            mPage = currentPage();
            if (mPage == null || !(mPage.getParent() instanceof ViewGroup parent)) return;
            mPage.addOnAttachStateChangeListener(mPageAttachmentListener);
            // ViewOverlay drawables disappear over the compositor's transparent surface region.
            parent.addView(
                    mCursorView,
                    parent.indexOfChild(mPage) + 1,
                    new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            mPage.requestFocus();
            mInput.activate(
                    action == R.string.brave_tv_pointer
                            ? TvRemoteInput.Mode.POINTER
                            : TvRemoteInput.Mode.SCROLL);
        } else if (action == R.string.brave_tv_address) {
            mActivity.onMenuOrKeyboardAction(R.id.focus_url_bar, false);
        } else if (action == R.string.brave_tv_home) {
            Tab tab = mActivity.getActivityTab();
            if (tab != null) tab.loadUrl(new LoadUrlParams("chrome-native://newtab/"));
        } else if (action == R.string.brave_tv_reload) {
            mActivity.onMenuOrKeyboardAction(R.id.reload_menu_id, false);
        } else if (action == R.string.brave_tv_tabs) {
            Tab tab = mActivity.getActivityTab();
            showTabs(tab != null && tab.isIncognito(), 0);
        } else if (action == R.string.brave_tv_shields) {
            showShields();
        } else if (action == R.string.brave_tv_bookmarks) {
            showBookmarks();
        } else if (action == R.string.brave_tv_history) {
            Tab tab = mActivity.getActivityTab();
            if (tab == null || tab.isDestroyed() || tab.isIncognito()) return;
            mHistoryPanel = new TvHistoryPanel(mActivity, tab, this::returnToControls);
            mHistoryPanel.show();
        } else if (action == R.string.brave_tv_settings) {
            showSettings();
        } else if (action == R.string.brave_tv_playback) {
            Tab tab = mActivity.getActivityTab();
            WebContents contents = tab == null ? null : tab.getWebContents();
            if (tab == null || contents == null || tab.isNativePage()) return;
            mMediaPanel = new TvMediaPanel(mActivity, tab, contents, this::returnToControls);
            mMediaPanel.show();
        } else if (action == R.string.brave_tv_close_tab) {
            mActivity.onMenuOrKeyboardAction(R.id.close_tab, false);
        } else {
            Tab tab = mActivity.getActivityTab();
            if (tab == null || tab.isDestroyed()) return;
            if (action == R.string.brave_tv_back && tab.canGoBack()) tab.goBack();
            if (action == R.string.brave_tv_forward && tab.canGoForward()) tab.goForward();
            if (action == R.string.brave_tv_stop) tab.stopLoading();
        }
    }

    @Override
    public int getWidth() {
        return mPage == null ? 0 : mPage.getWidth();
    }

    @Override
    public int getHeight() {
        return mPage == null ? 0 : mPage.getHeight();
    }

    @Override
    public void showPointer(int x, int y, TvRemoteInput.Mode mode) {
        mCursor.x = x;
        mCursor.y = y;
        mCursor.hintTop = mActivity.getBrowserControlsManager().getContentOffset();
        mCursor.hint =
                mActivity.getString(
                        mode == TvRemoteInput.Mode.SCROLL
                                ? R.string.brave_tv_scroll_hint
                                : R.string.brave_tv_pointer_hint);
        mCursor.setBounds(0, 0, getWidth(), getHeight());
        mCursor.invalidateSelf();
        sendPointerEvent(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, 0, SystemClock.uptimeMillis());
    }

    @Override
    public void click(int x, int y) {
        long time = SystemClock.uptimeMillis();
        // Chromium ignores mouse DOWN/UP; use a scoped touchscreen tap for OK.
        sendPointerEvent(MotionEvent.ACTION_DOWN, x, y, 0, 0, time);
        sendPointerEvent(MotionEvent.ACTION_UP, x, y, 0, 0, time);
    }

    @Override
    public void scroll(int x, int y, int dx, int dy) {
        sendPointerEvent(MotionEvent.ACTION_SCROLL, x, y, dx, -dy, SystemClock.uptimeMillis());
    }

    private void sendPointerEvent(int action, int x, int y, int dx, int dy, long downTime) {
        ContentView page = mPage;
        if (!isAlive()
                || page == null
                || page != currentPage()
                || !(page.getParent() instanceof ViewGroup parent)) return;
        boolean touch = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP;
        MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
        pointer.id = 0;
        pointer.toolType = touch ? MotionEvent.TOOL_TYPE_FINGER : MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords point = new MotionEvent.PointerCoords();
        point.x = x + page.getX();
        point.y = y + page.getY();
        point.pressure = action == MotionEvent.ACTION_DOWN ? 1 : 0;
        point.setAxisValue(MotionEvent.AXIS_HSCROLL, dx);
        point.setAxisValue(MotionEvent.AXIS_VSCROLL, dy);
        MotionEvent event =
                MotionEvent.obtain(
                        downTime,
                        SystemClock.uptimeMillis(),
                        action,
                        1,
                        new MotionEvent.PointerProperties[] {pointer},
                        new MotionEvent.PointerCoords[] {point},
                        0,
                        0,
                        1,
                        1,
                        0,
                        0,
                        touch ? InputDevice.SOURCE_TOUCHSCREEN : InputDevice.SOURCE_MOUSE,
                        0);
        try {
            // The compositor parent applies Chromium's viewport offsets before page dispatch.
            if (touch) {
                parent.dispatchTouchEvent(event);
            } else {
                parent.dispatchGenericMotionEvent(event);
            }
        } finally {
            event.recycle();
        }
    }

    private void detachCursor() {
        mInput.cancelPress();
        if (mPage != null) mPage.removeOnAttachStateChangeListener(mPageAttachmentListener);
        if (mCursorView.getParent() instanceof ViewGroup parent) parent.removeView(mCursorView);
        mPage = null;
    }

    public void destroy() {
        mDestroyed = true;
        mModalReturnPage = null;
        if (mModalDialogs != null) mModalDialogs.removeObserver(mModalObserver);
        mInput.cancelPress();
        mBackCallback.remove();
        detachCursor();
        dismissDialog();
    }

    private static final class Cursor extends Drawable {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float density;
        int x;
        int y;
        int hintTop;
        String hint = "";

        Cursor(float density) {
            this.density = density;
        }

        @Override
        public void draw(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(16 * density);
            paint.setColor(Color.BLACK);
            canvas.drawRect(
                    12 * density,
                    hintTop + 12 * density,
                    36 * density + paint.measureText(hint),
                    hintTop + 48 * density,
                    paint);
            paint.setColor(Color.WHITE);
            canvas.drawText(hint, 24 * density, hintTop + 36 * density, paint);
            canvas.drawCircle(x, y, 7 * density, paint);
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * density);
            canvas.drawCircle(x, y, 7 * density, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
