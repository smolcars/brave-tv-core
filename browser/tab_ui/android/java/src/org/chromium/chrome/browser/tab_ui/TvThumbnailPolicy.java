// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tab_ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;

import org.chromium.base.Callback;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.browser.tab.Tab;

/** Private TV cache policy; the native owner retains removal and callback delivery. */
@NullMarked
final class TvThumbnailPolicy {
    private TvThumbnailPolicy() {}

    /** UI thread only. Returns true after blocking capture and removing this tab's cache. */
    static boolean blockPrivateCapture(
            Context context,
            Tab tab,
            Runnable removeCache,
            @Nullable Callback<@Nullable Bitmap> callback) {
        ThreadUtils.assertOnUiThread();
        if (!tab.isIncognito()
                || (context.getResources().getConfiguration().uiMode
                                & Configuration.UI_MODE_TYPE_MASK)
                        != Configuration.UI_MODE_TYPE_TELEVISION) {
            return false;
        }
        removeCache.run();
        Callback.runNullSafe(callback, null);
        return true;
    }
}
