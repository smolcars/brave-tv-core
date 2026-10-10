// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.
package org.chromium.chrome.browser.tv;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** UI-thread session policy. No credentials survive this instance. */
final class TvRemoteSession {
    private static final long MAX_SAFE_INTEGER = 9007199254740991L;
    private static final SecureRandom RANDOM = new SecureRandom();
    final String invitation = randomToken();
    final String code = String.format(java.util.Locale.ROOT, "%08d", RANDOM.nextInt(100000000));
    final String epoch = randomToken();
    private final long mStarted;
    private String mController = "";
    private long mLastSeen;
    private long mSequence;
    private int mAttempts;
    private boolean mApproved;
    private boolean mRevoked;

    TvRemoteSession(long now) {
        mStarted = now;
        mLastSeen = now;
    }

    static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    boolean expired(long now) {
        return mRevoked
                || now - mStarted >= 35 * 60_000L
                || (!mApproved && now - mStarted >= 120_000L)
                || (mApproved && now - mLastSeen >= 120_000L);
    }

    String pair(String secret, long now) {
        if (expired(now) || !mController.isEmpty() || ++mAttempts > 5) return "";
        if (!equal(secret, invitation) && !equal(secret, code)) return "";
        mController = randomToken();
        return mController;
    }

    boolean authenticate(String token, long now) {
        if (expired(now) || mController.isEmpty() || !equal(token, mController)) return false;
        mLastSeen = now;
        return true;
    }

    boolean pending() {
        return !mController.isEmpty() && !mApproved && !mRevoked;
    }

    boolean approved() {
        return mApproved && !mRevoked;
    }

    void approve() {
        if (pending()) mApproved = true;
    }

    void revoke() {
        mRevoked = true;
        mController = "";
    }

    // Protocol identities must survive a JavaScript round trip without coercion.
    static long parseInteger(Object value) {
        if (!(value instanceof Number number)) return -1;
        double parsed = number.doubleValue();
        if (!Double.isFinite(parsed)
                || parsed < 0
                || parsed > MAX_SAFE_INTEGER
                || parsed != Math.rint(parsed)) return -1;
        return (long) parsed;
    }

    boolean acceptSequence(long sequence) {
        if (!approved() || sequence <= mSequence || sequence > MAX_SAFE_INTEGER) return false;
        mSequence = sequence;
        return true;
    }

    private static boolean equal(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
