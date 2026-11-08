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
    /usr/bin/cat "${IRONFOX_SCRIPTS}/gradle/resolve-all.init.gradle" \
      "${IRONFOX_SCRIPTS}/gradle/mirrors.init.gradle" 2> /dev/null
    /usr/bin/find "${IRONFOX_GECKO}" -maxdepth 6 -name libs.versions.toml \
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

# Make sure the mirror rewrite is active before any project is configured.
init_dir="${IRONFOX_GRADLE_HOME}/init.d"
/usr/bin/mkdir -p "${init_dir}" || exit 1
/bin/cp -f "${IRONFOX_SCRIPTS}/gradle/mirrors.init.gradle" "${init_dir}/vantage-mirrors.gradle" || exit 1

fail=0

# mozconfig 评估需要目标架构/项目变量；build.sh 会设置，预热阶段要自己给
# （缺失时 Gecko/IronFox Core/Android Components 三个项目都会在配置阶段失败）
export IRONFOX_MACH_TARGET_ARCH="${IRONFOX_MACH_TARGET_ARCH:-arm64}"
export IRONFOX_MACH_TARGET_PROJECT="${IRONFOX_MACH_TARGET_PROJECT:-gecko}"

warm() {
  local name="$1"
  local dir="$2"
  local java_home="$3"
  shift 3

  echo "==> warming ${name}"
  if ! (cd "${dir}" && /usr/bin/timeout --foreground --kill-after=10s 900 "${IRONFOX_GRADLE}" ${flags} \
    -Dorg.gradle.java.home="${java_home}" \
    -Dorg.gradle.java.installations.paths="${java_home}" "$@") \
    < /dev/null > "${log_dir}/warm-${name}.log" 2>&1; then
    echo "ERROR: warm-up failed: ${name} (see ${log_dir}/warm-${name}.log)" >&2
    fail=1
  fi
}

# Gecko root project: covers the android export / geckoview plugin classpaths.
warm 'gecko' "${IRONFOX_GECKO}" "${IRONFOX_JDK_17_HOME}" help
# IronFox Core (external/gecko/ironfox/android).
warm 'ironfox-core' "${IRONFOX_GECKO}" "${IRONFOX_JDK_17_HOME}" -p ironfox/android :core:tasks
# Android Components (the project that previously missed lifecycle-livedata).
warm 'android-components' "${IRONFOX_GECKO}" "${IRONFOX_JDK_17_HOME}" \
  -p mobile/android/android-components help
# UnifiedPush-AC.
warm 'up-ac' "${IRONFOX_UP_AC}" "${IRONFOX_JDK_17_HOME}" help
# Fenix is intentionally not warmed here: its buildscript needs the local
# glean-gradle-plugin, which the build itself publishes with a per-run
# timestamp before building Fenix.
# Glean's own Gradle build.
warm 'glean' "${IRONFOX_GLEAN}" "${IRONFOX_JDK_17_HOME}" help
# microG (gmscore).
warm 'microg' "${IRONFOX_GMSCORE}" "${IRONFOX_JDK_21_HOME}" help

# NOTE: resolving multi-module classpaths before the subproject artifacts exist
# (the old resolve-all stage) produced 240 false failures, so it is intentionally
# not part of warm-up. The actual build is the only reliable dependency proof.

if [[ "${fail}" -ne 0 ]]; then
  /usr/bin/rm -f "${stamp}"
  echo 'ERROR: one or more Gradle warm-ups failed.' >&2
  exit 1
fi

printf '%s\n' "${fp}" > "${stamp}"
echo "Gradle caches warmed (${fp:0:12})."
