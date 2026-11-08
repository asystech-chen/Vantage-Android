#!/bin/bash
# Idempotent wrapper around prebuild.sh.
#
# prebuild.sh is not idempotent and deletes build/finished-prebuild on every
# invocation, so a failed run cannot simply be restarted. This wrapper keeps a
# per-target stamp (fingerprinted by scripts/versions.sh + prebuild-if.sh) and
# only runs targets whose stamp is missing or stale.
#
# Usage:
#   ./scripts/prepare.sh [--dry-run] [--force] [target ...]
#
# Targets (default: all of them, in build order):
#   firefox ac android-sdk as bundletool fenix glean llvm microg rust uniffi wasi

set -uo pipefail

script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
# shellcheck source=env.sh
env_log="$(mktemp)"
if ! source "${script_dir}/env.sh" > "${env_log}" 2>&1; then
  cat "${env_log}" >&2
  rm -f "${env_log}"
  echo "ERROR: failed to source ${script_dir}/env.sh" >&2
  exit 1
fi
rm -f "${env_log}"
set +e
set +u

dry_run=0
force=0
targets=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      dry_run=1
      shift
      ;;
    --force)
      force=1
      shift
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "ERROR: unknown option: $1" >&2
      exit 2
      ;;
    *)
      targets+=("$1")
      shift
      ;;
  esac
done

if [[ ${#targets[@]} -eq 0 ]]; then
  targets=(firefox ac android-sdk as bundletool fenix glean llvm microg rust uniffi wasi)
fi

valid_targets=(firefox ac android-sdk as bundletool fenix glean llvm microg rust uniffi wasi)
for t in "${targets[@]}"; do
  found=0
  for v in "${valid_targets[@]}"; do
    if [[ "$t" == "$v" ]]; then found=1; fi
  done
  if [[ "$found" != 1 ]]; then
    echo "ERROR: invalid prebuild target: $t" >&2
    echo "Valid targets: ${valid_targets[*]}" >&2
    exit 2
  fi
done

sha256() {
  if [[ -x /usr/bin/sha256sum ]]; then
    /usr/bin/sha256sum | awk '{print $1}'
  elif [[ -x /usr/bin/shasum ]]; then
    /usr/bin/shasum -a 256 | awk '{print $1}'
  else
    return 1
  fi
}

state_dir="${IRONFOX_BUILD}/.state"
fingerprint_size=$(wc -c < "${IRONFOX_SCRIPTS}/versions.sh" 2> /dev/null)
if [[ -z "$fingerprint_size" ]] || [[ "$fingerprint_size" -eq 0 ]]; then
  echo "ERROR: cannot read ${IRONFOX_SCRIPTS}/versions.sh" >&2
  exit 1
fi
fingerprint=$(cat "${IRONFOX_SCRIPTS}/versions.sh" "${IRONFOX_SCRIPTS}/prebuild-if.sh" | sha256)
if [[ -z "$fingerprint" ]]; then
  echo "ERROR: no SHA-256 implementation available (sha256sum/shasum)" >&2
  exit 1
fi

echo "Vantage-Android prepare"
echo "  root:        ${IRONFOX_ROOT}"
echo "  fingerprint: ${fingerprint}"
echo "  state dir:   ${state_dir}"
echo

created_state=0
if [[ "${dry_run}" -eq 0 ]]; then
  mkdir -p "${state_dir}" || exit 1
  created_state=1
fi
: "${created_state}"

ran=0
skipped=0

for t in "${targets[@]}"; do
  stamp="${state_dir}/prepared-${t}"
  stamp_ok=0
  if [[ "${force}" -eq 0 ]] && [[ -f "${stamp}" ]]; then
    if [[ "$(cat "${stamp}" 2> /dev/null)" == "${fingerprint}" ]]; then
      stamp_ok=1
    fi
  fi

  if [[ "${stamp_ok}" -eq 1 ]]; then
    echo "skip: ${t} (already prepared)"
    skipped=$((skipped + 1))
    continue
  fi

  ran=$((ran + 1))
  if [[ "${dry_run}" -eq 1 ]]; then
    echo "would run: PHOENIX_ASSUME_YES=1 ./scripts/prebuild.sh ${t}"
    continue
  fi

  echo "==> prebuild: ${t}"
  if ! (cd "${IRONFOX_ROOT}" && PHOENIX_ASSUME_YES=1 /usr/bin/env -u IRONFOX_SET_ENVS -u IRONFOX_CI -u CI ./scripts/prebuild.sh "${t}"); then
    rm -f "${stamp}"
    echo "ERROR: prebuild target failed: ${t}" >&2
    echo "Fix the error, then re-run: ./scripts/prepare.sh ${t}" >&2
    exit 1
  fi
  printf '%s\n' "${fingerprint}" > "${stamp}"
done

if [[ "${dry_run}" -eq 0 ]]; then
  touch "${IRONFOX_BUILD}/finished-prebuild"
fi

if [[ "${dry_run}" -eq 0 ]]; then
  echo
  echo '==> warming Gradle caches'
  /usr/bin/env -u IRONFOX_SET_ENVS -u IRONFOX_CI -u CI "${IRONFOX_SCRIPTS}/warm-gradle.sh" || exit 1
fi

echo
if [[ "${dry_run}" -eq 1 ]]; then
  echo "DRY RUN: would run ${ran} target(s), skip ${skipped}."
  exit 0
fi

echo "Done: ran ${ran} target(s), skipped ${skipped}. build/finished-prebuild updated."
