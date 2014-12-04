/*
 * Copyright (c) 2013-2014, The Linux Foundation. All Rights Reserved.
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

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.TypedArray;
import android.net.Uri;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.telephony.CommandsInterface;

public class CdmaCallForwardEditPreference extends EditPhoneNumberPreference {
    private static final String LOG_TAG = "CdmaCallForwardEditPreference";

    private int mButtonClicked;
    private Context mContext;

    private int mPhoneId;
    private String mPrefixNumber;

    private Activity mForeground;

    int reason;

    public CdmaCallForwardEditPreference(Context context, AttributeSet attrs) {
        super(context, attrs);

        mContext = context;
        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.CallForwardEditPreference, 0, R.style.EditPhoneNumberPreference);
        reason = a.getInt(R.styleable.CallForwardEditPreference_reason,
                CommandsInterface.CF_REASON_UNCONDITIONAL);
        a.recycle();
    }

    public CdmaCallForwardEditPreference(Context context) {
        this(context, null);
    }

    public int getCallOptionType() {
        return reason;
    }

    public void init(Activity foreground, int phoneId, String prefixNum) {
        mForeground = foreground;
        mPhoneId = phoneId;
        mPrefixNumber = prefixNum;
        setSummary(prefixNum);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        mButtonClicked = which;
        Log.d(LOG_TAG, "mButtonClicked= " + mButtonClicked);
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        Log.d(LOG_TAG, "mButtonClicked: " + mButtonClicked
                + ", positiveResult: " + positiveResult + " mPrefixNumber: " + mPrefixNumber);
        // A positive result is technically either button1 or button3.
        if (mButtonClicked == DialogInterface.BUTTON_NEUTRAL) {
            final String forwardingNumber = getEditText().getText().toString();
            if (forwardingNumber.trim().length() == 0) {
                Toast.makeText(mContext, R.string.null_phone_number, Toast.LENGTH_LONG).show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_CALL_PRIVILEGED);
            intent.setData(Uri.fromParts("tel", mPrefixNumber + forwardingNumber, null));

            PhoneAccountHandle account = PhoneGlobals.getPhoneAccountHandle(mForeground, mPhoneId);
            if (account != null) {
                intent.putExtra(
                        TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account);
            }
            mForeground.startActivity(intent);
        } else {
            super.onDialogClosed(positiveResult);
        }
    }
}
