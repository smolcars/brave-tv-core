// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#ifndef BRAVE_CHROMIUM_SRC_CHROME_BROWSER_ANDROID_COMPOSITOR_TAB_CONTENT_MANAGER_H_
#define BRAVE_CHROMIUM_SRC_CHROME_BROWSER_ANDROID_COMPOSITOR_TAB_CONTENT_MANAGER_H_

// ETC1 compression/writes have no per-tab capture tracker.
#define IsTabCaptureInFlightForTesting(...)                        \
  IsTabCaptureInFlightForTesting_ChromiumImpl(__VA_ARGS__);        \
  bool IsTabCaptureInFlightForTesting(JNIEnv* env, int32_t id) {   \
    return IsTabCaptureInFlightForTesting_ChromiumImpl(env, id) || \
           thumbnail_cache_.HasPendingTasksForTesting();           \
  }

#include <chrome/browser/android/compositor/tab_content_manager.h>  // IWYU pragma: export

#undef IsTabCaptureInFlightForTesting

#endif  // BRAVE_CHROMIUM_SRC_CHROME_BROWSER_ANDROID_COMPOSITOR_TAB_CONTENT_MANAGER_H_
