/*
* Copyright (C) 2011-2014 Mediatek.inc.
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
package com.mediatek.phone;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemProperties;
import android.util.Log;

import com.android.phone.PhoneGlobals;

public class PhoneFeatureConstants {

    public static final class FeatureOption {
        private static final String TAG = "FeatureOption";
        private static final String MTK_DUAL_MIC_SUPPORT = "MTK_DUAL_MIC_SUPPORT";
        private static final String MTK_DUAL_MIC_SUPPORT_on = "MTK_DUAL_MIC_SUPPORT=true";
        private final static String ONE = "1";
        // C2K 5M (CLLWG)
        private final static String C2K_5M = "CLLWG";

        // C2k 3M (CWG)
        private final static String C2K_3M = "CWG";

        // C2k OM 4M (CLLG)
        private final static String C2K_4M = "CLLG";

        public static boolean isMtkDualMicSupport() {
            String state = null;
            AudioManager audioManager = (AudioManager)
                    PhoneGlobals.getInstance().getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                state = audioManager.getParameters(MTK_DUAL_MIC_SUPPORT);
                Log.d(state, "isMtkDualMicSupport(): state: " + state);
                if (state.equalsIgnoreCase(MTK_DUAL_MIC_SUPPORT_on)) {
                    return true;
                }
            }
            return false;
        }

        public static boolean isMtkFemtoCellSupport() {
            boolean isSupport = ONE.equals(
                    SystemProperties.get("ro.mtk_femto_cell_support")) ? true : false;
            Log.d(TAG, "isMtkFemtoCellSupport(): " + isSupport);
            return isSupport;
        }

        public static boolean isMtk3gDongleSupport() {
            boolean isSupport = ONE.equals(
                    SystemProperties.get("ro.mtk_3gdongle_support")) ? true : false;
            Log.d(TAG, "isMtk3gDongleSupport()" + isSupport);
            return isSupport;
        }

        public static boolean isMtkLteSupport() {
            boolean isSupport = ONE.equals(
                    SystemProperties.get("ro.mtk_lte_support")) ? true : false;
            Log.d(TAG, "isMtkLteSupport(): " + isSupport);
            return isSupport;
        }

        /**
         * ro.mtk_c2k_om_nw_sel_type value:
         * 1 : Foreign (India)
         * 0 : Home
         * @return
         */
        public static boolean isLoadForHome() {
            boolean isSupport = 1 == SystemProperties.getInt(
                    "ro.mtk_c2k_om_nw_sel_type", 0) ? false : true;
            Log.d(TAG, "isLoadForHome(): " + isSupport);
            return isSupport;
        }

        public static boolean isMtkC2k5MSupport() {
            boolean isSupport = C2K_5M.equalsIgnoreCase(
                    SystemProperties.get("ro.mtk.c2k.om.mode")) ? true : false;
            Log.d(TAG, "isMtkC2k5M(): " + isSupport);
            return isSupport;
        }

        public static boolean isMtkC2k4MSupport() {
            boolean isSupport = C2K_4M.equalsIgnoreCase(
                    SystemProperties.get("ro.mtk.c2k.om.mode")) ? true : false;
            Log.d(TAG, "isMtkC2k4M(): " + isSupport);
            return isSupport;
        }

        public static boolean isMtkC2k3MSupport() {
            boolean isSupport = C2K_3M.equalsIgnoreCase(
                    SystemProperties.get("ro.mtk.c2k.om.mode")) ? true : false;
            Log.d(TAG, "isMtkC2k3M(): " + isSupport);
            return isSupport;
        }

        public static boolean isMtkSvlteSupport() {
            boolean isSupport = ONE.equals(
                    SystemProperties.get("ro.mtk_svlte_support")) ? true : false;
            Log.d(TAG, "isMtkSvlteSupport(): " + isSupport);
            return isSupport;
        }

        public static boolean isMtkSrlteSupport() {
            boolean isSupport = ONE.equals(
                    SystemProperties.get("ro.mtk_srlte_support")) ? true : false;
            Log.d(TAG, "isMtkSrlteSupport(): " + isSupport);
            return isSupport;
        }

        /**
         * add for svlte solution2 selection.
         * solution2: true
         * solution1.5: false
         * @return
         */
        public static boolean isMtkSvlteSolutionSupport(){
            boolean isSupport = ONE.equals(
                    SystemProperties.get("ro.mtk.c2k.slot2.support")) ? true : false;
            Log.d(TAG, "isMtkSvlteSupport(): " + isSupport);
            return isSupport;
        }

        public static boolean isMtkCtaSet() {
            boolean isSupport = ONE.equals(
                    SystemProperties.get("ro.mtk_cta_set")) ? true : false;
            Log.d(TAG, "isMtkCtaSet(): " + isSupport);
            return isSupport;
        }

        public static boolean isMTKA1Support() {
            boolean isSupport = ONE.equals(
                    SystemProperties.get("ro.mtk_a1_feature")) ? true : false;
            Log.d(TAG, "isMTKA1Support(): " + isSupport);
            return isSupport;
        }
    }
}
