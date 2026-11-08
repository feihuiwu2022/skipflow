# 开发指南

## 目录结构

```
android/   Android 工程（AccessibilityService + 规则引擎 + OCR）
rules/     跨平台共用的广告规则（打包进 APK 的 assets）
samples/   实测采集的广告截图和界面树
docs/      架构设计、iOS 方案、本文件
ios/       iOS（规划中）
```

## 编译安装

需要 JDK 17+（Android Studio 自带的 jbr 即可）和 Android SDK。

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 发布版

发布版用独立的签名密钥。在 `android/keystore.properties`（已被 git 忽略）里写：

```properties
storeFile=C:/path/to/skipflow-release.jks
storePassword=...
keyAlias=skipflow
keyPassword=...
```

然后：

```bash
cd android
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk，发布时改名为 SkipFlow-<版本号>.apk
```

没有 `keystore.properties` 时 `assembleRelease` 会生成未签名的 APK。**密钥丢了就没法发布能覆盖安装的更新**，请备份。

## 用 adb 开关无障碍服务（调试用）

```bash
# 先让 SkipFlow 主界面停在前台（ZTE 等系统否则会拦截启动）
adb shell am start -n com.skipflow.app/.ui.MainActivity
adb shell settings put secure enabled_accessibility_services com.skipflow.app/com.skipflow.app.service.SkipService
adb shell settings put secure accessibility_enabled 1
```

注意：这会覆盖手机上已开启的其他无障碍服务列表。vivo 上偶尔第一次不生效，再执行一次即可。

## 看日志、导出界面树、热更新规则

```bash
# 运行日志（vivo 等系统会屏蔽第三方 App 的 logcat，以文件为准）
adb shell cat /sdcard/Android/data/com.skipflow.app/files/skipflow.log

# 导出当前界面树（在手机上停在广告页时执行），含 OCR 识别到的文字
adb shell am broadcast -a com.skipflow.app.DUMP
adb pull /sdcard/Android/data/com.skipflow.app/files/dumps/

# 热更新规则：推送后广播重新加载，不用重装
adb push rules/default.json /sdcard/Android/data/com.skipflow.app/files/rules.json
adb shell am broadcast -a com.skipflow.app.RELOAD
```

规则写法见 [ARCHITECTURE.md](ARCHITECTURE.md#3-规则格式rulesdefaultjson)。新增规则请在
`android/app/src/test/java/com/skipflow/app/engine/RuleEngineTest.kt` 里补一个用例，
最好用 OCR 实际识别出来的文字（含错字）。

## 各家系统的坑（实测）

- **ZTE / nubia（MyOS）**
  - 自启动被拦：logcat 里 `AutoLaunchManagerService: ... SkipService ... SelfStart BlockResult = true`。
    放行自启动后仍可能因 `android_caller_act_null_black` 被拦，先让 SkipFlow 主界面停在前台再开无障碍。
  - 后台冻结：`CpuFreezerManagerServiceV2 ... frozen-list = [<uid>]`。连前台服务也冻，
    只放过有可见窗口的进程，所以 SkipFlow 常驻一个 1×1 透明的无障碍悬浮窗。
  - 覆盖安装后会重置自启动/后台权限。
- **vivo（OriginOS）**：屏蔽第三方 App 的 logcat；`adb shell settings put` 偶尔要执行两次。
- **通用**：`uiautomator dump` 运行期间会暂停所有无障碍服务，调试用 SkipFlow 自己的导出功能。
