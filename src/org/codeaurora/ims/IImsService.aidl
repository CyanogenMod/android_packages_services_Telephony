/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above
 *    copyright notice, this list of conditions and the following
 *    disclaimer in the documentation and/or other materials provided
 *    with the distribution.
 *  * Neither the name of The Linux Foundation nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.

 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
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

package org.codeaurora.ims;

import org.codeaurora.ims.IImsServiceListener;
import android.os.Messenger;
import android.os.Message;

/**
 * Interface used to interact with IMS phone.
 *
 * {@hide}
 */
interface IImsService {

    /**
     * Register callback
     * @param imsServListener - IMS Service Listener
     */
    int registerCallback(IImsServiceListener imsServListener);

    /**
     * Deregister callback
     * @param imsServListener - IMS Service Listener
     */
    int deregisterCallback(IImsServiceListener imsServListener);

    /**
     * Set IMS Registration state
     * @param imsRegState - IMS Registration state
     */
    void setRegistrationState(int imsRegState);

    /**
     * Get IMS Registration state
     */
    int getRegistrationState();

    /**
     * Query the Service State for all IMS services
     * @param event - Handle to track the response for query operation
     * @param msgr - Messenger object used as a handle for response
     */
    void queryImsServiceStatus(int event, in Messenger msgr);

    /**
     * Set the Service State for a services
     * @param service - Type of IMS Service - voice, video etc
     * @param networkType - Network Type on which the set op is applicable ex: LTE only
     * @param enabled - Service should be enabled or not
     * @param restrictCause - Restriction Cause for the service disable, ex: if Video is
     *                        disabled, give a cause why so
     * @param event - Handle to track the response for set operation
     * @param msgr - Messenger object used as a handle for response
     */
    void setServiceStatus(int service, int networkType, int enabled, int restrictCause,
            int event, in Messenger msgr);

    /**
     * Query for current video call quality.
     * @param response - Message object is used to send back the status and quality value.
     * Message.arg1 contains 0 if the request succeeded, non-zero otherwise.
     * Message.obj int[] array, which int[0] element contains video quality value: 0-LOW; 1-HIGH.
     * Message.replyTo must be a valid Messenger.
     */
    void queryVtQuality(in Message response);

    /**
     * Set for current video call quality.
     * @param quality - Video call quality to set: 0-LOW; 1-HIGH.
     * @param response - Message object is used to send back the status and quality value.
     * Message.arg1 contains 0 if the request succeeded, non-zero otherwise.
     * Message.replyTo must be a valid Messenger.
     */
    void setVtQuality(int quality, in Message response);
}

