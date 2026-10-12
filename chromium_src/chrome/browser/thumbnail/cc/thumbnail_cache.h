// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#ifndef BRAVE_CHROMIUM_SRC_CHROME_BROWSER_THUMBNAIL_CC_THUMBNAIL_CACHE_H_
#define BRAVE_CHROMIUM_SRC_CHROME_BROWSER_THUMBNAIL_CC_THUMBNAIL_CACHE_H_

// UI sequence only. Observes JPEG/ETC1 compression and writes, not reads/deletes.
#define SetCaptureMinRequestTimeForTesting(...)    \
  SetCaptureMinRequestTimeForTesting(__VA_ARGS__); \
 private:                                          \
  size_t jpeg_compression_tasks_count_ = 0;        \
 public:                                           \
  bool HasPendingTasksForTesting() const

#include <chrome/browser/thumbnail/cc/thumbnail_cache.h>  // IWYU pragma: export

#undef SetCaptureMinRequestTimeForTesting

#endif  // BRAVE_CHROMIUM_SRC_CHROME_BROWSER_THUMBNAIL_CC_THUMBNAIL_CACHE_H_
