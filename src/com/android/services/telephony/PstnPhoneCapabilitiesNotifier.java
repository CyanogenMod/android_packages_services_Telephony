/*
 * Copyright (c) 2014, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.services.telephony;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.Preconditions;

/**
 * Listens to phone's capabilities changed event and notifies Telecomm. One instance of these exists
 * for each of the telephony-based call services.
 */
final class PstnPhoneCapabilitiesNotifier {
    private static final int EVENT_VIDEO_CAPABILITIES_CHANGED = 1;

    private final PhoneProxy mPhoneProxy;
    private Phone mPhoneBase;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_VIDEO_CAPABILITIES_CHANGED:
                    handleVideoCapabilitesChanged((AsyncResult) msg.obj);
                    break;
                default:
                    break;
            }
        }
    };

    private final BroadcastReceiver mRatReceiver = new BroadcastReceiver() {
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

    /*package*/
    PstnPhoneCapabilitiesNotifier(PhoneProxy phoneProxy) {
        Preconditions.checkNotNull(phoneProxy);

        mPhoneProxy = phoneProxy;

        registerForNotifications();

        IntentFilter intentFilter =
                new IntentFilter(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED);
        mPhoneProxy.getContext().registerReceiver(mRatReceiver, intentFilter);
    }

    /*package*/
    void teardown() {
        unregisterForNotifications();
        mPhoneProxy.getContext().unregisterReceiver(mRatReceiver);
    }

    private void registerForNotifications() {
        Phone newPhone = mPhoneProxy.getActivePhone();
        if (newPhone != mPhoneBase) {
            unregisterForNotifications();

            if (newPhone != null) {
                Log.d(this, "Registering: " + newPhone);
                mPhoneBase = newPhone;
                mPhoneBase.registerForVideoCapabilityChanged(
                        mHandler, EVENT_VIDEO_CAPABILITIES_CHANGED, null);
            }
        }
    }

    private void unregisterForNotifications() {
        if (mPhoneBase != null) {
            Log.d(this, "Unregistering: " + mPhoneBase);
            mPhoneBase.unregisterForVideoCapabilityChanged(mHandler);
        }
    }

    private void handleVideoCapabilitesChanged(AsyncResult ar) {
        try {
            boolean isVideoCapable = (Boolean) ar.result;
            Log.d(this, "handleVideoCapabilitesChanged. Video capability - " + isVideoCapable);
            PhoneAccountHandle accountHandle =
                    TelecomAccountRegistry.makePstnPhoneAccountHandle(mPhoneProxy);
            TelecomManager telecomMgr = TelecomManager.from(mPhoneProxy.getContext());
            PhoneAccount oldPhoneAccount = telecomMgr.getPhoneAccount(accountHandle);

            int capabilites = newCapabilities(oldPhoneAccount.getCapabilities(),
                    PhoneAccount.CAPABILITY_VIDEO_CALLING, isVideoCapable);
            PhoneAccount.Builder builder = PhoneAccount.builder(accountHandle,
                    oldPhoneAccount.getLabel());

            if (oldPhoneAccount.getIconBitmap() != null) {
                builder.setIcon(oldPhoneAccount.getIconBitmap());
            } else {
                builder.setIcon(mPhoneProxy.getContext(), oldPhoneAccount.getIconResId());
            }

            PhoneAccount newPhoneAccount = builder
                    .setAddress(oldPhoneAccount.getAddress())
                    .setSubscriptionAddress(oldPhoneAccount.getSubscriptionAddress())
                    .setCapabilities(capabilites)
                    .setHighlightColor(oldPhoneAccount.getHighlightColor())
                    .setShortDescription(oldPhoneAccount.getShortDescription())
                    .setSupportedUriSchemes(oldPhoneAccount.getSupportedUriSchemes())
                    .build();

            telecomMgr.registerPhoneAccount(newPhoneAccount);
        } catch (Exception e) {
            Log.d(this, "handleVideoCapabilitesChanged. Exception=" + e);
        }
    }

    private int newCapabilities(int capabilities, int capability, boolean set) {
        if (set) {
            capabilities |= capability;
        } else {
            capabilities &= ~capability;
        }
        return capabilities;
    }
}
