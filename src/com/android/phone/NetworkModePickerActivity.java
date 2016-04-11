/*
 * Copyright (C) 2007 The Android Open Source Project
 * Copyright (C) 2013 The CyanogenMod Project
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

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.SubscriptionController;

import java.util.ArrayList;
import java.util.Arrays;


/**
 * The {@link NetworkModePickerActivity} allows the user to choose a network mode
 * and receive the result back.
 *
 * @hide
 */
public final class NetworkModePickerActivity extends AlertActivity implements
        DialogInterface.OnClickListener, AlertController.AlertParams.OnPrepareListViewListener {

    private static final String TAG = "NetworkModePicker";

    private static final String SAVE_CLICKED_POS = "clicked_pos";

    public static final String EXTRA_TITLE = "network_mode_picker::title";
    public static final String EXTRA_NEUTRAL_TEXT = "network_mode_picker::neutral_text";
    public static final String EXTRA_SHOW_NONE = "network_mode_picker::show_none";
    public static final String EXTRA_SELECTED_MODE = "network_mode_picker::selected_mode";
    public static final String EXTRA_SUBID = "network_mode_picker::sub_id";

    public static final String EXTRA_NETWORK_MODE_PICKED = "network_mode_picker::chosen_value";


    /** The position in the list of the last clicked item. */
    private int mClickedPos = -1;

    /** Whether this list has the 'None' item. */
    private boolean mHasNoneItem;

    private OnClickListener mDialogChoiceClickListener =
            new OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            // Save the position of most recently clicked item
            mClickedPos = which;
        }

    };

    private String[] mNetworkChoices, mNetworkValues;
    private int mInitialMode;
    private int mSubId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();

        if (savedInstanceState != null) {
            mClickedPos = savedInstanceState.getInt(SAVE_CLICKED_POS, -1);
        }

        mSubId = intent.getIntExtra(EXTRA_SUBID, SubscriptionManager.INVALID_SUBSCRIPTION_ID);

        if (mSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            mSubId = SubscriptionManager.getDefaultDataSubId();
        }

        // Get whether to show the 'None' item
        mHasNoneItem = intent.getBooleanExtra(EXTRA_SHOW_NONE, false);

        mInitialMode = intent.getIntExtra(EXTRA_SELECTED_MODE,
                mHasNoneItem ? -1 : getCurrentNetworkMode(mSubId));

        Log.d(TAG, "mHasNoneItem: " + mHasNoneItem + ", mInitialMode: " + mInitialMode);
        Phone phone = PhoneFactory.getPhone(SubscriptionManager.getPhoneId(mSubId));
        if (phone == null) {
            phone = PhoneFactory.getDefaultPhone();
        }
        int[] ev = MobileNetworkSettings.getDeviceNetworkEntriesAndValues(this, phone,
                mInitialMode);

        mNetworkChoices = getResources().getStringArray(ev[0]);
        mNetworkValues = getResources().getStringArray(ev[1]);

        String noneItem = intent.getStringExtra(EXTRA_NEUTRAL_TEXT);
        if (noneItem == null) {
            Log.w(TAG, "caller requested a none item, but did not provide a none text!");
            mHasNoneItem = false;
        }

        if (mHasNoneItem) {
            ArrayList<String> choices = new ArrayList<String>();
            ArrayList<String> values = new ArrayList<String>();

            choices.add(noneItem);
            values.add(String.valueOf(-1));

            choices.addAll(Arrays.asList(mNetworkChoices));
            values.addAll(Arrays.asList(mNetworkValues));

            mNetworkChoices = new String[choices.size()];
            mNetworkValues = new String[values.size()];

            mNetworkChoices = choices.toArray(mNetworkChoices);
            mNetworkValues = values.toArray(mNetworkValues);
        }

        final AlertController.AlertParams p = mAlertParams;
        p.mAdapter = new ArrayAdapter<String>(this,
                com.android.internal.R.layout.select_dialog_singlechoice_material, mNetworkChoices);
        p.mOnClickListener = mDialogChoiceClickListener;
        p.mIsSingleChoice = true;
        p.mPositiveButtonText = getString(com.android.internal.R.string.ok);
        p.mPositiveButtonListener = this;
        p.mNegativeButtonText = getString(com.android.internal.R.string.cancel);
        p.mPositiveButtonListener = this;
        p.mOnPrepareListViewListener = this;

        p.mTitle = intent.getCharSequenceExtra(EXTRA_TITLE);
        if (p.mTitle == null) {
            p.mTitle = getString(R.string.choose_network_mode_title);
        }
        setupAlert();
    }

    private int getCurrentNetworkMode(int subId) {
        return SubscriptionController.getInstance().getUserNwMode(subId);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(SAVE_CLICKED_POS, mClickedPos);
    }

    @Override
    public void onPrepareListView(ListView listView) {
        mAlertParams.mCheckedItem = getIndexOfValue(String.valueOf(mInitialMode));
    }

    private int getIndexOfValue(String value) {
        for (int i = 0; i < mNetworkValues.length; i++) {
            String choice = mNetworkValues[i];
            if (choice.equals(value)) {
                return i;
            }
        }
        return -1;
    }

    /*
     * On click of Ok/Cancel buttons
     */
    public void onClick(DialogInterface dialog, int which) {
        boolean positiveResult = which == DialogInterface.BUTTON_POSITIVE;

        if (positiveResult) {
            Intent resultIntent = new Intent();

            if (mClickedPos == -1) {
                mClickedPos = getIndexOfValue(String.valueOf(mInitialMode));
            }
            resultIntent.putExtra(EXTRA_NETWORK_MODE_PICKED, mNetworkValues[mClickedPos]);
            resultIntent.putExtra(EXTRA_SUBID, mSubId);

            setResult(RESULT_OK, resultIntent);
        } else {
            setResult(RESULT_CANCELED);
        }

        finish();
    }
}
