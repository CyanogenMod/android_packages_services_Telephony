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
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.SubscriptionController;

public class PhoneToggler extends BroadcastReceiver {

    @Deprecated
    public static final String ACTION_REQUEST_NETWORK_MODE =
            "com.android.internal.telephony.REQUEST_NETWORK_MODE";

    public static final String ACTION_MODIFY_NETWORK_MODE =
            "com.android.internal.telephony.MODIFY_NETWORK_MODE";

    public static final String EXTRA_NETWORK_MODE = "networkMode";

    public static final String EXTRA_SUB_ID = "subId";

    private static final String LOG_TAG = "PhoneToggler";
    private static final boolean DBG = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_MODIFY_NETWORK_MODE.equals(action)) {
            if (DBG) Log.d(LOG_TAG, "Got modify intent");
            if (intent.getExtras() != null) {
                SubscriptionController subCtrl = SubscriptionController.getInstance();

                int networkMode = intent.getExtras().getInt(EXTRA_NETWORK_MODE, -1);
                int subId = intent.getExtras().getInt(EXTRA_SUB_ID,
                        SubscriptionManager.getDefaultDataSubId());

                // since the caller must be a system app, it's assumed that they have already
                // chosen a valid network mode for this subId, so only basic validation is done
                if (isValidModemNetworkMode(networkMode)) {
                    if (DBG) Log.d(LOG_TAG, "Changing network mode to " + networkMode);
                    subCtrl.setUserNwMode(subId, networkMode);
                    try {
                        PhoneFactory.getPhone(SubscriptionManager.getPhoneId(subId))
                                .setPreferredNetworkType(networkMode, null);
                    } catch (Throwable t) {
                        Log.d(LOG_TAG, "error setting preferred network", t);
                    }
                } else {
                    if (DBG) {
                        String error = "invalid network mode (%d) or invalid subId (%d)";
                        Log.d(LOG_TAG, String.format(error, networkMode, subId));
                    }
                }
            }
        } else if (ACTION_REQUEST_NETWORK_MODE.equals(action)) {
            if (DBG) {
                Log.e(LOG_TAG, "Requested network mode. Not honoring request. Get your own info.");
            }
        } else {
            if (DBG) {
                Log.d(LOG_TAG, "Unexpected intent: " + intent);
            }
        }
    }

    /*
     * Basic validation; from MobileNetworkSettings
     */
    private static boolean isValidModemNetworkMode(int mode) {
        switch (mode) {
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
            case Phone.NT_MODE_TDSCDMA_ONLY:
            case Phone.NT_MODE_TDSCDMA_WCDMA:
            case Phone.NT_MODE_LTE_TDSCDMA:
            case Phone.NT_MODE_TDSCDMA_GSM:
            case Phone.NT_MODE_LTE_TDSCDMA_GSM:
            case Phone.NT_MODE_TDSCDMA_GSM_WCDMA:
            case Phone.NT_MODE_LTE_TDSCDMA_WCDMA:
            case Phone.NT_MODE_LTE_TDSCDMA_GSM_WCDMA:
            case Phone.NT_MODE_TDSCDMA_CDMA_EVDO_GSM_WCDMA:
            case Phone.NT_MODE_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA:
                return true;
            default:
                return false;
        }
    }

}
