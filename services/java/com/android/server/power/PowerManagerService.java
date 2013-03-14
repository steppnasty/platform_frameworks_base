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

package com.android.server.power;

import com.android.internal.app.IBatteryStats;
import com.android.server.BatteryService;
import com.android.server.EventLogTags;
import com.android.server.am.BatteryStatsService;
import com.android.server.LightsService;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerService;
import com.android.server.display.DisplayManagerService;

import android.Manifest;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentQueryMap;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.Message;
import android.os.Power;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemService;
import android.os.WorkSource;
import android.os.SystemProperties;
import android.provider.Settings.SettingNotFoundException;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.TimeUtils;
import android.view.WindowManagerPolicy;
import static android.provider.Settings.System.DIM_SCREEN;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE;
import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
import static android.provider.Settings.System.SCREEN_OFF_TIMEOUT;
import static android.provider.Settings.System.STAY_ON_WHILE_PLUGGED_IN;
import static android.provider.Settings.System.WINDOW_ANIMATION_SCALE;
import static android.provider.Settings.System.TRANSITION_ANIMATION_SCALE;

import com.android.internal.app.ThemeUtils;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Observable;
import java.util.Observer;

import libcore.util.Objects;

public class PowerManagerService extends IPowerManager.Stub
        implements Watchdog.Monitor {

    private static final String TAG = "PowerManagerService";
    private static final String TAGF = "LightFilter";
    static final String PARTIAL_NAME = "PowerManagerService";

    private static final boolean DEBUG = false;
    private static final boolean DEBUG_SPEW = DEBUG && true;
    static final boolean DEBUG_SCREEN_ON = false;

    private static final boolean LOG_PARTIAL_WL = false;

    // Indicates whether touch-down cycles should be logged as part of the
    // LOG_POWER_SCREEN_STATE log events
    private static final boolean LOG_TOUCH_DOWNS = true;

    // Message: Sent when a user activity timeout occurs to update the power state.
    private static final int MSG_USER_ACTIVITY_TIMEOUT = 1;
    // Message: Sent when the device enters or exits a napping state.
    private static final int MSG_SANDMAN = 2;
    // Message: Sent when the screen on blocker is released.
    private static final int MSG_SCREEN_ON_BLOCKER_RELEASED = 3;
    // Message: Sent to poll whether the boot animation has terminated.
    private static final int MSG_CHECK_IF_BOOT_ANIMATION_FINISHED = 4;

    // Dirty bit: mWakeLocks changed
    private static final int DIRTY_WAKE_LOCKS = 1 << 0;
    // Dirty bit: mWakefulness changed
    private static final int DIRTY_WAKEFULNESS = 1 << 1;
    // Dirty bit: user activity was poked or may have timed out
    private static final int DIRTY_USER_ACTIVITY = 1 << 2;
    // Dirty bit: actual display power state was updated asynchronously
    private static final int DIRTY_ACTUAL_DISPLAY_POWER_STATE_UPDATED = 1 << 3;
    // Dirty bit: mBootCompleted changed
    private static final int DIRTY_BOOT_COMPLETED = 1 << 4;
    // Dirty bit: settings changed
    private static final int DIRTY_SETTINGS = 1 << 5;
    // Dirty bit: mIsPowered changed
    private static final int DIRTY_IS_POWERED = 1 << 6;
    // Dirty bit: mStayOn changed
    private static final int DIRTY_STAY_ON = 1 << 7;
    // Dirty bit: battery state changed
    private static final int DIRTY_BATTERY_STATE = 1 << 8;
    // Dirty bit: proximity state changed
    private static final int DIRTY_PROXIMITY_POSITIVE = 1 << 9;
    // Dirty bit: screen on blocker state became held or unheld
    private static final int DIRTY_SCREEN_ON_BLOCKER_RELEASED = 1 << 10;
    // Dirty bit: dock state changed
    private static final int DIRTY_DOCK_STATE = 1 << 11;

    // Wakefulness: The device is asleep and can only be awoken by a call to wakeUp().
    // The screen should be off or in the process of being turned off by the display controller.
    private static final int WAKEFULNESS_ASLEEP = 0;
    // Wakefulness: The device is fully awake.  It can be put to sleep by a call to goToSleep().
    // When the user activity timeout expires, the device may start napping or go to sleep.
    private static final int WAKEFULNESS_AWAKE = 1;
    // Wakefulness: The device is napping.  It is deciding whether to dream or go to sleep
    // but hasn't gotten around to it yet.  It can be awoken by a call to wakeUp(), which
    // ends the nap. User activity may brighten the screen but does not end the nap.
    private static final int WAKEFULNESS_NAPPING = 2;

    private static final int LOCK_MASK = PowerManager.PARTIAL_WAKE_LOCK
                                        | PowerManager.SCREEN_DIM_WAKE_LOCK
                                        | PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                        | PowerManager.FULL_WAKE_LOCK
                                        | PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK;

    // Summarizes the state of all active wakelocks.
    private static final int WAKE_LOCK_CPU = 1 << 0;
    private static final int WAKE_LOCK_SCREEN_BRIGHT = 1 << 1;
    private static final int WAKE_LOCK_SCREEN_DIM = 1 << 2;
    private static final int WAKE_LOCK_BUTTON_BRIGHT = 1 << 3;
    private static final int WAKE_LOCK_PROXIMITY_SCREEN_OFF = 1 << 4;
    private static final int WAKE_LOCK_STAY_AWAKE = 1 << 5; // only set if already awake

    // Summarizes the user activity state.
    private static final int USER_ACTIVITY_SCREEN_BRIGHT = 1 << 0;
    private static final int USER_ACTIVITY_SCREEN_DIM = 1 << 1;

    //                       time since last state:               time since last event:
    // The short keylight delay comes from secure settings; this is the default.
    private static final int SHORT_KEYLIGHT_DELAY_DEFAULT = 6000; // t+6 sec
    private static final int MEDIUM_KEYLIGHT_DELAY = 15000;       // t+15 sec
    private static final int LONG_KEYLIGHT_DELAY = 6000;        // t+6 sec
    private static final int LONG_DIM_TIME = 7000;              // t+N-5 sec

    // How long to wait to debounce light sensor changes in milliseconds
    private static final int LIGHT_SENSOR_DELAY = 4000;

    // light sensor events rate in microseconds
    private static final int LIGHT_SENSOR_RATE = 1000000;

    // For debouncing the proximity sensor in milliseconds
    private static final int PROXIMITY_SENSOR_DELAY = 1000;

    // trigger proximity if distance is less than 5 cm
    private static final float PROXIMITY_THRESHOLD = 5.0f;

    // Cached secure settings; see updateSettingsValues()
    private int mShortKeylightDelay = SHORT_KEYLIGHT_DELAY_DEFAULT;

    // Default and minimum screen off timeout in milliseconds.
    private static final int DEFAULT_SCREEN_OFF_TIMEOUT = 15 * 1000;
    private static final int MINIMUM_SCREEN_OFF_TIMEOUT = 10 * 1000;

    // flags for setPowerState
    private static final int SCREEN_ON_BIT          = 0x00000001;
    private static final int SCREEN_BRIGHT_BIT      = 0x00000002;
    private static final int BUTTON_BRIGHT_BIT      = 0x00000004;
    private static final int KEYBOARD_BRIGHT_BIT    = 0x00000008;
    private static final int BATTERY_LOW_BIT        = 0x00000010;

    // values for setPowerState

    // SCREEN_OFF == everything off
    private static final int SCREEN_OFF         = 0x00000000;

    // SCREEN_DIM == screen on, screen backlight dim
    private static final int SCREEN_DIM         = SCREEN_ON_BIT;

    // SCREEN_BRIGHT == screen on, screen backlight bright
    private static final int SCREEN_BRIGHT      = SCREEN_ON_BIT | SCREEN_BRIGHT_BIT;

    // SCREEN_BUTTON_BRIGHT == screen on, screen and button backlights bright
    private static final int SCREEN_BUTTON_BRIGHT  = SCREEN_BRIGHT | BUTTON_BRIGHT_BIT;

    // SCREEN_BUTTON_BRIGHT == screen on, screen, button and keyboard backlights bright
    private static final int ALL_BRIGHT         = SCREEN_BUTTON_BRIGHT | KEYBOARD_BRIGHT_BIT;

    // used for noChangeLights in setPowerState()
    private static final int LIGHTS_MASK        = SCREEN_BRIGHT_BIT | BUTTON_BRIGHT_BIT | KEYBOARD_BRIGHT_BIT;

    boolean mAnimateScreenLights = true;

    static final int ANIM_STEPS = 60/4;
    // Slower animation for autobrightness changes
    static final int AUTOBRIGHTNESS_ANIM_STEPS = 60;

    // These magic numbers are the initial state of the LEDs at boot.  Ideally
    // we should read them from the driver, but our current hardware returns 0
    // for the initial value.  Oops!
    static final int INITIAL_SCREEN_BRIGHTNESS = 255;
    static final int INITIAL_BUTTON_BRIGHTNESS = Power.BRIGHTNESS_OFF;
    static final int INITIAL_KEYBOARD_BRIGHTNESS = Power.BRIGHTNESS_OFF;

    // The screen dim duration, in milliseconds.
    // This is subtracted from the end of the screen off timeout so the
    // minimum screen off timeout should be longer than this.
    private static final int SCREEN_DIM_DURATION = 7 * 1000;
    private static final int BUTTON_ON_DURATION = 5 * 1000;

    // The maximum screen dim time expressed as a ratio relative to the screen
    // off timeout.  If the screen off timeout is very short then we want the
    // dim timeout to also be quite short so that most of the time is spent on.
    // Otherwise the user won't get much screen on time before dimming occurs.
    private static final float MAXIMUM_SCREEN_DIM_RATIO = 0.2f;

    // Upper bound on the battery charge percentage in order to consider turning
    // the screen on when the device starts charging wirelessly.
    // See point of use for more details.
    private static final int WIRELESS_CHARGER_TURN_ON_BATTERY_LEVEL_LIMIT = 95;

    // The name of the boot animation service in init.rc.
    private static final String BOOT_ANIMATION_SERVICE = "bootanim";

    // Poll interval in milliseconds for watching boot animation finished.
    private static final int BOOT_ANIMATION_POLL_INTERVAL = 200;

    private boolean mDoneBooting = false;
    private boolean mBootCompleted = false;
    private int mStayOnConditions = 0;
    private final int[] mBroadcastQueue = new int[] { -1, -1, -1 };
    private final int[] mBroadcastWhy = new int[3];
    private boolean mPreparingForScreenOn = false;
    private boolean mSkippedScreenOn = false;
    private boolean mInitialized = false;
    private int mPartialCount = 0;
    private int mPowerState;
    // mScreenOffReason can be WindowManagerPolicy.OFF_BECAUSE_OF_USER,
    // WindowManagerPolicy.OFF_BECAUSE_OF_TIMEOUT or WindowManagerPolicy.OFF_BECAUSE_OF_PROX_SENSOR
    private int mScreenOffReason;
    private int mUserState;
    private boolean mKeyboardVisible = false;
    private boolean mUserActivityAllowed = true;
    private int mProximityWakeLockCount = 0;
    private boolean mProximitySensorEnabled = false;
    private boolean mProximitySensorActive = false;
    private int mProximityPendingValue = -1; // -1 == nothing, 0 == inactive, 1 == active
    private long mLastProximityEventTime;
    private int mScreenOffTimeoutSetting;
    private int mMaximumScreenOffTimeout = Integer.MAX_VALUE;
    private int mKeylightDelay;
    private int mDimDelay;
    private int mScreenOffDelay;
    private int mWakeLockState;
    private long mLastEventTime = 0;
    private long mScreenOffTime;
    private volatile WindowManagerPolicy mPolicy;
    private Intent mScreenOffIntent;
    private Intent mScreenOnIntent;
    private LightsService mLightsService;
    private DisplayManagerService mDisplayManagerService;
    private Context mContext;
    private Context mUiContext;
    private Notifier mNotifier;
    private DisplayPowerController mDisplayPowerController;
    private SettingsObserver mSettingsObserver;
    private LightsService.Light mLcdLight;
    private LightsService.Light mButtonLight;
    private LightsService.Light mKeyboardLight;
    private LightsService.Light mAttentionLight;
    private LightsService.Light mButtonsLight;

    private final Object mLock = new Object();

    // A bitfield that indicates what parts of the power state have
    // changed and need to be recalculated.
    private int mDirty;

    // Indicates whether the device is awake or asleep or somewhere in between.
    // This is distinct from the screen power state, which is managed separately.
    private int mWakefulness;

    // True if MSG_SANDMAN has been scheduled.
    private boolean mSandmanScheduled;

    // Table of all suspend blockers.
    // There should only be a few of these.
    private final ArrayList<SuspendBlocker> mSuspendBlockers = new ArrayList<SuspendBlocker>();

    // Table of all wake locks acquired by applications.
    private final ArrayList<WakeLock> mWakeLocks = new ArrayList<WakeLock>();

    // A bitfield that summarizes the state of all active wakelocks.
    private int mWakeLockSummary;

    // If true, instructs the display controller to wait for the proximity sensor to
    // go negative before turning the screen on.
    private boolean mRequestWaitForNegativeProximity;

    // Timestamp of the last time the device was awoken or put to sleep.
    private long mLastWakeTime;
    private long mLastSleepTime;

    // True if we need to send a wake up or go to sleep finished notification
    // when the display is ready.
    private boolean mSendWakeUpFinishedNotificationWhenReady;
    private boolean mSendGoToSleepFinishedNotificationWhenReady;

    // Timestamp of the last call to user activity.
    private long mLastUserActivityTime;
    private long mLastUserActivityTimeNoChangeLights;

    // A bitfield that summarizes the effect of the user activity timer.
    // A zero value indicates that the user activity timer has expired.
    private int mUserActivitySummary;

    // The desired display power state.  The actual state may lag behind the
    // requested because it is updated asynchronously by the display power controller.
    private final DisplayPowerRequest mDisplayPowerRequest = new DisplayPowerRequest();

    // The time the screen was last turned off, in elapsedRealtime() timebase.
    private long mLastScreenOffEventElapsedRealTime;

    // True if the display power state has been fully applied, which means the display
    // is actually on or actually off or whatever was requested.
    private boolean mDisplayReady;

    // True if holding a wake-lock to block suspend of the CPU.
    private boolean mHoldingWakeLockSuspendBlocker;

    // The suspend blocker used to keep the CPU alive when wake locks have been acquired.
    private final SuspendBlocker mWakeLockSuspendBlocker;

    // The screen on blocker used to keep the screen from turning on while the lock
    // screen is coming up.
    private final ScreenOnBlockerImpl mScreenOnBlocker;

    // The display blanker used to turn the screen on or off.
    private final DisplayBlankerImpl mDisplayBlanker;

    // True if systemReady() has been called.
    private boolean mSystemReady;

    // True if the device is plugged into a power source.
    private boolean mIsPowered;

    // The current plug type, such as BatteryManager.BATTERY_PLUGGED_WIRELESS.
    private int mPlugType;

    // The current battery level percentage.
    private int mBatteryLevel;

    // The current dock state.
    private int mDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;

    // True if the device should wake up when plugged or unplugged.
    private boolean mWakeUpWhenPluggedOrUnpluggedConfig;

    private HandlerThread mHandlerThread;
    private HandlerThread mScreenOffThread;
    private Handler mScreenOffHandler;
    private Handler mHandler;
    private boolean mStillNeedSleepNotification;
    private IActivityManager mActivityService;
    private IBatteryStats mBatteryStats;
    private BatteryService mBatteryService;
    private SensorManager mSensorManager;
    private Sensor mProximitySensor;
    private Sensor mLightSensor;
    private boolean mLightSensorEnabled;
    private float mLightSensorValue = -1;
    private boolean mProxIgnoredBecauseScreenTurnedOff = false;
    private int mHighestLightSensorValue = -1;
    private boolean mLightSensorPendingDecrease = false;
    private boolean mLightSensorPendingIncrease = false;
    private float mLightSensorPendingValue = -1;
    private int mLightSensorScreenBrightness = -1;
    private int mLightSensorButtonBrightness = -1;
    private int mLightSensorKeyboardBrightness = -1;
    private boolean mDimScreen = true;
    private boolean mIsDocked = false;
    private long mNextTimeout;
    private volatile int mPokey = 0;
    private volatile boolean mPokeAwakeOnSet = false;
    private volatile boolean mInitComplete = false;
    // mLastScreenOnTime is the time the screen was last turned on
    private long mLastScreenOnTime;
    private boolean mPreventScreenOn;
    private int mScreenBrightnessOverride = -1;
    private int mButtonBrightnessOverride = -1;
    private int mScreenBrightnessDim;
    private boolean mUseSoftwareAutoBrightness;
    private boolean mAutoBrightessEnabled = true;
    private int[] mAutoBrightnessLevels;
    private int[] mLcdBacklightValues;
    private int[] mButtonBacklightValues;
    private int[] mKeyboardBacklightValues;
    private int mLightSensorWarmupTime;
    boolean mUnplugTurnsOnScreen;
    private int mWarningSpewThrottleCount;
    private long mWarningSpewThrottleTime;
    private int mAnimationSetting = ANIM_SETTING_OFF;

    // When using software auto-brightness, determines whether (true) button
    // and keyboard backlights should also be under automatic brightness
    // control (i.e., for dimmable backlights), or (false) if they should use
    // hard-coded brightness settings that timeout-to-off in subsequent screen
    // power states.
    private boolean mAutoBrightnessButtonKeyboard;

    // Must match with the ISurfaceComposer constants in C++.
    private static final int ANIM_SETTING_ON = 0x01;
    private static final int ANIM_SETTING_OFF = 0x10;

    // Custom light housekeeping
    private long mLightSettingsTag = -1;

    // Light sensor levels / values
    private boolean mLightDecrease;
    private float mLightHysteresis;
    private boolean mCustomLightEnabled;
    private int[] mCustomLightLevels;
    private int[] mCustomLcdValues;
    private int[] mCustomButtonValues;
    private int[] mCustomKeyboardValues;
    private int mLastLcdValue;
    private int mLastButtonValue;
    private int mLastKeyboardValue;
    private int mScreenDim = Power.BRIGHTNESS_DIM;
    private boolean mAlwaysOnAndDimmed;

    // Light sensor filter, times in milliseconds
    private boolean mLightFilterEnabled;
    private boolean mLightFilterRunning;
    private int mLightFilterSample = -1;
    private int[] mLightFilterSamples;
    private int mLightFilterIndex;
    private int mLightFilterSampleCounter;
    private int mLightFilterSum;
    private int mLightFilterEqualCounter;
    private int mLightFilterWindow;
    private int mLightFilterInterval;
    private int mLightFilterReset;

    // Used when logging number and duration of touch-down cycles
    private long mTotalTouchDownTime;
    private long mLastTouchDown;
    private int mTouchCycles;

    // could be either static or controllable at runtime
    private static final boolean mSpew = false;
    private static final boolean mDebugProximitySensor = (false || mSpew);
    private static final boolean mDebugLightSensor = (false || mSpew);

    // The maximum allowable screen off timeout according to the device
    // administration policy.  Overrides other settings.
    private int mMaximumScreenOffTimeoutFromDeviceAdmin = Integer.MAX_VALUE;

    // The stay on while plugged in setting.
    // A bitfield of battery conditions under which to make the screen stay on.
    private int mStayOnWhilePluggedInSetting;

    // True if the device should stay on.
    private boolean mStayOn;

    // True if the proximity sensor reads a positive result.
    private boolean mProximityPositive;

    // Screen brightness setting limits.
    private int mScreenBrightnessSettingMinimum;
    private int mScreenBrightnessSettingMaximum;
    private int mScreenBrightnessSettingDefault;

    // The screen brightness setting, from 0 to 255.
    // Use -1 if no value has been set.
    private int mScreenBrightnessSetting;

    // The screen auto-brightness adjustment setting, from -1 to 1.
    // Use 0 if there is no adjustment.
    private float mScreenAutoBrightnessAdjustmentSetting;

    // The screen brightness mode.
    // One of the Settings.System.SCREEN_BRIGHTNESS_MODE_* constants.
    private int mScreenBrightnessModeSetting;

    // The screen brightness setting override from the window manager
    // to allow the current foreground activity to override the brightness.
    // Use -1 to disable.
    private int mScreenBrightnessOverrideFromWindowManager = -1;

    // The user activity timeout override from the window manager
    // to allow the current foreground activity to override the user activity timeout.
    // Use -1 to disable.
    private long mUserActivityTimeoutOverrideFromWindowManager = -1;

    // The screen brightness setting override from the settings application
    // to temporarily adjust the brightness until next updated,
    // Use -1 to disable.
    private int mTemporaryScreenBrightnessSettingOverride = -1;

    // The screen brightness adjustment setting override from the settings
    // application to temporarily adjust the auto-brightness adjustment factor
    // until next updated, in the range -1..1.
    // Use NaN to disable.
    private float mTemporaryScreenAutoBrightnessAdjustmentSettingOverride = Float.NaN;

    // Time when we last logged a warning about calling userActivity() without permission.
    private long mLastWarningAboutUserActivityPermission = Long.MIN_VALUE;

    private native void nativeInit();
    private static native void nativeShutdown();
    private static native void nativeReboot(String reason) throws IOException;
    private native void nativeSetPowerState(boolean screenOn, boolean screenBright);
    private static native void nativeAcquireSuspendBlocker(String name);
    private static native void nativeReleaseSuspendBlocker(String name);
    private static native void nativeSetInteractive(boolean enable);
    private static native void nativeSetAutoSuspend(boolean enable);

    public PowerManagerService() {
        synchronized (mLock) {
            mWakeLockSuspendBlocker = createSuspendBlockerLocked("PowerManagerService");
            mWakeLockSuspendBlocker.acquire();
            mScreenOnBlocker = new ScreenOnBlockerImpl();
            mDisplayBlanker = new DisplayBlankerImpl();
            mHoldingWakeLockSuspendBlocker = true;
            mWakefulness = WAKEFULNESS_AWAKE;
        }

        nativeInit();
        nativeSetPowerState(true, true);
    }

    /**
     * Initialize the power manager.
     * Must be called before any other functions within the power manager are called.
     */
    public void init(Context context, LightsService ls,
            ActivityManagerService am, BatteryService bs, IBatteryStats bss,
            DisplayManagerService dm) {
        mContext = context;
        mLightsService = ls;
        mBatteryService = bs;
        mBatteryStats = bss;
        mDisplayManagerService = dm;
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new PowerManagerHandler(mHandlerThread.getLooper());

        Watchdog.getInstance().addMonitor(this);

        // Forcibly turn the screen on at boot so that it is in a known power state.
        // We do this in init() rather than in the constructor because setting the
        // screen state requires a call into surface flinger which then needs to call back
        // into the activity manager to check permissions.  Unfortunately the
        // activity manager is not running when the constructor is called, so we
        // have to defer setting the screen state until this point.
        mDisplayBlanker.unblankAllDisplays();
    }

    public void setPolicy(WindowManagerPolicy policy) {
        synchronized (mLock) {
            mPolicy = policy;
        }
    }

    public void systemReady() {
        synchronized (mLock) {
            mSystemReady = true;

            PowerManager pm = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
            mScreenBrightnessSettingMinimum = pm.getMinimumScreenBrightnessSetting();
            mScreenBrightnessSettingMaximum = pm.getMaximumScreenBrightnessSetting();
            mScreenBrightnessSettingDefault = pm.getDefaultScreenBrightnessSetting();

            // The notifier runs on the system server's main looper so as not to interfere
            // with the animations and other critical functions of the power manager.
            mNotifier = new Notifier(Looper.getMainLooper(), mContext, mBatteryStats,
                    createSuspendBlockerLocked("PowerManagerService.Broadcasts"),
                    mScreenOnBlocker, mPolicy);

            // The display power controller runs on the power manager service's
            // own handler thread.
            mDisplayPowerController = new DisplayPowerController(mHandler.getLooper(),
                    mContext, mNotifier, mLightsService, mDisplayBlanker,
                    mDisplayPowerControllerCallbacks, mHandler);

            mSettingsObserver = new SettingsObserver(mHandler);
            mAttentionLight = mLightsService.getLight(LightsService.LIGHT_ID_ATTENTION);
            mButtonsLight = mLightsService.getLight(LightsService.LIGHT_ID_BUTTONS);

            // Register for broadcasts from other components of the system.
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            mContext.registerReceiver(new BatteryReceiver(), filter, null, mHandler);

            filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BOOT_COMPLETED);
            mContext.registerReceiver(new BootCompletedReceiver(), filter, null, mHandler);

            filter = new IntentFilter();
            filter.addAction(Intent.ACTION_DOCK_EVENT);
            mContext.registerReceiver(new DockReceiver(), filter, null, mHandler);

            // Register for settings changes.
            final ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_OFF_TIMEOUT),
                    false, mSettingsObserver);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STAY_ON_WHILE_PLUGGED_IN),
                    false, mSettingsObserver);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS),
                    false, mSettingsObserver);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS_MODE),
                    false, mSettingsObserver);

            // Go.
            readConfigurationLocked();
            updateSettingsLocked();
            mDirty |= DIRTY_BATTERY_STATE;
            updatePowerStateLocked();
        }
    }

    private void readConfigurationLocked() {
        final Resources resources = mContext.getResources();

        mWakeUpWhenPluggedOrUnpluggedConfig = resources.getBoolean(
                com.android.internal.R.bool.config_unplugTurnsOnScreen);
    }

    private void updateSettingsLocked() {
        final ContentResolver resolver = mContext.getContentResolver();

        mScreenOffTimeoutSetting = Settings.System.getInt(resolver,
                Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_SCREEN_OFF_TIMEOUT);
        mStayOnWhilePluggedInSetting = Settings.System.getInt(resolver,
                Settings.System.STAY_ON_WHILE_PLUGGED_IN, BatteryManager.BATTERY_PLUGGED_AC);

        final int oldScreenBrightnessSetting = mScreenBrightnessSetting;
        mScreenBrightnessSetting = Settings.System.getInt(resolver,
                Settings.System.SCREEN_BRIGHTNESS, mScreenBrightnessSettingDefault);
        if (oldScreenBrightnessSetting != mScreenBrightnessSetting) {
            mTemporaryScreenBrightnessSettingOverride = -1;
        }

        final float oldScreenAutoBrightnessAdjustmentSetting =
                mScreenAutoBrightnessAdjustmentSetting;
        mScreenAutoBrightnessAdjustmentSetting = Settings.System.getFloat(resolver,
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, 0.0f);
        if (oldScreenAutoBrightnessAdjustmentSetting != mScreenAutoBrightnessAdjustmentSetting) {
            mTemporaryScreenAutoBrightnessAdjustmentSettingOverride = Float.NaN;
        }

        mScreenBrightnessModeSetting = Settings.System.getInt(resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);

        mDirty |= DIRTY_SETTINGS;
    }

    /**
     * Set the setting that determines whether the device stays on when plugged in.
     * The argument is a bit string, with each bit specifying a power source that,
     * when the device is connected to that source, causes the device to stay on.
     * See {@link android.os.BatteryManager} for the list of power sources that
     * can be specified. Current values include {@link android.os.BatteryManager#BATTERY_PLUGGED_AC}
     * and {@link android.os.BatteryManager#BATTERY_PLUGGED_USB}
     * @param val an {@code int} containing the bits that specify which power sources
     * should cause the device to stay on.
     */
    public void setStayOnSetting(int val) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WRITE_SETTINGS, null);
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.STAY_ON_WHILE_PLUGGED_IN, val);
    }

    private ContentQueryMap mSettings;

    private boolean isScreenLock(int flags)
    {
        int n = flags & LOCK_MASK;
        return n == PowerManager.FULL_WAKE_LOCK
                || n == PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                || n == PowerManager.SCREEN_DIM_WAKE_LOCK
                || n == PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK;
    }

    void enforceWakeSourcePermission(int uid, int pid) {
        if (uid == Process.myUid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_DEVICE_STATS,
                pid, uid, null);
    }

    private void handleSettingsChangedLocked() {
        updateSettingsLocked();
        updatePowerStateLocked();
    }

    @Override // Binder call
    public void acquireWakeLock(IBinder lock, int flags, String tag, WorkSource ws) {
        if (lock == null) {
            throw new IllegalArgumentException("lock must not be null");
        }
        PowerManager.validateWakeLockParameters(flags, tag);

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        if (ws != null && ws.size() != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.UPDATE_DEVICE_STATS, null);
        } else {
            ws = null;
        }

        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final long ident = Binder.clearCallingIdentity();
        try {
            acquireWakeLockInternal(lock, flags, tag, ws, uid, pid);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void acquireWakeLockInternal(IBinder lock, int flags, String tag, WorkSource ws,
            int uid, int pid) {
        synchronized (mLock) {
            if (DEBUG_SPEW) {
                Slog.d(TAG, "acquireWakeLockInternal: lock=" + Objects.hashCode(lock)
                        + ", flags=0x" + Integer.toHexString(flags)
                        + ", tag=\"" + tag + "\", ws=" + ws + ", uid=" + uid + ", pid=" + pid);
            }

            WakeLock wakeLock;
            int index = findWakeLockIndexLocked(lock);
            if (index >= 0) {
                wakeLock = mWakeLocks.get(index);
                if (!wakeLock.hasSameProperties(flags, tag, ws, uid, pid)) {
                    // Update existing wake lock.  This shouldn't happen but is harmless.
                    notifyWakeLockReleasedLocked(wakeLock);
                    wakeLock.updateProperties(flags, tag, ws, uid, pid);
                    notifyWakeLockAcquiredLocked(wakeLock);
                }
            } else {
                wakeLock = new WakeLock(lock, flags, tag, ws, uid, pid);
                try {
                    lock.linkToDeath(wakeLock, 0);
                } catch (RemoteException ex) {
                    throw new IllegalArgumentException("Wake lock is already dead.");
                }
                notifyWakeLockAcquiredLocked(wakeLock);
                mWakeLocks.add(wakeLock);
            }

            applyWakeLockFlagsOnAcquireLocked(wakeLock);
            mDirty |= DIRTY_WAKE_LOCKS;
            updatePowerStateLocked();
        }
    }

    private void applyWakeLockFlagsOnAcquireLocked(WakeLock wakeLock) {
        if ((wakeLock.mFlags & PowerManager.ACQUIRE_CAUSES_WAKEUP) != 0) {
            wakeUpNoUpdateLocked(SystemClock.uptimeMillis());
        }
    }

    @Override // Binder call
    public void releaseWakeLock(IBinder lock, int flags) {
        if (lock == null) {
            throw new IllegalArgumentException("lock must not be null");
        }

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            releaseWakeLockInternal(lock, flags);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void releaseWakeLockInternal(IBinder lock, int flags) {
        synchronized (mLock) {
            if (DEBUG_SPEW) {
                Slog.d(TAG, "releaseWakeLockInternal: lock=" + Objects.hashCode(lock)
                        + ", flags=0x" + Integer.toHexString(flags));
            }

            int index = findWakeLockIndexLocked(lock);
            if (index < 0) {
                return;
            }

            WakeLock wakeLock = mWakeLocks.get(index);
            mWakeLocks.remove(index);
            notifyWakeLockReleasedLocked(wakeLock);
            wakeLock.mLock.unlinkToDeath(wakeLock, 0);

            if ((flags & PowerManager.WAIT_FOR_PROXIMITY_NEGATIVE) != 0) {
                mRequestWaitForNegativeProximity = true;
            }

            applyWakeLockFlagsOnReleaseLocked(wakeLock);
            mDirty |= DIRTY_WAKE_LOCKS;
            updatePowerStateLocked();
        }
    }

    private void handleWakeLockDeath(WakeLock wakeLock) {
        synchronized (mLock) {
            if (DEBUG_SPEW) {
                Slog.d(TAG, "handleWakeLockDeath: lock=" + Objects.hashCode(wakeLock.mLock));
            }

            int index = mWakeLocks.indexOf(wakeLock);
            if (index < 0) {
                return;
            }

            mWakeLocks.remove(index);
            notifyWakeLockReleasedLocked(wakeLock);

            applyWakeLockFlagsOnReleaseLocked(wakeLock);
            mDirty |= DIRTY_WAKE_LOCKS;
            updatePowerStateLocked();
        }
    }

    private void applyWakeLockFlagsOnReleaseLocked(WakeLock wakeLock) {
        if ((wakeLock.mFlags & PowerManager.ON_AFTER_RELEASE) != 0) {
            userActivityNoUpdateLocked(SystemClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_OTHER,
                    PowerManager.USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS,
                    wakeLock.mOwnerUid);
        }
    }

    @Override // Binder call
    public void updateWakeLockWorkSource(IBinder lock, WorkSource ws) {
        if (lock == null) {
            throw new IllegalArgumentException("lock must not be null");
        }

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        if (ws != null && ws.size() != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.UPDATE_DEVICE_STATS, null);
        } else {
            ws = null;
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            updateWakeLockWorkSourceInternal(lock, ws);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void updateWakeLockWorkSourceInternal(IBinder lock, WorkSource ws) {
        synchronized (mLock) {
            int index = findWakeLockIndexLocked(lock);
            if (index < 0) {
                throw new IllegalArgumentException("Wake lock not active");
            }

            WakeLock wakeLock = mWakeLocks.get(index);
            if (!wakeLock.hasSameWorkSource(ws)) {
                notifyWakeLockReleasedLocked(wakeLock);
                wakeLock.updateWorkSource(ws);
                notifyWakeLockAcquiredLocked(wakeLock);
            }
        }
    }

    private int findWakeLockIndexLocked(IBinder lock) {
        final int count = mWakeLocks.size();
        for (int i = 0; i < count; i++) {
            if (mWakeLocks.get(i).mLock == lock) {
                return i;
            }
        }
        return -1;
    }

    private void notifyWakeLockReleasedLocked(WakeLock wakeLock) {
        if (mSystemReady) {
            mNotifier.onWakeLockReleased(wakeLock.mFlags, wakeLock.mTag,
                    wakeLock.mOwnerUid, wakeLock.mOwnerPid, wakeLock.mWorkSource);
        }
    }

    private void notifyWakeLockAcquiredLocked(WakeLock wakeLock) {
        if (mSystemReady) {
            mNotifier.onWakeLockAcquired(wakeLock.mFlags, wakeLock.mTag,
                    wakeLock.mOwnerUid, wakeLock.mOwnerPid, wakeLock.mWorkSource);
        }
    }

    @Override // Binder call
    public void userActivity(long eventTime, int event, int flags) {
        final long now = SystemClock.uptimeMillis();
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER)
                != PackageManager.PERMISSION_GRANTED) {
            // Once upon a time applications could call userActivity().
            // Now we require the DEVICE_POWER permission.  Log a warning and ignore the
            // request instead of throwing a SecurityException so we don't break old apps.
            synchronized (mLock) {
                if (now >= mLastWarningAboutUserActivityPermission + (5 * 60 * 1000)) {
                    mLastWarningAboutUserActivityPermission = now;
                    Slog.w(TAG, "Ignoring call to PowerManager.userActivity() because the "
                            + "caller does not have DEVICE_POWER permission.  "
                            + "Please fix your app!  "
                            + " pid=" + Binder.getCallingPid()
                            + " uid=" + Binder.getCallingUid());
                }
            }
            return;
        }

        if (eventTime > SystemClock.uptimeMillis()) {
            throw new IllegalArgumentException("event time must not be in the future");
        }

        final int uid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        try {
            userActivityInternal(eventTime, event, flags, uid);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    // Called from native code.
    private void userActivityFromNative(long eventTime, int event, int flags) {
        userActivityInternal(eventTime, event, flags, Process.SYSTEM_UID);
    }

    private void userActivityInternal(long eventTime, int event, int flags, int uid) {
        synchronized (mLock) {
            if (userActivityNoUpdateLocked(eventTime, event, flags, uid)) {
                updatePowerStateLocked();
            }
        }
    }

    private boolean userActivityNoUpdateLocked(long eventTime, int event, int flags, int uid) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "userActivityNoUpdateLocked: eventTime=" + eventTime
                    + ", event=" + event + ", flags=0x" + Integer.toHexString(flags)
                    + ", uid=" + uid);
        }

        if (eventTime < mLastSleepTime || eventTime < mLastWakeTime
                || mWakefulness == WAKEFULNESS_ASLEEP || !mBootCompleted || !mSystemReady) {
            return false;
        }

        mNotifier.onUserActivity(event, uid);

        if ((flags & PowerManager.USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS) != 0) {
            if (eventTime > mLastUserActivityTimeNoChangeLights
                    && eventTime > mLastUserActivityTime) {
                mLastUserActivityTimeNoChangeLights = eventTime;
                mDirty |= DIRTY_USER_ACTIVITY;
                return true;
            }
        } else {
            if (eventTime > mLastUserActivityTime) {
                mLastUserActivityTime = eventTime;
                mDirty |= DIRTY_USER_ACTIVITY;
                return true;
            }
        }
        return false;
    }

    private static String lockType(int type)
    {
        switch (type)
        {
            case PowerManager.FULL_WAKE_LOCK:
                return "FULL_WAKE_LOCK                ";
            case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                return "SCREEN_BRIGHT_WAKE_LOCK       ";
            case PowerManager.SCREEN_DIM_WAKE_LOCK:
                return "SCREEN_DIM_WAKE_LOCK          ";
            case PowerManager.PARTIAL_WAKE_LOCK:
                return "PARTIAL_WAKE_LOCK             ";
            case PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                return "PROXIMITY_SCREEN_OFF_WAKE_LOCK";
            default:
                return "???                           ";
        }
    }

    private static String dumpPowerState(int state) {
        return (((state & KEYBOARD_BRIGHT_BIT) != 0)
                        ? "KEYBOARD_BRIGHT_BIT " : "")
                + (((state & SCREEN_BRIGHT_BIT) != 0)
                        ? "SCREEN_BRIGHT_BIT " : "")
                + (((state & SCREEN_ON_BIT) != 0)
                        ? "SCREEN_ON_BIT " : "")
                + (((state & BUTTON_BRIGHT_BIT) != 0)
                        ? "BUTTON_BRIGHT_BIT " : "")
                + (((state & BATTERY_LOW_BIT) != 0)
                        ? "BATTERY_LOW_BIT " : "");
    }

    
    private boolean isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked() {
        return mMaximumScreenOffTimeoutFromDeviceAdmin >= 0
                && mMaximumScreenOffTimeoutFromDeviceAdmin < Integer.MAX_VALUE;
    }

    /**
     * Low-level function turn the device off immediately, without trying
     * to be clean.  Most people should use {@link ShutdownThread} for a clean shutdown.
     */
    public static void lowLevelShutdown() {
        nativeShutdown();
    }

    /**
     * Low-level function to reboot the device.
     *
     * @param reason code to pass to the kernel (e.g. "recovery"), or null.
     * @throws IOException if reboot fails for some reason (eg, lack of
     *         permission)
     */
    public static void lowLevelReboot(String reason) throws IOException {
        nativeReboot(reason);
    }

    @Override // Watchdog.Monitor implementation
    public void monitor() {
        // Grab and release lock for watchdog monitor to detect deadlocks.
        synchronized (mLock) {
        }
    }

    void logPointerUpEvent() {
        if (LOG_TOUCH_DOWNS) {
            mTotalTouchDownTime += SystemClock.elapsedRealtime() - mLastTouchDown;
            mLastTouchDown = 0;
        }
    }

    void logPointerDownEvent() {
        if (LOG_TOUCH_DOWNS) {
            // If we are not already timing a down/up sequence
            if (mLastTouchDown == 0) {
                mLastTouchDown = SystemClock.elapsedRealtime();
                mTouchCycles++;
            }
        }
    }

    private void updateNativePowerStateLocked() {
        nativeSetPowerState(
                (mPowerState & SCREEN_ON_BIT) != 0,
                (mPowerState & SCREEN_BRIGHT) == SCREEN_BRIGHT);
    }

    private boolean batteryIsLow() {
        return (!mIsPowered &&
                mBatteryService.getBatteryLevel() <= Power.LOW_BATTERY_THRESHOLD);
    }

    private boolean shouldDeferScreenOnLocked() {
        if (mPreparingForScreenOn) {
            // Currently waiting for confirmation from the policy that it
            // is okay to turn on the screen.  Don't allow the screen to go
            // on until that is done.
            if (DEBUG_SCREEN_ON) Slog.i(TAG,
                    "updateLights: delaying screen on due to mPreparingForScreenOn");
            return true;
        } else {
            // If there is a screen-on command in the notification queue, we
            // can't turn the screen on until it has been processed (and we
            // have set mPreparingForScreenOn) or it has been dropped.
            for (int i=0; i<mBroadcastQueue.length; i++) {
                if (mBroadcastQueue[i] == 1) {
                    if (DEBUG_SCREEN_ON) Slog.i(TAG,
                            "updateLights: delaying screen on due to notification queue");
                    return true;
                }
            }
        }
        return false;
    }

    private void setLightBrightness(int mask, int value) {
        int brightnessMode = (mAutoBrightessEnabled
                            ? LightsService.BRIGHTNESS_MODE_SENSOR
                            : LightsService.BRIGHTNESS_MODE_USER);
        if ((mask & SCREEN_BRIGHT_BIT) != 0) {
            if (DEBUG_SCREEN_ON) {
                RuntimeException e = new RuntimeException("here");
                e.fillInStackTrace();
                Slog.i(TAG, "Set LCD brightness: " + value, e);
            }
            mLcdLight.setBrightness(value, brightnessMode);
            mLastLcdValue = value;
        }
        if ((mask & BUTTON_BRIGHT_BIT) != 0) {
            // Use sensor-determined brightness values when the button (or keyboard)
            // light is on, since users may want to specify a custom brightness setting
            // that disables the button (or keyboard) backlight entirely in low-ambient
            // light situations.
            mButtonLight.setBrightness(mLightSensorButtonBrightness >= 0 && value > 0 ?
                                       mLightSensorButtonBrightness : value);

        }
        if ((mask & KEYBOARD_BRIGHT_BIT) != 0) {
            mKeyboardLight.setBrightness(mLightSensorKeyboardBrightness >= 0 && value > 0 ?
                                         mLightSensorKeyboardBrightness : value);
        }
    }

    private int getPreferredBrightness() {
        try {
            if (mScreenBrightnessOverride >= 0) {
                return mScreenBrightnessOverride;
            } else if (mLightSensorScreenBrightness >= 0 && mUseSoftwareAutoBrightness
                    && mAutoBrightessEnabled) {
                return mLightSensorScreenBrightness;
            }
            final int brightness = Settings.System.getInt(mContext.getContentResolver(),
                                                          SCREEN_BRIGHTNESS);
             // Don't let applications turn the screen all the way off
            return Math.max(brightness, mScreenDim);
        } catch (SettingNotFoundException snfe) {
            return Power.BRIGHTNESS_ON;
        }
    }

    private int applyButtonState(int state) {
        int brightness = -1;
        if ((state & BATTERY_LOW_BIT) != 0) {
            // do not override brightness if the battery is low
            return state;
        }
        if (mButtonBrightnessOverride >= 0) {
            brightness = mButtonBrightnessOverride;
        } else if (!mAutoBrightessEnabled) {
            // Light sensor is not on, mLightSensorButtonBrightness has not been cleared
            brightness = getPreferredBrightness();
        } else if (mLightSensorButtonBrightness >= 0 && mUseSoftwareAutoBrightness) {
            brightness = mLightSensorButtonBrightness;
        }
        if (brightness > 0) {
            return state | BUTTON_BRIGHT_BIT;
        } else if (brightness == 0) {
            return state & ~BUTTON_BRIGHT_BIT;
        } else {
            return state;
        }
    }

    private int applyKeyboardState(int state) {
        int brightness = -1;
        if ((state & BATTERY_LOW_BIT) != 0) {
            // do not override brightness if the battery is low
            return state;
        }
        if (!mKeyboardVisible) {
            brightness = 0;
        } else if (mButtonBrightnessOverride >= 0) {
            brightness = mButtonBrightnessOverride;
        } else if (!mAutoBrightessEnabled && mKeyboardVisible) {
            // Light sensor is not on, mLightSensorButtonBrightness has not been cleared
            brightness = getPreferredBrightness();
        } else if (mLightSensorKeyboardBrightness >= 0 && mUseSoftwareAutoBrightness) {
            brightness =  mLightSensorKeyboardBrightness;
        }
        if (brightness > 0) {
            return state | KEYBOARD_BRIGHT_BIT;
        } else if (brightness == 0) {
            return state & ~KEYBOARD_BRIGHT_BIT;
        } else {
            return state;
        }
    }

    private void handleScreenOnBlockerReleased() {
        synchronized (mLock) {
            mDirty |= DIRTY_SCREEN_ON_BLOCKER_RELEASED;
            updatePowerStateLocked();
        }
    }

    /**
     * Updates the display power state asynchronously.
     * When the update is finished, mDisplayReady will be set to true.  The display
     * controller posts a message to tell us when the actual display power state
     * has been updated so we come back here to double-check and finish up.
     *
     * This function recalculates the display power state each time.
     */
    private void updateDisplayPowerStateLocked(int dirty) {
        if ((dirty & (DIRTY_WAKE_LOCKS | DIRTY_USER_ACTIVITY | DIRTY_WAKEFULNESS
                | DIRTY_ACTUAL_DISPLAY_POWER_STATE_UPDATED | DIRTY_BOOT_COMPLETED
                | DIRTY_SETTINGS | DIRTY_SCREEN_ON_BLOCKER_RELEASED)) != 0) {
            int newScreenState = getDesiredScreenPowerStateLocked();
            if (newScreenState != mDisplayPowerRequest.screenState) {
                if (newScreenState == DisplayPowerRequest.SCREEN_STATE_OFF
                        && mDisplayPowerRequest.screenState
                                != DisplayPowerRequest.SCREEN_STATE_OFF) {
                    mLastScreenOffEventElapsedRealTime = SystemClock.elapsedRealtime();
                }

                mDisplayPowerRequest.screenState = newScreenState;
                nativeSetPowerState(
                        newScreenState != DisplayPowerRequest.SCREEN_STATE_OFF,
                        newScreenState == DisplayPowerRequest.SCREEN_STATE_BRIGHT);
            }

            int screenBrightness = mScreenBrightnessSettingDefault;
            float screenAutoBrightnessAdjustment = 0.0f;
            boolean autoBrightness = (mScreenBrightnessModeSetting ==
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            if (isValidBrightness(mScreenBrightnessOverrideFromWindowManager)) {
                screenBrightness = mScreenBrightnessOverrideFromWindowManager;
                autoBrightness = false;
            } else if (isValidBrightness(mTemporaryScreenBrightnessSettingOverride)) {
                screenBrightness = mTemporaryScreenBrightnessSettingOverride;
            } else if (isValidBrightness(mScreenBrightnessSetting)) {
                screenBrightness = mScreenBrightnessSetting;
            }
            if (autoBrightness) {
                screenBrightness = mScreenBrightnessSettingDefault;
                if (isValidAutoBrightnessAdjustment(
                        mTemporaryScreenAutoBrightnessAdjustmentSettingOverride)) {
                    screenAutoBrightnessAdjustment =
                            mTemporaryScreenAutoBrightnessAdjustmentSettingOverride;
                } else if (isValidAutoBrightnessAdjustment(
                        mScreenAutoBrightnessAdjustmentSetting)) {
                    screenAutoBrightnessAdjustment = mScreenAutoBrightnessAdjustmentSetting;
                }
            }
            screenBrightness = Math.max(Math.min(screenBrightness,
                    mScreenBrightnessSettingMaximum), mScreenBrightnessSettingMinimum);
            screenAutoBrightnessAdjustment = Math.max(Math.min(
                    screenAutoBrightnessAdjustment, 1.0f), -1.0f);
            mDisplayPowerRequest.screenBrightness = screenBrightness;
            mDisplayPowerRequest.screenAutoBrightnessAdjustment =
                    screenAutoBrightnessAdjustment;
            mDisplayPowerRequest.useAutoBrightness = autoBrightness;

            mDisplayPowerRequest.useProximitySensor = shouldUseProximitySensorLocked();

            mDisplayPowerRequest.blockScreenOn = mScreenOnBlocker.isHeld();

            mDisplayReady = mDisplayPowerController.requestPowerState(mDisplayPowerRequest,
                    mRequestWaitForNegativeProximity);
            mRequestWaitForNegativeProximity = false;

            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateScreenStateLocked: mDisplayReady=" + mDisplayReady
                        + ", newScreenState=" + newScreenState
                        + ", mWakefulness=" + mWakefulness
                        + ", mWakeLockSummary=0x" + Integer.toHexString(mWakeLockSummary)
                        + ", mUserActivitySummary=0x" + Integer.toHexString(mUserActivitySummary)
                        + ", mBootCompleted=" + mBootCompleted);
            }
        }
    }

    private static boolean isValidBrightness(int value) {
        return value >= 0 && value <= 255;
    }

    private static boolean isValidAutoBrightnessAdjustment(float value) {
        // Handles NaN by always returning false.
        return value >= -1.0f && value <= 1.0f;
    }

    private int getDesiredScreenPowerStateLocked() {
        if (mWakefulness == WAKEFULNESS_ASLEEP) {
            return DisplayPowerRequest.SCREEN_STATE_OFF;
        }

        if ((mWakeLockSummary & WAKE_LOCK_SCREEN_BRIGHT) != 0
                || (mUserActivitySummary & USER_ACTIVITY_SCREEN_BRIGHT) != 0
                || !mBootCompleted) {
            return DisplayPowerRequest.SCREEN_STATE_BRIGHT;
        }

        return DisplayPowerRequest.SCREEN_STATE_DIM;
    }

    private final DisplayPowerController.Callbacks mDisplayPowerControllerCallbacks =
            new DisplayPowerController.Callbacks() {
        @Override
        public void onStateChanged() {
            mDirty |= DIRTY_ACTUAL_DISPLAY_POWER_STATE_UPDATED;
            updatePowerStateLocked();
        }

        @Override
        public void onProximityPositive() {
            mProximityPositive = true;
            mDirty |= DIRTY_PROXIMITY_POSITIVE;
            updatePowerStateLocked();
        }

        @Override
        public void onProximityNegative() {
            mProximityPositive = false;
            mDirty |= DIRTY_PROXIMITY_POSITIVE;
            userActivityNoUpdateLocked(SystemClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, Process.SYSTEM_UID);
            updatePowerStateLocked();
        }
    };

    private boolean shouldUseProximitySensorLocked() {
        return (mWakeLockSummary & WAKE_LOCK_PROXIMITY_SCREEN_OFF) != 0;
    }

    /**
     * Updates the suspend blocker that keeps the CPU alive.
     *
     * This function must have no other side-effects.
     */
    private void updateSuspendBlockerLocked() {
        boolean wantCpu = isCpuNeededLocked();
        if (wantCpu != mHoldingWakeLockSuspendBlocker) {
            mHoldingWakeLockSuspendBlocker = wantCpu;
            if (wantCpu) {
                if (DEBUG) {
                    Slog.d(TAG, "updateSuspendBlockerLocked: Acquiring suspend blocker.");
                }
                mWakeLockSuspendBlocker.acquire();
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "updateSuspendBlockerLocked: Releasing suspend blocker.");
                }
                mWakeLockSuspendBlocker.release();
            }
        }
    }

    private boolean isCpuNeededLocked() {
        return !mBootCompleted
                || mWakeLockSummary != 0
                || mUserActivitySummary != 0
                || mDisplayPowerRequest.screenState != DisplayPowerRequest.SCREEN_STATE_OFF
                || !mDisplayReady;
    }

    @Override // Binder call
    public boolean isScreenOn() {
        final long ident = Binder.clearCallingIdentity();
        try {
            return isScreenOnInternal();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private boolean isScreenOnInternal() {
        synchronized (mLock) {
            return !mSystemReady
                    || mDisplayPowerRequest.screenState != DisplayPowerRequest.SCREEN_STATE_OFF;
        }
    }

    private void handleBatteryStateChangedLocked() {
        mDirty |= DIRTY_BATTERY_STATE;
        updatePowerStateLocked();
    }

    private void startWatchingForBootAnimationFinished() {
        mHandler.sendEmptyMessage(MSG_CHECK_IF_BOOT_ANIMATION_FINISHED);
    }

    private void checkIfBootAnimationFinished() {
        if (DEBUG) {
            Slog.d(TAG, "Check if boot animation finished...");
        }

        if (SystemService.isRunning(BOOT_ANIMATION_SERVICE)) {
            mHandler.sendEmptyMessageDelayed(MSG_CHECK_IF_BOOT_ANIMATION_FINISHED,
                    BOOT_ANIMATION_POLL_INTERVAL);
            return;
        }

        synchronized (mLock) {
            if (!mBootCompleted) {
                Slog.i(TAG, "Boot animation finished.");
                handleBootCompletedLocked();
            }
        }
    }

    private void handleBootCompletedLocked() {
        final long now = SystemClock.uptimeMillis();
        mBootCompleted = true;
        mDirty |= DIRTY_BOOT_COMPLETED;
        userActivityNoUpdateLocked(
                now, PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, Process.SYSTEM_UID);
        updatePowerStateLocked();
    }

    /**
     * Used by the window manager to override the screen brightness based on the
     * current foreground activity.
     *
     * This method must only be called by the window manager.
     *
     * @param brightness The overridden brightness, or -1 to disable the override.
     */
    public void setScreenBrightnessOverrideFromWindowManager(int brightness) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            setScreenBrightnessOverrideFromWindowManagerInternal(brightness);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setScreenBrightnessOverrideFromWindowManagerInternal(int brightness) {
        synchronized (mLock) {
            if (mScreenBrightnessOverrideFromWindowManager != brightness) {
                mScreenBrightnessOverrideFromWindowManager = brightness;
                mDirty |= DIRTY_SETTINGS;
                updatePowerStateLocked();
            }
        }
    }

    /**
     * Used by the window manager to override the button brightness based on the
     * current foreground activity.
     *
     * This method must only be called by the window manager.
     *
     * @param brightness The overridden brightness, or -1 to disable the override.
     */
    public void setButtonBrightnessOverrideFromWindowManager(int brightness) {
        // Do nothing.
        // Button lights are not currently supported in the new implementation.
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
    }

    /**
     * Used by the window manager to override the user activity timeout based on the
     * current foreground activity.  It can only be used to make the timeout shorter
     * than usual, not longer.
     *
     * This method must only be called by the window manager.
     *
     * @param timeoutMillis The overridden timeout, or -1 to disable the override.
     */
    public void setUserActivityTimeoutOverrideFromWindowManager(long timeoutMillis) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            setUserActivityTimeoutOverrideFromWindowManagerInternal(timeoutMillis);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setUserActivityTimeoutOverrideFromWindowManagerInternal(long timeoutMillis) {
        synchronized (mLock) {
            if (mUserActivityTimeoutOverrideFromWindowManager != timeoutMillis) {
                mUserActivityTimeoutOverrideFromWindowManager = timeoutMillis;
                mDirty |= DIRTY_SETTINGS;
                updatePowerStateLocked();
            }
        }
    }

    @Override // Binder call
    public void wakeUp(long eventTime) {
        if (eventTime > SystemClock.uptimeMillis()) {
            throw new IllegalArgumentException("event time must not be in the future");
        }

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            wakeUpInternal(eventTime);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    // Called from native code.
    private void wakeUpFromNative(long eventTime) {
        wakeUpInternal(eventTime);
    }

    private void wakeUpInternal(long eventTime) {
        synchronized (mLock) {
            if (wakeUpNoUpdateLocked(eventTime)) {
                updatePowerStateLocked();
            }
        }
    }

    private boolean wakeUpNoUpdateLocked(long eventTime) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "wakeUpNoUpdateLocked: eventTime=" + eventTime);
        }

        if (eventTime < mLastSleepTime || mWakefulness == WAKEFULNESS_AWAKE
                || !mBootCompleted || !mSystemReady) {
            return false;
        }

        switch (mWakefulness) {
            case WAKEFULNESS_ASLEEP:
                Slog.i(TAG, "Waking up from sleep...");
                sendPendingNotificationsLocked();
                mNotifier.onWakeUpStarted();
                mSendWakeUpFinishedNotificationWhenReady = true;
                break;
            case WAKEFULNESS_NAPPING:
                Slog.i(TAG, "Waking up from nap...");
                break;
        }

        mLastWakeTime = eventTime;
        mWakefulness = WAKEFULNESS_AWAKE;
        mDirty |= DIRTY_WAKEFULNESS;

        userActivityNoUpdateLocked(
                eventTime, PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, Process.SYSTEM_UID);
        return true;
    }

    @Override // Binder call
    public void goToSleep(long eventTime, int reason) {
        if (eventTime > SystemClock.uptimeMillis()) {
            throw new IllegalArgumentException("event time must not be in the future");
        }

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            goToSleepInternal(eventTime, reason);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    // Called from native code.
    private void goToSleepFromNative(long eventTime, int reason) {
        goToSleepInternal(eventTime, reason);
    }

    private void goToSleepInternal(long eventTime, int reason) {
        synchronized (mLock) {
            if (goToSleepNoUpdateLocked(eventTime, reason)) {
                updatePowerStateLocked();
            }
        }
    }

    private boolean goToSleepNoUpdateLocked(long eventTime, int reason) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "goToSleepNoUpdateLocked: eventTime=" + eventTime + ", reason=" + reason);
        }

        if (eventTime < mLastWakeTime || mWakefulness == WAKEFULNESS_ASLEEP
                || !mBootCompleted || !mSystemReady) {
            return false;
        }

        switch (reason) {
            case PowerManager.GO_TO_SLEEP_REASON_DEVICE_ADMIN:
                Slog.i(TAG, "Going to sleep due to device administration policy...");
                break;
            case PowerManager.GO_TO_SLEEP_REASON_TIMEOUT:
                Slog.i(TAG, "Going to sleep due to screen timeout...");
                break;
            default:
                Slog.i(TAG, "Going to sleep by user request...");
                reason = PowerManager.GO_TO_SLEEP_REASON_USER;
                break;
        }

        sendPendingNotificationsLocked();
        mNotifier.onGoToSleepStarted(reason);
        mSendGoToSleepFinishedNotificationWhenReady = true;

        mLastSleepTime = eventTime;
        mDirty |= DIRTY_WAKEFULNESS;
        mWakefulness = WAKEFULNESS_ASLEEP;

        // Report the number of wake locks that will be cleared by going to sleep.
        int numWakeLocksCleared = 0;
        final int numWakeLocks = mWakeLocks.size();
        for (int i = 0; i < numWakeLocks; i++) {
            final WakeLock wakeLock = mWakeLocks.get(i);
            switch (wakeLock.mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK) {
                case PowerManager.FULL_WAKE_LOCK:
                case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                case PowerManager.SCREEN_DIM_WAKE_LOCK:
                    numWakeLocksCleared += 1;
                    break;
            }
        }
        EventLog.writeEvent(EventLogTags.POWER_SLEEP_REQUESTED, numWakeLocksCleared);
        return true;
    }

    private boolean napNoUpdateLocked(long eventTime) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "napNoUpdateLocked: eventTime=" + eventTime);
        }

        if (eventTime < mLastWakeTime || mWakefulness != WAKEFULNESS_AWAKE
                || !mBootCompleted || !mSystemReady) {
            return false;
        }

        Slog.i(TAG, "Nap time...");

        mDirty |= DIRTY_WAKEFULNESS;
        mWakefulness = WAKEFULNESS_NAPPING;
        return true;
    }

    /**
     * Updates the global power state based on dirty bits recorded in mDirty.
     *
     * This is the main function that performs power state transitions.
     * We centralize them here so that we can recompute the power state completely
     * each time something important changes, and ensure that we do it the same
     * way each time.  The point is to gather all of the transition logic here.
     */
    private void updatePowerStateLocked() {
        if (!mSystemReady || mDirty == 0) {
            return;
        }

        // Phase 0: Basic state updates.
        updateIsPoweredLocked(mDirty);
        updateStayOnLocked(mDirty);

        // Phase 1: Update wakefulness.
        // Loop because the wake lock and user activity computations are influenced
        // by changes in wakefulness.
        final long now = SystemClock.uptimeMillis();
        int dirtyPhase2 = 0;
        for (;;) {
            int dirtyPhase1 = mDirty;
            dirtyPhase2 |= dirtyPhase1;
            mDirty = 0;

            updateWakeLockSummaryLocked(dirtyPhase1);
            updateUserActivitySummaryLocked(now, dirtyPhase1);
            if (!updateWakefulnessLocked(dirtyPhase1)) {
                break;
            }
        }

        // Phase 2: Display power state.
        updateDisplayPowerStateLocked(dirtyPhase2);

        // Phase 3: Send notifications, if needed.
        if (mDisplayReady) {
            sendPendingNotificationsLocked();
        }

        // Phase 4: Update suspend blocker.
        // Because we might release the last suspend blocker here, we need to make sure
        // we finished everything else first!
        updateSuspendBlockerLocked();
    }

    private void sendPendingNotificationsLocked() {
        if (mSendWakeUpFinishedNotificationWhenReady) {
            mSendWakeUpFinishedNotificationWhenReady = false;
            mNotifier.onWakeUpFinished();
        }
        if (mSendGoToSleepFinishedNotificationWhenReady) {
            mSendGoToSleepFinishedNotificationWhenReady = false;
            mNotifier.onGoToSleepFinished();
        }
    }

    /**
     * Updates the value of mIsPowered.
     * Sets DIRTY_IS_POWERED if a change occurred.
     */
    private void updateIsPoweredLocked(int dirty) {
        if ((dirty & DIRTY_BATTERY_STATE) != 0) {
            final boolean wasPowered = mIsPowered;
            final int oldPlugType = mPlugType;
            mIsPowered = mBatteryService.isPowered(BatteryManager.BATTERY_PLUGGED_ANY);
            mPlugType = mBatteryService.getPlugType();
            mBatteryLevel = mBatteryService.getBatteryLevel();

            if (DEBUG) {
                Slog.d(TAG, "updateIsPoweredLocked: wasPowered=" + wasPowered
                        + ", mIsPowered=" + mIsPowered
                        + ", oldPlugType=" + oldPlugType
                        + ", mPlugType=" + mPlugType
                        + ", mBatteryLevel=" + mBatteryLevel);
            }

            if (wasPowered != mIsPowered || oldPlugType != mPlugType) {
                mDirty |= DIRTY_IS_POWERED;

                // Treat plugging and unplugging the devices as a user activity.
                // Users find it disconcerting when they plug or unplug the device
                // and it shuts off right away.
                // Some devices also wake the device when plugged or unplugged because
                // they don't have a charging LED.
                final long now = SystemClock.uptimeMillis();
                if (shouldWakeUpWhenPluggedOrUnpluggedLocked(wasPowered, oldPlugType)) {
                    wakeUpNoUpdateLocked(now);
                }
                userActivityNoUpdateLocked(
                        now, PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, Process.SYSTEM_UID);
            }
        }
    }

    private boolean shouldWakeUpWhenPluggedOrUnpluggedLocked(boolean wasPowered, int oldPlugType) {
        // Don't wake when powered unless configured to do so.
        if (!mWakeUpWhenPluggedOrUnpluggedConfig) {
            return false;
        }

        // FIXME: Need more accurate detection of wireless chargers.
        //
        // We are unable to accurately detect whether the device is resting on the
        // charger unless it is actually receiving power.  This causes us some grief
        // because the device might not appear to be plugged into the wireless charger
        // unless it actually charging.
        //
        // To avoid spuriously waking the screen, we apply a special policy to
        // wireless chargers.
        //
        // 1. Don't wake the device when unplugged from wireless charger because
        //    it might be that the device is still resting on the wireless charger
        //    but is not receiving power anymore because the battery is full.
        //
        // 2. Don't wake the device when plugged into a wireless charger if the
        //    battery already appears to be mostly full.  This situation may indicate
        //    that the device was resting on the charger the whole time and simply
        //    wasn't receiving power because the battery was full.  We can't tell
        //    whether the device was just placed on the charger or whether it has
        //    been there for half of the night slowly discharging until it hit
        //    the point where it needed to start charging again.
        if (wasPowered && !mIsPowered
                && oldPlugType == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
            return false;
        }
        if (!wasPowered && mIsPowered
                && mPlugType == BatteryManager.BATTERY_PLUGGED_WIRELESS
                && mBatteryLevel >= WIRELESS_CHARGER_TURN_ON_BATTERY_LEVEL_LIMIT) {
            return false;
        }

        // Otherwise wake up!
        return true;
    }

    /**
     * Updates the value of mStayOn.
     * Sets DIRTY_STAY_ON if a change occurred.
     */
    private void updateStayOnLocked(int dirty) {
        if ((dirty & (DIRTY_BATTERY_STATE | DIRTY_SETTINGS)) != 0) {
            final boolean wasStayOn = mStayOn;
            if (mStayOnWhilePluggedInSetting != 0
                    && !isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked()) {
                mStayOn = mBatteryService.isPowered(mStayOnWhilePluggedInSetting);
            } else {
                mStayOn = false;
            }

            if (mStayOn != wasStayOn) {
                mDirty |= DIRTY_STAY_ON;
            }
        }
    }

    /**
     * Updates the value of mWakeLockSummary to summarize the state of all active wake locks.
     * Note that most wake-locks are ignored when the system is asleep.
     *
     * This function must have no other side-effects.
     */
    private void updateWakeLockSummaryLocked(int dirty) {
        if ((dirty & (DIRTY_WAKE_LOCKS | DIRTY_WAKEFULNESS)) != 0) {
            mWakeLockSummary = 0;

            final int numWakeLocks = mWakeLocks.size();
            for (int i = 0; i < numWakeLocks; i++) {
                final WakeLock wakeLock = mWakeLocks.get(i);
                switch (wakeLock.mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK) {
                    case PowerManager.PARTIAL_WAKE_LOCK:
                        mWakeLockSummary |= WAKE_LOCK_CPU;
                        break;
                    case PowerManager.FULL_WAKE_LOCK:
                        if (mWakefulness != WAKEFULNESS_ASLEEP) {
                            mWakeLockSummary |= WAKE_LOCK_CPU
                                    | WAKE_LOCK_SCREEN_BRIGHT | WAKE_LOCK_BUTTON_BRIGHT;
                            if (mWakefulness == WAKEFULNESS_AWAKE) {
                                mWakeLockSummary |= WAKE_LOCK_STAY_AWAKE;
                            }
                        }
                        break;
                    case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                        if (mWakefulness != WAKEFULNESS_ASLEEP) {
                            mWakeLockSummary |= WAKE_LOCK_CPU | WAKE_LOCK_SCREEN_BRIGHT;
                            if (mWakefulness == WAKEFULNESS_AWAKE) {
                                mWakeLockSummary |= WAKE_LOCK_STAY_AWAKE;
                            }
                        }
                        break;
                    case PowerManager.SCREEN_DIM_WAKE_LOCK:
                        if (mWakefulness != WAKEFULNESS_ASLEEP) {
                            mWakeLockSummary |= WAKE_LOCK_CPU | WAKE_LOCK_SCREEN_DIM;
                            if (mWakefulness == WAKEFULNESS_AWAKE) {
                                mWakeLockSummary |= WAKE_LOCK_STAY_AWAKE;
                            }
                        }
                        break;
                    case PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                        if (mWakefulness != WAKEFULNESS_ASLEEP) {
                            mWakeLockSummary |= WAKE_LOCK_CPU | WAKE_LOCK_PROXIMITY_SCREEN_OFF;
                        }
                        break;
                }
            }

            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateWakeLockSummaryLocked: mWakefulness="
                        + wakefulnessToString(mWakefulness)
                        + ", mWakeLockSummary=0x" + Integer.toHexString(mWakeLockSummary));
            }
        }
    }

    /**
     * Updates the value of mUserActivitySummary to summarize the user requested
     * state of the system such as whether the screen should be bright or dim.
     * Note that user activity is ignored when the system is asleep.
     *
     * This function must have no other side-effects.
     */
    private void updateUserActivitySummaryLocked(long now, int dirty) {
        // Update the status of the user activity timeout timer.
        if ((dirty & (DIRTY_USER_ACTIVITY | DIRTY_WAKEFULNESS | DIRTY_SETTINGS)) != 0) {
            mHandler.removeMessages(MSG_USER_ACTIVITY_TIMEOUT);

            long nextTimeout = 0;
            if (mWakefulness != WAKEFULNESS_ASLEEP) {
                final int screenOffTimeout = getScreenOffTimeoutLocked();
                final int screenDimDuration = getScreenDimDurationLocked(screenOffTimeout);

                mUserActivitySummary = 0;
                if (mLastUserActivityTime >= mLastWakeTime) {
                    nextTimeout = mLastUserActivityTime
                            + screenOffTimeout - screenDimDuration;
                    if (now < nextTimeout) {
                        if (now > mLastUserActivityTime + BUTTON_ON_DURATION) {
                            mButtonsLight.setBrightness(0);
                        } else {
                            mButtonsLight.setBrightness(mDisplayPowerRequest.screenBrightness);
                            nextTimeout = now + BUTTON_ON_DURATION;
                        }
                        mUserActivitySummary |= USER_ACTIVITY_SCREEN_BRIGHT;
                    } else {
                        nextTimeout = mLastUserActivityTime + screenOffTimeout;
                        if (now < nextTimeout) {
                            mUserActivitySummary |= USER_ACTIVITY_SCREEN_DIM;
                        }
                    }
                }
                if (mUserActivitySummary == 0
                        && mLastUserActivityTimeNoChangeLights >= mLastWakeTime) {
                    nextTimeout = mLastUserActivityTimeNoChangeLights + screenOffTimeout;
                    if (now < nextTimeout
                            && mDisplayPowerRequest.screenState
                                    != DisplayPowerRequest.SCREEN_STATE_OFF) {
                        mUserActivitySummary = mDisplayPowerRequest.screenState
                                == DisplayPowerRequest.SCREEN_STATE_BRIGHT ?
                                USER_ACTIVITY_SCREEN_BRIGHT : USER_ACTIVITY_SCREEN_DIM;
                    }
                }
                if (mUserActivitySummary != 0) {
                    Message msg = mHandler.obtainMessage(MSG_USER_ACTIVITY_TIMEOUT);
                    msg.setAsynchronous(true);
                    mHandler.sendMessageAtTime(msg, nextTimeout);
                }
            } else {
                mUserActivitySummary = 0;
            }

            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateUserActivitySummaryLocked: mWakefulness="
                        + wakefulnessToString(mWakefulness)
                        + ", mUserActivitySummary=0x" + Integer.toHexString(mUserActivitySummary)
                        + ", nextTimeout=" + TimeUtils.formatUptime(nextTimeout));
            }
        }
    }

    /**
     * Called when a user activity timeout has occurred.
     * Simply indicates that something about user activity has changed so that the new
     * state can be recomputed when the power state is updated.
     *
     * This function must have no other side-effects besides setting the dirty
     * bit and calling update power state.  Wakefulness transitions are handled elsewhere.
     */
    private void handleUserActivityTimeout() { // runs on handler thread
        synchronized (mLock) {
            if (DEBUG_SPEW) {
                Slog.d(TAG, "handleUserActivityTimeout");
            }

            mDirty |= DIRTY_USER_ACTIVITY;
            updatePowerStateLocked();
        }
    }

    private int getScreenOffTimeoutLocked() {
        int timeout = mScreenOffTimeoutSetting;
        if (isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked()) {
            timeout = Math.min(timeout, mMaximumScreenOffTimeoutFromDeviceAdmin);
        }
        if (mUserActivityTimeoutOverrideFromWindowManager >= 0) {
            timeout = (int)Math.min(timeout, mUserActivityTimeoutOverrideFromWindowManager);
        }
        return Math.max(timeout, MINIMUM_SCREEN_OFF_TIMEOUT);
    }

    private int getScreenDimDurationLocked(int screenOffTimeout) {
        return Math.min(SCREEN_DIM_DURATION,
                (int)(screenOffTimeout * MAXIMUM_SCREEN_DIM_RATIO));
    }

    /**
     * Updates the wakefulness of the device.
     *
     * This is the function that decides whether the device should start napping
     * based on the current wake locks and user activity state.  It may modify mDirty
     * if the wakefulness changes.
     *
     * Returns true if the wakefulness changed and we need to restart power state calculation.
     */
    private boolean updateWakefulnessLocked(int dirty) {
        boolean changed = false;
        if ((dirty & (DIRTY_WAKE_LOCKS | DIRTY_USER_ACTIVITY | DIRTY_BOOT_COMPLETED
                | DIRTY_WAKEFULNESS | DIRTY_STAY_ON | DIRTY_PROXIMITY_POSITIVE
                | DIRTY_DOCK_STATE)) != 0) {
            if (mWakefulness == WAKEFULNESS_AWAKE && isItBedTimeYetLocked()) {
                if (DEBUG_SPEW) {
                    Slog.d(TAG, "updateWakefulnessLocked: Bed time...");
                }
                final long time = SystemClock.uptimeMillis();
                changed = goToSleepNoUpdateLocked(time,
                        PowerManager.GO_TO_SLEEP_REASON_TIMEOUT);
            }
        }
        return changed;
    }

    /**
     * Returns true if the device should go to sleep now.
     * Also used when exiting a dream to determine whether we should go back
     * to being fully awake or else go to sleep for good.
     */
    private boolean isItBedTimeYetLocked() {
        return mBootCompleted && !isBeingKeptAwakeLocked();
    }

    /**
     * Returns true if the device is being kept awake by a wake lock, user activity
     * or the stay on while powered setting.
     */
    private boolean isBeingKeptAwakeLocked() {
        return mStayOn
                || mProximityPositive
                || (mWakeLockSummary & WAKE_LOCK_STAY_AWAKE) != 0
                || (mUserActivitySummary & (USER_ACTIVITY_SCREEN_BRIGHT
                        | USER_ACTIVITY_SCREEN_DIM)) != 0;
    }

    private int getAutoBrightnessValue(int current, int last, int[] levels, int[] values) {
        try {
            // If have a last value and sensor value is decreasing
            // we should include hysteresis in calculations
            if (last >= 0 && current < last) {
                int i;
                for (i = 0; i < levels.length; i++) {
                    if (last < levels[i]) {
                        break;
                    }
                }
                // First sensor level begins at 0 and doesn't have hysteresis
                if (i > 0) {
                    final int length = levels[i - 1] - (i == 1 ? 0 : levels[i - 2]);
                    final int lower = levels[i - 1] - Math.round(mLightHysteresis * length);
                    if (mDebugLightSensor) {
                        Slog.d(TAG, "level=" + i + " current=" + current + " last=" + last
                                + " length=" + length + " lower=" + lower);
                    }
                    if (current < levels[i - 1] && current >= lower) {
                        // Current sensor value has decreased to the level below but is
                        // still too large due to the hysteresis.
                        // Ignore current to reduce flicker (use last)
                        if (mDebugLightSensor) {
                            Slog.d(TAG, "using last instead of current: " + current
                                    + "->" + last);
                        }
                        current = last;
                    }
                }
            }

            int i;
            for (i = 0; i < levels.length; i++) {
                if (current < levels[i]) {
                    break;
                }
            }
            return values[i];
        } catch (Exception e) {
            // guard against null pointer or index out of bounds errors
            Slog.e(TAG, "getAutoBrightnessValue", e);
            return 255;
        }
    }

    private void lightFilterReset(int initial) {
        mLightFilterEqualCounter = 0;
        mLightFilterIndex = 0;
        mLightFilterSamples = new int[(mLightFilterWindow / mLightFilterInterval)];
        mLightFilterSampleCounter = initial == -1 ? 0 : mLightFilterSamples.length;
        mLightFilterSum = initial == -1 ? 0 : initial * mLightFilterSamples.length;
        java.util.Arrays.fill(mLightFilterSamples, initial);
        if (mDebugLightSensor) {
            Slog.d(TAGF, "reset: " + initial);
        }
    }

    private void resetLastLightValues() {
        mLastLcdValue = -1;
        mLastButtonValue = -1;
        mLastKeyboardValue = -1;
    }

    public int getLightSensorValue() {
        return (int) mLightSensorValue;
    }

    public int getRawLightSensorValue() {
        if (mLightFilterEnabled && mLightFilterSample != -1) {
            return mLightFilterSample;
        } else {
            return getLightSensorValue();
        }
    }

    public int getLightSensorScreenBrightness() {
        return mLightSensorScreenBrightness;
    }

    public int getLightSensorButtonBrightness() {
        return mLightSensorButtonBrightness;
    }

    public int getLightSensorKeyboardBrightness() {
        return mLightSensorKeyboardBrightness;
    }

    /**
     * Reboot the device immediately, passing 'reason' (may be null)
     * to the underlying __reboot system call.  Should not return.
     */
    public void reboot(String reason)
    {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.REBOOT, null);

        if (mHandler == null || !ActivityManagerNative.isSystemReady()) {
            throw new IllegalStateException("Too early to call reboot()");
        }

        final String finalReason = reason;
        Runnable runnable = new Runnable() {
            public void run() {
                synchronized (this) {
                    ShutdownThread.reboot(getUiContext(), finalReason, false);
                }
                
            }
        };
        // ShutdownThread must run on a looper capable of displaying the UI.
        mHandler.post(runnable);

        // PowerManager.reboot() is documented not to return so just wait for the inevitable.
        synchronized (runnable) {
            while (true) {
                try {
                    runnable.wait();
                } catch (InterruptedException e) {
                }
            }
        }
    }

    /**
     * Crash the runtime (causing a complete restart of the Android framework).
     * Requires REBOOT permission.  Mostly for testing.  Should not return.
     */
    public void crash(final String message)
    {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.REBOOT, null);
        Thread t = new Thread("PowerManagerService.crash()") {
            public void run() { throw new RuntimeException(message); }
        };
        try {
            t.start();
            t.join();
        } catch (InterruptedException e) {
            Log.wtf(TAG, e);
        }
    }

    private Context getUiContext() {
        if (mUiContext == null) {
            mUiContext = ThemeUtils.createUiContext(mContext);
        }
        return mUiContext != null ? mUiContext : mContext;
    }

    private int[] parseIntArray(String intArray) {
        int[] result;
        if (intArray == null || intArray.length() == 0) {
            result = new int[0];
        } else {
            String[] split = intArray.split(",");
            result = new int[split.length];
            for (int i = 0; i < split.length; i++) {
                result[i] = Integer.parseInt(split[i]);
            }
        }
        return result;
    }

    /**
     * Refreshes cached secure settings.  Called once on startup, and
     * on subsequent changes to secure settings.
     */
    private void updateSettingsValues() {
        mShortKeylightDelay = Settings.Secure.getInt(
                mContext.getContentResolver(),
                Settings.Secure.SHORT_KEYLIGHT_DELAY_MS,
                SHORT_KEYLIGHT_DELAY_DEFAULT);
        // Slog.i(TAG, "updateSettingsValues(): mShortKeylightDelay now " + mShortKeylightDelay);
    }

    public int getSupportedWakeLockFlags() {
        int result = PowerManager.PARTIAL_WAKE_LOCK
                   | PowerManager.FULL_WAKE_LOCK
                   | PowerManager.SCREEN_DIM_WAKE_LOCK;

        if (mProximitySensor != null) {
            result |= PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK;
        }

        return result;
    }

    /**
     * Used by device administration to set the maximum screen off timeout.
     *
     * This method must only be called by the device administration policy manager.
     */
    @Override // Binder call
    public void setMaximumScreenOffTimeoutFromDeviceAdmin(int timeMs) {
        final long ident = Binder.clearCallingIdentity();
        try {
            setMaximumScreenOffTimeoutFromDeviceAdminInternal(timeMs);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setMaximumScreenOffTimeoutFromDeviceAdminInternal(int timeMs) {
        synchronized (mLock) {
            mMaximumScreenOffTimeoutFromDeviceAdmin = timeMs;
            mDirty |= DIRTY_SETTINGS;
            updatePowerStateLocked();
        }
    }

    public void setAttentionLight(boolean on, int color) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);
        mAttentionLight.setFlashing(color, LightsService.LIGHT_FLASH_HARDWARE, (on ? 3 : 0), 0);
    }

    /**
     * Used by the Watchdog.
     */
    public long timeSinceScreenWasLastOn() {
        synchronized (mLock) {
            if (mDisplayPowerRequest.screenState != DisplayPowerRequest.SCREEN_STATE_OFF) {
                return 0;
            }
            return SystemClock.elapsedRealtime() - mLastScreenOffEventElapsedRealTime;
        }
    }

    /**
     * Used by the settings application and brightness control widgets to
     * temporarily override the current screen brightness setting so that the
     * user can observe the effect of an intended settings change without applying
     * it immediately.
     *
     * The override will be canceled when the setting value is next updated.
     *
     * @param brightness The overridden brightness.
     *
     * @see Settings.System#SCREEN_BRIGHTNESS
     */
    @Override // Binder call
    public void setTemporaryScreenBrightnessSettingOverride(int brightness) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            setTemporaryScreenBrightnessSettingOverrideInternal(brightness);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setTemporaryScreenBrightnessSettingOverrideInternal(int brightness) {
        synchronized (mLock) {
            if (mTemporaryScreenBrightnessSettingOverride != brightness) {
                mTemporaryScreenBrightnessSettingOverride = brightness;
                mDirty |= DIRTY_SETTINGS;
                updatePowerStateLocked();
            }
        }
    }

    /**
     * Used by the settings application and brightness control widgets to
     * temporarily override the current screen auto-brightness adjustment setting so that the
     * user can observe the effect of an intended settings change without applying
     * it immediately.
     *
     * The override will be canceled when the setting value is next updated.
     *
     * @param adj The overridden brightness, or Float.NaN to disable the override.
     *
     * @see Settings.System#SCREEN_AUTO_BRIGHTNESS_ADJ
     */
    @Override // Binder call
    public void setTemporaryScreenAutoBrightnessAdjustmentSettingOverride(float adj) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, null);

        final long ident = Binder.clearCallingIdentity();
        try {
            setTemporaryScreenAutoBrightnessAdjustmentSettingOverrideInternal(adj);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setTemporaryScreenAutoBrightnessAdjustmentSettingOverrideInternal(float adj) {
        synchronized (mLock) {
            // Note: This condition handles NaN because NaN is not equal to any other
            // value, including itself.
            if (mTemporaryScreenAutoBrightnessAdjustmentSettingOverride != adj) {
                mTemporaryScreenAutoBrightnessAdjustmentSettingOverride = adj;
                mDirty |= DIRTY_SETTINGS;
                updatePowerStateLocked();
            }
        }
    }

    @Override // Binder call
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump PowerManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        pw.println("POWER MANAGER (dumpsys power)\n");

        final DisplayPowerController dpc;
        synchronized (mLock) {
            pw.println("Power Manager State:");
            pw.println("  mDirty=0x" + Integer.toHexString(mDirty));
            pw.println("  mWakefulness=" + wakefulnessToString(mWakefulness));
            pw.println("  mIsPowered=" + mIsPowered);
            pw.println("  mPlugType=" + mPlugType);
            pw.println("  mBatteryLevel=" + mBatteryLevel);
            pw.println("  mDockState=" + mDockState);
            pw.println("  mStayOn=" + mStayOn);
            pw.println("  mProximityPositive=" + mProximityPositive);
            pw.println("  mBootCompleted=" + mBootCompleted);
            pw.println("  mSystemReady=" + mSystemReady);
            pw.println("  mWakeLockSummary=0x" + Integer.toHexString(mWakeLockSummary));
            pw.println("  mUserActivitySummary=0x" + Integer.toHexString(mUserActivitySummary));
            pw.println("  mRequestWaitForNegativeProximity=" + mRequestWaitForNegativeProximity);
            pw.println("  mSandmanScheduled=" + mSandmanScheduled);
            pw.println("  mLastWakeTime=" + TimeUtils.formatUptime(mLastWakeTime));
            pw.println("  mLastSleepTime=" + TimeUtils.formatUptime(mLastSleepTime));
            pw.println("  mSendWakeUpFinishedNotificationWhenReady="
                    + mSendWakeUpFinishedNotificationWhenReady);
            pw.println("  mSendGoToSleepFinishedNotificationWhenReady="
                    + mSendGoToSleepFinishedNotificationWhenReady);
            pw.println("  mLastUserActivityTime=" + TimeUtils.formatUptime(mLastUserActivityTime));
            pw.println("  mLastUserActivityTimeNoChangeLights="
                    + TimeUtils.formatUptime(mLastUserActivityTimeNoChangeLights));
            pw.println("  mDisplayReady=" + mDisplayReady);
            pw.println("  mHoldingWakeLockSuspendBlocker=" + mHoldingWakeLockSuspendBlocker);

            pw.println();
            pw.println("Settings and Configuration:");
            pw.println("  mScreenOffTimeoutSetting=" + mScreenOffTimeoutSetting);
            pw.println("  mMaximumScreenOffTimeoutFromDeviceAdmin="
                    + mMaximumScreenOffTimeoutFromDeviceAdmin + " (enforced="
                    + isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked() + ")");
            pw.println("  mStayOnWhilePluggedInSetting=" + mStayOnWhilePluggedInSetting);
            pw.println("  mScreenBrightnessSetting=" + mScreenBrightnessSetting);
            pw.println("  mScreenAutoBrightnessAdjustmentSetting="
                    + mScreenAutoBrightnessAdjustmentSetting);
            pw.println("  mScreenBrightnessModeSetting=" + mScreenBrightnessModeSetting);
            pw.println("  mScreenBrightnessOverrideFromWindowManager="
                    + mScreenBrightnessOverrideFromWindowManager);
            pw.println("  mUserActivityTimeoutOverrideFromWindowManager="
                    + mUserActivityTimeoutOverrideFromWindowManager);
            pw.println("  mTemporaryScreenBrightnessSettingOverride="
                    + mTemporaryScreenBrightnessSettingOverride);
            pw.println("  mTemporaryScreenAutoBrightnessAdjustmentSettingOverride="
                    + mTemporaryScreenAutoBrightnessAdjustmentSettingOverride);
            pw.println("  mScreenBrightnessSettingMinimum=" + mScreenBrightnessSettingMinimum);
            pw.println("  mScreenBrightnessSettingMaximum=" + mScreenBrightnessSettingMaximum);
            pw.println("  mScreenBrightnessSettingDefault=" + mScreenBrightnessSettingDefault);

            final int screenOffTimeout = getScreenOffTimeoutLocked();
            final int screenDimDuration = getScreenDimDurationLocked(screenOffTimeout);
            pw.println();
            pw.println("Screen off timeout: " + screenOffTimeout + " ms");
            pw.println("Screen dim duration: " + screenDimDuration + " ms");

            pw.println();
            pw.println("Wake Locks: size=" + mWakeLocks.size());
            for (WakeLock wl : mWakeLocks) {
                pw.println("  " + wl);
            }

            pw.println();
            pw.println("Suspend Blockers: size=" + mSuspendBlockers.size());
            for (SuspendBlocker sb : mSuspendBlockers) {
                pw.println("  " + sb);
            }

            pw.println();
            pw.println("Screen On Blocker: " + mScreenOnBlocker);

            pw.println();
            pw.println("Display Blanker: " + mDisplayBlanker);

            dpc = mDisplayPowerController;
        }

        if (dpc != null) {
            dpc.dump(pw);
        }
    }

    private SuspendBlocker createSuspendBlockerLocked(String name) {
        SuspendBlocker suspendBlocker = new SuspendBlockerImpl(name);
        mSuspendBlockers.add(suspendBlocker);
        return suspendBlocker;
    }

    private static String wakefulnessToString(int wakefulness) {
        switch (wakefulness) {
            case WAKEFULNESS_ASLEEP:
                return "Asleep";
            case WAKEFULNESS_AWAKE:
                return "Awake";
            case WAKEFULNESS_NAPPING:
                return "Napping";
            default:
                return Integer.toString(wakefulness);
        }
    }

    private static WorkSource copyWorkSource(WorkSource workSource) {
        return workSource != null ? new WorkSource(workSource) : null;
    }

    private final class BatteryReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                handleBatteryStateChangedLocked();
            }
        }
    }

    private final class BootCompletedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // This is our early signal that the system thinks it has finished booting.
            // However, the boot animation may still be running for a few more seconds
            // since it is ultimately in charge of when it terminates.
            // Defer transitioning into the boot completed state until the animation exits.
            // We do this so that the screen does not start to dim prematurely before
            // the user has actually had a chance to interact with the device.
            startWatchingForBootAnimationFinished();
        }
    }

    private final class DockReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                int dockState = intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                        Intent.EXTRA_DOCK_STATE_UNDOCKED);
                if (mDockState != dockState) {
                    mDockState = dockState;
                    mDirty |= DIRTY_DOCK_STATE;
                    updatePowerStateLocked();
                }
            }
        }
    }

    private final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            synchronized (mLock) {
                handleSettingsChangedLocked();
            }
        }
    }

    /**
     * Handler for asynchronous operations performed by the power manager.
     */
    private final class PowerManagerHandler extends Handler {
        public PowerManagerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_USER_ACTIVITY_TIMEOUT:
                    handleUserActivityTimeout();
                    break;
                case MSG_SCREEN_ON_BLOCKER_RELEASED:
                    handleScreenOnBlockerReleased();
                    break;
                case MSG_CHECK_IF_BOOT_ANIMATION_FINISHED:
                    checkIfBootAnimationFinished();
                    break;
            }
        }
    }

    /**
     * Represents a wake lock that has been acquired by an application.
     */
    private final class WakeLock implements IBinder.DeathRecipient {
        public final IBinder mLock;
        public int mFlags;
        public String mTag;
        public WorkSource mWorkSource;
        public int mOwnerUid;
        public int mOwnerPid;

        public WakeLock(IBinder lock, int flags, String tag, WorkSource workSource,
                int ownerUid, int ownerPid) {
            mLock = lock;
            mFlags = flags;
            mTag = tag;
            mWorkSource = copyWorkSource(workSource);
            mOwnerUid = ownerUid;
            mOwnerPid = ownerPid;
        }

        @Override
        public void binderDied() {
            PowerManagerService.this.handleWakeLockDeath(this);
        }

        public boolean hasSameProperties(int flags, String tag, WorkSource workSource,
                int ownerUid, int ownerPid) {
            return mFlags == flags
                    && mTag.equals(tag)
                    && hasSameWorkSource(workSource)
                    && mOwnerUid == ownerUid
                    && mOwnerPid == ownerPid;
        }

        public void updateProperties(int flags, String tag, WorkSource workSource,
                int ownerUid, int ownerPid) {
            mFlags = flags;
            mTag = tag;
            updateWorkSource(workSource);
            mOwnerUid = ownerUid;
            mOwnerPid = ownerPid;
        }

        public boolean hasSameWorkSource(WorkSource workSource) {
            return Objects.equal(mWorkSource, workSource);
        }

        public void updateWorkSource(WorkSource workSource) {
            mWorkSource = copyWorkSource(workSource);
        }

        @Override
        public String toString() {
            return getLockLevelString()
                    + " '" + mTag + "'" + getLockFlagsString()
                    + " (uid=" + mOwnerUid + ", pid=" + mOwnerPid + ", ws=" + mWorkSource + ")";
        }

        private String getLockLevelString() {
            switch (mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK) {
                case PowerManager.FULL_WAKE_LOCK:
                    return "FULL_WAKE_LOCK                ";
                case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                    return "SCREEN_BRIGHT_WAKE_LOCK       ";
                case PowerManager.SCREEN_DIM_WAKE_LOCK:
                    return "SCREEN_DIM_WAKE_LOCK          ";
                case PowerManager.PARTIAL_WAKE_LOCK:
                    return "PARTIAL_WAKE_LOCK             ";
                case PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                    return "PROXIMITY_SCREEN_OFF_WAKE_LOCK";
                default:
                    return "???                           ";
            }
        }

        private String getLockFlagsString() {
            String result = "";
            if ((mFlags & PowerManager.ACQUIRE_CAUSES_WAKEUP) != 0) {
                result += " ACQUIRE_CAUSES_WAKEUP";
            }
            if ((mFlags & PowerManager.ON_AFTER_RELEASE) != 0) {
                result += " ON_AFTER_RELEASE";
            }
            return result;
        }
    }

    private final class SuspendBlockerImpl implements SuspendBlocker {
        private final String mName;
        private int mReferenceCount;

        public SuspendBlockerImpl(String name) {
            mName = name;
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                if (mReferenceCount != 0) {
                    Log.wtf(TAG, "Suspend blocker \"" + mName
                            + "\" was finalized without being released!");
                    mReferenceCount = 0;
                    nativeReleaseSuspendBlocker(mName);
                }
            } finally {
                super.finalize();
            }
        }

        @Override
        public void acquire() {
            synchronized (this) {
                mReferenceCount += 1;
                if (mReferenceCount == 1) {
                    nativeAcquireSuspendBlocker(mName);
                }
            }
        }

        @Override
        public void release() {
            synchronized (this) {
                mReferenceCount -= 1;
                if (mReferenceCount == 0) {
                    nativeReleaseSuspendBlocker(mName);
                } else if (mReferenceCount < 0) {
                    Log.wtf(TAG, "Suspend blocker \"" + mName
                            + "\" was released without being acquired!", new Throwable());
                    mReferenceCount = 0;
                }
            }
        }

        @Override
        public String toString() {
            synchronized (this) {
                return mName + ": ref count=" + mReferenceCount;
            }
        }
    }

    private final class ScreenOnBlockerImpl implements ScreenOnBlocker {
        private int mNestCount;

        public boolean isHeld() {
            synchronized (this) {
                return mNestCount != 0;
            }
        }

        @Override
        public void acquire() {
            synchronized (this) {
                mNestCount += 1;
                if (DEBUG) {
                    Slog.d(TAG, "Screen on blocked: mNestCount=" + mNestCount);
                }
            }
        }

        @Override
        public void release() {
            synchronized (this) {
                mNestCount -= 1;
                if (mNestCount < 0) {
                    Log.wtf(TAG, "Screen on blocker was released without being acquired!",
                            new Throwable());
                    mNestCount = 0;
                }
                if (mNestCount == 0) {
                    mHandler.sendEmptyMessage(MSG_SCREEN_ON_BLOCKER_RELEASED);
                }
                if (DEBUG) {
                    Slog.d(TAG, "Screen on unblocked: mNestCount=" + mNestCount);
                }
            }
        }

        @Override
        public String toString() {
            synchronized (this) {
                return "held=" + (mNestCount != 0) + ", mNestCount=" + mNestCount;
            }
        }
    }

    private final class DisplayBlankerImpl implements DisplayBlanker {
        private boolean mBlanked;

        @Override
        public void blankAllDisplays() {
            synchronized (this) {
                mBlanked = true;
                mDisplayManagerService.blankAllDisplaysFromPowerManager();
                nativeSetInteractive(false);
                nativeSetAutoSuspend(true);
            }
        }

        @Override
        public void unblankAllDisplays() {
            synchronized (this) {
                nativeSetAutoSuspend(false);
                nativeSetInteractive(true);
                mDisplayManagerService.unblankAllDisplaysFromPowerManager();
                mBlanked = false;
            }
        }

        @Override
        public String toString() {
            synchronized (this) {
                return "blanked=" + mBlanked;
            }
        }
    }
}
