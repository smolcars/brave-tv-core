// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.chromium.base.ThreadUtils;
import org.chromium.base.test.util.CallbackHelper;
import org.chromium.base.test.util.CommandLineFlags;
import org.chromium.base.test.util.CriteriaHelper;
import org.chromium.base.test.util.DoNotBatch;
import org.chromium.base.test.util.Features.DisableFeatures;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.bookmarks.BookmarkModel;
import org.chromium.chrome.browser.flags.ChromeFeatureList;
import org.chromium.chrome.browser.flags.ChromeSwitches;
import org.chromium.chrome.test.ChromeJUnit4ClassRunner;
import org.chromium.chrome.test.ChromeTabbedActivityTestRule;
import org.chromium.components.bookmarks.BookmarkId;
import org.chromium.url.GURL;

import java.util.ArrayList;
import java.util.List;

/** Native saved-page activation, model-driven focus recovery and private Home isolation. */
@RunWith(ChromeJUnit4ClassRunner.class)
@CommandLineFlags.Add({ChromeSwitches.DISABLE_FIRST_RUN_EXPERIENCE})
@DisableFeatures(ChromeFeatureList.ANDROID_OPEN_INCOGNITO_AS_WINDOW)
@DoNotBatch(reason = "Uses real normal bookmarks and creates a private Home.")
public class TvHomePageTest {
    private static final String TITLE = "TV saved fixture ";
    private static final String URL = "data:text/html,<h1>TV%20saved%20page</h1>";

    @Rule
    public ChromeTabbedActivityTestRule mActivityTestRule = new ChromeTabbedActivityTestRule();

    private final List<BookmarkId> mOwnedBookmarks = new ArrayList<>();
    private BraveActivity mActivity;
    private BookmarkModel mBookmarks;
    private UiDevice mDevice;

    @Before
    public void setUp() throws Exception {
        mActivityTestRule.startMainActivityOnBlankPage();
        mActivity = (BraveActivity) (Object) mActivityTestRule.getActivity();
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mBookmarks =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> BookmarkModel.getForProfile(mActivity.getActivityTab().getProfile()));
        CallbackHelper loaded = new CallbackHelper();
        ThreadUtils.runOnUiThreadBlocking(
                () -> mBookmarks.finishLoadingBookmarkModel(loaded::notifyCalled));
        loaded.waitForCallback(0);
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    BookmarkId folder = mBookmarks.getDefaultBookmarkFolder();
                    assertNotNull(folder);
                    for (int i = 0; i < 7; i++) {
                        BookmarkId id =
                                mBookmarks.addBookmark(
                                        folder, i, TITLE + i, new GURL(URL + "#" + i));
                        assertNotNull(id);
                        mOwnedBookmarks.add(id);
                    }
                });
        mActivityTestRule.loadUrl("chrome-native://newtab/");
        CriteriaHelper.pollUiThread(
                () -> mActivity.getActivityTab().getNativePage() instanceof TvHomePage);
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.descStartsWith(TITLE + "0\n")));
    }

    @After
    public void tearDown() {
        if (mBookmarks == null) return;
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    for (BookmarkId id : mOwnedBookmarks) {
                        if (mBookmarks.doesBookmarkExist(id)) mBookmarks.deleteBookmark(id);
                    }
                });
    }

    @Test
    @MediumTest
    public void homePreviewsSixSavedPages() {
        assertFalse(mDevice.hasObject(By.descStartsWith(TITLE + "6\n")));
        UiObject2 scroll = mDevice.findObject(By.scrollable(true));
        assertNotNull(scroll);
        scroll.scroll(Direction.DOWN, 0.8f);
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.descStartsWith(TITLE + "5\n")));
        assertFalse(mDevice.hasObject(By.descStartsWith(TITLE + "6\n")));
    }

    @Test
    @MediumTest
    public void savedPageOpensThroughNativeHome() {
        UiObject2 saved = mDevice.findObject(By.descStartsWith(TITLE + "0\n"));
        assertNotNull(saved);
        saved.click();
        CriteriaHelper.pollUiThread(
                () -> mActivity.getActivityTab().getUrl().equals(new GURL(URL + "#0")));
        CriteriaHelper.pollInstrumentationThread(() -> mDevice.hasObject(By.text("TV saved page")));
    }

    @Test
    @MediumTest
    public void removingFocusedFavoriteRestoresAddress() {
        CriteriaHelper.pollInstrumentationThread(
                () -> {
                    UiObject2 address =
                            mDevice.findObject(
                                    By.desc(mActivity.getString(R.string.brave_tv_address)));
                    return address != null && address.isFocused();
                });
        mDevice.pressDPadDown();
        mDevice.pressDPadDown();
        CriteriaHelper.pollInstrumentationThread(
                () -> {
                    UiObject2 saved = mDevice.findObject(By.descStartsWith(TITLE + "0\n"));
                    return saved != null && saved.isFocused();
                });
        ThreadUtils.runOnUiThreadBlocking(() -> mBookmarks.deleteBookmark(mOwnedBookmarks.get(0)));
        CriteriaHelper.pollInstrumentationThread(
                () -> {
                    UiObject2 address =
                            mDevice.findObject(
                                    By.desc(mActivity.getString(R.string.brave_tv_address)));
                    return address != null && address.isFocused();
                });
        assertFalse(mDevice.hasObject(By.descStartsWith(TITLE + "0\n")));
    }

    @Test
    @MediumTest
    public void recentPageSelectsExistingTab() {
        mActivityTestRule.loadUrl(
                "data:text/html,<title>TV%20recent%20fixture</title><h1>Recent%20page</h1>");
        int recentId = ThreadUtils.runOnUiThreadBlocking(() -> mActivity.getActivityTab().getId());
        ThreadUtils.runOnUiThreadBlocking(
                () -> mActivity.onMenuOrKeyboardAction(R.id.new_tab_menu_id, false));
        CriteriaHelper.pollUiThread(
                () -> mActivity.getActivityTab().getNativePage() instanceof TvHomePage);
        int count =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> mActivity.getTabModelSelector().getModel(false).getCount());
        UiObject2 scroll = mDevice.findObject(By.scrollable(true));
        assertNotNull(scroll);
        scroll.scroll(Direction.DOWN, 0.8f);
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.descStartsWith("TV recent fixture\n")));
        UiObject2 recent = mDevice.findObject(By.descStartsWith("TV recent fixture\n"));
        assertNotNull(recent);
        recent.click();
        CriteriaHelper.pollUiThread(() -> mActivity.getActivityTab().getId() == recentId);
        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        assertEquals(
                                count, mActivity.getTabModelSelector().getModel(false).getCount()));
    }

    @Test
    @MediumTest
    public void privateHomeOmitsNormalSavedAndRecentSections() {
        ThreadUtils.runOnUiThreadBlocking(
                () -> mActivity.onMenuOrKeyboardAction(R.id.new_incognito_tab_menu_id, false));
        CriteriaHelper.pollUiThread(
                () ->
                        mActivity.getActivityTab().isIncognito()
                                && mActivity.getActivityTab().getNativePage()
                                        instanceof TvHomePage);
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(
                                By.text(mActivity.getString(R.string.brave_tv_private_home))));
        assertFalse(mDevice.hasObject(By.descStartsWith(TITLE)));
        assertFalse(
                mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_home_favorites))));
        assertFalse(
                mDevice.hasObject(
                        By.text(mActivity.getString(R.string.brave_tv_home_recent_tabs))));
        assertTrue(mDevice.hasObject(By.desc(mActivity.getString(R.string.brave_tv_address))));
    }
}
