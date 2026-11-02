# Declarative Anime cover proposal

## Current evidence

The Anime source contains 32,209 legacy references such as
`anime:v1|anilist=142051|mal=51478`: 30,741 include a MAL identity and 22,557
include an AniList identity. These values are identifiers, not image URLs or
stable CDN paths. The current universal resolver intentionally performs no
provider API lookup; it accepts only manifest templates or a structured cover
reference containing already resolved HTTPS URLs.

Consequently, neither an AniList nor a MAL image URL can be derived safely from
the current catalog alone. Reintroducing the old provider-specific resolver in
the APK would violate the universal-engine boundary and is not proposed.

## Proposed data-only solution

Resolve cover URLs at pack-generation time from a separate, versioned mapping
input whose provenance and redistribution terms have been reviewed. The Anime
adapter would translate each available mapping into the existing structured
reference syntax:

```json
{
  "schemaVersion": 1,
  "sourceId": "anime_cover_mapping",
  "identity": "mal:51478",
  "urls": ["https://approved-image-host.example/path/image.jpg"]
}
```

The manifest would declare `anime_cover_mapping` in `coverSources`, constrain
every URL with an explicit HTTPS `allowedHosts` list, connect it to an
`attribution.sources` entry and enable the existing `covers` capability. The
runtime then uses `DeclarativePackCoverProvider` unchanged. The mapping input,
its retrieval tool and its cache stay in the pack-building layer, never in the
APK.

## Acceptance requirements before implementation

1. Select an authoritative source that supplies direct, stable image URLs or a
   redistributable URL mapping for both current and future catalog snapshots.
2. Record provider terms, attribution, rate limits, snapshot date and mapping
   checksum; verify that storing/distributing URLs is permitted.
3. Use only the hosts explicitly declared in the pack and reject redirects to
   other hosts through the existing downloader policy.
4. Preserve item IDs and group relations; missing mappings remain missing
   covers rather than being matched approximately by title.
5. Prefer one physical downloaded image per logical source. Group fallback
   continues to borrow the representative image without copying it.
6. Validate a deliberately malformed host, URL, identity and oversized
   fallback list with both Python and Kotlin validators.
7. Compare mapping coverage and failed-download rate before enabling
   `capabilities.covers` for the official Anime pack.

No mapping provider has been approved and no Anime cover behavior is changed in
v0.2.2 by this document.
