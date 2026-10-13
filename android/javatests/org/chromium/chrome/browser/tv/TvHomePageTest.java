// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inspector.WindowInspector;

import androidx.annotation.Nullable;
import androidx.core.view.OneShotPreDrawListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.StaleObjectException;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.chromium.base.ActivityState;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.Callback;
import org.chromium.base.PackageUtils;
import org.chromium.base.ThreadUtils;
import org.chromium.base.Token;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
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
import org.chromium.chrome.browser.browsing_data.BrowsingDataBridge;
import org.chromium.chrome.browser.browsing_data.BrowsingDataBridgeJni;
import org.chromium.chrome.browser.browsing_data.BrowsingDataType;
import org.chromium.chrome.browser.browsing_data.TimePeriod;
import org.chromium.chrome.browser.flags.ChromeFeatureList;
import org.chromium.chrome.browser.flags.ChromeSwitches;
import org.chromium.chrome.browser.layouts.LayoutType;
import org.chromium.chrome.browser.preferences.Pref;
import org.chromium.chrome.browser.preferences.website.BraveShieldsContentSettings;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.tab.SadTab;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabBrowserControlsConstraintsHelper;
import org.chromium.chrome.browser.tab.TabObserver;
import org.chromium.chrome.browser.tab.TvBrowserControlsVisibilityDelegate;
import org.chromium.chrome.browser.tab_ui.TabContentManager;
import org.chromium.chrome.browser.tabmodel.TabClosureParams;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tabmodel.TabModelUtils;
import org.chromium.chrome.browser.tv.presentation.TvPresentation;
import org.chromium.chrome.browser.ui.messages.snackbar.SnackbarManager;
import org.chromium.chrome.test.ChromeJUnit4ClassRunner;
import org.chromium.chrome.test.ChromeTabbedActivityTestRule;
import org.chromium.components.bookmarks.BookmarkId;
import org.chromium.components.security_state.SecurityStateModel;
import org.chromium.components.user_prefs.UserPrefs;
import org.chromium.content.browser.input.ImeAdapterImpl;
import org.chromium.content.browser.input.TvTextInput;
import org.chromium.content_public.browser.ImeAdapter;
import org.chromium.content_public.browser.LoadUrlParams;
import org.chromium.content_public.browser.WebContents;
import org.chromium.content_public.browser.test.util.DOMUtils;
import org.chromium.content_public.browser.test.util.JavaScriptUtils;
import org.chromium.content_public.browser.test.util.WebContentsUtils;
import org.chromium.net.ChromiumNetworkAdapter;
import org.chromium.net.NetworkTrafficAnnotationTag;
import org.chromium.url.GURL;

import java.io.File;
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
    public void homePhoneWithoutExperimentRestoresFocusAndStopsInvitation() {
        String phone = mActivity.getString(R.string.brave_tv_phone);
        ThreadUtils.runOnUiThreadBlocking(
                () -> assertEquals(R.string.brave_tv_phone, mActivity.tvPhoneStatusLabel()));
        CriteriaHelper.pollInstrumentationThread(() -> mDevice.hasObject(By.desc(phone)));
        mDevice.pressDPadUp();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_home)));
        for (int i = 0; i < 3; i++) mDevice.pressDPadRight();
        waitForFocus(By.desc(phone));
        mDevice.pressDPadCenter();
        CriteriaHelper.pollUiThread(
                () -> mActivity.tvPhoneStatusLabel() == R.string.brave_tv_phone_waiting);
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.desc(mActivity.getString(R.string.brave_tv_phone_qr))));
        mDevice.pressBack();
        CriteriaHelper.pollUiThread(
                () -> mActivity.tvPhoneStatusLabel() == R.string.brave_tv_phone);
        waitForFocus(By.desc(phone));
        assertFalse(mDevice.hasObject(By.desc(mActivity.getString(R.string.brave_tv_phone_qr))));
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

    @Test
    @MediumTest
    public void phoneEnterUsesNativeFieldActionsAndMultiline() throws Exception {
        String fixture =
                "<form id='form'><input id='query' type='search'"
                        + " enterkeyhint='search'><button>Search</button></form><input id='other'"
                        + " type='search' enterkeyhint='search'><textarea id='lines'></textarea>"
                        + "<script>window.submitted=0;window.keys=0;window.lineKeys=[];"
                        + "form.onsubmit=e=>{e.preventDefault();submitted++;};"
                        + "query.onkeydown=e=>{if(e.key==='Enter')keys++;};"
                        + "lines.onkeydown=e=>lineKeys.push([e.key,e.isTrusted]);"
                        + "query.value='x'.repeat(2049);</script>";
        mActivityTestRule.loadUrl("data:text/html," + Uri.encode(fixture));
        WebContents contents =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> mActivity.getActivityTab().getWebContents());
        TvTextInput input = ThreadUtils.runOnUiThreadBlocking(() -> new TvTextInput(contents));
        try {
            long initialRevision = ThreadUtils.runOnUiThreadBlocking(input::revision);
            JavaScriptUtils.executeJavaScriptAndWaitForResult(contents, "query.focus();true");
            ThreadUtils.runOnUiThreadBlocking(input::requestStateUpdate);
            CriteriaHelper.pollUiThread(
                    () ->
                            input.ready()
                                    && !input.eligible()
                                    && input.revision() != initialRevision);
            JavaScriptUtils.executeJavaScriptAndWaitForResult(contents, "query.value='ok';true");
            ThreadUtils.runOnUiThreadBlocking(input::requestStateUpdate);
            CriteriaHelper.pollUiThread(
                    () -> input.ready() && input.eligible() && input.text().equals("ok"));
            ThreadUtils.runOnUiThreadBlocking(
                    () -> assertTrue(input.edit(input.revision(), "prepare", "", 0, 0, false)));
            CriteriaHelper.pollUiThread(() -> input.ready() && input.phoneTyping());
            ThreadUtils.runOnUiThreadBlocking(
                    () -> {
                        ImeAdapterImpl adapter = ImeAdapterImpl.fromWebContents(contents);
                        assertNull(adapter.onCreateInputConnection(new EditorInfo()));
                        assertFalse(TvTextInput.allowPhysicalInput(adapter, true));
                        assertEquals("ok", input.text());
                    });
            assertEquals(
                    "\"query\"",
                    JavaScriptUtils.executeJavaScriptAndWaitForResult(
                            contents, "document.activeElement.id"));
            ThreadUtils.runOnUiThreadBlocking(
                    () -> {
                        assertEquals("search", input.action());
                        assertFalse(input.multiline());
                        assertTrue(input.edit(input.revision(), "enter", "", 0, 0, false));
                        assertFalse(input.ready());
                        assertFalse(input.edit(input.revision(), "enter", "", 0, 0, false));
                    });
            CriteriaHelper.pollUiThread(input::ready);
            assertEquals(
                    "[1,1,\"ok\"]",
                    JavaScriptUtils.executeJavaScriptAndWaitForResult(
                            contents, "[submitted,keys,query.value]"));

            long leasedRevision = ThreadUtils.runOnUiThreadBlocking(input::revision);
            JavaScriptUtils.executeJavaScriptAndWaitForResult(contents, "other.focus();true");
            ThreadUtils.runOnUiThreadBlocking(input::requestStateUpdate);
            CriteriaHelper.pollUiThread(
                    () ->
                            input.ready()
                                    && !input.phoneTyping()
                                    && input.revision() != leasedRevision);
            CriteriaHelper.pollUiThread(
                    () -> ImeAdapterImpl.fromWebContents(contents).hasInputConnection());
            ThreadUtils.runOnUiThreadBlocking(
                    () ->
                            assertNotNull(
                                    ImeAdapterImpl.fromWebContents(contents)
                                            .onCreateInputConnection(new EditorInfo())));
            JavaScriptUtils.executeJavaScriptAndWaitForResult(contents, "query.focus();true");
            ThreadUtils.runOnUiThreadBlocking(input::requestStateUpdate);
            CriteriaHelper.pollUiThread(() -> input.ready() && input.text().equals("ok"));

            String[] hints = {"go", "send", "done", "enter", "next", "previous", "search"};
            for (String hint : hints) {
                long revision = ThreadUtils.runOnUiThreadBlocking(input::revision);
                JavaScriptUtils.executeJavaScriptAndWaitForResult(
                        contents, "query.enterKeyHint='" + hint + "';true");
                ThreadUtils.runOnUiThreadBlocking(input::requestStateUpdate);
                CriteriaHelper.pollUiThread(() -> input.ready() && input.revision() != revision);
                ThreadUtils.runOnUiThreadBlocking(
                        () -> {
                            assertEquals(
                                    hint.equals("next") || hint.equals("previous") ? "enter" : hint,
                                    input.action());
                            assertEquals("ok", input.text());
                        });
            }
            JavaScriptUtils.executeJavaScriptAndWaitForResult(contents, "lines.focus();true");
            ThreadUtils.runOnUiThreadBlocking(input::requestStateUpdate);
            CriteriaHelper.pollUiThread(
                    () -> input.ready() && input.eligible() && input.multiline());
            ThreadUtils.runOnUiThreadBlocking(() -> assertFalse(input.phoneTyping()));
            ThreadUtils.runOnUiThreadBlocking(
                    () -> assertTrue(input.edit(input.revision(), "prepare", "", 0, 0, false)));
            CriteriaHelper.pollUiThread(() -> input.ready() && input.phoneTyping());
            ThreadUtils.runOnUiThreadBlocking(
                    () -> {
                        assertEquals("enter", input.action());
                        assertTrue(input.edit(input.revision(), "replace", "one", 3, 3, false));
                    });
            CriteriaHelper.pollUiThread(() -> input.ready() && input.text().equals("one"));
            ThreadUtils.runOnUiThreadBlocking(
                    () -> {
                        if (input.composing())
                            assertFalse(input.edit(input.revision(), "enter", "", 0, 0, false));
                        assertTrue(input.edit(input.revision(), "finish", "", 0, 0, false));
                    });
            CriteriaHelper.pollUiThread(() -> input.ready() && !input.composing());
            ThreadUtils.runOnUiThreadBlocking(
                    () -> assertTrue(input.edit(input.revision(), "select", "", 3, 3, false)));
            CriteriaHelper.pollUiThread(
                    () ->
                            input.ready()
                                    && !input.composing()
                                    && input.start() == 3
                                    && input.end() == 3
                                    && input.text().equals("one"));
            ThreadUtils.runOnUiThreadBlocking(
                    () -> {
                        assertFalse(input.composing());
                        assertEquals(3, input.start());
                        assertEquals(3, input.end());
                        assertTrue(input.edit(input.revision(), "enter", "", 0, 0, false));
                    });
            CriteriaHelper.pollUiThread(() -> input.ready() && input.text().equals("one\n"));
            assertEquals(
                    "\"one\\n\"",
                    JavaScriptUtils.executeJavaScriptAndWaitForResult(contents, "lines.value"));
            ThreadUtils.runOnUiThreadBlocking(
                    () -> {
                        input.releasePhoneTyping();
                        assertFalse(input.phoneTyping());
                        assertTrue(
                                TvTextInput.allowPhysicalInput(
                                        ImeAdapterImpl.fromWebContents(contents), true));
                    });
        } catch (RuntimeException | AssertionError failure) {
            String state =
                    ThreadUtils.runOnUiThreadBlocking(
                            () ->
                                    "eligible="
                                            + input.eligible()
                                            + ",ready="
                                            + input.ready()
                                            + ",composing="
                                            + input.composing()
                                            + ",action="
                                            + input.action()
                                            + ",length="
                                            + input.text().length());
            ThreadUtils.runOnUiThreadBlocking(
                    () -> {
                        assertSame(contents, mActivity.getActivityTab().getWebContents());
                        assertEquals(
                                "data:text/html," + Uri.encode(fixture),
                                mActivity.getActivityTab().getUrl().getSpec());
                    });
            String renderer =
                    JavaScriptUtils.executeJavaScriptAndWaitForResult(
                            contents, "[document.activeElement.id,lines.value,lineKeys]");
            throw new AssertionError("Native Enter fixture: " + state + "; " + renderer, failure);
        } finally {
            ThreadUtils.runOnUiThreadBlocking(input::destroy);
        }
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
    public void firstPageCursorActivationWhileLoadingClicksOnce() throws Exception {
        // Owned slow document and explicit emulator reverse required; see tests/device/README.md.
        String slow = "http://127.0.0.1:18084/slow.html";
        Tab page = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        WebContents contents = ThreadUtils.runOnUiThreadBlocking(page::getWebContents);
        ThreadUtils.runOnUiThreadBlocking(() -> page.loadUrl(new LoadUrlParams(slow)));
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.text("Native loading click")));
        CriteriaHelper.pollUiThread(() -> page.isLoading() && slow.equals(page.getUrl().getSpec()));
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(
                                By.text(mActivity.getString(R.string.brave_tv_page_loading))));
        ThreadUtils.runOnUiThreadBlocking(mActivity::showTvBrowserControls);
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        !mDevice.hasObject(
                                By.text(mActivity.getString(R.string.brave_tv_page_loading))));
        mDevice.pressDPadRight();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_pointer)));
        mDevice.pressDPadCenter();
        CriteriaHelper.pollInstrumentationThread(
                () -> !mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_controls))));
        CriteriaHelper.pollUiThread(
                () -> {
                    assertSame(page, mActivity.getActivityTab());
                    assertSame(contents, page.getWebContents());
                    return page.isLoading()
                            && mActivity.hasWindowFocus()
                            && page.getContentView().hasFocus();
                });
        CriteriaHelper.pollInstrumentationThread(
                () -> fixtureCenterColor(page, slow) == Color.WHITE);
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(
                                By.text(mActivity.getString(R.string.brave_tv_page_loading))));
        assertEquals(
                "0",
                JavaScriptUtils.executeJavaScriptAndWaitForResult(
                        contents, "Number(document.querySelector('#clicks').textContent)"));
        injectDpad(KeyEvent.KEYCODE_DPAD_CENTER);
        CriteriaHelper.pollInstrumentationThread(() -> mDevice.hasObject(By.text("1")));
        assertEquals(
                "true",
                JavaScriptUtils.executeJavaScriptAndWaitForResult(
                        contents,
                        "document.querySelector('#clicks').textContent==='1'"
                                + " && Math.abs(point[0]-point[2]/2)<2"
                                + " && Math.abs(point[1]-point[3]/2)<2"));
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertSame(page, mActivity.getActivityTab());
                    assertSame(contents, page.getWebContents());
                    assertTrue(page.isLoading());
                    page.stopLoading();
                });
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        !mDevice.hasObject(
                                By.text(mActivity.getString(R.string.brave_tv_page_loading))));
        injectDpad(KeyEvent.KEYCODE_BACK);
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_pointer)));
    }

    @Test
    @MediumTest
    public void pageLoadingFollowsSelectedTabAndActivityResume() throws Exception {
        // Controlled pending resource, bounded by the owned fixture's watchdog.
        String url = "http://127.0.0.1:18091/hold.html";
        Tab loading = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        ThreadUtils.runOnUiThreadBlocking(() -> loading.loadUrl(new LoadUrlParams(url)));
        CriteriaHelper.pollUiThread(loading::isLoading);
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(
                                By.text(mActivity.getString(R.string.brave_tv_page_loading))));
        ThreadUtils.runOnUiThreadBlocking(
                () -> mActivity.onMenuOrKeyboardAction(R.id.new_tab_menu_id, false));
        CriteriaHelper.pollUiThread(
                () ->
                        mActivity.getActivityTab() != loading
                                && mActivity.getActivityTab().getNativePage()
                                        instanceof TvHomePage);
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        !mDevice.hasObject(
                                By.text(mActivity.getString(R.string.brave_tv_page_loading))));
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertTrue(loading.isLoading());
                    TabModel model = mActivity.getTabModelSelector().getModel(false);
                    TabModelUtils.setIndex(model, model.indexOf(loading));
                });
        CriteriaHelper.pollUiThread(() -> mActivity.getActivityTab() == loading);
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(
                                By.text(mActivity.getString(R.string.brave_tv_page_loading))));
        mDevice.pressHome();
        CriteriaHelper.pollUiThread(
                () -> {
                    int state = ApplicationStatus.getStateForActivity(mActivity);
                    return state == ActivityState.PAUSED || state == ActivityState.STOPPED;
                });
        mActivityTestRule.resumeMainActivityFromLauncher();
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        if (mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_controls)))) {
            mDevice.pressBack();
            CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        }
        ThreadUtils.runOnUiThreadBlocking(() -> assertTrue(loading.isLoading()));
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(
                                By.text(mActivity.getString(R.string.brave_tv_page_loading))));
        ThreadUtils.runOnUiThreadBlocking(loading::stopLoading);
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        !mDevice.hasObject(
                                By.text(mActivity.getString(R.string.brave_tv_page_loading))));
    }

    @Test
    @MediumTest
    public void pageLoadingIsVisibleAfterAddressSubmissionBeforeCommit() throws Exception {
        Tab tab = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
        mDevice.pressDPadCenter();
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.clazz("android.widget.EditText")));
        mDevice.findObject(By.clazz("android.widget.EditText"))
                .setText("http://127.0.0.1:18091/before.html");
        dismissLoadingTestKeyboard();
        mDevice.pressDPadDown();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_go)));
        mDevice.pressDPadCenter();
        CriteriaHelper.pollUiThread(
                () -> tab.isLoading() && tab.getNativePage() instanceof TvHomePage);
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(
                                By.text(mActivity.getString(R.string.brave_tv_page_loading))));
        releaseLoadingFixture();
        CriteriaHelper.pollUiThread(() -> !tab.isLoading() && !tab.isNativePage());
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(By.text("Resource complete"))
                                && !mDevice.hasObject(
                                        By.text(
                                                mActivity.getString(
                                                        R.string.brave_tv_page_loading))));
    }

    @Test
    @MediumTest
    public void pageLoadingCompletesAndClearsAcrossReplacementAndFailure() throws Exception {
        String base = "http://127.0.0.1:18091";
        Tab tab = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        ThreadUtils.runOnUiThreadBlocking(() -> tab.loadUrl(new LoadUrlParams(base + "/redirect")));
        CriteriaHelper.pollUiThread(
                () -> tab.isLoading() && (base + "/hold.html").equals(tab.getUrl().getSpec()));
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(By.text("Resource pending"))
                                && mDevice.hasObject(
                                        By.text(
                                                mActivity.getString(
                                                        R.string.brave_tv_page_loading))));
        releaseLoadingFixture();
        CriteriaHelper.pollUiThread(() -> !tab.isLoading());
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(By.text("Resource complete"))
                                && !mDevice.hasObject(
                                        By.text(
                                                mActivity.getString(
                                                        R.string.brave_tv_page_loading))));
        ThreadUtils.runOnUiThreadBlocking(
                () -> tab.loadUrl(new LoadUrlParams(base + "/hold.html")));
        CriteriaHelper.pollUiThread(tab::isLoading);
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(
                                By.text(mActivity.getString(R.string.brave_tv_page_loading))));
        mActivityTestRule.loadUrl(base + "/done.html");
        ThreadUtils.runOnUiThreadBlocking(() -> assertFalse(tab.isLoading()));
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        !mDevice.hasObject(
                                By.text(mActivity.getString(R.string.brave_tv_page_loading))));
        mActivityTestRule.loadUrl("http://127.0.0.1:18092/connection-refused");
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertFalse(tab.isLoading());
                    assertTrue(tab.isShowingErrorPage());
                });
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        !mDevice.hasObject(
                                By.text(mActivity.getString(R.string.brave_tv_page_loading))));
    }

    private boolean loadingTestKeyboardVisible() {
        WindowInsetsCompat insets =
                ViewCompat.getRootWindowInsets(mActivity.getWindow().getDecorView());
        return insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
    }

    private void dismissLoadingTestKeyboard() {
        if (!ThreadUtils.runOnUiThreadBlocking(this::loadingTestKeyboardVisible)) return;
        mDevice.pressBack();
        CriteriaHelper.pollUiThread(() -> !loadingTestKeyboardVisible());
    }

    private void releaseLoadingFixture() throws Exception {
        java.net.HttpURLConnection release =
                (java.net.HttpURLConnection)
                        ChromiumNetworkAdapter.openConnection(
                                new java.net.URL("http://127.0.0.1:18091/release"),
                                NetworkTrafficAnnotationTag.TRAFFIC_ANNOTATION_FOR_TESTS);
        release.setConnectTimeout(5000);
        release.setReadTimeout(5000);
        release.setRequestMethod("POST");
        try {
            assertEquals(200, release.getResponseCode());
        } finally {
            release.disconnect();
        }
    }

    @Test
    @MediumTest
    public void pageLoadingYieldsToSameWindowFindAndRecovers() {
        Tab tab = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        ThreadUtils.runOnUiThreadBlocking(
                () -> tab.loadUrl(new LoadUrlParams("http://127.0.0.1:18091/hold.html")));
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(By.text("Resource pending"))
                                && mDevice.hasObject(
                                        By.text(
                                                mActivity.getString(
                                                        R.string.brave_tv_page_loading))));
        ThreadUtils.runOnUiThreadBlocking(
                () -> mActivity.onMenuOrKeyboardAction(R.id.find_in_page_id, false));
        CriteriaHelper.pollUiThread(
                () -> {
                    View find = mActivity.findViewById(R.id.find_toolbar);
                    return find != null && find.isShown();
                });
        CriteriaHelper.pollUiThread(this::loadingTestKeyboardVisible);
        dismissLoadingTestKeyboard();
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    View find = mActivity.findViewById(R.id.find_toolbar);
                    assertNotNull(find);
                    assertTrue(find.isShown());
                    assertTrue(mActivity.hasWindowFocus());
                    assertFalse(loadingTestKeyboardVisible());
                });
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        !mDevice.hasObject(
                                By.text(mActivity.getString(R.string.brave_tv_page_loading))));
        ThreadUtils.runOnUiThreadBlocking(() -> assertTrue(tab.isLoading()));
        mDevice.pressBack();
        CriteriaHelper.pollUiThread(
                () -> {
                    View find = mActivity.findViewById(R.id.find_toolbar);
                    return find == null || !find.isShown();
                });
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(
                                By.text(mActivity.getString(R.string.brave_tv_page_loading))));
        ThreadUtils.runOnUiThreadBlocking(tab::stopLoading);
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        !mDevice.hasObject(
                                By.text(mActivity.getString(R.string.brave_tv_page_loading))));
    }

    @Test
    @MediumTest
    public void dedicatedDpadFromVirtualKeyboardClicksOnce() throws Exception {
        mActivityTestRule.loadUrl(
                "data:text/html,<style>button{position:fixed;inset:0;background:%23202124;"
                        + "color:white;display:flex;align-items:flex-start}</style>"
                        + "<button onclick='this.textContent=\"Pointer clicks \"+"
                        + "(++window.clicks)+\" detail \"+event.detail;"
                        + "window.point=[event.clientX,event.clientY,innerWidth,innerHeight]'>"
                        + "Pointer fixture</button>"
                        + "<script>window.clicks=0</script>");
        Tab page = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        String fixtureUrl = ThreadUtils.runOnUiThreadBlocking(() -> page.getUrl().getSpec());
        CriteriaHelper.pollUiThread(
                () ->
                        !page.isLoading()
                                && TabBrowserControlsConstraintsHelper.getConstraints(page)
                                        == BrowserControlsState.HIDDEN
                                && mActivity.getBrowserControlsManager().getContentOffset() == 0);
        ThreadUtils.runOnUiThreadBlocking(mActivity::showTvBrowserControls);
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
        mDevice.pressDPadRight();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_pointer)));
        mDevice.pressDPadCenter();
        CriteriaHelper.pollInstrumentationThread(
                () -> !mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_controls))));
        CriteriaHelper.pollUiThread(
                () -> {
                    Criteria.checkThat(
                            "Browser activity owns window focus",
                            mActivity.hasWindowFocus(),
                            is(true));
                    Criteria.checkThat(
                            "Native content owns view focus",
                            page.getContentView().hasFocus(),
                            is(true));
                });
        long downTime = SystemClock.uptimeMillis();
        KeyEvent down =
                new KeyEvent(
                        downTime,
                        downTime,
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        0,
                        0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD,
                        0,
                        0,
                        InputDevice.SOURCE_DPAD);
        InputDevice device = down.getDevice();
        assertNotNull(device);
        assertEquals(InputDevice.KEYBOARD_TYPE_ALPHABETIC, device.getKeyboardType());
        dispatchHeldDpad(down);
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.text("Pointer clicks 1 detail 1")));
        assertEquals(
                "1",
                JavaScriptUtils.executeJavaScriptAndWaitForResult(
                        page.getWebContents(), "window.clicks"));

        CriteriaHelper.pollInstrumentationThread(
                () -> fixtureCenterColor(page, fixtureUrl) == Color.WHITE);
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    for (int source :
                            new int[] {InputDevice.SOURCE_KEYBOARD, InputDevice.SOURCE_DPAD}) {
                        KeyEvent keyboard =
                                new KeyEvent(
                                        downTime,
                                        SystemClock.uptimeMillis(),
                                        KeyEvent.ACTION_DOWN,
                                        KeyEvent.KEYCODE_DPAD_RIGHT,
                                        0,
                                        source == InputDevice.SOURCE_DPAD
                                                ? KeyEvent.META_CTRL_ON
                                                : 0,
                                        KeyCharacterMap.VIRTUAL_KEYBOARD,
                                        0,
                                        0,
                                        source);
                        mActivity.dispatchKeyEvent(keyboard);
                        mActivity.dispatchKeyEvent(
                                KeyEvent.changeAction(keyboard, KeyEvent.ACTION_UP));
                    }
                });
        assertEquals(Color.WHITE, fixtureCenterColor(page, fixtureUrl));
        CriteriaHelper.pollInstrumentationThread(
                () -> fixtureCenterColor(page, fixtureUrl) == Color.rgb(32, 33, 36), 5000, 250);
        dispatchHeldDpad(down);
        CriteriaHelper.pollInstrumentationThread(
                () -> fixtureCenterColor(page, fixtureUrl) == Color.WHITE);
        assertEquals(
                "1",
                JavaScriptUtils.executeJavaScriptAndWaitForResult(
                        page.getWebContents(), "window.clicks"));
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    mActivity.dispatchKeyEvent(
                            KeyEvent.changeTimeRepeat(down, SystemClock.uptimeMillis(), 0));
                    mActivity.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP));
                });
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.text("Pointer clicks 2 detail 1")));
        assertEquals(
                "true",
                JavaScriptUtils.executeJavaScriptAndWaitForResult(
                        page.getWebContents(),
                        "window.clicks===2 && Math.abs(point[0]-point[2]/2)<2"
                                + " && Math.abs(point[1]-point[3]/2)<2"));
    }

    @Test
    @MediumTest
    public void compactControlsUseNativeNavigationAndSiteState() throws Exception {
        // Owned HTTP fixture and explicit emulator reverse required; see tests/device/README.md.
        String fixture = "http://127.0.0.1:18084/permission.html";
        String slow = "http://127.0.0.1:18084/slow.html";
        mActivityTestRule.loadUrl(URL);
        Tab page = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        String original = ThreadUtils.runOnUiThreadBlocking(() -> page.getUrl().getSpec());
        mActivityTestRule.loadUrl(fixture);
        boolean protection =
                ThreadUtils.runOnUiThreadBlocking(
                        () ->
                                BraveShieldsContentSettings.getShields(
                                        page.getProfile(),
                                        fixture,
                                        BraveShieldsContentSettings
                                                .RESOURCE_IDENTIFIER_BRAVE_SHIELDS));
        CallbackHelper loads = new CallbackHelper();
        TabObserver observer =
                new TabObserver() {
                    @Override
                    public void onLoadStarted(Tab tab, boolean toDifferentDocument) {
                        loads.notifyCalled();
                    }
                };
        ThreadUtils.runOnUiThreadBlocking(() -> page.addObserver(observer));
        try {
            openControlsWithAddressFocus();
            String connection = mActivity.getString(R.string.accessibility_security_btn_warn);
            String status =
                    mActivity.getString(
                            R.string.brave_tv_browser_state,
                            connection,
                            mActivity.getString(
                                    protection
                                            ? R.string.brave_tv_protection_on
                                            : R.string.brave_tv_protection_off));
            CriteriaHelper.pollInstrumentationThread(() -> mDevice.hasObject(By.text(status)));
            assertFalse(
                    mDevice.findObject(By.desc(mActivity.getString(R.string.brave_tv_forward)))
                            .isEnabled());
            mDevice.pressDPadRight();
            mDevice.pressDPadRight();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_back)));
            mDevice.pressDPadCenter();
            CriteriaHelper.pollUiThread(
                    () ->
                            mActivity.getActivityTab() == page
                                    && !page.isLoading()
                                    && original.equals(page.getUrl().getSpec()));
            openControlsWithAddressFocus();
            mDevice.pressDPadDown();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_forward)));
            mDevice.pressDPadCenter();
            CriteriaHelper.pollUiThread(
                    () ->
                            mActivity.getActivityTab() == page
                                    && !page.isLoading()
                                    && fixture.equals(page.getUrl().getSpec()));
            openControlsWithAddressFocus();
            mDevice.pressDPadDown();
            mDevice.pressDPadRight();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_reload)));
            int beforeReload = loads.getCallCount();
            mDevice.pressDPadCenter();
            loads.waitForCallback(beforeReload);
            CriteriaHelper.pollUiThread(() -> !page.isLoading());

            openControlsWithAddressFocus();
            UiObject2 address =
                    mDevice.findObject(By.desc(mActivity.getString(R.string.brave_tv_address)));
            int beforeProtection = loads.getCallCount();
            ThreadUtils.runOnUiThreadBlocking(
                    () -> {
                        BraveShieldsContentSettings.setShields(
                                page.getProfile(),
                                fixture,
                                BraveShieldsContentSettings.RESOURCE_IDENTIFIER_BRAVE_SHIELDS,
                                !protection,
                                false);
                        page.reload();
                    });
            loads.waitForCallback(beforeProtection);
            String changed =
                    mActivity.getString(
                            R.string.brave_tv_browser_state,
                            connection,
                            mActivity.getString(
                                    protection
                                            ? R.string.brave_tv_protection_off
                                            : R.string.brave_tv_protection_on));
            CriteriaHelper.pollInstrumentationThread(() -> mDevice.hasObject(By.text(changed)));
            assertTrue(address.isFocused());
            mDevice.pressBack();

            ThreadUtils.runOnUiThreadBlocking(() -> page.loadUrl(new LoadUrlParams(slow)));
            CriteriaHelper.pollInstrumentationThread(
                    () -> mDevice.hasObject(By.text("Controlled slow document")));
            CriteriaHelper.pollUiThread(
                    () -> page.isLoading() && slow.equals(page.getUrl().getSpec()));
            openControlsWithAddressFocus();
            mDevice.pressDPadDown();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_stop)));
            mDevice.pressDPadCenter();
            CriteriaHelper.pollUiThread(
                    () ->
                            mActivity.getActivityTab() == page
                                    && !page.isLoading()
                                    && slow.equals(page.getUrl().getSpec()));
            CriteriaHelper.pollInstrumentationThread(
                    () ->
                            !mDevice.hasObject(
                                    By.text(mActivity.getString(R.string.brave_tv_controls))));
        } finally {
            ThreadUtils.runOnUiThreadBlocking(
                    () -> {
                        page.removeObserver(observer);
                        BraveShieldsContentSettings.setShields(
                                page.getProfile(),
                                fixture,
                                BraveShieldsContentSettings.RESOURCE_IDENTIFIER_BRAVE_SHIELDS,
                                protection,
                                false);
                        page.stopLoading();
                    });
        }
    }

    private void openControlsWithAddressFocus() {
        ThreadUtils.runOnUiThreadBlocking(mActivity::showTvBrowserControls);
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_controls))));
        for (int i = 0; i < 8; i++) mDevice.pressDPadUp();
        for (int i = 0; i < 3; i++) mDevice.pressDPadLeft();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
    }

    private int fixtureCenterColor(Tab page, String fixtureUrl) {
        int[] center =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> {
                            assertSame(page, mActivity.getActivityTab());
                            assertFalse(page.isIncognito());
                            assertEquals(fixtureUrl, page.getUrl().getSpec());
                            assertTrue(mActivity.hasWindowFocus());
                            assertEquals(
                                    0,
                                    mActivity.getWindow().getAttributes().flags
                                            & WindowManager.LayoutParams.FLAG_SECURE);
                            int[] position = new int[2];
                            View content = page.getContentView();
                            content.getLocationOnScreen(position);
                            position[0] += content.getWidth() / 2;
                            position[1] += content.getHeight() / 2;
                            return position;
                        });
        Bitmap screen =
                InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screen);
        try {
            return screen.getPixel(center[0], center[1]);
        } finally {
            screen.recycle();
        }
    }

    @Test
    @MediumTest
    public void nativeFullscreenExitPreservesDocumentAndCursor() throws Exception {
        assertFullscreenCursorRecovery(
                "data:text/html,<style>button{position:fixed;inset:0;background:%23202124;"
                        + "color:white;display:flex;align-items:flex-start}</style>"
                        + "<button id='fullscreen' onclick='document.documentElement."
                        + "requestFullscreen()'>Fullscreen cursor fixture</button>",
                false, false);
    }

    @Test
    @MediumTest
    public void nativeVideoFullscreenExitPreservesDocumentAndCursor() throws Exception {
        String url =
                InstrumentationRegistry.getArguments().getString("tv_video_cursor_fixture_url");
        assertEquals("http://127.0.0.1:18088/media.html?native-cursor", url);
        assertFullscreenCursorRecovery(url, true, false);
    }

    @Test
    @MediumTest
    public void nativeCursorFirstAttachedDuringFullscreenRestoresAfterExit() throws Exception {
        assertFullscreenCursorRecovery(
                "data:text/html,<style>button{position:fixed;inset:0;background:%23202124;"
                        + "color:white;display:flex;align-items:flex-start}</style>"
                        + "<button id='fullscreen' onclick='document.documentElement."
                        + "requestFullscreen()'>First fullscreen cursor fixture</button>",
                false, true);
    }

    private void assertFullscreenCursorRecovery(
            String url, boolean video, boolean firstAttachmentInFullscreen) throws Exception {
        mActivityTestRule.loadUrl(url);
        Tab page = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        WebContents contents = ThreadUtils.runOnUiThreadBlocking(page::getWebContents);
        String fixtureUrl = ThreadUtils.runOnUiThreadBlocking(() -> page.getUrl().getSpec());
        String document =
                JavaScriptUtils.executeJavaScriptAndWaitForResult(
                        contents, "performance.timeOrigin");
        CriteriaHelper.pollInstrumentationThread(
                () -> fixtureCenterColor(page, fixtureUrl) == Color.rgb(32, 33, 36));
        TvBrowserSession session =
                ThreadUtils.runOnUiThreadBlocking(() -> new TvBrowserSession(mActivity, () -> {}));
        try {
            TvBrowserSession.Context context = ThreadUtils.runOnUiThreadBlocking(session::capture);
            if (firstAttachmentInFullscreen) {
                assertTrue(DOMUtils.clickNode(contents, "fullscreen"));
                CriteriaHelper.pollUiThread(contents::isFullscreenForCurrentTab);
            }
            ThreadUtils.runOnUiThreadBlocking(mActivity::showTvBrowserControls);
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
            mDevice.pressDPadRight();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_pointer)));
            mDevice.pressDPadCenter();
            CriteriaHelper.pollUiThread(
                    () -> mActivity.hasWindowFocus() && page.getContentView().hasFocus());
            if (!firstAttachmentInFullscreen) injectDpad(KeyEvent.KEYCODE_DPAD_CENTER);
            CriteriaHelper.pollUiThread(contents::isFullscreenForCurrentTab);
            if (video) DOMUtils.waitForMediaPlay(contents, "video");
            ThreadUtils.runOnUiThreadBlocking(
                    () -> {
                        assertFalse(session.isCurrent(context));
                        assertTrue(session.isCurrentDocument(context));
                    });
            injectDpad(KeyEvent.KEYCODE_BACK);
            CriteriaHelper.pollUiThread(() -> !contents.isFullscreenForCurrentTab());
            assertEquals(
                    document,
                    JavaScriptUtils.executeJavaScriptAndWaitForResult(
                            contents, "performance.timeOrigin"));
            ThreadUtils.runOnUiThreadBlocking(
                    () -> {
                        assertSame(page, mActivity.getActivityTab());
                        assertSame(contents, page.getWebContents());
                        assertTrue(session.isCurrentDocument(context));
                        assertFalse(session.isCurrent(context));
                    });
            injectDpad(KeyEvent.KEYCODE_DPAD_LEFT);
            injectDpad(KeyEvent.KEYCODE_DPAD_RIGHT);
            CriteriaHelper.pollInstrumentationThread(
                    () -> fixtureCenterColor(page, fixtureUrl) == Color.WHITE);
        } finally {
            ThreadUtils.runOnUiThreadBlocking(session::destroy);
        }
    }

    private void dispatchHeldDpad(KeyEvent down) {
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    for (int repeat = 0; repeat < 3; repeat++) {
                        mActivity.dispatchKeyEvent(
                                KeyEvent.changeTimeRepeat(
                                        down, SystemClock.uptimeMillis(), repeat));
                    }
                    mActivity.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP));
                });
    }

    private void injectDpad(int key) {
        long now = SystemClock.uptimeMillis();
        KeyEvent down =
                new KeyEvent(
                        now,
                        now,
                        KeyEvent.ACTION_DOWN,
                        key,
                        0,
                        0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD,
                        0,
                        0,
                        InputDevice.SOURCE_DPAD);
        var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        assertTrue(automation.injectInputEvent(down, true));
        assertTrue(
                automation.injectInputEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP), true));
    }

    @Test
    @MediumTest
    public void nativeCertificateBackReturnsToSafeDocument() {
        // Host TLS fixture and explicit emulator reverse required; see tests/device/README.md.
        mActivityTestRule.loadUrl(URL);
        Tab page = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        mActivityTestRule.loadUrl("https://127.0.0.1:18085/certificate.html");
        CriteriaHelper.pollUiThread(
                () ->
                        mActivity.hasWindowFocus()
                                && page.getUrl()
                                        .equals(
                                                new GURL(
                                                        "https://127.0.0.1:18085/certificate.html"))
                                && SecurityStateModel.isContentDangerous(page.getWebContents()));
        mDevice.pressBack();
        CriteriaHelper.pollUiThread(
                () ->
                        mActivity.getActivityTab() == page
                                && page.getUrl().equals(new GURL(URL))
                                && !page.isLoading()
                                && !SecurityStateModel.isContentDangerous(page.getWebContents()));
        assertFalse(mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_controls))));
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
            waitForFocus(By.desc(survivor));
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
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_forward)));
        mDevice.pressDPadDown();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_tabs)));
        mDevice.pressDPadDown();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_downloads)));
        mDevice.pressDPadDown();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_shields)));
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
        waitForFocus(By.desc(mActivity.getString(R.string.menu_page_info)));
    }

    private void openSettingsFromHome() {
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
        mDevice.pressDPadUp();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_home)));
        for (int i = 0; i < 4; i++) mDevice.pressDPadRight();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_settings)));
        mDevice.pressDPadCenter();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_search_engine)));
    }

    @Test
    @MediumTest
    @MinAndroidSdkLevel(Build.VERSION_CODES.Q)
    public void nativeMenuRoutesUseSingleTvPanelAndRetainBrowserOwners() {
        mActivityTestRule.loadUrl(URL);
        Tab original = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        WebContents contents = ThreadUtils.runOnUiThreadBlocking(original::getWebContents);
        int count =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> mActivity.getTabModelSelector().getModel(false).getCount());
        int[][] routes = {
            {R.id.all_bookmarks_menu_id, R.string.brave_tv_bookmarks},
            {R.id.open_history_menu_id, R.string.brave_tv_history},
            {R.id.downloads_menu_id, R.string.brave_tv_downloads},
            {R.id.tab_search, R.string.brave_tv_tabs},
            {R.id.tab_search_side_ui, R.string.brave_tv_tabs},
            {R.id.preferences_id, R.string.brave_tv_settings},
            {R.id.about_chrome_menu_id, R.string.brave_tv_about},
            {R.id.quick_delete_menu_id, R.string.brave_tv_clear_data}
        };
        for (int[] route : routes) {
            CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
            assertTrue(
                    ThreadUtils.runOnUiThreadBlocking(
                            () -> mActivity.onMenuOrKeyboardAction(route[0], false)));
            CriteriaHelper.pollInstrumentationThread(
                    () -> mDevice.hasObject(By.text(mActivity.getString(route[1]))));
            ThreadUtils.runOnUiThreadBlocking(
                    () -> {
                        assertSame(original, mActivity.getActivityTab());
                        assertSame(contents, original.getWebContents());
                        assertEquals(new GURL(URL), original.getUrl());
                        assertEquals(
                                count, mActivity.getTabModelSelector().getModel(false).getCount());
                        int windows = 0;
                        for (View window : WindowInspector.getGlobalWindowViews()) {
                            if (window.isAttachedToWindow() && window.isShown()) windows++;
                        }
                        assertEquals("One Activity and one TV panel", 2, windows);
                    });
            dismissNativeEntryToPage();
        }
        ThreadUtils.runOnUiThreadBlocking(
                () -> mActivity.onMenuOrKeyboardAction(R.id.preferences_id, false));
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_clear_data)));
        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        assertTrue(
                                mActivity.onMenuOrKeyboardAction(
                                        R.id.open_history_menu_id, false)));
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_clear_data)));
        assertFalse(mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_history))));
    }

    @Test
    @MediumTest
    @MinAndroidSdkLevel(Build.VERSION_CODES.Q)
    public void nativeSearchAndTabCommandsUseTvHomeAndAddress() {
        Tab original = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        assertTrue(
                ThreadUtils.runOnUiThreadBlocking(
                        () -> original.getNativePage() instanceof TvHomePage));
        ThreadUtils.runOnUiThreadBlocking(
                () -> assertTrue(mActivity.onMenuOrKeyboardAction(R.id.tab_search_side_ui, false)));
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_tabs))));
        dismissNativeEntryToPage();
        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        assertTrue(
                                mActivity.onMenuOrKeyboardAction(
                                        R.id.focus_and_clear_url_bar, false)));
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.clazz("android.widget.EditText")));
        assertFalse(mDevice.hasObject(By.res(mActivity.getPackageName(), "url_bar")));
        dismissNativeEntryToPage();
        ThreadUtils.runOnUiThreadBlocking(() -> assertSame(original, mActivity.getActivityTab()));

        mActivityTestRule.loadUrl(URL);
        WebContents contents = ThreadUtils.runOnUiThreadBlocking(original::getWebContents);
        int[] addressCommands = {R.id.focus_url_bar, R.id.focus_and_clear_url_bar};
        for (int command : addressCommands) {
            ThreadUtils.runOnUiThreadBlocking(
                    () -> assertTrue(mActivity.onMenuOrKeyboardAction(command, false)));
            String expected = command == R.id.focus_url_bar ? URL : "";
            CriteriaHelper.pollInstrumentationThread(
                    () -> {
                        UiObject2 editor = mDevice.findObject(By.clazz("android.widget.EditText"));
                        return editor != null
                                && (expected.isEmpty()
                                        ? TextUtils.isEmpty(editor.getText())
                                        : expected.equals(editor.getText()));
                    });
            dismissNativeEntryToPage();
            ThreadUtils.runOnUiThreadBlocking(
                    () -> {
                        assertSame(original, mActivity.getActivityTab());
                        assertSame(contents, original.getWebContents());
                        assertEquals(new GURL(URL), original.getUrl());
                    });
        }
    }

    private void dismissNativeEntryToPage() {
        for (int i = 0; i < 4; i++) {
            CriteriaHelper.pollUiThread(
                    () -> mActivity.hasWindowFocus() || focusedNativeWindow() != null);
            if (ThreadUtils.runOnUiThreadBlocking(mActivity::hasWindowFocus)) return;
            View owner = ThreadUtils.runOnUiThreadBlocking(this::focusedNativeWindow);
            assertNotNull(owner);
            boolean hadIme = ThreadUtils.runOnUiThreadBlocking(() -> windowHasIme(owner));
            mDevice.pressBack();
            CriteriaHelper.pollUiThread(
                    () ->
                            mActivity.hasWindowFocus()
                                    || !owner.isAttachedToWindow()
                                    || !owner.hasWindowFocus()
                                    || (hadIme && !windowHasIme(owner)));
        }
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
    }

    private @Nullable View focusedNativeWindow() {
        for (View root : WindowInspector.getGlobalWindowViews()) {
            if (root.isAttachedToWindow() && root.hasWindowFocus()) return root;
        }
        return null;
    }

    private boolean windowHasIme(View view) {
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(view);
        return insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
    }

    @Test
    @MediumTest
    @MinAndroidSdkLevel(Build.VERSION_CODES.Q)
    public void nativeAdblockUrlUsesTvFiltersAndReturnsToSettings() {
        mActivityTestRule.loadUrl(URL);
        Tab original = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        WebContents contents = ThreadUtils.runOnUiThreadBlocking(original::getWebContents);
        ThreadUtils.runOnUiThreadBlocking(
                () -> original.loadUrl(new LoadUrlParams("chrome://adblock/")));
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(
                                By.text(mActivity.getString(R.string.content_filters_title))));
        mDevice.pressBack();
        waitForFocus(By.desc(mActivity.getString(R.string.content_filters_title)));
        assertTrue(mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_settings))));
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertSame(original, mActivity.getActivityTab());
                    assertSame(contents, original.getWebContents());
                    assertEquals(new GURL(URL), original.getUrl());
                });
    }

    @Test
    @MediumTest
    public void privateNativeLibraryCommandsAreConsumedWithoutChangingOwners() {
        ThreadUtils.runOnUiThreadBlocking(
                () -> mActivity.onMenuOrKeyboardAction(R.id.new_incognito_tab_menu_id, false));
        CriteriaHelper.pollUiThread(
                () ->
                        mActivity.getActivityTab().isIncognito()
                                && mActivity.getActivityTab().getNativePage()
                                        instanceof TvHomePage);
        Tab original = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        for (int id :
                new int[] {
                    R.id.open_history_menu_id, R.id.downloads_menu_id, R.id.quick_delete_menu_id
                }) {
            ThreadUtils.runOnUiThreadBlocking(
                    () -> {
                        assertTrue(mActivity.onMenuOrKeyboardAction(id, false));
                        assertSame(original, mActivity.getActivityTab());
                        assertTrue(mActivity.hasWindowFocus());
                        assertTrue(mActivity.getTabModelSelector().isIncognitoSelected());
                    });
        }
        assertFalse(mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_history))));
        assertFalse(mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_downloads))));
        assertFalse(mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_clear_data))));
    }

    @Test
    @MediumTest
    public void nativeRendererCrashDpadReachesRecoveryAndPreservesTab() throws Exception {
        mActivityTestRule.loadUrl(URL);
        Tab original = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        WebContents contents = ThreadUtils.runOnUiThreadBlocking(original::getWebContents);
        assertNotNull(contents);
        int count =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> mActivity.getTabModelSelector().getModel(false).getCount());
        WebContentsUtils.crashTabAndWait(contents);
        CriteriaHelper.pollUiThread(() -> SadTab.isShowing(original));
        injectDpad(KeyEvent.KEYCODE_DPAD_DOWN);
        waitForFocus(By.res(mActivity.getPackageName(), "sad_tab_message"));
        injectDpad(KeyEvent.KEYCODE_DPAD_DOWN);
        waitForFocus(By.res(mActivity.getPackageName(), "sad_tab_button"));
        injectDpad(KeyEvent.KEYCODE_DPAD_CENTER);
        CriteriaHelper.pollUiThread(() -> !SadTab.isShowing(original));
        CriteriaHelper.pollInstrumentationThread(() -> mDevice.hasObject(By.text("TV saved page")));
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertSame(original, mActivity.getActivityTab());
                    assertSame(contents, original.getWebContents());
                    assertEquals(new GURL(URL), original.getUrl());
                    assertEquals(count, mActivity.getTabModelSelector().getModel(false).getCount());
                });
        mDevice.pressBack();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
    }

    @Test
    @MediumTest
    public void nativeFilePickerGuideCancelAndBackPreserveDocument() throws Exception {
        mActivityTestRule.loadUrl(
                "data:text/html,<input id='file' type='file'><script>window.pickerCancels=0;"
                    + "document.getElementById('file').oncancel=()=>window.pickerCancels++;</script>");
        Tab tab = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        WebContents contents = ThreadUtils.runOnUiThreadBlocking(tab::getWebContents);
        assertNotNull(contents);
        String document =
                JavaScriptUtils.executeJavaScriptAndWaitForResult(
                        contents, "performance.timeOrigin");
        int count =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> mActivity.getTabModelSelector().getModel(false).getCount());
        for (int i = 0; i < 2; i++) {
            assertTrue(DOMUtils.clickNode(contents, "file"));
            waitForFocus(By.desc(mActivity.getString(R.string.cancel)));
            assertTrue(
                    mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_file_picker))));
            if (i == 0) injectDpad(KeyEvent.KEYCODE_DPAD_CENTER);
            else mDevice.pressBack();
            CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
            assertFalse(
                    mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_file_picker))));
            assertEquals(
                    Integer.toString(i + 1),
                    JavaScriptUtils.executeJavaScriptAndWaitForResult(
                            contents, "window.pickerCancels"));
            ThreadUtils.runOnUiThreadBlocking(
                    () -> {
                        assertSame(tab, mActivity.getActivityTab());
                        assertSame(contents, tab.getWebContents());
                        assertEquals(
                                count, mActivity.getTabModelSelector().getModel(false).getCount());
                    });
            assertEquals(
                    document,
                    JavaScriptUtils.executeJavaScriptAndWaitForResult(
                            contents, "performance.timeOrigin"));
        }
    }

    @Test
    @MediumTest
    public void filePickerGuideCompletesOnceAndCancelsOnNavigationAndPause() throws Exception {
        mActivityTestRule.loadUrl(URL);
        CallbackHelper opened = new CallbackHelper();
        CallbackHelper cancelled = new CallbackHelper();
        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        assertTrue(
                                mActivity.showTvFilePickerGuide(
                                        opened::notifyCalled, cancelled::notifyCalled)));
        waitForFocus(By.desc(mActivity.getString(R.string.cancel)));
        mDevice.pressDPadRight();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_file_picker_continue)));
        injectDpad(KeyEvent.KEYCODE_DPAD_CENTER);
        opened.waitForCallback(0);
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        assertEquals(1, opened.getCallCount());
        assertEquals(0, cancelled.getCallCount());

        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        assertTrue(
                                mActivity.showTvFilePickerGuide(
                                        opened::notifyCalled, cancelled::notifyCalled)));
        waitForFocus(By.desc(mActivity.getString(R.string.cancel)));
        mActivityTestRule.loadUrl("data:text/html,<h1>TV%20picker%20replacement</h1>");
        cancelled.waitForCallback(0);
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        assertFalse(mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_file_picker))));
        assertEquals(1, opened.getCallCount());
        assertEquals(1, cancelled.getCallCount());

        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        assertTrue(
                                mActivity.showTvFilePickerGuide(
                                        opened::notifyCalled, cancelled::notifyCalled)));
        waitForFocus(By.desc(mActivity.getString(R.string.cancel)));
        mDevice.pressHome();
        CriteriaHelper.pollUiThread(
                () -> {
                    int state = ApplicationStatus.getStateForActivity(mActivity);
                    return state == ActivityState.PAUSED || state == ActivityState.STOPPED;
                });
        cancelled.waitForCallback(1);
        assertEquals(1, opened.getCallCount());
        assertEquals(2, cancelled.getCallCount());
    }

    @Test
    @MediumTest
    public void nativeModalCancelsFilePickerGuideAndOwnsFocus() throws Exception {
        mActivityTestRule.loadUrl(URL);
        Tab tab = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        WebContents contents = ThreadUtils.runOnUiThreadBlocking(tab::getWebContents);
        assertNotNull(contents);
        CallbackHelper opened = new CallbackHelper();
        CallbackHelper cancelled = new CallbackHelper();
        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        assertTrue(
                                mActivity.showTvFilePickerGuide(
                                        opened::notifyCalled, cancelled::notifyCalled)));
        waitForFocus(By.desc(mActivity.getString(R.string.cancel)));
        JavaScriptUtils.executeJavaScriptAndWaitForResult(
                contents, "setTimeout(()=>alert('TV picker native modal'),0); true");
        cancelled.waitForCallback(0);
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.text("TV picker native modal")));
        assertFalse(mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_file_picker))));
        waitForFocus(By.text("OK"));
        assertEquals(0, opened.getCallCount());
        assertEquals(1, cancelled.getCallCount());
        mDevice.pressBack();
        CriteriaHelper.pollInstrumentationThread(
                () -> !mDevice.hasObject(By.text("TV picker native modal")));
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertSame(tab, mActivity.getActivityTab());
                    assertSame(contents, tab.getWebContents());
                });
        assertEquals(0, opened.getCallCount());
        assertEquals(1, cancelled.getCallCount());
    }

    @Test
    @MediumTest
    @MinAndroidSdkLevel(Build.VERSION_CODES.Q)
    @DisableFeatures(ChromeFeatureList.INCOGNITO_SCREENSHOT)
    public void nativePrivateFilePickerGuideKeepsProtectionAndPrivateOwner() throws Exception {
        ThreadUtils.runOnUiThreadBlocking(
                () -> mActivity.onMenuOrKeyboardAction(R.id.new_incognito_tab_menu_id, false));
        CriteriaHelper.pollUiThread(() -> mActivity.getActivityTab().isIncognito());
        mActivityTestRule.loadUrl("data:text/html,<input id='file' type='file'>");
        Tab tab = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        WebContents contents = ThreadUtils.runOnUiThreadBlocking(tab::getWebContents);
        assertNotNull(contents);
        assertTrue(DOMUtils.clickNode(contents, "file"));
        waitForFocus(By.desc(mActivity.getString(R.string.cancel)));
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    View root = focusedNativeWindow();
                    assertNotNull(root);
                    assertTrue(
                            (((WindowManager.LayoutParams) root.getLayoutParams()).flags
                                            & WindowManager.LayoutParams.FLAG_SECURE)
                                    != 0);
                });
        mDevice.pressBack();
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertSame(tab, mActivity.getActivityTab());
                    assertSame(contents, tab.getWebContents());
                    assertTrue(tab.isIncognito());
                    assertTrue(mActivity.getTabModelSelector().isIncognitoSelected());
                });
        assertFalse(mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_file_picker))));
    }

    @Test
    @MediumTest
    public void unavailableExternalLinkReturnsToSameDocumentAndDisposesOnNavigation()
            throws Exception {
        String url =
                InstrumentationRegistry.getArguments().getString("tv_external_link_fixture_url");
        assertNotNull("Start the owned native-dialog fixture and pass its URL", url);
        assertEquals("http", new GURL(url).getScheme());
        mActivityTestRule.loadUrl(url);
        Tab original = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        WebContents contents = ThreadUtils.runOnUiThreadBlocking(original::getWebContents);
        assertNotNull(contents);
        String document =
                JavaScriptUtils.executeJavaScriptAndWaitForResult(
                        contents, "performance.timeOrigin");
        BySelector unavailable = By.text(mActivity.getString(R.string.brave_tv_link_unavailable));
        BySelector returnToPage = By.desc(mActivity.getString(R.string.brave_tv_return_to_page));
        for (boolean useBack : new boolean[] {true, false}) {
            assertTrue(DOMUtils.clickNode(contents, "external"));
            waitForFocus(returnToPage);
            assertTrue(mDevice.hasObject(unavailable));
            if (useBack) {
                mDevice.pressBack();
            } else {
                mDevice.pressDPadCenter();
            }
            CriteriaHelper.pollInstrumentationThread(() -> !mDevice.hasObject(unavailable));
            CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
            ThreadUtils.runOnUiThreadBlocking(
                    () -> {
                        assertSame(original, mActivity.getActivityTab());
                        assertSame(contents, original.getWebContents());
                        assertEquals(new GURL(url), original.getUrl());
                    });
            assertEquals(
                    document,
                    JavaScriptUtils.executeJavaScriptAndWaitForResult(
                            contents, "performance.timeOrigin"));
        }
        assertTrue(DOMUtils.clickNode(contents, "external"));
        waitForFocus(returnToPage);
        mActivityTestRule.loadUrl(URL);
        CriteriaHelper.pollInstrumentationThread(() -> !mDevice.hasObject(unavailable));
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
    }

    @Test
    @MediumTest
    public void unavailableExternalLinkDoesNotTakeFocusedEditorWithHiddenKeyboard()
            throws Exception {
        mActivityTestRule.loadUrl(
                "data:text/html,<input id='editor' aria-label='TV native editor'>");
        Tab tab = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        WebContents contents = ThreadUtils.runOnUiThreadBlocking(tab::getWebContents);
        assertNotNull(contents);
        assertTrue(DOMUtils.clickNode(contents, "editor"));
        CriteriaHelper.pollUiThread(
                () -> {
                    ImeAdapter input = ImeAdapter.fromWebContents(contents);
                    return input != null && input.focusedNodeEditable();
                });
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    InputMethodManager manager =
                            mActivity.getSystemService(InputMethodManager.class);
                    assertNotNull(manager);
                    manager.hideSoftInputFromWindow(tab.getContentView().getWindowToken(), 0);
                });
        CriteriaHelper.pollUiThread(
                () -> {
                    var insets =
                            ViewCompat.getRootWindowInsets(mActivity.getWindow().getDecorView());
                    return insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime());
                });
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    ImeAdapter input = ImeAdapter.fromWebContents(contents);
                    assertNotNull(input);
                    assertTrue(input.focusedNodeEditable());
                    mActivity.showTvUnavailableExternalLink(contents);
                    assertSame(tab, mActivity.getActivityTab());
                    assertSame(contents, tab.getWebContents());
                    assertTrue(input.focusedNodeEditable());
                    assertTrue(mActivity.hasWindowFocus());
                });
        assertFalse(
                mDevice.hasObject(
                        By.text(mActivity.getString(R.string.brave_tv_link_unavailable))));
    }

    @Test
    @MediumTest
    public void unavailableExternalLinkRejectsStaleAndPrivateOwners() {
        mActivityTestRule.loadUrl(URL);
        WebContents stale =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> mActivity.getActivityTab().getWebContents());
        assertNotNull(stale);
        ThreadUtils.runOnUiThreadBlocking(
                () -> mActivity.onMenuOrKeyboardAction(R.id.new_tab_menu_id, false));
        CriteriaHelper.pollUiThread(
                () -> mActivity.getActivityTab().getNativePage() instanceof TvHomePage);
        ThreadUtils.runOnUiThreadBlocking(() -> mActivity.showTvUnavailableExternalLink(stale));
        assertFalse(
                mDevice.hasObject(
                        By.text(mActivity.getString(R.string.brave_tv_link_unavailable))));
        ThreadUtils.runOnUiThreadBlocking(
                () -> mActivity.onMenuOrKeyboardAction(R.id.new_incognito_tab_menu_id, false));
        CriteriaHelper.pollUiThread(() -> mActivity.getActivityTab().isIncognito());
        mActivityTestRule.loadUrl(URL);
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    Tab privateTab = mActivity.getActivityTab();
                    WebContents contents = privateTab.getWebContents();
                    assertNotNull(contents);
                    mActivity.showTvUnavailableExternalLink(contents);
                    assertSame(privateTab, mActivity.getActivityTab());
                    assertTrue(mActivity.getTabModelSelector().isIncognitoSelected());
                    assertTrue(mActivity.hasWindowFocus());
                });
        assertFalse(
                mDevice.hasObject(
                        By.text(mActivity.getString(R.string.brave_tv_link_unavailable))));
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

    // Hold only native completion; actual deletion is covered by the production privacy fixture.
    private static final class PendingClear extends BrowsingDataBridgeJni {
        private final Profile mProfile;
        private @Nullable BrowsingDataBridge.OnClearBrowsingDataListener mCallback;
        private int mCalls;

        PendingClear(Profile profile) {
            mProfile = profile;
        }

        @Override
        public void clearBrowsingData(
                Profile profile,
                @Nullable BrowsingDataBridge.OnClearBrowsingDataListener callback,
                int[] dataTypes,
                int timePeriod,
                String[] excludedDomains,
                @Nullable String[] ignoredDomains) {
            ThreadUtils.assertOnUiThread();
            assertSame(mProfile, profile);
            assertFalse(profile.isOffTheRecord());
            assertArrayEquals(new int[] {BrowsingDataType.CACHE}, dataTypes);
            assertEquals(TimePeriod.ALL_TIME, timePeriod);
            assertEquals(0, excludedDomains.length);
            assertNotNull(ignoredDomains);
            assertEquals(0, ignoredDomains.length);
            assertNull(mCallback);
            assertNotNull(callback);
            mCallback = callback;
            mCalls++;
        }

        void complete() {
            ThreadUtils.assertOnUiThread();
            BrowsingDataBridge.OnClearBrowsingDataListener callback = mCallback;
            assertNotNull(callback);
            mCallback = null;
            callback.onBrowsingDataCleared();
        }
    }

    private PendingClear beginPendingCacheClear() {
        openSettingsFromHome();
        PendingClear pending =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> {
                            PendingClear fixture =
                                    new PendingClear(mActivity.getActivityTab().getProfile());
                            BrowsingDataBridgeJni.setInstanceForTesting(fixture);
                            return fixture;
                        });
        mDevice.pressDPadDown();
        waitForFocus(By.desc(mActivity.getString(R.string.content_filters_title)));
        mDevice.pressDPadDown();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_clear_data)));
        mDevice.pressDPadCenter();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_settings)));
        mDevice.pressDPadLeft();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_clear_cache)));
        mDevice.pressDPadCenter();
        waitForFocus(By.desc(mActivity.getString(R.string.cancel)));
        mDevice.pressDPadRight();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_clear_confirm)));
        mDevice.pressDPadCenter();
        CriteriaHelper.pollUiThread(() -> pending.mCallback != null);
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_settings)));
        assertTrue(
                mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_clearing_data))));
        assertTrue(
                mDevice.hasObject(
                        By.desc(mActivity.getString(R.string.brave_tv_clear_cache))
                                .enabled(false)));
        assertTrue(
                mDevice.hasObject(
                        By.desc(mActivity.getString(R.string.brave_tv_clear_history))
                                .enabled(false)));
        assertTrue(
                mDevice.hasObject(
                        By.desc(mActivity.getString(R.string.brave_tv_clear_cookies))
                                .enabled(false)));
        return pending;
    }

    @Test
    @MediumTest
    public void pendingClearBackDoesNotReopenOrDuplicate() {
        try {
            PendingClear pending = beginPendingCacheClear();
            mDevice.pressDPadLeft();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_clear_cache)));
            mDevice.pressDPadCenter();
            ThreadUtils.runOnUiThreadBlocking(() -> assertEquals(1, pending.mCalls));
            mDevice.pressBack();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_clear_data)));
            mDevice.pressBack();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_settings)));
            ThreadUtils.runOnUiThreadBlocking(pending::complete);
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_settings)));
            assertFalse(
                    mDevice.hasObject(
                            By.text(mActivity.getString(R.string.brave_tv_cleared_data))));
            mDevice.pressDPadCenter();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_clear_data)));
            mDevice.pressDPadCenter();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_settings)));
            assertTrue(
                    mDevice.hasObject(
                            By.desc(mActivity.getString(R.string.brave_tv_clear_cache))
                                    .enabled(true)));
        } finally {
            ThreadUtils.runOnUiThreadBlocking(
                    () -> BrowsingDataBridgeJni.setInstanceForTesting(null));
        }
    }

    @Test
    @MediumTest
    public void pendingClearPauseDoesNotReopenOnResume() throws Exception {
        try {
            PendingClear pending = beginPendingCacheClear();
            TvHomePage home =
                    ThreadUtils.runOnUiThreadBlocking(
                            () -> (TvHomePage) mActivity.getActivityTab().getNativePage());
            mDevice.pressHome();
            CriteriaHelper.pollUiThread(
                    () -> {
                        int state = ApplicationStatus.getStateForActivity(mActivity);
                        return state == ActivityState.PAUSED || state == ActivityState.STOPPED;
                    });
            ThreadUtils.runOnUiThreadBlocking(pending::complete);
            mActivityTestRule.resumeMainActivityFromLauncher();
            CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
            ThreadUtils.runOnUiThreadBlocking(
                    () -> assertSame(home, mActivity.getActivityTab().getNativePage()));
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_settings)));
            assertFalse(
                    mDevice.hasObject(By.desc(mActivity.getString(R.string.brave_tv_clear_cache))));
            assertFalse(
                    mDevice.hasObject(
                            By.text(mActivity.getString(R.string.brave_tv_cleared_data))));
        } finally {
            ThreadUtils.runOnUiThreadBlocking(
                    () -> BrowsingDataBridgeJni.setInstanceForTesting(null));
        }
    }

    @Test
    @MediumTest
    public void pendingClearCompletionReturnsToOwnedPanel() {
        try {
            PendingClear pending = beginPendingCacheClear();
            ThreadUtils.runOnUiThreadBlocking(pending::complete);
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_settings)));
            assertTrue(
                    mDevice.hasObject(
                            By.text(mActivity.getString(R.string.brave_tv_cleared_data))));
            assertTrue(
                    mDevice.hasObject(
                            By.desc(mActivity.getString(R.string.brave_tv_clear_cache))
                                    .enabled(true)));
            mDevice.pressBack();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_clear_data)));
            mDevice.pressBack();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_settings)));
        } finally {
            ThreadUtils.runOnUiThreadBlocking(
                    () -> BrowsingDataBridgeJni.setInstanceForTesting(null));
        }
    }

    @Test
    @MediumTest
    public void pendingClearCompletionRejectsReplacedDocument() {
        try {
            PendingClear pending = beginPendingCacheClear();
            Tab original = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
            mActivityTestRule.loadUrl(URL);
            ThreadUtils.runOnUiThreadBlocking(
                    () -> {
                        assertSame(original, mActivity.getActivityTab());
                        assertEquals(new GURL(URL), original.getUrl());
                        pending.complete();
                    });
            CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
            assertFalse(
                    mDevice.hasObject(
                            By.text(mActivity.getString(R.string.brave_tv_cleared_data))));
            assertFalse(
                    mDevice.hasObject(By.desc(mActivity.getString(R.string.brave_tv_clear_cache))));
            assertTrue(mDevice.hasObject(By.text("TV saved page")));
        } finally {
            ThreadUtils.runOnUiThreadBlocking(
                    () -> BrowsingDataBridgeJni.setInstanceForTesting(null));
        }
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
        for (int i = 0; i < 3; i++) mDevice.pressDPadRight();
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

    @Test
    @MediumTest
    public void removedNativeCaptureDrainsCompressionAndDoesNotReappear() throws Exception {
        mActivityTestRule.loadUrl(URL);
        Tab tab = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        int id = ThreadUtils.runOnUiThreadBlocking(tab::getId);
        TabContentManager manager =
                ThreadUtils.runOnUiThreadBlocking(mActivity::getTabContentManager);
        File jpeg = TabContentManager.getTabThumbnailFileJpeg(id);
        File etc = TabContentManager.getTabThumbnailFileEtc1(tab);
        ThreadUtils.runOnUiThreadBlocking(
                () -> manager.removeTabThumbnail(id, /* forceRemoval= */ true));
        CriteriaHelper.pollInstrumentationThread(() -> !jpeg.exists() && !etc.exists());
        CallbackHelper removed = new CallbackHelper();
        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        manager.cacheTabThumbnailWithCallback(
                                tab,
                                false,
                                bitmap -> {
                                    assertNull(bitmap);
                                    // OnTabReadback delivers this callback before enqueuing
                                    // compression replies.
                                    PostTask.postTask(
                                            TaskTraits.UI_DEFAULT,
                                            () -> {
                                                assertTrue(
                                                        "Native compression must have started",
                                                        manager.isTabCaptureInFlightForTesting(id));
                                                manager.removeTabThumbnail(
                                                        id, /* forceRemoval= */ true);
                                                assertTrue(
                                                        "Removal must not hide pending compression",
                                                        manager.isTabCaptureInFlightForTesting(id));
                                                removed.notifyCalled();
                                            });
                                }));
        removed.waitForCallback(0);
        CriteriaHelper.pollUiThread(() -> !manager.isTabCaptureInFlightForTesting(id));
        CriteriaHelper.pollInstrumentationThread(() -> !jpeg.exists() && !etc.exists());
        ThreadUtils.runOnUiThreadBlocking(() -> manager.cacheTabThumbnail(tab));
        CriteriaHelper.pollInstrumentationThread(jpeg::exists);
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertSame(tab, mActivity.getActivityTab());
                    assertFalse(tab.isIncognito());
                    assertEquals(new GURL(URL), tab.getUrl());
                });
    }

    @Test
    @MediumTest
    @DisableFeatures(ChromeFeatureList.INCOGNITO_SCREENSHOT)
    public void privateHomeNativeThumbnailCacheDoesNotPersistPixels() throws Exception {
        checkPrivateThumbnailCache(false);
    }

    @Test
    @MediumTest
    @DisableFeatures(ChromeFeatureList.INCOGNITO_SCREENSHOT)
    public void privateWebNativeThumbnailCacheDoesNotPersistPixels() throws Exception {
        checkPrivateThumbnailCache(true);
    }

    private void checkPrivateThumbnailCache(boolean webPage) throws Exception {
        mActivityTestRule.loadUrl(URL);
        Tab normal = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        TabContentManager manager =
                ThreadUtils.runOnUiThreadBlocking(mActivity::getTabContentManager);
        File normalJpeg = TabContentManager.getTabThumbnailFileJpeg(normal.getId());
        ThreadUtils.runOnUiThreadBlocking(
                () -> manager.removeTabThumbnail(normal.getId(), /* forceRemoval= */ true));
        CriteriaHelper.pollInstrumentationThread(() -> !normalJpeg.exists());
        ThreadUtils.runOnUiThreadBlocking(() -> manager.cacheTabThumbnail(normal));
        CriteriaHelper.pollInstrumentationThread(normalJpeg::exists);
        ThreadUtils.runOnUiThreadBlocking(
                () -> mActivity.onMenuOrKeyboardAction(R.id.new_incognito_tab_menu_id, false));
        CriteriaHelper.pollUiThread(
                () ->
                        mActivity.getActivityTab().isIncognito()
                                && mActivity.getActivityTab().getNativePage()
                                        instanceof TvHomePage);
        if (webPage) mActivityTestRule.loadUrl(URL);
        Tab privateTab = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        CriteriaHelper.pollUiThread(
                () -> {
                    View view =
                            privateTab.getNativePage() == null
                                    ? privateTab.getContentView()
                                    : privateTab.getNativePage().getView();
                    return mActivity.getActivityTab() == privateTab
                            && mActivity.hasWindowFocus()
                            && !privateTab.isHidden()
                            && view != null
                            && view.isAttachedToWindow()
                            && view.isShown()
                            && view.getWidth() > 0
                            && view.getHeight() > 0;
                });
        int privateId = ThreadUtils.runOnUiThreadBlocking(privateTab::getId);
        File privateJpeg = TabContentManager.getTabThumbnailFileJpeg(privateId);
        File privateEtc = TabContentManager.getTabThumbnailFileEtc1(privateTab);
        // Empty owned TEST entries require native cleanup of caches from previous captures.
        if (!privateJpeg.exists()) assertTrue(privateJpeg.createNewFile());
        if (!privateEtc.exists()) assertTrue(privateEtc.createNewFile());
        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        assertTrue(
                                (mActivity.getWindow().getAttributes().flags
                                                & WindowManager.LayoutParams.FLAG_SECURE)
                                        != 0));
        CallbackHelper cached = new CallbackHelper();
        ThreadUtils.runOnUiThreadBlocking(
                () ->
                        manager.cacheTabThumbnailWithCallback(
                                privateTab,
                                false,
                                bitmap -> {
                                    assertNull(bitmap);
                                    cached.notifyCalled();
                                }));
        cached.waitForCallback(0);
        CriteriaHelper.pollUiThread(() -> !manager.isTabCaptureInFlightForTesting(privateId));
        CriteriaHelper.pollInstrumentationThread(
                () -> !privateJpeg.exists() && !privateEtc.exists());
        mDevice.pressHome();
        CriteriaHelper.pollUiThread(
                () -> {
                    int state = ApplicationStatus.getStateForActivity(mActivity);
                    return state == ActivityState.PAUSED || state == ActivityState.STOPPED;
                });
        CriteriaHelper.pollUiThread(() -> !manager.isTabCaptureInFlightForTesting(privateId));
        CriteriaHelper.pollInstrumentationThread(
                () -> !privateJpeg.exists() && !privateEtc.exists());
        mActivityTestRule.resumeMainActivityFromLauncher();
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertSame(privateTab, mActivity.getActivityTab());
                    assertTrue(privateTab.isIncognito());
                    assertTrue(
                            (mActivity.getWindow().getAttributes().flags
                                            & WindowManager.LayoutParams.FLAG_SECURE)
                                    != 0);
                });
        assertTrue("Normal cached page must be preserved", normalJpeg.exists());
    }

    @Test
    @MediumTest
    @MinAndroidSdkLevel(Build.VERSION_CODES.Q)
    @DisableFeatures(ChromeFeatureList.INCOGNITO_SCREENSHOT)
    public void privateAddressDraftIsDiscardedAcrossActivityRecreation() {
        int normalId = ThreadUtils.runOnUiThreadBlocking(() -> mActivity.getActivityTab().getId());
        GURL normalUrl =
                ThreadUtils.runOnUiThreadBlocking(() -> mActivity.getActivityTab().getUrl());
        ThreadUtils.runOnUiThreadBlocking(
                () -> mActivity.onMenuOrKeyboardAction(R.id.new_incognito_tab_menu_id, false));
        CriteriaHelper.pollUiThread(
                () ->
                        mActivity.getActivityTab().isIncognito()
                                && mActivity.getActivityTab().getNativePage()
                                        instanceof TvHomePage);
        int privateId = ThreadUtils.runOnUiThreadBlocking(() -> mActivity.getActivityTab().getId());
        mActivityTestRule.loadUrl(URL);
        GURL privateUrl =
                ThreadUtils.runOnUiThreadBlocking(() -> mActivity.getActivityTab().getUrl());
        ThreadUtils.runOnUiThreadBlocking(
                () -> assertTrue(mActivity.onMenuOrKeyboardAction(R.id.focus_url_bar, false)));
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.clazz("android.widget.EditText")));
        String draft = "TV private draft must be discarded";
        mDevice.findObject(By.clazz("android.widget.EditText")).setText(draft);
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.clazz("android.widget.EditText").text(draft)));

        mActivity = ApplicationTestUtils.recreateActivity(mActivity);
        CriteriaHelper.pollUiThread(
                () -> {
                    Tab restored = mActivity.getActivityTab();
                    return restored != null
                            && restored.getId() == privateId
                            && restored.isIncognito()
                            && privateUrl.equals(restored.getUrl());
                });
        dismissNativeEntryToPage();
        assertFalse(mDevice.hasObject(By.text(draft)));
        ThreadUtils.runOnUiThreadBlocking(
                () -> assertTrue(mActivity.onMenuOrKeyboardAction(R.id.focus_url_bar, false)));
        CriteriaHelper.pollInstrumentationThread(
                () -> {
                    UiObject2 editor = mDevice.findObject(By.clazz("android.widget.EditText"));
                    return editor != null && privateUrl.getSpec().equals(editor.getText());
                });
        dismissNativeEntryToPage();
        ThreadUtils.runOnUiThreadBlocking(() -> mActivity.getTabModelSelector().selectModel(false));
        CriteriaHelper.pollUiThread(
                () ->
                        mActivity.getActivityTab() != null
                                && mActivity.getActivityTab().getId() == normalId
                                && !mActivity.getActivityTab().isIncognito()
                                && normalUrl.equals(mActivity.getActivityTab().getUrl()));
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_address)));
        assertFalse(mDevice.hasObject(By.text(draft)));
        CriteriaHelper.pollInstrumentationThread(
                () -> {
                    try {
                        UiObject2 editor = mDevice.findObject(By.clazz("android.widget.EditText"));
                        Criteria.checkThat(
                                "Unexpected editor owner: "
                                        + (editor == null
                                                ? "none"
                                                : editor.getApplicationPackage()
                                                        + "/"
                                                        + editor.getResourceName()
                                                        + "; focused="
                                                        + editor.isFocused()),
                                editor == null,
                                is(true));
                    } catch (StaleObjectException stale) {
                        return false;
                    }
                    return true;
                });
        assertFalse(
                mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_private_home))));
    }
}
