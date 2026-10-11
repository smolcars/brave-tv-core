// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.
package org.chromium.chrome.browser.tv;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.appcompat.app.AlertDialog;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;

import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.CommandLine;
import org.chromium.base.version_info.VersionInfo;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.tv.presentation.TvPresentation;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;

/** Opt-in HTTP experiment. No server exists outside this activity's foreground lifetime. */
final class TvPhoneRemote implements TvRemoteServer.Delegate {
    private final BraveActivity mActivity;
    private final TvBrowserControls mControls;
    private final ConnectivityManager mConnectivity;
    private final Handler mUi = new Handler(Looper.getMainLooper());
    private TvRemoteServer mServer;
    private TvRemoteSession mSession;
    private TvRemoteBrowser mBrowser;
    private AlertDialog mDialog;
    private View mBadge;
    private android.net.Network mNetwork;
    private String mAddress;
    private String mOrigin;
    private String mHtml;
    private String mCss;
    private String mJs;
    private final Runnable mTick =
            new Runnable() {
                @Override
                public void run() {
                    if (mSession == null) return;
                    if (mSession.expired(SystemClock.elapsedRealtime()) || !sameNetwork()) {
                        stop();
                        return;
                    }
                    mUi.postDelayed(this, 500);
                }
            };

    TvPhoneRemote(BraveActivity activity, TvBrowserControls controls) {
        mActivity = activity;
        mControls = controls;
        mConnectivity = activity.getSystemService(ConnectivityManager.class);
    }

    static boolean available() {
        return !VersionInfo.isOfficialBuild()
                && VersionInfo.isLocalBuild()
                && CommandLine.getInstance().hasSwitch("tv-local-remote-experiment");
    }

    void invalidate() {
        if (mBrowser != null) mBrowser.invalidate();
    }

    void show() {
        if (!available() || mActivity.isFinishing() || mActivity.isDestroyed()) return;
        if (mSession != null) {
            showPairing();
            return;
        }
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        text(R.string.brave_tv_phone),
                        text(R.string.brave_tv_phone_warning),
                        0);
        panel.addAction(
                R.string.brave_tv_phone_start,
                true,
                () -> {
                    dismiss();
                    start();
                });
        panel.addAction(R.string.cancel, true, this::dismiss);
        mDialog = panel.show(this::dismiss);
    }

    private void start() {
        mAddress = lanAddress();
        if (mAddress == null) {
            TvBrowserPanel panel =
                    new TvBrowserPanel(
                            mActivity,
                            text(R.string.brave_tv_phone),
                            text(R.string.brave_tv_phone_network),
                            0);
            panel.addAction(R.string.cancel, true, this::dismiss);
            mDialog = panel.show(this::dismiss);
            return;
        }
        try {
            mHtml = read(R.raw.tv_remote);
            mCss = read(R.raw.tv_remote_css);
            mJs = read(R.raw.tv_remote_js);
        } catch (IOException exception) {
            stop();
            return;
        }
        mNetwork = mConnectivity.getActiveNetwork();
        mSession = new TvRemoteSession(SystemClock.elapsedRealtime());
        mBrowser = new TvRemoteBrowser(mActivity, mControls);
        mServer = new TvRemoteServer(mAddress, this);
        mUi.postDelayed(mTick, 500);
    }

    @Override
    public void onStarted(int port) {
        if (port == 0 || mActivity.isFinishing() || mActivity.isDestroyed()) {
            stop();
            return;
        }
        mOrigin = "http://" + mAddress + ":" + port;
        showPairing();
    }

    private void showPairing() {
        if (mSession == null || mOrigin == null) return;
        dismiss();
        TvBrowserPanel panel =
                new TvBrowserPanel(
                        mActivity,
                        text(R.string.brave_tv_phone),
                        mSession.approved()
                                ? text(R.string.brave_tv_phone_connected)
                                : mSession.pending()
                                        ? text(R.string.brave_tv_phone_approve_description)
                                        : text(R.string.brave_tv_phone_pair)
                                                + "\n"
                                                + mOrigin
                                                + "\n"
                                                + mSession.code,
                        0);
        panel.useWideLayout();
        if (!mSession.pending() && !mSession.approved()) {
            try {
                int sizeDp = 180;
                int sizePx =
                        Math.round(sizeDp * mActivity.getResources().getDisplayMetrics().density);
                var matrix =
                        new MultiFormatWriter()
                                .encode(
                                        mOrigin + "/#" + mSession.invitation,
                                        BarcodeFormat.QR_CODE,
                                        sizePx,
                                        sizePx);
                int[] pixels = new int[sizePx * sizePx];
                for (int y = 0; y < sizePx; y++)
                    for (int x = 0; x < sizePx; x++)
                        pixels[y * sizePx + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                Bitmap bitmap =
                        Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888);
                // Resampling QR modules can make valid invitations unreadable.
                bitmap.setDensity(Bitmap.DENSITY_NONE);
                ImageView image = new ImageView(mActivity);
                image.setScaleType(ImageView.ScaleType.CENTER);
                image.setImageBitmap(bitmap);
                image.setContentDescription(text(R.string.brave_tv_phone_qr));
                panel.addContent(image, sizeDp);
            } catch (WriterException exception) {
                /* Manual pairing remains available. */
            }
        }
        if (mSession.pending())
            panel.addAction(
                    R.string.brave_tv_phone_approve,
                    true,
                    () -> {
                        if (mSession == null || mSession.expired(SystemClock.elapsedRealtime())) {
                            stop();
                            return;
                        }
                        mSession.approve();
                        dismiss();
                        showBadge();
                    });
        if (mSession.approved())
            panel.addAction(R.string.brave_tv_phone_continue, true, this::dismiss);
        panel.addAction(R.string.brave_tv_phone_disconnect, true, this::stop);
        mDialog = panel.show(mSession.approved() ? this::dismiss : this::stop);
    }

    private void showBadge() {
        if (mBadge != null) return;
        mBadge =
                TvPresentation.connectionBadge(
                        mActivity, text(R.string.brave_tv_phone_connected), this::show);
        var params =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(60 * mActivity.getResources().getDisplayMetrics().density);
        params.setMarginEnd(Math.round(24 * mActivity.getResources().getDisplayMetrics().density));
        ((FrameLayout) mActivity.findViewById(android.R.id.content)).addView(mBadge, params);
    }

    @Override
    public String onRequest(String path, String body) {
        if (mSession == null || mBrowser == null || mSession.expired(SystemClock.elapsedRealtime()))
            return "{}";
        if (path.equals("/")) return mHtml;
        if (path.equals("/remote.css")) return mCss;
        if (path.equals("/remote.js")) return mJs;
        if (!sameNetwork()) {
            mUi.post(this::stop);
            return error("revoked");
        }
        try {
            JSONObject request = new JSONObject(body);
            if (TvRemoteSession.parseInteger(request.opt("v")) != 1) return error("version");
            long now = SystemClock.elapsedRealtime();
            if (path.equals("/pair")) {
                String credential = mSession.pair(request.getString("invite"), now);
                if (credential.isEmpty()) return error("pairing");
                showPairing();
                return new JSONObject().put("token", credential).toString();
            }
            if (!mSession.authenticate(request.getString("token"), now)) return error("revoked");
            if (!mSession.approved()) return error("approval");
            String op = request.getString("op");
            if (op.equals("disconnect")) {
                mUi.post(this::stop);
                return "{\"ok\":true}";
            }
            boolean ok = true;
            if (!op.equals("state")) {
                ok =
                        mSession.epoch.equals(request.getString("epoch"))
                                && mSession.acceptSequence(
                                        TvRemoteSession.parseInteger(request.opt("seq")))
                                && mBrowser.command(request);
            }
            return new JSONObject()
                    .put("ok", ok)
                    .put("epoch", mSession.epoch)
                    .put("state", mBrowser.state())
                    .toString();
        } catch (JSONException | IllegalArgumentException exception) {
            return error("invalid");
        }
    }

    private static String error(String code) {
        return "{\"error\":\"" + code + "\"}";
    }

    void stop() {
        if (mSession != null) mSession.revoke();
        mSession = null;
        if (mServer != null) mServer.stop();
        mServer = null;
        if (mBrowser != null) mBrowser.destroy();
        mBrowser = null;
        mOrigin = null;
        mUi.removeCallbacksAndMessages(null);
        dismiss();
        if (mBadge != null) {
            TvPresentation.dispose(mBadge);
            if (mBadge.getParent() instanceof ViewGroup parent) parent.removeView(mBadge);
        }
        mBadge = null;
    }

    private void dismiss() {
        if (mDialog != null) mDialog.dismiss();
        mDialog = null;
    }

    private String text(int resource) {
        return mActivity.getString(resource);
    }

    private String read(int resource) throws IOException {
        try (InputStream stream = mActivity.getResources().openRawResource(resource)) {
            return new String(
                    org.chromium.base.FileUtils.readStream(stream), StandardCharsets.UTF_8);
        }
    }

    private boolean sameNetwork() {
        return mNetwork != null
                && mConnectivity != null
                && mNetwork.equals(mConnectivity.getActiveNetwork())
                && mAddress.equals(lanAddress());
    }

    private String lanAddress() {
        if (mConnectivity == null) return null;
        var network = mConnectivity.getActiveNetwork();
        var caps = mConnectivity.getNetworkCapabilities(network);
        var links = mConnectivity.getLinkProperties(network);
        if (caps == null
                || links == null
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                || !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null;
        String result = null;
        for (var link : links.getLinkAddresses()) {
            var address = link.getAddress();
            if (!(address instanceof Inet4Address) || !address.isSiteLocalAddress()) continue;
            if (result != null) return null;
            result = address.getHostAddress();
        }
        return result;
    }
}
