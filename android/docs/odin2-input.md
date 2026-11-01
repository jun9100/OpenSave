# Odin2 实测记录（阶段 0）

测量时间：2026-10-30，设备 AYN Odin2，Android 13，默认显示大小，横屏。

## 显示与窗口

| 项 | 实测值 | 来源 |
|---|---|---|
| 物理分辨率 | 1920×1080（横屏） | `adb shell wm size` |
| 密度 | 369dpi（约 2.31 px/dp） | `adb shell wm density` |
| 应用窗口 | 833×444dp（`sw468dp w833dp h444dp`），`mAppBounds=(0,0)-(1920,1025)` | `dumpsys window` |
| 状态栏 | 55px ≈ 24dp | `ITYPE_TOP_MANDATORY_GESTURES [0,0][1920,55]` |
| 底部手势条 | 55px ≈ 24dp | `ITYPE_BOTTOM_MANDATORY_GESTURES`，导航栏 `mInsetsHint bottom=55` |

结论：

- 计划里推算的 835×470dp 基本正确，不是 960×540dp。去掉状态栏和手势条后，横屏内容高度只有约 420dp（edge-to-edge 时整窗口 468dp）。
- `sw468dp` 小于 600，所以不能按"最短边 ≥ 600 算平板"来判断布局。横屏判断用"宽 ≥ 600dp 且宽 > 高"。
- 竖屏窗口约 468×809dp。

## 手柄

内置手柄以 `Xbox Wireless Controller` 身份上报（`vendor=0x2020 product=0x0112`，`Sources: KEYBOARD | GAMEPAD | JOYSTICK`），使用 `/system/usr/keylayout/Vendor_2020_Product_0112.kl` 和 `Generic.kcm`。

| 物理键 | KeyEvent |
|---|---|
| A / B / X / Y | `BUTTON_A` / `BUTTON_B` / `BUTTON_X` / `BUTTON_Y` |
| L1 / R1 | `BUTTON_L1` / `BUTTON_R1` |
| L2 / R2 | `BUTTON_L2` / `BUTTON_R2` 按键，另有 `LTRIGGER` / `RTRIGGER` 轴 |
| Start / Select | `BUTTON_START` / `BUTTON_SELECT` |
| 摇杆按下 | `BUTTON_THUMBL` / `BUTTON_THUMBR` |
| 方向键 | 布局文件同时映射了 `DPAD_*` 键和 `HAT_X/HAT_Y` 轴；如果上报为 HAT，系统会合成 `DPAD_*` 键事件，应用按 DPAD 处理即可 |
| 摇杆 | 左 `X/Y`，右 `Z/RZ` |
| 独立返回键 / 主页键 | `BACK`（0x9e）、`HOME`（0x66），机身 `gpio-keys` 另有 `HOME` |

结论：

- 有独立的返回键，B 键经 `Generic.kcm` 回退也会产生 `BACK`，所以 `BackHandler` 能同时覆盖两者。
- A 回退为 `DPAD_CENTER`，Compose 的可点击控件能直接响应，不能在 Activity 层拦截 A/B。
- 右摇杆是 `Z/RZ` 轴，不是 `RX/RY`。

## Root 工具

`find -printf` 可用（`su -c 'find /sdcard -maxdepth 1 -printf "%s %T@ %p\n"'` 正常输出），阶段 3 的廉价签名可以直接用它。
