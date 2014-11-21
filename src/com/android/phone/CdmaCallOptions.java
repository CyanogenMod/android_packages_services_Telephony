/*
 * Copyright (C) 2009 The Android Open Source Project
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

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;

import android.content.DialogInterface;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

public class CdmaCallOptions extends PreferenceActivity {
    private static final String LOG_TAG = "CdmaCallOptions";
    private final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    public static final int CALL_WAITING = 7;
    private static final String BUTTON_VP_KEY = "button_voice_privacy_key";
    private CheckBoxPreference mButtonVoicePrivacy;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.cdma_call_privacy);

        Phone phone = PhoneUtils.getPhoneFromIntent(getIntent());
        Log.d(LOG_TAG, "Get CDMACallOptions phoneId = " + phone.getPhoneId());

        initCallWaitingPref(this, phone.getPhoneId());

        mButtonVoicePrivacy = (CheckBoxPreference) findPreference(BUTTON_VP_KEY);
        if (phone.getPhoneType() != PhoneConstants.PHONE_TYPE_CDMA
                || getResources().getBoolean(R.bool.config_voice_privacy_disable)) {
            //disable the entire screen
            getPreferenceScreen().setEnabled(false);
        }
    }

    public static void initCallWaitingPref(PreferenceActivity activity, int phoneId) {
        PreferenceScreen prefCWAct = (PreferenceScreen)
                activity.findPreference("button_cw_act_key");
        PreferenceScreen prefCWDeact = (PreferenceScreen)
                activity.findPreference("button_cw_deact_key");

        CdmaCallOptionsSetting callOptionSettings = new CdmaCallOptionsSetting(activity,
                CALL_WAITING, phoneId);

        PhoneAccountHandle accountHandle = PhoneGlobals.getPhoneAccountHandle(activity, phoneId);
        prefCWAct.getIntent()
                .putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
                .setData(Uri.fromParts("tel", callOptionSettings.getActivateNumber(), null));
        prefCWAct.setSummary(callOptionSettings.getActivateNumber());

        prefCWDeact.getIntent()
                .putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
                .setData(Uri.fromParts("tel", callOptionSettings.getDeactivateNumber(), null));
        prefCWDeact.setSummary(callOptionSettings.getDeactivateNumber());
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference.getKey().equals(BUTTON_VP_KEY)) {
            return true;
        }
        return false;
    }

}
