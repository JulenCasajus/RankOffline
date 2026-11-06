from __future__ import annotations

import re
import struct
from urllib.parse import urlparse


ROOT_KEYS = {
    "schemaVersion", "packId", "version", "minRankOfflineVersion", "displayName",
    "presentation", "capabilities", "fields", "filters", "subtitle", "merge", "defaults",
    "rating", "coverSources", "catalog", "groups", "covers", "attribution", "integrity",
}
RECORD_KEYS = {
    "id", "title", "provider", "providerId", "popularity", "popularityRank",
    "remoteCoverRef", "attributes", "aliases",
}
GROUP_KEYS = {
    "id", "members", "displayTitle", "groupType", "representativeMediaId", "provider",
    "providerGroupId", "popularity", "popularityRank", "remoteCoverRef",
}
FORBIDDEN_SUFFIXES = (".dex", ".jar", ".class", ".so", ".apk", ".js", ".html", ".htm", ".svg", ".sh")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
LOCALE = re.compile(r"[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8})*\Z")
ISO_DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}\Z")
INTEGER = re.compile(r"-?(?:0|[1-9][0-9]*)\Z")
MAX_SAFE_INTEGER = 9_007_199_254_740_991

class PackValidationError(ValueError):
    pass

def _fail(message: str) -> None:
    raise PackValidationError(message)

def _exact_keys(value: dict, allowed: set[str], context: str) -> None:
    unknown = set(value) - allowed
    if unknown:
        _fail(f"{context} has unsupported properties: {sorted(unknown)}")

def _required_keys(value: dict, required: set[str], context: str) -> None:
    missing = required - set(value)
    if missing:
        _fail(f"{context} is missing properties: {sorted(missing)}")

def _text(value: object, limit: int, context: str) -> str:
    if (not isinstance(value, str) or not value.strip() or len(value) > limit or
            any(ord(ch) < 32 or 127 <= ord(ch) <= 159 for ch in value)):
        _fail(f"Invalid {context}")
    return value

def _notice(value: object, context: str) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > 4096:
        _fail(f"Invalid {context}")
    if any((ord(ch) < 32 and ch not in "\n\r\t") or 127 <= ord(ch) <= 159 for ch in value):
        _fail(f"Unsafe control character in {context}")
    return value

def _safe_path(value: object) -> str:
    if (not isinstance(value, str) or not 1 <= len(value) <= 256 or value.startswith("/") or
            "\\" in value or ":" in value or any(ord(ch) < 32 or 127 <= ord(ch) <= 159 for ch in value)):
        _fail(f"Invalid payload path: {value!r}")
    parts = value.split("/")
    if len(parts) < 2 or any(not part or part in (".", "..") for part in parts):
        _fail(f"Unsafe payload path: {value}")
    if parts[0] not in ("catalog", "groups", "covers", "metadata"):
        _fail(f"Unsupported payload directory: {value}")
    if value.lower().endswith(FORBIDDEN_SUFFIXES):
        _fail(f"Executable or active payload is forbidden: {value}")
    return value

def _https_url(value: object, context: str) -> str:
    text = _text(value, 2048, context)
    parsed = urlparse(text)
    if parsed.scheme.lower() != "https" or not parsed.hostname or parsed.username or parsed.password:
        _fail(f"Unsafe HTTPS URL in {context}")
    if parsed.port not in (None, 443) or "." not in parsed.hostname or parsed.hostname.endswith("."):
        _fail(f"Unsafe HTTPS host in {context}")
    if parsed.hostname.replace(".", "").isdigit() or parsed.hostname.endswith(".local"):
        _fail(f"Non-public HTTPS host in {context}")
    return text

def _image_dimensions(data: bytes) -> tuple[int, int] | None:
    if data.startswith(b"\x89PNG\r\n\x1a\n") and len(data) >= 24:
        return struct.unpack(">II", data[16:24])
    if data.startswith((b"II*\x00", b"MM\x00*")):
        return None
    if data.startswith(b"RIFF") and data[8:12] == b"WEBP" and len(data) >= 30:
        kind = data[12:16]
        if kind == b"VP8X":
            width = 1 + int.from_bytes(data[24:27], "little")
            height = 1 + int.from_bytes(data[27:30], "little")
            return width, height
        if kind == b"VP8 " and len(data) >= 30 and data[23:26] == b"\x9d\x01\x2a":
            return int.from_bytes(data[26:28], "little") & 0x3FFF, int.from_bytes(data[28:30], "little") & 0x3FFF
        if kind == b"VP8L" and len(data) >= 25 and data[20] == 0x2F:
            bits = int.from_bytes(data[21:25], "little")
            return (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1
    if data.startswith(b"\xff\xd8"):
        pos = 2
        while pos + 9 < len(data):
            if data[pos] != 0xFF:
                pos += 1
                continue
            marker = data[pos + 1]
            pos += 2
            if marker in (0xD8, 0xD9):
                continue
            if pos + 2 > len(data):
                break
            length = int.from_bytes(data[pos:pos + 2], "big")
            if marker in set(range(0xC0, 0xC4)) | set(range(0xC5, 0xC8)) | set(range(0xC9, 0xCC)) | set(range(0xCD, 0xD0)):
                return int.from_bytes(data[pos + 5:pos + 7], "big"), int.from_bytes(data[pos + 3:pos + 5], "big")
            pos += length
    return None
