#!/bin/bash
# List or delete old Vantage-Android build artifacts.
#
# Default (no arguments) is read-only: print a table of artifacts and mark
# each one KEEP / OLD / CLEAN. Nothing is deleted unless --del/--delete (or
# --interactive) is given.
#
# Usage:
#   ./scripts/artifacts.sh
#   ./scripts/artifacts.sh --del
#   ./scripts/artifacts.sh --del --only gradle,tmp
#   ./scripts/artifacts.sh --del --only objdirs,outputs
#   ./scripts/artifacts.sh --older-than 30d --del
#   ./scripts/artifacts.sh --interactive
#
# Never touches source trees, keystores, release keys, or ~/.cache/ccache.

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

mode=list
only=""
older_days=0
interactive=0

usage() {
  /usr/bin/sed -n '2,20p' "$0" | /usr/bin/sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --del | --delete)
      mode=delete
      shift
      ;;
    --interactive)
      mode=delete
      interactive=1
      shift
      ;;
    --list)
      mode=list
      shift
      ;;
    --only)
      only="${2:-}"
      shift 2
      ;;
    --only=*)
      only="${1#*=}"
      shift
      ;;
    --older-than)
      older_days="${2:-0}"
      older_days="${older_days%d}"
      shift 2
      ;;
    --older-than=*)
      older_days="${1#*=}"
      older_days="${older_days%d}"
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

if [[ "${older_days}" != "0" ]] && ! [[ "${older_days}" =~ ^[0-9]+$ ]]; then
  echo "ERROR: --older-than expects a number of days (for example 30d)" >&2
  exit 2
fi

declare -a L_STATUS=() L_TYPE=() L_PATH=() L_SIZE=() L_TIME=()
declare -a D_PATH=() D_TYPE=() D_SIZE=()

want_type() {
  local t="$1"
  if [[ -z "${only}" ]]; then
    case "${t}" in
      m2 | downloads | backups) return 1 ;;
      *) return 0 ;;
    esac
  fi
  case ",${only}," in
    *",${t},"*) return 0 ;;
    *) return 1 ;;
  esac
}

path_size() { /usr/bin/du -sh -- "$1" 2> /dev/null | /usr/bin/awk '{print $1}'; }
path_time() { /bin/date -d "@$(/usr/bin/stat -c %Y -- "$1" 2> /dev/null)" +'%Y-%m-%d %H:%M' 2> /dev/null; }

add_row() {
  local status="$1" type="$2" p="$3"
  local size time
  size="$(path_size "${p}")"
  time="$(path_time "${p}")"
  L_STATUS+=("${status}")
  L_TYPE+=("${type}")
  L_PATH+=("${p}")
  L_SIZE+=("${size}")
  L_TIME+=("${time}")
  [[ "${status}" == "OLD" || "${status}" == "CLEAN" ]] || return 0
  want_type "${type}" || return 0
  if [[ "${older_days}" -gt 0 ]]; then
    [[ -n "$(/usr/bin/find "${p}" -maxdepth 0 -mtime +"${older_days}" -print -quit 2> /dev/null)" ]] || return 0
  fi
  D_PATH+=("${p}")
  D_TYPE+=("${type}")
  D_SIZE+=("${size}")
}

# --- objdirs -----------------------------------------------------------------
for d in "${IRONFOX_GECKO}"/obj/ironfox-*; do
  [[ -d "${d}" ]] || continue
  b="$(basename "${d}")"
  if [[ "${b}" =~ ^ironfox-${IRONFOX_CHANNEL}-(arm|arm64|arm64-nolto|x86_64)(-dev)?$ ]]; then
    add_row KEEP objdirs "${d}"
  else
    add_row OLD objdirs "${d}"
  fi
done

# --- packaged outputs --------------------------------------------------------
if [[ -d "${IRONFOX_OUTPUTS}" ]]; then
  while IFS= read -r f; do
    [[ -n "${f}" ]] || continue
    b="$(basename "${f}")"
    if [[ "${b}" == *"${IRONFOX_VERSION}"* ]]; then
      add_row KEEP outputs "${f}"
    else
      add_row OLD outputs "${f}"
    fi
  done < <(/usr/bin/find "${IRONFOX_OUTPUTS}" -type f \( -name '*.apk' -o -name '*.aab' -o -name '*.zip' \) 2> /dev/null)
fi

# --- Gradle distributions ----------------------------------------------------
if [[ -d "${IRONFOX_GRADLE_CACHE}/versions" ]]; then
  current_gradle="$(/bin/ls -1 "${IRONFOX_GRADLE_CACHE}/versions" 2> /dev/null | /usr/bin/sort -V | /usr/bin/tail -n 1)"
  for d in "${IRONFOX_GRADLE_CACHE}/versions"/*; do
    [[ -d "${d}" ]] || continue
    if [[ "$(basename "${d}")" == "${current_gradle}" ]]; then
      add_row KEEP gradle "${d}"
    else
      add_row OLD gradle "${d}"
    fi
  done
fi

# --- old local Maven versions (only with --only m2) --------------------------
if want_type m2 && [[ -d "${IRONFOX_MAVEN_LOCAL}/org/mozilla" ]]; then
  for art in "${IRONFOX_MAVEN_LOCAL}/org/mozilla"/*/*; do
    [[ -d "${art}" ]] || continue
    newest="$(/bin/ls -1 "${art}" 2> /dev/null | /usr/bin/sort -V | /usr/bin/tail -n 1)"
    for v in "${art}"/*; do
      [[ -d "${v}" ]] || continue
      if [[ "$(basename "${v}")" == "${newest}" ]]; then
        add_row KEEP m2 "${v}"
      else
        add_row OLD m2 "${v}"
      fi
    done
  done
fi

# --- temp / logs -------------------------------------------------------------
for d in "${IRONFOX_TEMP}"/*; do
  [[ -e "${d}" ]] || continue
  add_row CLEAN tmp "${d}"
done

while IFS= read -r f; do
  [[ -n "${f}" ]] || continue
  add_row CLEAN logs "${f}"
done < <(/usr/bin/find "${IRONFOX_LOG_DIR}" -maxdepth 1 -type f -mtime +30 2> /dev/null)

# --- old downloads / backups (only on explicit request) ----------------------
if want_type downloads && [[ -d "${IRONFOX_DOWNLOADS}" ]]; then
  while IFS= read -r f; do
    [[ -n "${f}" ]] || continue
    add_row OLD downloads "${f}"
  done < <(/usr/bin/find "${IRONFOX_DOWNLOADS}" -maxdepth 1 -type f -mtime +90 2> /dev/null)
fi

if want_type backups; then
  for d in "${IRONFOX_ROOT}"/external.*-backup "${IRONFOX_ROOT}"/env_override.sh.*-backup; do
    [[ -e "${d}" ]] || continue
    add_row OLD backups "${d}"
  done
fi

# --- report ------------------------------------------------------------------
if [[ "${#L_PATH[@]}" -eq 0 ]]; then
  echo "No artifacts found."
  exit 0
fi

printf '%-6s %-11s %9s %-16s %s\n' STATUS TYPE SIZE MTIME PATH
for i in "${!L_PATH[@]}"; do
  printf '%-6s %-11s %9s %-16s %s\n' \
    "${L_STATUS[$i]}" "${L_TYPE[$i]}" "${L_SIZE[$i]}" "${L_TIME[$i]}" "${L_PATH[$i]}"
done

echo
if [[ "${mode}" != "delete" ]]; then
  echo "Delete candidates: ${#D_PATH[@]}"
  echo "Run with --del (or --delete) to remove them; add --only <types> to narrow."
  exit 0
fi

if [[ "${#D_PATH[@]}" -eq 0 ]]; then
  echo "Nothing to delete."
  exit 0
fi

# --- safety ------------------------------------------------------------------
if /usr/bin/pgrep -f '[m]ach build' > /dev/null 2>&1 ||
  /usr/bin/pgrep -f '[n]inja' > /dev/null 2>&1 ||
  /usr/bin/pgrep -f '[R]unner[.]Worker' > /dev/null 2>&1 ||
  /usr/bin/pgrep -f '[s]cripts/build.sh' > /dev/null 2>&1; then
  echo "ERROR: a build is running; refusing to delete artifacts." >&2
  exit 1
fi

safe_path() {
  local p="$1"
  case "${p}" in
    "${IRONFOX_ROOT}"/*) ;;
    *)
      echo "SKIP (outside root): ${p}" >&2
      return 1
      ;;
  esac
  case "${p}" in
    "${IRONFOX_ROOT}" | "${IRONFOX_ROOT}/external" | "${IRONFOX_ROOT}/keystores" | \
      "${IRONFOX_ROOT}/scripts" | "${IRONFOX_ROOT}/configs" | "${IRONFOX_ROOT}/outputs")
      echo "SKIP (protected): ${p}" >&2
      return 1
      ;;
  esac
  return 0
}

echo "Before: $(/usr/bin/df -h / | /usr/bin/tail -n 1)"
for i in "${!D_PATH[@]}"; do
  p="${D_PATH[$i]}"
  safe_path "${p}" || continue
  if [[ "${interactive}" -eq 1 ]]; then
    printf 'Delete %s? [y/N] ' "${p}"
    read -r answer
    [[ "${answer}" == "y" || "${answer}" == "Y" ]] || continue
  fi
  echo "delete: ${p}"
  /bin/rm -rf --one-file-system -- "${p}"
done
echo "After:  $(/usr/bin/df -h / | /usr/bin/tail -n 1)"
