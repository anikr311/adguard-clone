package com.adlocko.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings tile. It can only start the VPN when the system VPN
 * consent was already granted in the app; otherwise it opens AdLocko,
 * where Android shows the consent dialog.
 */
public class AdLockoTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        syncTileState();
    }

    @Override
    public void onClick() {
        super.onClick();
        SharedPreferences prefs = getSharedPreferences(LocalVpnService.PREFS, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(LocalVpnService.KEY_PROTECTION, false);
        if (enabled) {
            Intent stop = new Intent(this, LocalVpnService.class);
            stop.setAction(LocalVpnService.ACTION_STOP);
            startService(stop);
        } else {
            if (VpnService.prepare(this) == null) {
                Intent start = new Intent(this, LocalVpnService.class);
                start.setAction(LocalVpnService.ACTION_START);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(start);
                } else {
                    startService(start);
                }
            } else {
                Intent open = new Intent(this, MainActivity.class);
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivityAndCollapse(open);
                return;
            }
        }
        // Give the service a moment to persist its state, then re-read it.
        syncTileStateDelayed();
    }

    private void syncTileStateDelayed() {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        syncTileState();
                    }
                }, 400);
    }

    private void syncTileState() {
        SharedPreferences prefs = getSharedPreferences(LocalVpnService.PREFS, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(LocalVpnService.KEY_PROTECTION, false);
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel("AdLocko");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.setSubtitle(enabled ? "Protection on" : "Protection off");
            }
            tile.updateTile();
        }
    }
}
