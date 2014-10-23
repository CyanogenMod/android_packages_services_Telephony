/*
 * Copyright (c) 2014, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package com.android.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppState;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppType;
import com.android.internal.telephony.uicc.IccCardStatus.CardState;
import com.android.internal.telephony.uicc.IccConstants;
import com.android.internal.telephony.uicc.IccFileHandler;
import com.android.internal.telephony.uicc.IccUtils;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.UiccController;

public class CardStateMonitor extends Handler {

    private static final String TAG = "CardStateMonitor";
    private final static boolean DEBUG = true;
    static final int PHONE_COUNT = TelephonyManager.getDefault().getPhoneCount();

    private RegistrantList mAllCardsInfoAvailableRegistrants = new RegistrantList();

    private static final int EVENT_ICC_CHANGED = 1;
    private static final int EVENT_ICCID_LOAD_DONE = 2;

    static class CardInfo {
        boolean mLoadingIcc;
        String mIccId;
        String mCardState;

        boolean isCardStateEquals(String cardState) {
            return TextUtils.equals(mCardState, cardState);
        }

        boolean isCardAvailable() {
            return !isCardStateEquals(null)
                    && !(isCardStateEquals(CardState.CARDSTATE_PRESENT.toString()) && TextUtils
                            .isEmpty(mIccId));
        }

        private void reset() {
            mLoadingIcc = false;
            mIccId = null;
            mCardState = null;
        }
    }

    private static boolean mIsShutDownInProgress;
    private CardInfo[] mCards = new CardInfo[PHONE_COUNT];
    private Context mContext;
    private BroadcastReceiver receiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SHUTDOWN.equals(intent.getAction()) &&
                    !intent.getBooleanExtra(Intent.EXTRA_SHUTDOWN_USERSPACE_ONLY, false)) {
                logd("ACTION_SHUTDOWN Received");
                mIsShutDownInProgress = true;
            }
        }
    };

    public CardStateMonitor(Context context) {
        mContext = context;
        for (int index = 0; index < PHONE_COUNT; index++) {
            mCards[index] = new CardInfo();
        }
        UiccController.getInstance().registerForIccChanged(this, EVENT_ICC_CHANGED, null);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SHUTDOWN);
        mContext.registerReceiver(receiver, filter);
    }

    public void dispose() {
        mContext.unregisterReceiver(receiver);
        UiccController.getInstance().unregisterForIccChanged(this);
    }

    public void registerAllCardsInfoAvailable(Handler handler, int what, Object obj) {
        Registrant r = new Registrant(handler, what, obj);
        synchronized (mAllCardsInfoAvailableRegistrants) {
            mAllCardsInfoAvailableRegistrants.add(r);
            for (int index = 0; index < PHONE_COUNT; index++) {
                if (!mCards[index].isCardAvailable()) {
                    return;
                }
            }
            r.notifyRegistrant();
        }
    }

    public void unregisterAllCardsInfoAvailable(Handler handler) {
        synchronized (mAllCardsInfoAvailableRegistrants) {
            mAllCardsInfoAvailableRegistrants.remove(handler);
        }
    }

    public CardInfo getCardInfo(int cardIndex) {
        return mCards[cardIndex];
    }

    public String getIccId(int cardIndex) {
        return mCards[cardIndex].mIccId;
    }

    public static UiccCard getUiccCard(int cardIndex) {
        UiccCard uiccCard = null;
        PhoneBase phone = (PhoneBase) ((PhoneProxy) PhoneFactory.getPhones()[cardIndex])
                .getActivePhone();
        if (mIsShutDownInProgress
                || Settings.Global.getInt(phone.getContext().getContentResolver(),
                        Settings.Global.AIRPLANE_MODE_ON, 0) == 1) {
            return null;
        }
        if (phone.mCi.getRadioState().isOn()) {
            uiccCard = UiccController.getInstance().getUiccCard(cardIndex);
        }
        return uiccCard;
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_ICC_CHANGED:
                logd("on EVENT_ICC_CHANGED");
                onIccChanged((AsyncResult) msg.obj);
                break;
            case EVENT_ICCID_LOAD_DONE:
                logd("on EVENT_ICCID_LOAD_DONE");
                onIccIdLoaded((AsyncResult) msg.obj);
                break;
        }
    }

    private void onIccIdLoaded(AsyncResult iccIdResult) {
        byte[] data = (byte[]) iccIdResult.result;
        int cardIndex = (Integer) iccIdResult.userObj;
        String iccId = null;
        if (iccIdResult.exception != null) {
            logd("Exception in GET ICCID," + iccIdResult.exception);
        } else {
            iccId = IccUtils.bcdToString(data, 0, data.length);
            logd("get iccid on card" + cardIndex + ", iccId=" + iccId);
        }
        mCards[cardIndex].mLoadingIcc = false;
        if (!TextUtils.isEmpty(iccId)) {
            mCards[cardIndex].mIccId = iccId;
            mCards[cardIndex].mCardState = CardState.CARDSTATE_PRESENT.toString();
            notifyAllCardsAvailableIfNeed();
        }
    }

    private void onIccChanged(AsyncResult iccChangedResult) {
        if (iccChangedResult == null || iccChangedResult.result == null) {
            for (int index = 0; index < PHONE_COUNT; index++) {
                updateCardState(index);
            }
        } else {
            updateCardState((Integer) iccChangedResult.result);
        }
    }

    private void updateCardState(int sub) {
        UiccCard uiccCard = getUiccCard(sub);
        logd("ICC changed on sub" + sub + ", state is "
                + (uiccCard == null ? "NULL" : uiccCard.getCardState()));
        notifyCardAvailableIfNeed(sub, uiccCard);
    }

    private void loadIccId(int sub, UiccCard uiccCard) {
        mCards[sub].mLoadingIcc = true;
        boolean request = false;
        UiccCardApplication validApp = null;
        int numApps = uiccCard.getNumApplications();
        for (int i = 0; i < numApps; i++) {
            UiccCardApplication app = uiccCard.getApplicationIndex(i);
            if (app != null && app.getType() != AppType.APPTYPE_UNKNOWN) {
                validApp = app;
                break;
            }
        }
        if (validApp != null) {
            IccFileHandler fileHandler = validApp.getIccFileHandler();
            if (fileHandler != null) {
                fileHandler.loadEFTransparent(IccConstants.EF_ICCID,
                        obtainMessage(EVENT_ICCID_LOAD_DONE, sub));
                request = true;
            }
        }
        if (!request) {
            mCards[sub].mLoadingIcc = false;
        }
    }

    private void notifyCardAvailableIfNeed(int sub, UiccCard uiccCard) {
        if (uiccCard != null) {
            if(CardState.CARDSTATE_ABSENT == uiccCard.getCardState()){
                logd("notifyCardAvailableIfNeed sim hot swap");
                mCards[sub].mLoadingIcc = false;
                mCards[sub].mIccId = null;
            }

            if (CardState.CARDSTATE_PRESENT == uiccCard.getCardState()
                    && TextUtils.isEmpty(mCards[sub].mIccId)) {
                if (!mCards[sub].mLoadingIcc) {
                    loadIccId(sub, uiccCard);
                }
            } else if (!mCards[sub].isCardStateEquals(uiccCard.getCardState().toString())) {
                mCards[sub].mCardState = uiccCard.getCardState().toString();
                notifyAllCardsAvailableIfNeed();
            }
        } else {
            // card is null, means card info is inavailable or the device is in
            // APM, need to reset all card info, otherwise no change will be
            // detected when card info is available again!
            mCards[sub].reset();
        }
    }

    private void notifyAllCardsAvailableIfNeed() {
        for (int index = 0; index < PHONE_COUNT; index++) {
            if (!mCards[index].isCardAvailable()) {
                return;
            }
        }
        mAllCardsInfoAvailableRegistrants.notifyRegistrants();
    }

    static void logd(String msg) {
        if (DEBUG) {
            Log.d(TAG, msg);
        }
    }
}
