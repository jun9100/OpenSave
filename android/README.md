# OpenSave Android

这是面向 Odin2 / Android 13 的原生客户端。界面使用 Jetpack Compose，默认使用 Storage Access Framework（SAF）；在已经 Root 并由 Magisk 授权的设备上，也可以使用 Root 绝对路径访问系统文件选择器无法进入的目录。

## 已实现

- 选择并持久化授权一个存档目录，重启应用后继续使用。
- 在 SAF 和 Root 绝对路径之间切换；Root 路径经过保护目录和 shell 参数校验。
- 游戏库启动或刷新时自动汇总已授权 SAF 与所有可访问的内置目录；无需逐个切换 GameHub、Eden 或其他模拟器来源。
- 自动选中所有含明确存档位置的游戏条目；候选目录只展示、不默认纳入批量备份，且每个条目保留独立来源身份。
- Root 模式通过 `su` 复制目录到应用私有工作区；恢复使用临时目录发布，并保留目标目录的 UID/GID 和顶层权限。
- 流式复制到应用私有工作区，按文件生成 SHA-256 和稳定 manifest hash。
- 创建 OpenSave 兼容 ZIP 快照，保留本地历史；恢复前自动生成私有安全副本。
- WebDAV Basic Auth、HTTPS、连接测试、目录列举、下载、删除。
- WebDAV 上传使用唯一 `.part` 临时对象，`HEAD` 校验大小后用 `MOVE` 发布到最终 `.zip` 名称；云端列举会过滤临时对象。
- Wi-Fi 条件下通过 WorkManager 每 15 分钟检查一次已授权来源；内容未变化则跳过，失败任务可重试。
- 在授权工作区内识别 Eden/Yuzu/Suyu/Sudachi/Citron 的 NAND TitleID、Ryujinx/Ryubing 的 BIS 布局，以及 PPSSPP、RetroArch、Dolphin、Android 共享目录和 GameHub 痕迹。

扫描只访问内置来源路径和已授权目录，不会遍历整个设备。内置来源包括 GameHub PC、Eden/Yuzu/Suyu/Sudachi/Citron、PPSSPP、RetroArch、Dolphin，以及 Android 外部应用数据目录。对 Eden 等 Switch 模拟器，只识别已发现的 TitleID 位置；自定义到这些路径之外的 NAND 仍需在备份设置中指定。GameHub 云游戏/串流的存档不在 Odin2 本地时无法备份。

## Root 模式

1. 在 Magisk 的“超级用户”页面打开 `OpenSave` 授权；ADB 显示 root 不等于 APK 已获得 `su` 授权。
2. 在“存档来源”切换到“Root 路径”，输入实际绝对路径，例如 `/data/user/0/<游戏包名>/files`、`/storage/emulated/0/Android/data/<包名>/files` 或 Eden 的具体 TitleID 目录。
3. 点击“保存并使用 Root 路径”，再执行“复制到工作区”或“创建快照”。
4. 恢复前会保留当前工作区安全副本，然后通过临时目录替换目标目录，并尝试恢复目标目录原有的所有者和权限。

Root 模式不会允许 `/`、`/data`、`/storage/emulated/0`、系统分区等过宽或受保护路径。请只输入专用存档目录；恢复操作会替换该目录下的内容。工作区不接受符号链接或特殊文件。

## 本地构建

需要 JDK 17、Android SDK Platform 35、Build Tools 35.0.0 和 Android SDK Platform Tools。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`

已覆盖的本地测试包括：

- ZIP 快照往返、空目录、Unicode 文件名和 zip-slip 防护。
- 本地快照索引重载和特殊字符。
- WebDAV 的 `PUT -> HEAD -> MOVE`、Basic Auth、列举、下载和删除。
- Switch TitleID 目录识别。

## Go mobilecore 闸门

桌面端的 `delta`、`snapshot` 和 `switchtitle` 仍保持共用。移动核心的 AAR 可以单独验证，不作为 Android Gradle 主构建的前置条件：

```bash
cd ..
go test ./internal/delta ./internal/snapshot ./internal/switchtitle ./mobilecore

export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/27.0.12077973"
gomobile init
gomobile bind -target=android -androidapi=26 \
  -o /tmp/OpenSaveMobileCore.aar ./mobilecore
```

AAR 暴露 `buildManifestJSON`、`createSnapshot`、`manifestHash`、`restoreSnapshot`、`sha256`、`switchTitleID` 和 `version`。Android 端当前使用 Kotlin 流式实现以避免 APK 构建依赖主机 Go/NDK；两边的 ZIP 和 manifest 合同保持兼容。

## Odin2 / Android 13 实测

设备 IP：`192.168.12.40`。先在 Odin2 打开 **开发者选项 -> 无线调试**，按设备显示的端口执行：

```bash
adb pair 192.168.12.40:<配对端口> <配对码>
adb connect 192.168.12.40:<连接端口>
adb devices -l
adb -s 192.168.12.40:<连接端口> shell getprop ro.build.version.sdk
adb -s 192.168.12.40:<连接端口> install -r app/build/outputs/apk/debug/app-debug.apk
```

如果已经有 USB ADB，也可以：

```bash
adb devices
adb -s <usb-serial> tcpip 5555
adb connect 192.168.12.40:5555
```

验收顺序：

1. 用一次性测试目录授权，不要先使用真实存档。
2. 重启应用，确认 URI 读写权限仍然存在。
3. 复制工作区，创建本地快照，核对文件数、manifest hash 和 ZIP SHA-256。
4. 配置群晖 WebDAV 的 HTTPS 地址，先点“测试连接”，再创建快照并列举云端。
5. 下载一个云端快照，确认弹窗后恢复；核对源目录文件 hash。
6. 修改测试文件，打开 Wi-Fi 自动备份；等待 WorkManager 周期或手动创建快照确认上传。

不要把群晖 WebDAV 的网页端口误填成 WebDAV 端口；地址必须是实际 WebDAV collection URL，例如 `https://nas.example:55006/home/save/OpenSave`。

## 已知边界

- SAF 模式仍受 Android 13 对 `Android/data`、`Android/obb` 和 `/data/user/0/<package>` 的限制；需要访问这些目录时切换到 Root 模式，或使用游戏导出、Shizuku 和游戏自己的云同步。
- Root 模式依赖 Magisk 对 OpenSave 的 `su` 授权；没有授权时应用会显示 Root 不可用，不会假装完成备份。
- 当前没有常驻实时文件监控；自动备份使用前台手动触发和 Wi-Fi WorkManager 检查，周期受 Android 系统调度影响。
- Switch profile/account 的跨设备映射尚未自动化。跨设备恢复请按目标设备的单个 TitleID/profile 目录授权并检查模拟器能否读取。
- 云端只显示完整 `.zip` 快照；上传中断留下的 `.part` 会被清理或隐藏，不会报告为成功。
