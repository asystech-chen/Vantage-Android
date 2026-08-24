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
    --*|-*)
      # 丢弃 curl 专属参数；若下一个参数不像选项（不以 - 开头），连同其值一起丢弃
      # 例: --proto =https → 吞掉 --proto 和 =https；--tlsv1.2 → 只吞自己
      if [[ $# -gt 1 && "${2}" != -* ]]; then
        shift 2
      else
        shift
      fi
      ;;
    *)
      if [[ -z "${url}" ]]; then
        url="$1"
      fi
      shift
      ;;
  esac
done

aria2c_cmd=""
for candidate in /usr/bin/aria2c /usr/local/bin/aria2c /bin/aria2c; do
  if [[ -x "${candidate}" ]]; then
    aria2c_cmd="${candidate}"
    break
  fi
done
if [[ -z "${aria2c_cmd}" ]]; then
  echo "curl-aria2 wrapper: aria2c not found (install with: sudo apt install aria2)" >&2
  exit 1
fi

if [[ -z "${url}" || -z "${output}" ]]; then
  echo "curl-aria2 wrapper: missing url or output" >&2
  exit 1
fi

dir="${output%/*}"
file="${output##*/}"
mkdir -p "${dir}"

# GitLab raw/archive → API 路径（gitlab.com 的 -/raw 和 -/archive 被 Cloudflare 挑战返回 403，API 可用）
# raw:     gitlab.com/GROUP/PROJ/-/raw/COMMIT/PATH → api/v4/projects/GROUP%2FPROJ/repository/files/PATH%2Fenc/raw?ref=COMMIT
# archive: gitlab.com/GROUP/PROJ/-/archive/COMMIT/x.tar.gz → api/v4/projects/GROUP%2FPROJ/repository/archive.tar.gz?sha=COMMIT
if [[ "${url}" == "https://gitlab.com/"* ]]; then
  if [[ "${url}" == *"/-/raw/"* ]]; then
    rest="${url#https://gitlab.com/}"
    proj="${rest%%/-/raw/*}"
    tail="${rest#*/-/raw/}"
    commit="${tail%%/*}"
    path="${tail#*/}"
    url="https://gitlab.com/api/v4/projects/${proj//\//%2F}/repository/files/${path//\//%2F}/raw?ref=${commit}"
    echo "GitLab raw → API: ${file}" >&2
  elif [[ "${url}" == *"/-/archive/"* ]]; then
    rest="${url#https://gitlab.com/}"
    proj="${rest%%/-/archive/*}"
    tail="${rest#*/-/archive/}"
    commit="${tail%%/*}"
    url="https://gitlab.com/api/v4/projects/${proj//\//%2F}/repository/archive.tar.gz?sha=${commit}"
    echo "GitLab archive → API: ${file}" >&2
    # API archive 是动态打包，多连接 Range 不稳定 → 单连接下载
    aria2_args[0]="-x"
    aria2_args[1]=1
    aria2_args[2]="-s"
    aria2_args[3]=1
  fi
fi

# GitHub release 下载 → 国内镜像优先（中科大/清华/上交 + GitHub 兜底），aria2c 多镜像并行拉分片
# 规则: github.com/OWNER/REPO/releases/download/TAG/FILE → MIRROR/OWNER/REPO/TAG/FILE
aria2_urls=("${url}")
if [[ "${url}" == *"/releases/download/"* ]]; then
  if [[ "${url}" == "https://github.com/"* ]]; then
    rest="${url#https://github.com/}"                # OWNER/REPO/releases/download/TAG/FILE
    rest="${rest/\/releases\/download\//\/}"        # OWNER/REPO/TAG/FILE
    rest="${rest//+/%2B}"                            # 文件名里的 + 号 URL 编码（镜像服务器要求）
    aria2_urls=(
      "https://mirrors.ustc.edu.cn/github-release/${rest}"
      "https://mirrors.tuna.tsinghua.edu.cn/github-release/${rest}"
      "https://mirrors.sjtug.sjtu.edu.cn/github-release/${rest}"
      "${url}"
    )
    echo "Using GitHub release mirrors (USTC/TUNA/SJTU + GitHub) for ${file}" >&2
  fi
fi

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
  --user-agent "Mozilla/5.0 (X11; Linux x86_64; rv:138.0) Gecko/20100101 Firefox/138.0"
  -d "${dir}"
  -o "${file}"
)
# GitHub release 对高并发连接有限制（-x 16 会报 connection refused），降到 8
case "${url}" in
  *github.com*|*githubusercontent.com*|*githubassets.com*|*githubreleaseassets*)
    aria2_args[0]="-x"
    aria2_args[1]=8
    aria2_args[2]="-s"
    aria2_args[3]=8
    ;;
esac
if [[ -n "${user_agent+x}" ]]; then
  aria2_args+=(--user-agent "${user_agent}")
fi
aria2_args+=("${aria2_urls[@]}")

if ! "${aria2c_cmd}" "${aria2_args[@]}"; then
  # 模拟 curl --remove-on-error：失败时清理残留文件
  rm -f "${output}" 2>/dev/null
  exit 1
fi
exit 0
