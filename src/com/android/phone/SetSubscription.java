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

import java.lang.Integer;
import java.util.ArrayList;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.telephony.MSimTelephonyManager;

import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.codeaurora.telephony.msim.CardSubscriptionManager;
import com.codeaurora.telephony.msim.SubscriptionData;
import com.codeaurora.telephony.msim.Subscription;
import com.codeaurora.telephony.msim.SubscriptionManager;

import static android.telephony.TelephonyManager.SIM_STATE_ABSENT;

/**
 * Displays a dialer like interface to Set the Subscriptions.
 */
public class SetSubscription extends PreferenceActivity implements
       DialogInterface.OnDismissListener, DialogInterface.OnClickListener {

    private static final String TAG = "SetSubscription";
    public static final int SUBSCRIPTION_INDEX_INVALID = 99999;

    private PreferenceScreen mPreferenceScreen;
    private SwitchPreference subArray[];
    private ArrayList<SwitchPreference> mSwitches = new ArrayList<SwitchPreference>();
    private boolean subErr = false;
    private SubscriptionData[] mCardSubscrInfo;
    private SubscriptionData mCurrentSelSub;
    private SubscriptionData mUserSelSub;
    private SubscriptionManager mSubscriptionManager;
    private CardSubscriptionManager mCardSubscriptionManager;
    private AirplaneModeBroadcastReceiver mReceiver;
    //mIsForeground is added to track if activity is in foreground
    private boolean mIsForeground = false;
    private AlertDialog mAlertDialog;

    //String keys for preference lookup
    private static final String PREF_PARENT_KEY = "subscr_parent";

    private final int MAX_SUBSCRIPTIONS = SubscriptionManager.NUM_SUBSCRIPTIONS;

    private final int EVENT_SET_SUBSCRIPTION_DONE = 1;

    private final int EVENT_SIM_STATE_CHANGED = 2;

    public void onCreate(Bundle icicle) {
        boolean newCardNotify = getIntent().getBooleanExtra("NOTIFY_NEW_CARD_AVAILABLE", false);
        if (!newCardNotify) {
            setTheme(R.style.Theme_Settings);
        }
        super.onCreate(icicle);

        mSubscriptionManager = SubscriptionManager.getInstance();
        mCardSubscriptionManager = CardSubscriptionManager.getInstance();

        if (newCardNotify) {
            Log.d(TAG, "onCreate: Notify new cards are available!!!!");
            notifyNewCardAvailable();
        } else {
            // get the card subscription info from the Proxy Manager.
            mCardSubscrInfo = new SubscriptionData[MAX_SUBSCRIPTIONS];
            for (int i = 0; i < MAX_SUBSCRIPTIONS; i++) {
                mCardSubscrInfo[i] = mCardSubscriptionManager.getCardSubscriptions(i);
            }

            addPreferencesFromResource(R.xml.set_subscription_pref);
            mPreferenceScreen = getPreferenceScreen();

            // To store the selected subscriptions
            // index 0 for sub0 and index 1 for sub1
            subArray = new SwitchPreference[MAX_SUBSCRIPTIONS];

            if(mCardSubscrInfo != null) {
                populateList();

                mUserSelSub = new SubscriptionData(MAX_SUBSCRIPTIONS);

                updateSwitches();
            } else {
                Log.d(TAG, "onCreate: Card info not available: mCardSubscrInfo == NULL");
            }

            mCardSubscriptionManager.registerForSimStateChanged(mHandler,
                    EVENT_SIM_STATE_CHANGED, null);
            if (mSubscriptionManager.isSetSubscriptionInProgress()) {
                Log.d(TAG, "onCreate: SetSubscription is in progress when started this activity");
                getPreferenceScreen().setEnabled(false);

                mSubscriptionManager.registerForSetSubscriptionCompleted(
                        mHandler, EVENT_SET_SUBSCRIPTION_DONE, null);
            }
        }
        IntentFilter intentFilter =
                new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        mReceiver = new AirplaneModeBroadcastReceiver();
        registerReceiver(mReceiver, intentFilter);
    }

    /**
     * * Receiver for Airplane mode changed intent broadcasts.
     **/
    private class AirplaneModeBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                if (isAirplaneModeOn()) {
                    Log.d(TAG, "Airplane mode is: on ");
                    finish();
                }
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mIsForeground = true;
    }

    @Override
    public void onPause() {
        super.onPause();
        mIsForeground = false;
        if (mAlertDialog != null) {
            try {
                Log.d(TAG, "onPause disimissing dialog = " + mAlertDialog);
                mAlertDialog.dismiss();
            } catch (IllegalArgumentException e) {
                //This can happen if the dialog is not currently showing.
                Log.w(TAG, "Exception dismissing dialog. Ex=" + e);
            }
            mAlertDialog = null;
        }
    }

    protected void onDestroy () {
        super.onDestroy();
        mCardSubscriptionManager.unRegisterForSimStateChanged(mHandler);
        mSubscriptionManager.unRegisterForSetSubscriptionCompleted(mHandler);
        unregisterReceiver(mReceiver);
    }

    private boolean isAirplaneModeOn() {
        return Settings.System.getInt(getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0) != 0;
    }

    private void notifyNewCardAvailable() {
        Log.d(TAG, "notifyNewCardAvailable()");

        mAlertDialog = new AlertDialog.Builder(this)
            .setMessage(R.string.new_cards_available)
            .setTitle(R.string.config_sub_title)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        Intent msimSettings = new Intent("com.android.settings.MULTI_SIM_SETTINGS");
                        msimSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(msimSettings);
                    }
                })
            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    public void onDismiss(DialogInterface dialog) {
                        mAlertDialog = null;
                        finish();
                    }
                })
            .show();
    }

    private void updateSwitches() {
        for (int i = 0; i < mCardSubscrInfo.length; i++) {
            PreferenceCategory subGroup = (PreferenceCategory) mPreferenceScreen
                   .findPreference("sub_group_" + i);
            if (subGroup != null) {
                int count = subGroup.getPreferenceCount();
                for (int j = 0; j < count; j++) {
                    Preference pref = subGroup.getPreference(j);
                    if (pref instanceof SwitchPreference) {
                        SwitchPreference switchPreference =
                                (SwitchPreference) pref;
                        switchPreference.setChecked(false);
                        switchPreference.setTitle(getString(R.string.sub_not_active));
                    }
                }
            }
        }

        mCurrentSelSub = new SubscriptionData(MAX_SUBSCRIPTIONS);
        for (int i = 0; i < MAX_SUBSCRIPTIONS; i++) {
            Subscription sub = mSubscriptionManager.getCurrentSubscription(i);
                    mCurrentSelSub.subscription[i].copyFrom(sub);
        }

        if (mCurrentSelSub != null) {
            for (int i = 0; i < MAX_SUBSCRIPTIONS; i++) {
                Log.d(TAG, "updateSwitches: mCurrentSelSub.subscription[" + i + "] = "
                           + mCurrentSelSub.subscription[i]);
                subArray[i] = null;
                if (mCurrentSelSub.subscription[i].subStatus ==
                        Subscription.SubscriptionStatus.SUB_ACTIVATED) {
                    String key = "slot" + mCurrentSelSub.subscription[i].slotId
                                 + " index" + mCurrentSelSub.subscription[i].getAppIndex();

                    Log.d(TAG, "updateSwitches: key = " + key);

                    PreferenceCategory subGroup = (PreferenceCategory) mPreferenceScreen
                           .findPreference("sub_group_" + mCurrentSelSub.subscription[i].slotId);
                    if (subGroup != null) {
                        SwitchPreference switchPreference =
                               (SwitchPreference) subGroup.findPreference(key);
                        switchPreference.setChecked(true);
                        switchPreference.setTitle(getString(R.string.sub_active));
                        subArray[i] = switchPreference;
                    }
                }
            }
            mUserSelSub.copyFrom(mCurrentSelSub);
        }
    }

    /** add radio buttons to the group */
    private void populateList() {
        Log.d(TAG, "populateList:  mCardSubscrInfo.length = " + mCardSubscrInfo.length);
        MSimTelephonyManager tm = MSimTelephonyManager.getDefault();
        mSwitches.clear();
        int k = 0;
        // Create PreferenceCatergory sub groups for each card.
        for (SubscriptionData cardSub : mCardSubscrInfo) {
            if ((cardSub != null ) && (cardSub.getLength() > 0)) {
                int i = 0;

                String subGroupTitle = getString(R.string.multi_sim_entry_format_no_carrier, k + 1);
                String simName = Settings.Global.getSimNameForSubscription(this, k, null);
                if (TextUtils.isEmpty(simName)) {
                    // This should get generated at boot or on sim swap.
                    // But if not, do it now.
                    String operatorName = tm.getSimOperatorName(k);
                    if (tm.getSimState(i) == SIM_STATE_ABSENT || TextUtils.isEmpty(operatorName)) {
                        simName = getString(R.string.default_sim_name, k + 1);
                    } else {
                        simName = operatorName;
                        Settings.Global.setSimNameForSubscription(this, k, operatorName);
                    }
                }

                // Create a subgroup for the apps in each card
                PreferenceCategory subGroup = new PreferenceCategory(this);
                subGroup.setKey("sub_group_" + k);
                subGroup.setTitle(subGroupTitle);

                mPreferenceScreen.addPreference(subGroup);
                EditTextPreference editTextPreference = new EditTextPreference(this);
                editTextPreference.setPersistent(false);
                editTextPreference.setKey(String.valueOf(k));
                editTextPreference.setTitle(getString(R.string.sim_name));
                editTextPreference.setSummary(simName);
                editTextPreference.setDefaultValue(simName);
                editTextPreference.setOnPreferenceChangeListener(mEditTextListener);
                editTextPreference.setDialogTitle(R.string.sim_name);
                subGroup.addPreference(editTextPreference);

                // Add each element as a SwitchPreference to the group
                for (Subscription sub : cardSub.subscription) {
                    if (sub != null && sub.appType != null) {
                        Log.d(TAG, "populateList:  mCardSubscrInfo[" + k + "].subscription["
                                + i + "] = " + sub);
                        SwitchPreference switchPreference = new SwitchPreference(this);
                        switchPreference.setKey(new String("slot" + k + " index" + i));
                        switchPreference.setOnPreferenceChangeListener(mSwitchListener);
                        subGroup.addPreference(switchPreference);
                        mSwitches.add(switchPreference);
                    }
                    i++;
                }
            }
            k++;
        }
    }

    Preference.OnPreferenceChangeListener mSwitchListener =
            new Preference.OnPreferenceChangeListener() {
                public boolean onPreferenceChange(Preference preference,
                        Object newValue) {
                    SwitchPreference subPref = (SwitchPreference)preference;
                    String key = subPref.getKey();
                    boolean on = (Boolean)newValue;
                    int numSubSelected = 0;
                    for (int i = 0; i < subArray.length; i++) {
                        if (subArray[i] != null) {
                            SwitchPreference pref = subArray[i];
                            if (pref.isChecked() && !pref.equals(subPref)) {
                                numSubSelected++;
                            }
                        }
                    }
                    Log.d(TAG, "setSubscription: key = " + key);
                    if (numSubSelected == 0) {
                        // Show a message to prompt the user to select atleast one.
                        Toast toast = Toast.makeText(getApplicationContext(),
                                R.string.set_subscription_error_atleast_one,
                                Toast.LENGTH_SHORT);
                        toast.show();
                        subPref.setChecked(!on);
                        clearUpdatingPreferenceStatus();
                        return false;
                    }  else {
                        String splitKey[] = key.split(" ");
                        String sSlotId = splitKey[0].substring(splitKey[0].indexOf("slot") + 4);
                        int slotIndex = Integer.parseInt(sSlotId);

                        if (on) {
                            if (subArray[slotIndex] != null) {
                                subArray[slotIndex].setChecked(false);
                            }
                            subArray[slotIndex] = subPref;
                        } else {
                            subArray[slotIndex] = null;
                        }
                        setUpdatingPreferenceStatus(subPref, getResources()
                                .getString(R.string.set_uicc_subscription_progress));
                        setSubscription();
                        return true;
                    }
                }
            };

    Preference.OnPreferenceChangeListener mEditTextListener =
            new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    EditTextPreference editTextPreference = (EditTextPreference)preference;
                    String newVal = (String)newValue;
                    String oldVal = editTextPreference.getText();
                    if (!newVal.equals(oldVal)) {
                        Settings.Global.setSimNameForSubscription(getBaseContext(),
                                Integer.valueOf(preference.getKey()), newVal);
                        editTextPreference.setSummary(newVal);
                        return true;
                    }
                    return false;
                }
            };

    private void setSubscription() {
        Log.d(TAG, "setSubscription");

        int numSubSelected = 0;
        int deactRequiredCount = 0;
        subErr = false;

        for (int i = 0; i < subArray.length; i++) {
            if (subArray[i] != null) {
                numSubSelected++;
            }
        }

        Log.d(TAG, "setSubscription: numSubSelected = " + numSubSelected);

        if (numSubSelected == 0) {
            // Show a message to prompt the user to select atleast one.
            Toast toast = Toast.makeText(getApplicationContext(),
                    R.string.set_subscription_error_atleast_one,
                    Toast.LENGTH_SHORT);
            toast.show();
            updateSwitches();
        } else if (isPhoneInCall()) {
            // User is not allowed to activate or deactivate the subscriptions
            // while in a voice call.
            displayErrorDialog(R.string.set_sub_not_supported_phone_in_call);
        } else {
            for (int i = 0; i < MAX_SUBSCRIPTIONS; i++) {
                if (subArray[i] == null) {
                    if (mCurrentSelSub.subscription[i].subStatus ==
                            Subscription.SubscriptionStatus.SUB_ACTIVATED) {
                        Log.d(TAG, "setSubscription: Sub " + i + " not selected. Setting 99999");
                        mUserSelSub.subscription[i].slotId = SUBSCRIPTION_INDEX_INVALID;
                        mUserSelSub.subscription[i].m3gppIndex = SUBSCRIPTION_INDEX_INVALID;
                        mUserSelSub.subscription[i].m3gpp2Index = SUBSCRIPTION_INDEX_INVALID;
                        mUserSelSub.subscription[i].subId = i;
                        mUserSelSub.subscription[i].subStatus = Subscription.
                                SubscriptionStatus.SUB_DEACTIVATE;

                        deactRequiredCount++;
                    }
                } else {
                    // Key is the string :  "slot<SlotId> index<IndexId>"
                    // Split the string into two and get the SlotId and IndexId.
                    String key = subArray[i].getKey();
                    Log.d(TAG, "setSubscription: key = " + key);
                    String splitKey[] = key.split(" ");
                    String sSlotId = splitKey[0].substring(splitKey[0].indexOf("slot") + 4);
                    int slotId = Integer.parseInt(sSlotId);
                    String sIndexId = splitKey[1].substring(splitKey[1].indexOf("index") + 5);
                    int subIndex = Integer.parseInt(sIndexId);

                    if (mCardSubscrInfo[slotId] == null) {
                        Log.d(TAG, "setSubscription: mCardSubscrInfo is not in sync "
                                + "with SubscriptionManager");
                        mUserSelSub.subscription[i].slotId = SUBSCRIPTION_INDEX_INVALID;
                        mUserSelSub.subscription[i].m3gppIndex = SUBSCRIPTION_INDEX_INVALID;
                        mUserSelSub.subscription[i].m3gpp2Index = SUBSCRIPTION_INDEX_INVALID;
                        mUserSelSub.subscription[i].subId = i;
                        mUserSelSub.subscription[i].subStatus = Subscription.
                                SubscriptionStatus.SUB_DEACTIVATE;

                        if (mCurrentSelSub.subscription[i].subStatus ==
                                Subscription.SubscriptionStatus.SUB_ACTIVATED) {
                            deactRequiredCount++;
                        }
                        continue;
                    }


                    // Compate the user selected subscriptio with the current subscriptions.
                    // If they are not matching, mark it to activate.
                    mUserSelSub.subscription[i].copyFrom(mCardSubscrInfo[slotId].
                            subscription[subIndex]);
                    mUserSelSub.subscription[i].subId = i;
                    if (mCurrentSelSub != null) {
                        // subStatus used to store the activation status as the mCardSubscrInfo
                        // is not keeping track of the activation status.
                        Subscription.SubscriptionStatus subStatus =
                                mCurrentSelSub.subscription[i].subStatus;
                        mUserSelSub.subscription[i].subStatus = subStatus;
                        if ((subStatus != Subscription.SubscriptionStatus.SUB_ACTIVATED) ||
                            (!mUserSelSub.subscription[i].equals(mCurrentSelSub.subscription[i]))) {
                            // User selected a new subscription.  Need to activate this.
                            mUserSelSub.subscription[i].subStatus = Subscription.
                            SubscriptionStatus.SUB_ACTIVATE;
                        }

                        if (mCurrentSelSub.subscription[i].subStatus == Subscription.
                                 SubscriptionStatus.SUB_ACTIVATED
                                 && mUserSelSub.subscription[i].subStatus == Subscription.
                                 SubscriptionStatus.SUB_ACTIVATE) {
                            deactRequiredCount++;
                        }
                    } else {
                        mUserSelSub.subscription[i].subStatus = Subscription.
                                SubscriptionStatus.SUB_ACTIVATE;
                    }
                }
            }

            if (deactRequiredCount >= MAX_SUBSCRIPTIONS) {
                displayErrorDialog(R.string.deact_all_sub_not_supported);
            } else {
                boolean ret = mSubscriptionManager.setSubscription(mUserSelSub);
                if (ret) {
                    if (mIsForeground) {
                        getPreferenceScreen().setEnabled(false);
                    }
                    mSubscriptionManager.registerForSetSubscriptionCompleted(mHandler,
                            EVENT_SET_SUBSCRIPTION_DONE, null);
                } else {
                    //TODO: Already some set sub in progress. Display a Toast?
                }
            }
        }
    }

    private boolean isPhoneInCall() {
        boolean phoneInCall = false;
        for (int i = 0; i < MAX_SUBSCRIPTIONS; i++) {
            if (MSimTelephonyManager.getDefault().getCallState(i)
                    != TelephonyManager.CALL_STATE_IDLE) {
                phoneInCall = true;
                break;
            }
        }
        return phoneInCall;
    }

    /**
     *  Displays an dialog box with error message.
     *  "Deactivation of both subscription is not supported"
     */
    private void displayErrorDialog(int messageId) {
        Log.d(TAG, "errorMutipleDeactivate(): " + getResources().getString(messageId));

        mAlertDialog = new AlertDialog.Builder(this)
            .setTitle(R.string.config_sub_title)
            .setMessage(messageId)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        Log.d(TAG, "errorMutipleDeactivate:  onClick");
                        updateSwitches();
                    }
                })
            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    public void onDismiss(DialogInterface dialog) {
                        Log.d(TAG, "errorMutipleDeactivate:  onDismiss");
                        mAlertDialog = null;
                        updateSwitches();
                    }
                })
            .show();
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar;

            switch(msg.what) {
                case EVENT_SET_SUBSCRIPTION_DONE:
                    Log.d(TAG, "EVENT_SET_SUBSCRIPTION_DONE");
                    mSubscriptionManager.unRegisterForSetSubscriptionCompleted(mHandler);
                    clearUpdatingPreferenceStatus();
                    getPreferenceScreen().setEnabled(true);
                    ar = (AsyncResult) msg.obj;

                    String result[] = (String[]) ar.result;

                    if (result != null) {
                        displayAlertDialog(result);
                    } else {
                        finish();
                    }
                    break;
                case EVENT_SIM_STATE_CHANGED:
                    Log.d(TAG, "EVENT_SIM_STATE_CHANGED");

                    for (int i = 0; i < mCardSubscrInfo.length; i++) {
                        PreferenceCategory subGroup = (PreferenceCategory) mPreferenceScreen
                                 .findPreference("sub_group_" + i);
                        if (subGroup != null) {
                            subGroup.removeAll();
                        }
                    }
                    mPreferenceScreen.removeAll();
                    populateList();
                    updateSwitches();
                    clearUpdatingPreferenceStatus();
                    break;
            }
        }
    };

    private boolean isFailed(String status) {
        Log.d(TAG, "isFailed(" + status + ")");
        if (status == null ||
            (status != null &&
             (status.equals(SubscriptionManager.SUB_DEACTIVATE_FAILED)
              || status.equals(SubscriptionManager.SUB_DEACTIVATE_NOT_SUPPORTED)
              || status.equals(SubscriptionManager.SUB_ACTIVATE_FAILED)
              || status.equals(SubscriptionManager.SUB_ACTIVATE_NOT_SUPPORTED)))) {
            return true;
        }
        return false;
    }

    private void setUpdatingPreferenceStatus(Preference preference, String status) {
        if (preference != null) {
            preference.setSummary(status);
        }
    }

    private void clearUpdatingPreferenceStatus() {
        for (SwitchPreference preference: mSwitches) {
            preference.setSummary(null);
        }
    }

    String setSubscriptionStatusToString(String status) {
        String retStr = null;
        if (status.equals(SubscriptionManager.SUB_ACTIVATE_SUCCESS)) {
            retStr = getResources().getString(R.string.set_sub_activate_success);
        } else if (status.equals(SubscriptionManager.SUB_DEACTIVATE_SUCCESS)) {
            retStr = getResources().getString(R.string.set_sub_deactivate_success);
        } else if (status.equals(SubscriptionManager.SUB_DEACTIVATE_FAILED)) {
            retStr = getResources().getString(R.string.set_sub_deactivate_failed);
        } else if (status.equals(SubscriptionManager.SUB_DEACTIVATE_NOT_SUPPORTED)) {
            retStr = getResources().getString(R.string.set_sub_deactivate_not_supported);
        } else if (status.equals(SubscriptionManager.SUB_ACTIVATE_FAILED)) {
            retStr = getResources().getString(R.string.set_sub_activate_failed);
        } else if (status.equals(SubscriptionManager.SUB_GLOBAL_ACTIVATE_FAILED)) {
            retStr = getResources().getString(R.string.set_sub_global_activate_failed);
        } else if (status.equals(SubscriptionManager.SUB_GLOBAL_DEACTIVATE_FAILED)) {
            retStr = getResources().getString(R.string.set_sub_global_deactivate_failed);
        } else if (status.equals(SubscriptionManager.SUB_ACTIVATE_NOT_SUPPORTED)) {
            retStr = getResources().getString(R.string.set_sub_activate_not_supported);
        } else if (status.equals(SubscriptionManager.SUB_NOT_CHANGED)) {
            retStr = getResources().getString(R.string.set_sub_no_change);
        }
        return retStr;
    }

    void displayAlertDialog(String msg[]) {
        int resSubId[] = {R.string.set_sub_1, R.string.set_sub_2, R.string.set_sub_3};
        String dispMsg = "";
        int title = R.string.set_sub_failed;

        if (msg[0] != null && isFailed(msg[0])) {
            subErr = true;
        }
        if (msg[1] != null && isFailed(msg[1])) {
            subErr = true;
        }

        for (int i = 0; i < msg.length; i++) {
            if (msg[i] != null) {
                dispMsg = dispMsg + getResources().getString(resSubId[i]) + " " +
                                      setSubscriptionStatusToString(msg[i]) + "\n";
            }
        }

        if (!subErr) {
            title = R.string.set_sub_success;
        }

        Log.d(TAG, "displayAlertDialog:  dispMsg = " + dispMsg);
        mAlertDialog = new AlertDialog.Builder(this).setMessage(dispMsg)
            .setTitle(title)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(android.R.string.yes, this)
            .setOnDismissListener(this)
            .show();
    }

    // This is a method implemented for DialogInterface.OnDismissListener
    public void onDismiss(DialogInterface dialog) {
        clearUpdatingPreferenceStatus();
        mAlertDialog = null;
    }

    // This is a method implemented for DialogInterface.OnClickListener.
    // Used to dismiss the dialogs when they come up.
    public void onClick(DialogInterface dialog, int which) {
        try {
            dialog.dismiss();
        } catch (IllegalArgumentException e) {
            //This can happen if the dialog is not currently showing.
            Log.w(TAG, "Exception dismissing dialog. Ex=" + e);
        }
        updateSwitches();
    }

}
