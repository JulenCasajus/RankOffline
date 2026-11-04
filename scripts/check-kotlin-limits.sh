#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
kotlin_root="$project_dir/app/src"
failed=0

while IFS= read -r -d '' file; do
    lines="$(wc -l < "$file" | tr -d ' ')"
    functions="$(grep -Ec '^[[:space:]]*((public|private|internal|protected|inline|infix|operator|tailrec|suspend|override|open|final|abstract|external)[[:space:]]+)*fun[[:space:]]' "$file" || true)"
    if (( lines > 500 || functions > 12 )); then
        printf '%s: %s lines, %s functions\n' "${file#"$project_dir/"}" "$lines" "$functions" >&2
        failed=1
    fi
done < <(find "$kotlin_root" -type f -name '*.kt' -print0)

count="$(find "$kotlin_root" -type f -name '*.kt' | wc -l | tr -d ' ')"
if [[ "$count" != "199" ]]; then
    printf 'Expected 199 Kotlin files across all source sets, found %s\n' "$count" >&2
    failed=1
fi

exit "$failed"
