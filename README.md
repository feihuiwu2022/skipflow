# SkipFlow · 短剧广告自动续播

看红果短剧时，广告一允许上滑或跳过，SkipFlow 就自动帮你上滑或点"跳过"；倒计时广告期间自动静音。你只管看剧。

- 不破解广告，不跳过倒计时，不修改红果，**没有网络权限**
- 广告识别 = 无障碍界面树 + 屏幕文字识别（OCR）。截图只在内存里识别屏幕上下两条区域，不保存、不上传
- 规则写在 JSON 里，App 改版后改规则就行，不用重新发版
- 目前支持：Android 11+ 完整功能（OCR 依赖系统截屏接口），Android 8~10 只有界面树识别。
  已实测：vivo V2443A / Android 15（上滑广告自动划走 ✅），ZTE 7534N / Android 11。iOS 方案见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#5-ios-怎么做)

> ⚠️ 仅供个人学习和自用。自动化操作可能违反目标 App 的用户协议，可能被检测，账号风险自负。请勿商用或打包分发。

## 目录

```
android/   Android 工程（AccessibilityService + 规则引擎）
rules/     跨平台共用的广告规则（打包进 APK 的 assets）
samples/   实测采集的广告界面样本
docs/      架构设计、iOS 方案
ios/       iOS（规划中）
```

## 编译安装

需要 JDK 17+（Android Studio 自带的 jbr 即可）和 Android SDK。

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

装好后打开 SkipFlow →「打开无障碍设置」→ 开启「SkipFlow 广告自动处理」。
国产 ROM 还必须**允许 SkipFlow 自启动、允许后台运行**，否则无障碍开关显示"已开启"，服务却根本没启动：

- ZTE / nubia（MyOS）：设置 → 应用 → SkipFlow → 允许「自启动」「关联启动」「后台运行」。
  症状：logcat 里 `AutoLaunchManagerService: ... SkipService ... SelfStart BlockResult = true`，
  或 `CpuFreezerManagerServiceV2 ... frozen-list` 里有 SkipFlow 的 uid。
- 小米 / 华为 / OPPO / vivo：同理，在"自启动管理""电池优化"里放行。
- 每次覆盖安装后，部分 ROM 会把这些权限重置，需要重新放行并重开无障碍开关。
- ZTE 实测：即使放行了自启动，系统绑定服务时仍可能被拦（`android_caller_act_null_black`）。
  **先打开 SkipFlow 主界面，让它停在前台，再去开无障碍开关**就能启动；启动后前台服务会让它保持运行。

- vivo（OriginOS）实测：`adb shell settings put` 打开无障碍偶尔第一次不生效，再执行一次即可；
  vivo 会限制 App 的 logcat 输出，看运行情况以 SkipFlow 主界面里的日志为准。
- 注意：`uiautomator dump` 运行期间会暂停所有无障碍服务，调试时用 SkipFlow 自己的导出功能。

服务正常运行时，通知栏会有一条常驻的「SkipFlow 运行中」。

## 调试和补规则

```bash
# 查看运行日志
adb logcat -s SkipFlow

# 导出当前界面树（在手机上停在广告页时执行）
adb shell am broadcast -a com.skipflow.app.DUMP

# 拉取导出的界面树
adb pull /sdcard/Android/data/com.skipflow.app/files/dumps/

# 热更新规则：推送后广播重新加载
adb push rules/default.json /sdcard/Android/data/com.skipflow.app/files/rules.json
adb shell am broadcast -a com.skipflow.app.RELOAD
```

规则写法见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#3-规则格式rulesdefaultjson)。
新增规则请在 `android/app/src/test/.../RuleEngineTest.kt` 里补一个用例。
