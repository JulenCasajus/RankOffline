#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE_PROPERTIES="$PROJECT_DIR/keystore.properties"
VERSION_NAME="$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)"/\1/p' "$PROJECT_DIR/app/build.gradle.kts")"
SOURCE_APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
DIST_DIR="$PROJECT_DIR/dist"
DIST_APK="$DIST_DIR/RankOffline-v${VERSION_NAME}.apk"

if [[ -z "$VERSION_NAME" ]]; then
    echo "Error: no se pudo leer versionName desde app/build.gradle.kts." >&2
    exit 1
fi

if [[ ! -f "$KEYSTORE_PROPERTIES" ]]; then
    echo "Error: falta keystore.properties. Copia keystore.properties.example y configura tu keystore local." >&2
    exit 1
fi

cd "$PROJECT_DIR"
./gradlew testDebugUnitTest
./gradlew assembleRelease

if [[ ! -f "$SOURCE_APK" ]]; then
    echo "Error: Gradle terminó sin generar el APK release esperado: $SOURCE_APK" >&2
    exit 1
fi

mkdir -p "$DIST_DIR"
cp "$SOURCE_APK" "$DIST_APK"
echo "APK release creado: $DIST_APK"

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_DIR" && -f "$PROJECT_DIR/local.properties" ]]; then
    SDK_DIR="$(sed -nE 's/^sdk\.dir=(.*)/\1/p' "$PROJECT_DIR/local.properties")"
fi

if [[ -n "$SDK_DIR" && -d "$SDK_DIR/build-tools" ]]; then
    BUILD_TOOLS_DIR="$(find "$SDK_DIR/build-tools" -mindepth 1 -maxdepth 1 -type d | sort | tail -1)"
    if [[ -x "$BUILD_TOOLS_DIR/aapt" ]]; then
        "$BUILD_TOOLS_DIR/aapt" dump badging "$DIST_APK" | sed -n '1p'
    fi
    if [[ -x "$BUILD_TOOLS_DIR/apksigner" ]]; then
        "$BUILD_TOOLS_DIR/apksigner" verify --verbose "$DIST_APK"
    fi
else
    echo "Aviso: Android build-tools no encontrado; verifica manualmente los metadatos y la firma del APK." >&2
fi
