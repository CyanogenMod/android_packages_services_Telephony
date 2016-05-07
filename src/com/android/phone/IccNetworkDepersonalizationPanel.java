/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.content.Context;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.text.Editable;
import android.text.Spannable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.DialerKeyListener;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.uicc.IccCardApplicationStatus;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.PersoSubState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Vector;

/**
 * "SIM network unlock" PIN entry screen.
 *
 * @see PhoneGlobals.EVENT_SIM_NETWORK_LOCKED
 *
 * TODO: This UI should be part of the lock screen, not the
 * phone app (see bug 1804111).
 */
public class IccNetworkDepersonalizationPanel extends IccPanel {

    /**
     * Tracks whether there is an instance of the network depersonalization dialog showing or not.
     * Ensures only a single instance of the dialog is visible.
     */
    private static boolean sShowingDialog = false;

    //debug constants
    private static final boolean DBG = false;

    //events
    private static final int EVENT_ICC_NTWRK_DEPERSONALIZATION_RESULT = 100;

    private Phone mPhone;
    private int mPersoSubtype;

    //UI elements
    private EditText     mPinEntry;
    private LinearLayout mEntryPanel;
    private LinearLayout mStatusPanel;
    private TextView     mStatusText;
    private TextView     mPersoSubtypeText;

    private Button       mUnlockButton;
    private Button       mDismissButton;

    private final int ENTRY = 0;
    private final int IN_PROGRESS = 1;
    private final int ERROR = 2;
    private final int SUCCESS = 3;

    //Initialize the Persosubtype labels.
    //{ENTRY,   IN_PROGRESS,
    //ERROR,        SUCCESS},
    private final int[][] mPersoSubtypeLabels = new int[][] {
        {0,0,0,0}, // PERSOSUBSTATE_UNKNOWN,
        {0,0,0,0}, //PERSOSUBSTATE_IN_PROGRESS,
        {0,0,0,0}, //PERSOSUBSTATE_READY,

        //PERSOSUBSTATE_SIM_NETWORK,
        {R.string.label_ndp,                R.string.requesting_unlock_cm,
        R.string.unlock_failed_cm,          R.string.unlock_success_cm},

        //PERSOSUBSTATE_SIM_NETWORK_SUBSET,
        {R.string.label_nsdp,               R.string.requesting_nw_subset_unlock,
        R.string.nw_subset_unlock_failed,   R.string.nw_subset_unlock_success},

        //PERSOSUBSTATE_SIM_CORPORATE,
        {R.string.label_cdp,                R.string.requesting_corporate_unlock,
        R.string.corporate_unlock_failed,   R.string.corporate_unlock_success},

        //PERSOSUBSTATE_SIM_SERVICE_PROVIDER,
        {R.string.label_spdp,               R.string.requesting_sp_unlock,
        R.string.sp_unlock_failed,          R.string.sp_unlock_success},

        //PERSOSUBSTATE_SIM_SIM,
        {R.string.label_sdp,                R.string.requesting_sim_unlock,
        R.string.sim_unlock_failed,         R.string.sim_unlock_success},

        //PERSOSUBSTATE_SIM_NETWORK_PUK,
        {R.string.label_puk,                R.string.requesting_puk_unlock,
        R.string.puk_unlock_failed,         R.string.puk_unlock_success},

        //PERSOSUBSTATE_SIM_NETWORK_SUBSET_PUK,
        {R.string.label_puk,                R.string.requesting_puk_unlock,
        R.string.puk_unlock_failed,         R.string.puk_unlock_success},

        //PERSOSUBSTATE_SIM_CORPORATE_PUK,
        {R.string.label_puk,                R.string.requesting_puk_unlock,
        R.string.puk_unlock_failed,         R.string.puk_unlock_success},

        //PERSOSUBSTATE_SIM_SERVICE_PROVIDER_PUK,
        {R.string.label_puk,                R.string.requesting_puk_unlock,
        R.string.puk_unlock_failed,         R.string.puk_unlock_success},

        //PERSOSUBSTATE_SIM_SIM_PUK,
        {R.string.label_puk,                R.string.requesting_puk_unlock,
        R.string.puk_unlock_failed,         R.string.puk_unlock_success},

        //PERSOSUBSTATE_RUIM_NETWORK1,
        {R.string.label_rn1dp,              R.string.requesting_rnw1_unlock,
        R.string.rnw1_unlock_failed,        R.string.rnw1_unlock_success},

        //PERSOSUBSTATE_RUIM_NETWORK2,
        {R.string.label_rn2dp,              R.string.requesting_rnw2_unlock,
        R.string.rnw2_unlock_failed,        R.string.rnw2_unlock_success},

        //PERSOSUBSTATE_RUIM_HRPD,
        {R.string.label_rhrpd,              R.string.requesting_rhrpd_unlock,
        R.string.rhrpd_unlock_failed,       R.string.rhrpd_unlock_success},

        //PERSOSUBSTATE_RUIM_CORPORATE,
        {R.string.label_rcdp,               R.string.requesting_rc_unlock,
        R.string.rc_unlock_failed,          R.string.rc_unlock_success},

        //PERSOSUBSTATE_RUIM_SERVICE_PROVIDER,
        {R.string.label_rspdp,              R.string.requesting_rsp_unlock,
        R.string.rsp_unlock_failed,         R.string.rsp_unlock_success},

        //PERSOSUBSTATE_RUIM_RUIM,
        {R.string.label_rdp,                R.string.requesting_ruim_unlock,
        R.string.ruim_unlock_failed,        R.string.ruim_unlock_success},

        //PERSOSUBSTATE_RUIM_NETWORK1_PUK,
        {R.string.label_puk,                R.string.requesting_puk_unlock,
        R.string.puk_unlock_failed,         R.string.puk_unlock_success},

        //PERSOSUBSTATE_RUIM_NETWORK2_PUK,
        {R.string.label_puk,                R.string.requesting_puk_unlock,
        R.string.puk_unlock_failed,         R.string.puk_unlock_success},

        //PERSOSUBSTATE_RUIM_HRPD_PUK,
        {R.string.label_puk,                R.string.requesting_puk_unlock,
        R.string.puk_unlock_failed,         R.string.puk_unlock_success},

        //PERSOSUBSTATE_RUIM_CORPORATE_PUK,
        {R.string.label_puk,                R.string.requesting_puk_unlock,
        R.string.puk_unlock_failed,         R.string.puk_unlock_success},

        //PERSOSUBSTATE_RUIM_SERVICE_PROVIDER_PUK,
        {R.string.label_puk,                R.string.requesting_puk_unlock,
        R.string.puk_unlock_failed,         R.string.puk_unlock_success},

        //PERSOSUBSTATE_RUIM_RUIM_PUK,
        {R.string.label_puk,                R.string.requesting_puk_unlock,
        R.string.puk_unlock_failed,         R.string.puk_unlock_success},
    };

    /**
     * Shows the network depersonalization dialog, but only if it is not already visible.
     */
    public static void showDialog() {
        if (sShowingDialog) {
            Log.i(TAG, "[IccNetworkDepersonalizationPanel] - showDialog; skipped already shown.");
            return;
        }
        Log.i(TAG, "[IccNetworkDepersonalizationPanel] - showDialog; showing dialog.");
        sShowingDialog = true;
        IccNetworkDepersonalizationPanel ndpPanel =
                new IccNetworkDepersonalizationPanel(PhoneGlobals.getInstance());
        ndpPanel.show();
    }

    //private textwatcher to control text entry.
    private TextWatcher mPinEntryWatcher = new TextWatcher() {
        public void beforeTextChanged(CharSequence buffer, int start, int olen, int nlen) {
        }

        public void onTextChanged(CharSequence buffer, int start, int olen, int nlen) {
        }

        public void afterTextChanged(Editable buffer) {
            if (SpecialCharSequenceMgr.handleChars(
                    getContext(), buffer.toString())) {
                mPinEntry.getText().clear();
            }
        }
    };

    //handler for unlock function results
    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what == EVENT_ICC_NTWRK_DEPERSONALIZATION_RESULT) {
                AsyncResult res = (AsyncResult) msg.obj;
                if (res.exception != null) {
                    if (DBG) log("network depersonalization request failure.");
                    displayStatus(ERROR);
                    int retries = parseResult(msg);
                    if( retries >= 0) { displayRetryStatus(retries); }
                    postDelayed(new Runnable() {
                                    public void run() {
                                        hideAlert();
                                        mPinEntry.getText().clear();
                                        mPinEntry.requestFocus();
                                    }
                                }, 3000);
                } else {
                    if (DBG) log("network depersonalization success.");
                    displayStatus(SUCCESS);
                    postDelayed(new Runnable() {
                                    public void run() {
                                        dismiss();
                                    }
                                }, 3000);
                }
            }
        }
    };

    //constructor
    public IccNetworkDepersonalizationPanel(Context context) {
        super(context);
        mPersoSubtype = IccCardApplicationStatus.PersoSubState.PERSOSUBSTATE_SIM_NETWORK.ordinal();
    }

    //constructor
    public IccNetworkDepersonalizationPanel(Context context, int subtype) {
        super(context);
        mPersoSubtype = subtype;
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.sim_ndp);

        // PIN entry text field
        mPinEntry = (EditText) findViewById(R.id.pin_entry);
        mPinEntry.setKeyListener(DialerKeyListener.getInstance());
        mPinEntry.setOnClickListener(mUnlockListener);

        // Attach the textwatcher
        CharSequence text = mPinEntry.getText();
        Spannable span = (Spannable) text;
        span.setSpan(mPinEntryWatcher, 0, text.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);

        mEntryPanel = (LinearLayout) findViewById(R.id.entry_panel);
        mPersoSubtypeText = (TextView) findViewById(R.id.perso_subtype_text);
        displayStatus(ENTRY);

        mUnlockButton = (Button) findViewById(R.id.ndp_unlock);
        mUnlockButton.setOnClickListener(mUnlockListener);

        // The "Dismiss" button is present in some (but not all) products,
        // based on the "KEY_SIM_NETWORK_UNLOCK_ALLOW_DISMISS_BOOL" variable.
        mDismissButton = (Button) findViewById(R.id.ndp_dismiss);
        PersistableBundle carrierConfig = PhoneGlobals.getInstance().getCarrierConfig();
        if (carrierConfig.getBoolean(
                CarrierConfigManager.KEY_SIM_NETWORK_UNLOCK_ALLOW_DISMISS_BOOL)) {
            if (DBG) log("Enabling 'Dismiss' button...");
            mDismissButton.setVisibility(View.VISIBLE);
            mDismissButton.setOnClickListener(mDismissListener);
        } else {
            if (DBG) log("Removing 'Dismiss' button...");
            mDismissButton.setVisibility(View.GONE);
        }

        //status panel is used since we're having problems with the alert dialog.
        mStatusPanel = (LinearLayout) findViewById(R.id.status_panel);
        mStatusText = (TextView) findViewById(R.id.status_text);

        mPhone = PhoneGlobals.getPhone();
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
        Log.i(TAG, "[IccNetworkDepersonalizationPanel] - showDialog; hiding dialog.");
        sShowingDialog = false;
    }

    //Mirrors IccPinUnlockPanel.onKeyDown().
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    View.OnClickListener mUnlockListener = new View.OnClickListener() {
        public void onClick(View v) {
            String pin = mPinEntry.getText().toString();

            if (TextUtils.isEmpty(pin)) {
                return;
            }

            log("Requesting De-Personalization for subtype " + mPersoSubtype);
            mPhone.getIccCard().supplyNetworkDepersonalization(pin, Integer.toString(mPersoSubtype),
                    Message.obtain(mHandler, EVENT_ICC_NTWRK_DEPERSONALIZATION_RESULT));
            displayStatus(IN_PROGRESS);
        }
    };

    private void displayStatus(int type) {
        int label = 0;

        label = mPersoSubtypeLabels[mPersoSubtype][type];

        if (label == 0) {
            log ("Unsupported Perso Subtype :" + mPersoSubtype);
            return;
        }
        if (type == ENTRY) {
            String displayText = getContext().getString(label);
            mPersoSubtypeText.setText(displayText);
        } else {
            mStatusText.setText(label);
            mEntryPanel.setVisibility(View.GONE);
            mStatusPanel.setVisibility(View.VISIBLE);
        }
    }

    private void hideAlert() {
        mEntryPanel.setVisibility(View.VISIBLE);
        mStatusPanel.setVisibility(View.GONE);
    }

    View.OnClickListener mDismissListener = new View.OnClickListener() {
        public void onClick(View v) {
            if (DBG) log("mDismissListener: skipping depersonalization...");
            dismiss();
        }
    };

    private void log(String msg) {
        Log.d(TAG, "[IccNetworkDepersonalizationPanel] " + msg);
    }

    private int parseResult(Message msg) {
        AsyncResult res = (AsyncResult) msg.obj;
        if (res.result == null) { return -1; }

        byte [] resp = {0,0,0};
        resp = (byte [])res.result;
        if (resp.length <= 0) { return -1; }

        Vector<Integer> intV = new Vector<Integer>();
        intV.clear();
        int vectorSize = resp.length / 4; //uint32
        if (DBG) log("number of elements in resp is " + vectorSize);

        for (int i=0; i<vectorSize; i++) {
            int parsedInt = 0;
            for (int j=0; j<4; j++) {
                parsedInt += (resp[i*4+j] & 0xFF) << (8*j);
            }
            intV.add(Integer.valueOf(parsedInt));
            if (DBG) log("intV["+i+"]:" + intV.get(i));
        }

        return intV.get(0);
    }

    private void displayRetryStatus(int retries) {
        String msg = getContext().getString(
                     R.string.unlock_failed_remaining_retries, retries);
        mStatusText.setText(msg);
        mEntryPanel.setVisibility(View.GONE);
        mStatusPanel.setVisibility(View.VISIBLE);
    }
}
