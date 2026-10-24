package io.github.mattaltus.speakerleds;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts the LED holder after a reboot or an update, if it is switched on. TCL's wake broadcast also reaches the
 * app while the firmware has it frozen, where its own SCREEN_ON receiver may not run.
 */
public class BootReceiver extends BroadcastReceiver {
    static final String TCL_RESUME = "tcl.sys.intent.action.STR_RESUME_COMPLETED";

    @Override public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (TCL_RESUME.equals(a)) {
            LedHoldService.restart(context);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(a)) {
            if (LedHoldService.isEnabled(context)) {
                context.startForegroundService(new Intent(context, LedHoldService.class).setAction(LedHoldService.ACTION_BOOT));
            }
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            if (LedHoldService.isEnabled(context)) {
                context.startForegroundService(new Intent(context, LedHoldService.class));
            }
        }
    }
}
