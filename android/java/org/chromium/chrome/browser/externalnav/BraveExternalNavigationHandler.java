/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.externalnav;

import android.content.Intent;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.chrome.browser.BraveConstants;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.preferences.BravePrefServiceBridge;
import org.chromium.chrome.browser.privacy.settings.BravePrivacySettings;
import org.chromium.components.embedder_support.util.UrlUtilities;
import org.chromium.components.external_intents.ExternalNavigationDelegate;
import org.chromium.components.external_intents.ExternalNavigationHandler;
import org.chromium.components.external_intents.ExternalNavigationHandler.OverrideUrlLoadingResult;
import org.chromium.components.external_intents.ExternalNavigationParams;
import org.chromium.url.GURL;

/**
 * Extends Chromium's ExternalNavigationHandler
 */
public class BraveExternalNavigationHandler extends ExternalNavigationHandler {
    private static final String TAG = "BraveUrlHandler";
    private final ExternalNavigationDelegate mNavigationDelegate;

    public BraveExternalNavigationHandler(ExternalNavigationDelegate delegate) {
        super(delegate);
        mNavigationDelegate = delegate;
    }

    @Override
    public OverrideUrlLoadingResult shouldOverrideUrlLoading(ExternalNavigationParams params) {
        String originalUrl = params.getUrl().getSpec();
        if (originalUrl.equalsIgnoreCase("chrome://adblock/")) {
            try {
                BraveActivity.getBraveActivity().openBraveContentFilteringSettings();
            } catch (BraveActivity.BraveActivityNotFoundException e) {
                Log.e(TAG, "adblock url " + e);
            }
            return OverrideUrlLoadingResult.forExternalIntent();
        }
        OverrideUrlLoadingResult result = super.shouldOverrideUrlLoading(params);
        if (result.getResultType() == OverrideUrlLoadingResultType.NO_OVERRIDE
                && params.isMainFrame()
                && params.hasUserGesture()
                && !params.isIncognito()
                && !UrlUtilities.isAcceptedScheme(params.getUrl())
                && ContextUtils.activityFromContext(mNavigationDelegate.getContext())
                        instanceof BraveActivity activity) {
            var contents = mNavigationDelegate.getWebContents();
            if (contents != null) activity.showTvUnavailableExternalLink(contents);
        }
        return result;
    }

    @Override
    protected OverrideUrlLoadingResult startActivity(
            Intent intent,
            ExternalNavigationParams params,
            boolean requiresIntentChooser,
            QueryIntentActivitiesSupplier resolvingInfos,
            ResolveActivitySupplier resolveActivity,
            GURL browserFallbackUrl,
            GURL intentTargetUrl) {
        boolean isYoutubeDomain =
                intentTargetUrl != null && intentTargetUrl.domainIs(BraveConstants.YOUTUBE_DOMAIN);
        if ((isYoutubeDomain
                        && !BravePrefServiceBridge.getInstance().getPlayYTVideoInBrowserEnabled())
                || (!isYoutubeDomain
                        && ContextUtils.getAppSharedPreferences()
                                .getBoolean(BravePrivacySettings.PREF_APP_LINKS, true))) {
            return super.startActivity(
                    intent,
                    params,
                    requiresIntentChooser,
                    resolvingInfos,
                    resolveActivity,
                    browserFallbackUrl,
                    intentTargetUrl);
        } else {
            return OverrideUrlLoadingResult.forNoOverride();
        }
    }
}
