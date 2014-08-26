/*
 * Copyright (c) 2013, The Linux Foundation. All Rights Reserved.
 *
 * Redistribution and use in source and binary forms, with or without
   modification, are permitted provided that the following conditions are
   met:
 * Redistributions of source code must retain the above copyright
       notice, this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above
       copyright notice, this list of conditions and the following
       disclaimer in the documentation and/or other materials provided
       with the distribution.
 * Neither the name of The Linux Foundation, Inc. nor the names of its
       contributors may be used to endorse or promote products derived
       from this software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.phone;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SqliteWrapper;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.MSimTelephonyManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Config;
import android.util.Log;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.TelephonyProperties;

/**
 * Container of transaction settings. Instances of this class are contained
 * within Transaction instances to allow overriding of the default APN
 * settings or of the MMS Client.
 */
public class CdmaCallOptionsSetting {
    private static final String TAG = "CdmaCallOptionsSetting";
    private static final boolean DEBUG = true;
    private static final boolean LOCAL_LOGV = DEBUG ? Config.LOGD : Config.LOGV;

    private String mActNumber = "";
    private String mDeActNumber = "";
    private int mType;
    private int mCategory;

    private int mSubscription;

    private static final String NUM_PROJECTION[] = {
        Telephony.CdmaCallOptions.NUMBER,
        Telephony.CdmaCallOptions.STATE
    };

    private static final int COLUMN_NUMBER = 0;
    private static final int COLUMN_STATE = 1;

    private static final int ACTIVATED = 1;
    private static final int DEACTIVATED = 0;

    /**
     * Constructor that uses the default settings of the Cdma Call Option Client.
     *
     * @param context The context of the Cdma Call Option Client
     */
    public CdmaCallOptionsSetting(Context context, int type, int category, int subscription) {

        mType = type;
        mCategory = category;

        mSubscription = subscription;

        StringBuilder selection = new StringBuilder();

        // query all mcc/mnc related feature code
        selection.append("numeric = " + getOperatorNumeric());
        if(TextUtils.isEmpty(getOperatorNumeric())){
            Log.e(TAG, "numeric is not found!");
            return;
        }

        if(mCategory != -1) {
           selection.append(" and category = " + mCategory);
        }

        Cursor cursor = SqliteWrapper.query(context, context.getContentResolver(),
                Uri.withAppendedPath(Telephony.CdmaCallOptions.CONTENT_URI,
                        getCallOptionType(type)),
                                NUM_PROJECTION, selection.toString(), null, null);

        if (cursor == null) {
            Log.e(TAG, "call option is not found in Database!");
            return;
        }

        try {
            while (cursor.moveToNext()) {
                // Read values from cdma call option provider
                int state = Integer.valueOf(cursor.getString(COLUMN_STATE));
                if (state == ACTIVATED) {
                    mActNumber = cursor.getString(COLUMN_NUMBER);
                    if(DEBUG) Log.d(TAG, "act number for type " + mType + " is " + mActNumber);
                } else {
                    mDeActNumber = cursor.getString(COLUMN_NUMBER);
                    if(DEBUG) Log.d(TAG, "deact number for type " + mType + " is " + mDeActNumber);
                }
            }
        } finally {
            cursor.close();
        }
    }

    public CdmaCallOptionsSetting(Context context, int type, int subscription) {
        this(context, type, -1, subscription);
    }

    public CdmaCallOptionsSetting(int type, int category, String actNum, String deactNum) {
        mType = type;
        mCategory = category;
        mActNumber = actNum;
        mDeActNumber = deactNum;
    }

    public String getActivateNumber() {
        return mActNumber;
    }

    public String getDeactivateNumber() {
        return mDeActNumber;
    }

    private String getOperatorNumeric() {
        String numeric;
        numeric = MSimTelephonyManager.getDefault().getSimOperator(mSubscription);
        if(DEBUG) Log.d(TAG, "numeric is " + numeric + " sub " + mSubscription);
        return numeric;
    }

    private String getCallOptionType(int type) {
        String callType;
        switch (type) {
            case CommandsInterface.CF_REASON_UNCONDITIONAL: {
                callType = "cfu";
                break;
            }
            case CommandsInterface.CF_REASON_BUSY: {
                callType = "cfb";
                break;
            }
            case CommandsInterface.CF_REASON_NO_REPLY: {
                callType = "cfnry";
                break;
            }
            case CommandsInterface.CF_REASON_NOT_REACHABLE: {
                callType = "cfnrc";
                break;
            }
            case CommandsInterface.CF_REASON_ALL: {
                callType = "cfda";
                break;
            }
            case PhoneGlobals.CALL_WAITING: {
                callType = "cw";
                break;
            }
            default: {
                callType = "cfu";
            }
        }
        return callType;
    }
}
