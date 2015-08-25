/*
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

import android.provider.Settings;
import com.android.ims.ImsManager;
import com.android.ims.ImsException;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import android.app.ActionBar;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.MenuItem;
import java.util.Arrays;

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
public class MobileNetworkSettings extends PreferenceActivity
        implements DialogInterface.OnClickListener,
        DialogInterface.OnDismissListener, Preference.OnPreferenceChangeListener{

    // debug data
    private static final String LOG_TAG = "NetworkSettings";
    private static final boolean DBG = true;
    public static final int REQUEST_CODE_EXIT_ECM = 17;

    //String keys for preference lookup
    private static final String BUTTON_PREFERED_NETWORK_MODE = "preferred_network_mode_key";
    private static final String BUTTON_ROAMING_KEY = "button_roaming_key";
    private static final String BUTTON_CDMA_LTE_DATA_SERVICE_KEY = "cdma_lte_data_service_key";
    private static final String BUTTON_UPLMN_KEY = "button_uplmn_key";
    private static final String BUTTON_ENABLED_NETWORKS_KEY = "enabled_networks_key";
    private static final String BUTTON_4G_LTE_KEY = "enhanced_4g_lte";
    private static final String BUTTON_CELL_BROADCAST_SETTINGS = "cell_broadcast_settings";
    private static final String BUTTON_ROAMING_MODE_KEY = "roaming_mode_key";

    static final int preferredNetworkMode = Phone.PREFERRED_NT_MODE;
    static final int ROAMING_MODE_DISABLED = -1;
    static final String SHARED_PREFERENCES = "MOBILE_NETWORK_SETTING_SHARED_PREFERENCES";
    static final String SHARED_PREFERENCES_ROAMING_MODE = "ROAMING_MODE";
    static final String SHARED_PREFERENCES_PREFNET = "PREFERRED_NETWORK_MODE";

    //Information about logical "up" Activity
    private static final String UP_ACTIVITY_PACKAGE = "com.android.settings";
    private static final String UP_ACTIVITY_CLASS =
            "com.android.settings.Settings$WirelessSettingsActivity";

    //UI objects
    private ListPreference mButtonPreferredNetworkMode;
    private ListPreference mButtonEnabledNetworks;
    private ListPreference mButtonRoamingOptions;
    private SwitchPreference mButtonDataRoam;
    private SwitchPreference mButton4glte;
    private Preference mLteDataServicePref;

    private static final String iface = "rmnet0"; //TODO: this will go away

    private UserManager mUm;
    private Phone mPhone;
    private MyHandler mHandler;
    private NetworkModeObserver mObserver;
    private boolean mOkClicked;
    private CharSequence[] mDefaultPrefNetEntries = null;
    private CharSequence[] mDefaultPrefNetValues = null;
    private int mNewRoamMode;

    //GsmUmts options and Cdma options
    GsmUmtsOptions mGsmUmtsOptions;
    CdmaOptions mCdmaOptions;

    private Preference mClickedPreference;
    private boolean mShow4GForLTE;
    private boolean mIsGlobalCdma;
    private boolean mUnavailable;

    private SparseIntArray mPreferredNetworkModeSummaries;
    private SparseIntArray mEnabledNetworksSummaries;
    private int mSubId;
    private boolean mIsDsdsSetup;

    private final PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        /*
         * Enable/disable the 'Enhanced 4G LTE Mode' when in/out of a call.
         * @see android.telephony.PhoneStateListener#onCallStateChanged(int,
         * java.lang.String)
         */
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            if (DBG) log("PhoneStateListener.onCallStateChanged: state=" + state);
            Preference pref = getPreferenceScreen().findPreference(BUTTON_4G_LTE_KEY);
            if (pref != null) {
                pref.setEnabled(state == TelephonyManager.CALL_STATE_IDLE);
            }
        }
    };

    //This is a method implemented for DialogInterface.OnClickListener.
    //  Used to dismiss the dialogs when they come up.
    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_POSITIVE) {
            mPhone.setDataRoamingEnabled(true);
            mOkClicked = true;
        } else {
            // Reset the toggle
            mButtonDataRoam.setChecked(false);
        }
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        // Assuming that onClick gets called first
        mButtonDataRoam.setChecked(mOkClicked);
    }

    /**
     * Invoked on each preference click in this hierarchy, overrides
     * PreferenceActivity's implementation.  Used to make sure we track the
     * preference click events.
     */
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        /** TODO: Refactor and get rid of the if's using subclasses */
        if (preference.getKey().equals(BUTTON_4G_LTE_KEY)) {
            return true;
        } else if (mGsmUmtsOptions != null &&
                mGsmUmtsOptions.preferenceTreeClick(preference) == true) {
            return true;
        } else if (mCdmaOptions != null &&
                   mCdmaOptions.preferenceTreeClick(preference) == true) {
            if (Boolean.parseBoolean(
                    SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE))) {

                mClickedPreference = preference;

                // In ECM mode launch ECM app dialog
                startActivityForResult(
                    new Intent(TelephonyIntents.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS, null),
                    REQUEST_CODE_EXIT_ECM);
            }
            return true;
        } else if (preference == mButtonPreferredNetworkMode) {
            //displays the value taken from the Settings.System
            int settingsNetworkMode = getUserNetworkSetting();

            mButtonPreferredNetworkMode.setValue(Integer.toString(settingsNetworkMode));
            return true;
        } else if (preference == mLteDataServicePref) {
            String tmpl = android.provider.Settings.Global.getString(getContentResolver(),
                        android.provider.Settings.Global.SETUP_PREPAID_DATA_SERVICE_URL);
            if (!TextUtils.isEmpty(tmpl)) {
                TelephonyManager tm = (TelephonyManager) getSystemService(
                        Context.TELEPHONY_SERVICE);
                String imsi = tm.getSubscriberId();
                if (imsi == null) {
                    imsi = "";
                }
                final String url = TextUtils.isEmpty(tmpl) ? null
                        : TextUtils.expandTemplate(tmpl, imsi).toString();
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
            } else {
                android.util.Log.e(LOG_TAG, "Missing SETUP_PREPAID_DATA_SERVICE_URL");
            }
            return true;
        }  else if (preference == mButtonEnabledNetworks) {
            int settingsNetworkMode = getUserNetworkSetting();
            mButtonEnabledNetworks.setValue(Integer.toString(settingsNetworkMode));
            return true;
        }  else if (preference == mButtonRoamingOptions) {
            return true;
        }  else if (preference == mButtonDataRoam) {
            // Do not disable the preference screen if the user clicks Data roaming.
            return true;
        } else {
            // if the button is anything but the simple toggle preference,
            // we'll need to disable all preferences to reject all click
            // events until the sub-activity's UI comes up.
            preferenceScreen.setEnabled(false);
            // Let the intents be launched by the Preference manager
            return false;
        }
    }

    private void setIMS(boolean turnOn) {
        int value = (turnOn) ? 1:0;
        android.provider.Settings.Global.putInt(
                  mPhone.getContext().getContentResolver(),
                  android.provider.Settings.Global.ENHANCED_4G_MODE_ENABLED, value);
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mPhone = PhoneUtils.getPhoneFromIntent(getIntent());
        // When subscriptions are not ready, use default phone
        if (mPhone == null) mPhone = PhoneGlobals.getPhone();
        log("Settings onCreate phoneId =" + mPhone.getPhoneId());

        mHandler = new MyHandler();
        mObserver = new NetworkModeObserver(mHandler);
        mUm = (UserManager) getSystemService(Context.USER_SERVICE);

        if (mUm.hasUserRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)) {
            mUnavailable = true;
            setContentView(R.layout.telephony_disallowed_preference_screen);
            return;
        }

        addPreferencesFromResource(R.xml.network_setting);

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        int slotIndex = getIntent().getIntExtra(PhoneConstants.SLOT_KEY,
                SubscriptionManager.INVALID_SIM_SLOT_INDEX);
        mSubId = getIntent().getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        if (slotIndex != SubscriptionManager.INVALID_SIM_SLOT_INDEX) {
            setTitle(getString(R.string.msim_mobile_network_settings_title, slotIndex + 1));
            SubscriptionManager subManager = SubscriptionManager.from(this);
            SubscriptionInfo sir = subManager.getActiveSubscriptionInfo(mSubId);
            if (actionBar != null && sir != null) {
                actionBar.setSubtitle(sir.getDisplayName());
            }
        }

        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        boolean isDsds = tm.getMultiSimConfiguration() == TelephonyManager.MultiSimVariants.DSDS;
        boolean isMultiRat = SystemProperties.getBoolean("ro.ril.multi_rat_capable", false);

        mIsDsdsSetup = isDsds && !isMultiRat;

        mButton4glte = (SwitchPreference)findPreference(BUTTON_4G_LTE_KEY);

        mButton4glte.setOnPreferenceChangeListener(this);
        mButton4glte.setChecked(ImsManager.isEnhanced4gLteModeSettingEnabledByUser(this));

        try {
            Context con = createPackageContext("com.android.systemui", 0);
            int id = con.getResources().getIdentifier("config_show4GForLTE",
                    "bool", "com.android.systemui");
            mShow4GForLTE = con.getResources().getBoolean(id);
        } catch (NameNotFoundException e) {
            loge("NameNotFoundException for show4GFotLTE");
            mShow4GForLTE = false;
        }

        //get UI object references
        PreferenceScreen prefSet = getPreferenceScreen();

        mButtonDataRoam = (SwitchPreference) prefSet.findPreference(BUTTON_ROAMING_KEY);
        mButtonPreferredNetworkMode = (ListPreference) prefSet.findPreference(
                BUTTON_PREFERED_NETWORK_MODE);
        mButtonEnabledNetworks = (ListPreference) prefSet.findPreference(
                BUTTON_ENABLED_NETWORKS_KEY);
        mButtonRoamingOptions = (ListPreference) prefSet.findPreference(BUTTON_ROAMING_MODE_KEY);
        mButtonDataRoam.setOnPreferenceChangeListener(this);

        mLteDataServicePref = prefSet.findPreference(BUTTON_CDMA_LTE_DATA_SERVICE_KEY);

        if (!getResources().getBoolean(R.bool.config_uplmn_for_usim)) {
            Preference mUPLMNPref = prefSet.findPreference(BUTTON_UPLMN_KEY);
            prefSet.removePreference(mUPLMNPref);
            mUPLMNPref = null;
        }

        if (ImsManager.isVolteEnabledByPlatform(this)
                && ImsManager.isVolteProvisionedOnDevice(this)) {
            tm.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
        }

        boolean isLteOnCdma = mPhone.getLteOnCdmaMode() == PhoneConstants.LTE_ON_CDMA_TRUE;
        int phoneId = mPhone.getPhoneId();
        mIsGlobalCdma = isLteOnCdma &&
                (getResources().getIntArray(R.array.config_show_cdma)[phoneId] != 0);
        int shouldHideCarrierSettings = android.provider.Settings.Global.getInt(mPhone.getContext().
                getContentResolver(), android.provider.Settings.Global.HIDE_CARRIER_NETWORK_SETTINGS, 0);
        if (shouldHideCarrierSettings == 1) {
            prefSet.removePreference(mButtonPreferredNetworkMode);
            prefSet.removePreference(mButtonEnabledNetworks);
            prefSet.removePreference(mLteDataServicePref);
            prefSet.removePreference(mButtonRoamingOptions);
        } else if (getResources().getBoolean(R.bool.world_phone) == true) {
            prefSet.removePreference(mButtonEnabledNetworks);
            prefSet.removePreference(mButtonRoamingOptions);
            // mButtonEnabledNetworks = null as it is not needed anymore
            mButtonEnabledNetworks = null;
            // set the listener for the mButtonPreferredNetworkMode list preference so we can issue
            // change Preferred Network Mode.
            mButtonPreferredNetworkMode.setOnPreferenceChangeListener(this);

            //Get the networkMode from Settings.System and displays it
            int settingsNetworkMode = getUserNetworkSetting();
            mButtonPreferredNetworkMode.setValue(Integer.toString(settingsNetworkMode));
            mCdmaOptions = new CdmaOptions(this, prefSet, mPhone);
            mGsmUmtsOptions = new GsmUmtsOptions(this, prefSet, mPhone.getSubId());
        } else {
            prefSet.removePreference(mButtonPreferredNetworkMode);
            // mButtonPreferredNetworkMode = null as it is not needed anymore
            mButtonPreferredNetworkMode = null;
            int phoneType = mPhone.getPhoneType();
            int settingsNetworkMode = getUserNetworkSetting();
            if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                if (isLteOnCdma) {
                    mButtonEnabledNetworks.setEntries(
                            R.array.enabled_networks_cdma_choices);
                    mButtonEnabledNetworks.setEntryValues(
                            R.array.enabled_networks_cdma_values);
                }
                mCdmaOptions = new CdmaOptions(this, prefSet, mPhone);
            } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                if (!getResources().getBoolean(R.bool.config_prefer_2g)
                        && !getResources().getBoolean(R.bool.config_enabled_lte)) {
                    mButtonEnabledNetworks.setEntries(
                            R.array.enabled_networks_except_gsm_lte_choices);
                    mButtonEnabledNetworks.setEntryValues(
                            R.array.enabled_networks_except_gsm_lte_values);
                } else if (!getResources().getBoolean(R.bool.config_prefer_2g)) {
                    int select = (mShow4GForLTE == true) ?
                        R.array.enabled_networks_except_gsm_4g_choices
                        : R.array.enabled_networks_except_gsm_choices;
                    mButtonEnabledNetworks.setEntries(select);
                    mButtonEnabledNetworks.setEntryValues(
                            R.array.enabled_networks_except_gsm_values);
                } else if (!getResources().getBoolean(R.bool.config_enabled_lte)) {
                    mButtonEnabledNetworks.setEntries(
                            R.array.enabled_networks_except_lte_choices);
                    mButtonEnabledNetworks.setEntryValues(
                            R.array.enabled_networks_except_lte_values);
                } else if (mIsGlobalCdma) {
                    mButtonEnabledNetworks.setEntries(
                            R.array.enabled_networks_cdma_choices);
                    mButtonEnabledNetworks.setEntryValues(
                            R.array.enabled_networks_cdma_values);
                } else {
                    int select = (mShow4GForLTE == true) ? R.array.enabled_networks_4g_choices
                        : R.array.enabled_networks_choices;
                    mButtonEnabledNetworks.setEntries(select);
                    mButtonEnabledNetworks.setEntryValues(
                            R.array.enabled_networks_values);
                }
                mGsmUmtsOptions = new GsmUmtsOptions(this, prefSet, mPhone.getSubId());
            } else {
                throw new IllegalStateException("Unexpected phone type: " + phoneType);
            }
            mButtonEnabledNetworks.setOnPreferenceChangeListener(this);
            if (DBG) log("settingsNetworkMode: " + settingsNetworkMode);
            mButtonEnabledNetworks.setValue(Integer.toString(settingsNetworkMode));

            if (getResources().getIntArray(R.array.config_show_roaming_mode_option)[phoneId] == 1) {
                mDefaultPrefNetEntries = mButtonEnabledNetworks.getEntries();
                mDefaultPrefNetValues = mButtonEnabledNetworks.getEntryValues();
                int roamingMode = getRoamingMode();
                int choicesResId;
                int valuesResId;
                if (phoneId == 0) {
                    choicesResId = R.array.roaming_mode_choices_slot1;
                    valuesResId = R.array.roaming_mode_values_slot1;
                } else {
                    choicesResId = R.array.roaming_mode_choices_slot2;
                    valuesResId = R.array.roaming_mode_values_slot2;
                }
                mButtonRoamingOptions.setEntries(choicesResId);
                mButtonRoamingOptions.setEntryValues(valuesResId);
                mButtonRoamingOptions.setValue(Integer.toString(roamingMode));
                mButtonRoamingOptions.setOnPreferenceChangeListener(this);
                updatePreferredNetworkModeList(roamingMode);
            } else {
                prefSet.removePreference(mButtonRoamingOptions);
            }
        }

        final boolean missingDataServiceUrl = TextUtils.isEmpty(
                android.provider.Settings.Global.getString(getContentResolver(),
                        android.provider.Settings.Global.SETUP_PREPAID_DATA_SERVICE_URL));
        if (!isLteOnCdma || missingDataServiceUrl) {
            prefSet.removePreference(mLteDataServicePref);
        } else {
            android.util.Log.d(LOG_TAG, "keep ltePref");
        }

        // Enable enhanced 4G LTE mode settings depending on whether exists on platform
        if (!(ImsManager.isVolteEnabledByPlatform(this)
                && ImsManager.isVolteProvisionedOnDevice(this))) {
            Preference pref = prefSet.findPreference(BUTTON_4G_LTE_KEY);
            if (pref != null) {
                prefSet.removePreference(pref);
            }
        }

        final boolean isSecondaryUser = UserHandle.myUserId() != UserHandle.USER_OWNER;
        // Enable link to CMAS app settings depending on the value in config.xml.
        final boolean isCellBroadcastAppLinkEnabled = this.getResources().getBoolean(
                com.android.internal.R.bool.config_cellBroadcastAppLinks);
        if (isSecondaryUser || !isCellBroadcastAppLinkEnabled
                || mUm.hasUserRestriction(UserManager.DISALLOW_CONFIG_CELL_BROADCASTS)) {
            PreferenceScreen root = getPreferenceScreen();
            Preference ps = findPreference(BUTTON_CELL_BROADCAST_SETTINGS);
            if (ps != null) {
                root.removePreference(ps);
            }
        }

        mPreferredNetworkModeSummaries = new SparseIntArray();
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_WCDMA_PREF,
                R.string.preferred_network_mode_wcdma_perf_summary);
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_GSM_ONLY,
                R.string.preferred_network_mode_gsm_only_summary);
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_WCDMA_ONLY,
                R.string.preferred_network_mode_wcdma_only_summary);
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_GSM_UMTS,
                R.string.preferred_network_mode_gsm_wcdma_summary);
        // NT_MODE_CDMA set conditionally for lteOnCdma
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_CDMA_NO_EVDO,
                R.string.preferred_network_mode_cdma_only_summary);
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_EVDO_NO_CDMA,
                R.string.preferred_network_mode_evdo_only_summary);
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_GLOBAL,
                R.string.preferred_network_mode_cdma_evdo_gsm_wcdma_summary);
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_LTE_CDMA_AND_EVDO,
                R.string.preferred_network_mode_lte_cdma_evdo_summary);
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_LTE_GSM_WCDMA,
                R.string.preferred_network_mode_lte_gsm_wcdma_summary);
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_LTE_CDMA_EVDO_GSM_WCDMA,
                R.string.preferred_network_mode_global_summary_cm);
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_LTE_ONLY,
                R.string.preferred_network_mode_lte_summary);
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_LTE_WCDMA,
                R.string.preferred_network_mode_lte_wcdma_summary);
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_TD_SCDMA_ONLY,
                R.string.preferred_network_mode_td_scdma_only_summary);
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_TD_SCDMA_WCDMA,
                R.string.preferred_network_mode_td_scdma_wcdma_summary);
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_TD_SCDMA_LTE,
                R.string.preferred_network_mode_td_scdma_lte_summary);
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_TD_SCDMA_GSM,
                R.string.preferred_network_mode_td_scdma_gsm_summary);
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_TD_SCDMA_GSM_LTE,
                R.string.preferred_network_mode_td_scdma_gsm_lte_summary);
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_TD_SCDMA_GSM_WCDMA,
                R.string.preferred_network_mode_td_scdma_gsm_wcdma_summary);
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_TD_SCDMA_WCDMA_LTE,
                R.string.preferred_network_mode_td_scdma_wcdma_lte_summary);
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_TD_SCDMA_GSM_WCDMA_LTE,
                R.string.preferred_network_mode_td_scdma_gsm_wcdma_lte_summary);
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_TD_SCDMA_CDMA_EVDO_GSM_WCDMA,
                R.string.preferred_network_mode_td_scdma_cdma_evdo_gsm_wcdma_summary);
        mPreferredNetworkModeSummaries.append(Phone.NT_MODE_TD_SCDMA_LTE_CDMA_EVDO_GSM_WCDMA,
                R.string.preferred_network_mode_td_scdma_lte_cdma_evdo_gsm_wcdma_summary);

        int lteOnCdmaMode = mPhone.getLteOnCdmaMode();
        if (lteOnCdmaMode == PhoneConstants.LTE_ON_CDMA_TRUE) {
            mPreferredNetworkModeSummaries.append(Phone.NT_MODE_CDMA,
                       R.string.preferred_network_mode_cdma_summary);
        } else if (lteOnCdmaMode == PhoneConstants.LTE_ON_CDMA_FALSE) {
            mPreferredNetworkModeSummaries.append(Phone.NT_MODE_CDMA,
                       R.string.preferred_network_mode_cdma_evdo_summary);
        }

        mEnabledNetworksSummaries = new SparseIntArray();
        mEnabledNetworksSummaries.append(Phone.NT_MODE_WCDMA_PREF,
                R.string.network_wcdma_pref);
        mEnabledNetworksSummaries.append(Phone.NT_MODE_GSM_ONLY,
                R.string.network_gsm_only);
        mEnabledNetworksSummaries.append(Phone.NT_MODE_WCDMA_ONLY,
                R.string.network_wcdma_only);
        mEnabledNetworksSummaries.append(Phone.NT_MODE_GSM_UMTS,
               R.string.network_gsm_umts);
        mEnabledNetworksSummaries.append(Phone.NT_MODE_CDMA,
               R.string.network_cdma);
        mEnabledNetworksSummaries.append(Phone.NT_MODE_CDMA_NO_EVDO,
               R.string.network_cdma_no_evdo);
        mEnabledNetworksSummaries.append(Phone.NT_MODE_EVDO_NO_CDMA,
               R.string.network_evdo_no_cdma);
        mEnabledNetworksSummaries.append(Phone.NT_MODE_GLOBAL,
               R.string.network_3g_global);
        // NT_MODE_LTE_CDMA_AND_EVDO see below, mShow4GForLTE
        // NT_MODE_LTE_GSM_WCDMA see below, mShow4GForLTE
        mEnabledNetworksSummaries.append(Phone.NT_MODE_LTE_CDMA_EVDO_GSM_WCDMA,
               R.string.network_lte_cdma_evdo_gsm_wcdma);
        // NT_MODE_LTE_ONLY see below, mShow4GForLTE
        // NT_MODE_LTE_WCDMA see below, mShow4GForLTE
        mEnabledNetworksSummaries.append(Phone.NT_MODE_TD_SCDMA_ONLY,
               R.string.preferred_network_mode_td_scdma_only_summary);
        mEnabledNetworksSummaries.append(Phone.NT_MODE_TD_SCDMA_WCDMA,
               R.string.preferred_network_mode_td_scdma_wcdma_summary);
        mEnabledNetworksSummaries.append(Phone.NT_MODE_TD_SCDMA_LTE,
               R.string.preferred_network_mode_td_scdma_lte_summary);
        mEnabledNetworksSummaries.append(Phone.NT_MODE_TD_SCDMA_GSM,
               R.string.preferred_network_mode_td_scdma_gsm_summary);
        mEnabledNetworksSummaries.append(Phone.NT_MODE_TD_SCDMA_GSM_LTE,
               R.string.preferred_network_mode_td_scdma_gsm_lte_summary);
        mEnabledNetworksSummaries.append(Phone.NT_MODE_TD_SCDMA_GSM_WCDMA,
               R.string.preferred_network_mode_td_scdma_gsm_wcdma_summary);
        mEnabledNetworksSummaries.append(Phone.NT_MODE_TD_SCDMA_WCDMA_LTE,
               R.string.preferred_network_mode_td_scdma_wcdma_lte_summary);
        mEnabledNetworksSummaries.append(Phone.NT_MODE_TD_SCDMA_GSM_WCDMA_LTE,
               R.string.preferred_network_mode_td_scdma_gsm_wcdma_lte_summary);
        mEnabledNetworksSummaries.append(Phone.NT_MODE_TD_SCDMA_CDMA_EVDO_GSM_WCDMA,
               R.string.preferred_network_mode_td_scdma_cdma_evdo_gsm_wcdma_summary);
        mEnabledNetworksSummaries.append(Phone.NT_MODE_TD_SCDMA_LTE_CDMA_EVDO_GSM_WCDMA,
               R.string.preferred_network_mode_td_scdma_lte_cdma_evdo_gsm_wcdma_summary);

        if (mShow4GForLTE) {
            mEnabledNetworksSummaries.append(Phone.NT_MODE_LTE_GSM_WCDMA,
                    R.string.network_4G);
            mEnabledNetworksSummaries.append(Phone.NT_MODE_LTE_WCDMA,
                    R.string.network_4G);
            mEnabledNetworksSummaries.append(Phone.NT_MODE_LTE_ONLY,
                    R.string.network_4G);
            mEnabledNetworksSummaries.append(Phone.NT_MODE_LTE_CDMA_AND_EVDO,
                    R.string.network_4G);
        } else {
            mEnabledNetworksSummaries.append(Phone.NT_MODE_LTE_GSM_WCDMA,
                    R.string.network_lte_gsm_wcdma);
            mEnabledNetworksSummaries.append(Phone.NT_MODE_LTE_WCDMA,
                    R.string.network_lte_cdma);
            mEnabledNetworksSummaries.append(Phone.NT_MODE_LTE_ONLY,
                    R.string.network_lte_only);
            mEnabledNetworksSummaries.append(Phone.NT_MODE_LTE_CDMA_AND_EVDO,
                    R.string.network_lte_cdma_and_evdo);
        }
    }

    private int getPreferredNetworkSetting() {
        try {
            int setting = TelephonyManager.getIntAtIndex(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Global.PREFERRED_NETWORK_MODE, mPhone.getPhoneId());
            return setting;
        } catch (Settings.SettingNotFoundException e) {
            return Phone.PREFERRED_NT_MODE;
        }
    }

    private void setPreferredNetworkSetting(int mode) {
        TelephonyManager.putIntAtIndex(mPhone.getContext().getContentResolver(),
                android.provider.Settings.Global.PREFERRED_NETWORK_MODE, mPhone.getPhoneId(), mode);
    }

    private int getUserNetworkSetting() {
        int userNwMode = SubscriptionController.getInstance().getUserNwMode(mPhone.getSubId());
        if (userNwMode == SubscriptionManager.DEFAULT_NW_MODE) {
            return getPreferredNetworkSetting();
        }
        return userNwMode;
    }

    private void setUserNetworkSetting(int nwMode) {
        SubscriptionController.getInstance().setUserNwMode(mPhone.getSubId(), nwMode);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mUnavailable) {
            return;
        }

        // upon resumption from the sub-activity, make sure we re-enable the
        // preferences.
        getPreferenceScreen().setEnabled(true);

        // Set UI state in onResume because a user could go home, launch some
        // app to change this setting's backend, and re-launch this settings app
        // and the UI state would be inconsistent with actual state
        mButtonDataRoam.setChecked(mPhone.getDataRoamingEnabled());

        // Disable network buttons until we get a callback from the modem
        disableNetworkButtons();

        if (getPreferenceScreen().findPreference(BUTTON_PREFERED_NETWORK_MODE) != null)  {
            mPhone.getPreferredNetworkType(mHandler.obtainMessage(
                    MyHandler.MESSAGE_GET_PREFERRED_NETWORK_TYPE));
        }

        if (getPreferenceScreen().findPreference(BUTTON_ENABLED_NETWORKS_KEY) != null)  {
            mPhone.getPreferredNetworkType(mHandler.obtainMessage(
                    MyHandler.MESSAGE_GET_PREFERRED_NETWORK_TYPE));
        }

        mObserver.register(true);
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (ImsManager.isVolteEnabledByPlatform(this)
                && ImsManager.isVolteProvisionedOnDevice(this)) {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            tm.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
        }
        mObserver.register(false);
    }

    /**
     * Implemented to support onPreferenceChangeListener to look for preference
     * changes specifically on CLIR.
     *
     * @param preference is the preference to be changed, should be mButtonCLIR.
     * @param objValue should be the value of the selection, NOT its localized
     * display value.
     */
    public boolean onPreferenceChange(Preference preference, Object objValue) {
        if (preference == mButtonPreferredNetworkMode) {
            //NOTE onPreferenceChange seems to be called even if there is no change
            //Check if the button value is changed from the System.Setting
            mButtonPreferredNetworkMode.setValue((String) objValue);
            int buttonNetworkMode;
            buttonNetworkMode = Integer.valueOf((String) objValue).intValue();
            int settingsNetworkMode = getPreferredNetworkSetting();
            if (buttonNetworkMode != settingsNetworkMode) {
                int modemNetworkMode;
                // if new mode is invalid ignore it
                switch (buttonNetworkMode) {
                    case Phone.NT_MODE_WCDMA_PREF:
                    case Phone.NT_MODE_GSM_ONLY:
                    case Phone.NT_MODE_WCDMA_ONLY:
                    case Phone.NT_MODE_GSM_UMTS:
                    case Phone.NT_MODE_CDMA:
                    case Phone.NT_MODE_CDMA_NO_EVDO:
                    case Phone.NT_MODE_EVDO_NO_CDMA:
                    case Phone.NT_MODE_GLOBAL:
                    case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
                    case Phone.NT_MODE_LTE_GSM_WCDMA:
                    case Phone.NT_MODE_LTE_CDMA_EVDO_GSM_WCDMA:
                    case Phone.NT_MODE_LTE_ONLY:
                    case Phone.NT_MODE_LTE_WCDMA:
                    case Phone.NT_MODE_TD_SCDMA_ONLY:
                    case Phone.NT_MODE_TD_SCDMA_WCDMA:
                    case Phone.NT_MODE_TD_SCDMA_LTE:
                    case Phone.NT_MODE_TD_SCDMA_GSM:
                    case Phone.NT_MODE_TD_SCDMA_GSM_LTE:
                    case Phone.NT_MODE_TD_SCDMA_GSM_WCDMA:
                    case Phone.NT_MODE_TD_SCDMA_WCDMA_LTE:
                    case Phone.NT_MODE_TD_SCDMA_GSM_WCDMA_LTE:
                    case Phone.NT_MODE_TD_SCDMA_CDMA_EVDO_GSM_WCDMA:
                    case Phone.NT_MODE_TD_SCDMA_LTE_CDMA_EVDO_GSM_WCDMA:
                        // This is one of the modes we recognize
                        modemNetworkMode = buttonNetworkMode;
                        break;
                    default:
                        loge("Invalid Network Mode (" + buttonNetworkMode + ") chosen. Ignore.");
                        return true;
                }

                //Set the modem network mode
                if (!isSecondarySubInDsds()) {
                    setPreferredNetworkType(modemNetworkMode);
                }

                // Set the user configured network mode
                setUserNetworkSetting(modemNetworkMode);

                UpdatePreferredNetworkModeSummary(buttonNetworkMode);
            }
        } else if (preference == mButtonEnabledNetworks) {
            mButtonEnabledNetworks.setValue((String) objValue);
            int buttonNetworkMode;
            buttonNetworkMode = Integer.valueOf((String) objValue).intValue();
            if (DBG) log("buttonNetworkMode: " + buttonNetworkMode);
            int settingsNetworkMode = getUserNetworkSetting();
            if (buttonNetworkMode != settingsNetworkMode) {
                int modemNetworkMode;
                // if new mode is invalid ignore it
                switch (buttonNetworkMode) {
                    case Phone.NT_MODE_WCDMA_PREF:
                    case Phone.NT_MODE_GSM_ONLY:
                    case Phone.NT_MODE_LTE_GSM_WCDMA:
                    case Phone.NT_MODE_LTE_CDMA_EVDO_GSM_WCDMA:
                    case Phone.NT_MODE_CDMA:
                    case Phone.NT_MODE_CDMA_NO_EVDO:
                    case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
                    case Phone.NT_MODE_WCDMA_ONLY:
                    case Phone.NT_MODE_GSM_UMTS:
                    case Phone.NT_MODE_EVDO_NO_CDMA:
                    case Phone.NT_MODE_GLOBAL:
                    case Phone.NT_MODE_LTE_ONLY:
                    case Phone.NT_MODE_LTE_WCDMA:
                    case Phone.NT_MODE_TD_SCDMA_ONLY:
                    case Phone.NT_MODE_TD_SCDMA_WCDMA:
                    case Phone.NT_MODE_TD_SCDMA_LTE:
                    case Phone.NT_MODE_TD_SCDMA_GSM:
                    case Phone.NT_MODE_TD_SCDMA_GSM_LTE:
                    case Phone.NT_MODE_TD_SCDMA_GSM_WCDMA:
                    case Phone.NT_MODE_TD_SCDMA_WCDMA_LTE:
                    case Phone.NT_MODE_TD_SCDMA_GSM_WCDMA_LTE:
                    case Phone.NT_MODE_TD_SCDMA_CDMA_EVDO_GSM_WCDMA:
                    case Phone.NT_MODE_TD_SCDMA_LTE_CDMA_EVDO_GSM_WCDMA:
                        // This is one of the modes we recognize
                        modemNetworkMode = buttonNetworkMode;
                        break;
                    default:
                        loge("Invalid Network Mode (" + buttonNetworkMode + ") chosen. Ignore.");
                        return true;
                }

                //Set the modem network mode
                if (!isSecondarySubInDsds()) {
                    setPreferredNetworkType(modemNetworkMode);
                }

                // Set the user configured network mode
                setUserNetworkSetting(modemNetworkMode);

                UpdateEnabledNetworksValueAndSummary(modemNetworkMode);
            }
        } else if (preference == mButton4glte) {
            SwitchPreference ltePref = (SwitchPreference)preference;
            ltePref.setChecked(!ltePref.isChecked());
            setIMS(ltePref.isChecked());

            ImsManager imsMan = ImsManager.getInstance(getBaseContext(),
                    SubscriptionManager.getDefaultVoicePhoneId());
            if (imsMan != null) {

                try {
                    imsMan.setAdvanced4GMode(ltePref.isChecked());
                } catch (ImsException ie) {
                    // do nothing
                }
            }
        } else if (preference == mButtonDataRoam) {
            if (DBG) log("onPreferenceTreeClick: preference == mButtonDataRoam.");

            //normally called on the toggle click
            if (!mButtonDataRoam.isChecked()) {
                // First confirm with a warning dialog about charges
                mOkClicked = false;
                new AlertDialog.Builder(this).setMessage(
                        getResources().getString(R.string.roaming_warning))
                        .setTitle(android.R.string.dialog_alert_title)
                        .setIconAttribute(android.R.attr.alertDialogIcon)
                        .setPositiveButton(android.R.string.yes, this)
                        .setNegativeButton(android.R.string.no, this)
                        .show()
                        .setOnDismissListener(this);
            } else {
                mPhone.setDataRoamingEnabled(false);
            }
            return true;
        } else if (preference == mButtonRoamingOptions) {
            int idx = Integer.valueOf((String) objValue).intValue();
            log("New mode: " + idx + " current mode: " + getRoamingMode());
            if (idx == getRoamingMode())
                return true;
            if (idx == ROAMING_MODE_DISABLED) {
                // Disabled, so restore the preferred network list to default
                handleRoamingModeChange(idx);
            } else {
                // Display there is a warning message for this mode
                int warningResId;
                int modeEnableResId;
                if (mPhone.getPhoneId() == 0) {
                    warningResId = R.array.roaming_mode_warnings_slot1;
                    modeEnableResId = R.array.roaming_mode_enable_slot1;
                } else {
                    warningResId = R.array.roaming_mode_warnings_slot2;
                    modeEnableResId = R.array.roaming_mode_enable_slot2;
                }
                String[] warnings = null;
                String[] modeEnable = null;
                try {
                    warnings = getResources().getStringArray(warningResId);
                    modeEnable = getResources().getStringArray(modeEnableResId);
                } catch(Resources.NotFoundException ex) {
                    loge("Resource error " + ex.toString());
                }
                if (warnings == null || warnings.length <= idx ||
                        TextUtils.isEmpty(warnings[idx])) {
                    // No warning needed, change the mode now
                    handleRoamingModeChange(idx);
                    return true;
                }

                // Check if this roaming mode is enabled. This allows us to overlay the settings.
                // For example, we can allow a roaming mode only if a specific SIM card is inserted,
                // by setting the roaming_mode_enable_slotX to 0 (false) and overlaying it in a
                // mccXXX xml file. When a mode is disabled, the setting UI only displays a warning
                // dialog but does NO allow any change (e.g. Please insert XXX SIM card to enable
                // XXX roaming mode)
                final boolean enabled = !(modeEnable != null && modeEnable.length > idx &&
                        !TextUtils.isEmpty(modeEnable[idx]) && modeEnable[idx].equals("0"));
                // Display warnings before changing the roaming mode
                AlertDialog.Builder b = new AlertDialog.Builder(this);
                mNewRoamMode = idx;
                if (enabled) {
                    // "Cancel" buttions are available on if mode is enabled
                    b.setNegativeButton(android.R.string.cancel,
                            new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int whichButton) {
                            mButtonRoamingOptions.setValue(Integer.toString(getRoamingMode()));
                        }
                    });
                }
                b.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int whichButton) {
                        // Restore the previous choice if this mode is disabled
                        if (enabled)
                            handleRoamingModeChange(mNewRoamMode);
                        else
                            mButtonRoamingOptions.setValue(Integer.toString(getRoamingMode()));
                    }
                });
                b.setTitle(android.R.string.dialog_alert_title);
                b.setMessage(warnings[idx]);
                b.setIconAttribute(android.R.attr.alertDialogIcon);
                b.create().show();
            }
            return true;
        }

        // always let the preference setting proceed.
        return true;
    }

    private void setPreferredNetworkType(int modemNetworkMode) {
        disableNetworkButtons();
        mPhone.setPreferredNetworkType(modemNetworkMode, mHandler
                .obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
    }

    private class MyHandler extends Handler {

        static final int MESSAGE_GET_PREFERRED_NETWORK_TYPE = 0;
        static final int MESSAGE_SET_PREFERRED_NETWORK_TYPE = 1;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_PREFERRED_NETWORK_TYPE:
                    handleGetPreferredNetworkTypeResponse(msg);
                    break;

                case MESSAGE_SET_PREFERRED_NETWORK_TYPE:
                    handleSetPreferredNetworkTypeResponse(msg);
                    break;
            }
        }

        private void handleGetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                int modemNetworkMode = ((int[])ar.result)[0];

                if (DBG) {
                    log ("handleGetPreferredNetworkTypeResponse: modemNetworkMode = " +
                            modemNetworkMode);
                }

                int settingsNetworkMode = getPreferredNetworkSetting();

                if (DBG) {
                    log("handleGetPreferredNetworkTypeReponse: settingsNetworkMode = " +
                            settingsNetworkMode);
                }

                if (isValidModemNetworkMode(mPhone, modemNetworkMode)) {
                    if (DBG) {
                        log("handleGetPreferredNetworkTypeResponse: if 1: modemNetworkMode = " +
                                modemNetworkMode);
                    }

                    //check changes in modemNetworkMode
                    if (modemNetworkMode != settingsNetworkMode) {
                        if (DBG) {
                            log("handleGetPreferredNetworkTypeResponse: if 2: " +
                                    "modemNetworkMode != settingsNetworkMode");
                        }
                        setPreferredNetworkSetting(modemNetworkMode);
                        settingsNetworkMode = modemNetworkMode;

                        if (DBG) { log("handleGetPreferredNetworkTypeResponse: if 2: " +
                                "settingsNetworkMode = " + settingsNetworkMode);
                        }
                    }

                    if (mButtonPreferredNetworkMode != null) {
                        UpdatePreferredNetworkModeSummary(modemNetworkMode);
                    } else if (mButtonEnabledNetworks != null) {
                        UpdateEnabledNetworksValueAndSummary(modemNetworkMode);
                    }
                } else {
                    if (DBG) log("handleGetPreferredNetworkTypeResponse: else: reset to default");
                    resetNetworkModeToDefault();
                }
            }
            enableNetworkButtons();
        }

        private void handleSetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;
            int networkMode;

            if (ar.exception == null) {
                if (mButtonPreferredNetworkMode != null) {
                    networkMode = Integer.valueOf(
                            mButtonPreferredNetworkMode.getValue()).intValue();
                    setPreferredNetworkSetting(networkMode);
                } else if (mButtonEnabledNetworks != null) {
                    networkMode = Integer.valueOf(
                            mButtonEnabledNetworks.getValue()).intValue();
                    setPreferredNetworkSetting(networkMode);
                }
                enableNetworkButtons();
            } else {
                mPhone.getPreferredNetworkType(obtainMessage(MESSAGE_GET_PREFERRED_NETWORK_TYPE));
            }
        }

        private void resetNetworkModeToDefault() {
            //set the Settings.System
            setPreferredNetworkSetting(preferredNetworkMode);

            //Set the Modem
            mPhone.setPreferredNetworkType(preferredNetworkMode,
                    this.obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
        }
    }

    private final class NetworkModeObserver extends ContentObserver {
        private final Uri PREFERRED_URI =
                Settings.Global.getUriFor(Settings.Global.PREFERRED_NETWORK_MODE);
        private final Uri USER_CONFIGURED_URI =
                SubscriptionManager.CONTENT_URI; // TelephonyProvider siminfo table.

        public NetworkModeObserver(Handler handler) {
            super(handler);
        }

        public void register(boolean register) {
            final ContentResolver cr = getContentResolver();
            if (register) {
                cr.registerContentObserver(PREFERRED_URI, false, this);
                cr.registerContentObserver(USER_CONFIGURED_URI, false, this);
            } else {
                cr.unregisterContentObserver(this);
            }
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange,  uri);
            if (mButtonPreferredNetworkMode != null) {
                UpdatePreferredNetworkModeSummary(getPreferredNetworkSetting());
            } else if (mButtonEnabledNetworks != null) {
                UpdateEnabledNetworksValueAndSummary(getPreferredNetworkSetting());
            }
        }
    }

    /* package */ static boolean isValidModemNetworkMode(Phone phone, int modemNetworkMode) {
        switch (modemNetworkMode) {
            case Phone.NT_MODE_WCDMA_PREF:
            case Phone.NT_MODE_GSM_ONLY:
            case Phone.NT_MODE_WCDMA_ONLY:
            case Phone.NT_MODE_GSM_UMTS:
            case Phone.NT_MODE_CDMA:
            case Phone.NT_MODE_CDMA_NO_EVDO:
            case Phone.NT_MODE_EVDO_NO_CDMA:
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
            case Phone.NT_MODE_LTE_GSM_WCDMA:
            case Phone.NT_MODE_LTE_CDMA_EVDO_GSM_WCDMA:
            case Phone.NT_MODE_LTE_ONLY:
            case Phone.NT_MODE_LTE_WCDMA:
            case Phone.NT_MODE_TD_SCDMA_ONLY:
            case Phone.NT_MODE_TD_SCDMA_WCDMA:
            case Phone.NT_MODE_TD_SCDMA_LTE:
            case Phone.NT_MODE_TD_SCDMA_GSM:
            case Phone.NT_MODE_TD_SCDMA_GSM_LTE:
            case Phone.NT_MODE_TD_SCDMA_GSM_WCDMA:
            case Phone.NT_MODE_TD_SCDMA_WCDMA_LTE:
            case Phone.NT_MODE_TD_SCDMA_GSM_WCDMA_LTE:
            case Phone.NT_MODE_TD_SCDMA_CDMA_EVDO_GSM_WCDMA:
            case Phone.NT_MODE_TD_SCDMA_LTE_CDMA_EVDO_GSM_WCDMA:
                return true;
        }

        if (modemNetworkMode == Phone.NT_MODE_GLOBAL) {
            if (phone.getLteOnCdmaMode() == PhoneConstants.LTE_ON_CDMA_TRUE) {
                return true;
            }
            if (phone.getContext().getResources().getBoolean(R.bool.world_phone)) {
                return true;
            }
        }

        return false;
    }

    private void disableNetworkButtons() {
        if (mButtonPreferredNetworkMode != null) {
            mButtonPreferredNetworkMode.setEnabled(false);
        }  else if (mButtonEnabledNetworks != null) {
            mButtonEnabledNetworks.setEnabled(false);
        }
    }

    private void enableNetworkButtons() {
        if (mButtonPreferredNetworkMode != null) {
            mButtonPreferredNetworkMode.setEnabled(true);
        }  else if (mButtonEnabledNetworks != null) {
            mButtonEnabledNetworks.setEnabled(true);
        }
    }

    private void UpdatePreferredNetworkModeSummary(int NetworkMode) {
        int summaryResId = mPreferredNetworkModeSummaries.get(NetworkMode,
                R.string.preferred_network_mode_global_summary_cm);
        int confSummaryResId = mPreferredNetworkModeSummaries.get(
                getUserNetworkSetting(), -1);
        if (summaryResId != confSummaryResId && confSummaryResId != -1) {
            String summary = getString(R.string.preferred_network_mode_summary_mismatch,
                    getString(confSummaryResId), getString(summaryResId));
            mButtonPreferredNetworkMode.setSummary(summary);
        } else {
            mButtonPreferredNetworkMode.setSummary(summaryResId);
        }
    }

    private void UpdateEnabledNetworksValueAndSummary(int NetworkMode) {
        int userConfiguredMode = getUserNetworkSetting();
        int networkModeSummaryResId = mEnabledNetworksSummaries.get(NetworkMode, -1);
        int userNetworkModeSummaryResId = mEnabledNetworksSummaries.get(userConfiguredMode, -1);
        if (networkModeSummaryResId == -1) {
            loge("Invalid Network Mode (" + NetworkMode + "). Ignore.");
            mButtonEnabledNetworks.setSummary(null);
        } else {
            mButtonEnabledNetworks.setValue(Integer.toString(userConfiguredMode));
            if (networkModeSummaryResId == userNetworkModeSummaryResId) {
                mButtonEnabledNetworks.setSummary(networkModeSummaryResId);
            } else {
                String summary = getString(R.string.preferred_network_mode_summary_mismatch,
                        getString(userNetworkModeSummaryResId),
                        getString(networkModeSummaryResId));
                mButtonEnabledNetworks.setSummary(summary);
            }
        }
    }

    private int getRoamingMode()
    {
        int phoneId = mPhone.getPhoneId();
        SharedPreferences pref = getSharedPreferences(SHARED_PREFERENCES, MODE_PRIVATE);
        String key = SHARED_PREFERENCES_ROAMING_MODE + Integer.toString(phoneId);
        return pref.getInt(key, ROAMING_MODE_DISABLED);
    }

    private String[] getStringArrayFrom2DRes(int resId, int idx)
    {
        String[] strArray = null;
        TypedArray typedArray = null;
        Resources res = getResources();

        try {
            typedArray = res.obtainTypedArray(resId);
            if (typedArray.length() > idx)
                strArray = res.getStringArray(typedArray.getResourceId(idx, 0));
        } catch (Resources.NotFoundException ex) {
            loge("Error parsing resource " + ex.toString());
        }
        if (typedArray != null)
            typedArray.recycle();
        return strArray;
    }

    private void handleRoamingModeChange(int newMode)
    {
        int currentMode = getRoamingMode();
        log("Roaming mode: current mode " + currentMode + " new mode " + newMode);
        if (getRoamingMode() == newMode)
            return;
        int phoneId = mPhone.getPhoneId();
        SharedPreferences pref = getSharedPreferences(SHARED_PREFERENCES, MODE_PRIVATE);
        // Save current roaming mode
        String key;
        key = SHARED_PREFERENCES_ROAMING_MODE + Integer.toString(phoneId);
        pref.edit().putInt(key, newMode).commit();

        // Update the preferred network mode list
        updatePreferredNetworkModeList(newMode);

        // Save current preferred network mode, restore the saved preferred network mode of
        // the new roaming mode
        key = SHARED_PREFERENCES_PREFNET + Integer.toString(phoneId) + Integer.toString(currentMode);
        pref.edit().putInt(key, getPreferredNetworkSetting()).commit();
        key = SHARED_PREFERENCES_PREFNET + Integer.toString(phoneId) + Integer.toString(newMode);
        int prefNetMode = pref.getInt(key, -1);
        if (prefNetMode != -1) {
            setPreferredNetworkType(prefNetMode);
            UpdateEnabledNetworksValueAndSummary(prefNetMode);
        }
    }

    private void updatePreferredNetworkModeList(int idx)
    {
        log("updatePreferredNetworkModeList: new list id " + idx);

        // Restore the default list if roaming mode is disabled
        if (idx == ROAMING_MODE_DISABLED) {
            mButtonEnabledNetworks.setEntries(mDefaultPrefNetEntries);
            mButtonEnabledNetworks.setEntryValues(mDefaultPrefNetValues);
            return;
        }

        // Otherwise load the new network modes from xml
        int phoneId = mPhone.getPhoneId();
        int choicesResId;
        int valuesResId;
        if (phoneId == 0) {
            choicesResId = R.array.roaming_mode_preferred_network_choices_slot1;
            valuesResId = R.array.roaming_mode_preferred_network_values_slot1;
        } else {
            choicesResId = R.array.roaming_mode_preferred_network_choices_slot2;
            valuesResId = R.array.roaming_mode_preferred_network_values_slot2;
        }

        String[] roamingNetworkChoices = getStringArrayFrom2DRes(choicesResId, idx);
        String[] roamingNetworkValues = getStringArrayFrom2DRes(valuesResId, idx);
        if (roamingNetworkChoices == null || roamingNetworkValues == null ||
                roamingNetworkChoices.length != roamingNetworkValues.length) {
            loge("Roaming mode resource error");
            return;
        }

        // Concatenate the new roaming network mode array to existing array
        String[] entries = new String[mDefaultPrefNetEntries.length + roamingNetworkChoices.length];
        System.arraycopy(mDefaultPrefNetEntries, 0, entries, 0, mDefaultPrefNetEntries.length);
        System.arraycopy(roamingNetworkChoices, 0, entries,
                mDefaultPrefNetEntries.length, roamingNetworkChoices.length);
        String[] values = new String[mDefaultPrefNetValues.length + roamingNetworkValues.length];
        System.arraycopy(mDefaultPrefNetValues, 0, values, 0, mDefaultPrefNetValues.length);
        System.arraycopy(roamingNetworkValues, 0, values,
                mDefaultPrefNetValues.length, roamingNetworkValues.length);

        // Update the preferred network mode list
        mButtonEnabledNetworks.setEntries(entries);
        mButtonEnabledNetworks.setEntryValues(values);
        log("New preferred network mode choices " + Arrays.toString(entries));
        log("New preferred network mode values " + Arrays.toString(values));
    }

    private boolean isSecondarySubInDsds() {
        if (!mIsDsdsSetup) {
            return false;
        }
        SubscriptionManager subManager = SubscriptionManager.from(this);
        return mSubId != subManager.getDefaultDataSubId();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch(requestCode) {
        case REQUEST_CODE_EXIT_ECM:
            Boolean isChoiceYes =
                data.getBooleanExtra(EmergencyCallbackModeExitDialog.EXTRA_EXIT_ECM_RESULT, false);
            if (isChoiceYes) {
                // If the phone exits from ECM mode, show the CDMA Options
                mCdmaOptions.showDialog(mClickedPreference);
            } else {
                // do nothing
            }
            break;

        default:
            break;
        }
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }

    private static void loge(String msg) {
        Log.e(LOG_TAG, msg);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {  // See ActionBar#setDisplayHomeAsUpEnabled()
            // Commenting out "logical up" capability. This is a workaround for issue 5278083.
            //
            // Settings app may not launch this activity via UP_ACTIVITY_CLASS but the other
            // Activity that looks exactly same as UP_ACTIVITY_CLASS ("SubSettings" Activity).
            // At that moment, this Activity launches UP_ACTIVITY_CLASS on top of the Activity.
            // which confuses users.
            // TODO: introduce better mechanism for "up" capability here.
            /*Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(UP_ACTIVITY_PACKAGE, UP_ACTIVITY_CLASS);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);*/
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
