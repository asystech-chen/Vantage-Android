# Offline builds

Vantage-Android separates dependency acquisition from compilation so that a
build never silently pulls from the network. The split is:

| Stage | Entry point | Network | Output |
|---|---|---|---|
| 1. Sources / toolchains | `make fetch` (`get_sources.sh all`) | required | `external/` |
| 2. Prebuild products / caches | `make prepare` (`prepare.sh` → `prebuild.sh <target>`) | required | `build/` caches, patched trees |
| 3. Compile | `make dev` / `make arm64` (`build.sh`, `IRONFOX_OFFLINE=1`) | **disabled** | APK |

The first full build is always online. After that, stage 3 resolves everything
from local caches.

## Usage

```bash
make check        # read-only preflight: are all caches present?
make fetch        # online: download sources etc.
make prepare      # online: run only missing prebuild targets
make prepare-dry  # show what prepare would run
make dev          # offline: fast arm64-v8a dev build
make arm64        # offline: arm64 release build
make verify       # run dev inside an isolated network namespace
```

`make verify` uses `unshare -rn`, enables loopback, and runs the build with no
other network interfaces. If it succeeds, no dependency resolution needed the
internet.

## The offline switch

`IRONFOX_OFFLINE=1` (set automatically by `make dev` / `make arm64` / `make verify`):

- appends `--offline` to `IRONFOX_GRADLE_FLAGS` (`scripts/env_common.sh`),
  which also reaches mach-driven Gradle via
  `GRADLE_FLAGS="${IRONFOX_GRADLE_FLAGS} ..."` in `core.mozconfig`
- exports `CARGO_NET_OFFLINE=true`, `UV_OFFLINE=1`, `PIP_NO_INDEX=1`,
  `npm_config_offline=true` (`scripts/build.sh`)
- unsets `http_proxy` / `https_proxy` / `all_proxy` so an accidental network
  attempt fails instead of succeeding through the proxy

Preparation stages are unaffected: `make fetch` and `make prepare` still use the
proxy and mirrors.

## Common failure mode

Offline builds are fail-fast by design. Adding a platform, bumping a dependency
or changing the task graph can produce:

```
No cached version of ... available for offline mode
```

This means the cache was populated by a different dependency graph. Warm it once
online, then return to offline:

```bash
./scripts/build.sh dev            # online, no IRONFOX_OFFLINE
make dev                          # offline again
```

## prepare.sh stamps

`prebuild.sh` is not idempotent: it deletes `build/finished-prebuild` on start
and re-applying patches fails on an already-patched tree. `prepare.sh` keeps a
stamp per target in `build/.state/prepared-<target>` containing a fingerprint of
`scripts/versions.sh` + `scripts/prebuild-if.sh`.

- matching stamp → target skipped
- version bump → fingerprint changes → all targets re-run
- failure → stamp removed, `finished-prebuild` not touched
- `make clean-stamps` or `./scripts/prepare.sh --force` forces a full re-run

## Caveats

- `build/.gradle/gradle.properties` keeps the proxy settings; `--offline` makes
  them unused. Do not delete it — online mode needs it.
- Cargo uses the repository-local `build/.cargo` registry cache; do not clear it
  before an offline build.
- `unshare -rn` requires unprivileged user namespaces. On hosts where they are
  disabled, `make verify` exits with a clear error; `IRONFOX_OFFLINE=1` builds
  still work, they just are not network-isolated.

## Warming Gradle caches

Gradle resolves plugin/classpath artifacts lazily, so a project can configure
fine offline today and then fail on a never-resolved artifact after a source or
plugin change. `scripts/warm-gradle.sh` resolves those projects once with
network access:

```bash
make warm           # or: ./scripts/warm-gradle.sh [--force]
```

It warms the Gecko root project, `ironfox/android` (`:core:tasks`), Glean and
microG, logs to `build/logs/warm-<name>.log`, and records a fingerprint in
`build/.state/warmed-gradle`. `make prepare` and `prebuild.sh all` call it
automatically (`IRONFOX_PREPARE_GRADLE_WARM=0` disables it).

Known artifacts that were missing from an offline cache before warming:
`org.checkerframework:checker-qual:2.5.8`,
`org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.2.10`,
`com.google.errorprone:error_prone_annotations:2.27.0`.
