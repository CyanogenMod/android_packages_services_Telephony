/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.services.telephony;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.widget.Toast;


import com.android.internal.telephony.gsm.SuppServiceNotification;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyIntents;
import com.android.phone.R;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;



/**
 * Singleton entry point for the telephony-services app. Initializes ongoing systems relating to
 * PSTN calls. This is started when the device starts and will be restarted automatically
 * if it goes away for any reason (e.g., crashes).
 * This is separate from the actual Application class because we only support one instance of this
 * app - running as the default user. {@link com.android.phone.PhoneApp} determines whether or not
 * we are running as the default user and if we are, then initializes and runs this class's
 * {@link #onCreate}.
 */
public class TelephonyGlobals {
    static final String LOG_TAG = "TelephonyGlobals";
    private static TelephonyGlobals sInstance;
    private String[] mSubName = {"SUB 1", "SUB 2", "SUB 3"};
    private String mDisplayName;

    static SuppServiceNotification[] mSsNotification;
    /** The application context. */
    private static  Context mContext;

    private TtyManager mTtyManager;
    private Phone[] phones;
    private Phone defaultphone;
    private static final int EVENT_SUPP_SERVICE_NOTIFY = 1;
    /**
     * Persists the specified parameters.
     *
     * @param context The application context.
     */
    public TelephonyGlobals(Context context) {
        mContext = context.getApplicationContext();
    }

    public static synchronized TelephonyGlobals getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new TelephonyGlobals(context);
        }
        return sInstance;
    }

    public void onCreate() {
        // TODO: Make this work with Multi-SIM devices
        defaultphone = PhoneFactory.getDefaultPhone();

        int size = TelephonyManager.getDefault().getPhoneCount();
        mSsNotification = new SuppServiceNotification[size];

        if (defaultphone != null) {
            mTtyManager = new TtyManager(mContext, defaultphone);
        }
        IntentFilter intentFilter =
                new IntentFilter(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED);
        mContext.registerReceiver(mRATReceiver, intentFilter);

        TelecomAccountRegistry.getInstance(mContext).setupOnBoot();
        registerForNotifications();
    }

    public static Context getApplicationContext() {
        return mContext;
    }

    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_SUPP_SERVICE_NOTIFY:
                    AsyncResult ar = (AsyncResult)msg.obj;
                    int phoneId = (int)ar.userObj;
                    if (msg.obj != null && ((AsyncResult) msg.obj).result != null) {
                        Log.v(LOG_TAG, "MSG_SUPP_SERVICE_NOTIFY on phoneId : "
                                + phoneId);
                        mSsNotification[phoneId] =
                                (SuppServiceNotification)((AsyncResult) msg.obj).result;
                        final String notificationText =
                                getSuppSvcNotificationText(mSsNotification[phoneId], phoneId);
                        if (TelephonyManager.getDefault().getPhoneCount() > 1) {
                            SubscriptionInfo sub =
                                SubscriptionManager.from(getApplicationContext())
                                .getActiveSubscriptionInfoForSimSlotIndex(phoneId);

                            String displayName =  (sub != null) ?
                                 sub.getDisplayName().toString() : mSubName[phoneId];
                            mDisplayName = displayName + ":" + notificationText;
                        } else {
                            mDisplayName = notificationText;
                        }
                        if (notificationText != null && !notificationText.isEmpty()) {
                            final String history =(mSsNotification[phoneId].history != null
                                    && mSsNotification[phoneId].history.length > 0) ?
                                    " History: " + Arrays.toString
                                            (mSsNotification[phoneId].history) : "";
                            Toast.makeText(TelephonyGlobals.getApplicationContext(),
                                    mDisplayName + history, Toast.LENGTH_LONG).show();
                        }
                    } else {
                        Log.v(LOG_TAG,
                                "MSG_SUPP_SERVICE_NOTIFY event processing failed");
                    }
                    break;
            }
        }
    };

    private String getSuppSvcNotificationText(SuppServiceNotification suppSvcNotification,
            int phoneId) {
        final int SUPP_SERV_NOTIFICATION_TYPE_MO = 0;
        final int SUPP_SERV_NOTIFICATION_TYPE_MT = 1;
        String callForwardTxt = "";
        if (suppSvcNotification != null) {
            switch (suppSvcNotification.notificationType) {
                // The Notification is for MO call
                case SUPP_SERV_NOTIFICATION_TYPE_MO:
                    callForwardTxt = getMoSsNotificationText(suppSvcNotification.code, phoneId);
                    break;

                // The Notification is for MT call
                case SUPP_SERV_NOTIFICATION_TYPE_MT:
                    callForwardTxt = getMtSsNotificationText(suppSvcNotification.code, phoneId);
                    break;

                default:
                    Log.v(LOG_TAG, "Received invalid Notification Type :"
                            + suppSvcNotification.notificationType);
                    break;
            }
        }
        return callForwardTxt;
    }

    private String getMtSsNotificationText(int code, int phoneId) {
        String callForwardTxt = "";
        switch (code) {
            case SuppServiceNotification.MT_CODE_FORWARDED_CALL:
                //This message is displayed on C when the incoming
                //call is forwarded from B
                callForwardTxt = mContext.getString(R.string.card_title_forwarded_MTcall);
                break;

            case SuppServiceNotification.MT_CODE_CUG_CALL:
                //This message is displayed on B, when A makes call to B, both A & B
                //belong to a CUG group
                callForwardTxt = mContext.getString(R.string.card_title_cugcall);
                break;

            case SuppServiceNotification.MT_CODE_CALL_ON_HOLD:
                //This message is displayed on B,when A makes call to B & puts it on
                // hold
                callForwardTxt = mContext.getString(R.string.card_title_callonhold);
                break;

            case SuppServiceNotification.MT_CODE_CALL_RETRIEVED:
                //This message is displayed on B,when A makes call to B, puts it on
                //hold & retrives it back.
                callForwardTxt = mContext.getString(R.string.card_title_callretrieved);
                break;

            case SuppServiceNotification.MT_CODE_MULTI_PARTY_CALL:
                //This message is displayed on B when the the call is changed as
                //multiparty
                callForwardTxt = mContext.getString(R.string.card_title_multipartycall);
                break;

            case SuppServiceNotification.MT_CODE_ON_HOLD_CALL_RELEASED:
                //This message is displayed on B, when A makes call to B, puts it on
                //hold & then releases it.
                callForwardTxt = mContext.getString(R.string.card_title_callonhold_released);
                break;

            case SuppServiceNotification.MT_CODE_FORWARD_CHECK_RECEIVED:
                //This message is displayed on C when the incoming call is forwarded
                //from B
                callForwardTxt = mContext.getString(R.string.card_title_forwardcheckreceived);
                break;

            case SuppServiceNotification.MT_CODE_CALL_CONNECTING_ECT:
                //This message is displayed on B,when Call is connecting through
                //Explicit Cold Transfer
                callForwardTxt = mContext.getString(R.string.card_title_callconnectingect);
                break;

            case SuppServiceNotification.MT_CODE_CALL_CONNECTED_ECT:
                //This message is displayed on B,when Call is connected through
                //Explicit Cold Transfer
                callForwardTxt = mContext.getString(R.string.card_title_callconnectedect);
                break;

            case SuppServiceNotification.MT_CODE_DEFLECTED_CALL:
                //This message is displayed on B when the incoming call is deflected
                //call
                callForwardTxt = mContext.getString(R.string.card_title_deflectedcall);
                break;

            case SuppServiceNotification.MT_CODE_ADDITIONAL_CALL_FORWARDED:
                // This message is displayed on B when it is busy and the incoming call
                // gets forwarded to C
                callForwardTxt = mContext.getString(R.string.card_title_MTcall_forwarding);
                break;

            default :
               Log.v(LOG_TAG,"Received unsupported MT SS Notification :" + code
                      +" "+phoneId);
                break;
        }
        return callForwardTxt;
    }

    private String getMoSsNotificationText(int code, int phoneId) {
        String callForwardTxt = "";
        switch (code) {
            case SuppServiceNotification.MO_CODE_UNCONDITIONAL_CF_ACTIVE:
                // This message is displayed when an outgoing call is made
                // and unconditional forwarding is enabled.
                callForwardTxt = mContext.getString(R.string.card_title_unconditionalCF);
            break;

            case SuppServiceNotification.MO_CODE_SOME_CF_ACTIVE:
                // This message is displayed when an outgoing call is made
                // and conditional forwarding is enabled.
                callForwardTxt = mContext.getString(R.string.card_title_conditionalCF);
                break;

            case SuppServiceNotification.MO_CODE_CALL_FORWARDED:
                //This message is displayed on A when the outgoing call
                //actually gets forwarded to C
                callForwardTxt = mContext.getString(R.string.card_title_MOcall_forwarding);
                break;

            case SuppServiceNotification.MO_CODE_CALL_IS_WAITING:
                //This message is displayed on A when the B is busy on another call
                //and Call waiting is enabled on B
                callForwardTxt = mContext.getString(R.string.card_title_calliswaiting);
                break;

            case SuppServiceNotification.MO_CODE_CUG_CALL:
                //This message is displayed on A, when A makes call to B, both A & B
                //belong to a CUG group
                callForwardTxt = mContext.getString(R.string.card_title_cugcall);
                break;

            case SuppServiceNotification.MO_CODE_OUTGOING_CALLS_BARRED:
                //This message is displayed on A when outging is barred on A
                callForwardTxt = TelephonyGlobals.getApplicationContext().getString(
                        R.string.card_title_outgoing_barred);
                break;

            case SuppServiceNotification.MO_CODE_INCOMING_CALLS_BARRED:
                //This message is displayed on A, when A is calling B
                //& incoming is barred on B
                callForwardTxt = mContext.getString(R.string.card_title_incoming_barred);
                break;

            case SuppServiceNotification.MO_CODE_CLIR_SUPPRESSION_REJECTED:
                //This message is displayed on A, when CLIR suppression is rejected
                callForwardTxt = mContext.getString(R.string.card_title_clir_suppression_rejected);
                break;

            case SuppServiceNotification.MO_CODE_CALL_DEFLECTED:
                //This message is displayed on A, when the outgoing call
                //gets deflected to C from B
                callForwardTxt = mContext.getString(R.string.card_title_call_deflected);
                break;

            default:
                Log.v(LOG_TAG,"Received unsupported MO SS Notification :" + code
                        +" "+phoneId);
                break;
        }
        return callForwardTxt;
    }

    /**
     * Receiver to listen for radio technology change events.
     */
    private final BroadcastReceiver mRATReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED.equals(action)) {
                unregisterForNotifications();
                registerForNotifications();
            }
        }
    };

    private void registerForNotifications() {
        phones = PhoneFactory.getPhones();
        for (Phone phone : phones) {
            int phoneId = phone.getPhoneId();
            phone.registerForSuppServiceNotification(mHandler,
                    EVENT_SUPP_SERVICE_NOTIFY, phoneId);
        }
    }

    private void unregisterForNotifications() {
        for (Phone phone : phones) {
            phone.unregisterForSuppServiceNotification(mHandler);
        }
    }
}
