/*
 * Copyright (C) 2010 The CyanogenMod Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SubscriptionManager;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.SubscriptionController;

public class PhoneToggler extends BroadcastReceiver  {

    @Deprecated
    public static final String ACTION_REQUEST_NETWORK_MODE =
            "com.android.internal.telephony.REQUEST_NETWORK_MODE";

    public static final String ACTION_MODIFY_NETWORK_MODE =
            "com.android.internal.telephony.MODIFY_NETWORK_MODE";

    public static final String EXTRA_NETWORK_MODE = "networkMode";

    public static final String CHANGE_NETWORK_MODE_PERM =
            "com.android.phone.CHANGE_NETWORK_MODE";

    private static final String LOG_TAG = "PhoneToggler";
    private static final boolean DBG = false;

    private Phone getPhone() {
        return PhoneFactory.getPhone(SubscriptionManager.getPhoneId(
                SubscriptionManager.getDefaultDataSubId()));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_MODIFY_NETWORK_MODE.equals(action)) {
            if (DBG) Log.d(LOG_TAG, "Got modify intent");
            if (intent.getExtras() != null) {
                int networkMode = intent.getExtras().getInt(EXTRA_NETWORK_MODE);
                boolean networkModeOk = false;
                Phone phone = getPhone();
                int phoneType = phone.getPhoneType();
                boolean isLteOnCdma = phone.getLteOnCdmaMode() == PhoneConstants.LTE_ON_CDMA_TRUE;

                if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                    if (networkMode == Phone.NT_MODE_GSM_ONLY
                            || networkMode == Phone.NT_MODE_GSM_UMTS
                            || networkMode == Phone.NT_MODE_WCDMA_PREF
                            || networkMode == Phone.NT_MODE_LTE_GSM_WCDMA
                            || networkMode == Phone.NT_MODE_WCDMA_ONLY
                            || networkMode == Phone.NT_MODE_LTE_ONLY) {
                        networkModeOk = true;
                    }
                } else if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    if (networkMode == Phone.NT_MODE_CDMA
                            || networkMode == Phone.NT_MODE_CDMA_NO_EVDO
                            || networkMode == Phone.NT_MODE_EVDO_NO_CDMA) {
                        networkModeOk = true;
                    }
                }
                if (context.getResources().getBoolean(R.bool.world_phone) || isLteOnCdma) {
                    if (networkMode == Phone.NT_MODE_GLOBAL) {
                        networkModeOk = true;
                    }
                }

                if (networkModeOk) {
                    if (DBG) Log.d(LOG_TAG, "Changing network mode to " + networkMode);
                    changeNetworkMode(networkMode);
                } else {
                    Log.e(LOG_TAG,"Invalid network mode: "+networkMode);
                }
            }
        } else if (ACTION_REQUEST_NETWORK_MODE.equals(action)) {
            if (DBG) Log.e(LOG_TAG,"Requested network mode. Not honoring request. Get your own info.");
        } else {
            if (DBG) Log.d(LOG_TAG,"Unexpected intent: " + intent);
        }
    }

    private void changeNetworkMode(int modemNetworkMode) {
        SubscriptionController.getInstance().setUserNwMode(
                SubscriptionManager.getDefaultDataSubId(), modemNetworkMode);
        getPhone().setPreferredNetworkType(modemNetworkMode, null);
    }

}
