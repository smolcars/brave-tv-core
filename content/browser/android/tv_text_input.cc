/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/content/public/browser/tv_text_input.h"

#include <utility>

#include "base/functional/callback.h"
#include "content/browser/renderer_host/render_widget_host_impl.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/web_contents.h"
#include "third_party/blink/public/mojom/input/input_handler.mojom.h"

namespace content {
bool AwaitTvTextInput(WebContents *contents, base::OnceClosure completion) {
  DCHECK_CURRENTLY_ON(BrowserThread::UI);
  if (!contents || !contents->GetFocusedFrame()) {
    return false;
  }
  auto *widget = RenderWidgetHostImpl::From(
      contents->GetFocusedFrame()->GetRenderWidgetHost());
  if (!widget) {
    return false;
  }
  // FrameWidgetInputHandler is associated with this pipe. Both enqueue input
  // on MainThreadEventQueue; PingMainThread follows those queued operations.
  widget->GetWidgetInputHandler()->PingMainThread(std::move(completion));
  return true;
}
} // namespace content
