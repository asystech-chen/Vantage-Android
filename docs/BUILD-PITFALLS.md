# Vantage Android 构建踩坑记录（Pitfalls）

> 适用：vantage-android 仓库（IronFox 154 基线）在 Debian/Ubuntu 类系统 + 国内网络下的构建。
> 记录格式：现象 → 排查 → 根因 → 修复 → 教训。**构建出问题先看下方速查表，再翻对应章节。**
> 首次记录：2026-08-25 首轮构建测试（get_sources → prebuild → build 全流程）。

---

## 现象速查表

| 现象 | 章节 | 一句话解法 |
|------|------|-----------|
| 下载报 `不可识别的 URI` / 包装器递归 | [#1](#1-下载包装器curl-aria2sh-无限递归不可识别的-uri-或不支持的协议30) | 已修：绝对路径 + 递归保护 |
| 镜像下载成功但 checksum 失败（879B HTML） | [#2](#2-ustc-镜像按客户端指纹拦截-aria2c-checksum-失败-879b-html) | 已修：GitHub release 走 curl 镜像降级 |
| GitLab archive checksum 必挂 | [#3](#3-gitlab-archive-产物-sha512-与官方不一致-checksum-必挂) | 已修：git 浅克隆 |
| nvm 装 node 报错 / 下载包装器不兼容 | [#4](#4-nvm-内部-command-curl-与下载包装器不兼容) / [#5](#5-npmrc-的-prefix-导致-nvm-全家桶-exit-11-杀脚本) | 已修：透传 + 临时移走 ~/.npmrc |
| prebuild 报 patch 路径带引号 | [#6](#6-prebuild-报-patch-路径带引号-yq-版本差异) | 已修：yq -r |
| **configure 卡死 0% CPU / 日志不动** | [#7](#7-mach-configure-死锁-40-分钟glean-遥测初始化) | 已做 patch 自动修复（Glean 禁用） |
| **`[[: not found` / 走错 mozconfig 分支** | [#8](#8-mozconfig--not-founddebian-dash-vs-bash) | 已做 patch 自动修复（强制 bash） |
| gradle 下载失败 / checksum mismatch | [#9](#9-gradle-下载失败--缓存损坏813-与-961) | 镜像预下载 + sha256 校验 |
| **gradle 停 `> IDLE` / maven.google.com 卡死** | [#10](#10-agp-921-解析失败mavengooglecom-超时initgradle-无效) | TUN 透明代理（必需） |
| **卡在 `Are you sure? [y/N]`** | [#11](#11-phoenix-flysh-交互确认卡构建) | 已修：PHOENIX_ASSUME_YES=1 自动 |
| configure 报 repeated registration / Item already in manifest | [#12](#12-geckoview-构建-moz_build_appbrowser-引发的系列重复注册) | 修 patch 去重（工作树 + patch 源同步） |
| `MOZ_APP_VENDOR ... can not be set by mozconfig` | [#13](#13-moz_app_vendor-不能从-mozconfig-设置品牌化残留连锁坑) | 只能 implied 设置；扫 IronFox OSS 残留 |
| 依赖下载 `Remote host terminated the handshake`（Java） | [#14](#14-a-s-依赖仓库被改坏mavenlocal-指向空-m2-回落官方源被掐) | A-S 用 GRADLE_MAVEN_REPOSITORIES（aliyun） |
| aria2c TLS 握手失败（GitHub/codeload/dl.google.com） | 仓库 curl-aria2.sh | 已修：这些源走 curl |
| SDK 残留空目录导致跳过 | 仓库 get_sources-if.sh | 已修：自动检测重下 |

---

## 1. 下载包装器（curl-aria2.sh）无限递归："不可识别的 URI 或不支持的协议：30"

**现象**：GitHub release 下载时 wrapper 报 `aria2c errorCode=1 不可识别的 URI 或不支持的协议：30`，镜像降级 4 次全部失败。

**排查**：
- 报错来自 aria2c（download_helper.cc），但代码里 GitHub release 分支用的是 curl → 读脚本确认实际执行路径
- 模拟 IronFox 环境（`PATH=build/path`）复现 → 发现 `build/path/curl` 软链接指向 wrapper 自身

**根因**：IronFox 会 `unset PATH` 并改用 `build/path/` 软链接目录作为工具统一入口，其中 `curl → tools/curl-aria2.sh`（wrapper 本身）。wrapper 内部调用裸 `curl` 时，bash 在 PATH 中找到的是 **wrapper 自己** → 无限递归。第二次递归时参数解析错位：`--retry-all-errors` 被 `--*|-*` 分支当作带值选项 `shift 2`，吞掉了下一个参数 `--connect-timeout`，剩下的 `30` 被当作 URL 传给 aria2c。

**修复**（tools/curl-aria2.sh）：
- 开头探测真实 curl 绝对路径（`/usr/bin/curl`、`/bin/curl`、`/usr/local/bin/curl`），并排除"解析后指向自身"的路径
- 引入 `CURL_ARIA2_GUARD` 环境变量：被递归调用时直接 `exec "${curl_real}" "$@"` 透传
- `--retry-all-errors` / `--retry-connrefused` 改为 shift 1（无值选项，shift 2 会误吞下一个参数）

**教训**：在 PATH 被替换为软链接目录的环境里，包装器脚本内部调用同名命令必然自杀式递归。包装器内一律用绝对路径或加递归保护。

---

## 2. USTC 镜像按客户端指纹拦截 aria2c（checksum 失败，879B HTML）

**现象**：GitHub release 镜像下载"成功"但 sha512 不匹配，文件仅 879B，内容是 `Verifying - USTC Mirrors` 验证页 HTML。

**排查**（对照实验）：
- `curl` 默认 UA（curl/8.x）→ 真文件 35.8MB，sha512 匹配
- `curl -A "Mozilla/5.0 ..."` → 879B 验证页（被当成浏览器）
- `aria2c` 默认 UA → 403；`aria2c --user-agent "curl/8.5.0"` → 仍失败

**根因**：USTC github-release 镜像按**客户端指纹**（TLS/HTTP 指纹，非单纯 UA）区别对待：工具 UA（curl/wget）放行、浏览器 UA 弹 JS 验证页、aria2c 指纹直接 403。UA 伪装无效（aria2c 与 curl 的 TLS 指纹不同）。

**修复**：GitHub release 下载改为 **curl 顺序降级**（USTC → TUNA → SJTU → GitHub 直连兜底），放弃 aria2c 多源并行。实测 USTC 0.68s 下载 35.8MB（约 50MiB/s）。

**教训**：镜像站对下载工具的兼容性差异大，不能假设 aria2c 万能；下载后必须校验（sha512 / 文件头 / zip 完整性）。

---

## 3. GitLab archive 产物 SHA512 与官方不一致（checksum 必挂）

**现象**：GitLab archive 下载（API 路径重写后）成功但 `ERROR: Checksum validation failed`。

**背景**：gitlab.com 的 `-/raw/` 与 `-/archive/` 全被 Cloudflare 挑战拦截（403 "Just a moment..."，UA 无关）→ 需绕行。

**根因**：GitLab API `repository/archive.tar.gz?sha=<commit>` 的**动态打包产物与官方 `-/archive/<sha>/x.tar.gz` 的 SHA512 不一致**（tar 元数据差异）。官方 URL 不可达（403），API 产物校验必挂。

**验证结论**：
- API 打包是**确定性**的（同一 sha 下载两次 sha512 相同）
- **GitHub 镜像的 codeload archive 与 GitLab 官方产物 SHA512 完全一致**（都是标准 git archive 打包）——`celenityy/Phoenix` 有 GitHub 镜像 → 走镜像
- `ironfox-oss/UnifiedPush-AC`、`ironfox-oss/prebuilds` **无 GitHub 镜像** → 改 **git clone**

**修复**：
- 新增 `clone_at_commit()` 函数（scripts/get_sources-if.sh）：`git init` + `git remote add origin` + `git fetch --depth=1 origin <sha>` + `git checkout FETCH_HEAD`，带幂等检查（.git 完整则跳过）
- `get_phoenix` / `get_prebuilds` / `get_up_ac` 三个 GitLab 仓库全部改 `clone_at_commit`（git 自带完整性校验，绕开 SHA512）
- 注：上游 `clone_repo()` 的 `git clone --revision=` 是 hg 参数，git 不支持（实测报错），不可用

**验证**：GitLab git 端点（智能 HTTP）不触发 Cloudflare 挑战；unifiedpush 秒下；prebuilds 380MB 慢但可下（GitLab 带宽限制，属正常速度）。

**教训**：URL 重写不能只看"能不能下载"，还要保证**产物一致性**（checksum 是硬校验）；大仓库优先 git clone（完整性 + 可增量）。

---

## 4. nvm 内部 `command curl` 与下载包装器不兼容

**现象**：`nvm install 26.7.0` 报 `Version '26.7.0' not found`（该版本确实存在，nodejs.org index.tab 可查到）；修复后又报 `curl-aria2 wrapper: missing url`。

**排查**：
- 手动 `nvm ls-remote` 能列出 v26.7.0（说明版本列表拉取 OK）
- `bash -x` 跟踪 nvm 实际 curl 调用：`command curl --compressed -q --fail -L -s <url> -o <file>` 以及 `-o -`、`-o /dev/null`、`-C -`、`--progress-bar` 等变体

**根因**：nvm 内部大量 `command curl` 调用，格式与包装器（只认 `--location <url> --output <file>`）不兼容：
- `-o -`（输出到 stdout）→ wrapper 把 `-` 当下载目标 → aria2c 写目录 `-` 挂起
- `-o /dev/null`（探测用法）→ aria2c 写设备文件挂起
- `--progress-bar` 被 `--*|-*` 分支 shift 2 吞掉后面的 URL → "missing url"

**修复**（tools/curl-aria2.sh）：
- 参数解析后：**没有 --output、或值为 /dev/null、或值为 "-" → 直接透传真实 curl**（保持 100% 兼容）
- **保存 `original_args=("$@")`**：while 解析循环会把 `$@` shift 光，透传必须用循环前保存的原始参数
- `--progress-bar` / `-sS` 加入"无值选项"分支（shift 1）

**教训**：下载包装器必须区分"下载用途"和"工具链内部探测用途"的 curl 调用，后者一律透传；否则破坏 nvm/git 等工具的内部逻辑。

---

## 5. ~/.npmrc 的 prefix 导致 nvm 全家桶 exit 11 杀脚本

**现象**：`nvm install` / `nvm alias` / `nvm use` / **甚至 `source nvm.sh`** 全部报 `Your user's .npmrc file (${HOME}/.npmrc) has a globalconfig and/or a prefix setting`，随后整个 get_sources 退出。

**排查链**：
1. 先怀疑 IronFox 的 `npm_config_globalconfig` 环境变量 → unset 无效（nvm 检查的是文件，不是环境变量）
2. 临时移走 ~/.npmrc → nvm install 成功 → 确认是文件内容问题
3. `bash -x` 发现 **source nvm.sh 本身就触发**（nvm 加载时自动激活已装版本 → 走 prefix 检查）→ `set -e` 下 source 返回非 0 直接杀死脚本

**根因**：
- `~/.npmrc` 含 `prefix=/home/chen/.npm-global`（系统遗留的 npm 全局配置，不可删除）
- nvm 的 `nvm_die_on_prefix` 检查 4 个 npmrc 位置：builtin（`${NVM_VERSION_DIR}/lib/node_modules/npm/npmrc`）、global（`${NVM_VERSION_DIR}/etc/npmrc`）、user（`${HOME}/.npmrc`）、project（`$(nvm_find_project_dir)/.npmrc`），检测到 `prefix`/`globalconfig` 就 return 10/11
- **函数内 exit 11 杀死整个脚本**：`|| true` 拦不住（函数内 exit 直接终止进程），只有子 shell/管道能隔离

**修复**（get_node / get_npm / build-if.sh 三处 source nvm.sh 的地方）：
- 操作前**临时移走 ~/.npmrc**（`mv ~/.npmrc ~/.npmrc.vantage-bak`，不动内容）→ source/install/alias/use → **trap EXIT 恢复**（异常/中断也能恢复）
- nvm 调用统一加 `2>&1 | cat || true`（管道强制子 shell，函数内 exit 只杀管道左侧）
- 用文件存在性验证兜底（`${IRONFOX_NVM}/versions/node/v${IRONFOX_NODE_VERSION}/bin/node`）

**验证**：完整模拟 get_node 链路通过：node v26.7.0 可用、alias default 设置、~/.npmrc 恢复原样、npm 12.0.2 升级成功。

**教训**：nvm 对 npmrc 的检查是"文件级 + 函数内 exit"，防不胜防；临时移走 + trap 恢复是最稳方案。构建完成后 npm 继续走 IronFox 自己的配置（npm_config_globalconfig 环境变量）。

---

## 6. prebuild 报 patch 路径带引号（yq 版本差异）

**现象**：`'/home/chen/vantage-android/patches/"a-c-allow-build-date-override.patch"' does not exist or is not a file`（路径里混入引号字符）。

**根因**：`scripts/patches.sh` 用 `yq '.patches[].file' patches.yaml` 读取 patch 列表。本机 yq 是 **Python 版（kislyuk/yq 3.4.3，jq 包装）**，输出 JSON 风格（字符串带引号）；Go 版 yq（mikefarah）输出纯值。Debian/Ubuntu 的 yq apt 包是 Python 版，IronFox CI（fedora:44 镜像 apt 装）同样是 Python 版——两边行为一致，只是与 Go 版不同。

**修复**：patches.sh 中 4 处 yq 调用加 **`-r`**（raw 输出去引号）：
- PATCH_FILES / AS_PATCH_FILES / GLEAN_PATCH_FILES / UP_AC_PATCH_FILES 的读取行

**验证**：patches.yaml 114 条、a-s-patches.yaml 9 条、glean-patches.yaml 4 条全部 0 缺失。

**教训**：yq 有 Python 版和 Go 版两种实现，行为差异大（引号、`-r` 支持）；先用 `yq --version` 确认实现再写解析代码。

---

## 7. mach configure 死锁 40 分钟（Glean 遥测初始化）

**现象**：`mach configure` 卡死：0% CPU、日志 40 分钟无更新；进程 `wchan=futex_wait_queue`；strace 只见 `futex(FUTEX_WAIT_BITSET_PRIVATE|FUTEX_CLOCK_REALTIME, 0, NULL)` 无限等待。更换 python 3.13/3.14 均复现（与 python 版本无关）。

**排查**：
- `py-spy dump --pid <PID>` → 主线程停在 `mach/main.py:493` 的 `context._telemetry_init_done.wait()`（Event.wait）
- 线程列表只有主线程（worker 线程卡在 C 层，py-spy 无法显示其 python 帧）
- 追代码：`build/mach_initialize.py` 用 `ThreadPoolExecutor(max_workers=1).submit(_create_telemetry)` 在后台线程初始化 **Glean SDK**（telemetry.py 注释：即使遥测禁用也会初始化 Glean，为发送 deletion ping）→ Glean 初始化在本机死锁（C/Rust 层 futex）→ future 永不完成 → `add_done_callback` 不执行 → Event 不 set → 主线程无限等待

**修复**（external/gecko/build/mach_initialize.py）：
- 非 Windows 分支 `driver._telemetry_future = None`（不提交 telemetry future；main.py 的 `if self._telemetry_future is not None:` 会跳过 Event 创建与 wait）
- **保留 ThreadPoolExecutor 定义**（下方 `telemetry_executor.shutdown(wait=False)` 是 if/else 外共用语句，删了会 NameError）
- **已持久化**：`patches/gecko-disable-telemetry-init.patch`（2026-08-28 注册进 patches.yaml）——⚠️ 教训：早期修复只改工作树，get_sources 重下 gecko 后丢失，configure 再次死锁 22 分钟；凡改下载源码的修复必须做成 patch 或 prebuild 步骤

**验证**：mach configure 从"40 分钟卡死"变为"13 秒 Configure complete"。

**教训**：python 进程 0% CPU + futex 死锁 → **py-spy 是定位神器**（`pip install py-spy`）。mach 遥测（Glean）在受限网络/特定环境下初始化可能死锁；本地构建直接禁用（还更隐私）。

---

## 8. mozconfig `[[: not found`（Debian dash vs bash）

**现象**：mozconfig 加载报大量 `mozconfig_loader: 83: ironfox.mozconfig: [[: not found`，条件判断全部错乱，最终 `. projects/gecko.mozconfig: No such file`（走了错误分支）。

**排查**：
1. 修改 `mozconfig_loader` shebang `#!/bin/sh` → `#!/bin/bash` → **无效**（仍按 dash 行为报错）
2. 查看调用方 `mozconfig.py` → 发现**显式 `shell = "sh"`**（注释：Windows 下避免双重 shell 执行）→ **显式调用覆盖了 shebang**

**根因**：Debian/Ubuntu 的 `/bin/sh` 是 **dash**（不支持 `[[`），而 IronFox 的 mozconfig 全部使用 bash 语法（`[[ -z "${VAR+x}" ]]`、`set -euo pipefail`）。Fedora CI 的 /bin/sh 是 bash，因此上游从未暴露此问题。dash 解析 `[[` 报错 → 条件分支错乱。

**修复**：`python/mozbuild/mozbuild/mozconfig.py` 中 `shell = "sh"` → `shell = "bash"`（mozconfig_loader 的 shebang 同步改为 bash 双保险）。
**已持久化**：`patches/gecko-mozconfig-bash.patch`（2026-08-28 注册进 patches.yaml，同样因重下丢失复发过一次）。

**验证**：完整 mozconfig 环境 configure 5 秒 Configure complete，且 `GRADLE_MAVEN_REPOSITORIES` 等 subst 正确读入。

**教训**：跨发行版 shell 兼容性（dash vs bash）是隐藏雷；**"改了为什么没生效"要检查调用方**（显式调用优先级高于 shebang）。

---

## 9. gradle 下载失败 / 缓存损坏（8.13 与 9.6.1）

**现象**：
- microG 构建：gradle-8.13 从 `downloads.gradle.org` 下载 `Connection reset by peer / Connection refused`（国内直连被掐）
- geckoview 构建：gradle-9.6.1 缓存命中但 `Gradle download checksum mismatch!`（缓存为之前下载中断的 19.6MB 残缺文件）

**根因**：downloads.gradle.org 国内网络不稳；gradlew.py 缓存目录 `CACHEDIR=build/gradle/cache`（CACHEDIR 环境变量可覆盖），缓存文件存在就直接用，但**仍校验 sha256**（来自 gradle-transparency-log）。

**修复**：
- 腾讯镜像预下载到缓存目录：`https://mirrors.cloud.tencent.com/gradle/gradle-8.13-bin.zip`、`gradle-9.6.1-bin.zip`
- 删除损坏缓存 → 重新下载 → **sha256 与官方/transparency-log 完全匹配**（官方值：`services.gradle.org/distributions/<file>.sha256`；zip 完整性 `unzip -tq` 验证）
- gradlew.py 命中缓存 + 校验通过 → 跳过联网

**验证数据**：9.6.1 下载 140.7MB，sha256 `9c0f7fae...c9e14` 与 checksums.json 一致。

**教训**：gradlew.py 的缓存命中逻辑是"存在即用但校验 sha256"；镜像下载后必须验证 sha256 与 zip 完整性。

**补充（2026-08-26，9.7.0）**：`gradlew.py` 的 `download_gradle` 重试循环只捕获 `urllib.error.URLError`，**`ConnectionResetError`（socket 层）不在其列** → 一次断连直接 traceback 崩溃，RETRY=3 形同虚设。另外注意 downloads.gradle.org 会 307 到 GitHub release-assets CDN（国内慢且易断，实测单线程 212KB/s）。aria2c 16 线程预下载同样有效（~2.2MB/s 稳定）：
```bash
cd build/gradle/cache && aria2c -x 16 -s 16 -k 1M -o gradle-9.7.0-bin.zip https://downloads.gradle.org/distributions/gradle-9.7.0-bin.zip
sha256sum gradle-9.7.0-bin.zip   # 与 build/gradle/cache/gradle_checksums.json 对比（URL→hash 结构）
```
⚠️ 隐患：下载中断会留下半截文件，下次 `is_file()` 误判已缓存 → 报 checksum mismatch 而非重新下载。

---

## 10. AGP 9.2.1 解析失败（maven.google.com 超时；init.gradle 无效）

**现象**：gradle 报 `Plugin [id: 'com.android.lint', version: '9.2.1'] was not found`，Searched 仓库只有 `.m2 / plugins.gradle.org / maven.google.com`，约 6 分钟超时失败。

**排查链**：
1. 先写 `~/.gradle/init.gradle`（settingsEvaluated + allprojects 加阿里镜像）→ **无效**（Searched 列表不变）
2. 追 gecko 的 `mobile/android/fenix/settings.gradle` → `pluginManagement { repositories { gradle.configureMavenRepositories(delegate) } }`
3. 追 `configureMavenRepositories` 定义（`mobile/android/gradle/mozconfig.gradle`）→ 从 **mozconfig subst `GRADLE_MAVEN_REPOSITORIES`** 读仓库列表
4. `configs/mozconfigs/core.mozconfig` 行 19：`export GRADLE_MAVEN_REPOSITORIES="file://${IRONFOX_MAVEN_LOCAL}/","https://plugins.gradle.org/m2/","https://maven.google.com/"` —— 全国外仓库

**根因**：gecko 的 gradle 插件/依赖仓库**唯一入口是 mozconfig 的 `GRADLE_MAVEN_REPOSITORIES` subst**（configure 时读入 config.status，gradle 构建时经 mozconfig.gradle 消费）。init.gradle 的仓库追加被 settings 的显式配置覆盖，无效。

**修复**：core.mozconfig 的 GRADLE_MAVEN_REPOSITORIES **最前插入阿里镜像**，原仓库保留兜底：
```
"https://maven.aliyun.com/repository/google",
"https://maven.aliyun.com/repository/gradle-plugin",
"https://maven.aliyun.com/repository/central",
"file://${IRONFOX_MAVEN_LOCAL}/",
"https://plugins.gradle.org/m2/",
"https://maven.google.com/"
```

**验证**：
- 阿里镜像关键 artifact 实测存在：`com.android.lint.gradle.plugin:9.2.1` marker 200、`com.android.tools.build:gradle:9.2.1` 200
- 重跑 configure 后 config.status 的 substs 确认阿里镜像在最前
- gradle help：**1m49s BUILD SUCCESSFUL**（之前 4m20s 失败）

**⚠️ 修改 GRADLE_MAVEN_REPOSITORIES 后必须重跑 configure**（substs 才更新）。

**教训**：gradle 仓库配置的唯一入口是 mozconfig subst，不是 init.gradle；改配置后要重跑 configure 验证 substs。

**补充（2026-08-26，回落卡死 + 代理解法）**：即使阿里镜像已插到列表最前，仍有依赖（新发布/镜像同步延迟的 artifact）会**回落直连 `maven.google.com`** → 国内 TCP 握手不通 → 无限重试，构建表现为 CONFIGURING 完成后长时间 `> IDLE`。诊断：`ss -tn state syn-sent` 看是否有指向 `142.251.x.x`（1e100.net = Google）的挂起连接；`ss -tp | grep java` 看 daemon 的连接。**最终解法：TUN/透明代理**（clash 等开 TUN 开关，`127.0.0.1:10808` 类本地端口）——网络层透明转发，Java/gradle 无需任何环境变量即生效；仅 GNOME 系统代理（gsettings manual）Java 不认。代理生效验证：`curl -sI https://maven.google.com/` 应快速返回 3xx/200。无代理时的次选：mozconfig 删除列表末尾的 `maven.google.com`（报错优于卡死），或加华为云 `repo.huaweicloud.com/repository/maven/google/` 兜底。

---

## 11. Phoenix fly.sh 交互确认卡构建

**现象**：构建卡在 `'/home/chen/vantage-android/external/phoenix/outputs/android' already exists ... Are you sure you want to proceed? [y/N]`（等待终端输入）。

**根因**：phoenix 仓库的 `scripts/fly.sh` 有两处 `read -p` 确认（产物覆盖 / 目录删除），构建流程触发时在终端等待。

**修复**：
- fly.sh 两处确认改为检测 `PHOENIX_ASSUME_YES` 环境变量（=1 时自动 `REPLY='y'`；未设置时保持原交互行为）
- **已持久化**（2026-08-28）：get_sources-if.sh `get_phoenix()` clone 后自动 python 替换 fly.sh（幂等）+ build-if.sh `build_phoenix()` 强制 `export PHOENIX_ASSUME_YES=1`；原 env_override.sh 方案已不需要

**教训**：第三方子项目脚本的交互确认要环境变量化（保留手动使用时的确认）。注：build.sh/build-if.sh 本身没有 read -p（各类提示均为 `REPLY='y'` 自动继续），真正的交互确认点逐一排查即可。

---

## 12. GeckoView 构建 MOZ_BUILD_APP=browser 引发的系列重复注册

**现象**（configure 的 backend 阶段逐一暴露）：
- `Directory (aboutpdf) registered multiple times`
- `ValueError: Item already in manifest: browser/defaults/settings/main/anti-tracking-url-decoration.json`
- `ValueError: Item already in manifest: chrome/toolkit/skin/classic/global/aboutCache.css`（其后还有 autocomplete.css、mozapps 的 aboutServiceWorkers.css）

**根因**：**GeckoView 构建的 `CONFIG["MOZ_BUILD_APP"] = 'browser'`**（不是 mobile/android！config.status 中确认）→ 上游 moz.build 中 `!= "mobile/android"`、`not startswith("mobile/")` 等条件块在 Android 构建**也执行** → 与 IronFox 的 patch/overlay 重复注册同一目录/资源。

**修复链**（每处同时改工作树 + patches/ 源，防止 prebuild 重打还原）：
1. **aboutpdf**：`gecko-enable-about-pages.patch` 删除 toolkit/components/moz.build 的 hunk（上游 154 已把 aboutpdf 加入 `MOZ_BUILD_APP != "mobile/android"` 条件块，browser 时执行；patch 再往无条件 DIRS 加一次即重复）
2. **enterprisepolicies**：ironfox overlay 的注册删除（browser/components/moz.build 上游已注册 enterprisepolicies）
3. **settings dumps**：`services/settings/dumps/main/moz.build` 行 21 条件块追加 `and CONFIG["MOZ_BUILD_APP"] != "browser"`（行 143 无条件块在 browser 构建时已注册这些 dump）
4. **themes jar.mn**：`ironfox/themes/global/jar.mn` 55 项全部与 desktop-jar.inc.mn / 平台 jar 重叠 → 清空资源条目；`ironfox/themes/mozapps/jar.mn` 4 项重叠 → 清空（browser 构建时 desktop-jar + 平台 jar 已覆盖全部）

**教训**：Android 构建的 MOZ_BUILD_APP 值不能想当然（GeckoView 复用 browser 代码）；patch 的"语义冲突"（上下文匹配但逻辑重复）比上下文失配更隐蔽，configure 后端会逐一暴露——修一个下一个，要有耐心，且每处修复都要工作树 + patch 源同步。

---

## 13. MOZ_APP_VENDOR 不能从 mozconfig 设置（品牌化残留连锁坑）

**现象**：configure 依次报 `InvalidOptionError: MOZ_APP_VENDOR=Vantage can not be set by mozconfig. Values are accepted from: implied`，修掉后又报 `ConflictingOptionError: Cannot add 'MOZ_APP_VENDOR=Vantage' ... conflicts with 'MOZ_APP_VENDOR=IronFox OSS'`。

**根因链**：
1. gecko 的 `MOZ_APP_VENDOR` 是 `project_flag`（toolkit/moz.configure），**只接受 implied**（代码层 imply_option），mozconfig 里 `export` 直接拒绝
2. 正确姿势是 `ironfox.configure` 里 `imply_option("MOZ_APP_VENDOR", ...)`（overlay，prebuild 复制到 external/gecko/ironfox/）
3. 但 prebuild-if.sh 另有一个品牌化 sed 把 `mobile/android/moz.configure` 的 MOZ_APP_VENDOR 强制改成 `IronFox OSS` → 与 overlay 的 Vantage 冲突（**压缩包合并时漏改的残留**）

**修复**（2026-08-28）：
- `configs/mozconfigs/branding/common.mozconfig`：删除 `export MOZ_APP_VENDOR`
- `patches/gecko-overlay/ironfox/ironfox.configure`：`imply_option("MOZ_APP_VENDOR", "Vantage")`
- `scripts/prebuild-if.sh`：品牌化 sed `IronFox OSS → Vantage`
- 顺手清残留：overlay `android/core/build.gradle` organization、`branding/*/brand.ftl` vendor-short-name、about_content 文案

**教训**：合并第三方品牌化改动时，用 `grep -rn "IronFox OSS"` 全仓库扫残留（含脚本 sed 字符串、overlay 文件），不能只改表面文案。

---

## 14. A-S 依赖仓库被改坏：mavenLocal() 指向空 ~/.m2，回落官方源被掐

**现象**：gradle 报 `:nimbus:compileReleaseKotlin` 依赖解析失败：`Could not HEAD repo.maven.apache.org/...`、`dl.google.com/dl/android/maven2/... Remote host terminated the handshake`（Java TLS 被节点拒）。

**根因**：
- `a-s-localize-maven.patch`（IronFox 为绕 Mozilla 官方 maven 不可达）把 A-S 仓库改成裸 `mavenLocal()`
- `mavenLocal()` 默认指向 `~/.m2`（**空的**），而真实本地仓库是 `build/.m2/repository`（IRONFOX_MAVEN_LOCAL）→ 依赖全部回落官方 central/google → CN 网络被掐
- 我们的 `GRADLE_MAVEN_REPOSITORIES` 已改 aliyun 优先，patch 反而帮倒忙

**修复**（2026-08-28）：`a-s-localize-maven.patch` 只保留无 mozconfig 分支的 `mavenLocal()`；mozconfig 分支**恢复 `GRADLE_MAVEN_REPOSITORIES` 循环**（aliyun google/central + file://本地 + 兜底）。

**教训**：第三方 patch 的"离线化"假设（本地仓库已填充）在本仓库不一定成立；改仓库源之前先确认本地 maven 仓库实际内容和 subst 列表。

---

## 附：诊断工具速查

- **python 死锁**：`py-spy dump --pid <PID>`（`pip install py-spy`，定位到具体 .py 行）
- **进程卡点**：`cat /proc/<PID>/wchan`、`ls /proc/<PID>/task/`（线程数）、`strace -p <PID> -f -tt`
- **mozconfig subst 生效值**：`grep 'GRADLE_MAVEN_REPOSITORIES' external/gecko/obj/ironfox-*/config.status`
- **patch 残留**：`find . -name "*.rej"`
- **yq 实现确认**：`yq --version`（3.x = Python 版，解析要加 `-r`）
- **gradle 缓存**：CACHEDIR=`build/gradle/cache`（gradlew.py），官方 sha256 查 `services.gradle.org/distributions/<file>.sha256`
- **GitHub 镜像探测**：`curl -sIL <mirror-url> -o /dev/null -w "%{http_code}"`（200 且产物 sha512 匹配官方）
- **Java/gradle 下载被掐（TLS handshake）**：curl 同 URL 通但 Java 失败 = 节点/服务对 Java 客户端指纹拦截，改走镜像（aliyun maven）或换节点；`curl -sI https://maven.aliyun.com/repository/google/<path>` 先验证镜像有货
- **A-S 依赖仓库**：`external/application-services/settings.gradle` 的 mozconfig 分支应使用 `GRADLE_MAVEN_REPOSITORIES` 循环（勿改回裸 mavenLocal）
