// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;

import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabHidingType;
import org.chromium.chrome.browser.tab.TabObserver;
import org.chromium.content_public.browser.MediaSession;
import org.chromium.content_public.browser.MediaSessionObserver;
import org.chromium.content_public.browser.NavigationHandle;
import org.chromium.content_public.browser.WebContents;
import org.chromium.media_session.mojom.MediaSession.SuspendType;
import org.chromium.media_session.mojom.MediaSessionAction;

import java.util.Set;

/** Native playback actions scoped to the tab that opened the panel. */
final class TvMediaPanel {
    private final BraveActivity mActivity;
    private final Tab mTab;
    private final WebContents mWebContents;
    private final MediaSession mSession;
    private final MediaSessionObserver mObserver;
    private final TvBrowserPanel mPanel;
    private final Runnable mOnClose;
    private final MaterialButton mPlayPause;
    private final MaterialButton mBackward;
    private final MaterialButton mForward;
    private final MaterialButton mControls;
    private @Nullable AlertDialog mDialog;
    private Set<Integer> mActions = Set.of();
    private boolean mControllable;
    private boolean mSuspended;
    private boolean mDestroyed;
    private final TabObserver mTabObserver =
            new TabObserver() {
                @Override
                public void onHidden(Tab tab, @TabHidingType int type) {
                    destroy();
                }

                @Override
                public void onDestroyed(Tab tab) {
                    destroy();
                }

                @Override
                public void onContentChanged(Tab tab) {
                    destroy();
                }

                @Override
                public void onDidFinishNavigationInPrimaryMainFrame(
                        Tab tab, NavigationHandle navigation) {
                    if (navigation.hasCommitted() && !navigation.isSameDocument()) destroy();
                }
            };

    TvMediaPanel(BraveActivity activity, Tab tab, WebContents contents, Runnable onClose) {
        mActivity = activity;
        mTab = tab;
        mWebContents = contents;
        mOnClose = onClose;
        mSession = MediaSession.fromWebContents(contents);
        mControllable = mSession.isControllable();
        mPanel =
                new TvBrowserPanel(
                        activity,
                        activity.getString(R.string.brave_tv_playback),
                        activity.getString(R.string.brave_tv_playback_description),
                        R.string.brave_tv_play);
        mPlayPause = mPanel.addAction(R.string.brave_tv_play, mControllable, this::togglePlayback);
        mBackward = mPanel.addAction(R.string.brave_tv_seek_backward, false, () -> seek(-10000));
        mForward = mPanel.addAction(R.string.brave_tv_seek_forward, false, () -> seek(10000));
        mControls = mPanel.addAction(R.string.brave_tv_controls, true, onClose);
        // Registration immediately replays cached state, so build the buttons first.
        mObserver =
                new MediaSessionObserver(mSession) {
                    @Override
                    public void mediaSessionStateChanged(boolean controllable, boolean suspended) {
                        mControllable = controllable;
                        mSuspended = suspended;
                        updateButtons();
                    }

                    @Override
                    public void mediaSessionActionsChanged(Set<Integer> actions) {
                        mActions = Set.copyOf(actions);
                        updateButtons();
                    }

                    @Override
                    public void mediaSessionDestroyed() {
                        destroy();
                    }
                };
        tab.addObserver(mTabObserver);
    }

    private boolean isUsable() {
        return !mDestroyed
                && !mActivity.isFinishing()
                && !mActivity.isDestroyed()
                && !mTab.isDestroyed()
                && mActivity.getActivityTab() == mTab
                && mTab.getWebContents() == mWebContents
                && !mWebContents.isDestroyed();
    }

    void show() {
        if (isUsable()) mDialog = mPanel.show(mOnClose);
    }

    void destroy() {
        if (mDestroyed) return;
        mDestroyed = true;
        mObserver.stopObserving();
        mTab.removeObserver(mTabObserver);
        if (mDialog != null) mDialog.dismiss();
        mDialog = null;
    }

    private boolean canSeek(int action) {
        return mControllable && mActions.contains(action);
    }

    private void updateButtons() {
        if (!isUsable()) return;
        mPlayPause.setText(mSuspended ? R.string.brave_tv_play : R.string.brave_tv_pause);
        setEnabled(mPlayPause, mControllable);
        setEnabled(mBackward, canSeek(MediaSessionAction.SEEK_BACKWARD));
        setEnabled(mForward, canSeek(MediaSessionAction.SEEK_FORWARD));
    }

    private void setEnabled(MaterialButton button, boolean enabled) {
        boolean moveFocus = button.hasFocus() && !enabled;
        button.setEnabled(enabled);
        button.setFocusableInTouchMode(enabled);
        button.setAlpha(enabled ? 1f : 0.4f);
        if (moveFocus) mControls.requestFocus();
    }

    private void togglePlayback() {
        if (!isUsable() || !mControllable) return;
        if (mSuspended) {
            mSession.resume(SuspendType.UI);
        } else {
            mSession.suspend(SuspendType.UI);
        }
    }

    private void seek(long millis) {
        int action =
                millis < 0 ? MediaSessionAction.SEEK_BACKWARD : MediaSessionAction.SEEK_FORWARD;
        if (isUsable() && canSeek(action)) mSession.seek(millis);
    }
}
