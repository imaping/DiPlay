# 本分支新增功能说明（非上游主仓库功能）

本文档汇总本仓库（`imaping/DiPlay`）相对于上游主仓库（`shihabal3amri/DiPlay`）独立实现的所有功能与增强改动。

本分支重点面向 **吉利 / 亿咖通 ECARX 车机（尤其是星越 L / Monjaro KX11 OneOS）** 的深度适配，同时补充了 **自建 Cloudflare R2 诊断报告上传** 与 **车机桌面图标优化** 等通用能力。所有本地定制均遵循 `AGENTS.md` 中的低冲突上游同步规范，以独立扩展文件与最小化单点接入实现，便于长期与上游同步。

---

## 功能总览

| 模块 | 核心能力 | 适用范围 | 设置 / 触发入口 |
| --- | --- | --- | --- |
| [1. KX11 自动识别与开箱默认配置](#1-ecarx-kx11-车机自动识别与开箱默认配置) | 自动默认 60 fps、音频焦点、导航播报音频焦点、位置与车速上报 | 吉利星越 L / KX11 | 自动生效（可在设置中手动覆盖） |
| [2. ECARX GPS + CAN 轮速融合定位](#2-ecarx-硬件-gps--can-轮速融合定位增强) | 硬件 GNSS 与 `$PASCD` 融合、隧道去重、CAN 车速直填 `$GPRMC`、轮速诊断日志 | ECARX 车机 | **设置 → 导航 → 向 iPhone 发送位置** |
| [3. KX11 方向盘实体按键原生接入](#3-kx11-方向盘实体按键原生接入与学习) | 直连 `com.geely.inputservice`，支持 Siri、播放/暂停与自定义仪表视图循环切换 | 吉利 KX11 | **设置 → 车辆 → 方向盘按键** / **设置 → 吉利设置** |
| [4. Fake Ultra 矢量仪表与车况信息页](#4-吉利-fake-ultra-矢量双圆盘仪表与车况信息系统) | 6 套机甲双圆盘主题、原车 CAN/TPMS/行程信号、本次行程/胎压胎温/综合车况页、导航自动回地图与胎压异常告警 | 吉利 / ECARX 车机 | **设置 → 吉利设置 → 仪表与主题** |
| [5. 车机原生音乐与实时歌词同步](#5-车机原生音乐与实时歌词仪表同步) | 未连 CarPlay 时在常驻仪表展示网易云/QQ 音乐/蓝牙音乐封面与实时歌词 | 吉利 / ECARX 车机 | **设置 → 吉利设置 → 权限与兼容** |
| [6. 原车昼夜信号权威同步](#6-原车信号驱动的权威昼夜模式同步) | 读取 ECARX 大灯/主题昼夜信号，联动 CarPlay、仪表主题与自绘导航卡 | 吉利 / ECARX 车机 | 自动生效（仪表支持自动/深色/浅色切换） |
| [7. 自绘 W-HUD 与仪表导航卡片](#7-吉利自绘-w-hud经典仪表卡片与全屏导航卡片) | 独立悬浮窗自绘 W-HUD、经典仪表卡、全屏导航卡、车道提示，支持像素微调与实车预览 | 吉利 `1920×1080` / `1920×720` 复合仪表 | **设置 → 吉利设置 → 导航卡片 / 抬头显示 (HUD) / 效果预览** |
| [8. 独立「吉利设置」与全屏预览面板](#8-独立吉利设置页面与全屏配置预览面板) | 独立设置分类、5 分页配置、宽屏双栏全屏弹窗与 `1920×1080` 实时缩略预览 | 通用 / 吉利车机 | **设置 → 吉利设置** |
| [9. 诊断报告一键上传 Cloudflare R2](#9-诊断报告一键上传至自建-cloudflare-r2) | Gzip 压缩后 `PUT` 上传至自建 Worker + R2，绕开无外网 CarPlay 热点 | 全部机型 | **设置 → 诊断 → 上传诊断报告到 R2** |
| [10. 车机高分图标与低冲突同步规范](#10-其他定制与低冲突上游同步架构) | `drawable-nodpi` 清晰车机桌面图标、512px 应用内图标、低冲突架构规范 | 全部机型 | 自动生效 |

---

## 1. ECARX KX11 车机自动识别与开箱默认配置

实现文件：`EcarxKx11HeadUnit.kt`、`AirPlayPersistence.kt`

- **自动机型识别**：检测系统属性 `Build.PRODUCT` / `Build.DEVICE` / `Build.MODEL` 为 `kx11`、`kx11_*` 或 `kx11-*` 且具备 ECARX AdaptAPI 时自动命中（已验证 2021 款星越 L `kx11_high` / Android 9）。
- **开箱默认优化**（用户首次未手动改写时自动启用，仍可随时在设置中修改）：
  - **帧率默认 60 fps**（上游默认 30 fps）。
  - **音频焦点默认开启**（KX11 方向盘音量键按当前音频焦点路由，而非发送标准 Android `KeyEvent`）。
  - **导航播报音频焦点默认开启**：通过标准 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` 映射到 `STREAM_NAVI_TTS`（stream 15），导航播报期间旋转/按动方向盘音量键调节导航播报音量，播报结束后自动恢复调节媒体音量（stream 3）。
  - **向 iPhone 上报位置与车速默认开启**。

---

## 2. ECARX 硬件 GPS + CAN 轮速融合定位增强

实现文件：`EcarxLocationEnhancer.kt`

上游对 ECARX 车机默认采用 `EcarxWheelSpeed.NoPosition`，仅向 iPhone 发送 `$PASCD` 轮速而不发送车机 GPS 坐标。本分支通过独立实现与装饰器模式补充了以下能力：

- **纯硬件 GNSS + `$PASCD` 融合**：新增 `GpsOnlyCarPlayLocationProvider`，在带有车顶 GNSS 天线的国产 KX11 等车型上同时输出 `$GPGGA` + `$GPRMC` 与 `$PASCD`；永不回退到网络定位，在无 GPS 的出口车型上平滑降级为纯 `$PASCD`。
- **零节流与隧道断星保护**：
  - 以 `intervalMs = 0`、`minDistance = 0` 订阅系统 `GPS_PROVIDER`，避免 Android 额外节流导致原生 1 Hz 定位丢拍。
  - 按 `(timeMillis, elapsedRealtimeNanos)` 去重，并丢弃超过 5 秒的过期定位或精度差于 100 m 的漂移点；车辆驶入隧道断星瞬间立即停发旧坐标，防止旧坐标干扰 iPhone 轮速惯导推算。
- **低延迟 CAN 轮速直填 `$GPRMC`**：在同一 iAP2 周期内预先提取最新 250 ms ECARX CAN 轮速写入 `$GPRMC`，并缓存给紧随其后的 `drain()` 调用复用，消除 GNSS 多普勒测速滞后，使 `$GPRMC` 与 `$PASCD` 车速一致。
- **诊断日志增强**：使用 `DiagnosticVehicleSpeedSource` 包装上游 `EcarxWheelSpeed`，在不修改原类日志契约的前提下，向诊断报告补充首次轮速读数、档位切换记录，以及每分钟峰值车速与采样次数统计。

---

## 3. KX11 方向盘实体按键原生接入与学习

实现文件：`EcarxKx11WheelKeys.kt`

KX11 方向盘按键经由 `ECarXCarHardKeyService` → `com.geely.inputservice.InputService`（`IInputManager` / `IInputListener`）派发厂商自定义键码（`200000` / `210000` 号段），不产生 Linux `/dev/input` 或标准 Android `KeyEvent`，因此上游基于无障碍服务的按键监听无法捕获。

- **直连吉利输入服务**：自动绑定 `com.geely.inputservice.InputService`（断连后 3 秒自动重连），支持学习 18 个常用方向盘厂商按键（包括微信键 `200400`、语音键 `200231`、自定义多功能键 `200600`、静音 `200164`、模式 `200110`、播放/暂停 `200085`、上下曲 `200087/200088`、接听/挂断 `200005/200006`、菜单/设置/导航/收音机/媒体/音源/搜台等）。
- **Siri 与媒体控制**：
  - 直接接入 **设置 → 车辆 → 方向盘按键** 的原有学习入口（也可在 **设置 → 吉利设置 → 仪表与主题** 一键将原车语音键 `200231` 设为 Siri），学习结果以设备名 `geely-inputservice` 保存。
  - 支持 **单按** 与 **双击** 触发 Siri（复用双击间隔、延迟触发与通话中屏蔽逻辑）；提供 **CarPlay 期间覆盖原车语音键** 开关：开启后 CarPlay 连接期间自动调用 `IInputManager.interceptKeyCode(200231)` 尝试接管短按直接呼出 Siri，断开 CarPlay 自动 `releaseKeyCode` 还原原车「你好吉利」；若关闭或车机固件拒绝第三方拦截，自动退回「短按原车语音 / 长按呼出 Siri」。
  - 支持映射 **媒体播放/暂停**。
- **仪表主视图与临时车况双轨方向盘快捷键（KX11 开箱默认已配置）**：
  - **开箱默认预设**（仅在 KX11 首次安装或升级时自动写入一次，后续手动修改永久保留）：原车语音键（`200231`）默认设为 Siri 并开启 CarPlay 期间覆盖原车语音；微信键（`200400`）默认 **单击** 切换「导航地图 / 专辑封面」、**长按** 查看「本次行程 / 胎压胎温 / 综合车况」并 **5 秒后自动关闭**。
  - **主视图切换键（常驻显示）**：可在 **设置 → 吉利设置 → 仪表与主题** 修改按键与手势，在已勾选的「导航地图 / 专辑封面 / 数字时钟」间秒切并常驻停留（无地图投影或无专辑封面时自动跳过）。
  - **车况查看快捷键（临时查看，超时自动关闭）**：支持与主视图共用同一按键的不同手势（默认同键 **长按**，零延迟；也可选 **双击**），或单独绑定另一颗方向盘按键（如自定义键 `200600`），按需查看已勾选的「本次行程 / 胎压胎温 / 综合车况」。
  - **倒计时翻页与自动关闭**：进入车况页后，支持 `关闭 / 5 / 10 / 15 / 20 秒`（默认 `5 秒`）无操作自动关闭并返回进入前的原主视图（地图 / 专辑 / 时钟）；倒计时期间再短按按键可直接顺切下一项车况页（翻完最后一项后再按即立刻关闭）并重置倒计时。
  - 即使 CarPlay 已断开，只要常驻仪表主题仍在运行即可继续通过方向盘切换视图与查看车况。

---

## 4. 吉利 Fake Ultra 矢量双圆盘仪表与车况信息系统

实现文件：`GeelyFakeUltraView.kt`、`GeelyClusterThemes.kt`、`GeelyClusterExtraThemes.kt`、`GeelyClusterConfig.kt`、`GeelyClusterThemeWindow.kt`、`GeelyEcarxHardware.kt`、`GeelyVehicleTelemetry.kt`、`GeelyClusterInfoPages.kt`、`GeelyVehicleSignalProbe.kt`、`GeelyClusterEnhancer.kt`、`GeelyClusterSettingsUi.kt`

### 4.1 6 套机甲矢量双圆盘主题（实时切换 · 全部支持昼夜）

由统一主题规格表与矢量绘制引擎驱动，实时动态指针、能量块、光弧与数据由 Kotlin 阻尼插值渲染，全部支持浅色/深色昼夜配色：

- `AERO_MECHA`（机甲光翼 · 未来战术）
- `HYPER_WING`（超跑双翼 · 极速光刃）
- `VALKYRIE_HEX`（女武神 · 六维装甲）
- `ORBIT_RADAR`（量子雷达 · 脉冲星环）
- `TITAN_CORE`（泰坦核心 · 重装反应堆）
- `PHANTOM_STEALTH`（幽灵隐形 · 暗影战机）

### 4.2 原车 CAN / TPMS / 行程实时传感器驱动

通过零编译期厂商依赖的只读反射对接 ECARX AdaptAPI（已在星越 L `kx11_high` Android 9 实车验证）：
- **基础仪表通道**（`GeelyEcarxSensorReader`，250 ms 轮询 + 阻尼动画）：
  - **车速**（`0x00101A00`，m/s → km/h）与 **油量百分比**（`0x00404500`）→ 左侧圆盘
  - **发动机转速**（`0x00100900`）与 **实时档位**（`0x00200200`，支持 `P` / `R` / `N` / `D` / `M1..M10`）→ 右侧圆盘（全屏导航时中心可自动嵌入圆角专辑封面）
  - **平均油耗**（`0x00400100`，L/100km）→ 底部状态栏 / 待机时钟下方
- **扩展车况、胎压与行程通道**（`GeelyVehicleTelemetryReader`，1 s 轮询）：
  - **本次行程**：`IHev.getTripData()` 的 `getLatestDrivingInfo()`（行程里程米→km、行程用时毫秒→小时/分、平均速度）与 `getLatestAvgEnergyInfo()`（本次平均油耗，带锁定源与防抖防闪烁机制），叠加 `IVirtualSensor` 瞬时油耗（`0x00400200`）；无 `ITripData` 车型自动回退 CAN 车速数值积分。
  - **四轮胎压与胎温**：`ITireSensor`（`0x00500100..0x00500400` 四轮胎压 `kPa`、`0x00500500..0x00500800` 四轮胎温 `°C`、`0x00500900..0x00501400` 胎压/高温/快漏气告警、`0x00504000` TPMS 系统态），实车与原车仪表读数一致。
  - **车辆综合信息**：总里程（`0x00100700`）、总平均油耗（`0x00400100`）、剩余续航（`0x00100800`）、剩余油量（`0x00100600` mL→L 与 `0x00404500` %）、车外/车内温度（`0x00100B00`/`0x00100C00`）、距保养参考里程（`0x00403000`）、机油百分比与机油/冷却液液位及 6 路动力系统告警（`0x0030xxxx`），并附带可手动清零的本地持久化累计统计（类似 Trip B）。

### 4.3 配色模式、中央视图与车况信息页

- **3 种强调配色模式**（800 ms 平滑 RGB 过渡）：
  - **主题默认色**（随各主题昼夜默认色）
  - **随车速四段渐变**（0–40–80–120+ km/h：青 → 绿 → 琥珀 → 红）
  - **随专辑封面主色**（24-bin HSV 主色动态提取）
- **7 种中央显示模式**（支持在设置、中控车况总览或方向盘循环键切换）：
  - `自动（地图 / 专辑 / 时钟）`：导航时展示地图，非导航播放音乐时展示专辑封面，无音乐无导航时显示大号数字时钟。
  - `导航地图` / `专辑封面` / `大号数字时钟`
  - `本次行程`：居中 5 行矢量图标列表展示本次里程、用时、平均速度、平均油耗与瞬时油耗，底部附带剩余续航与车外温度。
  - `胎压与胎温`：居中俯视矢量车身 + 四轮状态胶囊 + 四角对称胎压（`kPa`）/胎温（`°C`）读数，异常轮胎标红高亮提示。
  - `综合车况`：2×3 卡片网格展示总里程、总平均油耗、剩余续航与油量、车内外温度、距保养参考及机油/冷却液健康态，底部展示自重置累计统计。
- **胎压异常后台提醒与中控总览**：
  - 开启「胎压 / 胎温异常自动提醒」后，即使当前停留在导航地图、专辑或时钟页，一旦检测到胎压偏低/偏高、胎温过高或快漏气，仪表顶部自动弹出高对比度告警横幅。
  - 可在 **设置 → 吉利设置 → 仪表与主题 → 在中控屏查看车况与行程总览** 中一屏查看全部车况数据、手动刷新、清零累计统计或一键切换仪表中央视图。
- **视觉细节与原车兼容**：
  - 专辑模式自带 3-pass Box Blur 全宽动态模糊背景、420 ms 交叉淡入淡出和居中圆角封面卡片。
  - 地图模式提供左右及顶部边缘羽化主题遮罩，保留中央透明地图窗口；导航中切换至车况页时自动避让顶部全屏导航卡片。
  - 双圆盘整体上移并缩放（`0.92x`），避让原车 QNX 仪表底部状态栏，避免遮挡原车底栏信息；CarPlay 底部歌词/歌名状态栏在待机或播放车机本地音乐时自动隐藏，避免与原车底栏重叠。

### 4.4 软件启动加载主题、断开常驻与后台保活

- **启动即加载 & 断开不消失**（`GeelyClusterThemeWindow`）：开启「软件启动时加载主题」并授予悬浮窗权限后，打开 DiPlay 即在仪表屏独立挂载 `1920×720` `TYPE_APPLICATION_OVERLAY (2038)` 主题窗口；iPhone 未连接或断开 CarPlay 后，仪表主题继续保留（自动清空 CarPlay 地图与导航，切为待机时钟或车机音乐），重新连上 CarPlay 时与视频流无缝叠加且避免重复绘制。
- **前台保活与 `GeelyRecent` 后台防杀**（`GeelyClusterKeepAliveService`、`DiPlaySessionService`）：
  - 独立前台服务维持常驻主题进程活跃，通知栏提供一键「关闭主题」按钮。
  - 针对吉利 / ECARX 车机切后台约 30 秒后由 `GeelyRecent` 移出最近任务（触发 `onTaskRemoved` 但未杀死进程）的行为做了专门兼容：保持 CarPlay 会话、独立导航 HUD 与常驻仪表主题持续工作，仅在主动断开或点击关闭主题时退出。
- **全屏仪表切入与通话后自动恢复**（`GeelyDimMenuController`、`GeelyDimLogcatWatcher`）：
  - 反射调用 `DimMenuInteraction.switchNaviMode(3 / 1)` 控制进入全屏地图（Mode 3）与返回经典仪表（Mode 1），支持句柄失效自动重试与 `1 → 3` 脉冲重入。
  - 后台监听 `DimMenuInteraction` 外部切出日志；当通话打断全屏仪表时自动记录状态，通话挂断后延迟 900 ms 自动切回全屏仪表。

---

## 5. 车机原生音乐与实时歌词仪表同步

实现文件：`GeelyVehicleMediaObserver.kt`

在未连接 CarPlay 的常驻仪表主题待机状态下，也可将车机自带或第三方音乐应用同步到仪表盘中央显示：

- **支持来源**：通过轻量级 `GeelyMediaNotificationListenerService` 读取 `MediaSessionManager` 活跃会话（支持网易云音乐、QQ 音乐、酷我、酷狗、蓝牙音乐 AVRCP、喜马拉雅、车机原生媒体等），并内置零权限传统音乐广播兜底。
- **实时歌词与多级封面解析**：
  - 提取音乐通知 `tickerText` 作为状态栏实时歌词（自动过滤应用名/状态提示等无效文本），在居中封面下方或时钟下方同步滚动展示。
  - 专辑封面按 `MediaMetadata Bitmap → MediaMetadata URI 异步解码缓存 → 通知大图标 → 同曲目保留缓存` 多级回退，防止歌词刷新清空封面。
  - 切歌/短暂停顿提供 12 秒缓冲，避免仪表在封面与时钟之间闪烁；长时间暂停后自动回到大号数字时钟。
- **权限配置**：在 **设置 → 吉利设置 → 权限与兼容** 中点击「开启车机音乐与歌词读取权限」跳转系统通知使用权授权即可。

---

## 6. 原车信号驱动的权威昼夜模式同步

实现文件：`GeelyEcarxDayNightReader.kt`、`CarPlayHostActivity.kt`、`GeelyClusterEnhancer.kt`

- **多通道反射读取原车昼夜状态**（每 1.5 秒轮询 + 回调即时推送）：
  1. 优先读取 `com.ecarx.xui.adaptapi.device.Device.create(ctx).getDayNightMode()`（`IDayNightMode` 及 `IDayNightModeCallBack`），同步大灯与车机主题综合昼夜状态。
  2. 回退读取 `com.ecarx.xui.adaptapi.car.Car.create(ctx).getSensorManager().getSensorEvent(SENSOR_TYPE_DAY_NIGHT)`。
  3. 非吉利车机或无厂商通道时无缝回退到标准 Android `Configuration.uiMode`。
- **全链路联动**：原车昼夜切换会自动同步到 **CarPlay 昼夜模式**（当 CarPlay 设为跟随系统/环境光时）、**Fake Ultra 仪表主题**（自动模式下）以及 **自绘导航卡片**，与原车 QNX 仪表底部栏保持严格一致。仪表也可在设置中单独固定为「始终深色」或「始终浅色」。

---

## 7. 吉利自绘 W-HUD、经典仪表卡片与全屏导航卡片

实现文件：`GeelySelfDrawOverlay.kt`、`GeelyClusterEnhancer.kt`

针对吉利 `1920×1080` 复合仪表副屏（上方 `1920×720` 为仪表主画面，下方 `Y: 720..1080` 供原车硬件裁剪投射到 W-HUD 与经典仪表导航卡槽）：

- **严格分区与透明裁剪源**：将 `ClusterMapPresentation` 地图视频锁定在顶部 `1920×720` 容器内，保持 `Y: 720..1080` 区域完全透明，避免切回经典仪表（Mode 1）时出现黑框。
- **独立 `TYPE_APPLICATION_OVERLAY (2038)` 覆盖窗**（无悬浮窗权限时自动降级为 `Presentation` 内置层）：挂载在最高 Z-order 层级，确保导航卡片始终位于主题窗口之上。
- **4 类自绘导航元素**（内置完整高德风格矢量转向图标库，含环岛出口编号及超长路名自动跑马灯滚动）：

| 自绘元素 | 默认坐标（基于 `1920×1080` 画布） | 说明 |
| --- | --- | --- |
| **W-HUD 裁剪源** | `x=0, y=720, 228×186` | 转向箭头、距离、路名及嵌入式车道提示 |
| **经典仪表导航卡源** | `x=1165, y=720, 364×112` | 原车经典仪表模式下显示转向、距离与路名 |
| **全屏地图顶部导航卡** | `x=595, y=118, 728×112` | 全屏地图模式下展示转向、距离、路名 + 剩余里程/时长/预计到达时间 |
| **车道提示条** | 随全屏卡片居中下挂，或自定义 `laneX/laneY` | 解析 CarPlay iAP2 `0x5204` 车道数据，无数据时自动隐藏 |

- **可视化校准与微调**：每个区域均支持 `X±4` / `Y±4` 快捷微调、精确数值输入与一键恢复默认布局；支持在设置页开启「在实车仪表与 HUD 上显示模拟预览」，无需真实导航即可直接校准裁剪框位置。

---

## 8. 独立「吉利设置」页面、全屏配置预览与信号探测工具

实现文件：`DiPlayActivity.kt`、`GeelyClusterSettingsUi.kt`、`GeelyClusterEnhancer.kt`、`GeelyVehicleSignalProbe.kt`、`strings_geely.xml`

- 在 DiPlay 设置中新增独立的 **吉利设置（Geely）** 分类（无需混杂在「高级」设置中）。
- 页面内嵌 4 个分页标签，所有修改实时持久化并立即刷新仪表显示：
  1. **仪表与主题**：6 套主题单选、启动加载主题、左右圆盘与元素开关、配色模式、昼夜模式、7 种中央显示模式、中控车况与行程总览入口、导航切看车况自动回地图时长、胎压异常提醒开关、方向盘切换按键学习/触发方式与循环页面多选。
  2. **导航与 HUD**：W-HUD / 全屏导航卡 / 经典仪表卡 / 车道提示开关与像素坐标微调。
  3. **预览与调试**：内置 `1920×1080` 等比交互预览画布（同步显示上下分区线、虚线裁剪框与本次行程/胎压/综合车况示例画面），支持一键切换主题/中央视图/昼夜/导航与待机场景、手动测试切全屏（Mode 3）/回经典（Mode 1）、实车模拟预览与原车信号探测快捷入口。
  4. **权限与副屏**：快捷管理悬浮窗权限（`TYPE_APPLICATION_OVERLAY`）、车机通知使用权（本地音乐与歌词读取）、手动选择仪表副屏 Display ID，以及**按需手动触发的「原车车况信号探测（只读）」**工具（平时行车零后台开销，仅点击时一次性只读采样并支持上传 R2 或本地保存，便于排查新车型或固件信号差异）。
- 点击「全屏配置与预览」可打开适配车机横屏宽屏的双栏 Studio 全屏配置弹窗，自动跟随 DiPlay 当前浅色/深色主题调色板。

---

## 9. 诊断报告一键上传至自建 Cloudflare R2

实现文件：`DiagnosticR2Uploader.kt`、`DiagnosticR2UploadUi.kt`、`common/build.gradle.kts`、`strings_r2_upload.xml`

在 **设置 → 诊断** 卡片中新增 **上传诊断报告到 R2** 与 **R2 上传配置** 两个按钮，方便车机端在不便插 U 盘或扫码时快速归档诊断日志：

- **手动触发与 Gzip 压缩**：仅在用户手动点击上传时触发；将诊断报告 UTF-8 文本 Gzip 压缩后，通过 HTTP `PUT`（`Content-Type: application/gzip`）上传至用户自建的 Cloudflare Worker 地址，支持可选 `Authorization: Bearer <token>` 鉴权，并附带 `X-DiPlay-Device`、`X-DiPlay-Report-Name`、`X-DiPlay-Uncompressed-Bytes` 请求头。
- **对象路径规范**：自动生成 `yyyy-MM-dd/<厂商-型号>/<报告文件名>.gz`。
- **绕开无外网 CarPlay 热点**：复用 `InternetNetworkSelector` 选择已验证可访问外网的网络通道（如车机 4G 移动网络），防止无线 CarPlay 热点劫持上传请求。
- **构建默认 + 运行时覆写**：
  - 支持在编译时通过环境变量 `DIPLAY_R2_UPLOAD_ENDPOINT` / `DIPLAY_R2_UPLOAD_TOKEN` 或 `local.properties` 中的 `r2.upload.endpoint` / `r2.upload.token` 注入默认配置。
  - 支持在车机端「R2 上传配置」弹窗（或长按上传按钮）随时覆写地址/Token，或一键恢复构建默认；上传失败时弹窗提供「重试」与「保存到本地」回退按钮。

---

## 10. 其他定制与低冲突上游同步架构

### 10.1 车机启动图标与应用内图标优化
- 新增 `mobile/src/main/res/drawable-nodpi/ic_launcher_geely.png` 并将其设为 `android:icon` 与 `android:roundIcon`，解决 KX11 等车机桌面忽略密度目录导致图标过小或模糊的问题。
- 更新 `common/src/main/res/drawable/ic_carplay.png` 为高清 512×512 方形 DiPlay 标识，改善应用内头部及悬浮气泡显示清晰度。

### 10.2 低冲突上游同步设计（开发者参考）
为确保后续持续 `merge` / `rebase` 上游 `shihabal3amri/DiPlay` 时尽量少冲突，所有定制逻辑均集中在新增的独立扩展文件中，对上游核心文件仅保留极简接线点：

- **新增独立模块**（`common/src/main/java/com/shilapi/xcertplay/`）：
  - `EcarxKx11HeadUnit.kt`、`EcarxLocationEnhancer.kt`、`EcarxKx11WheelKeys.kt`
  - `GeelyClusterConfig.kt`、`GeelyEcarxHardware.kt`、`GeelyEcarxDayNightReader.kt`、`GeelyVehicleMediaObserver.kt`、`GeelyVehicleTelemetry.kt`、`GeelyVehicleSignalProbe.kt`
  - `GeelyFakeUltraView.kt`、`GeelyClusterThemes.kt`、`GeelyClusterExtraThemes.kt`、`GeelyClusterInfoPages.kt`、`GeelySelfDrawOverlay.kt`、`GeelyClusterThemeWindow.kt`、`GeelyClusterEnhancer.kt`、`GeelyClusterSettingsUi.kt`
  - `DiagnosticR2Uploader.kt`、`DiagnosticR2UploadUi.kt`
- **上游文件最小化接入点**：
  - `AirPlayPersistence.kt`：4 处 KX11 默认值判断
  - `CarPlayHostActivity.kt`：挂载 `GeelyClusterEnhancer`、`GeelyEcarxDayNightReader` 与 `EcarxLocationEnhancer`
  - `ClusterMapPresentation.kt`：副屏匹配与 `GeelyClusterEnhancer` 生命周期钩子
  - `DiPlayActivity.kt`：新增 `SettingsCategory.GEELY` 入口、`DiagnosticR2UploadUi.attach`、KX11 方向盘按键学习接入与启动主题回调
  - `CarPlayMediaKeys.kt` / `WheelKeyService.kt`：KX11 方向盘按键启停与配置变更通知
  - `DiPlaySessionService.kt`：吉利/ECARX 车机 `onTaskRemoved` 会话保活与销毁回调
  - `common/build.gradle.kts` / `AndroidManifest.xml`：R2 可选 `BuildConfig` 字段与两个吉利后台/通知监听服务声明
