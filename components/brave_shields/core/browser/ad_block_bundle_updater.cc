// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. https://mozilla.org/MPL/2.0/.

#include "brave/components/brave_shields/core/browser/ad_block_bundle_updater.h"

#include <algorithm>
#include <utility>

#include "base/check.h"
#include "base/functional/bind.h"
#include "base/functional/callback_helpers.h"
#include "base/location.h"
#include "base/task/sequenced_task_runner.h"
#include "base/task/thread_pool.h"
#include "brave/components/brave_shields/core/browser/ad_block_signed_bundle.h"
#include "components/prefs/pref_registry_simple.h"
#include "components/prefs/pref_service.h"
#include "net/base/load_flags.h"
#include "net/traffic_annotation/network_traffic_annotation.h"
#include "net/url_request/redirect_info.h"
#include "services/network/public/cpp/resource_request.h"
#include "services/network/public/cpp/shared_url_loader_factory.h"
#include "services/network/public/cpp/simple_url_loader.h"
#include "services/network/public/mojom/fetch_api.mojom-shared.h"
#include "url/gurl.h"
#include "url/url_constants.h"

namespace brave_shields {
namespace {
constexpr char kLastAttempt[] = "brave.tv.adblock_update_last_attempt";
constexpr auto kUpdateAnnotation =
    net::DefineNetworkTrafficAnnotation("tv_browser_signed_filter_update", R"(
      semantics {
        sender: "TV Browser filter updater"
        description: "Downloads the fork's signed public ad-blocking snapshot."
        trigger: "At most once daily automatically, or a manual settings action."
        data: "A fixed public asset URL. No browsing data or identifiers."
        destination: OTHER
        destination_other: "GitHub release hosting for smolcars/brave-tv"
      }
      policy {
        cookies_allowed: NO
        setting: "Required filter maintenance while independent updates are enabled."
        policy_exception_justification: "No enterprise policy implemented."
      })");
}  // namespace

// static
void AdBlockBundleUpdater::RegisterPrefs(PrefRegistrySimple* registry) {
  registry->RegisterTimePref(kLastAttempt, base::Time());
}

AdBlockBundleUpdater::AdBlockBundleUpdater(
    scoped_refptr<AdBlockBundleStore> store,
    scoped_refptr<network::SharedURLLoaderFactory> factory,
    PrefService* prefs)
    : store_(std::move(store)),
      factory_(std::move(factory)),
      prefs_(prefs),
      worker_(base::ThreadPool::CreateSequencedTaskRunner(
          {base::MayBlock(), base::TaskPriority::USER_VISIBLE,
           base::TaskShutdownBehavior::SKIP_ON_SHUTDOWN})) {
  CHECK(store_);
  CHECK(factory_);
  CHECK(prefs_);
  ScheduleAutomaticCheck();
}

AdBlockBundleUpdater::~AdBlockBundleUpdater() = default;

void AdBlockBundleUpdater::ScheduleAutomaticCheck() {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  auto remaining =
      prefs_->GetTime(kLastAttempt) + base::Days(1) - base::Time::Now();
  auto delay = std::clamp(remaining, base::Seconds(30), base::Days(1));
  timer_.Start(FROM_HERE, delay,
               base::BindOnce(&AdBlockBundleUpdater::CheckAutomatically,
                              base::Unretained(this)));
}

void AdBlockBundleUpdater::CheckAutomatically() {
  CheckForUpdate(base::DoNothing());
}

void AdBlockBundleUpdater::CheckForUpdate(base::OnceClosure callback) {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  callbacks_.push_back(std::move(callback));
  if (result_ == AdBlockUpdateResult::kChecking) {
    return;
  }
  result_ = AdBlockUpdateResult::kChecking;
  prefs_->SetTime(kLastAttempt, base::Time::Now());
  ScheduleAutomaticCheck();
  auto request = std::make_unique<network::ResourceRequest>();
  request->url = GURL(kAdBlockBundleUpdateUrl);
  request->credentials_mode = network::mojom::CredentialsMode::kOmit;
  request->load_flags = net::LOAD_BYPASS_CACHE | net::LOAD_DISABLE_CACHE;
  request->do_not_prompt_for_login = true;
  loader_ =
      network::SimpleURLLoader::Create(std::move(request), kUpdateAnnotation);
  loader_->SetTimeoutDuration(base::Seconds(60));
  loader_->SetOnRedirectCallback(base::BindRepeating(
      &AdBlockBundleUpdater::OnRedirect, weak_factory_.GetWeakPtr()));
  loader_->DownloadToString(factory_.get(),
                            base::BindOnce(&AdBlockBundleUpdater::OnDownloaded,
                                           weak_factory_.GetWeakPtr()),
                            kMaxSignedAdBlockBundleBytes);
}

void AdBlockBundleUpdater::OnRedirect(
    const GURL& previous_url,
    const net::RedirectInfo& redirect,
    const network::mojom::URLResponseHead& head,
    std::vector<std::string>* removed_headers) {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  if (!redirect.new_url.SchemeIs(url::kHttpsScheme)) {
    Finish(AdBlockUpdateResult::kDownloadFailed);
  }
}

void AdBlockBundleUpdater::OnDownloaded(std::optional<std::string> body) {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  loader_.reset();
  if (!body) {
    Finish(AdBlockUpdateResult::kDownloadFailed);
    return;
  }
  worker_->PostTaskAndReplyWithResult(
      FROM_HERE,
      base::BindOnce(
          [](scoped_refptr<AdBlockBundleStore> store, std::string bytes) {
            return store->Stage(bytes, base::Time::Now());
          },
          store_, std::move(*body)),
      base::BindOnce(&AdBlockBundleUpdater::OnStaged,
                     weak_factory_.GetWeakPtr()));
}

void AdBlockBundleUpdater::OnStaged(
    base::expected<AdBlockBundleStageResult, AdBlockBundleStageError> result) {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  if (!result.has_value()) {
    Finish(result.error() == AdBlockBundleStageError::kWrite
               ? AdBlockUpdateResult::kSaveFailed
               : AdBlockUpdateResult::kRejected);
    return;
  }
  if (*result == AdBlockBundleStageResult::kStaged) {
    restart_required_ = true;
    Finish(AdBlockUpdateResult::kStaged);
  } else {
    Finish(AdBlockUpdateResult::kCurrent);
  }
}

void AdBlockBundleUpdater::Finish(AdBlockUpdateResult result) {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  loader_.reset();
  result_ = result;
  auto callbacks = std::exchange(callbacks_, {});
  for (auto& callback : callbacks) {
    std::move(callback).Run();
  }
}

void AdBlockBundleUpdater::GetStatus(
    base::OnceCallback<void(AdBlockUpdateStatus)> callback) {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  worker_->PostTaskAndReplyWithResult(
      FROM_HERE,
      base::BindOnce(
          [](scoped_refptr<AdBlockBundleStore> store) {
            AdBlockUpdateStatus status;
            if (const auto* active = store->GetActive(base::Time::Now())) {
              status.active_sequence = active->sequence;
              status.active_published = active->published;
            }
            return status;
          },
          store_),
      base::BindOnce(&AdBlockBundleUpdater::OnStatusLoaded,
                     weak_factory_.GetWeakPtr(), std::move(callback)));
}

void AdBlockBundleUpdater::OnStatusLoaded(
    base::OnceCallback<void(AdBlockUpdateStatus)> callback,
    AdBlockUpdateStatus status) {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  status.result = result_;
  status.restart_required = restart_required_;
  status.last_attempt = prefs_->GetTime(kLastAttempt);
  std::move(callback).Run(status);
}

}  // namespace brave_shields
