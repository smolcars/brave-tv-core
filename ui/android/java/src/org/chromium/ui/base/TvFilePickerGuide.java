// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.ui.base;

import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;

/** Resumes the initialized native picker after optional host-owned TV guidance. */
@NullMarked
public final class TvFilePickerGuide {
    /** The host owns document/lifecycle validation and completes exactly one callback. */
    public interface Host {
        boolean showTvFilePickerGuide(Runnable openPicker, Runnable cancelPicker);
    }

    private boolean mResuming;

    public boolean show(WindowAndroid window, Runnable openPicker, Runnable cancelPicker) {
        ThreadUtils.assertOnUiThread();
        if (mResuming || !(window.getActivity().get() instanceof Host host)) return false;
        return host.showTvFilePickerGuide(
                () -> {
                    // Re-enter only this synchronous native invocation, never another request.
                    mResuming = true;
                    try {
                        openPicker.run();
                    } finally {
                        mResuming = false;
                    }
                },
                cancelPicker);
    }
}
