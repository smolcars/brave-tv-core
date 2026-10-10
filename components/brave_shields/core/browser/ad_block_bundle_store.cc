// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. https://mozilla.org/MPL/2.0/.

#include "brave/components/brave_shields/core/browser/ad_block_bundle_store.h"

#include <utility>

#include "base/files/file_util.h"
#include "base/files/important_file_writer.h"

namespace brave_shields {

AdBlockBundleStore::AdBlockBundleStore(base::FilePath cache_file,
                                       std::array<uint8_t, 32> public_key,
                                       std::string catalog_sha256)
    : cache_file_(std::move(cache_file)),
      public_key_(public_key),
      catalog_sha256_(std::move(catalog_sha256)) {}

const AdBlockSignedBundle* AdBlockBundleStore::GetActive(base::Time now) {
  base::AutoLock lock(lock_);
  LoadActive(now);
  return active_ ? &*active_ : nullptr;
}

base::expected<AdBlockBundleStageResult, AdBlockBundleStageError>
AdBlockBundleStore::Stage(std::string_view bytes, base::Time now) {
  base::AutoLock lock(lock_);
  // Freeze the process snapshot before replacing anything on disk.
  LoadActive(now);
  auto bundle = VerifyAdBlockBundle(bytes, public_key_, catalog_sha256_, now,
                                    now - base::Days(90));
  if (!bundle.has_value()) {
    return base::unexpected(AdBlockBundleStageError::kInvalidBundle);
  }
  if (bundle->sequence < highest_sequence_ ||
      (bundle->sequence == highest_sequence_ &&
       bundle->digest != highest_digest_)) {
    return base::unexpected(AdBlockBundleStageError::kRollback);
  }
  if (bundle->sequence == highest_sequence_) {
    return AdBlockBundleStageResult::kUnchanged;
  }
  if (!base::CreateDirectory(cache_file_.DirName()) ||
      !base::ImportantFileWriter::WriteFileAtomically(cache_file_, bytes)) {
    return base::unexpected(AdBlockBundleStageError::kWrite);
  }
  highest_sequence_ = bundle->sequence;
  highest_digest_ = std::move(bundle->digest);
  return AdBlockBundleStageResult::kStaged;
}

AdBlockBundleStore::~AdBlockBundleStore() = default;

void AdBlockBundleStore::LoadActive(base::Time now) {
  if (loaded_) {
    return;
  }
  loaded_ = true;
  std::string bytes;
  if (!base::ReadFileToStringWithMaxSize(cache_file_, &bytes,
                                         kMaxSignedAdBlockBundleBytes)) {
    return;
  }
  auto bundle = VerifyAdBlockBundle(bytes, public_key_, catalog_sha256_, now,
                                    base::Time());
  if (!bundle.has_value()) {
    return;
  }
  highest_sequence_ = bundle->sequence;
  highest_digest_ = bundle->digest;
  active_ = std::move(*bundle);
}

}  // namespace brave_shields
