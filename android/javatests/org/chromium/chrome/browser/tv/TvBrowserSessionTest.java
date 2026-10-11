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

import android.view.View;

import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.chromium.base.ThreadUtils;
import org.chromium.base.test.util.CommandLineFlags;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.flags.ChromeSwitches;
import org.chromium.chrome.test.ChromeJUnit4ClassRunner;
import org.chromium.chrome.test.ChromeTabbedActivityTestRule;

/** Native viewport changes must cancel coordinate input without invalidating address input. */
@RunWith(ChromeJUnit4ClassRunner.class)
@CommandLineFlags.Add({ChromeSwitches.DISABLE_FIRST_RUN_EXPERIENCE})
public class TvBrowserSessionTest {
    @Rule
    public ChromeTabbedActivityTestRule mActivityTestRule = new ChromeTabbedActivityTestRule();

    private BraveActivity mActivity;

    @Before
    public void setUp() {
        mActivityTestRule.startMainActivityOnBlankPage();
        // The bytecode adapter inserts BraveActivity into the tabbed activity's hierarchy.
        mActivity = (BraveActivity) (Object) mActivityTestRule.getActivity();
    }

    @Test
    @MediumTest
    public void viewportResizeKeepsDocumentButCancelsCoordinates() {
        ThreadUtils.runOnUiThreadBlocking(
                () -> {
                    TvBrowserSession session = new TvBrowserSession(mActivity, () -> {});
                    View viewport = mActivity.getActivityTab().getContentView();
                    assertNotNull(viewport);
                    int left = viewport.getLeft();
                    int top = viewport.getTop();
                    int right = viewport.getRight();
                    int bottom = viewport.getBottom();
                    try {
                        assertTrue(bottom - top > 100);
                        TvBrowserSession.Context context = session.capture();
                        assertTrue(session.isCurrent(context));
                        viewport.layout(left, top, right, bottom - 100);
                        assertFalse(session.isCurrent(context));
                        assertTrue(session.isCurrentDocument(context));
                        viewport.layout(left, top, right, bottom);
                        assertFalse(session.isCurrent(context));
                        assertTrue(session.isCurrentDocument(context));
                        session.invalidate();
                        assertFalse(session.isCurrentDocument(context));
                    } finally {
                        viewport.layout(left, top, right, bottom);
                        session.destroy();
                    }
                });
    }

    @Test
    @MediumTest
    public void sameUrlNavigationInvalidatesDocument() {
        String url = "data:text/html,TV%20session%20lease%20fixture";
        mActivityTestRule.loadUrl(url);
        TvBrowserSession session =
                ThreadUtils.runOnUiThreadBlocking(() -> new TvBrowserSession(mActivity, () -> {}));
        try {
            TvBrowserSession.Context context = ThreadUtils.runOnUiThreadBlocking(session::capture);
            mActivityTestRule.loadUrl(url);
            ThreadUtils.runOnUiThreadBlocking(
                    () -> {
                        TvBrowserSession.Context current = session.capture();
                        assertSame(context.tab, current.tab);
                        assertSame(context.contents, current.contents);
                        assertEquals(context.url, current.url);
                        assertFalse(session.isCurrentDocument(context));
                        assertFalse(session.isCurrent(context));
                    });
        } finally {
            ThreadUtils.runOnUiThreadBlocking(session::destroy);
        }
    }
}
