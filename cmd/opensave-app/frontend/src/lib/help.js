// The Help page's text (views/Help.svelte), one whole version per language.
//
// Long-form help reads badly translated a phrase at a time, so unlike the
// rest of the app it is not keyed by its English: each language has its own
// copy, written to be read as a page. Each section is a title and a list of
// blocks — a paragraph, numbered steps, or a tip set apart.


const zh = [
  {
    id: 'start',
    title: '安装与启动',
    blocks: [
      { p: 'OpenSave 会自动为游戏存档创建快照（版本历史），并可在你的多台设备之间同步、备份到云端。它完全在本机运行，不需要注册账号。' },
      {
        steps: [
          'Windows：运行安装包 OpenSave.Setup.exe（会安装到“程序”并创建开始菜单项），或直接运行免安装版 OpenSave.exe。首次运行时 Windows 防火墙可能会询问，请允许“专用网络”，否则局域网中的其他设备无法发现这台电脑。',
          '关闭窗口不会退出程序：OpenSave 会缩到系统托盘（右下角）继续在后台工作。要彻底退出，请在托盘图标上右键选择“退出”。',
          '想开机自动运行，在 设置 → 常规 → 启动 中勾选“开机时启动 OpenSave”。',
          '界面语言默认跟随系统；可在 设置 → 常规 → 外观 → 语言 中切换为简体中文或 English，切换后立即生效。'
        ]
      },
      { tip: '如果启动时提示“无法连接到后台服务”，通常是防火墙/杀毒软件拦截了本地连接，或者还有一个旧的 OpenSave 进程在运行。检查托盘和任务管理器后点击“重试”。日志位于用户文件夹下的 .opensave\\opensave.log。' }
    ]
  },
  {
    id: 'add',
    title: '添加游戏与模拟器',
    blocks: [
      { p: '主页右上角有三种添加方式：' },
      {
        steps: [
          '扫描存档：自动检查 Steam、Epic、GOG、常见模拟器（RetroArch、Dolphin、Ryujinx、Yuzu/Eden、Citra、PCSX2、RPCS3、PPSSPP、Cemu、Xenia 等）以及常见的整合版/免 Steam 存档目录。在结果中勾选要管理的项目，点“跟踪所选”。',
          '跟踪文件夹：手动选择任意存档文件夹；也可以在对话框里改为“跟踪单个文件”。',
          '拖放：把存档文件夹直接拖进窗口即可添加，添加前可以修改显示名称。'
        ]
      },
      { p: '扫描结果里，每个位置都会显示文件数、大小和最后修改时间——最近修改的那个通常就是正在使用的存档。同一个游戏出现在多个位置时会合并成一张卡片，标注“在 N 个文件夹中找到”，点开可逐个勾选。' },
      { p: '模拟器：扫描结果顶部可以按“游戏 / 模拟器 / 整合版”筛选。模拟器的存档目录通常包含所有游戏，按整个目录跟踪即可；Switch 模拟器会按 Title ID 自动显示游戏封面。' },
      { p: '存档分散在多个文件夹（例如存档和设置分开）时，在游戏页 → 配置 → 存档位置 中添加附加文件夹，它们会一起快照、同步和恢复。不想同步的设备相关文件（如显卡设置）可以在“不同步的文件”中排除——它们仍会被快照备份。' },
      { tip: '扫描时总是出现不想要的旧目录？在扫描结果里点“排除”，或在 设置 → 存储 → 游戏扫描 中添加排除文件夹。' }
    ]
  },
  {
    id: 'snapshots',
    title: '快照（版本历史）',
    blocks: [
      { p: '快照就是存档在某个时间点的完整压缩包。OpenSave 会在这些时候自动创建：开始跟踪时、检测到存档变化时、游玩结束后、同步替换文件之前、发生冲突时。你也可以随时手动创建。' },
      {
        steps: [
          '手动创建：游戏页 → 快照 标签，填写备注（可选）后点“立即创建快照”；或在游戏卡片上右键选择“立即创建快照”。',
          '固定：点快照上的图钉，它就不会被上限或自动清理删除。',
          '备注：给重要节点写上说明，例如“打 Boss 之前”。',
          '比较：选择两个快照查看哪些文件不同。',
          '分支：想开二周目又不想覆盖主线？在“分支”标签页新建分支，每个分支有独立的存档线，可随时切换。'
        ]
      },
      { p: '保留数量：设置 → 存储 → 快照历史 中设置每个游戏保留的自动快照和手动快照数量（两者配额独立，频繁自动存档的游戏不会挤掉你的手动快照）。“空间占用”可以查看并清理占用最多的快照。' }
    ]
  },
  {
    id: 'restore',
    title: '恢复存档',
    blocks: [
      {
        steps: [
          '恢复整个存档：游戏页 → 快照，在要回到的快照上点“恢复”。恢复前会先和当前存档比较，告诉你哪些文件会变化。',
          '只恢复单个文件：在快照上点“文件”，找到文件后点“恢复文件”。',
          '快速恢复最新快照：在游戏卡片上右键 → “恢复最新快照…”。',
          '从云端恢复：游戏页 → 云端 标签，或 云备份 → 浏览云端，选择快照后“下载并恢复”。'
        ]
      },
      { tip: '每次恢复前，当前存档都会先自动保存为一个“安全副本”快照，所以恢复操作总是可以撤销。请在游戏退出后再恢复，避免游戏在运行中覆盖文件。' }
    ]
  },
  {
    id: 'cloud',
    title: '云端备份（WebDAV 与 game-save）',
    blocks: [
      { p: '云备份是可选的：开启后新快照会自动上传一份到你选择的位置，换电脑或硬盘损坏时可以取回。设置位置在侧边栏“云备份”页面。支持 Google Drive、OneDrive、Dropbox、本地/NAS 文件夹、WebDAV、自建 game-save 服务和 HTTP Webhook。' },
      { p: '使用自建 game-save 服务（推荐，原生同步接口）：' },
      {
        steps: [
          '在“云备份”页面选择“game-save”。',
          '服务地址填写 game-save 的根地址，例如 https://nas.example:8788（OpenSave 会自动拼接 /v1/opensave 下的原生接口，不使用 WebDAV）。',
          '同步密钥填写服务端的同步密钥（GAME_SAVE_TOKEN）。',
          '保存服务设置并打开“自动”。每个游戏按存档类型（PC、Switch、各模拟器）放在各自的命名空间里，同一服务器上不同类型的存档互不干扰。',
          '在游戏页 → 云端 标签可以看到云端权威版本、本机已确认的版本、可恢复的历史版本和保留的冲突副本；“发布当前快照”会通过版本链（CAS）把本机当前快照推进为云端最新版本。'
        ]
      },
      { tip: '如果另一台设备已经推进了云端版本，本机的发布不会覆盖它，而是作为“冲突副本”保留，需要你在游戏的云端标签页确认后再恢复或发布。本地存档有改动但还没有快照时，请先创建快照再发布。' },
      { p: '使用 WebDAV（Nextcloud、群晖/NAS、坚果云等）：选择“WebDAV 服务器”，地址填写一个专用文件夹，例如 https://nas.local/dav/opensave/，再填写用户名和密码。' },
      { p: '“从我的其他设备取回更新的存档”：开启后，如果另一台设备上传的存档是在本机存档基础上继续玩的，且本机之后没有改动，就会自动替换；其他情况会先询问。' },
      { tip: '上传失败时，失败原因会显示在提示和“动态”页中，常见原因：地址错误（404）、密钥或密码错误（401）、文件超过服务器大小限制（413）、云端存档有冲突、网络不通。批量上传时每个游戏的失败原因都会单独列出。' }
    ]
  },
  {
    id: 'bulk',
    title: '批量操作',
    blocks: [
      { p: '主页游戏库可以一次对多个游戏执行操作。' },
      {
        steps: [
          '进入选择模式：点游戏库右上角的“选择”，或按住 Ctrl 点击 / 长按任意游戏卡片。',
          '选择游戏：点击卡片勾选或取消；按住 Shift 点击可选中一个范围。工具栏中“全选”选中当前显示的游戏——如果设置了搜索或筛选，就是“全选筛选结果”；“整个游戏库”选中全部已跟踪游戏；“反选”反转当前显示范围内的选择。',
          '执行操作：多选工具栏下方可以批量 创建快照、上传到云端、恢复最新快照、立即同步、开启/关闭自动同步、加入收藏、停止跟踪。',
          '查看进度与结果：批量操作逐个执行，窗口显示进度和当前游戏，可以随时“停止”（正在处理的游戏会先完成）。完成后显示汇总：成功、失败（附失败原因）、跳过（附跳过原因，例如“没有可恢复的快照”“已经是开启状态”），可以复制结果或“重试失败的”。'
        ]
      },
      { tip: '“恢复最新快照”和“停止跟踪”属于危险操作，执行前会弹窗二次确认。恢复前每个游戏的当前存档都会先保存为快照；停止跟踪不会删除磁盘上的存档文件和快照，也不会删除云端副本。' }
    ]
  },
  {
    id: 'devices',
    title: '多设备同步',
    blocks: [
      {
        steps: [
          '同一局域网：在另一台设备上也运行 OpenSave，它会自动出现在“设备”页，点“配对”并在对方设备上批准即可。',
          '不同网络：在 设备 → 通过互联网 中，两台设备输入相同的房间码加入同一个中继房间，再完成配对。通过中继传输的存档端到端加密。',
          '存档变化后会自动同步到所有已配对设备。两台设备同时修改时会提示冲突，可选择保留哪一份或两者都保留（另一份放到分支上）。'
        ]
      }
    ]
  },
  {
    id: 'faq',
    title: '常见问题',
    blocks: [
      { p: '游戏状态显示“存档文件夹丢失”：可能是移动了游戏、换了盘符或硬盘未连接。恢复后一分钟内会自动重新识别；如果存档确实换了位置，在游戏页点“编辑”重新指定。' },
      { p: '某个设备找不到：确认两台设备在同一网段、防火墙已允许 OpenSave（专用网络），或改用互联网中继。' },
      { p: '想反馈问题：打开“动态”页，切换到“日志”，点“复制”即可把技术日志附在反馈中。' }
    ]
  }
];

const en = [
  {
    id: 'start',
    title: 'Installing and starting',
    blocks: [
      { p: 'OpenSave keeps snapshots (a version history) of your game saves, syncs them between your devices and can back them up to the cloud. It runs on this machine; there is no account.' },
      {
        steps: [
          'Windows: run OpenSave.Setup.exe, or the portable OpenSave.exe. If Windows Firewall asks on first run, allow private networks so your other devices can find this PC.',
          'Closing the window does not quit: OpenSave keeps working from the system tray. Right-click the tray icon and choose Quit to stop it.',
          'To start with Windows, tick “Start OpenSave when the computer starts” under Settings → General → Startup.',
          'The language follows your system. Change it under Settings → General → Appearance → Language; it takes effect at once.'
        ]
      },
      { tip: 'If the window says it can’t reach the background service, a firewall or antivirus is usually blocking local connections, or an old OpenSave is still running. Check the tray and Task Manager, then press Retry. The log is .opensave\\opensave.log in your user folder.' }
    ]
  },
  {
    id: 'add',
    title: 'Adding games and emulators',
    blocks: [
      {
        steps: [
          'Scan saves looks through Steam, Epic, GOG, common emulators (RetroArch, Dolphin, Ryujinx, Yuzu/Eden, Citra, PCSX2, RPCS3, PPSSPP, Cemu, Xenia…) and repack save folders. Tick what you want and press Track selected.',
          'Track folder picks any save folder by hand, or a single save file.',
          'Drop a save folder on the window to add it.'
        ]
      },
      { p: 'Each result shows its file count, size and last change — the recent one is usually the live save. A game found in several places is one tile marked “found in N folders”. Filter the results by Games, Emulators or Repacks; an emulator is usually tracked as its whole save folder.' },
      { p: 'A save split across folders: add the extra ones under the game’s Configuration → Save locations. Device-specific files can be kept from syncing under “Files that shouldn’t sync”; snapshots still include them.' }
    ]
  },
  {
    id: 'snapshots',
    title: 'Snapshots',
    blocks: [
      { p: 'A snapshot is a complete archive of the save at one moment. OpenSave takes them when tracking starts, when the save changes, after playing, before a sync replaces files and at a conflict. Take one yourself from the game’s Snapshots tab or its right-click menu.' },
      { steps: ['Pin a snapshot to keep it through every limit and clean-up.', 'Add a note to remember what it was.', 'Compare two snapshots to see which files differ.', 'Branches keep a second playthrough apart from the main one.'] },
      { p: 'Settings → Storage sets how many automatic and manual snapshots each game keeps, and shows what takes the space.' }
    ]
  },
  {
    id: 'restore',
    title: 'Restoring',
    blocks: [
      { steps: ['Whole save: Snapshots tab → Restore on the snapshot to go back to. You see what changes first.', 'One file: Files on the snapshot → Restore file.', 'Newest snapshot: right-click the game → Restore latest snapshot…', 'From the cloud: the game’s Cloud tab, or Cloud Backup → Browse cloud.'] },
      { tip: 'The current save is kept as a snapshot before every restore, so a restore can always be undone. Restore with the game closed.' }
    ]
  },
  {
    id: 'cloud',
    title: 'Cloud backup (WebDAV and game-save)',
    blocks: [
      { p: 'Optional: with it on, new snapshots are also uploaded. Set it up on the Cloud Backup page — Google Drive, OneDrive, Dropbox, a local/NAS folder, WebDAV, a self-hosted game-save service or an HTTP webhook.' },
      {
        steps: [
          'game-save: choose “game-save”, enter the service address (https://nas.example:8788) and its sync key (GAME_SAVE_TOKEN). OpenSave talks to its native API under /v1/opensave; WebDAV is not used.',
          'Each game is kept in its save type’s namespace (PC, Switch, each emulator), so different kinds of saves on one server never mix.',
          'A game’s Cloud tab shows the authoritative cloud version, the version this device confirmed, restorable history and kept conflict copies. “Publish current snapshot” advances the cloud version through the version chain (CAS).',
          'WebDAV (Nextcloud, a NAS): enter a dedicated folder URL, a username and a password.',
          'Save the provider, then turn on Automatically. Older snapshots can be sent with Browse cloud → Upload local snapshots, or for many games at once with the library’s batch Upload to cloud.'
        ]
      },
      { tip: 'If another device moved the cloud version on first, this device’s publish is kept as a conflict copy instead of overwriting it; review it in the game’s Cloud tab. Take a snapshot before publishing a save that changed since the last one.' },
      { tip: 'Failed uploads say why in the message and in Activity — a wrong address (404), a wrong key or password (401), a file over the server’s size limit (413), a cloud conflict, or no connection — and per game in a batch upload.' }
    ]
  },
  {
    id: 'bulk',
    title: 'Batch actions',
    blocks: [
      {
        steps: [
          'Press Select in the library, or Ctrl-click / long-press a tile.',
          'Click tiles to pick them; Shift-click picks a range. “Select all” takes what is shown — the filtered results when you have searched or filtered; “Whole library” takes every game; “Invert” flips the picks among those shown.',
          'Act on them: Snapshot, Upload to cloud, Restore latest, Sync, Turn sync on/off, Favourite, Stop tracking.',
          'Batches run one game at a time with progress, can be stopped between games, and end with a summary: done, failed with the reason, skipped with why. Copy it, or retry the failed ones.'
        ]
      },
      { tip: 'Restore latest and Stop tracking ask first. Restoring keeps each current save as a snapshot; stopping tracking deletes no save files, snapshots or cloud copies.' }
    ]
  },
  {
    id: 'devices',
    title: 'Syncing between devices',
    blocks: [
      { steps: ['Same network: run OpenSave on the other device; it appears under Devices — pair and approve it there.', 'Different networks: join the same room code under Devices → Over the internet, then pair. Relay traffic is end-to-end encrypted.', 'Changed saves sync to every paired device. If both changed, you choose which to keep, or keep both on a branch.'] }
    ]
  },
  {
    id: 'faq',
    title: 'Questions',
    blocks: [
      { p: '“Save folder missing”: the game moved, a drive letter changed or a drive is disconnected. It is picked up again within a minute of coming back; otherwise point the game at its new folder with Edit.' },
      { p: 'Reporting a bug: Activity → Log → Copy puts the technical log on the clipboard.' }
    ]
  }
];

/** The help sections for a shown locale ('zh-CN' or 'en'). */
export const helpFor = (locale) => (locale === 'zh-CN' ? zh : en);
