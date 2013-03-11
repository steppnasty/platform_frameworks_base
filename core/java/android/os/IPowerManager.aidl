/* //device/java/android/android/os/IPowerManager.aidl
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

package android.os;

import android.os.WorkSource;

/** @hide */

interface IPowerManager
{
    // WARNING: The first two methods must remain the first two methods because their
    // transaction numbers must not change unless IPowerManager.cpp is also updated.
    void acquireWakeLock(IBinder lock, int flags, String tag, in WorkSource ws);
    void releaseWakeLock(IBinder lock, int flags);

    void updateWakeLockWorkSource(IBinder lock, in WorkSource ws);

    void userActivity(long time, int event, int flags);
    void goToSleep(long time, int reason);
    void wakeUp(long time);
    int getSupportedWakeLockFlags();

    void setStayOnSetting(int val);
    void setMaximumScreenOffTimeoutFromDeviceAdmin(int timeMs);

    boolean isScreenOn();
    void reboot(String reason);
    void crash(String message);

    // temporarily overrides the screen brightness settings to allow the user to
    // see the effect of a settings change without applying it immediately
    void setTemporaryScreenBrightnessSettingOverride(int brightness);
    void setTemporaryScreenAutoBrightnessAdjustmentSettingOverride(float adj);

    // sets the brightness of the backlights (screen, keyboard, button) 0-255
    void setAttentionLight(boolean on, int color);

    // custom backlight things
    int getLightSensorValue();
    int getRawLightSensorValue();
    int getLightSensorScreenBrightness();
    int getLightSensorButtonBrightness();
    int getLightSensorKeyboardBrightness();
}
