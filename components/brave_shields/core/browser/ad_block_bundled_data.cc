// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. https://mozilla.org/MPL/2.0/.

#include "brave/components/brave_shields/core/browser/ad_block_bundled_data.h"

#include "base/time/time.h"
#include "brave/components/brave_shields/core/browser/ad_block_bundle_store.h"
#include "build/build_config.h"

#if BUILDFLAG(IS_ANDROID)
#include "brave/components/brave_shields/resources/bundled/version.h"
#include "components/grit/brave_components_resources.h"
#include "ui/base/resource/resource_bundle.h"
#endif

namespace brave_shields {
namespace {

int FilterResourceId(std::string_view component_id) {
#if BUILDFLAG(IS_ANDROID)
  if (component_id == "iodkpdagapdfkphljnddpjlldadblomo") {
    return IDR_BRAVE_ADBLOCK_BUNDLED_DEFAULT;
  }
  if (component_id == "kihnoaefogbkmblfimmibknnmkllbhlf") {
    return IDR_BRAVE_ADBLOCK_BUNDLED_PRIVACY;
  }
  if (component_id == "adcocjohghhfpidemphmcmlmhnfgikei") {
    return IDR_BRAVE_ADBLOCK_BUNDLED_FIRST_PARTY;
  }
  if (component_id == "cdbbhgbmjhfnhnmgeddbliobbofkgdhe") {
    return IDR_BRAVE_ADBLOCK_BUNDLED_COOKIES;
  }
  if (component_id == "bfpgedeaaibpoidldhjcknekahbikncb") {
    return IDR_BRAVE_ADBLOCK_BUNDLED_MOBILE;
  }
#endif  // BUILDFLAG(IS_ANDROID)
  return 0;
}

std::string LoadResource(int id) {
#if BUILDFLAG(IS_ANDROID)
  if (id && HasBundledAdBlockData()) {
    return ui::ResourceBundle::GetSharedInstance().LoadDataResourceString(id);
  }
#endif  // BUILDFLAG(IS_ANDROID)
  return {};
}

}  // namespace

bool HasBundledAdBlockData() {
#if BUILDFLAG(IS_ANDROID)
  return ui::ResourceBundle::HasSharedInstance();
#else
  return false;
#endif  // BUILDFLAG(IS_ANDROID)
}

bool HasBundledAdBlockFilters(std::string_view component_id) {
  return HasBundledAdBlockData() && FilterResourceId(component_id) != 0;
}

std::string GetBundledAdBlockVersion(scoped_refptr<AdBlockBundleStore> store) {
  if (store) {
    if (const auto* active = store->GetActive(base::Time::Now())) {
      return "TV signed adblock " + active->digest + "\n";
    }
  }
  std::string version;
#if BUILDFLAG(IS_ANDROID)
  version = kAdBlockBundleVersion;
#endif  // BUILDFLAG(IS_ANDROID)
  return store ? "TV independent packaged\n" + version : version;
}

std::string_view GetBundledAdBlockCatalogHash() {
#if BUILDFLAG(IS_ANDROID)
  return kAdBlockBundleCatalogHash;
#else
  return {};
#endif  // BUILDFLAG(IS_ANDROID)
}

std::string LoadBundledAdBlockCatalog() {
#if BUILDFLAG(IS_ANDROID)
  return LoadResource(IDR_BRAVE_ADBLOCK_BUNDLED_CATALOG);
#else
  return {};
#endif  // BUILDFLAG(IS_ANDROID)
}

std::string LoadBundledAdBlockResources(
    scoped_refptr<AdBlockBundleStore> store) {
  if (store) {
    if (const auto* active = store->GetActive(base::Time::Now())) {
      return active->resources;
    }
  }
#if BUILDFLAG(IS_ANDROID)
  return LoadResource(IDR_BRAVE_ADBLOCK_BUNDLED_RESOURCES);
#else
  return {};
#endif  // BUILDFLAG(IS_ANDROID)
}

std::string LoadBundledAdBlockFilters(std::string_view component_id,
                                      scoped_refptr<AdBlockBundleStore> store) {
  if (store) {
    if (const auto* active = store->GetActive(base::Time::Now())) {
      const auto found = active->filters.find(component_id);
      if (found != active->filters.end()) {
        return found->second;
      }
    }
  }
  return LoadResource(FilterResourceId(component_id));
}

}  // namespace brave_shields
