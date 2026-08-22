package com.saltlyric.bridge;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * 通知监听服务:
 * 读取椒盐音乐 (com.salt.music) 通知栏歌词, 转发到 SaltLyric 屏幕。
 *
 * 椒盐音乐需在设置中开启"状态栏歌词/通知栏歌词", 歌词会出现在通知的
 * title (歌名-歌手) 与 text (当前歌词) 字段。本服务监听通知变化,
 * 提取歌词后经 BLE 写入屏幕。仅处理变化的歌词, 避免重复写。
 */
public class LyricNotificationListener extends NotificationListenerService {
    private static final String TAG = "LyricNotif";
    /** 椒盐音乐包名 (Google Play / 酷安 / 官网同包名) */
    private static final String SALT_PKG = "com.salt.music";

    private BleLyricClient ble;

    // 去重: 只有内容变化才转发
    private String lastTitle = "";
    private String lastLine = "";
    private int lastState = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        ble = BleLyricClient.get(this);
    }

    @Override
    public void onDestroy() {
        if (ble != null) {
            ble.disconnect();
        }
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !SALT_PKG.equals(sbn.getPackageName())) {
            return;
        }
        Notification notif = sbn.getNotification();
        if (notif == null) {
            return;
        }
        Bundle extras = notif.extras;
        if (extras == null) {
            return;
        }

        // 提取 title / text
        CharSequence titleCs = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textCs = extras.getCharSequence(Notification.EXTRA_TEXT);
        String title = titleCs == null ? "" : titleCs.toString().trim();
        String text = textCs == null ? "" : textCs.toString().trim();

        // 有些版本歌词在 big text 里
        if (text.isEmpty()) {
            CharSequence big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (big != null) {
                text = big.toString().trim();
            }
        }

        // 播放状态: 从通知 actions 推断 (有暂停按钮 => 播放中)
        int playing = inferPlaying(notif);

        LogStore.add("通知: title='" + title + "' text='" + text + "'");

        // 确保已连接 (通知到达时才开始连, 避免空跑)
        if (ble != null) {
            ble.connect();
        }

        // 转发 (仅变化时)
        if (ble != null) {
            if (!title.equals(lastTitle) && !title.isEmpty()) {
                LogStore.add("写标题: " + title);
                boolean ok = ble.writeTitle(title);
                LogStore.add(ok ? "标题OK" : "标题写失败(未连接?)");
                lastTitle = title;
            }
            if (!text.equals(lastLine) && !text.isEmpty()) {
                LogStore.add("写歌词: " + text);
                boolean ok = ble.writeLine(text);
                LogStore.add(ok ? "歌词OK" : "歌词写失败(未连接?)");
                lastLine = text;
            }
            if (playing != lastState) {
                LogStore.add("写状态: " + playing);
                ble.writeState(playing);
                lastState = playing;
            }
        }
    }

    /** 从通知 actions 推断播放状态: 存在 ic_media_pause => 播放中 */
    private int inferPlaying(Notification notif) {
        try {
            android.app.Notification.Action[] actions = notif.actions;
            if (actions != null) {
                for (android.app.Notification.Action a : actions) {
                    if (a.icon == android.R.drawable.ic_media_pause) {
                        return 1;
                    }
                    if (a.icon == android.R.drawable.ic_media_play) {
                        return 0;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return -1; // 无法判断, 不更新
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // 椒盐音乐停止播放时清空歌词? 保持现状, 让屏幕停留最后一帧
    }
}
