// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.
package org.chromium.chrome.browser.tv;

import android.os.Handler;
import android.os.Looper;

import org.jni_zero.CalledByNative;
import org.jni_zero.NativeMethods;

/** Bounded native IO transport. The owner and all policy run on the UI thread. */
final class TvRemoteServer {
    interface Delegate {
        void onStarted(int port);

        String onRequest(String path, String body);
    }

    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final Delegate mDelegate;
    private long mNative;

    TvRemoteServer(String address, Delegate delegate) {
        mDelegate = delegate;
        mNative = TvRemoteServerJni.get().start(this, address);
    }

    @CalledByNative
    private void started(int port) {
        mUi.post(
                () -> {
                    if (mNative != 0) mDelegate.onStarted(port);
                });
    }

    @CalledByNative
    private void request(int id, String path, String body) {
        long received = android.os.SystemClock.elapsedRealtime();
        mUi.post(
                () -> {
                    if (mNative == 0 || android.os.SystemClock.elapsedRealtime() - received > 1000)
                        return;
                    String reply = mDelegate.onRequest(path, body);
                    if (mNative != 0) TvRemoteServerJni.get().reply(mNative, id, reply);
                });
    }

    void stop() {
        if (mNative == 0) return;
        long pointer = mNative;
        mNative = 0;
        TvRemoteServerJni.get().stop(pointer);
        mUi.removeCallbacksAndMessages(null);
    }

    @NativeMethods
    interface Natives {
        long start(TvRemoteServer owner, String address);

        void reply(long pointer, int id, String body);

        void stop(long pointer);
    }
}
