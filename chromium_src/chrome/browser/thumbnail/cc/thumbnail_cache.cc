// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#define BRAVE_THUMBNAIL_CACHE_SAVE_AS_JPEG ++jpeg_compression_tasks_count_;

#define BRAVE_THUMBNAIL_CACHE_WRITE_JPEG_THUMBNAIL_IF_NECESSARY \
  --jpeg_compression_tasks_count_;                              \
  if (!thumbnail_meta_data_.contains(tab_id)) {                 \
    if (tracker) {                                              \
      tracker->MarkJpegFailed();                                \
    }                                                           \
    return;                                                     \
  }

#define BRAVE_THUMBNAIL_CACHE_WRITE_ETC1_THUMBNAIL_IF_NECESSARY \
  if (!thumbnail_meta_data_.contains(tab_id)) {                 \
    return;                                                     \
  }

#include <chrome/browser/thumbnail/cc/thumbnail_cache.cc>

#undef BRAVE_THUMBNAIL_CACHE_WRITE_ETC1_THUMBNAIL_IF_NECESSARY
#undef BRAVE_THUMBNAIL_CACHE_WRITE_JPEG_THUMBNAIL_IF_NECESSARY
#undef BRAVE_THUMBNAIL_CACHE_SAVE_AS_JPEG

namespace thumbnail {

bool ThumbnailCache::HasPendingTasksForTesting() const {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  return jpeg_compression_tasks_count_ != 0 || compression_tasks_count_ != 0 ||
         write_tasks_count_ != 0;
}

}  // namespace thumbnail
