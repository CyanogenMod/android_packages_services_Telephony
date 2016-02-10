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

    Phone mPhone;
    private String[] mNetworkChoices, mNetworkValues;
    private int mInitialMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();

        if (savedInstanceState != null) {
            mClickedPos = savedInstanceState.getInt(SAVE_CLICKED_POS, -1);
        }

        mPhone = PhoneFactory.getDefaultPhone();
        if (mPhone == null) {
            finish();
        }

        // Get whether to show the 'None' item
        mHasNoneItem = intent.getBooleanExtra(EXTRA_SHOW_NONE, false);

        mInitialMode = intent.getIntExtra(EXTRA_SELECTED_MODE,
                mHasNoneItem ? -1 : getCurrentNetworkMode());

        Log.d(TAG, "mHasNoneItem: " + mHasNoneItem + ", mInitialMode: " + mInitialMode);

        int[] ev = MobileNetworkSettings.getDeviceNetworkEntriesAndValues(this, mPhone.getSubId(),
                mInitialMode);

        mNetworkChoices = getResources().getStringArray(ev[0]);
        mNetworkValues = getResources().getStringArray(ev[1]);

        if (mHasNoneItem) {
            ArrayList<String> choices = new ArrayList<String>();
            ArrayList<String> values = new ArrayList<String>();

            String noneItem = intent.getStringExtra(EXTRA_NEUTRAL_TEXT);
            if (noneItem == null) {
                noneItem = "System default";
            }

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

    private int getCurrentNetworkMode() {
       final int subId = SubscriptionManager.getDefaultDataSubId();

        int nwMode = SubscriptionManager.DEFAULT_NW_MODE;
        //If its default nw mode, choose the nw mode from the overlays.
        try {
            nwMode = android.provider.Settings.Global.getInt(
                    getContentResolver(), Settings.Global.PREFERRED_NETWORK_MODE + subId);
        } catch (Settings.SettingNotFoundException e) {
            e.printStackTrace();
        }
        return nwMode;
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
        boolean positiveResult = which != DialogInterface.BUTTON_NEGATIVE;

        if (positiveResult) {
            Intent resultIntent = new Intent();

            resultIntent.putExtra(EXTRA_NETWORK_MODE_PICKED, mNetworkValues[mClickedPos]);

            setResult(RESULT_OK, resultIntent);
        } else {
            setResult(RESULT_CANCELED);
        }

        finish();
    }
}
