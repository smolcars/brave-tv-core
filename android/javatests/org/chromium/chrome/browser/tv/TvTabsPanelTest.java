// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Build;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
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
import org.chromium.base.test.util.Features.DisableFeatures;
import org.chromium.base.test.util.MinAndroidSdkLevel;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.flags.ChromeFeatureList;
import org.chromium.chrome.browser.flags.ChromeSwitches;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabLaunchType;
import org.chromium.chrome.browser.tab.TabState;
import org.chromium.chrome.browser.tab.TabStateExtractor;
import org.chromium.chrome.browser.tabmodel.TabClosureParams;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tabmodel.TabModelUtils;
import org.chromium.chrome.test.ChromeJUnit4ClassRunner;
import org.chromium.chrome.test.ChromeTabbedActivityTestRule;
import org.chromium.content_public.browser.LoadUrlParams;
import org.chromium.url.GURL;

import java.util.ArrayList;
import java.util.List;

/** Native model updates, genuinely frozen navigation and private list window ownership. */
@RunWith(ChromeJUnit4ClassRunner.class)
@CommandLineFlags.Add({ChromeSwitches.DISABLE_FIRST_RUN_EXPERIENCE})
@DisableFeatures({
    ChromeFeatureList.LOAD_ALL_TABS_AT_STARTUP,
    ChromeFeatureList.ANDROID_OPEN_INCOGNITO_AS_WINDOW
})
@DoNotBatch(reason = "Restores and removes only owned serialized normal/private fixture tabs.")
public class TvTabsPanelTest {
    @Rule
    public ChromeTabbedActivityTestRule mActivityTestRule = new ChromeTabbedActivityTestRule();

    private final List<Integer> mOwnedIds = new ArrayList<>();
    private BraveActivity mActivity;
    private UiDevice mDevice;
    private Tab mAnchor;

    @Before
    public void setUp() {
        mActivityTestRule.startMainActivityOnBlankPage();
        mActivity = (BraveActivity) (Object) mActivityTestRule.getActivity();
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_controls))));
        mDevice.pressBack();
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        mAnchor = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
    }

    @After
    public void tearDown() {
        if (mActivity == null) return;
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    if (!mActivity.areTabModelsInitialized()) return;
                    TabModel normal = mActivity.getTabModelSelector().getModel(false);
                    if (!mAnchor.isDestroyed() && normal.indexOf(mAnchor) >= 0) {
                        mActivity.getTabModelSelector().selectModel(false);
                        TabModelUtils.setIndex(normal, normal.indexOf(mAnchor));
                    }
                    for (int id : mOwnedIds) {
                        for (boolean incognito : new boolean[] {false, true}) {
                            TabModel model = mActivity.getTabModelSelector().getModel(incognito);
                            if (model.isClosurePending(id)) model.commitTabClosure(id);
                        }
                        Tab tab = mActivity.getTabModelSelector().getTabById(id);
                        if (tab != null && !tab.isDestroyed()) closeOwned(tab);
                    }
                });
    }

    @Test
    @MediumTest
    public void largeListPreservesSelectionAndRestoresFrozenNativeDocument() {
        List<Tab> tabs = seedFrozenTabs();
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    for (Tab tab : tabs) {
                        assertTrue(tab.isFrozen());
                        assertNull(tab.getWebContents());
                        assertNull(tab.getTabGroupId());
                    }
                });
        ThreadUtils.runOnUiThreadBlocking(mActivity::showTvTabs);
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_new_tab)));
        assertFalse(mDevice.hasObject(By.desc(title(TvBrowserControls.TAB_PAGE_SIZE))));
        focusDown(By.desc(mActivity.getString(R.string.brave_tv_previous)));
        mDevice.pressDPadRight();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_next)));
        mDevice.pressDPadCenter();
        focusDown(By.desc(title(TvBrowserControls.TAB_PAGE_SIZE)));
        ThreadUtils.runOnUiThreadBlocking(
                () -> closeOwned(tabs.get(TvBrowserControls.TAB_PAGE_SIZE - 1)));
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(
                                By.desc(mActivity.getString(R.string.brave_tv_previous))
                                        .enabled(false)));
        waitForFocus(By.desc(title(TvBrowserControls.TAB_PAGE_SIZE)));
        for (int i = 1; i < TvBrowserControls.TAB_PAGE_SIZE; i++) mDevice.pressDPadUp();
        waitForFocus(By.desc(title(0)));
        for (int i = 1; i < TvBrowserControls.TAB_PAGE_SIZE; i++) mDevice.pressDPadDown();
        waitForFocus(By.desc(title(TvBrowserControls.TAB_PAGE_SIZE)));
        ThreadUtils.runOnUiThreadBlocking(
                () -> closeOwned(tabs.get(TvBrowserControls.TAB_PAGE_SIZE)));
        int survivor = TvBrowserControls.TAB_PAGE_SIZE + 1;
        waitForFocus(By.desc(title(survivor)));
        ThreadUtils.runOnUiThreadBlocking(() -> assertTrue(tabs.get(survivor).isFrozen()));
        mDevice.pressDPadCenter();
        CriteriaHelper.pollUiThread(
                () ->
                        mActivity.getActivityTab() == tabs.get(survivor)
                                && !tabs.get(survivor).isFrozen()
                                && !tabs.get(survivor).isLoading()
                                && tabs.get(survivor).getWebContents() != null);
        ThreadUtils.runOnUiThreadBlocking(
                () -> assertEquals(new GURL(url(survivor)), tabs.get(survivor).getUrl()));
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_tabs)));
        mDevice.pressBack();
        CriteriaHelper.pollInstrumentationThread(() -> mDevice.hasObject(By.text(title(survivor))));
        mDevice.pressBack();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_tabs)));
        for (int i = 0; i < 4; i++) mDevice.pressDPadDown();
        mDevice.pressDPadRight();
        mDevice.pressDPadRight();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_close_tab)));
        mDevice.pressDPadCenter();
        CriteriaHelper.pollUiThread(
                () ->
                        mActivity.getTabModelSelector().getModel(false).indexOf(tabs.get(survivor))
                                        < 0
                                && mActivity.getActivityTab() != null
                                && mActivity.getActivityTab() != tabs.get(survivor)
                                && !mActivity.getActivityTab().isDestroyed()
                                && mActivity
                                                .getTabModelSelector()
                                                .getModel(false)
                                                .indexOf(mActivity.getActivityTab())
                                        >= 0);
    }

    @Test
    @MediumTest
    @MinAndroidSdkLevel(Build.VERSION_CODES.Q)
    public void privateListFromNormalSelectionKeepsSecureWindow() {
        ThreadUtils.runOnUiThreadBlocking(
                () -> mActivity.onMenuOrKeyboardAction(R.id.new_incognito_tab_menu_id, false));
        CriteriaHelper.pollUiThread(
                () ->
                        mActivity.getActivityTab() != null
                                && mActivity.getActivityTab().isIncognito());
        Tab owned = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
        mOwnedIds.add(owned.getId());
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    TabModel normal = mActivity.getTabModelSelector().getModel(false);
                    mActivity.getTabModelSelector().selectModel(false);
                    TabModelUtils.setIndex(normal, normal.indexOf(mAnchor));
                });
        CriteriaHelper.pollUiThread(
                () ->
                        mActivity.getActivityTab() == mAnchor
                                && (mActivity.getWindow().getAttributes().flags
                                                & WindowManager.LayoutParams.FLAG_SECURE)
                                        == 0);
        ThreadUtils.runOnUiThreadBlocking(mActivity::showTvTabs);
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_new_tab)));
        mDevice.pressDPadRight();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_private_tabs)));
        mDevice.pressDPadCenter();
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(
                                By.text(mActivity.getString(R.string.brave_tv_private_tabs))));
        CriteriaHelper.pollUiThread(
                () ->
                        WindowInspector.getGlobalWindowViews().stream()
                                .anyMatch(
                                        root ->
                                                root.getLayoutParams()
                                                                instanceof
                                                                WindowManager.LayoutParams params
                                                        && (params.flags
                                                                        & WindowManager.LayoutParams
                                                                                .FLAG_SECURE)
                                                                != 0));
        ThreadUtils.runOnUiThreadBlocking(() -> assertSame(mAnchor, mActivity.getActivityTab()));
        mDevice.pressBack();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_tabs)));
        CriteriaHelper.pollUiThread(
                () ->
                        WindowInspector.getGlobalWindowViews().stream()
                                .noneMatch(
                                        root ->
                                                root.getLayoutParams()
                                                                instanceof
                                                                WindowManager.LayoutParams params
                                                        && (params.flags
                                                                        & WindowManager.LayoutParams
                                                                                .FLAG_SECURE)
                                                                != 0));
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertSame(mAnchor, mActivity.getActivityTab());
                    assertFalse(owned.isDestroyed());
                });
    }

    private List<Tab> seedFrozenTabs() {
        List<Tab> result = new ArrayList<>();
        for (int i = 0; i < 2 * TvBrowserControls.TAB_PAGE_SIZE; i++) {
            int index = i;
            Tab live =
                    ThreadUtils.runOnUiThreadBlocking(
                            () ->
                                    mActivity
                                            .getTabCreator(false)
                                            .createNewTab(
                                                    new LoadUrlParams(url(index)),
                                                    TabLaunchType.FROM_CHROME_UI,
                                                    null));
            assertNotNull(live);
            mOwnedIds.add(live.getId());
            CriteriaHelper.pollUiThread(
                    () -> !live.isLoading() && live.getTitle().equals(title(index)));
            Tab frozen =
                    ThreadUtils.runOnUiThreadBlocking(
                            () -> {
                                TabState state = TabStateExtractor.from(live);
                                assertNotNull(state);
                                assertNotNull(state.contentsState);
                                TabModel model = mActivity.getTabModelSelector().getModel(false);
                                TabModelUtils.setIndex(model, model.indexOf(mAnchor));
                                closeOwned(live);
                                return mActivity
                                        .getTabCreator(false)
                                        .createFrozenTab(state, live.getId(), index);
                            });
            assertNotNull(frozen);
            result.add(frozen);
        }
        return result;
    }

    private void closeOwned(Tab tab) {
        assertTrue(mOwnedIds.contains(tab.getId()));
        mActivity
                .getTabModelSelector()
                .getModel(tab.isIncognito())
                .getTabRemover()
                .closeTabs(TabClosureParams.closeTab(tab).allowUndo(false).build(), false);
    }

    private static String title(int index) {
        return "TV frozen tab " + index;
    }

    private static String url(int index) {
        String encoded = title(index).replace(" ", "%20");
        return "data:text/html,<title>" + encoded + "</title><h1>" + encoded + "</h1>";
    }

    private void waitForFocus(BySelector target) {
        CriteriaHelper.pollInstrumentationThread(
                () -> {
                    UiObject2 action = mDevice.findObject(target);
                    return action != null && action.isFocused();
                });
    }

    private void focusDown(BySelector target) {
        for (int i = 0; i < 16; i++) {
            UiObject2 action = mDevice.findObject(target);
            if (action != null && action.isFocused()) return;
            mDevice.pressDPadDown();
            mDevice.waitForIdle();
        }
        waitForFocus(target);
    }
}
