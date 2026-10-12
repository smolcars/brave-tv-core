// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import android.view.View;

import androidx.annotation.Nullable;

import org.chromium.base.ThreadUtils;
import org.chromium.chrome.browser.ActivityTabProvider.ActivityTabTabObserver;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.content_public.browser.NavigationHandle;
import org.chromium.content_public.browser.WebContents;
import org.chromium.content_public.browser.WebContentsObserver;

/** UI-thread native identity and navigation shared by TV presentation and phone commands. */
final class TvBrowserSession {
    enum Navigation {
        BACK,
        FORWARD,
        RELOAD,
        STOP
    }

    /** A callback/input lease, never a browser model or persisted presentation state. */
    static final class Context {
        final @Nullable Tab tab;
        final @Nullable WebContents contents;
        final long generation;
        final long documentGeneration;
        final long revision;
        final String url;
        final int width;
        final int height;
        final int offset;

        private Context(
                @Nullable Tab tab,
                @Nullable WebContents contents,
                long generation,
                long documentGeneration,
                long revision,
                int width,
                int height,
                int offset) {
            this.tab = tab;
            this.contents = contents;
            this.generation = generation;
            this.documentGeneration = documentGeneration;
            this.revision = revision;
            this.url = tab == null ? "" : tab.getUrl().getSpec();
            this.width = width;
            this.height = height;
            this.offset = offset;
        }
    }

    private final BraveActivity mActivity;
    private final Runnable mOnChanged;
    private final ActivityTabTabObserver mTabObserver;
    private @Nullable Tab mTab;
    private @Nullable WebContents mContents;
    private @Nullable WebContentsObserver mContentsObserver;
    private @Nullable View mViewport;
    private long mGeneration = 1;
    private long mDocumentGeneration = 1;
    private long mRevision = 1;
    private boolean mDestroyed;
    private final View.OnLayoutChangeListener mLayoutObserver =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                    invalidateViewport();
                }
            };

    TvBrowserSession(BraveActivity activity, Runnable onChanged) {
        ThreadUtils.assertOnUiThread();
        mActivity = activity;
        mOnChanged = onChanged;
        mTabObserver =
                new ActivityTabTabObserver(activity.getActivityTabProvider()) {
                    @Override
                    protected void onObservingDifferentTab(@Nullable Tab tab) {
                        refresh();
                    }

                    @Override
                    public void onContentChanged(Tab tab) {
                        refresh();
                        invalidate();
                    }

                    @Override
                    public void onHidden(Tab tab, int type) {
                        invalidate();
                    }

                    @Override
                    public void onDestroyed(Tab tab) {
                        refresh();
                        invalidate();
                    }

                    @Override
                    public void onLoadStarted(Tab tab, boolean toDifferentDocument) {
                        mOnChanged.run();
                    }

                    @Override
                    public void onLoadStopped(Tab tab, boolean toDifferentDocument) {
                        mOnChanged.run();
                    }

                    @Override
                    public void onUrlUpdated(Tab tab) {
                        mOnChanged.run();
                    }

                    @Override
                    public void onTitleUpdated(Tab tab) {
                        mOnChanged.run();
                    }

                    @Override
                    public void onSSLStateUpdated(Tab tab) {
                        mOnChanged.run();
                    }
                };
        // The supplier posts its initial notification; capture also refreshes synchronously.
    }

    void invalidate() {
        ThreadUtils.assertOnUiThread();
        if (mDestroyed) return;
        mDocumentGeneration++;
        invalidateViewport();
    }

    private void invalidateViewport() {
        ThreadUtils.assertOnUiThread();
        if (mDestroyed) return;
        mGeneration++;
        mRevision++;
        mOnChanged.run();
    }

    private void refresh() {
        if (mDestroyed) return;
        Tab tab = mActivity.getActivityTab();
        if (tab != null && tab.isDestroyed()) tab = null;
        WebContents contents = tab == null ? null : tab.getWebContents();
        View viewport = tab == null ? null : tab.getContentView();
        if (tab == mTab && contents == mContents && viewport == mViewport) return;
        if (mContentsObserver != null) mContentsObserver.observe(null);
        if (mViewport != null) mViewport.removeOnLayoutChangeListener(mLayoutObserver);
        mTab = tab;
        mContents = contents;
        mViewport = viewport;
        if (viewport != null) viewport.addOnLayoutChangeListener(mLayoutObserver);
        mContentsObserver =
                contents == null
                        ? null
                        : new WebContentsObserver(contents) {
                            @Override
                            public void didStartNavigationInPrimaryMainFrame(
                                    NavigationHandle navigation) {
                                invalidate();
                            }

                            @Override
                            public void didFinishNavigationInPrimaryMainFrame(
                                    NavigationHandle navigation) {
                                invalidate();
                            }

                            @Override
                            public void primaryMainFrameRenderProcessGone(int status) {
                                invalidate();
                            }

                            @Override
                            public void didToggleFullscreenModeForTab(
                                    boolean fullscreen, boolean resize) {
                                // Fullscreen changes coordinate ownership, not the document.
                                invalidateViewport();
                            }

                            @Override
                            public void onWebContentsLostFocus() {
                                // Opening our own panel changes input ownership, not the underlying
                                // document.
                                mRevision++;
                                mOnChanged.run();
                            }
                        };
        invalidate();
    }

    Context capture() {
        ThreadUtils.assertOnUiThread();
        refresh();
        return new Context(
                mTab,
                mContents,
                mGeneration,
                mDocumentGeneration,
                mRevision,
                mViewport == null ? 0 : mViewport.getWidth(),
                mViewport == null ? 0 : mViewport.getHeight(),
                mActivity.getBrowserControlsManager().getContentOffset());
    }

    boolean isCurrent(@Nullable Context expected) {
        ThreadUtils.assertOnUiThread();
        if (expected == null || mDestroyed || mActivity.isFinishing() || mActivity.isDestroyed()) {
            return false;
        }
        Context current = capture();
        return isCurrentDocument(expected, current)
                && expected.generation == current.generation
                && expected.width == current.width
                && expected.height == current.height
                && expected.offset == current.offset;
    }

    /** Document actions have no page coordinates; IME/layout may resize the same document. */
    boolean isCurrentDocument(@Nullable Context expected) {
        ThreadUtils.assertOnUiThread();
        if (expected == null || mDestroyed || mActivity.isFinishing() || mActivity.isDestroyed()) {
            return false;
        }
        return isCurrentDocument(expected, capture());
    }

    private boolean isCurrentDocument(Context expected, Context current) {
        return expected.tab == current.tab
                && expected.contents == current.contents
                && expected.documentGeneration == current.documentGeneration
                && (current.contents == null || !current.contents.isDestroyed());
    }

    /** Caller retains its own native-modal/private/authentication policy. Never invokes unlock. */
    boolean navigate(Context context, Navigation action) {
        if (!isCurrent(context) || context.tab == null) return false;
        Tab tab = context.tab;
        switch (action) {
            case BACK:
                if (!tab.canGoBack()) return false;
                invalidate();
                tab.goBack();
                break;
            case FORWARD:
                if (!tab.canGoForward()) return false;
                invalidate();
                tab.goForward();
                break;
            case RELOAD:
                if (tab.isNativePage()) return false;
                invalidate();
                tab.reload();
                break;
            case STOP:
                if (!tab.isLoading()) return false;
                tab.stopLoading();
                break;
        }
        return true;
    }

    void destroy() {
        ThreadUtils.assertOnUiThread();
        mDestroyed = true;
        mTabObserver.destroy();
        if (mContentsObserver != null) mContentsObserver.observe(null);
        if (mViewport != null) mViewport.removeOnLayoutChangeListener(mLayoutObserver);
        mContentsObserver = null;
        mContents = null;
        mViewport = null;
        mTab = null;
    }
}
