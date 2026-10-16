// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

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
    private View mBadge;

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
                    if (mBadge != null) {
                        TvPresentation.dispose(mBadge);
                        if (mBadge.getParent() instanceof ViewGroup parent)
                            parent.removeView(mBadge);
                    }
                });
    }

    private TvPresentation.Action action(String label, int key, Runnable run) {
        return new TvPresentation.Action(label, true, run, key);
    }

    private void showNarrow(View content, int paddingDp, int contentWidthDp) {
        mDialog = new AlertDialog.Builder(mActivity).setView(content).create();
        mDialog.setOnDismissListener(ignored -> TvPresentation.dispose(content));
        AlertDialog dialog = mDialog;
        dialog.getOnBackPressedDispatcher()
                .addCallback(
                        new OnBackPressedCallback(true) {
                            @Override
                            public void handleOnBackPressed() {
                                dialog.cancel();
                            }
                        });
        mDialog.show();
        var window = mDialog.getWindow();
        assertNotNull(window);
        window.setBackgroundDrawableResource(android.R.color.transparent);
        var resources = mActivity.getResources();
        var metrics = resources.getDisplayMetrics();
        // Two readable columns at either baseline or enlarged system text size.
        int width =
                Math.round(
                        (contentWidthDp * resources.getConfiguration().fontScale + paddingDp)
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

    private void visibleWithEdgeSpace(String label) {
        int bottom =
                mDevice.getDisplayHeight()
                        - Math.round(12 * mActivity.getResources().getDisplayMetrics().density);
        CriteriaHelper.pollInstrumentationThread(
                () -> {
                    UiObject2 target = mDevice.findObject(By.desc(label));
                    return target != null
                            && target.isFocused()
                            && target.getVisibleBounds().height() > 0
                            && target.getVisibleBounds().bottom <= bottom;
                });
    }

    @Test
    @MediumTest
    public void narrowHomeShortcutsKeepFocusAndCallbacks() {
        AtomicInteger phoneRuns = new AtomicInteger();
        AtomicInteger sourceRuns = new AtomicInteger();
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
                                                action("Home", 2, () -> {}),
                                                action("Tabs", 3, () -> {}),
                                                action("Favorites", 4, () -> {}),
                                                new TvPresentation.Action(
                                                        "Phone connected",
                                                        true,
                                                        phoneRuns::incrementAndGet,
                                                        5,
                                                        R.drawable.brave_tv_phone)),
                                        List.of(action("GitHub", 6, sourceRuns::incrementAndGet)),
                                        new TvPresentation.HomeState(false, "", "", "", "", "")),
                                96,
                                308));
        focused("Address or search");
        Rect homeBounds = mDevice.findObject(By.desc("Home")).getVisibleBounds();
        for (String label : List.of("Tabs", "Favorites")) {
            Rect bounds = mDevice.findObject(By.desc(label)).getVisibleBounds();
            assertEquals(homeBounds.width(), bounds.width());
            assertEquals(homeBounds.height(), bounds.height());
        }
        mDevice.pressDPadUp();
        focused("Home");
        mDevice.pressDPadRight();
        focused("Tabs");
        mDevice.pressDPadDown();
        focused("Phone connected");
        mDevice.pressDPadLeft();
        focused("Favorites");
        mDevice.pressDPadRight();
        focused("Phone connected");
        mDevice.pressDPadCenter();
        CriteriaHelper.pollInstrumentationThread(() -> phoneRuns.get() == 1);
        mDevice.pressDPadDown();
        focused("Address or search");
        assertEquals(1, phoneRuns.get());
        mDevice.pressDPadDown();
        focused("GitHub");
        visibleWithEdgeSpace("GitHub");
        mDevice.pressDPadCenter();
        CriteriaHelper.pollInstrumentationThread(() -> sourceRuns.get() == 1);
        mDevice.pressDPadUp();
        focused("Address or search");
    }

    @Test
    @MediumTest
    public void connectionBadgePreservesPageFocusAndDisposes() {
        AtomicInteger opens = new AtomicInteger();
        View page =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> mActivity.getActivityTab().getContentView());
        assertNotNull(page);
        String label = mActivity.getString(R.string.brave_tv_phone_connected);
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertTrue(page.requestFocus());
                    View focused = mActivity.getCurrentFocus();
                    mBadge =
                            TvPresentation.connectionBadge(
                                    mActivity, label, R.drawable.brave_tv_phone, opens::incrementAndGet);
                    ((FrameLayout) mActivity.findViewById(android.R.id.content))
                            .addView(
                                    mBadge,
                                    new FrameLayout.LayoutParams(
                                            ViewGroup.LayoutParams.WRAP_CONTENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT));
                    assertEquals(focused, mActivity.getCurrentFocus());
                });
        CriteriaHelper.pollInstrumentationThread(() -> mDevice.hasObject(By.desc(label)));
        Rect bounds = mDevice.findObject(By.desc(label)).getVisibleBounds();
        int badgeSize = Math.round(48 * mActivity.getResources().getDisplayMetrics().density);
        assertEquals(badgeSize, bounds.width());
        assertEquals(badgeSize, bounds.height());
        assertFalse(mDevice.hasObject(By.text(label)));
        ThreadUtils.runOnUiThreadBlocking(() -> assertEquals(page, mActivity.getCurrentFocus()));
        mDevice.findObject(By.desc(label)).click();
        CriteriaHelper.pollInstrumentationThread(() -> opens.get() == 1);
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    TvPresentation.dispose(mBadge);
                    ((ViewGroup) mBadge.getParent()).removeView(mBadge);
                    mBadge = null;
                    assertEquals(page, mActivity.getCurrentFocus());
                });
        CriteriaHelper.pollInstrumentationThread(() -> !mDevice.hasObject(By.desc(label)));
        assertEquals(1, opens.get());
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
                                        "Initial native status",
                                        controls(),
                                        4));
        ThreadUtils.runOnUiThreadBlocking(() -> showNarrow(content, 48, 380));
        focused("Address or search");
        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        TvPresentation.updateControls(
                                content,
                                "Page state changed",
                                "Updated native status",
                                controls()));
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.text("Page state changed")));
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.text("Updated native status")));
        focused("Address or search");
        mDevice.pressDPadDown();
        focused("Tabs");
        mDevice.pressDPadDown();
        focused("Downloads");
        visibleWithEdgeSpace("Downloads");
        mDevice.pressBack();
        CriteriaHelper.pollUiThread(() -> !mDialog.isShowing());
        assertFalse(ThreadUtils.runOnUiThreadBlocking(mDialog::isShowing));
    }

    @Test
    @MediumTest
    public void menuIconsAndLongLabelsKeepUniformTargets() {
        AtomicInteger bookmarksRuns = new AtomicInteger();
        List<TvPresentation.Action> actions =
                List.of(
                        new TvPresentation.Action(
                                "Search", true, () -> {}, 1, R.drawable.brave_tv_search),
                        new TvPresentation.Action(
                                "Jump between elements",
                                true,
                                () -> {},
                                2,
                                R.drawable.brave_tv_tabs),
                        new TvPresentation.Action(
                                "Tabs", true, () -> {}, 3, R.drawable.brave_tv_tabs),
                        new TvPresentation.Action(
                                "Bookmarks",
                                true,
                                bookmarksRuns::incrementAndGet,
                                4,
                                R.drawable.brave_tv_bookmark));
        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        showNarrow(
                                TvPresentation.controls(
                                        mActivity, "Browser controls", "", "", actions, 1),
                                48,
                                380));
        Rect first = focused("Search").getVisibleBounds();
        for (TvPresentation.Action action : actions) {
            UiObject2 button = mDevice.findObject(By.desc(action.getLabel()));
            assertNotNull(button);
            Rect bounds = button.getVisibleBounds();
            assertEquals(first.width(), bounds.width());
            assertEquals(first.height(), bounds.height());
        }
        mDevice.pressDPadRight();
        focused("Jump between elements");
        mDevice.pressDPadDown();
        focused("Bookmarks");
        mDevice.pressDPadCenter();
        CriteriaHelper.pollInstrumentationThread(() -> bookmarksRuns.get() == 1);
    }

    @Test
    @MediumTest
    public void longPanelTitleKeepsConservativeActionsReachable() {
        AtomicInteger cancels = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        String cancel = mActivity.getString(R.string.cancel);
        String delete = mActivity.getString(R.string.brave_tv_delete_download);
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    TvBrowserPanel panel =
                            new TvBrowserPanel(
                                    mActivity,
                                    "Long synthetic title العربية 日本語 ".repeat(20),
                                    "Synthetic item only",
                                    R.string.cancel);
                    panel.addAction(R.string.cancel, true, cancels::incrementAndGet);
                    panel.addAction(
                            R.string.brave_tv_delete_download, true, deletes::incrementAndGet);
                    mDialog = panel.show(() -> {});
                });
        focused(cancel);
        visibleWithEdgeSpace(cancel);
        mDevice.pressDPadRight();
        focused(delete);
        visibleWithEdgeSpace(delete);
        mDevice.pressDPadLeft();
        focused(cancel);
        mDevice.pressDPadCenter();
        CriteriaHelper.pollInstrumentationThread(() -> cancels.get() == 1);
        assertEquals(0, deletes.get());
    }
}
