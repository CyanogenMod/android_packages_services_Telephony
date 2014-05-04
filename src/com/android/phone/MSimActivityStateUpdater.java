/*
 * Copyright (C) 2014 The CyanogenMod Project
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

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.telephony.MSimTelephonyManager;

public class MSimActivityStateUpdater extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        boolean dualSim = MSimTelephonyManager.getDefault().isMultiSimEnabled();

        // These activities share actions in their intent filters. We need to select the correct one
        // so that apps get what they are looking for, depending if MSIM is available or not.
        setState(context, CallFeaturesSetting.class, !dualSim);
        setState(context, MobileNetworkSettings.class, !dualSim);
        setState(context, MSimCallFeaturesSetting.class, dualSim);
        setState(context, MSimMobileNetworkSettings.class, dualSim);
        setState(context, SelectSubscription.class, dualSim);
    }

    private void setState(Context context, Class<?> klass, boolean enabled) {
        PackageManager pm = context.getPackageManager();
        ComponentName component = new ComponentName(context, klass);
        pm.setComponentEnabledSetting(component, enabled ?
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT :
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}
