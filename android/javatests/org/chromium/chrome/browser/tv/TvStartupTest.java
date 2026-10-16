// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.test.filters.MediumTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.chromium.base.ActivityState;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.ThreadUtils;
import org.chromium.base.test.util.CommandLineFlags;
import org.chromium.base.test.util.CriteriaHelper;
import org.chromium.base.test.util.DoNotBatch;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.flags.ChromeSwitches;
import org.chromium.chrome.browser.tab.TabBrowserControlsConstraintsHelper;
import org.chromium.chrome.test.ChromeJUnit4ClassRunner;
import org.chromium.chrome.test.ChromeTabbedActivityTestRule;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Observes startup draws, including frames before native Home is ready. */
@RunWith(ChromeJUnit4ClassRunner.class)
@CommandLineFlags.Add({ChromeSwitches.DISABLE_FIRST_RUN_EXPERIENCE})
@DoNotBatch(reason = "Must observe the first browser Activity draw.")
public class TvStartupTest {
    @Rule
    public ChromeTabbedActivityTestRule mActivityTestRule =
            new ChromeTabbedActivityTestRule() {
                @Override
                public void waitForActivityCompletelyLoaded() {
                    // The upstream NTP waiter requires the mobile NewTabPage class.
                    CriteriaHelper.pollUiThread(
                            () ->
                                    getActivity().getActivityTab() != null
                                            && getActivity().getActivityTab().getNativePage()
                                                    instanceof TvHomePage);
                }
            };

    @Test
    @MediumTest
    public void homeLaunchNeverPaintsMobileToolbar() throws Exception {
        AtomicInteger draws = new AtomicInteger();
        AtomicBoolean exposed = new AtomicBoolean();
        AtomicReference<String> firstExposure = new AtomicReference<>();
        List<Runnable> cleanup = new ArrayList<>();
        ApplicationStatus.ActivityStateListener listener =
                (activity, state) -> {
                    if (!(activity instanceof BraveActivity) || state != ActivityState.CREATED)
                        return;
                    View decor = activity.getWindow().getDecorView();
                    ViewTreeObserver.OnDrawListener draw =
                            () -> {
                                draws.incrementAndGet();
                                View toolbar = activity.findViewById(R.id.control_container);
                                if (toolbar != null
                                        && toolbar.isShown()
                                        && toolbar.getGlobalVisibleRect(new Rect())) {
                                    exposed.set(true);
                                    if (firstExposure.get() == null) {
                                        Rect bounds = new Rect();
                                        toolbar.getGlobalVisibleRect(bounds);
                                        BraveActivity browser = (BraveActivity) activity;
                                        var tab = browser.getActivityTab();
                                        var manager = browser.getBrowserControlsManager();
                                        firstExposure.set(
                                                "draw="
                                                        + draws.get()
                                                        + " bounds="
                                                        + bounds
                                                        + " alpha="
                                                        + toolbar.getAlpha()
                                                        + " hiddenToken="
                                                        + manager.hasHidingTokens()
                                                        + " offset="
                                                        + manager.getContentOffset()
                                                        + " constraints="
                                                        + (tab == null
                                                                ? -1
                                                                : TabBrowserControlsConstraintsHelper
                                                                        .getConstraints(tab))
                                                        + " page="
                                                        + (tab == null
                                                                ? "no tab"
                                                                : tab.getNativePage()));
                                    }
                                }
                            };
                    decor.getViewTreeObserver().addOnDrawListener(draw);
                    cleanup.add(() -> decor.getViewTreeObserver().removeOnDrawListener(draw));
                };
        ThreadUtils.runOnUiThreadBlocking(
                () -> ApplicationStatus.registerStateListenerForAllActivities(listener));
        try {
            mActivityTestRule.startMainActivityWithURL("chrome-native://newtab/");
            BraveActivity activity = (BraveActivity) (Object) mActivityTestRule.getActivity();
            CriteriaHelper.pollUiThread(
                    () ->
                            activity.getActivityTab() != null
                                    && activity.getActivityTab().getNativePage() instanceof TvHomePage
                                    && activity.hasWindowFocus());
            Thread.sleep(3500);
            assertTrue("Startup draw observer did not run", draws.get() > 0);
            assertFalse(
                    "Mobile toolbar was visible in a startup frame: " + firstExposure.get(),
                    exposed.get());
        } finally {
            ThreadUtils.runOnUiThreadBlocking(
                    () -> {
                        ApplicationStatus.unregisterActivityStateListener(listener);
                        for (Runnable remove : cleanup) remove.run();
                    });
        }
    }
}
