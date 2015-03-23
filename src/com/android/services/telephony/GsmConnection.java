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

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.telecom.CallProperties;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.gsm.SuppServiceNotification;

/**
 * Manages a single phone call handled by GSM.
 */
final class GsmConnection extends TelephonyConnection {

    private final boolean mIsForwarded;
    private boolean mAdditionalCallForwarded;
    private boolean mDialingIsWaiting;
    private boolean mRemoteIncomingCallsBarred;

    GsmConnection(Connection connection, boolean isForwarded) {
        super(connection);
        mIsForwarded = isForwarded;
        setCallProperties(computeCallProperties());
    }

    GsmConnection(Connection connection, boolean isForwarded, Call.State state) {
        super(connection, state);
        mIsForwarded = isForwarded;
        setCallProperties(computeCallProperties());
    }

    /**
     * Clones the current {@link GsmConnection}.
     * <p>
     * Listeners are not copied to the new instance.
     *
     * @return The cloned connection.
     */
    @Override
    public TelephonyConnection cloneConnection() {
        GsmConnection gsmConnection = new GsmConnection(getOriginalConnection(),
                mIsForwarded, getOriginalConnectionState());
        return gsmConnection;
    }

    /** {@inheritDoc} */
    @Override
    public void onPlayDtmfTone(char digit) {
        if (getPhone() != null) {
            getPhone().startDtmf(digit);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onStopDtmfTone() {
        if (getPhone() != null) {
            getPhone().stopDtmf();
        }
    }

    @Override
    protected int buildConnectionCapabilities() {
        int capabilities = super.buildConnectionCapabilities();
        capabilities |= CAPABILITY_MUTE;
        capabilities |= CAPABILITY_SUPPORT_HOLD;
        if (getState() == STATE_ACTIVE || getState() == STATE_HOLDING) {
            capabilities |= CAPABILITY_HOLD;
        }
        return capabilities;
    }

    @Override
    void onRemovedFromCallService() {
        super.onRemovedFromCallService();
    }

    @Override
    protected void onSuppServiceNotification(SuppServiceNotification notification) {
        int state = getState();

        Log.d(this, "SS Notification: " + notification);

        if (notification.notificationType == SuppServiceNotification.NOTIFICATION_TYPE_MT &&
                notification.code == SuppServiceNotification.MT_CODE_ADDITIONAL_CALL_FORWARDED) {
                mAdditionalCallForwarded = true;
        } else if (notification.notificationType == SuppServiceNotification.NOTIFICATION_TYPE_MO) {
            if (notification.code == SuppServiceNotification.MO_CODE_CALL_IS_WAITING) {
                if (state == STATE_DIALING) {
                    mDialingIsWaiting = true;
                }
            } else if (notification.code ==
                    SuppServiceNotification.MO_CODE_INCOMING_CALLS_BARRED) {
                mRemoteIncomingCallsBarred = true;
            }
        }

        super.onSuppServiceNotification(notification);
    }

    @Override
    protected int computeCallProperties() {
        int newProperties = super.computeCallProperties();

        if (mIsForwarded) newProperties |= CallProperties.WAS_FORWARDED;
        if (mAdditionalCallForwarded) newProperties |= CallProperties.ADDITIONAL_CALL_FORWARDED;
        if (mDialingIsWaiting) newProperties |= CallProperties.DIALING_IS_WAITING;
        if (mRemoteIncomingCallsBarred) {
            newProperties |= CallProperties.REMOTE_INCOMING_CALLS_BARRED;
        }
        return newProperties;
    }
}
