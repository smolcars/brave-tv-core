// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import org.chromium.base.ThreadUtils;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.ActivityTabProvider.ActivityTabTabObserver;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tv.presentation.TvPresentation;
import org.chromium.content_public.browser.WebContents;

import java.util.function.BooleanSupplier;

/** Selected-tab loading feedback, independent of page input and document leases. */
final class TvPageLoadIndicator {
    private final BraveActivity mActivity;
    private final BooleanSupplier mCanShow;
    private final ActivityTabTabObserver mObserver;
    private final TvPresentation.LoadingState mState = new TvPresentation.LoadingState();
    private final View mDecor;
    private final ViewTreeObserver.OnGlobalFocusChangeListener mFocusObserver =
            (previous, current) -> refresh();
    private final ViewTreeObserver.OnGlobalLayoutListener mLayoutObserver = this::refresh;
    private @Nullable View mView;
    private boolean mPaused;
    private boolean mDestroyed;

    TvPageLoadIndicator(BraveActivity activity, BooleanSupplier canShow) {
        ThreadUtils.assertOnUiThread();
        mActivity = activity;
        mCanShow = canShow;
        mDecor = activity.getWindow().getDecorView();
        mObserver =
                new ActivityTabTabObserver(activity.getActivityTabProvider(), true) {
                    @Override
                    protected void onObservingDifferentTab(@Nullable Tab tab) {
                        refresh();
                    }

                    @Override
                    public void onLoadStarted(Tab tab, boolean toDifferentDocument) {
                        refresh();
                    }

                    @Override
                    public void onLoadStopped(Tab tab, boolean toDifferentDocument) {
                        refresh();
                    }

                    @Override
                    public void onLoadProgressChanged(Tab tab, float progress) {
                        refresh();
                    }

                    @Override
                    public void onContentChanged(Tab tab) {
                        refresh();
                    }

                    @Override
                    public void onSSLStateUpdated(Tab tab) {
                        refresh();
                    }

                    @Override
                    public void onDestroyed(Tab tab) {
                        hide();
                    }

                    @Override
                    public void onCrash(Tab tab) {
                        hide();
                    }
                };
        mDecor.getViewTreeObserver().addOnGlobalFocusChangeListener(mFocusObserver);
        mDecor.getViewTreeObserver().addOnGlobalLayoutListener(mLayoutObserver);
    }

    void refresh() {
        ThreadUtils.assertOnUiThread();
        if (mDestroyed) return;
        Tab tab = mActivity.getActivityTab();
        WebContents contents = tab == null || tab.isDestroyed() ? null : tab.getWebContents();
        if (mPaused
                || mActivity.isFinishing()
                || mActivity.isDestroyed()
                || !mActivity.hasWindowFocus()
                || tab == null
                || tab.isDestroyed()
                || !tab.isLoading()
                || contents == null
                || contents.isDestroyed()
                || !mCanShow.getAsBoolean()) {
            hide();
            return;
        }
        // TabImpl.getProgress truncates fractional progress; read the engine value directly.
        mState.setProgress((float) contents.getLoadProgress());
        if (mView != null) return;
        View root = mActivity.findViewById(android.R.id.content);
        if (!(root instanceof FrameLayout parent)) return;
        float density = mActivity.getResources().getDisplayMetrics().density;
        int width = parent.getWidth();
        if (width == 0) width = mActivity.getResources().getDisplayMetrics().widthPixels;
        View view =
                TvPresentation.loading(
                        mActivity, mActivity.getString(R.string.brave_tv_page_loading), mState);
        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(
                        Math.min(Math.round(300 * density), Math.round(width * 0.8f)),
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(32 * density);
        params.setMarginEnd(Math.round(48 * density));
        mView = view;
        parent.addView(view, params);
    }

    void onPause() {
        mPaused = true;
        hide();
    }

    int bottom() {
        return mView == null ? 0 : mView.getBottom();
    }

    void onResume() {
        mPaused = false;
        refresh();
    }

    private void hide() {
        if (mView == null) return;
        TvPresentation.dispose(mView);
        if (mView.getParent() instanceof ViewGroup parent) parent.removeView(mView);
        mView = null;
    }

    void destroy() {
        mDestroyed = true;
        mObserver.destroy();
        if (mDecor.getViewTreeObserver().isAlive()) {
            mDecor.getViewTreeObserver().removeOnGlobalFocusChangeListener(mFocusObserver);
            mDecor.getViewTreeObserver().removeOnGlobalLayoutListener(mLayoutObserver);
        }
        hide();
    }
}
