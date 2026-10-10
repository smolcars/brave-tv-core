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
#include "base/functional/callback_helpers.h"
#include "base/json/json_writer.h"
#include "base/memory/scoped_refptr.h"
#include "base/strings/string_number_conversions.h"
#include "base/strings/stringprintf.h"
#include "base/test/bind.h"
#include "base/test/task_environment.h"
#include "base/test/test_future.h"
#include "base/time/time.h"
#include "base/types/expected.h"
#include "base/values.h"
#include "brave/components/brave_shields/core/browser/ad_block_bundle_store.h"
#include "brave/components/brave_shields/core/browser/ad_block_bundle_updater.h"
#include "brave/components/brave_shields/core/browser/ad_block_bundled_data.h"
#include "components/prefs/testing_pref_service.h"
#include "crypto/hash.h"
#include "crypto/keypair.h"
#include "crypto/sign.h"
#include "net/base/load_flags.h"
#include "net/base/net_errors.h"
#include "net/http/http_status_code.h"
#include "net/url_request/redirect_info.h"
#include "services/network/public/cpp/resource_request.h"
#include "services/network/public/cpp/url_loader_completion_status.h"
#include "services/network/public/mojom/fetch_api.mojom-shared.h"
#include "services/network/public/mojom/url_response_head.mojom.h"
#include "services/network/test/test_url_loader_factory.h"
#include "services/network/test/test_utils.h"
#include "testing/gtest/include/gtest/gtest.h"
#include "url/gurl.h"

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

class AdBlockBundleUpdateTest : public AdBlockSignedBundleTest {
 protected:
  void SetUp() override {
    AdBlockSignedBundleTest::SetUp();
    ASSERT_TRUE(directory_.CreateUniqueTempDir());
    AdBlockBundleUpdater::RegisterPrefs(prefs_.registry());
    base::Time::Exploded date;
    base::Time::Now().UTCExplode(&date);
    payload_.Set(
        "published",
        base::StringPrintf("%04d-%02d-%02dT%02d:%02d:%02d.%03dZ", date.year,
                           date.month, date.day_of_month, date.hour,
                           date.minute, date.second, date.millisecond));
    path_ = directory_.GetPath().AppendASCII("filters.bundle");
    store_ =
        base::MakeRefCounted<AdBlockBundleStore>(path_, public_key_, catalog_);
    RestartUpdater();
  }

  void RestartUpdater() {
    updater_ = std::make_unique<AdBlockBundleUpdater>(
        store_, factory_.GetSafeWeakWrapper(), &prefs_);
  }

  AdBlockUpdateStatus Status() {
    base::test::TestFuture<AdBlockUpdateStatus> status;
    updater_->GetStatus(status.GetCallback());
    return status.Take();
  }

  AdBlockUpdateStatus Fetch(std::string_view body,
                            net::HttpStatusCode code = net::HTTP_OK) {
    factory_.AddResponse(kAdBlockBundleUpdateUrl, body, code);
    base::test::TestFuture<void> done;
    updater_->CheckForUpdate(done.GetCallback());
    EXPECT_TRUE(done.Wait());
    return Status();
  }

  base::test::TaskEnvironment environment_{
      base::test::TaskEnvironment::TimeSource::MOCK_TIME};
  base::ScopedTempDir directory_;
  base::FilePath path_;
  TestingPrefServiceSimple prefs_;
  network::TestURLLoaderFactory factory_;
  scoped_refptr<AdBlockBundleStore> store_;
  std::unique_ptr<AdBlockBundleUpdater> updater_;
};

TEST_F(AdBlockBundleUpdateTest, CoalescesAndStagesWithoutReplacingActiveData) {
  EXPECT_EQ(Status().active_sequence, 0);
  base::test::TestFuture<void> first;
  base::test::TestFuture<void> second;
  updater_->CheckForUpdate(first.GetCallback());
  updater_->CheckForUpdate(second.GetCallback());
  factory_.WaitForRequest(GURL(kAdBlockBundleUpdateUrl));
  ASSERT_EQ(factory_.pending_requests()->size(), 1u);
  const auto& request = factory_.GetPendingRequest(0)->request;
  EXPECT_EQ(request.credentials_mode, network::mojom::CredentialsMode::kOmit);
  EXPECT_TRUE(request.do_not_prompt_for_login);
  EXPECT_TRUE(request.referrer.is_empty());
  EXPECT_EQ(request.load_flags,
            net::LOAD_BYPASS_CACHE | net::LOAD_DISABLE_CACHE);
  EXPECT_EQ(Status().result, AdBlockUpdateResult::kChecking);
  ASSERT_TRUE(factory_.SimulateResponseForPendingRequest(
      kAdBlockBundleUpdateUrl, SignPayload()));
  ASSERT_TRUE(first.Wait());
  ASSERT_TRUE(second.Wait());
  auto status = Status();
  EXPECT_EQ(status.result, AdBlockUpdateResult::kStaged);
  EXPECT_TRUE(status.restart_required);
  EXPECT_EQ(status.active_sequence, 0);
  EXPECT_FALSE(status.last_attempt.is_null());
  auto restart =
      base::MakeRefCounted<AdBlockBundleStore>(path_, public_key_, catalog_);
  ASSERT_TRUE(restart->GetActive(base::Time::Now()));
  EXPECT_EQ(restart->GetActive(base::Time::Now())->sequence, 12);
  status = Fetch(SignPayload());
  EXPECT_EQ(status.result, AdBlockUpdateResult::kCurrent);
  EXPECT_TRUE(status.restart_required);
}

TEST_F(AdBlockBundleUpdateTest,
       CurrentAndRejectedUpdatesKeepTheActiveSnapshot) {
  const auto original = SignPayload();
  ASSERT_TRUE(base::WriteFile(path_, original));
  auto status = Fetch(original);
  EXPECT_EQ(status.result, AdBlockUpdateResult::kCurrent);
  EXPECT_EQ(status.active_sequence, 12);
  EXPECT_FALSE(status.active_published.is_null());
  EXPECT_FALSE(status.restart_required);
  auto tampered = original;
  tampered.back() ^= 1;
  EXPECT_EQ(Fetch(tampered).result, AdBlockUpdateResult::kRejected);
  payload_.Set("sequence", 11);
  EXPECT_EQ(Fetch(SignPayload()).result, AdBlockUpdateResult::kRejected);
  std::string retained;
  ASSERT_TRUE(base::ReadFileToString(path_, &retained));
  EXPECT_EQ(retained, original);
  EXPECT_EQ(Status().active_sequence, 12);
}

TEST_F(AdBlockBundleUpdateTest, FailedAndOversizedDownloadsDoNotCreateACache) {
  EXPECT_EQ(Fetch("unavailable", net::HTTP_SERVICE_UNAVAILABLE).result,
            AdBlockUpdateResult::kDownloadFailed);
  EXPECT_EQ(Fetch(std::string(kMaxSignedAdBlockBundleBytes + 1, 'x')).result,
            AdBlockUpdateResult::kDownloadFailed);
  EXPECT_FALSE(base::PathExists(path_));
}

TEST_F(AdBlockBundleUpdateTest, TimesOutAndAllowsAManualRetry) {
  base::test::TestFuture<void> done;
  updater_->CheckForUpdate(done.GetCallback());
  factory_.WaitForRequest(GURL(kAdBlockBundleUpdateUrl));
  environment_.FastForwardBy(base::Seconds(60));
  ASSERT_TRUE(done.Wait());
  EXPECT_EQ(Status().result, AdBlockUpdateResult::kDownloadFailed);
  EXPECT_EQ(Fetch(SignPayload()).result, AdBlockUpdateResult::kStaged);
}

TEST_F(AdBlockBundleUpdateTest, RejectsAnHttpRedirect) {
  net::RedirectInfo redirect;
  redirect.new_url = GURL("http://example.test/filters.bundle");
  network::TestURLLoaderFactory::Redirects redirects;
  redirects.emplace_back(redirect,
                         network::CreateURLResponseHead(net::HTTP_FOUND));
  factory_.AddResponse(
      GURL(kAdBlockBundleUpdateUrl),
      network::CreateURLResponseHead(net::HTTP_OK), SignPayload(),
      network::URLLoaderCompletionStatus(net::OK), std::move(redirects));
  base::test::TestFuture<void> done;
  updater_->CheckForUpdate(done.GetCallback());
  ASSERT_TRUE(done.Wait());
  EXPECT_EQ(Status().result, AdBlockUpdateResult::kDownloadFailed);
  EXPECT_FALSE(base::PathExists(path_));
}

TEST_F(AdBlockBundleUpdateTest,
       DailyScheduleSurvivesRecreationAndManualChecks) {
  int requests = 0;
  factory_.SetInterceptor(base::BindLambdaForTesting(
      [&](const network::ResourceRequest&) { ++requests; }));
  factory_.AddResponse(kAdBlockBundleUpdateUrl, "",
                       net::HTTP_SERVICE_UNAVAILABLE);
  environment_.FastForwardBy(base::Seconds(29));
  EXPECT_EQ(requests, 0);
  environment_.FastForwardBy(base::Seconds(1));
  EXPECT_EQ(requests, 1);
  RestartUpdater();
  environment_.FastForwardBy(base::Hours(23));
  EXPECT_EQ(requests, 1);
  environment_.FastForwardBy(base::Hours(1));
  EXPECT_EQ(requests, 2);
  EXPECT_EQ(Fetch("", net::HTTP_SERVICE_UNAVAILABLE).result,
            AdBlockUpdateResult::kDownloadFailed);
  EXPECT_EQ(requests, 3);
  RestartUpdater();
  environment_.FastForwardBy(base::Hours(23));
  EXPECT_EQ(requests, 3);
  environment_.FastForwardBy(base::Hours(1));
  EXPECT_EQ(requests, 4);
}

}  // namespace
}  // namespace brave_shields
