# 智能答疑（souti）—— 拍一道题，它讲一遍给你听

给一台**词典笔**（Android 8.1、960×400 固定横屏、单摄像头、WebView 61）写的拍照答疑 App：
拍下题目 → 重新打出题面（公式走 LaTeX 排版）→ 分步讲解 → 追问 → 收藏进错题本。

- 包名 `com.souti.ai`，Kotlin + 原生 View，minSdk 21，无第三方图片库、无 DI。
- AI 侧只有一个依赖：DeepSeek 的 OpenAI 兼容接口（`deepseek-flash`，支持图片输入）。
- 项目页与白话说明：<https://anastasya.top/project/souti>

## 来源说明（照实写）

界面复刻自商业 App `com.jxw.souti`（「智能答疑」）。做法是先把那个 APK 拆包，读出它的布局层级、
文案与交互顺序（`apk/`、`decoded/`、`res_decoded/` 三个目录是拆包工作区，**不入库**），
再用 Kotlin 从零写一套等价的实现。本仓库里的代码全部为本项目自研，不含反编译得到的字节码或资源文件。
原作者、原 App 的名称与商标归其权利人所有；本项目不官方、不商用，只是自用与研究。

## 功能与实现要点

| 模块 | 做法 |
| --- | --- |
| 拍照 | CameraX，前置摄像头需要视图级 `rotationX=180` 才正（M60pro 的摄像头是倒装的）；取景后进入裁剪，框外只加暗、框内不压 |
| 识别 + 讲解 | 图片压到最长边 1280 / JPEG75，**一次调用**同时返回「学科 / 题干 / 解答」，省一半成本；用流式（SSE）边生成边上屏 |
| 题干与解答渲染 | Markdown + LaTeX（`$…$` 行内、`$$…$$` 独立），题干栏是给你核对用的 |
| 问 AI | 上下文只带当前这道题；限定学习话题，越界请求会拒答，但可以顺着知识点拓展 |
| 错题本 | 详情页「收藏」切换开/关，写应用私有 `wrongbook.json`，按题干去重；列表支持学科筛选与批量删除 |
| 语音输入 | 长按聊天输入框录音，上滑取消本次识别，转写走局域网里的 FunASR（`tools/asr_relay.py` 做一层端口转发） |
| 离线保护 | 未联网时禁止拍照搜题并提示联网；相机被抢时强制取回，不弹错误页 |
| 桌面入口 | 无障碍服务把启动器上原「智能答疑」磁贴顶替成自研包；开机/升级/启动三处自愈写回（`nu.nav.bar` 会整条改写该设置） |

## 构建

需要 JDK 21（项目用 zulu-21；JDK 26 下 AGP 会炸）与 Android SDK 34。

```bash
cd app
JAVA_HOME=/path/to/jdk-21 ./gradlew assembleDebug
# 产物：app/app/build/outputs/apk/debug/app-debug.apk
```

## 配置你自己的 API Key（仓库里不带 Key）

Key **不进版本库**。`app/app/build.gradle` 按这个顺序取，取不到就是空串：

1. `-PDEEPSEEK_API_KEY=你的Key`
2. 环境变量 `DEEPSEEK_API_KEY`
3. 根目录 `local.properties` 里的 `DEEPSEEK_API_KEY`（这个文件被 `.gitignore` 排除，最适合放本机真实 Key）

```properties
# app/local.properties
sdk.dir=/Users/you/Library/Android/sdk
DEEPSEEK_API_KEY=sk-......
```

空 Key 也能正常构建、正常安装 —— 只是发起请求时会明确提示「未配置 DeepSeek API Key」，
而不是悄悄用掉别人的额度。已经装上设备后想换 Key，不必重新打包：

```bash
adb shell run-as com.souti.ai   # 写 SharedPreferences cfg / api_key 即可覆盖包内值
```

## 真机回归脚本

`tools/` 下是装机验证用的脚本（裁剪期摄像头是否真关掉、重拍能否接回来、上滑取消语音、断网挡拍照、
桌面入口自愈等），依赖本机 adb 与那台笔的序列号，改一下 `S=` 就能用。

## 已知边界

- 字太小、卷面太脏、题目拍歪会认错；题干栏要人工核对。
- 讲解由 AI 生成，个别步骤要自己判断。
- 错题只存在本机，卸载或清数据会丢，暂无同步。
- 布局按 960×400 横屏适配，在普通手机上能用但不会竖过来。

## 版权

© 2026 AnastasyaLiao. 保留所有权利（All rights reserved）。
未经许可禁止再分发代码、文档与资源。授权事宜见项目页联系方式。
