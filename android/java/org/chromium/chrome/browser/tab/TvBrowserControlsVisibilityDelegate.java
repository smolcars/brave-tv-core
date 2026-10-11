// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tab;

import androidx.annotation.Nullable;

import org.chromium.base.Callback;
import org.chromium.cc.input.BrowserControlsState;
import org.chromium.components.browser_ui.util.BrowserControlsVisibilityDelegate;
import org.chromium.components.security_state.SecurityStateModel;
import org.chromium.content_public.browser.ImeAdapter;
import org.chromium.content_public.browser.WebContents;

/** Retains native safety/input observers while ordinary TV pages use compact controls. */
public final class TvBrowserControlsVisibilityDelegate
        extends TabStateBrowserControlsVisibilityDelegate {
    private @Nullable BrowserControlsVisibilityDelegate mAppVisibility;
    private final Callback<Integer> mAppObserver = ignored -> updateVisibilityConstraints();

    /**
     * UI thread only. The native tab retains its inherited state observers; the app delegate is
     * borrowed. Native composition's first/last observer attaches/releases the app observer,
     * including when an activity replaces this delegate without destroying the tab.
     */
    public TvBrowserControlsVisibilityDelegate(Tab tab, BrowserControlsVisibilityDelegate app) {
        super(tab);
        mAppVisibility = app;
        updateVisibilityConstraints();
    }

    @Override
    protected @BrowserControlsState int calculateVisibilityConstraints() {
        Tab tab = mTab;
        WebContents contents = tab.getWebContents();
        ImeAdapter input =
                contents != null && !contents.isDestroyed()
                        ? ImeAdapter.fromWebContents(contents)
                        : null;
        boolean dangerous =
                contents != null
                        && !contents.isDestroyed()
                        && SecurityStateModel.isContentDangerous(contents);
        return dangerous
                        || tab.isShowingErrorPage()
                        || mTab.isRendererUnresponsive()
                        || (input != null && input.focusedNodeEditable())
                        || (mAppVisibility != null
                                && mAppVisibility.get() == BrowserControlsState.SHOWN)
                ? BrowserControlsState.SHOWN
                : BrowserControlsState.HIDDEN;
    }

    @Override
    public Integer addObserver(Callback<Integer> observer, @NotifyBehavior int behavior) {
        if (!hasObservers()) {
            if (mAppVisibility != null) mAppVisibility.addSyncObserver(mAppObserver);
            updateVisibilityConstraints();
        }
        return super.addObserver(observer, behavior);
    }

    @Override
    public void removeObserver(Callback<Integer> observer) {
        super.removeObserver(observer);
        if (!hasObservers() && mAppVisibility != null) mAppVisibility.removeObserver(mAppObserver);
    }

    @Override
    public Integer get() {
        if (!hasObservers()) updateVisibilityConstraints();
        return super.get();
    }
}
