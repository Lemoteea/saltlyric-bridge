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

    private Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private boolean scanning = false;
    private boolean connected = false;
    private boolean connecting = false;
    private final Runnable connectTimeoutRunnable = this::onConnectTimeout;
    private int connectAttempts = 0;

    private BluetoothGattCharacteristic chLine;
    private BluetoothGattCharacteristic chTitle;
    private BluetoothGattCharacteristic chState;

    /** 全局单例: 整个 App 共享一个 BLE 连接 */
    private static BleLyricClient instance;

    public static synchronized BleLyricClient get(Context context) {
        if (instance == null) {
            instance = new BleLyricClient(context);
        } else {
            // 用最新的 context 更新 (Activity context 比 application context 更利于 BLE 连接)
            instance.updateContext(context);
        }
        return instance;
    }

    private BleLyricClient(Context context) {
        // 注意: 不能用 getApplicationContext() 做 BLE 连接, 会导致 connectGatt 失败 (status 133)
        this.context = context;
    }

    /** 更新为最新传入的 context (MainActivity/Service), 保证连接时用有效 context */
    public synchronized void updateContext(Context context) {
        this.context = context;
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
        if (connecting) {
            return; // 正在连接中, 不重复发起
        }
        if (gatt != null) {
            // 清理残留的 gatt, 避免系统蓝牙缓存导致连接失败 (status 133)
            try {
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
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
            // 空列表 = 扫全部设备 (传 null 在部分设备上会静默失败)
            scanner.startScan(new ArrayList<ScanFilter>(), settings, scanCallback);
            LogStore.add("扫描已启动 (API " + android.os.Build.VERSION.SDK_INT + ")");
        } catch (Exception e) {
            post("扫描启动失败: " + e.getMessage());
            scanning = false;
        }
        // 15s 超时停止扫描
        handler.postDelayed(this::stopScanIfIdle, 15000);
    }

    /** connectGatt 后无回调的兜底超时: 15 秒未连接成功则清理并重新扫描 */
    private void onConnectTimeout() {
        if (connecting) {
            connecting = false;
            if (gatt != null) {
                try {
                    gatt.disconnect();
                    gatt.close();
                } catch (Exception ignored) {
                }
                gatt = null;
            }
            post("连接超时 (15s 无响应)，重新扫描...");
            handler.postDelayed(this::connect, 2000);
        }
    }

    /** 绕过扫描, 直接用 MAC 地址连接 (解决 Android 后台扫描限制) */
    @SuppressLint("MissingPermission")
    public void connectByAddress(String mac) {
        if (connected && gatt != null) {
            post("已连接");
            return;
        }
        if (connecting) {
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
            connecting = true;
            connectAttempts++;
            final BluetoothDevice target = device;
            handler.removeCallbacks(connectTimeoutRunnable);
            handler.postDelayed(connectTimeoutRunnable, 15000);
            handler.post(() -> {
                try {
                    gatt = target.connectGatt(context, false, gattCallback);
                    if (gatt == null) {
                        connecting = false;
                        handler.removeCallbacks(connectTimeoutRunnable);
                        post("connectGatt 返回 null，连接失败");
                    }
                } catch (Exception e) {
                    connecting = false;
                    handler.removeCallbacks(connectTimeoutRunnable);
                    post("连接异常: " + e.getMessage());
                }
            });
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
                // 在扫描回调(非主线程)里直接 connectGatt 会不可靠, 移到主线程
                if (gatt != null || connecting) {
                    return; // 已有连接或正在连接, 去重
                }
                stopScan();
                post("找到 " + DEVICE_NAME + " (匹配)，连接中...");
                final BluetoothDevice target = device;
                connecting = true;
                connectAttempts++;
                // 启动连接超时兜底 (15s)
                handler.removeCallbacks(connectTimeoutRunnable);
                handler.postDelayed(connectTimeoutRunnable, 15000);
                handler.post(() -> {
                    try {
                        gatt = target.connectGatt(context, false, gattCallback);
                        if (gatt == null) {
                            connecting = false;
                            handler.removeCallbacks(connectTimeoutRunnable);
                            post("connectGatt 返回 null，连接失败 (尝试 " + connectAttempts + ")");
                        }
                    } catch (Exception e) {
                        connecting = false;
                        handler.removeCallbacks(connectTimeoutRunnable);
                        post("连接异常: " + e.getMessage());
                    }
                });
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
            handler.removeCallbacks(connectTimeoutRunnable);
            LogStore.add("GATT: status=" + status + " newState=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connecting = false;
                connected = true;
                connectAttempts = 0; // 连接成功, 重置尝试计数
                post("已连接，发现服务...");
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                connecting = false;
                // status 133 = 连接建立失败/被拒; 0 = 正常断开
                if (status != 0) {
                    post("连接失败 status=" + status + "，3 秒后重新扫描...");
                } else {
                    post("连接断开，3 秒后重连...");
                }
                if (gatt != null) {
                    try {
                        gatt.close();
                    } catch (Exception ignored) {
                    }
                    gatt = null;
                }
                // 失败后回到扫描流程 (扫描连接地址类型正确, 优于 getRemoteDevice)
                // 但最多重试 6 次, 避免无限重连刷屏
                if (connectAttempts < 6) {
                    handler.postDelayed(() -> connect(), 3000);
                } else {
                    post("重试次数过多，已停止。请重启蓝牙或重启手机后重试");
                }
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
            return writeOnMain(() -> gatt.writeCharacteristic(chState));
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
            return writeOnMain(() -> gatt.writeCharacteristic(ch));
        } catch (Exception e) {
            return false;
        }
    }

    /** 在主线程序执行写操作 (BLE 写最好在主线程), 异步执行不阻塞调用方 */
    private boolean writeOnMain(final java.util.concurrent.Callable<Boolean> call) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                return call.call();
            } catch (Exception e) {
                return false;
            }
        }
        handler.post(() -> {
            try {
                call.call();
            } catch (Exception ignored) {
            }
        });
        return true;
    }

    @SuppressLint("MissingPermission")
    public void disconnect() {
        stopScan();
        handler.removeCallbacks(connectTimeoutRunnable);
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
        connected = false;
        connecting = false;
        connectAttempts = 0;
    }
}
