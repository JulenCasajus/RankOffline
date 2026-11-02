# Releasing

Release output is an Android App Bundle. The repository contains no keystore,
password, token or Play Console credential.

1. Confirm `versionCode` is greater than every Play artifact and update
   `versionName`/CHANGELOG.
2. Put `RANKOFFLINE_UPLOAD_*` values in environment variables or the owner's
   `~/.gradle/gradle.properties`; see `gradle/upload-signing.properties.example`.
3. Run `scripts/build_bundle.sh` on a clean, reviewed checkout.
4. Inspect lint/tests, generated Room schema, R8 mapping and the signed AAB.
5. Use bundletool/Play pre-launch reports to test API 26–37, 16 KB page-size
   devices, large screens, notification denial, backup exclusion and sharing.
6. Upload manually and enroll/retain the upload key under Play App Signing.

AGP 9.5 provides modern 16 KB ZIP alignment. The declared dependencies are
Java/Kotlin libraries; nevertheless inspect the final bundle for unexpected
native `.so` files. Privacy/data-safety declarations, screenshots, content
rating, store listing and account actions require the owner.

Never publish the debug application ID (`org.rankoffline.app.debug`) or generate
a production key in the repository.
