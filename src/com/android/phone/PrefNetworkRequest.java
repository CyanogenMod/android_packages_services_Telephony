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

import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.Phone;

public class PrefNetworkRequest extends SyncQueue.SyncRequest {

    private static final String TAG = "pref_network_request";

    private static final void logd(String msg) {
        Log.d(PrimarySubSelectionController.TAG, TAG + " " + msg);
    }

    private static final SyncQueue sSyncQueue = new SyncQueue();

    private final Message mCallback;
    private final List<PrefNetworkSetCommand> commands;
    private final Context mContext;

    private static final int EVENT_SET_PREF_NETWORK_DONE = 1;
    private static final int EVENT_GET_PREF_NETWORK_DONE = 2;
    private static final int EVENT_START_REQUEST = 3;

    private Handler mHandler = new Handler(Looper.getMainLooper()) {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_SET_PREF_NETWORK_DONE:
                    handleSetPreferredNetwork(msg);
                    break;
                case EVENT_GET_PREF_NETWORK_DONE:
                    handleGetPreferredNetwork(msg);
                    break;
                case EVENT_START_REQUEST:
                    request((Integer) msg.obj);
                    break;
            }
        }

    };

    private class PrefNetworkSetCommand {
        private final int mSlot;
        private final int mPrefNetwork;

        private PrefNetworkSetCommand(int slot, int prefNetwork) {
            mSlot = slot;
            mPrefNetwork = prefNetwork;
        }
    }

    private void request(final int index) {
        final PrefNetworkSetCommand command = commands.get(index);
        logd("save network mode " + command.mPrefNetwork + " for slot" + command.mSlot
                + " to DB first");
        savePrefNetworkInSetting(command.mSlot, command.mPrefNetwork);

        logd("set " + command.mPrefNetwork + " for slot" + command.mSlot);
        PrimarySubSelectionController.getInstance().mPhones[command.mSlot].setPreferredNetworkType(
                command.mPrefNetwork,
                mHandler.obtainMessage(EVENT_SET_PREF_NETWORK_DONE, index));
    }

    private void handleGetPreferredNetwork(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;
        int index = (Integer) ar.userObj;
        PrefNetworkSetCommand command = commands.get(index);
        if (ar.exception == null) {
            int modemNetworkMode = ((int[]) ar.result)[0];
            savePrefNetworkInSetting(command.mSlot, modemNetworkMode);
        }
        logd("get perferred network for slot" + command.mSlot + " done, " + ar.exception);
        if (++index < commands.size()) {
            request(index);
        } else {
            response(mCallback);
            end();
        }
    }

    private void handleSetPreferredNetwork(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;
        int index = (Integer) ar.userObj;
        PrefNetworkSetCommand command = commands.get(index);
        logd("set " + command.mPrefNetwork + " for slot" + command.mSlot + " done, "
                + ar.exception);
        if (ar.exception != null) {
            PrimarySubSelectionController.getInstance().mPhones[command.mSlot].getPreferredNetworkType(
                    mHandler.obtainMessage(EVENT_GET_PREF_NETWORK_DONE, index));
            return;
        }
        if (++index < commands.size()) {
            request(index);
        } else {
            response(mCallback);
            end();
        }
    }

    public PrefNetworkRequest(Context context, int slot, int networkMode, Message callback) {
        super(sSyncQueue);
        mContext = context;
        mCallback = callback;
        commands = new ArrayList<PrefNetworkSetCommand>();
        if (networkMode != Phone.NT_MODE_GSM_ONLY) {
            for (int index = 0; index < PrimarySubSelectionController.PHONE_COUNT; index++) {
                if (index != slot)
                    commands.add(new PrefNetworkSetCommand(index, Phone.NT_MODE_GSM_ONLY));
            }
        }
        if (slot >= 0 && slot < PrimarySubSelectionController.PHONE_COUNT) {
            commands.add(new PrefNetworkSetCommand(slot, networkMode));
        }
    }

    protected void start() {
        if (commands.isEmpty()) {
            logd("no command sent");
            response(mCallback);
            end();
        } else {
            PrefNetworkSetCommand command = commands.get(commands.size() - 1);
            logd("try to set network=" + command.mPrefNetwork + " on slot" + command.mSlot);
            mHandler.obtainMessage(EVENT_START_REQUEST, 0).sendToTarget();
        }
    }

    private void savePrefNetworkInSetting(int slot, int networkMode) {
        TelephonyManager.putIntAtIndex(mContext.getContentResolver(),
                android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                slot,
                networkMode);
    }

    private void response(Message callback) {
        if (callback == null) {
            return;
        }
        if (callback.getTarget() != null) {
            callback.sendToTarget();
        } else {
            Log.w(TAG, "can't response the result, replyTo and target are all null!");
        }
    }
}
