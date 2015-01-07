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
 *
 */

package com.android.phone;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.AsyncResult;
import android.os.Message;
import android.os.RemoteException;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.telephony.SubscriptionManager;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;

public class NetworkSettingDataManager {
    private static final String LOG_TAG = "phone ";
    private static final boolean DBG = true;
    Context mContext;
    private TelephonyManager mTelephonyManager;
    private boolean mNetworkSearchDataDisconnecting = false;
    private boolean mNetworkSearchDataDisabled = false;
    Message mMsg;

    public NetworkSettingDataManager(Context context) {
        mContext  = context;
        if (DBG) log(" Create NetworkSettingDataManager");
        mTelephonyManager = (TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE);
    }

    /**
     * Receiver for ANY_DATA_CONNECTION_STATE_CHANGED
     */
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (mNetworkSearchDataDisconnecting) {
                if (action.equals(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED)) {
                    if (mTelephonyManager.getDataState() == TelephonyManager.DATA_DISCONNECTED) {
                        log(" network disconnect data done");
                        mNetworkSearchDataDisabled = true;
                        mNetworkSearchDataDisconnecting = false;
                        mMsg.arg1 = 1;
                        mMsg.sendToTarget();

                    }
                }
            }
        }
    };

    public void updateDataState(boolean enable, Message msg) {
        if (!enable) {
            if (mTelephonyManager.getDataState() == TelephonyManager.DATA_CONNECTED) {
                log(" Data is in CONNECTED state");
                mMsg = msg;
                ConfirmDialogListener listener = new ConfirmDialogListener(msg);
                AlertDialog d = new AlertDialog.Builder(mContext)
                        .setTitle(android.R.string.dialog_alert_title)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setMessage(R.string.disconnect_data_confirm)
                        .setPositiveButton(android.R.string.ok, listener)
                        .setNegativeButton(android.R.string.no, listener)
                        .setOnCancelListener(listener)
                        .create();

                d.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
                d.show();
            } else {
                msg.arg1 = 1;
                msg.sendToTarget();
            }
        } else {
            if (mNetworkSearchDataDisabled || mNetworkSearchDataDisconnecting) {
                //enable data service
                mTelephonyManager.setDataEnabledUsingSubId( SubscriptionManager
                        .getDefaultDataSubId(), true);
                mContext.unregisterReceiver(mReceiver);
                mNetworkSearchDataDisabled = false;
                mNetworkSearchDataDisconnecting = false;
            }
        }
    }

    private final class ConfirmDialogListener
            implements DialogInterface.OnClickListener, DialogInterface.OnCancelListener {

        Message msg1;
        ConfirmDialogListener(Message msg) {
            msg1 = msg;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (which == DialogInterface.BUTTON_POSITIVE){
                //disable data service
                IntentFilter intentFilter = new IntentFilter();
                intentFilter.addAction(
                        TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
                mContext.registerReceiver(mReceiver, intentFilter);
                mNetworkSearchDataDisconnecting = true;
                mTelephonyManager.setDataEnabledUsingSubId(SubscriptionManager
                        .getDefaultDataSubId(), false);
            } else if (which == DialogInterface.BUTTON_NEGATIVE){
                log(" network search, do nothing");
                msg1.arg1 = 0;
                msg1.sendToTarget();
            }
        }

        @Override
        public void onCancel(DialogInterface dialog) {
            msg1.arg1 = 0;
            msg1.sendToTarget();
        }
    }

    private void log(String msg) {
        Log.d(LOG_TAG, "[NetworkSettingDataManager] " + msg);
    }
}
