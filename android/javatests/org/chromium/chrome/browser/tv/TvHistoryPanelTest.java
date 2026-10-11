// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

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
import org.chromium.base.test.util.CallbackHelper;
import org.chromium.base.test.util.CommandLineFlags;
import org.chromium.base.test.util.CriteriaHelper;
import org.chromium.base.test.util.DoNotBatch;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.flags.ChromeSwitches;
import org.chromium.chrome.browser.history.BrowsingHistoryBridge;
import org.chromium.chrome.browser.history.HistoryItem;
import org.chromium.chrome.browser.history.HistoryProvider;
import org.chromium.chrome.browser.preferences.Pref;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.test.ChromeJUnit4ClassRunner;
import org.chromium.chrome.test.ChromeTabbedActivityTestRule;
import org.chromium.components.user_prefs.UserPrefs;

import java.util.ArrayList;
import java.util.List;

/** Real history continuation, remote focus and native policy with owned visits only. */
@RunWith(ChromeJUnit4ClassRunner.class)
@CommandLineFlags.Add({ChromeSwitches.DISABLE_FIRST_RUN_EXPERIENCE})
@DoNotBatch(reason = "Creates and removes only uniquely identified native history visits.")
public class TvHistoryPanelTest {
    private static final int VISIT_COUNT = 160;
    private static final int PAGE_COUNT = VISIT_COUNT / TvHistoryPanel.PAGE_SIZE;

    @Rule
    public ChromeTabbedActivityTestRule mActivityTestRule = new ChromeTabbedActivityTestRule();

    private BraveActivity mActivity;
    private UiDevice mDevice;
    private Tab mTab;
    private TvHistoryPanel mPanel;
    private String mOwnedPrefix;

    @Before
    public void setUp() {
        mActivityTestRule.startMainActivityOnBlankPage();
        mActivity = (BraveActivity) (Object) mActivityTestRule.getActivity();
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_controls))));
        mDevice.pressBack();
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        mTab = ThreadUtils.runOnUiThreadBlocking(mActivity::getActivityTab);
    }

    @After
    public void tearDown() throws Exception {
        if (mPanel != null) ThreadUtils.runOnUiThreadBlocking(mPanel::destroy);
        if (mOwnedPrefix != null) remove(queryOwnedVisits());
    }

    @Test
    @MediumTest
    public void nativeBatchPagingFocusRemovalAndPolicy() throws Exception {
        String fixture = InstrumentationRegistry.getArguments().getString("tv_history_fixture_url");
        assertNotNull("Serve the owned history.html fixture and pass its URL", fixture);
        assertEquals("http://10.0.2.2:18088/history.html", fixture);
        mOwnedPrefix = fixture + "?run=" + System.nanoTime() + "&page=";
        for (int i = 1; i <= VISIT_COUNT; i++) {
            mActivityTestRule.loadUrl(mOwnedPrefix + i);
            String title = "TV history pagination " + i;
            CriteriaHelper.pollUiThread(() -> mTab.getTitle().equals(title));
        }
        List<HistoryItem> visits = queryOwnedVisits();
        assertEquals(VISIT_COUNT, visits.size());
        showHistory();
        waitForFocus(row(VISIT_COUNT));
        for (int page = 1; page < PAGE_COUNT; page++) {
            focusMoreHistory();
            mDevice.pressDPadCenter();
            waitForFocus(row(VISIT_COUNT - page * TvHistoryPanel.PAGE_SIZE));
        }
        mDevice.pressDPadCenter();
        waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_open_history)));
        mDevice.pressBack();
        waitForFocus(row(TvHistoryPanel.PAGE_SIZE));
        for (int page = PAGE_COUNT - 1; page >= PAGE_COUNT - 3; page--) {
            focusDown(By.desc(mActivity.getString(R.string.brave_tv_previous_history)));
            mDevice.pressDPadCenter();
            waitForFocus(row(VISIT_COUNT - (page - 1) * TvHistoryPanel.PAGE_SIZE));
        }
        int selected = VISIT_COUNT - (PAGE_COUNT - 4) * TvHistoryPanel.PAGE_SIZE - 1;
        mDevice.pressDPadDown();
        waitForFocus(row(selected));
        remove(
                List.of(
                        visits.stream()
                                .filter(i -> i.getTitle().endsWith(" " + (selected + 2)))
                                .findFirst()
                                .get()));
        CriteriaHelper.pollInstrumentationThread(() -> !mDevice.hasObject(row(selected + 1)));
        waitForFocus(row(selected));
        boolean allowed =
                ThreadUtils.runOnUiThreadBlocking(
                        () ->
                                UserPrefs.get(mTab.getProfile())
                                        .getBoolean(Pref.ALLOW_DELETING_BROWSER_HISTORY));
        try {
            ThreadUtils.runOnUiThreadBlocking(
                    () ->
                            UserPrefs.get(mTab.getProfile())
                                    .setBoolean(Pref.ALLOW_DELETING_BROWSER_HISTORY, false));
            mDevice.pressDPadCenter();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_open_history)));
            assertFalse(
                    mDevice.findObject(
                                    By.desc(mActivity.getString(R.string.brave_tv_remove_history)))
                            .isEnabled());
            mDevice.pressBack();
            waitForFocus(row(selected));
            ThreadUtils.runOnUiThreadBlocking(
                    () ->
                            UserPrefs.get(mTab.getProfile())
                                    .setBoolean(Pref.ALLOW_DELETING_BROWSER_HISTORY, true));
            mDevice.pressDPadCenter();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_open_history)));
            mDevice.pressDPadRight();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_remove_history)));
            mDevice.pressDPadCenter();
            waitForFocus(By.desc(mActivity.getString(R.string.cancel)));
            mDevice.pressDPadCenter();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_open_history)));
            assertEquals(VISIT_COUNT - 1, queryOwnedVisits().size());
            mDevice.pressDPadRight();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_remove_history)));
            mDevice.pressDPadCenter();
            waitForFocus(By.desc(mActivity.getString(R.string.cancel)));
            mDevice.pressDPadRight();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_remove_history)));
            mDevice.pressDPadCenter();
            waitForFocus(row(selected - 1));
            assertEquals(VISIT_COUNT - 2, queryOwnedVisits().size());
            mDevice.pressDPadCenter();
            waitForFocus(By.desc(mActivity.getString(R.string.brave_tv_open_history)));
            mDevice.pressDPadCenter();
            CriteriaHelper.pollUiThread(
                    () -> mTab.getUrl().getSpec().equals(mOwnedPrefix + (selected - 1)));
            CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        } finally {
            ThreadUtils.runOnUiThreadBlocking(
                    () ->
                            UserPrefs.get(mTab.getProfile())
                                    .setBoolean(Pref.ALLOW_DELETING_BROWSER_HISTORY, allowed));
        }
    }

    private void showHistory() {
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    mPanel = new TvHistoryPanel(mActivity, mTab, mActivity::showTvBrowserControls);
                    mPanel.show();
                });
    }

    private BySelector row(int number) {
        return By.descStartsWith("TV history pagination " + number + "\n");
    }

    private void waitForFocus(BySelector target) {
        CriteriaHelper.pollInstrumentationThread(
                () -> {
                    UiObject2 item = mDevice.findObject(target);
                    return item != null && item.isFocused();
                });
    }

    private void focusMoreHistory() {
        BySelector more = By.desc(mActivity.getString(R.string.brave_tv_more_history));
        BySelector previous = By.desc(mActivity.getString(R.string.brave_tv_previous_history));
        for (int i = 0; i < 12; i++) {
            if (mDevice.hasObject(more.focused(true))) return;
            if (mDevice.hasObject(previous.focused(true))) {
                mDevice.pressDPadRight();
                waitForFocus(more);
                return;
            }
            mDevice.pressDPadDown();
            mDevice.waitForIdle();
        }
        waitForFocus(more);
    }

    private void focusDown(BySelector target) {
        for (int i = 0; i < 12; i++) {
            UiObject2 item = mDevice.findObject(target);
            if (item != null && item.isFocused()) return;
            mDevice.pressDPadDown();
            mDevice.waitForIdle();
        }
        waitForFocus(target);
    }

    private List<HistoryItem> queryOwnedVisits() throws Exception {
        List<HistoryItem> visits = new ArrayList<>();
        CallbackHelper done = new CallbackHelper();
        BrowsingHistoryBridge history =
                ThreadUtils.runOnUiThreadBlocking(
                        () -> new BrowsingHistoryBridge(mTab.getProfile()));
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    history.setObserver(
                            new HistoryProvider.BrowsingHistoryObserver() {
                                @Override
                                public void onQueryHistoryComplete(
                                        List<HistoryItem> items, boolean more) {
                                    for (HistoryItem item : items) {
                                        if (item.getUrl().getSpec().startsWith(mOwnedPrefix))
                                            visits.add(item);
                                    }
                                    if (more) history.queryHistoryContinuation();
                                    else done.notifyCalled();
                                }

                                @Override
                                public void onHistoryDeleted() {}

                                @Override
                                public void hasOtherFormsOfBrowsingData(boolean hasOtherForms) {}

                                @Override
                                public void onQueryAppsComplete(List<String> items) {}
                            });
                    history.queryHistory("", null);
                });
        try {
            done.waitForCallback(0);
            return visits;
        } finally {
            ThreadUtils.runOnUiThreadBlocking(history::destroy);
        }
    }

    private void remove(List<HistoryItem> items) throws Exception {
        if (items.isEmpty()) return;
        CallbackHelper done = new CallbackHelper();
        boolean[] failed = {false};
        BrowsingHistoryBridge history =
                ThreadUtils.runOnUiThreadBlocking(
                        () ->
                                new BrowsingHistoryBridge(mTab.getProfile()) {
                                    @Override
                                    public void onRemoveComplete() {
                                        super.onRemoveComplete();
                                        done.notifyCalled();
                                    }

                                    @Override
                                    public void onRemoveFailed() {
                                        super.onRemoveFailed();
                                        failed[0] = true;
                                        done.notifyCalled();
                                    }
                                });
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    for (HistoryItem item : items) history.markItemForRemoval(item);
                    history.removeItems();
                });
        try {
            done.waitForCallback(0);
            assertFalse("Owned native history removal failed", failed[0]);
        } finally {
            ThreadUtils.runOnUiThreadBlocking(history::destroy);
        }
    }
}
