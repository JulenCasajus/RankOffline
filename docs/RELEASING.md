# Releasing RankOffline

**Current stable release: [v0.2.0](https://github.com/JulenCasajus/RankOffline/releases/tag/v0.2.0), already published.** The version-specific instructions below describe the historical v0.2.0 release contract. Do **not** reuse the v0.2.0 tag, versionCode, pack URL or release workflow unchanged for future versions.

## Checklist for the next stable release

1. Complete code review and test all changes. Recheck preservation of ratings, notes and settings across **signed stable** upgrades, including published migration paths. A debug-to-debug update alone is insufficient.
2. Verify offline pack installation, cancellation, process interruption/recovery, optional covers, performance and any new export/import or backup behavior on representative devices. Document skipped tests.
3. Audit third-party provider permissions, catalog redistribution, logos/icons, source attribution and privacy notices before distributing new assets. Unresolved permissions must be addressed rather than concealed.
4. Update Gradle `versionName` and strictly increasing `versionCode`, pack index/API version, URL and release metadata. Preserve the stable package ID and signing key.
5. Update and revalidate `.github/workflows/release.yml`: the existing workflow is specifically wired for **v0.2.0** and cannot be assumed to publish later versions safely.
6. Build and independently inspect the signed APK, match APK-embedded pack index to the published JSON/pack hashes, validate archive contents, and confirm the signing certificate and package version.
7. Update [CHANGELOG](../CHANGELOG.md), [README](../README.md), Fastlane descriptions/changelog and [privacy notice](../PRIVACY.md) when user-visible behavior changes. Keep unreleased features explicitly labeled.
8. Only after a reviewed candidate and stable in-place upgrade pass: merge, tag immutably, check draft assets and publish deliberately. Then verify downloads and installation in the public release.

Never commit signing keys, provider credentials, user data or signed APKs to source control.


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
The configured `.github/workflows/release.yml` builds all **seven** matched
assets on the same runner: the signed APK, the trusted index, and five
data-only packs. It validates each archive, verifies the index embedded in
the APK, checks app metadata, and compares the stable signing certificate
with `v0.1.3`. A manual `workflow_dispatch` run from `main` uploads a
**private Actions candidate artifact**, but creates **no GitHub Release**.
A run triggered by the exact `v0.2.0` tag creates a **draft Release** only
after successful checks; manual publication remains a separate step.

The v0.2.0 release is already public. These details remain for historical reproducibility and should not be treated as publication instructions for the next version.

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

## Historical v0.2.0 release checklist (archived)

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
10. Merge validated code/documentation into `main`; execute the manual
    seven-file candidate workflow and verify its full success.
11. Download the signed candidate artifact and confirm an in-place stable
    `v0.1.3 → v0.2.0` upgrade without deleting personal data.
12. Only then create an annotated, immutable `v0.2.0` tag on the exact
    validated `main` commit. Inspect the automatically created draft and
    its seven assets before publishing it.
13. After public publication, recheck asset sizes, hashes, download URLs,
    a clean install, and Obtainium or direct updates. A private candidate,
    a build or a draft is not a published Release.

The tag-triggered workflow excludes `v*-debug.*`, accepts only the exact
stable tag `v0.2.0` for this workflow version, regenerates/verifies the
six pack/index data assets, verifies the signed APK and prior stable signing
identity, and creates a **draft** with the seven matching files. It refuses
to replace an existing Release. Do not create the tag until the manual
candidate run and in-place signed upgrade have passed.

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

`RankOfflineAll` is under development for a later release and is not shipped in the public v0.2.0 APK. Community-pack installation is a separate future idea. See [ROADMAP.md](ROADMAP.md).
