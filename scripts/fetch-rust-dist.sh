#!/bin/bash
# 预下载 Rust 工具链 dist 文件（aria2c 多线程），供 rustup 离线安装加速
#
# 原理：static.rust-lang.org 是 AWS S3，支持 Range 分片 → aria2c -x 16 可达 10+ MiB/s
#       rustup 支持 RUSTUP_DIST_SERVER=file:// 从本地目录安装，秒装
#
# 用法:
#   eval "$(./scripts/fetch-rust-dist.sh)"            # 版本自动读 versions.sh，输出 export 语句
#   eval "$(./scripts/fetch-rust-dist.sh 1.97.1)"     # 指定版本
#   RUST_DIST_DIR=/path/to/cache ./scripts/fetch-rust-dist.sh   # 自定义缓存目录（CI 可复用）
#
# 输出: export RUSTUP_DIST_SERVER / RUSTUP_UPDATE_ROOT（file:// 指向本地 dist 目录）

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -z "${1+x}" ]]; then
  source "${script_dir}/versions.sh"
  version="${IRONFOX_RUST_VERSION}"
else
  version="$1"
fi

# 解析 host triple
host_triple="x86_64-unknown-linux-gnu"
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64)   host_triple="x86_64-unknown-linux-gnu" ;;
  Linux-aarch64)  host_triple="aarch64-unknown-linux-gnu" ;;
  Darwin-arm64)   host_triple="aarch64-apple-darwin" ;;
  Darwin-x86_64)  host_triple="x86_64-apple-darwin" ;;
esac

# Android targets（与 get_sources-if.sh 的 rustup target add 保持一致）
android_targets=(
  "aarch64-linux-android"
  "armv7-linux-androideabi"
  "thumbv7neon-linux-androideabi"
  "x86_64-linux-android"
)

dist_url="https://static.rust-lang.org/dist"
dest="${RUST_DIST_DIR:-${script_dir}/../external/rust-dist}"
mkdir -p "${dest}"

# 文件清单（含 .sha256，rustup 离线安装需要校验）
# - rust-<ver>-<host>.tar.gz：合并包（rustup default 安装 toolchain 用，含 rustc/cargo/rust-std）
# - rust-std-<ver>-<target>.tar.xz：Android 组件包（rustup target add 用）
files=(
  "channel-rust-${version}.toml"
  "rust-${version}-${host_triple}.tar.gz"
)
for t in "${android_targets[@]}"; do
  files+=("rust-std-${version}-${t}.tar.xz")
done

# aria2c 路径探测
aria2c_cmd=""
for c in /usr/bin/aria2c /usr/local/bin/aria2c /bin/aria2c; do
  if [[ -x "${c}" ]]; then
    aria2c_cmd="${c}"
    break
  fi
done
if [[ -z "${aria2c_cmd}" ]]; then
  echo "ERROR: aria2c not found (install: sudo apt install aria2)" >&2
  exit 1
fi

# 逐个下载缺失/损坏的文件（aria2c 多 URL 参数是“镜像”语义，只下第一个；循环保证每个文件都下）
# 完整性规则：文件已存在时用 .sha256 验证，不匹配则重新下载；.aria2 控制文件存在 = 未下完，清理重下
for f in "${files[@]}"; do
  # 未完成下载残留清理（aria2c 中断时留下 .aria2 控制文件）
  if [[ -f "${dest}/${f}.aria2" ]]; then
    echo "Removing incomplete download: ${f}" >&2
    rm -f "${dest}/${f}" "${dest}/${f}.aria2"
  fi

  need_download=0
  if [[ ! -f "${dest}/${f}" ]]; then
    need_download=1
  elif [[ ! -f "${dest}/${f}.sha256" ]]; then
    echo "Missing checksum file for ${f}, re-downloading..." >&2
    rm -f "${dest}/${f}"
    need_download=1
  else
    # 验证已有文件的 sha256
    actual=$(sha256sum "${dest}/${f}" | awk '{print $1}')
    expected=$(awk '{print $1}' "${dest}/${f}.sha256")
    if [[ "${actual}" == "${expected}" ]]; then
      echo "Already present and valid: ${f}" >&2
    else
      echo "Checksum mismatch for ${f}, re-downloading..." >&2
      rm -f "${dest}/${f}" "${dest}/${f}.sha256"
      need_download=1
    fi
  fi

  if [[ "${need_download}" == 1 ]]; then
    echo "Downloading ${f}..." >&2
    "${aria2c_cmd}" -x 16 -s 16 -k 1M \
      --file-allocation=none --max-tries=5 --retry-wait=3 --connect-timeout=30 --timeout=120 \
      --console-log-level=warn --summary-interval=0 --auto-file-renaming=false --allow-overwrite=true \
      -d "${dest}" -o "${f}" "${dist_url}/${f}"
    # 校验文件（rustup 离线安装需要）
    "${aria2c_cmd}" -x 4 -s 4 \
      --file-allocation=none --max-tries=3 --connect-timeout=30 --timeout=60 \
      --console-log-level=warn --summary-interval=0 --auto-file-renaming=false --allow-overwrite=true \
      -d "${dest}" -o "${f}.sha256" "${dist_url}/${f}.sha256" || true
    # 下载后验证，防止损坏
    actual=$(sha256sum "${dest}/${f}" | awk '{print $1}')
    expected=$(awk '{print $1}' "${dest}/${f}.sha256")
    if [[ "${actual}" != "${expected}" ]]; then
      echo "ERROR: checksum validation failed for ${f}!" >&2
      exit 1
    fi
  fi
done

echo "All Rust dist files ready at ${dest}" >&2

echo "export RUSTUP_DIST_SERVER=\"file://${dest}\""
# 注意：RUSTUP_UPDATE_ROOT 不设（保持官方）——rustup-init 二进制仍走官方下载（aria2c 16 线程 S3，很快）
# 只加速 toolchain 本体（rustc/cargo/rust-std，几百 MB 的大头）
