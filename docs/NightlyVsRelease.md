# Vantage Android Nightly 与 Release 差异

## 当前状态
- **当前构建：Nightly**（Firefox 159 基线，`arm64-nolto` 验证目标）
- **规划：迁移 Release 通道**（同包名同签名，无缝升级）

## 关键设计：同包名无缝升级

Vantage 的 **Nightly 与 Release 使用相同包名**，与上游 IronFox 的 Nightly 独立包名不同。当前实测包名为 `asys.vantage.nightly`（Nightly 通道）：

```
build-if.sh 中：
  Nightly: applicationIdSuffix ".nightly"
  Release: 对齐后统一下发（同包名同签名）
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

- 基于 Firefox 159 基线（`scripts/versions.sh`：Gecko `159.0`，Application Services `159.0.1`）
- Vantage 版本号不带 esr/nightly 后缀
- 构建变体切换：`build.sh` 通过 `IRONFOX_RELEASE` 环境变量控制（1=Release，默认 Nightly）

## 构建验证（2026-11-02）

- 变体：`./scripts/build.sh arm64-nolto`
- 产物：`asys.vantage.nightly`，versionCode `2016194026`，versionName `159.0.1793621914598428364`，约 161 MB
- 签名：未配置 keystore 时为 Android Debug（仅内测）；正式分发前需补 keystore
- 详见 [`BUILD-PITFALLS.md`](BUILD-PITFALLS.md) #18 / #19（打包路径与验签）
