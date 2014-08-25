/*
 * Copyright (c) 2013, The Linux Foundation. All Rights Reserved.
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

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.preference.PreferenceActivity;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.CommandsInterface;
import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;
import java.util.ArrayList;

public class CdmaCallForwardOptions extends PreferenceActivity {
    private static final String LOG_TAG = "CdmaCallForwardOptions";
    private final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    public static final String SUBSCRIPTION = "Subscription";
    public static final String CDMA_SUPP_CALL = "Cdma_Supp";

    private static final String NUM_PROJECTION[] = {Phone.NUMBER};

    private static final int CATEGORY_NORMAL = 1;

    private static final String BUTTON_CFU_KEY   = "button_cfu_key";
    private static final String BUTTON_CFB_KEY   = "button_cfb_key";
    private static final String BUTTON_CFNRY_KEY = "button_cfnry_key";
    private static final String BUTTON_CFNRC_KEY = "button_cfnrc_key";

    private static final String BUTTON_CFU_DEACT_KEY    = "button_cfu_deact_key";
    private static final String BUTTON_CFB_DEACT_KEY    = "button_cfb_deact_key";
    private static final String BUTTON_CFNRY_DEACT_KEY  = "button_cfnry_deact_key";
    private static final String BUTTON_CFNRC_DEACT_KEY  = "button_cfnrc_deact_key";
    private static final String BUTTON_CF_DEACT_ALL_KEY = "button_cf_deact_all_key";

    private CdmaCallForwardEditPreference mButtonCFU;
    private CdmaCallForwardEditPreference mButtonCFB;
    private CdmaCallForwardEditPreference mButtonCFNRy;
    private CdmaCallForwardEditPreference mButtonCFNRc;

    private PreferenceScreen mCfuDeactPref;
    private PreferenceScreen mCfbDeactPref;
    private PreferenceScreen mCfnryDeactPref;
    private PreferenceScreen mCfnrcDeactPref;
    private PreferenceScreen mCfDeactAllPref;

    private CdmaCallOptionsSetting mCallOptionSettings;

    private final ArrayList<CdmaCallForwardEditPreference> mPreferences =
            new ArrayList<CdmaCallForwardEditPreference> ();
    private final ArrayList<PreferenceScreen> mDeactPreScreens =
            new ArrayList<PreferenceScreen> ();

    private boolean mFirstResume;
    private int mSubscription = 0;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.callforward_cdma_options);

        // getting selected subscription
        mSubscription = getIntent().getIntExtra(SUBSCRIPTION_KEY, 0);
        Log.d(LOG_TAG, "Inside CF options, Getting subscription =" + mSubscription);

        PreferenceScreen prefSet = getPreferenceScreen();

        mButtonCFU   = (CdmaCallForwardEditPreference) prefSet.findPreference(BUTTON_CFU_KEY);
        mButtonCFB   = (CdmaCallForwardEditPreference) prefSet.findPreference(BUTTON_CFB_KEY);
        mButtonCFNRy = (CdmaCallForwardEditPreference) prefSet.findPreference(BUTTON_CFNRY_KEY);
        mButtonCFNRc = (CdmaCallForwardEditPreference) prefSet.findPreference(BUTTON_CFNRC_KEY);

        mButtonCFU.setParentActivity(this, mButtonCFU.reason);
        mButtonCFB.setParentActivity(this, mButtonCFB.reason);
        mButtonCFNRy.setParentActivity(this, mButtonCFNRy.reason);
        mButtonCFNRc.setParentActivity(this, mButtonCFNRc.reason);

        mPreferences.add(mButtonCFU);
        mPreferences.add(mButtonCFB);
        mPreferences.add(mButtonCFNRy);
        mPreferences.add(mButtonCFNRc);

        mCfuDeactPref = (PreferenceScreen)prefSet.findPreference(BUTTON_CFU_DEACT_KEY);
        mCfbDeactPref = (PreferenceScreen)prefSet.findPreference(BUTTON_CFB_DEACT_KEY);
        mCfnryDeactPref = (PreferenceScreen)prefSet.findPreference(BUTTON_CFNRY_DEACT_KEY);
        mCfnrcDeactPref = (PreferenceScreen)prefSet.findPreference(BUTTON_CFNRC_DEACT_KEY);
        mCfDeactAllPref = (PreferenceScreen)prefSet.findPreference(BUTTON_CF_DEACT_ALL_KEY);

        mDeactPreScreens.add(mCfuDeactPref);
        mDeactPreScreens.add(mCfbDeactPref);
        mDeactPreScreens.add(mCfnryDeactPref);
        mDeactPreScreens.add(mCfnrcDeactPref);
        mDeactPreScreens.add(mCfDeactAllPref);

        mFirstResume = true;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mFirstResume) {
            for (int i = CommandsInterface.CF_REASON_UNCONDITIONAL;
                    i < CommandsInterface.CF_REASON_ALL_CONDITIONAL; i++) {
                mCallOptionSettings = new CdmaCallOptionsSetting(this, i, CATEGORY_NORMAL,
                        mSubscription);
                if (i < mPreferences.size()) {
                    mPreferences.get(i).init(this, mSubscription, mCallOptionSettings
                            .getActivateNumber());
                }
                mDeactPreScreens.get(i).getIntent().putExtra(SUBSCRIPTION, mSubscription)
                        .putExtra(CDMA_SUPP_CALL, true);
                Log.d(LOG_TAG, "call option on type: " + i + " Getting deact num ="
                        + mCallOptionSettings.getDeactivateNumber());
                mDeactPreScreens.get(i).getIntent().setData(Uri.fromParts("tel",
                        mCallOptionSettings.getDeactivateNumber(), null));
                mDeactPreScreens.get(i).setSummary(mCallOptionSettings.getDeactivateNumber());
            }
            mFirstResume = false;
        }
    }

    // override the startsubactivity call to make changes in state consistent.
    @Override
    public void startActivityForResult(Intent intent, int requestCode) {
        if (requestCode == -1) {
            // this is an intent requested from the preference framework.
            super.startActivityForResult(intent, requestCode);
            return;
        }

        if (DBG) Log.d(LOG_TAG, "startSubActivity: starting requested subactivity");
        super.startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (DBG) Log.d(LOG_TAG, "onActivityResult: done");
        if (resultCode != RESULT_OK) {
            if (DBG) Log.d(LOG_TAG, "onActivityResult: contact picker result not OK.");
            return;
        }
        Cursor cursor = getContentResolver().query(data.getData(),
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
                break;

        }
    }
}
