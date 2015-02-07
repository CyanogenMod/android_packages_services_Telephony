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

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;
import android.telecom.CallState;
import android.telecom.PhoneAccount;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.cdma.CdmaCallWaitingNotification;
import com.android.phone.PhoneUtils;
import com.android.internal.telephony.gsm.SuppServiceNotification;
import com.google.common.base.Preconditions;

import java.util.Objects;

/**
 * Listens to incoming-call events from the associated phone object and notifies Telecom upon each
 * occurence. One instance of these exists for each of the telephony-based call services.
 */
final class PstnIncomingCallNotifier {
    /** New ringing connection event code. */
    private static final int EVENT_NEW_RINGING_CONNECTION = 100;
    private static final int EVENT_CDMA_CALL_WAITING = 101;
    private static final int EVENT_UNKNOWN_CONNECTION = 102;
    private static final int EVENT_SUPP_SERVICE_NOTIFY = 103;

    /** The phone proxy object to listen to. */
    private final PhoneProxy mPhoneProxy;

    private static final Uri FIREWALL_PROVIDER_URI = Uri
            .parse("content://com.android.firewall");
    private static final String EXTRA_NUMBER = "phonenumber";
    private static final String IS_FORBIDDEN = "isForbidden";
    private static final String BLOCK_NUMBER = "number";
    private static final String SUB_ID = "sub_id";
    private static final String BLOCK_CALL_INTENT = "com.android.firewall.ADD_CALL_BLOCK_RECORD";

    /**
     * The base phone implementation behind phone proxy. The underlying phone implementation can
     * change underneath when the radio technology changes. We listen for these events and update
     * the base phone in this variable. We save it so that when the change happens, we can
     * unregister from the events we were listening to.
     */
    private Phone mPhoneBase;

    private boolean mNextGsmCallIsForwarded = false;

    /**
     * Used to listen to events from {@link #mPhoneBase}.
     */
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case EVENT_NEW_RINGING_CONNECTION:
                    handleNewRingingConnection((AsyncResult) msg.obj);
                    break;
                case EVENT_CDMA_CALL_WAITING:
                    handleCdmaCallWaiting((AsyncResult) msg.obj);
                    break;
                case EVENT_UNKNOWN_CONNECTION:
                    handleNewUnknownConnection((AsyncResult) msg.obj);
                    break;
                case EVENT_SUPP_SERVICE_NOTIFY:
                    handleSuppServiceNotification((AsyncResult) msg.obj);
                    break;
                default:
                    break;
            }
        }
    };

    /**
     * Receiver to listen for radio technology change events.
     */
    private final BroadcastReceiver mRATReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED.equals(action)) {
                String newPhone = intent.getStringExtra(PhoneConstants.PHONE_NAME_KEY);
                Log.d(this, "Radio technology switched. Now %s is active.", newPhone);

                registerForNotifications();
            }
        }
    };

    /**
     * Persists the specified parameters and starts listening to phone events.
     *
     * @param phoneProxy The phone object for listening to incoming calls.
     */
    PstnIncomingCallNotifier(PhoneProxy phoneProxy) {
        Preconditions.checkNotNull(phoneProxy);

        mPhoneProxy = phoneProxy;

        registerForNotifications();

        IntentFilter intentFilter =
                new IntentFilter(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED);
        mPhoneProxy.getContext().registerReceiver(mRATReceiver, intentFilter);
    }

    void teardown() {
        unregisterForNotifications();
        mPhoneProxy.getContext().unregisterReceiver(mRATReceiver);
    }

    /**
     * Register for notifications from the base phone.
     * TODO: We should only need to interact with the phoneproxy directly. However,
     * since the phoneproxy only interacts directly with CallManager we either listen to callmanager
     * or we have to poke into the proxy like this.  Neither is desirable. It would be better if
     * this class and callManager could register generically with the phone proxy instead and get
     * radio techonology changes directly.  Or better yet, just register for the notifications
     * directly with phone proxy and never worry about the technology changes. This requires a
     * change in opt/telephony code.
     */
    private void registerForNotifications() {
        Phone newPhone = mPhoneProxy.getActivePhone();
        if (newPhone != mPhoneBase) {
            unregisterForNotifications();

            if (newPhone != null) {
                Log.i(this, "Registering: %s", newPhone);
                mPhoneBase = newPhone;
                mPhoneBase.registerForNewRingingConnection(
                        mHandler, EVENT_NEW_RINGING_CONNECTION, null);
                mPhoneBase.registerForCallWaiting(
                        mHandler, EVENT_CDMA_CALL_WAITING, null);
                mPhoneBase.registerForSuppServiceNotification(
                        mHandler, EVENT_SUPP_SERVICE_NOTIFY, null);
                mPhoneBase.registerForUnknownConnection(mHandler, EVENT_UNKNOWN_CONNECTION,
                        null);
            }
        }
    }

    private void unregisterForNotifications() {
        if (mPhoneBase != null) {
            Log.i(this, "Unregistering: %s", mPhoneBase);
            mPhoneBase.unregisterForNewRingingConnection(mHandler);
            mPhoneBase.unregisterForCallWaiting(mHandler);
            mPhoneBase.unregisterForUnknownConnection(mHandler);
            mPhoneBase.unregisterForSuppServiceNotification(mHandler);
        }
    }

    /**
     * Verifies the incoming call and triggers sending the incoming-call intent to Telecom.
     *
     * @param asyncResult The result object from the new ringing event.
     */
    private void handleNewRingingConnection(AsyncResult asyncResult) {
        Log.d(this, "handleNewRingingConnection");
        Connection connection = (Connection) asyncResult.result;
        if (connection != null) {
            Call call = connection.getCall();

            // Final verification of the ringing state before sending the intent to Telecom.
            if (call != null && call.getState().isRinging()) {
                Phone phone = call.getPhone();
                if (phone != null
                        && isBlockedByFirewall(connection.getAddress(), phone.getPhoneId())) {
                    PhoneUtils.hangupRingingCall(call);
                    sendBlockRecordBroadcast(phone.getPhoneId(), connection.getAddress());
                    return;
                }
                sendIncomingCallIntent(connection);
            }
        }
    }

    private void handleCdmaCallWaiting(AsyncResult asyncResult) {
        Log.d(this, "handleCdmaCallWaiting");
        CdmaCallWaitingNotification ccwi = (CdmaCallWaitingNotification) asyncResult.result;
        Call call = mPhoneBase.getRingingCall();
        if (call.getState() == Call.State.WAITING) {
            Connection connection = call.getLatestConnection();
            if (connection != null) {
                String number = connection.getAddress();
                Phone phone = call.getPhone();
                if (phone != null
                        && isBlockedByFirewall(number, phone.getPhoneId())) {
                    PhoneUtils.hangupRingingCall(call);
                    sendBlockRecordBroadcast(phone.getPhoneId(), number);
                    return;
                }
                if (number != null && Objects.equals(number, ccwi.number)) {
                    sendIncomingCallIntent(connection);
                }
            }
        }
    }

    private void sendBlockRecordBroadcast (int subId, String number) {
        Intent intent = new Intent(BLOCK_CALL_INTENT);
        intent.putExtra(SUB_ID, subId);
        intent.putExtra(BLOCK_NUMBER, number);
        mPhoneProxy.getContext().sendBroadcast(intent);
    }

    private void handleNewUnknownConnection(AsyncResult asyncResult) {
        Log.i(this, "handleNewUnknownConnection");
        if (!(asyncResult.result instanceof Connection)) {
            Log.w(this, "handleNewUnknownConnection called with non-Connection object");
            return;
        }
        Connection connection = (Connection) asyncResult.result;
        if (connection != null) {
            Call call = connection.getCall();
            if (call != null && call.getState().isAlive()) {
                addNewUnknownCall(connection);
            }
        }
    }

    private void handleSuppServiceNotification(AsyncResult asyncResult) {
        SuppServiceNotification ssn = (SuppServiceNotification) asyncResult.result;

        if (ssn.notificationType == SuppServiceNotification.NOTIFICATION_TYPE_MT) {
            if (ssn.code == SuppServiceNotification.MT_CODE_FORWARDED_CALL
                    || ssn.code == SuppServiceNotification.MT_CODE_DEFLECTED_CALL) {
                mNextGsmCallIsForwarded = true;
            }
        }
    }

    private void addNewUnknownCall(Connection connection) {
        Bundle extras = null;
        extras = new Bundle();
        Uri uri = null;

        if (connection.getNumberPresentation() == TelecomManager.PRESENTATION_ALLOWED &&
                !TextUtils.isEmpty(connection.getAddress())) {
            uri = Uri.fromParts(PhoneAccount.SCHEME_TEL, connection.getAddress(), null);
            extras.putParcelable(TelecomManager.EXTRA_UNKNOWN_CALL_HANDLE, uri);
        }

        if ((connection.getCall() != null) &&
                (connection.getCall().getState() == Call.State.HOLDING)) {
            extras.putString(TelecomManager.EXTRA_UNKNOWN_CALL_STATE,
                    connection.getCall().getState().toString());
        }

        TelecomManager.from(mPhoneProxy.getContext()).addNewUnknownCall(
                TelecomAccountRegistry.makePstnPhoneAccountHandle(mPhoneProxy), extras);
    }

    private boolean isBlockedByFirewall(String number, int sub) {
        boolean isForbidden = false;
        // Add to check the firewall when firewall provider is built.
        final ContentResolver cr = mPhoneProxy.getContext().getContentResolver();
        if (cr.acquireProvider(FIREWALL_PROVIDER_URI) != null) {
            Bundle extras = new Bundle();
            extras.putInt(PhoneConstants.SUBSCRIPTION_KEY, sub);
            extras.putString(EXTRA_NUMBER, number);
            extras = cr.call(FIREWALL_PROVIDER_URI, IS_FORBIDDEN, null, extras);
            if (extras != null) {
                isForbidden = extras.getBoolean(IS_FORBIDDEN);
            }
        }
        return isForbidden;
    }

    /**
     * Sends the incoming call intent to telecom.
     */
    private void sendIncomingCallIntent(Connection connection) {
        Bundle extras = null;
        if (connection.getNumberPresentation() == TelecomManager.PRESENTATION_ALLOWED &&
                !TextUtils.isEmpty(connection.getAddress())) {
            extras = new Bundle();
            Uri uri = Uri.fromParts(PhoneAccount.SCHEME_TEL, connection.getAddress(), null);
            extras.putParcelable(TelephonyManager.EXTRA_INCOMING_NUMBER, uri);
        }

        if (mNextGsmCallIsForwarded) {
            int phoneType = connection.getCall().getPhone().getPhoneType();
            if (phoneType == TelephonyManager.PHONE_TYPE_GSM) {
                if (extras == null) {
                    extras = new Bundle();
                }
                extras.putBoolean(TelephonyManager.EXTRA_IS_FORWARDED, true);
                mNextGsmCallIsForwarded = false;
            }
        }

        TelecomManager.from(mPhoneProxy.getContext()).addNewIncomingCall(
                TelecomAccountRegistry.makePstnPhoneAccountHandle(mPhoneProxy), extras);
    }
}
