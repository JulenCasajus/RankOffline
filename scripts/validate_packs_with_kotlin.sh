#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
KOTLIN_HOME="/Applications/Android Studio.app/Contents/plugins/Kotlin/kotlinc"
SDK_ROOT="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
SDK_SOURCES="$SDK_ROOT/sources/android-37.0"

if [[ ! -x "$KOTLIN_HOME/bin/kotlinc-jvm" || ! -d "$SDK_SOURCES/org/json" ]]; then
  echo "Android Studio Kotlin compiler or Android 37 sources are unavailable." >&2
  exit 2
fi

SERIALIZATION_CORE="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm/1.11.0" -type f -name '*1.11.0.jar' | head -n 1)"
SERIALIZATION_JSON="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.11.0" -type f -name '*1.11.0.jar' | head -n 1)"
COROUTINES="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.11.0" -type f -name '*1.11.0.jar' | head -n 1)"
if [[ -z "$SERIALIZATION_CORE" || -z "$SERIALIZATION_JSON" || -z "$COROUTINES" ]]; then
  echo "Required cached Kotlin dependencies are unavailable; sync the Android project first." >&2
  exit 2
fi

if [[ $# -eq 0 ]]; then
  set -- "$PROJECT_ROOT"/build/pack-output/pack-{1,2,3,4,5}.pack
fi

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/rankoffline-kotlin-validator.XXXXXX")"
cleanup() { rm -rf -- "$WORK_DIR"; }
trap cleanup EXIT
mkdir -p "$WORK_DIR/classes"

mkdir -p "$WORK_DIR/json"
for source in "$SDK_SOURCES"/org/json/*.java; do
  sed -E \
    -e '/^import static android\.annotation\.SystemApi\.Client\.MODULE_LIBRARIES;/d' \
    -e '/^import android\.(annotation|compat\.annotation)\./d' \
    -e '/^import libcore\.util\.(NonNull|Nullable);/d' \
    -e 's/@(NonNull|Nullable|UnsupportedAppUsage)[[:space:]]*//g' \
    -e 's/@SystemApi\(client = MODULE_LIBRARIES\)[[:space:]]*//g' \
    "$source" > "$WORK_DIR/json/$(basename "$source")"
done
javac -d "$WORK_DIR/classes" "$WORK_DIR"/json/*.java

SOURCES=(
  "$PROJECT_ROOT/app/src/java/org/rankoffline/app/packs/manifest/PackManifestJson.kt"
  "$PROJECT_ROOT/app/src/java/org/rankoffline/app/packs/manifest/PackManifestIntegrity.kt"
  "$PROJECT_ROOT/app/src/java/org/rankoffline/app/packs/manifest/PackManifestResources.kt"
  "$PROJECT_ROOT/app/src/java/org/rankoffline/app/packs/manifest/PackManifestSchema.kt"
  "$PROJECT_ROOT/app/src/java/org/rankoffline/app/packs/manifest/PackManifestSettings.kt"
  "$PROJECT_ROOT/app/src/java/org/rankoffline/app/packs/manifest/PackManifestRating.kt"
  "$PROJECT_ROOT/app/src/java/org/rankoffline/app/packs/manifest/PackManifestParser.kt"
  "$PROJECT_ROOT/app/src/java/org/rankoffline/app/packs/model/PackModels.kt"
  "$PROJECT_ROOT/app/src/java/org/rankoffline/app/packs/model/PackAttributions.kt"
  "$PROJECT_ROOT/app/src/java/org/rankoffline/app/packs/model/AttributionJsonReaders.kt"
  "$PROJECT_ROOT/app/src/java/org/rankoffline/app/packs/model/AttributionLegalValidator.kt"
  "$PROJECT_ROOT/app/src/java/org/rankoffline/app/ratings/model/RatingConfiguration.kt"
  "$PROJECT_ROOT/app/src/java/org/rankoffline/app/catalog/model/CatalogItemFormat.kt"
  "$PROJECT_ROOT/app/src/java/org/rankoffline/app/catalog/model/CatalogGroupFormat.kt"
  "$PROJECT_ROOT/app/src/java/org/rankoffline/app/packs/install/PackArchiveReader.kt"
  "$SCRIPT_DIR/kotlin-validator/ContractStubs.kt"
  "$SCRIPT_DIR/kotlin-validator/ValidatePacks.kt"
)

CLASSPATH="$WORK_DIR/classes:$SERIALIZATION_CORE:$SERIALIZATION_JSON:$COROUTINES"
"$KOTLIN_HOME/bin/kotlinc-jvm" \
  -Xplugin="$KOTLIN_HOME/lib/kotlinx-serialization-compiler-plugin.jar" \
  -classpath "$CLASSPATH" -d "$WORK_DIR/validator.jar" "${SOURCES[@]}"

"$KOTLIN_HOME/bin/kotlin" -classpath "$WORK_DIR/validator.jar:$CLASSPATH" \
  org.rankoffline.app.ValidatePacksKt "$@"

REPORT="$PROJECT_ROOT/build/pack-output/validation-report.json"
if [[ -f "$REPORT" ]]; then
  python3 "$SCRIPT_DIR/record_kotlin_pack_validation.py" "$REPORT" "$@"
fi
