// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. https://mozilla.org/MPL/2.0/.

#ifndef BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_BUNDLE_STORE_H_
#define BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_BUNDLE_STORE_H_

#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>

#include "base/files/file_path.h"
#include "base/memory/ref_counted.h"
#include "base/synchronization/lock.h"
#include "base/thread_annotations.h"
#include "base/time/time.h"
#include "base/types/expected.h"
#include "brave/components/brave_shields/core/browser/ad_block_signed_bundle.h"

namespace brave_shields {

enum class AdBlockBundleStageResult { kStaged, kUnchanged };
enum class AdBlockBundleStageError { kInvalidBundle, kRollback, kWrite };

// Worker-only access. One instance holds a fixed snapshot until browser
// restart.
class AdBlockBundleStore
    : public base::RefCountedThreadSafe<AdBlockBundleStore> {
 public:
  AdBlockBundleStore(base::FilePath cache_file,
                     std::array<uint8_t, 32> public_key,
                     std::string catalog_sha256);

  // Null means packaged fallback. A non-null result lives as long as this
  // store.
  const AdBlockSignedBundle* GetActive(base::Time now);
  base::expected<AdBlockBundleStageResult, AdBlockBundleStageError> Stage(
      std::string_view bytes,
      base::Time now);

 private:
  friend class base::RefCountedThreadSafe<AdBlockBundleStore>;

  ~AdBlockBundleStore();
  void LoadActive(base::Time now) EXCLUSIVE_LOCKS_REQUIRED(lock_);

  const base::FilePath cache_file_;
  const std::array<uint8_t, 32> public_key_;
  const std::string catalog_sha256_;
  base::Lock lock_;
  bool loaded_ GUARDED_BY(lock_) = false;
  std::optional<AdBlockSignedBundle> active_ GUARDED_BY(lock_);
  int highest_sequence_ GUARDED_BY(lock_) = 0;
  std::string highest_digest_ GUARDED_BY(lock_);
};

}  // namespace brave_shields

#endif  // BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_BUNDLE_STORE_H_
