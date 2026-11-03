package br.radar.alerta;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings tile to start and stop alerts. Stopping is direct; starting opens the app, which
 * starts the service while visible (Android does not let a tile start a location service itself).
 */
public final class StartTile extends TileService {
    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        if (RadarEngine.running() != null) {
            stopService(new Intent(this, AlertService.class));
            RadarEngine.stop();
            refresh();
            return;
        }
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_START, true);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(open);
        }
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = RadarEngine.running() != null;
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.app_name));
        tile.setSubtitle(getString(on ? R.string.title_on : R.string.title_off));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_notification));
        tile.updateTile();
    }
}
