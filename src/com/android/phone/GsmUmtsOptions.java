/*
 * Copyright (c) 2011-2014 The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
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

import android.content.Intent;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;

import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;

import static com.android.internal.telephony.PhoneConstants.SUBSCRIPTION_KEY;

import java.util.List;

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
    private PreferenceActivity mPrefActivity;
    private PreferenceScreen mPrefScreen;
    private Phone mPhone;
    private boolean mRemovedAPNExpand = false;

    public GsmUmtsOptions(PreferenceActivity prefActivity, PreferenceScreen prefScreen) {
        this(prefActivity,  prefScreen, 0);
    }

    public GsmUmtsOptions(PreferenceActivity prefActivity,
            PreferenceScreen prefScreen, int phoneId) {
        mPrefActivity = prefActivity;
        mPrefScreen = prefScreen;
        mPhone = PhoneUtils.getPhoneFromPhoneId(phoneId);
        log("GsmUmtsOptions onCreate, phoneId = " + phoneId);

        create();
    }

    protected void create() {
        mPrefActivity.addPreferencesFromResource(R.xml.gsm_umts_options);
        mButtonAPNExpand = (PreferenceScreen) mPrefScreen.findPreference(BUTTON_APN_EXPAND_KEY);
        if (needDisableSub2Apn(mPhone.getPhoneId())) {
            log("disable sub2 apn");
            mButtonAPNExpand.setEnabled(false);
        }
        mButtonOperatorSelectionExpand =
                (PreferenceScreen) mPrefScreen.findPreference(BUTTON_OPERATOR_SELECTION_EXPAND_KEY);
        mButtonOperatorSelectionExpand.getIntent().putExtra(SUBSCRIPTION_KEY, mPhone.getSubId());
        enableScreen();
    }

    /**
     * check whether NetworkSetting apk exist in system, if true, replace the
     * intent of the NetworkSetting Activity with the intent of NetworkSetting
     */
    private void enablePlmnIncSearch() {
        if (mButtonOperatorSelectionExpand != null) {
            PackageManager pm = mButtonOperatorSelectionExpand.getContext().getPackageManager();

            // check whether the target handler exist in system
            Intent intent = new Intent("org.codeaurora.settings.NETWORK_OPERATOR_SETTINGS_ASYNC");
            List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
            for(ResolveInfo resolveInfo : list){
                // check is it installed in system.img, exclude the application
                // installed by user
                if ((resolveInfo.activityInfo.applicationInfo.flags &
                        ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // set the target intent
                    intent.putExtra(SUBSCRIPTION_KEY, mPhone.getSubId());
                    mButtonOperatorSelectionExpand.setIntent(intent);
                    break;
                }
            }
        }
    }

    public void onResume() {
        updateOperatorSelectionVisibility();
    }

    public void enableScreen() {
        if (mPhone.getPhoneType() != PhoneConstants.PHONE_TYPE_GSM) {
            log("Not a GSM phone, disabling GSM preferences (apn, select operator)");
            mButtonOperatorSelectionExpand.setEnabled(false);
        } else {
            log("Not a CDMA phone");
            Resources res = mPrefActivity.getResources();

            // Determine which options to display, for GSM these are defaulted
            // are defaulted to true in Phone/res/values/config.xml. But for
            // some operators like verizon they maybe overriden in operator
            // specific resources or device specific overlays.
            if (!res.getBoolean(R.bool.config_apn_expand) && mButtonAPNExpand != null) {
                mPrefScreen.removePreference(mButtonAPNExpand);
                mRemovedAPNExpand = true;
            }
            if (!res.getBoolean(R.bool.config_operator_selection_expand)) {
                mPrefScreen.removePreference(mButtonOperatorSelectionExpand);
            }
        }
        updateOperatorSelectionVisibility();
    }

    private void updateOperatorSelectionVisibility() {
        log("updateOperatorSelectionVisibility. mPhone = " + mPhone.getPhoneName());
        Resources res = mPrefActivity.getResources();
        if(!res.getBoolean(R.bool.config_ef_plmn_sel)) {
            enablePlmnIncSearch();
        }
        if(!mPhone.isManualNetSelAllowed()) {
            log("Manual network selection not allowed.Disabling Operator Selection menu.");
            mPrefScreen.removePreference(mButtonOperatorSelectionExpand);
        } else if (res.getBoolean(R.bool.csp_enabled)) {
            if (mPhone.isCspPlmnEnabled()) {
                log("[CSP] Enabling Operator Selection menu.");
                mButtonOperatorSelectionExpand.setEnabled(true);
            } else {
                log("[CSP] Disabling Operator Selection menu.");
                mPrefScreen.removePreference(mButtonOperatorSelectionExpand);
            }
        }

        if (res.getBoolean(R.bool.csp_enabled)) {
            // Read platform settings for carrier settings
            final boolean isCarrierSettingsEnabled = mPrefActivity.getResources().getBoolean(
                    R.bool.config_carrier_settings_enable);
            if (!isCarrierSettingsEnabled) {
                Preference pref = mPrefScreen.findPreference(BUTTON_CARRIER_SETTINGS_KEY);
                if (pref != null) {
                    mPrefScreen.removePreference(pref);
                }
            }
        }
        if (!mRemovedAPNExpand) {
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
                            intent.putExtra(SUBSCRIPTION_KEY, mPhone.getSubId());
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

    protected boolean needDisableSub2Apn(int sub) {
        if (mPrefActivity.getResources().getBoolean(R.bool.disable_data_sub2)) {
            // When current SUB is SUB2, in DSDS mode, all 2 subscriptions are
            // active, we need disable current apn option.
            return (PhoneConstants.SUB2 == sub
                    && TelephonyManager.getDefault().getMultiSimConfiguration()
                            .equals(TelephonyManager.MultiSimVariants.DSDS)
                    && 2 == SubscriptionManager.from(mPrefActivity).
                    getActiveSubscriptionInfoCount());
        }
        return false;
    }
}
