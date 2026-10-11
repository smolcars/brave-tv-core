/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "third_party/blink/renderer/core/editing/ime/input_method_controller.h"

// Deletion dirties layout; the following visible-selection read requires it
// clean.
#define BRAVE_INPUT_METHOD_CONTROLLER_DELETE_SURROUNDING_TEXT_AFTER_DELETE \
  GetDocument().UpdateStyleAndLayout(DocumentUpdateReason::kEditing);

#include <third_party/blink/renderer/core/editing/ime/input_method_controller.cc>

#undef BRAVE_INPUT_METHOD_CONTROLLER_DELETE_SURROUNDING_TEXT_AFTER_DELETE
