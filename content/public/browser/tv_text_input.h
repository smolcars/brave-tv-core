/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#ifndef BRAVE_CONTENT_PUBLIC_BROWSER_TV_TEXT_INPUT_H_
#define BRAVE_CONTENT_PUBLIC_BROWSER_TV_TEXT_INPUT_H_

#include "base/functional/callback_forward.h"
#include "content/common/content_export.h"

namespace content {
class WebContents;

// UI thread only. Completes after prior native input tasks on the focused
// widget; callers must cancel stale field/document identities and bound their
// wait.
CONTENT_EXPORT bool AwaitTvTextInput(WebContents *contents,
                                     base::OnceClosure completion);
} // namespace content

#endif // BRAVE_CONTENT_PUBLIC_BROWSER_TV_TEXT_INPUT_H_
