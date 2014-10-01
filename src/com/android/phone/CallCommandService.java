/*
 * Copyright (C) 2013 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.phone;

import android.bluetooth.IBluetoothHeadsetPhone;
import android.content.Context;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.MSimConstants;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.util.BlacklistUtils;
import com.android.phone.CallModeler.CallResult;
import com.android.phone.NotificationMgr.StatusBarHelper;
import com.android.services.telephony.common.Call;
import com.android.services.telephony.common.CallDetails;
import com.android.services.telephony.common.ICallCommandService;

/**
 * Service interface used by in-call ui to control phone calls using commands exposed as methods.
 * Instances of this class are handed to in-call UI via CallMonitorService.
 */
class CallCommandService extends ICallCommandService.Stub {
    private static final String TAG = CallCommandService.class.getSimpleName();
    private static final boolean DBG =
            (PhoneGlobals.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);

    private final Context mContext;
    private final CallManager mCallManager;
    private final CallModeler mCallModeler;
    private final DTMFTonePlayer mDtmfTonePlayer;
    private final AudioRouter mAudioRouter;

    public CallCommandService(Context context, CallManager callManager, CallModeler callModeler,
            DTMFTonePlayer dtmfTonePlayer, AudioRouter audioRouter) {
        mContext = context;
        mCallManager = callManager;
        mCallModeler = callModeler;
        mDtmfTonePlayer = dtmfTonePlayer;
        mAudioRouter = audioRouter;
    }

    /**
     * TODO: Add a confirmation callback parameter.
     */
    @Override
    public void answerCall(int callId) {
        try {
            CallResult result = mCallModeler.getCallWithId(callId);
            if (result != null) {
                answerCallWithCallType(callId, CallDetails.CALL_TYPE_UNKNOWN);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during answerCall().", e);
        }
    }

    public void answerCallWithCallType(int callId, int callType) {
        Log.v(TAG, "answerCallWithCallType" + callId + " calltype" + callType);
        try {
            CallResult result = mCallModeler.getCallWithId(callId);
            if (result != null && callType != CallDetails.CALL_TYPE_UNKNOWN) {
                result.mCall.getCallDetails().setCallType(callType);
            }
            if (mCallManager.hasActiveFgCall() && mCallManager.hasActiveBgCall()) {
                PhoneUtils.answerAndEndActive(mCallManager, result.getConnection().getCall());
            } else {
                PhoneUtils.answerCall(result.getConnection().getCall(), callType);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during answerCall().", e);
        }
    }

    public void deflectCall(int callId, String number) {
        Log.v(TAG, "deflectCall connId" + callId + "to number" + number);
        try {
            CallResult result = mCallModeler.getCallWithId(callId);
            PhoneUtils.deflectCall(result.getConnection(), number);
        } catch (Exception e) {
            Log.e(TAG, "Error during deflectCall().", e);
        }
    }

    public void modifyCallInitiate(int callId, int callType) {
        Log.v(TAG, "modifyCallInitiate: callId=" + callId + "callType=" + callType);
        try {
            CallResult result = mCallModeler.getCallWithId(callId);
            if (result != null) {
                PhoneUtils.modifyCallInitiate(result.getConnection(), callType, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during modifyCallInitiate().", e);
        }
    }

    public void modifyCallConfirm(boolean responseType, int callId) {
        Log.v(TAG, "modifyCallConfirm" + "responseType " + responseType + "callId" + callId);
        CallDetails callModify;
        try {
            CallResult result = mCallModeler.getCallWithId(callId);
            if (result != null) {
                callModify = result.mCall.getCallModifyDetails();
                PhoneUtils.modifyCallConfirm(responseType, result.getConnection(),
                        callModify.getExtras());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during modifyCallInitiate().", e);
        }
    }

    /**
     * TODO: Add a confirmation callback parameter.
     */
    @Override
    public void rejectCall(Call call, boolean rejectWithMessage, String message) {
        try {
            int callId = Call.INVALID_CALL_ID;
            String phoneNumber = "";
            int subscription = 0;
            if (call != null) {
                callId = call.getCallId();
                phoneNumber = call.getNumber();
                subscription = call.getSubscription();
            }
            CallResult result = mCallModeler.getCallWithId(callId);

            if (result != null) {
                phoneNumber = result.getConnection().getAddress();

                Log.v(TAG, "Hanging up");
                PhoneUtils.hangupRingingCall(result.getConnection().getCall());
            }

            if (rejectWithMessage && !phoneNumber.isEmpty()) {
                RejectWithTextMessageManager.rejectCallWithMessage(phoneNumber, message,
                        subscription);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during rejectCall().", e);
        }
    }

    @Override
    public void disconnectCall(int callId) {
        try {
            CallResult result = mCallModeler.getCallWithId(callId);
            if (DBG) Log.d(TAG, "disconnectCall " + result.getCall());

            if (result != null) {
                int state = result.getCall().getState();
                if (Call.State.ACTIVE == state ||
                        Call.State.ONHOLD == state ||
                        Call.State.DIALING == state) {
                    result.getConnection().getCall().hangup();
                } else if (Call.State.CONFERENCED == state) {
                    result.getConnection().hangup();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during disconnectCall().", e);
        }
    }

    @Override
    public void separateCall(int callId) {
        try {
            CallResult result = mCallModeler.getCallWithId(callId);
            if (DBG) Log.d(TAG, "disconnectCall " + result.getCall());

            if (result != null) {
                int state = result.getCall().getState();
                if (Call.State.CONFERENCED == state) {
                    result.getConnection().separate();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error trying to separate call.", e);
        }
    }

    @Override
    public void hold(int callId, boolean hold) {
        try {
            CallResult result = mCallModeler.getCallWithId(callId);
            if (result != null) {
                int state = result.getCall().getState();
                if (hold && Call.State.ACTIVE == state) {
                    PhoneUtils.switchHoldingAndActive(mCallManager.getFirstActiveBgCall());
                } else if (!hold && Call.State.ONHOLD == state) {
                    PhoneUtils.switchHoldingAndActive(result.getConnection().getCall());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error trying to place call on hold.", e);
        }
    }

    @Override
    public void merge() {
        if (PhoneUtils.okToMergeCalls(mCallManager)) {
            PhoneUtils.mergeCalls(mCallManager);
        }
    }

    @Override
    public void addCall() {
        // start new call checks okToAddCall() already
        PhoneUtils.startNewCall(mCallManager);
    }


    @Override
    public void swap() {
        try {
            PhoneUtils.swap();
        } catch (Exception e) {
            Log.e(TAG, "Error during swap().", e);
        }
    }

    @Override
    public void mute(boolean onOff) {
        try {
            PhoneUtils.setMute(onOff);
        } catch (Exception e) {
            Log.e(TAG, "Error during mute().", e);
        }
    }

    @Override
    public void muteInternal(boolean onOff) {
        try {
            PhoneUtils.muteOnNewCall(onOff);
        } catch (Exception e) {
            Log.e(TAG, "Error during mute().", e);
        }
    }

    @Override
    public void updateMuteState(int sub, boolean muted) {
        try {
            PhoneUtils.updateMuteState(sub, muted);
        } catch (Exception e) {
            Log.e(TAG, "Error during updateMuteState().", e);
        }
    }

    @Override
    public void speaker(boolean onOff) {
        try {
            PhoneUtils.turnOnSpeaker(mContext, onOff, true);
        } catch (Exception e) {
            Log.e(TAG, "Error during speaker().", e);
        }
    }

    @Override
    public void playDtmfTone(char digit, boolean timedShortTone) {
        try {
            mDtmfTonePlayer.playDtmfTone(digit, timedShortTone);
        } catch (Exception e) {
            Log.e(TAG, "Error playing DTMF tone.", e);
        }
    }

    @Override
    public void stopDtmfTone() {
        try {
            mDtmfTonePlayer.stopDtmfTone();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping DTMF tone.", e);
        }
    }

    @Override
    public void setAudioMode(int mode) {
        try {
            mAudioRouter.setAudioMode(mode);
        } catch (Exception e) {
            Log.e(TAG, "Error setting the audio mode.", e);
        }
    }

    @Override
    public void postDialCancel(int callId) throws RemoteException {
        final CallResult result = mCallModeler.getCallWithId(callId);
        if (result != null) {
            result.getConnection().cancelPostDial();
        }
    }

    @Override
    public void postDialWaitContinue(int callId) throws RemoteException {
        final CallResult result = mCallModeler.getCallWithId(callId);
        if (result != null) {
            result.getConnection().proceedAfterWaitChar();
        }
    }

    public void hangupWithReason(int callId, String userUri, boolean mpty, int failCause,
            String errorInfo) {
        try {
            Log.d(TAG, "hangupWithReason");
            PhoneUtils.hangupWithReason(callId, userUri, mpty, failCause, errorInfo);
        } catch (Exception e) {
            Log.e(TAG, "Error hangupWithReason", e);
        }
    }

    @Override
    public void setSystemBarNavigationEnabled(boolean enable) {
        try {
            final StatusBarHelper statusBarHelper = PhoneGlobals.getInstance().notificationMgr.
                    statusBarHelper;
            statusBarHelper.enableSystemBarNavigation(enable);
            statusBarHelper.enableExpandedView(enable);
        } catch (Exception e) {
            Log.e(TAG, "Error enabling or disabling system bar navigation", e);
        }
    }

    @Override
    public void blacklistAndHangup(int callId) {
        final CallResult result = mCallModeler.getCallWithId(callId);
        if (result == null) {
            return;
        }

        try {
            final String phoneNumber = result.getConnection().getAddress();
            BlacklistUtils.addOrUpdate(mContext, phoneNumber,
                    BlacklistUtils.BLOCK_CALLS, BlacklistUtils.BLOCK_CALLS);
            Log.v(TAG, "Hanging up");
            PhoneUtils.hangup(result.getConnection().getCall());
        } catch (Exception e) {
            Log.e(TAG, "Error during blacklistAndHangup().", e);
        }
    }

    @Override
    public void setActiveSubscription(int subscriptionId) {
        try {
            // Set only the active subscription, if SubInConversation is not set, it
            // means lch state should be retained on active subscription, hence enable
            // mute so that user is aware that call is in lch.
            PhoneUtils.setActiveSubscription(subscriptionId);
            if ((mCallManager.getState(subscriptionId) == PhoneConstants.State.OFFHOOK) &&
                    (mCallManager.getSubInConversation() == MSimConstants.INVALID_SUBSCRIPTION)) {
                if (DBG) Log.d(TAG, "setActiveSubscription: call setMute");
                // Set mute on active sub, when active sub changed due to remote end of call
                // in conversation
                PhoneUtils.setMute(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during setActiveSubscription().", e);
        }
    }

    @Override
    public void setSubInConversation(int subscriptionId) {
        try {
            mCallManager.setSubInConversation(subscriptionId);
        } catch (Exception e) {
            Log.e(TAG, "Error during setSubInConversation().", e);
        }
    }

    @Override
    public void setActiveAndConversationSub(int subscriptionId) {
        try {
            PhoneUtils.setActiveAndConversationSub(subscriptionId);
        } catch (Exception e) {
            Log.e(TAG, "Error during setActiveAndConversationSub().", e);
        }
    }

    @Override
    public int getActiveSubscription() {
        int subscriptionId = MSimConstants.INVALID_SUBSCRIPTION;

        try {
            subscriptionId = PhoneUtils.getActiveSubscription();
        } catch (Exception e) {
            Log.e(TAG, "Error during getActiveSubscription().", e);
        }
        return subscriptionId;
    }
}
