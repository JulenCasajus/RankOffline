# Security policy

## Supported versions

The latest public stable RankOffline release is the focus for security fixes. As of 26 October 2026, that is **v0.2.0**. Older versions may not receive backports.

| Version | Status |
| --- | --- |
| v0.2.0 | Current stable; reports accepted |
| Earlier versions | Upgrade where possible |
| Development builds | Not supported public releases |

## Reporting a vulnerability privately

**Do not disclose exploits, sensitive logs or other user data in public GitHub issues.**

1. Check the repository's **Security** tab for **Private vulnerability reporting**. If available, use it to submit the report privately.
2. If private reporting is not available, request a private contact channel from the maintainer **without publishing vulnerability details**.

Please include the affected app version and Android version, a minimal reproduction, expected versus actual behavior and potential impact. Redact credentials, personal notes and identifiers. Do not send proof-of-concept data publicly.

Examples in scope include untrusted catalog or backup input, unauthorized file access, malicious archives, data leakage, update-signing problems, and security-sensitive data loss.

We will investigate responsibly, but do not promise a specific response time or fix date. Please coordinate public disclosure so users can receive a fix first.

For non-security bugs use the [issue templates](https://github.com/JulenCasajus/RankOffline/issues/new/choose); for data handling see [PRIVACY.md](PRIVACY.md).
