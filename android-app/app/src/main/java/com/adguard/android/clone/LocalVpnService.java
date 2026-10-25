package com.adguard.android.clone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;

public class LocalVpnService extends VpnService {

    public static final String ACTION_START = "com.adguard.android.clone.START_VPN";
    public static final String ACTION_STOP = "com.adguard.android.clone.STOP_VPN";
    private static final String CHANNEL_ID = "adguard_protection_channel";
    private static final int NOTIFICATION_ID = 4150;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            stopSelf();
            return START_NOT_STICKY;
        } else {
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, buildNotification());
            return START_STICKY;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "AdGuard Protection Status",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows active AdGuard Local VPN and DNS protection status");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Protection is enabled")
            .setContentText("AdGuard DNS (DoH) • 32 filters active • Local VPN")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }
}
