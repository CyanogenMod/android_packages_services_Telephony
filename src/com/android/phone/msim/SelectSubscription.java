/*
 * Copyright (c) 2010-2014, The Linux Foundation. All rights reserved.
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

package com.android.phone.msim;

import android.app.TabActivity;
import android.content.Intent;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.MenuItem;
import android.widget.TabHost;
import android.widget.TabHost.TabSpec;

import com.android.phone.PhoneGlobals;
import com.android.phone.R;

import java.util.List;

public class SelectSubscription extends TabActivity {

    private static final String LOG_TAG = "SelectSubscription";
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    public static final String PACKAGE = "PACKAGE";
    public static final String TARGET_CLASS = "TARGET_CLASS";
    public static final String EXTRA_THEME = "TARGET_THEME";

    private String[] tabLabel = {"SIM 1", "SIM 2", "SIM 3"};

    private TabSpec subscriptionPref;

    @Override
    public void onPause() {
        super.onPause();
    }

    /*
     * Activity class methods
     */
    @Override
    protected void onCreate(Bundle icicle) {
        Intent intent =  getIntent();
        if (intent.hasExtra(EXTRA_THEME)) {
            String themeName = intent.getStringExtra(EXTRA_THEME);
            if (themeName != null && !themeName.isEmpty()) {
                int style = getResources().getIdentifier(themeName, "style", getPackageName());
                if (style != 0) {
                    setTheme(style);
                }
            }
        } else {
            setTheme(R.style.SettingsLight);
        }

        super.onCreate(icicle);
        if (DBG) log("Creating activity");

        setContentView(R.layout.multi_sim_setting);

        TabHost tabHost = getTabHost();

        String pkg = intent.getStringExtra(PACKAGE);
        String targetClass = intent.getStringExtra(TARGET_CLASS);

        int numPhones = TelephonyManager.getDefault().getPhoneCount();

        SubscriptionManager subscriptionManager =
                       SubscriptionManager.from(super.getApplicationContext());
        for (int i = 0; i < numPhones; i++) {
            SubscriptionInfo sir =
                    subscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(i);
            String displayName =
                    ((sir != null)) ?
                    sir.getDisplayName().toString() : tabLabel[i];

            log("Creating SelectSub activity = " + i + " displayName = " + displayName);


            subscriptionPref = tabHost.newTabSpec(Integer.toString(i));
            subscriptionPref.setIndicator(displayName);
            intent = new Intent().setClassName(pkg, targetClass)
                    .setAction(intent.getAction());

            SubscriptionManager.putPhoneIdAndSubIdExtra(intent, i,
                    sir != null ? sir.getSubscriptionId() : -1);

            subscriptionPref.setContent(intent);
            tabHost.addTab(subscriptionPref);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }
}
