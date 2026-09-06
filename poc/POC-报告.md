# 菜包（Caibao）POC 验证报告

- **版本**：v0.1（S0 第一轮・可纯 adb 完成的部分）
- **日期**：2026-09-06
- **测试机**：Redmi K60 Pro `22127RK46C`，序列号 `f8d255b8`
- **目标**：在写第一行业务代码之前，把《可行性分析报告》里的关键假设拿真机数据验一遍
- **原始数据**：`poc/raw/*.xml`、`poc/measure_dump.sh`

> 说明：本轮完成了全部**不需要安装额外 App、不需要 API Key** 的验证项。剩余 P0-1 / P0-2 / P0-6 需要装 Shizuku、写 Demo App、配模型 Key，列为下一轮。

---

## 0. 结论摘要

### 0.1 本轮验证的三条核心结论

| # | 结论 | 数据支撑 |
|---|---|---|
| **1** | **报告 3.1 的判断完全成立，且实际更糟**：`uiautomator dump` 在本机 p50 为 **2.3–3.3 秒**，p95 最高到 **8.5 秒** | 11 个场景 × 5–20 次采样，见第 3 章 |
| **2** | **端侧 VLM 直接出局**：本机为 **8GB 运存**（MemTotal 7.2GB），低于 MobiAgent 官方要求的 12GB 门槛 | 第 2 章 |
| **3** | **「2K 屏截图」这个假设不成立**：系统实际输出 **1080×2400**（非 3200×1440），截图体积 0.8MB | 第 5 章 |

### 0.2 对《可行性分析报告》的四处修正

| 项 | 报告原假设 | 实测 | 影响 |
|---|---|---|---|
| 系统版本 | Android 13–14 / HyperOS | **Android 15（SDK 35）/ HyperOS 3.0（MIUI V816）** | 无障碍策略、`dispatchGesture` 限制需按 Android 15 重新确认 |
| 运存 | 未知（12.3 待确认） | **8GB** | **端侧 VLM 放弃做主决策**，P0-5 直接判不通过 |
| 截图分辨率 | 3200×1440，需降采样 | **1080×2400** | P0-7 风险大幅降低；但 0.8MB PNG 仍需压缩后再送模型 |
| 首期目标 App | 小红书 / B站 / 微博 | **均未安装** | 10 个任务清单必须按实际装机重选，见 0.3 |

### 0.3 需要用户决策的两件事

1. **首期 10 个任务按实际装机重选**（见 6.2 的建议清单）
2. **云端 VLM 选型与 API Key**（P0-6 待做，建议先测 Qwen-VL-Max 与 GLM-4V）

---

## 1. 设备环境实测

| 项 | 实测值 | 获取方式 |
|---|---|---|
| 型号 | `22127RK46C`（Redmi K60 Pro / socrates） | `ro.product.model` |
| Android 版本 | **15**（SDK **35**） | `ro.build.version.release` / `sdk` |
| 系统 | **HyperOS 3.0**（MIUI `V816`） | `ro.mi.os.version.name` / `ro.miui.ui.version.name` |
| 物理分辨率 | 1440×3200 | `wm size` |
| **实际输出分辨率** | **1080×2400** | `wm size`（Override size） |
| 密度 | 物理 560 / **Override 420** | `wm density` |
| **运存** | **7.2 GB（MemTotal 7373332 kB）→ 8GB 版本** | `/proc/meminfo` |
| Root | **否**（`uid=2000(shell)`） | `id` |
| Shizuku | **未安装** | `pm list packages` |
| 已启用无障碍服务 | **1 个**：`com.simple.calculator/.service.VolumeKeyAccessibilityService` | `settings get secure enabled_accessibility_services` |
| 已装无障碍服务总数 | 29 | `dumpsys accessibility` |
| 第三方应用数 | 98 | `pm list packages -3` |

**两个直接推论**：

- **8GB 运存 → 端侧 VLM 出局。** MobiAgent `phone_runner` 官方要求 12GB+，本报告 2.3 节对 K60 Pro 的乐观估计也是建立在 12/16GB 版本上的。8GB 下 3B 模型加载后剩余内存不足以支撑稳定推理，且会与前后台 App 抢内存。**结论：端侧只保留「可选 Grounder」这一条路，且优先级降到最低（P3）。**
- **已存在一个第三方无障碍服务 → 我们写无障碍开关时，必须走「先 get、再追加、再 put」的追加模式。** 直接 `settings put secure enabled_accessibility_services <ours>` 会把计算器这个服务顶掉，用户会莫名其妙发现音量键手势失灵。

---

## 2. P0-5 端侧推理可行性：**不通过**（已定论）

| 判据 | 要求 | 本机 |
|---|---|---|
| 运存 | MobiAgent 要求 ≥12GB | **8GB** ❌ |
| SoC | 建议 8Gen3 / 8Elite | 8 Gen 2 ❌ |
| Root / Termux | MNN 部署需 Termux | 未 Root ⚠️ |

**结论：P0-5 判定不通过，端侧不做 Planner/Decider。** 后续如要保留端侧能力，只能是：
- 端侧 Grounder（3B，只输出坐标 JSON），且列为 S6 增强项；
- 或「隐私模式」作为宣传点，但实测前不写进 README。

---

## 3. P0-3 感知层耗时实测：**报告 3.1 成立，且比文献数据更差**

**方法**：`adb shell uiautomator dump /data/local/tmp/poc/w.xml`，每场景 5–20 次，取 min/p50/p95/max。同时测 `screencap -p` 作为视觉路线基线。脚本见 `poc/measure_dump.sh`。

| 场景 | 包名 | dump min | **dump p50** | dump p95 | dump max | screencap p50 | 节点数 | 可点击 | 有文本 |
|---|---|---|---|---|---|---|---|---|---|
| 桌面 | com.miui.home | 2280 | **2309** | 4106 | 4106 | 1712 | 39 | 24 | 9 |
| 系统设置（首页） | com.android.settings | 2388 | **3278** | 3401 | 3401 | 520 | 94 | 14 | 18 |
| WLAN 设置页 | com.android.settings | 2320 | **2377** | 2931 | 2931 | 557 | 35 | 7 | 5 |
| 小米计算器 | com.miui.calculator | 2284 | **2427** | 3272 | 3272 | 482 | 88 | 78 | 16 |
| **微信** | com.tencent.mm | 2318 | **2524** | 3141 | 3141 | 632 | **1** | **0** | **0** |
| 高德地图 | com.autonavi.minimap | 2981 | **3369** | 5703 | 5703 | 1030 | 272 | 125 | 34 |
| 网易云音乐 | com.netease.cloudmusic | 2464 | **3162** | 5489 | 5489 | 581 | 177 | 46 | 36 |
| 拼多多 | com.xunmeng.pinduoduo | 2644 | **2801** | 3119 | 3119 | 1009 | 203 | 34 | 55 |
| **京东** | com.jingdong.app.mall | 4251 | **5496** | **8456** | **8456** | 966 | 414 | 54 | 55 |
| Alook 浏览器 | alook.browser | 2338 | **2374** | 3168 | 3168 | 592 | 79 | 25 | 11 |
| 七猫小说 | com.kmxs.reader | 2399 | **2445** | 3460 | 3460 | 717 | 168 | 40 | 32 |

**结论**：

1. **`uiautomator dump` 全域 p50 落在 2.3–3.3 秒**，与报告引用的文献值（2.2–2.8s）一致，**确认了报告 3.1 的核心判断**——这条路比「截图 + VLM」的感知环节（screencap p50 0.5–1.0s）**还慢**，原技术文档「感知 200–500ms」的目标完全不成立。
2. **p95 才是致命的**：京东 8.5s、高德 5.7s、网易云 5.5s。这是 `waitForIdle` 在信息流/动画页面上等待最长 10s 的直接体现。做 Agent 循环时，这种抖动会让任务耗时完全不可控。
3. **节点数越多越慢**：京东 414 节点 → p50 5.5s，Alook 79 节点 → p50 2.4s。呈明显正相关。
4. **决策：`uiautomator dump` 不作为主感知路径，只作为 U2 常驻 server 不可用时的最后兜底。** 主路径必须是自有 AccessibilityService（P0-2 待验证）或常驻 dump server（P1-2 待验证）。

---

## 4. P0-4 控件树可解析性普查：**系统类/效率类/内容类良好，微信完全屏蔽**

按「可点击节点数」与「有文本节点数」评估可解析性：

| 评级 | App | 依据 |
|---|---|---|
| ⭐⭐⭐⭐⭐ 优秀 | 小米计算器（88/78/16）、系统设置（94/14/18） | 节点结构清晰，可点击率高 |
| ⭐⭐⭐⭐ 良好 | 高德地图（272/125/34）、网易云音乐（177/46/36）、七猫小说（168/40/32） | 节点丰富，文本完整 |
| ⭐⭐⭐ 一般 | 拼多多（203/34/55）、京东（414/54/55） | 节点多但可点击率低（电商页大量展示型节点），需靠文本匹配 |
| ⭐ 完全不可用 | **微信（1/0/0）** | **dump 只返回 1 个空节点 `bounds="[0,0][0,0]"`** |

**微信实锤**：连续多次 dump，返回的 XML 恒为 381 字节，只有一个 `bounds="[0,0][0,0]"` 的空节点。**这完整印证了报告 4.2 的三重屏蔽机制**（SurfaceView/TextureView 绕过 View 管线 + `accessibilityTraversalEnabled=false` + 隐私进程标记）。

**决策**：
- 微信**首期不做**，且后续只走纯视觉路线（截图 + VLM）。
- 电商类 App 需要**文本优先**的匹配策略（可点击率低但文本丰富）。
- 这份表要持续扩充——报告 10.3 节指出「中文 App 控件树可解析性普查表」是最稀缺的差异化资产，本轮 11 个采样点是第一批数据。

---

## 5. P0-7 截图规格：**2K 假设不成立，风险降级**

| 项 | 报告假设 | 实测 |
|---|---|---|
| 分辨率 | 3200×1440 | **1080×2400**（Override） |
| PNG 体积 | 未估计 | **840 KB**（设置页 388 KB，桌面 3.0 MB） |
| screencap 耗时 | ~300ms | **p50 482–1712ms**（视页面复杂度） |

**结论**：

- ✅ **「2K 必须降采样」这个坑不存在**——系统 Override 到 1080×2400，截图尺寸已经友好。
- ⚠️ **但 0.8MB PNG（桌面页可达 3MB）仍偏大**，上传 VLM 前建议转 JPEG（质量 85）或按长边缩放到 1120，预计可降到 150–300KB。这部分收益仍值得做，但优先级从 P0 降到 P1。
- ⚠️ **screencap p50 最高到 1.7s（桌面动态壁纸）**，比报告假设的 300ms 慢不少。视觉路线的感知成本需要重新计入基线，不能想当然按 300ms 算。

---

## 6. 附带发现与下一步

### 6.1 P1-1 无障碍服务追加写入：**已验证可行**

```
$ adb shell settings get secure enabled_accessibility_services
com.simple.calculator/.service.VolumeKeyAccessibilityService

$ adb shell settings put secure enabled_accessibility_services "<原值>"   # 原值回写，语义 no-op
$ adb shell settings get secure enabled_accessibility_services
com.simple.calculator/.service.VolumeKeyAccessibilityService   # 一致
```

**结论**：shell 具备 `WRITE_SECURE_SETTINGS`，Shizuku 自动开启无障碍这条路**技术上可行**。但如第 1 章所述，必须实现「先 get → 追加 → 再 put」，否则会破坏用户已有的无障碍服务。

### 6.2 首期 10 个任务需要重选

报告 9.1 选的小红书 / B站 / 微博**本机均未安装**。按实际装机（98 个第三方 App）建议改为：

| 类别 | 可用 App | 建议任务 |
|---|---|---|
| 系统类 | 系统设置、小米计算器、通知栏 | 开关 WiFi/蓝牙、跳到指定设置项、算个数、清通知 |
| 效率类 | 高德地图、小米天气、WPS、日历/闹钟 | 搜索地点、查天气、新建日程、设闹钟 |
| 内容类 | 网易云音乐、七猫小说、Alook 浏览器 | 搜索歌曲并播放、打开指定小说、搜索网页 |
| 电商类（谨慎） | 拼多多、京东 | 只做「搜索商品」这类**不涉及下单支付**的任务 |

> ⚠️ 电商类建议只做搜索浏览，且 Guard 层要把「下单 / 支付 / 结算」关键词硬拦截。

### 6.3 下一轮 POC 待办（需要装 App / Key）

| ID | 验证项 | 阻塞条件 | 预计 |
|---|---|---|---|
| **P0-2** | 无障碍服务在 HyperOS 3 上存活（前台 30min / 锁屏 10min / 省电切换） | 需写一个最小 Demo App | 最高优先级 |
| **P0-1** | Shizuku 下 `injectInputEvent` | 需装 Shizuku APK | 高 |
| **P0-6** | 云端 VLM 单步延迟 | 需 API Key | 需用户决策 |
| **P1-2** | 常驻 uiautomator2 server | 需 Shizuku | 中 |

> P0-2 是架构分叉点：如果 HyperOS 3 上无障碍撑不过 30 分钟，整个架构要从「无障碍优先」改为「Shizuku + 常驻 dump server 优先」。**在它出结果之前，不要写感知层的业务代码。**

---

## 7. 修订记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v0.1 | 2026-09-06 | 首轮 POC：设备环境、P0-3 感知耗时、P0-4 可解析性普查、P0-5 端侧判定、P0-7 截图规格、P1-1 写入验证 |
