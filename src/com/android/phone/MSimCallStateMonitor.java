/*
 * Copyright (c) 2013 The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2013 The Android Open Source Project
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

import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;


/**
 * Dedicated Call state monitoring class.  This class communicates directly with
 * the call manager to listen for call state events and notifies registered
 * handlers.
 * It works as an inverse multiplexor for all classes wanted Call State updates
 * so that there exists only one channel to the telephony layer.
 *
 * TODO: Add manual phone state checks (getState(), etc.).
 */
class MSimCallStateMonitor extends CallStateMonitor {
    private static final String LOG_TAG = MSimCallStateMonitor.class.getSimpleName();
    private static final boolean DBG =
            (PhoneGlobals.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);

    // Events generated internally:
    public MSimCallStateMonitor(CallManager callManager) {
        super(callManager);
    }

    /**
     * Register for call state notifications with the CallManager.
     */
    @Override
    protected void registerForNotifications() {
        super.registerForNotifications();

        for (Phone phone : callManager.getAllPhones()) {
            if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
                Log.d(LOG_TAG, "register for cdma call waiting " + phone.getSubscription());
                callManager.registerForCallWaiting(this, PHONE_CDMA_CALL_WAITING,
                        phone.getSubscription());
                break;
            }
        }
        callManager.registerForSubscriptionChange(this, PHONE_ACTIVE_SUBSCRIPTION_CHANGE, null);
    }

    /**
     * When radio technology changes, we need to to reregister for all the events which are
     * all tied to the old radio.
     */
    @Override
    public void updateAfterRadioTechnologyChange() {
        if (DBG) Log.d(LOG_TAG, "updateCallNotifierRegistrationsAfterRadioTechnologyChange...");

        // Unregister all events from the old obsolete phone
        callManager.unregisterForSubscriptionChange(this);
        super.updateAfterRadioTechnologyChange();
    }
}
