package ir.baran.vpn;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads remote config (update + banners) from GitHub raw JSON.
 */
public final class RemoteConfig {

    private static final String TAG = "RemoteConfig";
    public static final String CONFIG_URL =
            "https://raw.githubusercontent.com/sourceandroidir/apn/refs/heads/main/config.json";

    private static final String PREFS = "remote_config";
    private static final String KEY_CACHE = "cache_json";
    private static final String KEY_FETCHED_AT = "fetched_at";

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicReference<Snapshot> SNAPSHOT = new AtomicReference<>();

    public static final class Banner {
        public final int id;
        public final String title;
        public final String imageUrl;
        public final String targetUrl;
        public final boolean active;

        public Banner(int id, String title, String imageUrl, String targetUrl, boolean active) {
            this.id = id;
            this.title = title == null ? "" : title;
            this.imageUrl = imageUrl == null ? "" : imageUrl;
            this.targetUrl = targetUrl == null ? "" : targetUrl;
            this.active = active;
        }
    }

    public static final class UpdateInfo {
        public final boolean force;
        public final int latestCode;
        public final String latestName;
        public final String message;
        public final String downloadUrl;

        public UpdateInfo(boolean force, int latestCode, String latestName,
                          String message, String downloadUrl) {
            this.force = force;
            this.latestCode = latestCode;
            this.latestName = latestName == null ? "" : latestName;
            this.message = message == null ? "" : message;
            this.downloadUrl = downloadUrl == null ? "" : downloadUrl;
        }
    }

    public static final class Snapshot {
        public final UpdateInfo update;
        public final List<Banner> banners;
        public final long fetchedAt;

        public Snapshot(UpdateInfo update, List<Banner> banners, long fetchedAt) {
            this.update = update;
            this.banners = banners;
            this.fetchedAt = fetchedAt;
        }
    }

    public interface Callback {
        void onLoaded(Snapshot snapshot);
    }

    private RemoteConfig() {
    }

    public static Snapshot getCached() {
        return SNAPSHOT.get();
    }

    /** Load from memory, then disk, then network. */
    public static void load(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        Snapshot mem = SNAPSHOT.get();
        if (mem != null) {
            if (callback != null) MAIN.post(() -> callback.onLoaded(mem));
            // still refresh in background
            EXEC.execute(() -> fetchAndStore(app, null));
            return;
        }
        Snapshot disk = readDisk(app);
        if (disk != null) {
            SNAPSHOT.set(disk);
            if (callback != null) MAIN.post(() -> callback.onLoaded(disk));
        }
        EXEC.execute(() -> fetchAndStore(app, callback));
    }

    public static Banner pickRandomActiveBanner() {
        Snapshot s = SNAPSHOT.get();
        if (s == null || s.banners == null) return null;
        List<Banner> active = new ArrayList<>();
        for (Banner b : s.banners) {
            if (b.active && b.imageUrl.length() > 0) active.add(b);
        }
        if (active.isEmpty()) return null;
        return active.get(new Random().nextInt(active.size()));
    }

    /**
     * Needs update if remote version_code is higher OR version_name is newer.
     */
    public static boolean needsUpdate(Context context, UpdateInfo info) {
        if (info == null) return false;
        int localCode = BuildConfig.VERSION_CODE;
        String localName = BuildConfig.VERSION_NAME == null ? "0" : BuildConfig.VERSION_NAME;
        boolean codeNewer = info.latestCode > localCode;
        boolean nameNewer = compareVersionName(localName, info.latestName) < 0;
        return codeNewer || nameNewer;
    }

    public static boolean isForceBlocked(Context context) {
        Snapshot s = SNAPSHOT.get();
        if (s == null || s.update == null) return false;
        return s.update.force && needsUpdate(context, s.update);
    }

    /** Compare dotted version names: -1 if a &lt; b, 0 equal, 1 if a &gt; b. */
    public static int compareVersionName(String a, String b) {
        String[] pa = (a == null ? "0" : a).replaceAll("[^0-9.]", "").split("\\.");
        String[] pb = (b == null ? "0" : b).replaceAll("[^0-9.]", "").split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int vb = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static void fetchAndStore(Context app, Callback callback) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(CONFIG_URL).openConnection();
            c.setConnectTimeout(12_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "AetherVPN/" + BuildConfig.VERSION_NAME);
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            String json = sb.toString();
            Snapshot snap = parse(json, System.currentTimeMillis());
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_CACHE, json)
                    .putLong(KEY_FETCHED_AT, snap.fetchedAt)
                    .apply();
            SNAPSHOT.set(snap);
            if (callback != null) MAIN.post(() -> callback.onLoaded(snap));
            Log.i(TAG, "config loaded banners=" + snap.banners.size()
                    + " force=" + snap.update.force
                    + " v" + snap.update.latestName + "(" + snap.update.latestCode + ")");
        } catch (Throwable t) {
            Log.w(TAG, "fetch failed: " + t.getMessage());
            Snapshot disk = SNAPSHOT.get();
            if (disk == null) disk = readDisk(app);
            if (disk != null && callback != null) {
                Snapshot finalDisk = disk;
                MAIN.post(() -> callback.onLoaded(finalDisk));
            }
        }
    }

    private static Snapshot readDisk(Context app) {
        try {
            SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String json = p.getString(KEY_CACHE, null);
            if (json == null || json.isEmpty()) return null;
            return parse(json, p.getLong(KEY_FETCHED_AT, 0L));
        } catch (Exception e) {
            return null;
        }
    }

    private static Snapshot parse(String json, long at) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONObject u = root.optJSONObject("app_update");
        UpdateInfo update = new UpdateInfo(
                u != null && u.optBoolean("is_force_update", false),
                u != null ? u.optInt("latest_version_code", 0) : 0,
                u != null ? u.optString("latest_version_name", "") : "",
                u != null ? u.optString("update_message", "") : "",
                u != null ? u.optString("download_url", "") : ""
        );
        List<Banner> banners = new ArrayList<>();
        JSONArray arr = root.optJSONArray("banners");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.optJSONObject(i);
                if (b == null) continue;
                banners.add(new Banner(
                        b.optInt("id", i),
                        b.optString("title", ""),
                        b.optString("image_url", ""),
                        b.optString("target_url", ""),
                        b.optBoolean("is_active", false)
                ));
            }
        }
        return new Snapshot(update, banners, at);
    }
}
