# Vantage Android 常见问题（FAQ）

> 构建 / 定制 / 使用中的常见问题速答。详细根因见 [BUILD-PITFALLS.md](BUILD-PITFALLS.md)。

## 构建相关

**Q: 构建卡在 `mach configure` 不动（0% CPU，日志不更新）？**
A: 这是 Glean 遥测初始化死锁（PITFALL #7），仓库已内置 patch（`gecko-disable-telemetry-init.patch`）自动修复。确认 prebuild 已跑过即可。还卡就 `py-spy dump --pid <PID>` 定位。

**Q: 报 `[[: not found` 或走错 mozconfig 分支？**
A: Debian/Ubuntu 的 dash 问题（PITFALL #8），已内置 patch（`gecko-mozconfig-bash.patch`）强制 bash。

**Q: gradle 卡在 `> IDLE` 很久不动？**
A: 依赖回落直连 `maven.google.com` 被掐（PITFALL #10）。开 TUN 透明代理；诊断：`ss -tn state syn-sent | grep 142.251`。

**Q: 下载报 aria2c TLS 握手失败？**
A: GitHub release/archive、dl.google.com 已自动改走 curl（代理直连 + 镜像兜底），无需手动处理。

**Q: 依赖下载 `Remote host terminated the handshake`（Java/gradle）？**
A: 节点对 Java 客户端指纹拦截。A-S 依赖已走阿里云镜像（PITFALL #14）；还遇到就走 TUN 或换节点。

**Q: 重新编译还是很慢？**
A: 移动版是多构建系统编排（gecko + gradle 多层），即使无改动也有固定编排开销；C++ 部分已启用 ccache 加速。Rust 增量被上游禁用（权衡新鲜度）。

**Q: 怎样算构建成功？**
A: `build/outputs/apk/` 出现 `vantage-*-arm64-v8a.apk`（已签名）。

## 定制相关

**Q: 我想改一个功能，应该改哪里？**
A: 先读 `docs/CUSTOMIZATION-GUIDE.md` 的「改功能工作流」——定位层（配置/UI/引擎）→ 选手段（cfg > overlay > patch）→ 重跑 prebuild + build。

**Q: 为什么我改了 external/ 下的源码不生效？**
A: prebuild 会把 `patches/` 下的内容重新覆盖到 `external/`，改 external 会被冲掉。改 `patches/` 或 `configs/` 下的源。

**Q: 默认搜索引擎是什么？怎么改？**
A: 默认 Bing，另有百度/Google/DDG 等。改 `patches/a-c-overlay/.../search/list.json` 的 `searchDefault` 和 `searchOrder`。

**Q: 怎么加一个新的搜索引擎？**
A: 见 CUSTOMIZATION-GUIDE「搜索引擎配置」：在 searchplugins/ 加 XML + 更新 list.json。

## 使用相关

**Q: Nightly 和 Release 什么区别？**
A: 见 `docs/NightlyVsRelease.md`。Vantage 当前是 Nightly 阶段，Nightly 与 Release 同包名（无缝升级设计）。

**Q: 安全浏览（Safe Browsing）数据来自哪里？**
A: 谷歌提供，Vantage 会代理连接保护隐私。详见 `docs/Safe-Browsing.md`。
