# Releasing RankOffline

RankOffline releases are built, signed, verified, and published by GitHub Actions when a version tag is pushed.

The signing identity must remain the same across releases so existing Android installations can update normally.

## Signing setup

The definitive release keystore should be backed up securely outside the repository.

GitHub Actions uses an encoded copy of that same keystore stored as an encrypted repository secret. The workflow temporarily decodes it only inside the GitHub-hosted runner and removes temporary signing files in the final cleanup step.

Required GitHub Actions secrets:

- `RANKOFFLINE_KEYSTORE_BASE64`
- `RANKOFFLINE_KEYSTORE_PASSWORD`
- `RANKOFFLINE_KEY_ALIAS`
- `RANKOFFLINE_KEY_PASSWORD`

Never commit:

- the real keystore;
- `keystore.properties`;
- passwords or secret values;
- generated signed APKs.

These paths are ignored by Git.

## Local signing

Local signing remains useful for pre-release verification.

Copy `keystore.properties.example` to `keystore.properties` and configure the same definitive release keystore:

```properties
storeFile=/absolute/or/project-relative/path/to/RankOffline-release.jks
storePassword=...
keyAlias=rankoffline
keyPassword=...
```

Then run:

```bash
tools/build_release.sh
```

The script:

1. runs JVM tests;
2. builds the signed release APK;
3. copies it to `dist/RankOffline-vX.Y.Z.apk`;
4. verifies package/signature metadata when Android build-tools are available.

## Release checklist

1. Make sure the intended code and documentation are committed.
2. Update `versionName` in `app/build.gradle.kts`.
3. Increment `versionCode`. Never reuse or decrease a published value.
4. Do **not** regenerate the bundled catalog unless the release intentionally contains a catalog update.
5. Run local validation:

   ```bash
   git diff --check
   ./gradlew testDebugUnitTest
   ./gradlew assembleDebug
   ```

   When a healthy emulator/device is available:

   ```bash
   ./gradlew connectedDebugAndroidTest
   ```

6. For releases containing a Room migration, test a real update from the previous published APK and confirm ratings, notes, categories, profiles, and settings survive.
7. Push the release commit to `main` and wait for **Build Android APK** to succeed.
8. Create and push the matching tag:

   ```bash
   git tag -a vX.Y.Z -m "RankOffline vX.Y.Z"
   git push origin vX.Y.Z
   ```

9. The **Release RankOffline** workflow then:
   - validates the bundled catalog hash;
   - verifies that the tag matches Gradle `versionName`;
   - validates signing secrets;
   - runs unit tests;
   - builds the signed release APK;
   - verifies its signature with `apksigner`;
   - uploads the APK as an Actions artifact;
   - creates the GitHub Release for tag-triggered runs.

10. Confirm the release contains `RankOffline-vX.Y.Z.apk`.
11. Verify Obtainium detects and installs the update over the previous version without clearing app data.

## Versioning

Published Android updates require a monotonically increasing `versionCode`.

Example progression:

```text
v0.1.0 -> versionCode 1
v0.1.1 -> versionCode 2
v0.2.0 -> versionCode 3
```

The semantic version may skip patch versions when a release contains a meaningful feature change; the Android `versionCode` still increments by at least one.

## Security notes

The release workflow has only the repository permission required to create releases. The ordinary Android build workflow is read-only.

Signing material is injected through encrypted GitHub Actions secrets, written with restrictive runner permissions, and deleted in an `always()` cleanup step.

If the definitive signing key is lost or replaced, future APKs will not update installations signed with the previous key. Keep an offline backup.
