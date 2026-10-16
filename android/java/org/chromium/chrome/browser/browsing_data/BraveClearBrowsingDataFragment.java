/* Copyright (c) 2024 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browsing_data;

import android.os.Bundle;

import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;

import org.chromium.build.annotations.NonNull;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.brave_leo.BraveLeoMojomHelper;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.settings.BraveSettingsPreferenceGroupAdapter;
import org.chromium.components.browser_ui.settings.SpinnerPreference;

public class BraveClearBrowsingDataFragment extends ClearBrowsingDataFragment {
    ClearBrowsingDataCheckBoxPreference mClearAIChatDataCheckBoxPreference;

    @Override
    protected @NonNull PreferenceGroupAdapter onCreateAdapter(
            @NonNull PreferenceScreen preferenceScreen) {
        return new BraveSettingsPreferenceGroupAdapter(preferenceScreen);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        getPreferenceScreen().addPreference(buildClearLeoAIHistory());
    }

    private ClearBrowsingDataCheckBoxPreference buildClearLeoAIHistory() {
        mClearAIChatDataCheckBoxPreference =
                new ClearBrowsingDataCheckBoxPreference(getContext(), null);
        mClearAIChatDataCheckBoxPreference.setTitle(R.string.brave_clear_ai_history_title);
        mClearAIChatDataCheckBoxPreference.setSummary(R.string.brave_clear_ai_history_summary);
        mClearAIChatDataCheckBoxPreference.setIcon(R.drawable.ic_product_brave_leo);

        return mClearAIChatDataCheckBoxPreference;
    }

    @Override
    protected void onClearBrowsingData() {
        super.onClearBrowsingData();

        if (mClearAIChatDataCheckBoxPreference != null
                && mClearAIChatDataCheckBoxPreference.isChecked()) {
            Profile profile = getProfile();
            if (profile == null) {
                return;
            }
            Object spinnerSelection =
                    ((SpinnerPreference) findPreference(PREF_TIME_RANGE)).getSelectedOption();
            @TimePeriod
            int lastSelectedTimePeriod =
                    ((TimePeriodUtils.TimePeriodSpinnerOption) spinnerSelection).getTimePeriod();

            BraveLeoMojomHelper.getInstance(profile).deleteConversations(lastSelectedTimePeriod);
        }
    }
}
