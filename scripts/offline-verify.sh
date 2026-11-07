#!/bin/bash
# Prove that a build really works without network access.
#
# Runs build.sh inside a new network namespace (no interfaces except a
# newly-enabled loopback). This catches any dependency resolution that would
# otherwise silently reach the internet.
#
# Usage:
#   ./scripts/offline-verify.sh [dev|arm64|arm|arm64-nolto|x86_64|bundle]

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
root_dir="$(cd -- "${script_dir}/.." && pwd)"

target="${1:-dev}"

if ! unshare -rn true 2> /dev/null; then
  echo "ERROR: unprivileged network namespaces are unavailable on this host." >&2
  echo "       Run the build normally with IRONFOX_OFFLINE=1 instead, or enable user namespaces." >&2
  exit 1
fi

echo "Offline verification: target=${target}, network namespace = isolated"
export IRONFOX_OFFLINE=1

cd "${root_dir}"
exec unshare -rn -- bash -c 'ip link set lo up; exec "$@"' bash ./scripts/build.sh "${target}"
