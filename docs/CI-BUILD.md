# CI 构建（GitHub Actions，自托管）

> 状态：脚本与 workflow 已就绪，runner 尚未安装。
> 相关脚本：`scripts/ci/setup-runner.sh`、`scripts/ci/move-workspace.sh`
> 工作流：`.github/workflows/android-build.yml`

## 设计

- **一棵树**：CI 与本地构建共用同一个工作区，不复制源码/objdir/缓存。
- **runner**：自托管，用户 `ci`，安装目录 `/home/chen/actions-runner-android`。
- **工作区**：`/home/chen/actions-runner-android/_work/Vantage-Android/Vantage-Android`。
- **下载包 / 日志归档**：`/data/ci/vantage-android`（机械盘，可重建）。
- **ccache**：工作区内独立一份，上限 20G。
- **互斥**：`flock /run/lock/ci-build.lock`，CI 与本地构建排队。

## 首次部署

### 1. 安装 runner

GitHub → Settings → Actions → Runners → New self-hosted runner，取注册 token：

```bash
sudo ./scripts/ci/setup-runner.sh --token-file /root/runner-token
```

预演（非 root 可跑）：

```bash
./scripts/ci/setup-runner.sh --dry-run
```

### 2. 迁移工作区

```bash
sudo ./scripts/ci/move-workspace.sh --dry-run
sudo ./scripts/ci/move-workspace.sh
```

迁移会把 `~/Vantage-Android` 整体移动到 runner 工作区，属主给 `ci`，并给
`chen` 保留 `rwX` ACL（含 default ACL），本地构建照常可写。

> 迁移后原路径不再存在，后续开发在
> `/home/chen/actions-runner-android/_work/Vantage-Android/Vantage-Android` 下进行。

### 3. 触发

Actions → Android Build → Run workflow：

| 输入 | 说明 |
|---|---|
| `variant` | `dev-incremental`（默认，10–15 分钟）/ `dev-full` / `release` |
| `sign` | dev 构建是否用发布签名 |
| `prepare` | 树未准备好时勾选，先跑 `prepare-build.sh`（需要联网） |
| `publish` | release 构建后上传 GitHub Release，需填 `tag` |

## 构建矩阵

| 类型 | 命令 | 签名 | 耗时 |
|---|---|---|---|
| dev-incremental | `build.sh dev rebuild-fenix` | 默认 debug；`sign=true` 用发布签名 | 10–15 分钟 |
| dev-full | `build.sh dev` | 同上 | 小时级 |
| release | `IRONFOX_RELEASE=1 IRONFOX_SIGN=1 build.sh arm64` | 发布签名 | 首次 5–9 小时 |

发布构建默认开启：full LTO、`-O3 -ftree-vectorize`、Rust opt-level 3、strip；
arm64 另有 `jit-simd` / `wasm-relaxed-simd`。

## 产物

- `outputs/apk/*.apk`
- `outputs/SHA256SUMS`

包名：dev/nightly 为 `asys.vantage.nightly`，release 为 `asys.vantage`。两者不能互相覆盖安装。

## 签名

直接使用宿主机的 `~/.vantage-android-keys/`（systemd 只读挂载），不向 GitHub 存副本：

```
vantage-release.keystore
.storepass
.keypass
```

别名：`vantage-release`。

## 已知限制

- 首次 release 构建要全量编译 Gecko（`ironfox-release-arm64` objdir 尚不存在）。
- ccache 上限 20G；全 LTO 构建命中率有限。
- 桌面 CI 尚未接入同一把锁；两边同时跑仍会争抢 CPU，手动触发时需自行错开。
- Safe Browsing 默认关闭（无 API key），发布包是否可接受需确认。
