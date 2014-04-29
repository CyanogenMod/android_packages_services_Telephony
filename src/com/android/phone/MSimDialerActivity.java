/*
 * Copyright (c) 2012-2013, The Linux Foundation. All rights reserved.
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
 *     * Neither the name of The Linux Foundation, Inc. nor the names of its
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

package com.android.phone;

import android.content.Context;
import android.content.Intent;
import android.content.DialogInterface;
import android.os.Bundle;
import android.telephony.PhoneNumberUtils;
import android.telephony.MSimTelephonyManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;
import com.android.internal.telephony.MSimConstants;
import com.android.internal.telephony.Phone;
import com.codeaurora.telephony.msim.MSimPhoneFactory;
import com.codeaurora.telephony.msim.SubscriptionManager;

import java.util.ArrayList;

/**
 * While initiating MO call this class is used to provide
 * a prompt option to user to choose the sub on  which user
 * want to make outgoing call.
 */
public class MSimDialerActivity extends AlertActivity implements
        DialogInterface.OnClickListener {
    private static final String TAG = "MSimDialerActivity";
    private static final boolean DBG = true;

    private MSimTelephonyManager mTelephonyManager;
    private MSimTargetAdapter mAdapter;

    public static final String PHONE_SUBSCRIPTION = "Subscription";
    public static final int INVALID_SUB = 99;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mTelephonyManager = MSimTelephonyManager.getDefault();

        final Intent intent = getIntent();
        if (DBG) Log.v(TAG, "Intent = " + intent);

        String number = PhoneNumberUtils.getNumberFromIntent(intent, this);
        if (number != null) {
            number = PhoneNumberUtils.convertKeypadLettersToDigits(number);
            number = PhoneNumberUtils.stripSeparators(number);
        }
        if (DBG) Log.v(TAG, "Number = " + number);

        Phone activePhone = getActivePhone();
        MSimTelephonyManager.MultiSimVariants config =
                mTelephonyManager.getMultiSimConfiguration();

        if (activePhone != null && config != MSimTelephonyManager.MultiSimVariants.DSDA) {
            if (DBG) Log.v(TAG, "SUB[" + activePhone.getSubscription() + "] is in call");
            // use the sub which is already in call
            startOutgoingCall(activePhone.getSubscription());
        } else if (PhoneNumberUtils.isEmergencyNumber(number)) {
            Log.d(TAG,"emergency call");
            startOutgoingCall(getSubscriptionForEmergencyCall());
        } else {
            mAdapter = new MSimTargetAdapter(this);
            if (mAdapter.getCount() == 0) {
                cancel();
            } else if (mAdapter.getCount() == 1) {
                MSimTargetAdapter.Item item = (MSimTargetAdapter.Item) mAdapter.getItem(0);
                startOutgoingCall(item.subscription);
            } else {
                if (DBG) Log.v(TAG, "Showing selector");

                String titleNumber = number;
                if (intent.getData() != null && "voicemail".equals(intent.getData().getScheme())) {
                    titleNumber = getString(R.string.voicemail);
                }

                // Create dialog parameters
                AlertController.AlertParams params = mAlertParams;
                params.mTitle = getString(R.string.msim_call_selector_title, titleNumber);
                params.mAdapter = mAdapter;
                params.mNegativeButtonText = getString(android.R.string.cancel);
                params.mNegativeButtonListener = this;

                setupAlert();

                final int defaultSub = mAdapter.getDefaultSubscriptionIndex();
                if (defaultSub >= 0) {
                    ListView list = mAlert.getListView();
                    list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
                    list.setItemChecked(defaultSub, true);
                }
            }
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_NEGATIVE) {
            cancel();
        } else {
            MSimTargetAdapter.Item item = (MSimTargetAdapter.Item) mAdapter.getItem(which);
            startOutgoingCall(item.subscription);
        }
    }

    @Override
    public void cancel() {
        startOutgoingCall(INVALID_SUB);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            cancel();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_CALL) {
            startOutgoingCall(MSimPhoneFactory.getVoiceSubscription());
            return true;
        }
        return false;
    }

    private Phone getActivePhone() {
        for (int i = 0; i < mTelephonyManager.getPhoneCount(); i++) {
             Phone phone = MSimPhoneFactory.getPhone(i);
             if (phone.getForegroundCall().getState().isAlive()) {
                 return phone;
             }
             if (phone.getBackgroundCall().getState().isAlive()) {
                 return phone;
             }
             if (phone.getRingingCall().getState().isAlive()) {
                 return phone;
             }
        }
        return null;
    }

    private int getSubscriptionForEmergencyCall() {
        if (DBG) Log.d(TAG,"emergency call, getVoiceSubscriptionInService");
        return PhoneGlobals.getInstance().getVoiceSubscriptionInService();
    }

    private static class MSimTargetAdapter extends BaseAdapter {
        private final LayoutInflater mInflater;
        private final ArrayList<Item> mItems;

        private static final class Item {
            int subscription;
            boolean isDefault;
            String label;
        }

        public MSimTargetAdapter(Context context) {
            MSimTelephonyManager tm = MSimTelephonyManager.getDefault();
            SubscriptionManager sm = SubscriptionManager.getInstance();
            int defaultSub = MSimPhoneFactory.getVoiceSubscription();

            mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mItems = new ArrayList<Item>();

            for (int i = 0; i < tm.getPhoneCount(); i++) {
                if (!sm.isSubActive(i) || tm.getSimState(i) == TelephonyManager.SIM_STATE_ABSENT) {
                    continue;
                }

                Item item = new Item();
                item.subscription = MSimPhoneFactory.getPhone(i).getSubscription();
                item.isDefault = item.subscription == defaultSub;
                item.label = context.getString(R.string.msim_call_selector_item,
                        i + 1, tm.getNetworkOperatorName(i));
                mItems.add(item);
            }
        }

        public int getDefaultSubscriptionIndex() {
            for (int i = 0; i < mItems.size(); i++) {
                if (mItems.get(i).isDefault) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public Object getItem(int position) {
            return mItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.dialer_msim_item, parent, false);
            }

            TextView textView = (TextView) convertView;
            textView.setText(mItems.get(position).label);

            return convertView;
        }
    }

    private void startOutgoingCall(int subscription) {
        final Intent intent = getIntent();
        intent.putExtra(MSimConstants.SUBSCRIPTION_KEY, subscription);
        intent.setClass(MSimDialerActivity.this, OutgoingCallBroadcaster.class);

        if (DBG) Log.v(TAG, "startOutgoingCall for sub " + subscription
                + " from intent: "+ intent);
         if (subscription < mTelephonyManager.getPhoneCount()) {
             setResult(RESULT_OK, intent);
         } else {
             Log.d(TAG, "call cancelled");
             setResult(RESULT_CANCELED, intent);
         }
         finish();
    }
}
