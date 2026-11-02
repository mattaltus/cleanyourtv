package io.github.mattaltus.speakerleds;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * Stays bound to the TV's assistant LED service and holds one of its patterns (by default "mute", which matches
 * what the four speaker LEDs show on stock firmware) while the screen is on. Standby darkens them by itself, and
 * after a wake the LED service is started afresh: setting the pattern it already holds does not relight them.
 * With follow_mic, as on stock, the pattern shows only while TCL's far-field mic is off, and the LEDs go dark
 * when it is switched on.
 */
public class LedHoldService extends Service {
    static final String TAG = "SpeakerLeds";
    static final String LED_PACKAGE = "com.google.android.tv.assistant";
    static final String LED_ACTION = "com.google.android.assistant.led.FixedStateLedService";
    static final String DESCRIPTOR = "com.google.android.assistant.led.IFixedStateLedService";
    // TCL's far-field mic state, read by Google's voice app on stock firmware: mic_status 1 = on, 0 = off.
    static final Uri MIC = Uri.parse("content://atv.hotwordmic/togglestate");

    private static final String PREFS = "leds";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PATTERN = "pattern";
    private static final String CHANNEL = "leds";
    // The LED service connects to its own worker asynchronously and drops calls until then, so after a connect
    // the calls wait, at SHOW_MS. Only a few calls, seconds apart: the service's animation loop crashes when its
    // pattern is switched in quick succession. Versions 7 and 8 set a different pattern first, to relight LEDs that
    // had gone dark, which showed white at every wake; those dark spells were most likely the mic switch being on.
    private static final long[] SHOW_MS = {3500, 6000};
    // Between letting go of the LED service (it shuts down with its last client) and binding it again.
    private static final long REBIND_MS = 700;
    // A wake sends both SCREEN_ON and TCL's STR_RESUME_COMPLETED: one restart covers both.
    private static final long RESTART_GAP_MS = 3000;
    static final String ACTION_RESTART = "io.github.mattaltus.speakerleds.RESTART";
    static final String ACTION_BOOT = "io.github.mattaltus.speakerleds.BOOT";
    // After a power cut, and after a long standby, the pattern set straight away is accepted but the LEDs stay
    // dark, and the service then holds it, so restart it again after these delays (10 s was enough after a power
    // cut on a 43C725; 60 s is a backup).
    private static final long[] LATE_RESTART_MS = {10000, 60000};
    // A standby at least this long gets the late restarts too; a shorter one doesn't, so it doesn't blink.
    private static final long LONG_STANDBY_MS = 10 * 60 * 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Handler lateHandler = new Handler(Looper.getMainLooper());
    private Notification notification;
    private IBinder led;
    private boolean bound;
    private boolean available;
    private long lastRestart;
    private long screenOffAt = -1; // elapsedRealtime of the last SCREEN_OFF, -1 when it was missed
    // elapsedRealtime minus uptimeMillis at the last wake: it grows only while the CPU sleeps in standby, so it
    // measures the standby even when a frozen app gets SCREEN_OFF late.
    private long sleptBefore;
    // Whether the LEDs may be showing the pattern: "off" is an animation that flashes first, so it goes out once
    // when the mic switches on (or once after the app starts, not knowing), not on every wake.
    private boolean showing = true;
    private final ContentObserver mic = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) {
            Log.i(TAG, "far-field mic " + (Boolean.TRUE.equals(micOn()) ? "on" : "off"));
            show();
        }
    };

    static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ENABLED, on).apply();
        Intent i = new Intent(c, LedHoldService.class);
        if (on) c.startForegroundService(i);
        else c.stopService(i);
    }

    static String pattern(Context c) {
        String p = prefs(c).getString(KEY_PATTERN, c.getString(R.string.led_pattern));
        return Patterns.isValid(p) ? p : Patterns.FALLBACK;
    }

    /** Stores the pattern and shows it now if the LEDs are on; an unknown name is ignored. */
    static void setPattern(Context c, String name) {
        if (!Patterns.isValid(name)) return;
        prefs(c).edit().putString(KEY_PATTERN, name).apply();
        if (isEnabled(c)) c.startForegroundService(new Intent(c, LedHoldService.class));
    }

    /** Starts the LED service afresh, from a broadcast that reaches the app while TCL has it frozen. */
    static void restart(Context c) {
        if (isEnabled(c)) c.startForegroundService(new Intent(c, LedHoldService.class).setAction(ACTION_RESTART));
    }

    static Intent ledIntent() {
        return new Intent(LED_ACTION).setPackage(LED_PACKAGE);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.i(TAG, "connected to the LED service");
            led = binder;
            showSoon();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "LED service disconnected");
            led = null; // the system reconnects when the LED service comes back
        }

        @Override public void onBindingDied(ComponentName name) {
            Log.w(TAG, "LED service binding died");
            led = null;
            unbind();
            bind();
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // No "off" on standby: that pattern is an animation (a flash), and standby darkens the LEDs anyway.
            String a = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(a)) {
                wake("screen on");
            } else if (Intent.ACTION_DREAMING_STOPPED.equals(a)) {
                if (!lit()) restartLed();
            } else {
                screenOffAt = SystemClock.elapsedRealtime();
                handler.removeCallbacksAndMessages(null);
                lateHandler.removeCallbacksAndMessages(null);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.channel), NotificationManager.IMPORTANCE_MIN));
        notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.icon)
                .setContentTitle(getString(R.string.notification))
                .build();
        promote();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_DREAMING_STOPPED);
        registerReceiver(screen, f);
        sleptBefore = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis();
        if (getResources().getBoolean(R.bool.follow_mic)) {
            try {
                getContentResolver().registerContentObserver(MIC, true, mic);
            } catch (SecurityException e) {
                Log.i(TAG, "no far-field mic state on this TV");
            }
        }
        bind();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isEnabled(this) || !available) {
            stopSelf();
            return START_NOT_STICKY;
        }
        promote();
        String action = intent == null ? null : intent.getAction();
        if (ACTION_RESTART.equals(action)) {
            wake("TCL resume");
        } else if (ACTION_BOOT.equals(action)) {
            restartLater();
        } else {
            show(); // a new pattern from the card; before the first connect this does nothing
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        lateHandler.removeCallbacksAndMessages(null);
        unregisterReceiver(screen);
        getContentResolver().unregisterContentObserver(mic);
        set(Patterns.GET_OFF);
        unbind();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void bind() {
        try {
            bound = bindService(ledIntent(), connection, BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            bound = false;
        }
        if (bound) available = true;
        else Log.w(TAG, "LED service not available");
    }

    private void unbind() {
        if (!bound) return;
        bound = false;
        unbindService(connection);
    }

    /** TCL firmware refuses this unless the app is on screen; the service then runs as a started service. */
    private void promote() {
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, notification);
    }

    /** A wake: restart the LED service now, and again later when the standby was long or its start was missed. */
    private void wake(String why) {
        long now = SystemClock.elapsedRealtime();
        long off = screenOffAt < 0 ? -1 : now - screenOffAt;
        long sleptNow = now - SystemClock.uptimeMillis();
        long slept = sleptNow - sleptBefore;
        sleptBefore = sleptNow;
        Log.i(TAG, "wake (" + why + "), screen off " + (off < 0 ? "unknown" : off / 1000 + " s")
                + ", asleep " + slept / 1000 + " s");
        // The bar lights itself orange at every wake. With the pattern lit that's already the look, and a restart
        // only adds a dark gap while the LED service is down, so it's left alone.
        if (lit()) return;
        restartLed();
        if (off < 0 || off >= LONG_STANDBY_MS || slept >= LONG_STANDBY_MS) restartLater();
    }

    private void restartLater() {
        lateHandler.removeCallbacksAndMessages(null);
        for (long ms : LATE_RESTART_MS) lateHandler.postDelayed(this::restartLed, ms);
    }

    /** Lets go of the LED service, so it shuts down, then binds a fresh one, which gets the pattern on connect. */
    private void restartLed() {
        long now = SystemClock.elapsedRealtime();
        if (!getSystemService(PowerManager.class).isInteractive() || now - lastRestart < RESTART_GAP_MS) return;
        lastRestart = now;
        Log.i(TAG, "restarting the LED service, " + (now / 1000) + " s after boot");
        handler.removeCallbacksAndMessages(null);
        led = null;
        unbind();
        handler.postDelayed(this::bind, REBIND_MS);
    }

    private void showSoon() {
        handler.removeCallbacksAndMessages(null);
        for (long ms : SHOW_MS) handler.postDelayed(this::show, ms);
    }

    private void show() {
        if (!getSystemService(PowerManager.class).isInteractive()) return;
        if (lit()) {
            if (set(Patterns.getter(pattern(this)))) showing = true;
        } else if (showing) {
            if (set(Patterns.GET_OFF)) showing = false;
        }
    }

    private boolean lit() {
        return Patterns.lit(getResources().getBoolean(R.bool.follow_mic), micOn());
    }

    /** TCL's far-field mic: true on, false off, null when the TV doesn't say. */
    private Boolean micOn() {
        try (Cursor c = getContentResolver().query(MIC, null, null, null, null)) {
            if (c == null || !c.moveToFirst()) return null;
            return c.getInt(0) != 0;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Reads the state's value with its getter, then passes it to setState; false when it couldn't. */
    private boolean set(int getter) {
        IBinder b = led;
        if (b == null) return false;
        try {
            int value = call(b, getter, -1);
            call(b, Patterns.SET_STATE, value);
            Log.i(TAG, "setState " + value + " (getter " + getter + ")");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "LED call failed", e);
            return false;
        }
    }

    private static int call(IBinder b, int code, int arg) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (arg >= 0) data.writeInt(arg);
            b.transact(code, data, reply, 0);
            reply.readException();
            return arg >= 0 ? 0 : reply.readInt();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
