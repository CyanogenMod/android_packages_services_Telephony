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
 * limitations under the License
 */

package com.android.services.telephony;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.imsphone.ImsPhoneConnection;
import android.telephony.TelephonyManager;

import android.net.Uri;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.telecom.Connection;
import android.telecom.Conference;
import android.telecom.ConferenceParticipant;
import android.telecom.DisconnectCause;
import android.telecom.Log;
import android.telecom.PhoneAccountHandle;
import android.telecom.PhoneCapabilities;
import android.telecom.VideoProfile;
import android.telecom.Conference.Listener;
import android.telecom.Connection.VideoProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents an IMS conference call.
 * <P>
 * An IMS conference call consists of a conference host connection and potentially a list of
 * conference participants.  The conference host connection represents the radio connection to the
 * IMS conference server.  Since it is not a connection to any one individual, it is not represented
 * in Telecom/InCall as a call.  The conference participant information is received via the host
 * connection via a conference event package.  Conference participant connections do not represent
 * actual radio connections to the participants; they act as a virtual representation of the
 * participant, keyed by a unique endpoint {@link android.net.Uri}.
 * <p>
 * The {@link ImsConference} listens for conference event package data received via the host
 * connection and is responsible for managing the conference participant connections which represent
 * the participants.
 */
public class ImsConference extends Conference {

    /**
     * Listener used to respond to changes to conference participants.  At the conference level we
     * are most concerned with handling destruction of a conference participant.
     */
    private final Connection.Listener mParticipantListener = new Connection.Listener() {
        /**
         * Participant has been destroyed.  Remove it from the conference.
         *
         * @param connection The participant which was destroyed.
         */
        @Override
        public void onDestroyed(Connection connection) {
            ConferenceParticipantConnection participant =
                    (ConferenceParticipantConnection) connection;
            removeConferenceParticipant(participant);
            updateManageConference();
        }

    };

    /**
     * Listener used to respond to changes to the underlying radio connection for the conference
     * host connection.  Used to respond to SRVCC changes.
     */
    private final TelephonyConnection.TelephonyConnectionListener mTelephonyConnectionListener =
            new TelephonyConnection.TelephonyConnectionListener() {

        @Override
        public void onOriginalConnectionConfigured(TelephonyConnection c) {
            if (c == mConferenceHost) {
                handleOriginalConnectionChange();
            }
        }
    };

    /**
     * Listener used to respond to changes to the connection to the IMS conference server.
     */
    private final android.telecom.Connection.Listener mConferenceHostListener =
            new android.telecom.Connection.Listener() {

        /**
         * Updates the state of the conference based on the new state of the host.
         *
         * @param c The host connection.
         * @param state The new state
         */
        @Override
        public void onStateChanged(android.telecom.Connection c, int state) {
            setState(state);
        }

        /**
         * Disconnects the conference when its host connection disconnects.
         *
         * @param c The host connection.
         * @param disconnectCause The host connection disconnect cause.
         */
        @Override
        public void onDisconnected(android.telecom.Connection c, DisconnectCause disconnectCause) {
            setDisconnected(disconnectCause);
        }

        /**
         * Handles destruction of the host connection; once the host connection has been
         * destroyed, cleans up the conference participant connection.
         *
         * @param connection The host connection.
         */
        @Override
        public void onDestroyed(android.telecom.Connection connection) {
            disconnectConferenceParticipants();
        }

        /**
         * Handles changes to conference participant data as reported by the conference host
         * connection.
         *
         * @param c The connection.
         * @param participants The participant information.
         */
        @Override
        public void onConferenceParticipantsChanged(android.telecom.Connection c,
                List<ConferenceParticipant> participants) {

            if (c == null || participants == null) {
                return;
            }
            Log.v(this, "onConferenceParticipantsChanged: %d participants", participants.size());
            TelephonyConnection telephonyConnection = (TelephonyConnection) c;
            handleConferenceParticipantsUpdate(telephonyConnection, participants);
        }

        @Override
        public void onVideoStateChanged(android.telecom.Connection c, int videoState) {
            Log.d(this, "onVideoStateChanged video state %d", videoState);
            setVideoState(c, videoState);
        }

        @Override
        public void onVideoProviderChanged(android.telecom.Connection c,
                Connection.VideoProvider videoProvider) {
            Log.d(this, "onVideoProviderChanged: Connection: %s, VideoProvider: %s", c,
                    videoProvider);
            setVideoProvider(c, videoProvider);
        }

        @Override
        public void onConnectionCapabilitiesChanged(Connection c, int callCapabilities) {
            Log.d(this, "onConnectionCapabilitiesChanged: Connection: %s, callCapabilities: %s", c,
                    callCapabilities);
            int capabilites = ImsConference.this.getCapabilities();
            setCapabilities(applyVideoCapabilities(capabilites, callCapabilities));
        }
    };

    /**
     * The telephony connection service; used to add new participant connections to Telecom.
     */
    private TelephonyConnectionService mTelephonyConnectionService;

    /**
     * The connection to the conference server which is hosting the conference.
     */
    private TelephonyConnection mConferenceHost;

    /**
     * The known conference participant connections.  The HashMap is keyed by endpoint Uri.
     * A {@link ConcurrentHashMap} is used as there is a possibility for radio events impacting the
     * available participants to occur at the same time as an access via the connection service.
     */
    private final ConcurrentHashMap<Uri, ConferenceParticipantConnection>
            mConferenceParticipantConnections =
                    new ConcurrentHashMap<Uri, ConferenceParticipantConnection>(8, 0.9f, 1);

    private static final int EVENT_REQUEST_ADD_PARTICIPANTS = 1;
    private static final int EVENT_ADD_PARTICIPANTS_DONE = 2;
    private static final String PARTICIPANTS_LIST_SEPARATOR = ";";
    // Pending participants to invite to conference
    private ArrayList<String> mPendingParticipantsList = new ArrayList<String>(0);

    private Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage (Message msg) {
            AsyncResult ar;
            Log.i(this, "handleMessage what=" + msg.what);
            switch (msg.what) {
                case EVENT_REQUEST_ADD_PARTICIPANTS:
                    if (msg.obj instanceof String) {
                        processAddParticipantsList((String)msg.obj);
                    }
                    break;
                case EVENT_ADD_PARTICIPANTS_DONE:
                    ar = (AsyncResult)msg.obj;
                    processAddParticipantResponse((ar.exception == null));
                    break;
            }
        }
    };

    private void processAddParticipantsList(String dialString) {
        boolean initAdding = false;
        String[] participantsArr = dialString.split(PARTICIPANTS_LIST_SEPARATOR);
        int numOfParticipants = ((participantsArr == null)? 0: participantsArr.length);
        Log.d(this,"processAddPartList: no of particpants = " + numOfParticipants
                + " pending = " + mPendingParticipantsList.size());
        if (numOfParticipants > 0) {
            if (mPendingParticipantsList.size() == 0) {
                //directly add participant if no pending participants.
                initAdding = true;
            }
            for (String participant: participantsArr) {
                mPendingParticipantsList.add(participant);
            }
            if (initAdding) {
                processNextParticipant();
            }
        }
    }

    private void processNextParticipant() {
        if (mPendingParticipantsList.size() > 0) {
            if (addParticipantInternal(mPendingParticipantsList.get(0))) {
                Log.d(this,"processNextParticipant: sent request");
            } else {
                Log.d(this,"processNextParticipant: failed. Clear pending list.");
                mPendingParticipantsList.clear();
            }
        }
    }

    private void processAddParticipantResponse(boolean success) {
        Log.d(this,"processAddPartResp: success = " + success + " pending = " +
                (mPendingParticipantsList.size() - 1));
        if (mPendingParticipantsList.size() > 0) {
            mPendingParticipantsList.remove(0);
            processNextParticipant();
        }
    }

    public void updateConferenceStateAfterCreation() {
        if (mConferenceHost != null) {
            Log.v(this, "updateConferenceStateAfterCreation :: process participant update");
            handleConferenceParticipantsUpdate(mConferenceHost,
                    mConferenceHost.getConferenceParticipants());
        } else {
            Log.v(this, "updateConferenceStateAfterCreation :: null mConferenceHost");
        }
    }

    /**
     * Initializes a new {@link ImsConference}.
     *
     * @param telephonyConnectionService The connection service responsible for adding new
     *                                   conferene participants.
     * @param conferenceHost The telephony connection hosting the conference.
     */
    public ImsConference(TelephonyConnectionService telephonyConnectionService,
            TelephonyConnection conferenceHost) {

        super(null);
        // Specify the connection time of the conference to be the connection time of the original
        // connection
        setConnectTimeMillis(conferenceHost.getOriginalConnection().getConnectTime());
        mTelephonyConnectionService = telephonyConnectionService;
        setConferenceHost(conferenceHost);
        if (conferenceHost != null && conferenceHost.getCall() != null
                && conferenceHost.getCall().getPhone() != null) {
            mPhoneAccount = TelecomAccountRegistry.makePstnPhoneAccountHandle(
                    conferenceHost.getCall().getPhone());
            Log.v(this, "set phacc to " + mPhoneAccount);
        }

        int capabilities = Connection.CAPABILITY_SUPPORT_HOLD |
                Connection.CAPABILITY_HOLD |
                Connection.CAPABILITY_MUTE | Connection.ADD_PARTICIPANT;

        capabilities = applyVideoCapabilities(capabilities, mConferenceHost.getCallCapabilities());
        setCapabilities(capabilities);

    }

    // FIXME MR1_INTERNAL, move IMS capabilities
    private int applyVideoCapabilities(int conferenceCapabilities, int capabilities) {
        if (PhoneCapabilities.can(capabilities, PhoneCapabilities.SUPPORTS_VT_LOCAL)) {
            conferenceCapabilities = applyCapability(conferenceCapabilities,
                    PhoneCapabilities.SUPPORTS_VT_LOCAL);
        } else {
            conferenceCapabilities = removeCapability(conferenceCapabilities,
                    PhoneCapabilities.SUPPORTS_VT_LOCAL);
        }

        if (PhoneCapabilities.can(capabilities, PhoneCapabilities.SUPPORTS_VT_REMOTE)) {
            conferenceCapabilities = applyCapability(conferenceCapabilities,
                    PhoneCapabilities.SUPPORTS_VT_REMOTE);
        } else {
            conferenceCapabilities = removeCapability(conferenceCapabilities,
                    PhoneCapabilities.SUPPORTS_VT_REMOTE);
        }

        if (PhoneCapabilities.can(capabilities, PhoneCapabilities.CALL_TYPE_MODIFIABLE)) {
            conferenceCapabilities = applyCapability(conferenceCapabilities,
                    PhoneCapabilities.CALL_TYPE_MODIFIABLE);
        } else {
            conferenceCapabilities = removeCapability(conferenceCapabilities,
                    PhoneCapabilities.CALL_TYPE_MODIFIABLE);
        }
        return conferenceCapabilities;
    }

    @Override
    public android.telecom.Connection getPrimaryConnection() {
        return mConferenceHost;
    }

    /**
     * Returns VideoProvider of the conference. This can be null.
     *
     * @hide
     */
    @Override
    public VideoProvider getVideoProvider() {
        if (mConferenceHost != null) {
            return mConferenceHost.getVideoProvider();
        }
        return null;
    }

    /**
     * Returns video state of conference
     *
     * @hide
     */
    @Override
    public int getVideoState() {
        if (mConferenceHost != null) {
            return mConferenceHost.getVideoState();
        }
        return VideoProfile.VideoState.AUDIO_ONLY;
    }

    /**
     * Invoked when the Conference and all its {@link Connection}s should be disconnected.
     * <p>
     * Hangs up the call via the conference host connection.  When the host connection has been
     * successfully disconnected, the {@link #mConferenceHostListener} listener receives an
     * {@code onDestroyed} event, which triggers the conference participant connections to be
     * disconnected.
     */
    @Override
    public void onDisconnect() {
        Log.v(this, "onDisconnect: hanging up conference host.");
        if (mConferenceHost == null) {
            return;
        }

        Call call = mConferenceHost.getCall();
        if (call != null) {
            try {
                call.hangup();
            } catch (CallStateException e) {
                Log.e(this, e, "Exception thrown trying to hangup conference");
            }
        }
    }

    /**
     * Invoked when the specified {@link android.telecom.Connection} should be separated from the
     * conference call.
     * <p>
     * IMS does not support separating connections from the conference.
     *
     * @param connection The connection to separate.
     */
    @Override
    public void onSeparate(android.telecom.Connection connection) {
        Log.wtf(this, "Cannot separate connections from an IMS conference.");
    }

    /**
     * Invoked when the specified {@link android.telecom.Connection} should be merged into the
     * conference call.
     *
     * @param connection The {@code Connection} to merge.
     */
    @Override
    public void onMerge(android.telecom.Connection connection) {
        try {
            Phone phone = ((TelephonyConnection) connection).getPhone();
            if (phone != null) {
                phone.conference();
            }
        } catch (CallStateException e) {
            Log.e(this, e, "Exception thrown trying to merge call into a conference");
        }
    }

    /**
     * Invoked when the conference adds a participant to the conference call.
     *
     * @param participant The participant to be added with conference call.
     */

    @Override
    public void onAddParticipant(String participant) {
        if (participant == null || participant.isEmpty()) {
            return;
        }
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REQUEST_ADD_PARTICIPANTS, participant));
    }

    private boolean addParticipantInternal(String participant) {
        boolean ret =  false;
        try {
            Phone phone = (mConferenceHost != null) ? mConferenceHost.getPhone() : null;
            Log.d(this, "onAddParticipant mConferenceHost = " + mConferenceHost
                    + " Phone = " + phone);
            if (phone != null) {
                phone.addParticipant(participant,
                        mHandler.obtainMessage(EVENT_ADD_PARTICIPANTS_DONE));
                ret = true;
            }
        } catch (CallStateException e) {
            Log.e(this, e, "Exception thrown trying to add a participant into conference");
        }
        return ret;
    }

    /**
     * Invoked when the conference should be put on hold.
     */
    @Override
    public void onHold() {
        if (mConferenceHost == null) {
            return;
        }
        mConferenceHost.performHold();
    }

    /**
     * Invoked when the conference should be moved from hold to active.
     */
    @Override
    public void onUnhold() {
        if (mConferenceHost == null) {
            return;
        }
        mConferenceHost.performUnhold();
    }

    /**
     * Invoked to play a DTMF tone.
     *
     * @param c A DTMF character.
     */
    @Override
    public void onPlayDtmfTone(char c) {
        if (mConferenceHost == null) {
            return;
        }
        mConferenceHost.onPlayDtmfTone(c);
    }

    /**
     * Invoked to stop playing a DTMF tone.
     */
    @Override
    public void onStopDtmfTone() {
        if (mConferenceHost == null) {
            return;
        }
        mConferenceHost.onStopDtmfTone();
    }

    /**
     * Handles the addition of connections to the {@link ImsConference}.  The
     * {@link ImsConferenceController} does not add connections to the conference.
     *
     * @param connection The newly added connection.
     */
    @Override
    public void onConnectionAdded(android.telecom.Connection connection) {
        // No-op
    }

    private int applyCapability(int capabilities, int capability) {
        int newCapabilities = capabilities | capability;
        return newCapabilities;
    }

    private int removeCapability(int capabilities, int capability) {
        int newCapabilities = capabilities & ~capability;
        return newCapabilities;
    }

    /**
     * Updates the manage conference capability of the conference.  Where there are one or more
     * conference event package participants, the conference management is permitted.  Where there
     * are no conference event package participants, conference management is not permitted.
     */
    private void updateManageConference() {
        boolean couldManageConference = can(Connection.CAPABILITY_MANAGE_CONFERENCE);
        boolean canManageConference = !mConferenceParticipantConnections.isEmpty();
        Log.v(this, "updateManageConference was:%s is:%s", couldManageConference ? "Y" : "N",
                canManageConference ? "Y" : "N");

        if (couldManageConference != canManageConference) {
            int newCapabilities = getConnectionCapabilities();

            if (canManageConference) {
                addCapability(Connection.CAPABILITY_MANAGE_CONFERENCE);
            } else {
                removeCapability(Connection.CAPABILITY_MANAGE_CONFERENCE);
            }
        }
    }

    /**
     * Sets the connection hosting the conference and registers for callbacks.
     *
     * @param conferenceHost The connection hosting the conference.
     */
    private void setConferenceHost(TelephonyConnection conferenceHost) {
        if (Log.VERBOSE) {
            Log.v(this, "setConferenceHost " + conferenceHost);
        }

        mConferenceHost = conferenceHost;
        mConferenceHost.addConnectionListener(mConferenceHostListener);
        mConferenceHost.addTelephonyConnectionListener(mTelephonyConnectionListener);
        setState(mConferenceHost.getState());
    }

    /**
     * Handles state changes for conference participant(s).  The participants data passed in
     *
     * @param parent The connection which was notified of the conference participant.
     * @param participants The conference participant information.
     */
    private void handleConferenceParticipantsUpdate(
            TelephonyConnection parent, List<ConferenceParticipant> participants) {

        if (participants == null) {
            return;
        }
        boolean newParticipantsAdded = false;
        boolean oldParticipantsRemoved = false;
        ArrayList<ConferenceParticipant> newParticipants = new ArrayList<>(participants.size());
        HashSet<Uri> participantUserEntities = new HashSet<>(participants.size());

        // Add any new participants and update existing.
        for (ConferenceParticipant participant : participants) {
            Uri userEntity = participant.getHandle();

            participantUserEntities.add(userEntity);
            if (!mConferenceParticipantConnections.containsKey(userEntity)) {
                createConferenceParticipantConnection(parent, participant);
                newParticipants.add(participant);
                newParticipantsAdded = true;
            } else {
                ConferenceParticipantConnection connection =
                        mConferenceParticipantConnections.get(userEntity);
                connection.updateState(participant.getState());
                if (participant.getState() == ConferenceParticipantConnection.STATE_DISCONNECTED) {
                    removeConferenceParticipant(connection);
                    removeConnection(connection);
                }
            }
        }

        // Set state of new participants.
        if (newParticipantsAdded) {
            // Set the state of the new participants at once and add to the conference
            for (ConferenceParticipant newParticipant : newParticipants) {
                ConferenceParticipantConnection connection =
                        mConferenceParticipantConnections.get(newParticipant.getHandle());
                connection.updateState(newParticipant.getState());
            }
        }

        // Finally, remove any participants from the conference that no longer exist in the
        // conference event package data.
        Iterator<Map.Entry<Uri, ConferenceParticipantConnection>> entryIterator =
                mConferenceParticipantConnections.entrySet().iterator();
        while (entryIterator.hasNext()) {
            Map.Entry<Uri, ConferenceParticipantConnection> entry = entryIterator.next();

            if (!participantUserEntities.contains(entry.getKey())) {
                ConferenceParticipantConnection participant = entry.getValue();
                participant.setDisconnected(new DisconnectCause(DisconnectCause.CANCELED));
                removeConferenceParticipant(participant);
                removeConnection(participant);
                entryIterator.remove();
                oldParticipantsRemoved = true;
            }
        }

        // If new participants were added or old ones were removed, we need to ensure the state of
        // the manage conference capability is updated.
        if (newParticipantsAdded || oldParticipantsRemoved) {
            updateManageConference();
        }
    }

    /**
     * Creates a new {@link ConferenceParticipantConnection} to represent a
     * {@link ConferenceParticipant}.
     * <p>
     * The new connection is added to the conference controller and connection service.
     *
     * @param parent The connection which was notified of the participant change (e.g. the
     *                         parent connection).
     * @param participant The conference participant information.
     */
    private void createConferenceParticipantConnection(
            TelephonyConnection parent, ConferenceParticipant participant) {

        // Create and add the new connection in holding state so that it does not become the
        // active call.
        ConferenceParticipantConnection connection = new ConferenceParticipantConnection(
                parent.getOriginalConnection(), participant);
        connection.addConnectionListener(mParticipantListener);

        if (Log.VERBOSE) {
            Log.v(this, "createConferenceParticipantConnection: %s", connection);
        }

        mConferenceParticipantConnections.put(participant.getHandle(), connection);
        PhoneAccountHandle phoneAccountHandle =
                TelecomAccountRegistry.makePstnPhoneAccountHandle(parent.getPhone());
        mTelephonyConnectionService.addExistingConnection(phoneAccountHandle, connection);
        addConnection(connection);
    }

    /**
     * Removes a conference participant from the conference.
     *
     * @param participant The participant to remove.
     */
    private void removeConferenceParticipant(ConferenceParticipantConnection participant) {
        Log.d(this, "removeConferenceParticipant: %s", participant);

        participant.removeConnectionListener(mParticipantListener);
        participant.getUserEntity();
        mConferenceParticipantConnections.remove(participant.getUserEntity());
        mTelephonyConnectionService.removeConnection(participant);
    }

    /**
     * Disconnects all conference participants from the conference.
     */
    private void disconnectConferenceParticipants() {
        Log.v(this, "disconnectConferenceParticipants");

        for (ConferenceParticipantConnection connection :
                mConferenceParticipantConnections.values()) {

            // Mark disconnect cause as cancelled to ensure that the call is not logged in the
            // call log.
            connection.setDisconnected(new DisconnectCause(DisconnectCause.CANCELED));
            removeConferenceParticipant(connection);

            connection.destroy();
        }
        mConferenceParticipantConnections.clear();
    }

    /**
     * Handles a change in the original connection backing the conference host connection.  This can
     * happen if an SRVCC event occurs on the original IMS connection, requiring a fallback to
     * GSM or CDMA.
     * <p>
     * If this happens, we will add the conference host connection to telecom and tear down the
     * conference.
     */
    private void handleOriginalConnectionChange() {
        if (mConferenceHost == null) {
            Log.w(this, "handleOriginalConnectionChange; conference host missing.");
            return;
        }

        com.android.internal.telephony.Connection originalConnection =
                mConferenceHost.getOriginalConnection();

        if (!(originalConnection instanceof ImsPhoneConnection)) {
            if (Log.VERBOSE) {
                Log.v(this,
                        "Original connection for conference host is no longer an IMS connection; " +
                                "new connection: %s", originalConnection);
            }
            PhoneAccountHandle phoneAccountHandle =
                    TelecomAccountRegistry.makePstnPhoneAccountHandle(mConferenceHost.getPhone());
            if (mConferenceHost.getPhone().getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) {
                GsmConnection c = new GsmConnection(originalConnection, false);
                c.updateState();
                mTelephonyConnectionService.addExistingConnection(phoneAccountHandle, c);
                mTelephonyConnectionService.addConnectionToConferenceController(c);
            } // CDMA case not applicable for SRVCC
            mConferenceHost.removeConnectionListener(mConferenceHostListener);
            mConferenceHost.removeTelephonyConnectionListener(mTelephonyConnectionListener);
            mConferenceHost = null;
            setDisconnected(new DisconnectCause(DisconnectCause.OTHER));
            disconnectConferenceParticipants();
            destroy();
        }
    }

    /**
     * Changes the state of the Ims conference.
     *
     * @param state the new state.
     */
    public void setState(int state) {
        Log.v(this, "setState %s", Connection.stateToString(state));

        switch (state) {
            case Connection.STATE_INITIALIZING:
            case Connection.STATE_NEW:
            case Connection.STATE_RINGING:
                // No-op -- not applicable.
                break;
            case Connection.STATE_DIALING:
                setDialing();
                break;
            case Connection.STATE_DISCONNECTED:
                DisconnectCause disconnectCause;
                if (mConferenceHost == null) {
                    disconnectCause = new DisconnectCause(DisconnectCause.CANCELED);
                } else {
                    disconnectCause = DisconnectCauseUtil.toTelecomDisconnectCause(
                            mConferenceHost.getOriginalConnection().getDisconnectCause());
                }
                setDisconnected(disconnectCause);
                destroy();
                break;
            case Connection.STATE_ACTIVE:
                setActive();
                break;
            case Connection.STATE_HOLDING:
                setOnHold();
                break;
        }
    }

    /**
     * Builds a string representation of the {@link ImsConference}.
     *
     * @return String representing the conference.
     */
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[ImsConference objId:");
        sb.append(System.identityHashCode(this));
        sb.append(" state:");
        sb.append(Connection.stateToString(getState()));
        sb.append(" hostConnection:");
        sb.append(mConferenceHost);
        sb.append(" participants:");
        sb.append(mConferenceParticipantConnections.size());
        sb.append("]");
        return sb.toString();
    }
}
