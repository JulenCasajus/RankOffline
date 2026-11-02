#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from packlib.common import canonical_json, sha256_file  # noqa: E402


def main(arguments: list[str]) -> int:
    if len(arguments) < 2:
        raise SystemExit("usage: record_kotlin_pack_validation.py REPORT PACK...")
    report_path = Path(arguments[0])
    archives = [Path(value) for value in arguments[1:]]
    report = json.loads(report_path.read_text(encoding="utf-8"))
    expected = {entry["archive"]: entry for entry in report["packs"]}
    validated = []
    for archive in archives:
        digest = sha256_file(archive)
        entry = expected.get(archive.name)
        if entry is None or entry["archiveSha256"] != digest:
            raise SystemExit(f"Kotlin-validated archive is absent/stale in report: {archive}")
        validated.append({"archive": archive.name, "archiveSha256": digest})
    if len(validated) != len(expected):
        raise SystemExit("Kotlin validation did not cover every archive in validation-report.json")
    report["kotlinContractValidation"] = {
        "status": "passed",
        "validator": "actual-runtime-source-harness",
        "archives": sorted(validated, key=lambda entry: entry["archive"]),
    }
    report_path.write_bytes(canonical_json(report, newline=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
