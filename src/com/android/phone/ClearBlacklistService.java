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

import android.app.IntentService;
import android.content.ContentValues;
import android.content.Intent;
import android.provider.CallLog.Calls;

/**
 * Handles the intent to clear the missed calls that is triggered when a notification is dismissed.
 */
public class ClearBlacklistService extends IntentService {
    /** This action is used to clear blacklisted calls. */
    public static final String ACTION_CLEAR_BLACKLISTED_CALLS =
            "com.android.phone.intent.CLEAR_BLACKLISTED_CALLS";

    private PhoneGlobals mApp;

    public ClearMissedCallsService() {
        super(ClearMissedCallsService.class.getSimpleName());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mApp = PhoneGlobals.getInstance();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        final String action = intent.getAction();
        if (ACTION_CLEAR_BLACKLISTED_CALLS.equals(action)) {
            mApp.notificationMgr.cancelBlacklistedCallNotification();
        }
    }
}
