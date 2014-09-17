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
import java.util.Comparator;
import java.util.HashMap;
import java.util.regex.Pattern;

import org.xmlpull.v1.XmlPullParser;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppType;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.util.XmlUtils;

public class IINList {

    private static final String TAG = "IIN_LIST";
    private static final boolean DEBUG = true;

    private final Context mContext;

    public static class IINInfo {
        public Pattern pattern;
        public String app;
        public int priority;
        public int network;

        public static IINInfo from(IINInfo iinInfo) {
            if (iinInfo == null) {
                return null;
            }
            IINInfo iin = new IINInfo();
            iin.pattern = Pattern.compile(iinInfo.pattern.pattern());
            iin.priority = iinInfo.priority;
            iin.network = iinInfo.network;
            iin.app = iinInfo.app;
            return iin;
        }

        @Override
        public String toString() {
            return "[iin " + pattern + ", " + app + ", " + priority + ", " + network + "]";
        }
    }

    public static class PriorityQueue<T> extends ArrayList<T> {

        private static final long serialVersionUID = 1L;

        private Comparator<T> mComparator;

        public PriorityQueue(Comparator<T> comparator) {
            mComparator = comparator;
        }

        @Override
        public boolean add(T e) {
            if (!isEmpty() && mComparator != null) {
                for (int index = 0; index < size(); index++) {
                    if (mComparator.compare(e, get(index)) < 0) {
                        super.add(index, e);
                        return true;
                    }
                }
            }
            return super.add(e);
        }

    }

    private final PriorityQueue<IINInfo> iinConfigs = new PriorityQueue<IINInfo>(
            new Comparator<IINInfo>() {
                public int compare(IINInfo c2, IINInfo c1) {
                    return c1.priority - c2.priority;
                }
            });

    private final HashMap<String, String> iinSpnMap = new HashMap<String, String>();

    private static IINList instance;

    private IINList(Context context) {
        mContext = context;
        loadIINSpnMap();
        loadIINConfigs();
    }

    public static IINList getDefault(Context context) {
        synchronized (IINList.class) {
            if (instance == null) {
                instance = new IINList(context);
            }
            return instance;
        }
    }

    public String getSpn(String iccId) {
        // iin length is 6 or 7
        if (iccId == null || iccId.length() < 6) {
            return null;
        }
        String spn = iinSpnMap.get(iccId.substring(0, 6));
        if (spn == null && iccId.length() > 6) {
            spn = iinSpnMap.get(iccId.substring(0, 7));
        }
        return spn;
    }

    public int getHighestPriority() {
        return iinConfigs.isEmpty() ? -1 : iinConfigs.get(0).priority;
    }

    public IINInfo getIINConfig(String iccId, UiccCard uiccCard) {
        if (iccId == null) {
            return null;
        }
        for (IINInfo iin : iinConfigs) {
            if (iin.pattern.matcher(iccId).find()
                    && (iin.app == null || (uiccCard != null && uiccCard.isApplicationOnIcc(AppType
                            .valueOf(iin.app))))) {
                return IINInfo.from(iin);
            }
        }
        return null;
    }

    public int getIINPriority(String iccId, UiccCard uiccCard) {
        IINInfo iin = getIINConfig(iccId, uiccCard);
        return iin == null ? -1 : iin.priority;
    }

    public int getIINPrefNetwork(String iccId, UiccCard uiccCard) {
        IINInfo iin = getIINConfig(iccId, uiccCard);
        return iin == null ? -1 : iin.network;
    }

    private void loadIINSpnMap() {
        iinSpnMap.clear();
        Resources r = mContext.getResources();
        XmlResourceParser parser = r.getXml(R.xml.iins);
        try {
            XmlUtils.beginDocument(parser, "iins");
            XmlUtils.nextElement(parser);
            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                String iin = parser.getAttributeValue(null, "iin");
                String spn = parser.getAttributeValue(null, "spn");
                if (!TextUtils.isEmpty(iin) && !TextUtils.isEmpty(spn)) {
                    iinSpnMap.put(iin, spn);
                }
                XmlUtils.nextElement(parser);
            }
        } catch (Exception e) {
            Log.w(TAG, "failed to load iins", e);
        } finally {
            parser.close();
        }
        logd("iinSpnMap:" + iinSpnMap);
    }

    private void loadIINConfigs() {
        iinConfigs.clear();
        Resources r = mContext.getResources();
        XmlResourceParser parser = r.getXml(R.xml.iins_conf);
        try {
            XmlUtils.beginDocument(parser, "iins");
            XmlUtils.nextElement(parser);
            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                IINInfo iinInfo = new IINInfo();
                iinInfo.pattern = Pattern.compile(parser.getAttributeValue(null, "pattern"));
                iinInfo.app = parser.getAttributeValue(null, "app_type");
                iinInfo.priority = Integer.parseInt(parser.getAttributeValue(null, "priority"));
                iinInfo.network = Integer.parseInt(parser.getAttributeValue(null, "network"));
                iinConfigs.add(iinInfo);
                XmlUtils.nextElement(parser);
            }
        } catch (Exception e) {
            Log.w(TAG, "failed to load iins_conf", e);
        } finally {
            parser.close();
        }
        logd("iins loaded:" + iinConfigs);
    }

    private void logd(String message) {
        if (DEBUG) {
            Log.d(TAG, message);
        }
    }
}
