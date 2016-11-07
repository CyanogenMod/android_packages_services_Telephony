/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.PersistableBundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.content.ComponentName;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;

/**
 * List of Network-specific settings screens.
 */
public class GsmUmtsOptions {
    private static final String LOG_TAG = "GsmUmtsOptions";

    private PreferenceScreen mButtonAPNExpand;
    private PreferenceScreen mButtonOperatorSelectionExpand;

    private static final String BUTTON_APN_EXPAND_KEY = "button_apn_key";
    private static final String BUTTON_OPERATOR_SELECTION_EXPAND_KEY = "button_carrier_sel_key";
    private static final String BUTTON_CARRIER_SETTINGS_KEY = "carrier_settings_key";
    public static final String EXTRA_SUB_ID = "sub_id";
    public static final String EXTRA_IMSI = "imsi";
    private PreferenceActivity mPrefActivity;
    private PreferenceScreen mPrefScreen;
    private int mSubId;
    private Phone mPhone = null;

    public GsmUmtsOptions(PreferenceActivity prefActivity, PreferenceScreen prefScreen,
            final int subId) {
        mPrefActivity = prefActivity;
        mPrefScreen = prefScreen;
        mSubId = subId;
        mPhone = getPhone(subId);
        create();
    }

    private Phone getPhone(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        log("getPhone: subId = " + subId + " phoneid = " + phoneId);
        if (!SubscriptionManager.isValidPhoneId(phoneId)) {
            phoneId = 0;
        }
        return PhoneFactory.getPhone(phoneId);
    }

    protected void create() {
        mPrefActivity.addPreferencesFromResource(R.xml.gsm_umts_options);
        mButtonAPNExpand = (PreferenceScreen) mPrefScreen.findPreference(BUTTON_APN_EXPAND_KEY);
        boolean removedAPNExpand = false;
        mButtonOperatorSelectionExpand =
                (PreferenceScreen) mPrefScreen.findPreference(BUTTON_OPERATOR_SELECTION_EXPAND_KEY);
        if (PhoneFactory.getDefaultPhone().getPhoneType() != PhoneConstants.PHONE_TYPE_GSM) {
            log("Not a GSM phone");
            mButtonAPNExpand.setEnabled(false);
            mButtonOperatorSelectionExpand.setEnabled(false);
        } else {
            log("Not a CDMA phone");
            Resources res = mPrefActivity.getResources();
            PersistableBundle carrierConfig =
                    PhoneGlobals.getInstance().getCarrierConfigForSubId(mSubId);

            // Determine which options to display. For GSM these are defaulted to true in
            // CarrierConfigManager, but they maybe overriden by DefaultCarrierConfigService or a
            // carrier app.
            // Note: these settings used to be controlled with overlays in
            // Telephony/res/values/config.xml
            if (!carrierConfig.getBoolean(CarrierConfigManager.KEY_APN_EXPAND_BOOL)
                    && mButtonAPNExpand != null) {
                mPrefScreen.removePreference(mButtonAPNExpand);
                removedAPNExpand = true;
            }
            if (!carrierConfig.getBoolean(
                    CarrierConfigManager.KEY_OPERATOR_SELECTION_EXPAND_BOOL)) {
                mPrefScreen.removePreference(mPrefScreen
                        .findPreference(BUTTON_OPERATOR_SELECTION_EXPAND_KEY));
            }

            if (carrierConfig.getBoolean(CarrierConfigManager.KEY_CSP_ENABLED_BOOL)) {
                if (PhoneFactory.getDefaultPhone().isCspPlmnEnabled()) {
                    log("[CSP] Enabling Operator Selection menu.");
                    mButtonOperatorSelectionExpand.setEnabled(true);
                } else {
                    log("[CSP] Disabling Operator Selection menu.");
                    mPrefScreen.removePreference(mPrefScreen
                          .findPreference(BUTTON_OPERATOR_SELECTION_EXPAND_KEY));
                }
            }

            // Read platform settings for carrier settings
            final boolean isCarrierSettingsEnabled = carrierConfig.getBoolean(
                CarrierConfigManager.KEY_CARRIER_SETTINGS_ENABLE_BOOL);
            if (!isCarrierSettingsEnabled) {
                Preference pref = mPrefScreen.findPreference(BUTTON_CARRIER_SETTINGS_KEY);
                if (pref != null) {
                    mPrefScreen.removePreference(pref);
                }
            }
        }
        if (!removedAPNExpand) {
            mButtonAPNExpand.setOnPreferenceClickListener(
                    new Preference.OnPreferenceClickListener() {
                        @Override
                        public boolean onPreferenceClick(Preference preference) {
                            // We need to build the Intent by hand as the Preference Framework
                            // does not allow to add an Intent with some extras into a Preference
                            // XML file
                            final Intent intent = new Intent(Settings.ACTION_APN_SETTINGS);
                            // This will setup the Home and Search affordance
                            intent.putExtra(":settings:show_fragment_as_subsetting", true);
                            intent.putExtra(EXTRA_SUB_ID, mSubId);
                            intent.putExtra(EXTRA_IMSI, mPhone.getSubscriberId());
                            mPrefActivity.startActivity(intent);
                            return true;
                        }
            });
        }
        if (mPrefScreen.findPreference(BUTTON_OPERATOR_SELECTION_EXPAND_KEY) != null) {
            mButtonOperatorSelectionExpand.setOnPreferenceClickListener(
                    new Preference.OnPreferenceClickListener() {
                        @Override
                        public boolean onPreferenceClick(Preference preference) {
                            final Intent intent = new Intent(Intent.ACTION_MAIN);
                            intent.setComponent(new ComponentName("com.android.phone",
                                    "com.android.phone.NetworkSetting"));
                            intent.putExtra(EXTRA_SUB_ID, mSubId);
                            mPrefActivity.startActivity(intent);
                            return true;
                        }
            });
        }
    }

    public boolean preferenceTreeClick(Preference preference) {
        log("preferenceTreeClick: return false");
        return false;
    }

    protected void log(String s) {
        android.util.Log.d(LOG_TAG, s);
    }
}
