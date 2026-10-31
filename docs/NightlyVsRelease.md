# Vantage Android Nightly 与 Release 差异

## 当前状态
- **当前构建：Nightly**（Firefox 154 基线，arm64 验证目标）
- **规划：迁移 Release 通道**（同包名同签名，无缝升级）

## 关键设计：同包名无缝升级

Vantage 的 **Nightly 与 Release 使用相同包名**（`org.vantage.browser`），与上游 IronFox 的 Nightly 独立包名不同：

```
build-if.sh 中：
  Nightly: applicationIdSuffix ".browser"（与 Release 相同）
  Release: applicationIdSuffix ".browser"
```

目的：用户从 Nightly 升级到 Release **不需要卸载重装**，数据无缝保留。

## 品牌差异（构建时切换）

| 项 | Nightly | Release |
|----|---------|---------|
| 应用名 | Vantage Nightly | Vantage |
| MOZ_APP_NAME | vantage-nightly | vantage |
| 输出 APK | vantage-nightly-*.apk | vantage-*.apk |
| 配置 | `configs/mozconfigs/branding/ironfox-nightly.mozconfig` | `.../ironfox.mozconfig` |

## 版本号

- 基于 Firefox 154 基线（`scripts/versions.sh`）
- Vantage 版本号不带 esr/nightly 后缀
- 构建变体切换：`build.sh` 通过 `IRONFOX_RELEASE` 环境变量控制（1=Release，默认 Nightly）
