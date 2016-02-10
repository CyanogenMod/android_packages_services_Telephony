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
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.RILConstants;

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
    private static final boolean DBG = true;

    private Phone mPhone;
    private Handler mHandler;
    private PendingResult mPendingAsyncResult;

    private Phone getPhone() {
        if (mPhone == null) {
            mPhone = PhoneFactory.getPhone(SubscriptionManager.getPhoneId(
                    SubscriptionManager.getDefaultDataSubId()));
        }
        return mPhone;
    }

    private Handler getHandler() {
        if (mHandler == null) {
            mHandler = new Handler(new MyHandlerCallback());
        }
        return mHandler;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_MODIFY_NETWORK_MODE.equals(action)) {
            if (DBG) Log.d(LOG_TAG, "Got modify intent");
            if (intent.getExtras() != null) {
                int networkMode = intent.getExtras().getInt(EXTRA_NETWORK_MODE, -1);

                if (networkMode > 0) {
                    if (DBG) Log.d(LOG_TAG, "Changing network mode to " + networkMode);
                    changeNetworkMode(networkMode);
                    mPendingAsyncResult = goAsync();
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
        getPhone().setPreferredNetworkType(modemNetworkMode,
                getHandler().obtainMessage(MyHandlerCallback.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
    }

    private static boolean isValidModemNetworkMode(Phone phone, int mode) {
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

    private class MyHandlerCallback implements Handler.Callback {
        private static final int MESSAGE_GET_PREFERRED_NETWORK_TYPE = 0;
        private static final int MESSAGE_SET_PREFERRED_NETWORK_TYPE = 1;

        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_PREFERRED_NETWORK_TYPE:
                    handleGetPreferredNetworkTypeResponse(msg);
                    break;
                case MESSAGE_SET_PREFERRED_NETWORK_TYPE:
                    handleSetPreferredNetworkTypeResponse(msg);
                    break;
            }
            return true;
        }

        private void handleSetPreferredNetworkTypeResponse(Message msg) {
            //TODO: For now no status is stored, so we will always get the real status from Phone.
            getPhone().getPreferredNetworkType(getHandler().
                    obtainMessage(MESSAGE_GET_PREFERRED_NETWORK_TYPE));
        }

        private void handleGetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                Context context = getPhone().getContext();
                int modemNetworkMode = ((int[]) ar.result)[0];
                int settingsNetworkMode = RILConstants.PREFERRED_NETWORK_MODE;
                try {
                    settingsNetworkMode = TelephonyManager.getIntAtIndex(context.getContentResolver(),
                            Settings.Global.PREFERRED_NETWORK_MODE , getPhone().getPhoneId());
                } catch (Settings.SettingNotFoundException snfe) {
                    Rlog.e(LOG_TAG, "Settings Exception Reading Valuefor phoneID");
                }

                if (DBG) {
                    Log.d(LOG_TAG, "handleGetPreferredNetworkTypeResponse: modemNetworkMode = "
                            + modemNetworkMode + ", settingsNetworkMode = " + settingsNetworkMode);
                }

                //check that modemNetworkMode is from an accepted value
                if (isValidModemNetworkMode(getPhone(), modemNetworkMode)) {
                    if (DBG) Log.d(LOG_TAG, "handleGetPreferredNetworkTypeResponse: mode ok");

                    //check changes in modemNetworkMode and updates settingsNetworkMode
                    if (modemNetworkMode != settingsNetworkMode) {
                        settingsNetworkMode = modemNetworkMode;

                        //changes the Settings.System accordingly to modemNetworkMode
//                        TelephonyManager.putIntAtIndex(context.getContentResolver(),
//                                Settings.Global.PREFERRED_NETWORK_MODE, getPhone().getPhoneId(),
//                                settingsNetworkMode);

                        Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                                Settings.Global.PREFERRED_NETWORK_MODE + getPhone().getSubId(),
                                settingsNetworkMode);
                    }

                } else if (modemNetworkMode == Phone.NT_MODE_LTE_ONLY) {
                    if (DBG) Log.d(LOG_TAG,
                            "handleGetPreferredNetworkTypeResponse: lte only: no action");
                }
            }

            if (mPendingAsyncResult != null) {
                mPendingAsyncResult.finish();
            }
        }
    }

}
