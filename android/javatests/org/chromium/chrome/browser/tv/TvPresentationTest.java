// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.chromium.base.ThreadUtils;
import org.chromium.base.test.util.CommandLineFlags;
import org.chromium.base.test.util.CriteriaHelper;
import org.chromium.base.test.util.DoNotBatch;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.flags.ChromeSwitches;
import org.chromium.chrome.browser.tv.presentation.TvPresentation;
import org.chromium.chrome.test.ChromeJUnit4ClassRunner;
import org.chromium.chrome.test.ChromeTabbedActivityTestRule;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Native narrow-window traversal and stable focus across browser state updates. */
@RunWith(ChromeJUnit4ClassRunner.class)
@CommandLineFlags.Add({ChromeSwitches.DISABLE_FIRST_RUN_EXPERIENCE})
@DoNotBatch(reason = "Owns a native TV dialog.")
public class TvPresentationTest {
    @Rule
    public ChromeTabbedActivityTestRule mActivityTestRule = new ChromeTabbedActivityTestRule();

    private BraveActivity mActivity;
    private UiDevice mDevice;
    private AlertDialog mDialog;

    @Before
    public void setUp() {
        mActivityTestRule.startMainActivityOnBlankPage();
        mActivity = (BraveActivity) (Object) mActivityTestRule.getActivity();
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_controls))));
        mDevice.pressBack();
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
    }

    @After
    public void tearDown() {
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    if (mDialog != null) mDialog.dismiss();
                });
    }

    private TvPresentation.Action action(String label, int key, Runnable run) {
        return new TvPresentation.Action(label, true, run, key);
    }

    private void showNarrow(View content, int paddingDp) {
        mDialog = new AlertDialog.Builder(mActivity).setView(content).create();
        mDialog.setOnDismissListener(ignored -> TvPresentation.dispose(content));
        mDialog.setOnKeyListener((dialog, key, event) -> TvPresentation.back(content, event));
        AlertDialog dialog = mDialog;
        dialog.getOnBackPressedDispatcher()
                .addCallback(
                        new OnBackPressedCallback(true) {
                            @Override
                            public void handleOnBackPressed() {
                                if (!TvPresentation.collapseControls(content)) dialog.cancel();
                            }
                        });
        mDialog.show();
        var window = mDialog.getWindow();
        assertNotNull(window);
        var resources = mActivity.getResources();
        var metrics = resources.getDisplayMetrics();
        // Two readable columns at either baseline or enlarged system text size.
        int width =
                Math.round(
                        (308 * resources.getConfiguration().fontScale + paddingDp)
                                * metrics.density);
        assertTrue(width < metrics.widthPixels);
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private UiObject2 focused(String label) {
        CriteriaHelper.pollInstrumentationThread(
                () -> {
                    UiObject2 target = mDevice.findObject(By.desc(label));
                    return target != null && target.isFocused();
                });
        return mDevice.findObject(By.desc(label));
    }

    @Test
    @MediumTest
    public void narrowHomeShortcutsKeepFocusAndCallbacks() {
        AtomicInteger settingsRuns = new AtomicInteger();
        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        showNarrow(
                                TvPresentation.home(
                                        mActivity,
                                        "Home",
                                        "",
                                        "",
                                        List.of(
                                                action("Address or search", 1, () -> {}),
                                                action("Browser controls", 2, () -> {}),
                                                action("Tabs", 3, () -> {}),
                                                action("Bookmarks", 4, () -> {}),
                                                action(
                                                        "Settings",
                                                        5,
                                                        settingsRuns::incrementAndGet)),
                                        List.of(),
                                        new TvPresentation.HomeState(false, "", "", "", "", "")),
                                96));
        focused("Address or search");
        mDevice.pressDPadDown();
        UiObject2 controls = focused("Browser controls");
        mDevice.pressDPadRight();
        UiObject2 tabs = focused("Tabs");
        assertEquals(controls.getVisibleBounds().centerY(), tabs.getVisibleBounds().centerY());
        mDevice.pressDPadDown();
        UiObject2 settings = focused("Settings");
        assertTrue(settings.getVisibleBounds().centerY() > tabs.getVisibleBounds().centerY());
        mDevice.pressDPadCenter();
        CriteriaHelper.pollInstrumentationThread(() -> settingsRuns.get() == 1);
        mDevice.pressDPadLeft();
        focused("Bookmarks");
        mDevice.pressDPadUp();
        focused("Browser controls");
        mDevice.pressDPadUp();
        focused("Address or search");
        assertEquals(1, settingsRuns.get());
    }

    private List<TvPresentation.Action> controls() {
        return List.of(
                action("Page cursor", 1, () -> {}),
                action("Go back", 2, () -> {}),
                action("Go forward", 3, () -> {}),
                action("Address or search", 4, () -> {}),
                action("Reload page", 5, () -> {}),
                action("Tabs", 6, () -> {}),
                action("Bookmarks", 7, () -> {}),
                action("Downloads", 8, () -> {}));
    }

    @Test
    @MediumTest
    public void narrowControlsUpdateAndBackRetainInvokerFocus() {
        View content =
                ThreadUtils.runOnUiThreadBlocking(
                        () ->
                                TvPresentation.controls(
                                        mActivity,
                                        "Browser controls",
                                        "",
                                        controls(),
                                        4,
                                        "More controls"));
        ThreadUtils.runOnUiThreadBlocking(() -> showNarrow(content, 48));
        focused("Address or search");
        ThreadUtils.runOnUiThreadBlocking(
                () -> TvPresentation.updateControls(content, "Page state changed", controls()));
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.text("Page state changed")));
        focused("Address or search");
        mDevice.pressDPadDown();
        focused("Tabs");
        mDevice.pressDPadDown();
        focused("More controls");
        mDevice.pressDPadCenter();
        focused("Downloads");
        mDevice.pressBack();
        focused("More controls");
        assertTrue(ThreadUtils.runOnUiThreadBlocking(mDialog::isShowing));
    }
}
