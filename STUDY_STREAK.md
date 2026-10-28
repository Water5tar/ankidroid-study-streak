<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# 火热连胜使用说明

本项目基于官方 `v2.25.3`，基线 commit：`d9bf072f814a0c37d30a694192593b31ff2df875`。

## 下载与安装

从本仓库的 Releases 下载对应 CPU 的 Debug APK。大多数较新的安卓手机使用 `arm64-v8a`；较旧的 32 位设备使用 `armeabi-v7a`。`x86_64` 和 `x86` 用于相应设备或模拟器。

安装包经过 Debug 签名验证，包名为 `com.ichi2.anki.debug`，最低 Android 7.0。这个包与官方版并存，不能覆盖升级官方签名的应用。

先保留手机原版，并导出整个集合、包含媒体，再在本版中导入。复习记录和牌组数据随集合迁移，手势、主题和按钮布局等 Android 本地设置需要另行配置。普通集合导出不包含本功能的本地连胜记录。

## 连胜与补签

- 主界面工具栏显示火焰和天数，点击查看当前／最长连胜、42 日历、补签卡和设置。
- 学习前选择计划牌组，可多选并自动包含子牌组；未选择时采用当天首次复习的牌组。第一次成功作答后固定当天计划，此后修改次日生效。
- 必须完成计划内全部今日任务，包含今天稍后到期的学习／重学卡。日期遵循 Anki rollover。
- 连续期间每 30 个真实完成日赠送一张补签卡。保护日计入连胜，但不产生奖励天数。
- 连续缺 N 天需要 N 张卡，一次全部恢复和扣卡；不足时完全不扣卡。成功购买的保护不会因当天新增学习任务而丢失。
- 每 7 天可触发一次轻量庆祝。动画与音效有独立开关，尊重应用和系统的动画及媒体音量设置。

历史从启用本功能后开始观察，不能凭 revlog 推断安装前是否完成过全部计划。连胜元数据仅保存在本机，不通过 AnkiWeb 同步。没有修改 revlog、collection schema、FSRS 或 scheduler 核心算法。

## 构建与验证

使用项目原有 JDK 21、Android SDK 36 和 Gradle wrapper，在配置 `JAVA_HOME` 与 `ANDROID_HOME` 后依次运行：

```powershell
.\gradlew.bat :AnkiDroid:ktlintFormat
.\gradlew.bat :AnkiDroid:ktlintCheck
.\gradlew.bat :AnkiDroid:testFullDebugUnitTest --tests 'com.ichi2.anki.streak.*' --tests 'com.ichi2.anki.StudyOptionsViewModelTest' --tests 'com.ichi2.anki.ui.windows.reviewer.ReviewerViewModelTest'
.\gradlew.bat :libanki:testDebugUnitTest --tests 'com.ichi2.anki.libanki.SchedulerTest'
.\gradlew.bat :AnkiDroid:lintFullDebug :AnkiDroid:assembleFullDebug
```

APK 输出在 `AnkiDroid/build/outputs/apk/full/debug/`。

本地验证：新增 46 项测试全部通过；相关既有测试 58 项通过，2 项原有跳过；Kotlin 格式校验通过；Android lint 为 0 errors、0 warnings、30 hints；Debug APK 构建及四种 ABI 的签名检查通过。

尚未进行真机运行、音效实际播放、实时 AnkiWeb 账户同步和 Accessibility Scanner 验证。上述本地测试不能代替这些验证。

## 来源与许可

官方来源：<https://github.com/ankidroid/Anki-Android>。保留上游的 COPYING、各文件许可和署名，新功能源码使用 GPL-3.0-or-later。

本次功能、测试与文档由 OpenAI Codex（GPT-6.1-sol）辅助实现。没有向官方仓库提交 PR；是否符合上游 AI 贡献资格是独立问题，见 AI_POLICY.md。
