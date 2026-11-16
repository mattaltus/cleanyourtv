package au.id.altus.harooms;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Your Home Assistant's rooms as tiles, in the Clean Your TV launcher's look: the wallpaper, a clock-style line with
 * the indoor temperature and the weather, then the rooms in a grid. OK opens a room's panel, where OK
 * switches a light and Left and Right dim it or move a thermostat. Locks are shown, never opened. It reads Home
 * Assistant only while this screen is open.
 */
public class MainActivity extends Activity {
    private static final long REFRESH_MS = 10_000;
    private static final long AREAS_MS = 5 * 60_000;
    private static final int COLUMNS = 5;
    private static final String SETTINGS = "\u0000settings";

    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0xB3FFFFFF;
    private static final int WARM = 0xFFFFC56B;
    private static final int AMBER = 0xFFFFB547;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ReadGate reads = new ReadGate();
    private final Targets targets = new Targets();
    private final Runnable afterCommand = this::load;
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            load();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    private Config config;
    private List<Ha.Entity> entities = new ArrayList<>();
    private List<Rooms.Room> rooms = new ArrayList<>();
    private volatile Map<String, String> areas = Collections.emptyMap();
    private volatile long areasAt;
    private String problem;

    private Typeface light, regular, extraLight;
    private ImageView wallpaper;
    private TextView temp, divider, outside, status;
    private ImageView weatherIcon;
    private ScrollView gridScroll;
    private LinearLayout grid;
    private final Map<String, Tile> tiles = new HashMap<>();
    private List<String> shownRooms = new ArrayList<>();

    private FrameLayout overlay;
    private LinearLayout panelRows;
    private ImageView panelIcon;
    private TextView panelTitle, panelSummary;
    /** The room whose panel is open, SETTINGS for the settings panel, or null. */
    private String open;

    private static final class Tile {
        final View cell;
        final FrameLayout card;
        final ImageView icon;
        final TextView status;

        Tile(View cell, FrameLayout card, ImageView icon, TextView status) {
            this.cell = cell;
            this.card = card;
            this.icon = icon;
            this.status = status;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        light = getResources().getFont(R.font.outfit_light);
        regular = getResources().getFont(R.font.outfit_regular);
        extraLight = getResources().getFont(R.font.outfit_extralight);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0D0F12);
        wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wallpaper, match());
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C000000, 0x40000000, 0x99000000}));
        root.addView(scrim, match());

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(px(48), px(26), px(48), 0);
        root.addView(page, match());

        TextView kicker = text("INSIDE", 11, regular, DIM);
        kicker.setLetterSpacing(0.25f);
        page.addView(kicker);

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        temp = text("", 58, extraLight, WHITE);
        temp.setIncludeFontPadding(false);
        line.addView(temp);
        divider = new TextView(this);
        divider.setBackgroundColor(0x4DFFFFFF);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(px(1), px(30));
        dp.setMarginStart(px(18));
        dp.setMarginEnd(px(18));
        line.addView(divider, dp);
        weatherIcon = new ImageView(this);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(px(22), px(22));
        wp.setMarginEnd(px(10));
        line.addView(weatherIcon, wp);
        outside = text("", 15, regular, 0xD9FFFFFF);
        outside.setLetterSpacing(0.15f);
        outside.setShadowLayer(px(6), 0, px(1), 0x99000000);
        temp.setShadowLayer(px(8), 0, px(1), 0x80000000);
        line.addView(outside);
        page.addView(line);

        status = text("", 13, regular, DIM);
        status.setPadding(0, px(2), 0, 0);
        page.addView(status);

        TextView title = text("Rooms", 30, light, WHITE);
        title.setPadding(px(4), px(8), 0, px(2));
        page.addView(title);

        gridScroll = new ScrollView(this);
        gridScroll.setVerticalScrollBarEnabled(false);
        gridScroll.setClipToPadding(false);
        gridScroll.setPadding(0, px(6), 0, px(16));
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        gridScroll.addView(grid);
        page.addView(gridScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(buildOverlay(), match());
        setContentView(root);
        loadWallpaper();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Config.importPushed(this);
        Config was = config;
        config = Config.load(this);
        if (was != null && (!was.url.equals(config.url) || !was.token.equals(config.token))) forget();
        render();
        if (config.complete()) main.post(refresh);
        else openSettings();
    }

    @Override
    protected void onPause() {
        main.removeCallbacksAndMessages(null);
        reads.reset();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (open != null) closePanel();
        else super.onBackPressed();
    }

    // ---------- data ----------

    private void forget() {
        reads.reset();
        targets.clear();
        main.removeCallbacks(afterCommand);
        problem = null;
        entities = new ArrayList<>();
        rooms = new ArrayList<>();
        areas = Collections.emptyMap();
        areasAt = 0;
    }

    private void submit(Runnable job) {
        try {
            net.execute(job);
        } catch (RejectedExecutionException ignored) {
            // Shut down with the screen.
        }
    }

    private void load() {
        int gen = reads.start();
        if (gen < 0) return;
        int since = targets.epoch();
        Config c = config;
        submit(() -> {
            if (!reads.current(gen)) return;
            HaClient.Reply r = new HaClient(c.url, c.token).get("/api/states");
            List<Ha.Entity> found = null;
            Map<String, String> a = null;
            String why = null;
            if (r.ok()) {
                try {
                    a = readAreas(c);
                    found = Ha.byArea(Ha.parse(r.body, c.glance, c.show, c.hide, c.hideUnavailable), a, c.show);
                } catch (JSONException e) {
                    why = "Home Assistant's answer couldn't be read.";
                }
            } else {
                why = Ha.problem(r.code);
            }
            List<Ha.Entity> f = found;
            Map<String, String> fa = a;
            String w = why;
            main.post(() -> {
                if (isFinishing() || !reads.finish(gen)) return;
                problem = w;
                if (f != null) {
                    entities = f;
                    rooms = Rooms.group(f, fa, config.roomIcons);
                    targets.read(since);
                }
                render();
                if (reads.takeAgain()) load();
            });
        });
    }

    /** Areas from Home Assistant, cached; on the network thread. A failed read keeps the last ones. */
    private Map<String, String> readAreas(Config c) {
        long now = SystemClock.elapsedRealtime();
        if (areasAt != 0 && now - areasAt < AREAS_MS) return areas;
        HaClient.Reply r = new HaClient(c.url, c.token).post("/api/template", Ha.areasRequest());
        areasAt = now;
        if (r.ok()) areas = Ha.areas(r.body);
        return areas;
    }

    private void send(Ha.Call call) {
        if (call == null) return;
        Config c = config;
        int gen = reads.generation();
        targets.sent();
        submit(() -> {
            HaClient.Reply r = new HaClient(c.url, c.token).post(call.path, call.body);
            main.post(() -> {
                if (isFinishing() || !reads.current(gen)) return;
                targets.replied();
                if (!r.ok()) status.setText(Ha.problem(r.code));
                main.removeCallbacks(afterCommand);
                main.postDelayed(afterCommand, 600);
            });
        });
    }

    private void step(Ha.Entity e, int direction) {
        if (e.kind == Ha.Kind.CLIMATE && !Double.isNaN(e.target)) {
            if (e.available()) send(Ha.temperature(e, targets.next(e, direction)));
        } else {
            send(Ha.step(e, direction));
        }
    }

    // ---------- header and tiles ----------

    private void render() {
        if (!config.complete()) {
            status.setText("Add your Home Assistant's address and a long-lived access token in Settings.");
        } else if (problem != null) {
            status.setText(problem);
        } else {
            status.setText(entities.isEmpty() ? "Reading Home Assistant…" : "");
        }
        status.setVisibility(status.getText().length() == 0 ? View.GONE : View.VISIBLE);

        String indoor = Rooms.indoor(entities, rooms, config.indoor);
        temp.setText(indoor == null ? "" : indoor + "°");
        Ha.Glance g = Ha.glance(entities, config.glance);
        Ha.Entity w = g.weather;
        boolean hasWeather = w != null && w.available();
        String out = hasWeather ? ("Outside " + (Double.isNaN(w.current) ? "" : Ha.number(w.current) + "° · ")
                + condition(w.state)).toUpperCase(Locale.ROOT) : "";
        outside.setText(out);
        weatherIcon.setVisibility(hasWeather ? View.VISIBLE : View.GONE);
        if (hasWeather) weatherIcon.setImageResource(weatherDrawable(g.icon));
        divider.setVisibility(indoor != null && hasWeather ? View.VISIBLE : View.GONE);

        List<String> names = new ArrayList<>();
        for (Rooms.Room r : rooms) names.add(r.name);
        names.add(SETTINGS);
        if (!names.equals(shownRooms)) buildGrid(names);
        for (Rooms.Room r : rooms) paint(tiles.get(r.name), r);
        if (open != null) refreshPanel();
    }

    private void buildGrid(List<String> names) {
        View had = getCurrentFocus();
        // Before the first read the grid holds Settings alone: focus then goes to the first room, not back to it.
        Object focusedKey = had == null || open != null || shownRooms.size() <= 1 ? null : had.getTag();
        grid.removeAllViews();
        tiles.clear();
        shownRooms = names;

        int width = getResources().getDisplayMetrics().widthPixels - px(96);
        int cellW = width / COLUMNS;
        int tileW = Math.round(cellW * 0.87f);
        int tileH = Math.round(tileW * 9f / 16f);
        float radius = 16f * getResources().getDisplayMetrics().density * tileW / (180f * getResources().getDisplayMetrics().density);

        LinearLayout row = null;
        for (int i = 0; i < names.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                row.setPadding(0, px(4), 0, px(4));
                grid.addView(row, rp);
            }
            String name = names.get(i);
            Tile t = tile(name, cellW, tileW, tileH, radius);
            tiles.put(name, t);
            row.addView(t.cell, new LinearLayout.LayoutParams(cellW, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        Tile settings = tiles.get(SETTINGS);
        settings.icon.setImageResource(R.drawable.room_settings);
        settings.status.setText(problem != null || !config.complete() ? "Connection" : "");
        idle(settings);

        View keep = focusedKey == null ? null : grid.findViewWithTag(focusedKey);
        if (keep != null) keep.requestFocus();
        else if (open == null && grid.getChildCount() > 0) {
            Tile first = tiles.get(names.get(0));
            if (first != null) first.card.requestFocus();
        }
    }

    private Tile tile(String name, int cellW, int tileW, int tileH, float radius) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClipChildren(false);
        cell.setClipToPadding(false);
        cell.setPadding(0, px(4), 0, px(4));

        FrameLayout card = new FrameLayout(this);
        card.setTag(name);
        card.setFocusable(true);
        card.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radius);
        card.setBackground(bg);
        card.setClipToOutline(true);

        ImageView icon = new ImageView(this);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(Math.round(tileH * 0.36f), Math.round(tileH * 0.36f),
                Gravity.CENTER);
        ip.bottomMargin = Math.round(tileH * 0.16f);
        card.addView(icon, ip);
        if (!SETTINGS.equals(name)) icon.setImageResource(iconDrawable(Rooms.icon(name, config.roomIcons)));

        TextView label = text(SETTINGS.equals(name) ? "Settings" : name, 13, regular, WHITE);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(px(8), 0, px(8), Math.round(tileH * 0.09f));
        card.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        cell.addView(card, new LinearLayout.LayoutParams(tileW, tileH));

        TextView st = text("", 11, regular, WHITE);
        st.setGravity(Gravity.CENTER);
        st.setSingleLine(true);
        st.setEllipsize(TextUtils.TruncateAt.END);
        st.setAlpha(0.7f);
        st.setShadowLayer(px(4), 0, px(1), 0xE6000000);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Math.round(cellW * 0.96f), ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = px(7);
        cell.addView(st, sp);

        card.setOnFocusChangeListener((v, focused) -> {
            v.animate().cancel();
            float s = focused ? 1.10f : 1f;
            v.animate().scaleX(s).scaleY(s)
                    .setDuration(focused ? 260 : 180)
                    .setInterpolator(focused ? new OvershootInterpolator(1.6f) : new DecelerateInterpolator())
                    .withLayer().start();
            st.animate().alpha(focused ? 1f : 0.7f).translationY(focused ? px(3) : 0).setDuration(220).start();
        });
        card.setOnClickListener(v -> {
            if (SETTINGS.equals(name)) openSettings();
            else openRoom(name);
        });
        return new Tile(cell, card, icon, st);
    }

    private void paint(Tile t, Rooms.Room r) {
        if (t == null) return;
        t.status.setText(r.summary());
        GradientDrawable bg = (GradientDrawable) t.card.getBackground();
        switch (r.state()) {
            case WARN:
                bg.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
                bg.setColors(new int[]{0xFF6B4510, 0xFF3D2708});
                t.icon.setColorFilter(AMBER, PorterDuff.Mode.SRC_IN);
                t.status.setTextColor(AMBER);
                break;
            case ACTIVE:
                bg.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
                bg.setColors(new int[]{0xFF3A3022, 0xFF231D16});
                t.icon.setColorFilter(WARM, PorterDuff.Mode.SRC_IN);
                t.status.setTextColor(WHITE);
                break;
            default:
                idle(t);
        }
    }

    private void idle(Tile t) {
        GradientDrawable bg = (GradientDrawable) t.card.getBackground();
        bg.setColors(new int[]{0xFF26262A, 0xFF1C1C1E});
        t.icon.setColorFilter(0xD9FFFFFF, PorterDuff.Mode.SRC_IN);
        t.status.setTextColor(WHITE);
    }

    // ---------- the room panel ----------

    private View buildOverlay() {
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x99000000);
        overlay.setVisibility(View.GONE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF22A2A2E, 0xF2161618});
        glass.setCornerRadius(px(24));
        glass.setStroke(Math.max(1, px(1)), 0x33FFFFFF);
        panel.setBackground(glass);
        panel.setPadding(px(28), px(22), px(28), px(18));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        panelIcon = new ImageView(this);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(px(30), px(30));
        ip.setMarginEnd(px(14));
        head.addView(panelIcon, ip);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        panelTitle = text("", 26, light, WHITE);
        names.addView(panelTitle);
        panelSummary = text("", 13, regular, DIM);
        names.addView(panelSummary);
        head.addView(names);
        panel.addView(head);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0, px(12), 0, 0);
        scroll.setClipToPadding(false);
        panelRows = new LinearLayout(this);
        panelRows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(panelRows);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(px(600), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        pp.topMargin = pp.bottomMargin = px(36);
        overlay.addView(panel, pp);
        return overlay;
    }

    private void openRoom(String name) {
        open = name;
        showOverlay();
    }

    private void openSettings() {
        open = SETTINGS;
        showOverlay();
    }

    private void showOverlay() {
        grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        panelRows.removeAllViews();
        refreshPanel();
        overlay.setAlpha(0f);
        overlay.setVisibility(View.VISIBLE);
        overlay.animate().alpha(1f).setDuration(160).start();
        for (int i = 0; i < panelRows.getChildCount(); i++) {
            if (panelRows.getChildAt(i).isFocusable()) {
                panelRows.getChildAt(i).requestFocus();
                break;
            }
        }
    }

    private void closePanel() {
        String was = open;
        open = null;
        overlay.animate().alpha(0f).setDuration(120).withEndAction(() -> overlay.setVisibility(View.GONE)).start();
        grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        Tile t = tiles.get(was);
        if (t != null) t.card.requestFocus();
        else if (!shownRooms.isEmpty() && tiles.get(shownRooms.get(0)) != null) tiles.get(shownRooms.get(0)).card.requestFocus();
    }

    /** Fills the open panel; focus stays on the same entity's row. */
    private void refreshPanel() {
        View had = panelRows.getFocusedChild();
        Object focusedKey = had == null ? null : had.getTag();
        int focused = panelRows.indexOfChild(had);
        panelRows.removeAllViews();
        if (SETTINGS.equals(open)) {
            panelTitle.setText("Settings");
            panelIcon.setImageResource(R.drawable.room_settings);
            panelIcon.setColorFilter(WHITE, PorterDuff.Mode.SRC_IN);
            panelSummary.setText(problem != null ? problem : config.complete() ? "Connected to " + config.url : "Not connected");
            Row url = add(new Row(this, "Home Assistant address", "For example http://192.168.1.10:8123", false,
                    this::editUrl, null));
            url.setTag("connection:url");
            url.setValue(config.url.isEmpty() ? "Not set" : config.url);
            Row token = add(new Row(this, "Access token", "Stored on this TV only.", false, this::editToken, null));
            token.setTag("connection:token");
            token.setValue(config.token.isEmpty() ? "Not set" : "Set");
        } else {
            Rooms.Room room = null;
            for (Rooms.Room r : rooms) if (r.name.equals(open)) room = r;
            if (room == null) {
                closePanel();
                return;
            }
            panelTitle.setText(room.name);
            panelIcon.setImageResource(iconDrawable(room.icon));
            Rooms.State s = room.state();
            panelIcon.setColorFilter(s == Rooms.State.WARN ? AMBER : s == Rooms.State.ACTIVE ? WARM : WHITE,
                    PorterDuff.Mode.SRC_IN);
            panelSummary.setText(room.summary());
            for (Ha.Entity e : room.entities) {
                Ha.Call press = Ha.press(e);
                boolean steps = Ha.step(e, 1) != null;
                boolean bar = (e.kind == Ha.Kind.TOGGLE || e.kind == Ha.Kind.COVER) && e.brightness >= 0;
                Row r = add(new Row(this, e.name, null, bar, press == null ? null : () -> send(Ha.press(e)),
                        steps ? d -> step(e, d) : null));
                r.setTag(e.id);
                r.setValue(e.value());
                if (bar) r.setBar(e.kind == Ha.Kind.COVER || "on".equals(e.state) ? e.brightness / 100f : 0f);
                if (!e.available()) r.setAlpha(0.5f);
                if (e.kind == Ha.Kind.LOCK) r.setDetail("Shown only: locks aren't opened from the TV.");
            }
        }
        View keep = focusedKey == null ? null : panelRows.findViewWithTag(focusedKey);
        if (keep == null && focused >= 0 && focused < panelRows.getChildCount()) keep = panelRows.getChildAt(focused);
        if (keep != null && keep.isFocusable()) keep.requestFocus();
    }

    private Row add(Row r) {
        panelRows.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    // ---------- connection settings ----------

    private void editUrl() {
        edit("Home Assistant address", config.url.isEmpty() ? "http://" : config.url,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, v -> {
                    String u = Ha.normalizeUrl(v);
                    if (!u.isEmpty() && !Ha.validUrl(u)) {
                        panelSummary.setText("That doesn't look like an address: " + u);
                        return;
                    }
                    Config.saveUrl(this, u);
                    reload();
                });
    }

    private void editToken() {
        edit("Access token", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, v -> {
            if (v.trim().isEmpty()) return;
            Config.saveToken(this, v);
            reload();
        });
    }

    private void reload() {
        config = Config.load(this);
        forget();
        main.removeCallbacks(refresh);
        render();
        if (config.complete()) main.post(refresh);
    }

    private interface Done {
        void done(String value);
    }

    private void edit(String title, String start, int type, Done done) {
        EditText field = new EditText(this);
        field.setInputType(type);
        field.setSingleLine(true);
        field.setText(start);
        field.setSelection(start.length());
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(field)
                .setPositiveButton("Save", (d, w) -> done.done(field.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
        field.requestFocus();
    }

    // ---------- wallpaper ----------

    /**
     * One of the launcher's wallpapers, which install.sh copies into this app's folder (the launcher's own folder
     * can't be read by another app). Decoded off the main thread at screen size, 16-bit like the launcher's.
     */
    private void loadWallpaper() {
        File dir = getExternalFilesDir("wallpapers");
        File[] files = dir == null ? null : dir.listFiles((d, n) -> {
            String l = n.toLowerCase(Locale.ROOT);
            return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp");
        });
        if (files == null || files.length == 0) {
            wallpaper.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF1E2A33, 0xFF101418, 0xFF0B0D10}));
            return;
        }
        File pick = files[new Random().nextInt(files.length)];
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        submit(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(pick.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeFile(pick.getPath(), o);
            if (b == null) return;
            main.post(() -> {
                if (isFinishing()) return;
                wallpaper.setImageBitmap(b);
                wallpaper.setAlpha(0f);
                wallpaper.animate().alpha(1f).setDuration(300).start();
            });
        });
    }

    // ---------- helpers ----------

    private static String condition(String state) {
        if (state == null || state.isEmpty()) return "";
        if (state.equals("partlycloudy")) return "Partly cloudy";
        String s = state.replace('-', ' ').replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static int iconDrawable(String icon) {
        switch (icon) {
            case "sofa": return R.drawable.room_sofa;
            case "kitchen": return R.drawable.room_kitchen;
            case "bed": return R.drawable.room_bed;
            case "desk": return R.drawable.room_desk;
            case "stairs": return R.drawable.room_stairs;
            case "music": return R.drawable.room_music;
            case "tree": return R.drawable.room_tree;
            case "garage": return R.drawable.room_garage;
            case "pool": return R.drawable.room_pool;
            case "door": return R.drawable.room_door;
            default: return R.drawable.room_house;
        }
    }

    private static int weatherDrawable(String icon) {
        switch (icon) {
            case "clear": return R.drawable.ic_weather_clear;
            case "rain": return R.drawable.ic_weather_rain;
            case "snow": return R.drawable.ic_weather_snow;
            case "storm": return R.drawable.ic_weather_storm;
            case "fog": return R.drawable.ic_weather_fog;
            case "night": return R.drawable.ic_weather_night;
            default: return R.drawable.ic_weather_cloudy;
        }
    }

    private TextView text(String s, float sp, Typeface face, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTypeface(face);
        t.setTextColor(color);
        return t;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
