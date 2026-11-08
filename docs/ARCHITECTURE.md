# SkipFlow 架构设计

目标：沉浸式看短剧时，**广告允许跳过/上滑的那一刻自动帮你操作**；倒计时期间等待（可选静音，默认关闭）。
不破解广告、不跳过倒计时、不改目标 App、不联网。

## 1. 总体分层

```
┌──────────────── 平台层（每个平台各写一份）────────────────┐
│ 采集：拿到当前界面树           执行：点击 / 滑动 / 返回 / 静音 │
│ Android = AccessibilityService  iOS = 见第 5 节              │
└───────────────┬──────────────────────────▲─────────────────┘
                │ UiNode 快照                │ Action
┌───────────────▼──────────────────────────┴─────────────────┐
│ 引擎层（纯逻辑，无平台依赖，可单测）                          │
│  Selector → RuleEngine（哪条规则命中）→ AdSession（该不该动手）│
└───────────────▲────────────────────────────────────────────┘
                │ JSON
        rules/default.json（跨平台共用的规则）
```

| 代码 | 职责 |
|---|---|
| `engine/UiNode.kt` | 界面树快照、`isOnScreen()` 过滤离屏/隐藏节点 |
| `engine/Selector.kt` | 单个节点的匹配条件（id/text/desc/label/正则/类名/区域） |
| `engine/Rule.kt` | 规则、动作、规则集的 JSON 解析 |
| `engine/RuleEngine.kt` | 按顺序找第一条命中的规则（顺序 = 优先级） |
| `engine/AdSession.kt` | 状态机：delay → 动手 → 冷却 → 重试 → 放弃 |
| `service/SkipService.kt` | 事件驱动 + 600ms 轮询；快照；调度；调试导出 |
| `service/ActionExecutor.kt` | `performAction(CLICK)`，失败退回到 `dispatchGesture` |
| `service/AudioController.kt` | 广告期间静音，只恢复自己静的音 |
| `service/RuleRepository.kt` | 规则来源：外部 `rules.json` 优先，否则 APK 内置 |

## 2. 为什么是"状态机 + 计时"，不是 sleep

广告出现时机、可跳过时机都不固定。每 600ms（或界面事件触发）拍一次快照：

```
没有命中 ──────────────────────────────► Idle（恢复音量）
命中规则 R（新出现）→ 记下 firstSeen
  ├─ R 是 wait 型（倒计时）────────────► Waiting（开了静音选项才静音）
  ├─ 距 firstSeen < delayMs ───────────► Waiting
  ├─ 上次动手后 < cooldownMs ─────────► Waiting
  ├─ 已经试了 maxAttempts 次 ──────────► GaveUp（导出界面树，等广告自己消失）
  └─ 否则 ────────────────────────────► Act（attempt++）
```

`maxAttempts`（默认 2）是防误伤的关键：如果界面树没及时刷新，
最多多滑一次，而不是一路把正片划走。

## 3. 规则格式（`rules/default.json`）

```jsonc
{
  "key": "swipe-continue",
  "name": "上滑继续观看短剧",
  "all":  [{ "textRegex": "上滑继续观看" }],   // 每个都要命中（可写多个特征）
  "any":  [],                                  // 至少命中一个（可选）
  "none": [],                                  // 一个都不能命中（防误伤）
  "action": { "type": "swipe", "from": [0.5, 0.8], "to": [0.5, 0.2], "durationMs": 500 },
  "delayMs": 800, "cooldownMs": 2500, "maxAttempts": 2, "mute": false
}
```

动作：`click`（点 all[target] 命中的节点）/ `tap` / `swipe` / `back` / `wait`。
选择器字段见 `Selector.kt` 注释。坐标全部用 0~1 的屏幕比例，跨分辨率通用。

**写规则的原则**

- 优先用文字（`上滑继续观看`），其次才用 id：字节系 id 是混淆过的（`aq9`），每次发版都可能变。
- 文字常放在 `content-desc` 里，用 `label` / `labelRegex` 同时匹配 text 和 desc。
- 能用 `none` 排除的就排除，比如"跳过"按钮在倒计时期间不点。

## 4. 规则维护闭环

1. 开着"疑似广告自动导出"，正常看剧；
2. 碰到没处理的广告，服务会把界面树导出到
   `/sdcard/Android/data/com.skipflow.app/files/dumps/`（`.txt` 精简版 + `.json` 完整版）；
3. `adb pull` 下来看，补一条规则到 `rules/default.json`，并加一个单测（`RuleEngineTest`）；
4. `adb push` 成 `files/rules.json` + 广播 RELOAD 热更新验证，没问题再提交。

已知实测（2026-11-08）：

- **界面树不可信**：ZTE 7534N / Android 11 上，广告在屏幕上时广告节点却是 hidden、高度为 0
  （`samples/zte-ad-swipe.tree.txt`）；另一次正好相反，划到正片后广告节点仍以"可见"留在树里。
  vivo V2443A / Android 15 上同一种广告的树又是准的（`samples/vivo-ad-swipe.tree.txt`）。
  所以"上滑继续"规则只认 OCR 节点（`cls: "#ocr"`）。
- **OCR 性能**：低端机整屏 720×1600 识别 1~4 秒，截屏本身 1~2 秒。只识别顶部 12% 和底部 15%
  两条区域（`ocr.regions`），结果按截屏时刻算龄，留 3 秒余量。
- 上滑型广告：底部文字「上滑继续观看短剧」，广告信息（`商家推荐`、广告文案等）在 content-desc 里。
- `input swipe` 250ms 的短快滑被忽略，500ms、屏幕 80%→20% 的滑动才翻页。
- 视频播放时 `uiautomator dump` 经常报 idle 错误或返回旧树，且运行期间会暂停所有无障碍服务。
- ML Kit 会合并进 INTERNET 权限做统计上报，manifest 里用 `tools:node="remove"` 去掉了。
- **提速后踩到的坑**（vivo / ZTE 实测，已修）：
  - ML Kit 的 success / complete 回调顺序不固定，曾导致"结果已回调但文字还没加进来"，白等一轮；
  - 缩到 0.5 倍识别会把"上滑"认成"土滑"，且没变快；改为把上下两条拼成一张图识别一次，单次调用开销只付一次；
  - OCR 常错一个字（"观春短剧""止滑"），规则改成"滑继续|看短剧|继续观.短剧"之一即可，只在底部条带生效；
  - 倒计时型广告显示"N秒后可继续上滑观看短剧"，期间滑不动：上滑规则用 `none` 排除"N秒后"，
    快速路径也按完整优先级匹配，倒计时规则先命中就等；
  - **连滑两次漏一集**：滑动动画期间截到的图里广告还在，冷却一过就凭这张旧图再滑。现在动作后
    "滑动时长 + 700ms"内不截图、也不认这段时间截的图；
  - 截屏要 0.5~2 秒，期间可能切到别的 App：截图回来时再确认一次前台，不是目标 App 就丢弃不识别；
  - 拍界面树快照在 vivo 上要 ~1 秒：纯 OCR 规则走快速路径，识别完到动手从 ~1 秒降到 10~40ms。
- 实测效果（ZTE 7534N，3 条 7 秒倒计时广告）：倒计时结束、"上滑继续观看短剧"出现后 0.2~0.6 秒自动上滑，无重复上滑。

## 2.1 识别管线

```
无障碍界面树 ──┐
               ├─► 合并成一棵 UiNode 树 ─► RuleEngine ─► AdSession ─► ActionExecutor
截屏 → 裁剪顶/底条带 → ML Kit 中文 OCR ─► #ocr 节点 ──┘
```

## 5. iOS 怎么做

iOS 沙箱不允许普通 App 读取其他 App 的界面或注入手势，**没有 AccessibilityService 的等价物**。可选方案：

| 方案 | 能力 | 代价 | 结论 |
|---|---|---|---|
| A. 电脑端驱动：WebDriverAgent（XCUITest）通过 USB/Wi-Fi 读界面树、点击、滑动 | 和安卓一样：界面树 + 手势，可直接复用 `rules/*.json` | 需要 Mac 编译签名 WDA，手机要连着电脑（或同局域网） | **推荐作为 iOS 第一版**：适合"手机架在支架上、电脑在旁边"的场景 |
| B. 辅助功能「切换控制」/「辅助触控」录制手势 + 快捷指令 | 一键执行"上滑" | 不能自动识别广告，需要手动触发 | 零开发的兜底方案，写进使用文档 |
| C. 越狱插件（Tweak） | 完全能力 | 越狱门槛高、安全风险大 | 不做 |
| D. 屏幕录制 + 视觉识别 | 只能"看"，iOS 上依然不能替你点 | — | 不可行 |

所以 iOS 侧的工程形态是一个**桌面端控制器**（建议 Python/Go，跑在电脑上）：
`WDA /source` 拿界面树 → 转成 `UiNode` → 同一套规则引擎 → `WDA /actions` 执行。
引擎层要么用 Kotlin Multiplatform 编到 JVM 桌面端复用，要么按本文档的语义另写一份（逻辑不到 200 行），
共享的是 `rules/*.json` 和单测用例。

同一个桌面端控制器也能通过 adb 驱动安卓手机，作为"不想装 App 的安卓用户"的备选。

## 6. 隐私与风险边界

- 没有网络权限；只订阅规则里列出的包名的无障碍事件；只在目标 App 前台时截屏，截图只在内存中做 OCR，不落盘。
- 只在广告"允许操作"时操作（点跳过、上滑），不篡改广告、不伪造播放完成。
- 违反目标 App 用户协议的风险依然存在（可能被检测无障碍服务、限制金币奖励等），README 里写清楚。
- 不上架应用商店，以源码 + 自行编译/Release APK 的方式分发。
