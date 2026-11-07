#!/bin/bash
# Prewarm Gradle dependency caches used by build.sh.
#
# The offline build fails fast when a Gradle project needs an artifact that was
# never resolved before (e.g. checker-qual, error_prone_annotations). This
# script resolves/configure those projects once while the network is available,
# so later `make dev` / `make arm64` runs resolve entirely from cache.
#
# Online by design. Never passes --offline to Gradle.
#
# Usage:
#   ./scripts/warm-gradle.sh [--force]

set -uo pipefail

script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
# shellcheck source=env.sh
env_log="$(/usr/bin/mktemp)"
if ! source "${script_dir}/env.sh" > "${env_log}" 2>&1; then
  cat "${env_log}" >&2
  /usr/bin/rm -f "${env_log}"
  echo "ERROR: failed to source ${script_dir}/env.sh" >&2
  exit 1
fi
/usr/bin/rm -f "${env_log}"
set +e
set +u

force=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --force)
      force=1
      shift
      ;;
    *)
      echo "ERROR: unknown option: $1" >&2
      exit 2
      ;;
  esac
done

state_dir="${IRONFOX_BUILD}/.state"
log_dir="${IRONFOX_LOG_DIR:-${IRONFOX_BUILD}/logs}"
/usr/bin/mkdir -p "${state_dir}" "${log_dir}" || exit 1

fingerprint() {
  {
    /usr/bin/cat "${IRONFOX_SCRIPTS}/versions.sh"
    /usr/bin/find "${IRONFOX_EXTERNAL}" -maxdepth 5 -name gradle-wrapper.properties \
      -print -exec /usr/bin/cat {} \; 2> /dev/null
  } | /usr/bin/sha256sum | /usr/bin/awk '{print $1}'
}

fp="$(fingerprint)"
stamp="${state_dir}/warmed-gradle"
if [[ "${force}" -eq 0 ]] && [[ -f "${stamp}" ]] &&
  [[ "$(/usr/bin/cat "${stamp}" 2> /dev/null)" == "${fp}" ]]; then
  echo "Gradle caches already warmed (${fp:0:12}); use --force to redo."
  exit 0
fi

# The Gradle wrapper must be allowed to refresh its checksum cache. IRONFOX_OFFLINE
## is readonly in this shell, so only strip --offline from the Gradle flags below.
flags="${IRONFOX_GRADLE_FLAGS//--offline/}"

fail=0

warm() {
  local name="$1"
  local dir="$2"
  local java_home="$3"
  shift 3

  echo "==> warming ${name}"
  if ! (cd "${dir}" && /usr/bin/timeout 900 "${IRONFOX_GRADLE}" ${flags} \
    -Dorg.gradle.java.home="${java_home}" \
    -Dorg.gradle.java.installations.paths="${java_home}" "$@") \
    > "${log_dir}/warm-${name}.log" 2>&1; then
    echo "ERROR: warm-up failed: ${name} (see ${log_dir}/warm-${name}.log)" >&2
    fail=1
  fi
}

# Gecko root project: covers the android export / geckoview plugin classpaths.
warm 'gecko' "${IRONFOX_GECKO}" "${IRONFOX_JDK_17_HOME}" help
# IronFox Core (external/gecko/ironfox/android).
warm 'ironfox-core' "${IRONFOX_GECKO}" "${IRONFOX_JDK_17_HOME}" -p ironfox/android :core:tasks
# Glean's own Gradle build.
warm 'glean' "${IRONFOX_GLEAN}" "${IRONFOX_JDK_17_HOME}" help
# microG (gmscore).
warm 'microg' "${IRONFOX_GMSCORE}" "${IRONFOX_JDK_21_HOME}" help

if [[ "${fail}" -ne 0 ]]; then
  /usr/bin/rm -f "${stamp}"
  echo 'ERROR: one or more Gradle warm-ups failed.' >&2
  exit 1
fi

printf '%s\n' "${fp}" > "${stamp}"
echo "Gradle caches warmed (${fp:0:12})."
