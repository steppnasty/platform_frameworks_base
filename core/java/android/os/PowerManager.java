/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.content.Context;
import android.util.Log;

/**
 * This class gives you control of the power state of the device.  
 * 
 * <p><b>Device battery life will be significantly affected by the use of this API.</b>  Do not
 * acquire WakeLocks unless you really need them, use the minimum levels possible, and be sure
 * to release it as soon as you can.
 * 
 * <p>You can obtain an instance of this class by calling 
 * {@link android.content.Context#getSystemService(java.lang.String) Context.getSystemService()}.
 * 
 * <p>The primary API you'll use is {@link #newWakeLock(int, String) newWakeLock()}.  This will
 * create a {@link PowerManager.WakeLock} object.  You can then use methods on this object to 
 * control the power state of the device.  In practice it's quite simple:
 * 
 * {@samplecode
 * PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
 * PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "My Tag");
 * wl.acquire();
 *   ..screen will stay on during this section..
 * wl.release();
 * }
 * 
 * <p>The following flags are defined, with varying effects on system power.  <i>These flags are
 * mutually exclusive - you may only specify one of them.</i>
 * <table border="2" width="85%" align="center" frame="hsides" rules="rows">
 *
 *     <thead>
 *     <tr><th>Flag Value</th> 
 *     <th>CPU</th> <th>Screen</th> <th>Keyboard</th></tr>
 *     </thead>
 *
 *     <tbody>
 *     <tr><th>{@link #PARTIAL_WAKE_LOCK}</th>
 *         <td>On*</td> <td>Off</td> <td>Off</td> 
 *     </tr>
 *     
 *     <tr><th>{@link #SCREEN_DIM_WAKE_LOCK}</th>
 *         <td>On</td> <td>Dim</td> <td>Off</td> 
 *     </tr>
 *
 *     <tr><th>{@link #SCREEN_BRIGHT_WAKE_LOCK}</th>
 *         <td>On</td> <td>Bright</td> <td>Off</td> 
 *     </tr>
 *     
 *     <tr><th>{@link #FULL_WAKE_LOCK}</th>
 *         <td>On</td> <td>Bright</td> <td>Bright</td> 
 *     </tr>
 *     </tbody>
 * </table>
 * 
 * <p>*<i>If you hold a partial wakelock, the CPU will continue to run, irrespective of any timers 
 * and even after the user presses the power button.  In all other wakelocks, the CPU will run, but
 * the user can still put the device to sleep using the power button.</i>
 * 
 * <p>In addition, you can add two more flags, which affect behavior of the screen only.  <i>These
 * flags have no effect when combined with a {@link #PARTIAL_WAKE_LOCK}.</i>
 * <table border="2" width="85%" align="center" frame="hsides" rules="rows">
 *
 *     <thead>
 *     <tr><th>Flag Value</th> <th>Description</th></tr>
 *     </thead>
 *
 *     <tbody>
 *     <tr><th>{@link #ACQUIRE_CAUSES_WAKEUP}</th>
 *         <td>Normal wake locks don't actually turn on the illumination.  Instead, they cause
 *         the illumination to remain on once it turns on (e.g. from user activity).  This flag
 *         will force the screen and/or keyboard to turn on immediately, when the WakeLock is
 *         acquired.  A typical use would be for notifications which are important for the user to
 *         see immediately.</td> 
 *     </tr>
 *     
 *     <tr><th>{@link #ON_AFTER_RELEASE}</th>
 *         <td>If this flag is set, the user activity timer will be reset when the WakeLock is
 *         released, causing the illumination to remain on a bit longer.  This can be used to 
 *         reduce flicker if you are cycling between wake lock conditions.</td> 
 *     </tr>
 *     </tbody>
 * </table>
 * 
 * Any application using a WakeLock must request the {@code android.permission.WAKE_LOCK}
 * permission in an {@code &lt;uses-permission&gt;} element of the application's manifest.
 */
public final class PowerManager
{
    private static final String TAG = "PowerManager";
    
    /**
     * These internal values define the underlying power elements that we might
     * want to control individually.  Eventually we'd like to expose them.
     */
    private static final int WAKE_BIT_CPU_STRONG = 1;
    private static final int WAKE_BIT_CPU_WEAK = 2;
    private static final int WAKE_BIT_SCREEN_DIM = 4;
    private static final int WAKE_BIT_SCREEN_BRIGHT = 8;
    private static final int WAKE_BIT_KEYBOARD_BRIGHT = 16;
    private static final int WAKE_BIT_PROXIMITY_SCREEN_OFF = 32;
    
    private static final int LOCK_MASK = WAKE_BIT_CPU_STRONG
                                        | WAKE_BIT_CPU_WEAK
                                        | WAKE_BIT_SCREEN_DIM
                                        | WAKE_BIT_SCREEN_BRIGHT
                                        | WAKE_BIT_KEYBOARD_BRIGHT
                                        | WAKE_BIT_PROXIMITY_SCREEN_OFF;

    /**
     * Mask for the wake lock level component of a combined wake lock level and flags integer.
     *
     * @hide
     */
    public static final int WAKE_LOCK_LEVEL_MASK = 0x0000ffff;

    /**
     * Wake lock that ensures that the CPU is running.  The screen might
     * not be on.
     */
    public static final int PARTIAL_WAKE_LOCK = WAKE_BIT_CPU_STRONG;

    /**
     * Wake lock that ensures that the screen and keyboard are on at
     * full brightness.
     *
     * <p class="note">Most applications should strongly consider using
     * {@link android.view.WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON}.
     * This window flag will be correctly managed by the platform
     * as the user moves between applications and doesn't require a special permission.</p>
     */
    public static final int FULL_WAKE_LOCK = WAKE_BIT_CPU_WEAK | WAKE_BIT_SCREEN_BRIGHT 
                                            | WAKE_BIT_KEYBOARD_BRIGHT;

    /**
     * @deprecated Most applications should use
     * {@link android.view.WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON} instead
     * of this type of wake lock, as it will be correctly managed by the platform
     * as the user moves between applications and doesn't require a special permission.
     *
     * Wake lock that ensures that the screen is on at full brightness;
     * the keyboard backlight will be allowed to go off.
     */
    @Deprecated
    public static final int SCREEN_BRIGHT_WAKE_LOCK = WAKE_BIT_CPU_WEAK | WAKE_BIT_SCREEN_BRIGHT;

    /**
     * Wake lock that ensures that the screen is on (but may be dimmed);
     * the keyboard backlight will be allowed to go off.
     */
    public static final int SCREEN_DIM_WAKE_LOCK = WAKE_BIT_CPU_WEAK | WAKE_BIT_SCREEN_DIM;

    /**
     * Wake lock that turns the screen off when the proximity sensor activates.
     * Since not all devices have proximity sensors, use
     * {@link #getSupportedWakeLockFlags() getSupportedWakeLockFlags()} to determine if
     * this wake lock mode is supported.
     *
     * {@hide}
     */
    public static final int PROXIMITY_SCREEN_OFF_WAKE_LOCK = WAKE_BIT_PROXIMITY_SCREEN_OFF;

    /**
     * Flag for {@link WakeLock#release release(int)} to defer releasing a
     * {@link #WAKE_BIT_PROXIMITY_SCREEN_OFF} wakelock until the proximity sensor returns
     * a negative value.
     *
     * {@hide}
     */
    public static final int WAIT_FOR_PROXIMITY_NEGATIVE = 1;

    /**
     * Brightness value for fully on.
     * @hide
     */
    public static final int BRIGHTNESS_ON = 255;

    /**
     * Brightness value for fully off.
     * @hide
     */
    public static final int BRIGHTNESS_OFF = 0;

    // Note: Be sure to update android.os.BatteryStats and PowerManager.h
    // if adding or modifying user activity event constants.

    /**
     * User activity event type: Unspecified event type.
     * @hide
     */
    public static final int USER_ACTIVITY_EVENT_OTHER = 0;

    /**
     * User activity event type: Button or key pressed or released.
     * @hide
     */
    public static final int USER_ACTIVITY_EVENT_BUTTON = 1;

    /**
     * User activity event type: Touch down, move or up.
     * @hide
     */
    public static final int USER_ACTIVITY_EVENT_TOUCH = 2;

    /**
     * User activity flag: Do not restart the user activity timeout or brighten
     * the display in response to user activity if it is already dimmed.
     * @hide
     */
    public static final int USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS = 1 << 0;

    /**
     * Go to sleep reason code: Going to sleep due by user request.
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_USER = 0;

    /**
     * Go to sleep reason code: Going to sleep due by request of the
     * device administration policy.
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_DEVICE_ADMIN = 1;

    /**
     * Go to sleep reason code: Going to sleep due to a screen timeout.
     * @hide
     */
    public static final int GO_TO_SLEEP_REASON_TIMEOUT = 2;

    /**
     * Normally wake locks don't actually wake the device, they just cause
     * it to remain on once it's already on.  Think of the video player
     * app as the normal behavior.  Notifications that pop up and want
     * the device to be on are the exception; use this flag to be like them.
     * <p> 
     * Does not work with PARTIAL_WAKE_LOCKs.
     */
    public static final int ACQUIRE_CAUSES_WAKEUP = 0x10000000;

    /**
     * When this wake lock is released, poke the user activity timer
     * so the screen stays on for a little longer.
     * <p>
     * Will not turn the screen on if it is not already on.  See {@link #ACQUIRE_CAUSES_WAKEUP}
     * if you want that.
     * <p>
     * Does not work with PARTIAL_WAKE_LOCKs.
     */
    public static final int ON_AFTER_RELEASE = 0x20000000;

    final Context mContext;
    final IPowerManager mService;
    final Handler mHandler;

    /**
     * {@hide}
     */
    public PowerManager(Context context, IPowerManager service, Handler handler) {
        mContext = context;
        mService = service;
        mHandler = handler;
    }

    /**
     * Gets the minimum supported screen brightness setting.
     * The screen may be allowed to become dimmer than this value but
     * this is the minimum value that can be set by the user.
     * @hide
     */
    public int getMinimumScreenBrightnessSetting() {
        return mContext.getResources().getInteger(
                com.android.internal.R.integer.config_screenBrightnessSettingMinimum);
    }

    /**
     * Gets the maximum supported screen brightness setting.
     * The screen may be allowed to become dimmer than this value but
     * this is the maximum value that can be set by the user.
     * @hide
     */
    public int getMaximumScreenBrightnessSetting() {
        return mContext.getResources().getInteger(
                com.android.internal.R.integer.config_screenBrightnessSettingMaximum);
    }

    /**
     * Gets the default screen brightness setting.
     * @hide
     */
    public int getDefaultScreenBrightnessSetting() {
        return mContext.getResources().getInteger(
                com.android.internal.R.integer.config_screenBrightnessSettingDefault);
    }

    /**
     * Returns true if the screen auto-brightness adjustment setting should
     * be available in the UI.  This setting is experimental and disabled by default.
     * @hide
     */
    public static boolean useScreenAutoBrightnessAdjustmentFeature() {
        return SystemProperties.getBoolean("persist.power.useautobrightadj", false);
    }

    /**
     * Class lets you say that you need to have the device on.
     * <p>
     * Call release when you are done and don't need the lock anymore.
     * <p>
     * Any application using a WakeLock must request the {@code android.permission.WAKE_LOCK}
     * permission in an {@code &lt;uses-permission&gt;} element of the application's manifest.
     */
    public class WakeLock
    {
        static final int RELEASE_WAKE_LOCK = 1;

        Runnable mReleaser = new Runnable() {
            public void run() {
                release();
            }
        };
	
        int mFlags;
        String mTag;
        IBinder mToken;
        int mCount = 0;
        boolean mRefCounted = true;
        boolean mHeld = false;
        WorkSource mWorkSource;

        WakeLock(int flags, String tag)
        {
            switch (flags & LOCK_MASK) {
            case PARTIAL_WAKE_LOCK:
            case SCREEN_DIM_WAKE_LOCK:
            case SCREEN_BRIGHT_WAKE_LOCK:
            case FULL_WAKE_LOCK:
            case PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                break;
            default:
                throw new IllegalArgumentException();
            }

            mFlags = flags;
            mTag = tag;
            mToken = new Binder();
        }

        /**
         * Sets whether this WakeLock is ref counted.
         *
         * <p>Wake locks are reference counted by default.
         *
         * @param value true for ref counted, false for not ref counted.
         */
        public void setReferenceCounted(boolean value)
        {
            mRefCounted = value;
        }

        /**
         * Makes sure the device is on at the level you asked when you created
         * the wake lock.
         */
        public void acquire()
        {
            synchronized (mToken) {
                acquireLocked();
            }
        }

        /**
         * Makes sure the device is on at the level you asked when you created
         * the wake lock. The lock will be released after the given timeout.
         * 
         * @param timeout Release the lock after the give timeout in milliseconds.
         */
        public void acquire(long timeout) {
            synchronized (mToken) {
                acquireLocked();
                mHandler.postDelayed(mReleaser, timeout);
            }
        }
        
        private void acquireLocked() {
            if (!mRefCounted || mCount++ == 0) {
                // Do this even if the wake lock is already thought to be held (mHeld == true)
                // because non-reference counted wake locks are not always properly released.
                // For example, the keyguard's wake lock might be forcibly released by the
                // power manager without the keyguard knowing.  A subsequent call to acquire
                // should immediately acquire the wake lock once again despite never having
                // been explicitly released by the keyguard.
                mHandler.removeCallbacks(mReleaser);
                try {
                    mService.acquireWakeLock(mToken, mFlags, mTag, mWorkSource);
                } catch (RemoteException e) {
                }
                mHeld = true;
            }
        }

        /**
         * Release your claim to the CPU or screen being on.
         *
         * <p>
         * It may turn off shortly after you release it, or it may not if there
         * are other wake locks held.
         */
        public void release() {
            release(0);
        }

        /**
         * Release your claim to the CPU or screen being on.
         * @param flags Combination of flag values to modify the release behavior.
         *              Currently only {@link #WAIT_FOR_PROXIMITY_NEGATIVE} is supported.
         *
         * <p>
         * It may turn off shortly after you release it, or it may not if there
         * are other wake locks held.
         *
         * {@hide}
         */
        public void release(int flags) {
            synchronized (mToken) {
                if (!mRefCounted || --mCount == 0) {
                    mHandler.removeCallbacks(mReleaser);
                    try {
                        mService.releaseWakeLock(mToken, flags);
                    } catch (RemoteException e) {
                    }
                    mHeld = false;
                }
                if (mCount < 0) {
                    throw new RuntimeException("WakeLock under-locked " + mTag);
                }
            }
        }

        public boolean isHeld()
        {
            synchronized (mToken) {
                return mHeld;
            }
        }

        public void setWorkSource(WorkSource ws) {
            synchronized (mToken) {
                if (ws != null && ws.size() == 0) {
                    ws = null;
                }
                boolean changed = true;
                if (ws == null) {
                    mWorkSource = null;
                } else if (mWorkSource == null) {
                    changed = mWorkSource != null;
                    mWorkSource = new WorkSource(ws);
                } else {
                    changed = mWorkSource.diff(ws);
                    if (changed) {
                        mWorkSource.set(ws);
                    }
                }
                if (changed && mHeld) {
                    try {
                        mService.updateWakeLockWorkSource(mToken, mWorkSource);
                    } catch (RemoteException e) {
                    }
                }
            }
        }

        public String toString() {
            synchronized (mToken) {
                return "WakeLock{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " held=" + mHeld + ", refCount=" + mCount + "}";
            }
        }

        @Override
        protected void finalize() throws Throwable
        {
            synchronized (mToken) {
                if (mHeld) {
                    Log.wtf(TAG, "WakeLock finalized while still held: " + mTag);
                    try {
                        mService.releaseWakeLock(mToken, 0);
                    } catch (RemoteException e) {
                    }
                }
            }
        }
    }

    /**
     * Get a wake lock at the level of the flags parameter.  Call
     * {@link WakeLock#acquire() acquire()} on the object to acquire the
     * wake lock, and {@link WakeLock#release release()} when you are done.
     *
     * {@samplecode
     *PowerManager pm = (PowerManager)mContext.getSystemService(
     *                                          Context.POWER_SERVICE);
     *PowerManager.WakeLock wl = pm.newWakeLock(
     *                                      PowerManager.SCREEN_DIM_WAKE_LOCK
     *                                      | PowerManager.ON_AFTER_RELEASE,
     *                                      TAG);
     *wl.acquire();
     * // ...
     *wl.release();
     * }
     *
     * <p class="note">If using this to keep the screen on, you should strongly consider using
     * {@link android.view.WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON} instead.
     * This window flag will be correctly managed by the platform
     * as the user moves between applications and doesn't require a special permission.</p>
     *
     * @param flags Combination of flag values defining the requested behavior of the WakeLock.
     * @param tag Your class name (or other tag) for debugging purposes.
     *
     * @see WakeLock#acquire()
     * @see WakeLock#release()
     */
    public WakeLock newWakeLock(int flags, String tag)
    {
        if (tag == null) {
            throw new NullPointerException("tag is null in PowerManager.newWakeLock");
        }
        return new WakeLock(flags, tag);
    }

    /** @hide */
    public static void validateWakeLockParameters(int levelAndFlags, String tag) {
        switch (levelAndFlags & WAKE_LOCK_LEVEL_MASK) {
            case PARTIAL_WAKE_LOCK:
            case SCREEN_DIM_WAKE_LOCK:
            case SCREEN_BRIGHT_WAKE_LOCK:
            case FULL_WAKE_LOCK:
            case PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                break;
            default:
                throw new IllegalArgumentException("Must specify a valid wake lock level.");
        }
        if (tag == null) {
            throw new IllegalArgumentException("The tag must not be null.");
        }
    }

    /**
     * Notifies the power manager that user activity happened.
     * <p>
     * Resets the auto-off timer and brightens the screen if the device
     * is not asleep.  This is what happens normally when a key or the touch
     * screen is pressed or when some other user activity occurs.
     * This method does not wake up the device if it has been put to sleep.
     * </p><p>
     * Requires the {@link android.Manifest.permission#DEVICE_POWER} permission.
     * </p>
     *
     * @param when The time of the user activity, in the {@link SystemClock#uptimeMillis()}
     * time base.  This timestamp is used to correctly order the user activity request with
     * other power management functions.  It should be set
     * to the timestamp of the input event that caused the user activity.
     * @param noChangeLights If true, does not cause the keyboard backlight to turn on
     * because of this event.  This is set when the power key is pressed.
     * We want the device to stay on while the button is down, but we're about
     * to turn off the screen so we don't want the keyboard backlight to turn on again.
     * Otherwise the lights flash on and then off and it looks weird.
     *
     * @see #wakeUp
     * @see #goToSleep
     */
    public void userActivity(long when, boolean noChangeLights) {
        try {
            mService.userActivity(when, USER_ACTIVITY_EVENT_OTHER,
                    noChangeLights ? USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS : 0);
        } catch (RemoteException e) {
        }
    }

   /**
     * Force the device to go to sleep. Overrides all the wake locks that are
     * held.
     * 
     * @param time is used to order this correctly with the wake lock calls. 
     *          The time  should be in the {@link SystemClock#uptimeMillis 
     *          SystemClock.uptimeMillis()} time base.
     */
    public void goToSleep(long time) 
    {
        try {
            mService.goToSleep(time, GO_TO_SLEEP_REASON_USER);
        } catch (RemoteException e) {
        }
    }

    /**
     * Forces the device to wake up from sleep.
     * <p>
     * If the device is currently asleep, wakes it up, otherwise does nothing.
     * This is what happens when the power key is pressed to turn on the screen.
     * </p><p>
     * Requires the {@link android.Manifest.permission#DEVICE_POWER} permission.
     * </p>
     *
     * @param time The time when the request to wake up was issued, in the
     * {@link SystemClock#uptimeMillis()} time base.  This timestamp is used to correctly
     * order the wake up request with other power management functions.  It should be set
     * to the timestamp of the input event that caused the request to wake up.
     *
     * @see #userActivity
     * @see #goToSleep
     */
    public void wakeUp(long time) {
        try {
            mService.wakeUp(time);
        } catch (RemoteException e) {
        }
    }

    /**
     * Sets the brightness of the backlights (screen, keyboard, button).
     * <p>
     * Requires the {@link android.Manifest.permission#DEVICE_POWER} permission.
     * </p>
     *
     * @param brightness The brightness value from 0 to 255.
     *
     * {@hide}
     */
    public void setBacklightBrightness(int brightness) {
        try {
            mService.setTemporaryScreenBrightnessSettingOverride(brightness);
        } catch (RemoteException e) {
        }
    }

   /**
     * Returns the set of flags for {@link #newWakeLock(int, String) newWakeLock()}
     * that are supported on the device.
     * For example, to test to see if the {@link #PROXIMITY_SCREEN_OFF_WAKE_LOCK}
     * is supported:
     *
     * {@samplecode
     * PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
     * int supportedFlags = pm.getSupportedWakeLockFlags();
     *  boolean proximitySupported = ((supportedFlags & PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
     *                                  == PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK);
     * }
     *
     * @return the set of supported WakeLock flags.
     *
     * {@hide}
     */
    public int getSupportedWakeLockFlags()
    {
        try {
            return mService.getSupportedWakeLockFlags();
        } catch (RemoteException e) {
            return 0;
        }
    }

    /**
      * Returns whether the screen is currently on. The screen could be bright
      * or dim.
      *
      * {@samplecode
      * PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
      * boolean isScreenOn = pm.isScreenOn();
      * }
      *
      * @return whether the screen is on (bright or dim).
      */
    public boolean isScreenOn()
    {
        try {
            return mService.isScreenOn();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Reboot the device.  Will not return if the reboot is
     * successful.  Requires the {@link android.Manifest.permission#REBOOT}
     * permission.
     *
     * @param reason code to pass to the kernel (e.g., "recovery") to
     *               request special boot modes, or null.
     */
    public void reboot(String reason)
    {
        try {
            mService.reboot(reason);
        } catch (RemoteException e) {
        }
    }
}
