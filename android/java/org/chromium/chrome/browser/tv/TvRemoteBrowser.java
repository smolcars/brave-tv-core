// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.
package org.chromium.chrome.browser.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.search_engines.TemplateUrlServiceFactory;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tabmodel.TabClosureParams;
import org.chromium.chrome.browser.tabmodel.TabModel;
import org.chromium.chrome.browser.tabmodel.TabModelUtils;
import org.chromium.components.url_formatter.UrlFormatter;
import org.chromium.content.browser.input.TvTextInput;
import org.chromium.content_public.browser.LoadUrlParams;
import org.chromium.content_public.browser.WebContents;
import org.chromium.url.GURL;

import java.util.IdentityHashMap;
import java.util.Objects;

/** Authenticated commands are revalidated and delivered synchronously on the UI thread. */
final class TvRemoteBrowser {
    private final BraveActivity mActivity;
    private final TvBrowserControls mControls;
    private final IdentityHashMap<Tab, String> mIds = new IdentityHashMap<>();
    private Tab mTab;
    private WebContents mContents;
    private long mSessionRevision;
    private TvTextInput mText;
    private long mRevision = 1;
    private int mWidth;
    private int mHeight;
    private int mContentOffset;
    private int mX;
    private int mY;
    private boolean mPaused = true;
    private String mPauseReason;

    TvRemoteBrowser(BraveActivity activity, TvBrowserControls controls) {
        mActivity = activity;
        mControls = controls;
    }

    void invalidate() {
        mRevision++;
        if (mText == null) return;
        mText.invalidate();
        if (mTab != null
                && !mTab.isDestroyed()
                && !mTab.isIncognito()
                && mActivity.getActivityTab() == mTab
                && mContents != null
                && !mContents.isDestroyed()
                && mTab.getWebContents() == mContents
                && !mControls.remotePaused()) mText.requestStateUpdate();
    }

    void destroy() {
        if (mText != null) mText.destroy();
        mIds.clear();
    }

    private void refresh() {
        Tab tab = mActivity.getActivityTab();
        WebContents contents = tab == null || tab.isIncognito() ? null : tab.getWebContents();
        if (mTab != tab || mContents != contents) {
            destroy();
            mTab = tab;
            mContents = contents;
            mText = null;
            if (contents != null) mText = new TvTextInput(contents);
        }
        TvBrowserSession.Context context = mControls.session().capture();
        if (context.revision != mSessionRevision) {
            mSessionRevision = context.revision;
            invalidate();
        }
        int width =
                tab == null || tab.getContentView() == null ? 0 : tab.getContentView().getWidth();
        int height =
                tab == null || tab.getContentView() == null ? 0 : tab.getContentView().getHeight();
        String pauseReason = mControls.remotePauseReason();
        boolean paused = pauseReason != null;
        int offset = mActivity.getBrowserControlsManager().getContentOffset();
        if (width != mWidth
                || height != mHeight
                || !Objects.equals(pauseReason, mPauseReason)
                || paused != mPaused
                || offset != mContentOffset) {
            mRevision++;
            mContentOffset = offset;
            mWidth = width;
            mHeight = height;
            mPaused = paused;
            mPauseReason = pauseReason;
            mX = width / 2;
            mY = height / 2;
        }
    }

    JSONObject state() throws JSONException {
        refresh();
        JSONObject state = new JSONObject().put("revision", mRevision).put("paused", mPaused);
        if (mPaused) return state.put("pauseReason", mPauseReason);
        if (mTab == null) return state;
        state.put("title", cut(mTab.getTitle(), 160))
                .put("url", cut(mTab.getUrl().getSpec(), 2048))
                .put("back", mControls.isPageFullscreen() || mTab.canGoBack())
                .put("forward", mTab.canGoForward())
                .put("loading", mTab.isLoading())
                .put("error", mTab.isShowingErrorPage())
                .put("page", webPage())
                .put("width", mWidth)
                .put("height", mHeight)
                .put("x", mX)
                .put("y", mY);
        JSONArray tabs = new JSONArray();
        TabModel model = mActivity.getTabModelSelector().getModel(false);
        mIds.keySet().removeIf(tab -> model.indexOf(tab) < 0);
        for (int i = 0; i < model.getCount() && i < 64; i++) {
            Tab tab = model.getTabAt(i);
            if (tab == null || tab.isIncognito() || tab.isDestroyed()) continue;
            String id = mIds.computeIfAbsent(tab, ignored -> TvRemoteSession.randomToken());
            tabs.put(
                    new JSONObject()
                            .put("id", id)
                            .put("title", cut(tab.getTitle(), 100))
                            .put("selected", tab == mTab));
        }
        state.put("tabs", tabs);
        if (webPage() && mText != null && mText.eligible()) {
            state.put(
                    "editable",
                    new JSONObject()
                            .put("revision", mText.revision())
                            .put("version", mText.version())
                            .put("replacementVersion", mText.replacementVersion())
                            .put("ready", mText.ready())
                            .put("action", mText.action())
                            .put("multiline", mText.multiline())
                            .put("composing", mText.composing())
                            .put("text", mText.text())
                            .put("start", mText.start())
                            .put("end", mText.end()));
        }
        return state;
    }

    boolean command(JSONObject command) throws JSONException {
        refresh();
        if (mPaused
                || mTab == null
                || TvRemoteSession.parseInteger(command.opt("revision")) != mRevision) return false;
        String op = command.getString("op");
        switch (op) {
            case "navigate":
                String input = command.getString("text").trim();
                if (input.isEmpty() || input.length() > 2048 || input.chars().anyMatch(c -> c < 32))
                    return false;
                // An explicit scheme is never interpreted as search to bypass the allowlist.
                if (input.matches("(?i)^[a-z][a-z0-9+.-]*:.*")
                        && !input.matches("(?i)^https?://.*")) return false;
                GURL destination;
                if (input.contains(" ") || (!input.contains(".") && !input.contains(":"))) {
                    var service = TemplateUrlServiceFactory.getForProfile(mTab.getProfile());
                    if (!service.isLoaded()) return false;
                    destination = new GURL(service.getUrlForSearchQuery(input, null));
                } else destination = UrlFormatter.fixupUrl(input);
                if (!destination.isValid()
                        || !(destination.getScheme().equals("https")
                                || destination.getScheme().equals("http"))) return false;
                invalidate();
                mTab.loadUrl(new LoadUrlParams(destination.getSpec()));
                return true;
            case "back":
                if (mControls.exitPageFullscreen()) return true;
                return mControls
                        .session()
                        .navigate(mControls.session().capture(), TvBrowserSession.Navigation.BACK);
            case "forward":
                return mControls
                        .session()
                        .navigate(
                                mControls.session().capture(), TvBrowserSession.Navigation.FORWARD);
            case "reload":
                if (!webPage()) return false;
                return mControls
                        .session()
                        .navigate(
                                mControls.session().capture(), TvBrowserSession.Navigation.RELOAD);
            case "stop":
                return mControls
                        .session()
                        .navigate(mControls.session().capture(), TvBrowserSession.Navigation.STOP);
            case "new":
                if (mActivity.getTabModelSelector().getModel(false).getCount() >= 64) return false;
                invalidate();
                mActivity.onMenuOrKeyboardAction(R.id.new_tab_menu_id, false);
                return true;
            case "selectTab":
            case "closeTab":
                TabModel model = mActivity.getTabModelSelector().getModel(false);
                for (var entry : mIds.entrySet()) {
                    Tab target = entry.getKey();
                    if (!entry.getValue().equals(command.getString("tab"))
                            || target.isIncognito()
                            || target.isDestroyed()
                            || model.indexOf(target) < 0) continue;
                    invalidate();
                    if (op.equals("selectTab"))
                        TabModelUtils.setIndex(model, model.indexOf(target));
                    else
                        model.getTabRemover()
                                .closeTabs(TabClosureParams.closeTab(target).build(), false);
                    return true;
                }
                return false;
            case "move":
            case "click":
            case "scroll":
                if (!webPage() || mWidth <= 0 || mHeight <= 0) return false;
                int dx = bounded(command, "dx", 1000);
                int dy = bounded(command, "dy", 1000);
                if (op.equals("move")) {
                    mX = Math.max(0, Math.min(mWidth - 1, mX + dx));
                    mY = Math.max(0, Math.min(mHeight - 1, mY + dy));
                }
                mControls.remotePointer(mX, mY, op, dx, dy);
                return true;
            case "edit":
                if (!webPage() || mText == null) return false;
                String operation = command.getString("edit");
                if (operation.equals("enter")
                        && !mText.action().equals(command.optString("action"))) return false;
                return (TvRemoteSession.parseInteger(command.opt("version")) == mText.version()
                                || (operation.equals("replace")
                                        && TvRemoteSession.parseInteger(
                                                        command.opt("replacementVersion"))
                                                == mText.replacementVersion()))
                        && mText.edit(
                                TvRemoteSession.parseInteger(command.opt("editable")),
                                operation,
                                command.optString("text", ""),
                                bounded(command, "start", 2048),
                                bounded(command, "end", 2048),
                                command.optBoolean("composing", false));
            default:
                return false;
        }
    }

    private boolean webPage() {
        return mTab != null
                && !mTab.isNativePage()
                && mContents != null
                && (mTab.getUrl().getScheme().equals("http")
                        || mTab.getUrl().getScheme().equals("https"));
    }

    private static int bounded(JSONObject object, String key, int limit) throws JSONException {
        double value = object.optDouble(key, 0);
        if (!Double.isFinite(value) || value != Math.rint(value) || Math.abs(value) > limit)
            throw new JSONException("Invalid coordinate");
        return (int) value;
    }

    private static String cut(String text, int limit) {
        return text.substring(0, Math.min(text.length(), limit));
    }
}
