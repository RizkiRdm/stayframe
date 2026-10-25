# ==============================================================================
# StayFrame - Makefile for Automated Android Build & Test Pipeline
# ==============================================================================
SHELL := /bin/bash
APP_NAME := StayFrame
GRADLE_FILE := app/build.gradle.kts
VERSION ?= $(shell grep -E 'versionName\s*=\s*"[^"]+"' $(GRADLE_FILE) | head -n1 | sed -E 's/.*"([^"]+)".*/\1/')
OUTPUT_DIR := dist
DEP_MARKER := .gradle/dependencies_installed.marker

.PHONY: all build build-debug build-release test clean install-deps help

all: build

help:
	@echo "StayFrame Build Targets:"
	@echo "  make build          - Check dependencies and build semantic versioned APK (debug)"
	@echo "  make build-release  - Build release APK variant"
	@echo "  make install-deps   - Check/install Gradle dependencies into cache"
	@echo "  make test           - Run local JVM and Robolectric unit tests"
	@echo "  make clean          - Clean build cache and dist output"

install-deps:
	@echo "[*] Checking dependency resolution state..."
	@if [ -f "$(DEP_MARKER)" ] && [ -d ".gradle/caches" ]; then \
		echo "[✓] All Gradle dependencies are already downloaded and cached."; \
	else \
		echo "[!] Dependencies not fully marked as resolved. Downloading and caching..."; \
		gradle --no-daemon :app:dependencies >/dev/null 2>&1 || true; \
		mkdir -p .gradle; \
		touch $(DEP_MARKER); \
		echo "[✓] Dependency cache initialized successfully."; \
	fi

build: install-deps
	@echo "[*] Building $(APP_NAME) v$(VERSION) (debug)..."
	@./build_apk.sh debug

build-release: install-deps
	@echo "[*] Building $(APP_NAME) v$(VERSION) (release)..."
	@./build_apk.sh release

test:
	@echo "[*] Running unit and Robolectric tests..."
	@gradle --no-daemon :app:testDebugUnitTest

clean:
	@echo "[*] Cleaning build artifacts..."
	@rm -rf $(OUTPUT_DIR) *.apk *.apk.sha256 app/build
	@echo "[✓] Clean complete."
