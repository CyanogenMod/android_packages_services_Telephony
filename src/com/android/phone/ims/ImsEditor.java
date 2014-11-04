/*
 * Copyright (c) 2012, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.phone.ims;

import java.util.HashSet;

import com.android.internal.telephony.Phone;
import com.android.phone.R;
import org.codeaurora.ims.IImsService;
import org.codeaurora.ims.IImsServiceListener;

import android.app.ActionBar;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.MultiSelectListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.Toast;

/**
 * The activity class for editing a new or existing IMS profile.
 */
public class ImsEditor extends PreferenceActivity
        implements Preference.OnPreferenceChangeListener {
    private static final int MENU_SAVE = Menu.FIRST;
    private static final int MENU_DISCARD = Menu.FIRST + 1;
    private static final int MENU_REMOVE = Menu.FIRST + 2;
    private static final int EVENT_QUERY_SERVICE_STATUS = 1;
    private static final int EVENT_SET_SERVICE_STATUS = 2;
    private static final int EVENT_SET_VT_CALL_QUALITY = 3;
    private static final int EVENT_QUERY_VT_CALL_QUALITY = 4;

    private static final String IMS_CALL_TYPE_VOICE = "Voice";
    private static final String IMS_CALL_TYPE_VIDEO = "Video";
    private static final String IMS_CALL_TYPE_CS = "CSVoice";

    private static final String TAG = ImsEditor.class.getSimpleName();

    // Check if the tag is loggable
    private static final boolean DBG = Log.isLoggable("IMS", Log.DEBUG);

    private ImsSharedPreferences mSharedPreferences;
    private MultiSelectListPreference mUseAlwaysPref;
    private ListPreference mCallTypePref;
    private ListPreference mVideoCallQuality;
    private IImsService mImsService = null;
    private boolean mIsImsListenerRegistered = false;
    private Handler mHandler;
    private Messenger mMessenger;

    enum PreferenceKey {
        CALLTYPE(R.string.call_type, R.string.default_call_type,
                R.string.default_preference_summary);

        final int text;
        final int initValue;
        final int defaultSummary;
        Preference preference;

        /**
         * @param key The key name of the preference.
         * @param initValue The initial value of the preference.
         * @param defaultSummary The default summary value of the preference
         *        when the preference value is empty.
         */
        PreferenceKey(int text, int initValue, int defaultSummary) {
            this.text = text;
            this.initValue = initValue;
            this.defaultSummary = defaultSummary;
        }

        String getValue() {
            if (preference instanceof ListPreference) {
                return ((ListPreference) preference).getValue();
            }
            throw new RuntimeException("getValue() for the preference " + this);
        }

        void setValue(String value) {
            if (preference instanceof ListPreference) {
                ((ListPreference) preference).setValue(value);
            }

            if (preference != null) {
                if (TextUtils.isEmpty(value)) {
                    preference.setSummary(defaultSummary);
                } else {
                    preference.setSummary(value);
                }
            }
            String oldValue = getValue();
            if (DBG) Log.v(TAG, this + ": setValue() " + value + ": " + oldValue
                    + " --> " + getValue());
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (DBG) Log.v(TAG, "start profile editor");
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.ims_edit);

        mSharedPreferences = new ImsSharedPreferences(this);

        mHandler = new ImsEditorHandler();
        mMessenger = new Messenger(mHandler);

        PreferenceGroup screen = getPreferenceScreen();
        for (int i = 0, n = screen.getPreferenceCount(); i < n; i++) {
            setupPreference(screen.getPreference(i));
        }
        mUseAlwaysPref = (MultiSelectListPreference) screen
                .findPreference(getString(R.string.ims_call_type_control));
        mCallTypePref = (ListPreference) screen.findPreference(getString(R.string.call_type));
        mVideoCallQuality = (ListPreference) screen
                .findPreference(getString(R.string.ims_vt_call_quality));
        screen.setTitle(R.string.ims_edit_title);
        bindImsService();
        loadPreferences();
        loadVideoCallQualityPrefs();

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    /**
     * Method to enable or disable a preference
     */
    private void enablePref(Preference pref, boolean enable) {
        if (pref != null) {
            pref.setEnabled(enable);
        }
    }


    private class ImsEditorHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case EVENT_QUERY_SERVICE_STATUS:/* Intentional fall through */
                case EVENT_SET_SERVICE_STATUS:
                    AsyncResult ar = (AsyncResult) msg.obj;
                    if (ar != null && ar.exception != null) {
                        Log.e(TAG, msg.what + " failed " + ar.exception.toString());
                        Toast toast = Toast.makeText(getApplicationContext(),
                                "Querying/Setting IMS Service Failed", Toast.LENGTH_LONG);
                        toast.show();
                        enablePref(mUseAlwaysPref, true);
                    } else {
                        enablePref(mUseAlwaysPref, false);
                    }
                    break;
                case EVENT_SET_VT_CALL_QUALITY:
                    handleSetVideoCallQuality(msg);
                    break;
                case EVENT_QUERY_VT_CALL_QUALITY:
                    handleGetVideoCallQuality(msg);
                    break;
                default:
                    Log.e(TAG, "Unhandled message " + msg.what);
                    break;
            }
        }
    };

    private void bindImsService() {
        try {
            // send intent to start ims service n get phone from ims service
            boolean bound = bindService(new Intent(
                    "org.codeaurora.ims.IImsService"), ImsServiceConnection,
                    Context.BIND_AUTO_CREATE);
            Log.v(TAG, "ImsEditor IMSService bound request" + bound);
        } catch (NoClassDefFoundError e) {
            Log.v(TAG, "Ignoring IMS class not found exception " + e);
        }
    }

    private ServiceConnection ImsServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.v(TAG, "ImsEditor Ims Service Connected");
            mImsService = IImsService.Stub.asInterface(service);
            if (mImsService != null && !mIsImsListenerRegistered) {
                try {
                    int result = mImsService.registerCallback(imsServListener);
                    if (result == 0) {
                        mIsImsListenerRegistered = true;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Exception in mImsService.registerCallback");
                }
                try {
                    mImsService.queryImsServiceStatus(
                            EVENT_QUERY_SERVICE_STATUS, mMessenger);
                    enablePref(mUseAlwaysPref, false);
                }
                catch (Exception e) {
                    Log.e(TAG, "Exception = " + e);
                }
            }
            queryVideoQuality();
        }

        public void onServiceDisconnected(ComponentName arg0) {
            Log.v(TAG, "Ims Service onServiceDisconnected");
            mImsService = null;
        }
    };

    /**
     * private Handler mHandler = new Handler() {
     *
     * @Override public void handleMessage(Message msg) { switch (msg.what) {
     *           default: super.handleMessage(msg); } } };
     */
    IImsServiceListener imsServListener = new IImsServiceListener.Stub() {
        public void imsUpdateServiceStatus(int service, int status) {
            Log.d(TAG, "imsUpdateServiceStatus response service " + service + "status = " + status);
            mSharedPreferences.setImsSrvStatus(service, status);
            loadPreferences();
        }

        public void imsRegStateChanged(int imsRegState) {
        }

        public void imsRegStateChangeReqFailed() {
        }
    };

    @Override
    public void onPause() {
        if (DBG) Log.v(TAG, "ImsEditor onPause(): finishing? " + isFinishing());
        if (!isFinishing()) {
            validateAndSetResult();
        }
        super.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mIsImsListenerRegistered == true) {
            try {
                mImsService.deregisterCallback(imsServListener);
                mIsImsListenerRegistered = false;
            } catch (Exception e) {
                Log.e(TAG, "Exception " + e);
            }
        }
        if (mImsService != null) {
            unbindService(ImsServiceConnection);
            mImsService = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, MENU_DISCARD, 1, R.string.ims_menu_discard)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, MENU_SAVE, 2, R.string.ims_menu_save)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, MENU_REMOVE, 3, R.string.remove_ims_account)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: // See ActionBar#setDisplayHomeAsUpEnabled()
                // This time just work as "back" or "save" capability.
            case MENU_SAVE:
                validateAndSetResult();
                return true;

            case MENU_DISCARD:
                finish();
                return true;

            case MENU_REMOVE: {
                removePreferencesAndFinish();
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                validateAndSetResult();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private String convertCallTypeToStr(int callType) {
        String callTypeStr = IMS_CALL_TYPE_CS;
        switch (callType) {
            case Phone.CALL_TYPE_VOICE:
                callTypeStr = IMS_CALL_TYPE_VOICE;
                break;
            case Phone.CALL_TYPE_VT:
                callTypeStr = IMS_CALL_TYPE_VIDEO;
                break;
        }
        return callTypeStr;
    }

    private int convertCallTypeToInt(String callType) {
        int callTypeInt = Phone.CALL_TYPE_UNKNOWN;
        if (IMS_CALL_TYPE_VOICE.equalsIgnoreCase(callType)) {
            callTypeInt = Phone.CALL_TYPE_VOICE;
        } else if (IMS_CALL_TYPE_VIDEO.equalsIgnoreCase(callType)) {
            callTypeInt = Phone.CALL_TYPE_VT;
        }
        return callTypeInt;
    }

    private void loadPreferences() {
        HashSet<String> callTypeSet = new HashSet<String>();
        boolean voiceSupp = mSharedPreferences.isImsSrvAllowed(Phone.CALL_TYPE_VOICE);
        boolean vtSupp = mSharedPreferences.isImsSrvAllowed(Phone.CALL_TYPE_VT);

        /* Update the Multi Select List Preference for Capabilities */
        if (voiceSupp) {
            callTypeSet.add(IMS_CALL_TYPE_VOICE);
        }
        if (vtSupp) {
            callTypeSet.add(IMS_CALL_TYPE_VIDEO);
        }
        mUseAlwaysPref.setValues(callTypeSet);
        if (callTypeSet.isEmpty()) {
            mUseAlwaysPref.setSummary(R.string.ims_service_capability);
        } else {
            mUseAlwaysPref.setSummary(callTypeSet.toString());
        }
        mSharedPreferences.setIsImsCapEnabled(Phone.CALL_TYPE_VOICE, voiceSupp);
        mSharedPreferences.setIsImsCapEnabled(Phone.CALL_TYPE_VT, vtSupp);

        /* Update List Preference for MO Call Type */
        if (voiceSupp && vtSupp) {
            mCallTypePref.setEntries(R.array.ims_call_types);
            mCallTypePref.setEntryValues(R.array.ims_call_types);
            mSharedPreferences.setCallTypeSelectable(true);
        } else if (voiceSupp) {
            mCallTypePref.setEntries(R.array.ims_voice_cs_call_types);
            mCallTypePref.setEntryValues(R.array.ims_voice_cs_call_types);
            if (mSharedPreferences.getCallType() == Phone.CALL_TYPE_VT) {
                mSharedPreferences.setCallType(Phone.CALL_TYPE_VOICE);
            }
            mSharedPreferences.setCallTypeSelectable(true);
        } else if (vtSupp) {
            mCallTypePref.setEntries(R.array.ims_video_cs_call_types);
            mCallTypePref.setEntryValues(R.array.ims_video_cs_call_types);
            if (mSharedPreferences.getCallType() == Phone.CALL_TYPE_VOICE) {
                mSharedPreferences.setCallType(Phone.CALL_TYPE_UNKNOWN);
            }
            mSharedPreferences.setCallTypeSelectable(true);
        } else {
            mCallTypePref.setEntries(R.array.cs_call_type);
            mCallTypePref.setEntryValues(R.array.cs_call_type);
            if (mSharedPreferences.getCallType() == Phone.CALL_TYPE_VOICE
                    || mSharedPreferences.getCallType() == Phone.CALL_TYPE_VT) {
                mSharedPreferences.setCallType(Phone.CALL_TYPE_UNKNOWN);
            }
            mSharedPreferences.setCallTypeSelectable(false);
        }

        if (voiceSupp | vtSupp) {
            enablePref(mUseAlwaysPref, true);
        } else {
            enablePref(mUseAlwaysPref, false);
        }
        PreferenceKey.CALLTYPE.setValue(convertCallTypeToStr(mSharedPreferences.getCallType()));
        PreferenceKey.CALLTYPE.preference.setEnabled(mSharedPreferences.isCallTypeSelectable());
        enablePref(mVideoCallQuality, vtSupp);
    }

    private void validateAndSetResult() {
        Log.v(TAG, "validateAndSetResult");
        mSharedPreferences.setCallType(convertCallTypeToInt(PreferenceKey.CALLTYPE.getValue()));

        if (mUseAlwaysPref.getSummary().toString().contains(IMS_CALL_TYPE_VOICE)) {
            mSharedPreferences.setIsImsCapEnabled(Phone.CALL_TYPE_VOICE, true);
        }
        if (mUseAlwaysPref.getSummary().toString().contains(IMS_CALL_TYPE_VIDEO)) {
            mSharedPreferences.setIsImsCapEnabled(Phone.CALL_TYPE_VT, true);
        }
        setResult(RESULT_OK);
        Toast.makeText(this, R.string.saving_account, Toast.LENGTH_SHORT)
                .show();
        finish();
    }

    private void removePreferencesAndFinish() {
        Log.v(TAG, "removePreferencesAndFinish");
        mSharedPreferences.setCallType(Phone.CALL_TYPE_UNKNOWN);
        mSharedPreferences.setIsImsCapEnabled(Phone.CALL_TYPE_VOICE, false);
        mSharedPreferences.setIsImsCapEnabled(Phone.CALL_TYPE_VT, false);
        setResult(RESULT_OK);
        finish();
    }

    private void handleCallDefaultPrefChange(Preference pref, Object newValue) {
        boolean hasVoice = pref.getSummary().toString().contains(IMS_CALL_TYPE_VOICE);
        boolean hasVT = pref.getSummary().toString().contains(IMS_CALL_TYPE_VIDEO);
        try {
            if (!(hasVoice && mSharedPreferences.getisImsCapEnabled(Phone.CALL_TYPE_VOICE))) {
                Log.d(TAG, "Voice Pref Changed - sending SET Request");
                mImsService.setServiceStatus(Phone.CALL_TYPE_VOICE, -1,
                        mSharedPreferences.getisImsCapEnabled(Phone.CALL_TYPE_VOICE) ? 0 : 1,
                        0, EVENT_SET_SERVICE_STATUS, mMessenger);
            }
            if (!(hasVT && mSharedPreferences.getisImsCapEnabled(Phone.CALL_TYPE_VT))) {
                Log.d(TAG, "Video Pref Changed - sending SET Request");
                mImsService.setServiceStatus(Phone.CALL_TYPE_VT, -1,
                        mSharedPreferences.getisImsCapEnabled(Phone.CALL_TYPE_VT) ? 0 : 1,
                        0, EVENT_SET_SERVICE_STATUS, mMessenger);
            }
            enablePref(mUseAlwaysPref, false);
        } catch (Exception e) {
            Log.e(TAG, "Exception " + e);
        }
    }

    public boolean onPreferenceChange(Preference pref, Object newValue) {
        String value = (newValue == null) ? "" : newValue.toString();
        String summary = null;
        if (pref.equals(mUseAlwaysPref) && ((HashSet) newValue).isEmpty()) {
            summary = getString(R.string.ims_service_capability);
        } else {
            summary = value;
        }
        if (TextUtils.isEmpty(value) && summary == null) {
            pref.setSummary(getPreferenceKey(pref).defaultSummary);
        } else if (summary != null) {
            pref.setSummary(summary);
        }
        if (pref.equals(mUseAlwaysPref)) {
            handleCallDefaultPrefChange(pref, newValue);
        }
        if (pref.equals(mVideoCallQuality)) {
            final int quality = Integer.parseInt(value);
            setVideoQuality(quality);
            mSharedPreferences.saveVideoCallQuality(quality);
            loadVideoCallQualityPrefs();
        }
        return true;
    }

    private PreferenceKey getPreferenceKey(Preference pref) {
        for (PreferenceKey key : PreferenceKey.values()) {
            if (key.preference == pref) return key;
        }
        throw new RuntimeException("not possible to reach here");
    }

    private void setupPreference(Preference pref) {
        pref.setOnPreferenceChangeListener(this);
        for (PreferenceKey key : PreferenceKey.values()) {
            String name = getString(key.text);
            if (name.equals(pref.getKey())) {
                key.preference = pref;
                return;
            }
        }
    }

    private void handleGetVideoCallQuality(Message msg) {
        if (hasMsgrRequestFailed(msg)) {
            Log.e(TAG, "VideoCallQuality: query failed. errorCode=" + msg.arg1);
        } else if (msg.obj == null){
            Log.e(TAG, "VideoCallQuality: query failed. object is null.");
        } else if (!(msg.obj instanceof int[])){
            Log.e(TAG, "VideoCallQuality: invalid object");
        } else {
            final int quality = ((int[]) msg.obj)[0];
            Log.d(TAG, "VideoCallQuality: value=" + quality);
            mSharedPreferences.saveVideoCallQuality(quality);
            loadVideoCallQualityPrefs();
        }
    }

    private void handleSetVideoCallQuality(Message msg) {
        if (hasMsgrRequestFailed(msg)) {
            Log.e(TAG, "VideoCallQuality: set failed. errorCode=" + msg.arg1);
            Toast.makeText(this, R.string.ims_vt_call_quality_set_failed,
                    Toast.LENGTH_SHORT).show();
            queryVideoQuality(); // Set request failed, request current value.
        } else {
            Log.d(TAG, "VideoCallQuality: set succeeded.");
        }
    }

    private void loadVideoCallQualityPrefs() {
        final int vqValue = mSharedPreferences.readVideoCallQuality();
        final String videoQuality = videoQualityToString(vqValue);
        Log.d(TAG, "loadVideoCallQualityPrefs, vqValue=" + vqValue);
        mVideoCallQuality.setValue(String.valueOf(vqValue));
        mVideoCallQuality.setSummary(videoQuality);
    }

    private void queryVideoQuality() {
        try {
            if (mImsService != null) {
                mImsService.queryVtQuality(obtainMessage(EVENT_QUERY_VT_CALL_QUALITY));
            } else {
                Log.e(TAG, "queryVtQuality failed. ImsService is null");
            }
        } catch (Exception e) {
            Log.e(TAG, "queryVtQuality failed. Exception=" + e);
        }
    }

    private void setVideoQuality(int quality) {
        try {
            if (mImsService != null) {
                mImsService.setVtQuality(quality, obtainMessage(EVENT_SET_VT_CALL_QUALITY));
            } else {
                Log.e(TAG, "setVtQuality failed. ImsService is null");
            }
        } catch (Exception e) {
            Log.e(TAG, "setVtQuality failed. Exception=" + e);
        }
    }

    private Message obtainMessage(int event) {
        Message msg = mHandler.obtainMessage(event);
        msg.replyTo = mMessenger;
        return msg;
    }

    private boolean hasMsgrRequestFailed(Message msg) {
        final int ERROR_SUCCESS = 0;
        return (msg == null) || (msg.arg1 != ERROR_SUCCESS);
    }

    private String videoQualityToString(int quality) {
        switch (quality) {
            case ImsSharedPreferences.VT_QUALITY_HIGH:
                return getString(R.string.ims_vt_call_quality_high);
            case ImsSharedPreferences.VT_QUALITY_LOW:
                return getString(R.string.ims_vt_call_quality_low);
            default:
                return getString(R.string.ims_vt_call_quality_unknown);
        }
    }
}
