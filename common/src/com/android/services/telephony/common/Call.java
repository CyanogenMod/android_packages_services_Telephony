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
 * limitations under the License.
 */

package com.android.services.telephony.common;

import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.PhoneNumberUtils;

import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.MSimConstants;
import com.google.android.collect.Sets;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.primitives.Ints;
import com.android.services.telephony.common.CallDetails;

import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Class object used across CallHandlerService APIs.
 * Describes a single call and its state.
 */
public final class Call implements Parcelable {

    public static final int INVALID_CALL_ID = -1;
    public static final int MAX_CONFERENCED_CALLS = 5;

    /* Defines different states of this call */
    public static class State {
        public static final int INVALID = 0;
        public static final int IDLE = 1;           /* The call is idle.  Nothing active */
        public static final int ACTIVE = 2;         /* There is an active call */
        public static final int INCOMING = 3;       /* A normal incoming phone call */
        public static final int CALL_WAITING = 4;   /* Incoming call while another is active */
        public static final int DIALING = 5;        /* An outgoing call during dial phase */
        public static final int REDIALING = 6;      /* Subsequent dialing attempt after a failure */
        public static final int ONHOLD = 7;         /* An active phone call placed on hold */
        public static final int DISCONNECTING = 8;  /* A call is being ended. */
        public static final int DISCONNECTED = 9;   /* State after a call disconnects */
        public static final int CONFERENCED = 10;   /* Call part of a conference call */

        public static boolean isConnected(int state) {
            switch(state) {
                case ACTIVE:
                case INCOMING:
                case CALL_WAITING:
                case DIALING:
                case REDIALING:
                case ONHOLD:
                case CONFERENCED:
                    return true;
                default:
            }
            return false;
        }

        public static boolean isDialing(int state) {
            return state == DIALING || state == REDIALING;
        }
    }

    /**
     * Defines a set of capabilities that a call can have as a bit mask.
     * TODO: Should some of these be capabilities of the Phone instead of the call?
     * TODO: This is starting to be a mix of capabilities and call properties.  Capabilities
     *       and properties should be separated.
     */
    public static class Capabilities {
        public static final int HOLD               = 0x00000001; /* has ability to hold the call */
        public static final int SUPPORT_HOLD       = 0x00000002; /* can show the hold button */
        public static final int MERGE_CALLS        = 0x00000004; /* has ability to merge calls */
        public static final int SWAP_CALLS         = 0x00000008; /* swap with a background call */
        public static final int ADD_CALL           = 0x00000010; /* add another call to this one */
        public static final int RESPOND_VIA_TEXT   = 0x00000020; /* has respond via text option */
        public static final int MUTE               = 0x00000040; /* can mute the call */
        public static final int GENERIC_CONFERENCE = 0x00000080; /* Generic conference mode */
        public static final int MODIFY_CALL        = 0x00000100; /* Upgrade or downgrade or
                                                                    callModify option*/
        public static final int ADD_PARTICIPANT    = 0x00000200; /* Add participant from and active
                                                                    or conference call option*/
        public static final int VOICE_PRIVACY      = 0x00000400; /* Voice Privacy is enabled */

        public static final int ALL = HOLD | SUPPORT_HOLD | MERGE_CALLS | SWAP_CALLS | ADD_CALL
                | RESPOND_VIA_TEXT | MUTE | GENERIC_CONFERENCE | MODIFY_CALL | ADD_PARTICIPANT
                | VOICE_PRIVACY;
    }

    /**
     * Copy of com.android.internal.telephony.gsm.SuppServiceNotification class
     *  to avoid dependency on telephony lib.
     */
    public static class SsNotification {
        /** Type of notification: 0 = MO; 1 = MT */
        public int notificationType;
        /** TS 27.007 7.17 "code1" or "code2" */
        public int code;
        /** TS 27.007 7.17 "index" */
        public int index;
        /** TS 27.007 7.17 "type" (MT only) */
        public int type;
        /** TS 27.007 7.17 "number" (MT only) */
        public String number;

        static public final int MO_CODE_UNCONDITIONAL_CF_ACTIVE     = 0;
        static public final int MO_CODE_SOME_CF_ACTIVE              = 1;
        static public final int MO_CODE_CALL_FORWARDED              = 2;
        static public final int MO_CODE_CALL_IS_WAITING             = 3;
        static public final int MO_CODE_CUG_CALL                    = 4;
        static public final int MO_CODE_OUTGOING_CALLS_BARRED       = 5;
        static public final int MO_CODE_INCOMING_CALLS_BARRED       = 6;
        static public final int MO_CODE_CLIR_SUPPRESSION_REJECTED   = 7;
        static public final int MO_CODE_CALL_DEFLECTED              = 8;

        static public final int MT_CODE_FORWARDED_CALL              = 0;
        static public final int MT_CODE_CUG_CALL                    = 1;
        static public final int MT_CODE_CALL_ON_HOLD                = 2;
        static public final int MT_CODE_CALL_RETRIEVED              = 3;
        static public final int MT_CODE_MULTI_PARTY_CALL            = 4;
        static public final int MT_CODE_ON_HOLD_CALL_RELEASED       = 5;
        static public final int MT_CODE_FORWARD_CHECK_RECEIVED      = 6;
        static public final int MT_CODE_CALL_CONNECTING_ECT         = 7;
        static public final int MT_CODE_CALL_CONNECTED_ECT          = 8;
        static public final int MT_CODE_DEFLECTED_CALL              = 9;
        static public final int MT_CODE_ADDITIONAL_CALL_FORWARDED   = 10;

        @Override
        public String toString() {
            return super.toString() + " mobile"
                    + (notificationType == 0 ? " originated " : " terminated ")
                    + " code: " + code
                    + " index: " + index
                    + " \""
                    + PhoneNumberUtils.stringFromStringAndTOA(number, type) + "\" ";
        }
    }

    /**
     * Copy of states found in Connection object since Connection object is not available to the UI
     * code.
     * TODO: Consider cutting this down to only the types used by the UI.
     * TODO: Consider adding a CUSTOM cause type and a customDisconnect member variable to
     *       the Call object.  This would allow OEMs to extend the cause list without
     *       needing to alter our implementation.
     */
    public enum DisconnectCause {
        NOT_DISCONNECTED,               /* has not yet disconnected */
        INCOMING_MISSED,                /* an incoming call that was missed and never answered */
        NORMAL,                         /* normal; remote */
        LOCAL,                          /* normal; local hangup */
        BUSY,                           /* outgoing call to busy line */
        CONGESTION,                     /* outgoing call to congested network */
        MMI,                            /* not presently used; dial() returns null */
        INVALID_NUMBER,                 /* invalid dial string */
        NUMBER_UNREACHABLE,             /* cannot reach the peer */
        SERVER_UNREACHABLE,             /* cannot reach the server */
        INVALID_CREDENTIALS,            /* invalid credentials */
        OUT_OF_NETWORK,                 /* calling from out of network is not allowed */
        SERVER_ERROR,                   /* server error */
        TIMED_OUT,                      /* client timed out */
        LOST_SIGNAL,
        LIMIT_EXCEEDED,                 /* eg GSM ACM limit exceeded */
        INCOMING_REJECTED,              /* an incoming call that was rejected */
        POWER_OFF,                      /* radio is turned off explicitly */
        OUT_OF_SERVICE,                 /* out of service */
        ICC_ERROR,                      /* No ICC, ICC locked, or other ICC error */
        CALL_BARRED,                    /* call was blocked by call barring */
        FDN_BLOCKED,                    /* call was blocked by fixed dial number */
        CS_RESTRICTED,                  /* call was blocked by restricted all voice access */
        CS_RESTRICTED_NORMAL,           /* call was blocked by restricted normal voice access */
        CS_RESTRICTED_EMERGENCY,        /* call was blocked by restricted emergency voice access */
        UNOBTAINABLE_NUMBER,            /* Unassigned number (3GPP TS 24.008 table 10.5.123) */
        CDMA_LOCKED_UNTIL_POWER_CYCLE,  /* MS is locked until next power cycle */
        CDMA_DROP,
        CDMA_INTERCEPT,                 /* INTERCEPT order received, MS state idle entered */
        CDMA_REORDER,                   /* MS has been redirected, call is cancelled */
        CDMA_SO_REJECT,                 /* service option rejection */
        CDMA_RETRY_ORDER,               /* requested service is rejected, retry delay is set */
        CDMA_ACCESS_FAILURE,
        CDMA_PREEMPTED,
        CDMA_NOT_EMERGENCY,              /* not an emergency call */
        CDMA_ACCESS_BLOCKED,            /* Access Blocked by CDMA network */
        DIAL_MODIFIED_TO_USSD,          /* DIAL request modified to USSD */
        DIAL_MODIFIED_TO_SS,            /* DIAL request modified to SS */
        DIAL_MODIFIED_TO_DIAL,          /* DIAL request modified to DIAL with diferent data */
        ERROR_UNSPECIFIED,
        UNKNOWN,                        /* Disconnect cause not known */
        SRVCC_CALL_DROP,                /* Call dropped because of SRVCC*/
        ANSWERED_ELSEWHERE,             /* MT call has ended due to a release from the network
                                         * because the call was answered elsewhere */
        CALL_FAIL_MISC                  /* miscellaneous error not covered in above errors */
    }

    private static final Map<Integer, String> STATE_MAP = ImmutableMap.<Integer, String>builder()
            .put(Call.State.ACTIVE, "ACTIVE")
            .put(Call.State.CALL_WAITING, "CALL_WAITING")
            .put(Call.State.DIALING, "DIALING")
            .put(Call.State.REDIALING, "REDIALING")
            .put(Call.State.IDLE, "IDLE")
            .put(Call.State.INCOMING, "INCOMING")
            .put(Call.State.ONHOLD, "ONHOLD")
            .put(Call.State.INVALID, "INVALID")
            .put(Call.State.DISCONNECTING, "DISCONNECTING")
            .put(Call.State.DISCONNECTED, "DISCONNECTED")
            .put(Call.State.CONFERENCED, "CONFERENCED")
            .build();

    // Number presentation type for caller id display
    // normal
    public static int PRESENTATION_ALLOWED = PhoneConstants.PRESENTATION_ALLOWED;
    // block by user
    public static int PRESENTATION_RESTRICTED = PhoneConstants.PRESENTATION_RESTRICTED;
    // no specified or unknown by network
    public static int PRESENTATION_UNKNOWN = PhoneConstants.PRESENTATION_UNKNOWN;
    // show pay phone info
    public static int PRESENTATION_PAYPHONE = PhoneConstants.PRESENTATION_PAYPHONE;

    private CallDetails mCallDetails;
    private CallDetails mCallModifyDetails;

    // Unique identifier for the call
    private int mCallId;

    private CallIdentification mIdentification;

    // The current state of the call
    private int mState = State.INVALID;

    // Reason for disconnect. Valid when the call state is DISCONNECTED.
    private DisconnectCause mDisconnectCause = DisconnectCause.UNKNOWN;

    // Bit mask of capabilities unique to this call.
    private int mCapabilities;

    // Time that this call transitioned into ACTIVE state from INCOMING, WAITING, or OUTGOING.
    private long mConnectTime = 0;

    // List of call Ids for for this call.  (Used for managing conference calls).
    private SortedSet<Integer> mChildCallIds = Sets.newSortedSet();

    // Gateway number used to dial this call
    private String mGatewayNumber;

    // Gateway service package name
    private String mGatewayPackage;

    // Whether the call was forwarded from another party (GSM only)
    private boolean mForwarded;

    // Whether the call is held remotely
    private boolean mHeldRemotely;

    // Supplementary Service notification for GSM calls
    private SsNotification mSsNotification;

    // Holds the subscription id, to which this call belongs to.
    private int mSubscription = MSimConstants.INVALID_SUBSCRIPTION;

    public Call(int callId) {
        mCallId = callId;
        mIdentification = new CallIdentification(mCallId);
        mCallDetails = new CallDetails();
        mCallModifyDetails = new CallDetails();
    }

    public Call(Call call) {
        mCallId = call.mCallId;
        mIdentification = new CallIdentification(call.mIdentification);
        mState = call.mState;
        mDisconnectCause = call.mDisconnectCause;
        mCapabilities = call.mCapabilities;
        mConnectTime = call.mConnectTime;
        mChildCallIds = new TreeSet<Integer>(call.mChildCallIds);
        mGatewayNumber = call.mGatewayNumber;
        mGatewayPackage = call.mGatewayPackage;
        mForwarded = call.mForwarded;
        mHeldRemotely = call.mHeldRemotely;
        mCallDetails = new CallDetails();
        mCallModifyDetails = new CallDetails();
        copyDetails(call.mCallDetails, mCallDetails);
        copyDetails(call.mCallModifyDetails, mCallModifyDetails);
        mSubscription = call.mSubscription;
    }

    private void copyDetails(CallDetails src, CallDetails dest) {
        dest.setCallType(src.getCallType());
        dest.setCallDomain(src.getCallDomain());
        dest.setExtras(src.getExtras());
        dest.setErrorInfo(src.getErrorInfo());
        dest.setConfUriList(src.getConfParticipantList());
        dest.setMpty(src.isMpty());
    }

    public int getCallId() {
        return mCallId;
    }

    public CallIdentification getIdentification() {
        return mIdentification;
    }

    public String getNumber() {
        return mIdentification.getNumber();
    }

    public void setNumber(String number) {
        mIdentification.setNumber(number);
    }

    public int getState() {
        return mState;
    }

    public void setState(int state) {
        mState = state;
    }

    public CallDetails getCallDetails() {
        return mCallDetails;
    }

    public void setCallDetails(CallDetails calldetails) {
        mCallDetails = calldetails;
    }

    public CallDetails getCallModifyDetails() {
        return mCallModifyDetails;
    }

    public void setCallModifyDetails(CallDetails calldetails) {
        mCallModifyDetails = calldetails;
    }

    public int getNumberPresentation() {
        return mIdentification.getNumberPresentation();
    }

    public void setNumberPresentation(int presentation) {
        mIdentification.setNumberPresentation(presentation);
    }

    public int getCnapNamePresentation() {
        return mIdentification.getCnapNamePresentation();
    }

    public void setCnapNamePresentation(int presentation) {
        mIdentification.setCnapNamePresentation(presentation);
    }

    public String getCnapName() {
        return mIdentification.getCnapName();
    }

    public void setCnapName(String cnapName) {
        mIdentification.setCnapName(cnapName);
    }

    public DisconnectCause getDisconnectCause() {
        if (mState == State.DISCONNECTED || mState == State.IDLE) {
            return mDisconnectCause;
        }

        return DisconnectCause.NOT_DISCONNECTED;
    }

    public void setDisconnectCause(DisconnectCause cause) {
        mDisconnectCause = cause;
    }

    public int getCapabilities() {
        return mCapabilities;
    }

    public void setCapabilities(int capabilities) {
        mCapabilities = (Capabilities.ALL & capabilities);
    }

    public boolean can(int capabilities) {
        return (capabilities == (capabilities & mCapabilities));
    }

    public void addCapabilities(int capabilities) {
        setCapabilities(capabilities | mCapabilities);
    }

    public void setConnectTime(long connectTime) {
        mConnectTime = connectTime;
    }

    public long getConnectTime() {
        return mConnectTime;
    }

    public boolean isForwarded() {
        return mForwarded;
    }

    public boolean isHeldRemotely() {
        return mHeldRemotely;
    }

    public void removeChildId(int id) {
        mChildCallIds.remove(id);
    }

    public void removeAllChildren() {
        mChildCallIds.clear();
    }

    public void addChildId(int id) {
        mChildCallIds.add(id);
    }

    public ImmutableSortedSet<Integer> getChildCallIds() {
        return ImmutableSortedSet.copyOf(mChildCallIds);
    }

    public boolean isConferenceCall() {
        return mChildCallIds.size() >= 2 || mCallDetails.isMpty();
    }

    public String getGatewayNumber() {
        return mGatewayNumber;
    }

    public void setGatewayNumber(String number) {
        mGatewayNumber = number;
    }

    public String getGatewayPackage() {
        return mGatewayPackage;
    }

    public void setGatewayPackage(String packageName) {
        mGatewayPackage = packageName;
    }

    public void setForwarded(boolean forwarded) {
        mForwarded = forwarded;
    }

    public void setHeldRemotely(boolean heldRemotely) {
        mHeldRemotely = heldRemotely;
    }

    public SsNotification getSuppServNotification() {
        return mSsNotification;
    }

    public void setSuppServNotification(SsNotification notification) {
        mSsNotification = notification;
    }

    public int getSubscription() {
        return mSubscription;
    }

    public void setSubscription(int subscription) {
        mSubscription = subscription;
    }

    /**
     * Parcelable implementation
     */

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mCallId);
        dest.writeInt(mState);
        dest.writeString(getDisconnectCause().toString());
        dest.writeInt(getCapabilities());
        dest.writeLong(getConnectTime());
        dest.writeIntArray(Ints.toArray(mChildCallIds));
        dest.writeString(getGatewayNumber());
        dest.writeString(getGatewayPackage());
        dest.writeParcelable(mIdentification, 0);
        dest.writeInt(mForwarded ? 1 : 0);
        dest.writeInt(mHeldRemotely ? 1 : 0);
        int hasSuppServNotification = 0;
        if (mSsNotification != null) hasSuppServNotification = 1;
        dest.writeInt(hasSuppServNotification);
        if (hasSuppServNotification == 1) {
            dest.writeInt(mSsNotification.notificationType);
            dest.writeInt(mSsNotification.code);
            dest.writeInt(mSsNotification.index);
            dest.writeInt(mSsNotification.type);
            dest.writeString(mSsNotification.number);
        }
        dest.writeParcelable(mCallDetails, 1);
        dest.writeParcelable(mCallModifyDetails, 2);
        dest.writeInt(mSubscription);
    }

    /**
     * Constructor for Parcelable implementation.
     */
    private Call(Parcel in) {
        mCallId = in.readInt();
        mState = in.readInt();
        mDisconnectCause = DisconnectCause.valueOf(in.readString());
        mCapabilities = in.readInt();
        mConnectTime = in.readLong();
        mChildCallIds.addAll(Ints.asList(in.createIntArray()));
        mGatewayNumber = in.readString();
        mGatewayPackage = in.readString();
        mIdentification = in.readParcelable(CallIdentification.class.getClassLoader());
        mForwarded = in.readInt() != 0;
        mHeldRemotely = in.readInt() != 0;
        int hasSuppServNotification = in.readInt();
        if (hasSuppServNotification == 1) {
            mSsNotification = new SsNotification();
            mSsNotification.notificationType = in.readInt();
            mSsNotification.code =  in.readInt();
            mSsNotification.index = in.readInt();
            mSsNotification.type = in.readInt();
            mSsNotification.number = in.readString();
        }
        mCallDetails = in.readParcelable(CallDetails.class.getClassLoader());
        mCallModifyDetails = in.readParcelable(CallDetails.class.getClassLoader());
        mSubscription = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Creates Call objects for Parcelable implementation.
     */
    public static final Parcelable.Creator<Call> CREATOR
            = new Parcelable.Creator<Call>() {

        @Override
        public Call createFromParcel(Parcel in) {
            return new Call(in);
        }

        @Override
        public Call[] newArray(int size) {
            return new Call[size];
        }
    };

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("mCallId", mCallId)
                .add("mState", STATE_MAP.get(mState))
                .add("mDisconnectCause", mDisconnectCause)
                .add("mCapabilities", mCapabilities)
                .add("mConnectTime", mConnectTime)
                .add("mChildCallIds", mChildCallIds)
                .add("mGatewayNumber", MoreStrings.toSafeString(mGatewayNumber))
                .add("mGatewayPackage", mGatewayPackage)
                .add("mIdentification", mIdentification)
                .add("mForwarded", mForwarded)
                .add("mHeldRemotely", mHeldRemotely)
                .add("mSsNotification", mSsNotification)
                .add("mCallDetails", mCallDetails)
                .add("mCallModifyDetails", mCallModifyDetails)
                .add("mSubscription", mSubscription)
                .toString();
    }
}
