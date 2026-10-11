// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import android.content.pm.PackageInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.OneShotPreDrawListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.color.MaterialColors;

import org.chromium.base.BravePreferenceKeys;
import org.chromium.base.CallbackController;
import org.chromium.base.PackageUtils;
import org.chromium.base.supplier.NonNullObservableSupplier;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.back_press.BackPressManager;
import org.chromium.chrome.browser.bookmarks.BookmarkModel;
import org.chromium.chrome.browser.bookmarks.BookmarkModelObserver;
import org.chromium.chrome.browser.browsing_data.BrowsingDataBridge;
import org.chromium.chrome.browser.browsing_data.BrowsingDataType;
import org.chromium.chrome.browser.browsing_data.TimePeriod;
import org.chromium.chrome.browser.download.DownloadManagerService;
import org.chromium.chrome.browser.download.items.OfflineContentAggregatorFactory;
import org.chromium.chrome.browser.omnibox.suggestions.AutocompleteController;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.chrome.browser.preferences.Pref;
import org.chromium.chrome.browser.preferences.website.BraveShieldsContentSettings;
import org.chromium.chrome.browser.search_engines.TemplateUrlServiceFactory;
import org.chromium.chrome.browser.search_engines.settings.BraveSearchEngineAdapter;
import org.chromium.chrome.browser.tab.EmptyTabObserver;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tabmodel.TabModelObserver;
import org.chromium.chrome.browser.tabmodel.TabModelSelectorTabModelObserver;
import org.chromium.chrome.browser.tabmodel.TabModelUtils;
import org.chromium.chrome.browser.tv.presentation.TvAddressEditor;
import org.chromium.chrome.browser.tv.presentation.TvPresentation;
import org.chromium.components.bookmarks.BookmarkId;
import org.chromium.components.bookmarks.BookmarkItem;
import org.chromium.components.browser_ui.widget.gesture.BackPressHandler;
import org.chromium.components.embedder_support.util.UrlUtilities;
import org.chromium.components.embedder_support.view.ContentView;
import org.chromium.components.omnibox.AutocompleteMatch;
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
    static final int BOOKMARK_PAGE_SIZE = 8;
    static final int TAB_PAGE_SIZE = 8;
    static final String PROJECT_SOURCE_URL = "https://github.com/smolcars/brave-tv";
    static final String LICENSES_URL = "chrome://credits";

    private final BraveActivity mActivity;
    private final BackPressManager mBackPressManager;
    private final NonNullObservableSupplier<Boolean> mScrimVisible;
    private final BooleanSupplier mPrivateTabsLocked;
    private final TvRemoteInput mInput;
    private final TvPhoneRemote mPhoneRemote;
    private final TvBrowserSession mSession;
    private @Nullable View mControlsView;
    private @Nullable TvAddressEditor.State mAddressState;
    private @Nullable TvBrowserSession.Context mAddressContext;
    private @Nullable Tab mControlsTab;
    private boolean mControlsUpdatePosted;
    private final Cursor mCursor;
    private final View mCursorView;
    private final OnBackPressedCallback mBackCallback;
    private final TabModelSelectorTabModelObserver mTabObserver;
    private @Nullable ContentView mPage;
    private @Nullable AlertDialog mDialog;
    private @Nullable CallbackController mBookmarkCallbacks;
    private @Nullable Runnable mDialogCleanup;
    private @Nullable CallbackController mSearchCallbacks;
    private @Nullable TvHistoryPanel mHistoryPanel;
    private @Nullable TvDownloadsPanel mDownloadsPanel;
    private @Nullable TvFilterPanel mFilterPanel;
    private @Nullable TvMediaPanel mMediaPanel;
    private final @Nullable ModalDialogManager mModalDialogs;
    private @Nullable ContentView mModalReturnPage;
    private @Nullable TvBrowserSession.Context mModalReturnContext;
    private final ModalDialogManager.ModalDialogManagerObserver mModalObserver =
            new ModalDialogManager.ModalDialogManagerObserver() {
                @Override
                public void onDialogShown(View dialogView) {
                    if (!isAlive()) return;
                    if (mModalReturnPage == null) {
                        mModalReturnPage = currentPage();
                        if (mModalReturnPage != null) mModalReturnContext = mSession.capture();
                    }
                    mInput.cancelPress();
                    mPressedContext = null;
                    mCursorView.removeCallbacks(mHideCursor);
                    mCursorView.setVisibility(View.INVISIBLE);
                    OneShotPreDrawListener.add(dialogView, () -> focusNativeDialog(dialogView));
                }

                @Override
                public void onLastDialogDismissed() {
                    restoreModalPageFocus();
                }
            };
    private @Nullable Runnable mOnDataCleared;
    private boolean mClearingData;
    private int mLastAction = R.string.brave_tv_address;
    private int mLastSettingsAction = R.string.brave_tv_search_engine;
    private @Nullable TvBrowserSession.Context mPressedContext;
    private boolean mDestroyed;
    private final Runnable mHideCursor = this::hideCursor;
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
        mPhoneRemote = new TvPhoneRemote(activity, this);
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
                            if (!exitPageFullscreen() && !mInput.back()) showControls();
                            return;
                        }
                        dispatchNativeBack();
                    }
                };
        activity.getOnBackPressedDispatcher().addCallback(activity, mBackCallback);
        mTabObserver =
                new TabModelSelectorTabModelObserver(activity.getTabModelSelector()) {
                    @Override
                    public void didRemoveTabForClosure(Tab tab) {
                        boolean closedPrivateTab = tab.isIncognito();
                        // Let the model finish closing/selecting before recovering TV home.
                        mActivity
                                .getWindow()
                                .getDecorView()
                                .post(() -> restoreUsableHome(closedPrivateTab));
                    }
                };
        mSession = new TvBrowserSession(activity, this::browserStateChanged);
        mActivity.getWindow().getDecorView().post(() -> restoreUsableHome(false));
    }

    private void restoreUsableHome(boolean closedPrivateTab) {
        if (!isAlive()
                || !mActivity.hasWindowFocus()
                || !mActivity.areTabModelsInitialized()
                || mActivity.getTabModelSelector().isIncognitoSelected()
                || (mModalDialogs != null && mModalDialogs.isShowing())
                || (mDialog != null && mDialog.isShowing())) return;
        if (mActivity.getTabModelSelector().getModel(false).getCount() == 0) {
            mActivity.onMenuOrKeyboardAction(R.id.new_tab_menu_id, false);
        } else if (closedPrivateTab
                && mActivity.getTabModelSelector().getModel(true).getCount() == 0) {
            TabModel normal = mActivity.getTabModelSelector().getModel(false);
            Tab current = TabModelUtils.getCurrentTab(normal);
            if (current != null
                    && !current.isDestroyed()
                    && !current.isIncognito()
                    && current.getNativePage() instanceof TvHomePage home) {
                // Closing the private model can clear the activity's selected-tab supplier.
                TabModelUtils.setIndex(normal, normal.index());
                home.focusAddress();
            }
        }
    }

    boolean isPageFullscreen() {
        Tab tab = mActivity.getActivityTab();
        WebContents contents = tab == null ? null : tab.getWebContents();
        return contents != null && contents.isFullscreenForCurrentTab();
    }

    boolean exitPageFullscreen() {
        if (!isPageFullscreen()) return false;
        mInput.cancelPress();
        mPhoneRemote.invalidate();
        mActivity.getActivityTab().getWebContents().exitFullscreen();
        return true;
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
        ArrayList<View> controls = new ArrayList<>();
        dialog.addFocusables(controls, View.FOCUS_FORWARD, View.FOCUSABLES_ALL);
        View preferred = null;
        for (View control : controls) {
            // Custom confirmation buttons have an inert, focusable spinner container.
            if (control.getId() == R.id.spinner_button) control.setFocusable(false);
            if (control == dialog
                    || !control.isEnabled()
                    || !control.isShown()
                    || (!control.isClickable() && !control.onCheckIsTextEditor())) continue;
            control.setFocusableInTouchMode(true);
            if (control.getForeground() == null) {
                float density = control.getResources().getDisplayMetrics().density;
                GradientDrawable outline = new GradientDrawable();
                outline.setCornerRadius(8 * density);
                outline.setStroke(
                        Math.round(3 * density),
                        MaterialColors.getColor(control, R.attr.colorOnSurface));
                StateListDrawable foreground = new StateListDrawable();
                foreground.addState(
                        new int[] {android.R.attr.state_enabled, android.R.attr.state_focused},
                        outline);
                control.setForeground(foreground);
            }
            if (preferred == null
                    || (!preferred.onCheckIsTextEditor()
                            && (control.onCheckIsTextEditor()
                                    || control.getId() == R.id.negative_button
                                    || control.getId() == R.id.button_secondary))) {
                preferred = control;
            }
        }
        if (preferred != null) preferred.requestFocus();
    }

    private void restoreModalPageFocus() {
        if (!isAlive()
                || !mActivity.hasWindowFocus()
                || mModalDialogs == null
                || mModalDialogs.isShowing()) return;
        ContentView page = mModalReturnPage;
        if (page == null) return;
        TvBrowserSession.Context context = mModalReturnContext;
        mModalReturnPage = null;
        mModalReturnContext = null;
        if (page != currentPage()
                || !page.isAttachedToWindow()
                || !mSession.isCurrentDocument(context)
                || (context != null
                        && context.tab != null
                        && context.tab.isIncognito()
                        && mPrivateTabsLocked.getAsBoolean())) {
            detachCursor();
            return;
        }
        if (nativeUiOwnsInput(false)) return;
        page.requestFocus();
        if (mPage == page && !keyboardVisible()) mInput.resume();
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
        // Native dialog dismissal can return focus to Chromium's content host.
        if (focus != null
                && page != null
                && !page.hasFocus()
                && focus.getId() != R.id.compositor_view_holder) return true;
        // CloseWatcher can outrank Find; inspect the native UI independently.
        View findToolbar = mActivity.findViewById(R.id.find_toolbar);
        if (findToolbar != null && findToolbar.isShown()) return true;
        for (@BackPressHandler.Type int type = 0;
                type < BackPressHandler.Type.TAB_HISTORY;
                type++) {
            // Web CloseWatcher must not trap the remote inside a page.
            if (type != BackPressHandler.Type.CLOSE_WATCHER
                    && type != BackPressHandler.Type.FULLSCREEN
                    && mBackPressManager.isBackPressHandlerConsumingBackEvent(type)) return true;
        }
        return false;
    }

    private @Nullable ContentView currentPage() {
        Tab tab = mActivity.getActivityTab();
        return tab == null || tab.isNativePage() ? null : tab.getContentView();
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        mPhoneRemote.invalidate();
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
        if (key == TvRemoteInput.Key.SELECT && down) {
            boolean hidden = mCursorView.getVisibility() != View.VISIBLE;
            mCursorView.setVisibility(View.VISIBLE);
            mCursorView.removeCallbacks(mHideCursor);
            if (hidden) {
                mInput.cancelPress();
                return true;
            }
        }
        if (key == TvRemoteInput.Key.SELECT && down && event.getRepeatCount() == 0) {
            mPressedContext = mSession.capture();
        }
        boolean handled =
                mInput.onKey(
                        key,
                        down,
                        event.getRepeatCount(),
                        event.isCanceled()
                                || (!mSession.isCurrent(mPressedContext)
                                        && key == TvRemoteInput.Key.SELECT));
        if (handled && key == TvRemoteInput.Key.SELECT && !down && mPage != null) showCursor();
        if (key == TvRemoteInput.Key.SELECT && !down) mPressedContext = null;
        return handled;
    }

    public void onWindowFocusChanged(boolean focused) {
        if (!focused) {
            mInput.cancelPress();
            mPressedContext = null;
        } else {
            restoreModalPageFocus();
            restoreUsableHome(false);
        }
    }

    public void onPause() {
        mPhoneRemote.stop();
        mSession.invalidate();
        mModalReturnPage = null;
        mModalReturnContext = null;
        dismissDialog();
        detachCursor();
    }

    public void showPhoneRemote() {
        if (!isAlive()) return;
        Tab tab = mActivity.getActivityTab();
        if (tab == null || tab.isIncognito()) return;
        dismissDialog();
        mPhoneRemote.show();
    }

    boolean remotePaused() {
        Tab tab = mActivity.getActivityTab();
        return !isAlive()
                || !mActivity.hasWindowFocus()
                || tab == null
                || tab.isIncognito()
                || !mActivity.areTabModelsInitialized()
                || mActivity.getTabModelSelector().isIncognitoSelected()
                || (mDialog != null && mDialog.isShowing())
                || nativeUiOwnsInput(true)
                || (tab.isNativePage() && !UrlUtilities.isNtpUrl(tab.getUrl()));
    }

    void remotePointer(int x, int y, String operation, int dx, int dy) {
        if (remotePaused()) return;
        mInput.cancelPress();
        ContentView page = currentPage();
        if (page == null) return;
        if (mPage != page) {
            detachCursor();
            mPage = page;
            page.addOnAttachStateChangeListener(mPageAttachmentListener);
            if (page.getParent() instanceof ViewGroup parent) {
                parent.addView(
                        mCursorView,
                        new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }
        showPointer(x, y, TvRemoteInput.Mode.POINTER);
        if (operation.equals("click")) click(x, y);
        if (operation.equals("scroll")) scroll(x, y, dx, dy);
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
        showControls(false);
    }

    private void showControls(boolean restoreFocus) {
        if (!isAlive()) return;
        if (mHistoryPanel != null
                || mDownloadsPanel != null
                || mFilterPanel != null
                || mMediaPanel != null) dismissDialog();
        detachCursor();
        if (mDialog != null && mDialog.isShowing()) return;
        Tab tab = mActivity.getActivityTab();
        if (!canAccessTabs(tab != null && tab.isIncognito())) return;
        if (!restoreFocus && tab != null && UrlUtilities.isNtpUrl(tab.getUrl())) {
            mLastAction = R.string.brave_tv_address;
        }
        TvBrowserSession.Context context = mSession.capture();
        String url = context.url;
        List<TvPresentation.Action> actions = controlActions(context);
        View content =
                TvPresentation.controls(
                        mActivity,
                        mActivity.getString(
                                tab != null && tab.isIncognito()
                                        ? R.string.brave_tv_private_controls
                                        : R.string.brave_tv_controls),
                        tab == null || tab.isNativePage() ? "" : url,
                        actions,
                        mLastAction,
                        mActivity.getString(R.string.brave_tv_more_controls));
        mControlsView = content;
        mControlsTab = tab;
        mDialog =
                new AlertDialog.Builder(mActivity)
                        .setView(content)
                        .setOnCancelListener(ignored -> returnToPage())
                        .create();
        mDialog.setOnKeyListener((dialog, key, event) -> TvPresentation.back(content, event));
        AlertDialog controlsDialog = mDialog;
        controlsDialog
                .getOnBackPressedDispatcher()
                .addCallback(
                        new OnBackPressedCallback(true) {
                            @Override
                            public void handleOnBackPressed() {
                                if (!TvPresentation.collapseControls(content))
                                    controlsDialog.cancel();
                            }
                        });
        mDialog.setOnDismissListener(
                ignored -> {
                    if (mDialog == controlsDialog) mDialog = null;
                    if (mControlsView == content) {
                        mControlsView = null;
                        mControlsTab = null;
                    }
                });
        mDialog.setCanceledOnTouchOutside(false);
        mDialog.show();
        Window window = mDialog.getWindow();
        if (window != null) {
            if (tab != null && tab.isIncognito()) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            }
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.BOTTOM);
            var metrics = mActivity.getResources().getDisplayMetrics();
            window.setLayout(
                    Math.round(metrics.widthPixels * 0.92f), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    TvBrowserSession session() {
        return mSession;
    }

    private void browserStateChanged() {
        mInput.cancelPress();
        mPressedContext = null;
        mPhoneRemote.invalidate();
        if (!isAlive()) return;
        TvBrowserSession.Context addressContext = mAddressContext;
        if (mDownloadsPanel != null) mDownloadsPanel.onBrowserStateChanged();
        if (addressContext != null) {
            TvBrowserSession.Context current = mSession.capture();
            // Capture can synchronously notify an ownership change and dismiss this editor.
            if (isAlive() && mAddressContext == addressContext) {
                if (current.tab != addressContext.tab
                        || current.contents != addressContext.contents) {
                    dismissDialog();
                } else if (mAddressState != null && !mSession.isCurrentDocument(addressContext)) {
                    mAddressState.setError(mActivity.getString(R.string.brave_tv_address_changed));
                    mAddressState.setSuggestions(List.of());
                }
            }
        }
        if (mControlsUpdatePosted || !isAlive()) return;
        mControlsUpdatePosted = true;
        mActivity
                .getWindow()
                .getDecorView()
                .post(
                        () -> {
                            mControlsUpdatePosted = false;
                            if (!isAlive() || mControlsView == null) return;
                            TvBrowserSession.Context context = mSession.capture();
                            if (mControlsTab != context.tab) {
                                // A new tab/profile has its own native focus and private screenshot
                                // policy.
                                dismissDialog();
                                return;
                            }
                            TvPresentation.updateControls(
                                    mControlsView,
                                    context.tab == null || context.tab.isNativePage()
                                            ? ""
                                            : context.url,
                                    controlActions(context));
                        });
    }

    private List<TvPresentation.Action> controlActions(TvBrowserSession.Context context) {
        Tab tab = context.tab;
        List<TvPresentation.Action> actions = new ArrayList<>();
        addComposeControl(actions, R.string.brave_tv_address, true, context);
        addComposeControl(actions, R.string.brave_tv_pointer, currentPage() != null, context);
        addComposeControl(actions, R.string.brave_tv_back, tab != null && tab.canGoBack(), context);
        addComposeControl(
                actions, R.string.brave_tv_forward, tab != null && tab.canGoForward(), context);
        addComposeControl(
                actions,
                tab != null && tab.isLoading() ? R.string.brave_tv_stop : R.string.brave_tv_reload,
                tab != null && !tab.isNativePage(),
                context);
        addComposeControl(
                actions, R.string.brave_tv_tabs, mActivity.areTabModelsInitialized(), context);
        addComposeControl(actions, R.string.brave_tv_bookmarks, tab != null, context);
        addComposeControl(
                actions, R.string.brave_tv_history, tab != null && !tab.isIncognito(), context);
        addComposeControl(
                actions, R.string.brave_tv_downloads, tab != null && !tab.isIncognito(), context);
        addComposeControl(actions, R.string.brave_tv_shields, isWebTab(tab), context);
        addComposeControl(actions, R.string.menu_page_info, isWebTab(tab), context);
        addComposeControl(actions, R.string.brave_tv_settings, true, context);
        addComposeControl(actions, R.string.brave_tv_scroll, currentPage() != null, context);
        addComposeControl(
                actions,
                R.string.brave_tv_playback,
                tab != null && !tab.isNativePage() && tab.getWebContents() != null,
                context);
        addComposeControl(actions, R.string.brave_tv_home, tab != null, context);
        addComposeControl(actions, R.string.brave_tv_close_tab, tab != null, context);
        if (TvPhoneRemote.available()) {
            actions.add(
                    new TvPresentation.Action(
                            mActivity.getString(R.string.brave_tv_phone),
                            tab != null && !tab.isIncognito(),
                            this::showPhoneRemote,
                            R.string.brave_tv_phone));
        }
        return actions;
    }

    private void addComposeControl(
            List<TvPresentation.Action> actions,
            int action,
            boolean enabled,
            TvBrowserSession.Context context) {
        actions.add(
                new TvPresentation.Action(
                        mActivity.getString(action),
                        enabled,
                        () -> runControl(action, context),
                        action == R.string.brave_tv_stop ? R.string.brave_tv_reload : action,
                        action == R.string.brave_tv_pointer ? R.drawable.ic_cursor : 0));
    }

    private void dismissDialog() {
        mAddressState = null;
        mAddressContext = null;
        mControlsView = null;
        mControlsTab = null;
        mOnDataCleared = null;
        if (mDownloadsPanel != null) {
            mDownloadsPanel.destroy();
            mDownloadsPanel = null;
        }
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
        if (mDialogCleanup != null) {
            mDialogCleanup.run();
            mDialogCleanup = null;
        }
        if (mBookmarkCallbacks != null) {
            mBookmarkCallbacks.destroy();
            mBookmarkCallbacks = null;
        }
        if (mDialog != null) mDialog.dismiss();
        mDialog = null;
    }

    private void setDialogCleanup(TvBrowserPanel panel, AlertDialog dialog, Runnable cleanup) {
        mDialogCleanup = cleanup;
        panel.setOnDismiss(
                () -> {
                    cleanup.run();
                    if (mDialogCleanup == cleanup) mDialogCleanup = null;
                    if (mDialog == dialog) mDialog = null;
                });
    }

    private void runControl(int action, TvBrowserSession.Context context) {
        Tab tab = context.tab;
        if (!isAlive()) return;
        mLastAction = action == R.string.brave_tv_stop ? R.string.brave_tv_reload : action;
        dismissDialog();
        // The page can navigate or a tab can close while the native panel is open.
        if (!mSession.isCurrent(context)) {
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
        showControls(true);
    }

    private void returnToPage() {
        if (!isAlive()) return;
        dismissDialog();
        Tab tab = mActivity.getActivityTab();
        if (tab != null && tab.getNativePage() instanceof TvHomePage home) {
            home.focusAddress();
        } else if (currentPage() != null) {
            performAction(R.string.brave_tv_pointer);
        }
    }

    /** Opens a TV editor without transferring native page or profile ownership. */
    public void showAddress() {
        if (!isAlive()) return;
        Tab tab = mActivity.getActivityTab();
        if (tab == null || tab.isDestroyed() || !canAccessTabs(tab.isIncognito())) return;
        mLastAction = R.string.brave_tv_address;
        dismissDialog();
        detachCursor();
        TvBrowserSession.Context context = mSession.capture();
        String initial = tab.isNativePage() ? "" : context.url;
        TvAddressEditor.State state =
                new TvAddressEditor.State(
                        initial.length() <= TvAddressEditor.MAX_TEXT_LENGTH ? initial : "");
        mAddressState = state;
        mAddressContext = context;
        if (initial.length() > TvAddressEditor.MAX_TEXT_LENGTH) {
            state.setError(mActivity.getString(R.string.brave_tv_address_too_long));
        }
        var service = TemplateUrlServiceFactory.getForProfile(tab.getProfile());
        TemplateUrl engine =
                service.isLoaded() ? service.getDefaultSearchEngineTemplateUrl() : null;
        String description =
                engine == null
                        ? mActivity.getString(R.string.brave_tv_address_description)
                        : mActivity.getString(
                                R.string.brave_tv_address_provider, engine.getShortName());
        if (isWebTab(tab)) description += "\n" + tab.getUrl().getOrigin().getSpec();
        var labels =
                new TvAddressEditor.Labels(
                        mActivity.getString(R.string.brave_tv_address),
                        description,
                        mActivity.getString(R.string.brave_tv_go),
                        mActivity.getString(R.string.brave_tv_clear_address),
                        mActivity.getString(R.string.cancel),
                        mActivity.getString(R.string.brave_tv_saved_suggestions),
                        mActivity.getString(R.string.brave_tv_address_hint),
                        mActivity.getString(R.string.brave_tv_address_too_long));
        View content =
                TvAddressEditor.create(
                        mActivity,
                        state,
                        labels,
                        input -> updateAddressSuggestions(context, state, input),
                        input -> submitAddress(context, state, input),
                        this::returnToPage);
        AlertDialog dialog =
                new AlertDialog.Builder(mActivity)
                        .setView(content)
                        .setOnCancelListener(ignored -> returnToPage())
                        .create();
        dialog.setOnDismissListener(
                ignored -> {
                    if (mDialog == dialog) {
                        mDialog = null;
                        mAddressState = null;
                        mAddressContext = null;
                    }
                    state.clear();
                    TvPresentation.dispose(content);
                });
        dialog.setCanceledOnTouchOutside(false);
        Window window = dialog.getWindow();
        if (window != null) {
            if (tab.isIncognito()) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.CENTER);
        }
        mDialog = dialog;
        dialog.show();
        if (window != null) {
            // AlertDialog checks for Android text editors before Compose attaches its input.
            window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            var metrics = mActivity.getResources().getDisplayMetrics();
            window.setLayout(
                    Math.min(
                            Math.round(700 * metrics.density),
                            Math.round(metrics.widthPixels * 0.92f)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void updateAddressSuggestions(
            TvBrowserSession.Context context, TvAddressEditor.State state, String input) {
        if (mAddressState != state) return;
        List<TvPresentation.Action> suggestions = new ArrayList<>();
        Tab tab = context.tab;
        if (tab != null && documentContextValid(context)) {
            BookmarkModel model =
                    BookmarkModel.getForProfile(tab.getProfile().getOriginalProfile());
            if (model.isBookmarkModelLoaded()) {
                for (BookmarkId id :
                        model.searchBookmarks(input.trim(), TvAddressEditor.MAX_SUGGESTIONS)) {
                    BookmarkItem item = model.getBookmarkById(id);
                    if (item == null || item.isFolder()) continue;
                    String url = item.getUrl().getSpec();
                    suggestions.add(
                            new TvPresentation.Action(
                                    item.getTitle() + "\n" + item.getUrlForDisplay(),
                                    true,
                                    () -> {
                                        if (!documentContextValid(context)) {
                                            state.setError(
                                                    mActivity.getString(
                                                            R.string.brave_tv_address_changed));
                                            return;
                                        }
                                        BookmarkItem current = model.getBookmarkById(id);
                                        if (current == null
                                                || !current.getUrl().getSpec().equals(url)) {
                                            updateAddressSuggestions(context, state, input);
                                            return;
                                        }
                                        submitAddress(context, state, url);
                                    }));
                }
            }
        }
        state.setSuggestions(List.copyOf(suggestions));
    }

    private void submitAddress(
            TvBrowserSession.Context context, TvAddressEditor.State state, String input) {
        if (mAddressState != state || mDialog == null || !mDialog.isShowing()) return;
        if (!documentContextValid(context)) {
            state.setError(mActivity.getString(R.string.brave_tv_address_changed));
            return;
        }
        String text = input.trim();
        if (text.isEmpty()
                || text.length() > TvAddressEditor.MAX_TEXT_LENGTH
                || text.chars().anyMatch(c -> c < 32)) {
            state.setError(mActivity.getString(R.string.brave_tv_address_invalid));
            return;
        }
        Tab tab = context.tab;
        if (tab == null) return;
        AutocompleteController controller = AutocompleteController.getForProfile(tab.getProfile());
        AutocompleteMatch match = controller == null ? null : controller.classify(text);
        if (match == null || !match.getUrl().isValid()) {
            state.setError(mActivity.getString(R.string.brave_tv_address_invalid));
            return;
        }
        if (mAddressState != state || !mSession.isCurrentDocument(context)) {
            state.setError(mActivity.getString(R.string.brave_tv_address_changed));
            return;
        }
        dismissDialog();
        tab.loadUrl(
                new LoadUrlParams(
                        match.getUrl().getSpec(),
                        match.getTransition() | PageTransition.FROM_ADDRESS_BAR));
        performAction(R.string.brave_tv_pointer);
    }

    public void showTabs() {
        if (!isAlive()) return;
        mLastAction = R.string.brave_tv_tabs;
        Tab tab = mActivity.getActivityTab();
        showTabs(tab != null && tab.isIncognito(), 0);
    }

    private void showTabs(boolean incognito, int start) {
        showTabs(incognito, start, Tab.INVALID_TAB_ID, 0);
    }

    private void showTabs(boolean incognito, int start, int focusedTabId, int focusedAction) {
        if (!isAlive() || !mActivity.areTabModelsInitialized()) return;
        dismissDialog();
        if (!canAccessTabs(incognito)) return;
        TabModel model = mActivity.getTabModelSelector().getModel(incognito);
        int selected =
                focusedTabId == Tab.INVALID_TAB_ID
                        ? TabModel.INVALID_TAB_INDEX
                        : TabModelUtils.getTabIndexById(model, focusedTabId);
        if (focusedTabId != Tab.INVALID_TAB_ID && selected == TabModel.INVALID_TAB_INDEX)
            selected = Math.min(start, model.getCount() - 1);
        int first = Math.max(0, Math.min(selected < 0 ? start : selected, model.getCount() - 1));
        first -= first % TAB_PAGE_SIZE;
        int end = Math.min(first + TAB_PAGE_SIZE, model.getCount());
        Tab selectedTab = selected < 0 ? null : model.getTabAt(selected);
        int preferred =
                selectedTab != null
                        ? selectedTab.getId()
                        : focusedAction == 0 ? R.string.brave_tv_new_tab : focusedAction;
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(
                                incognito
                                        ? R.string.brave_tv_private_tabs
                                        : R.string.brave_tv_tabs),
                        "",
                        preferred);
        if (incognito) panel.protectPrivateContent();
        if (focusedTabId != Tab.INVALID_TAB_ID || focusedAction != 0) panel.restorePreferredFocus();
        List<TvPresentation.Action> actions = new ArrayList<>();
        List<Tab> visibleTabs = new ArrayList<>();
        actions.add(
                panel.addAction(
                        R.string.brave_tv_new_tab,
                        true,
                        () -> {
                            if (!isAlive() || !canAccessTabs(incognito)) return;
                            dismissDialog();
                            mActivity.onMenuOrKeyboardAction(
                                    incognito
                                            ? R.id.new_incognito_tab_menu_id
                                            : R.id.new_tab_menu_id,
                                    false);
                            mLastAction = R.string.brave_tv_address;
                        }));
        actions.add(
                panel.addAction(
                        incognito ? R.string.brave_tv_normal_tabs : R.string.brave_tv_private_tabs,
                        true,
                        () -> showTabs(!incognito, 0)));
        actions.add(panel.addAction(R.string.brave_tv_controls, true, this::returnToControls));
        for (int i = first; i < end; i++) {
            Tab tab = model.getTabAt(i);
            if (tab == null) continue;
            visibleTabs.add(tab);
            int tabId = tab.getId();
            String title = tab.getTitle();
            if (title.isEmpty()) title = mActivity.getString(R.string.brave_tv_new_tab);
            if (tab == mActivity.getActivityTab())
                title = mActivity.getString(R.string.brave_tv_current_tab, title);
            int pageStart = first;
            actions.add(
                    panel.addItem(
                            title,
                            tabId,
                            () -> {
                                if (!isAlive() || !mActivity.areTabModelsInitialized()) return;
                                if (!canAccessTabs(incognito)) return;
                                TabModel currentModel =
                                        mActivity.getTabModelSelector().getModel(incognito);
                                int index = TabModelUtils.getTabIndexById(currentModel, tabId);
                                if (index == TabModel.INVALID_TAB_INDEX) {
                                    showTabs(incognito, pageStart, tabId, 0);
                                    return;
                                }
                                dismissDialog();
                                mActivity.getTabModelSelector().selectModel(incognito);
                                TabModelUtils.setIndex(currentModel, index);
                                if (tab.getNativePage() instanceof TvHomePage home) {
                                    // Re-selecting the active tab does not run Chromium's
                                    // attachment focus.
                                    home.focusAddress();
                                } else if (!UrlUtilities.isNtpUrl(tab.getUrl())) {
                                    showControls();
                                }
                            }));
        }
        int pageStart = first;
        actions.add(
                panel.addAction(
                        R.string.brave_tv_previous,
                        first > 0,
                        () -> showTabs(incognito, Math.max(0, pageStart - TAB_PAGE_SIZE))));
        actions.add(
                panel.addAction(
                        R.string.brave_tv_next,
                        end < model.getCount(),
                        () -> showTabs(incognito, end)));
        AlertDialog dialog = panel.show(this::returnToControls);
        mDialog = dialog;
        View owner = mActivity.getWindow().getDecorView();
        Runnable refresh =
                () -> {
                    if (mDialog != dialog || !isAlive() || !mActivity.areTabModelsInitialized())
                        return;
                    if (incognito && mPrivateTabsLocked.getAsBoolean()) {
                        dismissDialog();
                        return;
                    }
                    for (TvPresentation.Action action : actions) {
                        if (!action.hasFocus()) continue;
                        int key = action.getKey();
                        for (int i = 0; i < visibleTabs.size(); i++) {
                            if (visibleTabs.get(i).getId() != key) continue;
                            showTabs(incognito, pageStart + i, key, 0);
                            return;
                        }
                        showTabs(incognito, pageStart, Tab.INVALID_TAB_ID, key);
                        return;
                    }
                    showTabs(incognito, pageStart, focusedTabId, focusedAction);
                };
        Runnable changed =
                () -> {
                    owner.removeCallbacks(refresh);
                    owner.post(refresh);
                };
        TabModelObserver modelObserver =
                new TabModelObserver() {
                    @Override
                    public void didAddTab(
                            Tab tab, int type, int creationState, boolean markedForSelection) {
                        changed.run();
                    }

                    @Override
                    public void didSelectTab(Tab tab, int type, int lastId) {
                        changed.run();
                    }

                    @Override
                    public void didMoveTab(Tab tab, int newIndex, int curIndex) {
                        changed.run();
                    }

                    @Override
                    public void didRemoveTabForClosure(Tab tab) {
                        changed.run();
                    }

                    @Override
                    public void tabRemoved(Tab tab) {
                        changed.run();
                    }

                    @Override
                    public void tabClosureUndone(Tab tab) {
                        changed.run();
                    }
                };
        EmptyTabObserver tabObserver =
                new EmptyTabObserver() {
                    @Override
                    public void onTitleUpdated(Tab tab) {
                        changed.run();
                    }

                    @Override
                    public void onUrlUpdated(Tab tab) {
                        changed.run();
                    }

                    @Override
                    public void onDestroyed(Tab tab) {
                        changed.run();
                    }
                };
        model.addObserver(modelObserver);
        for (Tab tab : visibleTabs) tab.addObserver(tabObserver);
        setDialogCleanup(
                panel,
                dialog,
                () -> {
                    model.removeObserver(modelObserver);
                    for (Tab tab : visibleTabs) tab.removeObserver(tabObserver);
                    owner.removeCallbacks(refresh);
                });
    }

    private boolean tabContextValid(TvBrowserSession.Context context) {
        Tab tab = context.tab;
        if (tab == null) return false;
        return isAlive()
                && tab == mActivity.getActivityTab()
                && !tab.isDestroyed()
                && mSession.isCurrent(context)
                && canAccessTabs(tab.isIncognito());
    }

    private boolean documentContextValid(TvBrowserSession.Context context) {
        Tab tab = context.tab;
        return tab != null
                && isAlive()
                && tab == mActivity.getActivityTab()
                && !tab.isDestroyed()
                && mSession.isCurrentDocument(context)
                && canAccessTabs(tab.isIncognito());
    }

    public void showBookmarks() {
        if (!isAlive()) return;
        Tab tab = mActivity.getActivityTab();
        if (tab == null || tab.isDestroyed() || !canAccessTabs(tab.isIncognito())) return;
        mLastAction = R.string.brave_tv_bookmarks;
        showBookmarks(mSession.capture(), null, 0);
    }

    private void showBookmarks(
            TvBrowserSession.Context context, @Nullable BookmarkId folderId, int start) {
        showBookmarks(context, folderId, start, null, 0);
    }

    private void showBookmarks(
            TvBrowserSession.Context context,
            @Nullable BookmarkId folderId,
            int start,
            @Nullable BookmarkId focusedId,
            int focusedAction) {
        Tab tab = context.tab;
        if (tab == null) return;
        if (!documentContextValid(context)) return;
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
                                if (documentContextValid(context))
                                    showBookmarks(
                                            context, folderId, start, focusedId, focusedAction);
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
                        : () -> showBookmarks(context, folder.getParentId(), 0, id, 1);
        int selected = focusedId == null ? -1 : children.indexOf(focusedId);
        if (focusedId != null && selected < 0) selected = Math.min(start, children.size() - 1);
        int first = Math.max(0, Math.min(selected < 0 ? start : selected, children.size() - 1));
        first -= first % BOOKMARK_PAGE_SIZE;
        int end = Math.min(first + BOOKMARK_PAGE_SIZE, children.size());
        int preferred =
                selected >= 0
                        ? selected + 1
                        : focusedAction == 0 ? R.string.brave_tv_save_bookmark : focusedAction;
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        atRoot
                                ? mActivity.getString(R.string.brave_tv_bookmarks)
                                : folder.getTitle(),
                        children.isEmpty()
                                ? mActivity.getString(R.string.brave_tv_empty_bookmarks)
                                : "",
                        preferred);
        if (focusedAction != 0) panel.restorePreferredFocus();
        List<TvPresentation.Action> actions = new ArrayList<>();
        actions.add(
                panel.addAction(
                        R.string.brave_tv_save_bookmark,
                        isWebTab(tab)
                                && model.isEditBookmarksEnabled()
                                && model.getUserBookmarkIdForTab(tab) == null,
                        () -> saveBookmark(context, model)));
        actions.add(panel.addAction(R.string.brave_tv_parent_folder, !atRoot, back));
        actions.add(panel.addAction(R.string.brave_tv_controls, true, this::returnToControls));
        for (int i = first; i < end; i++) {
            BookmarkId childId = children.get(i);
            BookmarkItem item = model.getBookmarkById(childId);
            if (item == null) continue;
            String title = item.getTitle().isEmpty() ? item.getUrlForDisplay() : item.getTitle();
            int index = i;
            actions.add(
                    panel.addItem(
                            item.isFolder()
                                    ? mActivity.getString(R.string.brave_tv_bookmark_folder, title)
                                    : title,
                            i + 1,
                            () -> showBookmark(context, model, childId, id, index)));
        }
        int pageStart = first;
        List<BookmarkId> visibleIds = List.copyOf(children.subList(first, end));
        actions.add(
                panel.addAction(
                        R.string.brave_tv_previous_bookmarks,
                        first > 0,
                        () ->
                                showBookmarks(
                                        context, id, Math.max(0, pageStart - BOOKMARK_PAGE_SIZE))));
        actions.add(
                panel.addAction(
                        R.string.brave_tv_more_bookmarks,
                        end < children.size(),
                        () -> showBookmarks(context, id, end)));
        AlertDialog dialog = panel.show(back);
        mDialog = dialog;
        View owner = mActivity.getWindow().getDecorView();
        Runnable refresh =
                () -> {
                    if (mDialog != dialog || !documentContextValid(context)) return;
                    for (TvPresentation.Action action : actions) {
                        if (!action.hasFocus()) continue;
                        int key = action.getKey();
                        BookmarkId selectedId =
                                key > pageStart && key <= pageStart + visibleIds.size()
                                        ? visibleIds.get(key - pageStart - 1)
                                        : null;
                        showBookmarks(
                                context,
                                id,
                                selectedId == null ? pageStart : key - 1,
                                selectedId,
                                key);
                        return;
                    }
                    showBookmarks(context, id, pageStart, focusedId, preferred);
                };
        BookmarkModelObserver observer =
                new BookmarkModelObserver() {
                    @Override
                    public void bookmarkModelChanged() {
                        owner.removeCallbacks(refresh);
                        owner.post(refresh);
                    }
                };
        model.addObserver(observer);
        Runnable cleanup =
                () -> {
                    model.removeObserver(observer);
                    owner.removeCallbacks(refresh);
                };
        setDialogCleanup(panel, dialog, cleanup);
    }

    private void saveBookmark(TvBrowserSession.Context context, BookmarkModel model) {
        Tab tab = context.tab;
        if (tab == null) return;
        String url = context.url;
        if (!documentContextValid(context) || !isWebTab(tab) || !model.isEditBookmarksEnabled())
            return;
        BookmarkId folder = model.getDefaultBookmarkFolder();
        BookmarkItem parent = model.getBookmarkById(folder);
        if (folder == null || parent == null || parent.isManaged()) return;
        dismissDialog();
        if (model.getUserBookmarkIdForTab(tab) == null) {
            model.addBookmark(
                    folder,
                    model.getChildIds(folder).size(),
                    tab.getTitle().isEmpty() ? url : tab.getTitle(),
                    tab.getUrl());
        }
        showBookmarks(context, folder, 0);
    }

    private void showBookmark(
            TvBrowserSession.Context context,
            BookmarkModel model,
            BookmarkId id,
            BookmarkId folder,
            int start) {
        Tab tab = context.tab;
        if (tab == null) return;
        if (!documentContextValid(context)) return;
        BookmarkItem item = model.getBookmarkById(id);
        if (item == null) {
            showBookmarks(context, folder, start, id, start + 1);
            return;
        }
        if (item.isFolder()) {
            showBookmarks(context, id, 0);
            return;
        }
        dismissDialog();
        Runnable back = () -> showBookmarks(context, folder, start, id, start + 1);
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
                    if (!documentContextValid(context)) return;
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
                () -> confirmBookmarkRemoval(context, model, id, folder, start));
        panel.addAction(R.string.brave_tv_bookmarks, true, back);
        mDialog = panel.show(back);
    }

    private void confirmBookmarkRemoval(
            TvBrowserSession.Context context,
            BookmarkModel model,
            BookmarkId id,
            BookmarkId folder,
            int start) {
        Tab tab = context.tab;
        if (tab == null) return;
        if (!documentContextValid(context)) return;
        BookmarkItem item = model.getBookmarkById(id);
        if (item == null
                || item.isFolder()
                || !item.isEditable()
                || item.isManaged()
                || !model.isEditBookmarksEnabled()) return;
        dismissDialog();
        Runnable cancel = () -> showBookmark(context, model, id, folder, start);
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
                    if (!documentContextValid(context)) return;
                    BookmarkItem current = model.getBookmarkById(id);
                    if (current != null
                            && !current.isFolder()
                            && current.isEditable()
                            && !current.isManaged()
                            && model.isEditBookmarksEnabled()) {
                        model.deleteBookmarks(id);
                    }
                    showBookmarks(context, folder, start, id, start + 1);
                });
        mDialog = panel.show(cancel);
    }

    public void showShields() {
        Tab tab = mActivity.getActivityTab();
        if (!isAlive() || !isWebTab(tab) || tab == null) return;
        if (!canAccessTabs(tab.isIncognito())) return;
        dismissDialog();
        detachCursor();
        TvBrowserSession.Context context = mSession.capture();
        String url = context.url;
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
                            || !mSession.isCurrent(context)) return;
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
                context,
                mode,
                R.string.brave_tv_standard,
                BraveShieldsContentSettings.DEFAULT);
        addShieldMode(
                panel,
                context,
                mode,
                R.string.brave_tv_aggressive,
                BraveShieldsContentSettings.BLOCK_RESOURCE);
        panel.addAction(R.string.brave_tv_controls, true, this::returnToControls);
        mDialog = panel.show(this::returnToControls);
    }

    private void addShieldMode(
            TvBrowserPanel panel,
            TvBrowserSession.Context context,
            String currentMode,
            int label,
            String mode) {
        Tab tab = context.tab;
        if (tab == null) return;
        String url = context.url;
        panel.addAction(
                label,
                !mode.equals(currentMode),
                () -> {
                    if (!isAlive()
                            || tab != mActivity.getActivityTab()
                            || !mSession.isCurrent(context)) return;
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
        showSettings(null);
    }

    /**
     * UI thread only. Null returns to Controls; an exact selected Home restores Settings focus.
     * Stale owners are rejected, and valid Home focus is scheduled after dialog dismissal.
     */
    public void showSettings(@Nullable TvHomePage home) {
        if (!isAlive()) return;
        Tab tab = mActivity.getActivityTab();
        if (tab == null) return;
        if (home != null && tab.getNativePage() != home) return;
        if (!canAccessTabs(tab.isIncognito())) return;
        TvBrowserSession.Context context = mSession.capture();
        Runnable onReturn =
                home == null
                        ? this::returnToControls
                        : () -> {
                            if (!isAlive()) return;
                            dismissDialog();
                            if (!mSession.isCurrentDocument(context)
                                    || tab.getNativePage() != home
                                    || !home.focusSettings()) {
                                returnToPage();
                            }
                        };
        showSettings(context, onReturn);
    }

    private void showSettings(TvBrowserSession.Context context, Runnable onReturn) {
        Tab tab = context.tab;
        if (tab == null || !tabContextValid(context)) return;
        dismissDialog();
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(R.string.brave_tv_settings),
                        mActivity.getString(R.string.brave_tv_settings_description),
                        mLastSettingsAction);
        panel.addItem(
                        mActivity.getString(R.string.brave_tv_search_engine),
                        R.string.brave_tv_search_engine,
                        () -> showSearchEngines(context, onReturn))
                .setIcon(R.drawable.ic_search);
        panel.addItem(
                        mActivity.getString(R.string.content_filters_title),
                        R.string.content_filters_title,
                        () -> {
                            if (!tabContextValid(context)) return;
                            dismissDialog();
                            mLastSettingsAction = R.string.content_filters_title;
                            mFilterPanel =
                                    new TvFilterPanel(
                                            mActivity, tab, () -> showSettings(context, onReturn));
                            mFilterPanel.show();
                        })
                .setIcon(R.drawable.ic_shield_done);
        if (!tab.isIncognito()) {
            panel.addItem(
                            mActivity.getString(R.string.brave_tv_clear_data),
                            R.string.brave_tv_clear_data,
                            () -> showClearData(context, false, onReturn))
                    .setIcon(R.drawable.ic_shred_data);
        }
        panel.addItem(
                        mActivity.getString(R.string.brave_tv_about),
                        R.string.brave_tv_about,
                        () -> showAbout(context, onReturn))
                .setIcon(R.drawable.ic_code);
        panel.addItem(
                        mActivity.getString(R.string.brave_tv_controls),
                        R.string.brave_tv_controls,
                        this::returnToControls)
                .setIcon(R.drawable.ic_tune);
        mDialog = panel.show(onReturn);
    }

    private void showAbout(TvBrowserSession.Context context, Runnable onReturn) {
        Tab tab = context.tab;
        if (tab == null || !tabContextValid(context)) return;
        dismissDialog();
        mLastSettingsAction = R.string.brave_tv_about;
        PackageInfo info = PackageUtils.getPackageInfo(mActivity.getPackageName(), 0);
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(R.string.brave_tv_about),
                        info == null || info.versionName == null
                                ? ""
                                : mActivity.getString(
                                        R.string.brave_tv_about_version, info.versionName),
                        R.string.brave_tv_settings);
        panel.addDescription(mActivity.getString(R.string.brave_tv_attribution));
        panel.addAction(
                R.string.brave_tv_source,
                true,
                () -> openAboutDestination(context, PROJECT_SOURCE_URL));
        panel.addAction(
                R.string.brave_tv_licenses,
                true,
                () -> openAboutDestination(context, LICENSES_URL));
        Runnable back = () -> showSettings(context, onReturn);
        panel.addAction(R.string.brave_tv_settings, true, back);
        mDialog = panel.show(back);
    }

    private void openAboutDestination(TvBrowserSession.Context context, String url) {
        Tab tab = context.tab;
        if (tab == null || !tabContextValid(context)) return;
        dismissDialog();
        tab.loadUrl(new LoadUrlParams(url));
    }

    private boolean canClearData(TvBrowserSession.Context context, int type) {
        Tab tab = context.tab;
        if (tab == null) return false;
        return tabContextValid(context)
                && !tab.isIncognito()
                && !mClearingData
                && (type != BrowsingDataType.HISTORY
                        || UserPrefs.get(tab.getProfile())
                                .getBoolean(Pref.ALLOW_DELETING_BROWSER_HISTORY));
    }

    private void showClearData(
            TvBrowserSession.Context context, boolean completed, Runnable onReturn) {
        Tab tab = context.tab;
        if (tab == null) return;
        if (!tabContextValid(context) || tab.isIncognito()) return;
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
                canClearData(context, BrowsingDataType.HISTORY),
                () ->
                        confirmClearData(
                                context,
                                BrowsingDataType.HISTORY,
                                R.string.brave_tv_clear_history,
                                R.string.brave_tv_clear_history_scope,
                                onReturn));
        panel.addAction(
                R.string.brave_tv_clear_cookies,
                canClearData(context, BrowsingDataType.SITE_DATA),
                () ->
                        confirmClearData(
                                context,
                                BrowsingDataType.SITE_DATA,
                                R.string.brave_tv_clear_cookies,
                                R.string.brave_tv_clear_cookies_scope,
                                onReturn));
        panel.addAction(
                R.string.brave_tv_clear_cache,
                canClearData(context, BrowsingDataType.CACHE),
                () ->
                        confirmClearData(
                                context,
                                BrowsingDataType.CACHE,
                                R.string.brave_tv_clear_cache,
                                R.string.brave_tv_clear_cache_scope,
                                onReturn));
        panel.addAction(R.string.brave_tv_settings, true, () -> showSettings(context, onReturn));
        mDialog = panel.show(() -> showSettings(context, onReturn));
        if (mClearingData) mOnDataCleared = () -> showClearData(context, true, onReturn);
    }

    private void confirmClearData(
            TvBrowserSession.Context context, int type, int title, int scope, Runnable onReturn) {
        Tab tab = context.tab;
        if (tab == null) return;
        if (!canClearData(context, type)) return;
        dismissDialog();
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        mActivity.getString(title),
                        mActivity.getString(scope),
                        R.string.cancel);
        panel.addAction(R.string.cancel, true, () -> showClearData(context, false, onReturn));
        panel.addAction(
                R.string.brave_tv_clear_confirm,
                true,
                () -> {
                    if (!canClearData(context, type)) return;
                    mClearingData = true;
                    showClearData(context, false, onReturn);
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
        mDialog = panel.show(() -> showClearData(context, false, onReturn));
    }

    private void showSearchEngines(TvBrowserSession.Context context, Runnable onReturn) {
        Tab tab = context.tab;
        if (tab == null) return;
        if (!tabContextValid(context)) return;
        mLastSettingsAction = R.string.brave_tv_search_engine;
        dismissDialog();
        TemplateUrlService service = TemplateUrlServiceFactory.getForProfile(tab.getProfile());
        if (service == null) {
            showSettings(context, onReturn);
            return;
        }
        if (!service.isLoaded()) {
            TvBrowserPanel loading =
                    new TvBrowserPanel(
                            mActivity,
                            mActivity.getString(R.string.brave_tv_search_engine),
                            mActivity.getString(R.string.brave_tv_loading_search),
                            0);
            loading.addAction(
                    R.string.brave_tv_settings, true, () -> showSettings(context, onReturn));
            mDialog = loading.show(() -> showSettings(context, onReturn));
            mSearchCallbacks = new CallbackController();
            service.runWhenLoaded(
                    mSearchCallbacks.makeCancelable(() -> showSearchEngines(context, onReturn)));
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
                            if (!tabContextValid(context) || service.isDefaultSearchManaged())
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
                                showSearchEngines(context, onReturn);
                                return;
                            }
                            showSearchEngines(context, onReturn);
                        });
            }
        } else {
            panel.addItem(
                    mActivity.getString(R.string.brave_tv_managed_search),
                    0,
                    () -> showSettings(context, onReturn));
        }
        panel.addAction(R.string.brave_tv_settings, true, () -> showSettings(context, onReturn));
        mDialog = panel.show(() -> showSettings(context, onReturn));
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
            showAddress();
        } else if (action == R.string.brave_tv_home) {
            Tab tab = mActivity.getActivityTab();
            if (tab != null) tab.loadUrl(new LoadUrlParams("chrome-native://newtab/"));
        } else if (action == R.string.brave_tv_reload) {
            mSession.navigate(mSession.capture(), TvBrowserSession.Navigation.RELOAD);
        } else if (action == R.string.brave_tv_tabs) {
            Tab tab = mActivity.getActivityTab();
            showTabs(tab != null && tab.isIncognito(), 0);
        } else if (action == R.string.brave_tv_shields) {
            showShields();
        } else if (action == R.string.menu_page_info) {
            if (isWebTab(mActivity.getActivityTab())) {
                mActivity.onMenuOrKeyboardAction(R.id.info_menu_id, false);
            }
        } else if (action == R.string.brave_tv_bookmarks) {
            showBookmarks();
        } else if (action == R.string.brave_tv_history) {
            Tab tab = mActivity.getActivityTab();
            if (tab == null || tab.isDestroyed() || tab.isIncognito()) return;
            mHistoryPanel = new TvHistoryPanel(mActivity, tab, this::returnToControls);
            mHistoryPanel.show();
        } else if (action == R.string.brave_tv_downloads) {
            Tab tab = mActivity.getActivityTab();
            if (tab == null || tab.isDestroyed() || tab.isIncognito()) return;
            mDownloadsPanel =
                    new TvDownloadsPanel(
                            mActivity,
                            mSession,
                            OfflineContentAggregatorFactory.get(),
                            item ->
                                    DownloadManagerService.getDownloadManagerService()
                                            .removeDownload(
                                                    item.id.id, null, item.externallyRemoved),
                            this::returnToControls);
            mDownloadsPanel.show();
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
            if (action == R.string.brave_tv_back)
                mSession.navigate(mSession.capture(), TvBrowserSession.Navigation.BACK);
            if (action == R.string.brave_tv_forward)
                mSession.navigate(mSession.capture(), TvBrowserSession.Navigation.FORWARD);
            if (action == R.string.brave_tv_stop)
                mSession.navigate(mSession.capture(), TvBrowserSession.Navigation.STOP);
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
        showCursor();
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
        showCursor();
        sendPointerEvent(MotionEvent.ACTION_SCROLL, x, y, dx, -dy, SystemClock.uptimeMillis());
    }

    private void showCursor() {
        mCursorView.setVisibility(View.VISIBLE);
        mCursorView.removeCallbacks(mHideCursor);
        mCursorView.postDelayed(mHideCursor, 3000);
    }

    private void hideCursor() {
        if (!isAlive()) return;
        mCursorView.setVisibility(View.INVISIBLE);
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
        mCursorView.removeCallbacks(mHideCursor);
        mInput.cancelPress();
        mPressedContext = null;
        if (mPage != null) mPage.removeOnAttachStateChangeListener(mPageAttachmentListener);
        if (mCursorView.getParent() instanceof ViewGroup parent) parent.removeView(mCursorView);
        mPage = null;
    }

    public void destroy() {
        mPhoneRemote.stop();
        mDestroyed = true;
        mModalReturnPage = null;
        mModalReturnContext = null;
        if (mModalDialogs != null) mModalDialogs.removeObserver(mModalObserver);
        mInput.cancelPress();
        mBackCallback.remove();
        mTabObserver.destroy();
        mSession.destroy();
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
