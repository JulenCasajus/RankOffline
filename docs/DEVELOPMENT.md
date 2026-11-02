# Development

## Toolchain

| Component | Selected stable version | Reason/source |
|---|---:|---|
| Gradle | 9.8.2 | Current stable; wrapper distribution is checksum-pinned ([Gradle](https://gradle.org/releases/)) |
| Android Gradle Plugin | 9.5.0 | API 37 support; Gradle minimum 9.6 ([Android](https://developer.android.com/build/releases/agp-9-5-0-release-notes)) |
| Android SDK Build Tools | 36.0.0 (AGP default) | AGP 9.5's documented tested default; no manual override |
| Kotlin / Compose Compiler | 2.4.21 | Matching stable compiler/plugin ([Kotlin](https://kotlinlang.org/docs/releases.html)) |
| JDK for Gradle | 21 LTS | Supported by Gradle 9.8.2; conservative LTS above AGP's JDK 17 minimum |
| Java/Kotlin bytecode target | 17 | Android/AGP ecosystem baseline; independent from Gradle runtime JDK |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 | Android 17 target, Android 8.0 minimum |
| Compose BOM | 2026.09.00 | Stable compatibility set ([Compose BOM](https://developer.android.com/develop/ui/compose/bom)) |
| Activity / Core / AppCompat | 1.13.0 / 1.19.0 / 1.8.0 | Stable AndroidX channel |
| Lifecycle / DataStore | 2.11.0 / 1.3.0 | Stable AndroidX releases |
| Room / WorkManager | 2.8.5 / 2.12.0 | Stable; WorkManager minSdk 24 remains below app minSdk 26 |
| KSP | 2.3.12 | Stable KSP2; KSP1 is unsupported |
| Coroutines / Serialization | 1.11.0 / 1.11.0 | Latest non-preview stable releases |

AGP 9 built-in Kotlin is enabled; `org.jetbrains.kotlin.android` is deliberately
not applied. The Compose Compiler and Serialization plugins remain versioned at
2.4.21. Navigation is deliberately absent because the current UI uses its own
screen state and imports no Navigation API. No alpha, beta, RC, snapshot or
dynamic dependency is used.

## Local checks

```bash
./scripts/check-kotlin-limits.sh
./gradlew lint test assembleDebug
./gradlew bundleRelease   # signing is optional unless upload credentials exist
```

## Debug-only local pack installation

Debug builds contain a second launcher entry named **RankOffline pack installer
(debug)**. It opens Android's Storage Access Framework and accepts a local
`.pack` document. The selected document is staged in cache with a 2 GiB bound,
validated by the runtime ZIP/manifest reader and installed through
`PackManager.installLocalArchive`, including the Room import and READY commit.
No pack is bundled in the APK and the tool never writes directly to
`files/packs/`. The activity and its strings live exclusively in
`app/src/debug/`, so they are absent from release builds.

For emulator testing, drag `build/pack-output/pack-N.pack` onto the emulator,
open the debug installer, select the document and wait for the READY message.
Return to RankOffline and select the newly installed pack. Reopen the main app
after each first install to exercise persisted discovery and recovery.

Room schema generation must leave reviewed JSON in `app/schemas/`. Test API 26
and API 37, process death during WorkManager/restore, every launcher alias,
FileProvider shares, All READY gating, pagination, both Share dimensions and
Android 17 large-screen behavior.

## Android 17 target review

The app does not use local-network discovery, SMS/OTP access, background audio,
native dynamic loading, reflection that mutates static-final fields, or locked
orientation/aspect ratios. It therefore declares no local-network/SMS/audio
permission and relies on platform ECH/certificate-transparency defaults. The
new memory limits, adaptive large-screen behavior and foreground data-sync work
still require API 37 emulator/device testing; this review is not a runtime
certification.

The bundled wrapper JAR came from the reference project because network access
was unavailable during reconstruction. After the first successful sync, run
the wrapper task twice and verify its SHA-256 against Gradle's official 9.8.2
wrapper checksum before release.
