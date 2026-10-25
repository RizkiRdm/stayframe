#!/usr/bin/env bash
# ==============================================================================
# StayFrame - Android APK Build Script with Semantic Versioning
# ==============================================================================
set -euo pipefail

APP_NAME="StayFrame"
BUILD_TYPE="${1:-debug}" # "debug" or "release"
OUTPUT_DIR="dist"

echo "=================================================================="
echo " Starting $APP_NAME APK Build Pipeline"
echo "=================================================================="

# 1. Determine Semantic Version
GRADLE_FILE="app/build.gradle.kts"
if [ ! -f "$GRADLE_FILE" ]; then
    echo "[-] Error: $GRADLE_FILE not found!"
    exit 1
fi

EXTRACTED_VERSION=$(grep -E 'versionName\s*=\s*"[^"]+"' "$GRADLE_FILE" | head -n1 | sed -E 's/.*"([^"]+)".*/\1/')
VERSION="${VERSION:-$EXTRACTED_VERSION}"

if [ -z "$VERSION" ]; then
    VERSION="1.0.0"
fi

echo "[+] Target Semantic Version: v$VERSION"
echo "[+] Target Build Type: $BUILD_TYPE"

# 2. Check System Prerequisites & Toolchain
echo ""
echo "[*] Checking system environment and dependencies..."

if ! command -v java >/dev/null 2>&1; then
    echo "[-] Error: Java JDK is not installed. Please install OpenJDK 17 or 21."
    exit 1
else
    JAVA_VER=$(java -version 2>&1 | head -n 1)
    echo "[✓] Java environment detected: $JAVA_VER"
fi

if ! command -v gradle >/dev/null 2>&1; then
    echo "[-] Error: 'gradle' command not found in PATH."
    exit 1
else
    GRADLE_VER=$(gradle --version | grep -E '^Gradle [0-9]' | head -n1)
    echo "[✓] Gradle build tool detected: $GRADLE_VER"
fi

# 3. Check and Install / Warm Gradle Dependencies
echo ""
echo "[*] Checking dependency resolution state..."
DEP_MARKER=".gradle/dependencies_installed.marker"

if [ -f "$DEP_MARKER" ] && [ -d ".gradle/caches" ]; then
    echo "[✓] All Gradle dependencies are already downloaded and cached."
    echo "[✓] Skipping dependency installation, proceeding directly to build."
else
    echo "[!] Dependencies not fully marked as resolved. Downloading and caching dependencies..."
    gradle --no-daemon :app:dependencies >/dev/null 2>&1 || true
    mkdir -p .gradle
    touch "$DEP_MARKER"
    echo "[✓] Dependency cache initialized successfully."
fi

# 4. Build the APK using Gradle
echo ""
echo "[*] Compiling and assembling APK ($BUILD_TYPE mode)..."

if [ "$BUILD_TYPE" = "release" ]; then
    gradle --no-daemon :app:assembleRelease
    SOURCE_APK="app/build/outputs/apk/release/app-release.apk"
    if [ ! -f "$SOURCE_APK" ]; then
        SOURCE_APK="app/build/outputs/apk/release/app-release-unsigned.apk"
    fi
else
    gradle --no-daemon :app:assembleDebug
    SOURCE_APK="app/build/outputs/apk/debug/app-debug.apk"
fi

if [ ! -f "$SOURCE_APK" ]; then
    echo "[-] Error: Expected APK file was not created at: $SOURCE_APK"
    exit 1
fi

# 5. Output Semantic Versioned APK
mkdir -p "$OUTPUT_DIR"
FINAL_APK_NAME="${APP_NAME}-v${VERSION}-${BUILD_TYPE}.apk"
FINAL_APK_PATH="${OUTPUT_DIR}/${FINAL_APK_NAME}"

cp "$SOURCE_APK" "$FINAL_APK_PATH"
cp "$FINAL_APK_PATH" "${APP_NAME}-v${VERSION}.apk"

# Compute SHA-256 Checksum
if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$FINAL_APK_PATH" > "${FINAL_APK_PATH}.sha256"
fi

# Calculate file size in MB
APK_SIZE=$(du -h "$FINAL_APK_PATH" | cut -f1)

echo ""
echo "=================================================================="
echo " BUILD SUCCESSFUL!"
echo "=================================================================="
echo "[+] Application: $APP_NAME"
echo "[+] Semantic Version: $VERSION"
echo "[+] Primary APK: $FINAL_APK_PATH"
echo "[+] Root Alias: ${APP_NAME}-v${VERSION}.apk"
echo "[+] Package Size: $APK_SIZE"
if [ -f "${FINAL_APK_PATH}.sha256" ]; then
    echo "[+] SHA-256: $(cat "${FINAL_APK_PATH}.sha256" | awk '{print $1}')"
fi
echo "=================================================================="
