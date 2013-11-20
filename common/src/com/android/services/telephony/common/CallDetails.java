/* Copyright (c) 2012, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *  * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *  * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES O
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.services.telephony.common;

import java.util.Map;
import java.util.Map.Entry;
import android.util.Log;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * CallDetails class takes care of all the additional details like call type and
 * domain needed for IMS calls. This class is not relevant for non-IMS calls
 */
public class CallDetails implements Parcelable {

    /*
     * Type of the call based on the media type and the direction of the media.
     */

    public static final int CALL_TYPE_VOICE = 0; /*
                                                  * Phone.CALL_TYPE_VOICE /*
                                                  * Voice call-audio in both
                                                  * directions
                                                  */

    public static final int CALL_TYPE_VT_TX = 1; /*
                                                  * Phone.CALL_TYPE_VT_TX; PS
                                                  * Video telephony call: one
                                                  * way TX video, two way audio
                                                  */

    public static final int CALL_TYPE_VT_RX = 2; /*
                                                  * Phone.CALL_TYPE_VT_RX Video
                                                  * telephony call: one way RX
                                                  * video, two way audio
                                                  */

    public static final int CALL_TYPE_VT = 3; /*
                                               * Phone.CALL_TYPE_VT; Video
                                               * telephony call: two way video,
                                               * two way audio
                                               */

    public static final int CALL_TYPE_VT_NODIR = 4; /*
                                                     * Phone.CALL_TYPE_VT_NODIR;
                                                     * Video telephony call: no
                                                     * direction, two way audio,
                                                     * intermediate state in a
                                                     * video call till video
                                                     * link is setup
                                                     */

    public static final int CALL_TYPE_SMS = 5; /*
                                                * Phone.CALL_TYPE_SMS;SMS Type
                                                */

    public static final int CALL_TYPE_UNKNOWN = 10; /*
                                                     * Phone.CALL_TYPE_UNKNOWN;
                                                     * Unknown Call type, may be
                                                     * used for answering call
                                                     * with same call type as
                                                     * incoming call. This is
                                                     * only for telephony, not
                                                     * meant to be passed to RIL
                                                     */

    public static final int CALL_DOMAIN_UNKNOWN = 11; /*
                                                       * Phone.CALL_DOMAIN_UNKNOWN
                                                       * ; Unknown domain. Sent
                                                       * by RIL when modem has
                                                       * not yet selected a
                                                       * domain for a call
                                                       */
    public static final int CALL_DOMAIN_CS = 1; /*
                                                 * Phone.CALL_DOMAIN_CS; Circuit
                                                 * switched domain
                                                 */
    public static final int CALL_DOMAIN_PS = 2; /*
                                                 * Phone.CALL_DOMAIN_PS; Packet
                                                 * switched domain
                                                 */
    public static final int CALL_DOMAIN_AUTOMATIC = 3; /*
                                                        * Phone.
                                                        * CALL_DOMAIN_AUTOMATIC;
                                                        * Automatic domain. Sent
                                                        * by Android to indicate
                                                        * that the domain for a
                                                        * new call should be
                                                        * selected by modem
                                                        */
    public static final int CALL_DOMAIN_NOT_SET = 4; /*
                                                      * Phone.CALL_DOMAIN_NOT_SET
                                                      * ; Init value used
                                                      * internally by telephony
                                                      * until domain is set
                                                      */

    public static final int CALL_RESTRICT_CAUSE_NONE = 0; /*
                                                           * Default cause, not
                                                           * restricted
                                                           */
    public static final int CALL_RESTRICT_CAUSE_RAT = 1; /*
                                                          * Service not
                                                          * supported by RAT
                                                          */
    public static final int CALL_RESTRICT_CAUSE_DISABLED = 2; /*
                                                               * Service
                                                               * disabled
                                                               */

    public static final String EXTRAS_IS_CONFERENCE_URI = "isConferenceUri";
    public static final String EXTRAS_PARENT_CALL_ID = "parentCallId";

    private int mCallType;
    private int mCallDomain;
    private String[] mExtras;
    private String mErrorInfo;
    private String[] mConfParList;
    private boolean mIsMpty;
    private static String LOG_TAG = "CallDetails";

    public CallDetails() {
        mCallType = CALL_TYPE_UNKNOWN;
        mCallDomain = CALL_DOMAIN_NOT_SET;
        mExtras = null;
        mConfParList = new String[0];
        mErrorInfo = "";
        mIsMpty = false;
    }

    public CallDetails(Parcel in) {
        readFromParcel(in);
    }

    public CallDetails(int callType, int callDomain, String[] extraparams) {
        callType = callType;
        callDomain = callDomain;
        mExtras = extraparams;
    }

    public CallDetails(int callType, int callDomain, String[] extraparams, String errorInfo,
            String[] confParList) {
        callType = callType;
        callDomain = callDomain;
        mExtras = extraparams;
        errorInfo = errorInfo;
        confParList = confParList;
    }

    public CallDetails(CallDetails srcCall) {
        if (srcCall != null) {
            mCallType = srcCall.mCallType;
            mCallDomain = srcCall.mCallDomain;
            mExtras = srcCall.mExtras;
        }
    }

    public static final Parcelable.Creator<CallDetails> CREATOR =
            new Parcelable.Creator<CallDetails>() {
                public CallDetails createFromParcel(Parcel in) {
                    return new CallDetails(in);
                }

                public CallDetails[] newArray(int size) {
                    return new CallDetails[size];
                }
            };

    public int describeContents() {
        return 0;
    }

    public int getCallDomain() {
        return mCallDomain;
    }

    public void setCallDomain(int callDomain) {
        this.mCallDomain = callDomain;
    }

    public int getCallType() {
        return mCallType;
    }

    public void setCallType(int callType) {
        this.mCallType = callType;
    }

    public boolean isMpty() {
        return mIsMpty;
    }

    public void setMpty(boolean mpty) {
        this.mIsMpty = mpty;
    }

    public String getErrorInfo() {
        return mErrorInfo;
    }

    public void setErrorInfo(String errorInfo) {
        this.mErrorInfo = errorInfo;
    }

    public void setExtras(String[] extraparams) {
        if (extraparams != null) {
            int listLength = extraparams.length;
            mExtras = new String[listLength];
            for (int i = 0; i < listLength; i++) {
                mExtras[i] = extraparams[i];
            }
        } else {
            Log.e(LOG_TAG, "list is null in setConfUriList");
        }
    }

    public void setConfUriList(String[] list) {
        if (list != null) {
            int listLength = list.length;
            mConfParList = new String[listLength];
            for (int i = 0; i < listLength; i++) {
                mConfParList[i] = list[i];
            }
        } else {
            Log.e(LOG_TAG, "list is null in setConfUriList");
        }
    }

    public String[] getExtras() {
        return mExtras;
    }

    public String[] getConfParticipantList() {
        return mConfParList;
    }

    public void writeToParcel(Parcel dest, int flag) {
        dest.writeInt(mCallType);
        dest.writeInt(mCallDomain);
        dest.writeStringArray(mExtras);
        dest.writeString(mErrorInfo);
        dest.writeStringArray(mConfParList);
        dest.writeByte((byte) (mIsMpty ? 1 : 0));
    }

    public void readFromParcel(Parcel in) {
        mCallType = in.readInt();
        mCallDomain = in.readInt();
        mExtras = in.readStringArray();
        mErrorInfo = in.readString();
        mConfParList = in.readStringArray();
        mIsMpty = in.readByte() != 0;
    }

    public static String[] getExtrasFromMap(Map<String, String> newExtras) {
        String[] extras = null;

        if (newExtras == null) {
            return null;
        }

        // TODO: Merge new extras into extras. For now, just serialize and set
        // them
        extras = new String[newExtras.size()];

        if (extras != null) {
            int i = 0;
            for (Entry<String, String> entry : newExtras.entrySet()) {
                extras[i] = "" + entry.getKey() + "=" + entry.getValue();
            }
        }
        return extras;
    }

    public void setExtrasFromMap(Map<String, String> newExtras) {
        this.mExtras = getExtrasFromMap(newExtras);
    }

    public String getValueForKeyFromExtras(String[] extras, String key) {
        for (int i = 0; extras != null && i < extras.length; i++) {
            if (extras[i] != null) {
                String[] currKey = extras[i].split("=");
                if (currKey.length == 2 && currKey[0].equals(key)) {
                    return currKey[1];
                }
            }
        }
        return null;
    }

    /**
     * @return string representation.
     */
    @Override
    public String toString() {
        String extrasResult = "";
        String uri = "";
        if (mExtras != null) {
            for (String s : mExtras) {
                extrasResult += s;
            }
        }

        if (mConfParList != null) {
            for (String s : mConfParList) {
                uri += s;
            }
        }

        return (" calltype" + mCallType
                + " domain" + mCallDomain
                + " erroinfo" + mErrorInfo
                + " mConfParList" + uri
                + " multiparty" + mIsMpty
                + " " + extrasResult);
    }
}
