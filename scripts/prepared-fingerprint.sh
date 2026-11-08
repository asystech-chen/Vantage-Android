#!/bin/bash
# Print the fingerprint of everything that makes a prepared tree valid.
#
# Used by prepare-build.sh (write stamp) and build.sh (enforce stamp).

set -uo pipefail

script_dir="$(cd -- "$(/usr/bin/dirname -- "$0")" && pwd)"
env_log="$(/usr/bin/mktemp)"
if ! source "${script_dir}/env.sh" > "${env_log}" 2>&1; then
  echo "ERROR: failed to source ${script_dir}/env.sh" >&2
  /bin/cat "${env_log}" >&2 || true
  /usr/bin/rm -f "${env_log}"
  exit 1
fi
/usr/bin/rm -f "${env_log}"
set +e
set +u

if [[ -x /usr/bin/sha256sum ]]; then
  hasher=(/usr/bin/sha256sum)
elif [[ -x "${IRONFOX_SHASUM}" ]]; then
  hasher=("${IRONFOX_SHASUM}" -a 256)
else
  echo "ERROR: no SHA-256 implementation available" >&2
  exit 1
fi

{
  /usr/bin/cat "${IRONFOX_SCRIPTS}/versions.sh"
  /usr/bin/cat "${IRONFOX_SCRIPTS}/prebuild-if.sh"
  /usr/bin/find "${IRONFOX_EXTERNAL}" -maxdepth 5 -name gradle-wrapper.properties \
    -print -exec /usr/bin/cat {} \; 2> /dev/null
  /usr/bin/find "${IRONFOX_GECKO}" -maxdepth 6 -name libs.versions.toml \
    -print -exec /usr/bin/cat {} \; 2> /dev/null
  /usr/bin/cat "${IRONFOX_SCRIPTS}/gradle/resolve-all.init.gradle" 2> /dev/null
  /usr/bin/cat "${IRONFOX_SCRIPTS}/gradle/mirrors.init.gradle" 2> /dev/null
  /usr/bin/cat "${IRONFOX_SCRIPTS}/prepare-build.sh" 2> /dev/null
  /usr/bin/cat "${IRONFOX_SCRIPTS}/warm-gradle.sh" 2> /dev/null
} | "${hasher[@]}" | /usr/bin/awk '{print $1}'
