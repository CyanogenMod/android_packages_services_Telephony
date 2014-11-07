package com.android.phone;

import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import static com.android.phone.TimeConsumingPreferenceActivity.RESPONSE_ERROR;

public class CallForwardEditPreference extends EditPhoneNumberPreference {
    private static final String LOG_TAG = "CallForwardEditPreference";
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    private static final String SRC_TAGS[]       = {"{0}"};
    private CharSequence mSummaryOnTemplate;
    /**
     * Remembers which button was clicked by a user. If no button is clicked yet, this should have
     * {@link DialogInterface#BUTTON_NEGATIVE}, meaning "cancel".
     *
     * TODO: consider removing this variable and having getButtonClicked() in
     * EditPhoneNumberPreference instead.
     */
    private int mButtonClicked;
    private int mServiceClass;
    private MyHandler mHandler = new MyHandler();
    int reason;
    Phone mPhone;
    boolean canSetTimer = false;
    CallForwardInfo callForwardInfo;
    TimeConsumingPreferenceListener tcpListener;
    //add for CFUT ui test
    boolean isTestForUTInterface
            = SystemProperties.getBoolean("persist.radio.cfu.timer", false);

    public CallForwardEditPreference(Context context, AttributeSet attrs) {
        super(context, attrs);

        mPhone = PhoneGlobals.getPhone();
        mSummaryOnTemplate = this.getSummaryOn();

        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.CallForwardEditPreference, 0, R.style.EditPhoneNumberPreference);
        mServiceClass = a.getInt(R.styleable.CallForwardEditPreference_serviceClass,
                CommandsInterface.SERVICE_CLASS_VOICE);
        reason = a.getInt(R.styleable.CallForwardEditPreference_reason,
                CommandsInterface.CF_REASON_UNCONDITIONAL);
        a.recycle();

        if (DBG) Log.d(LOG_TAG, "mServiceClass=" + mServiceClass + ", reason=" + reason);
    }

    public CallForwardEditPreference(Context context) {
        this(context, null);
    }

    void init(TimeConsumingPreferenceListener listener, boolean skipReading, int phoneId) {

        // getting selected subscription
        if (DBG) Log.d(LOG_TAG, "Getting CallForwardEditPreference phoneId = " + phoneId);
        mPhone = PhoneUtils.getPhoneFromPhoneId(phoneId);

        if (PhoneGlobals.getInstance().isImsPhoneActive(mPhone)){
            setTimeSettingVisibility(true);
            canSetTimer = true;
        }

        tcpListener = listener;
        if (!skipReading) {
            mPhone.getCallForwardingOption(reason,
                    mHandler.obtainMessage(MyHandler.MESSAGE_GET_CF,
                            // unused in this case
                            CommandsInterface.CF_ACTION_DISABLE,
                            MyHandler.MESSAGE_GET_CF, null));
            if (tcpListener != null) {
                tcpListener.onStarted(this, true);
            }
        }
    }

    @Override
    protected void onBindDialogView(View view) {
        // default the button clicked to be the cancel button.
        mButtonClicked = DialogInterface.BUTTON_NEGATIVE;
        super.onBindDialogView(view);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        super.onClick(dialog, which);
        mButtonClicked = which;
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);

        if (DBG) Log.d(LOG_TAG, "mButtonClicked=" + mButtonClicked
                + ", positiveResult=" + positiveResult);
        // Ignore this event if the user clicked the cancel button, or if the dialog is dismissed
        // without any button being pressed (back button press or click event outside the dialog).
        if (this.mButtonClicked != DialogInterface.BUTTON_NEGATIVE) {
            int action = (isToggled() || (mButtonClicked == DialogInterface.BUTTON_POSITIVE)) ?
                    CommandsInterface.CF_ACTION_REGISTRATION :
                    CommandsInterface.CF_ACTION_DISABLE;
            int time = (reason != CommandsInterface.CF_REASON_NO_REPLY) ? 0 : 20;
            final String number = getPhoneNumber();
            final int editStartHour = isAllDayChecked()? 0 : getStartTimeHour();
            final int editStartMinute = isAllDayChecked()? 0 : getStartTimeMinute();
            final int editEndHour = isAllDayChecked()? 0 : getEndTimeHour();
            final int editEndMinute = isAllDayChecked()? 0 : getEndTimeMinute();

            if (DBG) Log.d(LOG_TAG, "callForwardInfo=" + callForwardInfo);

            boolean isCFSettingChanged = true;
            if (action == CommandsInterface.CF_ACTION_REGISTRATION
                    && callForwardInfo != null
                    && callForwardInfo.status == 1
                    && number.equals(callForwardInfo.number)) {
                if (reason == CommandsInterface.CF_REASON_UNCONDITIONAL){
                    // need to check if the time period for CFUT is changed
                    if (isAllDayChecked()){
                        isCFSettingChanged = isTimerValid(callForwardInfo);
                    } else {
                        isCFSettingChanged = callForwardInfo.startHour != editStartHour
                                || callForwardInfo.startHour != editStartMinute
                                || callForwardInfo.endHour != editEndHour
                                || callForwardInfo.endMinute != editEndMinute;
                    }
                } else {
                    // no change, do nothing
                    if (DBG) Log.d(LOG_TAG, "no change, do nothing");
                    isCFSettingChanged = false;
                }
            }
            if (DBG) Log.d(LOG_TAG, "isCFSettingChanged = " + isCFSettingChanged);
            if (isCFSettingChanged) {
                // set to network
                if (DBG) Log.d(LOG_TAG, "reason=" + reason + ", action=" + action
                        + ", number=" + number);

                // Display no forwarding number while we're waiting for
                // confirmation
                setSummaryOn("");

                if (isTestForUTInterface){
                    setFakeCallFowardInfo(editStartHour,
                            editStartMinute, editEndHour, editEndMinute);
                }

                // the interface of Phone.setCallForwardingOption has error:
                // should be action, reason...
                if (reason == CommandsInterface.CF_REASON_UNCONDITIONAL
                        && !isAllDayChecked() && canSetTimer){
                    if (DBG) Log.d(LOG_TAG, "setCallForwardingUncondTimerOption,"
                                                +"starthour = " + editStartHour
                                                + "startminute = " + editStartMinute
                                                + "endhour = " + editEndHour
                                                + "endminute = " + editEndMinute);
                    mPhone.setCallForwardingUncondTimerOption(editStartHour,
                            editStartMinute, editEndHour, editEndMinute, action,
                            reason,
                            number,
                            mHandler.obtainMessage(MyHandler.MESSAGE_SET_CF,
                                    action,
                                    MyHandler.MESSAGE_SET_CF));
                } else {
                mPhone.setCallForwardingOption(action,
                        reason,
                        number,
                        time,
                        mHandler.obtainMessage(MyHandler.MESSAGE_SET_CF,
                                action,
                                MyHandler.MESSAGE_SET_CF));
                }
                if (tcpListener != null) {
                    tcpListener.onStarted(this, false);
                }
            }
        }
    }

    void handleCallForwardResult(CallForwardInfo cf) {
        callForwardInfo = cf;
        if (DBG) Log.d(LOG_TAG, "handleGetCFResponse done, callForwardInfo=" + callForwardInfo);

        setToggled(callForwardInfo.status == 1);
        setPhoneNumber(callForwardInfo.number);

        //for cfu case, need to set time if timer is valid in callForwardInfo .
        if (callForwardInfo.reason == CommandsInterface.CF_REASON_UNCONDITIONAL){
            //set all day not checked if timer info is valid (which means not all be zero).
            setAllDayCheckBox(!(callForwardInfo.status == 1 && isTimerValid(callForwardInfo)));
            //set timer info even all be zero
            setPhoneNumberWithTimePeriod(callForwardInfo.number,
                    callForwardInfo.startHour, callForwardInfo.startMinute,
                    callForwardInfo.endHour, callForwardInfo.endMinute);
        }
    }

    private void updateSummaryText() {
        if (DBG) Log.d(LOG_TAG, "updateSummaryText, complete fetching for reason " + reason);
        if (isToggled()) {
            CharSequence summaryOn;
            String number = getRawPhoneNumber();
            if (reason == CommandsInterface.CF_REASON_UNCONDITIONAL
                    && PhoneGlobals.getInstance().isImsPhoneActive(mPhone)){
                number = getRawPhoneNumberWithTime();
            }
            if (number != null && number.length() > 0) {
                String values[] = { number };
                summaryOn = TextUtils.replace(mSummaryOnTemplate, SRC_TAGS, values);
            } else {
                summaryOn = getContext().getString(R.string.sum_cfu_enabled_no_number);
            }
            setSummaryOn(summaryOn);
        }

    }

    // Message protocol:
    // what: get vs. set
    // arg1: action -- register vs. disable
    // arg2: get vs. set for the preceding request
    private class MyHandler extends Handler {
        static final int MESSAGE_GET_CF = 0;
        static final int MESSAGE_SET_CF = 1;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_CF:
                    handleGetCFResponse(msg);
                    break;
                case MESSAGE_SET_CF:
                    handleSetCFResponse(msg);
                    break;
            }
        }

        private void handleGetCFResponse(Message msg) {
            if (DBG) Log.d(LOG_TAG, "handleGetCFResponse: done");

            if (msg.arg2 == MESSAGE_SET_CF) {
                tcpListener.onFinished(CallForwardEditPreference.this, false);
            } else {
                tcpListener.onFinished(CallForwardEditPreference.this, true);
            }

            AsyncResult ar = (AsyncResult) msg.obj;

            callForwardInfo = null;
            if (ar.exception != null && !isTestForUTInterface) {
                if (DBG) Log.d(LOG_TAG, "handleGetCFResponse: ar.exception=" + ar.exception);
                tcpListener.onException(CallForwardEditPreference.this,
                        (CommandException) ar.exception);
            } else {
                if (ar.userObj instanceof Throwable && !isTestForUTInterface) {
                    tcpListener.onError(CallForwardEditPreference.this, RESPONSE_ERROR);
                }
                CallForwardInfo cfInfoArray[] = (CallForwardInfo[]) ar.result;
                if (DBG && isTestForUTInterface){
                    if (cfInfoArray == null){
                        cfInfoArray = new CallForwardInfo[1];
                        cfInfoArray[0] = getFakeCallFowardInfo();
                    }
                }
                if (cfInfoArray.length == 0) {
                    if (DBG) Log.d(LOG_TAG, "handleGetCFResponse: cfInfoArray.length==0");
                    setEnabled(false);
                    tcpListener.onError(CallForwardEditPreference.this, RESPONSE_ERROR);
                } else {
                    for (int i = 0, length = cfInfoArray.length; i < length; i++) {
                        if (DBG) Log.d(LOG_TAG, "handleGetCFResponse, cfInfoArray[" + i + "]="
                                + cfInfoArray[i]);
                        if ((mServiceClass & cfInfoArray[i].serviceClass) != 0) {
                            // corresponding class
                            CallForwardInfo info = cfInfoArray[i];
                            handleCallForwardResult(info);

                            // Show an alert if we got a success response but
                            // with unexpected values.
                            // Currently only handle the fail-to-disable case
                            // since we haven't observed fail-to-enable.
                            if (msg.arg2 == MESSAGE_SET_CF &&
                                    msg.arg1 == CommandsInterface.CF_ACTION_DISABLE &&
                                    info.status == 1) {
                                CharSequence s;
                                switch (reason) {
                                    case CommandsInterface.CF_REASON_BUSY:
                                        s = getContext().getText(R.string.disable_cfb_forbidden);
                                        break;
                                    case CommandsInterface.CF_REASON_NO_REPLY:
                                        s = getContext().getText(R.string.disable_cfnry_forbidden);
                                        break;
                                    default: // not reachable
                                        s = getContext().getText(R.string.disable_cfnrc_forbidden);
                                }
                                AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                                builder.setNeutralButton(R.string.close_dialog, null);
                                builder.setTitle(getContext().getText(R.string.error_updating_title));
                                builder.setMessage(s);
                                builder.setCancelable(true);
                                builder.create().show();
                            }
                        }
                    }
                }
            }

            // Now whether or not we got a new number, reset our enabled
            // summary text since it may have been replaced by an empty
            // placeholder.
            updateSummaryText();
        }

        private void handleSetCFResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception != null) {
                if (DBG) Log.d(LOG_TAG, "handleSetCFResponse: ar.exception=" + ar.exception);
                // setEnabled(false);
            }
            if (DBG && isTestForUTInterface) {
                ar.exception = null;
            }
            if (DBG) Log.d(LOG_TAG, "handleSetCFResponse: re get");
            mPhone.getCallForwardingOption(reason,
                    obtainMessage(MESSAGE_GET_CF, msg.arg1, MESSAGE_SET_CF, ar.exception));
        }
    }

    //used to check if timer infor is valid
    private boolean isTimerValid(CallForwardInfo cfinfo) {
        return cfinfo.startHour != 0 || cfinfo.startMinute != 0
                  || cfinfo.endHour != 0 || cfinfo.endMinute != 0;
    }
    //used to get fake call fowarding info
    private CallForwardInfo getFakeCallFowardInfo(){
        CallForwardInfo cfInfoTest = new CallForwardInfo();
        cfInfoTest.status = 1;
        cfInfoTest.reason = 0;
        cfInfoTest.serviceClass = 1;
        cfInfoTest.toa = 0;
        cfInfoTest.number = "00900";
        cfInfoTest.timeSeconds = 0;
        cfInfoTest.startHour= SystemProperties.getInt("persist.radio.cfu.sh", 0);
        cfInfoTest.startMinute= SystemProperties.getInt("persist.radio.cfu.sm", 0);
        cfInfoTest.endHour =SystemProperties.getInt("persist.radio.cfu.eh", 0);
        cfInfoTest.endMinute = SystemProperties.getInt("persist.radio.cfu.em", 0);
        return cfInfoTest;
    }
    //used to set fake call fowarding info
    private void setFakeCallFowardInfo(int editStartHour,
            int editStartMinute, int editEndHour, int editEndMinute){
        SystemProperties.set("persist.radio.cfu.sh", String.valueOf(editStartHour));
        SystemProperties.set("persist.radio.cfu.sm", String.valueOf(editStartMinute));
        SystemProperties.set("persist.radio.cfu.eh", String.valueOf(editEndHour));
        SystemProperties.set("persist.radio.cfu.em", String.valueOf(editEndMinute));
    }
}
