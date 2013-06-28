/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.os;

import com.google.android.collect.Maps;

import java.util.HashMap;

/** @hide */
public class SystemService {

    private static HashMap<String, State> sStates = Maps.newHashMap();

    /**
     * State of a known {@code init} service.
     */
    public enum State {
        RUNNING("running"),
        STOPPING("stopping"),
        STOPPED("stopped"),
        RESTARTING("restarting");

        State(String state) {
            sStates.put(state, this);
        }
    }

        /** Request that the init daemon start a named service. */
    public static void start(String name) {
        SystemProperties.set("ctl.start", name);
    }
    
        /** Request that the init daemon stop a named service. */
    public static void stop(String name) {
        SystemProperties.set("ctl.stop", name);
    }

    /** Request that the init daemon restart a named service. */
    public static void restart(String name) {
        SystemProperties.set("ctl.restart", name);
    }

    /**
     * Return current state of given service.
     */
    public static State getState(String service) {
        final String rawState = SystemProperties.get("init.svc." + service);
        final State state = sStates.get(rawState);
        if (state != null) {
            return state;
        } else {
            return State.STOPPED;
        }
    }

    /**
     * Check if given service is {@link State#RUNNING}.
     */
    public static boolean isRunning(String service) {
        return State.RUNNING.equals(getState(service));
    }
}
