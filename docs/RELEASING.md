# Releasing RankOffline

Stable releases are signed and published through GitHub Actions.

## Important rules

- Keep the same release signing key across versions.
- Increment `versionCode` for every public Android update.
- Set the intended `versionName`.
- Never use destructive Room migrations for user data.
- Test upgrades from the previous public APK when persistence changes.

## Local validation

Before release:

```bash
git diff --check
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

When a device or emulator is available:

```bash
./gradlew connectedDebugAndroidTest
```

For locally signed release verification:

```bash
tools/build_release.sh
```

Signing configuration is read from `keystore.properties`, which must never be committed.

## Publishing a stable release

1. Commit the intended code and documentation.
2. Update `versionName` and increment `versionCode`.
3. Run tests and migration checks.
4. Push the release commit to `main`.
5. Create and push the matching tag:

```bash
git tag -a vX.Y.Z -m "RankOffline vX.Y.Z"
git push origin vX.Y.Z
```

6. Let the release workflow build, sign, verify, and publish the APK.
7. Confirm the APK updates the previous stable installation without clearing user data.
8. Confirm Obtainium detects the release correctly.

## Development builds

Debug builds may use a separate application ID such as:

```text
org.rankoffline.app.debug
```

This allows **RankOffline Dev** to coexist with the stable app.

Development builds should be published only as GitHub **pre-releases**, clearly labelled as unstable/testing builds.

## Signing

The definitive keystore must be backed up securely outside the repository.

Required GitHub Actions secrets:

- `RANKOFFLINE_KEYSTORE_BASE64`
- `RANKOFFLINE_KEYSTORE_PASSWORD`
- `RANKOFFLINE_KEY_ALIAS`
- `RANKOFFLINE_KEY_PASSWORD`

Never commit the keystore, passwords, `keystore.properties`, or signed APKs.
