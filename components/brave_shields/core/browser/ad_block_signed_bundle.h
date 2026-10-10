// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. https://mozilla.org/MPL/2.0/.

#ifndef BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_SIGNED_BUNDLE_H_
#define BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_SIGNED_BUNDLE_H_

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>

#include "base/containers/flat_map.h"
#include "base/containers/span.h"
#include "base/time/time.h"
#include "base/types/expected.h"

namespace brave_shields {

inline constexpr size_t kMaxSignedAdBlockBundleBytes = 24 * 1024 * 1024;

enum class AdBlockBundleError {
  kEnvelope,
  kSignature,
  kSchema,
  kCompatibility,
  kPublicationTime,
  kProvenance,
};

struct AdBlockSignedBundle {
  int sequence = 0;
  base::Time published;
  std::string digest;
  std::string resources;
  base::flat_map<std::string, std::string> filters;
};

// Worker-only parsing. A null earliest_publication permits old cached bundles.
// The caller separately enforces monotonic sequence against active/staged data.
base::expected<AdBlockSignedBundle, AdBlockBundleError> VerifyAdBlockBundle(
    std::string_view bytes,
    base::span<const uint8_t, 32> public_key,
    std::string_view catalog_sha256,
    base::Time now,
    base::Time earliest_publication);

}  // namespace brave_shields

#endif  // BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_SIGNED_BUNDLE_H_
