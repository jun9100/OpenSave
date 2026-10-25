# OpenSave Android

这是面向 Odin2 / Android 13 的原生 OpenSave 客户端。界面使用 Jetpack Compose；默认通过 Storage Access Framework (SAF) 访问用户授权的目录，在已经 Root 并由 Magisk 授权的设备上也可以读取受限的模拟器或 GameHub 本地路径。

## 已实现

- 自动扫描每一个已授权 SAF 目录，以及 Root 可访问的 GameHub PC、Eden/Yuzu/Suyu/Sudachi/Citron、Winlator、DroidDeck、共享 PC 目录、已安装 Android 应用的持久数据目录和共享存储；共享存储会补充发现未预设的模拟器目录。首次默认全部启用，可在“备份与设置”逐项取消，手动扫描和 Wi-Fi 自动备份会共用该选择。
- 把已识别的游戏存档按 `PC 游戏`、`Switch 游戏`、PPSSPP、RetroArch、Dolphin、DuckStation、AetherSX2/NetherSX2、Citra/Lime3DS、melonDS、Vita3K、Mupen64Plus、DraStic、Lemuroid、Android 游戏和其他模拟器分类；筛选、全选和批量备份只作用于当前分类。
- 识别 Eden/Yuzu 系 NAND 与 Ryujinx/Ryubing BIS 中的 Switch TitleID；常见 Switch 游戏显示中文名并缓存 TitleID 封面，Steam AppID 游戏可解析中文名并缓存封面。没有可用封面时显示对应平台图标。
- 创建流式 ZIP 快照、本地历史、SHA-256、稳定 manifest hash、恢复前安全副本，以及恢复时的路径/ZIP-slip 防护。
- 通过 `game-save` 原生 API 上传、列举、下载和删除云端快照。同步密钥使用 Android Keystore 的 AES-GCM 密文保存，应用数据不会参与 Android 云备份或设备迁移。
- 单一 Switch TitleID 快照上传成功后会发布与 Windows OpenSave 兼容的当前存档公告。只有远端公告明确携带 ZIP 内实际存档文件的最新修改时间、其同步链延续本机已知快照且本机内容未变化时，才允许 Windows 自动拉取，或由 Android Root 自动备份任务安全恢复。旧客户端公告、分叉、未知基线、本机已变化和未完成上传一律列为待确认，绝不静默覆盖。
- Wi-Fi 条件下，WorkManager 每 15 分钟检查所有当前可访问的预设来源和用户授权来源；未变化的游戏跳过，临时网络失败会重试。

扫描只访问预设路径和已经授权的目录；Root 下的共享存储扫描会自动排除 OpenSave 测试目录、Eden 迁移归档、ROM、密钥、固件、着色器缓存、日志和游戏安装文件。Root 下会检查已识别 Android 游戏的内部持久数据，并从共享存储发现外部数据；非 Root 下只能扫描用户逐个授予的目录。GameHub 云游戏、串流游戏的存档若实际在远端电脑或服务器，必须由对应电脑上的 OpenSave 备份。

## game-save 云端

Android 和新版 Windows OpenSave 使用同一个 `game-save` 服务根地址和同步密钥，不配置 WebDAV 地址、用户名或密码。例如服务根地址是：

```text
https://game-save.example:8788
```

客户端会以 `Authorization: Bearer <同步密钥>` 调用：

```text
GET    /v1/opensave/files
PUT    /v1/opensave/files/{type}/{name}
GET    /v1/opensave/files/{type}/{name}
DELETE /v1/opensave/files/{type}/{name}
```

云端类型隔离如下：

- `pc`：PC 游戏。
- `switch`：Switch 游戏。
- `emulator-*`：可扩展的模拟器类别，例如 `emulator-ppsspp`、`emulator-retroarch` 和 `emulator-dolphin`。

单一、已识别的 Root Switch TitleID 使用桌面兼容名称 `switch-<titleid>__main__snap_<timestamp>.zip`，并在上传后写入同类型的当前存档公告，因此同一 TitleID 可以在 Windows 与 Android 间浏览和恢复。公告同时记录 ZIP 内实际存档文件的最新修改时间和有限的同步链；较旧的镜像或迁移副本即使晚上传也不会替换当前进度。缺少该内容时间的旧公告仍可查看和手动恢复，但不会触发自动恢复。PC、多位置模拟器和 SAF 快照保留 Android 的位置清单，避免把不同设备的目录布局静默覆盖。

在“备份与设置”保存服务根地址和同步密钥后，可测试连接、列举云端快照。游戏卡片会显示该游戏的本地和已列举云端历史；恢复仍需要用户确认，并且只替换对应游戏的已验证存档位置。

## Root 模式

1. 在 Magisk 的超级用户页面授予 `OpenSave` 的 `su` 权限；ADB 可 root 不代表 APK 已获得权限。
2. 在“备份与设置”切换到“Root 路径”，选择一个专用存档目录，例如某个 Eden TitleID 目录或特定游戏目录。
3. 应用会扫描所有存在的已知 Root 来源；手动路径只作为额外来源，不会关闭其他已发现来源。
4. 恢复前应用创建私有安全快照，随后通过临时目录替换目标存档内容，并尽量保留 Root 目标目录的 UID/GID 与顶层权限。

Root 模式拒绝 `/`、`/data`、`/storage/emulated/0`、系统分区等过宽路径。恢复只应指向专用存档目录；工作区不会接受符号链接或特殊文件。

## 非 Root 模式

1. 在“备份与设置”的“系统授权”中点“添加并授权目录”，可重复添加多个模拟器或游戏目录。
2. OpenSave 保存每个目录的持久权限，并在手动扫描和 Wi-Fi 自动备份时一起使用。
3. 只读授权可用于备份；恢复需要该目录具有读写授权。
4. Android 13 通常禁止普通应用访问其他应用的 `/data/user/0/<包名>`。若系统文件选择器不让选择某个 `Android/data` 目录，只有该应用自身提供文档树、导出功能或 Root 才能读取它。

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
- 快照索引重载、中文/特殊字符、存档类型和云端游戏 ID 持久化。
- 原生 `game-save` 的 Bearer 认证、按类型上传、列举、下载和删除。
- Switch TitleID、PC 和各模拟器类别的分类、跨桌面兼容命名和当前存档公告 ZIP 兼容性。

## Odin2 验收

使用一次性测试目录或可恢复副本进行首轮真机测试：

```bash
adb devices -l
adb -s <usb-serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

验收顺序：

1. 确认 Magisk 授权和 SAF 持久权限均可用。
2. 扫描后核对 Eden、GameHub、PC 与模拟器来源的跳过/发现结果。
3. 检查分类、中文游戏名与可获取的 Steam 封面。
4. 用测试存档完成单游戏备份、当前分类批量备份、本地恢复和文件 hash 校验。
5. 用 `game-save` 服务完成连接测试、上传、云端列举、下载和确认恢复。
6. 修改测试存档后，验证 Wi-Fi 自动备份任务的变化检测与重试行为。

## 已知边界

- SAF 受 Android 13 对 `Android/data`、`Android/obb` 和其他应用私有目录的限制；这些目录需要 Root 或系统实际授予的权限。
- Switch 的 profile/account 映射尚未自动化。跨设备恢复时只同步单个 TitleID 内容，目标设备仍需存在可识别的 TitleID 目录。
- 当前没有常驻实时文件监控。后台自动备份受 Android 的 WorkManager 调度与网络条件约束。
- Android 端不会自动把云端快照静默写回游戏目录；用户必须从对应游戏历史中选择并确认恢复。
