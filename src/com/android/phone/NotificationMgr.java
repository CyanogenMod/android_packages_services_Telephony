/*
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

import java.util.ArrayList;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.AsyncQueryHandler;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.PhoneLookup;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.text.BidiFormatter;
import android.text.SpannableStringBuilder;
import android.text.TextDirectionHeuristics;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyCapabilities;
import com.android.internal.telephony.util.BlacklistUtils;

import java.util.List;

/**
 * NotificationManager-related utility code for the Phone app.
 *
 * This is a singleton object which acts as the interface to the
 * framework's NotificationManager, and is used to display status bar
 * icons and control other status bar-related behavior.
 *
 * @see PhoneGlobals.notificationMgr
 */
public class NotificationMgr {
    private static final String LOG_TAG = "NotificationMgr";
    protected static final boolean DBG =
            (PhoneGlobals.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    // Do not check in with VDBG = true, since that may write PII to the system log.
    private static final boolean VDBG = false;

    private static final String[] CALL_LOG_PROJECTION = new String[] {
        Calls._ID,
        Calls.NUMBER,
        Calls.NUMBER_PRESENTATION,
        Calls.DATE,
        Calls.DURATION,
        Calls.TYPE,
    };

    // notification types
    static final int MISSED_CALL_NOTIFICATION = 1;
    static final int IN_CALL_NOTIFICATION = 2;
    static final int MMI_NOTIFICATION = 3;
    static final int NETWORK_SELECTION_NOTIFICATION = 4;
    static final int VOICEMAIL_NOTIFICATION = 5;
    static final int CALL_FORWARD_NOTIFICATION = 6;
    static final int DATA_DISCONNECTED_ROAMING_NOTIFICATION = 7;
    static final int SELECTED_OPERATOR_FAIL_NOTIFICATION = 8;
    static final int BLACKLISTED_CALL_NOTIFICATION = 9;
    static final int BLACKLISTED_MESSAGE_NOTIFICATION = 10;
    static final int MISSED_VIDEOCALL_NOTIFICATION = 100;

    // notification light default constants
    public static final int DEFAULT_COLOR = 0xFFFFFF; //White
    public static final int DEFAULT_TIME = 1000; // 1 second

    /** The singleton NotificationMgr instance. */
    protected static NotificationMgr sInstance;

    protected PhoneGlobals mApp;
    private Phone mPhone;
    protected CallManager mCM;

    protected Context mContext;
    protected NotificationManager mNotificationManager;
    private StatusBarManager mStatusBarManager;
    private Toast mToast;
    private boolean mShowingSpeakerphoneIcon;
    private boolean mShowingMuteIcon;

    public StatusBarHelper statusBarHelper;

    // used to track missed calls
    private static class MissedCallInfo {
        String name;
        String number;
        long date;

        MissedCallInfo(String name, String number, long date) {
            this.name = name;
            this.number = number;
            this.date = date;
        }
    };
    private ArrayList<MissedCallInfo> mMissedCalls = new ArrayList<MissedCallInfo>();

    // used to track blacklisted calls and messages
    private static class BlacklistedItemInfo {
        String number;
        long date;
        int matchType;

        BlacklistedItemInfo(String number, long date, int matchType) {
            this.number = number;
            this.date = date;
            this.matchType = matchType;
        }
    };
    private ArrayList<BlacklistedItemInfo> mBlacklistedCalls =
            new ArrayList<BlacklistedItemInfo>();
    private ArrayList<BlacklistedItemInfo> mBlacklistedMessages =
            new ArrayList<BlacklistedItemInfo>();

    // used to track the missed video call counter, default to 0.
    private int mNumberMissedVideoCalls = 0;

    // used to track the notification of selected network unavailable
    private boolean mSelectedUnavailableNotify = false;

    // Retry params for the getVoiceMailNumber() call; see updateMwi().
    protected static final int MAX_VM_NUMBER_RETRIES = 5;
    protected static final int VM_NUMBER_RETRY_DELAY_MILLIS = 10000;
    protected int mVmNumberRetriesRemaining = MAX_VM_NUMBER_RETRIES;

    // Query used to look up caller-id info for the "call log" notification.
    private QueryHandler mQueryHandler = null;
    private static final int CALL_LOG_TOKEN = -1;
    private static final int CONTACT_TOKEN = -2;

    /** Call log type for missed CSVT calls. */
    protected static final int MISSED_CSVT_TYPE = 7;

    /**
     * Private constructor (this is a singleton).
     * @see init()
     */
    protected NotificationMgr(PhoneGlobals app) {
        mApp = app;
        mContext = app;
        mNotificationManager =
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        mStatusBarManager =
                (StatusBarManager) app.getSystemService(Context.STATUS_BAR_SERVICE);
        mPhone = app.phone;  // TODO: better style to use mCM.getDefaultPhone() everywhere instead
        mCM = app.mCM;
        statusBarHelper = new StatusBarHelper();
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
        synchronized (NotificationMgr.class) {
            if (sInstance == null) {
                sInstance = new NotificationMgr(app);
                // Update the notifications that need to be touched at startup.
                sInstance.updateNotificationsAtStartup();
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    /**
     * Helper class that's a wrapper around the framework's
     * StatusBarManager.disable() API.
     *
     * This class is used to control features like:
     *
     *   - Disabling the status bar "notification windowshade"
     *     while the in-call UI is up
     *
     *   - Disabling notification alerts (audible or vibrating)
     *     while a phone call is active
     *
     *   - Disabling navigation via the system bar (the "soft buttons" at
     *     the bottom of the screen on devices with no hard buttons)
     *
     * We control these features through a single point of control to make
     * sure that the various StatusBarManager.disable() calls don't
     * interfere with each other.
     */
    public class StatusBarHelper {
        // Current desired state of status bar / system bar behavior
        private boolean mIsNotificationEnabled = true;
        private boolean mIsExpandedViewEnabled = true;
        private boolean mIsSystemBarNavigationEnabled = true;

        private StatusBarHelper () {
        }

        /**
         * Enables or disables auditory / vibrational alerts.
         *
         * (We disable these any time a voice call is active, regardless
         * of whether or not the in-call UI is visible.)
         */
        public void enableNotificationAlerts(boolean enable) {
            if (mIsNotificationEnabled != enable) {
                mIsNotificationEnabled = enable;
                updateStatusBar();
            }
        }

        /**
         * Enables or disables the expanded view of the status bar
         * (i.e. the ability to pull down the "notification windowshade").
         *
         * (This feature is disabled by the InCallScreen while the in-call
         * UI is active.)
         */
        public void enableExpandedView(boolean enable) {
            if (mIsExpandedViewEnabled != enable) {
                mIsExpandedViewEnabled = enable;
                updateStatusBar();
            }
        }

        /**
         * Enables or disables the navigation via the system bar (the
         * "soft buttons" at the bottom of the screen)
         *
         * (This feature is disabled while an incoming call is ringing,
         * because it's easy to accidentally touch the system bar while
         * pulling the phone out of your pocket.)
         */
        public void enableSystemBarNavigation(boolean enable) {
            if (mIsSystemBarNavigationEnabled != enable) {
                mIsSystemBarNavigationEnabled = enable;
                updateStatusBar();
            }
        }

        /**
         * Updates the status bar to reflect the current desired state.
         */
        private void updateStatusBar() {
            int state = StatusBarManager.DISABLE_NONE;

            if (!mIsExpandedViewEnabled) {
                state |= StatusBarManager.DISABLE_EXPAND;
            }
            if (!mIsNotificationEnabled) {
                state |= StatusBarManager.DISABLE_NOTIFICATION_ALERTS;
            }
            if (!mIsSystemBarNavigationEnabled) {
                // Disable *all* possible navigation via the system bar.
                state |= StatusBarManager.DISABLE_HOME;
                state |= StatusBarManager.DISABLE_RECENT;
                state |= StatusBarManager.DISABLE_BACK;
                state |= StatusBarManager.DISABLE_SEARCH;
            }

            if (DBG) log("updateStatusBar: state = 0x" + Integer.toHexString(state));
            mStatusBarManager.disable(state);
        }
    }

    /**
     * Makes sure phone-related notifications are up to date on a
     * freshly-booted device.
     */
    protected void updateNotificationsAtStartup() {
        if (DBG) log("updateNotificationsAtStartup()...");

        // instantiate query handler
        mQueryHandler = new QueryHandler(mContext.getContentResolver());

        // setup query spec, look for all Missed calls that are new.
        StringBuilder where = null;
        if (PhoneUtils.isCallOnCsvtEnabled()) {
            where = new StringBuilder("(type=");
            where.append(Calls.MISSED_TYPE);
            where.append(" OR ");
            where.append("type=");
            where.append(MISSED_CSVT_TYPE);
            where.append(")");
            where.append(" AND new=1");
        } else {
            where = new StringBuilder("type=");
            where.append(Calls.MISSED_TYPE);
            where.append(" AND new=1");
        }

        // start the query
        if (DBG) log("- start call log query...");
        mQueryHandler.startQuery(CALL_LOG_TOKEN, null, Calls.CONTENT_URI,  CALL_LOG_PROJECTION,
                where.toString(), null, Calls.DEFAULT_SORT_ORDER);

        // Depend on android.app.StatusBarManager to be set to
        // disable(DISABLE_NONE) upon startup.  This will be the
        // case even if the phone app crashes.
    }

    /** The projection to use when querying the phones table */
    static final String[] PHONES_PROJECTION = new String[] {
        PhoneLookup.NUMBER,
        PhoneLookup.DISPLAY_NAME,
        PhoneLookup._ID
    };

    /**
     * Class used to run asynchronous queries to re-populate the notifications we care about.
     * There are really 3 steps to this:
     *  1. Find the list of missed calls
     *  2. For each call, run a query to retrieve the caller's name.
     *  3. For each caller, try obtaining photo.
     */
    private class QueryHandler extends AsyncQueryHandler
            implements ContactsAsyncHelper.OnImageLoadCompleteListener {

        /**
         * Used to store relevant fields for the Missed Call
         * notifications.
         */
        private class NotificationInfo {
            public String name;
            public String number;
            public int presentation;
            /**
             * Type of the call. {@link android.provider.CallLog.Calls#INCOMING_TYPE}
             * {@link android.provider.CallLog.Calls#OUTGOING_TYPE}, or
             * {@link android.provider.CallLog.Calls#MISSED_TYPE}.
             */
            public String type;
            public long date;
        }

        public QueryHandler(ContentResolver cr) {
            super(cr);
        }

        /**
         * Handles the query results.
         */
        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            // TODO: it would be faster to use a join here, but for the purposes
            // of this small record set, it should be ok.

            // Note that CursorJoiner is not useable here because the number
            // comparisons are not strictly equals; the comparisons happen in
            // the SQL function PHONE_NUMBERS_EQUAL, which is not available for
            // the CursorJoiner.

            // Executing our own query is also feasible (with a join), but that
            // will require some work (possibly destabilizing) in Contacts
            // Provider.

            // At this point, we will execute subqueries on each row just as
            // CallLogActivity.java does.
            switch (token) {
                case CALL_LOG_TOKEN:
                    if (DBG) log("call log query complete.");

                    // initial call to retrieve the call list.
                    if (cursor != null) {
                        while (cursor.moveToNext()) {
                            // for each call in the call log list, create
                            // the notification object and query contacts
                            NotificationInfo n = getNotificationInfo (cursor);

                            if (DBG) log("query contacts for number: " + n.number);

                            mQueryHandler.startQuery(CONTACT_TOKEN, n,
                                    Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, n.number),
                                    PHONES_PROJECTION, null, null, PhoneLookup.NUMBER);
                        }

                        if (DBG) log("closing call log cursor.");
                        cursor.close();
                    }
                    break;
                case CONTACT_TOKEN:
                    if (DBG) log("contact query complete.");

                    // subqueries to get the caller name.
                    if ((cursor != null) && (cookie != null)){
                        NotificationInfo n = (NotificationInfo) cookie;

                        Uri personUri = null;
                        if (cursor.moveToFirst()) {
                            n.name = cursor.getString(
                                    cursor.getColumnIndexOrThrow(PhoneLookup.DISPLAY_NAME));
                            long person_id = cursor.getLong(
                                    cursor.getColumnIndexOrThrow(PhoneLookup._ID));
                            if (DBG) {
                                log("contact :" + n.name + " found for phone: " + n.number
                                        + ". id : " + person_id);
                            }
                            personUri = ContentUris.withAppendedId(Contacts.CONTENT_URI, person_id);
                        }

                        if (personUri != null) {
                            if (DBG) {
                                log("Start obtaining picture for the missed call. Uri: "
                                        + personUri);
                            }
                            // Now try to obtain a photo for this person.
                            // ContactsAsyncHelper will do that and call onImageLoadComplete()
                            // after that.
                            ContactsAsyncHelper.startObtainPhotoAsync(
                                    0, mContext, personUri, this, n);
                        } else {
                            if (DBG) {
                                log("Failed to find Uri for obtaining photo."
                                        + " Just send notification without it.");
                            }
                            // We couldn't find person Uri, so we're sure we cannot obtain a photo.
                            // Call notifyMissedCall() right now.
                            if (String.valueOf(MISSED_CSVT_TYPE).equals(n.type)) {
                                notifyMissedVideoCall(n.name, n.number, n.type, n.date);
                            } else {
                                notifyMissedCall(n.name, n.number, n.presentation, n.type, null, null,
                                    n.date);
                            }
                        }

                        if (DBG) log("closing contact cursor.");
                        cursor.close();
                    }
                    break;
                default:
            }
        }

        @Override
        public void onImageLoadComplete(
                int token, Drawable photo, Bitmap photoIcon, Object cookie) {
            if (DBG) log("Finished loading image: " + photo);
            NotificationInfo n = (NotificationInfo) cookie;
            if (String.valueOf(MISSED_CSVT_TYPE).equals(n.type)) {
                notifyMissedVideoCall(n.name, n.number, n.type, n.date);
            } else {
                notifyMissedCall(n.name, n.number, n.presentation, n.type, 
                    photo, photoIcon, n.date);
            }
        }

        /**
         * Factory method to generate a NotificationInfo object given a
         * cursor from the call log table.
         */
        private final NotificationInfo getNotificationInfo(Cursor cursor) {
            NotificationInfo n = new NotificationInfo();
            n.name = null;
            n.number = cursor.getString(cursor.getColumnIndexOrThrow(Calls.NUMBER));
            n.presentation = cursor.getInt(cursor.getColumnIndexOrThrow(Calls.NUMBER_PRESENTATION));
            n.type = cursor.getString(cursor.getColumnIndexOrThrow(Calls.TYPE));
            n.date = cursor.getLong(cursor.getColumnIndexOrThrow(Calls.DATE));

            // make sure we update the number depending upon saved values in
            // CallLog.addCall().  If either special values for unknown or
            // private number are detected, we need to hand off the message
            // to the missed call notification.
            if (n.presentation != Calls.PRESENTATION_ALLOWED) {
                n.number = null;
            }

            if (DBG) log("NotificationInfo constructed for number: " + n.number);

            return n;
        }
    }

    /**
     * Configures a Notification to emit the blinky message-waiting/
     * missed-call signal.
     */
    protected static void configureLedNotification(Context context,
            int notificationType, Notification note) {
        ContentResolver resolver = context.getContentResolver();

        boolean lightEnabled = Settings.System.getInt(resolver,
                Settings.System.NOTIFICATION_LIGHT_PULSE, 0) == 1;
        if (!lightEnabled) {
            return;
        }

        note.flags |= Notification.FLAG_SHOW_LIGHTS;

        // Get Missed call and Voice mail values if they are to be used
        boolean customEnabled = Settings.System.getInt(resolver,
                Settings.System.NOTIFICATION_LIGHT_PULSE_CUSTOM_ENABLE, 0) == 1;
        if (!customEnabled) {
            note.defaults |= Notification.DEFAULT_LIGHTS;
            return;
        }

        String timeOnKey, timeOffKey, colorKey;
        if (notificationType == VOICEMAIL_NOTIFICATION) {
            colorKey = Settings.System.NOTIFICATION_LIGHT_PULSE_VMAIL_COLOR;
            timeOnKey = Settings.System.NOTIFICATION_LIGHT_PULSE_VMAIL_LED_ON;
            timeOffKey = Settings.System.NOTIFICATION_LIGHT_PULSE_VMAIL_LED_OFF;
        } else { // MISSED_CALL_NOTIFICATION
            colorKey = Settings.System.NOTIFICATION_LIGHT_PULSE_CALL_COLOR;
            timeOnKey = Settings.System.NOTIFICATION_LIGHT_PULSE_CALL_LED_ON;
            timeOffKey = Settings.System.NOTIFICATION_LIGHT_PULSE_CALL_LED_OFF;
        }

        note.ledARGB = Settings.System.getInt(resolver, colorKey, DEFAULT_COLOR);
        note.ledOnMS = Settings.System.getInt(resolver, timeOnKey, DEFAULT_TIME);
        note.ledOffMS = Settings.System.getInt(resolver, timeOffKey, DEFAULT_TIME);
     }

    /**
     * Displays a notification about a missed call.
     *
     * @param name the contact name.
     * @param number the phone number. Note that this may be a non-callable String like "Unknown",
     * or "Private Number", which possibly come from methods like
     * {@link PhoneUtils#modifyForSpecialCnapCases(Context, CallerInfo, String, int)}.
     * @param type the type of the call. {@link android.provider.CallLog.Calls#INCOMING_TYPE}
     * {@link android.provider.CallLog.Calls#OUTGOING_TYPE}, or
     * {@link android.provider.CallLog.Calls#MISSED_TYPE}
     * @param photo picture which may be used for the notification (when photoIcon is null).
     * This also can be null when the picture itself isn't available. If photoIcon is available
     * it should be prioritized (because this may be too huge for notification).
     * See also {@link ContactsAsyncHelper}.
     * @param photoIcon picture which should be used for the notification. Can be null. This is
     * the most suitable for {@link android.app.Notification.Builder#setLargeIcon(Bitmap)}, this
     * should be used when non-null.
     * @param date the time when the missed call happened
     */
    /* package */ void notifyMissedCall(String name, String number, int presentation, String type,
            Drawable photo, Bitmap photoIcon, long date) {

        // When the user clicks this notification, we go to the call log.
        final PendingIntent pendingCallLogIntent = PhoneGlobals.createPendingCallLogIntent(
                mContext);

        // Never display the missed call notification on non-voice-capable
        // devices, even if the device does somehow manage to get an
        // incoming call.
        if (!PhoneGlobals.sVoiceCapable) {
            if (DBG) log("notifyMissedCall: non-voice-capable device, not posting notification");
            return;
        }

        if (VDBG) {
            log("notifyMissedCall(). name: " + name + ", number: " + number
                + ", label: " + type + ", photo: " + photo + ", photoIcon: " + photoIcon
                + ", date: " + date);
        }

        // get the name for the ticker text
        // i.e. "Missed call from <caller name or number>"
        String callName;
        if (name != null && TextUtils.isGraphic(name)) {
            callName = name;
        } else if (!TextUtils.isEmpty(number)) {
            final BidiFormatter bidiFormatter = BidiFormatter.getInstance();
            // A number should always be displayed LTR using {@link BidiFormatter}
            // regardless of the content of the rest of the notification.
            callName = bidiFormatter.unicodeWrap(number, TextDirectionHeuristics.LTR);
        } else {
            // use "unknown" if the caller is unidentifiable.
            callName = mContext.getString(R.string.unknown);
        }

        // keep track of the call, keeping list sorted from newest to oldest
        mMissedCalls.add(0, new MissedCallInfo(callName, number, date));

        Notification.Builder builder = new Notification.Builder(mContext);
        builder.setSmallIcon(android.R.drawable.stat_notify_missed_call)
                .setTicker(mContext.getString(R.string.notification_missedCallTicker, callName))
                .setWhen(date)
                .setContentIntent(pendingCallLogIntent)
                .setAutoCancel(true)
                .setDeleteIntent(createClearMissedCallsIntent());

        // display the first line of the notification:
        // 1 missed call: call name
        // more than 1 missed call: <number of calls> + "missed calls" (+ list of calls)
        int numberOfMissedCalls = mMissedCalls.size();
        if (numberOfMissedCalls == 1) {
            builder.setContentTitle(mContext.getText(R.string.notification_missedCallTitle));
            builder.setContentText(callName);
        } else {
            String message = mContext.getString(R.string.notification_missedCallsMsg,
                    numberOfMissedCalls);

            builder.setContentTitle(mContext.getText(R.string.notification_missedCallsTitle));
            builder.setContentText(message);
            builder.setNumber(numberOfMissedCalls);

            Notification.InboxStyle style = new Notification.InboxStyle(builder);

            for (MissedCallInfo info : mMissedCalls) {
                style.addLine(formatSingleCallLine(info.name, info.date));

                // only keep number if equal for all calls in order to hide actions
                // if the calls came from different numbers
                if (!TextUtils.equals(number, info.number)) {
                    number = null;
                }
            }
            style.setBigContentTitle(message);
            style.setSummaryText(" ");
            builder.setStyle(style);
        }

        // Simple workaround for issue 6476275; refrain having actions when the given number seems
        // not a real one but a non-number which was embedded by methods outside (like
        // PhoneUtils#modifyForSpecialCnapCases()).
        // TODO: consider removing equals() checks here, and modify callers of this method instead.
        if (!TextUtils.isEmpty(number)
                && (presentation == PhoneConstants.PRESENTATION_ALLOWED ||
                        presentation == PhoneConstants.PRESENTATION_PAYPHONE)) {
            if (DBG) log("Add actions with the number " + number);

            builder.addAction(R.drawable.stat_sys_phone_call,
                    mContext.getString(R.string.notification_missedCall_call_back),
                    PhoneGlobals.getCallBackPendingIntent(mContext, number));

            builder.addAction(R.drawable.ic_text_holo_dark,
                    mContext.getString(R.string.notification_missedCall_message),
                    PhoneGlobals.getSendSmsFromNotificationPendingIntent(mContext, number));

            if (photoIcon != null) {
                builder.setLargeIcon(photoIcon);
            } else if (photo instanceof BitmapDrawable) {
                builder.setLargeIcon(((BitmapDrawable) photo).getBitmap());
            }
        } else {
            if (DBG) {
                log("Suppress actions. number: " + number + ", missedCalls: " + mMissedCalls.size());
            }
        }

        Notification notification = builder.build();
        configureLedNotification(mContext, MISSED_CALL_NOTIFICATION, notification);
        mNotificationManager.notify(MISSED_CALL_NOTIFICATION, notification);
    }

    private static final RelativeSizeSpan TIME_SPAN = new RelativeSizeSpan(0.7f);

    private CharSequence formatSingleCallLine(String caller, long date) {
        int flags = DateUtils.FORMAT_SHOW_TIME;
        if (!DateUtils.isToday(date)) {
            flags |= DateUtils.FORMAT_SHOW_WEEKDAY;
        }

        SpannableStringBuilder lineBuilder = new SpannableStringBuilder();
        lineBuilder.append(caller);
        lineBuilder.append("  ");

        int timeIndex = lineBuilder.length();
        lineBuilder.append(DateUtils.formatDateTime(mContext, date, flags));
        lineBuilder.setSpan(TIME_SPAN, timeIndex, lineBuilder.length(), 0);

        return lineBuilder;
    }

    /** Returns an intent to be invoked when the missed call notification is cleared. */
    private PendingIntent createClearMissedCallsIntent() {
        Intent intent = new Intent(mContext, ClearMissedCallsService.class);
        intent.setAction(ClearMissedCallsService.ACTION_CLEAR_MISSED_CALLS);
        return PendingIntent.getService(mContext, 0, intent, 0);
    }

    /**
     * Cancels the "missed call" notification.
     *
     * @see ITelephony.cancelMissedCallsNotification()
     */
    void cancelMissedCallNotification() {
        // reset the list of missed calls
        mMissedCalls.clear();
        mNumberMissedVideoCalls = 0;
        mNotificationManager.cancel(MISSED_CALL_NOTIFICATION);
        mNotificationManager.cancel(MISSED_VIDEOCALL_NOTIFICATION);
    }

    /**
     * Displays a notification about a missed video call.
     */
    void notifyMissedVideoCall(String name, String number, String label, long date) {
        // When the user clicks this notification, we go to the call log.
        final Intent callLogIntent = PhoneGlobals.createCallLogIntent();

        // title resource id
        int titleResId;
        // the text in the notification's line 1 and 2.
        String expandedText, callName;

        // increment number of missed calls.
        mNumberMissedVideoCalls++;

        // get the name for the ticker text
        // i.e. "Missed call from <caller name or number>"
        if (name != null && TextUtils.isGraphic(name)) {
            callName = name;
        } else if (!TextUtils.isEmpty(number)) {
            callName = number;
        } else {
            // use "unknown" if the caller is unidentifiable.
            callName = mContext.getString(R.string.unknown);
        }

        // display the first line of the notification:
        // 1 missed call: call name
        // more than 1 missed call: <number of calls> + "missed calls"
        if (mNumberMissedVideoCalls == 1) {
            titleResId = R.string.notification_missedVideoCallTitle;
            expandedText = callName;
        } else {
            titleResId = R.string.notification_missedVideoCallsTitle;
            expandedText = mContext.getString(R.string.notification_missedCallsMsg,
                    mNumberMissedVideoCalls);
        }

        // make the notification
        Notification note = new Notification(
                android.R.drawable.stat_notify_missed_call,
                mContext.getString(R.string.notification_missedVideoCallTicker, callName),
                date
                );
        note.setLatestEventInfo(mContext, mContext.getText(titleResId), expandedText,
                PendingIntent.getActivity(mContext, 0, callLogIntent, 0));
        note.flags |= Notification.FLAG_AUTO_CANCEL;
        // This intent will be called when the notification is dismissed.
        // It will take care of clearing the list of missed calls.
        note.deleteIntent = createClearMissedVideoCallsIntent();

        configureLedNotification(mContext, VOICEMAIL_NOTIFICATION, note);
        mNotificationManager.notify(MISSED_VIDEOCALL_NOTIFICATION, note);
    }

    /** Returns an intent to be invoked when the missed call notification is cleared. */
    private PendingIntent createClearMissedVideoCallsIntent() {
        Intent intent = new Intent(mContext, ClearMissedCallsService.class);
        intent.setAction(ClearMissedCallsService.ACTION_CLEAR_MISSED_CALLS);
        return PendingIntent.getService(mContext, 0, intent, 0);
    }

    /* package */ void notifyBlacklistedCall(String number, long date, int matchType) {
        notifyBlacklistedItem(number, date, matchType, BLACKLISTED_CALL_NOTIFICATION);
    }

    /* package */ void notifyBlacklistedMessage(String number, long date, int matchType) {
        notifyBlacklistedItem(number, date, matchType, BLACKLISTED_MESSAGE_NOTIFICATION);
    }

    private void notifyBlacklistedItem(String number, long date,
            int matchType, int notificationId) {
        if (!BlacklistUtils.isBlacklistNotifyEnabled(mContext)) {
            return;
        }

        if (VDBG) {
            log("notifyBlacklistedItem(). number: " + number
                + ", match type: " + matchType + ", date: " + date + ", type: " + notificationId);
        }

        ArrayList<BlacklistedItemInfo> items = notificationId == BLACKLISTED_CALL_NOTIFICATION
                ? mBlacklistedCalls : mBlacklistedMessages;
        PendingIntent clearIntent = notificationId == BLACKLISTED_CALL_NOTIFICATION
                ? createClearBlacklistedCallsIntent() : createClearBlacklistedMessagesIntent();
        int iconDrawableResId = notificationId == BLACKLISTED_CALL_NOTIFICATION
                ? R.drawable.ic_block_contact_holo_dark : R.drawable.ic_block_message_holo_dark;

        // Keep track of the call/message, keeping list sorted from newest to oldest
        items.add(0, new BlacklistedItemInfo(number, date, matchType));

        // Get the intent to open Blacklist settings if user taps on content ready
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName("com.android.settings",
                "com.android.settings.Settings$BlacklistSettingsActivity");
        PendingIntent blSettingsIntent = PendingIntent.getActivity(mContext, 0, intent, 0);

        // Start building the notification
        Notification.Builder builder = new Notification.Builder(mContext);
        builder.setSmallIcon(iconDrawableResId)
                .setContentIntent(blSettingsIntent)
                .setAutoCancel(true)
                .setContentTitle(mContext.getString(R.string.blacklist_title))
                .setWhen(date)
                .setDeleteIntent(clearIntent);

        // Add the 'Remove block' notification action only for MATCH_LIST items since
        // MATCH_REGEX and MATCH_PRIVATE items does not have an associated specific number
        // to unblock, and MATCH_UNKNOWN unblock for a single number does not make sense.
        boolean addUnblockAction = true;

        if (items.size() == 1) {
            int messageResId;

            switch (matchType) {
                case BlacklistUtils.MATCH_PRIVATE:
                    messageResId = notificationId == BLACKLISTED_CALL_NOTIFICATION
                            ? R.string.blacklist_call_notification_private_number
                            : R.string.blacklist_message_notification_private_number;
                    break;
                case BlacklistUtils.MATCH_UNKNOWN:
                    messageResId = notificationId == BLACKLISTED_CALL_NOTIFICATION
                            ? R.string.blacklist_call_notification_unknown_number
                            : R.string.blacklist_message_notification_unknown_number;
                    break;
                default:
                    messageResId = notificationId == BLACKLISTED_CALL_NOTIFICATION
                            ? R.string.blacklist_call_notification
                            : R.string.blacklist_message_notification;
                    break;
            }
            builder.setContentText(mContext.getString(messageResId, number));

            if (matchType != BlacklistUtils.MATCH_LIST) {
                addUnblockAction = false;
            }
        } else {
            int messageResId = notificationId == BLACKLISTED_CALL_NOTIFICATION
                    ? R.string.blacklist_call_notification_multiple
                    : R.string.blacklist_message_notification_multiple;
            String message = mContext.getString(messageResId, items.size());

            builder.setContentText(message);
            builder.setNumber(items.size());

            Notification.InboxStyle style = new Notification.InboxStyle(builder);

            for (BlacklistedItemInfo info : items) {
                // Takes care of displaying "Private" instead of an empty string
                String numberString = TextUtils.isEmpty(info.number)
                        ? mContext.getString(R.string.blacklist_notification_list_private)
                        : info.number;
                style.addLine(formatSingleCallLine(numberString, info.date));

                if (!TextUtils.equals(number, info.number)) {
                    addUnblockAction = false;
                } else if (info.matchType != BlacklistUtils.MATCH_LIST) {
                    addUnblockAction = false;
                }
            }
            style.setBigContentTitle(message);
            style.setSummaryText(" ");
            builder.setStyle(style);
        }

        if (addUnblockAction) {
            int actionDrawableResId = notificationId == BLACKLISTED_CALL_NOTIFICATION
                    ? R.drawable.ic_unblock_contact_holo_dark
                    : R.drawable.ic_unblock_message_holo_dark;
            int unblockType = notificationId == BLACKLISTED_CALL_NOTIFICATION
                    ? BlacklistUtils.BLOCK_CALLS : BlacklistUtils.BLOCK_MESSAGES;
            PendingIntent action = PhoneGlobals.getUnblockNumberFromNotificationPendingIntent(
                    mContext, number, unblockType);

            builder.addAction(actionDrawableResId,
                    mContext.getString(R.string.unblock_number), action);
        }

        mNotificationManager.notify(notificationId, builder.getNotification());
    }

    private PendingIntent createClearBlacklistedCallsIntent() {
        Intent intent = new Intent(mContext, ClearMissedCallsService.class);
        intent.setAction(ClearMissedCallsService.ACTION_CLEAR_BLACKLISTED_CALLS);
        return PendingIntent.getService(mContext, 0, intent, 0);
    }

    private PendingIntent createClearBlacklistedMessagesIntent() {
        Intent intent = new Intent(mContext, ClearMissedCallsService.class);
        intent.setAction(ClearMissedCallsService.ACTION_CLEAR_BLACKLISTED_MESSAGES);
        return PendingIntent.getService(mContext, 0, intent, 0);
    }

    void cancelBlacklistedNotification(int type) {
        if ((type & BlacklistUtils.BLOCK_CALLS) != 0) {
            mBlacklistedCalls.clear();
            mNotificationManager.cancel(BLACKLISTED_CALL_NOTIFICATION);
        }
        if ((type & BlacklistUtils.BLOCK_MESSAGES) != 0) {
            mBlacklistedMessages.clear();
            mNotificationManager.cancel(BLACKLISTED_MESSAGE_NOTIFICATION);
        }
    }

    private void notifySpeakerphone() {
        if (!mShowingSpeakerphoneIcon) {
            mStatusBarManager.setIcon("speakerphone", android.R.drawable.stat_sys_speakerphone, 0,
                    mContext.getString(R.string.accessibility_speakerphone_enabled));
            mShowingSpeakerphoneIcon = true;
        }
    }

    private void cancelSpeakerphone() {
        if (mShowingSpeakerphoneIcon) {
            mStatusBarManager.removeIcon("speakerphone");
            mShowingSpeakerphoneIcon = false;
        }
    }

    /**
     * Shows or hides the "speakerphone" notification in the status bar,
     * based on the actual current state of the speaker.
     *
     * If you already know the current speaker state (e.g. if you just
     * called AudioManager.setSpeakerphoneOn() yourself) then you should
     * directly call {@link #updateSpeakerNotification(boolean)} instead.
     *
     * (But note that the status bar icon is *never* shown while the in-call UI
     * is active; it only appears if you bail out to some other activity.)
     */
    protected void updateSpeakerNotification() {
        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        boolean showNotification =
                (mCM.getState() == PhoneConstants.State.OFFHOOK) && audioManager.isSpeakerphoneOn();

        if (DBG) log(showNotification
                     ? "updateSpeakerNotification: speaker ON"
                     : "updateSpeakerNotification: speaker OFF (or not offhook)");

        updateSpeakerNotification(showNotification);
    }

    /**
     * Shows or hides the "speakerphone" notification in the status bar.
     *
     * @param showNotification if true, call notifySpeakerphone();
     *                         if false, call cancelSpeakerphone().
     *
     * Use {@link updateSpeakerNotification()} to update the status bar
     * based on the actual current state of the speaker.
     *
     * (But note that the status bar icon is *never* shown while the in-call UI
     * is active; it only appears if you bail out to some other activity.)
     */
    public void updateSpeakerNotification(boolean showNotification) {
        if (DBG) log("updateSpeakerNotification(" + showNotification + ")...");

        // Regardless of the value of the showNotification param, suppress
        // the status bar icon if the the InCallScreen is the foreground
        // activity, since the in-call UI already provides an onscreen
        // indication of the speaker state.  (This reduces clutter in the
        // status bar.)

        if (showNotification) {
            notifySpeakerphone();
        } else {
            cancelSpeakerphone();
        }
    }

    protected void notifyMute() {
        if (!mShowingMuteIcon) {
            mStatusBarManager.setIcon("mute", android.R.drawable.stat_notify_call_mute, 0,
                    mContext.getString(R.string.accessibility_call_muted));
            mShowingMuteIcon = true;
        }
    }

    protected void cancelMute() {
        if (mShowingMuteIcon) {
            mStatusBarManager.removeIcon("mute");
            mShowingMuteIcon = false;
        }
    }

    /**
     * Shows or hides the "mute" notification in the status bar,
     * based on the current mute state of the Phone.
     *
     * (But note that the status bar icon is *never* shown while the in-call UI
     * is active; it only appears if you bail out to some other activity.)
     */
    void updateMuteNotification() {
        // Suppress the status bar icon if the the InCallScreen is the
        // foreground activity, since the in-call UI already provides an
        // onscreen indication of the mute state.  (This reduces clutter
        // in the status bar.)

        if ((mCM.getState() == PhoneConstants.State.OFFHOOK) && PhoneUtils.getMute()) {
            if (DBG) log("updateMuteNotification: MUTED");
            notifyMute();
        } else {
            if (DBG) log("updateMuteNotification: not muted (or not offhook)");
            cancelMute();
        }
    }

    /**
     * Completely take down the in-call notification *and* the mute/speaker
     * notifications as well, to indicate that the phone is now idle.
     */
    /* package */ void cancelCallInProgressNotifications() {
        if (DBG) log("cancelCallInProgressNotifications");
        cancelMute();
        cancelSpeakerphone();
    }

    /**
     * Updates the message waiting indicator (voicemail) notification.
     *
     * @param visible true if there are messages waiting
     */
    /* package */ void updateMwi(boolean visible) {
        if (DBG) log("updateMwi(): " + visible);

        if (visible) {
            int resId = android.R.drawable.stat_notify_voicemail;

            // This Notification can get a lot fancier once we have more
            // information about the current voicemail messages.
            // (For example, the current voicemail system can't tell
            // us the caller-id or timestamp of a message, or tell us the
            // message count.)

            // But for now, the UI is ultra-simple: if the MWI indication
            // is supposed to be visible, just show a single generic
            // notification.

            String notificationTitle = mContext.getString(R.string.notification_voicemail_title);
            String vmNumber = mPhone.getVoiceMailNumber();
            if (DBG) log("- got vm number: '" + vmNumber + "'");

            // Watch out: vmNumber may be null, for two possible reasons:
            //
            //   (1) This phone really has no voicemail number
            //
            //   (2) This phone *does* have a voicemail number, but
            //       the SIM isn't ready yet.
            //
            // Case (2) *does* happen in practice if you have voicemail
            // messages when the device first boots: we get an MWI
            // notification as soon as we register on the network, but the
            // SIM hasn't finished loading yet.
            //
            // So handle case (2) by retrying the lookup after a short
            // delay.

            if ((vmNumber == null) && !mPhone.getIccRecordsLoaded()) {
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
                    mApp.notifier.sendMwiChangedDelayed(VM_NUMBER_RETRY_DELAY_MILLIS);
                    return;
                } else {
                    Log.w(LOG_TAG, "NotificationMgr.updateMwi: getVoiceMailNumber() failed after "
                          + MAX_VM_NUMBER_RETRIES + " retries; giving up.");
                    // ...and continue with vmNumber==null, just as if the
                    // phone had no VM number set up in the first place.
                }
            }

            if (TelephonyCapabilities.supportsVoiceMessageCount(mPhone)) {
                int vmCount = mPhone.getVoiceMessageCount();
                String titleFormat = mContext.getString(R.string.notification_voicemail_title_count);
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

            CallFeaturesSetting.migrateVoicemailVibrationSettingsIfNeeded(prefs);
            final boolean vibrate = prefs.getBoolean(
                    CallFeaturesSetting.BUTTON_VOICEMAIL_NOTIFICATION_VIBRATE_KEY, false);
            if (vibrate) {
                notification.defaults |= Notification.DEFAULT_VIBRATE;
            }
            configureLedNotification(mContext, VOICEMAIL_NOTIFICATION, notification);
            mNotificationManager.notify(VOICEMAIL_NOTIFICATION, notification);
        } else {
            mNotificationManager.cancel(VOICEMAIL_NOTIFICATION);
        }
    }

    /**
     * Updates the message call forwarding indicator notification.
     *
     * @param visible true if there are messages waiting
     */
    /* package */ void updateCfi(boolean visible) {
        if (DBG) log("updateCfi(): " + visible);
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

            Notification notification;
            final boolean showExpandedNotification = true;
            if (showExpandedNotification) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setClassName("com.android.phone",
                        "com.android.phone.CallFeaturesSetting");

                notification = new Notification(
                        R.drawable.stat_sys_phone_call_forward,  // icon
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
                        R.drawable.stat_sys_phone_call_forward,  // icon
                        null,  // tickerText
                        System.currentTimeMillis()  // when
                        );
            }

            notification.flags |= Notification.FLAG_ONGOING_EVENT;  // also implies FLAG_NO_CLEAR

            mNotificationManager.notify(
                    CALL_FORWARD_NOTIFICATION,
                    notification);
        } else {
            mNotificationManager.cancel(CALL_FORWARD_NOTIFICATION);
        }
    }

    /**
     * Shows the "data disconnected due to roaming" notification, which
     * appears when you lose data connectivity because you're roaming and
     * you have the "data roaming" feature turned off.
     */
    /* package */ void showDataDisconnectedRoaming() {
        if (DBG) log("showDataDisconnectedRoaming()...");

        // "Mobile network settings" screen / dialog
        Intent intent = new Intent(mContext, com.android.phone.MobileNetworkSettings.class);

        final CharSequence contentText = mContext.getText(R.string.roaming_reenable_message);

        final Notification.Builder builder = new Notification.Builder(mContext);
        builder.setSmallIcon(android.R.drawable.stat_sys_warning);
        builder.setContentTitle(mContext.getText(R.string.roaming));
        builder.setContentText(contentText);
        builder.setContentIntent(PendingIntent.getActivity(mContext, 0, intent, 0));

        final Notification notif = new Notification.BigTextStyle(builder).bigText(contentText)
                .build();

        mNotificationManager.notify(DATA_DISCONNECTED_ROAMING_NOTIFICATION, notif);
    }

    /**
     * Turns off the "data disconnected due to roaming" notification.
     */
    /* package */ void hideDataDisconnectedRoaming() {
        if (DBG) log("hideDataDisconnectedRoaming()...");
        mNotificationManager.cancel(DATA_DISCONNECTED_ROAMING_NOTIFICATION);
    }

    /**
     * Display the network selection "no service" notification
     * @param operator is the numeric operator number
     */
    private void showNetworkSelection(String operator) {
        if (DBG) log("showNetworkSelection(" + operator + ")...");

        String titleText = mContext.getString(
                R.string.notification_network_selection_title);
        String expandedText = mContext.getString(
                R.string.notification_network_selection_text, operator);

        Notification notification = new Notification();
        notification.icon = android.R.drawable.stat_sys_warning;
        notification.when = 0;
        notification.flags = Notification.FLAG_ONGOING_EVENT;
        notification.tickerText = null;

        // create the target network operators settings intent
        Intent intent;
        if (isAppInstalled("org.codeaurora.settings.NETWORK_OPERATOR_SETTINGS_ASYNC")) {
            intent = new Intent("org.codeaurora.settings.NETWORK_OPERATOR_SETTINGS_ASYNC");
        } else {
            intent = new Intent(Intent.ACTION_MAIN);
            // Use NetworkSetting to handle the selection intent
            intent.setComponent(new ComponentName("com.android.phone",
                    "com.android.phone.NetworkSetting"));
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);

        notification.setLatestEventInfo(mContext, titleText, expandedText, pi);

        mNotificationManager.notify(SELECTED_OPERATOR_FAIL_NOTIFICATION, notification);
    }

    /**
     * Check whether the target handler exist in system
     */
    private boolean isAppInstalled(String action) {
        boolean installed = false;
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> list = pm.queryIntentActivities(new Intent(action), 0);
        int listSize = list.size();
        for (int i = 0; i < listSize; i++) {
            ResolveInfo resolveInfo = list.get(i);
            if ((resolveInfo.activityInfo.applicationInfo.flags &
                    ApplicationInfo.FLAG_SYSTEM) != 0) {
                installed = true;
                break;
            }
        }
        return installed;
    }

    /**
     * Turn off the network selection "no service" notification
     */
    private void cancelNetworkSelection() {
        if (DBG) log("cancelNetworkSelection()...");
        mNotificationManager.cancel(SELECTED_OPERATOR_FAIL_NOTIFICATION);
    }

    /**
     * Update notification about no service of user selected operator
     *
     * @param serviceState Phone service state
     */
    void updateNetworkSelection(int serviceState, Phone phone) {
        if (TelephonyCapabilities.supportsNetworkSelection(phone)) {
            // get the shared preference of network_selection.
            // empty is auto mode, otherwise it is the operator alpha name
            // in case there is no operator name, check the operator numeric
            SharedPreferences sp =
                    PreferenceManager.getDefaultSharedPreferences(mContext);
            String networkSelection =
                    sp.getString(PhoneBase.NETWORK_SELECTION_NAME_KEY, "");
            if (TextUtils.isEmpty(networkSelection)) {
                networkSelection =
                        sp.getString(PhoneBase.NETWORK_SELECTION_KEY, "");
            }

            if (DBG) log("updateNetworkSelection()..." + "state = " +
                    serviceState + " new network " + networkSelection);

            if (serviceState == ServiceState.STATE_OUT_OF_SERVICE
                    && !TextUtils.isEmpty(networkSelection)) {
                if (!mSelectedUnavailableNotify) {
                    showNetworkSelection(networkSelection);
                    mSelectedUnavailableNotify = true;
                }
            } else {
                if (mSelectedUnavailableNotify) {
                    cancelNetworkSelection();
                    mSelectedUnavailableNotify = false;
                }
            }
        }
    }

    /* package */ void postTransientNotification(int notifyId, CharSequence msg) {
        if (mToast != null) {
            mToast.cancel();
        }

        mToast = Toast.makeText(mContext, msg, Toast.LENGTH_LONG);
        mToast.show();
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
