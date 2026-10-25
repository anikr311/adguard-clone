package com.adguard.android.clone;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class AdguardTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        syncTileState();
    }

    @Override
    public void onClick() {
        super.onClick();
        SharedPreferences prefs = getSharedPreferences("adguard_prefs", MODE_PRIVATE);
        boolean nextState = !prefs.getBoolean("protection_enabled", true);
        prefs.edit().putBoolean("protection_enabled", nextState).apply();

        Intent vpnIntent = new Intent(this, LocalVpnService.class);
        vpnIntent.setAction(nextState ? LocalVpnService.ACTION_START : LocalVpnService.ACTION_STOP);
        if (nextState && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(vpnIntent);
        } else {
            startService(vpnIntent);
        }
        syncTileState();
    }

    private void syncTileState() {
        SharedPreferences prefs = getSharedPreferences("adguard_prefs", MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("protection_enabled", true);
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel("AdGuard");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.setSubtitle(enabled ? "Protected" : "Paused");
            }
            tile.updateTile();
        }
    }
}
