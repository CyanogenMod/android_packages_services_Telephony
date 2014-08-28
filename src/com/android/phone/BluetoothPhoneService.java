/*
 * Copyright (c) 2014, The Linux Foundation. All rights reserved.
 * Not a contribution.
 *
 * Copyright (C) 2012 The Android Open Source Project
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

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothHeadsetPhone;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemProperties;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.util.Log;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.CallManager;
import android.telephony.MSimTelephonyManager;

import com.android.phone.CallGatewayManager.RawGatewayInfo;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import org.codeaurora.btmultisim.IBluetoothDsdaService;
import android.content.ServiceConnection;
import android.os.RemoteException;
import android.content.ComponentName;


/**
 * Bluetooth headset manager for the Phone app.
 * @hide
 */
public class BluetoothPhoneService extends Service {
    private static final String TAG = "BluetoothPhoneService";
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 1)
            && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (PhoneGlobals.DBG_LEVEL >= 2);  // even more logging

    private static final String MODIFY_PHONE_STATE = android.Manifest.permission.MODIFY_PHONE_STATE;

    private BluetoothAdapter mAdapter;
    private CallManager mCM;
    private CallGatewayManager mCallGatewayManager;

    private BluetoothHeadset mBluetoothHeadset;

    private PowerManager mPowerManager;

    private WakeLock mStartCallWakeLock;  // held while waiting for the intent to start call

    private PhoneConstants.State mPhoneState = PhoneConstants.State.IDLE;
    CdmaPhoneCallState.PhoneCallState mCdmaThreeWayCallState =
                                            CdmaPhoneCallState.PhoneCallState.IDLE;

    private Call.State mForegroundCallState;
    private Call.State mRingingCallState;
    private CallNumber mRingNumber;
    // number of active calls
    int mNumActive;
    // number of background (held) calls
    int mNumHeld;
    private IBluetoothDsdaService mBluetoothDsda = null; //Handles DSDA Service.

    long mBgndEarliestConnectionTime = 0;

    // CDMA specific flag used in context with BT devices having display capabilities
    // to show which Caller is active. This state might not be always true as in CDMA
    // networks if a caller drops off no update is provided to the Phone.
    // This flag is just used as a toggle to provide a update to the BT device to specify
    // which caller is active.
    private boolean mCdmaIsSecondCallActive = false;
    private boolean mCdmaCallsSwapped = false;

    private long[] mClccTimestamps; // Timestamps associated with each clcc index
    private boolean[] mClccUsed;     // Is this clcc index in use

    private static final int GSM_MAX_CONNECTIONS = 6;  // Max connections allowed by GSM
    private static final int CDMA_MAX_CONNECTIONS = 2;  // Max connections allowed by CDMA

    @Override
    public void onCreate() {
        super.onCreate();
        mCM = CallManager.getInstance();
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mAdapter == null) {
            if (VDBG) Log.d(TAG, "mAdapter null");
            return;
        }
        mCallGatewayManager = CallGatewayManager.getInstance();

        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mStartCallWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                                       TAG + ":StartCall");
        mStartCallWakeLock.setReferenceCounted(false);

        mAdapter.getProfileProxy(this, mProfileListener, BluetoothProfile.HEADSET);

        mForegroundCallState = Call.State.IDLE;
        mRingingCallState = Call.State.IDLE;
        mNumActive = 0;
        mNumHeld = 0;
        mRingNumber = new CallNumber("", 0);;

        handlePreciseCallStateChange(null);
        if(VDBG) Log.d(TAG, "registerForServiceStateChanged");
        // register for updates
        Log.d(TAG, "registerForPreciseCallStateChanged start");
        mCM.registerForPreciseCallStateChanged(mHandler, PRECISE_CALL_STATE_CHANGED, null);
        for (Phone phone : mCM.getAllPhones()) {
            if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA ) {
                log("register for cdma call waiting " + phone.getSubscription());
                mCM.registerForCallWaiting(mHandler, PHONE_CDMA_CALL_WAITING,
                                           phone.getSubscription());
                break;
            }
        }
        if (isDsdaEnabled())
            mCM.registerForSubscriptionChange(mHandler,
                      PHONE_ACTIVE_SUBSCRIPTION_CHANGE, null);
        // TODO(BT) registerForIncomingRing?

        if (mCM.isCallOnCsvtEnabled()) {
            IntentFilter filter = new IntentFilter();
            filter.addAction("intent.action.CSVT_PRECISE_CALL_STATE_CHANGED");
            PhoneGlobals.getInstance().registerReceiver(mCsvtCallStateReceiver, filter);
        }
        mCM.registerForDisconnect(mHandler, PHONE_ON_DISCONNECT, null);

        mClccTimestamps = new long[GSM_MAX_CONNECTIONS];
        mClccUsed = new boolean[GSM_MAX_CONNECTIONS];
        for (int i = 0; i < GSM_MAX_CONNECTIONS; i++) {
            mClccUsed[i] = false;
        }
        //Check whether we support DSDA or not
        if (MSimTelephonyManager.getDefault().isMultiSimEnabled()) {
            Log.d(TAG, "DSDA is enabled, Bind to DSDA service");
            createBTMultiSimService();
        }
    }

    @Override
    public void onStart(Intent intent, int startId) {
        if (mAdapter == null) {
            Log.w(TAG, "Stopping Bluetooth BluetoothPhoneService Service: device does not have BT");
            stopSelf();
        }
        if (VDBG) Log.d(TAG, "BluetoothPhoneService started");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mCM.isCallOnCsvtEnabled()) {
            PhoneGlobals.getInstance().unregisterReceiver(mCsvtCallStateReceiver);
        }
        if (DBG) log("Stopping Bluetooth BluetoothPhoneService Service");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private void createBTMultiSimService() {
        try {
            // send intent to start BtDsdaService service
            boolean bound = bindService(new Intent
                   ("org.codeaurora.btmultisim.IBluetoothDsdaService"),
                    btMultiSimServiceConnection, Context.BIND_AUTO_CREATE);
            Log.d(TAG, "IBluetoothDsdaService bound request : " + bound);
        } catch (NoClassDefFoundError e) {
            Log.w(TAG, "Ignoring IBluetoothDsdaService class not found exception " + e);
        }
    }

    private ServiceConnection btMultiSimServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            //Get handle to IBluetoothDsdaService.Stub.asInterface(service);
            mBluetoothDsda = IBluetoothDsdaService.Stub.asInterface(service);
            Log.d(TAG,"Dsda Service Connected" + mBluetoothDsda);
            if (mBluetoothDsda != null) {
                    Log.e(TAG, "IBluetoothDsdaService created");
                    if (isDsdaEnabled())
                        handlePreciseCallStateChange(null);
                } else Log.e(TAG, "IBluetoothDsdaService Error");
        }

        public void onServiceDisconnected(ComponentName arg0) {
            Log.w(TAG,"DSDA Service onServiceDisconnected");
            mBluetoothDsda = null;
        }
    };

    private static final int PRECISE_CALL_STATE_CHANGED = 1;
    private static final int PHONE_CDMA_CALL_WAITING = 2;
    private static final int LIST_CURRENT_CALLS = 3;
    private static final int QUERY_PHONE_STATE = 4;
    private static final int CDMA_SWAP_SECOND_CALL_STATE = 5;
    private static final int CDMA_SET_SECOND_CALL_STATE = 6;
    private static final int PHONE_ON_DISCONNECT = 7;
    private static final int PHONE_ACTIVE_SUBSCRIPTION_CHANGE = 8;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (VDBG) Log.d(TAG, "handleMessage: " + msg.what);
            switch(msg.what) {
                case PRECISE_CALL_STATE_CHANGED:
                    Connection connection = null;
                    if (((AsyncResult) msg.obj).result instanceof Connection) {
                        connection = (Connection) ((AsyncResult) msg.obj).result;
                    }
                    if (isDsdaEnabled() && (mBluetoothDsda != null)) {
                        //Get the Sub on which call state change happened
                        if (((AsyncResult) msg.obj).result instanceof PhoneBase) {
                            PhoneBase pb =  (PhoneBase)((AsyncResult) msg.obj).result;
                            int subscription = pb.getSubscription();
                            log("SUB on which it happned: " + subscription);
                            try {
                                int mPhonetype = -1;
                                mBluetoothDsda.setCurrentSub(subscription);
                                //Get the CDMA call states and update to DSDA state
                                // machine
                                for (Phone phone : mCM.getAllPhones()) {
                                    if (phone != null) {
                                        if (phone.getSubscription() == subscription) {
                                            mPhonetype = phone.getPhoneType();
                                            if (mPhonetype == PhoneConstants.PHONE_TYPE_CDMA) {
                                                Log.d(TAG, "CDMA.Update held calls on this SUB");
                                                mBluetoothDsda.updateCdmaHeldCall(getNumHeldCdma());
                                            }
                                        }
                                    }
                                }
                            } catch (RemoteException e) {
                                Log.w(TAG, "mBluetoothDsda class not found exception " + e);
                                break;
                            }
                        } else log("No PhoneBase object found");
                    }
                    handlePreciseCallStateChange(connection);
                    break;
                case PHONE_CDMA_CALL_WAITING:
                    Connection conn = null;
                    if (isDsdaEnabled() && (mBluetoothDsda != null)) {
                        AsyncResult ar = (AsyncResult) msg.obj;
                        int subscription = (Integer) ar.userObj;
                        log("CDMA call waiting on sub: " + subscription);
                        conn = mCM.getFirstActiveRingingCall(subscription).getLatestConnection();
                        try {
                            mBluetoothDsda.setCurrentSub(subscription);
                            mBluetoothDsda.updateCdmaHeldCall(getNumHeldCdma());
                        } catch (RemoteException e) {
                            Log.w(TAG, "Class not found exception " + e);
                            break;
                        }
                    } else {
                        if (((AsyncResult) msg.obj).result instanceof Connection) {
                            conn = (Connection) ((AsyncResult) msg.obj).result;
                        }
                    }
                    handlePreciseCallStateChange(conn);
                    break;
                case PHONE_ON_DISCONNECT:
                    connection = null;
                    if (((AsyncResult) msg.obj).result instanceof Connection) {
                        connection = (Connection) ((AsyncResult) msg.obj).result;
                    }
                    if (isDsdaEnabled() && (mBluetoothDsda != null)) {
                        //Get the Sub on which call state change happened
                        if (((AsyncResult) msg.obj).result instanceof PhoneBase) {
                            PhoneBase pb =  (PhoneBase)((AsyncResult) msg.obj).result;
                            int subscription = pb.getSubscription();
                            log("SUB on which it happned: " + subscription);
                            try {
                                int mPhonetype = -1;
                                mBluetoothDsda.setCurrentSub(subscription);
                                //Get the CDMA call states and update to DSDA state
                                // machine
                                for (Phone phone : mCM.getAllPhones()) {
                                    if (phone != null) {
                                        if (phone.getSubscription() == subscription) {
                                            mPhonetype = phone.getPhoneType();
                                            if (mPhonetype == PhoneConstants.PHONE_TYPE_CDMA) {
                                                Log.d(TAG, "CDMA. Update held calls on this SUB");
                                                mBluetoothDsda.updateCdmaHeldCall(getNumHeldCdma());
                                            }
                                        }
                                    }
                                }
                            } catch (RemoteException e) {
                                Log.w(TAG, " mBluetoothDsda class not found exception " + e);
                                break;
                            }
                        } else log("No PhoneBase object found");
                    }
                    handlePreciseCallStateChange(connection);
                    break;
                case LIST_CURRENT_CALLS:
                    handleListCurrentCalls();
                    break;
                case QUERY_PHONE_STATE:
                    handleQueryPhoneState();
                    break;
                case CDMA_SWAP_SECOND_CALL_STATE:
                    handleCdmaSwapSecondCallState();
                    break;
                case CDMA_SET_SECOND_CALL_STATE:
                    handleCdmaSetSecondCallState((Boolean) msg.obj);
                    break;
                case PHONE_ACTIVE_SUBSCRIPTION_CHANGE:
                    if (isDsdaEnabled() && (mBluetoothDsda != null))
                        try {
                             mBluetoothDsda.phoneSubChanged();
                        } catch (RemoteException e) {
                            Log.w(TAG, "DSDA class not found exception " + e);
                        }
                    break;
            }
        }
    };

    private boolean isDsdaEnabled() {
        //Check whether we support DSDA or not
        if ((MSimTelephonyManager.getDefault().getMultiSimConfiguration()
            == MSimTelephonyManager.MultiSimVariants.DSDA)) {
            Log.d(TAG, "DSDA is enabled");
            return true;
        }
        return false;
    }

    private void updateBtPhoneStateAfterRadioTechnologyChange() {
        if(VDBG) Log.d(TAG, "updateBtPhoneStateAfterRadioTechnologyChange...");

        //Unregister all events from the old obsolete phone
        mCM.unregisterForPreciseCallStateChanged(mHandler);
        mCM.unregisterForCallWaiting(mHandler);
        if (isDsdaEnabled())
            mCM.unregisterForSubscriptionChange(mHandler);

        //Register all events new to the new active phone
        mCM.registerForPreciseCallStateChanged(mHandler,
                                               PRECISE_CALL_STATE_CHANGED, null);
        for (Phone phone : mCM.getAllPhones()) {
            if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA ) {
                log("register for cdma call waiting " + phone.getSubscription());
                mCM.registerForCallWaiting(mHandler, PHONE_CDMA_CALL_WAITING,
                                           phone.getSubscription());
                break;
            }
        }
        if (isDsdaEnabled())
            mCM.registerForSubscriptionChange(mHandler,
                     PHONE_ACTIVE_SUBSCRIPTION_CHANGE, null);
    }

    private void handlePreciseCallStateChange(Connection connection) {

        //Check whether we support DSDA or not
        if (isDsdaEnabled()) {
            Log.d(TAG, "DSDA call operation, handle it separately");
            if (mBluetoothDsda != null) {
                try {
                    //Update the CDMA call states here only
                    updateCdmaCallStates();
                    mBluetoothDsda.handleMultiSimPreciseCallStateChange();
                } catch (RemoteException e) {
                    Log.w(TAG, "Ignoring DSDA class not found exception " + e);
                }
            }
            return;
        }

        //Regular Single SUB call handling
        // get foreground call state
        int oldNumActive = mNumActive;
        int oldNumHeld = mNumHeld;
        Call.State oldRingingCallState = mRingingCallState;
        Call.State oldForegroundCallState = mForegroundCallState;
        CallNumber oldRingNumber = mRingNumber;

        Call foregroundCall = mCM.getActiveFgCall();

        if (VDBG)
            Log.d(TAG, " handlePreciseCallStateChange: foreground: " + foregroundCall +
                " background: " + mCM.getFirstActiveBgCall() + " ringing: " +
                mCM.getFirstActiveRingingCall());

        mForegroundCallState = foregroundCall.getState();
        /* if in transition, do not update */
        if (mForegroundCallState == Call.State.DISCONNECTING)
        {
            Log.d(TAG, "handlePreciseCallStateChange. Call disconnecting, wait before update");
            return;
        }
        else
            mNumActive = (mForegroundCallState == Call.State.ACTIVE) ? 1 : 0;

        Call ringingCall = mCM.getFirstActiveRingingCall();
        mRingingCallState = ringingCall.getState();
        mRingNumber = getCallNumber(connection, ringingCall);

        if (mCM.getDefaultPhone().getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            mNumHeld = getNumHeldCdma();
            PhoneGlobals app = PhoneGlobals.getInstance();
            if (app.cdmaPhoneCallState != null) {
                CdmaPhoneCallState.PhoneCallState currCdmaThreeWayCallState =
                        app.cdmaPhoneCallState.getCurrentCallState();
                CdmaPhoneCallState.PhoneCallState prevCdmaThreeWayCallState =
                    app.cdmaPhoneCallState.getPreviousCallState();
                log("CDMA call state: " + currCdmaThreeWayCallState + " prev state:" +
                    prevCdmaThreeWayCallState);

                if ((mBluetoothHeadset != null) &&
                    (mCdmaThreeWayCallState != currCdmaThreeWayCallState)) {
                    // In CDMA, the network does not provide any feedback
                    // to the phone when the 2nd MO call goes through the
                    // stages of DIALING > ALERTING -> ACTIVE we fake the
                    // sequence
                    log("CDMA 3way call state change. mNumActive: " + mNumActive +
                        " mNumHeld: " + mNumHeld + " IsThreeWayCallOrigStateDialing: " +
                        app.cdmaPhoneCallState.IsThreeWayCallOrigStateDialing());
                    if ((currCdmaThreeWayCallState ==
                            CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE)
                                && app.cdmaPhoneCallState.IsThreeWayCallOrigStateDialing()) {
                        // Mimic dialing, put the call on hold, alerting
                        mBluetoothHeadset.phoneStateChanged(0, mNumHeld,
                            convertCallState(Call.State.IDLE, Call.State.DIALING),
                            mRingNumber.mNumber, mRingNumber.mType);

                        mBluetoothHeadset.phoneStateChanged(0, mNumHeld,
                            convertCallState(Call.State.IDLE, Call.State.ALERTING),
                            mRingNumber.mNumber, mRingNumber.mType);

                    }

                    // In CDMA, the network does not provide any feedback to
                    // the phone when a user merges a 3way call or swaps
                    // between two calls we need to send a CIEV response
                    // indicating that a call state got changed which should
                    // trigger a CLCC update request from the BT client.
                    if (currCdmaThreeWayCallState ==
                            CdmaPhoneCallState.PhoneCallState.CONF_CALL &&
                            prevCdmaThreeWayCallState ==
                              CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                        log("CDMA 3way conf call. mNumActive: " + mNumActive +
                            " mNumHeld: " + mNumHeld);
                        mBluetoothHeadset.phoneStateChanged(mNumActive, mNumHeld,
                            convertCallState(Call.State.IDLE, mForegroundCallState),
                            mRingNumber.mNumber, mRingNumber.mType);
                    }
                }
                mCdmaThreeWayCallState = currCdmaThreeWayCallState;
            }
        } else {
            mNumHeld = getNumHeldUmts();
        }

        boolean callsSwitched = false;
        if (mCM.getDefaultPhone().getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA &&
            mCdmaThreeWayCallState == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
            callsSwitched = mCdmaCallsSwapped;
        } else {
            Call backgroundCall = mCM.getFirstActiveBgCall();
            callsSwitched =
                (mNumHeld == 1 && ! (backgroundCall.getEarliestConnectTime() ==
                    mBgndEarliestConnectionTime));
            mBgndEarliestConnectionTime = backgroundCall.getEarliestConnectTime();
        }
        log("update the call states, active: " + mNumActive + "held" + mNumHeld);

        if (mNumActive != oldNumActive || mNumHeld != oldNumHeld ||
            mRingingCallState != oldRingingCallState ||
            mForegroundCallState != oldForegroundCallState ||
            !mRingNumber.equalTo(oldRingNumber) ||
            callsSwitched) {
            if (mBluetoothHeadset != null) {
                log("update the headset");
                mBluetoothHeadset.phoneStateChanged(mNumActive, mNumHeld,
                    convertCallState(mRingingCallState, mForegroundCallState),
                    mRingNumber.mNumber, mRingNumber.mType);
            }
        }
    }

    private void handleListCurrentCalls() {
        if (isDsdaEnabled()) {
            if (mBluetoothDsda != null)
            try {
                updateCdmaCallStates();
                mBluetoothDsda.handleListCurrentCalls();
            } catch (RemoteException e) {
                Log.w(TAG, "Ignoring DSDA class not found exception " + e);
            }
            return;
        }
        Phone phone = mCM.getDefaultPhone();
        int phoneType = phone.getPhoneType();

        // TODO(BT) handle virtual call

        if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
            listCurrentCallsCdma();
        } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
            listCurrentCallsGsm();
        } else {
            Log.e(TAG, "Unexpected phone type: " + phoneType);
        }
        // end the result
        // when index is 0, other parameter does not matter
        if (mBluetoothHeadset != null) {
            mBluetoothHeadset.clccResponse(0, 0, 0, 0, false, "", 0);
        }
    }

    private void handleQueryPhoneState() {
        if (isDsdaEnabled()) {
            if (mBluetoothDsda != null) {
                try {
                    mBluetoothDsda.processQueryPhoneState();
                } catch (RemoteException e) {
                    Log.e(TAG, "DSDA Service not found exception " + e);
                }
            }
            return;
        }
        if (mBluetoothHeadset != null) {
            mBluetoothHeadset.phoneStateChanged(mNumActive, mNumHeld,
                convertCallState(mRingingCallState, mForegroundCallState),
                mRingNumber.mNumber, mRingNumber.mType);
        }
    }

    private int getNumHeldUmts() {
        int countHeld = 0;
        List<Call> heldCalls = mCM.getBackgroundCalls();

        for (Call call : heldCalls) {
            if (call.getState() == Call.State.HOLDING) {
                countHeld++;
            }
        }
        return countHeld;
    }

    private int getNumHeldCdma() {
        int numHeld = 0;
        PhoneGlobals app = PhoneGlobals.getInstance();
        if (app.cdmaPhoneCallState != null) {
            CdmaPhoneCallState.PhoneCallState curr3WayCallState =
                app.cdmaPhoneCallState.getCurrentCallState();
            CdmaPhoneCallState.PhoneCallState prev3WayCallState =
                app.cdmaPhoneCallState.getPreviousCallState();

            log("CDMA call state: " + curr3WayCallState + " prev state:" +
                prev3WayCallState);
            if (curr3WayCallState == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
                if (prev3WayCallState == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                    numHeld = 0; //0: no calls held, as now *both* the caller are active
                } else {
                    numHeld = 1; //1: held call and active call, as on answering a
                    // Call Waiting, one of the caller *is* put on hold
                }
            } else if (curr3WayCallState == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                numHeld = 1; //1: held call and active call, as on make a 3 Way Call
                // the first caller *is* put on hold
            } else {
                numHeld = 0; //0: no calls held as this is a SINGLE_ACTIVE call
            }
        }
        return numHeld;
    }

    private void updateCdmaCallStates() throws RemoteException {
        PhoneGlobals app = PhoneGlobals.getInstance();
        int currCallState = 0;
        int prevCallState = 0;

        if (app.cdmaPhoneCallState != null) {
            CdmaPhoneCallState.PhoneCallState curr3WayCallState =
                app.cdmaPhoneCallState.getCurrentCallState();
            CdmaPhoneCallState.PhoneCallState prev3WayCallState =
                app.cdmaPhoneCallState.getPreviousCallState();
            log("CDMA call state: " + curr3WayCallState + " prev state:" + prev3WayCallState);
            switch (curr3WayCallState) {
                case IDLE:
                    currCallState = 0;
                    break;
                case SINGLE_ACTIVE:
                    currCallState = 1;
                    break;
                case THRWAY_ACTIVE:
                    currCallState = 2;
                    break;
                case CONF_CALL:
                    currCallState = 3;
                    break;
                default:
                    break;
            }
            switch (prev3WayCallState) {
                case IDLE:
                    prevCallState = 0;
                    break;
                case SINGLE_ACTIVE:
                    prevCallState = 1;
                    break;
                case THRWAY_ACTIVE:
                    prevCallState = 2;
                    break;
                case CONF_CALL:
                    prevCallState = 3;
                    break;
                default:
                    break;
            }
            mBluetoothDsda.setCurrentCallState(currCallState, prevCallState,
                app.cdmaPhoneCallState.IsThreeWayCallOrigStateDialing());
        }
    }

    private CallNumber getCallNumber(Connection connection, Call call) {
        String number = null;
        int type = 128;
        // find phone number and type
        if (connection == null) {
            connection = call.getEarliestConnection();
            if (connection == null) {
                Log.e(TAG, "Could not get a handle on Connection object for the call");
            }
        }
        if (connection != null) {
            number = connection.getAddress();
            if (number != null) {
                type = PhoneNumberUtils.toaFromString(number);
            }
        }
        if (number == null) {
            number = "";
        }
        return new CallNumber(number, type);
    }

    private class CallNumber
    {
        private String mNumber = null;
        private int mType = 0;

        private CallNumber(String number, int type) {
            mNumber = number;
            mType = type;
        }

        private boolean equalTo(CallNumber callNumber)
        {
            if (mType != callNumber.mType) return false;

            if (mNumber != null && mNumber.compareTo(callNumber.mNumber) == 0) {
                return true;
            }
            return false;
        }
    }

    private BluetoothProfile.ServiceListener mProfileListener =
            new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mBluetoothHeadset = (BluetoothHeadset) proxy;
        }
        public void onServiceDisconnected(int profile) {
            mBluetoothHeadset = null;
        }
    };

    private void listCurrentCallsGsm() {
        // Collect all known connections
        // clccConnections isindexed by CLCC index
        Connection[] clccConnections = new Connection[GSM_MAX_CONNECTIONS];
        LinkedList<Connection> newConnections = new LinkedList<Connection>();
        LinkedList<Connection> connections = new LinkedList<Connection>();

        Call foregroundCall = mCM.getActiveFgCall();
        Call backgroundCall = mCM.getFirstActiveBgCall();
        Call ringingCall = mCM.getFirstActiveRingingCall();

        if (ringingCall.getState().isAlive()) {
            connections.addAll(ringingCall.getConnections());
        }
        if (foregroundCall.getState().isAlive()) {
            connections.addAll(foregroundCall.getConnections());
        }
        if (backgroundCall.getState().isAlive()) {
            connections.addAll(backgroundCall.getConnections());
        }

        // Mark connections that we already known about
        boolean clccUsed[] = new boolean[GSM_MAX_CONNECTIONS];
        for (int i = 0; i < GSM_MAX_CONNECTIONS; i++) {
            clccUsed[i] = mClccUsed[i];
            mClccUsed[i] = false;
        }
        for (Connection c : connections) {
            boolean found = false;
            long timestamp = c.getCreateTime();
            for (int i = 0; i < GSM_MAX_CONNECTIONS; i++) {
                if (clccUsed[i] && timestamp == mClccTimestamps[i]) {
                    mClccUsed[i] = true;
                    found = true;
                    clccConnections[i] = c;
                    break;
                }
            }
            if (!found) {
                newConnections.add(c);
            }
        }

        // Find a CLCC index for new connections
        while (!newConnections.isEmpty()) {
            // Find lowest empty index
            int i = 0;
            while (mClccUsed[i]) i++;
            // Find earliest connection
            long earliestTimestamp = newConnections.get(0).getCreateTime();
            Connection earliestConnection = newConnections.get(0);
            for (int j = 0; j < newConnections.size(); j++) {
                long timestamp = newConnections.get(j).getCreateTime();
                if (timestamp < earliestTimestamp) {
                    earliestTimestamp = timestamp;
                    earliestConnection = newConnections.get(j);
                }
            }

            // update
            mClccUsed[i] = true;
            mClccTimestamps[i] = earliestTimestamp;
            clccConnections[i] = earliestConnection;
            newConnections.remove(earliestConnection);
        }

        // Send CLCC response to Bluetooth headset service
        for (int i = 0; i < clccConnections.length; i++) {
            if (mClccUsed[i]) {
                sendClccResponseGsm(i, clccConnections[i]);
            }
        }
    }

    /** Convert a Connection object into a single +CLCC result */
    private void sendClccResponseGsm(int index, Connection connection) {
        int state = convertCallState(connection.getState());
        boolean mpty = false;
        Call call = connection.getCall();
        if (call != null) {
            mpty = call.isMultiparty();
        }

        boolean isIncoming = connection.isIncoming();

        // For GV outgoing calls send the contact phone #, not the gateway #.
        String number = connection.getAddress();
        if (!isIncoming) {
            RawGatewayInfo rawInfo = mCallGatewayManager.getGatewayInfo(connection);
            if (!rawInfo.isEmpty()) {
                number = rawInfo.trueNumber;
            }
        }
        int type = -1;
        if (number != null) {
            type = PhoneNumberUtils.toaFromString(number);
        } else {
            number = "";
        }

        if (mBluetoothHeadset != null) {
            mBluetoothHeadset.clccResponse(index + 1, isIncoming ? 1 : 0,
                    state, 0, mpty, number, type);
        }
    }

    /** Build the +CLCC result for CDMA
     *  The complexity arises from the fact that we need to maintain the same
     *  CLCC index even as a call moves between states. */
    private synchronized void listCurrentCallsCdma() {
        // In CDMA at one time a user can have only two live/active connections
        Connection[] clccConnections = new Connection[CDMA_MAX_CONNECTIONS];// indexed by CLCC index
        Call foregroundCall = mCM.getActiveFgCall();
        Call ringingCall = mCM.getFirstActiveRingingCall();

        Call.State ringingCallState = ringingCall.getState();
        // If the Ringing Call state is INCOMING, that means this is the very first call
        // hence there should not be any Foreground Call
        if (ringingCallState == Call.State.INCOMING) {
            if (VDBG) log("Filling clccConnections[0] for INCOMING state");
            clccConnections[0] = ringingCall.getLatestConnection();
        } else if (foregroundCall.getState().isAlive()) {
            // Getting Foreground Call connection based on Call state
            if (ringingCall.isRinging()) {
                if (VDBG) log("Filling clccConnections[0] & [1] for CALL WAITING state");
                clccConnections[0] = foregroundCall.getEarliestConnection();
                clccConnections[1] = ringingCall.getLatestConnection();
            } else {
                if (foregroundCall.getConnections().size() <= 1) {
                    // Single call scenario
                    if (VDBG) {
                        log("Filling clccConnections[0] with ForgroundCall latest connection");
                    }
                    clccConnections[0] = foregroundCall.getLatestConnection();
                } else {
                    // Multiple Call scenario. This would be true for both
                    // CONF_CALL and THRWAY_ACTIVE state
                    if (VDBG) {
                        log("Filling clccConnections[0] & [1] with ForgroundCall connections");
                    }
                    clccConnections[0] = foregroundCall.getEarliestConnection();
                    clccConnections[1] = foregroundCall.getLatestConnection();
                }
            }
        }

        // Update the mCdmaIsSecondCallActive flag based on the Phone call state
        if (PhoneGlobals.getInstance().cdmaPhoneCallState.getCurrentCallState()
                == CdmaPhoneCallState.PhoneCallState.SINGLE_ACTIVE) {
            Message msg = mHandler.obtainMessage(CDMA_SET_SECOND_CALL_STATE, false);
            mHandler.sendMessage(msg);
        } else if (PhoneGlobals.getInstance().cdmaPhoneCallState.getCurrentCallState()
                == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
            Message msg = mHandler.obtainMessage(CDMA_SET_SECOND_CALL_STATE, true);
            mHandler.sendMessage(msg);
        }

        // send CLCC result
        for (int i = 0; (i < clccConnections.length) && (clccConnections[i] != null); i++) {
            sendClccResponseCdma(i, clccConnections[i]);
        }
    }

    /** Send ClCC results for a Connection object for CDMA phone */
    private void sendClccResponseCdma(int index, Connection connection) {
        int state;
        PhoneGlobals app = PhoneGlobals.getInstance();
        CdmaPhoneCallState.PhoneCallState currCdmaCallState =
                app.cdmaPhoneCallState.getCurrentCallState();
        CdmaPhoneCallState.PhoneCallState prevCdmaCallState =
                app.cdmaPhoneCallState.getPreviousCallState();

        if ((prevCdmaCallState == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE)
                && (currCdmaCallState == CdmaPhoneCallState.PhoneCallState.CONF_CALL)) {
            // If the current state is reached after merging two calls
            // we set the state of all the connections as ACTIVE
            state = CALL_STATE_ACTIVE;
        } else {
            Call.State callState = connection.getState();
            switch (callState) {
            case ACTIVE:
                // For CDMA since both the connections are set as active by FW after accepting
                // a Call waiting or making a 3 way call, we need to set the state specifically
                // to ACTIVE/HOLDING based on the mCdmaIsSecondCallActive flag. This way the
                // CLCC result will allow BT devices to enable the swap or merge options
                if (index == 0) { // For the 1st active connection
                    state = mCdmaIsSecondCallActive ? CALL_STATE_HELD : CALL_STATE_ACTIVE;
                } else { // for the 2nd active connection
                    state = mCdmaIsSecondCallActive ? CALL_STATE_ACTIVE : CALL_STATE_HELD;
                }
                break;
            case HOLDING:
                state = CALL_STATE_HELD;
                break;
            case DIALING:
                state = CALL_STATE_DIALING;
                break;
            case ALERTING:
                state = CALL_STATE_ALERTING;
                break;
            case INCOMING:
                state = CALL_STATE_INCOMING;
                break;
            case WAITING:
                state = CALL_STATE_WAITING;
                break;
            default:
                Log.e(TAG, "bad call state: " + callState);
                return;
            }
        }

        boolean mpty = false;
        if (currCdmaCallState == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
            if (prevCdmaCallState == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                // If the current state is reached after merging two calls
                // we set the multiparty call true.
                mpty = true;
            } // else
                // CALL_CONF state is not from merging two calls, but from
                // accepting the second call. In this case first will be on
                // hold in most cases but in some cases its already merged.
                // However, we will follow the common case and the test case
                // as per Bluetooth SIG PTS
        }

        boolean isIncoming = connection.isIncoming();

        // For GV outgoing calls send the contact phone #, not the gateway #.
        String number = connection.getAddress();
        if (!isIncoming) {
            RawGatewayInfo rawInfo = mCallGatewayManager.getGatewayInfo(connection);
            if (!rawInfo.isEmpty()) {
                number = rawInfo.trueNumber;
            }
        }
        int type = -1;
        if (number != null) {
            type = PhoneNumberUtils.toaFromString(number);
        } else {
            number = "";
        }

        if (mBluetoothHeadset != null) {
            mBluetoothHeadset.clccResponse(index + 1, isIncoming ? 1 : 0,
                    state, 0, mpty, number, type);
        }
    }

    private void handleCdmaSwapSecondCallState() {
        if (VDBG) log("cdmaSwapSecondCallState: Toggling mCdmaIsSecondCallActive");
        if (isDsdaEnabled() && (mBluetoothDsda != null)) {
            if (VDBG) log("DSDA.handleCdmaSwapSecondCallState");
            try {
                mBluetoothDsda.handleCdmaSwapSecondCallState();
            } catch (RemoteException e) {
                Log.w(TAG, "DSDA class not found exception " + e);
            }
            return;
        }
        mCdmaIsSecondCallActive = !mCdmaIsSecondCallActive;
        mCdmaCallsSwapped = true;
    }

    private void handleCdmaSetSecondCallState(boolean state) {
        if (VDBG) log("cdmaSetSecondCallState: Setting mCdmaIsSecondCallActive to " + state);
        if (isDsdaEnabled() && (mBluetoothDsda != null)) {
            if (VDBG) log("DSDA.handleCdmaSetSecondCallState");
            try {
                mBluetoothDsda.handleCdmaSetSecondCallState(state);
            } catch (RemoteException e) {
                Log.w(TAG, "DSDA class not found exception " + e);
            }
            return;
        }
        mCdmaIsSecondCallActive = state;

        if (!mCdmaIsSecondCallActive) {
            mCdmaCallsSwapped = false;
        }
    }

    /* Get the active or held call on other Sub. */
    private Call getCallOnOtherSub() throws  RemoteException {
        if (VDBG) log("getCallOnOtherSub");
        int activeSub = mCM.getActiveSubscription();
        int bgSub =  PhoneUtils.getOtherActiveSub(activeSub);
        /*bgSub would be -1 when bg subscription has no calls*/
        if (bgSub == -1)
            return null;

        Call call = null;
        if (mBluetoothDsda.getTotalCallsOnSub(bgSub) == 1) {
            if (mCM.hasActiveFgCall(bgSub))
                call = mCM.getActiveFgCall(bgSub);
            else if (mCM.hasActiveBgCall(bgSub))
                call = mCM.getFirstActiveBgCall(bgSub);
        }
        return call;
    }

    private Call getCallOnActiveSub() throws  RemoteException {
        if (VDBG) log("getCallOnActiveSub");
        int activeSub = mCM.getActiveSubscription();
        Call call = null;
        int bgSub =  PhoneUtils.getOtherActiveSub(activeSub);
        /*bgSub would be -1 when bg subscription has no calls*/
        if (bgSub == -1)
            return null;

        if (mBluetoothDsda.getTotalCallsOnSub(bgSub) < 2 ) {
            if (mCM.hasActiveFgCall(activeSub))
                call = mCM.getActiveFgCall(activeSub);
            else if (mCM.hasActiveBgCall(activeSub))
                call = mCM.getFirstActiveBgCall(activeSub);
        }
        return call;
    }

    private boolean processDsdaChld(int chld) throws  RemoteException {
        Phone phone;
        int phoneType;
        int activeSub = mCM.getActiveSubscription();
        boolean status = true;
        phone = MSimPhoneGlobals.getInstance().getPhone(activeSub);

        phoneType = phone.getPhoneType();
        log("processChld: " + chld + " for Phone type: " + phoneType);
        Call ringingCall = mCM.getFirstActiveRingingCall(activeSub);
        Call backgroundCall = mCM.getFirstActiveBgCall(activeSub);
        switch (chld) {
            case CHLD_TYPE_RELEASEHELD:
                if (ringingCall.isRinging()) {
                status = PhoneUtils.hangupRingingCall(ringingCall);
                } else {
                Call call = getCallOnOtherSub();
                if (call != null) {
                PhoneUtils.hangup(call);
                status = true;
                } else status = PhoneUtils.hangupHoldingCall(backgroundCall);
                }
                break;

            case CHLD_TYPE_RELEASEACTIVE_ACCEPTHELD:
                if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    Call call = getCallOnOtherSub();
                    if (ringingCall.isRinging() && (call != null)) {
                        //first answer the incoming call
                        PhoneUtils.answerCall(mCM.getFirstActiveRingingCall(activeSub));
                        //Try to Drop the call on the other SUB.
                        PhoneUtils.hangup(call);
                    } else if (mBluetoothDsda.isSwitchSubAllowed()) {
                        /* In case of Sub1=Active and Sub2=lch/held, drop call
                        on active  Sub*/
                        log("Drop the call on Active sub, move LCH to active");
                        call = getCallOnActiveSub();
                        if(call != null)
                        PhoneUtils.hangup(call);
                    } else {
                        //Operate on single SUB
                        if (ringingCall.isRinging()) {
                            // Hangup the active call and then answer call waiting call.
                            log("CHLD:1 Callwaiting Answer call");
                            PhoneUtils.hangupRingingAndActive(phone);
                        } else {
                            // If there is no Call waiting then just hangup
                            // the active call. In CDMA this mean that the complete
                            // call session would be ended
                            log("CHLD:1 Hangup Call");
                            PhoneUtils.hangup(PhoneGlobals.getInstance().mCM);
                        }
                    }
                    status = true;
                } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                    Call call = getCallOnOtherSub();
                    if (ringingCall.isRinging() && (call != null)) {
                        //first answer the incoming call
                        PhoneUtils.answerCall(mCM.getFirstActiveRingingCall(activeSub));
                        //Try to Drop the call on the other SUB.
                        PhoneUtils.hangup(call);
                    } else if (mBluetoothDsda.isSwitchSubAllowed()) {
                        /* In case of Sub1=Active and Sub2=lch/held, drop call
                        on active  Sub*/
                        log("processChld drop the call on Active sub, move LCH to active");
                        log("Drop call on active sub");
                        call = getCallOnActiveSub();
                        if(call != null)
                        PhoneUtils.hangup(call);
                    } else {
                        PhoneUtils.answerAndEndActive(PhoneGlobals.getInstance().mCM, ringingCall);
                    }
                    status = true;
                } else {
                    Log.e(TAG, "bad phone type: " + phoneType);
                    status = false;
                }
                break;

            case CHLD_TYPE_HOLDACTIVE_ACCEPTHELD:
                if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    if (mBluetoothDsda.canDoCallSwap()) {
                        log("Try to do call swap on same sub");
                        if (PhoneGlobals.getInstance().cdmaPhoneCallState.getCurrentCallState()
                            == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
                            log("CHLD:2 Swap Calls");
                            PhoneUtils.switchHoldingAndActive(backgroundCall);
                            // Toggle the second callers active state flag
                            handleCdmaSwapSecondCallState();
                        } else {
                            Log.e(TAG, "CDMA fail to do hold active and accept held");
                        }
                    } else if (mBluetoothDsda.isSwitchSubAllowed()) {
                        //Switch SUB.
                        log("CHLD = 2 Switch sub");
                        // If there is a change in active subscription while both the
                        // subscriptions are in active state, need to siwtch the
                        // playing of LCH/SCH tone to new LCH subscription.
                        final MSimCallNotifier notifier =
                        (MSimCallNotifier)PhoneGlobals.getInstance().notifier;
                        notifier.manageMSimInCallTones(true);
                        mBluetoothDsda.SwitchSub();
                    } else if (mBluetoothDsda.answerOnThisSubAllowed() == true) {
                        log("Can we answer the call on other SUB?");
                        // Answer the call on current SUB.
                        if (ringingCall.isRinging())
                        PhoneUtils.answerCall(ringingCall);
                    } else {
                        //On same sub
                        if (ringingCall.isRinging()) {
                            log("CHLD:2 Callwaiting Answer call");
                            PhoneUtils.answerCall(ringingCall);
                            PhoneUtils.setMute(false);
                            // Setting the second callers state flag to TRUE (i.e. active)
                            handleCdmaSetSecondCallState(true);
                        } else if (PhoneGlobals.getInstance().cdmaPhoneCallState
                            .getCurrentCallState()
                            == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
                            log("CHLD:2 Swap Calls");
                            PhoneUtils.switchHoldingAndActive(backgroundCall);
                            // Toggle the second callers active state flag
                            handleCdmaSwapSecondCallState();
                        }
                        Log.e(TAG, "CDMA fail to do hold active and accept held");
                    }
                    status = true;
                } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                    if (mBluetoothDsda.canDoCallSwap()) {
                        log("Try to do call swap on same sub");
                        PhoneUtils.switchHoldingAndActive(backgroundCall);
                    } else if (mBluetoothDsda.isSwitchSubAllowed()) {
                        /*Switch SUB*/
                        log("Switch sub");
                        // If there is a change in active subscription while both the
                        // subscriptions are in active state, need to siwtch the
                        // playing of LCH/SCH tone to new LCH subscription.
                        final MSimCallNotifier notifier =
                        (MSimCallNotifier)PhoneGlobals.getInstance().notifier;
                        notifier.manageMSimInCallTones(true);
                        mBluetoothDsda.SwitchSub();
                    } else if (mBluetoothDsda.answerOnThisSubAllowed() == true) {
                        log("Can we answer the call on other SUB?");
                        /* Answer the call on current SUB*/
                        if (ringingCall.isRinging())
                        PhoneUtils.answerCall(ringingCall);
                    } else {
                        log("CHLD=2, Answer the call on same sub");
                        if ((backgroundCall.mState == Call.State.HOLDING)
                            && ringingCall.isRinging()) {
                            log("Background is on hold when incoming call came");
                            PhoneUtils.answerCall(ringingCall);
                        } else PhoneUtils.switchHoldingAndActive(backgroundCall);
                    }
                    status = true;
                } else {
                    Log.e(TAG, "Unexpected phone type: " + phoneType);
                    status = false;
                }
                break;

            case CHLD_TYPE_ADDHELDTOCONF:
                if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    CdmaPhoneCallState.PhoneCallState state =
                        PhoneGlobals.getInstance().cdmaPhoneCallState.getCurrentCallState();
                    // For CDMA, we need to check if the call is in THRWAY_ACTIVE state
                    if (state == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                        if (VDBG) log("CHLD:3 Merge Calls");
                        PhoneUtils.mergeCalls();
                        status = true;
                    }    else if (state == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
                        // State is CONF_CALL already and we are getting a merge call
                        // This can happen when CONF_CALL was entered from a Call Waiting
                        // TODO(BT)
                        status = false;
                    } else {
                        Log.e(TAG, "GSG no call to add conference");
                        status = false;
                    }
                } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                    if (mCM.hasActiveFgCall() && mCM.hasActiveBgCall()) {
                        PhoneUtils.mergeCalls();
                        status = true;
                    } else {
                        Log.e(TAG, "GSG no call to merge");
                        status = false;
                    }
                } else {
                    Log.e(TAG, "Unexpected phone type: " + phoneType);
                    status = false;
                }
                break;

            default:
                Log.e(TAG, "bad CHLD value: " + chld);
                status = false;
                break;
        }
        return status;
    }


    private final BroadcastReceiver mCsvtCallStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if ("intent.action.CSVT_PRECISE_CALL_STATE_CHANGED".equals(action)) {
                handlePreciseCallStateChange(null);
            }
        }
    };

    private boolean answerCsvtCall() {
        if (VDBG) log("answerCsvtCall");
        Intent intent = new Intent("com.borqs.videocall.action.answerCall");
        PhoneGlobals.getInstance().sendBroadcast(intent);
        return true;
    }

    private boolean hangupCsvtCall( ) {
        if (VDBG) log("hangupCsvtCall");
        Intent intent = new Intent("com.borqs.videocall.action.StopVTCall");
        PhoneGlobals.getInstance().sendBroadcast(intent);
        return true;
    }

    private final IBluetoothHeadsetPhone.Stub mBinder = new IBluetoothHeadsetPhone.Stub() {
        public boolean answerCall() {
            enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
            if (PhoneUtils.isImsVideoCall(mCM.getFirstActiveRingingCall())) {
                return answerCsvtCall();
            } else {
                return PhoneUtils.answerCall(mCM.getFirstActiveRingingCall());
            }
        }

        public boolean hangupCall() {
            enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
            if (mCM.hasActiveFgCall()) {
                if (PhoneUtils.isImsVideoCall(mCM.getActiveFgCall())) {
                    return hangupCsvtCall();
                } else {
                    return PhoneUtils.hangupActiveCall(mCM.getActiveFgCall());
                }
            } else if (mCM.hasActiveRingingCall()) {
                if (PhoneUtils.isImsVideoCall(mCM.getFirstActiveRingingCall())) {
                    return hangupCsvtCall();
                } else {
                    return PhoneUtils.hangupRingingCall(mCM.getFirstActiveRingingCall());
                }
            } else if (mCM.hasActiveBgCall()) {
                return PhoneUtils.hangupHoldingCall(mCM.getFirstActiveBgCall());
            }
            // TODO(BT) handle virtual voice call
            return false;
        }

        public boolean sendDtmf(int dtmf) {
            enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
            return mCM.sendDtmf((char) dtmf);
        }

        public boolean processChld(int chld) {
            enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
            if (isDsdaEnabled() && (mBluetoothDsda != null)) {
                try {
                    return processDsdaChld(chld);
                } catch (RemoteException e) {
                    Log.e(TAG, " BluetoothDsdaService class not found exception " + e);
                }
            }
            Phone phone = mCM.getDefaultPhone();
            int phoneType = phone.getPhoneType();
            log("processChld: " + chld + " for Phone type: " + phoneType);
            Call ringingCall = mCM.getFirstActiveRingingCall();
            Call backgroundCall = mCM.getFirstActiveBgCall();

            if (chld == CHLD_TYPE_RELEASEHELD) {
                if (ringingCall.isRinging()) {
                    return PhoneUtils.hangupRingingCall(ringingCall);
                } else {
                    return PhoneUtils.hangupHoldingCall(backgroundCall);
                }
            } else if (chld == CHLD_TYPE_RELEASEACTIVE_ACCEPTHELD) {
                if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    if (ringingCall.isRinging()) {
                        // Hangup the active call and then answer call waiting call.
                        if (VDBG) log("CHLD:1 Callwaiting Answer call");
                        PhoneUtils.hangupRingingAndActive(phone);
                    } else {
                        // If there is no Call waiting then just hangup
                        // the active call. In CDMA this mean that the complete
                        // call session would be ended
                        if (VDBG) log("CHLD:1 Hangup Call");
                        PhoneUtils.hangup(PhoneGlobals.getInstance().mCM);
                    }
                    return true;
                } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                    if (ringingCall.isRinging() && (mNumHeld > 0 && mNumActive == 0)) {
                       if (VDBG) log("CHLD:1 Answer the Call");
                       return PhoneUtils.answerCall(ringingCall);
                    }
                    // Hangup active call, answer held call
                    return PhoneUtils.answerAndEndActive(PhoneGlobals.getInstance().mCM, ringingCall);
                } else {
                    Log.e(TAG, "bad phone type: " + phoneType);
                    return false;
                }
            } else if (chld == CHLD_TYPE_HOLDACTIVE_ACCEPTHELD) {
                if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    // For CDMA, the way we switch to a new incoming call is by
                    // calling PhoneUtils.answerCall(). switchAndHoldActive() won't
                    // properly update the call state within telephony.
                    // If the Phone state is already in CONF_CALL then we simply send
                    // a flash cmd by calling switchHoldingAndActive()
                    if (ringingCall.isRinging()) {
                        if (VDBG) log("CHLD:2 Callwaiting Answer call");
                        PhoneUtils.answerCall(ringingCall);
                        PhoneUtils.setMute(false);
                        // Setting the second callers state flag to TRUE (i.e. active)
                        cdmaSetSecondCallState(true);
                        return true;
                    } else if (PhoneGlobals.getInstance().cdmaPhoneCallState
                               .getCurrentCallState()
                               == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
                        if (VDBG) log("CHLD:2 Swap Calls");
                        PhoneUtils.switchHoldingAndActive(backgroundCall);
                        // Toggle the second callers active state flag
                        cdmaSwapSecondCallState();
                        return true;
                    }
                    Log.e(TAG, "CDMA fail to do hold active and accept held");
                    return false;
                } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                    if (ringingCall.isRinging() && (mNumHeld > 0 && mNumActive == 0)) {
                       PhoneUtils.answerCall(ringingCall);
                    } else {
                       PhoneUtils.switchHoldingAndActive(backgroundCall);
                    }
                    return true;
                } else {
                    Log.e(TAG, "Unexpected phone type: " + phoneType);
                    return false;
                }
            } else if (chld == CHLD_TYPE_ADDHELDTOCONF) {
                if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    CdmaPhoneCallState.PhoneCallState state =
                        PhoneGlobals.getInstance().cdmaPhoneCallState.getCurrentCallState();
                    // For CDMA, we need to check if the call is in THRWAY_ACTIVE state
                    if (state == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                        if (VDBG) log("CHLD:3 Merge Calls");
                        PhoneUtils.mergeCalls();
                        return true;
                    }   else if (state == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
                        // State is CONF_CALL already and we are getting a merge call
                        // This can happen when CONF_CALL was entered from a Call Waiting
                        // TODO(BT)
                        return false;
                    }
                    Log.e(TAG, "GSG no call to add conference");
                    return false;
                } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {
                    log("processChld fr CHLD = 3 for GSM, operate only on single sub");
                    if (mCM.hasActiveFgCall() && mCM.hasActiveBgCall()) {
                        PhoneUtils.mergeCalls();
                        return true;
                    } else {
                        Log.e(TAG, "GSG no call to merge");
                        return false;
                    }
                } else {
                    Log.e(TAG, "Unexpected phone type: " + phoneType);
                    return false;
                }
            } else {
                Log.e(TAG, "bad CHLD value: " + chld);
                return false;
            }
        }

        public String getNetworkOperator() {
            enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
            if (isDsdaEnabled()) {
                log("getNetworkOperator for DSDA");
                int activeSub = mCM.getActiveSubscription();
                return mCM.getFgPhone(activeSub).getServiceState().getOperatorAlphaLong();
            }
            return mCM.getDefaultPhone().getServiceState().getOperatorAlphaLong();
        }

        public String getSubscriberNumber() {
            enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
            if (isDsdaEnabled()) {
                log("getSubscriberNumber for DSDA");
                int activeSub = mCM.getActiveSubscription();
                Phone phone = MSimPhoneGlobals.getInstance().getPhone(activeSub);
                return phone.getLine1Number();
            }
            return mCM.getDefaultPhone().getLine1Number();
        }

        public boolean listCurrentCalls() {
            enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
            Message msg = Message.obtain(mHandler, LIST_CURRENT_CALLS);
            mHandler.sendMessage(msg);
            return true;
        }

        public boolean queryPhoneState() {
            enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
            Message msg = Message.obtain(mHandler, QUERY_PHONE_STATE);
            mHandler.sendMessage(msg);
            return true;
        }

        public void updateBtHandsfreeAfterRadioTechnologyChange() {
            enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
            if (VDBG) Log.d(TAG, "updateBtHandsfreeAfterRadioTechnologyChange...");
            updateBtPhoneStateAfterRadioTechnologyChange();
        }

        public void cdmaSwapSecondCallState() {
            enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
            Message msg = Message.obtain(mHandler, CDMA_SWAP_SECOND_CALL_STATE);
            mHandler.sendMessage(msg);
        }

        public void cdmaSetSecondCallState(boolean state) {
            enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, null);
            Message msg = mHandler.obtainMessage(CDMA_SET_SECOND_CALL_STATE, state);
            mHandler.sendMessage(msg);
        }
    };

    // match up with bthf_call_state_t of bt_hf.h
    final static int CALL_STATE_ACTIVE = 0;
    final static int CALL_STATE_HELD = 1;
    final static int CALL_STATE_DIALING = 2;
    final static int CALL_STATE_ALERTING = 3;
    final static int CALL_STATE_INCOMING = 4;
    final static int CALL_STATE_WAITING = 5;
    final static int CALL_STATE_IDLE = 6;

    // match up with bthf_chld_type_t of bt_hf.h
    final static int CHLD_TYPE_RELEASEHELD = 0;
    final static int CHLD_TYPE_RELEASEACTIVE_ACCEPTHELD = 1;
    final static int CHLD_TYPE_HOLDACTIVE_ACCEPTHELD = 2;
    final static int CHLD_TYPE_ADDHELDTOCONF = 3;

     /* Convert telephony phone call state into hf hal call state */
    static int convertCallState(Call.State ringingState, Call.State foregroundState) {
        int retval = CALL_STATE_IDLE;

        if ((ringingState == Call.State.INCOMING) ||
            (ringingState == Call.State.WAITING) )
            retval = CALL_STATE_INCOMING;
        else if (foregroundState == Call.State.DIALING)
            retval = CALL_STATE_DIALING;
        else if (foregroundState == Call.State.ALERTING)
            retval = CALL_STATE_ALERTING;
        else
            retval = CALL_STATE_IDLE;

        if (VDBG) {
            Log.v(TAG, "Call state Converted2: " + ringingState + "/" + foregroundState + " -> " +
                    retval);
        }
        return retval;
    }

    static int convertCallState(Call.State callState) {
        int retval = CALL_STATE_IDLE;

        switch (callState) {
        case IDLE:
        case DISCONNECTED:
        case DISCONNECTING:
            retval = CALL_STATE_IDLE;
            break;
        case ACTIVE:
            retval = CALL_STATE_ACTIVE;
            break;
        case HOLDING:
            retval = CALL_STATE_HELD;
            break;
        case DIALING:
            retval = CALL_STATE_DIALING;
            break;
        case ALERTING:
            retval = CALL_STATE_ALERTING;
            break;
        case INCOMING:
            retval = CALL_STATE_INCOMING;
            break;
        case WAITING:
            retval = CALL_STATE_WAITING;
            break;
        default:
            Log.e(TAG, "bad call state: " + callState);
            retval = CALL_STATE_IDLE;
            break;
        }

        if (VDBG) {
            Log.v(TAG, "Call state Converted2: " + callState + " -> " + retval);
        }

        return retval;
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
