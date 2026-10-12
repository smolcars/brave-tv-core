// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

// Android may retain an ID after a fading video control becomes ignored.
#define BRAVE_POPULATE_ACCESSIBILITY_NODE_INFO \
  if (node->IsIgnored()) {                     \
    return false;                              \
  }
#define BRAVE_UPDATE_CACHED_ACCESSIBILITY_NODE_INFO \
  BRAVE_POPULATE_ACCESSIBILITY_NODE_INFO

#include <content/browser/accessibility/web_contents_accessibility_android.cc>  // IWYU pragma: export

#undef BRAVE_UPDATE_CACHED_ACCESSIBILITY_NODE_INFO
#undef BRAVE_POPULATE_ACCESSIBILITY_NODE_INFO
