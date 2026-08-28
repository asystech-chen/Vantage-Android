#!/bin/bash
# 启用 aria2c 多线程下载加速（curl → aria2c 兼容包装）
# 让 IronFox 的大文件下载（NDK/JDK/Python 等）走 aria2c -x 16 多线程
#
# 用法：
#   ./scripts/enable-aria2.sh                 # 生成本机 env_override.sh（gitignore，不提交）
#   ./scripts/enable-aria2.sh --env           # 输出 export 语句（CI/其他设备用 eval 加载）
#   ./scripts/enable-aria2.sh --check         # 只检测 aria2c 是否可用
#
# 原理：env_common.sh 支持通过 env_override.sh 或环境变量覆盖 IRONFOX_CURL，
#       这里把 IRONFOX_CURL 指向 tools/curl-aria2.sh（已随仓库提交）。

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root_dir="$(dirname "${script_dir}")"
wrapper="${root_dir}/tools/curl-aria2.sh"

find_aria2c() {
  for candidate in /usr/bin/aria2c /usr/local/bin/aria2c /bin/aria2c "$(command -v aria2c 2>/dev/null || true)"; do
    if [[ -n "${candidate}" && -x "${candidate}" ]]; then
      echo "${candidate}"
      return 0
    fi
  done
  return 1
}

mode="${1:-default}"

case "${mode}" in
  --check)
    if aria2c_bin="$(find_aria2c)"; then
      echo "✅ aria2c 可用: ${aria2c_bin}"
      exit 0
    else
      echo "❌ aria2c 未安装（安装: sudo apt install aria2 / sudo dnf install aria2 / brew install aria2）"
      exit 1
    fi
    ;;
  --env)
    if ! aria2c_bin="$(find_aria2c)"; then
      echo "❌ aria2c 未安装，请先安装 aria2" >&2
      exit 1
    fi
    cat << EOF
# aria2c 下载加速（eval "\$(./scripts/enable-aria2.sh --env)" 加载）
export IRONFOX_CURL="${wrapper}"
export IRONFOX_CURL_FLAGS=''
export IRONFOX_CURL_FLAGS_OVERRIDE=1
EOF
    ;;
  default)
    if ! aria2c_bin="$(find_aria2c)"; then
      echo "❌ aria2c 未安装，请先安装（sudo apt install aria2 / sudo dnf install aria2 / brew install aria2）" >&2
      exit 1
    fi
    cat > "${root_dir}/env_override.sh" << EOF
# 本机构建环境覆盖配置（不提交，由 scripts/enable-aria2.sh 生成）
# 用 aria2c 多线程替代 curl 下载（加速大文件）
# 注意：此处只做普通赋值（env_common.sh 会自行 readonly）
# 用 BASH_SOURCE[0] 动态解析仓库根目录（延迟求值，仓库移动后无需重新生成）
IRONFOX_CURL="\$(cd "\$(dirname "\${BASH_SOURCE[0]}")" && pwd)/tools/curl-aria2.sh"
export IRONFOX_CURL
IRONFOX_CURL_FLAGS=''
export IRONFOX_CURL_FLAGS
IRONFOX_CURL_FLAGS_OVERRIDE=1
export IRONFOX_CURL_FLAGS_OVERRIDE
EOF
    echo "✅ 已生成 ${root_dir}/env_override.sh（aria2c: ${aria2c_bin}）"
    ;;
  *)
    echo "用法: $0 [--check|--env]" >&2
    exit 1
    ;;
esac
