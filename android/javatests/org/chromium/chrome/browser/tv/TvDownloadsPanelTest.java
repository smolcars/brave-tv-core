// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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

import org.chromium.base.Callback;
import org.chromium.base.ThreadUtils;
import org.chromium.base.test.util.CommandLineFlags;
import org.chromium.base.test.util.CriteriaHelper;
import org.chromium.base.test.util.DoNotBatch;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.flags.ChromeSwitches;
import org.chromium.chrome.test.ChromeJUnit4ClassRunner;
import org.chromium.chrome.test.ChromeTabbedActivityTestRule;
import org.chromium.components.offline_items_collection.ContentId;
import org.chromium.components.offline_items_collection.LegacyHelpers;
import org.chromium.components.offline_items_collection.OfflineContentProvider;
import org.chromium.components.offline_items_collection.OfflineItem;
import org.chromium.components.offline_items_collection.OfflineItemState;
import org.chromium.components.offline_items_collection.UpdateDelta;

import java.util.ArrayList;
import java.util.List;

/**
 * Native dialog/focus tests with a provider fixture; real download storage is tested separately.
 */
@RunWith(ChromeJUnit4ClassRunner.class)
@CommandLineFlags.Add({ChromeSwitches.DISABLE_FIRST_RUN_EXPERIENCE})
@DoNotBatch(reason = "Owns a native TV dialog and browser session.")
public class TvDownloadsPanelTest {
    @Rule
    public ChromeTabbedActivityTestRule mActivityTestRule = new ChromeTabbedActivityTestRule();

    private final List<OfflineItem> mItems = new ArrayList<>();
    private BraveActivity mActivity;
    private UiDevice mDevice;
    private OfflineContentProvider mProvider;
    private TvBrowserSession mSession;
    private TvDownloadsPanel mPanel;
    private int mHistoryRemovals;
    private Callback<ArrayList<OfflineItem>> mPendingQuery;
    private boolean mHoldQuery;

    @Before
    public void setUp() {
        mActivityTestRule.startMainActivityOnBlankPage();
        mActivity = (BraveActivity) (Object) mActivityTestRule.getActivity();
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        CriteriaHelper.pollInstrumentationThread(
                () -> mDevice.hasObject(By.text(mActivity.getString(R.string.brave_tv_controls))));
        mDevice.pressBack();
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        mProvider = mock(OfflineContentProvider.class);
        doAnswer(
                        invocation -> {
                            Callback<ArrayList<OfflineItem>> callback = invocation.getArgument(0);
                            if (mHoldQuery) mPendingQuery = callback;
                            else
                                mActivity
                                        .getWindow()
                                        .getDecorView()
                                        .post(() -> callback.onResult(new ArrayList<>(mItems)));
                            return null;
                        })
                .when(mProvider)
                .getAllItems(any());
        doAnswer(
                        invocation -> {
                            ContentId id = invocation.getArgument(0);
                            Callback<OfflineItem> callback = invocation.getArgument(1);
                            mActivity
                                    .getWindow()
                                    .getDecorView()
                                    .post(
                                            () ->
                                                    callback.onResult(
                                                            mItems.stream()
                                                                    .filter(
                                                                            item ->
                                                                                    item.id.equals(
                                                                                            id))
                                                                    .findFirst()
                                                                    .orElse(null)));
                            return null;
                        })
                .when(mProvider)
                .getItemById(any(), any());
        doAnswer(
                        invocation -> {
                            ContentId id = invocation.getArgument(0);
                            mItems.removeIf(item -> item.id.equals(id));
                            mPanel.onItemRemoved(id);
                            return null;
                        })
                .when(mProvider)
                .removeItem(any());
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    mSession =
                            new TvBrowserSession(
                                    mActivity,
                                    () -> {
                                        if (mPanel != null) mPanel.onBrowserStateChanged();
                                    });
                    mPanel =
                            new TvDownloadsPanel(
                                    mActivity,
                                    mSession,
                                    mProvider,
                                    item -> {
                                        mHistoryRemovals++;
                                        mItems.removeIf(current -> current.id.equals(item.id));
                                        mPanel.onItemRemoved(item.id);
                                    },
                                    () -> mPanel.destroy());
                });
    }

    @After
    public void tearDown() {
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    if (mPanel != null) mPanel.destroy();
                    if (mSession != null) mSession.destroy();
                });
    }

    private OfflineItem item(int index) {
        OfflineItem item = new OfflineItem();
        item.id = new ContentId(LegacyHelpers.LEGACY_DOWNLOAD_NAMESPACE, "tv-fixture-" + index);
        item.title = "TV file " + index;
        item.creationTimeMs = 1000 - index;
        item.receivedBytes = 123;
        item.state = OfflineItemState.COMPLETE;
        item.isOpenable = true;
        return item;
    }

    private BySelector label(int resource) {
        return By.desc(mActivity.getString(resource));
    }

    private void focus(BySelector selector) {
        CriteriaHelper.pollInstrumentationThread(
                () -> {
                    UiObject2 action = mDevice.findObject(selector);
                    return action != null && action.isFocused();
                });
    }

    private void click(BySelector selector) {
        CriteriaHelper.pollInstrumentationThread(() -> mDevice.hasObject(selector));
        UiObject2 action = mDevice.findObject(selector);
        assertNotNull(action);
        action.click();
    }

    private void showOne() {
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    mItems.add(item(0));
                    mPanel.show();
                });
        focus(By.descStartsWith("TV file 0\n"));
        mDevice.pressDPadCenter();
        focus(label(R.string.brave_tv_open_download));
    }

    private void confirm(int resource) {
        click(label(resource));
        focus(label(R.string.cancel));
        mDevice.pressDPadRight();
        focus(label(resource));
        mDevice.pressDPadLeft();
        focus(label(R.string.cancel));
        mDevice.pressDPadRight();
        focus(label(resource));
        mDevice.pressDPadCenter();
    }

    @Test
    @MediumTest
    public void pagingExcludesPrivateAndProgressKeepsFocus() {
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    for (int i = 0; i < 9; i++) mItems.add(item(i));
                    OfflineItem privateItem = item(10);
                    privateItem.isOffTheRecord = true;
                    privateItem.title = "Private file must stay hidden";
                    privateItem.creationTimeMs = 2000;
                    mItems.add(privateItem);
                    mPanel.show();
                });
        focus(By.descStartsWith("TV file 0\n"));
        assertFalse(mDevice.hasObject(By.descStartsWith("Private file")));
        assertFalse(mDevice.hasObject(By.descStartsWith("TV file 8\n")));
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    mItems.get(0).receivedBytes = 987;
                    mPanel.onItemUpdated(mItems.get(0), new UpdateDelta());
                });
        focus(By.descStartsWith("TV file 0\n"));
        click(label(R.string.brave_tv_more_downloads));
        focus(By.descStartsWith("TV file 8\n"));
        assertFalse(mDevice.hasObject(By.descStartsWith("TV file 0\n")));
        assertFalse(mDevice.hasObject(By.descStartsWith("Private file")));
    }

    @Test
    @MediumTest
    public void removalAndDeletionUseDifferentNativeOwners() {
        showOne();
        confirm(R.string.brave_tv_remove_download);
        CriteriaHelper.pollUiThread(() -> mHistoryRemovals == 1);
        verify(mProvider, never()).removeItem(any());
        ThreadUtils.runOnUiThreadBlocking(() -> mItems.add(item(1)));
        click(label(R.string.brave_tv_refresh_downloads));
        focus(By.descStartsWith("TV file 1\n"));
        mDevice.pressDPadCenter();
        focus(label(R.string.brave_tv_open_download));
        confirm(R.string.brave_tv_delete_download);
        CriteriaHelper.pollInstrumentationThread(
                () ->
                        mDevice.hasObject(
                                By.text(mActivity.getString(R.string.brave_tv_empty_downloads))));
        verify(mProvider).removeItem(eq(item(1).id));
        ThreadUtils.runOnUiThreadBlocking(() -> assertEquals(1, mHistoryRemovals));
    }

    @Test
    @MediumTest
    public void cancelConfirmationAndPrivateReplacementRejectActions() {
        showOne();
        click(label(R.string.brave_tv_delete_download));
        focus(label(R.string.cancel));
        mDevice.pressDPadCenter();
        focus(label(R.string.brave_tv_open_download));
        verify(mProvider, never()).removeItem(any());
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    assertEquals(1, mItems.size());
                    mItems.get(0).isOffTheRecord = true;
                });
        mDevice.pressDPadCenter();
        focus(label(R.string.brave_tv_refresh_downloads));
        verify(mProvider, never()).openItem(any(), any());
        verify(mProvider, never()).validateDangerousDownload(any());
    }

    @Test
    @MediumTest
    public void dangerousUpdateDisablesOpenAndRestoresSafeFocus() {
        showOne();
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    mItems.get(0).isDangerous = true;
                    mPanel.onItemUpdated(mItems.get(0), new UpdateDelta());
                });
        focus(label(R.string.brave_tv_downloads));
        UiObject2 open = mDevice.findObject(label(R.string.brave_tv_open_download));
        assertNotNull(open);
        assertFalse(open.isEnabled());
        verify(mProvider, never()).openItem(any(), any());
        verify(mProvider, never()).validateDangerousDownload(any());
    }

    @Test
    @MediumTest
    public void navigationDisposesObserverAndRejectsLateQuery() {
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    mHoldQuery = true;
                    mPanel.show();
                    assertNotNull(mPendingQuery);
                });
        focus(label(R.string.brave_tv_controls));
        mActivityTestRule.loadUrl("data:text/html,<h1>Downloads%20owner%20changed</h1>");
        CriteriaHelper.pollUiThread(mActivity::hasWindowFocus);
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    mPendingQuery.onResult(new ArrayList<>(List.of(item(0))));
                    mPanel.destroy();
                });
        verify(mProvider).removeObserver(eq(mPanel));
        assertFalse(mDevice.hasObject(By.descStartsWith("TV file 0\n")));
        verify(mProvider, never()).openItem(any(), any());
    }
}
