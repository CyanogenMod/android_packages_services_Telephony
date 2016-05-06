/*
 * Copyright (C) 2016 The CyanogenMod Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.util.Log;

public class WifiApStateReceiver extends BroadcastReceiver{
    private final String LOG_TAG = "WifiApStateReceiver";
    private final boolean DBG = false;
    Phone mPhone = null;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(LOG_TAG, "onReceive()");
        if(WifiManager.WIFI_AP_STATE_CHANGED_ACTION.equals(action)) {
            int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_AP_STATE,
                                           WifiManager.WIFI_AP_STATE_FAILED);
            if(state == WifiManager.WIFI_AP_STATE_ENABLED ||
               state == WifiManager.WIFI_AP_STATE_DISABLED){
                mPhone = PhoneGlobals.getPhone();
                handleWifiApStateChange(mPhone, state);
            }
        }
    }

    private void handleWifiApStateChange(Phone mPhone, int state){
        switch (state) {
            case WifiManager.WIFI_AP_STATE_ENABLED:
            Log.d(LOG_TAG, "WIFI_AP_STATE_ENABLED");
            if(mPhone!=null){
                Log.d(LOG_TAG, "setMaxTransmitPower enable");
                mPhone.setMaxTransmitPower(1, null);
            }
            break;

            case WifiManager.WIFI_AP_STATE_DISABLED:
            Log.d(LOG_TAG, "WIFI_AP_STATE_DISABLED");
            if(mPhone!=null){
                Log.d(LOG_TAG, "setMaxTransmitPower disable");
                mPhone.setMaxTransmitPower(0, null);
            }
            break;

            default:
                Log.d(LOG_TAG, "Unhandled WIFI STATE " + state);
        }
    }
}
