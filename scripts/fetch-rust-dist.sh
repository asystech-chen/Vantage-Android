#!/bin/bash
# 预下载 Rust 工具链 dist 文件（aria2c 多线程），供 rustup 离线安装加速
#
# 原理：static.rust-lang.org 是 AWS S3，支持 Range 分片 → aria2c -x 16 可达 10+ MiB/s
#       rustup 支持 RUSTUP_DIST_SERVER=file:// 从本地目录安装，秒装
# 目录结构（rustup dist 协议）：
#   {DIST_SERVER}/dist/channel-rust-<ver>.toml           ← channel manifest（根）
#   {DIST_SERVER}/dist/<date>/<component>-<ver>-<triple> ← 组件（日期子目录）
#
# 用法:
#   eval "$(./scripts/fetch-rust-dist.sh)"            # 版本自动读 versions.sh，输出 export 语句
#   eval "$(./scripts/fetch-rust-dist.sh 1.97.1)"     # 指定版本
#   RUST_DIST_DIR=/path/to/cache ./scripts/fetch-rust-dist.sh   # 自定义缓存目录（CI 可复用）
#
# 输出（stdout 仅 export 行，供 eval；信息走 stderr）:
#   export RUSTUP_DIST_SERVER / RUSTUP_UPDATE_ROOT（file:// 指向本地 dist 目录）

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
dist_dir="${dest}/dist"
mkdir -p "${dist_dir}"

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

# 校验函数：验证 dist_dir 下（或 date_dir 下）的文件 sha256
verify_file() {
  local f="$1"
  local d="$2"
  if [[ ! -f "${d}/${f}.sha256" ]]; then
    return 1
  fi
  local actual expected
  actual=$(sha256sum "${d}/${f}" 2>/dev/null | awk '{print $1}')
  expected=$(awk '{print $1}' "${d}/${f}.sha256" 2>/dev/null)
  [[ -n "${actual}" && "${actual}" == "${expected}" ]]
}

download_file() {
  local f="$1"
  local d="$2"
  local url="$3"
  echo "Downloading ${f}..." >&2
  "${aria2c_cmd}" -x 16 -s 16 -k 1M \
    --file-allocation=none --max-tries=5 --retry-wait=3 --connect-timeout=30 --timeout=120 \
    --console-log-level=warn --summary-interval=0 --auto-file-renaming=false --allow-overwrite=true \
    -d "${d}" -o "${f}" "${url}" >&2
  # 校验文件（rustup 离线安装需要）
  "${aria2c_cmd}" -x 4 -s 4 \
    --file-allocation=none --max-tries=3 --connect-timeout=30 --timeout=60 \
    --console-log-level=warn --summary-interval=0 --auto-file-renaming=false --allow-overwrite=true \
    -d "${d}" -o "${f}.sha256" "${url}.sha256" >&2 || true
  # 下载后强制校验，防止损坏入库
  if ! verify_file "${f}" "${d}"; then
    echo "ERROR: checksum validation failed for ${f}!" >&2
    exit 1
  fi
}

ensure_file() {
  local f="$1"
  local d="$2"
  local url="$3"
  # 未完成下载残留清理（aria2c 中断时留下 .aria2 控制文件）
  if [[ -f "${d}/${f}.aria2" ]]; then
    echo "Removing incomplete download: ${f}" >&2
    rm -f "${d}/${f}" "${d}/${f}.aria2"
  fi
  if [[ -f "${d}/${f}" ]] && verify_file "${f}" "${d}"; then
    echo "Already present and valid: ${f}" >&2
  else
    if [[ -f "${d}/${f}" ]]; then
      echo "Checksum mismatch for ${f}, re-downloading..." >&2
      rm -f "${d}/${f}" "${d}/${f}.sha256"
    fi
    download_file "${f}" "${d}" "${url}"
  fi
}

# 1. channel manifest（dist 根，无日期目录）
manifest="channel-rust-${version}.toml"
ensure_file "${manifest}" "${dist_dir}" "${dist_url}/${manifest}"

# 2. 解析日期目录（manifest 内 date 字段）
date_dir=$(awk -F'"' '/^date =/{print $2; exit}' "${dist_dir}/${manifest}")
if [[ -z "${date_dir}" ]]; then
  echo "ERROR: could not parse date from ${manifest}" >&2
  exit 1
fi
echo "Rust dist date: ${date_dir}" >&2

# 3. 组件文件（dist/<date>/ 下）
#    rustup default 安装 toolchain 时按 manifest 下载的是**组件包**（不是合并包）：
#    - rustc-<ver>-<host>.tar.xz / cargo-<ver>-<host>.tar.xz / rust-std-<ver>-<host>.tar.xz
#    - rust-std-<ver>-<target>.tar.xz：Android 组件包（rustup target add 用）
comp_dir="${dist_dir}/${date_dir}"
mkdir -p "${comp_dir}"

components=(
  "rustc-${version}-${host_triple}.tar.xz"
  "cargo-${version}-${host_triple}.tar.xz"
  "rust-std-${version}-${host_triple}.tar.xz"
)
for t in "${android_targets[@]}"; do
  components+=("rust-std-${version}-${t}.tar.xz")
done

for f in "${components[@]}"; do
  ensure_file "${f}" "${comp_dir}" "${dist_url}/${date_dir}/${f}"
done

echo "All Rust dist files ready at ${dest}/dist" >&2
echo "export RUSTUP_DIST_SERVER=\"file://${dest}\""
echo "export RUSTUP_UPDATE_ROOT=\"file://${dest}\""
