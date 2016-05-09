/*
 * Copyright (C) 2010-2013 The CyanogenMod Project
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
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;

public class MyPhoneNumber extends BroadcastReceiver {
    private final String LOG_TAG = "MyPhoneNumber";
    private final boolean DBG = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        TelephonyManager telephonyMgr =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String phoneNum = telephonyMgr.getLine1Number();
        final int slot = intent.getIntExtra(PhoneConstants.SLOT_KEY, 0);
        String savedNum = prefs.getString(MSISDNEditPreference.PHONE_NUMBER, null);
        String simState = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);

        //Get the sub from the slot
        final int[] subIdForSlot = SubscriptionManager.getSubId(slot);
        if (subIdForSlot == null) {
            if (DBG) {
                Log.e(LOG_TAG, "subIdForSlot: " + slot + " returns null");
                return;
            }
            return;
        }
        final int subId = subIdForSlot[0];
        //Get the phone id from the sub
        final int phoneId = SubscriptionManager.getPhoneId(subId);

        if (!IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(simState)) {
            /* Don't set line 1 number unless SIM_STATE is LOADED
             * (We're not using READY because the MSISDN record is not yet loaded on READY)
             */
            if (DBG) {
                Log.d(LOG_TAG, "Invalid simState: " + simState + ". Phone number not set.");
            }
        } else if (TextUtils.isEmpty(phoneNum)) {
            if (DBG) {
                Log.d(LOG_TAG, "Trying to read the phone number from file");
            }

            if (savedNum != null) {
                Phone mPhone = PhoneFactory.getPhone(phoneId);
                String alphaTag = mPhone.getLine1AlphaTag();

                if (TextUtils.isEmpty(alphaTag)) {
                    // No tag, set it.
                    alphaTag = context.getString(R.string.msisdn_alpha_tag);
                }

                mPhone.setLine1Number(alphaTag, savedNum, null);

                if (DBG) {
                    Log.d(LOG_TAG, "Phone number set to: " + savedNum);
                }
            } else if (DBG) {
                Log.d(LOG_TAG, "No phone number set yet");
            }
        } else if (DBG) {
            Log.d(LOG_TAG, "Phone number exists. No need to read it from file.");
        }
    }
}
