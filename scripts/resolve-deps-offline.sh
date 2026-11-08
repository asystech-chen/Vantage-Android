#!/bin/bash
# Prove that the Gradle dependency graph resolves with no network access.
#
# Runs resolve-all.init.gradle inside a fresh network namespace (loopback
# only) with --offline. Build-essential configurations are resolved strictly;
# test/lint/debug/benchmark configurations are skipped by the init script.
#
# Usage: ./scripts/resolve-deps-offline.sh

set -uo pipefail

IRONFOX_OFFLINE=1
export IRONFOX_OFFLINE

script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
env_log="$(/usr/bin/mktemp)"
if ! source "${script_dir}/env.sh" > "${env_log}" 2>&1; then
  /usr/bin/cat "${env_log}" >&2
  /usr/bin/rm -f "${env_log}"
  echo "ERROR: failed to source ${script_dir}/env.sh" >&2
  exit 1
fi
/usr/bin/rm -f "${env_log}"
set +e
set +u

if ! /usr/bin/unshare -rn true 2> /dev/null; then
  echo "WARN: unprivileged network namespaces are unavailable; skipping offline resolve proof." >&2
  exit 0
fi

init="${IRONFOX_SCRIPTS}/gradle/resolve-all.init.gradle"
log_dir="${IRONFOX_LOG_DIR}/resolve-offline"
/usr/bin/mkdir -p "${log_dir}" || exit 1

fail=0

run_one() {
  local name="$1" dir="$2" project="$3"
  local log="${log_dir}/${name}.log"
  local -a gradle_flags=()
  read -r -a gradle_flags <<< "${IRONFOX_GRADLE_FLAGS}"
  local -a cmd=("${IRONFOX_GRADLE}" "${gradle_flags[@]}" -I "${init}")
  if [[ -n "${project}" ]]; then
    cmd+=(-p "${project}")
  fi
  cmd+=(help)
  echo "==> offline resolve: ${name}"
  if ! /usr/bin/timeout --foreground --kill-after=10s 1800 /usr/bin/unshare -rn -- /bin/bash -c '
    ip link set lo up 2> /dev/null || true
    cd "$1" || exit 1
    shift
    exec "$@"
  ' bash "${dir}" "${cmd[@]}" < /dev/null > "${log}" 2>&1; then
    echo "ERROR: offline resolve failed: ${name} (see ${log})" >&2
    fail=1
  fi
}

run_one 'gecko' "${IRONFOX_GECKO}" ''
run_one 'android-components' "${IRONFOX_GECKO}" 'mobile/android/android-components'
# Fenix is intentionally skipped: it needs the locally published Glean plugin,
# which only exists after the build publishes it.
run_one 'ironfox-core' "${IRONFOX_GECKO}" 'ironfox/android'
run_one 'up-ac' "${IRONFOX_UP_AC}" ''
run_one 'glean' "${IRONFOX_GLEAN}" ''
run_one 'microg' "${IRONFOX_GMSCORE}" ''

if [[ "${fail}" -ne 0 ]]; then
  echo "ERROR: offline dependency proof failed. Run make warm / make online-dev to fill the cache." >&2
  exit 1
fi

echo "Offline dependency proof passed."
