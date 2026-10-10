// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. https://mozilla.org/MPL/2.0/.

#ifndef BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_BUNDLE_UPDATER_H_
#define BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_BUNDLE_UPDATER_H_

#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "base/functional/callback.h"
#include "base/memory/raw_ptr.h"
#include "base/memory/scoped_refptr.h"
#include "base/memory/weak_ptr.h"
#include "base/sequence_checker.h"
#include "base/time/time.h"
#include "base/timer/timer.h"
#include "base/types/expected.h"
#include "brave/components/brave_shields/core/browser/ad_block_bundle_store.h"

class GURL;
class PrefRegistrySimple;
class PrefService;
namespace base {
class SequencedTaskRunner;
}
namespace net {
struct RedirectInfo;
}
namespace network {
class SharedURLLoaderFactory;
class SimpleURLLoader;
namespace mojom {
class URLResponseHead;
}
}  // namespace network

namespace brave_shields {

inline constexpr char kAdBlockBundleUpdateUrl[] =
    "https://github.com/smolcars/brave-tv/releases/download/filters-current/"
    "filters.bundle";

enum class AdBlockUpdateResult {
  kIdle,
  kChecking,
  kCurrent,
  kStaged,
  kDownloadFailed,
  kRejected,
  kSaveFailed,
};

struct AdBlockUpdateStatus {
  AdBlockUpdateResult result = AdBlockUpdateResult::kIdle;
  bool restart_required = false;
  int active_sequence = 0;  // Zero means packaged data.
  base::Time active_published;
  base::Time last_attempt;
};

// Owns daily delivery; the store keeps the active snapshot fixed until restart.
class AdBlockBundleUpdater {
 public:
  static void RegisterPrefs(PrefRegistrySimple* registry);

  AdBlockBundleUpdater(scoped_refptr<AdBlockBundleStore> store,
                       scoped_refptr<network::SharedURLLoaderFactory> factory,
                       PrefService* prefs);
  ~AdBlockBundleUpdater();
  AdBlockBundleUpdater(const AdBlockBundleUpdater&) = delete;
  AdBlockBundleUpdater& operator=(const AdBlockBundleUpdater&) = delete;

  void GetStatus(base::OnceCallback<void(AdBlockUpdateStatus)> callback);
  void CheckForUpdate(base::OnceClosure callback);

 private:
  void ScheduleAutomaticCheck();
  void CheckAutomatically();
  void OnRedirect(const GURL& previous_url,
                  const net::RedirectInfo& redirect,
                  const network::mojom::URLResponseHead& head,
                  std::vector<std::string>* removed_headers);
  void OnDownloaded(std::optional<std::string> body);
  void OnStaged(
      base::expected<AdBlockBundleStageResult, AdBlockBundleStageError> result);
  void OnStatusLoaded(base::OnceCallback<void(AdBlockUpdateStatus)> callback,
                      AdBlockUpdateStatus status);
  void Finish(AdBlockUpdateResult result);

  const scoped_refptr<AdBlockBundleStore> store_;
  const scoped_refptr<network::SharedURLLoaderFactory> factory_;
  const raw_ptr<PrefService> prefs_;
  const scoped_refptr<base::SequencedTaskRunner> worker_;
  std::unique_ptr<network::SimpleURLLoader> loader_;
  base::OneShotTimer timer_;
  AdBlockUpdateResult result_ = AdBlockUpdateResult::kIdle;
  bool restart_required_ = false;
  std::vector<base::OnceClosure> callbacks_;
  SEQUENCE_CHECKER(sequence_checker_);
  base::WeakPtrFactory<AdBlockBundleUpdater> weak_factory_{this};
};

}  // namespace brave_shields
#endif  // BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_BUNDLE_UPDATER_H_
