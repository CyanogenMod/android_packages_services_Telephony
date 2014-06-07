/*
 * Copyright (c) 2011-2013 The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2006 The Android Open Source Project
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

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.CallLog.Calls;
import android.provider.Settings;
import android.telephony.MSimTelephonyManager;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyCapabilities;

import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;

/**
 * NotificationManager-related utility code for the Phone app.
 *
 * This is a singleton object which acts as the interface to the
 * framework's NotificationManager, and is used to display status bar
 * icons and control other status bar-related behavior.
 *
 * @see PhoneApp.notificationMgr
 */
public class MSimNotificationMgr extends NotificationMgr {
    private static final String LOG_TAG = "MSimNotificationMgr";

    static final int VOICEMAIL_NOTIFICATION_SUB2 = 20;
    static final int CALL_FORWARD_NOTIFICATION_SUB2 = 21;
    static final int CALL_FORWARD_XDIVERT = 22;
    static final int VOICEMAIL_NOTIFICATION_SUB3 = 23;
    static final int CALL_FORWARD_NOTIFICATION_SUB3 = 24;

    /**
     * Private constructor (this is a singleton).
     * @see init()
     */
    private MSimNotificationMgr(PhoneGlobals app) {
        super(app);
    }

    /**
     * Initialize the singleton NotificationMgr instance.
     *
     * This is only done once, at startup, from PhoneApp.onCreate().
     * From then on, the NotificationMgr instance is available via the
     * PhoneApp's public "notificationMgr" field, which is why there's no
     * getInstance() method here.
     */
    /* package */ static NotificationMgr init(PhoneGlobals app) {
        synchronized (MSimNotificationMgr.class) {
            if (sInstance == null) {
                sInstance = new MSimNotificationMgr(app);
                // Update the notifications that need to be touched at startup.
                sInstance.updateNotificationsAtStartup();
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    /**
     * Updates the message waiting indicator (voicemail) notification.
     *
     * @param visible true if there are messages waiting
     */
    /* package */
    void updateMwi(boolean visible, Phone phone) {
        int subscription = phone.getSubscription();
        if (DBG) log("updateMwi(): " + visible + " Subscription: " + subscription);

        int[] iconId = {R.drawable.stat_notify_voicemail_sub1,
                R.drawable.stat_notify_voicemail_sub2, R.drawable.stat_notify_voicemail_sub3};
        int resId = iconId[subscription];

        int notificationId = getNotificationIdBasedOnSubscription(subscription);

        if (visible) {
            // This Notification can get a lot fancier once we have more
            // information about the current voicemail messages.
            // (For example, the current voicemail system can't tell
            // us the caller-id or timestamp of a message, or tell us the
            // message count.)

            // But for now, the UI is ultra-simple: if the MWI indication
            // is supposed to be visible, just show a single generic
            // notification.

            String notificationTitle = mContext.getString(R.string.notification_voicemail_title);
            String vmNumber = phone.getVoiceMailNumber();
            if (DBG) log("- got vm number: '" + vmNumber + "'");

            // Watch out: vmNumber may be null, for two possible reasons:
            //
            //   (1) This phone really has no voicemail number
            //
            //   (2) This phone *does* have a voicemail number, but
            //       the SIM isn't ready yet.
            //
                                       //
            // Case (2) *does* happen in practice if you have voicemail
            // messages when the device first boots: we get an MWI
            // notification as soon as we register on the network, but the
            // SIM hasn't finished loading yet.
            //
            // So handle case (2) by retrying the lookup after a short
            // delay.

            if ((vmNumber == null) && !phone.getIccRecordsLoaded()) {
                if (DBG) log("- Null vm number: SIM records not loaded (yet)...");

                // TODO: rather than retrying after an arbitrary delay, it
                // would be cleaner to instead just wait for a
                // SIM_RECORDS_LOADED notification.
                // (Unfortunately right now there's no convenient way to
                // get that notification in phone app code.  We'd first
                // want to add a call like registerForSimRecordsLoaded()
                // to Phone.java and GSMPhone.java, and *then* we could
                // listen for that in the CallNotifier class.)

                // Limit the number of retries (in case the SIM is broken
                // or missing and can *never* load successfully.)
                if (mVmNumberRetriesRemaining-- > 0) {
                    if (DBG) log("  - Retrying in " + VM_NUMBER_RETRY_DELAY_MILLIS + " msec...");
                    ((MSimCallNotifier)mApp.notifier).sendMwiChangedDelayed(
                            VM_NUMBER_RETRY_DELAY_MILLIS, phone);
                    return;
                } else {
                    Log.w(LOG_TAG, "NotificationMgr.updateMwi: getVoiceMailNumber() failed after "
                          + MAX_VM_NUMBER_RETRIES + " retries; giving up.");
                    // ...and continue with vmNumber==null, just as if the
                    // phone had no VM number set up in the first place.
                }
            }

            if (TelephonyCapabilities.supportsVoiceMessageCount(phone)) {
                int vmCount = phone.getVoiceMessageCount();
                String titleFormat = mContext.getString(
                        R.string.notification_voicemail_title_count);
                notificationTitle = String.format(titleFormat, vmCount);
            }

            String notificationText;
            if (TextUtils.isEmpty(vmNumber)) {
                notificationText = mContext.getString(
                        R.string.notification_voicemail_no_vm_number);
            } else {
                notificationText = String.format(
                        mContext.getString(R.string.notification_voicemail_text_format),
                        PhoneNumberUtils.formatNumber(vmNumber));
            }

            Intent intent = new Intent(Intent.ACTION_CALL,
                    Uri.fromParts(Constants.SCHEME_VOICEMAIL, "", null));
            PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, intent, 0);

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
            Uri ringtoneUri;

            String uriString = prefs.getString(
                    CallFeaturesSetting.BUTTON_VOICEMAIL_NOTIFICATION_RINGTONE_KEY, null);
            if (!TextUtils.isEmpty(uriString)) {
                ringtoneUri = Uri.parse(uriString);
            } else {
                ringtoneUri = Settings.System.DEFAULT_NOTIFICATION_URI;
            }

            Notification.Builder builder = new Notification.Builder(mContext);
            builder.setSmallIcon(resId)
                    .setWhen(System.currentTimeMillis())
                    .setContentTitle(notificationTitle)
                    .setContentText(notificationText)
                    .setContentIntent(pendingIntent)
                    .setSound(ringtoneUri);
            Notification notification = builder.getNotification();


            MSimCallFeaturesSubSetting.migrateVoicemailVibrationSettingsIfNeeded(prefs,
                    phone.getSubscription());
            final boolean vibrate = prefs.getBoolean(
                    MSimCallFeaturesSubSetting.BUTTON_VOICEMAIL_NOTIFICATION_VIBRATE_KEY
                            + phone.getSubscription(), false);
            if (vibrate) {
                notification.defaults |= Notification.DEFAULT_VIBRATE;
            }

            notification.flags |= Notification.FLAG_NO_CLEAR;
            configureLedNotification(mContext, VOICEMAIL_NOTIFICATION, notification);
            mNotificationManager.notify(notificationId, notification);
        } else {
            mNotificationManager.cancel(notificationId);
        }
    }

    /**
     * Updates the message call forwarding indicator notification.
     *
     * @param visible true if there are messages waiting
     */
    /* package */ void updateCfi(boolean visible, int subscription) {
        if (DBG) log("updateCfi(): " + visible + "Sub: " + subscription);
        int [] callfwdIcon = {R.drawable.stat_sys_phone_call_forward_sub1,
                R.drawable.stat_sys_phone_call_forward_sub2,
                R.drawable.stat_sys_phone_call_forward_sub3};

        int notificationId = CALL_FORWARD_NOTIFICATION;
        switch (subscription) {
            case 0:
                notificationId =  CALL_FORWARD_NOTIFICATION;
                break;
            case 1:
                notificationId =  CALL_FORWARD_NOTIFICATION_SUB2;
                break;
            case 2:
                notificationId = CALL_FORWARD_NOTIFICATION_SUB3;
                break;
            default:
                //subscription should always be a vaild value and case
                //need to add in future for multiSIM (>3S) architecture, (if any).
                //Here, this default case should not hit in any of multiSIM scenario.
                Log.e(LOG_TAG, "updateCfi: This should not happen, subscription = "+subscription);
                return;
        }

        if (visible) {
            // If Unconditional Call Forwarding (forward all calls) for VOICE
            // is enabled, just show a notification.  We'll default to expanded
            // view for now, so the there is less confusion about the icon.  If
            // it is deemed too weird to have CF indications as expanded views,
            // then we'll flip the flag back.

            // TODO: We may want to take a look to see if the notification can
            // display the target to forward calls to.  This will require some
            // effort though, since there are multiple layers of messages that
            // will need to propagate that information.

            int resId = callfwdIcon[subscription];
            Notification notification;
            final boolean showExpandedNotification = true;
            if (showExpandedNotification) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setClassName("com.android.phone",
                        "com.android.phone.MSimCallFeaturesSetting");

                notification = new Notification(
                        resId,  // icon
                        null, // tickerText
                        0); // The "timestamp" of this notification is meaningless;
                            // we only care about whether CFI is currently on or not.
                notification.setLatestEventInfo(
                        mContext, // context
                        mContext.getString(R.string.labelCF), // expandedTitle
                        mContext.getString(R.string.sum_cfu_enabled_indicator), // expandedText
                        PendingIntent.getActivity(mContext, 0, intent, 0)); // contentIntent
            } else {
                notification = new Notification(
                        resId,  // icon
                        null,  // tickerText
                        System.currentTimeMillis()  // when
                        );
            }

            notification.flags |= Notification.FLAG_ONGOING_EVENT;  // also implies FLAG_NO_CLEAR

            mNotificationManager.notify(
                    notificationId,
                    notification);
        } else {
            mNotificationManager.cancel(notificationId);
        }
    }

    private void updateMuteNotification(int subscription) {
        // Suppress the status bar icon if the the InCallScreen is the
        // foreground activity, since the in-call UI already provides an
        // onscreen indication of the mute state.  (This reduces clutter
        // in the status bar.)

        if ((mCM.getState(subscription) == PhoneConstants.State.OFFHOOK) && PhoneUtils.getMute()) {
            if (DBG) log("updateMuteNotification: MUTED");
            notifyMute();
        } else {
            if (DBG) log("updateMuteNotification: not muted (or not offhook)");
            cancelMute();
        }
    }

    @Override
    protected void updateSpeakerNotification() {
        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        boolean showNotification = false;
        boolean state = false;
        for (int i = 0; i < MSimTelephonyManager.getDefault().getPhoneCount(); i++) {
            state = (state || (mCM.getState(i) == PhoneConstants.State.OFFHOOK));
        }
        showNotification = state && audioManager.isSpeakerphoneOn();

        if (DBG) log(showNotification
                     ? "updateSpeakerNotification: speaker ON"
                     : "updateSpeakerNotification: speaker OFF (or not offhook)");

        updateSpeakerNotification(showNotification);
    }

    /**
     * Updates the XDivert indicator notification.
     *
     * @param visible true if XDivert is enabled.
     */
    /* package */ void updateXDivert(boolean visible) {
        Log.d(LOG_TAG, "updateXDivert: " + visible);
        if (visible) {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setClassName("com.android.phone",
                    "com.android.phone.MSimCallFeaturesSetting");
            int resId = R.drawable.stat_sys_phone_call_forward_xdivert;
            Notification notification = new Notification(
                    resId,  // icon
                    null, // tickerText
                    System.currentTimeMillis()
                    );
            notification.setLatestEventInfo(
                    mContext, // context
                    mContext.getString(R.string.xdivert_title), // expandedTitle
                    mContext.getString(R.string.sum_xdivert_enabled), // expandedText
                    PendingIntent.getActivity(mContext, 0, intent, 0)); // contentIntent

            mNotificationManager.notify(CALL_FORWARD_XDIVERT, notification);
        } else {
            mNotificationManager.cancel(CALL_FORWARD_XDIVERT);
        }
    }

    private int getNotificationIdBasedOnSubscription(int subscription) {

        int notificationId = VOICEMAIL_NOTIFICATION;

        switch (subscription) {
            case 0:
                notificationId = VOICEMAIL_NOTIFICATION;
                break;
            case 1:
                notificationId = VOICEMAIL_NOTIFICATION_SUB2;
                break;
            case 2:
                notificationId = VOICEMAIL_NOTIFICATION_SUB3;
                break;
            default:
                // Subscription should always be a vaild value and case need
                // to add in future for multiSIM (>3S) architecture, (if any).
                // Here, default case should not hit in any of multiSIM scenario.
                Log.e(LOG_TAG, "updateMwi: This should not happen, subscription = " + subscription);
                break;
        }
        return notificationId;
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
