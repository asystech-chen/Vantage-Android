# Vantage（Android）

<div align="center">

<img src="assets/ironfox.png"
  alt="Vantage"
  height="200">

</div>

> ⚠️ 上方为 IronFox 占位图标，Vantage 品牌图标制作中，后续替换。

**Vantage** 是一款面向 Android 的 Firefox 浏览器。我们的目标是做「**更好用的 Firefox**」——
保留 Firefox 完整生态（扩展、账号同步、全语言支持），融合桌面版 Vantage 的隐私理念与品牌风格，
为中文用户提供开箱即用的体验。

- **引擎**：GeckoView（Firefox 154）
- **基线**：[IronFox](https://gitlab.com/ironfox-oss/IronFox)（[Mull](https://web.archive.org/web/20250113132510/https://divestos.org/pages/our_apps#mull) 的继任者，Firefox Android 的隐私分支）
- **姊妹项目**：[桌面版 Vantage](https://github.com/asystech-chen/Vantage)（基于 Firefox ESR / LibreWolf）

## 项目方向

**更好用的 Firefox，不是魔改的隐私浏览器。**

**好用优先：**
- 开放扩展安装（Firefox 生态核心，uBlock Origin 等开箱即用）
- 权限请求采用询问制（通知/定位/相机/麦克风）
- 保留密码保存与自动填充
- 默认不清理浏览数据（退出清理作为可选项保留）

**隐私不缺席**（对齐桌面版 Vantage）：
- 遥测 / 崩溃报告 / Nimbus 实验全部禁用
- uBlock Origin 内置
- HTTPS-Only、DoH（阿里 DNS + DNSPod）
- Canvas 指纹随机化、网络 ID 隐藏、推测连接禁用
- 移除 Mozilla 服务（Pocket / Suggest / Relay / AI 控制等）

**品牌与体验对齐桌面版：**
- 蓝绿主题色、Vantage 图标（基于桌面版资产修改）
- **zh-MS 巨硬中文**语言包（全量硬翻，桌面版特色）
- 全语言支持；Vantage 自定义文案仅维护 en-US / zh-CN / zh-TW

## 状态

- 当前：**Nightly 阶段**（Firefox 154 基线，arm64）
- 规划：迁移 **Release** 通道（同包名同签名，无缝升级）
- 包名：`org.vantage.browser`
- 分发：GitHub Releases + 官网；F-Droid 后置
- 更新机制：暂未启用（保留上游检查框架，后续接入）

## 构建

IronFox 让本地构建项目变得更容易（也更快）。例如，使用预构建的 wasi-sdk sysroot 和 llvm-project，而不是在本地构建它们。~~F-Droid 构建仍然从源码构建这些。~~

**建议使用 Docker 镜像构建 IronFox。**

### 使用 Docker 构建

你也可以使用 `main` 标签拉取用于构建最新 IronFox 发布的镜像。或者使用精确的版本名拉取对应版本的镜像。

### 不使用 Docker 构建

**注意**：目前支持在最新版本的 **`Fedora`**、**`macOS`**、**`secureblue`**、**`Ubuntu`** 和 **`Debian`** 系统上构建。其他操作系统/环境可能有效，效果因人而异。

**注意**：**`macOS`** 用户必须先安装 [Homebrew](https://brew.sh/) _（如果尚未安装）_，然后再执行以下步骤。

首先，如果尚未安装 `git`，请为你的平台安装：

**`Fedora`**：

```sh
sudo dnf install git
```

**`macOS`**：

```sh
brew install git
```

**`secureblue`**：

`secureblue` 默认已安装 `git`，无需任何操作。

**`Ubuntu`** / **`Debian`**：

```sh
sudo apt install git
```

成功安装 `git` 后，你需要做的第一件事是克隆 Vantage 的源代码仓库：

_（下面指定了 `--depth=1` 以减少克隆仓库的大小，如果愿意可以移除）_

```sh
git clone --depth=1 git@github.com:asystech-chen/Vantage-Android.git
```

现在，你应该导航到 Vantage 源目录的根目录，并运行 `bootstrap` 脚本：

_（`bootstrap` 脚本将设置并安装构建 Vantage 所需的依赖）_

```sh
cd Vantage-Android
./scripts/bootstrap.sh
```

#### 获取源文件

仍然在 Vantage 源目录的根目录，现在你应该运行 `get_sources` 脚本下载构建 Vantage 所需的外部源（Firefox / Fenix / Application Services 等）：

**注意**：如果需要获取与 Vantage 当前使用的不同版本的依赖源，你需要在运行 `get_sources` 脚本**之前**修改 `scripts/versions.sh`。

_这可能需要一些时间，取决于你的网络速度……_

```sh
./scripts/get_sources.sh
```

#### 准备源文件

现在你需要使用 `prebuild` 脚本对新下载的源进行打补丁/准备：

_获取源文件后必须运行一次。_

```sh
./scripts/prebuild.sh
```

#### 构建

最后，你可以开始构建过程：

```sh
./scripts/build.sh <构建变体>
```

其中 `<构建变体>` 指定要构建的变体，是以下**之一**：

- `arm` - 32 位 ARM（`armeabi-v7a`）
- `arm64` - 64 位 ARM（`arm64-v8a`）
- `x86_64` - 64 位 x86
- `bundle` - 包含所有受支持 ABI 的 Android App Bundle（AAB）

除了 `AAB` 之外，`bundle` 目标还会为每个架构生成 APK _（`arm`、`arm64` 和 `x86_64`）_，以及包含所有架构的 universal APK。

Vantage 当前构建验证目标为 `arm64`（主流手机全覆盖）。

### 国内网络构建提示

构建过程需要大量访问海外资源（GitHub / GitLab / Google maven / Mozilla 等），国内网络下推荐以下措施：

1. **保持代理在线（重要）**：gradle 依赖解析会访问 `maven.google.com`，即使已配置阿里云镜像，个别依赖仍会回落直连 Google 并卡死（TCP 握手无限重试，表现为构建停在 `> IDLE`）。**必须使用 TUN/透明代理模式**（如 clash 的 TUN 开关），仅设置系统代理（GNOME/gsettings）对 Java/gradle 无效。代理断开后最典型症状：`ss -tn state syn-sent` 看到大量指向 `142.251.x.x`（Google）的连接。
2. **aria2c 多线程下载**：执行 `./scripts/enable-aria2.sh --env` 并 eval 输出（或直接跑 `./scripts/enable-aria2.sh` 生成 `env_override.sh`），将 curl 下载替换为 aria2c 16 线程，实测 NDK 783MB 28s（约 26MiB/s）。
3. **Gradle 二进制预下载**：gradlew.py 缓存目录为 `build/gradle/cache/`，网络不稳时可用国内镜像（腾讯云 `mirrors.cloud.tencent.com/gradle/`、华为云 `mirrors.huaweicloud.com/gradle/`）或 aria2c 预先下载对应版本 zip 放入缓存目录，脚本命中缓存后校验 sha256 通过即跳过联网。
4. **GitLab 源**：curl-aria2 包装器已自动处理 GitLab 的 Cloudflare 拦截（raw/archive 重写为 API 路径、archive 优先走 GitHub 镜像）；对打包产物与官方 SHA512 不一致的仓库（Phoenix/prebuilds/unifiedpush-ac）已改为 git 浅克隆指定 commit。
5. **编译机配置**：实测 8 vCPU / 16GB 内存即可完成 arm64 全链路构建（gradle 阶段约 10 分钟；GeckoView C++/Rust 编译耗时数小时）。

构建成功验证：`build/outputs/apk/` 下应出现 `ironfox-<版本>-<abi>-debug-signed.apk`。

更多构建踩坑记录见 [`docs/BUILD-PITFALLS.md`](docs/BUILD-PITFALLS.md)。

### Linting

项目主要由 shell 脚本驱动，使用 [`shellcheck`](https://www.shellcheck.net/) _（静态分析）_ 和 [`shfmt`](https://github.com/mvdan/sh) _（格式化）_ 检查。这些在 CI _（`lint-scripts` 作业）_ 中自动运行并强制执行——lint 失败会在任何构建开始前停止流水线。

Linter 配置位于 `.shellcheckrc` _（检查）_ 和 `.editorconfig` _（格式化）_ 中。

## 贡献

欢迎社区参与：提交 Issue、Pull Request、翻译、测试反馈。贡献指南（CONTRIBUTING.md）整理中。

## 文档

- [`docs/CUSTOMIZATION-GUIDE.md`](docs/CUSTOMIZATION-GUIDE.md) — 定制化指南（154 版）
- [`docs/BUILD-PITFALLS.md`](docs/BUILD-PITFALLS.md) — 构建踩坑记录
- [`docs/Features.md`](docs/Features.md) — 功能特性
- [`docs/Limitations.md`](docs/Limitations.md) — 限制说明
- [`docs/FAQ.md`](docs/FAQ.md) — 常见问题
- [`docs/Network-Connections.md`](docs/Network-Connections.md) — 网络连接说明
- [`docs/NightlyVsRelease.md`](docs/NightlyVsRelease.md) — Nightly 与 Release 差异

## 许可

- **构建脚本**：根据 [GNU Affero General Public License, version 3 or later](COPYING) 许可（继承自 IronFox）
- **补丁**：根据补丁添加或修改的文件头中的许可 _（[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) 或 [MPL 2.0](https://www.mozilla.org/MPL/)）_ 许可（继承自上游 Firefox/Fenix）
- [Phoenix](https://phoenix.celenity.dev/) 在适用情况下根据 [GNU General Public License v3.0 or later](https://spdx.org/licenses/GPL-3.0-or-later.html) _（`GPL-3.0-or-later`）_ 许可。参见 [`COPYING`](https://phoenix.celenity.dev/COPYING.txt)
- `a-c-liberate.patch`、`a-s-localize-maven.patch` 和 `fenix-liberate.patch` 改编自 [Fennec F-Droid](https://gitlab.com/relan/fennecbuild)。参见 [`COPYING`](https://gitlab.com/relan/fennecbuild/-/blob/master/COPYING)
- `gecko-configure-ublock-origin.patch`、`gecko-devtools-bypass.patch`、`gecko-prevent-exposing-name-and-vendor-to-extensions.patch` 和 `gecko-rs-blocker.patch` 改编自 [LibreWolf](https://librewolf.net/)。参见 [LibreWolf License and Disclaimers](https://librewolf.net/license-disclaimers/)
- `fenix-disable-network-connectivity-monitoring.patch`、`gecko-disable-network-id.patch`、`gecko-prevent-fingerprinting-via-chrome-resources.patch`、`geckoview-ironfox-settings-support-spoof-english.patch` 和 `glean-noop.patch` 改编自 [Tor Project](https://www.torproject.org/)。参见 [`LICENSE`](https://gitlab.torproject.org/tpo/core/tor/-/raw/HEAD/LICENSE)
- 壁纸集取自 [Fennec F-Droid](https://gitlab.com/relan/fennecmedia)，可在 [Unsplash license](https://gitlab.com/relan/fennecmedia#licenses) 下使用

## 声明

Mozilla Firefox 是 Mozilla 基金会的商标。

这不是 Mozilla 官方支持的产品。Vantage 与 Mozilla 没有任何关联。

Vantage 不受 Mozilla 赞助或认可。

Vantage 与 DivestOS、Divested Computing Group、Mull 或 IronFox 没有任何关联。

Firefox 源代码可在 [https://github.com/mozilla-firefox/firefox](https://github.com/mozilla-firefox/firefox) 获取。
