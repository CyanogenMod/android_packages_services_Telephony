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
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;

public class PhoneToggler extends BroadcastReceiver  {

    /** Used for brodcasting network data change and receive new mode **/
    public static final String ACTION_NETWORK_MODE_CHANGED =
            "com.android.internal.telephony.NETWORK_MODE_CHANGED";
    public static final String ACTION_REQUEST_NETWORK_MODE =
            "com.android.internal.telephony.REQUEST_NETWORK_MODE";
    public static final String ACTION_MODIFY_NETWORK_MODE =
            "com.android.internal.telephony.MODIFY_NETWORK_MODE";
    public static final String ACTION_MOBILE_DATA_CHANGED =
            "com.android.internal.telephony.MOBILE_DATA_CHANGED";
    public static final String EXTRA_NETWORK_MODE = "networkMode";

    public static final String CHANGE_NETWORK_MODE_PERM =
            "com.android.phone.CHANGE_NETWORK_MODE";

    private static final String LOG_TAG = "PhoneToggler";
    private static final boolean DBG = true;

    private MyHandler mHandler;

    private Phone getPhone() {
        return PhoneFactory.getPhone(SubscriptionManager.getPhoneId(
                SubscriptionManager.getDefaultDataSubId()));
    }

    private MyHandler getHandler() {
        if (mHandler == null) {
            mHandler = new MyHandler();
        }
        return mHandler;
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
                            || networkMode == Phone.NT_MODE_WCDMA_ONLY) {
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
            if (DBG) Log.d(LOG_TAG,"Sending Intent with current phone network mode");
            triggerIntent();
        } else {
            if (DBG) Log.d(LOG_TAG,"Unexpected intent: " + intent);
        }
    }

    private void changeNetworkMode(int modemNetworkMode) {
        getPhone().setPreferredNetworkType(modemNetworkMode,
                getHandler().obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));

    }

    private void triggerIntent() {
        getPhone().getPreferredNetworkType(
                getHandler().obtainMessage(MyHandler.MESSAGE_GET_PREFERRED_NETWORK_TYPE));
    }

    private class MyHandler extends Handler {
        private static final int MESSAGE_GET_PREFERRED_NETWORK_TYPE = 0;
        private static final int MESSAGE_SET_PREFERRED_NETWORK_TYPE = 1;

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
                Context context = getPhone().getContext();
                int modemNetworkMode = ((int[]) ar.result)[0];
                int settingsNetworkMode = Settings.Global.getInt(context.getContentResolver(),
                        Settings.Global.PREFERRED_NETWORK_MODE,
                        MobileNetworkSettings.preferredNetworkMode);

                if (DBG) {
                    Log.d(LOG_TAG,"handleGetPreferredNetworkTypeResponse: modemNetworkMode = "
                            + modemNetworkMode + ", settingsNetworkMode = " + settingsNetworkMode);
                }

                //check that modemNetworkMode is from an accepted value
                if (MobileNetworkSettings.isValidModemNetworkMode(getPhone(), modemNetworkMode)) {
                    if (DBG) Log.d(LOG_TAG, "handleGetPreferredNetworkTypeResponse: mode ok");

                    //check changes in modemNetworkMode and updates settingsNetworkMode
                    if (modemNetworkMode != settingsNetworkMode) {
                        settingsNetworkMode = modemNetworkMode;

                        //changes the Settings.System accordingly to modemNetworkMode
                        Settings.Global.putInt(context.getContentResolver(),
                                Settings.Global.PREFERRED_NETWORK_MODE, settingsNetworkMode);
                    }

                    Intent intent = new Intent(ACTION_NETWORK_MODE_CHANGED);
                    intent.putExtra(EXTRA_NETWORK_MODE, settingsNetworkMode);
                    context.sendBroadcast(intent, CHANGE_NETWORK_MODE_PERM);
                } else if (modemNetworkMode == Phone.NT_MODE_LTE_ONLY) {
                    if (DBG) Log.d(LOG_TAG,
                            "handleGetPreferredNetworkTypeResponse: lte only: no action");
                } else {
                    if (DBG) Log.d(LOG_TAG,
                            "handleGetPreferredNetworkTypeResponse: reset to default");
                    resetNetworkModeToDefault();
                }
            }
        }

        private void handleSetPreferredNetworkTypeResponse(Message msg) {
            //TODO: For now no status is stored, so we will always get the real status from Phone.
            getPhone().getPreferredNetworkType(obtainMessage(MESSAGE_GET_PREFERRED_NETWORK_TYPE));
        }

        private void resetNetworkModeToDefault() {
            Settings.Global.putInt(getPhone().getContext().getContentResolver(),
                        Settings.Global.PREFERRED_NETWORK_MODE,
                        MobileNetworkSettings.preferredNetworkMode);
            //Set the Modem
            getPhone().setPreferredNetworkType(MobileNetworkSettings.preferredNetworkMode,
                    this.obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
        }
    }
}
