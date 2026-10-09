/* Copyright (c) 2026 brave-tv contributors.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/components/p3a/p3a_service.h"

#include <optional>
#include <string>
#include <string_view>
#include <utility>

#include "base/time/time.h"
#include "brave/components/p3a/message_manager.h"
#include "brave/components/p3a/pref_names.h"
#include "components/prefs/pref_registry_simple.h"
#include "components/prefs/pref_service.h"
#include "services/network/public/cpp/shared_url_loader_factory.h"

namespace p3a {

// Keep callers compatible without creating analytics storage, observers or
// uploaders.
P3AService::P3AService(PrefService& local_state,
                       std::string channel,
                       base::Time first_run_time,
                       P3AConfig config)
    : local_state_(local_state), config_(std::move(config)) {
  local_state.SetBoolean(kP3AEnabled, false);
}

P3AService::~P3AService() = default;

void P3AService::RegisterPrefs(PrefRegistrySimple* registry, bool first_run) {
  MessageManager::RegisterPrefs(registry);
  registry->RegisterBooleanPref(kP3AEnabled, false);
  registry->RegisterBooleanPref(kP3ANoticeAcknowledged, first_run);
  registry->RegisterDictionaryPref(kCustomAttributesDictPref);
  registry->RegisterDictionaryPref(kDynamicMetricsDictPref);
  registry->RegisterDictionaryPref(kActivationDatesDictPref);
}

bool P3AService::IsP3AEnabled() const {
  return false;
}

void P3AService::InitCallbacks() {}
void P3AService::StartTeardown() {}
void P3AService::Init(
    scoped_refptr<network::SharedURLLoaderFactory> url_loader_factory,
    component_updater::ComponentUpdateService* cus) {}

void P3AService::RegisterDynamicMetric(const std::string& histogram_name,
                                       MetricLogType log_type,
                                       bool should_be_on_ui_thread) {}
void P3AService::RemoveDynamicMetric(const std::string& histogram_name) {}

base::CallbackListSubscription P3AService::RegisterRotationCallback(
    base::RepeatingCallback<void(MetricLogType)> callback) {
  return {};
}

base::CallbackListSubscription P3AService::RegisterMetricCycledCallback(
    base::RepeatingCallback<void(const std::string&)> callback) {
  return {};
}

void P3AService::SetDefaultBrowserMonitor(
    misc_metrics::DefaultBrowserMonitor* monitor) {}
void P3AService::OnDefaultBrowserStatusChanged(bool is_default) {}
void P3AService::OnHistogramChanged(std::string_view histogram_name,
                                    uint64_t name_hash,
                                    base::HistogramBase::Sample32 sample) {}
void P3AService::OnRotation(MetricLogType log_type) {}
void P3AService::OnMetricCycled(const std::string& histogram_name) {}
void P3AService::OnRemoteConfigLoaded() {}
void P3AService::OnCustomAttributeSet(
    std::string_view attribute_name,
    std::optional<std::string_view> attribute_value) {}

std::optional<MetricLogType> P3AService::GetDynamicMetricLogType(
    std::string_view histogram_name) const {
  return std::nullopt;
}

std::optional<MetricLogType> P3AService::GetLogTypeForHistogram(
    std::string_view histogram_name) const {
  return std::nullopt;
}

const MetricConfig* P3AService::GetMetricConfig(
    std::string_view histogram_name) const {
  return nullptr;
}

}  // namespace p3a
