package br.radar.alerta;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private CameraStore viewStore;   // used for browsing the map while alerts are off
    private MapView map;
    private ImageButton recenter, gear;
    private TextView title, headline, subline;
    private Button toggle;
    private LinearLayout panel;
    private boolean followInit;   // set once the map has locked on to the live GPS fix
    private boolean centeredOnce;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Ui.BG));
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(Ui.SURFACE);
        viewStore = new CameraStore(this);

        map = new MapView(this);
        map.setCameraProvider(() -> store().cameras());
        map.setListener(new MapView.Listener() {
            @Override
            public void onViewportSettled(double s, double w, double n, double e, double zoom) {
                // Load camera data only for the area on screen, and only when zoomed in enough.
                if (zoom >= 10) store().ensureArea(s, w, n, e);
                map.refresh();
            }

            @Override
            public void onFollowChanged(boolean following) {
                recenter.setVisibility(!following && map.hasUser() ? View.VISIBLE : View.GONE);
            }
        });

        // Overlay controls on the map.
        FrameLayout mapArea = new FrameLayout(this);
        mapArea.addView(map, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        title = Ui.text(this, getString(R.string.app_name), 16, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        GradientDrawable titleBg = new GradientDrawable();
        titleBg.setCornerRadius(Ui.dp(this, 24));
        titleBg.setColor(0xDD0E1116);
        title.setBackground(titleBg);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 48),
                Gravity.TOP | Gravity.START);
        tp.setMargins(Ui.dp(this, 16), Ui.dp(this, 8), 0, 0);
        mapArea.addView(title, tp);

        gear = circleButton(R.drawable.ic_settings, getString(R.string.settings));
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48),
                Gravity.TOP | Gravity.END);
        gp.setMargins(0, Ui.dp(this, 8), Ui.dp(this, 16), 0);
        mapArea.addView(gear, gp);

        recenter = circleButton(R.drawable.ic_my_location, getString(R.string.recenter));
        recenter.setVisibility(View.GONE);
        recenter.setOnClickListener(v -> map.setFollowing(true));
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48),
                Gravity.TOP | Gravity.END);
        rp.setMargins(0, Ui.dp(this, 68), Ui.dp(this, 16), 0);
        mapArea.addView(recenter, rp);

        // Bottom panel: status and the one primary action.
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 24);
        panel.setPadding(pad, Ui.dp(this, 20), pad, pad);
        GradientDrawable panelBg = new GradientDrawable();
        float r = Ui.dp(this, 24);
        panelBg.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        panelBg.setColor(Ui.SURFACE);
        panel.setBackground(panelBg);

        headline = Ui.text(this, "", 24, Ui.TEXT, true);
        panel.addView(headline);
        subline = Ui.text(this, "", 16, Ui.TEXT_2, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.dp(this, 4);
        panel.addView(subline, sp);
        toggle = Ui.button(this, "", true);
        toggle.setOnClickListener(v -> onToggle());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 56));
        bp.topMargin = Ui.dp(this, 20);
        panel.addView(toggle, bp);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(mapArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Edge-to-edge: the map runs under the status bar, the controls and panel clear the system bars.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            shift(title, bars.top);
            shift(gear, bars.top);
            shift(recenter, bars.top);
            root.setPadding(bars.left, 0, bars.right, 0);
            panel.setPadding(panel.getPaddingLeft(), panel.getPaddingTop(), panel.getPaddingRight(),
                    Ui.dp(this, 24) + bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(root);

        Settings settings = new Settings(this);
        if (!settings.legalAccepted()) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(R.string.legal_title)
                    .setMessage(R.string.legal_body)
                    .setCancelable(false)
                    .setPositiveButton(R.string.legal_ok, (d, w) -> settings.setLegalAccepted())
                    .show();
        }
    }

    private final java.util.Map<View, Integer> baseTop = new java.util.HashMap<>();

    /** Moves an overlay control below the status bar, remembering its original top margin. */
    private void shift(View v, int inset) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        Integer base = baseTop.get(v);
        if (base == null) {
            base = lp.topMargin;
            baseTop.put(v, base);
        }
        lp.topMargin = base + inset;
        v.setLayoutParams(lp);
    }

    private ImageButton circleButton(int drawable, String description) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(drawable);
        b.setImageTintList(ColorStateList.valueOf(Ui.TEXT));
        b.setContentDescription(description);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xDD0E1116);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null));
        return b;
    }

    private CameraStore store() {
        RadarEngine engine = RadarEngine.running();
        return engine != null ? engine.store() : viewStore;
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
        // Opening the app (e.g. from a car-Bluetooth routine) can start alerts, once per launch.
        if (!autoTried) {
            autoTried = true;
            Settings st = new Settings(this);
            if (st.autoStart() && st.legalAccepted() && RadarEngine.running() == null
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                startAlerts();
            }
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private boolean autoTried;

    private void onToggle() {
        if (RadarEngine.running() != null) {
            stopService(new Intent(this, AlertService.class));
            handler.postDelayed(this::update, 300);
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS}, REQUEST_PERMISSIONS);
            return;
        }
        startAlerts();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != REQUEST_PERMISSIONS) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startAlerts();
        } else {
            Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show();
        }
    }

    private void startAlerts() {
        // Started while the app is visible, so location access continues with the screen off.
        startForegroundService(new Intent(this, AlertService.class));
        handler.postDelayed(this::update, 300);
    }

    private void update() {
        RadarEngine engine = RadarEngine.running();
        boolean on = engine != null;

        // Map position: the live fix while alerts run, otherwise the last known location once.
        Location fix = on ? engine.lastFix() : null;
        if (fix != null) {
            float bearing = engine.lastBearing();
            map.setUser(fix.getLatitude(), fix.getLongitude(), bearing, engine.lastSpeed());
            map.setAlertRange(engine.alertRangeM());
            if (!followInit) {
                // First live fix of this session: zoom in and follow the user.
                followInit = true;
                centeredOnce = true;
                map.centerOn(fix.getLatitude(), fix.getLongitude(), 14);
                map.setFollowing(true);
            }
        } else if (!on) {
            map.setAlertRange(0);
            if (!centeredOnce) {
                Location last = lastKnown();
                if (last != null) {
                    centeredOnce = true;
                    map.setUser(last.getLatitude(), last.getLongitude(), Float.NaN, 0f);
                    map.centerOn(last.getLatitude(), last.getLongitude(), 13);
                }
            }
        }
        if (!on) followInit = false;
        // Show the recenter control whenever the map has drifted away from the user.
        recenter.setVisibility(on && !map.isFollowing() && map.hasUser() ? View.VISIBLE : View.GONE);
        map.refresh();

        headline.setText(on ? R.string.title_on : R.string.title_off);
        List<CameraStore.Camera> cams = store().cameras();
        String age = Ui.agoIso(this, store().osmBase());
        subline.setTextColor(Ui.TEXT_2);
        if (!on) {
            subline.setText(R.string.sub_off);
        } else if (store().currentAreaMissing()) {
            // Never leave the user thinking they are protected when there is no data here.
            subline.setText(R.string.no_data);
            subline.setTextColor(Ui.FAIL);
        } else if (cams.isEmpty()) {
            subline.setText(R.string.sub_loading);
        } else {
            subline.setText(age.isEmpty() ? getString(R.string.sub_cameras_nodata, cams.size())
                    : getString(R.string.sub_cameras, cams.size(), age));
        }
        toggle.setText(on ? R.string.stop : R.string.start);
        styleToggle(on);
    }

    private boolean toggleIsPrimary = true;

    private void styleToggle(boolean on) {
        if (toggleIsPrimary == !on) return;
        toggleIsPrimary = !on;
        Button fresh = Ui.button(this, toggle.getText(), !on);
        toggle.setBackground(fresh.getBackground());
        toggle.setTextColor(fresh.getTextColors());
    }

    private Location lastKnown() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        LocationManager lm = getSystemService(LocationManager.class);
        Location best = null;
        for (String provider : lm.getProviders(true)) {
            Location l = lm.getLastKnownLocation(provider);
            if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
        }
        return best;
    }
}
