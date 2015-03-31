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

public class ComponentEnableReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        // These components get disabled/enabled during the upgrade process from KK
        // re-enable these components as they're handled dynamically in L
        if (!getEnabled(context, CallFeaturesSetting.class)) {
            setState(context, CallFeaturesSetting.class, true);
        }
        if (!getEnabled(context, MobileNetworkSettings.class)) {
            setState(context, MobileNetworkSettings.class, true);
        }

        // We should only ever have to do this once, disable this receiver
        setState(context, ComponentEnableReceiver.class, false);
    }

    private void setState(Context context, Class<?> klass, boolean enabled) {
        PackageManager pm = context.getPackageManager();
        ComponentName component = new ComponentName(context, klass);
        pm.setComponentEnabledSetting(component, enabled ?
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT :
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    private boolean getEnabled(Context context, Class<?> klass) {
        PackageManager pm = context.getPackageManager();
        ComponentName component = new ComponentName(context, klass);
        return pm.getComponentEnabledSetting(component) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }
}
