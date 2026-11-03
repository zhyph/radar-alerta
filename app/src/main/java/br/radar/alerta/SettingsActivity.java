package br.radar.alerta;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public final class SettingsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Settings settings;
    private AlertSound testSound;
    private LinearLayout content;
    private TextView cacheInfo;

    // One row per source choice; index 0 is "Automatic".
    private final List<RadioButton> sourceRadios = new ArrayList<>();
    private final List<String> sourceIds = new ArrayList<>();
    private final List<TextView> sourceStatus = new ArrayList<>();
    private final List<RadioButton> timingRadios = new ArrayList<>();
    private final List<RadioButton> soundRadios = new ArrayList<>();
    private static final int[] SOUND_LABELS = {R.string.sound_siren, R.string.sound_klaxon,
            R.string.sound_chirp, R.string.sound_pulse, R.string.sound_doorbell, R.string.sound_ping};

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateSourceStatus();
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new Settings(this);
        getWindow().setBackgroundDrawable(new ColorDrawable(Ui.BG));
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 24);
        content.setPadding(pad, Ui.dp(this, 8), pad, pad);

        // Header: back (48 dp target) and title.
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setImageTintList(ColorStateList.valueOf(Ui.TEXT));
        back.setContentDescription(getString(R.string.back));
        back.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null));
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
        bl.leftMargin = -Ui.dp(this, 12);
        bar.addView(back, bl);
        bar.addView(Ui.text(this, getString(R.string.settings), 22, Ui.TEXT, true));
        content.addView(bar);

        buildSourceSection();
        buildTimingSection();
        buildSoundSection();
        buildRulesSection();
        buildTypesSection();
        buildToolsSection();
        buildBackgroundSection();

        TextView attribution = Ui.text(this, getString(R.string.attribution), 12, Ui.TEXT_2, false);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = Ui.dp(this, 24);
        content.addView(attribution, ap);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(scroll);
    }

    // ---- Sections -----------------------------------------------------------------------

    private void section(String name) {
        TextView t = Ui.text(this, name, 14, Ui.AMBER, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 28);
        lp.bottomMargin = Ui.dp(this, 4);
        content.addView(t, lp);
    }

    /** A tappable row with a title, optional subtitle and a trailing control. */
    private LinearLayout row(String title, String subtitle, View trailing, View.OnClickListener click, TextView[] subOut) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, 56));
        row.setClickable(true);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, null));
        row.setOnClickListener(click);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(0, Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        texts.addView(Ui.text(this, title, 16, Ui.TEXT, false));
        if (subtitle != null) {
            TextView sub = Ui.text(this, subtitle, 14, Ui.TEXT_2, false);
            texts.addView(sub);
            if (subOut != null) subOut[0] = sub;
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(trailing);
        content.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private RadioButton radio() {
        RadioButton r = new RadioButton(this);
        r.setClickable(false); // the whole row handles the tap
        r.setButtonTintList(new ColorStateList(
                new int[][] {{android.R.attr.state_checked}, {}}, new int[] {Ui.AMBER, Ui.TEXT_2}));
        return r;
    }

    private void buildSourceSection() {
        section(getString(R.string.sec_source));
        List<CameraStore.Source> sources = CameraStore.newSources();
        addSourceRow(Settings.SOURCE_AUTO, getString(R.string.source_auto), getString(R.string.source_auto_sub));
        for (CameraStore.Source s : sources) {
            addSourceRow(s.id, s.id, getString(R.string.source_pref_sub));
        }
        updateSourceChecks();
    }

    private void addSourceRow(String id, String title, String subtitle) {
        RadioButton r = radio();
        TextView[] sub = new TextView[1];
        row(title, subtitle, r, v -> {
            settings.setSource(id);
            updateSourceChecks();
        }, sub);
        sourceRadios.add(r);
        sourceIds.add(id);
        sourceStatus.add(sub[0]);
    }

    private void updateSourceChecks() {
        String current = settings.source();
        for (int i = 0; i < sourceRadios.size(); i++) sourceRadios.get(i).setChecked(sourceIds.get(i).equals(current));
    }

    /** Shows each source's live health under its name (only while the alert service runs). */
    private void updateSourceStatus() {
        RadarEngine engine = RadarEngine.running();
        long now = System.currentTimeMillis();
        for (int i = 0; i < sourceIds.size(); i++) {
            if (sourceIds.get(i).equals(Settings.SOURCE_AUTO)) continue;
            String text = getString(R.string.source_pref_sub);
            if (engine != null) {
                for (CameraStore.Source s : engine.store().sources()) {
                    if (!s.id.equals(sourceIds.get(i))) continue;
                    if (s.coolingDown(now)) text = getString(R.string.src_cooling);
                    else if (s.consecutiveFails > 0) text = getString(R.string.src_failed);
                    else if (s.lastOkMs > 0) text = getString(R.string.src_ok, Ui.ago(this, s.lastOkMs));
                    else text = getString(R.string.src_idle);
                }
            }
            sourceStatus.get(i).setText(text);
        }
    }

    private void buildTimingSection() {
        section(getString(R.string.sec_timing));
        int[] labels = {R.string.timing_early, R.string.timing_normal, R.string.timing_late};
        for (int i = 0; i < labels.length; i++) {
            int seconds = Settings.LEAD_SECONDS[i];
            RadioButton r = radio();
            row(getString(labels[i]), null, r, v -> {
                settings.setLeadSeconds(seconds);
                updateTimingChecks();
            }, null);
            timingRadios.add(r);
        }
        updateTimingChecks();
    }

    private void updateTimingChecks() {
        for (int i = 0; i < timingRadios.size(); i++) {
            timingRadios.get(i).setChecked(Settings.LEAD_SECONDS[i] == settings.leadSeconds());
        }
    }

    private void buildSoundSection() {
        section(getString(R.string.sec_sound));
        for (int i = 0; i < Tone.IDS.length; i++) {
            String id = Tone.IDS[i];
            RadioButton r = radio();
            row(getString(SOUND_LABELS[i]), null, r, v -> {
                settings.setSoundId(id);
                updateSoundChecks();
                if (testSound == null) testSound = new AlertSound(this);
                testSound.play(id);   // preview on selection
            }, null);
            soundRadios.add(r);
        }
        TextView hint = Ui.text(this, getString(R.string.sound_hint), 14, Ui.TEXT_2, false);
        content.addView(hint);
        updateSoundChecks();
    }

    private void updateSoundChecks() {
        for (int i = 0; i < soundRadios.size(); i++) {
            soundRadios.get(i).setChecked(Tone.IDS[i].equals(settings.soundId()));
        }
    }

    private void buildRulesSection() {
        section(getString(R.string.sec_rules));
        Switch sw = new Switch(this);
        sw.setClickable(false);
        sw.setChecked(settings.onlyWhenOver());
        sw.setThumbTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}},
                new int[] {Ui.AMBER, Ui.TEXT_2}));
        sw.setTrackTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}},
                new int[] {0x66FFB300, Ui.OUTLINE}));
        row(getString(R.string.opt_over_title), getString(R.string.opt_over_sub), sw, v -> {
            boolean on = !sw.isChecked();
            sw.setChecked(on);
            settings.setOnlyWhenOver(on);
        }, null);

        Switch auto = new Switch(this);
        auto.setClickable(false);
        auto.setChecked(settings.autoStart());
        auto.setThumbTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}},
                new int[] {Ui.AMBER, Ui.TEXT_2}));
        auto.setTrackTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}},
                new int[] {0x66FFB300, Ui.OUTLINE}));
        row(getString(R.string.opt_auto_title), getString(R.string.opt_auto_sub), auto, v -> {
            boolean on = !auto.isChecked();
            auto.setChecked(on);
            settings.setAutoStart(on);
        }, null);
    }

    private void buildTypesSection() {
        section(getString(R.string.sec_types));
        addTypeRow(CameraStore.Kind.SPEED, R.string.type_speed);
        addTypeRow(CameraStore.Kind.RED_LIGHT, R.string.type_red);
        addTypeRow(CameraStore.Kind.AVERAGE, R.string.type_avg);
    }

    private void addTypeRow(CameraStore.Kind kind, int label) {
        Switch sw = new Switch(this);
        sw.setClickable(false);
        sw.setChecked(settings.kindEnabled(kind));
        sw.setThumbTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}},
                new int[] {Ui.AMBER, Ui.TEXT_2}));
        sw.setTrackTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}},
                new int[] {0x66FFB300, Ui.OUTLINE}));
        row(getString(label), null, sw, v -> {
            boolean on = !sw.isChecked();
            sw.setChecked(on);
            settings.setKindEnabled(kind, on);
        }, null);
    }

    private void buildToolsSection() {
        section(getString(R.string.sec_tools));
        Button test = Ui.button(this, getString(R.string.test_siren), false);
        test.setOnClickListener(v -> {
            if (testSound == null) testSound = new AlertSound(this);
            testSound.play(settings.soundId());
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 56));
        lp.topMargin = Ui.dp(this, 8);
        content.addView(test, lp);

        Button clear = Ui.button(this, getString(R.string.clear_cache), false);
        clear.setOnClickListener(v -> {
            RadarEngine engine = RadarEngine.running();
            if (engine != null) engine.store().clear();
            else CameraStore.deleteTiles(this);
            updateCacheInfo();
            Toast.makeText(this, R.string.cache_cleared, Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 56));
        cp.topMargin = Ui.dp(this, 12);
        content.addView(clear, cp);

        Button download = Ui.button(this, getString(R.string.download_area), false);
        download.setOnClickListener(v -> downloadArea(download));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 56));
        dp.topMargin = Ui.dp(this, 12);
        content.addView(download, dp);

        Button report = Ui.button(this, getString(R.string.report_button), false);
        report.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setMessage(R.string.report_confirm)
                .setPositiveButton(R.string.report_send, (d, w) -> reportCamera(report))
                .setNegativeButton(android.R.string.cancel, null).show());
        content.addView(report, new LinearLayout.LayoutParams(dp));

        cacheInfo = Ui.text(this, "", 14, Ui.TEXT_2, false);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.topMargin = Ui.dp(this, 8);
        content.addView(cacheInfo, ip);
        updateCacheInfo();
    }

    /** Fetches the mirror's camera data for a 3x3 degree block around the current position. */
    private void downloadArea(Button button) {
        RadarEngine engine = RadarEngine.running();
        android.location.Location fix = engine != null && engine.lastFix() != null ? engine.lastFix() : Ui.lastKnown(this);
        if (fix == null) {
            Toast.makeText(this, R.string.download_nolocation, Toast.LENGTH_LONG).show();
            return;
        }
        button.setEnabled(false);
        button.setText(R.string.download_busy);
        CameraStore store = engine != null ? engine.store() : new CameraStore(getApplicationContext());
        new Thread(() -> {
            String message;
            try {
                int n = store.downloadArea(fix.getLatitude(), fix.getLongitude(), 1);
                message = getString(R.string.download_done, n);
            } catch (Exception e) {
                message = getString(R.string.download_fail);
            }
            String finalMessage = message;
            runOnUiThread(() -> {
                Toast.makeText(this, finalMessage, Toast.LENGTH_LONG).show();
                button.setEnabled(true);
                button.setText(R.string.download_area);
                updateCacheInfo();
            });
        }, "radar-download").start();
    }

    /** Files an anonymous OpenStreetMap note at the current position so a mapper can fix the data. */
    private void reportCamera(Button button) {
        RadarEngine engine = RadarEngine.running();
        android.location.Location fix = engine != null && engine.lastFix() != null ? engine.lastFix() : Ui.lastKnown(this);
        if (fix == null) {
            Toast.makeText(this, R.string.download_nolocation, Toast.LENGTH_LONG).show();
            return;
        }
        button.setEnabled(false);
        new Thread(() -> {
            boolean ok = CameraStore.postNote(fix.getLatitude(), fix.getLongitude(),
                    "Speed camera missing or wrong near here (reported from the Radar Alert app at the reporter's position).");
            runOnUiThread(() -> {
                Toast.makeText(this, ok ? R.string.report_done : R.string.report_fail, Toast.LENGTH_LONG).show();
                button.setEnabled(true);
            });
        }, "radar-report").start();
    }

    private TextView batteryStatus;
    private Button batteryButton;

    private void buildBackgroundSection() {
        section(getString(R.string.sec_background));
        content.addView(Ui.text(this, getString(R.string.battery_title), 16, Ui.TEXT, false));
        batteryStatus = Ui.text(this, getString(R.string.battery_allowed), 14, Ui.TEXT_2, false);
        content.addView(batteryStatus);
        batteryButton = Ui.button(this, getString(R.string.battery_button), false);
        batteryButton.setOnClickListener(v -> startActivity(new android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:" + getPackageName()))));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 56));
        bp.topMargin = Ui.dp(this, 8);
        content.addView(batteryButton, bp);
        updateBattery();
    }

    private void updateBattery() {
        boolean ok = getSystemService(android.os.PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        batteryStatus.setVisibility(ok ? View.VISIBLE : View.GONE);
        batteryButton.setVisibility(ok ? View.GONE : View.VISIBLE);
    }

    private void updateCacheInfo() {
        long[] stats = CameraStore.cacheStats(this);
        cacheInfo.setText(getString(R.string.cache_info, (int) stats[0], Ui.bytes(stats[1])));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateCacheInfo();
        updateBattery();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (testSound != null) testSound.release();
        super.onDestroy();
    }
}
