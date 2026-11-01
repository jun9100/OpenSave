package main

import (
	"fmt"
	"strings"
	"testing"
)

func TestTranslationsKeepFormatVerbs(t *testing.T) {
	for en, zh := range zhCN {
		if strings.Count(en, "%") != strings.Count(zh, "%") {
			t.Errorf("%q → %q: format verbs differ", en, zh)
		}
	}
}

func TestTrayStatusInChinese(t *testing.T) {
	setUILanguage("zh-Hans-CN")
	defer setUILanguage("en")
	if got := trayStatus(trayFacts{Games: 6}); got != "正在监视 6 个游戏" {
		t.Errorf("trayStatus = %q", got)
	}
	if got := T("Quit"); got != "退出" {
		t.Errorf("T(Quit) = %q", got)
	}
	if got := fmt.Sprintf(T("%dh %dm left"), 1, 30); got != "还剩 1 小时 30 分钟" {
		t.Errorf("minutes = %q", got)
	}
}

func TestLanguageChangeIsAnnounced(t *testing.T) {
	calls := 0
	langMu.Lock()
	onLanguage = func() { calls++ }
	langMu.Unlock()
	defer func() {
		langMu.Lock()
		onLanguage = nil
		langMu.Unlock()
		setUILanguage("en")
	}()
	setUILanguage("zh-CN")
	setUILanguage("zh-CN") // no change, no call
	setUILanguage("en-US")
	if calls != 2 {
		t.Errorf("onLanguage called %d times, want 2", calls)
	}
}
