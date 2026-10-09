/* Copyright (c) 2026 brave-tv contributors.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/components/p3a/p3a_service.h"

#include "base/memory/scoped_refptr.h"
#include "base/test/task_environment.h"
#include "base/time/time.h"
#include "brave/components/p3a/p3a_config.h"
#include "brave/components/p3a/pref_names.h"
#include "components/prefs/testing_pref_service.h"
#include "testing/gtest/include/gtest/gtest.h"

namespace p3a {

TEST(P3ADisabledAndroidTest, CannotEnableOrRegisterMetrics) {
  base::test::TaskEnvironment task_environment;
  TestingPrefServiceSimple prefs;
  P3AService::RegisterPrefs(prefs.registry(), false);
  prefs.SetBoolean(kP3AEnabled, true);
  auto service = base::MakeRefCounted<P3AService>(
      prefs, "stable", base::Time::Now(), P3AConfig());
  EXPECT_FALSE(prefs.GetBoolean(kP3AEnabled));
  prefs.SetBoolean(kP3AEnabled, true);
  EXPECT_FALSE(service->IsP3AEnabled());
  service->InitCallbacks();
  service->Init(nullptr, nullptr);
  service->OnCustomAttributeSet("test", "value");
  EXPECT_TRUE(prefs.GetDict(kCustomAttributesDictPref).empty());
  EXPECT_TRUE(prefs.GetDict(kDynamicMetricsDictPref).empty());
  EXPECT_EQ(nullptr, service->remote_config_manager());
  EXPECT_EQ(nullptr, service->GetMetricConfig("Brave.Test"));
  service->StartTeardown();
}

}  // namespace p3a
