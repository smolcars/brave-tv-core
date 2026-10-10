// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include <map>
#include <memory>
#include <string>

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/android/scoped_java_ref.h"
#include "base/functional/bind.h"
#include "base/json/json_reader.h"
#include "base/memory/weak_ptr.h"
#include "base/task/single_thread_task_runner.h"
#include "base/time/time.h"
#include "base/values.h"
#include "chrome/android/chrome_jni_headers/TvRemoteServer_jni.h"
#include "content/public/browser/browser_task_traits.h"
#include "content/public/browser/browser_thread.h"
#include "net/base/ip_address.h"
#include "net/base/ip_endpoint.h"
#include "net/base/net_errors.h"
#include "net/log/net_log_source.h"
#include "net/server/http_server.h"
#include "net/server/http_server_request_info.h"
#include "net/server/http_server_response_info.h"
#include "net/socket/tcp_server_socket.h"
#include "net/traffic_annotation/network_traffic_annotation.h"

namespace {
constexpr auto kTraffic =
    net::DefineNetworkTrafficAnnotation("tv_local_remote", R"(
  semantics {
    sender: "TV local remote development experiment"
    description: "Serves bundled remote controls and accepts explicitly paired local commands."
    trigger: "User enables the development experiment and approves a phone on the TV."
    data: "Normal tab metadata and input commands. No cookies or password fields."
    destination: LOCAL
  }
  policy {
    cookies_allowed: NO
    setting: "Off by default; requires debug APK, process switch and TV opt-in."
    policy_exception_justification: "Development-only user initiated local experiment."
  })");

// All socket operations and destruction run on Chromium's IO thread. Java
// revokes admission before posting deletion, after its last queued command.
class TvRemoteServer final : public net::HttpServer::Delegate {
 public:
  explicit TvRemoteServer(const jni_zero::JavaRef<jobject>& owner)
      : owner_(owner), io_task_runner_(content::GetIOThreadTaskRunner({})) {}
  ~TvRemoteServer() override { server_.reset(); }
  void Start(std::string address) {
    io_task_runner_->PostTask(
        FROM_HERE, base::BindOnce(&TvRemoteServer::Listen,
                                  base::Unretained(this), std::move(address)));
  }
  void Stop() { io_task_runner_->DeleteSoon(FROM_HERE, this); }
  void Reply(int id, std::string body) {
    io_task_runner_->PostTask(
        FROM_HERE, base::BindOnce(&TvRemoteServer::Respond,
                                  base::Unretained(this), id, std::move(body)));
  }

 private:
  void Listen(std::string address) {
    net::IPAddress ip;
    if (!ip.AssignFromIPLiteral(address) || !ip.IsIPv4() ||
        ip.IsPubliclyRoutable() || ip.IsLoopback() || ip.IsZero()) {
      Java_TvRemoteServer_started(base::android::AttachCurrentThread(), owner_,
                                  0);
      return;
    }
    auto socket =
        std::make_unique<net::TCPServerSocket>(nullptr, net::NetLogSource());
    if (socket->Listen(net::IPEndPoint(ip, 0), 4, std::nullopt) != net::OK) {
      Java_TvRemoteServer_started(base::android::AttachCurrentThread(), owner_,
                                  0);
      return;
    }
    net::IPEndPoint endpoint;
    socket->GetLocalAddress(&endpoint);
    host_ = endpoint.ToString();
    origin_ = "http://" + host_;
    server_ = std::make_unique<net::HttpServer>(std::move(socket), this);
    Java_TvRemoteServer_started(base::android::AttachCurrentThread(), owner_,
                                endpoint.port());
  }
  void OnConnect(int id) override {
    auto now = base::TimeTicks::Now();
    if (now - admission_start_ >= base::Seconds(1)) {
      admission_start_ = now;
      admissions_ = 0;
    }
    if (++admissions_ > 40 || connections_.size() >= 4) {
      server_->Close(id);
      return;
    }
    connections_[id] = false;
    server_->SetReceiveBufferSize(id, 16384);
    server_->SetSendBufferSize(id, 65536);
    io_task_runner_->PostDelayedTask(
        FROM_HERE,
        base::BindOnce(&TvRemoteServer::Timeout, weak_factory_.GetWeakPtr(),
                       id),
        base::Seconds(5));
  }
  void Timeout(int id) {
    if (server_ && connections_.contains(id)) {
      server_->Close(id);
    }
  }
  void OnHttpRequest(int id,
                     const net::HttpServerRequestInfo& request) override {
    auto it = connections_.find(id);
    if (it == connections_.end()) {
      return;
    }
    auto now = base::TimeTicks::Now();
    if (now - rate_start_ >= base::Seconds(1)) {
      rate_start_ = now;
      requests_ = 0;
    }
    if (it->second || ++requests_ > 40 ||
        request.GetHeaderValue("host") != host_ ||
        !request.GetHeaderValue("transfer-encoding").empty() ||
        !request.GetHeaderValue("cookie").empty() ||
        request.data.size() > 8192) {
      server_->Close(id);
      return;
    }
    bool asset = request.method == "GET" && request.data.empty() &&
                 (request.path == "/" || request.path == "/remote.js" ||
                  request.path == "/remote.css");
    bool command = request.method == "POST" &&
                   (request.path == "/pair" || request.path == "/api") &&
                   request.GetHeaderValue("origin") == origin_ &&
                   request.GetHeaderValue("content-type") == "application/json";
    const auto site = request.GetHeaderValue("sec-fetch-site");
    if ((!asset && !command) ||
        (site != "" && site != "none" && site != "same-origin")) {
      server_->Close(id);
      return;
    }
    if (command) {
      auto json = base::JSONReader::Read(request.data, 0, 8);
      if (!json || !json->is_dict()) {
        server_->Close(id);
        return;
      }
    }
    it->second = true;
    paths_[id] = request.path;
    JNIEnv* env = base::android::AttachCurrentThread();
    Java_TvRemoteServer_request(
        env, owner_, id,
        base::android::ConvertUTF8ToJavaString(env, request.path),
        base::android::ConvertUTF8ToJavaString(env, request.data));
  }
  void Respond(int id, std::string body) {
    if (!server_ || !paths_.contains(id) || body.size() > 60000) {
      return;
    }
    const auto path = paths_[id];
    net::HttpServerResponseInfo response(net::HTTP_OK);
    response.SetBody(body, path == "/" ? "text/html; charset=utf-8"
                           : path == "/remote.js"
                               ? "text/javascript; charset=utf-8"
                           : path == "/remote.css" ? "text/css; charset=utf-8"
                                                   : "application/json");
    response.AddHeader("Cache-Control", "no-store");
    response.AddHeader("Connection", "close");
    response.AddHeader("Referrer-Policy", "no-referrer");
    response.AddHeader("X-Content-Type-Options", "nosniff");
    response.AddHeader("X-Frame-Options", "DENY");
    response.AddHeader(
        "Content-Security-Policy",
        "default-src 'none'; script-src 'self'; style-src 'self'; connect-src "
        "'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
    server_->SendResponse(id, response, kTraffic);
    // The client closes after Connection: close; timeout bounds broken clients.
  }
  void OnWebSocketRequest(int id, const net::HttpServerRequestInfo&) override {
    server_->Close(id);
  }
  void OnWebSocketMessage(int id, std::string) override { server_->Close(id); }
  void OnClose(int id) override {
    connections_.erase(id);
    paths_.erase(id);
  }

  base::android::ScopedJavaGlobalRef<jobject> owner_;
  const scoped_refptr<base::SingleThreadTaskRunner> io_task_runner_;
  std::unique_ptr<net::HttpServer> server_;
  std::map<int, bool> connections_;
  std::map<int, std::string> paths_;
  std::string host_;
  std::string origin_;
  base::TimeTicks rate_start_;
  int requests_ = 0;
  base::TimeTicks admission_start_;
  int admissions_ = 0;
  base::WeakPtrFactory<TvRemoteServer> weak_factory_{this};
};
}  // namespace

static int64_t JNI_TvRemoteServer_Start(
    JNIEnv* env,
    const jni_zero::JavaRef<jobject>& owner,
    const jni_zero::JavaRef<jstring>& address) {
  auto* server = new TvRemoteServer(owner);
  server->Start(base::android::ConvertJavaStringToUTF8(env, address));
  return reinterpret_cast<int64_t>(server);
}
static void JNI_TvRemoteServer_Reply(JNIEnv* env,
                                     int64_t pointer,
                                     int32_t id,
                                     const jni_zero::JavaRef<jstring>& body) {
  reinterpret_cast<TvRemoteServer*>(pointer)->Reply(
      id, base::android::ConvertJavaStringToUTF8(env, body));
}
static void JNI_TvRemoteServer_Stop(JNIEnv*, int64_t pointer) {
  reinterpret_cast<TvRemoteServer*>(pointer)->Stop();
}
DEFINE_JNI(TvRemoteServer)
