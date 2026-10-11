// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Build;

import androidx.core.view.OneShotPreDrawListener;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
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
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tabmodel.TabClosureParams;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tv.presentation.TvPresentation;
import org.chromium.chrome.browser.ui.messages.snackbar.SnackbarManager;
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
        // TV startup opens controls for about:blank. Home must own the test window.
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_controls))));
        mDevice.pressBack();
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
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
                    for (int i = 0; i <= TvPresentation.MAX_HOME_FAVORITES; i++) {
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
        assertFalse(
                mDevice.hasObject(
                        By.descStartsWith(TITLE + TvPresentation.MAX_HOME_FAVORITES + "\n")));
        UiObject2 scroll = mDevice.findObject(By.scrollable(true));
        assertNotNull(scroll);
        scroll.scroll(Direction.DOWN, 0.8f);
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(
                                By.descStartsWith(
                                        TITLE + (TvPresentation.MAX_HOME_FAVORITES - 1) + "\n")));
        assertFalse(
                mDevice.hasObject(
                        By.descStartsWith(TITLE + TvPresentation.MAX_HOME_FAVORITES + "\n")));
    }

    @Test
    @MediumTest
    public void savedPageOpensThroughNativeHome() {
        focusFirstFavorite();
        mDevice.pressDPadCenter();
        CriteriaHelper.pollUiThread(
                () -> mActivity.getActivityTab().getUrl().equals(new GURL(URL + "#0")));
        CriteriaHelper.pollInstrumentationThread(() -> mDevice.hasObject(By.text("TV saved page")));
    }

    @Test
    @MediumTest
    public void removingFocusedFavoriteRestoresAddress() {
        focusFirstFavorite();
        ThreadUtils.runOnUiThreadBlocking(() -> mBookmarks.deleteBookmark(mOwnedBookmarks.get(0)));
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
        CriteriaHelper.pollInstrumentationThread(
                () -> !mDevice.hasObject(By.descStartsWith(TITLE + "0\n")));
    }

    private void focusFirstFavorite() {
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
        mDevice.pressDPadDown();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_controls)));
        mDevice.pressDPadDown();
        waitForFocus(By.descStartsWith(TITLE + "0\n"));
    }

    private void waitForFocus(BySelector target) {
        CriteriaHelper.pollInstrumentationThread(
                () -> {
                    UiObject2 control = mDevice.findObject(target);
                    return control != null && control.isFocused();
                });
    }

    private void waitForSourceFocus() {
        BySelector source = By.desc(mActivity.getString(R.string.brave_tv_source));
        try {
            waitForFocus(source);
        } catch (CriteriaHelper.TimeoutException failure) {
            // [DEBUG-home-footer] Fixed-label booleans only; runs after the test has failed.
            boolean present = mDevice.hasObject(source);
            boolean focusedBefore = mDevice.hasObject(source.focused(true));
            boolean addressFocused =
                    mDevice.hasObject(
                            By.desc(mActivity.getString(R.string.brave_tv_address)).focused(true));
            boolean favoriteFocused =
                    mDevice.hasObject(By.descStartsWith(TITLE + "3\n").focused(true));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                InstrumentationRegistry.getInstrumentation().getUiAutomation().clearCache();
            }
            boolean focusedAfter =
                    mDevice.hasObject(
                            By.desc(mActivity.getString(R.string.brave_tv_source)).focused(true));
            throw new AssertionError(
                    "[DEBUG-home-footer] present="
                            + present
                            + " focusedBefore="
                            + focusedBefore
                            + " focusedAfter="
                            + focusedAfter
                            + " addressFocused="
                            + addressFocused
                            + " favoriteFocused="
                            + favoriteFocused,
                    failure);
        }
    }

    @Test
    @MediumTest
    public void homeSettingsBackRestoresHomeInvoker() {
        TvHomePage home =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> (TvHomePage) mActivity.getActivityTab().getNativePage());
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
        mDevice.pressDPadDown();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_controls)));
        mDevice.pressDPadRight();
        mDevice.pressDPadRight();
        mDevice.pressDPadRight();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_settings)));
        mDevice.pressDPadCenter();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_search_engine)));
        mDevice.pressBack();
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_settings)));
        ThreadUtils.runOnUiThreadBlocking(
                () -> assertTrue(mActivity.getActivityTab().getNativePage() == home));
    }

    @Test
    @MediumTest
    public void recentPageSelectsExistingTab() {
        Tab recentTab = openHomeWithRecentPage();
        int recentId = ThreadUtils.runOnUiThreadBlocking(recentTab::getId);
        int count =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> mActivity.getTabModelSelector().getModel(false).getCount());
        waitForFocus(By.descStartsWith("TV recent fixture\n"));
        mDevice.pressDPadCenter();
        CriteriaHelper.pollUiThread(() -> mActivity.getActivityTab().getId() == recentId);
        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        assertEquals(
                                count, mActivity.getTabModelSelector().getModel(false).getCount()));
    }

    private Tab openHomeWithRecentPage() {
        mActivityTestRule.loadUrl(
                "data:text/html,<title>TV%20recent%20fixture</title><h1>Recent%20page</h1>");
        Tab recent = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        ThreadUtils.runOnUiThreadBlocking(
                () -> mActivity.onMenuOrKeyboardAction(R.id.new_tab_menu_id, false));
        CriteriaHelper.pollUiThread(
                () -> mActivity.getActivityTab().getNativePage() instanceof TvHomePage);
        focusFirstFavorite();
        mDevice.pressDPadDown();
        mDevice.pressDPadDown();
        waitForFocus(By.descStartsWith("TV recent fixture\n"));
        return recent;
    }

    @Test
    @MediumTest
    public void closingAndUndoingUnselectedRecentTabRefreshesSameHome() throws Exception {
        // UndoIntegrationTest also scopes the native timer with this resettable hook.
        SnackbarManager.setDurationForTesting(30_000);
        Tab recent = openHomeWithRecentPage();
        TvHomePage home =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> (TvHomePage) mActivity.getActivityTab().getNativePage());
        TabModel model =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> mActivity.getTabModelSelector().getModel(false));
        mDevice.waitForIdle();
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertTrue(model.supportsPendingClosures());
                    model.getTabRemover()
                            .closeTabs(
                                    TabClosureParams.closeTab(recent).allowUndo(true).build(),
                                    false);
                    assertTrue(model.isClosurePending(recent.getId()));
                    assertTrue(mActivity.getActivityTab().getNativePage() == home);
                });
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
        focusFirstFavorite();
        mDevice.pressDPadDown();
        waitForFocus(By.descStartsWith(TITLE + "3\n"));
        mDevice.pressDPadDown();
        waitForSourceFocus();
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(
                                By.text(
                                        mActivity.getString(
                                                R.string.brave_tv_home_empty_recent_tabs))));
        assertFalse(mDevice.hasObject(By.descStartsWith("TV recent fixture\n")));
        CallbackHelper restoredLayout = new CallbackHelper();
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertTrue(model.isClosurePending(recent.getId()));
                    model.cancelTabClosure(recent.getId());
                    assertFalse(model.isClosurePending(recent.getId()));
                    assertTrue(mActivity.getActivityTab().getNativePage() == home);
                    OneShotPreDrawListener.add(home.getView(), restoredLayout::notifyCalled);
                });
        // The restored card is above the focused footer and may be off screen.
        restoredLayout.waitForCallback(0);
        mDevice.pressDPadUp();
        waitForFocus(By.descStartsWith("TV recent fixture\n"));
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
