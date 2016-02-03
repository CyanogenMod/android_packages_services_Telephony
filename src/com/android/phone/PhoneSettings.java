/*
 * Copyright (C) 2014 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public final class PhoneSettings {
    public static boolean showInCallEvents(Context context) {
        return getPrefs(context).getBoolean("button_show_ssn_key",
                context.getResources().getBoolean(R.bool.def_show_ssn_toast_enabled));
    }

    private static String getKeyForSubscription(String key, int subscription) {
        if (subscription == -1) return key;
        return key + subscription;
    }

    private static SharedPreferences getPrefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static boolean isAecDisabled(Context context) {
        return !getPrefs(context).getBoolean("enable_aec", true);
    }
}

