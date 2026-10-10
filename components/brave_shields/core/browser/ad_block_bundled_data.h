// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. https://mozilla.org/MPL/2.0/.

#ifndef BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_BUNDLED_DATA_H_
#define BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_BUNDLED_DATA_H_

#include <string>
#include <string_view>

#include "base/memory/scoped_refptr.h"
#include "brave/components/brave_shields/core/browser/ad_block_bundle_store.h"

namespace brave_shields {

// Workers read one process snapshot, falling back to the APK if no cache is
// valid.
bool HasBundledAdBlockData();
bool HasBundledAdBlockFilters(std::string_view component_id);
std::string GetBundledAdBlockVersion(
    scoped_refptr<AdBlockBundleStore> store = nullptr);
std::string_view GetBundledAdBlockCatalogHash();
std::string LoadBundledAdBlockCatalog();
std::string LoadBundledAdBlockResources(
    scoped_refptr<AdBlockBundleStore> store = nullptr);
std::string LoadBundledAdBlockFilters(
    std::string_view component_id,
    scoped_refptr<AdBlockBundleStore> store = nullptr);

}  // namespace brave_shields

#endif  // BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_BUNDLED_DATA_H_
