package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Local (living room): the Continue watching row above the dock, from the rows other apps publish to the TV
 * provider's Watch Next table. With READ_TV_LISTINGS a sideloaded launcher sees every row marked searchable
 * (docs/watch-next.md). Not for upstream: it needs that permission and network access for the posters.
 */
final class ContinueWatching {
    static final int MAX_ITEMS = 15;

    static final class Item {
        final String pkg;
        final String title;
        /** Thumbnail, then poster art: some apps serve one from a private provider only their own home can read. */
        final String[] images;
        final String intentUri;
        final long engaged;
        /** Played fraction, 0 when unknown. */
        final float progress;

        Item(String pkg, String title, String[] images, String intentUri, long engaged, float progress) {
            this.pkg = pkg;
            this.title = title;
            this.images = images;
            this.intentUri = intentUri;
            this.engaged = engaged;
            this.progress = progress;
        }
    }

    private ContinueWatching() {
    }

    /** Every Watch Next row the launcher can read, best first; empty without the permission. Runs off the main thread. */
    static List<Item> load(ContentResolver resolver) {
        List<Item> rows = new ArrayList<>();
        String[] columns = {
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
        };
        // No selection: without ACCESS_ALL_EPG_DATA the provider refuses one (docs/watch-next.md).
        try (Cursor c = resolver.query(TvContract.WatchNextPrograms.CONTENT_URI, columns, null, null, null)) {
            if (c == null) return rows;
            while (c.moveToNext()) {
                long position = c.isNull(9) ? 0 : c.getLong(9);
                long duration = c.isNull(10) ? 0 : c.getLong(10);
                rows.add(new Item(c.getString(0), title(c.getString(1), c.getString(2), c.getString(3), c.getString(4)),
                        new String[]{c.getString(5), c.getString(6)}, c.getString(7), c.isNull(8) ? 0 : c.getLong(8),
                        duration > 0 ? Math.min(1f, Math.max(0f, position / (float) duration)) : 0f));
            }
        } catch (RuntimeException e) {
            // SecurityException without READ_TV_LISTINGS, or a provider that is not there: no row.
        }
        return pick(rows);
    }

    /** "Show · S2 E5" for an episode, else the title. */
    static String title(String title, String episodeTitle, String season, String episode) {
        String t = title == null ? "" : title;
        if (season != null && episode != null) return t + " · S" + season + " E" + episode;
        if (episodeTitle != null && !episodeTitle.equals(t)) return t.isEmpty() ? episodeTitle : t + " · " + episodeTitle;
        return t;
    }

    /** Rows that can be opened, most recently watched first, without duplicates, at most MAX_ITEMS. */
    static List<Item> pick(List<Item> rows) {
        List<Item> sorted = new ArrayList<>();
        for (Item i : rows) if (i.intentUri != null && !i.intentUri.isEmpty()) sorted.add(i);
        Collections.sort(sorted, (a, b) -> Long.compare(b.engaged, a.engaged));
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Item i : sorted) {
            if (!seen.add(i.pkg + "\n" + i.title)) continue;
            out.add(i);
            if (out.size() == MAX_ITEMS) break;
        }
        return out;
    }

    interface Listener {
        void onContinueFocused(int position);
        void onContinueClick(Item item);
        /** Up or Down out of the row; true when the key was used. */
        boolean onContinueLeave(int keyCode);
    }

    static final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        private final Context ctx;
        private final Posters posters;
        private final Listener listener;
        private final boolean animations;
        private List<Item> items = new ArrayList<>();

        Adapter(Context ctx, Posters posters, Listener listener, boolean animations) {
            this.ctx = ctx;
            this.posters = posters;
            this.listener = listener;
            this.animations = animations;
        }

        void setItems(List<Item> fresh) {
            items = fresh;
            notifyDataSetChanged();
        }

        /** The app's TV banner (or icon) for a card whose images can't be read; null if the app is gone. */
        private Drawable banner(String pkg) {
            try {
                PackageManager pm = ctx.getPackageManager();
                Drawable d = pm.getApplicationBanner(pkg);
                return d != null ? d : pm.getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
        }

        @Override public int getItemCount() {
            return items.size();
        }

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(ctx).inflate(R.layout.item_continue, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull Holder h, int position) {
            Item item = items.get(position);
            h.title.setText(item.title);
            h.card.setContentDescription(item.title);
            h.progress.setVisibility(item.progress > 0 ? View.VISIBLE : View.GONE);
            h.track.setVisibility(item.progress > 0 ? View.VISIBLE : View.GONE);
            h.progress.setPivotX(0f);
            h.progress.setScaleX(item.progress);
            posters.into(h.poster, item.images, banner(item.pkg));
            h.card.setOnClickListener(v -> listener.onContinueClick(item));
            h.card.setOnFocusChangeListener((v, focused) -> {
                float s = focused && animations ? 1.08f : 1f;
                if (animations) v.animate().scaleX(s).scaleY(s).setDuration(150).start();
                h.card.setForeground(focused ? ctx.getDrawable(R.drawable.continue_focus) : null);
                if (focused) listener.onContinueFocused(h.getBindingAdapterPosition());
            });
            h.card.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int at = h.getBindingAdapterPosition();
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        return listener.onContinueLeave(keyCode);
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return at == 0;                         // the row's ends: no wrap, no escape
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return at == getItemCount() - 1;
                    default:
                        return false;
                }
            });
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final View card;
            final ImageView poster;
            final TextView title;
            final View track;
            final View progress;

            Holder(View v) {
                super(v);
                card = v.findViewById(R.id.continueCard);
                poster = v.findViewById(R.id.continuePoster);
                title = v.findViewById(R.id.continueTitle);
                track = v.findViewById(R.id.continueTrack);
                progress = v.findViewById(R.id.continueProgress);
                card.setClipToOutline(true);
            }
        }
    }
}
