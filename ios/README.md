# iOS（规划中）

iOS 不允许普通 App 读取其他 App 的界面或注入手势，所以 iOS 版不会是手机上的一个 App，而是**电脑端控制器 + WebDriverAgent**：

1. 在 Mac 上编译并签名 [WebDriverAgent](https://github.com/appium/WebDriverAgent)，装到 iPhone；
2. 控制器轮询 WDA `/source` 拿界面树 → 转成 UiNode → 复用 `rules/*.json` 和同一套引擎语义；
3. 命中后通过 WDA `/actions` 执行点击/滑动。

详见 [../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md#5-ios-怎么做)。
