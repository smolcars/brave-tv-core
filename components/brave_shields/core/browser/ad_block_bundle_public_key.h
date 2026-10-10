// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. https://mozilla.org/MPL/2.0/.

#ifndef BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_BUNDLE_PUBLIC_KEY_H_
#define BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_BUNDLE_PUBLIC_KEY_H_

#include <array>
#include <cstdint>

namespace brave_shields {
inline constexpr std::array<uint8_t, 32> kAdBlockBundlePublicKey = {
    0xb0, 0x0e, 0x2c, 0x0a, 0x8f, 0xf2, 0x47, 0xd3, 0x2e, 0x20, 0x32,
    0x98, 0xbe, 0x63, 0x8e, 0x33, 0x42, 0x10, 0x89, 0x4b, 0xc2, 0x21,
    0x83, 0xf2, 0x36, 0x6c, 0x27, 0x6d, 0x9e, 0xba, 0xaa, 0xda};
}  // namespace brave_shields

#endif  // BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_BUNDLE_PUBLIC_KEY_H_
