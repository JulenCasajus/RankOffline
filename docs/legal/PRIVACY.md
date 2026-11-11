# Privacy

RankOffline stores ratings, notes, preferences, installed catalogs and covers in Android
app-private storage. The application has no account system, advertising SDK, analytics
SDK or automatic rating synchronization.

The app uses HTTPS to retrieve its configured package index and user-requested package
archives. Cover downloads contact the sources declared by an installed pack and can
occur as part of that pack's initial setup or after a user requests more covers.
Provider requests may include public catalog identifiers needed to retrieve an image.
Personal ratings and notes are not sent to those providers.

Android cloud backup and device-to-device transfer are disabled. Users can explicitly
export or import personal JSON with the system picker and can share a rendered ranking
image. A share operation makes the selected content available to the receiving app
through a temporary file and a scoped URI grant.

Pack publishers provide their own dataset and cover-source attribution. Network services
contacted for a pack may have independent privacy policies. See the installed pack's
attribution before using its online cover sources.
