/*
 * Copyright (c) 2012-2014, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.phone.ims;

import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import com.android.ims.ImsConfig;
import com.android.ims.ImsConfigListener;
import com.android.ims.ImsException;
import com.android.ims.ImsManager;
import com.android.phone.R;

/**
 * The activity class for editing a new or existing IMS profile.
 */
public class ImsEditor extends PreferenceActivity
        implements Preference.OnPreferenceChangeListener {

    private static final String TAG = ImsEditor.class.getSimpleName();

    private ListPreference mVideoCallQuality;
    private Preference mPcPreference;
    private Preference mPerPreference;
    private ImsConfig mImsConfig;

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        getVideoQuality();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.v(TAG, "start profile editor");
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.ims_edit);
        PreferenceScreen screen = getPreferenceScreen();

        mVideoCallQuality = (ListPreference) screen
                .findPreference(getString(R.string.ims_vt_call_quality));
        mVideoCallQuality.setOnPreferenceChangeListener(this);

        mPcPreference = (Preference) screen.findPreference(getString(R.string.ims_pc_count));
        mPerPreference = (Preference) screen.findPreference(getString(R.string.ims_per_count));

        //remove the references by default and add them only when config is enabled
        screen.removePreference(mPcPreference);
        screen.removePreference(mPerPreference);

        //Show Packet Count/Packet Error Count UI only if the config is enabled
        if (isRtpStatisticsQueryEnabled()) {
            screen.addPreference(mPcPreference);
            screen.addPreference(mPerPreference);

            mPcPreference.setOnPreferenceClickListener(prefClickListener);
            mPerPreference.setOnPreferenceClickListener(prefClickListener);
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

    private ImsConfigListener imsConfigListener = new ImsConfigListener.Stub() {
        public void onGetVideoQuality(int status, int quality) {
            if (hasRequestFailed(status)) {
                quality = ImsConfig.OperationValuesConstants.VIDEO_QUALITY_UNKNOWN;
                Log.e(TAG, "onGetVideoQuality: failed. errorCode = " + status);
            }
            Log.d(TAG, "onGetVideoQuality: value = " + quality);
            loadVideoCallQualityPrefs(quality);
        }

        public void onSetVideoQuality(int status) {
            if (hasRequestFailed(status)) {
                Log.e(TAG, "onSetVideoQuality: set failed. errorCode = " + status);
                Toast.makeText(getApplicationContext(), R.string.ims_vt_call_quality_set_failed,
                        Toast.LENGTH_SHORT).show();
                getVideoQuality(); // Set request failed, get current value.
            } else {
                Log.d(TAG, "onSetVideoQuality: set succeeded.");
            }
        }

        public void onGetPacketCount(int status, long packetCount) {
            mPcPreference.setEnabled(true);
            if (hasRequestFailed(status)) {
                Log.e(TAG, "onGetPacketCount: get failed. errorCode = " + status);
                Toast.makeText(getApplicationContext(), R.string.ims_get_packet_count_failed,
                        Toast.LENGTH_SHORT).show();
            } else {
                Log.d(TAG, "onGetPacketCount: packetCount = " + packetCount);
                Toast.makeText(getApplicationContext(), "packetCount = " + packetCount,
                        Toast.LENGTH_LONG).show();
            }

        }

        public void onGetPacketErrorCount(int status, long packetErrorCount) {
            mPerPreference.setEnabled(true);
            if (hasRequestFailed(status)) {
                Log.e(TAG, "onGetPacketErrorCount: get failed. errorCode = " + status);
                Toast.makeText(getApplicationContext(), R.string.ims_get_packet_error_count_failed,
                        Toast.LENGTH_SHORT).show();
            } else {
                Log.d(TAG, "onGetPacketErrorCount: packetErrorCount = " + packetErrorCount);
                Toast.makeText(getApplicationContext(), "packetErrorCount = " + packetErrorCount,
                        Toast.LENGTH_LONG).show();
            }
        }

        public void onGetFeatureResponse(int feature, int network, int value, int status) {
            //TODO not required as of now
        }

        public void onSetFeatureResponse(int feature, int network, int value, int status) {
            //TODO not required as of now
        }

        public void onGetWifiCallingPreference(int status, int wifiCallingStatus,
                int wifiCallingPreference) {
            //TODO not required as of now
        }

        public void onSetWifiCallingPreference(int status) {
            //TODO not required as of now
        }
    };

    private void onPcPrefClicked() {
        Log.d(TAG, "onPcPrefClicked");

        try {
            if (mImsConfig != null) {
                mImsConfig.getPacketCount(imsConfigListener);
                mPcPreference.setEnabled(false);
            } else {
                Log.e(TAG, "getPacketCount failed. mImsConfig is null");
            }
        } catch (ImsException e) {
            Log.e(TAG, "getPacketCount failed. Exception = " + e);
        }
    }

    private void onPerPrefClicked() {
        Log.d(TAG, "onPerPrefClicked");

        try {
            if (mImsConfig != null) {
                mImsConfig.getPacketErrorCount(imsConfigListener);
                mPerPreference.setEnabled(false);
            } else {
                Log.e(TAG, "getPacketErrorCount failed. mImsConfig is null");
            }
        } catch (ImsException e) {
            Log.e(TAG, "getPacketErrorCount failed. Exception = " + e);
        }
    }

    Preference.OnPreferenceClickListener prefClickListener =
            new Preference.OnPreferenceClickListener() {

        @Override
        public boolean onPreferenceClick(Preference pref) {
            Log.d(TAG, "onPreferenceClick");
            if (pref.equals(mPcPreference)) {
                onPcPrefClicked();
            } else if (pref.equals(mPerPreference)) {
                onPerPrefClicked();
            }
            return true;
        }
    };

    @Override
    public boolean onPreferenceChange(Preference pref, Object newValue) {
        if (pref.equals(mVideoCallQuality)) {
            if (newValue == null) {
                Log.e(TAG, "onPreferenceChange invalid value received");
            } else {
                final int quality = Integer.parseInt(newValue.toString());
                boolean result = setVideoQuality(quality);
                if (result) {
                    loadVideoCallQualityPrefs(quality);
                }
                return result;
            }
        }
        return true;
    }

    private void loadVideoCallQualityPrefs(int vqValue) {
        Log.d(TAG, "loadVideoCallQualityPrefs, vqValue = " + vqValue);
        final String videoQuality = videoQualityToString(vqValue);
        mVideoCallQuality.setValue(String.valueOf(vqValue));
        mVideoCallQuality.setSummary(videoQuality);
    }

    private void getVideoQuality() {
        try {
            if (mImsConfig != null) {
                mImsConfig.getVideoQuality(imsConfigListener);
            } else {
                loadVideoCallQualityPrefs(ImsConfig.OperationValuesConstants.VIDEO_QUALITY_UNKNOWN);
                Log.e(TAG, "getVideoQuality failed. mImsConfig is null");
            }
        } catch (ImsException e) {
            Log.e(TAG, "getVideoQuality failed. Exception = " + e);
        }
    }

    private boolean setVideoQuality(int quality) {
        try {
            if (mImsConfig != null) {
                mImsConfig.setVideoQuality(quality, imsConfigListener);
            } else {
                Log.e(TAG, "setVideoQuality failed. mImsConfig is null");
                return false;
            }
        } catch (ImsException e) {
            Log.e(TAG, "setVideoQuality failed. Exception = " + e);
            return false;
        }
        return true;
    }

    private boolean hasRequestFailed(int result) {
        return (result != ImsConfig.OperationStatusConstants.SUCCESS);
    }

    private String videoQualityToString(int quality) {
        switch (quality) {
            case ImsConfig.OperationValuesConstants.VIDEO_QUALITY_HIGH:
                return getString(R.string.ims_vt_call_quality_high);
            case ImsConfig.OperationValuesConstants.VIDEO_QUALITY_LOW:
                return getString(R.string.ims_vt_call_quality_low);
            case ImsConfig.OperationValuesConstants.VIDEO_QUALITY_UNKNOWN:
            default:
                return getString(R.string.ims_vt_call_quality_unknown);
        }
    }

    private boolean isRtpStatisticsQueryEnabled() {
        return getApplicationContext().getResources().getBoolean(
                R.bool.config_ims_enable_rtp_statistics);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
