/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.net.Uri;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.view.WindowManager;

import android.util.Log;
import com.android.internal.telephony.Phone;

/**
 * Used to display an error dialog from within the Telephony service when an outgoing call fails
 */
public class ErrorDialogActivity extends Activity {
    private static final String TAG = ErrorDialogActivity.class.getSimpleName();

    public static final String SHOW_MISSING_VOICEMAIL_NO_DIALOG_EXTRA = "show_missing_voicemail";
    public static final String ERROR_MESSAGE_ID_EXTRA = "error_message_id";
    public static final String CALL_NUMBER_EXTRA = "call_number";

    // prompt airplane mode is being turn off
    private ProgressDialog mPromptProDlg;
    // For SIM turn off dialog
    boolean okClicked = false;
    private Phone mPhone;
    private static final int SERVICE_STATE_CHANGED = 1;
    private String mNumber = "";

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SERVICE_STATE_CHANGED:
                    onServiceStateChanged(msg);
                    break;
                default:
                    Log.wtf(TAG, "handleMessage: unexpected message: " + msg);
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mPhone = PhoneGlobals.getInstance().mCM.getDefaultPhone();

        mNumber = getIntent().getStringExtra(CALL_NUMBER_EXTRA);
        final boolean showVoicemailDialog = getIntent().getBooleanExtra(
                SHOW_MISSING_VOICEMAIL_NO_DIALOG_EXTRA, false);

        if (showVoicemailDialog) {
            showMissingVoicemailErrorDialog();
        } else {
            final int error = getIntent().getIntExtra(ERROR_MESSAGE_ID_EXTRA, -1);
            if (error == -1) {
                Log.e(TAG, "ErrorDialogActivity called with no error type extra.");
                finish();
            }
            if(error == R.string.incall_error_power_off){
                showSIMTurnOff();
            }else{
                showGenericErrorDialog(error);
            }

        }
    }

    private void onServiceStateChanged(Message msg) {
        ServiceState state = (ServiceState) ((AsyncResult) msg.obj).result;
        Log.d(TAG, "onServiceStateChanged()...  new state = " + state);
        boolean okToCall = state.getState() == ServiceState.STATE_IN_SERVICE;

        if (okToCall) {
            if (mPromptProDlg != null && mPromptProDlg.isShowing()) {
                // close progressDialog
                mPromptProDlg.dismiss();
                mPromptProDlg = null;
            }
            // It's OK to actually place the call.
            Log.d(TAG, "onServiceStateChanged: ok to call!");
            Intent activityIntent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:"
                    + mNumber));
            startActivity(activityIntent);

            // Deregister for the service state change events.
            unregisterForServiceStateChanged();
            finish();
        }
    }

    private void registerForServiceStateChanged() {
        // Unregister first, just to make sure we never register ourselves
        // twice.
        mPhone.unregisterForServiceStateChanged(mHandler);
        mPhone.registerForServiceStateChanged(mHandler, SERVICE_STATE_CHANGED, null);
    }

    private void unregisterForServiceStateChanged() {
        if (mPhone != null) {
            mPhone.unregisterForServiceStateChanged(mHandler);
        }
        if (mHandler != null) {
            mHandler.removeMessages(SERVICE_STATE_CHANGED);
        }
    }

    protected void showSIMTurnOff() {
        okClicked = false;
        AlertDialog alertDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.title_sim_prompt_dlg).setMessage(R.string.msg_sim_prompt_dlg)
                .setPositiveButton(R.string.ok, new OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // TODO Auto-generated method stub
                        okClicked = true;

                    }
                }).setNegativeButton(R.string.cancel, new OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // TODO Auto-generated method stub
                        okClicked = false;
                    }
                }).create();
        alertDialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            public void onDismiss(android.content.DialogInterface dialog) {
                if (okClicked) {
                    // turn off airplane mode.
                    Settings.Global.putInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON,
                            0);
                    Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
                    intent.putExtra("state", false);
                    sendBroadcast(intent);

                    registerForServiceStateChanged();

                    // prompt airplane mode is being turned off.
                    mPromptProDlg = new ProgressDialog(ErrorDialogActivity.this);
                    mPromptProDlg.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                    mPromptProDlg.setMessage(getText(R.string.msg_wait_prodlg));
                    mPromptProDlg.setIndeterminate(true);
                    mPromptProDlg.setCancelable(false);
                    mPromptProDlg.getWindow()
                            .setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
                    mPromptProDlg.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                    mPromptProDlg.show();
                } else {
                    ErrorDialogActivity.this.finish();
                }
            }
        });
        alertDialog.show();
    }

    private void showGenericErrorDialog(int resid) {
        final CharSequence msg = getResources().getText(resid);

        final DialogInterface.OnClickListener clickListener;

        final DialogInterface.OnCancelListener cancelListener;
        clickListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                finish();
            }
        };
        cancelListener = new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                finish();
            }
        };

        final AlertDialog errorDialog = new AlertDialog.Builder(this)
                .setMessage(msg).setPositiveButton(R.string.ok, clickListener)
                        .setOnCancelListener(cancelListener).create();

        errorDialog.show();
    }

    private void showMissingVoicemailErrorDialog() {
        final AlertDialog missingVoicemailDialog = new AlertDialog.Builder(this)
        .setTitle(R.string.no_vm_number)
        .setMessage(R.string.no_vm_number_msg)
        .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dontAddVoiceMailNumber();
                }})
        .setNegativeButton(R.string.add_vm_number_str, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    addVoiceMailNumberPanel(dialog);
                }})
        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    dontAddVoiceMailNumber();
                }}).show();
    }


    private void addVoiceMailNumberPanel(DialogInterface dialog) {
        if (dialog != null) {
            dialog.dismiss();
        }

        // navigate to the Voicemail setting in the Call Settings activity.
        Intent intent = new Intent(CallFeaturesSetting.ACTION_ADD_VOICEMAIL);
        intent.setClass(this, CallFeaturesSetting.class);
        startActivity(intent);
        finish();
    }

    private void dontAddVoiceMailNumber() {
        finish();
    }

    @Override
    protected void onDestroy() {
        // TODO Auto-generated method stub
        super.onDestroy();
        //Maybe sometimes system kill this activity, lead to IllegalArgumentException
        //so ensure to dissmiss the dialog in this funtion
        if (mPromptProDlg != null && mPromptProDlg.isShowing()) {
            // close progressDialog
            mPromptProDlg.dismiss();
            mPromptProDlg = null;
        }
    }
}
