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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.telephony.MSimTelephonyManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;

import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;

import com.android.phone.PhoneUtils.PhoneSettings;

/**
 * Second level "MSim Call settings" UI; see res/xml/msim_call_feature_sub_setting.xml
 *
 * This preference screen is the root of the "MSim Call settings" hierarchy
 * available from the Phone app; the settings here let you control various
 * features related to phone calls (including voicemail settings
 * and others.)  It's used only on voice-capable phone devices.
 *
 * Note that this activity is part of the package com.android.phone, even
 * though you reach it from the "Phone" app (i.e. DialtactsActivity) which
 * is from the package com.android.contacts.
 *
 * For the "MSim Mobile network settings" screen under the main Settings app,
 * see apps/Phone/src/com/android/phone/Settings.java.
 */
public class MSimCallFeaturesSubSetting extends CallFeaturesSetting {
    private static final String LOG_TAG = "MSimCallFeaturesSubSetting";
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    // String keys for preference lookup
    // TODO: Naming these "BUTTON_*" is confusing since they're not actually buttons(!)
    private static final String BUTTON_RINGTONE_CATEGORY_KEY = "button_ringtone_category_key";
    private static final String BUTTON_CF_EXPAND_KEY = "button_cf_expand_key";
    private static final String BUTTON_MORE_EXPAND_KEY = "button_more_expand_key";
    private static final String BUTTON_IPPREFIX_KEY = "button_ipprefix_key";
    private static final String BUTTON_CB_EXPAND_KEY = "button_callbarring_expand_key";

    private static final String BUTTON_VIBRATE_OUTGOING_KEY = "button_vibrate_outgoing";
    private static final String BUTTON_VIBRATE_CALL_WAITING_KEY = "button_vibrate_call_waiting";
    private static final String BUTTON_HANGUP_OUTGOING_KEY = "button_vibrate_hangup";
    private static final String BUTTON_45_KEY = "button_vibrate_45";
    private static final String BUTTON_SHOW_SSN_KEY = "button_show_ssn_key";

    private PreferenceScreen mSubscriptionPrefFDN;
    private PreferenceScreen mSubscriptionPrefGSM;
    private PreferenceScreen mSubscriptionPrefCDMA;
    private PreferenceScreen mSubscriptionPrefEXPAND;
    private PreferenceScreen mSubscriptionPrefMOREEXPAND;
    private PreferenceScreen mSubscriptionIPPrefix;

    private CheckBoxPreference mVibrateOutgoingPref;
    private CheckBoxPreference mVibrateCallWaitingPref;
    private CheckBoxPreference mVibrateHangupPref;
    private CheckBoxPreference mVibrate45Pref;
    private CheckBoxPreference mShowSSNPref;

    /**
     * Receiver for Receiver for ACTION_AIRPLANE_MODE_CHANGED and ACTION_SIM_STATE_CHANGED.
     */
    private BroadcastReceiver mReceiver = new BroadcastReceiver (){
        @Override
        public void onReceive(Context context, Intent intent) {
            setScreenState();
        }
    };

    /*
     * Click Listeners, handle click based on objects attached to UI.
     */

    // Click listener for all toggle events
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mSubscriptionIPPrefix) {
            View v = getLayoutInflater().inflate(R.layout.ip_prefix, null);
            final EditText edit = (EditText) v.findViewById(R.id.ip_prefix_dialog_edit);
            String ip_prefix = Settings.System.getString(getContentResolver(),
                    Constants.SETTINGS_IP_PREFIX + (mSubscription + 1));
            edit.setText(ip_prefix);

            new AlertDialog.Builder(this)
                    .setTitle(R.string.ipcall_dialog_title)
                    .setIcon(android.R.drawable.ic_dialog_info)
                    .setView(v)
                    .setPositiveButton(android.R.string.ok,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    String ip_prefix = edit.getText().toString();
                                    Settings.System.putString(getContentResolver(),
                                            Constants.SETTINGS_IP_PREFIX + (mSubscription + 1),
                                            ip_prefix);
                                    if (TextUtils.isEmpty(ip_prefix)) {
                                        mSubscriptionIPPrefix.setSummary(
                                                R.string.ipcall_sub_summery);
                                    } else {
                                        mSubscriptionIPPrefix.setSummary(edit.getText());
                                    }
                                    onResume();
                                }
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        }
        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    private void setScreenState() {
        int simState = MSimTelephonyManager.getDefault().getSimState(mSubscription);
        getPreferenceScreen().setEnabled(simState == TelephonyManager.SIM_STATE_READY);
    }

    @Override
    protected void onCreate(Bundle icicle) {
        // getting selected subscription
        mSubscription = getIntent().getIntExtra(SUBSCRIPTION_KEY, 0);
        super.onCreate(icicle);
        log("onCreate(). Intent: " + getIntent());
        log("settings onCreate subscription =" + mSubscription);

        //Register for intent broadcasts
        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intentFilter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);

        registerReceiver(mReceiver, intentFilter);

        mSubscriptionPrefFDN  = (PreferenceScreen) findPreference(BUTTON_FDN_KEY);
        mSubscriptionPrefGSM  = (PreferenceScreen) findPreference(BUTTON_GSM_UMTS_OPTIONS);
        mSubscriptionPrefCDMA = (PreferenceScreen) findPreference(BUTTON_CDMA_OPTIONS);
        if (mSubscriptionPrefFDN != null) {
            mSubscriptionPrefFDN.getIntent().putExtra(SUBSCRIPTION_KEY, mSubscription);
        }
        if (mSubscriptionPrefGSM != null) {
            mSubscriptionPrefGSM.getIntent().putExtra(SUBSCRIPTION_KEY, mSubscription);
        }
        if (mSubscriptionPrefCDMA != null) {
            mSubscriptionPrefCDMA.getIntent().putExtra(SUBSCRIPTION_KEY, mSubscription);
        }
        mSubscriptionIPPrefix = (PreferenceScreen) findPreference(BUTTON_IPPREFIX_KEY);
        if (mSubscriptionIPPrefix != null) {
            String ip_prefix = Settings.System.getString(getContentResolver(),
                    Constants.SETTINGS_IP_PREFIX + (mSubscription + 1));
            if (TextUtils.isEmpty(ip_prefix)) {
                mSubscriptionIPPrefix.setSummary(R.string.ipcall_sub_summery);
            } else {
                mSubscriptionIPPrefix.setSummary(ip_prefix);
            }
        }
        //Change the pref keys to be per subscription
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        mVibrateOutgoingPref = (CheckBoxPreference) findPreference(BUTTON_VIBRATE_OUTGOING_KEY);
        boolean initialState = prefs.getBoolean(mVibrateOutgoingPref.getKey(), false);
        PhoneSettings.setPreferenceKeyForSubscription(mVibrateOutgoingPref, mSubscription);
        mVibrateOutgoingPref.setChecked(prefs.getBoolean(mVibrateOutgoingPref.getKey(), initialState));
        mVibrateCallWaitingPref = (CheckBoxPreference) findPreference(BUTTON_VIBRATE_CALL_WAITING_KEY);
        initialState = prefs.getBoolean(mVibrateCallWaitingPref.getKey(), false);
        PhoneSettings.setPreferenceKeyForSubscription(mVibrateCallWaitingPref, mSubscription);
        mVibrateCallWaitingPref.setChecked(prefs.getBoolean(mVibrateCallWaitingPref.getKey(), initialState));
        mVibrateHangupPref = (CheckBoxPreference) findPreference(BUTTON_HANGUP_OUTGOING_KEY);
        initialState = prefs.getBoolean(mVibrateHangupPref.getKey(), false);
        PhoneSettings.setPreferenceKeyForSubscription(mVibrateHangupPref, mSubscription);
        mVibrateHangupPref.setChecked(prefs.getBoolean(mVibrateHangupPref.getKey(), initialState));
        mVibrate45Pref = (CheckBoxPreference) findPreference(BUTTON_45_KEY);
        initialState = prefs.getBoolean(mVibrate45Pref.getKey(), false);
        PhoneSettings.setPreferenceKeyForSubscription(mVibrate45Pref, mSubscription);
        mVibrate45Pref.setChecked(prefs.getBoolean(mVibrate45Pref.getKey(), initialState));
    }

    @Override
    protected void onResume() {
        super.onResume();
        setScreenState();
    }

    private static void log(String msg) {
        if (DBG) Log.d(LOG_TAG, msg);
    }

    @Override
    protected void onCreateLookupPrefs() {
        //Do Nothing
    }

    @Override
    protected void onResumeLookupPrefs() {
       //Do Nothing
    }

    @Override
    protected void addOptionalPrefs(PreferenceScreen preferenceScreen) {
        super.addOptionalPrefs(preferenceScreen);
        if (!getResources().getBoolean(R.bool.world_phone)) {
            int phoneType = mPhone.getPhoneType();
            if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                mSubscriptionPrefEXPAND = (PreferenceScreen) findPreference(BUTTON_CF_EXPAND_KEY);
                mSubscriptionPrefMOREEXPAND =
                        (PreferenceScreen) findPreference(BUTTON_MORE_EXPAND_KEY);
                mSubscriptionPrefEXPAND.getIntent().putExtra(SUBSCRIPTION_KEY, mSubscription);
                mSubscriptionPrefMOREEXPAND.getIntent().putExtra(SUBSCRIPTION_KEY, mSubscription);
                findPreference(BUTTON_CB_EXPAND_KEY).getIntent().putExtra(SUBSCRIPTION_KEY,
                        mSubscription);
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                mShowSSNPref = (CheckBoxPreference) findPreference(BUTTON_SHOW_SSN_KEY);
                boolean initialState = prefs.getBoolean(mShowSSNPref.getKey(), false);
                PhoneSettings.setPreferenceKeyForSubscription(mShowSSNPref, mSubscription);
                mShowSSNPref.setChecked(prefs.getBoolean(mShowSSNPref.getKey(), initialState));
            }
        }
    }

    @Override
    protected void removeOptionalPrefs(PreferenceScreen preferenceScreen) {
        super.removeOptionalPrefs(preferenceScreen);
        // "Vibrate When Ringing" item is no long needed on DSDS mode
        if (mVibrateWhenRinging != null) {
            PreferenceGroup ringtoneCategory = (PreferenceGroup)
                    findPreference(BUTTON_RINGTONE_CATEGORY_KEY);
            ringtoneCategory.removePreference(mVibrateWhenRinging);
        }
    }

    /**
     * Finish current Activity and go up to the top level Settings ({@link CallFeaturesSetting}).
     * This is useful for implementing "HomeAsUp" capability for second-level Settings.
     */
    public static void goUpToTopLevelSetting(Activity activity) {
        Intent intent = new Intent(activity, SelectSubscription.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.putExtra(SelectSubscription.PACKAGE, "com.android.phone");
        intent.putExtra(SelectSubscription.TARGET_CLASS,
                "com.android.phone.MSimCallFeaturesSubSetting");
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.startActivity(intent);
        activity.finish();
    }

    @Override
    protected int getPreferencesResource() {
        return R.xml.msim_call_feature_sub_setting;
    }

    @Override
    protected Phone getPhone() {
        return MSimPhoneGlobals.getInstance().getPhone(mSubscription);
    }
}
