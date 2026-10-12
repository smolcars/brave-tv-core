// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.util.Size;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.chromium.base.ThreadUtils;
import org.chromium.base.test.util.CallbackHelper;
import org.chromium.base.test.util.CommandLineFlags;
import org.chromium.base.test.util.CriteriaHelper;
import org.chromium.base.test.util.DoNotBatch;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.flags.ChromeSwitches;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab_ui.TabContentManager;
import org.chromium.chrome.test.ChromeJUnit4ClassRunner;
import org.chromium.chrome.test.ChromeTabbedActivityTestRule;
import org.chromium.content_public.browser.WebContents;
import org.chromium.content_public.browser.test.util.JavaScriptUtils;
import org.chromium.url.GURL;

import java.io.File;

/** Real native write failure and recovery, confined to one normal TEST tab's cache targets. */
@RunWith(ChromeJUnit4ClassRunner.class)
@CommandLineFlags.Add({ChromeSwitches.DISABLE_FIRST_RUN_EXPERIENCE})
@DoNotBatch(reason = "Owns normal native thumbnail write targets.")
public class TvThumbnailCacheTest {
    private static final String URL = "data:text/html,<h1>TV%20thumbnail%20write%20fixture</h1>";

    @Rule
    public ChromeTabbedActivityTestRule mActivityTestRule = new ChromeTabbedActivityTestRule();

    @Test
    @MediumTest
    public void nativeWriteFailuresPreserveDocumentAndAllowRecapture() throws Exception {
        mActivityTestRule.startMainActivityOnBlankPage();
        BraveActivity activity = (BraveActivity) (Object) mActivityTestRule.getActivity();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        CriteriaHelper.pollInstrumentationThread(
                () -> device.hasObject(By.text(activity.getString(R.string.brave_tv_controls))));
        device.pressBack();
        CriteriaHelper.pollUiThread(activity::hasWindowFocus);
        mActivityTestRule.loadUrl(URL);
        Tab tab = ThreadUtils.runOnUiThreadBlocking(activity::getActivityTab);
        WebContents contents = ThreadUtils.runOnUiThreadBlocking(tab::getWebContents);
        assertNotNull(contents);
        int id = ThreadUtils.runOnUiThreadBlocking(tab::getId);
        TabContentManager manager =
                ThreadUtils.runOnUiThreadBlocking(activity::getTabContentManager);
        File jpeg = TabContentManager.getTabThumbnailFileJpeg(id);
        File etc = TabContentManager.getTabThumbnailFileEtc1(tab);
        ThreadUtils.runOnUiThreadBlocking(() -> assertFalse(tab.isIncognito()));
        String document =
                JavaScriptUtils.executeJavaScriptAndWaitForResult(
                        contents, "performance.timeOrigin");
        removeCache(manager, id, jpeg, etc);
        capture(manager, tab);
        assertReadableJpeg(jpeg, id);
        removeCache(manager, id, jpeg, etc);

        boolean jpegDirectory = false;
        boolean etcDirectory = false;
        try {
            // JpegThumbnailHelper::WriteTask uses WriteFile; a directory must fail that write.
            jpegDirectory = jpeg.mkdir();
            assertTrue("Create only the owned JPEG obstruction", jpegDirectory);
            etcDirectory = etc.mkdir();
            assertTrue("Create only the owned ETC1 obstruction", etcDirectory);
            capture(manager, tab);
            assertTrue(jpeg.isDirectory());
            assertTrue(etc.isDirectory());
            ThreadUtils.runOnUiThreadBlocking(
                    () -> {
                        assertSame(tab, activity.getActivityTab());
                        assertSame(contents, tab.getWebContents());
                        assertFalse(tab.isIncognito());
                        assertEquals(new GURL(URL), tab.getUrl());
                    });
            assertTrue(device.hasObject(By.text("TV thumbnail write fixture")));
            assertEquals(
                    document,
                    JavaScriptUtils.executeJavaScriptAndWaitForResult(
                            contents, "performance.timeOrigin"));
        } finally {
            try {
                if (etcDirectory)
                    assertTrue("Remove only the empty ETC1 obstruction", etc.delete());
            } finally {
                if (jpegDirectory)
                    assertTrue("Remove only the empty JPEG obstruction", jpeg.delete());
            }
        }

        removeCache(manager, id, jpeg, etc);
        capture(manager, tab);
        assertReadableJpeg(jpeg, id);
        mActivityTestRule.loadUrl(URL + "#recovered");
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertSame(tab, activity.getActivityTab());
                    assertSame(contents, tab.getWebContents());
                    assertEquals(new GURL(URL + "#recovered"), tab.getUrl());
                });
    }

    private static void removeCache(TabContentManager manager, int id, File jpeg, File etc) {
        ThreadUtils.runOnUiThreadBlocking(() -> manager.removeTabThumbnail(id));
        CriteriaHelper.pollUiThread(() -> !manager.isTabCaptureInFlightForTesting(id));
        CriteriaHelper.pollInstrumentationThread(() -> !jpeg.exists() && !etc.exists());
    }

    private static void capture(TabContentManager manager, Tab tab) throws Exception {
        int id = ThreadUtils.runOnUiThreadBlocking(tab::getId);
        CallbackHelper captured = new CallbackHelper();
        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        manager.cacheTabThumbnailWithCallback(
                                tab,
                                true,
                                bitmap -> {
                                    assertNotNull(
                                            "Actual normal renderer readback required", bitmap);
                                    if (bitmap != null) bitmap.recycle();
                                    captured.notifyCalled();
                                }));
        captured.waitForCallback(0);
        CriteriaHelper.pollUiThread(() -> !manager.isTabCaptureInFlightForTesting(id));
    }

    private static void assertReadableJpeg(File jpeg, int id) {
        assertTrue(jpeg.isFile());
        Bitmap bitmap = TabContentManager.getJpegForTab(id, new Size(0, 0));
        assertNotNull("Native normal JPEG must decode", bitmap);
        if (bitmap != null) {
            try {
                assertTrue(bitmap.getWidth() > 0 && bitmap.getHeight() > 0);
            } finally {
                bitmap.recycle();
            }
        }
    }
}
