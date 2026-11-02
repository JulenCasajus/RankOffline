#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from packlib import PackValidationError, validate_pack  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate RankOffline schema-2 .pack archives")
    parser.add_argument("archives", type=Path, nargs="+")
    parser.add_argument("--expected-pack-id", type=int)
    args = parser.parse_args()
    reports = []
    try:
        for archive in args.archives:
            reports.append(validate_pack(archive, expected_pack_id=args.expected_pack_id))
    except PackValidationError as error:
        print(f"INVALID: {error}", file=sys.stderr)
        return 1
    print(json.dumps(reports, ensure_ascii=False, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
