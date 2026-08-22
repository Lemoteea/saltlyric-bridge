package com.saltlyric.bridge;

import android.Manifest;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * 主界面: 权限引导 + 开启桥接服务 + 显示状态。
 * 使用步骤:
 *  1. 授予通知使用权 (读取椒盐音乐歌词的必需权限)
 *  2. 授予蓝牙/定位权限
 *  3. 打开椒盐音乐播放, 屏幕自动显示歌词
 */
public class MainActivity extends AppCompatActivity {
    private static final int REQ_PERMS = 100;

    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        Button btnPerm = findViewById(R.id.btn_notif_perm);
        Button btnBluetooth = findViewById(R.id.btn_bluetooth);
        Button btnStart = findViewById(R.id.btn_start);
        Button btnStop = findViewById(R.id.btn_stop);

        btnPerm.setOnClickListener(v -> openNotifListenerSettings());
        btnBluetooth.setOnClickListener(v -> requestRuntimePermissions());
        btnStart.setOnClickListener(v -> {
            requestRuntimePermissions();
            startServiceCompat();
            Toast.makeText(this, "桥接服务已启动，请打开椒盐音乐播放", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });
        btnStop.setOnClickListener(v -> {
            stopService(new Intent(this, BridgeService.class));
            Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean notifOk = isNotifListenerEnabled();
        boolean bluetoothOk = isBluetoothReady();
        StringBuilder sb = new StringBuilder();
        sb.append("① 通知使用权: ").append(notifOk ? "✔ 已开启" : "✘ 未开启（必需）\n");
        sb.append("② 蓝牙: ").append(bluetoothOk ? "✔ 可用" : "✘ 未开启/无权限\n");
        sb.append("③ 椒盐音乐: 设置→歌词→开启『状态栏歌词』\n");
        sb.append("④ 打开椒盐音乐播放，歌词自动显示到屏幕\n\n");
        sb.append("说明: 手机系统会限制后台扫描，建议保持 App 在前台运行；");
        sb.append("屏幕设备需已开机并在广播 SaltLyric。");
        statusText.setText(sb.toString());
    }

    private void startServiceCompat() {
        Intent i = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private boolean isNotifListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (flat == null || flat.isEmpty()) {
            return false;
        }
        ComponentName cn = new ComponentName(this, LyricNotificationListener.class);
        String target = cn.flattenToString();
        for (String s : flat.split(":")) {
            if (target.equals(s)) {
                return true;
            }
        }
        return false;
    }

    private void openNotifListenerSettings() {
        try {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private boolean isBluetoothReady() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        return adapter != null && adapter.isEnabled() &&
                (Build.VERSION.SDK_INT < 31 || checkSelfPermission(
                        Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED);
    }

    private void requestRuntimePermissions() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= 31) {
            perms = new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.POST_NOTIFICATIONS};
        } else {
            perms = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS};
        }
        boolean need = false;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                need = true;
                break;
            }
        }
        if (need) {
            ActivityCompat.requestPermissions(this, perms, REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            refreshStatus();
        }
    }
}
