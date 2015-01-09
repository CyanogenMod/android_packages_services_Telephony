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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import com.android.internal.telephony.SubscriptionController;
import android.telephony.TelephonyManager;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.util.NativeTextHelper;
import android.view.WindowManager;

import com.android.internal.telephony.ModemStackController;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppType;
import com.android.internal.telephony.uicc.IccCardStatus.CardState;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccCardApplication;

public class PrimarySubSelectionController extends Handler implements OnClickListener,
        OnDismissListener {

    private static final boolean DEBUG = true;
    static final String TAG = "PrimarySubSelectionController";
    static final int PHONE_COUNT = TelephonyManager.getDefault().getPhoneCount();
    static final boolean MULTI_MODE = TelephonyManager.getDefault().isMultiSimEnabled();

    private static PrimarySubSelectionController instance;
    Phone[] mPhones;
    CardStateMonitor mCardStateMonitor;
    ModemStackController mModemStackController;
    private boolean mAllCardsAbsent = true;
    private boolean mCardChanged = false;
    private boolean mNeedHandleModemReadyEvent = false;
    private boolean mRestoreDdsToPrimarySub = false;
    private boolean[] mIccLoaded;

    public static final String CONFIG_LTE_SUB_SELECT_MODE = "config_lte_sub_select_mode";
    public static final String CONFIG_PRIMARY_SUB_SETABLE = "config_primary_sub_setable";

    private static final int MSG_ALL_CARDS_AVAILABLE = 1;
    private static final int MSG_CONFIG_LTE_DONE = 2;
    private static final int MSG_MODEM_STACK_READY = 3;

    private final Context mContext;
    private AlertDialog mSIMChangedDialog = null;

    private PrimarySubSelectionController(Context context) {
        mContext = context;
        mPhones = new PhoneProxy[PHONE_COUNT];
        mPhones = PhoneFactory.getPhones();
        mCardStateMonitor = new CardStateMonitor(context);
        mCardStateMonitor.registerAllCardsInfoAvailable(this,
                MSG_ALL_CARDS_AVAILABLE, null);
        mModemStackController = ModemStackController.getInstance();

        mIccLoaded = new boolean[PHONE_COUNT];
        for (int i = 0; i < PHONE_COUNT; i++) {
            mIccLoaded[i] = false;
        }

        if (mModemStackController != null) {
            mModemStackController.registerForStackReady(this, MSG_MODEM_STACK_READY, null);
        }

        IntentFilter intentFilter = new IntentFilter(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intentFilter.addAction(Intent.ACTION_LOCALE_CHANGED);
        mContext.registerReceiver(mReceiver, intentFilter);
    }

    private void logd(String message) {
        if (DEBUG) {
            Log.d(TAG, message);
        }
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(action)) {
                final int slot = intent.getIntExtra(PhoneConstants.SLOT_KEY, 0);
                final String stateExtra = intent
                        .getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                mIccLoaded[slot] = false;
                if (IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(stateExtra) ||
                        IccCardConstants.INTENT_VALUE_ICC_IMSI.equals(stateExtra)) {
                    mIccLoaded[slot] = true;
                    int primarySlot = getPrimarySlot();
                    int currentDds = SubscriptionManager.getSlotId(SubscriptionManager
                            .getDefaultDataSubId());
                    logd("ACTION_SIM_STATE_CHANGED current defalut dds :" + currentDds
                            + ", primary slot :" + primarySlot);
                    if (currentDds == primarySlot) {
                        mRestoreDdsToPrimarySub = false;
                        return;
                    }

                    logd("ACTION_SIM_STATE_CHANGED mRestoreDdsToPrimarySub: "
                            + mRestoreDdsToPrimarySub);
                    if (mRestoreDdsToPrimarySub) {
                        if (slot == primarySlot) {
                            logd("restore dds to primary card");
                            SubscriptionController.getInstance().setDefaultDataSubId(SubscriptionManager
                                    .getSubId(slot)[0]);
                            mRestoreDdsToPrimarySub = false;
                        }
                    }
                }
                logd("ACTION_SIM_STATE_CHANGED intent received SIM STATE is " + stateExtra
                        + ", mIccLoaded[" + slot + "] = " + mIccLoaded[slot]);
            } else if(Intent.ACTION_LOCALE_CHANGED.equals(action)){
                logd("Recieved EVENT ACTION_LOCALE_CHANGED");
                if (mSIMChangedDialog != null && mSIMChangedDialog.isShowing()) {
                    logd("Update SIMChanged dialog");
                    mSIMChangedDialog.dismiss();
                    alertSIMChanged();
                }
            }
        }
    };

    protected boolean isCardsInfoChanged(int sub) {
        String iccId = mCardStateMonitor.getIccId(sub);
        String iccIdInSP = PreferenceManager.getDefaultSharedPreferences(mContext).getString(
                PhoneConstants.SUBSCRIPTION_KEY + sub, null);
        logd("sub" + sub + " icc id=" + iccId + ", icc id in sp=" + iccIdInSP);
        return !TextUtils.isEmpty(iccId) && !iccId.equals(iccIdInSP);
    }

    private void saveLteSubSelectMode() {
        Settings.Global.putInt(
                mContext.getContentResolver(),
                CONFIG_LTE_SUB_SELECT_MODE, isManualConfigMode() ? 0 : 1);
    }

    private void savePrimarySetable() {
        Settings.Global.putInt(
                mContext.getContentResolver(),
                CONFIG_PRIMARY_SUB_SETABLE, isPrimarySetable() ? 1 : 0);
    }

    private boolean isManualConfigMode() {
        return isPrimaryLteSubEnabled() && isPrimarySetable()
                && getPrefPrimarySlot() == -1;
    }

    private boolean isAutoConfigMode() {
        return isPrimaryLteSubEnabled() && isPrimarySetable()
                && getPrefPrimarySlot() != -1;
    }

    public void setRestoreDdsToPrimarySub(boolean restoreDdsToPrimarySub) {
        mRestoreDdsToPrimarySub = restoreDdsToPrimarySub;
    }

    private void loadStates() {
        mCardChanged = false;
        for (int i = 0; i < PHONE_COUNT; i++) {
            if (isCardsInfoChanged(i)) {
                mCardChanged = true;
            }
        }
        mAllCardsAbsent = isAllCardsAbsent();
    }

    private boolean isAllCardsAbsent() {
        for (int i = 0; i < PHONE_COUNT; i++) {
            UiccCard uiccCard = CardStateMonitor.getUiccCard(i);
            if (uiccCard == null || uiccCard.getCardState() != CardState.CARDSTATE_ABSENT) {
                logd("card state on sub" + i + " not absent");
                return false;
            }
        }
        logd("all cards absent");
        return true;
    }

    private void saveSubscriptions() {
        for (int i = 0; i < PHONE_COUNT; i++) {
            String iccId = mCardStateMonitor.getIccId(i);
            if (iccId != null) {
                logd("save subscription on sub" + i + ", iccId :" + iccId);
                PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                        .putString(PhoneConstants.SUBSCRIPTION_KEY + i, iccId).commit();
            }
        }
    }

    public void onClick(DialogInterface dialog, int which) {
        switch (which) {
            case DialogInterface.BUTTON_NEGATIVE:
                // do nothing.
                break;
            case DialogInterface.BUTTON_POSITIVE:
                // call dual settings;
                Intent intent = new Intent("com.android.settings.sim.SIM_SUB_INFO_SETTINGS");
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                try {
                    mContext.startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    logd("can not start activity " + intent);
                }
                break;
        }
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
       if (mSIMChangedDialog == (AlertDialog) dialog) {
            mSIMChangedDialog = null;
        }
    }

    protected void alertSIMChanged() {
        AlertDialog.Builder builder = new AlertDialog.Builder(mContext).setTitle(R.string.sim_info)
                .setMessage(Html.fromHtml(getSIMInfo())).setNegativeButton(R.string.close, this);
        if (MULTI_MODE) {
            builder.setPositiveButton(R.string.change, this);
        }
        mSIMChangedDialog = builder.create();
        mSIMChangedDialog.getWindow().setType(
                WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
        mSIMChangedDialog.setOnDismissListener(this);
        mSIMChangedDialog.show();
    }

    private String getSIMInfo() {

        ConnectivityManager connService = (ConnectivityManager) mContext
                .getSystemService(Context.CONNECTIVITY_SERVICE);

        String mobileDataState = connService.getMobileDataEnabled() ? mContext
                .getString(R.string.mobile_data_on) : mContext.getString(R.string.mobile_data_off);

        String html = mContext.getString(R.string.new_sim_detected) + "<br>";
        // show SIM card info
        for (int index = 0; index < PHONE_COUNT; index++) {
            html += getSimName(index) + ":" + getSimCardInfo(index) + "<br>";
        }

        // show data status
        html += mContext.getString(R.string.default_sim_setting) + "<br>"
                + mContext.getString(R.string.mobile_data, mobileDataState);

        return html;
    }

    public String getSimName(int slot) {
        SubscriptionInfo subInfo = SubscriptionManager.from(mContext)
                .getActiveSubscriptionInfo(SubscriptionManager.getSubId(slot)[0]);
        return subInfo == null ? null : subInfo.getDisplayName().toString();
    }

    private String getSimCardInfo(int slot) {
        UiccCard uiccCard = CardStateMonitor.getUiccCard(slot);
        if (uiccCard != null && uiccCard.getCardState() == CardState.CARDSTATE_ABSENT) {
            return mContext.getString(R.string.sim_absent);
        } else {
            String carrierName = TelephonyManager.getDefault().getSimOperatorNameForSubscription(slot);
            if (TextUtils.isEmpty(carrierName) || TextUtils.isDigitsOnly(carrierName)) {
                String iccId = mCardStateMonitor.getIccId(slot);
                String spn = IINList.getDefault(mContext).getSpn(iccId);
                if (spn != null) {
                    carrierName = NativeTextHelper.getInternalLocalString(mContext, spn,
                            R.array.origin_carrier_names, R.array.locale_carrier_names);
                } else {
                    carrierName = mContext.getString(R.string.sim_unknown);
                }
            }
            if (isAutoConfigMode() && slot == getPrimarySlot()) {
                if (uiccCard.isApplicationOnIcc(AppType.APPTYPE_USIM)) {
                    return carrierName + "(4G)";
                } else {
                    return carrierName + "(3G)";
                }
            } else {
                return carrierName;
            }
        }
    }

    private void configPrimaryLteSub() {
        int slot = -1;
        if (!isPrimarySetable()) {
            logd("primary is not setable in any sub!");
        } else {
            int prefPrimarySlot = getPrefPrimarySlot();
            int primarySlot = getPrimarySlot();
            logd("preferred primary slot is " + prefPrimarySlot);
            logd("primary slot is " + primarySlot);
            logd("is card changed? " + mCardChanged);
            if (prefPrimarySlot == -1 && (mCardChanged || primarySlot == -1)) {
                slot = 0;
            } else if (prefPrimarySlot != -1 && (mCardChanged || primarySlot != prefPrimarySlot)) {
                slot = prefPrimarySlot;
            }
            if (slot != -1 && primarySlot == slot && !mCardChanged) {
                logd("primary sub and network mode are all correct, just notify");
                obtainMessage(MSG_CONFIG_LTE_DONE).sendToTarget();
                return;
            } else if (slot == -1) {
                logd("card not changed and primary sub is correct, do nothing");
                return;
            }
        }
        setPreferredNetwork(slot, obtainMessage(MSG_CONFIG_LTE_DONE));
    }

    protected void onConfigLteDone(Message msg) {
        int primarySlot = getPrimarySlot();
        int currentDds = SubscriptionManager.getSlotId(SubscriptionManager
                .getDefaultDataSubId());
        if (primarySlot != -1) {
            logd("onConfigLteDone primary Slot " + primarySlot + ", currentDds = " + currentDds
                    + ", mIccLoaded[" + primarySlot
                    + "] =" + mIccLoaded[primarySlot]);
            if (mIccLoaded[primarySlot]
                    && currentDds != primarySlot) {
                SubscriptionController.getInstance()
                        .setDefaultDataSubId(SubscriptionManager.getSubId(primarySlot)[0]);
                mRestoreDdsToPrimarySub = false;
            } else {
                mRestoreDdsToPrimarySub = true;
            }
        }

        boolean isManualConfigMode = isManualConfigMode();
        logd("onConfigLteDone isManualConfigMode " + isManualConfigMode);
        if(isAutoConfigMode()){
            alertSIMChanged();
        }else if (isManualConfigMode) {
            Intent intent = new Intent(mContext, PrimarySubSetting.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            mContext.startActivity(intent);
        }
    }

    private void setPrimarySub() {
        if (!mModemStackController.isStackReady()) {
            logd("modem stack is not ready, do not set primary sub.");
            mNeedHandleModemReadyEvent = true;
            return;
        }

        // reset states and load again by new card info
        loadStates();
        if (isPrimaryLteSubEnabled()) {
            logd("primary sub config feature is enabled!");
            configPrimaryLteSub();
        }
        saveSubscriptions();
        saveLteSubSelectMode();
        savePrimarySetable();
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_MODEM_STACK_READY:
                logd("on EVENT MSG_MODEM_STACK_READY");
                if (mNeedHandleModemReadyEvent) {
                    setPrimarySub();
                    mNeedHandleModemReadyEvent = false;
                }
                break;
            case MSG_ALL_CARDS_AVAILABLE:
                logd("on EVENT MSG_ALL_CARDS_AVAILABLE");
                setPrimarySub();
                break;
            case MSG_CONFIG_LTE_DONE:
                logd("on EVENT MSG_CONFIG_LTE_DONE");
                onConfigLteDone(msg);
                break;
        }

    }

    public static void init(Context context) {
        synchronized (PrimarySubSelectionController.class) {
            if (instance == null) {
                instance = new PrimarySubSelectionController(context);
            }
        }
    }

    public static PrimarySubSelectionController getInstance() {
        synchronized (PrimarySubSelectionController.class) {
            if (instance == null) {
                throw new RuntimeException("PrimarySubSelectionController was not initialize!");
            }
            return instance;
        }
    }

    public void setPreferredNetwork(int slot, Message callback) {
        int network = -1;
        if (slot >= 0 && slot < PHONE_COUNT) {
            String iccId = mCardStateMonitor.getIccId(slot);
            UiccCard uiccCard = CardStateMonitor.getUiccCard(slot);
            network = IINList.getDefault(mContext).getIINPrefNetwork(iccId, uiccCard);
            if (network == -1) {
                logd("network mode is -1 , can not set primary card ");
                return;
            }
        }

        logd("set primary card for sub" + slot + ", network=" + network);
        new PrefNetworkRequest(mContext, slot, network, callback).loop();
    }

    public int getPrefPrimarySlot() {
        return getPriority(retrievePriorities(), IINList.getDefault(mContext).getHighestPriority());
    }

    public boolean isPrimarySetable() {
        Map<Integer, Integer> priorities = retrievePriorities();
        int unsetableCount = getCount(priorities, -1);
        return unsetableCount < priorities.size();
    }

    public boolean isPrimaryLteSubEnabled() {
        return SystemProperties.getBoolean("persist.radio.primarycard", false)
                && (PHONE_COUNT > 1);
    }

    public int getPrimarySlot() {
        for (int index = 0; index < PHONE_COUNT; index++) {
            int current = getPreferredNetworkFromDb(index);
            if (current == Phone.NT_MODE_TD_SCDMA_GSM_WCDMA_LTE
                    || current == Phone.NT_MODE_TD_SCDMA_GSM_WCDMA) {
                return index;
            }
        }
        return -1;
    }

    private Map<Integer, Integer> retrievePriorities() {
        Map<Integer, Integer> priorities = new HashMap<Integer, Integer>();
        for (int index = 0; index < PHONE_COUNT; index++) {
            String iccId = mCardStateMonitor.getIccId(index);
            UiccCard uiccCard = CardStateMonitor.getUiccCard(index);
            priorities.put(index, IINList.getDefault(mContext).getIINPriority(iccId, uiccCard));
        }
        return priorities;
    }

    private int getPriority(Map<Integer, Integer> priorities, Integer higherPriority) {
        int count = getCount(priorities, higherPriority);
        if (count == 1) {
            return getKey(priorities, higherPriority);
        } else if (count > 1) {
            return -1;
        } else if (higherPriority > 0) {
            return getPriority(priorities, --higherPriority);
        } else {
            return -1;
        }
    }

    private int getCount(Map<Integer, Integer> priorities, int priority) {
        int count = 0;
        for (Integer key : priorities.keySet()) {
            if (priorities.get(key) == priority) {
                count++;
            }
        }
        return count;
    }

    private Integer getKey(Map<Integer, Integer> map, int priority) {
        for (Integer key : map.keySet()) {
            if (map.get(key) == priority) {
                return key;
            }
        }
        return null;
    }

    private int getPreferredNetworkFromDb(int sub) {
        int nwMode = -1;
        try {
            nwMode = TelephonyManager.getIntAtIndex(mContext.getContentResolver(),
                    android.provider.Settings.Global.PREFERRED_NETWORK_MODE, sub);
        } catch (SettingNotFoundException snfe) {
        }
        return nwMode;
    }
}
