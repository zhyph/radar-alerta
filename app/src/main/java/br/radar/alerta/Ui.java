package br.radar.alerta;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

import java.time.Instant;

/** Shared look: a dark, high-contrast palette for glancing at in a car, and small helpers. */
final class Ui {
    static final int BG = 0xFF0E1116;
    static final int SURFACE = 0xFF171B22;
    static final int OUTLINE = 0xFF2B323C;
    static final int TEXT = 0xFFF2F4F7;
    static final int TEXT_2 = 0xFFA9B0BA;
    static final int AMBER = 0xFFFFB300;
    static final int OK = 0xFF4ADE80;
    static final int FAIL = 0xFFFF6B5E;

    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, CharSequence t, float sp, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    /** Pill button, 56 dp tall. primary = amber fill with dark text; otherwise a tonal surface. */
    static Button button(Context c, CharSequence label, boolean primary) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? BG : TEXT);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 56));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(c, 28));
        shape.setColor(primary ? AMBER : SURFACE);
        if (!primary) shape.setStroke(dp(c, 1), OUTLINE);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null));
        b.setStateListAnimator(null);
        return b;
    }

    static String ago(Context c, long epochMs) {
        long sec = Math.max(0, (System.currentTimeMillis() - epochMs) / 1000);
        if (sec < 60) return c.getString(R.string.ago_now);
        if (sec < 3600) return c.getString(R.string.ago_min, (int) (sec / 60));
        if (sec < 48 * 3600) return c.getString(R.string.ago_hour, (int) (sec / 3600));
        return c.getString(R.string.ago_day, (int) (sec / 86400));
    }

    /** Relative age of an ISO-8601 timestamp such as 2026-11-03T00:44:50Z; "" if unparsable. */
    static String agoIso(Context c, String iso) {
        try {
            return ago(c, Instant.parse(iso).toEpochMilli());
        } catch (Exception e) {
            return "";
        }
    }

    /** Most recent location any enabled provider knows, or null (also when permission is missing). */
    static android.location.Location lastKnown(Context c) {
        if (c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        android.location.LocationManager lm = c.getSystemService(android.location.LocationManager.class);
        android.location.Location best = null;
        for (String provider : lm.getProviders(true)) {
            android.location.Location l = lm.getLastKnownLocation(provider);
            if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
        }
        return best;
    }

    static String bytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
    }
}
