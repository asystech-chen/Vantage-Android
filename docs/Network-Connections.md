# Vantage Android 网络连接说明

## 构建期网络（国内必读）

构建需要访问大量海外资源，仓库已内置镜像/代理降级方案，详见 README「网络与下载方案」：

| 目标 | 方案 |
|------|------|
| GitHub release / archive | USTC/TUNA/SJTU 镜像 → curl 代理直连 |
| dl.google.com（Android SDK） | curl 代理直连 → 腾讯云 AndroidSDK 镜像 |
| GitLab（Phoenix/prebuilds 等） | GitHub 镜像 / git 浅克隆 |
| gradle 二进制 | 腾讯云/华为云 gradle 镜像预下载 |
| gradle 依赖 | 阿里云 maven 镜像（GRADLE_MAVEN_REPOSITORIES） |
| 其他（crates.io 等） | TUN 透明代理 |

**TUN 透明代理是 Java/gradle 类工具唯一可靠的代理方式**（系统代理对其无效）。

## 运行时网络

- **DoH**：默认阿里 DNS（`dns.alidns.com`），TRR first 有回退（PITFALL 见 IFPrefs.kt）
- **安全浏览**：由谷歌提供数据库，Vantage 代理连接以保护 IP（见 Safe-Browsing.md）
- **更新检查**：默认关闭（`DisableAppUpdate`、更新 URL 指向 localhost）
- **遥测/崩溃上报**：全部禁用，无后台数据连接
- **主页搜索建议**：随搜索引擎（Bing/百度等）的 suggestion API

## 本机开发环境（桶哥的 Debian 机器）

- mihomo：`127.0.0.1:7890`（http+socks 混合端口），TUN 开关见本机 `tun` 命令
- dl.google.com 走「Google 下载」策略组（香港/新加坡/日本节点自动测速）
- `~/.cargo/config.toml` 的旧代理已注释（走 TUN）
