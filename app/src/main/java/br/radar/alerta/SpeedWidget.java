package br.radar.alerta;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Home-screen widget: current speed and the limit of the next camera. */
public final class SpeedWidget extends AppWidgetProvider {
    private static long lastPush;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        push(context, RadarEngine.running(), true);
    }

    /** Called by the engine on each fix (throttled) and when it stops. */
    static void push(Context context, RadarEngine engine, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastPush < 2_000) return;
        lastPush = now;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, SpeedWidget.class));
        if (ids.length == 0) return;

        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_speed);
        if (engine == null || engine.lastFix() == null) {
            v.setTextViewText(R.id.widget_speed, "--");
            v.setTextViewText(R.id.widget_limit, context.getString(R.string.title_off));
            v.setTextColor(R.id.widget_speed, Ui.TEXT);
        } else {
            int speed = (int) Math.round(engine.lastSpeed() * 3.6);
            CameraStore.Camera next = engine.nextCamera();
            String limit = "";
            boolean over = false;
            if (next != null) {
                int kmh = (int) Math.round(next.maxSpeed * (next.mph ? 1.609344 : 1.0));
                int dist = (int) engine.nextDistM() / 10 * 10;
                limit = (next.kind == CameraStore.Kind.RED_LIGHT ? context.getString(R.string.type_red_short)
                        : kmh > 0 ? context.getString(R.string.widget_limit, kmh)
                        : context.getString(R.string.limit_unknown)) + " · " + dist + " m";
                over = kmh > 0 && speed > kmh + 2;
            }
            v.setTextViewText(R.id.widget_speed, String.valueOf(speed));
            v.setTextViewText(R.id.widget_limit, limit);
            v.setTextColor(R.id.widget_speed, over ? Ui.FAIL : Ui.TEXT);
        }
        v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 2,
                new Intent(context, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        manager.updateAppWidget(ids, v);
    }
}
