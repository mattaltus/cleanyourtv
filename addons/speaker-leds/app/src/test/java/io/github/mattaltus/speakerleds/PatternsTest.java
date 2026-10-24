package io.github.mattaltus.speakerleds;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PatternsTest {
    @Test public void gettersMatchTheServiceTransactions() {
        assertEquals(1, Patterns.getter("listening"));
        assertEquals(5, Patterns.getter("mute"));
        assertEquals(7, Patterns.getter("error"));
    }

    @Test public void unknownNamesFallBackToMute() {
        assertFalse(Patterns.isValid("off"));
        assertFalse(Patterns.isValid(null));
        assertTrue(Patterns.isValid(Patterns.FALLBACK));
        assertEquals(5, Patterns.getter("off"));
        assertEquals(5, Patterns.getter(null));
    }

    @Test public void stepWrapsBothWays() {
        assertEquals("error", Patterns.step("mute", 1));
        assertEquals("listening", Patterns.step("error", 1));
        assertEquals("error", Patterns.step("listening", -1));
        assertEquals("thinking", Patterns.step("bogus", 1));
    }

    @Test public void nudgeIsAlwaysADifferentPattern() {
        for (String name : Patterns.NAMES) {
            assertTrue(Patterns.isValid(Patterns.nudge(name)));
            assertFalse(name.equals(Patterns.nudge(name)));
        }
    }
}
