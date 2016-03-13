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

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.CarrierConfigManager;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.widget.Toast;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.IExtTelephony;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.cdma.CDMAPhone;
import com.android.internal.telephony.TelephonyProperties;
import com.android.phone.MMIDialogActivity;
import com.android.phone.PhoneUtils;
import com.android.phone.R;

import java.util.ArrayList;
import java.util.List;
import android.os.Bundle;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Service for making GSM and CDMA connections.
 */
public class TelephonyConnectionService extends ConnectionService {

    // If configured, reject attempts to dial numbers matching this pattern.
    private static final Pattern CDMA_ACTIVATION_CODE_REGEX_PATTERN =
            Pattern.compile("\\*228[0-9]{0,2}");

    private static final int sPhoneCount = TelephonyManager.getDefault().getPhoneCount();
    private static final String EMR_DIAL_ACCOUNT = "emr_dial_account";

    private final TelephonyConferenceController[] mTelephonyConferenceController =
            new TelephonyConferenceController[sPhoneCount];
    private final CdmaConferenceController[] mCdmaConferenceController =
            new CdmaConferenceController[sPhoneCount];
    private final ImsConferenceController[] mImsConferenceController =
            new ImsConferenceController[sPhoneCount];

    private ComponentName mExpectedComponentName = null;
    private EmergencyCallHelper mEmergencyCallHelper;
    private EmergencyTonePlayer mEmergencyTonePlayer;
    private static boolean [] sLchState = new
            boolean[sPhoneCount];
    private ConnectionRequest mRequest;

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
            Bundle connExtras = connection.getExtras();
            if (connExtras == null) {
                connExtras = new Bundle();
            }
            connExtras.putParcelable(EMR_DIAL_ACCOUNT, redialPhoneAccount);
            connection.setExtras(connExtras);

            Bundle bundle = mRequest.getExtras();
            com.android.internal.telephony.Connection originalConnection;
            try {
                originalConnection = phone.dial(number, null, mRequest.getVideoState(), bundle);
            } catch (CallStateException e) {
                Log.e(this, e, "onEmergencyRedial, phone.dial exception: " + e);
                connection.setDisconnected(DisconnectCauseUtil.toTelecomDisconnectCause(
                        android.telephony.DisconnectCause.OUTGOING_FAILURE,
                        e.getMessage()));
                return;
            }

            if (originalConnection == null) {
                int telephonyDisconnectCause = android.telephony.DisconnectCause.OUTGOING_FAILURE;
                Log.d(this, "onEmergencyRedial, phone.dial returned null");
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
        int size = sPhoneCount;
        for (int i = 0; i < size; i++) {
            mTelephonyConferenceController[i] = new TelephonyConferenceController(this);
            mImsConferenceController[i] = new ImsConferenceController(this);
            mCdmaConferenceController[i] = new CdmaConferenceController(this);
        }
        mExpectedComponentName = new ComponentName(this, this.getClass());
        mEmergencyTonePlayer = new EmergencyTonePlayer(this);
        TelecomAccountRegistry.getInstance(this).setTelephonyConnectionService(this);
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

            final Phone phone = getPhoneForAccount(request.getAccountHandle(), false);
            if (phone != null && CDMA_ACTIVATION_CODE_REGEX_PATTERN.matcher(number).matches()) {
                // Obtain the configuration for the outgoing phone's SIM. If the outgoing number
                // matches the *228 regex pattern, fail the call. This number is used for OTASP, and
                // when dialed could lock LTE SIMs to 3G if not prohibited..
                boolean disableActivation = false;
                CarrierConfigManager cfgManager = (CarrierConfigManager)
                        phone.getContext().getSystemService(Context.CARRIER_CONFIG_SERVICE);
                if (cfgManager != null) {
                    disableActivation = cfgManager.getConfigForSubId(phone.getSubId())
                            .getBoolean(CarrierConfigManager.KEY_DISABLE_CDMA_ACTIVATION_CODE_BOOL);
                }

                if (disableActivation) {
                    return Connection.createFailedConnection(
                            DisconnectCauseUtil.toTelecomDisconnectCause(
                                    android.telephony.DisconnectCause
                                            .CDMA_ALREADY_ACTIVATED,
                                    "Tried to dial *228"));
                }
            }
        }

        boolean isEmergencyNumber = PhoneNumberUtils.isLocalEmergencyNumber(this, number);

        // Get the right phone object from the account data passed in.
        final Phone phone = getPhoneForAccount(request.getAccountHandle(), isEmergencyNumber);
        if (phone == null) {
            final Context context = getApplicationContext();
            if (context.getResources().getBoolean(R.bool.config_checkSimStateBeforeOutgoingCall)) {
                // Check SIM card state before the outgoing call.
                // Start the SIM unlock activity if PIN_REQUIRED.
                final Phone defaultPhone = PhoneFactory.getDefaultPhone();
                final IccCard icc = defaultPhone.getIccCard();
                IccCardConstants.State simState = IccCardConstants.State.UNKNOWN;
                if (icc != null) {
                    simState = icc.getState();
                }
                if (simState == IccCardConstants.State.PIN_REQUIRED) {
                    final String simUnlockUiPackage = context.getResources().getString(
                            R.string.config_simUnlockUiPackage);
                    final String simUnlockUiClass = context.getResources().getString(
                            R.string.config_simUnlockUiClass);
                    if (simUnlockUiPackage != null && simUnlockUiClass != null) {
                        Intent simUnlockIntent = new Intent().setComponent(new ComponentName(
                                simUnlockUiPackage, simUnlockUiClass));
                        simUnlockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            context.startActivity(simUnlockIntent);
                        } catch (ActivityNotFoundException exception) {
                            Log.e(this, exception, "Unable to find SIM unlock UI activity.");
                        }
                    }
                    return Connection.createFailedConnection(
                            DisconnectCauseUtil.toTelecomDisconnectCause(
                                    android.telephony.DisconnectCause.OUT_OF_SERVICE,
                                    "SIM_STATE_PIN_REQUIRED"));
                }
            }

            Log.d(this, "onCreateOutgoingConnection, phone is null");
            return Connection.createFailedConnection(
                    DisconnectCauseUtil.toTelecomDisconnectCause(
                            android.telephony.DisconnectCause.OUT_OF_SERVICE, "Phone is null"));
        }

        // Check both voice & data RAT to enable normal CS call,
        // when voice RAT is OOS but Data RAT is present.
        int state = phone.getServiceState().getState();
        if (state == ServiceState.STATE_OUT_OF_SERVICE) {
            int dataNetType = phone.getServiceState().getDataNetworkType();
            if (dataNetType == TelephonyManager.NETWORK_TYPE_LTE ||
                    dataNetType == TelephonyManager.NETWORK_TYPE_LTE_CA) {
                        state = phone.getServiceState().getDataRegState();
            }
        }
        boolean useEmergencyCallHelper = false;

        // If we're dialing a non-emergency number and the phone is in ECM mode, reject the call if
        // carrier configuration specifies that we cannot make non-emergency calls in ECM mode.
        if (!isEmergencyNumber && phone.isInEcm()) {
            boolean allowNonEmergencyCalls = true;
            CarrierConfigManager cfgManager = (CarrierConfigManager)
                    phone.getContext().getSystemService(Context.CARRIER_CONFIG_SERVICE);
            if (cfgManager != null) {
                allowNonEmergencyCalls = cfgManager.getConfigForSubId(phone.getSubId())
                        .getBoolean(CarrierConfigManager.KEY_ALLOW_NON_EMERGENCY_CALLS_IN_ECM_BOOL);
            }

            if (!allowNonEmergencyCalls) {
                return Connection.createFailedConnection(
                        DisconnectCauseUtil.toTelecomDisconnectCause(
                                DisconnectCause.CDMA_NOT_EMERGENCY,
                                "Cannot make non-emergency call in ECM mode."
                        ));
            }
        }

        if (isEmergencyNumber) {
            mRequest = request;
            if (!phone.isRadioOn()) {
                mRequest = request;
                useEmergencyCallHelper = true;
            }
        } else {
            switch (state) {
                case ServiceState.STATE_IN_SERVICE:
                case ServiceState.STATE_EMERGENCY_ONLY:
                    break;
                case ServiceState.STATE_OUT_OF_SERVICE:
                    if (phone.isUtEnabled() && number.endsWith("#")) {
                        Log.d(this, "onCreateOutgoingConnection dial for UT");
                        break;
                    } else {
                        return Connection.createFailedConnection(
                                DisconnectCauseUtil.toTelecomDisconnectCause(
                                        android.telephony.DisconnectCause.OUT_OF_SERVICE,
                                        "ServiceState.STATE_OUT_OF_SERVICE"));
                    }
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

        final TelephonyConnection connection = createConnectionFor(
                phone, null, true /* isOutgoing */, request.getAccountHandle(), null /* extras */);
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
                                placeOutgoingConnection(connection, phone, request);
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
                            android.telephony.DisconnectCause.ERROR_UNSPECIFIED,
                            "Phone is null"));
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
                createConnectionFor(phone, originalConnection, false /* isOutgoing */,
                        request.getAccountHandle(), extras);
        if (connection == null) {
            return Connection.createCanceledConnection();
        } else {
            return connection;
        }
    }

    @Override
    public void triggerConferenceRecalculate() {
        int size = sPhoneCount;
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
                            android.telephony.DisconnectCause.ERROR_UNSPECIFIED,
                            "Phone is null"));
        }

        final List<com.android.internal.telephony.Connection> allConnections = new ArrayList<>();
        final Call ringingCall = phone.getRingingCall();
        if (ringingCall.hasConnections()) {
            allConnections.addAll(ringingCall.getConnections());
        }
        final Call foregroundCall = phone.getForegroundCall();
        if ((foregroundCall.getState() != Call.State.DISCONNECTED)
                && (foregroundCall.hasConnections())) {
            allConnections.addAll(foregroundCall.getConnections());
        }
        if (phone.getImsPhone() != null) {
            final Call imsFgCall = phone.getImsPhone().getForegroundCall();
            if ((imsFgCall.getState() != Call.State.DISCONNECTED) && imsFgCall.hasConnections()) {
                allConnections.addAll(imsFgCall.getConnections());
            }
        }
        final Call backgroundCall = phone.getBackgroundCall();
        if (backgroundCall.hasConnections()) {
            allConnections.addAll(phone.getBackgroundCall().getConnections());
        }

        com.android.internal.telephony.Connection unknownConnection = null;
        for (com.android.internal.telephony.Connection telephonyConnection : allConnections) {
            if (!isOriginalConnectionKnown(telephonyConnection)) {
                unknownConnection = telephonyConnection;
                Log.d(this, "onCreateUnknownConnection: conn = " + unknownConnection);
                break;
            }
        }

        if (unknownConnection == null) {
            Log.i(this, "onCreateUnknownConnection, did not find previously unknown connection.");
            return Connection.createCanceledConnection();
        }

        TelephonyConnection connection =
                createConnectionFor(phone, unknownConnection,
                        !unknownConnection.isIncoming() /* isOutgoing */,
                        request.getAccountHandle(), null /* extras */);

        if (connection == null) {
            return Connection.createCanceledConnection();
        } else {
            connection.updateState();
            return connection;
        }
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

        PhoneAccountHandle pHandle = PhoneUtils.makePstnPhoneAccountHandle(phone);
        // For ECall handling on MSIM, till the request reaches here(i.e PhoneApp)
        // we dont know on which phone account ECall can be placed, once after deciding
        // the phone account for ECall we should inform Telecomm so that
        // the proper sub information will be displayed on InCallUI.
        if (TelephonyManager.getDefault().isMultiSimEnabled() && !Objects.equals(pHandle,
                request.getAccountHandle())) {
            Log.i(this, "setPhoneAccountHandle, account = " + pHandle);
            request.setAccountHandle(pHandle);
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
                originalConnection = phone.dial(number, null, request.getVideoState(), bundle);
            }
        } catch (CallStateException e) {
            Log.e(this, e, "placeOutgoingConnection, phone.dial exception: " + e);
            int cause = android.telephony.DisconnectCause.OUTGOING_FAILURE;
            if (e.getError() == CallStateException.ERROR_DISCONNECTED) {
                cause = android.telephony.DisconnectCause.OUT_OF_SERVICE;
            }
            connection.setDisconnected(DisconnectCauseUtil.toTelecomDisconnectCause(
                    cause, e.getMessage()));
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
                    int subId = phone.getSubId();
                    Log.d(this, "subId: " + subId);
                    telephonyDisconnectCause = android.telephony.DisconnectCause.DIALED_MMI;
                    final Intent intent = new Intent(this, MMIDialogActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                    if (SubscriptionManager.isValidSubscriptionId(subId)) {
                        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
                    }
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
            boolean isOutgoing,
            PhoneAccountHandle phoneAccountHandle,
            Bundle extras) {
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
            returnConnection.setVideoPauseSupported(
                    TelecomAccountRegistry.getInstance(this).isVideoPauseSupported(
                            phoneAccountHandle));
            returnConnection.setConferenceSupported(
                    TelecomAccountRegistry.getInstance(this).isMergeCallSupported(
                            phoneAccountHandle));
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
            IExtTelephony mExtTelephony =
                    IExtTelephony.Stub.asInterface(ServiceManager.getService("extphone"));
            int phoneId = 0; // default phoneId
            try {
                phoneId = mExtTelephony.getPhoneIdForECall();
                return PhoneFactory.getPhone(phoneId);
            } catch (RemoteException ex) {
                Log.e(this, ex, "Exception : " + ex);
            } catch (NullPointerException ex) {
                Log.e(this, ex, "Exception : " + ex);
            }
            return PhoneFactory.getPhone(phoneId);
        }

        int subId = PhoneUtils.getSubIdForPhoneAccountHandle(accountHandle);
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            int phoneId = SubscriptionController.getInstance().getPhoneId(subId);
            return PhoneFactory.getPhone(phoneId);
        }

        return null;
    }

    private Phone getFirstPhoneForEmergencyCall() {
        Phone selectPhone = null;
        for (int i = 0; i < TelephonyManager.getDefault().getSimCount(); i++) {
            int[] subIds = SubscriptionController.getInstance().getSubIdUsingSlotId(i);
            if (subIds.length == 0)
                continue;

            int phoneId = SubscriptionController.getInstance().getPhoneId(subIds[0]);
            Phone phone = PhoneFactory.getPhone(phoneId);
            if (phone == null)
                continue;

            if (ServiceState.STATE_IN_SERVICE == phone.getServiceState().getState()) {
                // the slot is radio on & state is in service
                Log.d(this, "pickBestPhoneForEmergencyCall, radio on & in service, slotId:" + i);
                return phone;
            } else if (ServiceState.STATE_POWER_OFF != phone.getServiceState().getState()) {
                // the slot is radio on & with SIM card inserted.
                if (TelephonyManager.getDefault().hasIccCard(i)) {
                    Log.d(this, "pickBestPhoneForEmergencyCall," +
                            "radio on and SIM card inserted, slotId:" + i);
                    selectPhone = phone;
                } else if (selectPhone == null) {
                    Log.d(this, "pickBestPhoneForEmergencyCall, radio on, slotId:" + i);
                    selectPhone = phone;
                }
            }
        }

        if (selectPhone == null) {
            Log.d(this, "pickBestPhoneForEmergencyCall, return default phone");
            selectPhone = PhoneFactory.getDefaultPhone();
        }

        return selectPhone;
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
        int phoneId;
        if (connection.getPhone()!= null) {
            phoneId = connection.getPhone().getPhoneId();
        } else {
            Log.w(this, "getPhone() has returned null, return from here." + connection);
            return;
        }

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
            Log.d(this, "Removing connection from IMS conference controller: " + connection);
            mImsConferenceController[phoneId].remove(connection);
        }
    }

    public static void setLocalCallHold(Phone ph, boolean lchStatus) {
        int phoneId = ph.getPhoneId();
        Log.d("setLocalCallHold", "lchStatus:" + lchStatus + " phoneId:"
                + phoneId + " sLchState:" + sLchState);
        if (sLchState[phoneId] != lchStatus) {
            ph.setLocalCallHold(lchStatus);
            sLchState[phoneId] = lchStatus;
        }
    }

    public static boolean isLchActive(int phoneId) {
        return sLchState[phoneId];
    }
}
