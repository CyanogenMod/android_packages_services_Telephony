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
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.cdma.CDMAPhone;
import com.android.internal.telephony.cdma.CdmaCallWaitingNotification;
import com.android.internal.telephony.gsm.SuppServiceNotification;
import com.android.phone.PhoneUtils;

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
                if (number != null && Objects.equals(number, ccwi.number)) {
                    sendIncomingCallIntent(connection);
                }
            }
        }
    }

    private void handleNewUnknownConnection(AsyncResult asyncResult) {
        Log.i(this, "handleNewUnknownConnection");
        if (!(asyncResult.result instanceof Connection)) {
            Log.w(this, "handleNewUnknownConnection called with non-Connection object");
            return;
        }
        Connection connection = (Connection) asyncResult.result;
        if (connection != null) {
            // Because there is a handler between telephony and here, it causes this action to be
            // asynchronous which means that the call can switch to DISCONNECTED by the time it gets
            // to this code. Check here to ensure we are not adding a disconnected or IDLE call.
            Call.State state = connection.getState();
            if (state == Call.State.DISCONNECTED || state == Call.State.IDLE) {
                Log.i(this, "Skipping new unknown connection because it is idle. " + connection);
                return;
            }

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
        Log.i(this, "addNewUnknownCall, connection is: %s", connection);

        if (!maybeSwapAnyWithUnknownConnection(connection)) {
            Log.i(this, "determined new connection is: %s", connection);
            Bundle extras = null;
            if (connection.getNumberPresentation() == TelecomManager.PRESENTATION_ALLOWED &&
                    !TextUtils.isEmpty(connection.getAddress())) {
                extras = new Bundle();
                Uri uri = Uri.fromParts(PhoneAccount.SCHEME_TEL, connection.getAddress(), null);
                extras.putParcelable(TelecomManager.EXTRA_UNKNOWN_CALL_HANDLE, uri);
            }
            PhoneAccountHandle handle = findCorrectPhoneAccountHandle();
            if (handle == null) {
                try {
                    connection.hangup();
                } catch (CallStateException e) {
                    // connection already disconnected. Do nothing
                }
            } else {
                TelecomManager.from(mPhoneProxy.getContext()).addNewUnknownCall(handle, extras);
            }
        } else {
            Log.i(this, "swapped an old connection, new one is: %s", connection);
        }
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
            extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, uri);
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

        PhoneAccountHandle handle = findCorrectPhoneAccountHandle();
        if (handle == null) {
            try {
                connection.hangup();
            } catch (CallStateException e) {
                // connection already disconnected. Do nothing
            }
        } else {
            TelecomManager.from(mPhoneProxy.getContext()).addNewIncomingCall(handle, extras);
        }
    }

    /**
     * Returns the PhoneAccount associated with this {@code PstnIncomingCallNotifier}'s phone. On a
     * device with No SIM or in airplane mode, it can return an Emergency-only PhoneAccount. If no
     * PhoneAccount is registered with telecom, return null.
     * @return A valid PhoneAccountHandle that is registered to Telecom or null if there is none
     * registered.
     */
    private PhoneAccountHandle findCorrectPhoneAccountHandle() {
        TelecomAccountRegistry telecomAccountRegistry = TelecomAccountRegistry.getInstance(null);
        // Check to see if a the SIM PhoneAccountHandle Exists for the Call.
        PhoneAccountHandle handle = PhoneUtils.makePstnPhoneAccountHandle(mPhoneBase);
        if (telecomAccountRegistry.hasAccountEntryForPhoneAccount(handle)) {
            return handle;
        }
        // The PhoneAccountHandle does not match any PhoneAccount registered in Telecom.
        // This is only known to happen if there is no SIM card in the device and the device
        // receives an MT call while in ECM. Use the Emergency PhoneAccount to receive the account.
        PhoneAccountHandle emergencyHandle =
                PhoneUtils.makePstnPhoneAccountHandleWithPrefix(mPhoneBase, "",
                        true /* isEmergency */);
        if(telecomAccountRegistry.hasAccountEntryForPhoneAccount(emergencyHandle)) {
            Log.i(this, "Receiving MT call in ECM. Using Emergency PhoneAccount Instead.");
            return emergencyHandle;
        }
        Log.w(this, "PhoneAccount not found.");
        return null;
    }

    /**
     * Define cait.Connection := com.android.internal.telephony.Connection
     *
     * Given a previously unknown cait.Connection, check to see if it's likely a replacement for
     * another cait.Connnection we already know about. If it is, then we silently swap it out
     * underneath within the relevant {@link TelephonyConnection}, using
     * {@link TelephonyConnection#setOriginalConnection(Connection)}, and return {@code true}.
     * Otherwise, we return {@code false}.
     */
    private boolean maybeSwapAnyWithUnknownConnection(Connection unknown) {
        if (!unknown.isIncoming()) {
            TelecomAccountRegistry registry = TelecomAccountRegistry.getInstance(null);
            if (registry != null) {
                TelephonyConnectionService service = registry.getTelephonyConnectionService();
                if (service != null) {
                    for (android.telecom.Connection telephonyConnection : service
                            .getAllConnections()) {
                        if (telephonyConnection instanceof TelephonyConnection) {
                            if (maybeSwapWithUnknownConnection(
                                    (TelephonyConnection) telephonyConnection,
                                    unknown)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean maybeSwapWithUnknownConnection(
            TelephonyConnection telephonyConnection,
            Connection unknown) {
        Connection original = telephonyConnection.getOriginalConnection();
        if (original != null && !original.isIncoming()
                && Objects.equals(original.getAddress(), unknown.getAddress())) {
            telephonyConnection.setOriginalConnection(unknown);
            return true;
        }
        return false;
    }
}
