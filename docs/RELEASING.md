# Releasing RankOffline

Stable releases are built, signed and published with GitHub Actions. Debug builds use a separate application ID so they can coexist with stable builds.

## Safety requirements

- Preserve the release signing key and increment `versionCode` on public updates.
- Maintain explicit non-destructive Room migrations; **do not clear app storage** as a fix for schema/import bugs.
- Keep personal data on pack **updates**. Destructive pack **deletion** is different and must use a strong confirmation.
- Never commit signing keys, `keystore.properties` or secrets.

## Local checks

```bash
git diff --check
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest  # with emulator/device attached
```

Verify physical-device behavior: install a compatible pack, reach READY, search/rank, Merge, download/open covers, save/reopen ratings, force-stop/relaunch, update and explicitly purge. Pure JVM tests are not sufficient.

## v0.2.0 pack/index coherence

The base APK embeds `category_pack_index.json`. The index declares **each** `.pack` archive's filename, download byte length, SHA-256, installed byte count and manifest. The APK and all five packs must be generated **from the same state**. Strict validation must never be weakened to accept stale assets.

Typical development workflow from the v0.2.0 source tree:

```bash
python3 tools/category_packs/build_category_packs.py
./gradlew assembleDebug \
  -Prankoffline.categoryPackBaseUrl=https://github.com/JulenCasajus/RankOffline/releases/download/<NEXT_DEBUG_TAG>/
```

**Replace `<NEXT_DEBUG_TAG>` with the actual prerelease tag before running.** Verify that the generated BuildConfig URL is correct, that `app-debug.apk` contains the intended index, and that all archive hashes and sizes match. Release assets should include the APK **and all five matching packs**. Do not re-use an old index with newly built packs.

Publish a debug test version using a **new prerelease tag**, and explicitly describe it as a development build. Do not overwrite old debug assets silently when format/hashes changed.

## Stable release

1. Commit the intended code, docs and compatible pack manifests.
2. Test upgrade from previous stable app **without clearing user data**.
3. Update version, tag and push a tested commit to `main`.
4. Let the signed release workflow publish and verify the APK.
5. Confirm Obtainium recognizes the stable release.

Local signing is described by `tools/build_release.sh` and `keystore.properties.example`; see the repository workflow for exact production signing secrets.
