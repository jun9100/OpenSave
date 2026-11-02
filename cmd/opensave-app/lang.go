package main

import (
	"strings"
	"sync"
)

// The language of what the Go side shows itself: the tray menu, its status
// line and the native file dialogs. The window has its own dictionary
// (frontend/src/lib/i18n.js) and tells this one which language it settled
// on through SetLanguage, so a choice made in Settings reaches the tray
// too; until it does, the system's language is used.
//
// English is the key, as in the window: T("Quit") is "Quit" in English and
// the dictionary's entry otherwise, and a string with no entry stays English.

var (
	langMu     sync.RWMutex
	uiLanguage = "en"
	// onLanguage is told when the language changes (the tray relabels).
	onLanguage func()
)

// zhCN is the tray's and dialogs' Chinese. Format verbs are kept in order.
var zhCN = map[string]string{
	"SaveVault — game save sync":                          "SaveVault — 游戏存档同步",
	"Starting…":                                           "正在启动…",
	"Where your saves stand":                              "存档状态",
	"Recent activity":                                     "最近动态",
	"The last few things that happened":                   "最近发生的几件事",
	"Open Activity":                                       "打开动态",
	"See everything that happened":                        "查看全部动态",
	"Open SaveVault":                                      "打开 SaveVault",
	"Show the SaveVault window":                           "显示 SaveVault 窗口",
	"Sync all games":                                      "同步所有游戏",
	"Sync every tracked game now":                         "立即同步所有已跟踪的游戏",
	"Snapshot every game now":                             "立即为所有游戏创建快照",
	"Take a snapshot of every tracked game":               "为每个已跟踪的游戏创建快照",
	"Pause syncing":                                       "暂停同步",
	"Stop syncing for a while; snapshots are still taken": "暂时停止同步；快照仍会照常创建",
	"Resume syncing":                                      "恢复同步",
	"Start syncing again and catch up":                    "重新开始同步并补上进度",
	"Quit":                                                "退出",
	"Stop syncing and exit":                               "停止同步并退出",
	"For 15 minutes":                                      "15 分钟",
	"For 1 hour":                                          "1 小时",
	"For 3 hours":                                         "3 小时",
	"Until I resume":                                      "直到我手动恢复",

	"1 game needs a decision":         "1 个游戏需要处理",
	"%d games need a decision":        "%d 个游戏需要处理",
	"Syncing paused until you resume": "同步已暂停，直到你手动恢复",
	"Syncing paused · %s":             "同步已暂停 · %s",
	"Syncing %s…":                     "正在同步 %s…",
	"Syncing %d games…":               "正在同步 %d 个游戏…",
	"No games tracked yet":            "还没有跟踪任何游戏",
	"Watching 1 game":                 "正在监视 1 个游戏",
	"Watching %d games":               "正在监视 %d 个游戏",
	"%dh left":                        "还剩 %d 小时",
	"%dh %dm left":                    "还剩 %d 小时 %d 分钟",
	"under a minute left":             "还剩不到 1 分钟",
	"%dm left":                        "还剩 %d 分钟",

	"Select folder":                                "选择文件夹",
	"Select file":                                  "选择文件",
	"Select backup file":                           "选择备份文件",
	"Programs (*.exe;*.bat)":                       "程序 (*.exe;*.bat)",
	"All files":                                    "所有文件",
	"SaveVault backup (*.sscb)":                    "SaveVault 备份 (*.sscb)",
	"Could not open the file manager: ":            "无法打开文件管理器：",
	"No save location set for this game.":          "这个游戏没有设置存档位置。",
	"That folder no longer exists on this device.": "这台设备上已经没有这个文件夹了。",
	"Could not open the folder: ":                  "无法打开文件夹：",
}

// T is s in the current language.
func T(s string) string {
	langMu.RLock()
	defer langMu.RUnlock()
	if uiLanguage == "zh-CN" {
		if v, ok := zhCN[s]; ok {
			return v
		}
	}
	return s
}

// normalizeLanguage maps a language tag to one this build speaks.
func normalizeLanguage(tag string) string {
	t := strings.ToLower(strings.TrimSpace(tag))
	if strings.HasPrefix(t, "zh") {
		return "zh-CN"
	}
	return "en"
}

func setUILanguage(tag string) {
	lang := normalizeLanguage(tag)
	langMu.Lock()
	changed := lang != uiLanguage
	uiLanguage = lang
	notify := onLanguage
	langMu.Unlock()
	if changed && notify != nil {
		notify()
	}
}

// initUILanguage starts out in the system's language, before the window has
// said which it uses.
func initUILanguage() {
	if tag := systemLanguage(); tag != "" {
		setUILanguage(tag)
	}
}

// SetLanguage is called by the window with the language it is shown in
// ("zh-CN" or "en"), so the tray and dialogs match it.
func (a *App) SetLanguage(tag string) {
	setUILanguage(tag)
}
