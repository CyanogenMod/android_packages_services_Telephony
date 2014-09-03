/*
 * Copyright (c) 2011-2013 The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2006 The Android Open Source Project
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

import android.app.ActionBar;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.ConnectivityManager;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.provider.Settings.SettingNotFoundException;
import android.util.Log;
import android.view.MenuItem;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;

import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;

/**
 * "Mobile network settings" screen.  This preference screen lets you
 * enable/disable mobile data, and control data roaming and other
 * network-specific mobile data features.  It's used on non-voice-capable
 * tablets as well as regular phone devices.
 *
 * Note that this PreferenceActivity is part of the phone app, even though
 * you reach it from the "Wireless & Networks" section of the main
 * Settings app.  It's not part of the "Call settings" hierarchy that's
 * available from the Phone app (see CallFeaturesSetting for that.)
 */
// To support Dialog interface, enhanced the class definition.
public class MSimMobileNetworkSubSettings extends MobileNetworkSettings {

    // debug data
    private static final String LOG_TAG = "MSimMobileNetworkSubSettings";

    // Used for restoring the preference if APSS tune away is enabled
    private static final String KEY_PREF_NETWORK_MODE = "pre_network_mode_sub";
    private static final String PREF_FILE = "pre-network-mode";

    private int mSubscription;

    @Override
    protected Phone getPhone() {
        PhoneGlobals app = PhoneGlobals.getInstance();
        mSubscription = getIntent().getIntExtra(SUBSCRIPTION_KEY, app.getDefaultSubscription());
        return app.getPhone(mSubscription);
    }

    @Override
    protected GsmUmtsOptions getUmtsOptions(PreferenceScreen prefSet) {
        return new GsmUmtsOptions(this, prefSet, mSubscription);
    }

    @Override
    protected int getPreferredNetworkMode() {
        int nwMode;
        try {
            nwMode = android.telephony.MSimTelephonyManager.getIntAtIndex(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                    mSubscription);
        } catch (SettingNotFoundException snfe) {
            nwMode = preferredNetworkMode;
        }
        return nwMode;
    }

    @Override
    protected void setPreferredNetworkMode(int nwMode) {
        android.telephony.MSimTelephonyManager.putIntAtIndex(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                    mSubscription, nwMode);
    }

    @Override
    protected boolean isMobileDataEnabled() {
        return android.provider.Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                android.provider.Settings.Global.MOBILE_DATA + mSubscription, 0) != 0;
    }

    @Override
    protected void setMobileDataEnabled(boolean enabled) {
        android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                android.provider.Settings.Global.MOBILE_DATA + mSubscription, enabled ? 1 : 0);

        if (mSubscription == android.telephony.MSimTelephonyManager.
                getDefault().getPreferredDataSubscription()) {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.setMobileDataEnabled(enabled);
        }
    }

    @Override
    protected boolean isDataRoamingEnabled() {
        return android.provider.Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                android.provider.Settings.Global.DATA_ROAMING + mSubscription, 0) != 0;
    }

    @Override
    protected void setDataRoaming(boolean enabled) {
        android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                android.provider.Settings.Global.DATA_ROAMING + mSubscription, enabled ? 1 : 0);

        if (mSubscription == android.telephony.MSimTelephonyManager.
                getDefault().getPreferredDataSubscription()) {
            mPhone.setDataRoamingEnabled(enabled);
        }
    }

    @Override
    protected void savePreferredNetworkModeToPrefs(int mode) {
        SharedPreferences sp = mPhone.getContext().getSharedPreferences(PREF_FILE,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putInt(KEY_PREF_NETWORK_MODE + mSubscription, mode);
        editor.apply();
    }
}
