# SaveVault Android Google Play 合规代码普查

> 2026-11-10 由 DeepSeek（dsh）只读调研、主会话抽查。以下均为代码/文档中看到的事实，不包含合规结论；结论由主会话判断。行号来自当前工作区。未联网、未运行构建或测试。

## 1. titledb 数据

| 项目 | 事实 | 证据 |
|---|---|---|
| 文件 | `android/app/src/main/assets/switch-titles.tsv`，2,662,051 字节（约 2.5 MiB），24,252 行 | `wc -c -l` 输出 `24252 2662051`；`ls -lh` 显示 `2.5M` |
| 列格式 | 每行 3 列，TAB 分隔：`TitleID<TAB>name<TAB>icon hash`；无表头、无注释，第一行即数据 | 首行 `0100000000010000	超级玛利欧 奥德赛	ad4d...`；`awk -F '\t'` 检查 24,252 行全部 `NF==3`、TitleID 均为 16 位大写十六进制、icon 均为 64 位小写十六进制且无空值 |
| 来源（脚本） | 由 `android/scripts/build_switch_titles.py` 从 `blawar/titledb`（MIT）生成；输入 `US.en.json`、`HK.zh.json`、`JP.ja.json`；只保留以 `000` 结尾的基础应用；名称优先香港 eShop 并用 OpenCC 转简体，其次英文，再次日文；icon 为 eShop 图标 URL 中的哈希 | `android/scripts/build_switch_titles.py:2-20`、`:24-50`、`:53-70` |
| 来源（README） | README 明确写出来源与第三列用途 | `android/README.md:119` |
| 代码读取 | `OfflineSwitchTitles.load(context)` 从 assets 打开并逐行解析；`name()`/`icon()` 按大写 TitleID 查询 | `android/app/src/main/java/com/jun9100/opensave/GameNames.kt:190-226` |
| 加载时机 | `SourceViewModel` 初始化时在 IO 线程加载，完成后 `GameNames.touch()` 并刷新 UI | `android/app/src/main/java/com/jun9100/opensave/SourceViewModel.kt:335-340` |
| 用途 | 仅用于“显示名称”和“eShop 封面哈希”；检测本身是纯文件系统规则，不依赖该表 | `GameCatalog.kt:3-6` 明确 “detection remains filesystem-only”；`SaveSourceDetector.kt:227-255` 仅按目录/TitleID 识别 |
| 调用点 1：名称 | `GameCatalog.present()` 读取 `OfflineSwitchTitles.name(titleId)`，作为 `originalName`/`localizedName` 的回退 | `GameCatalog.kt:133-140` |
| 调用点 2：封面哈希 | `GameCatalog.present()` 读取 `OfflineSwitchTitles.icon(titleId)` 填入 `switchIconHash` | `GameCatalog.kt:145-146` |
| 调用点 3：封面 URL | `GamePresentation.coverUrls` 用 `switchIconHash` 拼 eShop URL；无哈希时仍保留 tinfoil 回退 | `GameCatalog.kt:45-56` |
| 调用点 4：缓存键 | `coverCacheKey` 使用 `switch-<titleid>`，与哈希无关 | `GameCatalog.kt:58-62` |
| 调用点 5：云端事实 | `GameCloudFacts.of()` 用 `presentation.primaryName` 作为上报的 `name` | `GameSaveTypes.kt:160-168` |
| 调用点 6：测试 | 单元测试直接读取该 assets 文件验证解析 | `android/app/src/test/java/com/jun9100/opensave/SwitchTitleNameSourcesTest.kt:79-80` |

**去掉该文件后的逐点影响：**

1. `OfflineSwitchTitles.load()` 的 `runCatching` 会得到空 Map，不会崩溃（`GameNames.kt:205-211`）。
2. `GameCatalog.present()` 的 `offlineName` 和 `switchIconHash` 变为 null（`GameCatalog.kt:136-146`）。
3. 对于不在硬编码 `titleIdEntries`（15 个常见 TitleID，`GameCatalog.kt:201-218`）里的 Switch 游戏：`SaveSourceDetector` 生成的 `displayName` 是 `"Switch <TitleID>"`（`SaveSourceDetector.kt:190`、`:252`）；`GameCatalog.humanReadableName()` 对以 `Switch ` 开头的名称返回 null（`GameCatalog.kt:169-175`）；最终 `GamePresentation.primaryName` 回退为 `"未命名游戏"`（`GameCatalog.kt:39-40`）。库列表、排序、云端 `name` 都受影响。
4. 封面：不再尝试 eShop URL；但 `switchTitleId` 仍存在，所以 `tinfoil.media` 回退仍会尝试（`GameCatalog.kt:48-54`）。`coverCacheKey` 仍是 `switch-<titleid>`，已有缓存仍可能显示。
5. 云端 C1：`GameCloudFacts.name` 在 `"未命名游戏"` 时为空，`putScopeIdentity` 会省略 `name` 字段；`titleId` 仍会发送（`GameSaveTypes.kt:163-165`、`GameSaveClient.kt:368-374`）。
6. 检测/备份/恢复不受影响：`SaveSourceDetector` 不读该表；`GameSaveTypes.forSave()` 只看 `kind`（`GameSaveTypes.kt:71-89`）。
7. 测试会失败：`SwitchTitleNameSourcesTest.kt:79-80` 直接打开该文件。

## 2. 封面与图片

**所有联网取图/取元数据路径：**

| 来源 | URL 模板 | 触发时机 | 缓存位置/策略 | 证据 |
|---|---|---|---|---|
| Steam 封面 | `https://cdn.cloudflare.steamstatic.com/steam/apps/{appId}/header.jpg` | 游戏行/详情首次组合时 `GameArtwork` 调用 `CoverRepository.load()`；无自定义/缓存封面时下载 | `filesDir/covers/steam-{appId}.jpg`；无显式过期；下载后缩到短边 256px、JPEG 88；单文件上限 4 MiB、15s 超时 | `GameCatalog.kt:45-56`；`GameArtwork.kt:36-62`；`CoverRepository.kt:38-51`、`:92-104`、`:213-229` |
| Nintendo eShop 封面 | `https://img-eshop.cdn.nintendo.net/i/{iconHash}.jpg` | 同上；仅当 TSV 提供 64 位 icon hash | `filesDir/covers/switch-{titleid}.jpg` | `GameCatalog.kt:48-54`；`CoverRepository.kt:30-31`、`:92-104` |
| tinfoil 回退 | `https://tinfoil.media/ti/{titleId}/0/0/` | 同上；eShop URL 之后作为回退；即使无 icon hash 也会尝试 | 同上 `switch-{titleid}.jpg` | `GameCatalog.kt:49-54` |
| Steam 商店搜索 | `https://store.steampowered.com/api/storesearch/?term={name}&l=english&cc=US` | 仅 PC 容器中“只有名称、没有 AppID”的游戏：`GamePresentation.steamSearchName` 非空时，`CoverRepository.load()` 先按名称查 AppID | `filesDir/steam-search/{sha256(normalized name) 前24位}`，缓存 7 天；空结果也缓存 | `GameCatalog.kt:151-153`；`CoverRepository.kt:42-47`、`:111-133`、`:223` |
| Steam 名称元数据 | `https://store.steampowered.com/api/appdetails?appids={appId}&l=schinese&cc=cn` | 游戏行组合时，若 `localizedName`/`originalName` 都为空，`SteamMetadataRepository.enrich()` 查名称 | `filesDir/steam-metadata/{appId}.json`；无 TTL | `SteamMetadataRepository.kt:17-52`、`:54-72`；`GameLibraryPage.kt:893-899` |
| 用户自选封面 | 无网络请求；读取 `content://` URI | 用户选择图片 | `filesDir/covers/custom-{sha256(gameKey) 前24位}.jpg` | `CoverRepository.kt:65-72`、`:90` |
| PSP 游戏图标 | 无网络请求；读取存档目录内 `ICON0.PNG` | 扫描到 PPSSPP 存档后复制 | `filesDir/covers/ppsspp-{gameId}.jpg` | `CoverRepository.kt:54-61`；`SourceViewModel.kt:1324-1349` |
| Android 应用图标 | 无网络请求；`PackageManager.getApplicationIcon()` | 无上述封面时回退 | 不落盘，运行时绘制 192px Bitmap | `CoverRepository.kt:180-187` |

所有 Steam/eShop/tinfoil 请求都显式设置 `User-Agent: SaveVault Android`（`CoverRepository.kt:123`、`:152`；`SteamMetadataRepository.kt:45`）。

**`res/` 与 `assets/` 图片清单：**

- `android/app/src/main/res/drawable/ic_launcher_foreground.xml`：自适应图标前景，盾牌+手柄矢量（注释写明来自 `branding/savevault-icon.svg`），`ic_launcher_foreground.xml:2-3`。
- `android/app/src/main/res/drawable/ic_launcher_monochrome.xml`：Android 13 主题图标层，盾牌挖空手柄，`ic_launcher_monochrome.xml:2`。
- `android/app/src/main/res/mipmap-anydpi/ic_launcher.xml`：自适应图标组合，引用上述前景/背景，`ic_launcher.xml:2-6`。
- `android/app/src/main/res/drawable/ic_stat_savevault.xml`：通知小图标，白色盾牌+手柄，`ic_stat_savevault.xml:2`。
- `android/app/src/main/res/values/ic_launcher_colors.xml`：图标背景色 `#26215C`。
- `assets/` 下只有 `switch-titles.tsv`，没有图片文件。
- `find android/app/src/main/res android/app/src/main/assets -type f` 未发现 PNG/JPEG/WebP；`file` 结果均为 XML 或 TSV。没有游戏封面、主机/商标图片，也没有预取图片；封面只在 UI 组合时按需下载（`GameArtwork.kt:42-46`；`GameLibraryPage.kt:896-899`）。

## 3. 商标与名称用语

**统计口径：** 范围 `android/app/src/main` 下 `.kt` 与 `values/strings.xml`。“字面量”只统计非注释行中的 Kotlin 双引号字符串与 XML 文本；“全部源码”统计所有行（含注释、类名、包名、路径）。两者都包含包名/路径等非界面字符串，因此是上限。

| 词 | 字面量次数 | 全部源码次数 | 备注 |
|---|---:|---:|---|
| Nintendo | 1 | 2 | 字面量仅为 eShop URL 的 `nintendo.net`；另有 `GameCatalog.kt:49` 注释；界面文案 0 |
| Switch | 81 | 210 | 含 `"switch"` 类型键、Material3 `Switch` 标识符等 |
| Steam | 28 | 111 | 含 URL、`Steam AppID` 显示名、`SteamMetadataRepository` 类名 |
| PlayStation | 0 | 0 | 未出现 |
| PSP | 10 | 40 | 含 `psp` 键、`PSP` 标签 |
| Xbox | 0 | 0 | 未出现 |
| Yuzu | 42 | 43 | |
| Eden | 80 | 130 | 含 `eden` 路径/包名 |
| Ryujinx | 6 | 6 | |
| Citron | 10 | 10 | |
| Sudachi | 10 | 10 | |
| Suyu | 10 | 10 | |
| PPSSPP | 38 | 51 | |
| RetroArch | 27 | 34 | |
| Dolphin | 25 | 32 | |
| DuckStation | 19 | 26 | |
| AetherSX2 | 20 | 27 | |
| NetherSX2 | 13 | 13 | |
| ePSXe | 19 | 25 | |
| MAME | 14 | 21 | |
| Winlator | 22 | 22 | |
| GameHub | 25 | 37 | |
| Eglantine | 32 | 32 | |
| DroidDeck | 20 | 26 | |
| Lime3DS | 4 | 4 | |
| Azahar | 4 | 4 | |
| Citra | 9 | 12 | |

**界面文案中的代表性位置（50 条以内）：**

```text
android/app/src/main/res/values/strings.xml:17   PSP, RetroArch, Eden, DroidDeck
android/app/src/main/res/values/strings.xml:22   Switch
android/app/src/main/res/values/strings.xml:42   PPSSPP, PSP, RetroArch, Dolphin
android/app/src/main/java/com/jun9100/opensave/ui/settings/CloudCards.kt:88   Switch
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:22   Switch
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:23   PPSSPP
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:24   RetroArch
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:25   Dolphin
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:26   DuckStation
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:27   AetherSX2, NetherSX2
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:28   Citra, Lime3DS, Azahar
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:34   ePSXe
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:35   MAME
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:102  Eden, Switch
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:103  Eglantine, Switch
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:104  Eden, Switch
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:105  Yuzu, Suyu, Sudachi, Citron, Ryujinx
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:106  GameHub
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:107  DroidDeck
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:108  Winlator
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:109  Winlator
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:110  PPSSPP
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:111  RetroArch
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:112  Dolphin
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:113  DuckStation
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:114  AetherSX2, NetherSX2
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:115  ePSXe
android/app/src/main/java/com/jun9100/opensave/GameSaveTypes.kt:116  MAME
android/app/src/main/java/com/jun9100/opensave/FolderRecommendations.kt:65   Eden, Switch
android/app/src/main/java/com/jun9100/opensave/FolderRecommendations.kt:69   Eden
android/app/src/main/java/com/jun9100/opensave/FolderRecommendations.kt:72   Eglantine, Switch
android/app/src/main/java/com/jun9100/opensave/FolderRecommendations.kt:77   Eden, Switch
android/app/src/main/java/com/jun9100/opensave/FolderRecommendations.kt:82   DroidDeck
android/app/src/main/java/com/jun9100/opensave/FolderRecommendations.kt:87   Winlator
android/app/src/main/java/com/jun9100/opensave/FolderRecommendations.kt:92   PPSSPP, PSP
android/app/src/main/java/com/jun9100/opensave/FolderRecommendations.kt:93   RetroArch
android/app/src/main/java/com/jun9100/opensave/FolderRecommendations.kt:94   Dolphin
android/app/src/main/java/com/jun9100/opensave/FolderRecommendations.kt:95   DuckStation
android/app/src/main/java/com/jun9100/opensave/FolderRecommendations.kt:96   AetherSX2
android/app/src/main/java/com/jun9100/opensave/FolderRecommendations.kt:97   NetherSX2
android/app/src/main/java/com/jun9100/opensave/FolderRecommendations.kt:98   ePSXe
android/app/src/main/java/com/jun9100/opensave/FolderRecommendations.kt:99   MAME
android/app/src/main/java/com/jun9100/opensave/FolderRecommendations.kt:101  GameHub
android/app/src/main/java/com/jun9100/opensave/FolderRecommendations.kt:105  Winlator
android/app/src/main/java/com/jun9100/opensave/GameCatalog.kt:104  Eden
android/app/src/main/java/com/jun9100/opensave/GameCatalog.kt:105  Eglantine
android/app/src/main/java/com/jun9100/opensave/GameCatalog.kt:106  Yuzu
android/app/src/main/java/com/jun9100/opensave/GameCatalog.kt:107  Eden
android/app/src/main/java/com/jun9100/opensave/GameCatalog.kt:108  Yuzu
android/app/src/main/java/com/jun9100/opensave/GameCatalog.kt:109  Yuzu
android/app/src/main/java/com/jun9100/opensave/GameCatalog.kt:113  PPSSPP
android/app/src/main/java/com/jun9100/opensave/GameCatalog.kt:114  RetroArch
android/app/src/main/java/com/jun9100/opensave/GameCatalog.kt:115  Dolphin
android/app/src/main/java/com/jun9100/opensave/ui/Formatters.kt:29   Yuzu, Eden, Switch
android/app/src/main/java/com/jun9100/opensave/ui/Formatters.kt:30   Ryujinx, Switch
android/app/src/main/java/com/jun9100/opensave/ui/Formatters.kt:31   Switch
android/app/src/main/java/com/jun9100/opensave/ui/library/LibraryToolbar.kt:274  Switch
android/app/src/main/java/com/jun9100/opensave/SaveSourceDetector.kt:521  Steam
android/app/src/main/java/com/jun9100/opensave/GameCatalog.kt:141  Steam
```

**补充：**

- `Nintendo` 在 App 界面文案中没有出现；只有 `GameCatalog.kt:49` 注释和 `GameCatalog.kt:52` 的 `nintendo.net` URL。
- `android/app/src/main/AndroidManifest.xml:27-44` 的 `<queries>` 声明了 Eden/Eglantine/Eden SX2A、GameHub、Winlator、Winlator 6.1、DroidDeck、Yuzu、Yuzu EA、Suyu、Sudachi、Citron 等包名。
- `GameArtworkSources.kt:13-27` 还有 PPSSPP、RetroArch、Dolphin、DuckStation、AetherSX2、Citra/Lime3DS/Azahar、melonDS、Vita3K、Mupen64Plus、DraStic、Lemuroid、ePSXe、MAME 的包名。
- 硬编码游戏名中还有 Mario/Zelda/Pokemon 等任天堂作品名（`GameCatalog.kt:202-217`），这些是游戏标题，不是平台商标文案。
- 内置 `switch-titles.tsv` 的第三方游戏标题中：`Nintendo` 出现 248 次、`Switch` 243 次、`Steam` 26 次（`grep -o -i ... | wc -l`）。

**README 用语：**

- `android/README.md:3`：`这是 SaveVault（原名 OpenSave）的原生 Android 客户端，适配 Android 手机和掌机（Android 8 及以上；无 Root 时用系统授权目录，Android 10 及以下可一次授权整个内部存储）……`
- `android/README.md:7`：列出 `GameHub PC、Eden/Yuzu/Suyu/Sudachi/Citron、Winlator、DroidDeck` 等 Root 来源。
- `android/README.md:8`：列出 `PC 游戏、Switch 游戏、PPSSPP、RetroArch、Dolphin、DuckStation、AetherSX2/NetherSX2、Citra/Lime3DS、melonDS、Vita3K、Mupen64Plus、DraStic、Lemuroid` 等分类。
- `android/README.md:9`：`识别 Eden/Yuzu 系 NAND 与 Ryujinx/Ryubing BIS 中的 Switch TitleID；常见 Switch 游戏显示中文名并缓存 TitleID 封面，Steam AppID 游戏可解析中文名并缓存封面。`
- 仓库根 `README.md:9` 只说明 SaveVault 是 `jun9100/OpenSave` 分支，PC 端、Android 端和自建云端 `game-save` 一起使用；根 README 没有单独的 Android 功能章节。
- 根 `README.md:7` 标题为 `Steam Cloud for every game you own.`；`:11` 写 `no Steam required`；`:44` 的 PC 自动检测列表包含 `Goldberg/GSE, CODEX, RUNE, Tenoke, EMPRESS, Online-Fix, CPY, SKIDROW, 3DM` 等。

## 4. Root

| 项目 | 事实 | 证据 |
|---|---|---|
| `su` 调用形式 | `ProcessBuilder("su", "-mm", "-c", command)`；`-mm` 用于 Magisk 全局 mount namespace；stderr 合并；60 秒超时 | `android/app/src/main/java/com/jun9100/opensave/RootStorage.kt:19-42` |
| Root 检查 | 执行 `id -u`，要求输出 `0`；不是 `which su` | `RootStorage.kt:65-68` |
| su 存在性检查 | 只检查文件存在，不执行：`/system/bin/su`、`/system/xbin/su`、`/sbin/su`、`/system_ext/bin/su`、`/vendor/bin/su`、`/debug_ramdisk/su`、`/data/adb/magisk` | `Onboarding.kt:68-82` |
| 何时允许调用 | 首次向导完成前不调用；`AccessChoice.ROOT` 为 true，`SAF` 为 false，`null` 且向导已完成才允许 | `Onboarding.kt:47-51` |
| 用户选择 Root | “使用 Root”是第一次调用 `su` 的时机；成功后记录 `rootConfirmed` | `SourceViewModel.kt:443-469`；`Onboarding.kt:103-106` |
| Root 路径（预设） | `/data/user/0/<pkg>/files`、`/storage/emulated/0/Android/data/<pkg>/files`、`/storage/emulated/0/eden`、`/storage/emulated/0/pc-game`、`/storage/emulated/0/PSP` 等 | `SourceSelection.kt:42-72` |
| Root 路径（按已安装应用生成） | `/data/user/0/<pkg>`、`/storage/emulated/0/Android/data/<pkg>` | `InstalledGameCatalog.kt:76-90` |
| Switch 名称学习 | 读取 `/data/user/0/<pkg>/shared_prefs/<pkg>_preferences.xml`，再对模拟器记录的 ROM 目录执行 `ls -1` | `SourceViewModel.kt:1380-1400` |
| 扫描命令 | `find <path> -type d -print 2>/dev/null`，并输出 find 状态 | `RootStorage.kt:82-117` |
| 复制命令 | 检查符号链接/特殊文件后 `cp -a -- <source>/. <dest>/`，再 `chown -R <uid>:<uid>` | `RootStorage.kt:166-196` |
| 恢复命令 | `cp -a`、`chown -R`、`chmod`，再用 `mv` 替换目标；失败时回滚 | `RootStorage.kt:198-287` |
| 受保护路径 | 精确拒绝 `/`、`/data`、`/data/user`、`/data/user/0`、`/data/user_de`、`/data/user_de/0`、`/data/data`、`/data/media`、`/storage`、`/storage/emulated`、`/storage/emulated/0`、`/sdcard`；前缀拒绝 `/system`、`/vendor`、`/product`、`/odm`、`/apex`、`/proc`、`/sys`、`/dev`、`/metadata`、`/linkerconfig` | `RootPathPolicy.kt:7-33` |
| `/data/data` 与 `/data/media` | 只出现在受保护路径集合中；代码实际使用的是 `/data/user/0/<pkg>`，没有读取 `/data/data/<pkg>` 或 `/data/media` 的调用点 | `RootPathPolicy.kt:14-15`；`SourceSelection.kt:42-63`；`InstalledGameCatalog.kt:83-88` |
| 宽泛共享存储 | `canonicalizeForScan` 允许 `/storage/emulated/0`、`/sdcard` 仅用于只读扫描；`canonicalize` 用于备份/恢复时拒绝这些路径 | `RootPathPolicy.kt:35-60` |

**开启 Root 前用户看到的原文：**

- `android/app/src/main/res/values/strings.xml:21`：`检测到 Root`
- `android/app/src/main/res/values/strings.xml:22`：`有 Root 时还能读取 Android/data 等受保护的目录（例如 Switch 模拟器的存档）。选择后 Magisk 会弹出授权：请选“永久”，因为后台自动备份无法弹出授权提示。`
- `android/app/src/main/res/values/strings.xml:25`：`没有检测到 su，将只使用授权目录。以后获得 Root 时，可以在 设置 → 关于 中重新打开本向导。`
- 失败提示原文：`没有获得 Root：${error.message ?: "su 未返回 uid 0"}。可以在 Magisk 中允许 SaveVault 后再试，或改用授权目录。`（`SourceViewModel.kt:462-463`）

## 5. 破解版 / 盗版相关识别

**PC 端 `internal/presets` 中的显式“Steam emulator / repack”预设：**

| ID | 名称 | 路径模板 | 证据 |
|---|---|---|---|
| `goldberg` | Goldberg Steam Emulator | `%APPDATA%/Goldberg SteamEmu Saves` | `internal/presets/presets.go:201` |
| `gse` | Goldberg (GSE fork) Saves | `%APPDATA%/GSE Saves` | `:202` |
| `codex` | CODEX / PLAZA Steam Emulator | `%PUBLIC%/Documents/Steam/CODEX` | `:203` |
| `rune` | RUNE Steam Emulator | `%PUBLIC%/Documents/Steam/RUNE` | `:204` |
| `tenoke` | Tenoke Steam Emulator | `%USERPROFILE%/Documents/Steam/TENOKE` | `:205` |
| `empress` | EMPRESS Saves | `%PUBLIC%/Documents/EMPRESS` | `:206` |
| `onlinefix` | Online-Fix Saves | `%PUBLIC%/Documents/OnlineFix` | `:207` |
| `cpy` | CPY Saves | `%PUBLIC%/Documents/CPY_SAVES` | `:208` |
| `sse` | SmartSteamEmu Saves | `%APPDATA%/SmartSteamEmu` | `:209` |
| `skidrow-appdata` | SKIDROW Saves | `%APPDATA%/SKIDROW` | `:210` |
| `skidrow-local` | SKIDROW Saves (Local) | `%LOCALAPPDATA%/SKIDROW` | `:211` |
| `3dm` | 3DM Saves | `%PUBLIC%/Documents/3DMGAME` | `:212` |
| `flt` | Fairlight (FLT) Saves | `%APPDATA%/FLT` | `:213` |
| `ali` | ALi Saves | `%APPDATA%/ALi` | `:214` |
| `reloaded` | RELOADED (RLD!) Wrapper | `%PROGRAMDATA%/Steam/RLD!` | `:215` |
| `generic-wrapper` | Public Documents Steam Wrapper | `%PUBLIC%/Documents/Steam` | `:216` |

补充事实：

- 包注释把这类目录称为 `Steam-emulator/repack wrapper folders`，类型为 `"repack"`（`internal/presets/presets.go:2`、`:19`、`:198-200`）。
- 扫描器会把这些 wrapper 的每个子目录当作一个游戏/AppID 处理（`internal/presets/scanner.go:107`、`:119`），并在注释中明确提到 `portable/cracked installs`（`:189`、`:383`、`:397`）。
- 全仓搜索 `steam_emu.ini` 无命中；`SmartSteamEmu` 只作为目录名/预设名出现，没有解析其配置文件。
- `internal/delta/delta_test.go:327-329` 只是测试里用 `CODEX`、`Goldberg SteamEmu Saves` 作为路径样例，不是生产识别逻辑。
- `internal/p2p` 有 “cracked copy” 相关注释/测试（如 `internal/p2p/switchmatch.go:19`、`internal/p2p/routes.go:503-506`、`internal/p2p/matching_test.go:26`），但不是 Android 或 `internal/presets` 的目录预设。

**Android 端：**

- `android/app/src/main` 下搜索 `codex|goldberg|smartsteamemu|steam_emu|onlinefix|empress|skidrow|fairlight|flt|rune|tenoke|cpy|3dm|rld|crack|repack|盗版|破解` 无命中（大小写不敏感；`RUNE` 只命中 `Rune Factory` 游戏名等）。
- `SaveSourceDetector.kt` 没有破解/盗版分支；只有通用的 PC 容器识别：`GameHub`、`Winlator`、`DroidDeck`、`pc-game`、`Steam userdata` 等（`SaveSourceDetector.kt:484-555`）。
- Android 的 `switch-titles.tsv` 里出现的 `Anime Codex`、`The Crackpet Show` 等只是游戏标题（如 `switch-titles.tsv:450`、`:767`、`:7523`），不是识别规则。

## 6. 网络与数据

**请求头（普通 game-save 请求）：**

| 字段 | 内容 | 证据 |
|---|---|---|
| `Authorization` | `Bearer <同步密钥或设备令牌>` | `GameSaveClient.kt:483-486` |
| `X-OpenSave-Platform` / `X-Eden-Platform` | 默认 `opensave-android` | `GameSaveClient.kt:90-95`、`:639-650` |
| `X-OpenSave-Device-ID` / `X-Eden-Device-ID` | 随机 UUID，保存在 `opensave_game_save_device` SharedPreferences | `GameSaveHeadSync.kt:519-535`；`GameSaveClient.kt:646-647` |
| `X-OpenSave-Device-Name` / `X-Eden-Device-Name` | `Build.MODEL`，最多 80 字符；非 ASCII 用 RFC 8187 `UTF-8''...` 编码 | `GameSaveHeadSync.kt:528`；`DeviceHeaders.kt:16-35`；`GameSaveClient.kt:648` |
| `X-OpenSave-Version` / `X-Eden-Version` | 仅当 `identity.version` 非空时发送；当前 `GameSaveDeviceStore.identity()` 未设置 version，因此实际不发送 | `GameSaveClient.kt:648`；`GameSaveHeadSync.kt:529` |
| `Content-Type` | JSON 请求为 `application/json; charset=utf-8`；ZIP 上传为 `application/zip` | `GameSaveClient.kt:538-539`、`:221-224` |
| `If-Match` | scope 快照 CAS 写入时带 `"<expectedHead>"`；冲突副本不带 | `GameSaveClient.kt:415-419` |

**配对请求体 `POST /v1/pair/claim`：**

- `code`、`device_id`、`device_name`（最多 80）、`platform`、`version`（`GameSaveClient.kt:566-604`）。
- 同时带上述 `X-OpenSave-*` / `X-Eden-*` 设备头；不带 `Authorization`（配对前没有已保存凭据）。
- 返回 token 只在此处返回一次；`GameSavePairingResult.toString()` 把 token 掩码为 `***`（`GameSaveClient.kt:106-116`、`:606-620`）。

**C1 身份上报 `PUT /v1/opensave/scopes/{type}/{gameId}/identity`：**

- 请求体字段：`name`、`titleId`、`steamAppId`、`pspGameId`、`androidPackage`；空字符串字段省略（`GameSaveClient.kt:366-383`）。
- 字段来源：`GameCloudFacts.of()` 从 `GamePresentation` 取 `primaryName`（`"未命名游戏"` 时为空）、`switchTitleId`、`steamAppId`、`pspGameId`、`androidPackageName`（`GameSaveTypes.kt:147-169`）。
- 触发：备份发布成功后 best-effort 调用；失败不影响备份成功（`GameSaveNativeSync.kt:201-216`）。

**scope 快照请求体 `PUT .../snapshots/{id}` 或 `.../conflicts/{id}`：**

- 字段：`id`、`archive`、`parent`、`contentDigest`、`createdAt`；普通快照带 `If-Match`，冲突副本不带（`GameSaveClient.kt:390-428`）。

**文件上传：**

- `PUT /v1/opensave/files/{type}/{name}`，body 是原始 ZIP，`application/zip`，不是 multipart（`GameSaveClient.kt:218-228`）。

**上传的存档 ZIP 内容：**

- 普通游戏：`opensave-game.json` + `location-0000/...` 等目录（`GameSnapshotManifest.kt:41-75`；`AutoBackup.kt:395-408`；`GameOperations.kt:188-192`）。
- `opensave-game.json` 字段：`version`、`gameKey`、`gameName`、`locations[]`，每个 location 含 `kind`、`relativePath`、`archivePath`、`candidate`（`GameSnapshotManifest.kt:19-35`）。
- 桌面兼容的单目录 Root Switch 存档：直接压缩该存档目录内容，不写 `opensave-game.json`（`AutoBackup.kt:650-655`；`GameOperations.kt:228-234`）。
- ZIP 条目使用文件/目录的 mtime（`SnapshotEngine.kt:80-97`）。

**另有一种“当前存档公告”ZIP：**

- 文件名 `gameId__head-<deviceKey>-<announcedAt>.zip`，内含 `head.json`（`GameSaveHeadSync.kt:107-113`、`:142-167`）。
- `head.json` 字段：`version`、`deviceId`、`deviceName`、`gameId`、`branch`、`snapshot`、`file`、`chain`、`savedAt`、`contentModifiedAt`（`GameSaveHeadSync.kt:148-160`）。
- 通过同一个 `/v1/opensave/files/{type}/{name}` 上传（`GameSaveHeadSync.kt:630-651`）。

**本地日志/动态：**

- `ActivityLogStore` 写入 app 私有 `filesDir/activity-log.json`，最多 200 条，最近 20 条内相同内容折叠（`ActivityLog.kt:73-87`、`:129-165`）。
- 批量报告写入 `filesDir/batch-reports.json`，最多 20 份（`BatchReportStore.kt:11-42`）。
- 代码中没有把 `activity-log.json` 或 `batch-reports.json` 上传到服务端的路径；`grep` 未发现相关网络调用。
- 后台“需要处理”的通知会把 `text`/`detail` 放进系统通知（`ActivityLog.kt:201-229`），通知内容是否显示在锁屏由系统设置决定。
- Android 主代码没有 `android.util.Log` / `println` 调用（`grep` 无命中）。

**第三方 SDK：**

- `android/app/build.gradle.kts:80-100` 的依赖只有 AndroidX（Compose、Activity、Core、DocumentFile、Lifecycle、WorkManager）、OkHttp、Material3、测试库。
- 未发现 Firebase/Crashlytics/Sentry/AppCenter/AdMob/Facebook/统计/广告 SDK；未发现 `google-services.json`。
- `AndroidManifest.xml:4-13` 只声明 `INTERNET`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`。

**`network_security_config.xml`：**

- 基础配置 `android:usesCleartextTraffic="false"`（`AndroidManifest.xml:56`）。
- 仅对 `192.168.12.4`、`127.0.0.1`、`localhost` 允许明文，`includeSubdomains="false"`（`android/app/src/main/res/xml/network_security_config.xml:2-8`）。
- `GameSaveClient.normalizeServiceUrl()` 本身允许 `http` 或 `https`（`GameSaveClient.kt:660-681`）；因此用户填写的其他 `http://` 主机能否实际连接，受上述网络安全配置限制。

**`allowBackup` / 备份规则：**

- `android:allowBackup="false"`（`AndroidManifest.xml:45-56`）。
- `backup_rules.xml` 排除 `file`、`database`、`sharedpref` 全部内容（`res/xml/backup_rules.xml:2-6`）。
- `data_extraction_rules.xml` 的 `cloud-backup`（`disableIfNoEncryptionCapabilities="true"`）和 `device-transfer` 都排除 `file`、`database`、`sharedpref` 全部内容（`res/xml/data_extraction_rules.xml:2-10`）。

## 7. 前台服务与后台任务

| Worker/任务 | 前台服务 | 类型 | 周期/约束 | 证据 |
|---|---|---|---|---|
| `BatchWorker` | 是，`setForeground(BatchNotifications.foregroundInfo(...))`；失败时继续普通任务 | `dataSync`（Android Q+） | 一次性、`setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)`、唯一任务 `opensave_batch`；无显式时长上限 | `BatchWorker.kt:26-81`、`:139-163` |
| `AutoBackupWorker` | 否 | — | `PeriodicWorkRequestBuilder(15, MINUTES)`；约束 `NetworkType.UNMETERED`；唯一任务 `opensave_auto_backup`；有批量任务运行时跳过 | `AutoBackup.kt:20-59`、`:196-211` |
| `PendingUploadWorker` | 否 | — | 一次性；约束 `NetworkType.CONNECTED`；指数退避初始 30s；最多 3 次重试；唯一任务 `opensave_pending_upload` | `PendingUpload.kt:69-84`、`:91-106`、`:216-260` |

- 清单中合并声明 `androidx.work.impl.foreground.SystemForegroundService`，`foregroundServiceType="dataSync"`（`AndroidManifest.xml:74-77`）。
- 批量任务可能持续很久：清单注释写明“长上传不会被后台时限切断”（`AndroidManifest.xml:5-9`）；`GameSaveClient` 单请求 connect/read/write 超时均为 30s（`GameSaveClient.kt:162-166`），批量按选中游戏逐个执行。代码没有设置批量总时长上限。

**通知渠道：**

| ID | 名称 | 重要性 | 证据 |
|---|---|---|---|
| `opensave_attention` | `需要处理` | `IMPORTANCE_DEFAULT` | `ActivityLog.kt:186-207`；`strings.xml:57` |
| `opensave_batch` | `批量任务` | `IMPORTANCE_LOW` | `BatchWorker.kt:139-159` |

**`POST_NOTIFICATIONS` 申请时机与文案：**

- 只在 Android 13+ 且 `!asked && !allowed` 时询问；最多一次；之后只能去系统设置（`AdaptiveShell.kt:551-580`；`Onboarding.kt:108-111`）。
- 入口一：首次向导第 3 步“连接 SaveVault 云端（可选）”的 `NotificationCard`（`FirstRunWizard.kt:249-265`、`:303-336`）。
- 入口二：设置 → 自动化 → `NotificationRow`（`SettingsPage.kt:189-225`）。
- 说明文案：`后台发现冲突或权限问题时发通知提醒；只会询问这一次。`（`strings.xml:34`）；按钮 `允许通知`（`:35`）；已允许 `通知已允许`（`:36`）；拒绝后 `通知未允许；可以在系统设置中打开`（`:37`）。

## 8. 构建与签名

| 项目 | 事实 | 证据 |
|---|---|---|
| `compileSdk` | 35 | `android/app/build.gradle.kts:21` |
| `minSdk` | 26 | `:25` |
| `targetSdk` | 35 | `:26` |
| `versionCode` | 24 | `:27` |
| `versionName` | `0.6.0` | `:28` |
| `applicationId` | `com.jun9100.opensave` | `:24` |
| 签名读取方式 | 先看环境变量 `SAVEVAULT_KEYSTORE_PROPERTIES` 指向的文件；否则读 `${user.home}/.savevault/keystore.properties`；只读取 `storeFile`、`storePassword`、`keyAlias`、`keyPassword` 四个属性；找不到时 release 不配置签名 | `android/app/build.gradle.kts:9-17`、`:36-45` |
| 是否有 `bundle {}` | 未发现 | 全仓 `grep 'bundle\s*{'` 无命中 |
| R8/minify | `isMinifyEnabled = false`；`proguard-rules.pro` 只有占位注释，明确“currently ships without minification” | `android/app/build.gradle.kts:54-60`；`android/app/proguard-rules.pro:1-3` |
| Android CI | `.github/workflows/ci.yml` 的 `android-unit` 只运行 `./gradlew :app:testDebugUnitTest --no-daemon`，不产出 APK/AAB | `.github/workflows/ci.yml:54-69` |
| Android 发布流程 | `.github/workflows/release.yml` 是 Windows release，构建 Wails/NSIS、CLI、relay，没有 Android 构建或发布步骤 | `.github/workflows/release.yml:1-11`、`:52-82` |

## 9. “关于”与许可页面

- 设置 → 关于只有：版本号（`PackageInfo.versionName` + longVersionCode）、`SaveVault $version`、`游戏存档备份与跨设备同步。`、按钮 `首次使用向导…` 及其说明（`SettingsPage.kt:251-286`；`strings.xml:60-62`）。
- 未找到“开源许可 / 隐私政策 / license / 隐私”的 Android 入口或字符串（`grep` 无命中）；`AboutCard` 没有这些按钮。
- 仓库 `LICENSE` 是 MIT；版权行原文：
  - `Copyright (c) 2026 Siva Prakash (original OpenSave)`（`LICENSE:3`）
  - `Copyright (c) 2026 OpenSave contributors (Go rewrite)`（`LICENSE:4`）
- `PRIVACY.md` 要点：
  - `No accounts`、`No telemetry`、`No third-party save storage`（`PRIVACY.md:8-15`）。
  - 出站请求列出：GitHub Releases 更新检查、Steam 名称、Ludusavi manifest、Steam 封面 CDN 及 `images.weserv.nl` 代理（`PRIVACY.md:40-63`）。
  - 可选 Cloud Backup 会把快照上传到用户选择的 provider；OAuth token 本地保存；断开 provider 会删除 token（`PRIVACY.md:65-71`）。
  - 配对/取消配对描述的是 PC 的 Devices 页面（`PRIVACY.md:73-76`）。
- `PRIVACY.md` 没有提到 Android 的 eShop/tinfoil 取图，也没有提到 Android 的 `game-save` 自建云上传；这是文档与 Android 代码的覆盖差异。

## 10. 账户相关

**`GameSaveClient` 的路由/操作：**

| 方法 | 路径 | 用途 | 证据 |
|---|---|---|---|
| GET | `/v1/auth/whoami` | 查询凭据类型/设备名/共享密钥状态 | `GameSaveClient.kt:177-190` |
| GET | `/v1/opensave/files` | 列举快照 | `:192-216`、`:521` |
| PUT | `/v1/opensave/files/{type}/{name}` | 上传 ZIP（存档或 head） | `:218-228`、`:526-527` |
| GET | `/v1/opensave/files/{type}/{name}` | 下载 ZIP | `:230-254` |
| DELETE | `/v1/opensave/files/{type}/{name}` | 删除云端快照 | `:256-261` |
| GET | `/v1/opensave/scopes/{type}/{gameId}` | 读取 scope/head | `:268-275`、`:523-524` |
| PUT | `/v1/opensave/scopes/{type}/{gameId}/snapshots/{id}` | CAS 推进 head | `:278-290`、`:390-428` |
| PUT | `/v1/opensave/scopes/{type}/{gameId}/conflicts/{id}` | 保留冲突副本 | `:292-302`、`:390-428` |
| GET | `/v1/opensave/games` | 读取 C1 游戏分组 | `:308-314` |
| PUT | `/v1/opensave/scopes/{type}/{gameId}/identity` | 上报 C1 名称/TitleID/AppID/PSP 编号/包名 | `:366-383` |
| POST | `/v1/pair/claim` | 用配对码换设备 token | `:566-604` |

**App 内入口：**

- 云端卡片：显示服务地址、同步密钥（掩码）、凭据状态；按钮 `用配对码连接`、`从剪贴板导入配置`、`检查跨设备更新`（`CloudCards.kt:74-147`）。
- 配置编辑器：服务地址 + 同步密钥，`测试并保存`；连接失败可 `仍然保存` 或返回修改（`GameSaveDialogs.kt:45-148`）。
- 配对对话框：地址 + 8 位配对码/`savevault://pair` 链接；确认服务器后才发送（`PairingDialogs.kt:38-150`）。
- token 存储：`GameSaveConfigStore` 只明文保存服务地址；同步密钥用 Android Keystore AES-GCM 加密（`GameSaveConfigStore.kt:25-45`、`:47-95`）。`GameSaveConfig.toString()` 和 `GameSavePairingResult.toString()` 都掩码 token（`GameSaveConfigStore.kt:20-22`；`GameSaveClient.kt:113-115`）。
- 凭据被服务端撤销：`GameSaveAuthGuard` 记录 `token_revoked` / `shared_token_disabled`，取消自动备份和补传任务，写入动态并通知；提示“用配对码重新连接”（`GameSaveAuthState.kt:13-110`）。

**有没有“删除我的数据 / 解除配对”：**

- 未找到“解除配对 / 取消配对 / 移除设备 / 注销 / 删除账户 / 删除我的数据”的 Android 入口或字符串（`grep` 无命中）。
- `GameSaveClient.delete()` 只删除云端快照文件，不是账户/设备删除；且主代码中没有调用点（`GameSaveClient.kt:256-261`；`grep` 无调用）。
- `GameSaveConfigStore.save()` 在 `syncKey` 为空时会移除本地密钥（`GameSaveConfigStore.kt:42`），但 UI 的 `testAndSaveGameSave()` 明确拒绝空密钥（`SourceViewModel.kt:916-930`），没有“清空配置/解除配对”的按钮。
- 因此，Android App 内目前没有“删除我的数据”或“解除配对/注销设备”的操作；只有覆盖配置、重新配对，以及服务端撤销后的提示。

## 取证命令（摘要）

```text
wc -c -l android/app/src/main/assets/switch-titles.tsv
awk -F '\t' 'NF!=3{...} ...' android/app/src/main/assets/switch-titles.tsv
find android/app/src/main/res android/app/src/main/assets -type f
grep -RIn --exclude-dir=build --exclude-dir=.gradle 'switch-titles|titledb' .
grep -RIn --exclude-dir=build --exclude-dir=.gradle -E 'https?://|cdn\.|tinfoil|eshop|steamstatic|steampowered' android/app/src/main/java
grep -RIn --exclude-dir=build --exclude-dir=.gradle -iE 'codex|goldberg|smartsteamemu|steam_emu|onlinefix|empress|skidrow|fairlight|flt|rune|tenoke|cpy|3dm|rld|crack|repack' internal android
grep -RIn --exclude-dir=build --exclude-dir=.gradle -E 'setForeground|ForegroundInfo|PeriodicWorkRequest|NotificationChannel' android/app/src/main/java
grep -RIn --exclude-dir=build --exclude-dir=.gradle -E '关于|许可|license|隐私|privacy' android/app/src/main
grep -RIn --exclude-dir=build --exclude-dir=.gradle -E '解除配对|取消配对|移除设备|注销|删除账户|删除我的数据' android/app/src/main
python3 -c "..."  # 商标/模拟器词频统计（字面量与全部源码两列）
```
