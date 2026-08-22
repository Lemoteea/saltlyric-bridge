package com.saltlyric.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * 前台服务: 保持 BLE 连接与歌词转发活跃 (系统不会轻易杀掉)。
 */
public class BridgeService extends Service {
    private static final String CHANNEL_ID = "bridge";
    private static final int NOTIF_ID = 1;

    private BleLyricClient ble;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildNotification("正在等待椒盐音乐歌词"));
        ble = BleLyricClient.get(this);
        ble.setListener(text -> updateNotif(text));
        ble.connect();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 启动时尝试自动扫描连接; 用户手动直连后, 连接已建立, 此处幂等
        if (ble != null) {
            ble.connect();
        }
        return START_STICKY;
    }

    private void updateNotif(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification(text));
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        if (ble != null) {
            ble.disconnect();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "歌词桥接", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("SaltLyric 桥接")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .build();
    }
}
