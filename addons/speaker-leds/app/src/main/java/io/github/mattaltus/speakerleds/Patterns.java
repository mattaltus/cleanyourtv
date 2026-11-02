package io.github.mattaltus.speakerleds;

/** The LED service's patterns and the transaction codes of their getters (IFixedStateLedService). */
final class Patterns {
    static final String[] NAMES = {"listening", "thinking", "responding", "timeout", "mute", "error"};
    private static final int[] GETTERS = {1, 2, 3, 4, 5, 7};
    static final int GET_OFF = 6;
    static final int SET_STATE = 8;
    static final String FALLBACK = "mute";

    private Patterns() {}

    static boolean isValid(String name) {
        return index(name) >= 0;
    }

    /** The getter for a pattern name; an unknown name gets the fallback's. */
    static int getter(String name) {
        int i = index(name);
        return GETTERS[i >= 0 ? i : index(FALLBACK)];
    }

    /** The pattern step places after name (negative steps go back), wrapping around. */
    static String step(String name, int step) {
        int i = Math.max(index(name), 0);
        int n = NAMES.length;
        return NAMES[((i + step) % n + n) % n];
    }

    /**
     * Whether the LEDs show the pattern, as Google's voice app had them on stock firmware: lit (its "mute") while
     * TCL's far-field mic is off, dark while it is on. micOn is null when the TV doesn't say; then always lit.
     */
    static boolean lit(boolean followMic, Boolean micOn) {
        return !followMic || !Boolean.TRUE.equals(micOn);
    }

    private static int index(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
        return -1;
    }
}
