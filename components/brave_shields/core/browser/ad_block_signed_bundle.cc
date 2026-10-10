// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. https://mozilla.org/MPL/2.0/.

#include "brave/components/brave_shields/core/browser/ad_block_signed_bundle.h"

#include <algorithm>
#include <iterator>
#include <utility>

#include "base/json/json_reader.h"
#include "base/strings/string_number_conversions.h"
#include "base/values.h"
#include "crypto/hash.h"
#include "crypto/keypair.h"
#include "crypto/sign.h"
#include "third_party/abseil-cpp/absl/strings/str_format.h"

namespace brave_shields {
namespace {

constexpr char kFormat[] = "format";
constexpr char kSequence[] = "sequence";
constexpr char kEngine[] = "engine";
constexpr char kCatalogSha256[] = "catalog_sha256";
constexpr char kSourceVersion[] = "source_version";
constexpr char kPublished[] = "published";
constexpr char kResources[] = "resources";
constexpr char kFilters[] = "filters";
constexpr char kProvenance[] = "provenance";
constexpr char kVersion[] = "version";
constexpr char kFiles[] = "files";

constexpr std::string_view kMagic = "TVAB0001";
constexpr size_t kSignatureBytes = 64;
constexpr std::string_view kFilterIds[] = {
    "adcocjohghhfpidemphmcmlmhnfgikei", "bfpgedeaaibpoidldhjcknekahbikncb",
    "cdbbhgbmjhfnhnmgeddbliobbofkgdhe", "iodkpdagapdfkphljnddpjlldadblomo",
    "kihnoaefogbkmblfimmibknnmkllbhlf"};

std::string Sha256(std::string_view bytes) {
  return base::HexEncodeLower(crypto::hash::Sha256(bytes));
}

bool IsDigest(const std::string* text) {
  return text && text->size() == 64 && std::ranges::all_of(*text, [](char c) {
           return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
         });
}

bool ParsePublicationTime(const std::string& text, base::Time* time) {
  if (text.size() != 24 || !base::Time::FromUTCString(text.c_str(), time)) {
    return false;
  }
  base::Time::Exploded exploded;
  time->UTCExplode(&exploded);
  return text == absl::StrFormat(
                     "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ", exploded.year,
                     exploded.month, exploded.day_of_month, exploded.hour,
                     exploded.minute, exploded.second, exploded.millisecond);
}

bool MatchesHash(const base::DictValue& files,
                 std::string_view name,
                 std::string_view contents) {
  const std::string* hash = files.FindString(name);
  return hash && *hash == Sha256(contents);
}

}  // namespace

base::expected<AdBlockSignedBundle, AdBlockBundleError> VerifyAdBlockBundle(
    std::string_view bytes,
    base::span<const uint8_t, 32> public_key,
    std::string_view catalog_sha256,
    base::Time now,
    base::Time earliest_publication) {
  if (bytes.size() <= kMagic.size() + kSignatureBytes ||
      bytes.size() > kMaxSignedAdBlockBundleBytes ||
      !bytes.starts_with(kMagic)) {
    return base::unexpected(AdBlockBundleError::kEnvelope);
  }
  const auto signature = bytes.substr(kMagic.size(), kSignatureBytes);
  const auto payload = bytes.substr(kMagic.size() + kSignatureBytes);
  // Authenticate before exposing any payload to the JSON or filter parsers.
  if (!crypto::sign::Verify(
          crypto::sign::ED25519,
          crypto::keypair::PublicKey::FromEd25519PublicKey(public_key),
          base::as_byte_span(payload), base::as_byte_span(signature))) {
    return base::unexpected(AdBlockBundleError::kSignature);
  }
  auto value = base::JSONReader::ReadDict(payload, base::JSON_PARSE_RFC);
  if (!value || value->size() != 9) {
    return base::unexpected(AdBlockBundleError::kSchema);
  }
  const auto format = value->FindInt(kFormat);
  const auto sequence = value->FindInt(kSequence);
  const auto* engine = value->FindString(kEngine);
  const auto* catalog = value->FindString(kCatalogSha256);
  const auto* source = value->FindString(kSourceVersion);
  const auto* published = value->FindString(kPublished);
  auto* resources = value->FindString(kResources);
  auto* filters = value->FindDict(kFilters);
  const auto* provenance = value->FindDict(kProvenance);
  if (!format || !sequence || *sequence < 1 || !engine || !IsDigest(catalog) ||
      !IsDigest(source) || !published || !resources || resources->empty() ||
      !filters || filters->size() != std::size(kFilterIds) || !provenance) {
    return base::unexpected(AdBlockBundleError::kSchema);
  }
  // Catalog permissions and engine compatibility are fixed by the application.
  if (*format != 1 || *engine != "adblock-rs-0.13.2" ||
      *catalog != catalog_sha256) {
    return base::unexpected(AdBlockBundleError::kCompatibility);
  }
  AdBlockSignedBundle bundle;
  if (!ParsePublicationTime(*published, &bundle.published) ||
      bundle.published > now + base::Days(1) ||
      (!earliest_publication.is_null() &&
       bundle.published < earliest_publication)) {
    return base::unexpected(AdBlockBundleError::kPublicationTime);
  }
  const auto* version = provenance->FindString(kVersion);
  const auto* files = provenance->FindDict(kFiles);
  if (!version || *version != *source || !files || files->size() != 7 ||
      !files->FindString("catalog.json") ||
      *files->FindString("catalog.json") != *catalog ||
      !MatchesHash(*files, "resources.json", *resources)) {
    return base::unexpected(AdBlockBundleError::kProvenance);
  }
  // Resources must have the shape accepted by the existing engine loader.
  if (!base::JSONReader::ReadList(*resources, base::JSON_PARSE_RFC)) {
    return base::unexpected(AdBlockBundleError::kSchema);
  }
  for (auto id : kFilterIds) {
    auto* filter = filters->FindString(id);
    if (!filter || filter->empty()) {
      return base::unexpected(AdBlockBundleError::kSchema);
    }
    if (!MatchesHash(*files, std::string(id) + ".txt", *filter)) {
      return base::unexpected(AdBlockBundleError::kProvenance);
    }
    bundle.filters.emplace(id, std::move(*filter));
  }
  bundle.sequence = *sequence;
  bundle.digest = Sha256(payload);
  bundle.resources = std::move(*resources);
  return bundle;
}

}  // namespace brave_shields
