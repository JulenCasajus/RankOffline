#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
required=(
    RANKOFFLINE_UPLOAD_STORE_FILE
    RANKOFFLINE_UPLOAD_STORE_PASSWORD
    RANKOFFLINE_UPLOAD_KEY_ALIAS
    RANKOFFLINE_UPLOAD_KEY_PASSWORD
)

for name in "${required[@]}"; do
    if [[ -z "${!name:-}" ]]; then
        printf 'Missing release signing variable: %s\n' "$name" >&2
        exit 1
    fi
done

"$project_dir/scripts/check-kotlin-limits.sh"
cd "$project_dir"
./gradlew --no-daemon clean lint test bundleRelease
printf 'Bundle: %s\n' "$project_dir/app/build/outputs/bundle/release/app-release.aab"
