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

import org.chromium.base.supplier.NonNullObservableSupplier;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.back_press.BackPressManager;
import org.chromium.chrome.browser.preferences.website.BraveShieldsContentSettings;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tabmodel.TabModelUtils;
import org.chromium.components.browser_ui.widget.gesture.BackPressHandler;
import org.chromium.components.embedder_support.view.ContentView;

/** TV-only adapter around the existing browser UI and the active Chromium content view. */
public final class TvBrowserControls implements TvRemoteInput.Target {
    private final BraveActivity mActivity;
    private final BackPressManager mBackPressManager;
    private final NonNullObservableSupplier<Boolean> mScrimVisible;
    private final TvRemoteInput mInput;
    private final Cursor mCursor;
    private final View mCursorView;
    private final OnBackPressedCallback mBackCallback;
    private @Nullable ContentView mPage;
    private @Nullable AlertDialog mDialog;
    private int mLastAction = R.string.brave_tv_address;
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
            NonNullObservableSupplier<Boolean> scrimVisible) {
        mActivity = activity;
        mBackPressManager = backPressManager;
        mScrimVisible = scrimVisible;
        float density = activity.getResources().getDisplayMetrics().density;
        mInput = new TvRemoteInput(this, Math.max(1, Math.round(16 * density)));
        mCursor = new Cursor(density);
        mCursorView = new View(activity);
        mCursorView.setBackground(mCursor);
        mCursorView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mBackCallback =
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (!isAlive()) return;
                        if (!keyboardVisible() && !nativeUiOwnsInput()) {
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

    private boolean keyboardVisible() {
        WindowInsetsCompat insets =
                ViewCompat.getRootWindowInsets(mActivity.getWindow().getDecorView());
        return insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
    }

    private boolean nativeUiOwnsInput() {
        ContentView page = currentPage();
        if (page == null || mScrimVisible.get()) return true;
        View focus = mActivity.getCurrentFocus();
        if (focus != null && !page.hasFocus()) return true;
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
                || nativeUiOwnsInput()) {
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

    @Override
    public void showControls() {
        if (!isAlive()) return;
        detachCursor();
        if (mDialog != null && mDialog.isShowing()) return;
        Tab tab = mActivity.getActivityTab();
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
        addControl(panel, R.string.brave_tv_menu, true, tab, url);
        mDialog = panel.show(this::dispatchNativeBack);
    }

    private void addControl(
            TvBrowserPanel panel, int action, boolean enabled, @Nullable Tab tab, String url) {
        panel.addAction(action, enabled, () -> runControl(action, tab, url));
    }

    private void dismissDialog() {
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

    private void showTabs(boolean incognito, int start) {
        if (!isAlive() || !mActivity.areTabModelsInitialized()) return;
        dismissDialog();
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
                    if (!isAlive()) return;
                    dismissDialog();
                    mActivity.onMenuOrKeyboardAction(
                            incognito ? R.id.new_incognito_tab_menu_id : R.id.new_tab_menu_id,
                            false);
                    mLastAction = R.string.brave_tv_address;
                    showControls();
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
                        TabModel currentModel = mActivity.getTabModelSelector().getModel(incognito);
                        int index = TabModelUtils.getTabIndexById(currentModel, tabId);
                        if (index == TabModel.INVALID_TAB_INDEX) {
                            showTabs(incognito, first);
                            return;
                        }
                        dismissDialog();
                        mActivity.getTabModelSelector().selectModel(incognito);
                        TabModelUtils.setIndex(currentModel, index);
                        showControls();
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

    private void showShields() {
        Tab tab = mActivity.getActivityTab();
        if (!isAlive() || !isWebTab(tab) || tab == null) return;
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
        } else if (action == R.string.brave_tv_menu) {
            mActivity.onMenuOrKeyboardAction(R.id.show_menu, false);
        } else if (action == R.string.brave_tv_reload) {
            mActivity.onMenuOrKeyboardAction(R.id.reload_menu_id, false);
        } else if (action == R.string.brave_tv_tabs) {
            Tab tab = mActivity.getActivityTab();
            showTabs(tab != null && tab.isIncognito(), 0);
        } else if (action == R.string.brave_tv_shields) {
            showShields();
        } else if (action == R.string.brave_tv_bookmarks) {
            mActivity.onMenuOrKeyboardAction(R.id.all_bookmarks_menu_id, false);
        } else if (action == R.string.brave_tv_history) {
            mActivity.onMenuOrKeyboardAction(R.id.open_history_menu_id, false);
        } else if (action == R.string.brave_tv_settings) {
            mActivity.onMenuOrKeyboardAction(R.id.preferences_id, false);
        } else if (action == R.string.brave_tv_close_tab) {
            mActivity.onMenuOrKeyboardAction(R.id.close_tab, false);
            showControls();
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
        mInput.cancelPress();
        mBackCallback.remove();
        detachCursor();
        if (mDialog != null) mDialog.dismiss();
        mDialog = null;
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
