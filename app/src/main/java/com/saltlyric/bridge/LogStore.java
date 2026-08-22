package com.saltlyric.bridge;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局调试日志 (单例): 记录链路各环节状态, 供主界面显示。
 */
public class LogStore {
    private static final int MAX = 40;
    private static final StringBuilder sb = new StringBuilder();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public static synchronized void add(String s) {
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append('[').append(FMT.format(new Date())).append("] ").append(s);
        // 裁剪旧行
        int idx = 0, count = 0;
        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) == '\n') {
                count++;
            }
            if (count > MAX) {
                idx = i + 1;
                break;
            }
        }
        if (idx > 0) {
            sb.delete(0, idx);
        }
    }

    public static synchronized String dump() {
        return sb.toString();
    }

    public static synchronized void clear() {
        sb.setLength(0);
    }
}
