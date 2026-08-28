# Vantage 定制化指南（基于 IronFox v154）

本文档说明如何基于本仓库源码进行**品牌定制**和**功能调整**。方法学与桌面版 Vantage 一致（patch / overlay / 配置文件），只是 Android 版有多个源码层（gecko / fenix / A-S）需要分别处理。

> 本指南基于 **IronFox v154.0.0.1**（2026-08-21 上游发布）整理。150 → 154 期间 patch 体系经过重构（35 新增 / 33 删除），旧版（150）指南中的部分 patch 名称已失效，请以本文档为准。

---

## 目录

1. [快速开始](#快速开始)
2. [改功能工作流（先读）](#改功能工作流先读)
3. [品牌定制](#品牌定制)
4. [功能定制](#功能定制)
5. [搜索引擎配置](#搜索引擎配置)
6. [隐私与安全设置](#隐私与安全设置)
7. [构建配置](#构建配置)
8. [常见问题](#常见问题)

---

## 快速开始

### 前置要求

- **Android SDK**：platform 37.1 / build-tools 37.0.0（见 `scripts/versions.sh`）
- **Android NDK**：r29
- **JDK**：17 / 21 / 25（构建脚本按需自动选择）
- **Git**、**Docker**（推荐）或 Fedora / Ubuntu / Debian / macOS / secureblue 构建环境
- 至少 100GB 可用磁盘空间、16GB+ 内存

### 源码结构

```
vantage-android/
├── assets/                 # 品牌/分发资源（图标、应用商店徽章、二维码）
├── configs/
│   ├── mozconfigs/         # Gecko 编译配置
│   │   ├── branding/       #   品牌配置（ironfox / ironfox-nightly）
│   │   ├── projects/       #   各项目配置（fenix / geckoview / android-components / ironfox-core）
│   │   └── targets/        #   架构目标（arm / arm64 / x86_64 / bundle）
│   └── phoenix/            # AutoConfig + 企业策略（ironfox.cfg / policies.json / overrides）
├── docs/                   # 文档
├── patches/                # 补丁文件（核心）
│   ├── a-c-overlay/        # Android Components 覆盖
│   ├── a-s-overlay/        # Application Services 覆盖
│   ├── fenix-overlay/      # Fenix 覆盖（UI 代码 / 品牌资源 / 字符串）
│   ├── gecko-overlay/      # Gecko 覆盖（默认 prefs / about 页面 / 品牌 / 指纹防护 dumps）
│   ├── glean-overlay/      # Glean 遥测移除覆盖
│   └── *.patch             # 补丁文件
├── scripts/                # 构建脚本
│   ├── patches.yaml        # patch 分类清单（8 大分类）
│   ├── versions.sh         # 版本与工具链配置
│   ├── build.sh            # 构建入口
│   ├── get_sources.sh      # 拉取上游源码
│   ├── prebuild.sh         # 打补丁/准备源码
│   └── sign.sh             # APK/AAB 签名
├── templates/              # 构建模板（local.properties / llvm targets / updates.json）
├── tools/                  # 工具包装（gradle / bundletool）
└── Dockerfile              # 构建镜像（fedora:44）
```

### patch 分层约定

| 前缀 | 作用层 |
|------|--------|
| `a-c-*` | Android Components |
| `a-s-*` | Application Services |
| `fenix-*` | Fenix 应用层（UI、设置、引导） |
| `gecko-*` | Gecko 引擎层 |
| `geckoview-*` | GeckoView 集成层 |
| `glean-*` | Glean 遥测层 |
| `microg-*` | microG 依赖 |

---

## 改功能工作流（先读）

改任何功能前，按下面顺序定位「改哪里」，**优先选最上层、最不依赖源码结构的手段**：

### 第 1 步：定位功能所在层

| 你想改的东西 | 所在层 | 主要位置 |
|-------------|--------|----------|
| 搜索引擎 / 默认搜索 | 引擎数据 | `patches/a-c-overlay/.../search/`（list.json + searchplugins/*.xml） |
| 品牌名 / 图标 / 颜色 / 引导页 | UI | `patches/fenix-overlay/` + `configs/mozconfigs/branding/` |
| 隐私 / 网络 / 行为 pref | 运行时配置 | `configs/phoenix/ironfox.cfg` + `policies.json`（**优先改这里**） |
| DoH / 时区伪装等硬编码默认值 | 引擎 | `patches/gecko-overlay/ironfox/ironfox.configure` |
| 界面文案 | UI 字符串 | `patches/fenix-overlay/.../res/values*/ironfox_strings.xml` |
| 引擎级逻辑（WebGL、扩展、遥测） | 引擎 | gecko/fenix 的 `*.patch`（patches.yaml 注册） |

### 第 2 步：选手段（从稳到不稳）

1. **配置文件**（`ironfox.cfg` / `policies.json`）：改 pref 首选，零编译，改完打包即生效
2. **overlay 整文件覆盖**（`patches/*-overlay/`）：改文件内容/资源，prebuild 直接覆盖到源码树，不受上游代码结构影响
3. **diff patch**（`patches/*.patch` + `scripts/patches.yaml` 注册）：改逻辑，受上游代码结构影响（大版本升级需重新生成）
4. **prebuild sed**（`scripts/prebuild-if.sh`）：品牌/包名类字符串替换

### 第 3 步：改 + 验证

```sh
./scripts/prebuild.sh          # 重打补丁（自动检查 *.rej，冲突会红字列出）
find . -name "*.rej"           # 确认无残留（prebuild 已自动检查，可跳过）
./scripts/build.sh arm64       # 重新构建
```

### 常见误区

- ❌ 直接改 `external/` 下的源码 → **会被 prebuild 覆盖**，必须改 `patches/` 下的源
- ❌ 改完不重跑 `prebuild.sh` → 改动不生效（构建用的是 external/ 里的源码）
- ❌ 上游大版本升级后 patch 直接硬打 → 先跑 `check_patch` 看 dry-run，冲突先重新生成 patch

## 品牌定制

### 1. 应用名称与品牌标识（核心）

**修改位置**: `configs/mozconfigs/branding/ironfox.mozconfig`

```bash
ac_add_options --enable-ironfox-release
ac_add_options --with-app-basename='IronFox'          # 应用基础名
ac_add_options --with-app-name='ironfox'              # 应用名（决定二进制名）
ac_add_options --with-branding='ironfox/branding/ironfox'  # 品牌资源路径
export MOZ_APP_BASENAME='IronFox'
export MOZ_APP_NAME='ironfox'
export MOZ_APP_REMOTINGNAME='ironfox'
```

Vantage 定制时改为 `--with-app-basename='Vantage'`、`--with-app-name='vantage'`，并同步修改 `--with-branding` 指向新品牌目录。

### 2. 品牌常量（154 机制）

**修改位置**: `patches/gecko-overlay/ironfox/prefs/ironfox.js`

品牌常量以 `@IRONFOX_*@` 占位符注入（configure 阶段替换）：

```js
pref("browser.ironfox.const.IRONFOX_APP_NAME",        "@IRONFOX_APP_NAME@", locked);
pref("browser.ironfox.const.IRONFOX_APP_NAME_PRETTY", "@IRONFOX_APP_NAME_PRETTY@", locked);
pref("browser.ironfox.const.IRONFOX_BUGS_URL",        "@IRONFOX_BUGS_URL@", locked);
pref("browser.ironfox.const.IRONFOX_DEFAULT_DOH_URL", "@IRONFOX_DEFAULT_DOH_URL@", locked);
pref("browser.ironfox.const.IRONFOX_DEFAULT_UBO_ASSETS_URL", "@IRONFOX_DEFAULT_UBO_ASSETS_URL@", locked);
pref("browser.ironfox.const.IRONFOX_FAQ_URL",         "@IRONFOX_FAQ_URL@", locked);
pref("browser.ironfox.const.IRONFOX_RELEASES_URL",    "@IRONFOX_RELEASES_URL@", locked);
pref("browser.ironfox.const.IRONFOX_REPO_URL",        "@IRONFOX_REPO_URL@", locked);
pref("browser.ironfox.const.IRONFOX_URL",             "@IRONFOX_URL@", locked);
pref("browser.ironfox.const.IRONFOX_VERSION",         "@IRONFOX_VERSION@", locked);
```

**修改位置**: `patches/gecko-overlay/ironfox/ironfox.configure`

`set_define("IRONFOX_APP_NAME", ironfox_app_name)` 等定义占位符的实际值，同时作为构建配置的**验证层**（禁用 telemetry / crashreporter / artifact builds / HLS 等，若这些补丁未生效此处构建会失败）：

```
# 品牌常量定义
set_define("IRONFOX_APP_NAME", ironfox_app_name)
set_define("IRONFOX_APP_NAME_PRETTY", ...)
set_define("IRONFOX_VERSION", ...)
# 强制禁用项（验证用）
imply_option("--enable-crashreporter", False)
imply_option("--enable-address-sanitizer-reporter", False)
imply_option("MOZ_NORMANDY", False)
imply_option("MOZ_ANDROID_HLS_SUPPORT", False)
```

### 3. 图标和视觉资源

**修改位置**: `patches/fenix-overlay/app/src/release/res/`

| 文件 | 说明 |
|------|------|
| `mipmap-*/ic_launcher.webp` | 主图标（各密度） |
| `mipmap-*/ic_launcher_private*.webp` | 隐私模式图标 |
| `drawable/ic_launcher_foreground.xml` | 前景矢量 |
| `drawable/ic_launcher_monochrome.xml` | 单色图标（Android 13+） |
| `drawable/*/ic_wordmark_logo.webp` | 文字标志 |
| `drawable/*/ic_wordmark_text_normal.webp` | 普通模式文字标 |
| `drawable/*/ic_wordmark_text_private.webp` | 隐私模式文字标 |
| `drawable/animated_splash_screen.xml` | 启动动画 |
| `drawable/*/fenix_search_widget.webp` | 搜索小部件图标 |

多密度目录：`drawable-hdpi`(1.5x) / `mdpi`(1x) / `xhdpi`(2x) / `xxhdpi`(3x) / `xxxhdpi`(4x)

### 4. 颜色主题

**修改位置**: `patches/fenix-overlay/app/src/main/res/values/ironfox_colors.xml`

```xml
<color name="fx_mobile_layer1">#1A1A2E</color>   <!-- 主背景 -->
<color name="fx_mobile_layer2">#16213E</color>   <!-- 次级背景 -->
<color name="fx_mobile_accent">#0F3460</color>   <!-- 强调色 -->
```

**OLED 真黑主题**：`patches/fenix-ironfox-oled-theme.patch` 提供纯黑（`#000000`）模式。

### 5. 关于页面

**修改位置**: `patches/gecko-overlay/ironfox/about/ironfox/`

- `ironfox.html` - 页面结构（`<link rel="localization" href="ironfox/ironfox.ftl" />`）
- `ironfox.css` - 样式
- 本地化文件：`patches/gecko-overlay/ironfox/locales/<lang>/ironfox/ironfox.ftl`

```ftl
about-vantage-title = 关于 Vantage
about-vantage-description = Vantage 是一个基于 Firefox 的隐私浏览器。
```

### 6. 引导页面（Onboarding）

**修改位置**: `patches/fenix-ironfox-onboarding.patch`

变更文件（154）：
- `mobile/android/fenix/app/onboarding.fml.yaml` - 引导卡片配置
- `.../onboarding/OnboardingFragment.kt`、`OnboardingMapper.kt`、`OnboardingPageState.kt`、`OnboardingPageUiData.kt`、`OnboardingScreen.kt`
- `.../settings/doh/root/DohSettingsScreen.kt` - DoH 配置页
- `FenixApplication.kt` - 应用启动逻辑

在 `onboarding.fml.yaml` 中添加自定义引导卡片（变体方式）：

```yaml
features:
  onboarding:
    variables:
      cards:
        variants:
          vantage-features:
            card-type: vantage-features
            enabled: true
            title: onboarding_vantage_title
            body: onboarding_vantage_description
            image-res: ic_launcher_foreground
            ordering: 10000
            primary-button-label: onboarding_get_started
```

### 7. 品牌链接

**修改位置**: `patches/fenix-ironfox-branding.patch`

替换支持链接、FAQ、仓库地址等为项目自己的：

```diff
-  "https://gitlab.com/ironfox-oss/IronFox/-/issues",
+  "https://github.com/asystech-chen/Vantage-Android/issues",
```

### 8. AutoConfig 与企业策略

**修改位置**: `configs/phoenix/`

| 文件 | 作用 |
|------|------|
| `ironfox.cfg` | AutoConfig 主配置（首行须为 `//` 注释，用 `pref()` / `setEnv()` / `lockPref()` 设置默认值） |
| `phoenix-overrides.cfg` | 覆盖配置 |
| `policies.json` | 企业策略（禁用更新、扩展安装限制、权限策略等） |

Gecko 侧已通过 `gecko-support-policies.patch`、`gecko-support-autoconfig.patch` 启用策略与 AutoConfig 支持；`ironfox.js` 中锁定：

```js
pref("general.config.filename", "ironfox.cfg", locked);
pref("general.config.vendor", "ironfox", locked);
pref("general.config.sandbox_enabled", true, locked);
pref("general.config.obscure_value", 0, locked);
```

---

## 功能定制

### 1. Patch 分类（`scripts/patches.yaml`）

8 大分类：**Build System** / **Dependency** / **Mozilla** / **Privacy** / **Security** / **User Control** / **User Experience** / **User Interface**

### 2. 常用功能 patch 一览（154 有效）

**🔒 隐私**

| Patch | 功能 |
|-------|------|
| `fenix-disable-telemetry.patch` / `gecko-disable-telemetry.patch` | 禁用遥测 |
| `a-c-liberate-glean.patch` / `a-s-liberate-glean.patch` / `fenix-liberate-glean.patch` / `glean-noop.patch` | Glean SDK 清除 |
| `fenix-disable-crash-reporting.patch` / `geckoview-disable-crash-reporting.patch` / `a-c-disable-crash-reporting.patch` | 禁用崩溃报告 |
| `fenix-disable-nimbus.patch` / `gecko-disable-nimbus.patch` / `a-c-liberate-nimbus.patch` / `a-s-disable-nimbus.patch` / `gecko-substitute-nimbus-fml.patch` | 禁用 A/B 实验/远程配置 |
| `fenix-disable-firefox-suggest.patch` | 移除赞助建议 |
| `fenix-disable-pocket.patch` | 移除 Pocket |
| `fenix-disable-contile.patch` | 移除赞助瓷砖 |
| `fenix-liberate-mars.patch` | 移除广告路由服务 |
| `fenix-disable-sync-avatar-fetching.patch` / `fenix-disable-sync-engines-by-default.patch` | 同步隐私 |
| `a-c-disable-amo-collections.patch` | 禁用 AMO 扩展推荐 |
| `fenix-disable-cfrs.patch` | 禁用 CFR（上下文推荐） |
| `fenix-remove-sync-promo-bookmarks.patch` / `fenix-remove-sync-promo-settings.patch` | 移除同步推广 |
| `gecko-disable-network-id.patch` | 禁用网络 ID |
| `gecko-stub-beacon.patch` | Stub `navigator.sendBeacon` |
| `gecko-disable-native-messaging.patch` | 禁用原生消息传递 |
| `gecko-remove-url-tracking-params.patch` | 移除 URL 跟踪参数 |
| `geckoview-disable-network-connectivity-monitoring.patch` / `fenix-disable-network-connectivity-monitoring.patch` | 禁用网络连通性监控 |
| `geckoview-disable-speculative-connections.patch` | 禁用推测连接 |

**🔐 安全**

| Patch | 功能 |
|-------|------|
| `gecko-harden-pdfjs.patch` | PDF.js 加固（参考 GrapheneOS） |
| `gecko-certificate-pinning.patch` | 扩展证书固定域名 |
| `gecko-fix-canvas-randomization.patch` | Canvas 指纹防护 |
| `fenix-enable-memory-tagging.patch` | ARM MTE 内存安全 |
| `fenix-enable-encrypted-storage.patch` | Android Keystore 加密 |
| `gecko-prevent-extensions-from-changing-browser-settings.patch` | 阻止扩展修改设置 |
| `gecko-prevent-exposing-name-and-vendor-to-extensions.patch` | 防止暴露浏览器标识 |
| `gecko-prevent-fingerprinting-via-chrome-resources.patch` / `gecko-prevent-fingerprinting-via-crash-resources.patch` / `gecko-prevent-fingerprinting-via-eme.patch` | 防指纹泄漏 |
| `gecko-remove-clearkey.patch` | 移除 ClearKey DRM |
| `fenix-disable-gms-fonts.patch` | 禁用 GMS 字体 |
| `gecko-remove-aboutrestricted.patch` / `gecko-remove-abouttelemetry.patch` | 移除敏感 about 页面 |

**⚙️ 默认行为（154 合并进 core patch）**

| Patch | 功能 |
|-------|------|
| `fenix-ironfox-core.patch` | Fenix 核心：默认 ETP Strict / HTTPS-Only / 密码管理器禁用 / 自动填充禁用 等（替换了旧版多个小 patch） |
| `gecko-ironfox-core.patch` | Gecko 核心：`geckoview-prefs.js` 默认 prefs、`moz.configure` 调整 |
| `fenix-configure-doh-providers.patch` | DoH 提供商配置（Mullvad / Cloudflare / DNS4EU 等，含国家/地区标签） |
| `fenix-control-autofill-gecko.patch` / `fenix-control-password-mgr-gecko.patch` | 密码管理器/自动填充的 Gecko 联动控制 |
| `fenix-sanitize-data-on-exit-by-default.patch` | 退出时清理数据默认开启 |
| `fenix-increase-update-frequency.patch` | 更新检查频率 24h → 1h |
| `fenix-disable-profiling.patch` | 禁用性能分析 |
| `fenix-disable-firefox-suggest.patch` | 见上 |
| `fenix-remove-ai-controls.patch` | 移除 Firefox 154 AI 功能入口 |
| `gecko-remove-openai.patch` | 移除 OpenAI 相关代码 |
| `gecko-rs-preview-mode.patch` / `gecko-rs-blocker.patch` | Rust 组件相关 |

**🎨 品牌与界面**

| Patch | 功能 |
|-------|------|
| `fenix-ironfox-branding.patch` | Fenix 品牌链接 |
| `gecko-ironfox-branding.patch` | Gecko 品牌 |
| `fenix-ironfox-ui.patch` | UI 调整 |
| `fenix-ironfox-oled-theme.patch` | OLED 主题 |
| `fenix-ironfox-settings.patch` + `fenix-ironfox-settings-search.patch` + `fenix-ironfox-settings-support-*.patch`（accessibility-services / collections / pb-always / translations / xpinstall） | IronFox 设置页与子项 |
| `fenix-ironfox-onboarding.patch` | 引导流程 |
| `fenix-local-wallpapers.patch` | 内置壁纸（替换在线获取） |
| `fenix-secret-settings-visibility.patch` / `fenix-site-settings-visibility.patch` / `fenix-tracking-protection-settings-visibility.patch` | 设置可见性控制 |
| `fenix-hide-remove-and-pb-for-builtin-addons.patch` | 内置扩展管理限制 |

**🔌 依赖替换（Dependency 类）**

| Patch | 功能 |
|-------|------|
| `a-c-liberate.patch` / `fenix-liberate.patch` | Play 服务清除（FIDO → microG） |
| `a-c-liberate-play-integrity.patch` / `fenix-liberate-play-integrity.patch` | 移除 Play Integrity |
| `fenix-liberate-firebase.patch` / `fenix-liberate-sentry.patch` / `fenix-liberate-adjust.patch` / `fenix-liberate-play-review.patch` | 移除 Firebase/Sentry/Adjust/Play 评价 |
| `a-s-bump-android-sdk.patch` / `a-s-remove-dumps.patch` / `a-s-liberate.patch` / `a-s-liberate-error-support.patch` | Application Services 调整 |
| `glean-localize-maven.patch` / `glean-gradle-project-resolution.patch` / `microg-gradle-project-resolution.patch` 等 | Gradle 依赖本地化 |

### 3. 自定义功能开关示例

以 `fenix-ironfox-core.patch` 为参考，修改 `mobile/android/fenix/app/src/main/java/org/mozilla/fenix/utils/Settings.kt` 中的默认值：

```diff
--- a/mobile/android/fenix/app/src/main/java/org/mozilla/fenix/utils/Settings.kt
+++ b/mobile/android/fenix/app/src/main/java/org/mozilla/fenix/utils/Settings.kt
@@ -174,7 +174,7 @@ class Settings(
     var shouldPromptToSaveLogins by booleanPreference(
         appContext.getPreferenceKey(R.string.pref_key_save_logins),
-        default = false,
+        default = true,
     )
```

---

## 搜索引擎配置

### 1. 添加搜索引擎

**修改位置**: `patches/a-c-overlay/components/feature/search/src/main/assets/search/list.json`

```json
{
  "default": {
    "searchDefault": "DuckDuckGo",
    "searchOrder": ["DuckDuckGo", "Marginalia", "Wikipedia"],
    "visibleDefaultEngines": ["ddg", "marginalia", "wikipedia"]
  }
}
```

### 2. 创建搜索引擎定义

**修改位置**: `patches/a-c-overlay/components/feature/search/src/main/assets/searchplugins/`

创建 `yoursearch.xml`：

```xml
<SearchPlugin xmlns="http://www.mozilla.org/2006/browser/search/">
  <ShortName>YourSearch</ShortName>
  <InputEncoding>UTF-8</InputEncoding>
  <Image width="16" height="16">data:image/png;base64,...</Image>
  <Url type="text/html" method="GET" template="https://search.example.com/search">
    <Param name="q" value="{searchTerms}"/>
  </Url>
</SearchPlugin>
```

### 3. 区域特定配置

在 `list.json` 的 `locales` 部分为不同语言配置默认引擎：

```json
"locales": {
  "zh-CN": {
    "default": {
      "visibleDefaultEngines": ["baidu", "ddg", "wikipedia-zh-CN"]
    }
  }
}
```

> 完整多语言 searchplugins（100+ 个 wikipedia-*.xml 等）已包含在 `a-c-overlay` 中。

---

## 隐私与安全设置

### 1. 默认偏好（核心）

**修改位置**: `patches/gecko-overlay/ironfox/prefs/ironfox.js`

这是 Gecko 层默认值的**总开关**（configure 时通过 `gecko-ironfox-core.patch` 注入）。隐私相关默认值示例：

```js
// HTTPS-Only 默认开启
pref("dom.security.https_only_mode", true, locked);

// 本地网络访问限制
pref("dom.security.respect_embedding_origin_policy", true, locked);

// 网络 ID 禁用（见 gecko-disable-network-id.patch）
pref("network.netid.disabled", true, locked);

// 推测连接禁用
pref("network.prefetch-next", false, locked);
pref("network.dns.disablePrefetch", true, locked);

// 扩展权限限制
pref("extensions.webextensions.restrictedDomains", "", locked);
```

### 2. 指纹防护覆盖

**修改位置**: `patches/gecko-overlay/ironfox/dumps/`

- `ironfox-fingerprinting-protection-overrides-harden.json` - 强化模式
- `ironfox-fingerprinting-protection-overrides-unbreak.json` - 兼容模式
- `ironfox-fingerprinting-protection-overrides-unbreak-webgl.json` - WebGL 兼容
- `ironfox-fingerprinting-protection-overrides-unbreak-timezone.json` - 时区兼容

### 3. DoH 提供商

**修改位置**: `patches/fenix-configure-doh-providers.patch`

154 内置提供商：**Mullvad (Base)**（默认）/ **Cloudflare** / **Cloudflare (Malware Protection)** / **DNS4EU (Ad Blocking / Protective / Unfiltered)** / **NextDNS** 等，每个带国家/地区 emoji 标签。默认值通过 `IRONFOX_DEFAULT_DOH_URL` 品牌常量注入。

Vantage 定制时可改为阿里 DNS / DNSPod 等（参考桌面版 DoH 方案）：

```diff
- val mullvadBase = Provider.BuiltIn(
-     url = mullvadBaseUri,
-     name = "Mullvad (Base) 🇸🇪",
-     default = dohDefaultProviderUrl.isNullOrBlank() || dohDefaultProviderUrl == mullvadBaseUri,
+ val alidns = Provider.BuiltIn(
+     url = alidnsUri,
+     name = "AliDNS 🇨🇳",
+     default = dohDefaultProviderUrl.isNullOrBlank() || dohDefaultProviderUrl == alidnsUri,
```

### 4. 数据清理

**修改位置**: `patches/fenix-sanitize-data-on-exit-by-default.patch`

```kotlin
var shouldDeleteBrowsingDataOnQuit = true

private val deleteOnQuitSettings = setOf(
    DeleteOnQuitItem.HISTORY,
    DeleteOnQuitItem.COOKIES,
    DeleteOnQuitItem.CACHE,
    DeleteOnQuitItem.DOWNLOADS
)
```

### 5. 权限控制

**修改位置**: `patches/fenix-ironfox-core.patch` / `fenix-site-settings-visibility.patch` 相关

```kotlin
// 默认阻止的权限
val blockedPermissions = setOf(
    SitePermissions.Permission.NOTIFICATION,
    SitePermissions.Permission.LOCATION,
    SitePermissions.Permission.CAMERA,
    SitePermissions.Permission.MICROPHONE
)
```

### 6. uBlock Origin

**修改位置**: `patches/gecko-configure-ublock-origin.patch`

- 配置 uBlock Origin 默认设置与资源 URL（`IRONFOX_DEFAULT_UBO_ASSETS_URL` 品牌常量）
- `patches/a-c-allow-ubo-in-private-browsing-by-default.patch` - 允许 uBO 在隐私窗口运行
- `patches/a-c-display-builtin-addons.patch` - 显示内置扩展

---

## 构建配置

### 1. 版本与工具链

**修改位置**: `scripts/versions.sh`

154 关键版本（2026-08-21）：

```bash
readonly IRONFOX_GECKO_VERSION='154.0'          # Firefox Gecko 版本
readonly IRONFOX_VERSION="${IRONFOX_GECKO_VERSION}.0.1"  # IronFox 版本
readonly IRONFOX_AS_VERSION='154.0'             # Application Services
readonly IRONFOX_GLEAN_VERSION='68.0.1'         # Glean
readonly IRONFOX_ANDROID_NDK_VERSION='r29'
readonly IRONFOX_ANDROID_SDK_VERSION='21.0'
readonly IRONFOX_ANDROID_SDK_BUILD_TOOLS_VERSION='r37'   # 37.0.0
readonly IRONFOX_ANDROID_SDK_PLATFORM_VERSION='37.1'
readonly IRONFOX_JDK_17_VERSION='17.0.20'       # 另有 21 / 25
readonly IRONFOX_RUST_VERSION='1.97.1'
readonly IRONFOX_NODE_VERSION='26.7.0'
readonly IRONFOX_PYTHON_VERSION='3.14.7'
```

### 2. Mozconfig 结构

```
configs/mozconfigs/
├── branding/
│   ├── common.mozconfig
│   ├── ironfox.mozconfig          # release 品牌（--enable-ironfox-release）
│   └── ironfox-nightly.mozconfig  # nightly 品牌
├── build.mozconfig / clean.mozconfig / core.mozconfig / no-build.mozconfig
├── ironfox.mozconfig              # 主配置（引用 projects/ + targets/ + branding/）
└── projects/
    ├── android-components.mozconfig
    ├── fenix.mozconfig
    ├── geckoview.mozconfig
    └── ironfox-core.mozconfig
```

### 3. 构建流程

**使用 Docker（推荐）**：

```bash
# 本地构建镜像（fedora:44 + 依赖，自动构建缓存）
./scripts/run-docker.sh

# 在容器内执行构建
./scripts/run-docker.sh -- ./scripts/build.sh arm64

# 或先获取源码/打补丁
./scripts/run-docker.sh -- ./scripts/get_sources.sh
./scripts/run-docker.sh -- ./scripts/prebuild.sh
./scripts/run-docker.sh -- ./scripts/build.sh bundle
```

**本地构建**（Fedora / Ubuntu / Debian / macOS / secureblue）：

```bash
# 1. 初始化环境（安装依赖）
./scripts/bootstrap.sh

# 2. 获取源码（可修改 versions.sh 指定依赖版本）
./scripts/get_sources.sh

# 3. 打补丁/准备源码（获取源码后必须运行一次）
./scripts/prebuild.sh

# 4. 构建（arm / arm64 / x86_64 / bundle）
./scripts/build.sh arm64
```

> `bundle` 目标同时产出各架构 APK + universal APK + AAB（ApkSet）。

**构建变体**：

| 变体 | 说明 |
|------|------|
| `arm` | 32 位 ARM（armeabi-v7a） |
| `arm64` | 64 位 ARM（arm64-v8a） |
| `x86_64` | 64 位 x86 |
| `bundle` | 全 ABI AAB + 各架构 APK + universal APK |

### 4. 签名配置

**环境变量**（`scripts/env_common.sh` 默认 `null`，CI 在 `env_ci.sh` 配置）：

```bash
export IRONFOX_ANDROID_KEYSTORE='/path/to/keystore.jks'
export IRONFOX_ANDROID_KEYSTORE_KEY_ALIAS='vantage'
export IRONFOX_ANDROID_KEYSTORE_PASS_FILE='/path/to/keystore-pass.txt'
export IRONFOX_ANDROID_KEYSTORE_KEY_PASS_FILE='/path/to/key-pass.txt'
```

设置后运行 `./scripts/sign.sh <arm|arm64|x86_64|bundle>` 签名。未设置时构建跳过签名。

### 5. Patch 管理

**修改位置**: `scripts/patches.yaml`

添加自定义 patch：

```yaml
categories:
  - name: "Vantage Custom"
    excerpt: "Vantage 专属定制补丁"
    description: "品牌定制和功能调整"

patches:
  - file: "vantage-branding.patch"
    name: "Vantage 品牌定制"
    category: "Vantage Custom"
```

**禁用 patch**：将对应条目删除或注释（patches.yaml 是补丁应用清单）。

**生成 patch**（`scripts/patch_create.sh`）：在打补丁后的源码树上修改，用 `git diff` 生成新 patch，遵循上游 patch 格式（`From 0000...` 头 + Signed-off-by）。

---

## 常见问题

### Q1: 如何修改应用包名？

修改 `configs/mozconfigs/branding/ironfox.mozconfig`（或新建 `vantage.mozconfig`）中的 `--with-app-name` / `--with-app-basename`，以及 Fenix 层包名相关 patch。包名 `org.ironfoxoss.ironfox` 的替换涉及 `patches/fenix-overlay/` 下的代码与资源。

### Q2: 如何添加自定义 about 页面？

1. 在 `patches/gecko-overlay/ironfox/about/` 创建页面目录
2. 添加 HTML / CSS / FTL 本地化文件
3. 在 `moz.build` / `jar.mn` 中注册页面
4. 参考 `about:ironfox` 的实现（`IronFoxAboutPages.sys.mjs`）

### Q3: 如何禁用某个 patch？

在 `scripts/patches.yaml` 中删除或注释对应条目。⚠️ 154 起大量默认行为合并进 `fenix-ironfox-core.patch` / `gecko-ironfox-core.patch`，禁用小 patch 前先确认功能是否在 core patch 中。

### Q4: 构建失败怎么办？

1. 检查磁盘空间（≥100GB）、内存（≥16GB）
2. 确认网络可访问 Mozilla / GitLab / GitHub 源
3. 查看日志：`./scripts/build.sh arm64 2>&1 | tee build.log`
4. 检查 `.rej` 文件（patch 应用失败残留）：`find . -name "*.rej"`（⚠️ 上游 `patch --forward` 对 hunk 失败是静默容忍的，编译"成功"但产物可能缺代码）
5. 清理重试：删除已拉取的源码目录后重新 `get_sources.sh` + `prebuild.sh`

### Q5: 如何测试修改？

```bash
# 安装到设备
adb install -r vantage-arm64.apk

# 查看日志
adb logcat | grep -i vantage
```

### Q6: 如何更新 Firefox 基础版本？

1. 修改 `scripts/versions.sh` 中的 `IRONFOX_GECKO_VERSION`、`IRONFOX_AS_VERSION`、`IRONFOX_GLEAN_VERSION` 等
2. 运行 `./scripts/get_sources.sh` 获取新源码
3. `prebuild.sh` 打补丁，逐个检查失败的 patch 并更新（150 → 154 时 patch 体系有大重构，跨大版本建议对比上游 patch 变化）

### Q7: 更新源（updates.json）在哪？

`templates/updates.json` 是更新源模板（当前指向 `releases.ironfoxoss.org`），Vantage 发布时需改为自己的更新服务器。

---

## 参考资源

- [IronFox 上游仓库](https://gitlab.com/ironfox-oss/IronFox)（GitLab，镜像 Codeberg / GitHub）
- [Firefox for Android 源码](https://github.com/mozilla-mobile/firefox-android)
- [GeckoView 文档](https://mozilla.github.io/geckoview/)
- [Android Components](https://github.com/mozilla-mobile/android-components)
- [Phoenix（AutoConfig 体系来源）](https://phoenix.celenity.dev/)

---

_最后更新：2026-08-24（基于 IronFox v154.0.0.1）_
