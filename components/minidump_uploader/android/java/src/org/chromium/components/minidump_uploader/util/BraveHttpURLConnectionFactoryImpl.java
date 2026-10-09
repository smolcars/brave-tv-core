/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.components.minidump_uploader.util;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;

import java.net.HttpURLConnection;

@NullMarked
public class BraveHttpURLConnectionFactoryImpl extends HttpURLConnectionFactoryImpl {
    @Override
    public @Nullable HttpURLConnection createHttpURLConnection(String url) {
        // Crash upload is unavailable in this independent Android browser.
        return null;
    }
}
