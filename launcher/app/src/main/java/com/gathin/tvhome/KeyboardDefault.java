package com.gathin.tvhome;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

/**
 * Local (living room and bedroom): Google's remote service makes its phone bridge (ImeBridgeService) the default
 * keyboard at every boot, and search boxes then only say "Use the keyboard from your mobile device". With
 * default_keyboard set in config.xml, Home starting or coming back and the Home button helper connecting put that
 * keyboard back as the default, when the installer granted WRITE_SECURE_SETTINGS. Empty: the keyboard is left alone.
 * The bridge is set late in the boot, after both, so the helper also watches the setting for a few minutes after it
 * connects; past that window a keyboard picked by hand in Settings stays, until Home comes back.
 */
final class KeyboardDefault {
    /** How long after the helper connects a change of the default keyboard is undone. */
    static final long WATCH_MS = 5 * 60 * 1000;

    private KeyboardDefault() {
    }

    /** Puts default_keyboard back whenever the default changes, for WATCH_MS; run the result to stop sooner. */
    static Runnable watch(Context context, Handler handler) {
        apply(context);
        if (context.getString(R.string.default_keyboard).isEmpty()) return () -> { };
        ContentResolver cr = context.getContentResolver();
        ContentObserver observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                apply(context);
            }
        };
        cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, observer);
        Runnable stop = new Runnable() {
            private boolean stopped;

            @Override
            public void run() {
                if (stopped) return;
                stopped = true;
                handler.removeCallbacks(this);
                cr.unregisterContentObserver(observer);
            }
        };
        handler.postDelayed(stop, WATCH_MS);
        return stop;
    }

    static void apply(Context context) {
        String wanted = context.getString(R.string.default_keyboard);
        if (wanted.isEmpty()) return;
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            ContentResolver cr = context.getContentResolver();
            if (wanted.equals(Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD))) return;
            // A keyboard that is not installed would leave the TV with none; Android falls back by itself then.
            InputMethodManager imm = context.getSystemService(InputMethodManager.class);
            boolean installed = false;
            if (imm != null) {
                for (InputMethodInfo info : imm.getInputMethodList()) {
                    if (info.getId().equals(wanted)) installed = true;
                }
            }
            if (!installed) return;
            String enabled = Settings.Secure.getString(cr, Settings.Secure.ENABLED_INPUT_METHODS);
            boolean listed = false;
            if (enabled != null) {
                for (String entry : enabled.split(":")) {
                    if (entry.split(";")[0].equals(wanted)) listed = true;
                }
            }
            if (!listed) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_INPUT_METHODS,
                        enabled == null || enabled.isEmpty() ? wanted : enabled + ":" + wanted);
            }
            Settings.Secure.putString(cr, Settings.Secure.DEFAULT_INPUT_METHOD, wanted);
        } catch (RuntimeException ignored) {
            // The setting is the system's; a refusal leaves the keyboard as it was.
        }
    }
}
