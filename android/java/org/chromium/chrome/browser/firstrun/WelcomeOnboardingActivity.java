/* Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */
package org.chromium.chrome.browser.firstrun;

import android.app.Activity;
import android.widget.FrameLayout;

import org.chromium.base.ActivityState;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.ApplicationStatus.ActivityStateListener;
import org.chromium.base.Log;
import org.chromium.build.annotations.NullMarked;
import org.chromium.chrome.browser.BraveLocalState;
import org.chromium.chrome.browser.metrics.ChangeMetricsReportingStateCalledFrom;
import org.chromium.chrome.browser.metrics.UmaSessionStats;
import org.chromium.chrome.browser.onboarding.OnboardingPrefManager;
import org.chromium.chrome.browser.preferences.BravePref;

/** Completes first run without onboarding, reporting opt-ins, or promotional UI. */
@NullMarked
public class WelcomeOnboardingActivity extends FirstRunActivityBase {
    private static final String TAG = "WelcomeOnboarding";

    @Override
    public void triggerLayoutInflation() {
        super.triggerLayoutInflation();
        setContentView(new FrameLayout(this));
        onInitialLayoutInflationComplete();
    }

    @Override
    public void finishNativeInitialization() {
        super.finishNativeInitialization();
        setMetricsReportingConsent(false, true);
        setP3aConsent(false);
        FirstRunStatus.setFirstRunFlowComplete(true);
        FirstRunUtils.setEulaAccepted();
        launchPendingIntentAndFinish();
    }

    private void setMetricsReportingConsent(final boolean enabled, final boolean markAsShown) {
        try {
            // Updates reporting consent for first run.
            UmaSessionStats.changeMetricsReportingState(
                    enabled, ChangeMetricsReportingStateCalledFrom.UI_FIRST_RUN);
        } catch (Exception e) {
            Log.e(TAG, "CrashReportingOnboarding", e);
        }
        if (markAsShown) {
            // Marks crash reporting message as shown.
            OnboardingPrefManager.getInstance().setP3aCrashReportingMessageShown(true);
        }
    }

    private void setP3aConsent(final boolean consent) {
        try {
            BraveLocalState.get().setBoolean(BravePref.P3A_ENABLED, consent);
            BraveLocalState.get().setBoolean(BravePref.P3A_NOTICE_ACKNOWLEDGED, true);
            BraveLocalState.commitPendingWrite();
        } catch (Exception e) {
            Log.e(TAG, "P3aOnboarding", e);
        }
    }

    // Wait for the destination to resume before finishing, avoiding a handoff flash.
    private void launchPendingIntentAndFinish() {
        if (!sendFirstRunCompleteIntent()) {
            finish();
        } else {
            ApplicationStatus.registerStateListenerForAllActivities(
                    new ActivityStateListener() {
                        @Override
                        public void onActivityStateChange(Activity activity, int newState) {
                            boolean shouldFinish;
                            if (activity == WelcomeOnboardingActivity.this) {
                                shouldFinish =
                                        (newState == ActivityState.STOPPED
                                                || newState == ActivityState.DESTROYED);
                            } else {
                                shouldFinish = newState == ActivityState.RESUMED;
                            }
                            if (shouldFinish) {
                                finish();
                                ApplicationStatus.unregisterActivityStateListener(this);
                            }
                        }
                    });
        }
    }

    @Override
    public @BackPressResult int handleBackPress() {
        return BackPressResult.SUCCESS;
    }
}
