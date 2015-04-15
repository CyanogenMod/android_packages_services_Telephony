/* Copyright (c) 2015, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.phone;

import android.app.ActionBar;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import com.android.ims.ImsConfig;
import com.android.ims.ImsConfigListener;
import com.android.ims.ImsException;
import com.android.ims.ImsManager;

public class WifiCallingSettings extends PreferenceActivity
        implements Preference.OnPreferenceChangeListener {

    private static final String TAG = WifiCallingSettings.class.getSimpleName();

    private static final String WIFI_CALLING_KEY = "wifi_calling";
    private static final String WIFI_CALLING_PREFERENCE_KEY = "wifi_calling_preferrence";

    private static final int WIFI_PREF_NONE = 0;
    private static final int WIFI_PREFERRED = 1;
    private static final int WIFI_ONLY = 2;
    private static final int CELLULAR_PREFERRED = 3;
    private static final int CELLULAR_ONLY = 4;


    private SwitchPreference mWifiCallingSetting;
    private ListPreference mWifiCallingPreference;
    private ImsConfig mImsConfig;

    @Override
    protected void onCreate(Bundle icicle) {
        setTheme(R.style.Theme_Material_Settings);
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.wifi_call_setting);
        PreferenceScreen screen = getPreferenceScreen();

        mWifiCallingSetting = (SwitchPreference)screen.findPreference(WIFI_CALLING_KEY);
        mWifiCallingSetting.setOnPreferenceChangeListener(this);

        mWifiCallingPreference = (ListPreference)screen.findPreference(WIFI_CALLING_PREFERENCE_KEY);
        mWifiCallingPreference.setOnPreferenceChangeListener(this);

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        try {
            ImsManager imsManager = ImsManager.getInstance(getBaseContext(),
                    SubscriptionManager.getDefaultVoiceSubId());
            mImsConfig = imsManager.getConfigInterface();
        } catch (ImsException e) {
            mImsConfig = null;
            Log.e(TAG, "ImsService is not running");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isWifiCallingPreferenceSupported() && mWifiCallingPreference != null) {
            getPreferenceScreen().removePreference(mWifiCallingPreference);
            mWifiCallingPreference = null;
        }
        getWifiCallingPreference();
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

    public boolean onPreferenceChange(Preference preference, Object objValue) {
        if (preference == mWifiCallingSetting) {
            mWifiCallingSetting.setChecked(!mWifiCallingSetting.isChecked());
            final int status =  mWifiCallingSetting.isChecked() ?
                    ImsConfig.WifiCallingValueConstants.ON :
                    ImsConfig.WifiCallingValueConstants.OFF;
            int wifiPreference;
            if (mWifiCallingPreference != null) {
                wifiPreference = mWifiCallingPreference.getValue() == null ?
                        ImsConfig.WifiCallingPreference.WIFI_PREF_NONE :
                        Integer.valueOf(mWifiCallingPreference.getValue()).intValue();
            } else {
                wifiPreference = getDefaultWifiCallingPreference();
            }
            Log.d(TAG, "onPreferenceChange user selected status : wifiStatus " + status +
                    " wifiPreference: " + wifiPreference);
            boolean result = setWifiCallingPreference(status, wifiPreference);
            if (result) {
                loadWifiCallingPreference(status, wifiPreference);
            }
            return result;
        } else if (preference == mWifiCallingPreference) {
            final int wifiPreference = Integer.parseInt(objValue.toString());
            final int status = mWifiCallingSetting.isChecked() ?
                    ImsConfig.WifiCallingValueConstants.ON :
                    ImsConfig.WifiCallingValueConstants.OFF;
            Log.d(TAG, "onPreferenceChange user selected wifiPreference: status " + status +
                    " wifiPreference: " + wifiPreference);
            boolean result = setWifiCallingPreference(status, wifiPreference);
            if (result) {
                loadWifiCallingPreference(status, wifiPreference);
            }
            return result;
        }
        return true;
    }

    private String getWifiPreferenceString(int wifiPreference) {
        switch (wifiPreference) {
            case WIFI_PREFERRED:
                return (getString(R.string.wifi_preferred));
            case WIFI_ONLY:
                return (getString(R.string.wifi_only));
            case CELLULAR_PREFERRED:
                return (getString(R.string.cellular_preferred));
            case CELLULAR_ONLY:
                return (getString(R.string.cellular_only));
            case WIFI_PREF_NONE:
            default:
                return (getString(R.string.wifi_pref_none));
        }
    }

    private ImsConfigListener imsConfigListener = new ImsConfigListener.Stub() {
        public void onGetVideoQuality(int status, int quality) {
            //TODO not required as of now
        }

        public void onSetVideoQuality(int status) {
            //TODO not required as of now
        }

        public void onGetFeatureResponse(int feature, int network, int value, int status) {
            //TODO not required as of now
        }

        public void onSetFeatureResponse(int feature, int network, int value, int status) {
            //TODO not required as of now
        }

        public void onGetPacketCount(int status, long packetCount) {
            //TODO not required as of now
        }

        public void onGetPacketErrorCount(int status, long packetErrorCount) {
            //TODO not required as of now
        }

        public void onGetWifiCallingPreference(int status, int wifiCallingStatus,
                int wifiCallingPreference) {
            if (hasRequestFailed(status)) {
                wifiCallingStatus = ImsConfig.WifiCallingValueConstants.OFF;
                wifiCallingPreference = ImsConfig.WifiCallingPreference.WIFI_PREF_NONE;
                Log.e(TAG, "onGetWifiCallingPreference: failed. errorCode = " + status);
            }
            Log.d(TAG, "onGetWifiCallingPreference: status = " + wifiCallingStatus +
                    " preference = " + wifiCallingPreference);
            loadWifiCallingPreference(wifiCallingStatus, wifiCallingPreference);
        }

        public void onSetWifiCallingPreference(int status) {
            if (hasRequestFailed(status)) {
                Log.e(TAG, "onSetWifiCallingPreference : set failed. errorCode = " + status);
                Toast.makeText(getApplicationContext(), R.string.
                        ims_set_wifi_calling_preference_failed, Toast.LENGTH_SHORT).show();
                getWifiCallingPreference();
            } else {
                Log.d(TAG, "onSetWifiCallingPreference: set succeeded.");
            }
        }
    };

    private void loadWifiCallingPreference(int status, int preference) {
        Log.d(TAG, "loadWifiCallingPreference status = " + status + " preference = " + preference);
        if (status == ImsConfig.WifiCallingValueConstants.NOT_SUPPORTED) {
            mWifiCallingSetting.setEnabled(false);
            if (preference == ImsConfig.WifiCallingPreference.WIFI_PREF_NONE &&
                    mWifiCallingPreference != null) {
                mWifiCallingPreference.setEnabled(false);
            }
            return;
        }
        mWifiCallingSetting.setChecked(getWifiCallingSettingFromStatus(status));
        if (mWifiCallingPreference != null) {
            mWifiCallingPreference.setValue(String.valueOf(preference));
            mWifiCallingPreference.setSummary(getWifiPreferenceString(preference));
        }
    }

    private void getWifiCallingPreference() {
        try {
            if (mImsConfig != null) {
                mImsConfig.getWifiCallingPreference(imsConfigListener);
            } else {
                loadWifiCallingPreference(ImsConfig.WifiCallingValueConstants.OFF,
                        ImsConfig.WifiCallingPreference.WIFI_PREF_NONE);
                Log.e(TAG, "getWifiCallingPreference failed. mImsConfig is null");
            }
        } catch (ImsException e) {
            Log.e(TAG, "getWifiCallingPreference failed. Exception = " + e);
        }
    }

    private boolean setWifiCallingPreference(int wifiCallingStatus, int wifiCallingPreference) {
        try {
            if (mImsConfig != null) {
                mImsConfig.setWifiCallingPreference(wifiCallingStatus,
                        wifiCallingPreference, imsConfigListener);
            } else {
                Log.e(TAG, "setWifiCallingPreference failed. mImsConfig is null");
                return false;
            }
        } catch (ImsException e) {
            Log.e(TAG, "setWifiCallingPreference failed. Exception = " + e);
            return false;
        }
        return true;
    }

    private boolean getWifiCallingSettingFromStatus(int status) {
        switch (status) {
            case ImsConfig.WifiCallingValueConstants.ON:
                return true;
            case ImsConfig.WifiCallingValueConstants.OFF:
            case ImsConfig.WifiCallingValueConstants.NOT_SUPPORTED:
            default:
                return false;
        }
    }

    private boolean hasRequestFailed(int result) {
        return (result != ImsConfig.OperationStatusConstants.SUCCESS);
    }

    private boolean isWifiCallingPreferenceSupported() {
        return getApplicationContext().getResources().getBoolean(
                R.bool.config_wifi_calling_preference_supported);
    }

    private int getDefaultWifiCallingPreference() {
        return getApplicationContext().getResources().getInteger(
                R.integer.config_default_wifi_calling_preference);
    }
}
