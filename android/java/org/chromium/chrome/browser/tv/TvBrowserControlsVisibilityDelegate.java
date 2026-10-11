// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import androidx.annotation.Nullable;

import org.chromium.base.Callback;
import org.chromium.cc.input.BrowserControlsState;
import org.chromium.chrome.browser.tab.EmptyTabObserver;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabStateBrowserControlsVisibilityDelegate;
import org.chromium.components.browser_ui.util.BrowserControlsVisibilityDelegate;
import org.chromium.components.security_state.SecurityStateModel;
import org.chromium.content_public.browser.WebContents;

/** Retains native safety/input observers while ordinary TV pages use compact controls. */
public final class TvBrowserControlsVisibilityDelegate
        extends TabStateBrowserControlsVisibilityDelegate {
    private @Nullable BrowserControlsVisibilityDelegate mAppVisibility;
    private boolean mEditable;
    private final Callback<Integer> mAppObserver = ignored -> updateVisibilityConstraints();

    public TvBrowserControlsVisibilityDelegate(Tab tab, BrowserControlsVisibilityDelegate app) {
        super(tab);
        mAppVisibility = app;
        app.addSyncObserver(mAppObserver);
        tab.addObserver(
                new EmptyTabObserver() {
                    @Override
                    public void onDestroyed(Tab destroyed) {
                        app.removeObserver(mAppObserver);
                    }
                });
        updateVisibilityConstraints();
    }

    @Override
    protected @BrowserControlsState int calculateVisibilityConstraints() {
        WebContents contents = mTab.getWebContents();
        boolean dangerous =
                contents != null
                        && !contents.isDestroyed()
                        && SecurityStateModel.isContentDangerous(contents);
        return dangerous
                        || mTab.isShowingErrorPage()
                        || mTab.isRendererUnresponsive()
                        || mEditable
                        || (mAppVisibility != null
                                && mAppVisibility.get() == BrowserControlsState.SHOWN)
                ? BrowserControlsState.SHOWN
                : BrowserControlsState.HIDDEN;
    }

    @Override
    public void onNodeAttributeUpdated(boolean editable, boolean password) {
        mEditable = editable;
        super.onNodeAttributeUpdated(editable, password);
    }
}
