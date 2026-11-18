package org.rankoffline.app

import java.math.BigInteger
import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PackIdentityTest {
    private fun manifest(id: Long, slug: String, revision: String): PackManifest =
        PackManifestParser.parse(JSONObject(TestFixtures.manifestJson(id))
            .put("slug", slug).put("version", "$slug-v$revision"))

    private fun remote(manifest: PackManifest, official: Boolean = false) = PackDownloadDescriptor(
        manifest, URI("https://example.com/pack-${manifest.packId.value}.pack"), 100,
        "a".repeat(64), if (official) "rankoffline-official-${manifest.packId.value}" else "test")

    @Test fun suffixUsesLastMarkerAndSupportsLongRevisions() {
        assertEquals(PackRevision("animes-v7", BigInteger.ONE), parsePackRevision("animes-v7-v1"))
        assertEquals(PackRevision("animeeee------", BigInteger.ONE),
            parsePackRevision("animeeee-------v1"))
        assertEquals(PackRevision("animes-2027-02-10", BigInteger.valueOf(25)),
            parsePackRevision("animes-2027-02-10-v25"))
        assertEquals(BigInteger("123456789012345678901234567890"),
            parsePackRevision("animes_pablo-v123456789012345678901234567890")?.number)
        listOf("Animes-v1", "animes-v0", "animes-v01", "animes-v", "-v1", "animes-v1x")
            .forEach { assertEquals(null, parsePackRevision(it)) }
    }

    @Test fun updatesRequireSameIdAndSlugAndGreaterRevision() {
        val old = manifest(11, "animes", "5")
        assertTrue(newerPackRevision(old, remote(manifest(11, "animes", "6"))))
        assertFalse(newerPackRevision(old, remote(manifest(11, "animes", "5"))))
        assertFalse(newerPackRevision(old, remote(manifest(11, "animes", "4"))))
        assertFalse(newerPackRevision(old, remote(manifest(11, "animes_pablo", "6"))))
        assertFalse(newerPackRevision(old, remote(manifest(12, "animes", "6"))))
        assertTrue(newerPackRevision(manifest(11, "animes_pablo", "1"),
            remote(manifest(11, "animes_pablo", "2"))))
        assertTrue(newerPackRevision(manifest(11, "animes-2027-02-10", "1"),
            remote(manifest(11, "animes-2027-02-10", "2"))))
    }

    @Test fun samePublicNameDoesNotMergeDifferentSlugs() {
        val one = remote(manifest(11, "animes", "1"))
        val two = remote(manifest(12, "animes_pablo", "1"))
        val registry = PackSourceRegistry()
        registry.replace(listOf(one, two))
        assertEquals(2, registry.descriptors.value.size)
        assertThrows(IllegalArgumentException::class.java) {
            registry.replace(listOf(one, remote(manifest(12, "animes", "1"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.replace(listOf(remote(manifest(11, "renamed", "2")), two))
        }
    }

    @Test fun oldOfficialVersionsMapOnlyForTheVerifiedIdentity() {
        val previous = listOf(
            "anime-69005e06b4a92ee6caa4c57efcb5951086a2ffaa6b8ade997276f301908b6223-v5",
            "mangadex-2026-10-16-v3", "tmdb-movies-2026-10-16-v3",
            "tmdb-series-2026-10-16-v3", "openlibrary-2026-09-30-readinglog-top-50000-v4")
        val slugs = listOf("animes", "mangas", "movies", "series", "books")
        previous.forEachIndexed { index, version ->
            val id = (index + 1).toLong()
            val old = PackManifestParser.parse(JSONObject(TestFixtures.manifestJson(id))
                .put("version", version))
            val next = manifest(id, slugs[index], if (index == 0) "6" else
                if (index == 4) "5" else "4")
            assertTrue(newerPackRevision(old, remote(next, official = true)))
            assertFalse(newerPackRevision(old, remote(next, official = false)))
            if (id == 5L) assertFalse(newerPackRevision(old,
                remote(manifest(id, "books", "4"), official = true)))
        }
    }

    @Test fun manifestRejectsContradictorySlug() {
        assertThrows(IllegalArgumentException::class.java) {
            PackManifestParser.parse(JSONObject(TestFixtures.manifestJson())
                .put("slug", "animes").put("version", "mangas-v1"))
        }
    }
}
