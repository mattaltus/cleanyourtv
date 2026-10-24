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

    @Test public void plezyRatingKey() {
        assertEquals("58068", ContinueWatching.plexRatingKey("plezy://play?content_id=plexs_0123456789abcdef0123456789abcdef01234567_58068"));
        assertEquals("43716", ContinueWatching.plexRatingKey("intent:#Intent;S.x=1;end?content_id=a_b_43716&t=2"));
        assertEquals(null, ContinueWatching.plexRatingKey("plezy://play?content_id=nonumber"));
        assertEquals(null, ContinueWatching.plexRatingKey(null));
    }

    @Test public void plezyImagesComeFromTheServerFirst() {
        String[] images = ContinueWatching.images("com.edde746.plezy", "plezy://play?content_id=x_y_58068", "http://plex.local:32400", "content://t", null);
        assertEquals(3, images.length);
        assertEquals("http://plex.local:32400/photo/:/transcode?width=352&height=198&minSize=1&upscale=1&url=%2Flibrary%2Fmetadata%2F58068%2Fthumb", images[0]);
        assertEquals("content://t", images[1]);
        assertEquals(2, ContinueWatching.images("com.apple.atve.androidtv.appletv", "plezy://play?content_id=x_y_1", "http://s", "t", "p").length);
        assertEquals(2, ContinueWatching.images("com.edde746.plezy", "plezy://play?content_id=x_y_1", "", "t", "p").length);
    }

    @Test public void titles() {
        assertEquals("Severance · S2 E5", ContinueWatching.title("Severance", "Trojan's Horse", "2", "5"));
        assertEquals("Show · Pilot", ContinueWatching.title("Show", "Pilot", null, null));
        assertEquals("Film", ContinueWatching.title("Film", null, null, null));
        assertEquals("Pilot", ContinueWatching.title(null, "Pilot", null, null));
    }
}
