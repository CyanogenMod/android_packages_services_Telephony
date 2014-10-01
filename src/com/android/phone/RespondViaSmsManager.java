/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.app.ActivityManager;
import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemProperties;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.telephony.PhoneNumberUtils;
import android.telephony.MSimTelephonyManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.PhoneConstants;

import com.google.android.collect.Lists;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Helper class to manage the "Respond via Message" feature for incoming calls.
 *
 * @see InCallScreen.internalRespondViaSms()
 */
public class RespondViaSmsManager {
    private static final String TAG = "RespondViaSmsManager";
    private static final boolean DBG =
            (PhoneGlobals.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    // Do not check in with VDBG = true, since that may write PII to the system log.
    private static final boolean VDBG = false;

    /** SharedPreferences file name for our persistent settings. */
    private static final String SHARED_PREFERENCES_NAME = "respond_via_sms_prefs";

    // Preference keys for the 4 "canned responses"; see RespondViaSmsManager$Settings.
    // Since (for now at least) the number of messages is fixed at 4, and since
    // SharedPreferences can't deal with arrays anyway, just store the messages
    // as 4 separate strings.
    private static final String[] CANNED_RESPONSE_KEYS = new String[] {
        "canned_response_pref_1", "canned_response_pref_2",
        "canned_response_pref_3", "canned_response_pref_4"
    };
    private static final String KEY_PREFERRED_PACKAGE = "preferred_package_pref";

    /**
     * Settings activity under "Call settings" to let you manage the
     * canned responses; see respond_via_sms_settings.xml
     */
    public static class Settings extends PreferenceActivity
            implements Preference.OnPreferenceChangeListener {
        @Override
        protected void onCreate(Bundle icicle) {
            super.onCreate(icicle);
            if (DBG) log("Settings: onCreate()...");

            getPreferenceManager().setSharedPreferencesName(SHARED_PREFERENCES_NAME);

            // This preference screen is ultra-simple; it's just 4 plain
            // <EditTextPreference>s, one for each of the 4 "canned responses".
            //
            // The only nontrivial thing we do here is copy the text value of
            // each of those EditTextPreferences and use it as the preference's
            // "title" as well, so that the user will immediately see all 4
            // strings when they arrive here.
            //
            // Also, listen for change events (since we'll need to update the
            // title any time the user edits one of the strings.)

            addPreferencesFromResource(R.xml.respond_via_sms_settings);

            for (String key : CANNED_RESPONSE_KEYS) {
                EmptyWatchingEditTextPreference pref =
                        (EmptyWatchingEditTextPreference) findPreference(key);
                pref.setTitle(pref.getText());
                pref.setWatchedButton(DialogInterface.BUTTON_POSITIVE);
                pref.setOnPreferenceChangeListener(this);
            }

            ActionBar actionBar = getActionBar();
            if (actionBar != null) {
                // android.R.id.home will be triggered in onOptionsItemSelected()
                actionBar.setDisplayHomeAsUpEnabled(true);
            }
        }

        // Preference.OnPreferenceChangeListener implementation
        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            if (DBG) log("onPreferenceChange: key = " + preference.getKey());
            if (VDBG) log("  preference = '" + preference + "'");
            if (VDBG) log("  newValue = '" + newValue + "'");

            EditTextPreference pref = (EditTextPreference) preference;

            // Copy the new text over to the title, just like in onCreate().
            // (Watch out: onPreferenceChange() is called *before* the
            // Preference itself gets updated, so we need to use newValue here
            // rather than pref.getText().)
            pref.setTitle((String) newValue);

            return true;  // means it's OK to update the state of the Preference with the new value
        }

        @Override
        public boolean onOptionsItemSelected(MenuItem item) {
            final int itemId = item.getItemId();
            switch (itemId) {
                case android.R.id.home:
                    // See ActionBar#setDisplayHomeAsUpEnabled()
                    if (MSimTelephonyManager.getDefault().isMultiSimEnabled()){
                        MSimCallFeaturesSubSetting.goUpToTopLevelSetting(this);
                    } else {
                        CallFeaturesSetting.goUpToTopLevelSetting(this);
                    }
                    return true;
                default:
            }
            return super.onOptionsItemSelected(item);
        }

    }

    private static void log(String msg) {
        Log.e(TAG, msg);
    }
}
