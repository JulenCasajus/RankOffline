# Asset provenance and release gate

The following visual files came from the v0.2.1 reference archive because the
current v0.2.2 Kotlin UI refers to them:

- `drawable-nodpi/rankoffline_*_source.png` launcher previews and icons;
- `drawable/rankoffline_*_foreground.xml` and matching adaptive icons;
- `drawable-nodpi/onboarding_*.webp`;
- `drawable-nodpi/rankoffline_podium_{1,2,3}.png`.

The owner supplied the two default Share images and the ten
package-specific Share source images during FASE 9. The package images are
stored as high-quality WebP under `scripts/pack-assets/pack-<id>/`; every Top 7
asset is 1080×1920 and every Top 10 asset is 1920×1080. The defaults are stored
as `raw/default_share_top*.webp`; original PNG filenames were used only during
ingestion and are not embedded in the APK.

Provider logos and catalog artwork from the legacy archive were not imported.
Before publication, the owner must produce documentary rights/provenance for
every retained or owner-supplied asset (especially character-named launcher variants) or replace
it with original cleared branding. This is a release blocker, not a claim that
the current assets are distributable.
