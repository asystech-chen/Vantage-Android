# Vantage-Android unified build entry point.
#
# Three stages:
#   fetch    -> download source trees / toolchains        (network required)
#   prepare  -> run prebuild targets and warm caches      (network required)
#   dev/arm64-> compile against local caches only         (offline, enforced)
#
# Prefer these targets over calling the individual scripts by hand.

SHELL := /bin/bash

.PHONY: help check fetch prepare prepare-dry warm dev arm64 online-dev verify clean-stamps

help:
	@echo 'Vantage-Android targets:'
	@echo '  make check        Verify offline readiness (read-only, no network)'
	@echo '  make fetch        Download sources/toolchains via get_sources.sh'
	@echo '  make prepare      Run missing prebuild targets (idempotent wrapper)'
	@echo '  make prepare-dry  Show which prebuild targets would run'
	@echo '  make warm         Prewarm Gradle caches (online; included in prepare)'
	@echo '  make dev          Offline build: dev (fast, arm64-v8a, no LTO)'
	@echo '  make arm64        Offline build: arm64 release profile'
	@echo '  make online-dev   Build dev without offline restrictions'
	@echo '  make verify       Prove dev builds inside an isolated network namespace'
	@echo '  make clean-stamps Remove prepare.sh stamps (force full re-prepare)'

check:
	./scripts/check-offline.sh

fetch:
	./scripts/get_sources.sh all

prepare:
	./scripts/prepare.sh

prepare-dry:
	./scripts/prepare.sh --dry-run

warm:
	./scripts/warm-gradle.sh

dev:
	IRONFOX_OFFLINE=1 ./scripts/build.sh dev

arm64:
	IRONFOX_OFFLINE=1 ./scripts/build.sh arm64

online-dev:
	./scripts/build.sh dev

verify:
	./scripts/offline-verify.sh dev

clean-stamps:
	rm -f build/.state/prepared-*
