// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageInfo;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;

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

import org.chromium.base.Callback;
import org.chromium.base.PackageUtils;
import org.chromium.base.ThreadUtils;
import org.chromium.base.Token;
import org.chromium.base.test.util.ApplicationTestUtils;
import org.chromium.base.test.util.CallbackHelper;
import org.chromium.base.test.util.CommandLineFlags;
import org.chromium.base.test.util.Criteria;
import org.chromium.base.test.util.CriteriaHelper;
import org.chromium.base.test.util.DoNotBatch;
import org.chromium.base.test.util.Features.DisableFeatures;
import org.chromium.base.test.util.MinAndroidSdkLevel;
import org.chromium.cc.input.BrowserControlsState;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.bookmarks.BookmarkModel;
import org.chromium.chrome.browser.flags.ChromeFeatureList;
import org.chromium.chrome.browser.flags.ChromeSwitches;
import org.chromium.chrome.browser.layouts.LayoutType;
import org.chromium.chrome.browser.preferences.Pref;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabBrowserControlsConstraintsHelper;
import org.chromium.chrome.browser.tab.TvBrowserControlsVisibilityDelegate;
import org.chromium.chrome.browser.tabmodel.TabClosureParams;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tv.presentation.TvPresentation;
import org.chromium.chrome.browser.ui.messages.snackbar.SnackbarManager;
import org.chromium.chrome.test.ChromeJUnit4ClassRunner;
import org.chromium.chrome.test.ChromeTabbedActivityTestRule;
import org.chromium.components.bookmarks.BookmarkId;
import org.chromium.components.bookmarks.BookmarkItem;
import org.chromium.components.user_prefs.UserPrefs;
import org.chromium.content_public.browser.test.util.JavaScriptUtils;
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
    private Token mClosingGroupId;

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
    public void televisionToolbarUsesNativeHiddenOffsets() {
        assertTelevisionViewport();
        mActivityTestRule.loadUrl(URL);
        CriteriaHelper.pollInstrumentationThread(() -> mDevice.hasObject(By.text("TV saved page")));
        assertTelevisionViewport();
        mDevice.pressBack();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
        mDevice.pressBack();
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        assertTelevisionViewport();
    }

    private void assertTelevisionViewport() {
        CriteriaHelper.pollUiThread(
                () ->
                        TabBrowserControlsConstraintsHelper.getConstraints(
                                        mActivity.getActivityTab())
                                == BrowserControlsState.HIDDEN);
        CriteriaHelper.pollUiThread(
                () -> mActivity.getBrowserControlsManager().getContentOffset() == 0);
        CriteriaHelper.pollUiThread(
                () -> {
                    View toolbar = mActivity.findViewById(R.id.control_container);
                    return !toolbar.isShown() || !toolbar.getGlobalVisibleRect(new Rect());
                });
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertTrue(mActivity.isTelevision());
                    assertFalse(mActivity.getFullscreenManager().getPersistentFullscreenMode());
                    if (mActivity.getActivityTab().getNativePage() instanceof TvHomePage home) {
                        Rect bounds = new Rect();
                        assertTrue(home.getView().getGlobalVisibleRect(bounds));
                        assertEquals(0, bounds.top);
                        assertEquals(
                                mActivity.getWindow().getDecorView().getHeight(), bounds.bottom);
                    }
                });
    }

    @Test
    @MediumTest
    public void closingFinalNormalTabCreatesUsableHome() {
        Tab closing = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertEquals(1, mActivity.getTabModelSelector().getModel(false).getCount());
                    assertFalse(closing.isIncognito());
                    mActivity.onMenuOrKeyboardAction(R.id.close_tab, false);
                });
        CriteriaHelper.pollUiThread(
                () ->
                        mActivity.getActivityTab() != null
                                && mActivity.getActivityTab() != closing
                                && !mActivity.getActivityTab().isIncognito()
                                && mActivity.getActivityTab().getNativePage() instanceof TvHomePage
                                && mActivity.getTabModelSelector().getModel(false).getCount() == 1);
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
        assertTrue(mDevice.hasObject(By.descStartsWith(TITLE + "0\n")));
    }

    @Test
    @MediumTest
    public void cancelFinalGroupedTabPreservesOriginalPageAndGroup() {
        Tab original = openFinalGroupedTabConfirmation();
        mDevice.pressDPadCenter();
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    TabModel model = mActivity.getTabModelSelector().getModel(false);
                    assertTrue(mActivity.getActivityTab() == original);
                    assertEquals(1, model.getCount());
                    assertEquals(1, model.getTabGroupCount());
                    assertNotNull(original.getTabGroupId());
                    assertEquals(mClosingGroupId, original.getTabGroupId());
                    assertEquals(new GURL(URL), original.getUrl());
                });
        CriteriaHelper.pollInstrumentationThread(() -> mDevice.hasObject(By.text("TV saved page")));
        ThreadUtils.runOnUiThreadBlocking(
                () -> mActivity.onMenuOrKeyboardAction(R.id.close_tab, false));
        waitForFocus(By.text(mActivity.getString(R.string.cancel)));
        assertTrue(
                mDevice.hasObject(
                        By.text(mActivity.getString(R.string.close_from_group_dialog_title))));
        mDevice.pressDPadCenter();
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertTrue(mActivity.getActivityTab() == original);
                    assertEquals(mClosingGroupId, original.getTabGroupId());
                });
    }

    @Test
    @MediumTest
    public void deleteFinalGroupedTabCreatesUsableHome() {
        Tab original = openFinalGroupedTabConfirmation();
        mDevice.pressDPadRight();
        waitForFocus(By.text(mActivity.getString(R.string.delete_tab_group_action)));
        mDevice.pressDPadCenter();
        CriteriaHelper.pollUiThread(
                () -> {
                    TabModel model = mActivity.getTabModelSelector().getModel(false);
                    return model.getCount() == 1
                            && model.getTabGroupCount() == 0
                            && model.getTabById(original.getId()) == null
                            && mActivity.getActivityTab() != null
                            && mActivity.getActivityTab().getNativePage() instanceof TvHomePage;
                });
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
        assertTrue(mDevice.hasObject(By.descStartsWith(TITLE + "0\n")));
    }

    private Tab openFinalGroupedTabConfirmation() {
        mActivityTestRule.loadUrl(URL);
        CriteriaHelper.pollInstrumentationThread(() -> mDevice.hasObject(By.text("TV saved page")));
        Tab original = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    TabModel model = mActivity.getTabModelSelector().getModel(false);
                    assertEquals(1, model.getCount());
                    model.createSingleTabGroup(original);
                    mClosingGroupId = original.getTabGroupId();
                    assertNotNull(mClosingGroupId);
                    assertEquals(1, model.getTabGroupCount());
                    mActivity.onMenuOrKeyboardAction(R.id.close_tab, false);
                });
        waitForFocus(By.text(mActivity.getString(R.string.cancel)));
        assertTrue(
                mDevice.hasObject(
                        By.text(mActivity.getString(R.string.close_from_group_dialog_title))));
        mDevice.pressDPadRight();
        waitForFocus(By.text(mActivity.getString(R.string.delete_tab_group_action)));
        mDevice.pressDPadLeft();
        waitForFocus(By.text(mActivity.getString(R.string.cancel)));
        return original;
    }

    @Test
    @MediumTest
    public void televisionTabAnimationPolicySurvivesAccessibilityChanges() {
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    var layout = mActivityTestRule.getActivity().getLayoutManager();
                    assertFalse(layout.animationsEnabled());
                    layout.onAccessibilityModeChanged(false);
                    assertFalse(layout.animationsEnabled());
                    layout.onAccessibilityModeChanged(true);
                    assertFalse(layout.animationsEnabled());
                });
    }

    @Test
    @MediumTest
    @DisableFeatures(ChromeFeatureList.INCOGNITO_SCREENSHOT)
    public void closingFinalPrivateTabRetainsOriginalNormalHome() {
        Tab normal = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertFalse(
                            mActivityTestRule.getActivity().getLayoutManager().animationsEnabled());
                    mActivity.onMenuOrKeyboardAction(R.id.new_incognito_tab_menu_id, false);
                });
        CriteriaHelper.pollUiThread(
                () ->
                        mActivity.getActivityTab().isIncognito()
                                && mActivity.getActivityTab().getNativePage() instanceof TvHomePage
                                && (mActivity.getWindow().getAttributes().flags
                                                & WindowManager.LayoutParams.FLAG_SECURE)
                                        != 0);
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertEquals(1, mActivity.getTabModelSelector().getModel(true).getCount());
                    mActivity.onMenuOrKeyboardAction(R.id.close_tab, false);
                });
        CriteriaHelper.pollUiThread(
                () ->
                        mActivity.getActivityTab() == normal
                                && mActivity.getTabModelSelector().getModel(true).getCount() == 0
                                && mActivity.getTabModelSelector().getModel(false).getCount() == 1
                                && (mActivity.getWindow().getAttributes().flags
                                                & WindowManager.LayoutParams.FLAG_SECURE)
                                        == 0);
        CriteriaHelper.pollUiThread(
                () -> {
                    Criteria.checkThat(
                            "Normal activity tab must remain selected during layout return; layout="
                                    + mActivityTestRule
                                            .getActivity()
                                            .getLayoutManager()
                                            .getActiveLayoutType()
                                    + "; private model selected="
                                    + mActivity.getTabModelSelector().isIncognitoSelected()
                                    + "; model retains normal="
                                    + (mActivity.getTabModelSelector().getCurrentTab() == normal),
                            mActivity.getActivityTab() == normal,
                            is(true));
                    Criteria.checkThat(
                            "Final private closure must return to browsing, not cover normal Home",
                            mActivityTestRule
                                    .getActivity()
                                    .getLayoutManager()
                                    .getActiveLayoutType(),
                            is(LayoutType.BROWSING));
                    Criteria.checkThat(
                            mActivity.getActivityTab().getNativePage() instanceof TvHomePage,
                            is(true));
                    Criteria.checkThat(
                            mActivity.getActivityTab().getNativePage().getView().isShown(),
                            is(true));
                });
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
        assertTrue(mDevice.hasObject(By.descStartsWith(TITLE + "0\n")));
        assertFalse(
                mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_private_home))));
    }

    @Test
    @MediumTest
    public void compactControlsActivatePageAfterToolbarSettles() {
        mActivityTestRule.loadUrl(URL);
        Tab page = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        ThreadUtils.runOnUiThreadBlocking(mActivity::showTvBrowserControls);
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
        CriteriaHelper.pollUiThread(
                () ->
                        TabBrowserControlsConstraintsHelper.getConstraints(page)
                                        == BrowserControlsState.HIDDEN
                                && mActivity.getBrowserControlsManager().getContentOffset() == 0);
        mDevice.pressDPadRight();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_pointer)));
        mDevice.pressDPadCenter();
        CriteriaHelper.pollInstrumentationThread(
                () -> !mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_controls))));
        CriteriaHelper.pollUiThread(() -> page.getContentView().hasFocus());
        ThreadUtils.runOnUiThreadBlocking(() -> assertTrue(mActivity.getActivityTab() == page));
    }

    @Test
    @MediumTest
    public void nativeAppShowingRequestOverridesCompactTvControls() {
        assertTelevisionViewport();
        int token =
                ThreadUtils.runOnUiThreadBlocking(
                        () ->
                                mActivity
                                        .getBrowserControlsManager()
                                        .getBrowserVisibilityDelegate()
                                        .showControlsPersistent());
        try {
            CriteriaHelper.pollUiThread(
                    () ->
                            TabBrowserControlsConstraintsHelper.getConstraints(
                                            mActivity.getActivityTab())
                                    == BrowserControlsState.SHOWN);
        } finally {
            ThreadUtils.runOnUiThreadBlocking(
                    () ->
                            mActivity
                                    .getBrowserControlsManager()
                                    .getBrowserVisibilityDelegate()
                                    .releasePersistentShowingToken(token));
        }
        assertTelevisionViewport();
    }

    @Test
    @MediumTest
    public void nativeEditableVisibilityRecoversAfterNavigation() {
        mActivityTestRule.loadUrl("data:text/html,<input autofocus aria-label='TV native editor'>");
        CriteriaHelper.pollUiThread(
                () ->
                        TabBrowserControlsConstraintsHelper.getConstraints(
                                        mActivity.getActivityTab())
                                == BrowserControlsState.SHOWN);
        mActivityTestRule.loadUrl(URL);
        CriteriaHelper.pollInstrumentationThread(() -> mDevice.hasObject(By.text("TV saved page")));
        assertTelevisionViewport();
    }

    @Test
    @MediumTest
    public void unobservedTvDelegateReleasesNativeActivityObserver() {
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    var app = mActivity.getBrowserControlsManager().getBrowserVisibilityDelegate();
                    int existing = app.getObserverCount();
                    var delegate =
                            new TvBrowserControlsVisibilityDelegate(
                                    mActivity.getActivityTab(), app);
                    assertEquals(existing, app.getObserverCount());
                    Callback<Integer> observer = ignored -> {};
                    delegate.addSyncObserver(observer);
                    assertEquals(existing + 1, app.getObserverCount());
                    delegate.removeObserver(observer);
                    assertEquals(existing, app.getObserverCount());
                    int token = app.showControlsPersistent();
                    try {
                        assertEquals(Integer.valueOf(BrowserControlsState.SHOWN), delegate.get());
                    } finally {
                        app.releasePersistentShowingToken(token);
                    }
                    assertEquals(existing, app.getObserverCount());
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

    @Test
    @MediumTest
    public void bookmarkLibraryPagesAndOpensNativeSavedPage() {
        openOwnedBookmarkFolder();
        String finalTitle = "TV library fixture " + TvBrowserControls.BOOKMARK_PAGE_SIZE;
        assertFalse(mDevice.hasObject(By.desc(finalTitle)));
        focusDown(By.desc(mActivity.getString(R.string.brave_tv_previous_bookmarks)));
        mDevice.pressDPadRight();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_more_bookmarks)));
        mDevice.pressDPadCenter();
        focusDown(By.desc(finalTitle));
        assertFalse(mDevice.hasObject(By.desc("TV library fixture 0")));
        mDevice.pressDPadCenter();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_open_bookmark)));
        mDevice.pressDPadCenter();
        CriteriaHelper.pollUiThread(
                () ->
                        mActivity
                                .getActivityTab()
                                .getUrl()
                                .equals(
                                        new GURL(
                                                URL
                                                        + "#library"
                                                        + TvBrowserControls.BOOKMARK_PAGE_SIZE)));
        CriteriaHelper.pollInstrumentationThread(() -> mDevice.hasObject(By.text("TV saved page")));
    }

    @Test
    @MediumTest
    public void bookmarkRemovalCancelPreservesThenConfirmRemovesOnlySelectedPage() {
        List<BookmarkId> items = openOwnedBookmarkFolder();
        focusDown(By.desc("TV library fixture 0"));
        mDevice.pressDPadCenter();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_open_bookmark)));
        mDevice.pressDPadRight();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_remove_bookmark)));
        mDevice.pressDPadCenter();
        waitForFocus(By.desc(mActivity.getString(R.string.cancel)));
        mDevice.pressDPadCenter();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_open_bookmark)));
        ThreadUtils.runOnUiThreadBlocking(
                () -> assertTrue(mBookmarks.doesBookmarkExist(items.get(0))));
        mDevice.pressDPadRight();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_remove_bookmark)));
        mDevice.pressDPadCenter();
        waitForFocus(By.desc(mActivity.getString(R.string.cancel)));
        mDevice.pressDPadRight();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_remove_bookmark)));
        mDevice.pressDPadCenter();
        CriteriaHelper.pollUiThread(() -> !mBookmarks.doesBookmarkExist(items.get(0)));
        waitForFocus(By.desc("TV library fixture 1"));
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    for (int i = 1; i < items.size(); i++) {
                        assertTrue(mBookmarks.doesBookmarkExist(items.get(i)));
                    }
                    for (int i = 0; i <= TvPresentation.MAX_HOME_FAVORITES; i++) {
                        assertTrue(mBookmarks.doesBookmarkExist(mOwnedBookmarks.get(i)));
                    }
                });
    }

    @Test
    @MediumTest
    public void bookmarkLibraryRestoresNativeSelectionAcrossUpdates() {
        List<BookmarkId> items = openOwnedBookmarkFolder();
        String lastTitle = "TV library fixture " + TvBrowserControls.BOOKMARK_PAGE_SIZE;
        focusDown(By.desc(mActivity.getString(R.string.brave_tv_previous_bookmarks)));
        mDevice.pressDPadRight();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_more_bookmarks)));
        mDevice.pressDPadCenter();
        focusDown(By.desc(lastTitle));
        mDevice.pressDPadCenter();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_open_bookmark)));
        mDevice.pressBack();
        waitForFocus(By.desc(lastTitle));
        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        mBookmarks.deleteBookmark(
                                items.get(TvBrowserControls.BOOKMARK_PAGE_SIZE - 1)));
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(
                                By.desc(mActivity.getString(R.string.brave_tv_previous_bookmarks))
                                        .enabled(false)));
        waitForFocus(By.desc(lastTitle));
        for (int i = 1; i < TvBrowserControls.BOOKMARK_PAGE_SIZE; i++) mDevice.pressDPadUp();
        waitForFocus(By.desc("TV library fixture 0"));
        for (int i = 1; i < TvBrowserControls.BOOKMARK_PAGE_SIZE; i++) mDevice.pressDPadDown();
        waitForFocus(By.desc(lastTitle));
        boolean editing =
                ThreadUtils.runOnUiThreadBlocking(
                        () ->
                                UserPrefs.get(mActivity.getActivityTab().getProfile())
                                        .getBoolean(Pref.EDIT_BOOKMARKS_ENABLED));
        try {
            ThreadUtils.runOnUiThreadBlocking(
                    () ->
                            UserPrefs.get(mActivity.getActivityTab().getProfile())
                                    .setBoolean(Pref.EDIT_BOOKMARKS_ENABLED, false));
            CriteriaHelper.pollUiThread(() -> !mBookmarks.isEditBookmarksEnabled());
            waitForFocus(By.desc(lastTitle));
            mDevice.pressDPadCenter();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_open_bookmark)));
            CriteriaHelper.pollInstrumentationThread(
                    () ->
                            mDevice.hasObject(
                                    By.desc(mActivity.getString(R.string.brave_tv_remove_bookmark))
                                            .enabled(false)));
            mDevice.pressBack();
            waitForFocus(By.desc(lastTitle));
            ThreadUtils.runOnUiThreadBlocking(
                    () ->
                            UserPrefs.get(mActivity.getActivityTab().getProfile())
                                    .setBoolean(Pref.EDIT_BOOKMARKS_ENABLED, true));
            CriteriaHelper.pollUiThread(mBookmarks::isEditBookmarksEnabled);
            ThreadUtils.runOnUiThreadBlocking(
                    () ->
                            mBookmarks.deleteBookmark(
                                    items.get(TvBrowserControls.BOOKMARK_PAGE_SIZE)));
            String survivor = "TV library fixture " + (TvBrowserControls.BOOKMARK_PAGE_SIZE - 2);
            try {
                waitForFocus(By.desc(survivor));
            } catch (CriteriaHelper.TimeoutException failure) {
                List<String> focused = new ArrayList<>();
                for (UiObject2 control :
                        mDevice.findObjects(By.clazz("android.widget.Button").focused(true))) {
                    if (focused.size() == 8) break;
                    String label = control.getContentDescription();
                    boolean known =
                            label != null
                                    && (label.startsWith("TV library fixture ")
                                            || label.equals(
                                                    mActivity.getString(
                                                            R.string.brave_tv_parent_folder))
                                            || label.equals(
                                                    mActivity.getString(R.string.brave_tv_controls))
                                            || label.equals(
                                                    mActivity.getString(
                                                            R.string.brave_tv_previous_bookmarks))
                                            || label.equals(
                                                    mActivity.getString(
                                                            R.string.brave_tv_more_bookmarks))
                                            || label.equals(
                                                    mActivity.getString(
                                                            R.string.brave_tv_open_bookmark))
                                            || label.equals(
                                                    mActivity.getString(
                                                            R.string.brave_tv_save_bookmark)));
                    focused.add(known ? label : "other button");
                }
                String modelState =
                        ThreadUtils.runOnUiThreadBlocking(
                                () -> {
                                    BookmarkId survivorId =
                                            items.get(TvBrowserControls.BOOKMARK_PAGE_SIZE - 2);
                                    BookmarkItem survivorItem =
                                            mBookmarks.getBookmarkById(survivorId);
                                    assertNotNull(survivorItem);
                                    List<BookmarkId> children =
                                            mBookmarks.getChildIds(survivorItem.getParentId());
                                    return "rows="
                                            + children.size()
                                            + ", survivorIndex="
                                            + children.indexOf(survivorId)
                                            + ", removedExists="
                                            + mBookmarks.doesBookmarkExist(
                                                    items.get(
                                                            TvBrowserControls.BOOKMARK_PAGE_SIZE));
                                });
                throw new AssertionError(
                        "Nearest owned bookmark focus failed: " + focused + "; " + modelState,
                        failure);
            }
            String renamed = "TV library fixture renamed";
            ThreadUtils.runOnUiThreadBlocking(
                    () ->
                            mBookmarks.setBookmarkTitle(
                                    items.get(TvBrowserControls.BOOKMARK_PAGE_SIZE - 2), renamed));
            waitForFocus(By.desc(renamed));
            mDevice.pressDPadCenter();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_open_bookmark)));
            mDevice.pressBack();
            waitForFocus(By.desc(renamed));
            mDevice.pressBack();
            waitForFocus(
                    By.desc(
                            mActivity.getString(
                                    R.string.brave_tv_bookmark_folder,
                                    "TV library fixture folder")));
            mActivityTestRule.loadUrl(URL);
            mDevice.pressBack();
            CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
            ThreadUtils.runOnUiThreadBlocking(
                    () -> mBookmarks.setBookmarkTitle(items.get(0), "TV library after dismissal"));
            ThreadUtils.runOnUiThreadBlocking(mActivity::showTvBrowserControls);
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_bookmarks)));
            assertFalse(mDevice.hasObject(By.text("TV library fixture folder")));
            ThreadUtils.runOnUiThreadBlocking(
                    () -> assertEquals(new GURL(URL), mActivity.getActivityTab().getUrl()));
        } finally {
            ThreadUtils.runOnUiThreadBlocking(
                    () ->
                            UserPrefs.get(mActivity.getActivityTab().getProfile())
                                    .setBoolean(Pref.EDIT_BOOKMARKS_ENABLED, editing));
        }
    }

    @Test
    @MediumTest
    public void bookmarkLibraryPreservesFocusedSaveWhenPolicyDisablesIt() {
        String url = InstrumentationRegistry.getArguments().getString("tv_bookmark_fixture_url");
        assertNotNull("Serve the owned bookmark.html fixture and pass its URL", url);
        assertEquals("http://10.0.2.2:18088/bookmark.html", url);
        mActivityTestRule.loadUrl(url);
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.text("TV bookmark workflow fixture")));
        ThreadUtils.runOnUiThreadBlocking(mActivity::showTvBookmarks);
        String saveLabel = mActivity.getString(R.string.brave_tv_save_bookmark);
        BySelector enabledSave = By.desc(saveLabel).enabled(true);
        BySelector disabledSave = By.desc(saveLabel).enabled(false);
        waitForFocus(enabledSave);
        boolean editing =
                ThreadUtils.runOnUiThreadBlocking(
                        () ->
                                UserPrefs.get(mActivity.getActivityTab().getProfile())
                                        .getBoolean(Pref.EDIT_BOOKMARKS_ENABLED));
        try {
            ThreadUtils.runOnUiThreadBlocking(
                    () ->
                            UserPrefs.get(mActivity.getActivityTab().getProfile())
                                    .setBoolean(Pref.EDIT_BOOKMARKS_ENABLED, false));
            waitForFocus(disabledSave);
            mDevice.pressDPadCenter();
            ThreadUtils.runOnUiThreadBlocking(
                    () ->
                            assertTrue(
                                    mBookmarks.getUserBookmarkIdForTab(mActivity.getActivityTab())
                                            == null));
            waitForFocus(disabledSave);
            ThreadUtils.runOnUiThreadBlocking(
                    () ->
                            UserPrefs.get(mActivity.getActivityTab().getProfile())
                                    .setBoolean(Pref.EDIT_BOOKMARKS_ENABLED, true));
            waitForFocus(enabledSave);
            mDevice.pressBack();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_bookmarks)));
        } finally {
            ThreadUtils.runOnUiThreadBlocking(
                    () ->
                            UserPrefs.get(mActivity.getActivityTab().getProfile())
                                    .setBoolean(Pref.EDIT_BOOKMARKS_ENABLED, editing));
        }
    }

    private List<BookmarkId> openOwnedBookmarkFolder() {
        List<BookmarkId> items = new ArrayList<>();
        String parentTitle =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> {
                            BookmarkId parent = mBookmarks.getDefaultBookmarkFolder();
                            BookmarkId folder =
                                    mBookmarks.addFolder(parent, 0, "TV library fixture folder");
                            assertNotNull(folder);
                            mOwnedBookmarks.add(folder);
                            for (int i = 0; i <= TvBrowserControls.BOOKMARK_PAGE_SIZE; i++) {
                                BookmarkId id =
                                        mBookmarks.addBookmark(
                                                folder,
                                                i,
                                                "TV library fixture " + i,
                                                new GURL(URL + "#library" + i));
                                assertNotNull(id);
                                items.add(id);
                                mOwnedBookmarks.add(id);
                            }
                            return mBookmarks.getBookmarkById(parent).getTitle();
                        });
        ThreadUtils.runOnUiThreadBlocking(mActivity::showTvBookmarks);
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_controls)));
        focusDown(By.desc(mActivity.getString(R.string.brave_tv_bookmark_folder, parentTitle)));
        mDevice.pressDPadCenter();
        CriteriaHelper.pollInstrumentationThread(() -> mDevice.hasObject(By.text(parentTitle)));
        CriteriaHelper.pollUiThread(
                () -> mActivity.getBrowserControlsManager().getContentOffset() == 0);
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_parent_folder)));
        focusDown(
                By.desc(
                        mActivity.getString(
                                R.string.brave_tv_bookmark_folder, "TV library fixture folder")));
        mDevice.pressDPadCenter();
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.text("TV library fixture folder")));
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_parent_folder)));
        return items;
    }

    private void focusDown(BySelector target) {
        for (int i = 0; i < 24; i++) {
            UiObject2 control = mDevice.findObject(target);
            if (control != null && control.isFocused()) return;
            mDevice.pressDPadDown();
            mDevice.waitForIdle();
        }
        waitForFocus(target);
    }

    @Test
    @MediumTest
    public void compactPageInfoUsesNativeOriginAndReturnsToSamePage() {
        String url = InstrumentationRegistry.getArguments().getString("tv_site_info_fixture_url");
        assertNotNull("Start the owned HTTP bookmark fixture and pass its URL", url);
        assertEquals("http", new GURL(url).getScheme());
        mActivityTestRule.loadUrl(url);
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.text("TV bookmark workflow fixture")));
        Tab original = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        ThreadUtils.runOnUiThreadBlocking(mActivity::showTvBrowserControls);
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
        mDevice.pressDPadDown();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_reload)));
        mDevice.pressDPadRight();
        mDevice.pressDPadRight();
        mDevice.pressDPadRight();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_more_controls)));
        mDevice.pressDPadCenter();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_history)));
        mDevice.pressDPadRight();
        mDevice.pressDPadRight();
        mDevice.pressDPadRight();
        waitForFocus(By.desc(mActivity.getString(R.string.menu_page_info)));
        mDevice.pressDPadCenter();
        BySelector nativeInfo = By.res(mActivity.getPackageName(), "page_info_url_wrapper");
        CriteriaHelper.pollInstrumentationThread(() -> mDevice.hasObject(nativeInfo));
        assertTrue(mDevice.hasObject(By.textContains(new GURL(url).getHost())));
        assertTrue(
                mDevice.hasObject(By.res(mActivity.getPackageName(), "page_info_connection_row")));
        mDevice.pressBack();
        CriteriaHelper.pollInstrumentationThread(() -> !mDevice.hasObject(nativeInfo));
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertTrue(mActivity.getActivityTab() == original);
                    assertEquals(new GURL(url), original.getUrl());
                });
        mDevice.pressBack();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_more_controls)));
    }

    private void openSettingsFromHome() {
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
        mDevice.pressDPadDown();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_controls)));
        mDevice.pressDPadRight();
        mDevice.pressDPadRight();
        mDevice.pressDPadRight();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_settings)));
        mDevice.pressDPadCenter();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_search_engine)));
    }

    @Test
    @MediumTest
    public void aboutBackRestoresSettingsAndOpensGeneratedLicenses() throws Exception {
        Tab original = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        openSettingsFromHome();
        focusDown(By.desc(mActivity.getString(R.string.brave_tv_about)));
        mDevice.pressDPadCenter();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_settings)));
        PackageInfo info = PackageUtils.getPackageInfo(mActivity.getPackageName(), 0);
        assertNotNull(info);
        assertTrue(
                mDevice.hasObject(
                        By.text(
                                mActivity.getString(
                                        R.string.brave_tv_about_version, info.versionName))));
        assertTrue(mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_attribution))));
        assertTrue(mDevice.hasObject(By.desc(mActivity.getString(R.string.brave_tv_source))));
        mDevice.pressBack();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_about)));
        mDevice.pressBack();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_settings)));
        ThreadUtils.runOnUiThreadBlocking(() -> assertTrue(mActivity.getActivityTab() == original));
        mDevice.pressDPadCenter();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_about)));
        mDevice.pressDPadCenter();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_settings)));
        mDevice.pressDPadUp();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_source)));
        mDevice.pressDPadRight();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_licenses)));
        mDevice.pressDPadCenter();
        CriteriaHelper.pollUiThread(
                () ->
                        original.getUrl().equals(new GURL(TvBrowserControls.LICENSES_URL))
                                && !original.isLoading());
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertTrue(mActivity.getActivityTab() == original);
                    assertFalse(original.isShowingErrorPage());
                });
        assertEquals(
                "true",
                JavaScriptUtils.executeJavaScriptAndWaitForResult(
                        original.getWebContents(),
                        "document.querySelectorAll('a').length > 10 &&"
                                + " /copyright/i.test(document.body.textContent)"));
        mDevice.pressBack();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
    }

    @Test
    @MediumTest
    public void homeSettingsBackRestoresHomeInvoker() {
        TvHomePage home =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> (TvHomePage) mActivity.getActivityTab().getNativePage());
        openSettingsFromHome();
        mDevice.pressBack();
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_settings)));
        ThreadUtils.runOnUiThreadBlocking(
                () -> assertTrue(mActivity.getActivityTab().getNativePage() == home));
    }

    @Test
    @MediumTest
    public void nestedClearDataBackRetainsHomeSettingsInvoker() {
        openSettingsFromHome();
        mDevice.pressDPadDown();
        waitForFocus(By.desc(mActivity.getString(R.string.content_filters_title)));
        mDevice.pressDPadDown();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_clear_data)));
        mDevice.pressDPadCenter();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_settings)));
        mDevice.pressBack();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_clear_data)));
        mDevice.pressBack();
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_settings)));
    }

    @Test
    @MediumTest
    public void settingsEntryRejectsReplacedHome() {
        TvHomePage home =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> (TvHomePage) mActivity.getActivityTab().getNativePage());
        mActivityTestRule.loadUrl(URL);
        CriteriaHelper.pollInstrumentationThread(() -> mDevice.hasObject(By.text("TV saved page")));
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertFalse(mActivity.getActivityTab().getNativePage() == home);
                    mActivity.showTvSettings(home);
                    assertEquals(new GURL(URL), mActivity.getActivityTab().getUrl());
                });
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        assertFalse(
                mDevice.hasObject(By.desc(mActivity.getString(R.string.brave_tv_search_engine))));
    }

    @Test
    @MediumTest
    public void settingsEntryRejectsNormalHomeAfterPrivateSelection() {
        TvHomePage normalHome =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> (TvHomePage) mActivity.getActivityTab().getNativePage());
        ThreadUtils.runOnUiThreadBlocking(
                () -> mActivity.onMenuOrKeyboardAction(R.id.new_incognito_tab_menu_id, false));
        CriteriaHelper.pollUiThread(
                () ->
                        mActivity.getActivityTab().isIncognito()
                                && mActivity.getActivityTab().getNativePage()
                                        instanceof TvHomePage);
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    Tab privateTab = mActivity.getActivityTab();
                    Object privateHome = privateTab.getNativePage();
                    mActivity.showTvSettings(normalHome);
                    assertTrue(mActivity.getTabModelSelector().isIncognitoSelected());
                    assertTrue(mActivity.getActivityTab() == privateTab);
                    assertTrue(privateTab.getNativePage() == privateHome);
                });
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
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
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_source)));
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

    @Test
    @MediumTest
    public void recreatedHomeRestoresNativeTabAndDetachesOldView() {
        int tabId = ThreadUtils.runOnUiThreadBlocking(() -> mActivity.getActivityTab().getId());
        View oldView =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> mActivity.getActivityTab().getNativePage().getView());
        mActivity = ApplicationTestUtils.recreateActivity(mActivity);
        CriteriaHelper.pollUiThread(
                () ->
                        mActivity.getActivityTab() != null
                                && mActivity.getActivityTab().getId() == tabId
                                && mActivity.getActivityTab().getNativePage()
                                        instanceof TvHomePage);
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertFalse(oldView.isAttachedToWindow());
                    View newView = mActivity.getActivityTab().getNativePage().getView();
                    assertTrue(newView != oldView);
                    assertTrue(newView.isAttachedToWindow());
                    assertFalse(mActivity.getActivityTab().isIncognito());
                });
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
        assertTrue(mDevice.hasObject(By.descStartsWith(TITLE + "0\n")));
    }

    @Test
    @MediumTest
    @MinAndroidSdkLevel(Build.VERSION_CODES.Q)
    @DisableFeatures(ChromeFeatureList.INCOGNITO_SCREENSHOT)
    public void privateHomeAndControlsKeepNativeWindowProtection() {
        ThreadUtils.runOnUiThreadBlocking(
                () -> mActivity.onMenuOrKeyboardAction(R.id.new_incognito_tab_menu_id, false));
        CriteriaHelper.pollUiThread(
                () ->
                        mActivity.getActivityTab().isIncognito()
                                && mActivity.getActivityTab().getNativePage() instanceof TvHomePage
                                && (mActivity.getWindow().getAttributes().flags
                                                & WindowManager.LayoutParams.FLAG_SECURE)
                                        != 0);
        ThreadUtils.runOnUiThreadBlocking(mActivity::showTvBrowserControls);
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(
                                By.text(mActivity.getString(R.string.brave_tv_private_controls))));
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    boolean dialogFound = false;
                    for (View root : WindowInspector.getGlobalWindowViews()) {
                        if (root == mActivity.getWindow().getDecorView() || !root.isShown())
                            continue;
                        WindowManager.LayoutParams attributes =
                                (WindowManager.LayoutParams) root.getLayoutParams();
                        assertTrue(
                                (attributes.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                        dialogFound = true;
                    }
                    assertTrue("Actual private dialog window required", dialogFound);
                });
        mDevice.pressBack();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
        ThreadUtils.runOnUiThreadBlocking(() -> mActivity.getTabModelSelector().selectModel(false));
        CriteriaHelper.pollUiThread(
                () ->
                        !mActivity.getActivityTab().isIncognito()
                                && (mActivity.getWindow().getAttributes().flags
                                                & WindowManager.LayoutParams.FLAG_SECURE)
                                        == 0);
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.descStartsWith(TITLE + "0\n")));
        assertFalse(
                mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_private_home))));
    }
}
