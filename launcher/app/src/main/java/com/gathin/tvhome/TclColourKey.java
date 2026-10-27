package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * Local (both TCLs): presses a colour key (red, green, yellow, blue) through TCL's on-screen remote, the virtual key
 * panel of com.tcl.keyhelp. The launcher cannot send a key into another app, but KeyHelp is a system app and sends a
 * real key press when one of its keys is clicked, so the Home button helper opens the panel, clicks the key whose view
 * id ends in "_green" (hbbtv_dvb_green over Freeview, other layouts elsewhere) and closes it again. Freeview Plus opens
 * its guide on green, and ignores the Guide key TCL sends it. Needs flagRetrieveInteractiveWindows and
 * flagReportViewIds on the helper. The "tcl_guide" word does that only while Freeview is running, and opens TCL's own
 * guide otherwise, without the panel.
 */
final class TclColourKey {
    private static final String KEYHELP = "com.tcl.keyhelp";
    private static final String PANEL = "com.tcl.keyhelp.virtualkey.KeysService";
    /** How often, and how long, to look for the panel after asking for it. */
    private static final long POLL_MS = 10;
    private static final long GIVE_UP_MS = 2000;

    private TclColourKey() {
    }

    static boolean isColour(String word) {
        switch (word) {
            case "red": case "green": case "yellow": case "blue":
                return true;
            default:
                return false;
        }
    }

    /** Freeview's guide (green) while Freeview Plus is running on the channel, TCL's Program Guide otherwise. */
    static void guide(AccessibilityService service) {
        if (freeviewRunning()) {
            press(service, "green");
            return;
        }
        try {
            service.startActivity(new Intent("com.tcl.tv.action.EPG").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            // not a TCL with its guide
        }
    }

    /**
     * Whether Freeview's app is up, from TCL's HbbTV state: 1 away from Live TV, 2 in Live TV before an app has
     * loaded (no popup, green does nothing), 3 once it has (popup shown). KeyHelp takes 2, 3 or 4 as HbbTV.
     */
    @SuppressLint("PrivateApi")   // android.os.SystemProperties; sys.tcl.* is log_prop, readable by any app
    static boolean freeviewRunning() {
        try {
            Object state = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class)
                    .invoke(null, "sys.tcl.hbbtv.state", "0");
            return isHbbtvState(String.valueOf(state));
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return false;
        }
    }

    static boolean isHbbtvState(String state) {
        return "3".equals(state);
    }

    static void press(AccessibilityService service, String colour) {
        Handler handler = new Handler(Looper.getMainLooper());
        // KeysService toggles: asked while the panel shows, it closes it.
        boolean showing = findKey(service, colour) != null;
        if (!showing && !start(service, new Intent("com.tcl.keyhelp.SHOW"))) return;
        long until = System.currentTimeMillis() + GIVE_UP_MS;
        handler.post(new Runnable() {
            @Override
            public void run() {
                AccessibilityNodeInfo key = findKey(service, colour);
                if (key == null) {
                    if (System.currentTimeMillis() < until) handler.postDelayed(this, POLL_MS);
                    return;
                }
                key.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                // KeyHelp sends the key within the click, so the panel can go at once. Asking again closes it;
                // com.tcl.quickmenu.dismiss leaves this panel up (until TCL's own 20 s timeout).
                if (!showing) start(service, new Intent("com.tcl.keyhelp.SHOW"));
            }
        });
    }

    private static boolean start(AccessibilityService service, Intent intent) {
        try {
            return service.startService(intent.setClassName(KEYHELP, PANEL)) != null;
        } catch (IllegalStateException | SecurityException unavailable) {
            return false;
        }
    }

    /** The panel's key for this colour, or null while the panel is not on screen. */
    private static AccessibilityNodeInfo findKey(AccessibilityService service, String colour) {
        List<AccessibilityWindowInfo> windows = service.getWindows();
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null || !KEYHELP.contentEquals(root.getPackageName())) continue;
            AccessibilityNodeInfo key = find(root, "_" + colour);
            if (key != null) return key;
        }
        return null;
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, String idSuffix) {
        String id = node.getViewIdResourceName();
        if (id != null && id.endsWith(idSuffix)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = find(child, idSuffix);
            if (found != null) return found;
        }
        return null;
    }
}
