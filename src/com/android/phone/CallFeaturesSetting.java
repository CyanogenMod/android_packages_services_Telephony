/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (C) 2014 The CyanogenMod Project
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
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import com.android.ims.ImsConfig;
import com.android.ims.ImsManager;
import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.phone.common.util.SettingsUtil;
import com.android.phone.settings.AccountSelectionPreference;
import com.android.phone.settings.PhoneAccountSettingsFragment;
import com.android.phone.settings.VoicemailSettingsActivity;
import com.android.phone.settings.fdn.FdnSetting;
import com.android.services.telephony.sip.SipUtil;
import com.android.internal.telephony.util.BlacklistUtils;

import java.lang.String;
import java.util.ArrayList;
import java.util.List;

/**
 * Top level "Call settings" UI; see res/xml/call_feature_setting.xml
 *
 * This preference screen is the root of the "Call settings" hierarchy available from the Phone
 * app; the settings here let you control various features related to phone calls (including
 * voicemail settings, the "Respond via SMS" feature, and others.)  It's used only on
 * voice-capable phone devices.
 *
 * Note that this activity is part of the package com.android.phone, even
 * though you reach it from the "Phone" app (i.e. DialtactsActivity) which
 * is from the package com.android.contacts.
 *
 * For the "Mobile network settings" screen under the main Settings app,
 * See {@link MobileNetworkSettings}.
 *
 * @see com.android.phone.MobileNetworkSettings
 */
public class CallFeaturesSetting extends PreferenceActivity
        implements Preference.OnPreferenceChangeListener {
    private static final String LOG_TAG = "CallFeaturesSetting";
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);
    // STOPSHIP if true. Flag to override behavior default behavior to hide VT setting.
    private static final boolean ENABLE_VT_FLAG = true;

    // String keys for preference lookup
    // TODO: Naming these "BUTTON_*" is confusing since they're not actually buttons(!)
    // TODO: Consider moving these strings to strings.xml, so that they are not duplicated here and
    // in the layout files. These strings need to be treated carefully; if the setting is
    // persistent, they are used as the key to store shared preferences and the name should not be
    // changed unless the settings are also migrated.
    private static final String VOICEMAIL_SETTING_SCREEN_PREF_KEY = "button_voicemail_category_key";
    private static final String BUTTON_FDN_KEY   = "button_fdn_key";
    private static final String BUTTON_RETRY_KEY       = "button_auto_retry_key";
    private static final String BUTTON_GSM_UMTS_OPTIONS = "button_gsm_more_expand_key";
    private static final String BUTTON_CDMA_OPTIONS = "button_cdma_more_expand_key";
    private static final String IMS_SETTINGS_KEY      = "ims_settings_key";
    private static final String QTI_IMS_PACKAGE_NAME = "com.qualcomm.qti.ims";

    private static final String PHONE_ACCOUNT_SETTINGS_KEY =
            "phone_account_settings_preference_screen";

    private static final String ENABLE_VIDEO_CALLING_KEY = "button_enable_video_calling";

    private Phone mPhone;
    private SubscriptionInfoHelper mSubscriptionInfoHelper;
    private TelecomManager mTelecomManager;

    // Blacklist support
    private static final String BUTTON_BLACKLIST = "button_blacklist";

    private CheckBoxPreference mButtonAutoRetry;
    private PreferenceScreen mVoicemailSettingsScreen;
    private CheckBoxPreference mEnableVideoCalling;
    private PreferenceScreen mButtonBlacklist;

    /*
     * Click Listeners, handle click based on objects attached to UI.
     */

    // Click listener for all toggle events
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mButtonAutoRetry) {
            android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Global.CALL_AUTO_RETRY,
                    mButtonAutoRetry.isChecked() ? 1 : 0);
            return true;
        }
        return false;
    }

    /**
     * Implemented to support onPreferenceChangeListener to look for preference
     * changes.
     *
     * @param preference is the preference to be changed
     * @param objValue should be the value of the selection, NOT its localized
     * display value.
     */
    @Override
    public boolean onPreferenceChange(Preference preference, Object objValue) {
        if (DBG) log("onPreferenceChange: \"" + preference + "\" changed to \"" + objValue + "\"");

        if (preference == mEnableVideoCalling) {
            if (ImsManager.isEnhanced4gLteModeSettingEnabledByUser(mPhone.getContext())) {
                PhoneGlobals.getInstance().phoneMgr.enableVideoCalling((boolean) objValue);
            } else {
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                DialogInterface.OnClickListener networkSettingsClickListener =
                        new Dialog.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                startActivity(new Intent(mPhone.getContext(),
                                        com.android.phone.MobileNetworkSettings.class));
                            }
                        };
                builder.setMessage(getResources().getString(
                                R.string.enable_video_calling_dialog_msg))
                        .setNeutralButton(getResources().getString(
                                R.string.enable_video_calling_dialog_settings),
                                networkSettingsClickListener)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return false;
            }
        }

        // Always let the preference setting proceed.
        return true;
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        if (DBG) log("onCreate: Intent is " + getIntent());

        // Make sure we are running as the primary user.
        if (UserHandle.myUserId() != UserHandle.USER_OWNER) {
            Toast.makeText(this, R.string.call_settings_primary_user_only,
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        mSubscriptionInfoHelper = new SubscriptionInfoHelper(this, getIntent());
        mSubscriptionInfoHelper.setActionBarTitle(
                getActionBar(), getResources(), R.string.call_settings_with_label);
        mPhone = mSubscriptionInfoHelper.getPhone();
        mTelecomManager = TelecomManager.from(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        PreferenceScreen preferenceScreen = getPreferenceScreen();
        if (preferenceScreen != null) {
            preferenceScreen.removeAll();
        }

        addPreferencesFromResource(R.xml.call_feature_setting);

        TelephonyManager telephonyManager =
                (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

        Preference phoneAccountSettingsPreference = findPreference(PHONE_ACCOUNT_SETTINGS_KEY);
        if (telephonyManager.isMultiSimEnabled() || !SipUtil.isVoipSupported(mPhone.getContext())) {
            getPreferenceScreen().removePreference(phoneAccountSettingsPreference);
        }

        PreferenceScreen prefSet = getPreferenceScreen();
        mVoicemailSettingsScreen =
                (PreferenceScreen) findPreference(VOICEMAIL_SETTING_SCREEN_PREF_KEY);
        mVoicemailSettingsScreen.setIntent(mSubscriptionInfoHelper.getIntent(
                VoicemailSettingsActivity.class));

        mButtonAutoRetry = (CheckBoxPreference) findPreference(BUTTON_RETRY_KEY);

        mEnableVideoCalling = (CheckBoxPreference) findPreference(ENABLE_VIDEO_CALLING_KEY);

        PersistableBundle carrierConfig =
                PhoneGlobals.getInstance().getCarrierConfigForSubId(mPhone.getSubId());

        if (carrierConfig.getBoolean(CarrierConfigManager.KEY_AUTO_RETRY_ENABLED_BOOL)) {
            mButtonAutoRetry.setOnPreferenceChangeListener(this);
            int autoretry = Settings.Global.getInt(
                    getContentResolver(), Settings.Global.CALL_AUTO_RETRY, 0);
            mButtonAutoRetry.setChecked(autoretry != 0);
        } else {
            prefSet.removePreference(mButtonAutoRetry);
            mButtonAutoRetry = null;
        }

        Preference cdmaOptions = prefSet.findPreference(BUTTON_CDMA_OPTIONS);
        Preference gsmOptions = prefSet.findPreference(BUTTON_GSM_UMTS_OPTIONS);
        Preference fdnButton = prefSet.findPreference(BUTTON_FDN_KEY);
        fdnButton.setIntent(mSubscriptionInfoHelper.getIntent(FdnSetting.class));
        if (carrierConfig.getBoolean(CarrierConfigManager.KEY_WORLD_PHONE_BOOL)) {
            cdmaOptions.setIntent(mSubscriptionInfoHelper.getIntent(CdmaCallOptions.class));
            gsmOptions.setIntent(mSubscriptionInfoHelper.getIntent(GsmUmtsCallOptions.class));
        } else {
            prefSet.removePreference(cdmaOptions);
            prefSet.removePreference(gsmOptions);

            int phoneType = mPhone.getPhoneType();
            if (carrierConfig.getBoolean(CarrierConfigManager.KEY_HIDE_CARRIER_NETWORK_SETTINGS_BOOL)) {
                prefSet.removePreference(fdnButton);
            } else {
                if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    prefSet.removePreference(fdnButton);

                    if (!carrierConfig.getBoolean(
                            CarrierConfigManager.KEY_VOICE_PRIVACY_DISABLE_UI_BOOL)) {
                        addPreferencesFromResource(R.xml.cdma_call_privacy);
                    }
                } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {

                    if (carrierConfig.getBoolean(
                            CarrierConfigManager.KEY_ADDITIONAL_CALL_SETTING_BOOL)) {
                        addPreferencesFromResource(R.xml.gsm_umts_call_options);
                        GsmUmtsCallOptions.init(prefSet, mSubscriptionInfoHelper);
                    }
                } else {
                    throw new IllegalStateException("Unexpected phone type: " + phoneType);
                }
            }
        }

        if (ImsManager.isVtEnabledByPlatform(mPhone.getContext()) && ENABLE_VT_FLAG) {
            boolean currentValue =
                    ImsManager.isEnhanced4gLteModeSettingEnabledByUser(mPhone.getContext())
                    ? PhoneGlobals.getInstance().phoneMgr.isVideoCallingEnabled(
                            getOpPackageName()) : false;
            mEnableVideoCalling.setChecked(currentValue);
            mEnableVideoCalling.setOnPreferenceChangeListener(this);
        } else {
            prefSet.removePreference(mEnableVideoCalling);
        }

        if (ImsManager.isVolteEnabledByPlatform(this) &&
                !carrierConfig.getBoolean(
                        CarrierConfigManager.KEY_CARRIER_VOLTE_TTY_SUPPORTED_BOOL)) {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            /* tm.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE); */
        }

        Preference imsSettings = findPreference(IMS_SETTINGS_KEY);

        if (!isPackageInstalled(this, QTI_IMS_PACKAGE_NAME)) {
            prefSet.removePreference(imsSettings);
        }

        Preference wifiCallingSettings = findPreference(
                getResources().getString(R.string.wifi_calling_settings_key));

        final PhoneAccountHandle simCallManager = mTelecomManager.getSimCallManager();
        if (simCallManager != null) {
            Intent intent = PhoneAccountSettingsFragment.buildPhoneAccountConfigureIntent(
                    this, simCallManager);
            if (intent != null) {
                wifiCallingSettings.setTitle(R.string.wifi_calling);
                wifiCallingSettings.setSummary(null);
                wifiCallingSettings.setIntent(intent);
            } else {
                prefSet.removePreference(wifiCallingSettings);
            }
        } else if (!ImsManager.isWfcEnabledByPlatform(mPhone.getContext())) {
            prefSet.removePreference(wifiCallingSettings);
        } else {
            int resId = com.android.internal.R.string.wifi_calling_off_summary;
            if (ImsManager.isWfcEnabledByUser(mPhone.getContext())) {
                int wfcMode = ImsManager.getWfcMode(mPhone.getContext());
                switch (wfcMode) {
                    case ImsConfig.WfcModeFeatureValueConstants.WIFI_ONLY:
                        resId = com.android.internal.R.string.wfc_mode_wifi_only_summary;
                        break;
                    case ImsConfig.WfcModeFeatureValueConstants.CELLULAR_PREFERRED:
                        resId = com.android.internal.R.string.wfc_mode_cellular_preferred_summary;
                        break;
                    case ImsConfig.WfcModeFeatureValueConstants.WIFI_PREFERRED:
                        resId = com.android.internal.R.string.wfc_mode_wifi_preferred_summary;
                        break;
                    default:
                        if (DBG) log("Unexpected WFC mode value: " + wfcMode);
                }
            }
            wifiCallingSettings.setSummary(resId);
        }
        updateBlacklistSummary();
    }

    public static boolean isPackageInstalled(Context context, String pkg, boolean ignoreState) {
        if (pkg != null) {
            try {
                PackageInfo pi = context.getPackageManager().getPackageInfo(pkg, 0);
                if (!pi.applicationInfo.enabled && !ignoreState) {
                    return false;
                }
            } catch (NameNotFoundException e) {
                return false;
            }
        }

        return true;
    }

    public static boolean isPackageInstalled(Context context, String pkg) {
        return isPackageInstalled(context, pkg, true);
    }

    @Override
    protected void onNewIntent(Intent newIntent) {
        setIntent(newIntent);

        mSubscriptionInfoHelper = new SubscriptionInfoHelper(this, getIntent());
        mSubscriptionInfoHelper.setActionBarTitle(
                getActionBar(), getResources(), R.string.call_settings_with_label);
        mPhone = mSubscriptionInfoHelper.getPhone();
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }

    private void updateBlacklistSummary() {
        if (mButtonBlacklist != null) {
            if (BlacklistUtils.isBlacklistEnabled(this)) {
                mButtonBlacklist.setSummary(R.string.blacklist_summary);
            } else {
                mButtonBlacklist.setSummary(R.string.blacklist_summary_disabled);
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {  // See ActionBar#setDisplayHomeAsUpEnabled()
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Finish current Activity and go up to the top level Settings ({@link CallFeaturesSetting}).
     * This is useful for implementing "HomeAsUp" capability for second-level Settings.
     */
    public static void goUpToTopLevelSetting(
            Activity activity, SubscriptionInfoHelper subscriptionInfoHelper) {
        Intent intent = subscriptionInfoHelper.getIntent(CallFeaturesSetting.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.startActivity(intent);
        activity.finish();
    }
}
