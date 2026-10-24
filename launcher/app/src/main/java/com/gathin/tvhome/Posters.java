package com.gathin.tvhome;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local (living room): loads the Continue watching posters (http, https, content and android.resource URIs) on two
 * background threads, decoded at card size in RGB_565 and kept in a small memory cache, for a 1 GB TV.
 */
final class Posters {
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private final ContentResolver resolver;
    private final int width;
    private final int height;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Images that would not load: not tried again until Home is recreated (main thread only). */
    private final Set<String> failed = new HashSet<>();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    Posters(ContentResolver resolver, int width, int height) {
        this.resolver = resolver;
        this.width = width;
        this.height = height;
    }

    /**
     * Shows the first of these images that loads (nulls are skipped), else the fallback (the app's banner); a view
     * rebound to another item meanwhile keeps the newer one. Cached under the first non-null URI.
     */
    void into(ImageView view, String[] uris, Drawable fallback) {
        String key = null;
        for (String u : uris) {
            if (u != null) {
                key = u;
                break;
            }
        }
        view.setTag(key);
        if (key == null || failed.contains(key)) {
            view.setImageDrawable(fallback);
            return;
        }
        Bitmap cached = cache.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        String tag = key;
        pool.execute(() -> {
            Bitmap b = null;
            for (String u : uris) {
                if (u != null && (b = decode(u)) != null) break;
            }
            Bitmap loaded = b;
            main.post(() -> {
                if (loaded != null) cache.put(tag, loaded);
                else failed.add(tag);
                if (!tag.equals(view.getTag())) return;
                if (loaded != null) view.setImageBitmap(loaded);
                else view.setImageDrawable(fallback);
            });
        });
    }

    void shutdown() {
        pool.shutdownNow();
    }

    private Bitmap decode(String uri) {
        byte[] bytes = read(uri);
        if (bytes == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width && o.outHeight / (sample * 2) >= height) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
    }

    /** Scheme and host only: poster links can carry access tokens (Plex). */
    private static String where(String uri) {
        Uri u = Uri.parse(uri);
        return u.getScheme() + "://" + u.getHost();
    }

    private byte[] read(String uri) {
        String scheme = Uri.parse(uri).getScheme();
        HttpURLConnection http = null;
        try {
            InputStream in;
            if ("http".equals(scheme) || "https".equals(scheme)) {
                http = (HttpURLConnection) new URL(uri).openConnection();
                http.setConnectTimeout(5000);
                http.setReadTimeout(8000);
                int code = http.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    Log.w("Posters", "HTTP " + code + " from " + where(uri));
                    return null;
                }
                in = http.getInputStream();
            } else {
                in = resolver.openInputStream(Uri.parse(uri));
            }
            if (in == null) return null;
            try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = s.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES) return null;
                }
                return out.toByteArray();
            }
        } catch (IOException | RuntimeException e) {
            Log.w("Posters", e.getClass().getSimpleName() + " from " + where(uri) + ": " + e.getMessage());
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }
}
