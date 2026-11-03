package br.radar.alerta;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The last few alerts, kept so a missed or false alert can be traced to the data or the app. */
final class History {
    private static final int MAX = 30;

    static final class Entry {
        final long time;
        final long cameraId;
        final CameraStore.Kind kind;
        final int distM;
        final int speedKmh;
        final int limit;      // 0 = unknown
        final boolean mph;

        Entry(long time, long cameraId, CameraStore.Kind kind, int distM, int speedKmh, int limit, boolean mph) {
            this.time = time;
            this.cameraId = cameraId;
            this.kind = kind;
            this.distM = distM;
            this.speedKmh = speedKmh;
            this.limit = limit;
            this.mph = mph;
        }
    }

    private History() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("history", Context.MODE_PRIVATE);
    }

    static synchronized void add(Context c, Entry e) {
        try {
            JSONArray old = new JSONArray(prefs(c).getString("list", "[]"));
            JSONArray out = new JSONArray();
            out.put(new JSONObject().put("t", e.time).put("id", e.cameraId).put("k", e.kind.name())
                    .put("d", e.distM).put("s", e.speedKmh).put("l", e.limit).put("m", e.mph));
            for (int i = 0; i < old.length() && out.length() < MAX; i++) out.put(old.get(i));
            prefs(c).edit().putString("list", out.toString()).apply();
        } catch (Exception ignored) {
            // History is a convenience; never let it break an alert.
        }
    }

    /** Newest first. */
    static synchronized List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Entry(o.getLong("t"), o.getLong("id"), CameraStore.Kind.valueOf(o.getString("k")),
                        o.getInt("d"), o.getInt("s"), o.getInt("l"), o.optBoolean("m")));
            }
        } catch (Exception ignored) {
            // Corrupt history is treated as empty.
        }
        return out;
    }

    static synchronized void clear(Context c) {
        prefs(c).edit().remove("list").apply();
    }
}
