/*
 * Copyright (c) 2012, The Linux Foundation. All rights reserved.
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

import com.android.internal.telephony.Phone;
import com.android.phone.PhoneUtils;
import com.android.phone.R;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Wrapper for IMS's shared preferences.
 */
public class ImsSharedPreferences {
    private static final String TAG = "IMSPreferences";
    private static final String IMS_SHARED_PREFERENCES = "IMS_PREFERENCES";
    private static final String KEY_IMS_CALL_TYPE = "ims_call_type";
    private static final String KEY_IMS_IS_VOICE_CAPABLE = "ims_is_voice_cap";
    private static final String KEY_IMS_IS_VIDEO_CAPABLE = "ims_is_default_video";
    private static final String KEY_IMS_VOICE_SRV_ALLOWED = "ims_is_voice_srv_allowed";
    private static final String KEY_IMS_VIDEO_SRV_ALLOWED = "ims_is_video_srv_allowed";
    private static final String KEY_IMS_IS_CALL_TYPE_ENABLED = "ims_is_call_type_enabled";
    private static final String KEY_IMS_IS_CALL_TYPE_SELECTABLE = "ims_is_call_type_selectable";

    public final static int VT_QUALITY_UNKNOWN = -1;
    public final static int VT_QUALITY_LOW = 0;
    public final static int VT_QUALITY_HIGH = 1;


    private SharedPreferences mPreferences;
    private Context mContext;

    // Check if the tag is loggable
    private static final boolean DBG = Log.isLoggable("IMS", Log.DEBUG);

    public ImsSharedPreferences(Context context) {
        mPreferences = context.getSharedPreferences(
                IMS_SHARED_PREFERENCES, Context.MODE_WORLD_READABLE);
        mContext = context;
    }

    public void setCallType(int callType) {
        if (DBG) Log.d(TAG, "setCallType: " + callType);
        SharedPreferences.Editor editor = mPreferences.edit();
        editor.putInt(KEY_IMS_CALL_TYPE, callType);
        editor.apply();
    }

    public int getCallType() {
        int callType = mPreferences.getInt(KEY_IMS_CALL_TYPE, Phone.CALL_TYPE_UNKNOWN);
        if (DBG) Log.d(TAG, "getCallType: " + callType);
        return callType;
    }

    public boolean isCallTypeSelectable() {
        boolean isSelectable = mPreferences.getBoolean(KEY_IMS_IS_CALL_TYPE_SELECTABLE, false);
        if (DBG) {
            Log.d(TAG, "isCallTypeSelectable: " + isSelectable);
        }
        return isSelectable;
    }

    public void setCallTypeSelectable(boolean isSelectable) {
        if (DBG)
            Log.d(TAG, "setCallTypeSelectable: " + isSelectable);
        SharedPreferences.Editor editor = mPreferences.edit();
        editor.putBoolean(KEY_IMS_IS_CALL_TYPE_SELECTABLE, isSelectable);
        editor.apply();
    }

    public boolean getisImsCapEnabled(int service) {
        boolean isImsDefault = false;
        switch (service) {
            case Phone.CALL_TYPE_VOICE:
                isImsDefault = mPreferences.getBoolean(KEY_IMS_IS_VOICE_CAPABLE, false);
                break;
            case Phone.CALL_TYPE_VT:
                isImsDefault = mPreferences.getBoolean(KEY_IMS_IS_VIDEO_CAPABLE, false);
                break;
            default:
                Log.e(TAG, "getisImsCapEnabled not supported " + service);
                break;
        }
        return isImsDefault;
    }

    /**
     * Method to enable/disable IMS Capability for a service
     *
     * @param service - service to be changed
     * @param enable - enable/disable the service capability
     */
    public void setIsImsCapEnabled(int service, boolean enable) {
        SharedPreferences.Editor editor = mPreferences.edit();
        switch (service) {
            case Phone.CALL_TYPE_VOICE:
                editor.putBoolean(KEY_IMS_IS_VOICE_CAPABLE, enable);
                break;
            case Phone.CALL_TYPE_VT:
                editor.putBoolean(KEY_IMS_IS_VIDEO_CAPABLE, enable);
                break;
            default:
                Log.e(TAG, "setIsImsCapEnabled not supported " + service + " " + enable);
                return;
        }
        editor.apply();
    }

    public void setImsSrvStatus(int service, int status) {
        SharedPreferences.Editor editor = mPreferences.edit();
        switch (service) {
            case Phone.CALL_TYPE_VOICE:
                editor.putInt(KEY_IMS_VOICE_SRV_ALLOWED, status);
                break;
            case Phone.CALL_TYPE_VT:
                editor.putInt(KEY_IMS_VIDEO_SRV_ALLOWED, status);
                break;
            default:
                Log.e(TAG, "setImsSrvStatus not supported " + service + " " + status);
                return;
        }
        editor.apply();
    }

    public int getImsSrvStatus(int service) {
        int status = PhoneUtils.IMS_SRV_STATUS_NOT_SUPPORTED;
        switch (service) {
            case Phone.CALL_TYPE_VOICE:
                status = mPreferences.getInt(KEY_IMS_VOICE_SRV_ALLOWED,
                        PhoneUtils.IMS_SRV_STATUS_NOT_SUPPORTED);
                break;
            case Phone.CALL_TYPE_VT:
                status = mPreferences.getInt(KEY_IMS_VIDEO_SRV_ALLOWED,
                        PhoneUtils.IMS_SRV_STATUS_NOT_SUPPORTED);
                break;
            default:
                Log.e(TAG, "getImsSrvStatus not supported service " + service);
                break;
        }
        return status;
    }

    public boolean isImsSrvAllowed(int service) {
        int status = getImsSrvStatus(service);
        return (status == PhoneUtils.IMS_SRV_STATUS_ENABLED)
                || (status == PhoneUtils.IMS_SRV_STATUS_PARTIALLY_DISABLED);
    }

    public int readVideoCallQuality() {
        final String key = mContext.getString(R.string.ims_vt_call_quality);
        return mPreferences.getInt(key, VT_QUALITY_UNKNOWN);
    }

    public void saveVideoCallQuality(int quality) {
        if (isValidVideoCallQuality(quality)) {
            SharedPreferences.Editor editor = mPreferences.edit();
            final String key = mContext.getString(R.string.ims_vt_call_quality);
            editor.putInt(key, quality);
            editor.apply();
        } else {
            Log.e(TAG, "VideoCallQuality: invalid quality value, " + quality);
        }
    }

    private static boolean isValidVideoCallQuality(int value) {
        return value == VT_QUALITY_LOW || value == VT_QUALITY_HIGH;
    }
}
