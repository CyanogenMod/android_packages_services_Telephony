/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.services.telephony;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.telecom.AudioState;
import android.telecom.Conference;
import android.telecom.ConferenceParticipant;
import android.telecom.CallProperties;
import android.telecom.Connection;
import android.telecom.PhoneAccount;
import android.telecom.PhoneCapabilities;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.widget.Toast;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection.PostDialListener;
import com.android.internal.telephony.gsm.SuppServiceNotification;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.imsphone.ImsPhoneConnection;
import com.android.internal.telephony.PhoneConstants;

import com.android.phone.R;

import java.lang.Override;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base class for CDMA and GSM connections.
 */
abstract class TelephonyConnection extends Connection {
    private static final int MSG_PRECISE_CALL_STATE_CHANGED = 1;
    private static final int MSG_RINGBACK_TONE = 2;
    private static final int MSG_HANDOVER_STATE_CHANGED = 3;
    private static final int MSG_DISCONNECT = 4;
    private static final int MSG_SUPP_SERVICE_NOTIFY = 5;
    private static final int MSG_PHONE_VP_ON = 6;
    private static final int MSG_PHONE_VP_OFF = 7;
    private static final int MSG_SUPP_SERVICE_FAILED = 8;
    private static final int MSG_SET_VIDEO_STATE = 9;
    private static final int MSG_SET_LOCAL_VIDEO_CAPABILITY = 10;
    private static final int MSG_SET_REMOTE_VIDEO_CAPABILITY = 11;
    private static final int MSG_SET_VIDEO_PROVIDER = 12;
    private static final int MSG_SET_AUDIO_QUALITY = 13;
    private static final int MSG_SET_CALL_SUBSTATE = 14;
    private static final int MSG_SET_CONFERENCE_PARTICIPANTS = 15;

    private static final String ACTION_SUPP_SERVICE_FAILURE =
            "org.codeaurora.ACTION_SUPP_SERVICE_FAILURE";

    private String[] mSubName = {"SUB 1", "SUB 2", "SUB 3"};
    private String mDisplayName;
    private boolean mVoicePrivacyState = false;
    protected boolean mIsOutgoing;
    private boolean[] mIsPermDiscCauseReceived = new
            boolean[TelephonyManager.getDefault().getPhoneCount()];

    protected static SuppServiceNotification mSsNotification = null;
    private boolean mHeldRemotely;

    private static final boolean DBG = false;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PRECISE_CALL_STATE_CHANGED:
                    Log.v(TelephonyConnection.this, "MSG_PRECISE_CALL_STATE_CHANGED");
                    setExtras();
                    updateState();
                    break;
                case MSG_HANDOVER_STATE_CHANGED:
                    Log.v(TelephonyConnection.this, "MSG_HANDOVER_STATE_CHANGED");
                    AsyncResult ar = (AsyncResult) msg.obj;
                    com.android.internal.telephony.Connection connection =
                         (com.android.internal.telephony.Connection) ar.result;
                    if ((mOriginalConnection != null && connection != null) &&
                            ((connection.getAddress() != null &&
                            mOriginalConnection.getAddress() != null &&
                            mOriginalConnection.getAddress().contains(connection.getAddress())) ||
                            mOriginalConnection.getStateBeforeHandover()
                            == connection.getState())) {
                        Log.d(TelephonyConnection.this, "SettingOriginalConnection " +
                                mOriginalConnection.toString() + " with " + connection.toString());

                        if (TelephonyGlobals.getApplicationContext().getResources()
                                .getBoolean(R.bool.config_show_srvcc_toast)) {
                            int srvccMessageRes = VideoProfile.VideoState.isVideo(
                                    mOriginalConnection.getVideoState()) ? R.string.srvcc_video_message
                                    : R.string.srvcc_message;
                            Toast.makeText(TelephonyGlobals.getApplicationContext(),
                                    srvccMessageRes, Toast.LENGTH_LONG).show();
                        }
                        setOriginalConnection(connection);
                        mWasImsConnection = false;
                    }
                    break;
                case MSG_RINGBACK_TONE:
                    Log.v(TelephonyConnection.this, "MSG_RINGBACK_TONE");
                    // TODO: This code assumes that there is only one connection in the foreground
                    // call, in other words, it punts on network-mediated conference calling.
                    if (getOriginalConnection() != getForegroundConnection()) {
                        Log.v(TelephonyConnection.this, "handleMessage, original connection is " +
                                "not foreground connection, skipping");
                        return;
                    }
                    setRingbackRequested((Boolean) ((AsyncResult) msg.obj).result);
                    break;
                case MSG_DISCONNECT:
                    updateState();
                    break;
                case MSG_SUPP_SERVICE_NOTIFY:
                    Log.v(TelephonyConnection.this, "MSG_SUPP_SERVICE_NOTIFY");
                    SuppServiceNotification ssn =
                            (SuppServiceNotification)((AsyncResult) msg.obj).result;
                    if (ssn != null) {
                        onSuppServiceNotification(ssn);
                    }
                    break;
                case MSG_PHONE_VP_ON:
                    if (!mVoicePrivacyState) {
                        mVoicePrivacyState = true;
                        updateState();
                    }
                    break;
                case MSG_PHONE_VP_OFF:
                    if (mVoicePrivacyState) {
                        mVoicePrivacyState = false;
                        updateState();
                    }
                    break;
                case MSG_SUPP_SERVICE_FAILED:
                    Log.d(TelephonyConnection.this, "MSG_SUPP_SERVICE_FAILED");
                    AsyncResult r = (AsyncResult) msg.obj;
                    Phone.SuppService service = (Phone.SuppService) r.result;
                    int val = service.ordinal();
                    Intent failure = new Intent();
                    failure.setAction(ACTION_SUPP_SERVICE_FAILURE);
                    failure.putExtra("supp_serv_failure", val);
                    TelephonyGlobals.getApplicationContext().sendBroadcast(failure);
                    break;

                case MSG_SET_VIDEO_STATE:
                    setVideoState(msg.arg1);
                    break;

                case MSG_SET_LOCAL_VIDEO_CAPABILITY:
                    setLocalVideoCapable(toBoolean(msg.arg1));
                    break;

                case MSG_SET_REMOTE_VIDEO_CAPABILITY:
                    setRemoteVideoCapable(toBoolean(msg.arg1));
                    break;

                case MSG_SET_VIDEO_PROVIDER:
                    VideoProvider videoProvider = (VideoProvider) msg.obj;
                    setVideoProvider(videoProvider);
                    break;

                case MSG_SET_AUDIO_QUALITY:
                    setAudioQuality(msg.arg1);
                    break;

                case MSG_SET_CALL_SUBSTATE:
                    setCallSubstate(msg.arg1);
                    break;

                case MSG_SET_CONFERENCE_PARTICIPANTS:
                    List<ConferenceParticipant> participants =
                            (List<ConferenceParticipant>) msg.obj;
                    updateConferenceParticipants(participants);
                    break;

            }
        }
    };

    private boolean toBoolean(int value) {
        return (value == 1);
    }

    private int toInt(boolean value) {
        return (value) ? 1 : 0;
    }

    protected boolean isOutgoing() {
        return mIsOutgoing;
    }

    private String getSuppSvcNotificationText(SuppServiceNotification suppSvcNotification) {
        final int SUPP_SERV_NOTIFICATION_TYPE_MO = 0;
        final int SUPP_SERV_NOTIFICATION_TYPE_MT = 1;
        String callForwardTxt = "";
        if (suppSvcNotification != null) {
            switch (suppSvcNotification.notificationType) {
                // The Notification is for MO call
                case SUPP_SERV_NOTIFICATION_TYPE_MO:
                    callForwardTxt = getMoSsNotificationText(suppSvcNotification.code);
                    break;

                // The Notification is for MT call
                case SUPP_SERV_NOTIFICATION_TYPE_MT:
                    callForwardTxt = getMtSsNotificationText(suppSvcNotification.code);
                    break;

                default:
                    Log.v(TelephonyConnection.this, "Received invalid Notification Type :"
                            + suppSvcNotification.notificationType);
                    break;
            }
        }
        return callForwardTxt;
    }

    private String getMtSsNotificationText(int code) {
        String callForwardTxt = "";
        switch (code) {
            case SuppServiceNotification.MT_CODE_FORWARDED_CALL:
                //This message is displayed on C when the incoming
                //call is forwarded from B
                callForwardTxt = TelephonyGlobals.getApplicationContext().getString(
                        R.string.card_title_forwarded_MTcall);
                break;

            case SuppServiceNotification.MT_CODE_CUG_CALL:
                //This message is displayed on B, when A makes call to B, both A & B
                //belong to a CUG group
                callForwardTxt = TelephonyGlobals.getApplicationContext()
                        .getString(R.string.card_title_cugcall);
                break;

            case SuppServiceNotification.MT_CODE_CALL_ON_HOLD:
                //This message is displayed on B,when A makes call to B & puts it on
                // hold
                callForwardTxt = TelephonyGlobals.getApplicationContext()
                        .getString(R.string.card_title_callonhold);
                break;

            case SuppServiceNotification.MT_CODE_CALL_RETRIEVED:
                //This message is displayed on B,when A makes call to B, puts it on
                //hold & retrives it back.
                callForwardTxt = TelephonyGlobals.getApplicationContext().getString(
                        R.string.card_title_callretrieved);
                break;

            case SuppServiceNotification.MT_CODE_MULTI_PARTY_CALL:
                //This message is displayed on B when the the call is changed as
                //multiparty
                callForwardTxt = TelephonyGlobals.getApplicationContext().getString(
                        R.string.card_title_multipartycall);
                break;

            case SuppServiceNotification.MT_CODE_ON_HOLD_CALL_RELEASED:
                //This message is displayed on B, when A makes call to B, puts it on
                //hold & then releases it.
                callForwardTxt = TelephonyGlobals.getApplicationContext().getString(
                        R.string.card_title_callonhold_released);
                break;

            case SuppServiceNotification.MT_CODE_FORWARD_CHECK_RECEIVED:
                //This message is displayed on C when the incoming call is forwarded
                //from B
                callForwardTxt = TelephonyGlobals.getApplicationContext().getString(
                        R.string.card_title_forwardcheckreceived);
                break;

            case SuppServiceNotification.MT_CODE_CALL_CONNECTING_ECT:
                //This message is displayed on B,when Call is connecting through
                //Explicit Cold Transfer
                callForwardTxt = TelephonyGlobals.getApplicationContext().getString(
                        R.string.card_title_callconnectingect);
                break;

            case SuppServiceNotification.MT_CODE_CALL_CONNECTED_ECT:
                //This message is displayed on B,when Call is connected through
                //Explicit Cold Transfer
                callForwardTxt = TelephonyGlobals.getApplicationContext().getString(
                        R.string.card_title_callconnectedect);
                break;

            case SuppServiceNotification.MT_CODE_DEFLECTED_CALL:
                //This message is displayed on B when the incoming call is deflected
                //call
                callForwardTxt = TelephonyGlobals.getApplicationContext().getString(
                        R.string.card_title_deflectedcall);
                break;

            case SuppServiceNotification.MT_CODE_ADDITIONAL_CALL_FORWARDED:
                // This message is displayed on B when it is busy and the incoming call
                // gets forwarded to C
                callForwardTxt = TelephonyGlobals.getApplicationContext().getString(
                        R.string.card_title_MTcall_forwarding);
                break;

            default :
               Log.v(TelephonyConnection.this,"Received unsupported MT SS Notification :" + code
                      +" "+getPhone().getPhoneId() );
                break;
        }
        return callForwardTxt;
    }

    private String getMoSsNotificationText(int code) {
        String callForwardTxt = "";
        switch (code) {
            case SuppServiceNotification.MO_CODE_UNCONDITIONAL_CF_ACTIVE:
                // This message is displayed when an outgoing call is made
                // and unconditional forwarding is enabled.
                callForwardTxt = TelephonyGlobals.getApplicationContext().getString(
                        R.string.card_title_unconditionalCF);
            break;

            case SuppServiceNotification.MO_CODE_SOME_CF_ACTIVE:
                // This message is displayed when an outgoing call is made
                // and conditional forwarding is enabled.
                callForwardTxt = TelephonyGlobals.getApplicationContext().getString(
                        R.string.card_title_conditionalCF);
                break;

            case SuppServiceNotification.MO_CODE_CALL_FORWARDED:
                //This message is displayed on A when the outgoing call
                //actually gets forwarded to C
                callForwardTxt = TelephonyGlobals.getApplicationContext().getString(
                        R.string.card_title_MOcall_forwarding);
                break;

            case SuppServiceNotification.MO_CODE_CALL_IS_WAITING:
                //This message is displayed on A when the B is busy on another call
                //and Call waiting is enabled on B
                callForwardTxt = TelephonyGlobals.getApplicationContext().getString(
                        R.string.card_title_calliswaiting);
                break;

            case SuppServiceNotification.MO_CODE_CUG_CALL:
                //This message is displayed on A, when A makes call to B, both A & B
                //belong to a CUG group
                callForwardTxt = TelephonyGlobals.getApplicationContext()
                        .getString(R.string.card_title_cugcall);
                break;

            case SuppServiceNotification.MO_CODE_OUTGOING_CALLS_BARRED:
                //This message is displayed on A when outging is barred on A
                callForwardTxt = TelephonyGlobals.getApplicationContext().getString(
                        R.string.card_title_outgoing_barred);
                break;

            case SuppServiceNotification.MO_CODE_INCOMING_CALLS_BARRED:
                //This message is displayed on A, when A is calling B
                //& incoming is barred on B
                callForwardTxt = TelephonyGlobals.getApplicationContext().getString(
                        R.string.card_title_incoming_barred);
                break;

            case SuppServiceNotification.MO_CODE_CLIR_SUPPRESSION_REJECTED:
                //This message is displayed on A, when CLIR suppression is rejected
                callForwardTxt = TelephonyGlobals.getApplicationContext().getString(
                        R.string.card_title_clir_suppression_rejected);
                break;

            case SuppServiceNotification.MO_CODE_CALL_DEFLECTED:
                //This message is displayed on A, when the outgoing call
                //gets deflected to C from B
                callForwardTxt = TelephonyGlobals.getApplicationContext().getString(
                        R.string.card_title_call_deflected);
                break;

            default:
                Log.v(TelephonyConnection.this,"Received unsupported MO SS Notification :" + code
                        +" "+getPhone().getPhoneId());
                break;
        }
        return callForwardTxt;
    }

    /**
     * A listener/callback mechanism that is specific communication from TelephonyConnections
     * to TelephonyConnectionService (for now). It is more specific that Connection.Listener
     * because it is only exposed in Telephony.
     */
    public abstract static class TelephonyConnectionListener {
        public void onOriginalConnectionConfigured(TelephonyConnection c) {}
        public void onEmergencyRedial(TelephonyConnection c, PhoneAccountHandle pHandle,
                int phoneId) {}
    }

    private final PostDialListener mPostDialListener = new PostDialListener() {
        @Override
        public void onPostDialWait() {
            Log.v(TelephonyConnection.this, "onPostDialWait");
            if (mOriginalConnection != null) {
                setPostDialWait(mOriginalConnection.getRemainingPostDialString());
            }
        }

        @Override
        public void onPostDialChar(char c) {
            Log.v(TelephonyConnection.this, "onPostDialChar: %s", c);
            if (mOriginalConnection != null) {
                setNextPostDialWaitChar(c);
            }
        }
    };

    /**
     * Listener for listening to events in the {@link com.android.internal.telephony.Connection}.
     */
    private final com.android.internal.telephony.Connection.Listener mOriginalConnectionListener =
            new com.android.internal.telephony.Connection.ListenerBase() {
        @Override
        public void onVideoStateChanged(int videoState) {
            mHandler.obtainMessage(MSG_SET_VIDEO_STATE, videoState, 0).sendToTarget();
        }

        /**
         * The {@link com.android.internal.telephony.Connection} has reported a change in local
         * video capability.
         *
         * @param capable True if capable.
         */
        @Override
        public void onLocalVideoCapabilityChanged(boolean capable) {
            mHandler.obtainMessage(MSG_SET_LOCAL_VIDEO_CAPABILITY,
                    toInt(capable), 0).sendToTarget();
        }

        /**
         * The {@link com.android.internal.telephony.Connection} has reported a change in remote
         * video capability.
         *
         * @param capable True if capable.
         */
        @Override
        public void onRemoteVideoCapabilityChanged(boolean capable) {
            mHandler.obtainMessage(MSG_SET_REMOTE_VIDEO_CAPABILITY,
                    toInt(capable), 0).sendToTarget();
        }

        /**
         * The {@link com.android.internal.telephony.Connection} has reported a change in the
         * video call provider.
         *
         * @param videoProvider The video call provider.
         */
        @Override
        public void onVideoProviderChanged(VideoProvider videoProvider) {
            mHandler.obtainMessage(MSG_SET_VIDEO_PROVIDER, videoProvider).sendToTarget();
        }

        /**
         * Used by the {@link com.android.internal.telephony.Connection} to report a change in the
         * audio quality for the current call.
         *
         * @param audioQuality The audio quality.
         */
        @Override
        public void onAudioQualityChanged(int audioQuality) {
            mHandler.obtainMessage(MSG_SET_AUDIO_QUALITY, audioQuality, 0).sendToTarget();
        }

        /**
         * Used by the {@link com.android.internal.telephony.Connection} to report a change in the
         * substate of the current call
         *
         * @param callSubstate The call substate.
         */
        @Override
        public void onCallSubstateChanged(int callSubstate) {
            mHandler.obtainMessage(MSG_SET_CALL_SUBSTATE, callSubstate, 0).sendToTarget();
        }

        /**
         * Handles a change in the state of conference participant(s), as reported by the
         * {@link com.android.internal.telephony.Connection}.
         *
         * @param participants The participant(s) which changed.
         */
        @Override
        public void onConferenceParticipantsChanged(List<ConferenceParticipant> participants) {
            mHandler.obtainMessage(MSG_SET_CONFERENCE_PARTICIPANTS, participants).sendToTarget();
        }
    };

    /* package */ com.android.internal.telephony.Connection mOriginalConnection;
    private Call.State mOriginalConnectionState = Call.State.IDLE;
    private Bundle mOriginalConnectionExtras;

    private boolean mWasImsConnection;

    /**
     * Determines if the {@link TelephonyConnection} has local video capabilities.
     * This is used when {@link TelephonyConnection#updateConnectionCapabilities()}} is called,
     * ensuring the appropriate capabilities are set.  Since capabilities
     * can be rebuilt at any time it is necessary to track the video capabilities between rebuild.
     * The capabilities (including video capabilities) are communicated to the telecom
     * layer.
     */
    private boolean mLocalVideoCapable;

    /**
     * Determines if the {@link TelephonyConnection} has remote video capabilities.
     * This is used when {@link TelephonyConnection#updateConnectionCapabilities()}} is called,
     * ensuring the appropriate capabilities are set.  Since capabilities can be rebuilt at any time
     * it is necessary to track the video capabilities between rebuild. The capabilities (including
     * video capabilities) are communicated to the telecom layer.
     */
    private boolean mRemoteVideoCapable;

    /**
     * Determines the current audio quality for the {@link TelephonyConnection}.
     * This is used when {@link TelephonyConnection#updateConnectionCapabilities}} is called to
     * indicate whether a call has the {@link Connection#CAPABILITY_HIGH_DEF_AUDIO} capability.
     */
    private int mAudioQuality;

    /**
     * Listeners to our TelephonyConnection specific callbacks
     */
    private final Set<TelephonyConnectionListener> mTelephonyListeners = Collections.newSetFromMap(
            new ConcurrentHashMap<TelephonyConnectionListener, Boolean>(8, 0.9f, 1));

    protected TelephonyConnection(com.android.internal.telephony.Connection originalConnection) {
        if (originalConnection != null) {
            setOriginalConnection(originalConnection);
        }
    }

    protected TelephonyConnection(com.android.internal.telephony.Connection originalConnection,
                Call.State originalConnectionState) {
        if (originalConnection != null) {
            setOriginalConnection(originalConnection);
            mOriginalConnectionState = originalConnectionState;
        }
    }

    /**
     * Creates a clone of the current {@link TelephonyConnection}.
     *
     * @return The clone.
     */
    public abstract TelephonyConnection cloneConnection();

    @Override
    public void onAudioStateChanged(AudioState audioState) {
        // TODO: update TTY mode.
        if (getPhone() != null) {
            getPhone().setEchoSuppressionEnabled();
        }
    }

    @Override
    public void onStateChanged(int state) {
        Log.v(this, "onStateChanged, state: " + Connection.stateToString(state));
    }

    @Override
    public void onDisconnect() {
        Log.v(this, "onDisconnect");
        hangup(android.telephony.DisconnectCause.LOCAL);
    }

    /**
     * Notifies this Connection of a request to disconnect a participant of the conference managed
     * by the connection.
     *
     * @param endpoint the {@link Uri} of the participant to disconnect.
     */
    @Override
    public void onDisconnectConferenceParticipant(Uri endpoint) {
        Log.v(this, "onDisconnectConferenceParticipant %s", endpoint);

        if (mOriginalConnection == null) {
            return;
        }

        mOriginalConnection.onDisconnectConferenceParticipant(endpoint);
    }

    @Override
    public void onSeparate() {
        Log.v(this, "onSeparate");
        if (mOriginalConnection != null) {
            try {
                mOriginalConnection.separate();
            } catch (CallStateException e) {
                Log.e(this, e, "Call to Connection.separate failed with exception");
            }
        }
    }

    @Override
    public void onAbort() {
        Log.v(this, "onAbort");
        hangup(android.telephony.DisconnectCause.LOCAL);
    }

    @Override
    public void setLocalCallHold(int lchStatus) {
        TelephonyConnectionService.setLocalCallHold(getPhone(), lchStatus);
    }

    @Override
    public void setActiveSubscription() {
        Phone ph = getPhone();
        if (ph == null) {
            return;
        }
        int subId = ph.getSubId();
        Log.i(this, "setActiveSubscription subId:" + subId);
        CallManager.getInstance().setActiveSubscription(subId);
    }

    @Override
    public void onHold() {
        performHold();
    }

    @Override
    public void onUnhold() {
        performUnhold();
    }

    @Override
    public void onAnswer(int videoState) {
        Log.v(this, "onAnswer");
        if (isValidRingingCall() && getPhone() != null) {
            try {
                getPhone().acceptCall(videoState);
            } catch (CallStateException e) {
                Log.e(this, e, "Failed to accept call.");
            }
        }
    }

    @Override
    public void onDeflect(String number) {
        Log.v(this, "onDeflect: " + number);

        if (isValidRingingCall() && getPhone() != null) {
            try {
                getPhone().deflectCall(number);
            } catch (CallStateException e) {
                Log.e(this, e, "Failed to deflect call.");
            }
        }
    }

    @Override
    public void onReject() {
        Log.v(this, "onReject");
        if (isValidRingingCall()) {
            hangup(android.telephony.DisconnectCause.INCOMING_REJECTED);
        }
        super.onReject();
    }

    @Override
    public void onPostDialContinue(boolean proceed) {
        Log.v(this, "onPostDialContinue, proceed: " + proceed);
        if (mOriginalConnection != null) {
            if (proceed) {
                mOriginalConnection.proceedAfterWaitChar();
            } else {
                mOriginalConnection.cancelPostDial();
            }
        }
    }

    public void performHold() {
        Log.v(this, "performHold");
        // TODO: Can dialing calls be put on hold as well since they take up the
        // foreground call slot?
        if (Call.State.ACTIVE == mOriginalConnectionState) {
            Log.v(this, "Holding active call");
            try {
                Phone phone = mOriginalConnection.getCall().getPhone();
                Call ringingCall = phone.getRingingCall();

                // Although the method says switchHoldingAndActive, it eventually calls a RIL method
                // called switchWaitingOrHoldingAndActive. What this means is that if we try to put
                // a call on hold while a call-waiting call exists, it'll end up accepting the
                // call-waiting call, which is bad if that was not the user's intention. We are
                // cheating here and simply skipping it because we know any attempt to hold a call
                // while a call-waiting call is happening is likely a request from Telecom prior to
                // accepting the call-waiting call.
                // TODO: Investigate a better solution. It would be great here if we
                // could "fake" hold by silencing the audio and microphone streams for this call
                // instead of actually putting it on hold.
                if (ringingCall.getState() != Call.State.WAITING) {
                    phone.switchHoldingAndActive();
                }

                // TODO: Cdma calls are slightly different.
            } catch (CallStateException e) {
                Log.e(this, e, "Exception occurred while trying to put call on hold.");
            }
        } else {
            Log.w(this, "Cannot put a call that is not currently active on hold.");
        }
    }

    public void performUnhold() {
        Log.v(this, "performUnhold");
        if (Call.State.HOLDING == mOriginalConnectionState) {
            try {
                // Here's the deal--Telephony hold/unhold is weird because whenever there exists
                // more than one call, one of them must always be active. In other words, if you
                // have an active call and holding call, and you put the active call on hold, it
                // will automatically activate the holding call. This is weird with how Telecom
                // sends its commands. When a user opts to "unhold" a background call, telecom
                // issues hold commands to all active calls, and then the unhold command to the
                // background call. This means that we get two commands...each of which reduces to
                // switchHoldingAndActive(). The result is that they simply cancel each other out.
                // To fix this so that it works well with telecom we add a minor hack. If we
                // have one telephony call, everything works as normally expected. But if we have
                // two or more calls, we will ignore all requests to "unhold" knowing that the hold
                // requests already do what we want. If you've read up to this point, I'm very sorry
                // that we are doing this. I didn't think of a better solution that wouldn't also
                // make the Telecom APIs very ugly.

                if (!hasMultipleTopLevelCalls()) {
                    mOriginalConnection.getCall().getPhone().switchHoldingAndActive();
                } else {
                    Log.i(this, "Skipping unhold command for %s", this);
                }
            } catch (CallStateException e) {
                Log.e(this, e, "Exception occurred while trying to release call from hold.");
            }
        } else {
            Log.w(this, "Cannot release a call that is not already on hold from hold.");
        }
    }

    public void performConference(TelephonyConnection otherConnection) {
        Log.d(this, "performConference - %s", this);
        if (getPhone() != null) {
            try {
                // We dont use the "other" connection because there is no concept of that in the
                // implementation of calls inside telephony. Basically, you can "conference" and it
                // will conference with the background call.  We know that otherConnection is the
                // background call because it would never have called setConferenceableConnections()
                // otherwise.
                getPhone().conference();
            } catch (CallStateException e) {
                Log.e(this, e, "Failed to conference call.");
            }
        }
    }

    public void performAddParticipant(String participant) {
        Log.d(this, "performAddParticipant - %s", participant);
        if (getPhone() != null) {
            try {
                // We should send AddParticipant request using connection.
                // Basically, you can make call to conference with AddParticipant
                // request on single normal call.
                getPhone().addParticipant(participant);
            } catch (CallStateException e) {
                Log.e(this, e, "Failed to performAddParticipant.");
            }
        }
    }

    /**
     * Builds call capabilities common to all TelephonyConnections. Namely, apply IMS-based
     * capabilities.
     */
    protected int buildConnectionCapabilities() {
        int callCapabilities = 0;
        if (isImsConnection()) {
            callCapabilities |= CAPABILITY_SUPPORT_HOLD;
            if (getState() == STATE_ACTIVE || getState() == STATE_HOLDING) {
                callCapabilities |= CAPABILITY_HOLD;
            }
        }
        return callCapabilities;
    }

    protected final void updateConnectionCapabilities() {
        int newCapabilities = buildConnectionCapabilities();
        newCapabilities = applyVideoCapabilities(newCapabilities);
        newCapabilities = applyAudioQualityCapabilities(newCapabilities);
        newCapabilities = applyConferenceTerminationCapabilities(newCapabilities);
        newCapabilities = applyVoicePrivacyCapabilities(newCapabilities);
        newCapabilities = applyAddParticipantCapabilities(newCapabilities);
        newCapabilities = applyConferenceCapabilities(newCapabilities);

        if (getConnectionCapabilities() != newCapabilities) {
            setConnectionCapabilities(newCapabilities);
        }
    }

    protected final void updateAddress() {
        updateConnectionCapabilities();
        Uri address;
        if (mOriginalConnection != null) {
            if ((getAddress() != null) &&
                    (getPhone().getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) &&
                            isOutgoing()) {
                address = getAddressFromNumber(mOriginalConnection.getOrigDialString());
            } else {
                address = getAddressFromNumber(mOriginalConnection.getAddress());
            }
            int presentation = mOriginalConnection.getNumberPresentation();
            if (!Objects.equals(address, getAddress()) ||
                    presentation != getAddressPresentation()) {
                Log.v(this, "updateAddress, address changed");
                setAddress(address, presentation);
            }

            String name = mOriginalConnection.getCnapName();
            int namePresentation = mOriginalConnection.getCnapNamePresentation();
            if (!Objects.equals(name, getCallerDisplayName()) ||
                    namePresentation != getCallerDisplayNamePresentation()) {
                Log.v(this, "updateAddress, caller display name changed");
                setCallerDisplayName(name, namePresentation);
            }
        }
    }

    void onRemovedFromCallService() {
        // Subclass can override this to do cleanup.
    }

    protected void onSuppServiceNotification(SuppServiceNotification notification) {
        Log.d(this, "SS Notification: " + notification);

        // hold/retrieved notifications can come from either gsm or ims phones
        if (notification.notificationType == SuppServiceNotification.NOTIFICATION_TYPE_MT) {
            if (notification.code == SuppServiceNotification.MT_CODE_CALL_ON_HOLD) {
                mHeldRemotely = true;
            } else if (notification.code == SuppServiceNotification.MT_CODE_CALL_RETRIEVED) {
                mHeldRemotely = false;
            }
        }

        setCallProperties(computeCallProperties());
    }

    protected int computeCallProperties() {
        return mHeldRemotely ? CallProperties.HELD_REMOTELY : 0;
    }

    void setOriginalConnection(com.android.internal.telephony.Connection originalConnection) {
        Log.v(this, "new TelephonyConnection, originalConnection: " + originalConnection);
        clearOriginalConnection();

        mOriginalConnection = originalConnection;
        getPhone().registerForPreciseCallStateChanged(
                mHandler, MSG_PRECISE_CALL_STATE_CHANGED, null);
        getPhone().registerForHandoverStateChanged(
                mHandler, MSG_HANDOVER_STATE_CHANGED, null);
        getPhone().registerForRingbackTone(mHandler, MSG_RINGBACK_TONE, null);
        getPhone().registerForDisconnect(mHandler, MSG_DISCONNECT, null);
        getPhone().registerForSuppServiceNotification(mHandler, MSG_SUPP_SERVICE_NOTIFY, null);
        getPhone().registerForInCallVoicePrivacyOn(mHandler, MSG_PHONE_VP_ON, null);
        getPhone().registerForInCallVoicePrivacyOff(mHandler, MSG_PHONE_VP_OFF, null);
        getPhone().registerForSuppServiceFailed(mHandler, MSG_SUPP_SERVICE_FAILED, null);
        mOriginalConnection.addPostDialListener(mPostDialListener);
        mOriginalConnection.addListener(mOriginalConnectionListener);

        updateAddress();

        // Set video state and capabilities
        setVideoState(mOriginalConnection.getVideoState());
        setLocalVideoCapable(mOriginalConnection.isLocalVideoCapable());
        setRemoteVideoCapable(mOriginalConnection.isRemoteVideoCapable());
        setVideoProvider(mOriginalConnection.getVideoProvider());
        setAudioQuality(mOriginalConnection.getAudioQuality());
        setCallSubstate(mOriginalConnection.getCallSubstate());

        if (isImsConnection()) {
            mWasImsConnection = true;
        }

        fireOnOriginalConnectionConfigured();
    }

    /**
     * Un-sets the underlying radio connection.
     */
    void clearOriginalConnection() {
        if (mOriginalConnection != null) {
            getPhone().unregisterForPreciseCallStateChanged(mHandler);
            getPhone().unregisterForRingbackTone(mHandler);
            getPhone().unregisterForHandoverStateChanged(mHandler);
            getPhone().unregisterForDisconnect(mHandler);
            getPhone().unregisterForSuppServiceNotification(mHandler);
            getPhone().unregisterForInCallVoicePrivacyOn(mHandler);
            getPhone().unregisterForInCallVoicePrivacyOff(mHandler);
            getPhone().unregisterForSuppServiceFailed(mHandler);
            mOriginalConnection = null;
        }
    }

    protected void hangup(int telephonyDisconnectCode) {
        if (mOriginalConnection != null) {
            try {
                // Hanging up a ringing call requires that we invoke call.hangup() as opposed to
                // connection.hangup(). Without this change, the party originating the call will not
                // get sent to voicemail if the user opts to reject the call.
                if (isValidRingingCall()) {
                    Call call = getCall();
                    if (call != null) {
                        call.hangup();
                    } else {
                        Log.w(this, "Attempting to hangup a connection without backing call.");
                    }
                } else {
                    // We still prefer to call connection.hangup() for non-ringing calls in order
                    // to support hanging-up specific calls within a conference call. If we invoked
                    // call.hangup() while in a conference, we would end up hanging up the entire
                    // conference call instead of the specific connection.
                    mOriginalConnection.hangup();
                }
            } catch (CallStateException e) {
                Log.e(this, e, "Call to Connection.hangup failed with exception");
            }
        }
    }

    com.android.internal.telephony.Connection getOriginalConnection() {
        return mOriginalConnection;
    }

    Call.State getOriginalConnectionState() {
        return mOriginalConnectionState;
    }

    protected Call getCall() {
        if (mOriginalConnection != null) {
            return mOriginalConnection.getCall();
        }
        return null;
    }

    Phone getPhone() {
        Call call = getCall();
        if (call != null) {
            return call.getPhone();
        }
        return null;
    }

    private boolean isMultiparty() {
        if (mOriginalConnection != null) {
            return mOriginalConnection.isMultiparty();
        }
        return false;
    }

    private boolean hasMultipleTopLevelCalls() {
        int numCalls = 0;
        Phone phone = getPhone();
        if (phone != null) {
            if (!phone.getRingingCall().isIdle()) {
                numCalls++;
            }
            if (!phone.getForegroundCall().isIdle()) {
                numCalls++;
            }
            if (!phone.getBackgroundCall().isIdle()) {
                numCalls++;
            }
        }
        return numCalls > 1;
    }

    private com.android.internal.telephony.Connection getForegroundConnection() {
        if (getPhone() != null) {
            return getPhone().getForegroundCall().getEarliestConnection();
        }
        return null;
    }

    /**
     * Checks for and returns the list of conference participants
     * associated with this connection.
     */
    public List<ConferenceParticipant> getConferenceParticipants() {
        if (mOriginalConnection == null) {
            Log.v(this, "Null mOriginalConnection, cannot get conf participants.");
            return null;
        }
        return mOriginalConnection.getConferenceParticipants();
    }

    /**
     * Checks to see the original connection corresponds to an active incoming call. Returns false
     * if there is no such actual call, or if the associated call is not incoming (See
     * {@link Call.State#isRinging}).
     */
    private boolean isValidRingingCall() {
        if (getPhone() == null) {
            Log.v(this, "isValidRingingCall, phone is null");
            return false;
        }

        Call ringingCall = getPhone().getRingingCall();
        if (!ringingCall.getState().isRinging()) {
            Log.v(this, "isValidRingingCall, ringing call is not in ringing state");
            return false;
        }

        if (ringingCall.getEarliestConnection() != mOriginalConnection) {
            Log.v(this, "isValidRingingCall, ringing call connection does not match");
            return false;
        }

        Log.v(this, "isValidRingingCall, returning true");
        return true;
    }

    protected void setExtras() {
        Bundle extras = null;
        if (mOriginalConnection != null) {
            extras = mOriginalConnection.getCall().getExtras();
            if (extras != null) {
                // Check if extras have changed and need updating.
                if (!Objects.equals(mOriginalConnectionExtras, extras)) {
                    if (DBG) {
                        Log.d(TelephonyConnection.this, "Updating extras:");
                        for (String key : extras.keySet()) {
                            Object value = extras.get(key);
                            if (value instanceof String) {
                                Log.d(TelephonyConnection.this,
                                        "setExtras Key=" + key +
                                                " value=" + (String)value);
                            }
                        }
                    }
                    mOriginalConnectionExtras = extras;
                    super.setExtras(extras);
                } else {
                    Log.d(TelephonyConnection.this,
                        "Extras update not required");
                }
            } else {
                Log.d(TelephonyConnection.this, "Null call extras");
            }
        }
    }

    void updateState() {
        if (mOriginalConnection == null) {
            return;
        }

        final String number = mOriginalConnection.getAddress();
        final Phone phone = mOriginalConnection.getCall().getPhone();
        int cause = mOriginalConnection.getDisconnectCause();
        final boolean isEmergencyNumber =
                    PhoneNumberUtils.isLocalEmergencyNumber(TelephonyGlobals.
                    getApplicationContext(), number);
        Call.State newState = mOriginalConnection.getState();

        Log.v(this, "Update state from %s to %s for %s", mOriginalConnectionState, newState, this);
        if (mOriginalConnectionState != newState) {
            mOriginalConnectionState = newState;
            switch (newState) {
                case IDLE:
                    break;
                case ACTIVE:
                    setActiveInternal();
                    break;
                case HOLDING:
                    setOnHold();
                    break;
                case DIALING:
                case ALERTING:
                    setDialing();
                    break;
                case INCOMING:
                case WAITING:
                    setRinging();
                    break;
                case DISCONNECTED:
                    if (mSsNotification != null) {
                        setDisconnected(DisconnectCauseUtil.toTelecomDisconnectCause(
                                mOriginalConnection.getDisconnectCause(),
                                mSsNotification.notificationType,
                                mSsNotification.code));
                        mSsNotification = null;
                    } else if(isEmergencyNumber &&
                            (TelephonyManager.getDefault().getPhoneCount() > 1) &&
                            ((cause == android.telephony.DisconnectCause.EMERGENCY_TEMP_FAILURE) ||
                            (cause == android.telephony.DisconnectCause.EMERGENCY_PERM_FAILURE))) {
                        // If emergency call failure is received with cause codes
                        // EMERGENCY_TEMP_FAILURE & EMERGENCY_PERM_FAILURE, then redial on other sub.
                        emergencyRedial(cause, phone);
                        break;
                    } else {
                        setDisconnected(DisconnectCauseUtil.toTelecomDisconnectCause(
                                mOriginalConnection.getDisconnectCause()));
                    }
                    resetDisconnectCause();
                    close();
                    break;
                case DISCONNECTING:
                    break;
            }
        }
        updateConnectionCapabilities();
        updateAddress();
    }

    private void emergencyRedial(int cause, Phone phone) {
        int PhoneIdToCall = phone.getPhoneId();
        if (cause == android.telephony.DisconnectCause.EMERGENCY_PERM_FAILURE) {
            Log.d(this,"EMERGENCY_PERM_FAILURE received on sub:" + PhoneIdToCall);
            // update mIsPermDiscCauseReceived so that next redial doesn't occur
            // on this sub
            mIsPermDiscCauseReceived[phone.getPhoneId()] = true;
            PhoneIdToCall = SubscriptionManager.INVALID_PHONE_INDEX;
        }
        // Check for any subscription on which EMERGENCY_PERM_FAILURE is received
        // if no such sub, then redial should be stopped.
        for (int i = getNextPhoneId(phone.getPhoneId()); i != phone.getPhoneId();
                i = getNextPhoneId(i)) {
            if (mIsPermDiscCauseReceived[i] == false) {
                PhoneIdToCall = i;
                break;
            }
        }

        int subId = SubscriptionController.getInstance().getSubIdUsingPhoneId(PhoneIdToCall);
        if (PhoneIdToCall == SubscriptionManager.INVALID_PHONE_INDEX) {
            Log.d(this,"EMERGENCY_PERM_FAILURE received on all subs, abort redial");
            setDisconnected(DisconnectCauseUtil.toTelecomDisconnectCause(
                    mOriginalConnection.getDisconnectCause(), 0xFF, 0xFF));
            resetDisconnectCause();
            close();
        } else {
            Log.d(this,"Redial emergency call on subscription " + PhoneIdToCall);
            TelecomManager telecommMgr = (TelecomManager)
            TelephonyGlobals.getApplicationContext().getSystemService(Context.TELECOM_SERVICE);
            if (telecommMgr != null) {
                List<PhoneAccountHandle> phoneAccountHandles =
                        telecommMgr.getCallCapablePhoneAccounts();
                for (PhoneAccountHandle handle : phoneAccountHandles) {
                    String sub = handle.getId();
                    if (Integer.toString(subId).equals(sub)){
                        Log.d(this,"EMERGENCY REDIAL");
                        for (TelephonyConnectionListener l : mTelephonyListeners) {
                            l.onEmergencyRedial(this, handle, PhoneIdToCall);
                        }
                    }
                }
            }
        }
    }

    private int getNextPhoneId(int curId) {
        int nextId =  curId + 1;
        if (nextId >= TelephonyManager.getDefault().getPhoneCount()) {
            nextId = 0;
        }
        return nextId;
    }

    private void resetDisconnectCause() {
        for (int i = 0; i < TelephonyManager.getDefault().getPhoneCount(); i++) {
            mIsPermDiscCauseReceived[i] = false;
        }
    }

    private void setActiveInternal() {
        if (getState() == STATE_ACTIVE) {
            Log.w(this, "Should not be called if this is already ACTIVE");
            return;
        }

        // When we set a call to active, we need to make sure that there are no other active
        // calls. However, the ordering of state updates to connections can be non-deterministic
        // since all connections register for state changes on the phone independently.
        // To "optimize", we check here to see if there already exists any active calls.  If so,
        // we issue an update for those calls first to make sure we only have one top-level
        // active call.
        if (getConnectionService() != null) {
            for (Connection current : getConnectionService().getAllConnections()) {
                if (current != this && current instanceof TelephonyConnection) {
                    TelephonyConnection other = (TelephonyConnection) current;
                    if (other.getState() == STATE_ACTIVE) {
                        other.updateState();
                    }
                }
            }
        }
        setActive();
    }

    protected void close() {
        Log.v(this, "close");
        if (getPhone() != null) {
            if (getPhone().getState() == PhoneConstants.State.IDLE) {
                Log.i(this, "disable local call hold, if not already done by telecomm service");
                setLocalCallHold(0);
            }
            getPhone().unregisterForPreciseCallStateChanged(mHandler);
            getPhone().unregisterForRingbackTone(mHandler);
            getPhone().unregisterForHandoverStateChanged(mHandler);
            getPhone().unregisterForSuppServiceNotification(mHandler);
            getPhone().unregisterForInCallVoicePrivacyOn(mHandler);
            getPhone().unregisterForInCallVoicePrivacyOff(mHandler);
        }
        mOriginalConnection = null;
        destroy();
    }

    /**
     * Applies the video capability states to the CallCapabilities bit-mask.
     *
     * @param capabilities The CallCapabilities bit-mask.
     * @return The capabilities with video capabilities applied.
     */
    private int applyVideoCapabilities(int capabilities) {
        int currentCapabilities = capabilities;
        if (mRemoteVideoCapable) {
            currentCapabilities = applyCapability(currentCapabilities,
                    CAPABILITY_SUPPORTS_VT_REMOTE);
        } else {
            currentCapabilities = removeCapability(currentCapabilities,
                    CAPABILITY_SUPPORTS_VT_REMOTE);
        }

        if (mLocalVideoCapable) {
            currentCapabilities = applyCapability(currentCapabilities,
                    CAPABILITY_SUPPORTS_VT_LOCAL);
        } else {
            currentCapabilities = removeCapability(currentCapabilities,
                    CAPABILITY_SUPPORTS_VT_LOCAL);
        }
        int callState = getState();
        if (mLocalVideoCapable && mRemoteVideoCapable
                && (callState == STATE_ACTIVE || callState == STATE_HOLDING)) {
            currentCapabilities = applyCapability(currentCapabilities,
                    PhoneCapabilities.CALL_TYPE_MODIFIABLE);
        } else {
            currentCapabilities = removeCapability(currentCapabilities,
                    PhoneCapabilities.CALL_TYPE_MODIFIABLE);
        }
        return currentCapabilities;
    }

    /**
     * Applies the audio capabilities to the {@code CallCapabilities} bit-mask.  A call with high
     * definition audio is considered to have the {@code HIGH_DEF_AUDIO} call capability.
     *
     * @param capabilities The {@code CallCapabilities} bit-mask.
     * @return The capabilities with the audio capabilities applied.
     */
    private int applyAudioQualityCapabilities(int capabilities) {
        int currentCapabilities = capabilities;

        if (mAudioQuality ==
                com.android.internal.telephony.Connection.AUDIO_QUALITY_HIGH_DEFINITION) {
            currentCapabilities = applyCapability(currentCapabilities, CAPABILITY_HIGH_DEF_AUDIO);
        } else {
            currentCapabilities = removeCapability(currentCapabilities, CAPABILITY_HIGH_DEF_AUDIO);
        }

        return currentCapabilities;
    }

    /**
     * Applies capabilities specific to conferences termination to the
     * {@code CallCapabilities} bit-mask.
     *
     * @param capabilities The {@code CallCapabilities} bit-mask.
     * @return The capabilities with the IMS conference capabilities applied.
     */
    private int applyConferenceTerminationCapabilities(int capabilities) {
        int currentCapabilities = capabilities;

        // An IMS call cannot be individually disconnected or separated from its parent conference.
        // If the call was IMS, even if it hands over to GMS, these capabilities are not supported.
        if (!mWasImsConnection) {
            currentCapabilities |= CAPABILITY_DISCONNECT_FROM_CONFERENCE;
            currentCapabilities |= CAPABILITY_SEPARATE_FROM_CONFERENCE;
        }

        return currentCapabilities;
    }

    /**
     * Applies the voice privacy capabilities to the {@code CallCapabilities} bit-mask.
     *
     * @param callCapabilities The {@code CallCapabilities} bit-mask.
     * @return The capabilities with the voice privacy capabilities applied.
     */
    private int applyVoicePrivacyCapabilities(int callCapabilities) {
        int currentCapabilities = callCapabilities;
        if (mVoicePrivacyState) {
            currentCapabilities = applyCapability(currentCapabilities,
                    PhoneCapabilities.VOICE_PRIVACY);
        } else {
            currentCapabilities = removeCapability(currentCapabilities,
                    PhoneCapabilities.VOICE_PRIVACY);
        }

        return currentCapabilities;
    }

    /**
     * Applies the add participant capabilities to the {@code CallCapabilities} bit-mask.
     *
     * @param callCapabilities The {@code CallCapabilities} bit-mask.
     * @return The capabilities with the add participant capabilities applied.
     */
    private int applyAddParticipantCapabilities(int callCapabilities) {
        int currentCapabilities = callCapabilities;
        if (getPhone() != null &&
                 getPhone().getPhoneType() == PhoneConstants.PHONE_TYPE_IMS) {
            currentCapabilities = applyCapability(currentCapabilities,
                    PhoneCapabilities.ADD_PARTICIPANT);
        } else {
            currentCapabilities = removeCapability(currentCapabilities,
                    PhoneCapabilities.ADD_PARTICIPANT);
        }

        return currentCapabilities;
    }

    /**
     * Applies the conference capabilities to the {@code CallCapabilities} bit-mask.
     *
     * @param callCapabilities The {@code CallCapabilities} bit-mask.
     * @return The capabilities with the conference capabilities applied.
     */
    private int applyConferenceCapabilities(int callCapabilities) {
        int currentCapabilities = callCapabilities;
        if (getPhone() != null &&
                 getPhone().getPhoneType() == PhoneConstants.PHONE_TYPE_IMS &&
                 isMultiparty()) {
            currentCapabilities = applyCapability(currentCapabilities,
                    PhoneCapabilities.GENERIC_CONFERENCE);
        }

        return currentCapabilities;
    }

    /**
     * Returns the local video capability state for the connection.
     *
     * @return {@code True} if the connection has local video capabilities.
     */
    public boolean isLocalVideoCapable() {
        return mLocalVideoCapable;
    }

    /**
     * Returns the remote video capability state for the connection.
     *
     * @return {@code True} if the connection has remote video capabilities.
     */
    public boolean isRemoteVideoCapable() {
        return mRemoteVideoCapable;
    }

    /**
     * Sets whether video capability is present locally.  Used during rebuild of the
     * capabilities to set the video call capabilities.
     *
     * @param capable {@code True} if video capable.
     */
    public void setLocalVideoCapable(boolean capable) {
        mLocalVideoCapable = capable;
        updateConnectionCapabilities();
    }

    /**
     * Sets whether video capability is present remotely.  Used during rebuild of the
     * capabilities to set the video call capabilities.
     *
     * @param capable {@code True} if video capable.
     */
    public void setRemoteVideoCapable(boolean capable) {
        mRemoteVideoCapable = capable;
        updateConnectionCapabilities();
    }

    /**
     * Sets the current call audio quality.  Used during rebuild of the capabilities
     * to set or unset the {@link Connection#CAPABILITY_HIGH_DEF_AUDIO} capability.
     *
     * @param audioQuality The audio quality.
     */
    public void setAudioQuality(int audioQuality) {
        mAudioQuality = audioQuality;
        updateConnectionCapabilities();
    }

    /**
     * Obtains the current call audio quality.
     */
    public int getAudioQuality() {
        return mAudioQuality;
    }

    void resetStateForConference() {
        if (getState() == Connection.STATE_HOLDING) {
            if (mOriginalConnection.getState() == Call.State.ACTIVE) {
                setActive();
            }
        }
    }

    boolean setHoldingForConference() {
        if (getState() == Connection.STATE_ACTIVE) {
            setOnHold();
            return true;
        }
        return false;
    }

    /**
     * Whether the original connection is an IMS connection.
     * @return {@code True} if the original connection is an IMS connection, {@code false}
     *     otherwise.
     */
    protected boolean isImsConnection() {
        return getOriginalConnection() instanceof ImsPhoneConnection;
    }

    /**
     * Whether the original connection was ever an IMS connection, either before or now.
     * @return {@code True} if the original connection was ever an IMS connection, {@code false}
     *     otherwise.
     */
    public boolean wasImsConnection() {
        return mWasImsConnection;
    }

    private static Uri getAddressFromNumber(String number) {
        // Address can be null for blocked calls.
        if (number == null) {
            number = "";
        }
        return Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null);
    }

    /**
     * Applies a capability to a capabilities bit-mask.
     *
     * @param capabilities The capabilities bit-mask.
     * @param capability The capability to apply.
     * @return The capabilities bit-mask with the capability applied.
     */
    private int applyCapability(int capabilities, int capability) {
        int newCapabilities = capabilities | capability;
        return newCapabilities;
    }

    /**
     * Removes a capability from a capabilities bit-mask.
     *
     * @param capabilities The capabilities bit-mask.
     * @param capability The capability to remove.
     * @return The capabilities bit-mask with the capability removed.
     */
    private int removeCapability(int capabilities, int capability) {
        int newCapabilities = capabilities & ~capability;
        return newCapabilities;
    }

    /**
     * Register a listener for {@link TelephonyConnection} specific triggers.
     * @param l The instance of the listener to add
     * @return The connection being listened to
     */
    public final TelephonyConnection addTelephonyConnectionListener(TelephonyConnectionListener l) {
        mTelephonyListeners.add(l);
        // If we already have an original connection, let's call back immediately.
        // This would be the case for incoming calls.
        if (mOriginalConnection != null) {
            fireOnOriginalConnectionConfigured();
        }
        return this;
    }

    /**
     * Remove a listener for {@link TelephonyConnection} specific triggers.
     * @param l The instance of the listener to remove
     * @return The connection being listened to
     */
    public final TelephonyConnection removeTelephonyConnectionListener(
            TelephonyConnectionListener l) {
        if (l != null) {
            mTelephonyListeners.remove(l);
        }
        return this;
    }

    /**
     * Fire a callback to the various listeners for when the original connection is
     * set in this {@link TelephonyConnection}
     */
    private final void fireOnOriginalConnectionConfigured() {
        for (TelephonyConnectionListener l : mTelephonyListeners) {
            l.onOriginalConnectionConfigured(this);
        }
    }

    /**
     * Creates a string representation of this {@link TelephonyConnection}.  Primarily intended for
     * use in log statements.
     *
     * @return String representation of the connection.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[TelephonyConnection objId:");
        sb.append(System.identityHashCode(this));
        sb.append(" type:");
        if (isImsConnection()) {
            sb.append("ims");
        } else if (this instanceof com.android.services.telephony.GsmConnection) {
            sb.append("gsm");
        } else if (this instanceof CdmaConnection) {
            sb.append("cdma");
        }
        sb.append(" state:");
        sb.append(Connection.stateToString(getState()));
        sb.append(" capabilities:");
        sb.append(capabilitiesToString(getConnectionCapabilities()));
        sb.append(" address:");
        sb.append(Log.pii(getAddress()));
        sb.append(" originalConnection:");
        sb.append(mOriginalConnection);
        sb.append(" partOfConf:");
        if (getConference() == null) {
            sb.append("N");
        } else {
            sb.append("Y");
        }
        sb.append("]");
        sb.append("OriginalConnection is" + mOriginalConnection);
        return sb.toString();
    }
}
