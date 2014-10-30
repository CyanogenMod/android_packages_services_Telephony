/*
 * Copyright (c) 2010-2013, The Linux Foundation. All rights reserved.
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

import android.app.ActionBar;
import android.app.TabActivity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.MSimTelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.widget.TabHost;
import android.widget.TabHost.TabSpec;

import static android.telephony.TelephonyManager.SIM_STATE_ABSENT;
import static android.telephony.TelephonyManager.SIM_STATE_READY;
import static com.android.internal.telephony.MSimConstants.DEFAULT_SUBSCRIPTION;
import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;

public class SelectSubscription extends  TabActivity {

    private static final String LOG_TAG = "SelectSubscription";
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    public static final String PACKAGE = "PACKAGE";
    public static final String TARGET_CLASS = "TARGET_CLASS";

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
        super.onCreate(icicle);
        if (DBG) log("Creating activity");

        Intent intent =  getIntent();
        String pkg = intent.getStringExtra(PACKAGE);
        String targetClass = intent.getStringExtra(TARGET_CLASS);

        // Fixed value for public intent
        if (intent.getAction().equals(Settings.ACTION_DATA_ROAMING_SETTINGS)) {
            setTheme(R.style.Theme_Settings);
            pkg = "com.android.phone";
            targetClass = "com.android.phone.MSimMobileNetworkSubSettings";
            // Update title for mobile networks settings.
            setTitle(getResources().getText(R.string.mobile_networks));
        }  else {
            setTheme(R.style.SettingsLight);
        }

        setContentView(R.layout.multi_sim_setting);

        TabHost tabHost = getTabHost();

        MSimTelephonyManager tm = MSimTelephonyManager.getDefault();

        int numPhones = tm.getPhoneCount();

        for (int i = 0; i < numPhones; i++) {
            String label = Settings.Global.getSimNameForSubscription(this, i, null);
            if (TextUtils.isEmpty(label)) {
                String operatorName = tm.getSimOperatorName(i);
                if (tm.getSimState(i) == SIM_STATE_ABSENT || TextUtils.isEmpty(operatorName)) {
                    label = getString(R.string.default_sim_name, i + 1);
                } else {
                    label = getString(R.string.multi_sim_entry_format, operatorName, i + 1);
                }
            } else {
                label = getString(R.string.multi_sim_entry_format, label, i + 1);
            }
            subscriptionPref = tabHost.newTabSpec(label);
            subscriptionPref.setIndicator(label);
            intent = new Intent().setClassName(pkg, targetClass)
                    .setAction(intent.getAction()).putExtra(SUBSCRIPTION_KEY, i);
            subscriptionPref.setContent(intent);
            tabHost.addTab(subscriptionPref);
        }
        tabHost.setCurrentTab(getIntent().getIntExtra(SUBSCRIPTION_KEY, DEFAULT_SUBSCRIPTION));

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
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
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
