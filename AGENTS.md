# AGENTS.md

Instructions for coding agents that work on DiPlay.

## Checks

Run the CI command from `.github/workflows/android.yml` before you report a change as done:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

Set `ANDROID_HOME` or `local.properties` if Gradle cannot find the SDK.

## Adding or moving a setting

The Settings screen is grouped by driver goal, not by implementation.
`SettingsCategory`, `SettingsSection` and `SettingsInformationArchitecture` at the top of
`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` define the groups.
`SettingsLayoutPolicyTest` fails when a section has no category or has more than one.

### Choose the category

Ask which goal the driver has when they look for the setting. Use the first row that fits.

| Category | Put a setting here when it controls… | Examples |
| --- | --- | --- |
| Connection | how the iPhone connects and how DiPlay starts | connection setup, connect on open, start with the car, USB permissions, car hotspot automation, iPhone choice, Android permissions |
| Display | how CarPlay looks on the head-unit screen | day/night mode, picture, size, resolution, frame rate, dock, system bars, multi-window resolution |
| Audio | what the driver hears | media and navigation streams, music buffer |
| Navigation | location and turn-by-turn guidance | location to iPhone, BYD HUD and cluster guidance |
| Vehicle | how CarPlay fits this car and its driver | driving side, wheel keys (Siri, BYD joystick and map zoom), car button, gestures that conflict with the head unit |
| Diagnostics | troubleshooting evidence | diagnostic reports |
| Advanced | experimental, firmware-specific or risky behavior | dashboard map, split screen, screen rotation, side panel, HEVC video, audio focus, audio channel mapping, buffered music, vehicle data |
| Overview | nothing new | see "Overview" below |

A setting goes to Advanced when it is experimental, depends on specific firmware, or is an opt-in that can break sound, video or the connection on some head units.
A control that fixes a common problem and is safe at its default (for example the audio stream choice) stays in its category.
Exception: a gated control that only its own audience sees, and that completes an everyday goal, stays in that goal's category.
Example: auto car hotspot needs ADB but appears only for car-hotspot users, so it is in Connection.

Mark an experimental setting or card with the title suffix ` (experimental)`, never `· experimental`, a dash, or an `Experimental …` prefix.
Each locale MUST use its existing form: `(تجريبي)`, `(experimental)`, `(sperimentale)`, `(экспериментально)`, `(експериментально)`, `(deneysel)`, `(thử nghiệm)`, `(expérimental)`, `（实验性）`, `（實驗性）`, `（実験的機能）`, `(실험 기능)`.
`SettingsTranslationsTest` enforces this list; keep its suffix table in sync with these twelve forms so the two lists do not drift.

### Add the code

1. To extend an existing card, add the control inside the `filteredSection(...)` block of a card in the correct category.
2. For a new card, add a `SettingsSection` entry and map it to exactly one category in `SettingsInformationArchitecture.sectionsByCategory`.
3. Build the new card with `filteredSection(content, SettingsSection.X, …)` inside `allSettingsSections()`.
   A plain `section(...)` call there MUST NOT be used: it renders on every category page.
4. A setting MUST NOT appear in two categories.
   Overview quick settings are the only duplicates. They MUST use the same persistence as the full control. Overview SHOULD NOT have more than four quick settings.
   A header shortcut MAY duplicate a Display setting when it uses the same persistence.
5. If the change reconnects CarPlay or applies at the next connection, the description MUST say so.
   A setting that only takes effect at the next connection MUST call `markReconnectNeeded()` after it saves.
   This shows the "Reconnect now" bar. It MUST NOT drop a running session without the driver's consent.
   Reconnect at once (`reconnectIfRunning()`) only after a dialog button that says "Apply and reconnect", or when the description says that the change reconnects CarPlay.
6. Add each new string to `common/src/main/res/values/` and to every `values-xx` locale folder.
   New Settings copy SHOULD use the `settings_` prefix. `SettingsTranslationsTest` fails when a `settings_*` string has no translation.
7. A control that opens a choice uses `button()` with the text `"Title · Value"`.
   That form renders as a setting row with the value and a chevron, and Search indexes the title.
8. When you move a control between categories, update `AdaptiveSettingsUiTest.settingsLiveWhereDriversLookForThem`.

### Overview

Overview holds the connection status, links to the categories, quick settings, About and Language.
Do not add a new setting to Overview. Add it to its category. Then promote it to quick settings only if drivers change it often.

## Settings layout

Spacing, size and shape in the Settings screen MUST come from one place.

1. A gap between blocks MUST use a named dp constant, such as `SETTINGS_BLOCK_GAP_DP`.
   A new literal gap value MUST NOT be added.
2. A size MUST NOT be a pixel constant (`*_PX`). Use dp, and clamp to the available window width.
   Test a menu or panel on a narrow window and on a high-density screen.
3. Controls in one row MUST have the same height.
   Corner radius MUST follow the control height. A new button MUST NOT set its own radius.
4. Every `SettingsCategory` except Overview MUST have a link on Overview.
   `AdaptiveSettingsUiTest.overviewLinksToEveryOtherCategory` checks this.
5. A layout change MUST include compact and full screenshots with a large font scale.
   Add an Arabic (right-to-left) screenshot when the change touches row alignment.

## 本地定制与上游同步规范（低冲突改造原则）

本仓库需要长期与上游主库同步。为尽量避免 `git merge` / `git rebase` 冲突，所有本地定制、车型适配或诊断增强，**必须优先采用“独立扩展文件 + 组合/装饰器 + 最小化接线点”的方式**，避免直接大面积修改主库原有文件。

### 核心原则

1. **优先新增独立文件，而非修改上游原有类**
   - 将新增逻辑、车型适配或诊断封装集中在**同包下的独立新文件**中（例如 `common/src/main/java/com/shilapi/xcertplay/EcarxLocationEnhancer.kt`），不要把自定义逻辑散落到多个上游文件里。
   - 保持同包名可以让原文件调用时**无需新增 `import`**，进一步避免文件头部 import 区产生合并冲突。

2. **优先组合与接口装饰器（Decorator），而非修改原类或强行继承**
   - Kotlin 类默认 `final`，且项目中大量车机适配器是 `object` 单例（如 `EcarxWheelSpeed`），不要为了继承去给上游类加 `open` / `protected`。
   - 优先实现或包装上游已有稳定接口（例如 `Iap2LocationProvider`、`VehicleSpeedSource` 等）：
     - 需要替换某个组件行为时：在独立文件中新建该接口的实现类（如 `GpsOnlyCarPlayLocationProvider : Iap2LocationProvider`），原实现文件保持 **0 改动**。
     - 需要对已有组件做日志增强、过滤、单位换算或兜底时：新建装饰器类包装原实例并透传调用（如 `DiagnosticVehicleSpeedSource(delegate: VehicleSpeedSource)`），原组件文件保持 **0 改动**。

3. **热点文件只保留最小化单点接入（Hook）**
   - 对上游高频变动的大文件（如 `CarPlayHostActivity.kt`、`DiPlayActivity.kt`、`CarPlayController.kt` 等），只在必要的装配位置替换或插入**尽可能少的行数**（理想为 1 行工厂方法调用，如 `EcarxLocationEnhancer.create(this, ::appendLog)`）。
   - 不要顺手重构、格式化或改动接入点之外的无关代码与注释。

4. **不破坏上游原有日志格式与接口契约**
   - 上游可能存在针对日志文本或公开签名的单元测试（例如 `VehicleSpeedLocationProvider` 的 `Location: last 60 s ...` 日志格式），增强日志应作为**新增独立日志行**在自定义封装中输出，不改动上游已有日志模板与公开方法签名。

5. **改造前先评估冲突面**
   - 动手修改前，先用 `git log` 或查看文件规模确认目标文件是否属于上游热点文件；只有在确实没有接口边界、无法通过包装/独立实现完成时，才允许对上游原文件做最小化直接修改，并在修改说明中列出具体接入点。

### 参考范例

- **ECARX（星越L / KX11）位置与轮速增强**：
  - 独立实现与装饰器：`common/src/main/java/com/shilapi/xcertplay/EcarxLocationEnhancer.kt`
  - 上游原文件变动：
    - `EcarxWheelSpeed.kt`：0 改动
    - `AndroidCarPlayLocationProvider.kt`：0 改动
    - `CarPlayHostActivity.kt`：仅 ECARX 分支 1 处工厂调用接入（无新增 `import`）
