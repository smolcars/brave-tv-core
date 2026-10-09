// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. https://mozilla.org/MPL/2.0/.

#ifndef BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_BUNDLED_DATA_H_
#define BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_BUNDLED_DATA_H_

#include <string>
#include <string_view>

namespace brave_shields {

// APK snapshots bootstrap Android profiles until signed components arrive.
bool HasBundledAdBlockData();
bool HasBundledAdBlockFilters(std::string_view component_id);
std::string_view GetBundledAdBlockVersion();
std::string LoadBundledAdBlockCatalog();
std::string LoadBundledAdBlockResources();
std::string LoadBundledAdBlockFilters(std::string_view component_id);

}  // namespace brave_shields

#endif  // BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_BUNDLED_DATA_H_
