/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/browser/ui/brave_browser_window.h"

#include "chrome/test/base/browser_with_test_window_test.h"
#include "testing/gtest/include/gtest/gtest.h"

using BraveBrowserWindowTest = BrowserWithTestWindowTest;

// TestBrowserWindow derives from BraveBrowserWindow but is not a BrowserView,
// so it stands in for other BrowserWindow implementations such as
// WebUIBrowserWindow.
TEST_F(BraveBrowserWindowTest, FromReturnsNullForNonBrowserView) {
  EXPECT_FALSE(BraveBrowserWindow::FromBrowser(browser()));
  EXPECT_FALSE(BraveBrowserWindow::From(window()));
  EXPECT_FALSE(BraveBrowserWindow::From(nullptr));
}
