#!/bin/bash
# curl → aria2c 兼容包装器
# 让 IronFox 的 curl 下载自动走 aria2c 多线程（-x 16 -s 16），大幅提升大文件下载速度
#
# 用法（保持 curl 兼容）:
#   curl-aria2.sh [curl 参数...] --location <url> --output <file>
#
# 通过仓库根目录 env_override.sh 注入:
#   readonly IRONFOX_CURL="<本脚本路径>"
#   readonly IRONFOX_CURL_FLAGS=''

set -uo pipefail

url=""
output=""
retries=5

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output|-o)
      output="$2"
      shift 2
      ;;
    --output=*)
      output="${1#*=}"
      shift
      ;;
    --location|-L)
      # aria2c 默认跟随重定向
      shift
      ;;
    --retry|--retry-all-errors|--retry-connrefused|--retry-delay)
      shift 2
      ;;
    --fail|-f|--fail-early)
      shift
      ;;
    --user-agent)
      # 透传 UA（有些服务器需要）
      user_agent="$2"
      shift 2
      ;;
    -s|--silent|--show-error|-S|--progress-meter|--verbose|--no-progress-meter)
      # 输出控制交给 aria2c
      shift
      ;;
    --*)
      # 丢弃 curl 专属参数（--ciphers/--proto/--tls13-ciphers 等，aria2c 不识别）
      shift
      ;;
    *)
      if [[ -z "${url}" ]]; then
        url="$1"
      fi
      shift
      ;;
  esac
done

if [[ -z "${url}" || -z "${output}" ]]; then
  echo "curl-aria2 wrapper: missing url or output" >&2
  exit 1
fi

dir="$(dirname "${output}")"
file="$(basename "${output}")"
mkdir -p "${dir}"

aria2_args=(
  -x 16
  -s 16
  -k 1M
  --file-allocation=none
  --max-tries="${retries}"
  --retry-wait=3
  --connect-timeout=30
  --timeout=60
  --console-log-level=warn
  --summary-interval=0
  --auto-file-renaming=false
  --allow-overwrite=true
  -d "${dir}"
  -o "${file}"
)
if [[ -n "${user_agent+x}" ]]; then
  aria2_args+=(--user-agent "${user_agent}")
fi
aria2_args+=("${url}")

if ! aria2c "${aria2_args[@]}"; then
  # 模拟 curl --remove-on-error：失败时清理残留文件
  rm -f "${output}" 2>/dev/null
  exit 1
fi
exit 0
