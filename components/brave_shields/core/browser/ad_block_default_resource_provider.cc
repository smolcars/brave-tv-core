// Copyright (c) 2022 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include "brave/components/brave_shields/core/browser/ad_block_default_resource_provider.h"

#include <string>
#include <utility>

#include "base/files/file_path.h"
#include "base/task/thread_pool.h"
#include "brave/components/brave_component_updater/browser/dat_file_util.h"
#include "brave/components/brave_shields/core/browser/ad_block_bundle_store.h"
#include "brave/components/brave_shields/core/browser/ad_block_bundled_data.h"
#include "brave/components/brave_shields/core/browser/ad_block_component_installer.h"

namespace {

constexpr char kAdBlockResourcesFilename[] = "resources.json";

}  // namespace

namespace brave_shields {

AdBlockDefaultResourceProvider::AdBlockDefaultResourceProvider(
    component_updater::ComponentUpdateService* cus,
    scoped_refptr<AdBlockBundleStore> bundle_store)
    : bundle_store_(std::move(bundle_store)) {
  // Can be nullptr in unit tests
  if (!cus || bundle_store_) {
    return;
  }

  RegisterAdBlockDefaultResourceComponent(
      cus,
      base::BindRepeating(&AdBlockDefaultResourceProvider::OnComponentReady,
                          weak_factory_.GetWeakPtr()));
}

AdBlockDefaultResourceProvider::~AdBlockDefaultResourceProvider() = default;

base::FilePath AdBlockDefaultResourceProvider::GetResourcesPath() {
  if (component_path_.empty()) {
    // Since we know it's empty return it as is.
    return component_path_;
  }

  return component_path_.AppendASCII(kAdBlockResourcesFilename);
}

void AdBlockDefaultResourceProvider::OnComponentReady(
    const base::FilePath& path) {
  component_path_ = path;
  base::FilePath resources_path = GetResourcesPath();

  if (resources_path.empty()) {
    // This should not happen, but if it does, we should not proceed.
    return;
  }

  // Load the resources (as ResourceStorage)
  base::ThreadPool::PostTaskAndReplyWithResult(
      FROM_HERE, {base::MayBlock()},
      base::BindOnce(&brave_component_updater::GetDATFileAsString,
                     resources_path),
      base::BindOnce(
          [](base::WeakPtr<AdBlockDefaultResourceProvider> provider,
             const std::string& resources_json) {
            if (!provider) {
              return;
            }
            auto storage = adblock::new_resource_storage(resources_json);
            provider->NotifyResourcesLoaded(std::move(storage));
          },
          weak_factory_.GetWeakPtr()));
}

void AdBlockDefaultResourceProvider::LoadResources(
    base::OnceCallback<void(AdblockResourceStorageBox)> cb) {
  base::FilePath resources_path = GetResourcesPath();
  if (resources_path.empty()) {
    if (HasBundledAdBlockData()) {
      base::ThreadPool::PostTaskAndReplyWithResult(
          FROM_HERE,
          {base::MayBlock(), base::TaskPriority::USER_VISIBLE,
           base::TaskShutdownBehavior::SKIP_ON_SHUTDOWN},
          base::BindOnce(
              [](scoped_refptr<AdBlockBundleStore> store) {
                return adblock::new_resource_storage(
                    LoadBundledAdBlockResources(store));
              },
              bundle_store_),
          base::BindOnce(
              &AdBlockDefaultResourceProvider::OnBundledResourcesLoaded,
              weak_factory_.GetWeakPtr(), std::move(cb)));
      return;
    }
    // If the path is not ready yet, run the callback with empty resources to
    // avoid blocking filter data loads.
    auto empty_storage = adblock::new_empty_resource_storage();
    std::move(cb).Run(std::move(empty_storage));
    return;
  }

  base::ThreadPool::PostTaskAndReplyWithResult(
      FROM_HERE, {base::MayBlock()},
      base::BindOnce(&brave_component_updater::GetDATFileAsString,
                     resources_path),
      base::BindOnce(
          [](base::OnceCallback<void(AdblockResourceStorageBox)> cb,
             const std::string& resources_json) {
            auto storage = adblock::new_resource_storage(resources_json);
            std::move(cb).Run(std::move(storage));
          },
          std::move(cb)));
}

void AdBlockDefaultResourceProvider::OnBundledResourcesLoaded(
    base::OnceCallback<void(AdblockResourceStorageBox)> callback,
    AdblockResourceStorageBox storage) {
  if (!component_path_.empty()) {
    LoadResources(std::move(callback));
    return;
  }
  std::move(callback).Run(std::move(storage));
}

}  // namespace brave_shields
