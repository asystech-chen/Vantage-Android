#!/bin/bash
# Offline readiness preflight for Vantage-Android.
#
# Read-only: verifies that every local cache needed by an offline build exists.
# Never touches the network and never starts a build.
#
# Usage:
#   ./scripts/check-offline.sh
#
# Exit codes: 0 = ready for offline build, 1 = missing prerequisites.

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

# env.sh sets -euo pipefail; loosen it so every check runs and reports.
set +e
set +u

fail=0
checked=0

ok() {
  printf '  [ OK ] %s\n' "$*"
}

miss() {
  printf '  [MISS] %s\n' "$*"
  fail=$((fail + 1))
}

need_dir() {
  checked=$((checked + 1))
  if [[ -d "${2}" ]]; then ok "${1}"; else miss "${1} -> ${2}"; fi
}

need_file() {
  checked=$((checked + 1))
  if [[ -f "${2}" ]]; then ok "${1}"; else miss "${1} -> ${2}"; fi
}

echo 'Vantage-Android offline readiness check'
echo

echo '== Source trees / toolchains (external/) =='
need_dir 'Gecko source' "${IRONFOX_GECKO}"
need_dir 'Android Components' "${IRONFOX_AC}"
need_dir 'Fenix' "${IRONFOX_FENIX}"
need_dir 'Glean' "${IRONFOX_GLEAN}"
need_dir 'UniFFI' "${IRONFOX_UNIFFI_DIR}"
need_dir 'WASI SDK' "${IRONFOX_WASI}"
need_dir 'Phoenix' "${IRONFOX_PHOENIX}"
need_dir 'microG (gmscore)' "${IRONFOX_GMSCORE}"
need_dir 'Android NDK' "${IRONFOX_ANDROID_NDK}"
need_dir 'Android SDK' "${IRONFOX_ANDROID_SDK}"
need_dir 'Bundletool' "${IRONFOX_BUNDLETOOL_DIR}"
need_dir 'JDK 21' "${IRONFOX_JDK_21_HOME}"
need_dir 'JDK 25' "${IRONFOX_JAVA_HOME}"
need_dir 'l10n-central' "${IRONFOX_L10N_CENTRAL}"
need_dir 'uv' "${IRONFOX_UV_LOCAL}"
need_dir 'pip' "${IRONFOX_PIP_DIR}"

echo
echo '== Toolchain shims =='
need_dir 'rustup stable toolchain (symlink)' "${IRONFOX_BUILD}/.rustup/toolchains/stable-x86_64-unknown-linux-gnu"
need_file 'rustup 1.99.1 rustc' "${IRONFOX_BUILD}/.rustup/toolchains/1.99.1-x86_64-unknown-linux-gnu/bin/rustc"
need_dir 'Gradle distributions' "${IRONFOX_GRADLE_CACHE}/versions"

echo
echo '== Dependency caches =='
need_dir 'Cargo registry index' "${IRONFOX_BUILD}/.cargo/registry/index"
need_dir 'Cargo registry cache' "${IRONFOX_BUILD}/.cargo/registry/cache"
need_dir 'Gradle module cache' "${IRONFOX_GRADLE_HOME}/caches/modules-2"
need_dir 'Maven local repository' "${IRONFOX_MAVEN_LOCAL}"
need_dir 'uv Python cache' "${IRONFOX_UV_LOCAL}/python-cache"
if [[ -d "${IRONFOX_UV_CACHE}" ]]; then need_dir 'uv package cache' "${IRONFOX_UV_CACHE}"; fi
need_dir 'mach state (.mozbuild)' "${IRONFOX_BUILD}/.mozbuild"

echo
echo '== Prebuild stamp =='
need_file 'build/finished-prebuild' "${IRONFOX_BUILD}/finished-prebuild"

echo
if [[ "${fail}" -eq 0 ]]; then
  echo "RESULT: ready (${checked} checks passed) - offline build should resolve entirely from local caches."
  exit 0
fi

echo "RESULT: ${fail}/${checked} check(s) failed."
echo 'Run `make prepare` (or `make fetch`) with network access to fill the gaps.'
exit 1
