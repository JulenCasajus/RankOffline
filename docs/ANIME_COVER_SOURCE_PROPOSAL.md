# Declarative Anime cover sources

## Implemented v0.2.2 contract

The recovered Anime catalog contains 32,209 remote references. Generation
converts their historical `anime:v1|...` prefix to the generic, versioned form
`cover:v1|anilist=142051|mal=51478`; IDs and relations are otherwise unchanged.

The APK contains no Anime, AniList or Jikan branch. Each metadata lookup is a
strict `coverSources[].metadataRequest` recipe declared by the pack:

- a reference key present in `cover:v1` data;
- `GET` or `POST_JSON`, one fixed HTTPS host and a bounded body template;
- ordered JSON paths from which image URLs may be read;
- a request interval and an explicit allow-list of image hosts.

The official Anime pack declares AniList first and Jikan second. RankOffline
resolves one recipe at a time, downloads the returned image through the normal
secure cover downloader, and uses Jikan only when the AniList route does not
produce a usable image. HTTP 401/403/408/425/429 and server failures remain
retryable; 404/410 is a missing candidate, not a network error. Cancellation,
image-size/type validation and the three download passes are shared with every
other pack.

AniList is conservatively spaced at 2.1 seconds while its documented degraded
limit is 30 requests/minute. Jikan is spaced at 1.1 seconds, below its published
60 requests/minute limit. `Retry-After` can extend the delay. These are external
policies and must be reviewed before a public release.

## Security boundaries

The manifest parser and independent Python validator reject dynamic hosts,
cleartext URLs, unsupported placeholders/methods, malformed JSON bodies,
unbounded response paths and undeclared image hosts. Responses are limited to
64 KiB. The recipe cannot add headers, execute code, choose filesystem paths or
bypass the existing image downloader.

The package still requires real-device/emulator checks for provider outages,
rate limiting, cancellation latency and image-host redirects. Provider terms
and redistribution/attribution wording also require owner review before public
distribution.
