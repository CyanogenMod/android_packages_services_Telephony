package com.android.phone;

import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.CommandsInterface;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ActionBar;
import android.content.Intent;
import android.content.Context;
import android.content.DialogInterface;
import android.database.Cursor;
import android.os.Bundle;
import android.os.SystemProperties;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.MenuItem;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import java.util.ArrayList;

import static com.android.internal.telephony.PhoneConstants.SUBSCRIPTION_KEY;
import static com.android.internal.telephony.PhoneConstants.SUB1;

public class GsmUmtsCallForwardOptions extends TimeConsumingPreferenceActivity
        implements DialogInterface.OnClickListener, DialogInterface.OnCancelListener {
    private static final String LOG_TAG = "GsmUmtsCallForwardOptions";
    private final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    private static final String NUM_PROJECTION[] = {Phone.NUMBER};

    private static final String BUTTON_CFU_KEY   = "button_cfu_key";
    private static final String BUTTON_CFB_KEY   = "button_cfb_key";
    private static final String BUTTON_CFNRY_KEY = "button_cfnry_key";
    private static final String BUTTON_CFNRC_KEY = "button_cfnrc_key";

    private static final String KEY_TOGGLE = "toggle";
    private static final String KEY_STATUS = "status";
    private static final String KEY_NUMBER = "number";
    private static final String KEY_STARTHOUR = "starthour";
    private static final String KEY_STARTMINUTE = "startminute";
    private static final String KEY_ENDHOUR = "endhour";
    private static final String KEY_ENDMINUTE = "endminute";

    private CallForwardEditPreference mButtonCFU;
    private CallForwardEditPreference mButtonCFB;
    private CallForwardEditPreference mButtonCFNRy;
    private CallForwardEditPreference mButtonCFNRc;

    private final ArrayList<CallForwardEditPreference> mPreferences =
            new ArrayList<CallForwardEditPreference> ();
    private int mInitIndex= 0;

    private boolean mFirstResume;
    private Bundle mIcicle;
    private int mPhoneId;

    static final int DIALOG_NO_INTERNET_ERROR = 0;
    static final int DIALOG_SIZE = 1;
    private Dialog[] mDialogs = new Dialog[DIALOG_SIZE];

    @Override
    protected void onCreate(Bundle icicle) {
        Log.d(LOG_TAG, "onCreate");
        super.onCreate(icicle);

        boolean isTestForUTInterface = SystemProperties.getBoolean(
                "persist.radio.cfu.timer", false);
        if (DBG){
            Log.d(LOG_TAG, "networktype = " + getActiveNetworkType());
            Log.d(LOG_TAG, "isTestForUTInterface = " + isTestForUTInterface);
        }

        if (getActiveNetworkType() != ConnectivityManager.TYPE_MOBILE
                && getResources().getBoolean(R.bool.check_nw_for_ut)) {
            if (isTestForUTInterface){
                if (DBG) Log.d(LOG_TAG, "testing, ignore mobile network status!");
                return;
            }
            if (DBG) Log.d(LOG_TAG, "pls open mobile network for UT settings!");
            Dialog dialog = new AlertDialog.Builder(this)
                        .setTitle("No Mobile Data Aviable")
                        .setMessage(R.string.cf_mobile_date)
                        .setIconAttribute(android.R.attr.alertDialogIcon)
                        .setPositiveButton(android.R.string.ok, this)
                        .setNegativeButton(android.R.string.cancel, this)
                        .setOnCancelListener(this)
                        .create();
            if (dialog != null) {
                mDialogs[DIALOG_NO_INTERNET_ERROR] = dialog;
            }
            dialog.show();
            return;
        }

        addPreferencesFromResource(R.xml.callforward_options);

        mPhoneId = PhoneUtils.getPhoneId(PhoneUtils.getSubIdFromIntent(getIntent()));
        Log.d(LOG_TAG, "Call Forwarding options, subscription =" + mPhoneId);

        PreferenceScreen prefSet = getPreferenceScreen();
        mButtonCFU   = (CallForwardEditPreference) prefSet.findPreference(BUTTON_CFU_KEY);
        mButtonCFB   = (CallForwardEditPreference) prefSet.findPreference(BUTTON_CFB_KEY);
        mButtonCFNRy = (CallForwardEditPreference) prefSet.findPreference(BUTTON_CFNRY_KEY);
        mButtonCFNRc = (CallForwardEditPreference) prefSet.findPreference(BUTTON_CFNRC_KEY);

        if ((mPhoneId == SUB1) && getResources().getBoolean(R.bool.join_call_forward)) {
            int phoneType = getPhoneTypeBySubscription(mPhoneId);
            if (TelephonyManager.PHONE_TYPE_GSM == phoneType && mButtonCFNRc != null) {
                prefSet.removePreference(mButtonCFNRc);
            }
        }

        mButtonCFU.setParentActivity(this, mButtonCFU.reason);
        mButtonCFB.setParentActivity(this, mButtonCFB.reason);
        mButtonCFNRy.setParentActivity(this, mButtonCFNRy.reason);
        mButtonCFNRc.setParentActivity(this, mButtonCFNRc.reason);

        mPreferences.add(mButtonCFU);
        mPreferences.add(mButtonCFB);
        mPreferences.add(mButtonCFNRy);
        mPreferences.add(mButtonCFNRc);

        // we wait to do the initialization until onResume so that the
        // TimeConsumingPreferenceActivity dialog can display as it
        // relies on onResume / onPause to maintain its foreground state.

        mFirstResume = true;
        mIcicle = icicle;

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    private int getPhoneTypeBySubscription(int subscription) {
        int phoneType = PhoneUtils.isMultiSimEnabled() ?
                TelephonyManager.getDefault().getCurrentPhoneType(subscription) :
                TelephonyManager.getDefault().getCurrentPhoneType();
        return phoneType;
    }
    public void onClick(DialogInterface dialog, int id) {
        if (id == DialogInterface.BUTTON_NEGATIVE) {
            // button negative is cancel
            finish();
            return;
        } else if (dialog == mDialogs[DIALOG_NO_INTERNET_ERROR]) {
            if (id == DialogInterface.BUTTON_POSITIVE) {
                // Redirect to data settings and drop call fowarding setting.
                Intent newIntent = new Intent("android.settings.SETTINGS");
                newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(newIntent);
            }
            finish();
            return;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mFirstResume) {
            if (mIcicle == null) {
                if (DBG) Log.d(LOG_TAG, "start to init ");
                mPreferences.get(mInitIndex).init(this, false, mPhoneId);
            } else {
                mInitIndex = mPreferences.size();

                for (CallForwardEditPreference pref : mPreferences) {
                    Bundle bundle = mIcicle.getParcelable(pref.getKey());
                    pref.setToggled(bundle.getBoolean(KEY_TOGGLE));
                    CallForwardInfo cf = new CallForwardInfo();
                    cf.number = bundle.getString(KEY_NUMBER);
                    cf.status = bundle.getInt(KEY_STATUS);
                    pref.handleCallForwardResult(cf);
                    pref.init(this, true, mPhoneId);
                }
            }
            mFirstResume = false;
            mIcicle=null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        for (CallForwardEditPreference pref : mPreferences) {
            Bundle bundle = new Bundle();
            bundle.putBoolean(KEY_TOGGLE, pref.isToggled());
            if (pref.callForwardInfo != null) {
                bundle.putString(KEY_NUMBER, pref.callForwardInfo.number);
                bundle.putInt(KEY_STATUS, pref.callForwardInfo.status);
            }
            outState.putParcelable(pref.getKey(), bundle);
        }
    }

    @Override
    public void onFinished(Preference preference, boolean reading) {
        if (mInitIndex < mPreferences.size()-1 && !isFinishing()) {
            mInitIndex++;
            mPreferences.get(mInitIndex).init(this, false, mPhoneId);
        }

        super.onFinished(preference, reading);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (DBG) Log.d(LOG_TAG, "onActivityResult: done");
        if (resultCode != RESULT_OK) {
            if (DBG) Log.d(LOG_TAG, "onActivityResult: contact picker result not OK.");
            return;
        }
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(data.getData(),
                NUM_PROJECTION, null, null, null);
            if ((cursor == null) || (!cursor.moveToFirst())) {
                if (DBG) Log.d(LOG_TAG, "onActivityResult: bad contact data, no results found.");
                return;
            }

            switch (requestCode) {
                case CommandsInterface.CF_REASON_UNCONDITIONAL:
                    mButtonCFU.onPickActivityResult(cursor.getString(0));
                    break;
                case CommandsInterface.CF_REASON_BUSY:
                    mButtonCFB.onPickActivityResult(cursor.getString(0));
                    break;
                case CommandsInterface.CF_REASON_NO_REPLY:
                    mButtonCFNRy.onPickActivityResult(cursor.getString(0));
                    break;
                case CommandsInterface.CF_REASON_NOT_REACHABLE:
                    mButtonCFNRc.onPickActivityResult(cursor.getString(0));
                    break;
                default:
                    // TODO: may need exception here.
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {  // See ActionBar#setDisplayHomeAsUpEnabled()
            if (PhoneUtils.isMultiSimEnabled()) {
                MSimCallFeaturesSubSetting.goUpToTopLevelSetting(this);
            } else {
                CallFeaturesSetting.goUpToTopLevelSetting(this);
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private int getActiveNetworkType() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if ((ni == null) || !ni.isConnected()){
                return ConnectivityManager.TYPE_NONE;
            }
            return ni.getType();
        }
        return ConnectivityManager.TYPE_NONE;
    }
}
