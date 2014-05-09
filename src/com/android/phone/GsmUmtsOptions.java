/*
 * Copyright (c) 2011-2013 The Linux Foundation. All rights reserved.
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

import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.telephony.MSimTelephonyManager;

import com.android.internal.telephony.MSimConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.codeaurora.telephony.msim.SubscriptionManager;

import java.util.List;
import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;

/**
 * List of Network-specific settings screens.
 */
public class GsmUmtsOptions {
    private static final String LOG_TAG = "GsmUmtsOptions";

    private PreferenceScreen mButtonAPNExpand;
    private PreferenceScreen mButtonOperatorSelectionExpand;

    private static final String BUTTON_APN_EXPAND_KEY = "button_apn_key";
    private static final String BUTTON_OPERATOR_SELECTION_EXPAND_KEY = "button_carrier_sel_key";
    private PreferenceActivity mPrefActivity;
    private PreferenceScreen mPrefScreen;
    private int mSubscription = 0;
    private Phone mPhone;

    public GsmUmtsOptions(PreferenceActivity prefActivity, PreferenceScreen prefScreen) {
        this(prefActivity,  prefScreen, 0);
    }

    public GsmUmtsOptions(PreferenceActivity prefActivity,
            PreferenceScreen prefScreen, int subscription) {
        mPrefActivity = prefActivity;
        mPrefScreen = prefScreen;
        mSubscription = subscription;
        // TODO DSDS: Try to move DSDS changes to new file
        mPhone = PhoneGlobals.getInstance().getPhone(mSubscription);
        create();
    }

    protected void create() {
        mPrefActivity.addPreferencesFromResource(R.xml.gsm_umts_options);
        mButtonAPNExpand = (PreferenceScreen) mPrefScreen.findPreference(BUTTON_APN_EXPAND_KEY);

        if (needDisableSub2Apn(mSubscription)) {
            log("disable sub2 apn");
            mPrefScreen.removePreference(mButtonAPNExpand);
        } else {
            mButtonAPNExpand.getIntent().putExtra(SUBSCRIPTION_KEY,
                    mSubscription);
        }
        mButtonOperatorSelectionExpand =
                (PreferenceScreen) mPrefScreen.findPreference(BUTTON_OPERATOR_SELECTION_EXPAND_KEY);
        mButtonOperatorSelectionExpand.getIntent().putExtra(SUBSCRIPTION_KEY, mSubscription);
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
                    intent.putExtra(SUBSCRIPTION_KEY, mSubscription);
                    mButtonOperatorSelectionExpand.setIntent(intent);
                }
            }
        }
    }

    public void onResume() {
        updateOperatorSelectionVisibility();
    }

    public void enableScreen() {
        if (mPhone.getPhoneType() != PhoneConstants.PHONE_TYPE_GSM) {
            log("Not a GSM phone, disabling GSM preferences (select operator)");
            mButtonOperatorSelectionExpand.setEnabled(false);
        } else {
            log("Not a CDMA phone");
            Resources res = mPrefActivity.getResources();

            // Determine which options to display, for GSM these are defaulted
            // are defaulted to true in Phone/res/values/config.xml. But for
            // some operators like verizon they maybe overriden in operator
            // specific resources or device specifc overlays.
            if (!res.getBoolean(R.bool.config_apn_expand)) {
                mPrefScreen.removePreference(mPrefScreen.findPreference(BUTTON_APN_EXPAND_KEY));
            }
            if (!res.getBoolean(R.bool.config_operator_selection_expand)) {
                if (mButtonOperatorSelectionExpand != null) {
                    mPrefScreen.removePreference(mButtonOperatorSelectionExpand);
               }
            }
        }
        updateOperatorSelectionVisibility();
    }

    private void updateOperatorSelectionVisibility() {
        log("updateOperatorSelectionVisibility. mPhone = " + mPhone.getPhoneName());
        Resources res = mPrefActivity.getResources();
        if (mButtonOperatorSelectionExpand == null) {
            android.util.Log.e(LOG_TAG, "mButtonOperatorSelectionExpand is null");
            return;
        }

        enablePlmnIncSearch();
        if (!mPhone.isManualNetSelAllowed()) {
            log("Manual network selection not allowed.Disabling Operator Selection menu.");
            mButtonOperatorSelectionExpand.setEnabled(false);
        } else if (res.getBoolean(R.bool.csp_enabled)) {
            if (mPhone.isCspPlmnEnabled()) {
                log("[CSP] Enabling Operator Selection menu.");
                mButtonOperatorSelectionExpand.setEnabled(true);
            } else {
                log("[CSP] Disabling Operator Selection menu.");
                if (mButtonOperatorSelectionExpand != null) {
                    mPrefScreen.removePreference(mButtonOperatorSelectionExpand);
                }
            }
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
            //when current SUB is SUB2 , in DSDS mode, all 2 subscriptions are active , we need disable current apn option.
            return MSimConstants.SUB2 == sub
                    && MSimTelephonyManager.getDefault().getMultiSimConfiguration()
                            .equals(MSimTelephonyManager.MultiSimVariants.DSDS)
                    && 2 == SubscriptionManager.getInstance().getActiveSubscriptionsCount();
        }
        return false;
    }
}
