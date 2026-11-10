# SaveVault Android：Google Play 上架合规审查

- 审查对象：`android/`，`main` 分支 `1ccd688`（Android 0.6.0 / versionCode 24）；C6（PR #40）尚未合入，不在本次范围。
- 审查日期：2026-11-10。文中 Play 政策和数字都来自当期官方文档，查阅日期均为 **2026-11-10**，以官方页面为准。
- 背景：计划在 Google Play 上架；App 免费，SaveVault 云端存储按订阅收费，多租户设计见 game-save#34 的 `docs/C7_MULTI_TENANT.md`。
- 本文只做审查，不改功能代码。证据材料：
  - [`play-research/licenses.md`](play-research/licenses.md)：依赖许可清单，由脚本 [`play-research/gen_licenses.py`](play-research/gen_licenses.py) 从 Gradle 依赖报告生成；
  - [`play-research/survey.md`](play-research/survey.md)：代码普查，每条都带文件和行号。

**风险等级**：

- **阻止上架**：不改就无法提交，或大概率被拒；
- **审核风险**：可能被拒或下架，取决于审核人员和后续投诉；
- **建议**：不改也能上架，但改了更稳妥或体验更好。

**改动量**：S 为半天以内，M 为 1–3 天，L 为一周左右，XL 为跨端或需要服务端配合。

---

## 结论摘要

最要紧的五件事：

1. **targetSdk 不达标**（阻止上架）：Play 从 2026-08-31 起要求新应用和更新 target API 36，可申请延期到的 2026-11-01 也已经过了。现在是 35（`android/app/build.gradle.kts:26`），需要先把 AGP 升到 8.9.1 以上。
2. **网络安全配置里写死了家用 NAS 的 IP**（阻止上架）：`network_security_config.xml:4` 只允许对 `192.168.12.4`、本机回环使用 http。Play 版里其他用户的 http 自托管地址都连不上；这个私人内网地址也不该随商店包发出去。
3. **隐私政策和许可页面都没有**（阻止上架）：
   - `PRIVACY.md` 写的是 PC 端，没有覆盖 Android；
   - App 内没有“开源许可 / 隐私政策”入口；
   - MIT 和 Apache-2.0 都要求随二进制附上许可声明。
4. **内置 titledb 和第三方封面来源**（审核风险，titledb 怎么处理要你决定）：
   - 内置的 `switch-titles.tsv`（2.6 MB，24,252 条）是从任天堂 eShop 抓取的数据；
   - 封面会直连任天堂 CDN，以及和盗版生态关系密切的 `tinfoil.media`。
5. **Root 功能**（审核风险，是否保留要你决定）：Root 模式会读取其他应用的私有目录 `/data/user/0/<pkg>`，Play 审核对这类行为敏感，至少要在首次使用时充分披露。

整改清单见文末。

---

## 一、许可与声明

### 1.1 客户端代码的来源与许可

| 项 | 内容 |
|---|---|
| 现状 | 仓库根 `LICENSE` 是 MIT，版权行为 `Copyright (c) 2026 Siva Prakash (original OpenSave)`、`Copyright (c) 2026 OpenSave contributors (Go rewrite)`（`LICENSE:3-4`）。Android 客户端 `android/` 是在这个分支里新写的（最早一次提交 `ac5136b feat(android): add Chinese save manager foundation`），整个仓库按 MIT 发布。 |
| MIT 的要求 | 在“软件的所有副本或主要部分”里保留版权声明和许可声明。APK 是二进制副本，所以 App 里或随附的文档里要有这段 MIT 全文和版权行。MIT 允许商业销售、允许闭源再分发，只要求保留声明。 |
| 风险 | **阻止上架**（许可义务；漏了也不一定被 Play 拒，但属于违反许可）。 |
| 建议 | 1）在 App 内“设置 › 关于 › 开源许可”页列出 SaveVault 自己的 MIT 声明，含上面两行版权和 `Copyright (c) 2026 jun9100 (SaveVault)`（第三行需要你确认署名）；2）商店页的“应用说明”或网站上写明以 MIT 开源，附仓库链接。 |
| 改动量 | S（和 1.3 一起做） |

### 1.2 Gradle 依赖的许可

| 项 | 内容 |
|---|---|
| 现状 | `releaseRuntimeClasspath` 去重后有 **104 个依赖，全部是 Apache-2.0**（AndroidX、Compose、Kotlin 标准库、kotlinx-coroutines、OkHttp、Okio 等），没有 GPL、LGPL、EPL、MPL，也没有许可不明的依赖。完整表格见 [`play-research/licenses.md`](play-research/licenses.md)。直接依赖声明在 `android/app/build.gradle.kts:80-100`。没有任何统计、崩溃、广告类 SDK，也没有 Google Play 服务 / Firebase（`survey.md` 第 6 节）。 |
| Apache-2.0 的要求 | 分发时附上许可全文；依赖自带 `NOTICE` 文件的，要转载 NOTICE 内容。 |
| 风险 | **阻止上架**（许可义务，同 1.1）。 |
| 建议 | 开源许可页列出全部依赖（名称、版本、许可），并附上 Apache-2.0 全文。生成方式见 1.3。每次升级依赖后重新生成：`docs/play-research/gen_licenses.py` 已可复现。 |
| 改动量 | S–M |

### 1.3 “关于 / 开源许可”页面

| 项 | 内容 |
|---|---|
| 现状 | 设置 › 关于（`ui/settings/SettingsPage.kt:253` 的 `AboutCard`）只有版本号、一句介绍和“首次使用向导”按钮。没有开源许可、隐私政策、用户协议的入口（`survey.md` 第 9 节）。 |
| 风险 | **阻止上架**：许可义务见 1.1、1.2；Play 还要求在商店页和 App 内都能找到隐私政策（见 4.3）。 |
| 方案 | **推荐 A：构建时生成，不依赖 Google Play 服务**。用 [AboutLibraries](https://github.com/mikepenz/AboutLibraries)（Apache-2.0）的 Gradle 插件，构建时生成依赖清单，在 Compose 页面里显示；或者把 `gen_licenses.py` 接进构建，生成 `assets/licenses.json`，再自己画一个页面。B：Google 的 `oss-licenses-plugin` 加 `play-services-oss-licenses`。它会引入 Google Play 服务库，掌机和国内设备常常没有 GMS，**不推荐**。 |
| 页面内容 | 关于页新增三项：“开源许可”（SaveVault 的 MIT、依赖清单、Apache-2.0 全文）、“隐私政策”（打开网页）、“用户协议”（C7 收费服务上线后才需要）。三项都要能用手柄打开。 |
| 改动量 | M |

---

## 二、内置数据与素材

### 2.1 `assets/switch-titles.tsv`（titledb）

| 项 | 内容 |
|---|---|
| 现状 | 2,662,051 字节，24,252 行，每行 `TitleID<TAB>名称<TAB>eShop 图标哈希`。生成脚本是 `android/scripts/build_switch_titles.py`：从 [blawar/titledb](https://github.com/blawar/titledb) 的 `US.en.json`、`HK.zh.json`、`JP.ja.json` 生成，名称优先用香港 eShop 的数据并转成简体（`build_switch_titles.py:2-70`；`android/README.md:119`）。代码里由 `OfflineSwitchTitles` 读取（`GameNames.kt:190-226`），只用来**显示名称**（`GameCatalog.kt:133-140`）和**拼 eShop 封面地址**（`GameCatalog.kt:45-56`、`:145-146`）；识别存档完全不靠它，只靠文件系统规则（`GameCatalog.kt:3-6`、`SaveSourceDetector.kt:227-255`）。 |
| 许可与来源 | titledb 仓库本身声明 MIT，但里面的名称、简介、图标哈希都是从任天堂 eShop 抓取的：仓库的 MIT 管不到这些数据本身的权利。titledb 主要服务于 Tinfoil 这类第三方安装工具，审核或投诉时容易被联想到盗版生态。 |
| 风险 | **审核风险**（偏高）。单个游戏名属于事实信息，问题在于整批打包从 eShop 抓来的数据库，再配合 eShop 图标地址一起发布。 |
| 去掉后的影响 | 不在硬编码 15 个常见 TitleID（`GameCatalog.kt:201-218`）里的 Switch 游戏，会显示成“未命名游戏”（`GameCatalog.kt:39-40`、`:169-175`）；不再请求 eShop 封面；C1 身份上报不再带名称，但 TitleID 照常上报。**识别、备份、恢复、同步都不受影响**。`SwitchTitleNameSourcesTest.kt:79-80` 要相应改。逐点分析见 `survey.md` 第 1 节。 |
| 替代方案 | 1）**从云端 C5 获取**（推荐长期方案）：客户端只发 TitleID，服务端返回名称和封面。数据源和合规由服务端统一处理（可以人工维护，也可以用有授权的数据源），自托管用户也能用。改动量 XL，需要 C5。2）**只用本机能识别的信息**（推荐 Play 首发）：Root 模式下已经能从模拟器配置里学到 ROM 目录（`SourceViewModel.kt:1380-1400`），可以扩展成读模拟器自己的游戏列表缓存；无 Root 时显示 TitleID，并允许用户改名（已有“重命名”功能）。改动量 M。3）**用户自行导入**：设置里放“导入游戏名称表（TSV）”，App 不内置。改动量 S–M，对普通用户门槛高。 |
| 建议 | **Play 版不内置 titledb**；GitHub 版可以继续内置（用 product flavor 区分，见 6.4）。**需要你决定**。 |
| 改动量 | Play 版去掉：S；本机识别补强：M；C5：XL |

### 2.2 封面来源

| 来源 | 地址 | 代码位置 | 风险 | 建议 |
|---|---|---|---|---|
| Steam CDN | `cdn.cloudflare.steamstatic.com/steam/apps/{appId}/header.jpg` | `GameCatalog.kt:45-56`、`CoverRepository.kt:92-104` | 审核风险（低）：游戏宣传图有版权，只在 App 内展示用户自己的游戏库，属常见做法 | 保留；隐私政策写明会向 Steam 请求 |
| Steam 商店搜索、名称元数据 | `store.steampowered.com/api/storesearch`、`/api/appdetails` | `CoverRepository.kt:111-133`、`SteamMetadataRepository.kt:17-72` | 建议：会把游戏名或 AppID 发给 Steam | 隐私政策披露；长期改走 C5 |
| 任天堂 eShop CDN | `img-eshop.cdn.nintendo.net/i/{iconHash}.jpg` | `GameCatalog.kt:48-54` | 审核风险（中）：直接引用任天堂 CDN 上的图，图标哈希来自 titledb | 跟随 2.1 的决定，Play 版去掉 |
| tinfoil.media | `tinfoil.media/ti/{titleId}/0/0/` | `GameCatalog.kt:49-54` | **审核风险（高）**：Tinfoil 是常用于安装未授权游戏的第三方商店，向它的服务器发请求会被认为和盗版生态有关联 | **Play 版必须去掉**；建议 GitHub 版也去掉 |
| 本机素材 | PSP 的 `ICON0.PNG`、已安装应用图标、用户自选图片 | `CoverRepository.kt:54-72`、`:180-187` | 无 | 保留 |

App 里**没有内置或预先下载任何游戏封面、主机或商标图片**：`res/` 只有 SaveVault 自己的矢量图标，`assets/` 里只有那份 TSV（`survey.md` 第 2 节）。封面都是在界面显示时按需下载，缓存在 `filesDir/covers/`。

改动量：去掉 tinfoil、按 flavor 关掉 eShop，S。

### 2.3 商标用语（App 内与商店素材）

| 项 | 内容 |
|---|---|
| 现状 | 界面文案没有出现“Nintendo”（只有 eShop 地址里的 `nintendo.net`）。“Switch”“Steam”“PSP”以及模拟器名（Eden、Yuzu、Ryujinx、Citron、Sudachi、Suyu、PPSSPP、RetroArch、Dolphin、DuckStation、AetherSX2/NetherSX2、ePSXe、MAME、Winlator、GameHub、DroidDeck、Eglantine 等）用来标注存档类型和来源（`GameSaveTypes.kt:22-35`、`:102-116`；`FolderRecommendations.kt:65-105`；`ui/Formatters.kt:29-31`）。`<queries>` 里列了 12 个模拟器/容器包名（`AndroidManifest.xml:27-44`）。硬编码的常见游戏名里有马力欧、塞尔达、宝可梦（`GameCatalog.kt:202-217`）。词频见 `survey.md` 第 3 节。 |
| 风险 | **审核风险**（中）。Play 知识产权政策禁止以可能造成混淆的方式使用他人商标（[IP 政策](https://support.google.com/googleplay/android-developer/answer/9888072)）。App 内把“Switch 存档”“Steam AppID”当作说明是在描述兼容性，一般可以接受；风险主要在商店素材。Yuzu 已经在 2024 年和任天堂和解后停止开发和分发，Suyu、Sudachi 等分支同样有争议，在 App 内或商店页突出展示这些名字会增加被投诉的概率。 |
| App 内建议 | 1）文案统一成“适用于……的存档”这类描述兼容性的用法，不用任何平台方的标志或配色；2）Yuzu 系分支只在“自动识别”里静默支持，不在推荐目录和来源名里点名（GitHub 版可保留）；3）硬编码游戏名改走 2.1 的方案，或保留但不出现在截图里。 |
| 商店素材建议 | 1）**标题、简短说明、图标不出现** Nintendo、Switch、Steam、PlayStation 等商标和任何模拟器名；2）完整说明里可以写一句兼容性说明，例如“支持 PPSSPP、RetroArch 等模拟器的存档文件夹”，后面附免责声明：“SaveVault 与 Nintendo、Valve、Sony 及所提及的模拟器项目均无关联”；3）截图只用自制的演示数据，不出现游戏封面和商标；4）分类选“工具”，不选“游戏”。 |
| 改动量 | App 内文案：S–M；商店素材：S |

---

## 三、权限与 Play 政策

`AndroidManifest.xml` 逐项检查：

| # | 声明 | 位置 | 现状 | 风险 | 建议 / 改动量 |
|---|---|---|---|---|---|
| 1 | `INTERNET` | `:4` | 连接云端、取封面 | 无 | — |
| 2 | `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`；WorkManager 的 `SystemForegroundService` 声明 `foregroundServiceType="dataSync"` | `:11-12`、`:74-77` | 只有**用户手动发起的批量操作**（上传待传、检查云端更新、恢复云端较新等）会作为加急 Worker 在 dataSync 前台服务里运行（`batch/BatchWorker.kt:48`、`:77`）。周期性的自动备份（`AutoBackup.kt`，15 分钟、需要 Wi-Fi）和补传（`PendingUpload.kt`）**都不用前台服务** | **阻止上架（声明）**：target 34 及以上要在 Play Console 的“应用内容”页声明每种前台服务类型，写清功能说明、被系统推迟或打断对用户的影响，并附一段视频。dataSync 的“备份与恢复”用例要求视频展示**由用户操作启动**的任务，“不能展示全自动的后台备份或恢复”（[前台服务声明](https://support.google.com/googleplay/android-developer/answer/13392821)）。我们的用法正好符合。**审核风险（时长）**：target 35 及以上时，dataSync 每 24 小时累计最多运行 6 小时，到时系统会调用 `Service.onTimeout()`，服务必须在几秒内停止，否则会抛异常；用户把 App 切回前台时计时清零（[Android 15 行为变更](https://developer.android.com/about/versions/15/behavior-changes-15#datasync-timeout)）。批量任务没有设置总时长上限，现在用的 WorkManager 2.10.0 是否处理了 `onTimeout` **需要核实** | 1）准备声明文案和一段录屏：在游戏库选中几款游戏 → 批量“上传待传”，通知栏出现进度；2）升级 WorkManager 到确认处理了 `onTimeout` 的版本，并用 `adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS <包名>` 加 `data_sync_fgs_timeout_duration` 实测超时时批量任务能停下、报告写明“被系统中断，可重试”；3）可选：改用官方推荐给用户发起传输的“用户发起的数据传输任务”（user-initiated data transfer job），就不用申请 dataSync 了。改动量：声明 S；超时处理 S–M；换 API M |
| 3 | `POST_NOTIFICATIONS` | `:13` | Android 13 及以上只问一次：首次向导第 3 步和“设置 › 自动化”各有入口，说明文案是“后台发现冲突或权限问题时发通知提醒；只会询问这一次”（`strings.xml:34`；`AdaptiveShell.kt:551-580`）。两个通知渠道：`opensave_attention`（默认重要性）、`opensave_batch`（低重要性） | 无 | — |
| 4 | `<queries>`：LAUNCHER intent 加 12 个包名 | `:27-44` | **没有声明 `QUERY_ALL_PACKAGES`**（清单里还有注释说明为什么不申请）。LAUNCHER intent 能看到所有可启动的应用；用于识别哪些应用是游戏、有哪些模拟器 | 审核风险（低）：Play 把“已安装的应用列表”视为个人数据。只在本机用不需要声明；但 **C1 身份上报会把游戏的包名发到服务器**（`GameSaveTypes.kt:147-169`），需要在数据安全表单里申报“已安装的应用”（见第四节） | 数据安全表单申报；隐私政策写明。S |
| 5 | 存储访问 | — | 只用 SAF（`ACTION_OPEN_DOCUMENT_TREE`，`SafStorage.kt`）。**没有** `MANAGE_EXTERNAL_STORAGE`、`READ/WRITE_EXTERNAL_STORAGE`、`requestLegacyExternalStorage`（清单里没有这些声明）。README 提到“Android 10 及以下可一次授权整个内部存储”，指的是在 SAF 选择器里选根目录，不是申请权限 | 无 | — |
| 6 | 深链接 `savevault://pair` | `:66-72` | `VIEW`/`BROWSABLE`，自定义 scheme，不是需要验证域名的 App Link。解析后**一定先显示服务器主机名，确认后才发请求**（C3，`PairingDialogs.kt`） | 无；自定义 scheme 可能被其他应用抢注，但只会打开确认页，不会自动发送 | 建议以后加 `https://` 形式的 App Link（需要自有域名）。可选 |
| 7 | `allowBackup="false"`，备份规则排除全部内容 | `:46-49`；`res/xml/backup_rules.xml`、`data_extraction_rules.xml` | 令牌、存档都不会进系统备份 | 无 | — |
| 8 | 网络安全配置 | `:53`；`res/xml/network_security_config.xml:2-8` | 全局禁止明文（`usesCleartextTraffic="false"`），只对 `192.168.12.4`、`127.0.0.1`、`localhost` 开了例外。**`192.168.12.4` 是开发者家里 NAS 的内网地址** | **阻止上架**（功能上和隐私上都有问题）：Play 版的其他自托管用户用 http 填内网地址都连不上；把个人内网地址打进商店包也不合适 | **需要你决定**：A）Play 版只允许 https（收费云端肯定是 https；自托管用户要配反向代理和证书，门槛高）；B）允许明文，但在 App 内对 `http://` 地址给出醒目提示，例如“未加密，只建议在家庭局域网使用”，数据安全表单如实回答“传输中不一定加密”；C）只允许私有网段的明文：网络安全配置不支持网段写法，要在代码里拦截，配置里仍要全局允许。推荐 **B**：自托管是免费功能，用户自己担风险，提示清楚即可。无论选哪种，都要先删掉 `192.168.12.4`。S |
| 9 | Root（`su`） | `RootStorage.kt:19-42`、`:65-68`；`Onboarding.kt:47-51`、`:68-82` | 用 `ProcessBuilder("su", "-mm", "-c", …)` 执行命令，`-mm` 是 Magisk 的全局挂载命名空间；`id -u` 返回 0 才算成功。首次向导完成前不会调用 `su`，用户选择“使用 Root”后才第一次调用。Root 模式会读写 `/data/user/0/<包名>`、`Android/data/<包名>` 下其他应用的存档（`SourceSelection.kt:42-72`、`InstalledGameCatalog.kt:76-90`），也会读模拟器的 `shared_prefs`（`SourceViewModel.kt:1380-1400`）。危险路径都有拦截（`RootPathPolicy.kt:7-33`）。向导里的说明是“有 Root 时还能读取 Android/data 等受保护的目录（例如 Switch 模拟器的存档）。选择后 Magisk 会弹出授权……”（`strings.xml:22`） | **审核风险（中）**：Play 的设备与网络滥用政策禁止“未经授权访问或干扰设备上的其他应用”，以及“绕过 Android 沙箱保护”来获取其他应用的用户活动或身份（[设备与网络滥用](https://support.google.com/googleplay/android-developer/answer/16559646)）。我们需要用户自己有 Root、主动选择并在 Magisk 里授权，目的是备份用户自己的存档，所以不属于“未经授权”，但审核人员可能不会细看 | **需要你决定是否保留**。A）Play 版保留：首次使用时单独弹一页披露，写明会读取哪些应用的哪些目录、只用于备份存档、不上传其他数据，需要用户勾选同意；商店说明写“可选的 Root 模式”；B）Play 版关掉 Root（flavor），GitHub 版保留。推荐 **A**，掌机用户很依赖 Root 模式；如果审核被拒，再退到 B。A：S–M；B：S |

另外两项：

- **目标 SDK 带来的行为变更**：升到 36 时要按 Android 16 行为变更测试，见 7.1。
- **账户删除**：见第五节。

---

## 四、数据安全表单（草稿）

Play 的定义：数据离开设备就算“收集”（包括第三方 SDK 发出的）；交给第三方才算“共享”，但交给代表你处理数据的服务提供方、用户自己发起的传输，都不算共享（[数据安全说明](https://support.google.com/googleplay/android-developer/answer/10787469)）。

**两种使用方式要分开看**：

- **自托管**：数据只发到用户自己填的服务器，开发者收不到。最接近的豁免情形是“用户自选的外部存储”，但官方举的例子是 Google Drive、Dropbox，自托管服务器算不算没有明确说法；
- **SaveVault 云端（C7 收费服务）**：数据到我们的服务器，肯定算“收集”。

表单是按 App 填的，不分使用方式，所以**按收费云端的情况保守填写**。

| Play 数据类别 | 我们的数据 | 收集 | 共享 | 用途 | 可选 | 说明 |
|---|---|---|---|---|---|---|
| 文件和文档 | 存档 ZIP（`GameSnapshotManifest.kt:41-75`，含存档文件和 `opensave-game.json`：游戏名、相对路径） | 是 | 否 | 应用功能（备份、同步） | 是（不连云端就不上传） | C7 若做客户端加密，可按“端到端加密”考虑豁免；在那之前按收集申报 |
| 设备或其他 ID | 设备 ID（App 生成的随机 UUID，`GameSaveHeadSync.kt:519-535`）、设备令牌 / 配对令牌 | 是 | 否 | 应用功能、账户管理、安全 | 连云端时必需 | 不是广告 ID，也不是 Android ID |
| 应用信息和性能 › 其他 | 设备名（`Build.MODEL`，`GameSaveHeadSync.kt:528`）、平台、版本 | 是 | 否 | 应用功能 | 连云端时必需 | 设备名可能被用户改成包含姓名，按保守处理申报 |
| 应用活动 › 已安装的应用 | C1 身份上报的 Android 包名（`GameSaveTypes.kt:147-169`；`GameSaveClient.kt:366-383`） | 是 | 否 | 应用功能（跨设备归组） | 连云端时必需 | 只上报被备份的游戏，不上报完整应用列表 |
| 应用活动 › 其他用户生成内容 | 游戏身份：名称、Switch TitleID、Steam AppID、PSP 编号 | 是 | **可能是** | 应用功能 | — | 发给我们的服务器算收集；获取封面时 TitleID/AppID/游戏名会发给 Steam、任天堂、tinfoil（第 2.2 节）。这一点要么在隐私政策里披露，要么改成经我们的服务器代理（C5） |
| 个人信息 › 电子邮件、用户 ID | C7 账户（邮箱、账户 ID） | C7 上线后是 | 否 | 账户管理 | 收费云端必需 | 现在没有 |
| 财务信息 › 购买记录 | Play 结算的订阅状态 | C7 上线后是 | 否 | 账户管理 | — | 购买由 Google 处理，我们只拿到购买令牌和订阅状态 |
| 应用信息和性能 › 崩溃日志、诊断信息 | 动态日志 `activity-log.json`、批量报告 | **否** | 否 | — | — | 只存在本机，没有任何上传路径（`ActivityLog.kt:73-87`；`survey.md` 第 6 节） |

安全实践问题：

- **传输中是否加密**：收费云端是 https，可以答“是”。但如果第 3 节第 8 行选了 B（允许 http），自托管时可能不加密，需要如实回答，或者在表单说明里写明“用户自行配置的 http 服务器除外”。在表单定稿前要先决定这一点。
- **能否请求删除数据**：C7 上线后要有删除入口，见第五节。自托管时数据在用户自己手里。
- **隐私政策**：必须提供网址，并且内容要覆盖上面每一项。现有的 `PRIVACY.md` 是 PC 端的，写着“没有账户、没有遥测、不经第三方存储”，没有提到 Android 的 game-save 上传、eShop/tinfoil 取图，C7 上线后“没有账户”这句也不再成立。**阻止上架**；改动量 S（写文档）加 S（App 内加入口）。

---

## 五、账户与删除

| 项 | 内容 |
|---|---|
| 规则 | **只要允许用户在 App 内创建账户**，就必须提供 App 内的删除账户入口，以及一个不用下载 App 也能提交删除请求的网页链接；删除时要删掉数据安全表单里申报过的数据，有保留的要写进隐私政策（[账户删除要求](https://support.google.com/googleplay/android-developer/answer/13327111)）。 |
| 现状 | C3 配对只给设备发令牌，账户在服务端管理员那边，App 内不能创建账户，**现在不受这条约束**。App 内也没有“解除配对 / 删除我的数据”（`survey.md` 第 10 节）。 |
| C7 上线后 | 收费云端会在 App 内“登录 / 注册 SaveVault 云端”（C7 第七节），**这时就必须有删除入口**。**C7 上线时阻止上架**。 |
| 入口位置 | 设置 › 云端，在连接状态下面加一组“账户与数据”：1）“解除本设备”：吊销本机令牌并清掉本机的云端配置，本机存档不动；2）“管理账户”：打开网页 `https://<云端>/account`；3）“删除账户和云端数据”：二次确认、显示会删除什么、写明保留期，然后调用接口，同时提供网页入口。所有按钮都要能用手柄操作，危险操作的焦点默认在“取消”。 |
| 接口（待 C7 定义） | `POST /v1/auth/device/revoke`（吊销当前令牌，C3 可以先做）；`DELETE /account/api/me`（删除账户，服务端异步执行，返回预计完成时间）；网页 `/account/delete`（填到 Play Console 的“删除账户网址”）。 |
| 改动量 | 解除本设备：M（需要服务端加一个接口）；删除账户：XL（跟 C7 一起做） |

---

## 六、签名与发布

### 6.1 现有密钥

- 发布密钥放在 `~/.savevault/`。构建脚本按顺序读环境变量 `SAVEVAULT_KEYSTORE_PROPERTIES` 指向的文件，或者 `~/.savevault/keystore.properties`（`android/app/build.gradle.kts:9-17`、`:36-45`）。本次审查没有读取密钥内容。
- GitHub 上已发布的 [android-v0.5.0](https://github.com/jun9100/OpenSave/releases/tag/android-v0.5.0)、[android-v0.6.0](https://github.com/jun9100/OpenSave/releases/tag/android-v0.6.0) 都用这把密钥签名：CN=SaveVault, O=jun9100，证书 SHA-256 `a2:8a:5f:ad:…:1e:53:ca`。

### 6.2 推荐方案：把现有密钥作为 Play 的应用签名密钥

- Play App Signing 分两把钥匙：
  - **应用签名密钥**：Google 保管，用它给用户实际下载的 APK 签名；
  - **上传密钥**：你保管，只用来证明上传的 AAB 来自你；丢了可以请 Google 重置。
- 新应用默认由 Google 生成应用签名密钥；在发布到开放测试或正式版之前，可以改成上传自己的密钥：用 PEPK 工具加密后上传（[Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756)）。
- **推荐上传现有的 SaveVault 密钥作为应用签名密钥**：这样 Play 下发的 APK 和 GitHub 上的 APK 签名一致，用户可以在两个渠道之间直接覆盖升级。如果让 Google 生成，GitHub 版和 Play 版的签名就不同，用户换渠道时只能卸载重装，本机快照、目录授权、云端配置都会丢。
- **上传密钥另外新建一把**，和应用签名密钥分开，放在 `~/.savevault/upload.*`，不提交、不打印。上传密钥泄露了只影响上传，可以重置；应用签名密钥交给 Google 之后，本地那份要离线备份好。
- 代价：要选择不用 Google 新默认的“抗量子混合签名”（因为要上传自己的密钥）。**需要你决定。**
- 改动量：S（Play Console 操作 + 新建上传密钥）。

### 6.3 versionCode 策略

- Play 要求 versionCode 严格递增，用过的不能再用。现在 GitHub 已经用到 24。
- **推荐 GitHub 版和 Play 版共用一个递增序列**，同一个版本号对应同一份代码。
- 如果两个渠道的包内容不同（比如 Play 版去掉 titledb、tinfoil，或关掉 Root，见 6.4），推荐用 `versionCode = 版本序号 × 10 + 渠道号`（GitHub = 1，Play = 2）区分。这样同一个版本里 Play 版的号更大，用户从 GitHub 版换到 Play 版时能正常升级。
- 无论哪种，`CHANGELOG` 和 Release 都按 `versionName` 写。
- 改动量：S。

### 6.4 AAB 与构建脚本

- **必须用 AAB**：新应用从 2021 年 8 月起必须以 Android App Bundle 发布（[App Bundle](https://developer.android.com/guide/app-bundle)）。现在只有 `assembleRelease`（APK），没有 `bundle {}` 配置（`survey.md` 第 8 节）。
- 构建脚本要改的地方：
  1. 用 `./gradlew bundleRelease` 产出 `app-release.aab`，用上传密钥签名；签名配置要能区分“上传密钥”（Play）和“应用签名密钥”（GitHub APK）。
  2. 如果两个渠道内容不同，加 `productFlavors { github {}; play {} }`：`play` 不打包 `switch-titles.tsv`，`BuildConfig` 里关掉 tinfoil、eShop 封面，也可以关掉 Root。
  3. CI（`.github/workflows/ci.yml:54-69`，现在只跑单测）加 `bundlePlayRelease` 的编译检查；正式签名仍在本机做，不把密钥放进 CI。
  4. 可选：开启 R8（现在 `isMinifyEnabled = false`，`build.gradle.kts:56`），包更小，但要补混淆规则并重新测一遍。
- 改动量：M。

---

## 七、SDK 与技术要求

### 7.1 targetSdk

| 项 | 内容 |
|---|---|
| 规则 | **从 2026-08-31 起，新应用和应用更新必须 target Android 16（API 36）或更高**；可以申请延期到 2026-11-01。已上架的应用要 target 35 及以上，才会继续向更新系统版本的新用户展示（[目标 API 要求](https://developer.android.com/google/play/requirements/target-sdk)）。 |
| 现状 | `compileSdk = 35`、`targetSdk = 35`（`build.gradle.kts:21`、`:26`）。今天是 2026-11-10，延期期限已过。 |
| 风险 | **阻止上架**。 |
| 要改的东西 | 1）AGP 从 8.7.3 升到 ≥ 8.9.1（API 36 需要 AGP 8.9.1 以上，Gradle ≥ 8.11.1；[AGP 与 API 级别对照](https://developer.android.com/build/releases/about-agp)），Kotlin 和 Compose BOM 按需一起升；2）`compileSdk`、`targetSdk` 改成 36；3）按 [Android 16 行为变更](https://developer.android.com/about/versions/16/behavior-changes-16) 回归，重点是大屏和掌机上的方向与可调整大小、边到边显示、预测性返回（App 已开 `enableOnBackInvokedCallback`）、JobScheduler 配额；4）真机回归：Odin2 掌机、手机。 |
| 每年的升级 | Play 每年 8 月 31 日把要求提高一级，通常可以延期到 11 月 1 日。建议把每年 6 月定为“升级 targetSdk”的固定时间。 |
| 改动量 | M（升级加回归） |

### 7.2 原生库与 16 KB 页大小

- **APK 里有一个原生库**：`lib/{arm64-v8a,armeabi-v7a,x86,x86_64}/libandroidx.graphics.path.so`，来自 Compose 依赖的 `androidx.graphics:graphics-path:1.0.1`（Apache-2.0）。
- 本次检查：四个 ABI 的 ELF LOAD 段对齐都是 16384；`zipalign -c -P 16 -v 4 app-release.apk` 结果为 “Verification successful”。也就是说，**已经支持 16 KB 页大小**。
- Play 要求：target 35 及以上的应用必须支持 16 KB，**2027-02-01 起不支持的更新将无法发布**（[16 KB 页大小](https://developer.android.com/guide/practices/page-sizes)）。
- 建议：每次升级依赖后，在 CI 里加一步 `zipalign -c -P 16` 检查。改动量 S。

### 7.3 minSdk 26

- Android 8.0 及以上，覆盖面足够，Play 结算库和 Play Integrity 都支持。
- 不用改。

### 7.4 Play 结算库版本

- 结算库每个大版本支持两年：v7 的新应用和更新截止日是 2026-08-31（可延期到 2026-11-01），v8 是 2027-08-31（[结算库弃用时间表](https://developer.android.com/google/play/billing/deprecation-faq)）。
- 现在没有接入结算。**接入时直接用 v8 或更新的版本。**

---

## 八、结算接入的准备（只写方案）

### 8.1 为什么“App 免费、云存储订阅收费”

1. Play 付款政策把“云软件和服务，例如数据存储服务”明确列为必须使用 Play 结算的数字服务（[付款政策](https://support.google.com/googleplay/android-developer/answer/9858738)）。所以云存储订阅在 App 内必须用 Play 结算；App 内也**不能**通过链接、按钮或说明引导用户去网页付款，参加了替代结算或外部链接计划的地区除外。
2. App 本身的功能是本机备份、自托管同步、存档管理，都免费，这样不涉及“付费解锁功能”。收费的只有我们提供的存储服务，计费维度和 C7 第五节一致。
3. 自托管用户不付任何钱，App 里也没有“为自托管付费”的地方，免费的核心功能和收费的服务分得很清楚，审核时容易解释。

### 8.2 流程

```text
App（Play 结算库 v8+）
  └─ 已登录 SaveVault 云端账户（C7）
  └─ launchBillingFlow(订阅商品, obfuscatedAccountId = 账户 ID 的哈希)
        │ 购买成功，拿到 purchaseToken
        ▼
POST /account/api/billing/google-play/purchases   （待 C7 定义）
  { purchaseToken, productId }
        │
        ▼
服务端：调用 Google Play Developer API purchases.subscriptionsv2.get 校验
        → 核对 obfuscatedAccountId 与当前账户一致
        → 3 天内调用 acknowledge（否则 Google 会自动退款）
        → 通过 C7 计费接口 PUT /ops/api/accounts/{id}/plan 更新权益（active / grace / readonly / closed）
        ▲
Cloud Pub/Sub ← 实时开发者通知（RTDN）
  SUBSCRIPTION_PURCHASED / RENEWED / IN_GRACE_PERIOD / ON_HOLD / CANCELED / EXPIRED / REVOKED …
  收到通知后，服务端再调一次 subscriptionsv2.get 拿完整状态，然后更新权益
```

- 依据：
  - 购买必须在服务端校验后再给权益，并在三天内确认，否则自动退款（[集成结算库](https://developer.android.com/google/play/billing/integrate)）；
  - RTDN 只说明状态变了，完整信息要再调 Developer API 查（[RTDN 参考](https://developer.android.com/google/play/billing/rtdn-reference)）。
- 权益只认服务端：App 不根据本机的购买记录决定能不能上传，统一以服务端的 `402 quota_exceeded`（C7）和账户状态为准。
- 宽限期、账户暂停时按 C7 第五节处理：只读、不删数据。
- 退款、撤销（`SUBSCRIPTION_REVOKED`、作废的购买通知）时进入宽限处理，**不直接删数据**。

### 8.3 Play Integrity API 的接入点

- **只用在我们的收费云端**，自托管模式不调用、不受影响。
- 调用时机：
  - 账户登录、为新设备签发令牌时，把 integrity token 交给服务端校验，防止脚本批量注册和滥用免费额度；
  - 上报购买时一并附上。
- 服务端拿到 `UNRECOGNIZED_VERSION`、`MEETS_DEVICE_INTEGRITY` 不满足等结果时，**只做风险标记和限流，不直接拒绝**。很多掌机和 Root 设备过不了设备完整性检查，而它们正是我们的目标用户。

### 8.4 自托管在 Play 版里的定位

- 在 Play 版里作为**免费的“连接自己的服务器”**：设置 › 云端 提供两个入口，“登录 SaveVault 云端”（收费）和“连接自己的服务器”（C3 配对码或同步密钥）。
- 自托管模式下不显示任何付费入口，也不提示升级，避免被理解成“付费解锁”。
- 商店说明里写清：“可连接你自己部署的开源 SaveVault 云端（game-save），免费；也可以订阅我们托管的 SaveVault 云端。”

---

## 九、内容与知识产权风险

### 9.1 破解版目录识别

| 端 | 现状 | 风险 |
|---|---|---|
| PC | `internal/presets/presets.go:201-216` 内置 16 个 Steam 模拟器 / 破解包装目录预设：Goldberg、GSE、CODEX/PLAZA、RUNE、Tenoke、EMPRESS、Online-Fix、CPY、SmartSteamEmu、SKIDROW、3DM、FLT、ALi、RELOADED 等，类型是 `"repack"`。扫描器注释直接写了 “portable/cracked installs”（`internal/presets/scanner.go:189`、`:383`、`:397`）。根 `README.md:44` 也在功能列表里列出了这些名字 | 不在 Play 上架，不影响这次审查。但 Android 的商店页、官网如果链接到 PC 端，或者把两端放在一起宣传，会被联想到“支持破解版”。建议 PC 端把这些预设移到不默认开启、不在 README 里列名字的“高级 › 自定义目录”里，由 PC 会话决定 |
| Android | **没有**任何破解或盗版目录的识别逻辑。`android/app/src/main` 下搜索这些名字，只在 titledb 的游戏名里出现，例如 “Anime Codex”“Rune Factory”。识别规则只有通用的 PC 容器（GameHub、Winlator、DroidDeck、`pc-game`、Steam `userdata`，见 `SaveSourceDetector.kt:484-555`） | 无 |

### 9.2 商店描述应该避开的说法

- 不要写：“ROM”“破解”“免费玩 Switch 游戏”“安装游戏”“Yuzu/Suyu 必备”，以及任何指向未授权获取游戏的说法；Play 的 IP 政策把商店描述和截图里鼓励未授权获取内容的情况也算作违规。
- 不要把平台方的商标用在标题、图标、宣传图上；不要用游戏封面做宣传图（第 2.3 节）。
- 应该写：“备份和同步你自己的游戏存档”“支持 PPSSPP、RetroArch 等模拟器的存档文件夹”“可选的 Root 模式”，并附上无关联声明。
- 隐私政策和商店页的说法要一致：“本机备份免费；云端同步可连接自己的服务器，或订阅 SaveVault 云端”。

---

## 十、其他上架流程要求

| 项 | 内容 | 来源 |
|---|---|---|
| 个人开发者账号的测试要求 | 2023-11-13 之后创建的个人账号，申请正式发布前要先做封闭测试：**至少 12 名测试者连续选择加入 14 天** | [测试要求](https://support.google.com/googleplay/android-developer/answer/14151465) |
| 组织账号 | 计划收费运营的话，建议用组织账号（需要 D-U-N-S 编号）。可以免掉上面的测试要求，开发者信息也更正式。**需要你决定** | 同上 |
| Android 开发者验证 | 2026-09-30 起，在巴西、印度尼西亚、新加坡、泰国的认证设备上，从参与计划的商店安装的应用要由已验证的开发者注册；侧载暂不受影响，2027 年起全球推行。Play Console 可以统一管理在 Play 内外分发的应用的验证；用了 Play App Signing 的应用会自动认领；一个包名可以登记多把签名密钥。所以 GitHub 版也能在 Play Console 里一并注册；如果按 6.2 让两个渠道用同一把签名密钥，管理最简单 | [开发者验证](https://developer.android.com/developer-verification)、[FAQ](https://developer.android.com/developer-verification/guides/faq)、[Play 政策截止日期](https://support.google.com/googleplay/android-developer/answer/12253906) |
| 包名 | `com.jun9100.opensave` 和 GitHub 版保持一致，便于覆盖升级；显示名是 SaveVault。上架前建议查一下 “SaveVault” 是否和已有商标冲突 | — |

---

## 整改清单

### 上架前必须

| # | 事项 | 章节 | 改动量 | 需要决定 |
|---|---|---|---|---|
| 1 | targetSdk/compileSdk 升到 36：AGP ≥ 8.9.1，Gradle ≥ 8.11.1，按 Android 16 行为变更回归 | 7.1 | M | — |
| 2 | 网络安全配置删掉 `192.168.12.4`，确定 http 策略（推荐：允许 http，并醒目提示“未加密”） | 3 第 8 行 | S | **是** |
| 3 | 隐私政策：新写一份覆盖 Android 的（上传什么、发给谁、封面请求、保留和删除），挂到网页上，App 内加入口 | 4、1.3 | S + S | — |
| 4 | App 内“开源许可”页：SaveVault 的 MIT 声明、104 个依赖、Apache-2.0 全文，构建时生成，不依赖 GMS | 1.1–1.3 | M | 署名行 |
| 5 | 去掉 tinfoil.media 封面来源 | 2.2 | S | — |
| 6 | titledb 和 eShop 封面：Play 版不内置（flavor），无 Root 时显示 TitleID 并允许改名 | 2.1、2.2 | S–M | **是** |
| 7 | Root：决定 Play 版是否保留；保留就加单独的披露和同意页 | 3 第 9 行 | S–M | **是** |
| 8 | 前台服务声明（dataSync）：文案加录屏；实测 6 小时上限的超时处理 | 3 第 2 行 | S + S–M | — |
| 9 | 签名：把现有密钥作为应用签名密钥上传（PEPK），新建上传密钥 | 6.2 | S | **是** |
| 10 | AAB 构建：`bundleRelease`，必要时加 `github`/`play` flavor 和 versionCode 规则 | 6.3、6.4 | M | 跟 6、7 的决定走 |
| 11 | 数据安全表单按第四节填写，和隐私政策逐项一致 | 4 | S | 跟 2 的决定走 |
| 12 | 商店素材：标题和图标不含商标，截图用演示数据，写无关联声明，分类选“工具” | 2.3、9.2 | S | — |
| 13 | 开发者账号：个人账号要先完成 12 人 × 14 天的封闭测试；或者改用组织账号 | 10 | 流程 | **是**（含首发市场） |

### 上架后尽快

| # | 事项 | 章节 | 改动量 |
|---|---|---|---|
| 14 | “解除本设备”：吊销本机令牌并清掉云端配置（服务端加 `POST /v1/auth/device/revoke`） | 5 | M |
| 15 | App 内文案收敛：模拟器名只用于兼容性说明，Yuzu 系分支不在推荐目录里点名，硬编码游戏名改走名称服务 | 2.3 | S–M |
| 16 | 每年 6 月升级 targetSdk；CI 里加 16 KB 对齐检查和 `bundlePlayRelease` 编译检查 | 7.1、7.2、6.4 | S |
| 17 | 封面和游戏名改由云端 C5 提供（替代 titledb，也不再把游戏 ID 发给 Steam 等第三方） | 2.1、2.2、4 | XL |

### 收费云端（C7）上线时（对那一版是阻止项）

| # | 事项 | 章节 | 改动量 |
|---|---|---|---|
| 18 | App 内删除账户入口加网页删除链接，按数据安全表单删除数据，在隐私政策里写明保留期 | 5 | XL（随 C7） |
| 19 | Play 结算 v8+ 订阅；服务端校验、确认，RTDN 加 Pub/Sub，对接 C7 权益接口 | 8.2 | XL |
| 20 | Play Integrity：只在收费云端的登录和购买时用，只做风险标记，不直接拒绝 | 8.3 | M |
| 21 | 数据安全表单更新：邮箱、账户 ID、购买记录 | 4 | S |

### 可选

| # | 事项 | 章节 | 改动量 |
|---|---|---|---|
| 22 | 前台服务改用“用户发起的数据传输任务”，不再申请 dataSync | 3 第 2 行 | M |
| 23 | 配对二维码加 `https://` 形式的 App Link（需要自有域名） | 3 第 6 行 | M |
| 24 | 开启 R8 缩包 | 6.4 | M |
| 25 | PC 端：破解目录预设不默认开启，README 里不列名字（由 PC 会话决定） | 9.1 | S |

### 需要你决定的事

1. **首发市场**：影响隐私合规（GDPR、国内个保法）、C7 登录方式、开发者验证，以及是否需要备案。另外，Play 在中国大陆不可用；国内用户需要另找渠道。
2. **Play 版是否保留 Root 功能**：推荐保留，加披露和同意页；被拒再关掉。
3. **签名方案**：推荐把现有密钥上传作为应用签名密钥，让 Play 版和 GitHub 版签名一致。
4. **titledb 的处理**：推荐 Play 版不内置，GitHub 版保留；长期改由 C5 提供。
5. **http 自托管策略**：推荐允许，并提示“未加密”。
6. **开发者账号类型**：个人（需要封闭测试）还是组织。
7. **开源署名**：SaveVault 自己的版权行怎么写。
