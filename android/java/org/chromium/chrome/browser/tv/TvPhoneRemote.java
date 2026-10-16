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
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;

import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.ObserverList;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.tv.presentation.TvPresentation;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;

/** User-initiated local Phone connection, confined to this activity's foreground lifetime. */
final class TvPhoneRemote implements TvRemoteServer.Delegate {
    private final BraveActivity mActivity;
    private final TvBrowserControls mControls;
    private final ConnectivityManager mConnectivity;
    private final Handler mUi = new Handler(Looper.getMainLooper());
    private TvRemoteServer mServer;
    private TvRemoteSession mSession;
    private TvRemoteBrowser mBrowser;
    private AlertDialog mDialog;
    private final ObserverList<Runnable> mObservers = new ObserverList<>();
    private int mStatus = R.string.brave_tv_phone;
    private Runnable mOnReturn = () -> {};
    private View mBadge;
    private final ViewTreeObserver.OnGlobalLayoutListener mBadgeLayoutObserver =
            this::positionBadge;
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
                    if (mSession.expired(SystemClock.elapsedRealtime())) {
                        fail(R.string.brave_tv_phone_expired);
                        return;
                    }
                    if (!sameNetwork()) {
                        fail(R.string.brave_tv_phone_network);
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

    int statusLabel() {
        return mStatus;
    }

    void addObserver(Runnable observer) {
        mObservers.addObserver(observer);
    }

    void removeObserver(Runnable observer) {
        mObservers.removeObserver(observer);
    }

    private void setStatus(int status) {
        if (mStatus == status) return;
        mStatus = status;
        for (Runnable observer : mObservers) observer.run();
    }

    void invalidate() {
        if (mBrowser != null) mBrowser.invalidate();
        if (!mControls.canPresentPhone()) {
            if (mSession != null && !mSession.approved()) stop();
            else {
                dismiss();
                mOnReturn = () -> {};
            }
        }
        positionBadge();
    }

    void releaseTyping() {
        if (mBrowser != null) mBrowser.releaseTyping();
    }

    void show(Runnable onReturn) {
        if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
        mOnReturn = onReturn;
        if (mSession != null) {
            if (mOrigin == null) showStarting();
            else showPairing();
            return;
        }
        start();
    }

    private TvBrowserPanel panel(String description) {
        TvBrowserPanel panel =
                new TvBrowserPanel(mActivity, text(R.string.brave_tv_phone), description, 0);
        panel.useAmbientStyle();
        panel.useWideLayout();
        return panel;
    }

    private void showStarting() {
        dismiss();
        TvBrowserPanel panel = panel(text(R.string.brave_tv_phone_starting));
        panel.addDescription(text(R.string.brave_tv_phone_warning));
        panel.addAction(R.string.cancel, true, this::close);
        mDialog = panel.show(this::close);
    }

    private void fail(int reason) {
        boolean visible = mDialog != null;
        Runnable onReturn = mOnReturn;
        stop();
        if (visible && !mActivity.isFinishing() && !mActivity.isDestroyed()) {
            mOnReturn = onReturn;
            showError(reason);
        }
    }

    private void showError(int reason) {
        dismiss();
        TvBrowserPanel panel = panel(text(reason));
        panel.addDescription(text(R.string.brave_tv_phone_warning));
        panel.addAction(R.string.brave_tv_phone_start, true, this::start);
        panel.addAction(R.string.cancel, true, this::finishPanel);
        mDialog = panel.show(this::finishPanel);
    }

    private void start() {
        dismiss();
        mAddress = lanAddress();
        if (mAddress == null) {
            showError(R.string.brave_tv_phone_network);
            return;
        }
        try {
            mHtml = read(R.raw.tv_remote);
            mCss = read(R.raw.tv_remote_css);
            mJs = read(R.raw.tv_remote_js);
        } catch (IOException exception) {
            showError(R.string.brave_tv_phone_failed);
            return;
        }
        mNetwork = mConnectivity.getActiveNetwork();
        mSession = new TvRemoteSession(SystemClock.elapsedRealtime());
        mBrowser = new TvRemoteBrowser(mActivity, mControls);
        setStatus(R.string.brave_tv_phone_connecting);
        showStarting();
        mServer = new TvRemoteServer(mAddress, this);
        mUi.postDelayed(mTick, 500);
    }

    @Override
    public void onStarted(int port) {
        if (mSession == null) return;
        if (mActivity.isFinishing() || mActivity.isDestroyed()) {
            stop();
            return;
        }
        if (port == 0) {
            fail(R.string.brave_tv_phone_failed);
            return;
        }
        mOrigin = "http://" + mAddress + ":" + port;
        showPairing();
    }

    private void showPairing() {
        if (mSession == null || mOrigin == null) return;
        if (!mControls.canPresentPhone()) {
            stop();
            return;
        }
        dismiss();
        setStatus(
                mSession.approved()
                        ? R.string.brave_tv_phone_connected
                        : mSession.pending()
                                ? R.string.brave_tv_phone_approval
                                : R.string.brave_tv_phone_waiting);
        boolean invitation = !mSession.pending() && !mSession.approved();
        String description =
                mSession.approved()
                        ? text(R.string.brave_tv_phone_connected)
                        : mSession.pending()
                                ? text(R.string.brave_tv_phone_approve_description)
                                : text(R.string.brave_tv_phone_pair)
                                        + "\n\n"
                                        + mOrigin
                                        + "\n"
                                        + mSession.code;
        TvBrowserPanel panel = panel(invitation ? "" : description);
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
                LinearLayout row = new LinearLayout(mActivity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(image, new LinearLayout.LayoutParams(sizePx, sizePx));
                TextView instructions = new TextView(mActivity);
                instructions.setText(description);
                instructions.setTextSize(16);
                instructions.setTextColor(Color.rgb(208, 223, 216));
                var params =
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                params.setMarginStart(
                        Math.round(24 * mActivity.getResources().getDisplayMetrics().density));
                row.addView(instructions, params);
                int height = sizeDp;
                panel.addContent(row, height);
            } catch (WriterException exception) {
                panel.addDescription(description);
            }
        }
        panel.addDescription(text(R.string.brave_tv_phone_warning));
        if (mSession.pending())
            panel.addAction(
                    R.string.brave_tv_phone_approve,
                    true,
                    () -> {
                        if (mSession == null
                                || mSession.expired(SystemClock.elapsedRealtime())
                                || !mControls.canPresentPhone()) {
                            stop();
                            return;
                        }
                        mSession.approve();
                        setStatus(R.string.brave_tv_phone_connected);
                        showBadge();
                        finishPanel();
                    });
        if (mSession.approved())
            panel.addAction(R.string.brave_tv_phone_continue, true, this::finishPanel);
        panel.addAction(
                mSession.approved() ? R.string.brave_tv_phone_disconnect : R.string.cancel,
                true,
                this::close);
        mDialog = panel.show(mSession.approved() ? this::finishPanel : this::close);
    }

    private void showBadge() {
        if (mBadge != null) return;
        mBadge =
                TvPresentation.connectionBadge(
                        mActivity,
                        text(R.string.brave_tv_phone_connected),
                        R.drawable.brave_tv_phone,
                        mControls::showPhoneRemote);
        var params =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(60 * mActivity.getResources().getDisplayMetrics().density);
        params.setMarginEnd(Math.round(24 * mActivity.getResources().getDisplayMetrics().density));
        FrameLayout parent = mActivity.findViewById(android.R.id.content);
        parent.addView(mBadge, params);
        parent.getViewTreeObserver().addOnGlobalLayoutListener(mBadgeLayoutObserver);
        positionBadge();
    }

    private void positionBadge() {
        if (mActivity.isFinishing()
                || mActivity.isDestroyed()
                || mBadge == null
                || !(mBadge.getLayoutParams() instanceof FrameLayout.LayoutParams params)) return;
        var tab = mActivity.getActivityTab();
        mBadge.setVisibility(
                tab == null || tab.isIncognito() || tab.getNativePage() instanceof TvHomePage
                        ? View.GONE
                        : View.VISIBLE);
        float density = mActivity.getResources().getDisplayMetrics().density;
        int top = Math.round(60 * density);
        int loadingBottom = mControls.pageLoadingBottom();
        if (loadingBottom > 0) top = Math.max(top, loadingBottom + Math.round(16 * density));
        if (params.topMargin == top) return;
        params.topMargin = top;
        mBadge.setLayoutParams(params);
    }

    @Override
    public String onRequest(String path, String body) {
        if (mSession == null || mBrowser == null || mSession.expired(SystemClock.elapsedRealtime()))
            return "{}";
        if (path.equals("/")) return mHtml;
        if (path.equals("/remote.css")) return mCss;
        if (path.equals("/remote.js")) return mJs;
        if (!sameNetwork()) {
            mUi.post(() -> fail(R.string.brave_tv_phone_network));
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
                mUi.post(this::close);
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
            if (mBadge.getParent() instanceof ViewGroup parent
                    && parent.getViewTreeObserver().isAlive()) {
                parent.getViewTreeObserver().removeOnGlobalLayoutListener(mBadgeLayoutObserver);
            }
            TvPresentation.dispose(mBadge);
            if (mBadge.getParent() instanceof ViewGroup parent) parent.removeView(mBadge);
        }
        mBadge = null;
        mOnReturn = () -> {};
        setStatus(R.string.brave_tv_phone);
    }

    private void finishPanel() {
        dismiss();
        Runnable onReturn = mOnReturn;
        mOnReturn = () -> {};
        onReturn.run();
    }

    private void close() {
        boolean visible = mDialog != null;
        Runnable onReturn = mOnReturn;
        stop();
        if (visible) onReturn.run();
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
