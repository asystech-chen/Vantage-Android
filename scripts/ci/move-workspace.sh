#!/bin/bash
# Move the Vantage-Android build tree into the Actions runner workspace, so CI
# and local builds share one tree, one objdir and one cache set.
#
# Usage:
#   sudo ./scripts/ci/move-workspace.sh --dry-run
#   sudo ./scripts/ci/move-workspace.sh
#
# Options:
#   --source PATH   默认 /home/chen/Vantage-Android
#   --dest PATH     默认 /home/chen/actions-runner-android/_work/Vantage-Android/Vantage-Android
#   --user NAME     runner 用户，默认 ci

set -uo pipefail

dry_run=0
source_dir='/home/chen/Vantage-Android'
dest_dir='/home/chen/actions-runner-android/_work/Vantage-Android/Vantage-Android'
runner_user='ci'

usage() {
  /usr/bin/sed -n '2,14p' "$0" | /usr/bin/sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      dry_run=1
      shift
      ;;
    --source)
      source_dir="$2"
      shift 2
      ;;
    --dest)
      dest_dir="$2"
      shift 2
      ;;
    --user)
      runner_user="$2"
      shift 2
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

run() {
  if [[ "${dry_run}" -eq 1 ]]; then
    printf '[dry-run] %s\n' "$*"
  else
    "$@"
  fi
}

if [[ "${dry_run}" -eq 0 ]] && [[ "${EUID}" -ne 0 ]]; then
  echo 'ERROR: 真实执行需要 root（--dry-run 可以在普通用户下预览）' >&2
  exit 1
fi

# --- preflight ---------------------------------------------------------------
if [[ ! -d "${source_dir}/.git" ]]; then
  echo "ERROR: 源目录不是 git 仓库: ${source_dir}" >&2
  exit 1
fi
if [[ -e "${dest_dir}" ]]; then
  echo "ERROR: 目标已存在: ${dest_dir}" >&2
  echo '  如果它是旧工作区，请先确认后手动移走。' >&2
  exit 1
fi
if ! id -u "${runner_user}" > /dev/null 2>&1; then
  echo "ERROR: 用户不存在: ${runner_user}" >&2
  exit 1
fi
if /usr/bin/pgrep -f '[m]ach build' > /dev/null 2>&1 ||
  /usr/bin/pgrep -f '[n]inja' > /dev/null 2>&1 ||
  /usr/bin/pgrep -f '[R]unner[.]Worker' > /dev/null 2>&1 ||
  /usr/bin/pgrep -f '[s]cripts/build[.]sh' > /dev/null 2>&1; then
  echo 'ERROR: 有构建在跑，拒绝迁移' >&2
  exit 1
fi

src_dev="$(/usr/bin/stat -c %d "${source_dir}")"
dest_parent="$(/usr/bin/dirname "${dest_dir}")"
# 目标父目录可能还不存在：向上找到最近的已存在祖先，用于空间/文件系统判断
probe_dir="${dest_parent}"
while [[ ! -d "${probe_dir}" ]] && [[ "${probe_dir}" != '/' ]]; do
  probe_dir="$(/usr/bin/dirname "${probe_dir}")"
done
dst_dev="$(/usr/bin/stat -c %d "${probe_dir}" 2> /dev/null || echo '')"
if [[ -n "${dst_dev}" ]] && [[ "${src_dev}" != "${dst_dev}" ]]; then
  echo 'ERROR: 源与目标不在同一文件系统，mv 会变成复制' >&2
  echo "  源: ${source_dir} (dev ${src_dev})" >&2
  echo "  目标: ${dest_dir} (dev ${dst_dev})" >&2
  exit 1
fi

echo "source: ${source_dir}"
echo "dest  : ${dest_dir}"
echo "user  : ${runner_user}"
echo "size  : $(/usr/bin/du -sh "${source_dir}" 2> /dev/null | /usr/bin/cut -f1)"
echo "free  : $(/usr/bin/df -h "${probe_dir}" 2> /dev/null | /usr/bin/tail -n 1)"
echo

# --- move --------------------------------------------------------------------
run /usr/bin/install -d -o "${runner_user}" -g "${runner_user}" -m 0755 "${dest_parent}"
run /bin/mv "${source_dir}" "${dest_dir}"
run /usr/bin/chown -R "${runner_user}:${runner_user}" "${dest_dir}"

# chen keeps write access so local builds keep working.
run /usr/bin/setfacl -R -m u:chen:rwX "${dest_dir}"
# 工作区属主是 ci，chen 在里面执行 git 需要显式放行（dubious ownership）
run /usr/sbin/runuser -u chen -- /usr/bin/git config --global --add safe.directory "${dest_dir}"
if [[ "${dry_run}" -eq 1 ]]; then
  printf '[dry-run] find %s -type d -exec setfacl -d -m u:chen:rwX {} +\n' "${dest_dir}"
else
  /usr/bin/find "${dest_dir}" -type d -exec /usr/bin/setfacl -d -m u:chen:rwX {} +
fi

echo
echo '完成。验证：'
echo "  git -C ${dest_dir} status -sb"
echo "  getfacl ${dest_dir} | head"
