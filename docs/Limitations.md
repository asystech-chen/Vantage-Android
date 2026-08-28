# Vantage Android 限制说明

## 当前状态限制（Nightly 阶段）
- **Nightly 构建**：未到 Release 质量，可能有不稳定问题
- **更新机制未启用**：保留上游检查框架，后续接入（`vantage.aboutMenu.checkVersion` 相关 pref 已就绪）
- **分发渠道**：GitHub Releases + 官网；F-Droid 后置（规划中）

## 架构限制
- **多构建系统**：GeckoView（mach）+ Fenix/A-S/UP-AC（gradle）串联，全量重编耗时数小时；即使无改动，重编也有固定编排开销
- **Rust 增量编译被上游禁用**（`--disable-cargo-incremental`，保证产物新鲜度）——Rust 相关改动重编较慢
- **补丁体系依赖上游源码结构**：Firefox 大版本升级时，diff patch 可能需要重新生成（冲突会显性暴露，见 CUSTOMIZATION-GUIDE 常见误区）

## 网络限制
- 国内网络下构建需要代理/镜像方案（见 README「网络与下载方案」）
- 部分海外服务（maven.google.com 等）直连不可达，依赖 TUN 代理或镜像

## 品牌说明
- 图标仍为 IronFox 占位，Vantage 品牌图标制作中
- Vantage 与 Mozilla / IronFox 无官方关联
