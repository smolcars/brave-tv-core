/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include <jni.h>

#include "base/android/scoped_java_ref.h"
#include "base/functional/bind.h"
#include "brave/content/public/browser/tv_text_input.h"
#include "content/public/browser/web_contents.h"
#include "third_party/jni_zero/common_apis.h"

#include "chrome/android/chrome_jni_headers/TvTextInput_jni.h"

static bool JNI_TvTextInput_AwaitInput(
    JNIEnv* env,
    const jni_zero::JavaRef<jobject>& contents,
    const jni_zero::JavaRef<jobject>& completion) {
  return content::AwaitTvTextInput(
      content::WebContents::FromJavaWebContents(contents),
      base::BindOnce(&jni_zero::RunRunnable,
                     base::android::ScopedJavaGlobalRef<jobject>(completion)));
}

DEFINE_JNI(TvTextInput)
