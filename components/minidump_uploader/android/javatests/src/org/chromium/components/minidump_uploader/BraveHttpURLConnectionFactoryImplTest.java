/* Copyright (c) 2025 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.components.minidump_uploader;

import androidx.test.filters.SmallTest;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.components.minidump_uploader.util.BraveHttpURLConnectionFactoryImpl;
import org.chromium.components.minidump_uploader.util.HttpURLConnectionFactory;

/** Unittests for {@link BraveHttpURLConnectionFactoryImpl}. */
@RunWith(BaseRobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class BraveHttpURLConnectionFactoryImplTest {
    @Test
    @SmallTest
    public void testCrashConnectionsAreUnavailable() {
        HttpURLConnectionFactory factory = new BraveHttpURLConnectionFactoryImpl();
        Assert.assertNull(factory.createHttpURLConnection(""));
        Assert.assertNull(factory.createHttpURLConnection("https://cr.brave.com"));
    }
}
