# Preparing official F-Droid distribution

This is a preparation guide, **not** proof that RankOffline is accepted,
reproducible, or currently available in the official F-Droid repository.

## Goal

Keep `org.rankoffline.app` and the existing stable GitHub signing certificate so
an APK distributed via F-Droid can update the same installation as the APK
published at GitHub Releases. This requires reproducible builds; adding a
`Binaries:` URL and certificate fingerprint does **not** prove reproducibility.

The *draft* F-Droid metadata is at
`distribution/fdroid/org.rankoffline.app.yml`. Copy it to
`fdroiddata/metadata/org.rankoffline.app.yml` after validation, rather than
assuming it is imported automatically by F-Droid from this repository.

## Verified repository facts

- Official package name: `org.rankoffline.app`; debug adds `.debug`.
- Stable `v0.2.0`: `versionCode=7`, published GitHub tag `v0.2.0`.
- F-Droid source commit for 0.2.0:
  `4f1c66a2b17d50c9a5e863368db252d778dbb37e`.
- GitHub release asset: `RankOffline-v0.2.0.apk`.
- Stable signing certificate SHA-256 (from verified GitHub Actions logs):
  `8c83ef0fef0568c9530fd6fda3f2cb435303777d4791839122f01b2f35c0abca`.
- The v0.2.0 build currently defaults to the GitHub v0.2.0 pack URL and
  `bundleProductionCatalogs=false`; preserve these build inputs for comparison.

## Blocking checks before submission

1. **Build from a clean v0.2.0 checkout, not an arbitrary latest main branch.**
   The Gradle version, Git tag, catalog index and build dependencies must match
   the already-published APK. F-Droid builds must not rely on signing secrets.
2. **Prove reproducibility against the actual signed stable GitHub APK.**
   On macOS/Linux with JDK 17, the Android SDK, `apksigner`, Python and
   `apksigcopier` available, in a **separate worktree** from development:

   ```bash
   git fetch origin --tags
   git worktree add --detach /tmp/rankoffline-fdroid-v020 v0.2.0
   cd /tmp/rankoffline-fdroid-v020
   # The tagged v0.2.0 source enforces signing even for unsigned builds.
   # Exclude only that guard when comparing; do not modify production signing.
   ./gradlew --no-daemon assembleRelease -x :app:verifyReleaseSigning \
     -Prankoffline.categoryPackBaseUrl=https://github.com/JulenCasajus/RankOffline/releases/download/v0.2.0/ \
     -Prankoffline.bundleProductionCatalogs=false
   # Download RankOffline-v0.2.0.apk from the GitHub v0.2.0 Release.
   # The unsigned output typically ends with app-release-unsigned.apk.
   apksigcopier compare --unsigned /path/to/RankOffline-v0.2.0.apk \
     app/build/outputs/apk/release/app-release-unsigned.apk
   ```

   The F-Droid recipe uses an equivalent narrowly scoped prebuild patch to
   remove the preReleaseBuild signing guard without changing the application
   code or release key. A zero exit status is required. Differences require investigation (e.g.
   pinned toolchain, ZIP ordering, embedded metadata, or AGP). Do not publish
   the draft recipe under reproducible-build settings until it actually passes.
   Remove the temporary worktree after testing using `git worktree remove`.
3. **Run F-Droid's own scan and build.** Fork
   [fdroiddata](https://gitlab.com/fdroid/fdroiddata), place the draft YAML in
   `metadata/org.rankoffline.app.yml`, and run `fdroid lint org.rankoffline.app`,
   `fdroid checkupdates org.rankoffline.app`, and the fdroiddata GitLab CI build.
   Do not bypass scanner findings just to make CI green.
4. **Audit redistributable artwork and data.** Especially review all bundled
   launcher icons (including art reminiscent of recognizable anime characters),
   fonts, bundled assets and catalog-source licenses. F-Droid requires legal
   redistribution rights for assets included in the APK. Provider attribution
   alone is not a substitute for those rights; disclose any Anti-Features.
5. **Ask maintainers to verify the developer-signed APK strategy** and record
   any required metadata changes. If reproducibility cannot be achieved,
   a F-Droid-signed build is possible, but it **cannot update existing
   GitHub-signed RankOffline installations in place**.

## Future updates after inclusion

1. Develop and test the new stable version with a *strictly increasing*
   `versionCode` (must exceed `7`), without changing the Android application ID.
2. Preserve the same stable signing key and reproducible build inputs.
3. Create an immutable **stable** GitHub tag `vX.Y.Z`, and publish a signed APK
   named `RankOffline-vX.Y.Z.apk` as its corresponding GitHub release asset.
4. Keep the build's pack URL and index consistent with that version. The
   v0.2.0 release workflow is intentionally hard-coded to v0.2.0; update the
   workflow for future releases, rather than reusing it unchanged.
5. F-Droid's `UpdateCheckMode: Tags ^v[0-9]+\.[0-9]+\.[0-9]+$` excludes
   prerelease/debug tags. `AutoUpdateMode: Version` requests automatic update
   metadata generation. F-Droid then rebuilds, verifies and publishes on its
   own schedule, not atomically with GitHub.
6. For each future *tagged* release, update the upstream Fastlane
   changelog `changelogs/<versionCode>.txt` **before creating the tag**.

## Official documentation

- https://f-droid.org/docs/Inclusion_Policy/
- https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/
- https://f-droid.org/docs/Reproducible_Builds/
- https://f-droid.org/docs/Build_Metadata_Reference/

This preparation makes **no code changes** to the currently published app.
