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
import java.util.Collection;

public class SyncQueue extends ArrayList<SyncQueue.SyncRequest> {

    private static final long serialVersionUID = 1L;

    public static abstract class SyncRequest {

        private final SyncQueue mSyncQueue;

        public SyncRequest(SyncQueue queue) {
            mSyncQueue = queue;
        }

        protected abstract void start();

        protected final void end() {
            if (!mSyncQueue.contains(this)) {
                return;
            }
            mSyncQueue.remove(this);
            if (!mSyncQueue.isEmpty()) {
                mSyncQueue.get(0).start();
            }
        }

        public void loop() {
            if (!mSyncQueue.contains(this)) {
                mSyncQueue.add(this);
            }
        }
    }

    @Override
    public boolean add(SyncRequest object) {
        boolean result = super.add(object);
        if (size() == 1) {
            object.start();
        }
        return result;
    }

    @Override
    public void add(int index, SyncRequest object) {
        super.add(index, object);
        if (size() == 1) {
            object.start();
        }
    }

    @Override
    public boolean addAll(Collection<? extends SyncRequest> collection) {
        boolean result = super.addAll(collection);
        if (size() > 0 && size() == collection.size()) {
            get(0).start();
        }
        return result;
    }

    @Override
    public boolean addAll(int index, Collection<? extends SyncRequest> collection) {
        boolean result = super.addAll(index, collection);
        if (size() > 0 && size() == collection.size()) {
            get(0).start();
        }
        return result;
    }
}
