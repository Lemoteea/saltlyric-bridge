# SaltLyric 桥接 (Android)

把**椒盐音乐 (Salt Player)** 的通知栏歌词，通过 BLE 转发到 **ESP32-S3 歌词显示屏** (SaltLyric 设备)。

## 工作流程

```
椒盐音乐(开启"状态栏歌词") → 系统通知 → 本 App 通知监听 → BLE → SaltLyric 屏幕
```

## 使用步骤

1. 安装 APK（从 GitHub Actions 构建产物下载）
2. 打开 App，点 **① 开启通知使用权**，在系统设置里允许本 App 读取通知
3. 点 **② 授予蓝牙权限**（允许"附近设备"权限）
4. 点 **③ 启动桥接**（App 会扫描并连接名为 SaltLyric 的 BLE 设备）
5. 在椒盐音乐中开启：**设置 → 歌词 → 状态栏歌词 / 通知栏歌词**
6. 播放歌曲，歌词自动显示到屏幕

## 构建 APK（GitHub 云端，无需本地环境）

1. 在 GitHub 新建一个仓库（例如 `saltlyric-bridge`，Public/Private 均可）
2. 把本目录所有文件上传到仓库根目录
3. 仓库会自动触发 **Actions → Build APK** 工作流
4. 工作流完成后，进入 **Actions → 最新一次运行 → Artifacts**，下载 `saltlyric-bridge-apk`，解压得到 `app-debug.apk`
5. 把 APK 传到手机安装（首次安装需允许"未知来源"）

> 也可在仓库页面点 **Actions → Build APK → Run workflow** 手动触发。

## 协议

见 ESP32 端 `docs/ble-protocol.md`：
- 服务 `8F70A001-1234-4A1E-9D5E-1B2C3D4E5F60`
- LINE `8F70A002-...`（当前歌词行，UTF-8）
- TITLE `8F70A003-...`（歌名-歌手）
- STATE `8F70A004-...`（1 字节：0 暂停 / 1 播放）

## 注意

- Android 12+ 后台扫描 BLE 受限，**建议保持 App 前台运行**
- 椒盐音乐包名 `com.salt.music`；如你用的是车机版/其他包名，改 `LyricNotificationListener.java` 中的 `SALT_PKG`
- 屏幕设备需已开机并处于广播状态（默认名称 `SaltLyric`）
