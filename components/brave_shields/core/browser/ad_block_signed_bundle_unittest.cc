// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. https://mozilla.org/MPL/2.0/.

#include "brave/components/brave_shields/core/browser/ad_block_signed_bundle.h"

#include <array>
#include <string>
#include <string_view>
#include <utility>

#include "base/check.h"
#include "base/containers/span.h"
#include "base/files/file_path.h"
#include "base/files/file_util.h"
#include "base/files/scoped_temp_dir.h"
#include "base/json/json_writer.h"
#include "base/memory/scoped_refptr.h"
#include "base/strings/string_number_conversions.h"
#include "base/time/time.h"
#include "base/types/expected.h"
#include "base/values.h"
#include "brave/components/brave_shields/core/browser/ad_block_bundle_store.h"
#include "brave/components/brave_shields/core/browser/ad_block_bundled_data.h"
#include "crypto/hash.h"
#include "crypto/keypair.h"
#include "crypto/sign.h"
#include "testing/gtest/include/gtest/gtest.h"

namespace brave_shields {
namespace {

std::string Hash(std::string_view text) {
  return base::HexEncodeLower(crypto::hash::Sha256(text));
}

class AdBlockSignedBundleTest : public testing::Test {
 protected:
  void SetUp() override {
    ASSERT_TRUE(base::Time::FromUTCString("2026-10-10 00:00:00 GMT", &now_));
    base::DictValue filters;
    base::DictValue files;
    files.Set("catalog.json", catalog_);
    files.Set("resources.json", Hash("[]"));
    for (auto id : {"adcocjohghhfpidemphmcmlmhnfgikei",
                    "bfpgedeaaibpoidldhjcknekahbikncb",
                    "cdbbhgbmjhfnhnmgeddbliobbofkgdhe",
                    "iodkpdagapdfkphljnddpjlldadblomo",
                    "kihnoaefogbkmblfimmibknnmkllbhlf"}) {
      filters.Set(id, "||example.test^");
      files.Set(std::string(id) + ".txt", Hash("||example.test^"));
    }
    base::DictValue provenance;
    provenance.Set("version", Hash("source"));
    provenance.Set("files", std::move(files));
    payload_.Set("format", 1);
    payload_.Set("engine", "adblock-rs-0.13.2");
    payload_.Set("sequence", 12);
    payload_.Set("published", "2026-10-10T00:00:00.000Z");
    payload_.Set("catalog_sha256", catalog_);
    payload_.Set("source_version", Hash("source"));
    payload_.Set("provenance", std::move(provenance));
    payload_.Set("resources", "[]");
    payload_.Set("filters", std::move(filters));
  }

  std::string Sign(std::string_view payload) {
    auto signature = crypto::sign::Sign(crypto::sign::ED25519, key_,
                                        base::as_byte_span(payload));
    return "TVAB0001" + std::string(base::as_string_view(signature)) +
           std::string(payload);
  }

  std::string SignPayload() {
    auto json = base::WriteJson(payload_);
    CHECK(json);
    return Sign(*json);
  }

  base::expected<AdBlockSignedBundle, AdBlockBundleError> Verify(
      std::string_view bytes) {
    return VerifyAdBlockBundle(bytes, public_key_, catalog_, now_,
                               now_ - base::Days(90));
  }

  crypto::keypair::PrivateKey key_ =
      crypto::keypair::PrivateKey::GenerateEd25519();
  std::array<uint8_t, 32> public_key_ =
      crypto::keypair::PublicKey::FromPrivateKey(key_).ToEd25519PublicKey();
  std::string catalog_ = Hash("catalog");
  base::Time now_;
  base::DictValue payload_;
};

TEST_F(AdBlockSignedBundleTest, ValidDataAndPayloadCacheIdentity) {
  auto original = Verify(SignPayload());
  ASSERT_TRUE(original.has_value());
  EXPECT_EQ(original->sequence, 12);
  EXPECT_EQ(original->published, now_);
  EXPECT_EQ(original->resources, "[]");
  EXPECT_EQ(original->filters.size(), 5u);
  EXPECT_EQ(original->filters.at("iodkpdagapdfkphljnddpjlldadblomo"),
            "||example.test^");
  payload_.Set("sequence", 13);
  auto updated = Verify(SignPayload());
  ASSERT_TRUE(updated.has_value());
  EXPECT_NE(original->digest, updated->digest);
}

TEST_F(AdBlockSignedBundleTest, SignatureBeforeParsing) {
  auto bytes = Sign("not JSON");
  EXPECT_EQ(Verify(bytes).error(), AdBlockBundleError::kSchema);
  bytes.back() ^= 1;
  EXPECT_EQ(Verify(bytes).error(), AdBlockBundleError::kSignature);
  auto valid = SignPayload();
  public_key_ = crypto::keypair::PublicKey::FromPrivateKey(
                    crypto::keypair::PrivateKey::GenerateEd25519())
                    .ToEd25519PublicKey();
  EXPECT_EQ(Verify(valid).error(), AdBlockBundleError::kSignature);
}

TEST_F(AdBlockSignedBundleTest, EnvelopeBounds) {
  auto bytes = SignPayload();
  EXPECT_EQ(Verify(bytes.substr(0, 72)).error(), AdBlockBundleError::kEnvelope);
  EXPECT_EQ(Verify(bytes.substr(0, bytes.size() - 1)).error(),
            AdBlockBundleError::kSignature);
  bytes[0] = 'X';
  EXPECT_EQ(Verify(bytes).error(), AdBlockBundleError::kEnvelope);
  bytes.resize(kMaxSignedAdBlockBundleBytes + 1, 'x');
  EXPECT_EQ(Verify(bytes).error(), AdBlockBundleError::kEnvelope);
}

TEST_F(AdBlockSignedBundleTest, FixedEngineAndCatalog) {
  payload_.Set("engine", "different-engine");
  EXPECT_EQ(Verify(SignPayload()).error(), AdBlockBundleError::kCompatibility);
  payload_.Set("engine", "adblock-rs-0.13.2");
  payload_.Set("catalog_sha256", Hash("other catalog permissions"));
  EXPECT_EQ(Verify(SignPayload()).error(), AdBlockBundleError::kCompatibility);
  payload_.Set("catalog_sha256", catalog_);
  payload_.Set("format", 2);
  EXPECT_EQ(Verify(SignPayload()).error(), AdBlockBundleError::kCompatibility);
}

TEST_F(AdBlockSignedBundleTest, SchemaAndProvenance) {
  payload_.Set("sequence", 0);
  EXPECT_EQ(Verify(SignPayload()).error(), AdBlockBundleError::kSchema);
  payload_.Set("sequence", 1.5);
  EXPECT_EQ(Verify(SignPayload()).error(), AdBlockBundleError::kSchema);
  payload_.Set("sequence", 12);
  payload_.Set("unexpected", true);
  EXPECT_EQ(Verify(SignPayload()).error(), AdBlockBundleError::kSchema);
  payload_.Remove("unexpected");
  auto* filters = payload_.FindDict("filters");
  filters->Set("iodkpdagapdfkphljnddpjlldadblomo", "tampered rules");
  EXPECT_EQ(Verify(SignPayload()).error(), AdBlockBundleError::kProvenance);
  filters->Set("iodkpdagapdfkphljnddpjlldadblomo", "");
  EXPECT_EQ(Verify(SignPayload()).error(), AdBlockBundleError::kSchema);
  filters->Remove("iodkpdagapdfkphljnddpjlldadblomo");
  filters->Set("unknown-permission-group", "||example.test^");
  EXPECT_EQ(Verify(SignPayload()).error(), AdBlockBundleError::kSchema);
}

TEST_F(AdBlockSignedBundleTest, ResourceValidation) {
  payload_.Set("resources", "{}");
  auto* files = payload_.FindDict("provenance")->FindDict("files");
  files->Set("resources.json", Hash("{}"));
  EXPECT_EQ(Verify(SignPayload()).error(), AdBlockBundleError::kSchema);
  payload_.Set("resources", "[]");
  EXPECT_EQ(Verify(SignPayload()).error(), AdBlockBundleError::kProvenance);
}

TEST_F(AdBlockSignedBundleTest, PublicationAgeAndOfflineRetention) {
  payload_.Set("published", "2026-10-12T00:00:00.000Z");
  EXPECT_EQ(Verify(SignPayload()).error(),
            AdBlockBundleError::kPublicationTime);
  payload_.Set("published", "2026-01-01T00:00:00.000Z");
  auto old = SignPayload();
  EXPECT_EQ(Verify(old).error(), AdBlockBundleError::kPublicationTime);
  EXPECT_TRUE(
      VerifyAdBlockBundle(old, public_key_, catalog_, now_, base::Time())
          .has_value());
  for (auto invalid : {"2026-10-10", "2026-02-30T00:00:00.000Z",
                       "2026-10-10T00:00:00+00:00"}) {
    payload_.Set("published", invalid);
    EXPECT_EQ(Verify(SignPayload()).error(),
              AdBlockBundleError::kPublicationTime);
  }
  payload_.Set("published", "2026-10-10T00:00:00.123Z");
  auto fractional = Verify(SignPayload());
  ASSERT_TRUE(fractional.has_value());
  EXPECT_EQ(fractional->published, now_ + base::Milliseconds(123));
}

TEST_F(AdBlockSignedBundleTest, StagePreservesActiveUntilRestart) {
  base::ScopedTempDir directory;
  ASSERT_TRUE(directory.CreateUniqueTempDir());
  auto path = directory.GetPath().AppendASCII("filters.bundle");
  auto store =
      base::MakeRefCounted<AdBlockBundleStore>(path, public_key_, catalog_);
  EXPECT_FALSE(store->GetActive(now_));
  const auto legacy_dat = GetBundledAdBlockVersion() + "serialized filters";
  EXPECT_FALSE(legacy_dat.starts_with(GetBundledAdBlockVersion(store)));
  EXPECT_EQ(store->Stage(SignPayload(), now_).value(),
            AdBlockBundleStageResult::kStaged);
  EXPECT_FALSE(store->GetActive(now_));
  store = base::MakeRefCounted<AdBlockBundleStore>(path, public_key_, catalog_);
  const auto* active = store->GetActive(now_);
  ASSERT_TRUE(active);
  EXPECT_EQ(active->sequence, 12);
  const auto version = GetBundledAdBlockVersion(store);
  EXPECT_EQ(version, "TV signed adblock " + active->digest + "\n");
  EXPECT_EQ(LoadBundledAdBlockResources(store), "[]");
  EXPECT_EQ(
      LoadBundledAdBlockFilters("iodkpdagapdfkphljnddpjlldadblomo", store),
      "||example.test^");
  EXPECT_EQ(store->Stage(SignPayload(), now_).value(),
            AdBlockBundleStageResult::kUnchanged);
  payload_.Set("sequence", 13);
  EXPECT_EQ(store->Stage(SignPayload(), now_).value(),
            AdBlockBundleStageResult::kStaged);
  EXPECT_EQ(store->GetActive(now_), active);
  EXPECT_EQ(GetBundledAdBlockVersion(store), version);
  EXPECT_EQ(active->sequence, 12);
  auto restarted =
      base::MakeRefCounted<AdBlockBundleStore>(path, public_key_, catalog_);
  ASSERT_TRUE(restarted->GetActive(now_));
  EXPECT_EQ(restarted->GetActive(now_)->sequence, 13);
  EXPECT_NE(GetBundledAdBlockVersion(restarted), version);
  payload_.Set("sequence", 12);
  EXPECT_EQ(store->Stage(SignPayload(), now_).error(),
            AdBlockBundleStageError::kRollback);
  EXPECT_EQ(restarted->Stage(SignPayload(), now_).error(),
            AdBlockBundleStageError::kRollback);
}

TEST_F(AdBlockSignedBundleTest, FailedUpdatesRetainLastGoodBytes) {
  base::ScopedTempDir directory;
  ASSERT_TRUE(directory.CreateUniqueTempDir());
  auto path = directory.GetPath().AppendASCII("filters.bundle");
  auto store =
      base::MakeRefCounted<AdBlockBundleStore>(path, public_key_, catalog_);
  const auto good = SignPayload();
  ASSERT_TRUE(store->Stage(good, now_).has_value());
  payload_.Set("published", "2026-10-10T01:00:00.000Z");
  EXPECT_EQ(store->Stage(SignPayload(), now_).error(),
            AdBlockBundleStageError::kRollback);
  auto tampered = good;
  tampered.back() ^= 1;
  EXPECT_EQ(store->Stage(tampered, now_).error(),
            AdBlockBundleStageError::kInvalidBundle);
  std::string retained;
  ASSERT_TRUE(base::ReadFileToString(path, &retained));
  EXPECT_EQ(retained, good);
  auto offline =
      base::MakeRefCounted<AdBlockBundleStore>(path, public_key_, catalog_);
  ASSERT_TRUE(offline->GetActive(now_ + base::Days(180)));
  EXPECT_EQ(offline->GetActive(now_ + base::Days(180))->sequence, 12);
}

TEST_F(AdBlockSignedBundleTest, InvalidCacheAndWriteFailure) {
  base::ScopedTempDir directory;
  ASSERT_TRUE(directory.CreateUniqueTempDir());
  auto path = directory.GetPath().AppendASCII("filters.bundle");
  ASSERT_TRUE(base::WriteFile(path, "truncated"));
  auto store =
      base::MakeRefCounted<AdBlockBundleStore>(path, public_key_, catalog_);
  EXPECT_FALSE(store->GetActive(now_));
  EXPECT_EQ(store->Stage(SignPayload(), now_).value(),
            AdBlockBundleStageResult::kStaged);
  EXPECT_FALSE(store->GetActive(now_));
  auto invalid_path = path.AppendASCII("cannot-be-inside-a-file");
  auto unwritable = base::MakeRefCounted<AdBlockBundleStore>(
      invalid_path, public_key_, catalog_);
  EXPECT_EQ(unwritable->Stage(SignPayload(), now_).error(),
            AdBlockBundleStageError::kWrite);
  EXPECT_FALSE(unwritable->GetActive(now_));
  EXPECT_EQ(unwritable->Stage(SignPayload(), now_).error(),
            AdBlockBundleStageError::kWrite);
  auto retained =
      base::MakeRefCounted<AdBlockBundleStore>(path, public_key_, catalog_);
  ASSERT_TRUE(retained->GetActive(now_));
  EXPECT_EQ(retained->GetActive(now_)->sequence, 12);
}

}  // namespace
}  // namespace brave_shields
