/*
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

package com.android.server.am;

import java.util.Iterator;

import android.os.Message;
import android.os.SystemClock;
import android.util.Slog;

public class ActiveServices {
    static final boolean DEBUG_SERVICE = ActivityManagerService.DEBUG_SERVICE;
    static final boolean DEBUG_SERVICE_EXECUTING = ActivityManagerService.DEBUG_SERVICE_EXECUTING;
    static final boolean DEBUG_MU = ActivityManagerService.DEBUG_MU;
    static final String TAG = ActivityManagerService.TAG;
    static final String TAG_MU = ActivityManagerService.TAG_MU;

    // How long we wait for a service to finish executing.
    static final int SERVICE_TIMEOUT = 20*1000;

    // How long a service needs to be running until restarting its process
    // is no longer considered to be a relaunch of the service.
    static final int SERVICE_RESTART_DURATION = 5*1000;

    // How long a service needs to be running until it will start back at
    // SERVICE_RESTART_DURATION after being killed.
    static final int SERVICE_RESET_RUN_DURATION = 60*1000;

    // Multiplying factor to increase restart duration time by, for each time
    // a service is killed before it has run for SERVICE_RESET_RUN_DURATION.
    static final int SERVICE_RESTART_DURATION_FACTOR = 4;

    // The minimum amount of time between restarting services that we allow.
    // That is, when multiple services are restarting, we won't allow each
    // to restart less than this amount of time from the last one.
    static final int SERVICE_MIN_RESTART_TIME_BETWEEN = 10*1000;

    // Maximum amount of time for there to be no activity on a service before
    // we consider it non-essential and allow its process to go on the
    // LRU background list.
    static final int MAX_SERVICE_INACTIVITY = 30*60*1000;

    final ActivityManagerService mAm;

    public ActiveServices(ActivityManagerService service) {
        mAm = service;
    }

    void serviceTimeout(ProcessRecord proc) {
        String anrMessage = null;

        synchronized(this) {
            if (proc.executingServices.size() == 0 || proc.thread == null) {
                return;
            }
            long maxTime = SystemClock.uptimeMillis() - SERVICE_TIMEOUT;
            Iterator<ServiceRecord> it = proc.executingServices.iterator();
            ServiceRecord timeout = null;
            long nextTime = 0;
            while (it.hasNext()) {
                ServiceRecord sr = it.next();
                if (sr.executingStart < maxTime) {
                    timeout = sr;
                    break;
                }
                if (sr.executingStart > nextTime) {
                    nextTime = sr.executingStart;
                }
            }
            if (timeout != null && mAm.mLruProcesses.contains(proc)) {
                Slog.w(TAG, "Timeout executing service: " + timeout);
                anrMessage = "Executing service " + timeout.shortName;
            } else {
                Message msg = mAm.mHandler.obtainMessage(
                        ActivityManagerService.SERVICE_TIMEOUT_MSG);
                msg.obj = proc;
                mAm.mHandler.sendMessageAtTime(msg, nextTime+SERVICE_TIMEOUT);
            }
        }

        if (anrMessage != null) {
            mAm.appNotResponding(proc, null, null, false, anrMessage);
        }
    }
}
