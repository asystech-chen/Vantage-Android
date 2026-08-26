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

# 保存原始参数（while 解析会 shift 消费 $@，透传真实 curl 时必须用原始参数）
original_args=("$@")

# 递归保护：IronFox 的 PATH=build/path，其中 curl → 本 wrapper。若本脚本又被当作 curl 调用
# （即 build/path/curl → 本文件 → 内部裸 curl → 又是本文件），直接透传给真实 curl 执行
curl_real=""
for candidate in /usr/bin/curl /bin/curl /usr/local/bin/curl; do
  if [[ -x "${candidate}" && "${candidate}" != "$(readlink -f "$0" 2> /dev/null || echo "$0")" ]]; then
    curl_real="${candidate}"
    break
  fi
done
if [[ -z "${curl_real}" ]]; then
  echo "curl-aria2 wrapper: real curl not found" >&2
  exit 1
fi
if [[ "${CURL_ARIA2_GUARD:-0}" == "1" ]]; then
  exec "${curl_real}" "$@"
fi
export CURL_ARIA2_GUARD=1

url=""
output=""
retries=5

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output | -o)
      output="$2"
      shift 2
      ;;
    --output=*)
      output="${1#*=}"
      shift
      ;;
    --location | -L)
      # aria2c 默认跟随重定向
      shift
      ;;
    --retry | --retry-delay)
      shift 2
      ;;
    --retry-all-errors | --retry-connrefused)
      # 无值选项，只吞自己（shift 2 会误吞下一个参数）
      shift
      ;;
    --fail | -f | --fail-early)
      shift
      ;;
    --user-agent)
      # 透传 UA（有些服务器需要）
      user_agent="$2"
      shift 2
      ;;
    -s | --silent | --show-error | -S | --progress-meter | --verbose | --no-progress-meter | --progress-bar | -sS)
      # 输出控制交给 aria2c（--progress-bar/-sS 是 nvm 等工具的无值选项）
      shift
      ;;
    --* | -*)
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

if [[ -z "${output}" || "${output}" == "/dev/null" || "${output}" == "-" ]]; then
  # 调用方没用 --output/-o、输出到 /dev/null 或 stdout（-o -）（如 nvm 内部 `command curl`）：
  # 直接透传真实 curl（原始参数），保持 100% 兼容
  exec "${curl_real}" "${original_args[@]}"
fi
if [[ -z "${url}" ]]; then
  echo "curl-aria2 wrapper: missing url" >&2
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
    # GitHub 镜像优先：git archive 产物与官方一致（SHA512 匹配），codeload 直连可用
    github_mirror="https://github.com/${proj}/archive/${commit}.tar.gz"
    if "${curl_real}" -sIL --max-time 15 "${github_mirror}" -o /dev/null -w "%{http_code}" 2> /dev/null | grep -q "200"; then
      url="${github_mirror}"
      echo "GitLab archive → GitHub mirror: ${file}" >&2
    else
      # 无 GitHub 镜像 → GitLab API（⚠️ 打包产物 SHA512 与官方不一致，仅用于无镜像仓库，如 unifiedpush/prebuilds 已改 git clone）
      url="https://gitlab.com/api/v4/projects/${proj//\//%2F}/repository/archive.tar.gz?sha=${commit}"
      echo "GitLab archive → API: ${file} (single connection)" >&2
      # API archive 是动态打包，多连接 Range 不稳定 → 单连接下载（在下方数组初始化后应用）
      gitlab_archive=1
    fi
  fi
fi

# GitHub release 下载 → 国内镜像顺序降级（USTC→TUNA→SJTU→GitHub 直连兜底）
# 规则: github.com/OWNER/REPO/releases/download/TAG/FILE → MIRROR/OWNER/REPO/TAG/FILE
# 不用 aria2c 多 URL 并行：镜像间内容/速度差异会导致分片拼接损坏（实测 checksum 失败）
github_candidates=()
if [[ "${url}" == "https://github.com/"*"/releases/download/"* ]]; then
  rest="${url#https://github.com/}"        # OWNER/REPO/releases/download/TAG/FILE
  rest="${rest/\/releases\/download\//\/}" # OWNER/REPO/TAG/FILE
  rest="${rest//+/%2B}"                    # 文件名里的 + 号 URL 编码（镜像服务器要求）
  github_candidates=(
    "https://mirrors.ustc.edu.cn/github-release/${rest}"
    "https://mirrors.tuna.tsinghua.edu.cn/github-release/${rest}"
    "https://mirrors.sjtug.sjtu.edu.cn/github-release/${rest}"
    "${url}"
  )
  echo "GitHub release: sequential mirrors (USTC→TUNA→SJTU→GitHub) for ${file}" >&2
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
# GitLab API archive：动态打包不支持 Range 分片，强制单连接
if [[ "${gitlab_archive:-0}" == "1" ]]; then
  aria2_args[0]="-x"
  aria2_args[1]=1
  aria2_args[2]="-s"
  aria2_args[3]=1
fi
# GitHub 直连（codeload archive / raw 等非 release 路径）对高并发敏感，-x 16 会被拒绝连接 → 降到 8
case "${url}" in
  https://github.com/* | https://codeload.github.com/* | https://raw.githubusercontent.com/*)
    aria2_args[0]="-x"
    aria2_args[1]=8
    aria2_args[2]="-s"
    aria2_args[3]=8
    ;;
esac
if [[ -n "${user_agent+x}" ]]; then
  aria2_args+=(--user-agent "${user_agent}")
fi

if [[ ${#github_candidates[@]} -gt 0 ]]; then
  for cand in "${github_candidates[@]}"; do
    # USTC 等镜像按客户端指纹拦截：仅放行工具 UA（curl/wget），aria2c 指纹一律 403/验证页
    # → GitHub release 镜像走 curl 单线程（USTC 实测 ~40MiB/s，够快）；GitHub 直连慢但正确
    if "${curl_real}" -sSL --fail --retry 3 --retry-delay 3 --retry-all-errors --connect-timeout 30 \
      -o "${output}" "${cand}"; then
      echo "OK: ${file} from ${cand%%/github-release/*} (curl)" >&2
      exit 0
    fi
    rm -f "${output}" 2> /dev/null
    echo "mirror failed, trying next: ${file}" >&2
  done
  echo "curl-aria2 wrapper: all GitHub release mirrors failed for ${file}" >&2
  exit 1
fi

if ! "${aria2c_cmd}" "${aria2_args[@]}" "${url}"; then
  # 模拟 curl --remove-on-error：失败时清理残留文件
  rm -f "${output}" 2> /dev/null
  exit 1
fi
exit 0
