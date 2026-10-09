// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.ui.appmenu;

/** Lets the menu module open the TV controls without depending on the activity module. */
public interface TvMenuDelegate {
    void showTvBrowserControls();
}
