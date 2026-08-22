package com.saltlyric.bridge;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BLE 客户端: 扫描 "SaltLyric" 设备, 连接并写入歌词特征。
 * 协议见 docs/ble-protocol.md:
 *   服务 8F70A001-1234-4A1E-9D5E-1B2C3D4E5F60
 *   LINE 8F70A002 / TITLE 8F70A003 / STATE 8F70A004 / LRC 8F70A005 / SEEK 8F70A006
 */
public class BleLyricClient {
    private static final String TAG = "BleLyricClient";
    public static final String DEVICE_NAME = "SaltLyric";

    public static final UUID SERVICE_UUID = UUID.fromString("8F70A001-1234-4A1E-9D5E-1B2C3D4E5F60");
    public static final UUID CHAR_LINE = UUID.fromString("8F70A002-1234-4A1E-9D5E-1B2C3D4E5F60");
    public static final UUID CHAR_TITLE = UUID.fromString("8F70A003-1234-4A1E-9D5E-1B2C3D4E5F60");
    public static final UUID CHAR_STATE = UUID.fromString("8F70A004-1234-4A1E-9D5E-1B2C3D4E5F60");
    public static final UUID CHAR_LRC = UUID.fromString("8F70A005-1234-4A1E-9D5E-1B2C3D4E5F60");
    public static final UUID CHAR_SEEK = UUID.fromString("8F70A006-1234-4A1E-9D5E-1B2C3D4E5F60");

    public interface Listener {
        void onState(String text); // 状态文字 (UI/日志)
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private boolean scanning = false;
    private boolean connected = false;

    private BluetoothGattCharacteristic chLine;
    private BluetoothGattCharacteristic chTitle;
    private BluetoothGattCharacteristic chState;

    /** 全局单例: 整个 App 共享一个 BLE 连接 */
    private static BleLyricClient instance;

    public static synchronized BleLyricClient get(Context context) {
        if (instance == null) {
            instance = new BleLyricClient(context);
        }
        return instance;
    }

    private BleLyricClient(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    private void post(String s) {
        LogStore.add(s);
        if (listener != null) {
            listener.onState(s);
        }
    }

    /** 开始扫描 "SaltLyric" 并自动连接 (幂等) */
    @SuppressLint("MissingPermission")
    public void connect() {
        if (connected && gatt != null) {
            post("已连接");
            return;
        }
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            post("蓝牙未开启");
            return;
        }
        // 权限诊断: 检查 BLUETOOTH_SCAN 是否授予
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            int scanPerm = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN);
            int connPerm = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT);
            LogStore.add("权限: SCAN=" + (scanPerm == 0 ? "已授予" : "未授予") +
                    " CONNECT=" + (connPerm == 0 ? "已授予" : "未授予"));
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            post("无 BLE 扫描器");
            return;
        }
        if (scanning) {
            return;
        }
        scanning = true;
        post("扫描中... (寻找 " + DEVICE_NAME + ")");
        // 注意: 设备名放在扫描响应里, 不能用 ScanFilter 过滤名字,
        // 只能扫全部设备再逐个比对 (BluetoothDevice.getName() 能读到扫描响应的名字)
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(null, settings, scanCallback);
        } catch (Exception e) {
            post("扫描启动失败: " + e.getMessage());
            scanning = false;
        }
        // 15s 超时停止扫描
        handler.postDelayed(this::stopScanIfIdle, 15000);
    }

    /** 绕过扫描, 直接用 MAC 地址连接 (解决 Android 后台扫描限制) */
    @SuppressLint("MissingPermission")
    public void connectByAddress(String mac) {
        if (connected && gatt != null) {
            post("已连接");
            return;
        }
        if (gatt != null) {
            try {
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
        stopScan();
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            post("蓝牙未开启");
            return;
        }
        try {
            BluetoothDevice device = adapter.getRemoteDevice(mac.toUpperCase().trim());
            post("按 MAC 直连 " + mac + " ...");
            gatt = device.connectGatt(context, false, gattCallback);
        } catch (Exception e) {
            post("MAC 地址无效: " + e.getMessage());
        }
    }

    private void stopScanIfIdle() {
        if (scanning) {
            stopScan();
            if (!connected) {
                post("未发现 " + DEVICE_NAME + "，请确认设备在广播");
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (scanner != null && scanning) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
            scanning = false;
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        // 记录每个扫到的设备地址, 避免日志刷屏 (每个设备只记一次)
        private final java.util.Set<String> seen = new java.util.HashSet<>();

        @SuppressLint("MissingPermission")
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();
            String addr = device.getAddress();

            // 全视图日志: 记录扫到的每个设备 (名字 + 服务UUID)
            if (!seen.contains(addr)) {
                seen.add(addr);
                try {
                    android.bluetooth.le.ScanRecord record = result.getScanRecord();
                    String uuidStr = "";
                    if (record != null) {
                        List<android.os.ParcelUuid> uuids = record.getServiceUuids();
                        if (uuids != null && !uuids.isEmpty()) {
                            for (android.os.ParcelUuid u : uuids) {
                                uuidStr += u.getUuid().toString() + " ";
                            }
                        }
                    }
                    LogStore.add("扫到: " + (name == null ? "(无名)" : name) +
                            " [" + addr + "] UUID:" + (uuidStr.isEmpty() ? "(无)" : uuidStr));
                } catch (Exception ignored) {
                }
            }

            boolean matched = false;

            // 首选: 广播包里的服务 UUID (最可靠, 不依赖名字)
            try {
                android.bluetooth.le.ScanRecord record = result.getScanRecord();
                if (record != null) {
                    List<android.os.ParcelUuid> uuids = record.getServiceUuids();
                    if (uuids != null) {
                        for (android.os.ParcelUuid u : uuids) {
                            if (SERVICE_UUID.equals(u.getUuid())) {
                                matched = true;
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            // 兜底: 名字匹配
            if (!matched && name != null && name.equalsIgnoreCase(DEVICE_NAME)) {
                matched = true;
            }

            if (matched) {
                stopScan();
                post("找到 " + DEVICE_NAME + " (匹配)，连接中...");
                gatt = device.connectGatt(context, false, gattCallback);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            post("扫描失败 code=" + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                post("已连接，发现服务...");
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                post("连接断开，3 秒后重连...");
                gatt = null;
                handler.postDelayed(() -> connect(), 3000);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                post("服务发现失败 status=" + status);
                return;
            }
            BluetoothGattService svc = g.getService(SERVICE_UUID);
            if (svc == null) {
                post("未找到服务 (固件版本不符?)");
                return;
            }
            chLine = svc.getCharacteristic(CHAR_LINE);
            chTitle = svc.getCharacteristic(CHAR_TITLE);
            chState = svc.getCharacteristic(CHAR_STATE);
            connected = true;
            post("已连接 " + DEVICE_NAME + "，等待歌词...");
        }
    };

    /** 写入当前行歌词 (UTF-8, 不带换行) */
    public boolean writeLine(String text) {
        return writeChar(chLine, text);
    }

    public boolean writeTitle(String text) {
        return writeChar(chTitle, text);
    }

    /** 播放状态: 0=暂停 1=播放 */
    public boolean writeState(int playing) {
        if (chState == null || !connected) {
            return false;
        }
        try {
            chState.setValue(new byte[]{(byte) (playing != 0 ? 1 : 0)});
            return gatt.writeCharacteristic(chState);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean writeChar(BluetoothGattCharacteristic ch, String text) {
        if (ch == null || !connected) {
            return false;
        }
        try {
            ch.setValue(text == null ? "" : text);
            return gatt.writeCharacteristic(ch);
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    public void disconnect() {
        stopScan();
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
        connected = false;
    }
}
