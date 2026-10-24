package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ContinueWatchingTest {
    private static ContinueWatching.Item item(String pkg, String title, String intent, long engaged) {
        return new ContinueWatching.Item(pkg, title, new String[]{null, null}, intent, engaged, 0f);
    }

    @Test public void newestFirstWithoutRowsThatCannotOpen() {
        List<ContinueWatching.Item> picked = ContinueWatching.pick(Arrays.asList(
                item("a", "Old", "intent:#Intent;end", 1),
                item("b", "No intent", null, 9),
                item("c", "New", "intent:#Intent;end", 5),
                item("d", "Empty intent", "", 7)));
        assertEquals(2, picked.size());
        assertEquals("New", picked.get(0).title);
        assertEquals("Old", picked.get(1).title);
    }

    @Test public void duplicatesAndOverflowAreDropped() {
        List<ContinueWatching.Item> rows = new ArrayList<>();
        rows.add(item("a", "Same", "intent:#Intent;end", 3));
        rows.add(item("a", "Same", "intent:#Intent;end", 2));
        for (int i = 0; i < 30; i++) rows.add(item("b", "Show " + i, "intent:#Intent;end", 1));
        List<ContinueWatching.Item> picked = ContinueWatching.pick(rows);
        assertEquals(ContinueWatching.MAX_ITEMS, picked.size());
        assertEquals("Same", picked.get(0).title);
        assertEquals("Show 0", picked.get(1).title);
    }

    @Test public void titles() {
        assertEquals("Severance · S2 E5", ContinueWatching.title("Severance", "Trojan's Horse", "2", "5"));
        assertEquals("Show · Pilot", ContinueWatching.title("Show", "Pilot", null, null));
        assertEquals("Film", ContinueWatching.title("Film", null, null, null));
        assertEquals("Pilot", ContinueWatching.title(null, "Pilot", null, null));
    }
}
