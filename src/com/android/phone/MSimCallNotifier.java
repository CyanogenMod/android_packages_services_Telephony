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

import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.CallLog.Calls;
import android.provider.Settings;
import android.telephony.MSimTelephonyManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.EventLog;
import android.util.Log;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.cdma.CdmaCallWaitingNotification;
import com.android.internal.telephony.cdma.CdmaInformationRecords.CdmaSignalInfoRec;
import com.android.internal.telephony.cdma.SignalToneUtil;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.MSimConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.TelephonyCapabilities;
import com.android.internal.telephony.gsm.SuppServiceNotification;

/**
 * Phone app module that listens for phone state changes and various other
 * events from the telephony layer, and triggers any resulting UI behavior
 * (like starting the Ringer and Incoming Call UI, playing in-call tones,
 * updating notifications, writing call log entries, etc.)
 */
public class MSimCallNotifier extends CallNotifier {
    private static final String LOG_TAG = "MSimCallNotifier";
    private static final boolean DBG =
            (MSimPhoneGlobals.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (MSimPhoneGlobals.DBG_LEVEL >= 2);

    private static final int PHONE_START_MSIM_INCALL_TONE = 55;
    private static final int LCH_PLAY_DTMF = 56;
    private static final int LCH_STOP_DTMF = 57;
    private static final int LCH_DTMF_PERIODICITY = 3000;
    private static final int LCH_DTMF_PERIOD = 500;

    private static final String XDIVERT_STATUS = "xdivert_status_key";

    private int mLchSub = MSimConstants.INVALID_SUBSCRIPTION;

    private InCallTonePlayer mLocalCallReminderTonePlayer = null;
    private InCallTonePlayer mSupervisoryCallHoldTonePlayer = null;
    private InCallTonePlayer mLocalCallWaitingTonePlayer = null;

    private static final boolean sLocalCallHoldToneEnabled =
            SystemProperties.getBoolean("persist.radio.lch_inband_tone", false);

    private boolean[] mIsPermDiscCauseReceived = new
            boolean[MSimTelephonyManager.getDefault().getPhoneCount()];

    private Phone mPhone;

    /**
     * Initialize the singleton CallNotifier instance.
     * This is only done once, at startup, from PhoneApp.onCreate().
     */
    /* package */ static CallNotifier init(MSimPhoneGlobals app, Phone phone, Ringer ringer,
                          CallLogger callLogger, CallStateMonitor callStateMonitor,
                          BluetoothManager bluetoothManager, CallModeler callModeler) {
        synchronized (MSimCallNotifier.class) {
            if (sInstance == null) {
                sInstance = new MSimCallNotifier(app, phone, ringer, callLogger, callStateMonitor,
                        bluetoothManager, callModeler);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return (MSimCallNotifier) sInstance;
        }
    }

    /** Private constructor; @see init() */
    protected MSimCallNotifier(MSimPhoneGlobals app, Phone phone, Ringer ringer,
            CallLogger callLogger,CallStateMonitor callStateMonitor,
            BluetoothManager bluetoothManager, CallModeler callModeler) {
        super(app, phone, ringer, callLogger, callStateMonitor, bluetoothManager, callModeler);
        mPhone = phone;
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case CallStateMonitor.PHONE_INCOMING_RING:
                // repeat the ring when requested by the RIL, when the user has NOT
                // specifically requested silence and when there in no active call
                // on other subscription.
                if (msg.obj != null && ((AsyncResult) msg.obj).result != null) {
                    PhoneBase pb =  (PhoneBase)((AsyncResult)msg.obj).result;

                    if ((pb.getState() == PhoneConstants.State.RINGING)
                            && (mSilentRingerRequested == false)
                            && !mCM.hasActiveFgCallAnyPhone()) {
                        if (DBG) log("RINGING... (PHONE_INCOMING_RING event)");
                        mRinger.ring();
                    } else {
                        if (DBG) log("Skipping generating Ring tone, state = " + pb.getState()
                                + " silence requested = " + mSilentRingerRequested);
                    }
                }
                break;

            case PHONE_MWI_CHANGED:
                Phone phone = (Phone)msg.obj;
                onMwiChanged(mApplication.phone.getMessageWaitingIndicator(), phone);
                break;

            case CallStateMonitor.PHONE_ACTIVE_SUBSCRIPTION_CHANGE:
                if (DBG) log("PHONE_ACTIVE_SUBSCRIPTION_CHANGE...");
                AsyncResult r = (AsyncResult) msg.obj;
                log(" Change in subscription " + (Integer) r.result);
                break;

            case PHONE_START_MSIM_INCALL_TONE:
                if (DBG) log("PHONE_START_MSIM_INCALL_TONE...");
                startMSimInCallTones();
                break;
            case LCH_PLAY_DTMF:
                playLchDtmf();
                break;
            case LCH_STOP_DTMF:
                stopLchDtmf();
                break;
            default:
                super.handleMessage(msg);
        }
    }

    @Override
    protected void listen() {
        MSimTelephonyManager telephonyManager = (MSimTelephonyManager)mApplication.
                getSystemService(Context.MSIM_TELEPHONY_SERVICE);
        for (int i = 0; i < MSimTelephonyManager.getDefault().getPhoneCount(); i++) {
            telephonyManager.listen(getPhoneStateListener(i),
                    PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR
                    | PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR);
        }
    }

    private void onMwiChanged(boolean visible, Phone phone) {
        if (VDBG) log("onMwiChanged(): " + visible);

        // "Voicemail" is meaningless on non-voice-capable devices,
        // so ignore MWI events.
        if (!PhoneGlobals.sVoiceCapable) {
            // ...but still log a warning, since we shouldn't have gotten this
            // event in the first place!
            // (PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR events
            // *should* be blocked at the telephony layer on non-voice-capable
            // capable devices.)
            Log.w(LOG_TAG, "Got onMwiChanged() on non-voice-capable device! Ignoring...");
            return;
        }

        ((MSimNotificationMgr)mApplication.notificationMgr).updateMwi(visible, phone);
    }

    /**
     * Posts a delayed PHONE_MWI_CHANGED event, to schedule a "retry" for a
     * failed NotificationMgr.updateMwi() call.
     */
    /* package */
    void sendMwiChangedDelayed(long delayMillis, Phone phone) {
        Message message = Message.obtain(this, PHONE_MWI_CHANGED, phone);
        sendMessageDelayed(message, delayMillis);
    }

    protected void onCfiChanged(boolean visible, int subscription) {
        if (VDBG) log("onCfiChanged(): " + visible + " sub: " + subscription);
        ((MSimNotificationMgr)mApplication.notificationMgr).updateCfi(visible, subscription);
    }

    protected void onXDivertChanged(boolean visible) {
        if (VDBG) log("onXDivertChanged(): " + visible);
        ((MSimNotificationMgr)mApplication.notificationMgr).updateXDivert(visible);
    }

    // Gets the XDivert Status from shared preference.
    protected boolean getXDivertStatus() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                mApplication);
        boolean status = sp.getBoolean(XDIVERT_STATUS, false);
        Log.d(LOG_TAG, "getXDivertStatus status = " + status);
        return status;
    }

    // Sets the XDivert Status to shared preference.
    protected void setXDivertStatus(boolean status) {
        Log.d(LOG_TAG, "setXDivertStatus status = " + status);
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                mApplication);
        SharedPreferences.Editor editor = sp.edit();
        editor.putBoolean(XDIVERT_STATUS, status);
        editor.apply();
    }

    private PhoneStateListener getPhoneStateListener(int sub) {
        Log.d(LOG_TAG, "getPhoneStateListener: SUBSCRIPTION == " + sub);

        PhoneStateListener phoneStateListener = new PhoneStateListener(sub) {
            @Override
            public void onMessageWaitingIndicatorChanged(boolean mwi) {
                // mSubscription is a data member of PhoneStateListener class.
                // Each subscription is associated with one PhoneStateListener.
                onMwiChanged(mwi, PhoneGlobals.getInstance().getPhone(mSubscription));
            }

            @Override
            public void onCallForwardingIndicatorChanged(boolean cfi) {
                onCfiChanged(cfi, mSubscription);
            }
        };
        return phoneStateListener;
    }

    @Override
    protected void onNewRingingConnection(AsyncResult r) {
        Connection c = (Connection) r.result;
        int subscription = c.getCall().getPhone().getSubscription();

        log("onNewRingingConnection(): state = " + mCM.getState() + ", conn = { " + c + " }" +
                " subscription = " + subscription);
        Call ringing = c.getCall();
        Phone phone = ringing.getPhone();

        PhoneUtils.maybeShowOrHideUssdDialog(false);
        // Check for a few cases where we totally ignore incoming calls.
        if (ignoreAllIncomingCalls(phone)||MSimPhoneGlobals.getInstance().isCsvtActive()) {
            // Immediately reject the call, without even indicating to the user
            // that an incoming call occurred.  (This will generally send the
            // caller straight to voicemail, just as if we *had* shown the
            // incoming-call UI and the user had declined the call.)
            PhoneUtils.hangupRingingCall(ringing);
            return;
        }

        if (!c.isRinging()) {
            Log.i(LOG_TAG, "CallNotifier.onNewRingingConnection(): connection not ringing!");
            // This is a very strange case: an incoming call that stopped
            // ringing almost instantly after the onNewRingingConnection()
            // event.  There's nothing we can do here, so just bail out
            // without doing anything.  (But presumably we'll log it in
            // the call log when the disconnect event comes in...)
            return;
        }

        // Check if phone number is blacklisted
        if (isConnectionBlacklisted(c)) {
            return;
        }

        // Stop any signalInfo tone being played on receiving a Call
        stopSignalInfoTone();

        Call.State state = c.getState();
        // State will be either INCOMING or WAITING.
        if (VDBG) log("- connection is ringing!  state = " + state);
        // if (DBG) PhoneUtils.dumpCallState(mPhone);

        // No need to do any service state checks here (like for
        // "emergency mode"), since in those states the SIM won't let
        // us get incoming connections in the first place.

        // TODO: Consider sending out a serialized broadcast Intent here
        // (maybe "ACTION_NEW_INCOMING_CALL"), *before* starting the
        // ringer and going to the in-call UI.  The intent should contain
        // the caller-id info for the current connection, and say whether
        // it would be a "call waiting" call or a regular ringing call.
        // If anybody consumed the broadcast, we'd bail out without
        // ringing or bringing up the in-call UI.
        //
        // This would give 3rd party apps a chance to listen for (and
        // intercept) new ringing connections.  An app could reject the
        // incoming call by consuming the broadcast and doing nothing, or
        // it could "pick up" the call (without any action by the user!)
        // via some future TelephonyManager API.
        //
        // See bug 1312336 for more details.
        // We'd need to protect this with a new "intercept incoming calls"
        // system permission.

        // Obtain a partial wake lock to make sure the CPU doesn't go to
        // sleep before we finish bringing up the InCallScreen.
        // (This will be upgraded soon to a full wake lock; see
        // showIncomingCall().)
        if (VDBG) log("Holding wake lock on new incoming connection.");
        mApplication.requestWakeState(PhoneGlobals.WakeState.PARTIAL);

        // - don't ring for call waiting connections
        // - do this before showing the incoming call panel
        startIncomingCallQuery(c);

        // Note we *don't* post a status bar notification here, since
        // we're not necessarily ready to actually show the incoming call
        // to the user.  (For calls in the INCOMING state, at least, we
        // still need to run a caller-id query, and we may not even ring
        // at all if the "send directly to voicemail" flag is set.)
        //
        // Instead, we update the notification (and potentially launch the
        // InCallScreen) from the showIncomingCall() method, which runs
        // when the caller-id query completes or times out.

        // Finally, do the Quiet Hours ringer handling
        checkInQuietHours(c);

        if (VDBG) log("- onNewRingingConnection() done.");
    }

    /**
     * Notifies the Call Modeler that there is a new ringing connection.
     * If it is not a waiting call (there is no other active call in foreground), we will ring the
     * ringtone. Otherwise we will play the call waiting tone instead.
     * @param c The new ringing connection.
     */
    @Override
    protected void ringAndNotifyOfIncomingCall(Connection c) {
        if (PhoneUtils.isRealIncomingCall(c.getState()) && !mSilentRingerRequested) {
            mRinger.ring();
        } else {
            int subscription = c.getCall().getPhone().getSubscription();
            int otherActiveSub = PhoneUtils.getOtherActiveSub(subscription);
            if ((MSimConstants.INVALID_SUBSCRIPTION == otherActiveSub)
                    && (mCallWaitingTonePlayer == null)) {
                if (PhoneUtils.PhoneSettings.vibCallWaiting(mApplication, subscription)) {
                    vibrate(200, 300, 500);
                }
                if (VDBG) log("- starting call waiting tone...");
                mCallWaitingTonePlayer = new InCallTonePlayer(InCallTonePlayer.TONE_CALL_WAITING);
                mCallWaitingTonePlayer.start();
            }
        }
        mCallModeler.onNewRingingConnection(c);
    }

    @Override
    protected void onUnknownConnectionAppeared(AsyncResult r) {
        PhoneBase pb =  (PhoneBase)r.result;
        int subscription = pb.getSubscription();
        PhoneConstants.State state = mCM.getState(subscription);

        if (state == PhoneConstants.State.OFFHOOK) {
            if (DBG) log("unknown connection appeared...");

            // update the active sub & conversation sub before launching incall UI.
            PhoneUtils.setActiveAndConversationSub(subscription);
            // basically do onPhoneStateChanged + display the incoming call UI
            onPhoneStateChanged(r);
        }
    }

    /**
     * Updates the phone UI in response to phone state changes.
     *
     * Watch out: certain state changes are actually handled by their own
     * specific methods:
     *   - see onNewRingingConnection() for new incoming calls
     *   - see onDisconnect() for calls being hung up or disconnected
     */
    @Override
    protected void onPhoneStateChanged(AsyncResult r) {
        PhoneBase pb =  (PhoneBase)r.result;
        int subscription = pb.getSubscription();

        PhoneConstants.State state = mCM.getState(subscription);

        if(MSimPhoneGlobals.getInstance().isCsvtActive() &&
            state == PhoneConstants.State.OFFHOOK ) {
            log("onPhoneStateChanged: CSVT is active");
            return;
        }

        if (VDBG) log("onPhoneStateChanged: state = " + state +
                " subscription = " + subscription);

        // Turn status bar notifications on or off depending upon the state
        // of the phone.  Notification Alerts (audible or vibrating) should
        // be on if and only if the phone is IDLE.
        mApplication.notificationMgr.statusBarHelper
                .enableNotificationAlerts(state == PhoneConstants.State.IDLE);

        Phone fgPhone = mCM.getFgPhone(subscription);
        Connection c;

        // CTA require that UE should disconnect current foregroundCall if answering a MT call on
        // other sub and current foregroundCall callstate is dialing
        if (fgPhone.getForegroundCall().getState() == Call.State.ACTIVE &&
                mApplication.getApplicationContext().getResources().getBoolean
                (R.bool.config_disconnect_other_fgcall)) {
            for (int i = 0; i < MSimTelephonyManager.getDefault().getPhoneCount(); i++) {
                if (i != subscription) {
                    Phone otherFgPhone = mCM.getFgPhone(i);
                    if (DBG) log("otherFgPhoneState: " + otherFgPhone.getForegroundCall().
                            getState());
                    if (otherFgPhone.getForegroundCall().getState() == Call.State.DIALING ||
                            otherFgPhone.getForegroundCall().getState() == Call.State.ALERTING) {
                        PhoneUtils.hangupActiveCall(otherFgPhone.getForegroundCall());
                    }
                }
            }
        }
        if (fgPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            if ((fgPhone.getForegroundCall().getState() == Call.State.ACTIVE)
                    && ((mPreviousCdmaCallState == Call.State.DIALING)
                    ||  (mPreviousCdmaCallState == Call.State.ALERTING))) {
                if (mIsCdmaRedialCall) {
                    int toneToPlay = InCallTonePlayer.TONE_REDIAL;
                    new InCallTonePlayer(toneToPlay).start();
                }
                // Stop any signal info tone when call moves to ACTIVE state
                stopSignalInfoTone();
            }
            mPreviousCdmaCallState = fgPhone.getForegroundCall().getState();
            c = fgPhone.getForegroundCall().getLatestConnection();
        } else {
            c = fgPhone.getForegroundCall().getEarliestConnection();
        }

        // Have the PhoneApp recompute its mShowBluetoothIndication
        // flag based on the (new) telephony state.
        // There's no need to force a UI update since we update the
        // in-call notification ourselves (below), and the InCallScreen
        // listens for phone state changes itself.
        // TODO: Have BluetoothManager listen to CallModeler instead of relying on
        // CallNotifier
        mBluetoothManager.updateBluetoothIndication();

        // Update the phone state and other sensor/lock.
        mApplication.updatePhoneState(state);

        if (state == PhoneConstants.State.OFFHOOK) {
            // stop call waiting tone if needed when answering
            if (mCallWaitingTonePlayer != null) {
                mCallWaitingTonePlayer.stopTone();
                mCallWaitingTonePlayer = null;
            }

            manageLocalCallWaitingTone();

            if (VDBG) log("onPhoneStateChanged: OFF HOOK");
            // make sure audio is in in-call mode now
            PhoneUtils.setAudioMode(mCM);

            // Since we're now in-call, the Ringer should definitely *not*
            // be ringing any more.  (This is just a sanity-check; we
            // already stopped the ringer explicitly back in
            // PhoneUtils.answerCall(), before the call to phone.acceptCall().)
            // TODO: Confirm that this call really *is* unnecessary, and if so,
            // remove it!
            if (DBG) log("stopRing()... (OFFHOOK state)");
            mRinger.stopRing();
        }

        manageMSimInCallTones(false);

        if (c != null && !c.isIncoming() && c.getState() == Call.State.ACTIVE) {
            long callDurationMsec = c.getDurationMillis();
            if (VDBG) Log.v(LOG_TAG, "duration is " + callDurationMsec);

            boolean vibOut = PhoneUtils.PhoneSettings.vibOutgoing(mApplication, subscription);
            if (vibOut && callDurationMsec < 200) {
                vibrate(100, 200, 0);
            }
            boolean vib45 = PhoneUtils.PhoneSettings.vibOn45Secs(mApplication, subscription);
            if (vib45) {
                start45SecondVibration(callDurationMsec);
            }
        }

        if (fgPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            if ((c != null) && (PhoneNumberUtils.isLocalEmergencyNumber(c.getAddress(),
                                                                        mApplication))) {
                if (VDBG) log("onPhoneStateChanged: it is an emergency call.");
                Call.State callState = fgPhone.getForegroundCall().getState();
                if (mEmergencyTonePlayerVibrator == null) {
                    mEmergencyTonePlayerVibrator = new EmergencyTonePlayerVibrator();
                }

                if (callState == Call.State.DIALING || callState == Call.State.ALERTING) {
                    mIsEmergencyToneOn = Settings.Global.getInt(
                            mApplication.getContentResolver(),
                            Settings.Global.EMERGENCY_TONE, EMERGENCY_TONE_OFF);
                    if (mIsEmergencyToneOn != EMERGENCY_TONE_OFF &&
                        mCurrentEmergencyToneState == EMERGENCY_TONE_OFF) {
                        if (mEmergencyTonePlayerVibrator != null) {
                            mEmergencyTonePlayerVibrator.start();
                        }
                    }
                } else if (callState == Call.State.ACTIVE) {
                    if (mCurrentEmergencyToneState != EMERGENCY_TONE_OFF) {
                        if (mEmergencyTonePlayerVibrator != null) {
                            mEmergencyTonePlayerVibrator.stop();
                        }
                    }
                }
            }
        }

        if ((fgPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM)
                || (fgPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_SIP)
                || (fgPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_IMS)) {
            Call.State callState = mCM.getActiveFgCallState(subscription);
            if (!callState.isDialing()) {
                // If call get activated or disconnected before the ringback
                // tone stops, we have to stop it to prevent disturbing.
                if (mInCallRingbackTonePlayer != null) {
                    mInCallRingbackTonePlayer.stopTone();
                    mInCallRingbackTonePlayer = null;
                }
            }
        }
    }

    @Override
    protected void onCustomRingQueryComplete(Connection c) {
        boolean isQueryExecutionTimeExpired = false;
        synchronized (mCallerInfoQueryStateGuard) {
            if (mCallerInfoQueryState == CALLERINFO_QUERYING) {
                mCallerInfoQueryState = CALLERINFO_QUERY_READY;
                isQueryExecutionTimeExpired = true;
            }
        }
        if (isQueryExecutionTimeExpired) {
            // There may be a problem with the query here, since the
            // default ringtone is playing instead of the custom one.
            Log.w(LOG_TAG, "CallerInfo query took too long; falling back to default ringtone");
            EventLog.writeEvent(EventLogTags.PHONE_UI_RINGER_QUERY_ELAPSED);
        }

        Call ringingCall;
        for (int i = 0; i < MSimTelephonyManager.getDefault().getPhoneCount(); i++) {
            if (mCM.getState(i) == PhoneConstants.State.RINGING) {
                // If the ringing call still does not have any connection anymore, do not send the
                // notification to the CallModeler.
                ringingCall = mCM.getFirstActiveRingingCall(i);
                if (ringingCall != null && ringingCall.getLatestConnection() == c) {
                    ringAndNotifyOfIncomingCall(c);
                    break;
                }
            }
        }
    }

    @Override
    protected void onDisconnect(AsyncResult r) {
        if (VDBG) log("onDisconnect()...  CallManager state: " + mCM.getState());
        PhoneUtils.maybeShowOrHideUssdDialog(true);
        mVoicePrivacyState = false;
        Connection c = (Connection) r.result;
        int subscription = c.getCall().getPhone().getSubscription();
        if (c != null) {
            log("onDisconnect: cause = " + c.getDisconnectCause()
                  + ", incoming = " + c.isIncoming()
                  + ", date = " + c.getCreateTime()
                  + ", subscription = " + subscription);
        } else {
            Log.w(LOG_TAG, "onDisconnect: null connection");
        }

        boolean disconnectedDueToBlacklist = isDisconnectedDueToBlacklist(c);
        if (c != null) {
            boolean vibHangup = PhoneUtils.PhoneSettings.vibHangup(mApplication, subscription);
            if (!disconnectedDueToBlacklist && vibHangup && c.getDurationMillis() > 0) {
                vibrate(50, 100, 50);
            }
        }

        int autoretrySetting = 0;
        if ((c != null) && (c.getCall().getPhone().getPhoneType() ==
                PhoneConstants.PHONE_TYPE_CDMA)) {
            autoretrySetting = android.provider.Settings.Global.getInt(mApplication.
                    getContentResolver(),android.provider.Settings.Global.CALL_AUTO_RETRY, 0);
        }

        // Stop any signalInfo tone being played when a call gets ended
        stopSignalInfoTone();

        // Stop 45-second vibration
        removeMessages(VIBRATE_45_SEC);

        if ((c != null) && (c.getCall().getPhone().getPhoneType() ==
                PhoneConstants.PHONE_TYPE_CDMA)) {
            // Resetting the CdmaPhoneCallState members
            mApplication.cdmaPhoneCallState.resetCdmaPhoneCallState();

            // Remove Call waiting timers
            removeMessages(CALLWAITING_CALLERINFO_DISPLAY_DONE);
            removeMessages(CALLWAITING_ADDCALL_DISABLE_TIMEOUT);
        }

        // Stop the ringer if it was ringing (for an incoming call that
        // either disconnected by itself, or was rejected by the user.)
        //
        // TODO: We technically *shouldn't* stop the ringer if the
        // foreground or background call disconnects while an incoming call
        // is still ringing, but that's a really rare corner case.
        // It's safest to just unconditionally stop the ringer here.

        // CDMA: For Call collision cases i.e. when the user makes an out going call
        // and at the same time receives an Incoming Call, the Incoming Call is given
        // higher preference. At this time framework sends a disconnect for the Out going
        // call connection hence we should *not* be stopping the ringer being played for
        // the Incoming Call
        Call ringingCall = mCM.getFirstActiveRingingCall(subscription);
        if (ringingCall.getPhone().getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            if (PhoneUtils.isRealIncomingCall(ringingCall.getState())) {
                // Also we need to take off the "In Call" icon from the Notification
                // area as the Out going Call never got connected
                if (DBG) log("cancelCallInProgressNotifications()... (onDisconnect)");
                mApplication.notificationMgr.cancelCallInProgressNotifications();
            } else {
                if (DBG) log("stopRing()... (onDisconnect)");
                mRinger.stopRing();
            }
        } else { // GSM
            if (DBG) log("stopRing()... (onDisconnect)");
            mRinger.stopRing();
        }

        // stop call waiting tone if needed when disconnecting
        if (mCallWaitingTonePlayer != null) {
            mCallWaitingTonePlayer.stopTone();
            mCallWaitingTonePlayer = null;
        }

        manageLocalCallWaitingTone();

        if (c != null) {
            final String number = c.getAddress();
            final Phone phone = c.getCall().getPhone();
            final Connection.DisconnectCause cause = c.getDisconnectCause();
            final boolean isEmergencyNumber =
                    PhoneNumberUtils.isLocalEmergencyNumber(number, mApplication);
            // If emergency call failure is received with cause codes
            // EMERGENCY_TEMP_FAILURE & EMERGENCY_PERM_FAILURE, then redial on other sub.
            if (isEmergencyNumber &&
                    (cause == Connection.DisconnectCause.EMERGENCY_TEMP_FAILURE
                    || cause == Connection.DisconnectCause.EMERGENCY_PERM_FAILURE)) {
                int subToCall = phone.getSubscription();
                if (cause == Connection.DisconnectCause.EMERGENCY_PERM_FAILURE) {
                    log("EMERGENCY_PERM_FAILURE received on sub:" +
                            phone.getSubscription());
                    // update mIsPermDiscCauseReceived so that next redial doesn't occur
                    // on this sub
                    mIsPermDiscCauseReceived[phone.getSubscription()] = true;
                    subToCall = MSimConstants.INVALID_SUBSCRIPTION;
                }
                // Check for any subscription on which EMERGENCY_PERM_FAILURE is received
                // if no such sub, then redial should be stopped.
                for (int i = PhoneUtils.getNextSubscriptionId(phone.getSubscription());
                        i != phone.getSubscription(); i = PhoneUtils.getNextSubscriptionId(i)) {
                    if (mIsPermDiscCauseReceived[i] == false) {
                        subToCall = i;
                        break;
                    }
                }
                if (subToCall == MSimConstants.INVALID_SUBSCRIPTION) {
                    log("EMERGENCY_PERM_FAILURE recieved on all subs, abort redial");
                } else {
                    log("Redial emergency call on subscription " + subToCall);
                    PhoneUtils.placeCall(mApplication,
                            mApplication.getPhone(subToCall), number, null, false);
                    return;
                }
            }
        }
        // If this is the end of an OTASP call, pass it on to the PhoneApp.
        if (c != null && TelephonyCapabilities.supportsOtasp(c.getCall().getPhone())) {
            final String number = c.getAddress();
            if (c.getCall().getPhone().isOtaSpNumber(number)) {
                if (DBG) log("onDisconnect: this was an OTASP call!");
                mApplication.handleOtaspDisconnect();
            }
        }

        // Check for the various tones we might need to play (thru the
        // earpiece) after a call disconnects.
        int toneToPlay = InCallTonePlayer.TONE_NONE;

        // The "Busy" or "Congestion" tone is the highest priority:
        if (c != null) {
            Connection.DisconnectCause cause = c.getDisconnectCause();
            if (cause == Connection.DisconnectCause.BUSY) {
                if (DBG) log("- need to play BUSY tone!");
                toneToPlay = InCallTonePlayer.TONE_BUSY;
            } else if (cause == Connection.DisconnectCause.CONGESTION) {
                if (DBG) log("- need to play CONGESTION tone!");
                toneToPlay = InCallTonePlayer.TONE_CONGESTION;
            } else if (((cause == Connection.DisconnectCause.NORMAL)
                    || (cause == Connection.DisconnectCause.LOCAL))
                    && (mApplication.isOtaCallInActiveState())) {
                if (DBG) log("- need to play OTA_CALL_END tone!");
                toneToPlay = InCallTonePlayer.TONE_OTA_CALL_END;
            } else if (cause == Connection.DisconnectCause.CDMA_REORDER) {
                if (DBG) log("- need to play CDMA_REORDER tone!");
                toneToPlay = InCallTonePlayer.TONE_REORDER;
            } else if (cause == Connection.DisconnectCause.CDMA_INTERCEPT) {
                if (DBG) log("- need to play CDMA_INTERCEPT tone!");
                toneToPlay = InCallTonePlayer.TONE_INTERCEPT;
            } else if (cause == Connection.DisconnectCause.CDMA_DROP) {
                if (DBG) log("- need to play CDMA_DROP tone!");
                toneToPlay = InCallTonePlayer.TONE_CDMA_DROP;
            } else if (cause == Connection.DisconnectCause.OUT_OF_SERVICE) {
                if (DBG) log("- need to play OUT OF SERVICE tone!");
                toneToPlay = InCallTonePlayer.TONE_OUT_OF_SERVICE;
            } else if (cause == Connection.DisconnectCause.UNOBTAINABLE_NUMBER) {
                if (DBG) log("- need to play TONE_UNOBTAINABLE_NUMBER tone!");
                toneToPlay = InCallTonePlayer.TONE_UNOBTAINABLE_NUMBER;
            } else if (cause == Connection.DisconnectCause.ERROR_UNSPECIFIED) {
                if (DBG) log("- DisconnectCause is ERROR_UNSPECIFIED: play TONE_CALL_ENDED!");
                toneToPlay = InCallTonePlayer.TONE_CALL_ENDED;
            }
        }

        // If we don't need to play BUSY or CONGESTION, then play the
        // "call ended" tone if this was a "regular disconnect" (i.e. a
        // normal call where one end or the other hung up) *and* this
        // disconnect event caused the phone to become idle.  (In other
        // words, we *don't* play the sound if one call hangs up but
        // there's still an active call on the other line.)
        // TODO: We may eventually want to disable this via a preference.
        if ((toneToPlay == InCallTonePlayer.TONE_NONE)
            && (mCM.getState(subscription) == PhoneConstants.State.IDLE)
            && (c != null)) {
            Connection.DisconnectCause cause = c.getDisconnectCause();
            if ((cause == Connection.DisconnectCause.NORMAL)  // remote hangup
                || (cause == Connection.DisconnectCause.LOCAL)) {  // local hangup
                if (VDBG) log("- need to play CALL_ENDED tone!");
                toneToPlay = InCallTonePlayer.TONE_CALL_ENDED;
                mIsCdmaRedialCall = false;
            }
        }

        // All phone calls are disconnected.
        if (mCM.getState(subscription) == PhoneConstants.State.IDLE) {
            // Don't reset the audio mode or bluetooth/speakerphone state
            // if we still need to let the user hear a tone through the earpiece.
            if (toneToPlay == InCallTonePlayer.TONE_NONE) {
                resetAudioStateAfterDisconnect();
            }

            mApplication.notificationMgr.cancelCallInProgressNotifications();
        }

        if (c != null) {
            if (!disconnectedDueToBlacklist) {
                mCallLogger.logCall(c);
            }

            final String number = c.getAddress();
            final Phone phone = c.getCall().getPhone();
            final boolean isEmergencyNumber =
                    PhoneNumberUtils.isLocalEmergencyNumber(number, mApplication);

            if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
                if ((isEmergencyNumber)
                        && (mCurrentEmergencyToneState != EMERGENCY_TONE_OFF)) {
                    if (mEmergencyTonePlayerVibrator != null) {
                        mEmergencyTonePlayerVibrator.stop();
                    }
                }
            }

            final long date = c.getCreateTime();
            final Connection.DisconnectCause cause = c.getDisconnectCause();
            final boolean missedCall = c.isIncoming() &&
                    (cause == Connection.DisconnectCause.INCOMING_MISSED);

            if (missedCall) {
                // Show the "Missed call" notification.
                // (Note we *don't* do this if this was an incoming call that
                // the user deliberately rejected.)
                showMissedCallNotification(c, date);
            }

            // Possibly play a "post-disconnect tone" thru the earpiece.
            // We do this here, rather than from the InCallScreen
            // activity, since we need to do this even if you're not in
            // the Phone UI at the moment the connection ends.
            if (toneToPlay != InCallTonePlayer.TONE_NONE) {
                if (VDBG) log("- starting post-disconnect tone (" + toneToPlay + ")...");
                new InCallTonePlayer(toneToPlay).start();

                // TODO: alternatively, we could start an InCallTonePlayer
                // here with an "unlimited" tone length,
                // and manually stop it later when this connection truly goes
                // away.  (The real connection over the network was closed as soon
                // as we got the BUSY message.  But our telephony layer keeps the
                // connection open for a few extra seconds so we can show the
                // "busy" indication to the user.  We could stop the busy tone
                // when *that* connection's "disconnect" event comes in.)
            }

            if (((mPreviousCdmaCallState == Call.State.DIALING)
                    || (mPreviousCdmaCallState == Call.State.ALERTING))
                    && (!isEmergencyNumber)
                    && (cause != Connection.DisconnectCause.INCOMING_MISSED )
                    && (cause != Connection.DisconnectCause.NORMAL)
                    && (cause != Connection.DisconnectCause.LOCAL)
                    && (cause != Connection.DisconnectCause.INCOMING_REJECTED)) {
                if (!mIsCdmaRedialCall) {
                    if (autoretrySetting == InCallScreen.AUTO_RETRY_ON) {
                        // TODO: (Moto): The contact reference data may need to be stored and use
                        // here when redialing a call. For now, pass in NULL as the URI parameter.
                        final int status =
                                PhoneUtils.placeCall(mApplication, phone, number, null, false);
                        if (status != PhoneUtils.CALL_STATUS_FAILED) {
                            mIsCdmaRedialCall = true;
                        }
                    } else {
                        mIsCdmaRedialCall = false;
                    }
                } else {
                    mIsCdmaRedialCall = false;
                }
            }
            int activeSub = PhoneUtils.getActiveSubscription();
            int ConversationSub = mCM.getSubInConversation();
            if (PhoneUtils.getOtherActiveSub(activeSub) == MSimConstants.INVALID_SUBSCRIPTION
                    && mCM.getState(activeSub) == PhoneConstants.State.IDLE) {
                log("No calls active on both subs");
                // No calls active on both subs, below call takes care of stopping tones
                PhoneUtils.setSubInConversation(MSimConstants.INVALID_SUBSCRIPTION);
            } else if (subscription == ConversationSub && mCM.getState(subscription)
                    == PhoneConstants.State.IDLE) {
                log("No calls active in conversation sub, only update conversation sub");
                // Calls active on active sub, but not on conversation sub
                mCM.setSubInConversation(MSimConstants.INVALID_SUBSCRIPTION);
            } else if (subscription == activeSub && ConversationSub !=
                    MSimConstants.INVALID_SUBSCRIPTION && mCM.getState(activeSub)
                    == PhoneConstants.State.OFFHOOK) {
                // Call on active sub disconnected while user is in conversation on another sub
                // In this case, if no other calls on active sub, then InCallPresenter takes
                // care of switching sub to other sub and setting mute on other sub.
                // If active sub has other calls, then set active sub to conversation sub here
                log("Set active sub to conversation sub " + ConversationSub);
                PhoneUtils.setActiveSubscription(ConversationSub);
            }
        }
    }

    /**
     * Resets mIsPermDiscCauseReceived array elements to false.
     */
    void onEmergencyCallDialed() {
        for (int i = 0; i < mIsPermDiscCauseReceived.length; i++) {
            mIsPermDiscCauseReceived[i] = false;
        }
    }

    /**
     * Resets the audio mode and speaker state when a call ends.
     */
    @Override
    protected void resetAudioStateAfterDisconnect() {
        if (VDBG) log("resetAudioStateAfterDisconnect()...");

        // If any subscription has active voice call, do not reset the audio.
        if (PhoneUtils.isAnySubActive()) {
            if (DBG) log("there is a sub which has active call, Do not reset audio ");
            return;
        }

        super.resetAudioStateAfterDisconnect();
    }

    /**
     * Plays a Call waiting tone if it is present in the second incoming call.
     */
    @Override
    protected void onCdmaCallWaiting(AsyncResult r) {
        int subscription = (Integer) r.userObj;

        PhoneUtils.setActiveSubscription(subscription);

        super.onCdmaCallWaiting(r);
    }

    /**
     * Plays a tone when the phone receives a SignalInfo record.
     */
    @Override
    protected void onSignalInfo(AsyncResult r) {
        // Signal Info are totally ignored on non-voice-capable devices.
        if (!PhoneGlobals.sVoiceCapable) {
            Log.w(LOG_TAG, "Got onSignalInfo() on non-voice-capable device! Ignoring...");
            return;
        }

        // In CDMA networks only we get Signal info records.
        int subscription = 0;
        if (PhoneUtils.isRealIncomingCall(mCM.getFirstActiveRingingCall(subscription).getState())) {
            // Do not start any new SignalInfo tone when Call state is INCOMING
            // and stop any previous SignalInfo tone which is being played
            stopSignalInfoTone();
        } else {
            // Extract the SignalInfo String from the message
            CdmaSignalInfoRec signalInfoRec = (CdmaSignalInfoRec)(r.result);
            // Only proceed if a Signal info is present.
            if (signalInfoRec != null) {
                boolean isPresent = signalInfoRec.isPresent;
                if (DBG) log("onSignalInfo: isPresent=" + isPresent);
                if (isPresent) {// if tone is valid
                    int uSignalType = signalInfoRec.signalType;
                    int uAlertPitch = signalInfoRec.alertPitch;
                    int uSignal = signalInfoRec.signal;

                    if (DBG) log("onSignalInfo: uSignalType=" + uSignalType + ", uAlertPitch=" +
                            uAlertPitch + ", uSignal=" + uSignal);
                    //Map the Signal to a ToneGenerator ToneID only if Signal info is present
                    int toneID = SignalToneUtil.getAudioToneFromSignalInfo
                            (uSignalType, uAlertPitch, uSignal);

                    //Create the SignalInfo tone player and pass the ToneID
                    new SignalInfoTonePlayer(toneID).start();
                }
            }
        }
    }

    void manageMSimInCallTones(boolean isSubSwitch) {
        if (VDBG) log(" entered manageMSimInCallTones ");
        int activeSub = PhoneUtils.getActiveSubscription();
        int otherSub = PhoneUtils.getOtherActiveSub(activeSub);

        // If there is no background active subscription available, stop playing the tones.
        if ((otherSub != MSimConstants.INVALID_SUBSCRIPTION) &&
                (mCM.getState(activeSub) != PhoneConstants.State.IDLE)) {
            // Do not start/stop LCH/SCH tones when phone is in RINGING state.
            if ((mCM.getState(activeSub) != PhoneConstants.State.RINGING) &&
                    (mCM.getState(otherSub) != PhoneConstants.State.RINGING)) {
                //If sub switch happens re-start the tones with a delay of 100msec.
                if (isSubSwitch) {
                    log(" manageMSimInCallTones: re-start playing tones, active sub = "
                            + activeSub + " other sub = " + otherSub);
                    reStartMSimInCallTones();
                } else {
                    if (VDBG) log(" entered manageMSimInCallTones ");
                    startMSimInCallTones();
                }
            }
        } else if (mCM.getLocalCallHoldStatus(activeSub) == false) {
            // if the sub is not in Lch state, then stop playing the tones
            stopMSimInCallTones();
        }
    }

    public void reStartMSimInCallTones() {
        stopMSimInCallTones();
        /* Remove any pending PHONE_START_MSIM_INCALL_TONE messages from queue */
        removeMessages(PHONE_START_MSIM_INCALL_TONE);
        Message message = Message.obtain(this, PHONE_START_MSIM_INCALL_TONE);
        sendMessageDelayed(message, 100);
    }

    private void playLchDtmf() {
        if (mLchSub != MSimConstants.INVALID_SUBSCRIPTION || hasMessages(LCH_PLAY_DTMF)) {
            // Ignore any redundant requests to start playing tones
            return;
        }
        int activeSub = PhoneUtils.getActiveSubscription();
        int otherSub = PhoneUtils.getOtherActiveSub(activeSub);

        log(" playLchDtmf... activesub " + activeSub + " otherSub " + otherSub);
        if (mCM.getLocalCallHoldStatus(activeSub) == true) {
            mLchSub = activeSub;
        } else if (mCM.getLocalCallHoldStatus(otherSub) == true) {
            mLchSub = otherSub;
        } else {
        // There is no other sub active apart from active sub, no need of lch
            log(" There is no sub on lch, returning... ");
            return;
        }
        removeAnyPendingDtmfMsgs();
        char c;
        // For CDMA use # as DTMF char for SCH tones
        if (mCM.getPhoneInCall(mLchSub).getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            c = '#';
        } else {
            c = mApplication.getApplicationContext().getResources().getString(
                R.string.Lch_dtmf_key).charAt(0);
        }
        mCM.startDtmf(c, mLchSub);
        // Keep playing LCH DTMF tone to remote party on LCH call, with periodicity
        // "LCH_DTMF_PERIODICITY" until call moves out of LCH.
        sendMessageDelayed(Message.obtain(this, LCH_PLAY_DTMF), LCH_DTMF_PERIODICITY);
        sendMessageDelayed(Message.obtain(this, LCH_STOP_DTMF), LCH_DTMF_PERIOD);
    }

    private void stopLchDtmf() {
        if (mLchSub != MSimConstants.INVALID_SUBSCRIPTION) {
            // Ignore any redundant requests to stop playing tones
            mCM.stopDtmf(mLchSub);
        }
        mLchSub = MSimConstants.INVALID_SUBSCRIPTION;
    }

    private void startMSimInCallTones() {
        if (mLocalCallReminderTonePlayer == null) {
            if (DBG) log(" Play local call hold reminder tone ");
            mLocalCallReminderTonePlayer = new InCallTonePlayer(InCallTonePlayer.TONE_HOLD_RECALL);
            mLocalCallReminderTonePlayer.start();
        }
        if (sLocalCallHoldToneEnabled) {
            // Only play inband Supervisory call hold tone when
            // "persist.radio.lch_inband_tone" is set to true, else play the SCH tones
            // over DTMF
            if (mSupervisoryCallHoldTonePlayer == null) {
                log(" startMSimInCallTones: Supervisory call hold tone ");
                mSupervisoryCallHoldTonePlayer =
                        new InCallTonePlayer(InCallTonePlayer.TONE_SUPERVISORY_CH);
                mSupervisoryCallHoldTonePlayer.start();
            }
        } else {
            log(" startMSimInCallTones: Supervisory call hold tone over dtmf ");
            playLchDtmf();
        }
    }

    private void removeAnyPendingDtmfMsgs() {
        removeMessages(LCH_PLAY_DTMF);
        removeMessages(LCH_STOP_DTMF);
    }

    protected void stopMSimInCallTones() {
        if (mLocalCallReminderTonePlayer != null) {
            if (DBG) log(" stopMSimInCallTones: local call hold reminder tone ");
            mLocalCallReminderTonePlayer.stopTone();
            mLocalCallReminderTonePlayer = null;
        }
        if (mSupervisoryCallHoldTonePlayer != null) {
            log(" stopMSimInCallTones: Supervisory call hold tone ");
            mSupervisoryCallHoldTonePlayer.stopTone();
            mSupervisoryCallHoldTonePlayer = null;
        }
        if (!sLocalCallHoldToneEnabled) {
            log(" stopMSimInCallTones: stop SCH Dtmf call hold tone ");
            stopLchDtmf();
            /* Remove any previous dtmf nssages from queue */
            removeAnyPendingDtmfMsgs();
        }
    }

    void manageLocalCallWaitingTone() {
        int activeSub = PhoneUtils.getActiveSubscription();
        int otherSub = PhoneUtils.getOtherActiveSub(activeSub);

        if ((otherSub != MSimConstants.INVALID_SUBSCRIPTION) &&
             mCM.hasActiveFgCallAnyPhone() &&
            ((mCM.getState(activeSub) == PhoneConstants.State.RINGING) ||
            (mCM.getState(otherSub) == PhoneConstants.State.RINGING))) {
            log(" manageLocalCallWaitingTone : start tone play");
            startLocalCallWaitingTone();
         } else {
             stopLocalCallWaitingTone();
         }
    }

    private void startLocalCallWaitingTone() {
        if (DBG) log("startLocalCallWaitingTone: Local call waiting tone ");

        if (mLocalCallWaitingTonePlayer == null) {
            mLocalCallWaitingTonePlayer = new InCallTonePlayer(InCallTonePlayer.TONE_LOCAL_CW);
            mLocalCallWaitingTonePlayer.start();
        }
    }

    private void stopLocalCallWaitingTone() {
        if (mLocalCallWaitingTonePlayer != null) {
            log(" Stop playing LCW tone ");
            mLocalCallWaitingTonePlayer.stopTone();
            mLocalCallWaitingTonePlayer = null;
        }
    }

    @Override
    protected int getSuppServiceToastTextResIdIfEnabled(SuppServiceNotification notification) {
        if (!PhoneUtils.PhoneSettings.showInCallEvents(mApplication,
                mPhone.getSubscription())) {
            /* don't show anything if the user doesn't want it */
            return -1;
        }
        return getSuppServiceToastTextResId(notification);
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
