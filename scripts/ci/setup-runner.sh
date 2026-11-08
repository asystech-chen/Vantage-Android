#!/bin/bash
# Deploy the self-hosted GitHub Actions runner for Vantage-Android.
#
# Usage:
#   sudo ./scripts/ci/setup-runner.sh --token-file /root/runner-token
#   ./scripts/ci/setup-runner.sh --dry-run
#
# Options:
#   --dry-run            只打印将执行的动作，不做修改（非 root 可跑）
#   --token-file PATH    runner 注册 token（GitHub → Settings → Actions → Runners）
#   --runner-dir PATH    默认 /home/chen/actions-runner-android
#   --user NAME          运行用户，默认 ci
#   --name NAME          runner 名称，默认 vantage-android-builder
#   --labels LIST        标签，默认 self-hosted,vantage-android
#   --repo-url URL       仓库地址
#   --runner-tarball P   runner 安装包，默认复用桌面版的 actions-runner.tar.gz
#   --skip-service       只准备目录与锁文件，不安装 systemd 服务

set -uo pipefail

script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"

dry_run=0
runner_dir='/home/chen/actions-runner-android'
runner_user='ci'
runner_name='vantage-android-builder'
runner_labels='self-hosted,vantage-android'
repo_url='https://github.com/asystech-chen/Vantage-Android'
runner_tarball='/home/chen/actions-runner/actions-runner.tar.gz'
token_file=''
install_service=1
lock_file='/run/lock/ci-build.lock'
ci_data_root='/data/ci/vantage-android'

usage() {
  /usr/bin/sed -n '2,20p' "$0" | /usr/bin/sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      dry_run=1
      shift
      ;;
    --token-file)
      token_file="$2"
      shift 2
      ;;
    --runner-dir)
      runner_dir="$2"
      shift 2
      ;;
    --user)
      runner_user="$2"
      shift 2
      ;;
    --name)
      runner_name="$2"
      shift 2
      ;;
    --labels)
      runner_labels="$2"
      shift 2
      ;;
    --repo-url)
      repo_url="$2"
      shift 2
      ;;
    --runner-tarball)
      runner_tarball="$2"
      shift 2
      ;;
    --skip-service)
      install_service=0
      shift
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

# Real runs need root; dry runs must work as a normal user.
if [[ "${dry_run}" -eq 0 ]] && [[ "${EUID}" -ne 0 ]]; then
  echo 'ERROR: 真实执行需要 root（--dry-run 可以在普通用户下预览）' >&2
  exit 1
fi

runner_home="/home/${runner_user}"
unit_name="actions.runner.$(echo "${repo_url}" | /usr/bin/awk -F/ '{print $(NF-1) "-" $NF}').${runner_name}"
unit_path="/etc/systemd/system/${unit_name}.service"

echo "runner dir : ${runner_dir}"
echo "runner user: ${runner_user}"
echo "runner name: ${runner_name}"
echo "labels     : ${runner_labels}"
echo "repo       : ${repo_url}"
echo "unit       : ${unit_path}"
echo

# --- read-only preflight -----------------------------------------------------
if [[ -n "${token_file}" ]] && [[ ! -f "${token_file}" ]]; then
  echo "ERROR: token 文件不存在: ${token_file}" >&2
  exit 1
fi
if ! id -u "${runner_user}" > /dev/null 2>&1; then
  echo "ERROR: 用户不存在: ${runner_user}" >&2
  exit 1
fi
if [[ ! -f "${runner_tarball}" ]]; then
  echo "ERROR: runner 安装包不存在: ${runner_tarball}" >&2
  echo '请从 GitHub → Settings → Actions → Runners → New runner 下载，或用 --runner-tarball 指定。' >&2
  exit 1
fi

# --- directories -------------------------------------------------------------
run /usr/bin/install -d -o "${runner_user}" -g "${runner_user}" -m 0755 "${runner_dir}"
run /usr/bin/install -d -o "${runner_user}" -g "${runner_user}" -m 0755 "${ci_data_root}"
run /usr/bin/install -o root -g root -m 0666 /dev/null "${lock_file}"

# --- runner binaries ---------------------------------------------------------
if [[ -f "${runner_dir}/config.sh" ]]; then
  echo "runner 已解压，跳过: ${runner_dir}"
else
  run /usr/bin/tar -xzf "${runner_tarball}" -C "${runner_dir}"
  run /usr/bin/chown -R "${runner_user}:${runner_user}" "${runner_dir}"
fi

# --- registration ------------------------------------------------------------
if [[ -f "${runner_dir}/.runner" ]]; then
  echo "runner 已注册，跳过 config.sh: ${runner_dir}/.runner"
else
  if [[ -z "${token_file}" ]] && [[ "${dry_run}" -eq 0 ]]; then
    echo 'ERROR: 尚未注册，需要 --token-file' >&2
    exit 1
  fi
  if [[ "${dry_run}" -eq 1 ]]; then
    printf '[dry-run] runuser -u %s -- %s/config.sh --unattended --url %s --token <token> --name %s --labels %s --work _work --replace\n' \
      "${runner_user}" "${runner_dir}" "${repo_url}" "${runner_name}" "${runner_labels}"
  else
    token="$(/usr/bin/tr -d '[:space:]' < "${token_file}")"
    /usr/sbin/runuser -u "${runner_user}" -- /usr/bin/env HOME="${runner_home}" "${runner_dir}/config.sh" \
      --unattended \
      --url "${repo_url}" \
      --token "${token}" \
      --name "${runner_name}" \
      --labels "${runner_labels}" \
      --work '_work' \
      --replace || exit 1
  fi
fi

# --- systemd unit ------------------------------------------------------------
if [[ "${install_service}" -eq 0 ]]; then
  echo '跳过 systemd 服务安装（--skip-service）'
  exit 0
fi

unit_content="# 由 Vantage-Android scripts/ci/setup-runner.sh 生成
[Unit]
Description=GitHub Actions Runner (${repo_url##*/}, ${runner_name})
After=network-online.target

[Service]
ExecStart=${runner_dir}/runsvc.sh
User=${runner_user}
Group=${runner_user}
WorkingDirectory=${runner_dir}
Environment=HOME=${runner_home}
KillMode=process
KillSignal=SIGTERM
TimeoutStopSec=5min

# ---- 沙箱：白名单之外一律拒绝 ----
PrivateTmp=yes
NoNewPrivileges=yes
ProtectSystem=strict
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectKernelLogs=yes
ProtectControlGroups=yes
ProtectClock=yes
ProtectHostname=yes
RestrictSUIDSGID=yes
RestrictRealtime=yes
LockPersonality=yes
CapabilityBoundingSet=
AmbientCapabilities=
SystemCallArchitectures=native

# 抹掉真实家目录，只把白名单挂回来（路径不变）
TemporaryFileSystem=/home/chen

# --- 可写 ---
BindPaths=${runner_dir} /data/ci/vantage-android
# --- 只读 ---
BindReadOnlyPaths=/home/chen/git /home/chen/.vantage-android-keys

ReadWritePaths=${runner_dir} /run/lock /data/ci/vantage-android /home/${runner_user}

[Install]
WantedBy=multi-user.target
"

if [[ "${dry_run}" -eq 1 ]]; then
  echo "[dry-run] 将写入 ${unit_path}："
  printf '%s\n' "${unit_content}"
  echo "[dry-run] systemctl daemon-reload && systemctl enable --now ${unit_name}.service"
else
  printf '%s' "${unit_content}" > "${unit_path}"
  chmod 0644 "${unit_path}"
  systemctl daemon-reload
  systemctl enable --now "${unit_name}.service"
fi

echo
echo '完成。下一步：'
echo "  1) 迁移工作区: ./scripts/ci/move-workspace.sh --dry-run"
echo "  2) 给工作区补 ACL（move 脚本会自动处理）"
echo "  3) 在 Actions 页面手动触发 Android Build"
