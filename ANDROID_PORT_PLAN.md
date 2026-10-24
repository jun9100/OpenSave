# OpenSave Android 实施计划

## 当前目标

OpenSave Android 作为 `game-save` 的原生客户端，与 Windows OpenSave 使用相同的服务根地址和同步密钥。它不再接入 WebDAV，也不依赖某一台开机的 PC 作为服务器。

本计划只改变 Android 客户端；桌面端的既有 WebDAV、P2P 和其他云端提供者保持兼容。GitHub Release 工作流当前仍只构建 Windows，Android Debug APK 在本地构建和真机验证完成前不加入 Release 发布矩阵。

## 已完成

- Kotlin / Jetpack Compose Android 工程，最低 Android 8，目标 Android 15，Odin2 Android 13 为首要设备。
- SAF 持久目录权限、Root 精确路径模式、私有工作区、流式 ZIP 快照、本地历史和恢复前安全副本。
- 安全恢复：路径范围限制、符号链接/特殊文件防护、ZIP-slip 防护，以及仅替换快照清单列出的游戏位置。
- 所有可访问来源的扫描：已授权 SAF、GameHub PC、Eden/Yuzu/Suyu/Sudachi/Citron、PPSSPP、RetroArch、Dolphin、Android 应用数据和用户指定 Root 路径。
- 游戏库：中文界面、按来源展示、`PC` / `Switch` / 各模拟器类别筛选、当前分类全选/取消全选、批量备份、候选位置显式确认。
- 游戏呈现：常用 Switch TitleID 中文名、Steam AppID 中文名解析与封面缓存、平台后备图标。
- 原生 `game-save` 客户端：Bearer 同步密钥、连接测试、按类型列举、上传、下载和删除；同步密钥经 Android Keystore AES-GCM 加密后保存。
- 自动备份：Wi-Fi WorkManager 周期扫描所有可访问预设及授权来源，按 manifest hash 跳过未变化的游戏并重试临时故障。
- 本地验证：`testDebugUnitTest`、`lintDebug` 和 `assembleDebug` 已通过。

## 云端协议

服务根地址后由客户端拼接下列接口：

```text
GET    /v1/opensave/files
PUT    /v1/opensave/files/{type}/{name}
GET    /v1/opensave/files/{type}/{name}
DELETE /v1/opensave/files/{type}/{name}
```

认证头：

```text
Authorization: Bearer <同步密钥>
```

存档类别是协议的一部分，而不是界面标签：

| 类别 | 服务端命名空间 | Android 已识别来源 |
| --- | --- | --- |
| PC 游戏 | `pc` | GameHub 本地 PC、Steam 目录 |
| Switch 游戏 | `switch` | Eden、Yuzu 系、Ryujinx/Ryubing、Switch TitleID |
| PPSSPP | `emulator-ppsspp` | `PSP/SAVEDATA` |
| RetroArch | `emulator-retroarch` | saves / states |
| Dolphin | `emulator-dolphin` | Wii、GC、statesaves |
| Android 游戏 | `emulator-android` | 用户授权的应用数据目录 |
| 其他模拟器 | `emulator-*` | DuckStation、AetherSX2/NetherSX2 与扩展来源 |

`pc`、`switch` 和每种 `emulator-*` 都在 `game-save` 上独立存储，文件名相同也不会互相覆盖。

### Windows 与 Android 互通

- 已识别的单 TitleID Switch 存档以 `switch-<titleid>__main__snap_<timestamp>.zip` 保存，符合 Windows OpenSave 的游戏 ID 和 ZIP 根目录约定。
- Android 多位置、PC 与通用模拟器快照保留 Android 的 `opensave-game.json` 清单，只有同一来源/布局的客户端才能恢复，防止错误覆盖。
- 云端恢复从对应游戏卡片发起，先校验类型、游戏身份和路径，再创建安全副本。自动任务只上传，不会静默覆盖本地游戏存档。

## Android 端产品行为

### 来源与识别

1. 无 Root 时仅读取用户通过 SAF 明确授权的目录。
2. Root 可用时，自动探测存在的内置预设与用户添加的精确路径；缺失或无权限的来源显示原因，不当作空存档。
3. Eden/Yuzu 系按 `nand/user/save/.../<TitleID>`，Ryujinx/Ryubing 按 `bis/user/save/.../<TitleID>` 拆分成独立游戏。
4. GameHub 仅备份实际存在于 Odin2 本地、可读的 PC/模拟器存档；云游戏和串流存档仍由承载它的电脑负责。

### 游戏库与批量操作

1. 刷新后默认勾选所有明确识别的游戏存档，候选路径不默认写入备份。
2. 用户可按 PC、Switch 或具体模拟器类别筛选；全选、取消选择和批量备份仅影响当前可见类别。
3. 每张游戏卡显示来源、类别、存档位置数量、本地历史、云端历史、中文名/原始名和可用封面。
4. 网络封面获取失败时保留离线名称与平台图标，不会阻塞扫描或备份。

### Root 与恢复保护

1. 不允许 `/`、`/data`、`/storage/emulated/0`、系统分区等宽泛路径作为恢复目标。
2. 只从应用私有工作区读取快照，拒绝 zip-slip、符号链接与工作区外路径。
3. 恢复游戏前先备份当前对应位置；无法确认游戏身份、存档类别或布局时拒绝恢复。

## 真机验收计划

当前 Odin2 未通过 ADB 连接，因此下列项目尚未取得真机证据：

| 场景 | 验收证据 |
| --- | --- |
| SAF | 选择测试目录、重启应用后权限仍在、备份/恢复后 hash 一致 |
| Root | Magisk 对 APK 授权、预设来源只读扫描、精确 TitleID 目录安全恢复 |
| Eden | 至少两个已存在 TitleID 显示名称/类别，备份后由 Eden 实际读取恢复的数据 |
| GameHub | 区分本地 PC/模拟器存档、私有/云端不可访问路径的准确提示 |
| 分类与批量 | 切换每个类别后全选只影响该类别，候选条目未经确认不会备份 |
| game-save | 连接、Bearer 认证、类型隔离、上传、列举、下载、恢复、断网重试 |
| Windows 互通 | 选择一个单 TitleID Switch 存档，在 Windows 与 Odin2 双向恢复并通过游戏内读取确认 |

首轮测试必须使用测试目录或现有存档的额外副本，不把恢复操作直接用于唯一存档副本。

## 后续工作

1. 恢复 USB ADB 后安装 Debug APK，在 Odin2 完成上述矩阵并记录实际路径、包名、日志和 hash。
2. 根据真实 Eden 与 GameHub 目录样本补充识别预设；不凭猜测扩大 Root 扫描范围。
3. 补充 Android instrumentation 测试和 CI，在真机基线稳定后再决定是否增加独立 APK 构建工作流。
4. 处理 Switch profile/account 映射和 Android 跨设备同步策略，仍以显式确认恢复和安全副本为前提。
5. 真机回归通过后创建签名 Release APK、更新版本号和发布说明。
