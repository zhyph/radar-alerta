package br.radar.alerta;

import android.content.Context;
import android.content.SharedPreferences;

/** User preferences, stored in SharedPreferences. */
final class Settings {
    static final String SOURCE_AUTO = "auto";
    static final int[] LEAD_SECONDS = {15, 10, 6}; // early, normal, late

    private final SharedPreferences prefs;

    Settings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** "auto" or the id (host) of a preferred source. */
    String source() { return prefs.getString("source", SOURCE_AUTO); }

    void setSource(String id) { prefs.edit().putString("source", id).apply(); }

    int leadSeconds() { return prefs.getInt("lead", 10); }

    void setLeadSeconds(int seconds) { prefs.edit().putInt("lead", seconds).apply(); }

    /** When true, a camera with a known limit only warns if you are near or over that limit. */
    boolean onlyWhenOver() { return prefs.getBoolean("only_over", false); }

    void setOnlyWhenOver(boolean value) { prefs.edit().putBoolean("only_over", value).apply(); }

    /** Id of the alert sound (see Tone.IDS). */
    String soundId() { return prefs.getString("sound", Tone.DEFAULT); }

    void setSoundId(String id) { prefs.edit().putString("sound", id).apply(); }

    boolean autoStart() { return prefs.getBoolean("auto_start", false); }

    void setAutoStart(boolean on) { prefs.edit().putBoolean("auto_start", on).apply(); }

    boolean legalAccepted() { return prefs.getBoolean("legal_ok", false); }

    void setLegalAccepted() { prefs.edit().putBoolean("legal_ok", true).apply(); }

    boolean kindEnabled(CameraStore.Kind kind) { return prefs.getBoolean("kind_" + kind.name(), true); }

    void setKindEnabled(CameraStore.Kind kind, boolean enabled) {
        prefs.edit().putBoolean("kind_" + kind.name(), enabled).apply();
    }
}
