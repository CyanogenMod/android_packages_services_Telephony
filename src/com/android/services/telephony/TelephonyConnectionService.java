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

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.Conference;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.widget.Toast;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.ModemStackController;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.cdma.CDMAPhone;
import com.android.internal.telephony.TelephonyProperties;
import com.android.phone.MMIDialogActivity;

import java.util.ArrayList;
import java.util.List;
import android.os.Bundle;
import java.util.Objects;

/**
 * Service for making GSM and CDMA connections.
 */
public class TelephonyConnectionService extends ConnectionService {
    private TelephonyConferenceController[] mTelephonyConferenceController;
    private CdmaConferenceController[] mCdmaConferenceController;
    private ImsConferenceController[] mImsConferenceController;

    private ComponentName mExpectedComponentName = null;
    private EmergencyCallHelper mEmergencyCallHelper;
    private EmergencyTonePlayer mEmergencyTonePlayer;
    private ConnectionRequest mRequest;
    static int [] sLchState = new int[TelephonyManager.getDefault().getPhoneCount()];

    static final int SINGLE_DIGIT_DIALED =    1;

    /**
     * A listener to actionable events specific to the TelephonyConnection.
     */
    private final TelephonyConnection.TelephonyConnectionListener mTelephonyConnectionListener =
            new TelephonyConnection.TelephonyConnectionListener() {
        @Override
        public void onOriginalConnectionConfigured(TelephonyConnection c) {
            addConnectionToConferenceController(c);
        }

        @Override
        public void onEmergencyRedial(
                TelephonyConnection connection, PhoneAccountHandle redialPhoneAccount,
                        int phoneId) {
            Log.d(this,"onEmergencyRedial");
            String number = connection.getAddress().getSchemeSpecificPart();
            Phone phone = PhoneFactory.getPhone(phoneId);

            Log.i(this, "setPhoneAccountHandle, account = " + redialPhoneAccount);
            connection.setPhoneAccountHandle(redialPhoneAccount);

            Bundle bundle = mRequest.getExtras();
            com.android.internal.telephony.Connection originalConnection;
            try {
                originalConnection = phone.dial(number, mRequest.getVideoState(), bundle);
            } catch (CallStateException e) {
                Log.e(this, e, "placeOutgoingConnection, phone.dial exception: " + e);
                connection.setDisconnected(DisconnectCauseUtil.toTelecomDisconnectCause(
                        android.telephony.DisconnectCause.OUTGOING_FAILURE,
                        e.getMessage()));
                return;
            }

            if (originalConnection == null) {
                int telephonyDisconnectCause = android.telephony.DisconnectCause.OUTGOING_FAILURE;
                Log.d(this, "placeOutgoingConnection, phone.dial returned null");
                connection.setDisconnected(DisconnectCauseUtil.toTelecomDisconnectCause(
                        telephonyDisconnectCause, "Connection is null"));
            } else {
                connection.setOriginalConnection(originalConnection);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        int size = TelephonyManager.getDefault().getPhoneCount();
        mTelephonyConferenceController = new TelephonyConferenceController[size];
        mCdmaConferenceController = new CdmaConferenceController[size];
        mImsConferenceController = new ImsConferenceController[size];
        for (int i = 0; i < size; i++) {
            mTelephonyConferenceController[i] = new TelephonyConferenceController(this);
            mCdmaConferenceController[i] = new CdmaConferenceController(this);
            mImsConferenceController [i] = new ImsConferenceController(this);
        }
        mExpectedComponentName = new ComponentName(this, this.getClass());
        mEmergencyTonePlayer = new EmergencyTonePlayer(this);
    }

    @Override
    public Connection onCreateOutgoingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount,
            final ConnectionRequest request) {
        Log.i(this, "onCreateOutgoingConnection, request: " + request);

        Bundle bundle = request.getExtras();
        boolean isSkipSchemaOrConfUri = (bundle != null) && (bundle.getBoolean(
                TelephonyProperties.EXTRA_SKIP_SCHEMA_PARSING, false) ||
                bundle.getBoolean(TelephonyProperties.EXTRA_DIAL_CONFERENCE_URI, false));
        Uri handle = request.getAddress();
        if (!isSkipSchemaOrConfUri && handle == null) {
            Log.d(this, "onCreateOutgoingConnection, handle is null");
            return Connection.createFailedConnection(
                    DisconnectCauseUtil.toTelecomDisconnectCause(
                            android.telephony.DisconnectCause.NO_PHONE_NUMBER_SUPPLIED,
                            "No phone number supplied"));
        }
        if (handle == null) handle = Uri.EMPTY;
        String scheme = handle.getScheme();
        final String number;
        if (PhoneAccount.SCHEME_VOICEMAIL.equals(scheme)) {
            // TODO: We don't check for SecurityException here (requires
            // CALL_PRIVILEGED permission).
            final Phone phone = getPhoneForAccount(request.getAccountHandle(), false);
            if (phone == null) {
                Log.d(this, "onCreateOutgoingConnection, phone is null");
                return Connection.createFailedConnection(
                        DisconnectCauseUtil.toTelecomDisconnectCause(
                                android.telephony.DisconnectCause.OUT_OF_SERVICE,
                                "Phone is null"));
            }
            number = phone.getVoiceMailNumber();
            if (TextUtils.isEmpty(number)) {
                Log.d(this, "onCreateOutgoingConnection, no voicemail number set.");
                return Connection.createFailedConnection(
                        DisconnectCauseUtil.toTelecomDisconnectCause(
                                android.telephony.DisconnectCause.VOICEMAIL_NUMBER_MISSING,
                                "Voicemail scheme provided but no voicemail number set."));
            }

            // Convert voicemail: to tel:
            handle = Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null);
        } else {
            if (!isSkipSchemaOrConfUri && !PhoneAccount.SCHEME_TEL.equals(scheme)) {
                Log.d(this, "onCreateOutgoingConnection, Handle %s is not type tel", scheme);
                return Connection.createFailedConnection(
                        DisconnectCauseUtil.toTelecomDisconnectCause(
                                android.telephony.DisconnectCause.INVALID_NUMBER,
                                "Handle scheme is not type tel"));
            }

            number = handle.getSchemeSpecificPart();
            if (!isSkipSchemaOrConfUri && TextUtils.isEmpty(number)) {
                Log.d(this, "onCreateOutgoingConnection, unable to parse number");
                return Connection.createFailedConnection(
                        DisconnectCauseUtil.toTelecomDisconnectCause(
                                android.telephony.DisconnectCause.INVALID_NUMBER,
                                "Unable to parse number"));
            }
        }

        boolean isEmergencyNumber = PhoneNumberUtils.isPotentialEmergencyNumber(number);

        // Get the right phone object from the account data passed in.
        final Phone phone = getPhoneForAccount(request.getAccountHandle(), isEmergencyNumber);
        if (phone == null) {
            Log.d(this, "onCreateOutgoingConnection, phone is null");
            return Connection.createFailedConnection(
                    DisconnectCauseUtil.toTelecomDisconnectCause(
                            android.telephony.DisconnectCause.OUT_OF_SERVICE, "Phone is null"));
        }

        // Check both voice & data RAT to enable normal CS call,
        // when voice RAT is OOS but Data RAT is present.
        int state = phone.getServiceState().getState();
        if (state == ServiceState.STATE_OUT_OF_SERVICE) {
            if (phone.getServiceState().getDataNetworkType() ==
                    ServiceState.RIL_RADIO_TECHNOLOGY_LTE) {
                state = phone.getServiceState().getDataRegState();
            }
        }
        boolean useEmergencyCallHelper = false;

        if (isEmergencyNumber) {
            mRequest = request;
            if (phone.getBaseServiceState().getState() == ServiceState.STATE_POWER_OFF) {
                useEmergencyCallHelper = true;
            }
        } else {
            switch (state) {
                case ServiceState.STATE_IN_SERVICE:
                case ServiceState.STATE_EMERGENCY_ONLY:
                    break;
                case ServiceState.STATE_OUT_OF_SERVICE:
                    return Connection.createFailedConnection(
                            DisconnectCauseUtil.toTelecomDisconnectCause(
                                    android.telephony.DisconnectCause.OUT_OF_SERVICE,
                                    "ServiceState.STATE_OUT_OF_SERVICE"));
                case ServiceState.STATE_POWER_OFF:
                    return Connection.createFailedConnection(
                            DisconnectCauseUtil.toTelecomDisconnectCause(
                                    android.telephony.DisconnectCause.POWER_OFF,
                                    "ServiceState.STATE_POWER_OFF"));
                default:
                    Log.d(this, "onCreateOutgoingConnection, unknown service state: %d", state);
                    return Connection.createFailedConnection(
                            DisconnectCauseUtil.toTelecomDisconnectCause(
                                    android.telephony.DisconnectCause.OUTGOING_FAILURE,
                                    "Unknown service state " + state));
            }
        }

        final TelephonyConnection connection =
                createConnectionFor(phone, null, true /* isOutgoing */, null);
        if (connection == null) {
            return Connection.createFailedConnection(
                    DisconnectCauseUtil.toTelecomDisconnectCause(
                            android.telephony.DisconnectCause.OUTGOING_FAILURE,
                            "Invalid phone type"));
        }
        connection.setAddress(handle, PhoneConstants.PRESENTATION_ALLOWED);
        connection.setInitializing();
        connection.setVideoState(request.getVideoState());

        if (useEmergencyCallHelper) {
            if (mEmergencyCallHelper == null) {
                mEmergencyCallHelper = new EmergencyCallHelper(this);
            }
            mEmergencyCallHelper.startTurnOnRadioSequence(phone,
                    new EmergencyCallHelper.Callback() {
                        @Override
                        public void onComplete(boolean isRadioReady) {
                            if (connection.getState() == Connection.STATE_DISCONNECTED) {
                                // If the connection has already been disconnected, do nothing.
                            } else if (isRadioReady) {
                                connection.setInitialized();
                                placeOutgoingConnection(connection,
                                         PhoneFactory.getPhone(getPhoneIdForECall()), request);
                            } else {
                                Log.d(this, "onCreateOutgoingConnection, failed to turn on radio");
                                connection.setDisconnected(
                                        DisconnectCauseUtil.toTelecomDisconnectCause(
                                                android.telephony.DisconnectCause.POWER_OFF,
                                                "Failed to turn on radio."));
                                connection.destroy();
                            }
                        }
                    });

        } else {
            placeOutgoingConnection(connection, phone, request);
        }

        return connection;
    }

    @Override
    public Connection onCreateIncomingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request) {
        Log.i(this, "onCreateIncomingConnection, request: " + request);

        Phone phone = getPhoneForAccount(request.getAccountHandle(), false);
        if (phone == null) {
            return Connection.createFailedConnection(
                    DisconnectCauseUtil.toTelecomDisconnectCause(
                            android.telephony.DisconnectCause.ERROR_UNSPECIFIED));
        }

        Call call = phone.getRingingCall();
        if (!call.getState().isRinging()) {
            Log.i(this, "onCreateIncomingConnection, no ringing call");
            return Connection.createFailedConnection(
                    DisconnectCauseUtil.toTelecomDisconnectCause(
                            android.telephony.DisconnectCause.INCOMING_MISSED,
                            "Found no ringing call"));
        }

        com.android.internal.telephony.Connection originalConnection =
                call.getState() == Call.State.WAITING ?
                    call.getLatestConnection() : call.getEarliestConnection();
        if (isOriginalConnectionKnown(originalConnection)) {
            Log.i(this, "onCreateIncomingConnection, original connection already registered");
            return Connection.createCanceledConnection();
        }

        final Bundle extras = request.getExtras();
        Connection connection =
                createConnectionFor(phone, originalConnection, false /* isOutgoing */, extras);
        if (connection == null) {
            connection = Connection.createCanceledConnection();
            return Connection.createCanceledConnection();
        } else {
            return connection;
        }
    }

    @Override
    public void triggerConferenceRecalculate() {
        int size = TelephonyManager.getDefault().getPhoneCount();
        for (int i = 0; i < size; i++) {
            if (mTelephonyConferenceController[i].shouldRecalculate()) {
                 mTelephonyConferenceController[i].recalculate();
            }
        }
    }

    @Override
    public Connection onCreateUnknownConnection(PhoneAccountHandle connectionManagerPhoneAccount,
            ConnectionRequest request) {
        Log.i(this, "onCreateUnknownConnection, request: " + request);

        Phone phone = getPhoneForAccount(request.getAccountHandle(), false);
        if (phone == null) {
            return Connection.createFailedConnection(
                    DisconnectCauseUtil.toTelecomDisconnectCause(
                            android.telephony.DisconnectCause.ERROR_UNSPECIFIED));
        }

        final List<com.android.internal.telephony.Connection> allConnections = new ArrayList<>();
        final Call ringingCall = phone.getRingingCall();
        if (ringingCall.hasConnections()) {
            allConnections.addAll(ringingCall.getConnections());
        }
        final Call foregroundCall = phone.getForegroundCall();
        if (foregroundCall.hasConnections()) {
            allConnections.addAll(foregroundCall.getConnections());
        }
        if (phone.getImsPhone() != null) {
            final Call imsFgCall = phone.getImsPhone().getForegroundCall();
            if (imsFgCall.hasConnections()) {
                allConnections.addAll(imsFgCall.getConnections());
            }

            final Call imsBgCall = phone.getImsPhone().getBackgroundCall();
            if (imsBgCall.hasConnections()) {
                allConnections.addAll(imsBgCall.getConnections());
            }
        }
        final Call backgroundCall = phone.getBackgroundCall();
        if (backgroundCall.hasConnections()) {
            allConnections.addAll(phone.getBackgroundCall().getConnections());
        }

        com.android.internal.telephony.Connection unknownConnection = null;
        for (com.android.internal.telephony.Connection telephonyConnection : allConnections) {
            if ((!isOriginalConnectionKnown(telephonyConnection)) &&
                    !isConferenceHostOriginalConnection(telephonyConnection)) {
                unknownConnection = telephonyConnection;
                break;
            }
        }

        if (unknownConnection == null) {
            Log.i(this, "onCreateUnknownConnection, did not find previously unknown connection.");
            return Connection.createCanceledConnection();
        }

        TelephonyConnection connection =
                createConnectionFor(phone, unknownConnection,
                        !unknownConnection.isIncoming() /* isOutgoing */, null);

        if (connection == null) {
            return Connection.createCanceledConnection();
        } else {
            connection.updateState();
            return connection;
        }
    }

    /**
     * Check whether the original connection is part of any Imsconference
     */
    private boolean isConferenceHostOriginalConnection(
        com.android.internal.telephony.Connection originalConnection) {
        int phoneId = originalConnection.getCall().getPhone().getPhoneId();
        ArrayList<ImsConference> conferences =
                mImsConferenceController[phoneId].getImsConferences();
        if (conferences != null) {
            for (Conference conference: conferences) {
                TelephonyConnection primaryConn =
                        (TelephonyConnection) conference.getPrimaryConnection();
                if (primaryConn != null &&
                        primaryConn.getOriginalConnection() == originalConnection) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void onConference(Connection connection1, Connection connection2) {
        if (connection1 instanceof TelephonyConnection &&
                connection2 instanceof TelephonyConnection) {
            ((TelephonyConnection) connection1).performConference(
                (TelephonyConnection) connection2);
        }

    }

    @Override
    public void onAddParticipant(Connection connection, String participant) {
        if (connection instanceof TelephonyConnection) {
            ((TelephonyConnection) connection).performAddParticipant(participant);
        }

    }

    private void placeOutgoingConnection(
            TelephonyConnection connection, Phone phone, ConnectionRequest request) {
        String number = connection.getAddress().getSchemeSpecificPart();

        PhoneAccountHandle pHandle = TelecomAccountRegistry.makePstnPhoneAccountHandle(phone);
        // For ECall handling on MSIM, till the request reaches here(i.e PhoneApp)
        // we dont know on which phone account ECall can be placed, once after deciding
        // the phone account for ECall we should inform Telecomm so that
        // the proper sub information will be displayed on InCallUI.
        if (!Objects.equals(pHandle, request.getAccountHandle())) {
            Log.i(this, "setPhoneAccountHandle, account = " + pHandle);
            connection.setPhoneAccountHandle(pHandle);
        }
        Bundle bundle = request.getExtras();
        boolean isAddParticipant = (bundle != null) && bundle
                .getBoolean(TelephonyProperties.ADD_PARTICIPANT_KEY, false);
        Log.d(this, "placeOutgoingConnection isAddParticipant = " + isAddParticipant);

        com.android.internal.telephony.Connection originalConnection;
        try {
            if (isAddParticipant) {
                phone.addParticipant(number);
                return;
            } else {
                originalConnection = phone.dial(number, request.getVideoState(), bundle);
            }
        } catch (CallStateException e) {
            Log.e(this, e, "placeOutgoingConnection, phone.dial exception: " + e);
            connection.setDisconnected(DisconnectCauseUtil.toTelecomDisconnectCause(
                    android.telephony.DisconnectCause.OUTGOING_FAILURE,
                    e.getMessage()));
            return;
        }

        if (originalConnection == null) {
            int telephonyDisconnectCause = android.telephony.DisconnectCause.OUTGOING_FAILURE;
            // On GSM phones, null connection means that we dialed an MMI code
            if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM) {
                if ((number != null) && (number.length() == SINGLE_DIGIT_DIALED)) {
                    telephonyDisconnectCause = android.telephony.DisconnectCause.INVALID_NUMBER;
                    Toast.makeText(
                        getApplicationContext(),
                        getApplicationContext()
                        .getText(com.android.internal.R.string.mmiError).toString(),
                        Toast.LENGTH_SHORT).show();
                } else {
                    Log.d(this, "dialed MMI code");
                    telephonyDisconnectCause = android.telephony.DisconnectCause.DIALED_MMI;
                    final Intent intent = new Intent(this, MMIDialogActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                    startActivity(intent);
                }
            }
            Log.d(this, "placeOutgoingConnection, phone.dial returned null");
            connection.setDisconnected(DisconnectCauseUtil.toTelecomDisconnectCause(
                    telephonyDisconnectCause, "Connection is null"));
        } else {
            connection.setOriginalConnection(originalConnection);
        }
    }

    private TelephonyConnection createConnectionFor(
            Phone phone,
            com.android.internal.telephony.Connection originalConnection,
            boolean isOutgoing, Bundle extras) {
        TelephonyConnection returnConnection = null;
        int phoneType = phone.getPhoneType();
        if (phoneType == TelephonyManager.PHONE_TYPE_GSM) {
            boolean isForwarded = extras != null
                    && extras.getBoolean(TelephonyManager.EXTRA_IS_FORWARDED, false);
            returnConnection = new GsmConnection(originalConnection, isForwarded);
        } else if (phoneType == TelephonyManager.PHONE_TYPE_CDMA) {
            boolean allowMute = allowMute(phone);
            returnConnection = new CdmaConnection(
                    originalConnection, mEmergencyTonePlayer, allowMute, isOutgoing);
        }
        if (returnConnection != null) {
            // Listen to Telephony specific callbacks from the connection
            returnConnection.addTelephonyConnectionListener(mTelephonyConnectionListener);
        }
        return returnConnection;
    }

    private boolean isOriginalConnectionKnown(
            com.android.internal.telephony.Connection originalConnection) {
        for (Connection connection : getAllConnections()) {
            if (connection instanceof TelephonyConnection) {
                TelephonyConnection telephonyConnection = (TelephonyConnection) connection;
                if (telephonyConnection.getOriginalConnection() == originalConnection) {
                    return true;
                }
            }
        }
        return false;
    }

    private Phone getPhoneForAccount(PhoneAccountHandle accountHandle, boolean isEmergency) {
        if (isEmergency) {
            return PhoneFactory.getPhone(getPhoneIdForECall());
        }

        if (Objects.equals(mExpectedComponentName, accountHandle.getComponentName())) {
            if (accountHandle.getId() != null) {
                try {
                    int phoneId = SubscriptionController.getInstance().getPhoneId(
                            Integer.parseInt(accountHandle.getId()));
                    return PhoneFactory.getPhone(phoneId);
                } catch (NumberFormatException e) {
                    Log.w(this, "Could not get subId from account: " + accountHandle.getId());
                }
            }
        }
        return null;
    }

    /**
     * Determines if the connection should allow mute.
     *
     * @param phone The current phone.
     * @return {@code True} if the connection should allow mute.
     */
    private boolean allowMute(Phone phone) {
        // For CDMA phones, check if we are in Emergency Callback Mode (ECM).  Mute is disallowed
        // in ECM mode.
        if (phone.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) {
            PhoneProxy phoneProxy = (PhoneProxy)phone;
            CDMAPhone cdmaPhone = (CDMAPhone)phoneProxy.getActivePhone();
            if (cdmaPhone != null) {
                if (cdmaPhone.isInEcm()) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
      * Pick the best possible phoneId for emergency call
      * Following are the conditions applicable when deciding the sub/phone for dial
      * 1. Place E911 call on a sub(i.e Phone) whichever is IN_SERVICE/Limited
      *    Service(phoneId should be corresponds to user preferred voice sub)
      * 2. If both subs are activated and out of service(i.e. other than limited/in service)
      *    place call on voice pref sub.
      * 3. If both subs are not activated(i.e NO SIM/PIN/PUK lock state) then choose
      *    the first sub by default for placing E911 call.
      */
    public int getPhoneIdForECall() {
        SubscriptionController scontrol = SubscriptionController.getInstance();
        int voicePhoneId = scontrol.getPhoneId(scontrol.getDefaultVoiceSubId());

        //Emergency Call should always go on 1st sub .i.e.0
        //when both the subscriptions are out of service
        int phoneId = -1;
        TelephonyManager tm = TelephonyManager.from(this);
        int phoneCount = tm.getPhoneCount();

        for (int phId = 0; phId < phoneCount; phId++) {
            Phone phone = PhoneFactory.getPhone(phId);
            int ss = phone.getServiceState().getState();
            if ((ss == ServiceState.STATE_IN_SERVICE)
                    || (phone.getServiceState().isEmergencyOnly())) {
                phoneId = phId;
                if (phoneId == voicePhoneId) break;
            }
        }
        Log.v(this, "Voice phoneId in service = "+ phoneId);

        if (phoneId == -1) {
            for (int phId = 0; phId < phoneCount; phId++) {
                int[] subId = scontrol.getSubId(phId);
                if ((tm.getSimState(phId) == TelephonyManager.SIM_STATE_READY) &&
                        (scontrol.getSubState(subId[0]) == SubscriptionManager.ACTIVE)) {
                    phoneId = phId;
                    if (phoneId == voicePhoneId) break;
                }
            }
            if (phoneId == -1) {
                ModemStackController mdmStackContl = ModemStackController.getInstance();
                if (mdmStackContl != null) {
                    phoneId = mdmStackContl.getPrimarySub();
                } else {
                    phoneId = 0;
                }
            }
        }
        Log.d(this, "Voice phoneId in service = "+ phoneId +
                " preferred phoneId =" + voicePhoneId);

        return phoneId;
    }

    public static void setLocalCallHold(Phone ph, int lchStatus) {
        int phoneId = ph.getPhoneId();
        Log.i("setLocalCallHold", "lchStatus:" + lchStatus + " phoneId:"
                + phoneId + " sLchState:" + sLchState);
        if (sLchState[phoneId] != lchStatus) {
            ph.setLocalCallHold(lchStatus);
            sLchState[phoneId] = lchStatus;
        }
    }

    public static boolean isLchActive(int phoneId) {
        return (sLchState[phoneId] == 1);
    }

    @Override
    public void removeConnection(Connection connection) {
        super.removeConnection(connection);
        if (connection instanceof TelephonyConnection) {
            TelephonyConnection telephonyConnection = (TelephonyConnection) connection;
            telephonyConnection.removeTelephonyConnectionListener(mTelephonyConnectionListener);
        }
    }

    /**
     * When a {@link TelephonyConnection} has its underlying original connection configured,
     * we need to add it to the correct conference controller.
     *
     * @param connection The connection to be added to the controller
     */
    public void addConnectionToConferenceController(TelephonyConnection connection) {
        // TODO: Do we need to handle the case of the original connection changing
        // and triggering this callback multiple times for the same connection?
        // If that is the case, we might want to remove this connection from all
        // conference controllers first before re-adding it.
        int phoneId = connection.getCall().getPhone().getPhoneId();
        if (connection.isImsConnection()) {
            Log.d(this, "Adding IMS connection to conference controller: " + connection);
            mImsConferenceController[phoneId].add(connection);
        } else {
            int phoneType = connection.getCall().getPhone().getPhoneType();
            if (phoneType == TelephonyManager.PHONE_TYPE_GSM) {
                Log.d(this, "Adding GSM connection to conference controller: " + connection);
                mTelephonyConferenceController[phoneId].add(connection);
            } else if (phoneType == TelephonyManager.PHONE_TYPE_CDMA &&
                    connection instanceof CdmaConnection) {
                Log.d(this, "Adding CDMA connection to conference controller: " + connection);
                mCdmaConferenceController[phoneId].add((CdmaConnection)connection);
            }
        }
    }
}
