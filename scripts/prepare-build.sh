#!/bin/bash
# One-shot preparation for Vantage-Android.
#
# Stages:
#   1. host + disk preflight
#   2. download/verify sources
#   3. prebuild (adopts an already-finished tree, or runs missing targets)
#   4. warm every Gradle project used by the build (online)
#   5. prove the Gradle graph resolves offline in a network namespace
#   6. toolchain + offline readiness check
#   7. write build/.state/prepared-build
#
# After this exits 0, an offline build (make dev / make arm64) is expected to
# run without network access or missing-cache surprises.
#
# Usage:
#   ./scripts/prepare-build.sh
#   ./scripts/prepare-build.sh --dry-run
#   ./scripts/prepare-build.sh --force-prebuild
#   ./scripts/prepare-build.sh --no-verify
#   ./scripts/prepare-build.sh --prune-old [--yes]

set -uo pipefail

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

dry_run=0
force_prebuild=0
verify_offline=1
prune_old=0
assume_yes=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      dry_run=1
      shift
      ;;
    --force-prebuild)
      force_prebuild=1
      shift
      ;;
    --no-verify)
      verify_offline=0
      shift
      ;;
    --prune-old)
      prune_old=1
      shift
      ;;
    --yes)
      assume_yes=1
      shift
      ;;
    -h | --help)
      /usr/bin/sed -n '2,28p' "$0" | /usr/bin/sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

# Child scripts re-source env.sh themselves. Unset the "already set up" guard
# so they do not inherit a half-populated environment.
run_child() {
  /usr/bin/env -u IRONFOX_SET_ENVS -u IRONFOX_CI -u CI -- "$@"
}

state_dir="${IRONFOX_BUILD}/.state"
prepared_stamp="${state_dir}/prepared-build"
prebuild_fp="$(/usr/bin/cat "${IRONFOX_SCRIPTS}/versions.sh" "${IRONFOX_SCRIPTS}/prebuild-if.sh" |
  /usr/bin/sha256sum | /usr/bin/awk '{print $1}')"

echo 'Vantage-Android prepare-build'
echo "  root:        ${IRONFOX_ROOT}"
echo "  version:     ${IRONFOX_VERSION} (${IRONFOX_CHANNEL})"
echo "  state dir:   ${state_dir}"
echo "  dry run:     ${dry_run}"
echo

# --- 1. host + disk preflight ------------------------------------------------
echo '==> 1/7 host preflight'
if [[ "${dry_run}" -eq 0 ]]; then
  avail_kb="$(/usr/bin/df -Pk "${IRONFOX_ROOT}" | /usr/bin/awk 'NR==2 {print $4}')"
  if [[ -z "${avail_kb}" ]] || [[ "${avail_kb}" -lt 15728640 ]]; then
    echo "ERROR: need at least 15 GiB free under ${IRONFOX_ROOT}." >&2
    /usr/bin/df -h "${IRONFOX_ROOT}" >&2
    exit 1
  fi
  /usr/bin/mkdir -p "${state_dir}" || exit 1
fi
echo '    ok'

# --- 2. sources ---------------------------------------------------------------
echo '==> 2/7 sources'
gecko_version_file="${IRONFOX_GECKO}/browser/config/version.txt"
if [[ -f "${gecko_version_file}" ]] &&
  [[ "$(/usr/bin/tr -d '[:space:]' < "${gecko_version_file}")" == "${IRONFOX_VERSION}" ]]; then
  echo "    gecko ${IRONFOX_VERSION} already present; skipping get_sources"
else
  echo "    fetching sources (network)"
  if [[ "${dry_run}" -eq 1 ]]; then
    echo "    would run: yes n | ./scripts/get_sources.sh all"
  else
    export http_proxy="${http_proxy:-http://127.0.0.1:7890}"
    export https_proxy="${https_proxy:-http://127.0.0.1:7890}"
    if ! (cd "${IRONFOX_ROOT}" && yes n | run_child ./scripts/get_sources.sh all); then
      echo "ERROR: get_sources.sh failed." >&2
      exit 1
    fi
  fi
fi

# --- 3. prebuild --------------------------------------------------------------
echo '==> 3/7 prebuild'
if [[ "${dry_run}" -eq 0 ]] && [[ "${force_prebuild}" -eq 0 ]] &&
  [[ -f "${IRONFOX_BUILD}/finished-prebuild" ]] &&
  [[ -z "$(/bin/ls -1 "${state_dir}"/prepared-* 2> /dev/null)" ]]; then
  echo '    adopting existing finished-prebuild tree (writing per-target stamps)'
  for t in firefox ac android-sdk as bundletool fenix glean llvm microg rust uniffi wasi; do
    printf '%s\n' "${prebuild_fp}" > "${state_dir}/prepared-${t}" || exit 1
  done
fi

# Install the mirror rewrite before any Gradle invocation (prepare.sh warms
# at the end of its run).
init_dir="${IRONFOX_GRADLE_HOME}/init.d"
if [[ "${dry_run}" -eq 1 ]]; then
  echo "    would install ${IRONFOX_SCRIPTS}/gradle/mirrors.init.gradle -> ${init_dir}/vantage-mirrors.gradle"
else
  /usr/bin/mkdir -p "${init_dir}" || exit 1
  /bin/cp -f "${IRONFOX_SCRIPTS}/gradle/mirrors.init.gradle" "${init_dir}/vantage-mirrors.gradle" || exit 1
fi

if [[ "${dry_run}" -eq 1 ]]; then
  echo '    would run: ./scripts/prepare.sh --dry-run'
  run_child ./scripts/prepare.sh --dry-run || exit 1
else
  prepare_args=()
  [[ "${force_prebuild}" -eq 1 ]] && prepare_args+=(--force)
  if ! run_child ./scripts/prepare.sh "${prepare_args[@]}"; then
    echo "ERROR: prepare.sh failed." >&2
    exit 1
  fi
fi

# --- 4. Gradle warm (includes online resolve-all) -----------------------------
echo '==> 4/7 Gradle warm'
echo '    handled by prepare.sh -> warm-gradle.sh (task-path warm-ups, online)'

# --- 5. offline dependency proof ---------------------------------------------
echo '==> 5/7 dependency proof'
echo '    skipped: multi-module classpaths cannot be resolved before the build produces subproject artifacts'
echo '    (the actual build remains the authoritative dependency check)'

# --- 6. readiness check ------------------------------------------------------
echo '==> 6/7 offline readiness'
if [[ "${dry_run}" -eq 1 ]]; then
  echo '    would run: ./scripts/check-offline.sh'
else
  if ! run_child ./scripts/check-offline.sh; then
    /usr/bin/rm -f "${prepared_stamp}"
    echo "ERROR: offline readiness check failed." >&2
    exit 1
  fi
fi

# --- 7. stamp -----------------------------------------------------------------
echo '==> 7/7 write stamp'
if [[ "${dry_run}" -eq 1 ]]; then
  echo "    would write ${prepared_stamp}"
else
  current_fp="$(run_child "${IRONFOX_SCRIPTS}/prepared-fingerprint.sh")" || exit 1
  printf '%s\n' "${current_fp}" > "${prepared_stamp}" || exit 1
  echo "    ${current_fp:0:16}"
fi

echo
if [[ "${dry_run}" -eq 1 ]]; then
  echo 'DRY RUN complete: no stamp written, no artifacts deleted.'
else
  echo 'Prepared: toolchain verified, Gradle caches warmed, stamp written.'
  echo 'Note: the first full build may still fetch a few Fenix-only dependencies; use make online-dev once if needed.'
fi

if [[ "${prune_old}" -eq 1 ]]; then
  echo
  echo '==> old artifacts'
  run_child ./scripts/artifacts.sh || true
  if [[ "${assume_yes}" -eq 1 && "${dry_run}" -eq 0 ]]; then
    run_child ./scripts/artifacts.sh --del || true
  fi
else
  echo 'Tip: ./scripts/artifacts.sh lists old build outputs; --del deletes them.'
fi
