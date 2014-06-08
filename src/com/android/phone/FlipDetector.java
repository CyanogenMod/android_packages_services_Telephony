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

import com.android.internal.telephony.Call;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

public class FlipDetector implements SensorEventListener{
    private static final String TAG = "FlipDetector";
    private static final boolean DEBUG = true;

    private static final int MUTE = 1;
    private static final int HANGUP = 2;

    private SensorManager mSensorManager;
    private Sensor mGravity;
    private Sensor mProximity;

    private CallNotifier mNotifier;
    private Context mContext;
    private Call mCall;

    private static final int AMOUNT_OF_GRAVITY_VALUE = 10;
    private boolean mCloseProximity;
    private float[] mGravityOnZ;
    private int mGravityValuesKept;

    private static final float MIN_GRAVITY = 8;
    private static final float MIN_GRAVITY_CHANGE = 13;

    public FlipDetector(Context context, CallNotifier notifier, Call call) {
        if (DEBUG) {
            Log.d(TAG, "Init FlipDetector");
        }
        mSensorManager = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        mGravity = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        mProximity = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        mNotifier = notifier;
        mContext = context;
        mCall = call;

        mGravityOnZ = new float[AMOUNT_OF_GRAVITY_VALUE];
        mGravityValuesKept = 0;
    }

    public void start() {
        mSensorManager.registerListener(this, mProximity, SensorManager.SENSOR_DELAY_NORMAL);
        mSensorManager.registerListener(this, mGravity, SensorManager.SENSOR_DELAY_NORMAL);
    }

    public void stop() {
        mSensorManager.unregisterListener(this);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Can be ignored.
    }

    private static float difference(float[] a) {
        float min = a[0];
        float max = a[0];

        for (int i=1; i<a.length; i++) {
            if (a[i] < min) {
                min = a[i];
            } else if (a[i] > max) {
                max = a[i];
            }
        }
        return max - min;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_PROXIMITY:
                mCloseProximity = event.values[0] < mProximity.getMaximumRange();
            break;
            case Sensor.TYPE_GRAVITY:
                // Gravity values: positive Z axis is facing up. If a positive 9.8 is detected,
                // the phone is sitting still facing up. If a negative 9.8 is detected, the
                // phone is facing down.
                //
                // We keep some values to calculate a difference.
                if (mGravityValuesKept < AMOUNT_OF_GRAVITY_VALUE) {
                    mGravityOnZ[mGravityValuesKept] = event.values[2];
                    mGravityValuesKept++;
                } else {
                    for (int i=1; i<AMOUNT_OF_GRAVITY_VALUE; i++) {
                        mGravityOnZ[i-1] = mGravityOnZ[i];
                    }
                    mGravityOnZ[AMOUNT_OF_GRAVITY_VALUE-1] = event.values[2];
                }

            break;
        }

        // The difference in gravity must exceed a certain value and the current gravity must be
        // close to -9.8, and the proximity sensor must be detecting the ground.
        if (difference(mGravityOnZ) > MIN_GRAVITY_CHANGE
                && mGravityOnZ[AMOUNT_OF_GRAVITY_VALUE - 1] < -MIN_GRAVITY
                && mCloseProximity) {
            stop();
            switch (PhoneUtils.PhoneSettings.flipAction(mContext)) {
                case MUTE:
                    Log.d(TAG, "Flip detected! Muting...");
                    mNotifier.silenceRinger();
                break;
                case HANGUP:
                    Log.d(TAG, "Flip detected! Hanging up...");
                    PhoneUtils.hangupRingingCall(mCall);
                break;
            }
        }
    }


}
