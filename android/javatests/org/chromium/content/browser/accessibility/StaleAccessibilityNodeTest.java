// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.content.browser.accessibility;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.text.TextUtils;
import android.view.View;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;

import org.chromium.base.ThreadUtils;
import org.chromium.base.test.util.CommandLineFlags;
import org.chromium.base.test.util.CriteriaHelper;
import org.chromium.base.test.util.DoNotBatch;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.flags.ChromeSwitches;
import org.chromium.chrome.test.ChromeJUnit4ClassRunner;
import org.chromium.chrome.test.ChromeTabbedActivityTestRule;
import org.chromium.content_public.browser.WebContents;
import org.chromium.content_public.browser.test.util.DOMUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayDeque;

/** Android may request a video-control node after it becomes ignored and invisible. */
@RunWith(ChromeJUnit4ClassRunner.class)
@CommandLineFlags.Add({ChromeSwitches.DISABLE_FIRST_RUN_EXPERIENCE})
@DoNotBatch(reason = "Uses a real renderer and native accessibility tree.")
public class StaleAccessibilityNodeTest {
    @Rule
    public ChromeTabbedActivityTestRule mActivityTestRule = new ChromeTabbedActivityTestRule();

    private WebContentsAccessibilityImpl mAccessibility;
    private int mControlId = View.NO_ID;

    @Before
    public void setUp() throws Exception {
        mActivityTestRule.startMainActivityOnBlankPage();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        device.hasObject(
                                By.text(
                                        mActivityTestRule
                                                .getActivity()
                                                .getString(R.string.brave_tv_controls))));
        device.pressBack();
        CriteriaHelper.pollUiThread(mActivityTestRule.getActivity()::hasWindowFocus);
        String fixture =
                InstrumentationRegistry.getArguments().getString("tv_accessibility_fixture_url");
        assertNotNull("Pass the controlled ordinary video fixture URL", fixture);
        mActivityTestRule.loadUrl(fixture);
        WebContents contents =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> mActivityTestRule.getActivity().getActivityTab().getWebContents());
        mAccessibility =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> WebContentsAccessibilityImpl.fromWebContents(contents));
        assertNotNull(mAccessibility);
        CriteriaHelper.pollUiThread(
                () ->
                        mAccessibility.getAccessibilityNodeProviderCompat() != null
                                && mAccessibility.isAccessibilityEnabled());
        CriteriaHelper.pollUiThread(
                () -> {
                    mControlId = findControl();
                    return mControlId != View.NO_ID;
                });
        assertTrue(DOMUtils.clickNode(contents, "play"));
        DOMUtils.waitForMediaPlay(contents, "video");
        CriteriaHelper.pollUiThread(
                () ->
                        !WebContentsAccessibilityImplJni.get()
                                        .isNodeValid(mAccessibility.mNativeObj, mControlId)
                                && WebContentsAccessibilityImplJni.get()
                                                .getAbsolutePositionForNode(
                                                        mAccessibility.mNativeObj, mControlId)
                                        != null,
                10000,
                50);
    }

    @Test
    @MediumTest
    public void ignoredExistingNodeRejectsFreshInfo() {
        assertIgnoredInfoRejected(false);
    }

    @Test
    @MediumTest
    public void ignoredExistingNodeRejectsCachedInfo() {
        assertIgnoredInfoRejected(true);
    }

    private void assertIgnoredInfoRejected(boolean cached) {
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    AccessibilityNodeInfoCompat info =
                            AccessibilityNodeInfoCompat.obtain(
                                    mActivityTestRule
                                            .getActivity()
                                            .getActivityTab()
                                            .getContentView());
                    try {
                        assertFalse(
                                cached
                                        ? WebContentsAccessibilityImplJni.get()
                                                .updateCachedAccessibilityNodeInfo(
                                                        mAccessibility.mNativeObj, info, mControlId)
                                        : WebContentsAccessibilityImplJni.get()
                                                .populateAccessibilityNodeInfo(
                                                        mAccessibility.mNativeObj,
                                                        info,
                                                        mControlId));
                        int root = mAccessibility.getRootIdForTesting();
                        assertTrue(
                                WebContentsAccessibilityImplJni.get()
                                        .isNodeValid(mAccessibility.mNativeObj, root));
                        AccessibilityNodeInfoCompat exposed =
                                mAccessibility.createAccessibilityNodeInfo(root);
                        assertNotNull(exposed);
                        exposed.recycle();
                    } finally {
                        info.recycle();
                    }
                });
    }

    private int findControl() {
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        pending.add(mAccessibility.getRootIdForTesting());
        for (int visited = 0; !pending.isEmpty() && visited < 32; visited++) {
            int id = pending.removeFirst();
            AccessibilityNodeInfoCompat info = mAccessibility.createAccessibilityNodeInfo(id);
            if (info == null) continue;
            boolean control =
                    TextUtils.equals("android.widget.Button", info.getClassName())
                            && (TextUtils.equals("mute", info.getText())
                                    || TextUtils.equals("mute", info.getContentDescription()));
            info.recycle();
            if (control) return id;
            int[] children = mAccessibility.getChildIdsForTesting(id);
            if (children != null) for (int child : children) pending.addLast(child);
        }
        return View.NO_ID;
    }
}
