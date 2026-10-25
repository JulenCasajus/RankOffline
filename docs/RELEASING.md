# Releasing RankOffline

RankOffline has two distinct Android application identities:

- Debug/test: `org.rankoffline.app.debug` (for example `0.2.0-debug.5`).
- Signed stable release: `org.rankoffline.app` (the upgrade path from stable `0.1.3`).

A debug-to-debug update does **not** validate the signed stable
`0.1.3 → 0.2.0` migration. Preserve stable signing identity and use a
strictly higher `versionCode` for every new version of the same application ID.

## Production 0.2 asset contract

The normal APK contains no production catalog. A stable `v0.2.0` must
distribute **seven matching assets**:

```text
RankOffline-v0.2.0.apk
category_pack_index.json
RankOffline-Anime-v0.2.0.pack
RankOffline-Manga-v0.2.0.pack
RankOffline-Movies-v0.2.0.pack
RankOffline-Series-v0.2.0.pack
RankOffline-Books-v0.2.0.pack
```

Each archive's byte length and SHA-256 must match the trusted index that is
embedded **byte-for-byte** in the APK. Pack URL for the production APK must
resolve to `https://github.com/JulenCasajus/RankOffline/releases/download/v0.2.0/`.
The current stable workflow builds/releases the signed APK **only**.
Before making 0.2 generally available, either extend that workflow to
attach/verifiably publish the six data assets in the same release, or use a
controlled draft-and-verify workflow. Do not assume that the current
`.github/workflows/release.yml` already handles packs.

Debug prereleases use their own tag-specific pack base URL and may keep a
different application ID and signing certificate. Do not edit an earlier
release tag to point at unrelated code.

## Signing and secrets

Store the definitive stable keystore offline and back it up. Locally,
`keystore.properties` configures:

```properties
storeFile=/absolute/or/project-relative/path/to/RankOffline-release.jks
storePassword=...
keyAlias=rankoffline
keyPassword=...
```

The GitHub Action uses these encrypted secrets:

- `RANKOFFLINE_KEYSTORE_BASE64`
- `RANKOFFLINE_KEYSTORE_PASSWORD`
- `RANKOFFLINE_KEY_ALIAS`
- `RANKOFFLINE_KEY_PASSWORD`

Never commit the keystore, `keystore.properties`, passwords, access tokens or
generated signed APKs.

## Checklist before v0.2.0

1. Complete and review the final debug.6 fixes. Test the real device,
   particularly five-pack installs, queueing, Top-100 providers and cancellation.
2. Verify a **non-destructive signed stable** 0.1.3 → 0.2.0 install with the
   exact stable app ID/key. Check ratings, notes, category weights, profiles,
   themes, settings and offline packs.
3. Regenerate and validate the five pack files with the current tooling and
   intended release defaults. Keep third-party cover binaries out unless there
   is documented permission to redistribute them.
4. Verify the index and all pack payload paths, sizes and hashes.
5. Configure Gradle's **release** `versionName = "0.2.0"`, increase the
   stable `versionCode` above the last published stable code and set the
   correct production pack base URL. Do not confuse the debug output-name
   override with the release `versionName`.
6. Verify the APK-embedded `category_pack_index.json` is byte-identical
   to the index included among the upload assets.
7. Run `git diff --check`, focused/full unit tests, `assembleDebug`,
   relevant instrumentation tests and `assembleRelease` with validated
   signing settings. Check targetSdk suitability for the chosen channel.
8. Verify the signed APK with `apksigner verify --print-certs`, compare its
   certificate against the previous stable release, and inspect the package
   ID, `versionName`, `versionCode` and pack URL.
9. Review [DATA_SOURCES.md](DATA_SOURCES.md),
   [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) and provider/API terms.
10. Merge validated code/documentation into `main`; create an immutable,
    consistent `v0.2.0` tag once the release pipeline and seven-asset
    publication sequence are prepared.
11. Publish the seven matching assets; verify GitHub's reported file sizes,
    hashes and downloaded installation. Do not declare success after an
    APK-only workflow run.
12. Verify Obtainium or direct stable updates without clearing personal data.

The existing tag-triggered workflow excludes `v*-debug.*`, validates signing,
runs tests, signs the APK and creates a GitHub Release for stable tags.
It does not yet regenerate/upload the six pack assets; this is an explicit
release-engineering task.

## Local verification examples

```bash
git diff --check
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest  # when a healthy test device is available
./gradlew :app:verifyReleaseSigning
./gradlew assembleRelease
```

The current official pack builder writes output to
`tools/.cache/category-packs/output/`:

```bash
python3 tools/category_packs/build_category_packs.py
```

For a local signed release, `tools/build_release.sh` also performs
versioned output/signature checks. The CI workflow uses an encrypted copy of
the same stable signing key and removes its temporary copy after use.

Never release an APK with an outdated index. Never publish a debug APK under
the stable tag.

## Privacy, provider rights and future updates

All catalog/reference operations are offline after a pack is installed.
Remote requests cover verified pack archive downloads and optional image
retrieval, not remote title discovery. Personal ratings stay private.

Provider metadata, images and logos retain independent terms. Access to an
image URL is not evidence of redistribution permission.

`RankOfflineAll` and community-pack installation belong to a later version.
