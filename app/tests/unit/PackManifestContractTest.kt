package org.rankoffline.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PackManifestContractTest {
    @Test fun validSchemaTwoManifestPreservesGenericDeclarations() {
        val manifest = TestFixtures.manifest()
        assertEquals(2, manifest.schemaVersion)
        assertEquals(PackId(11), manifest.packId)
        assertEquals(listOf("genre"), manifest.filters.map { it.id })
        assertEquals("fixture_cover", manifest.coverSources.single().id)
    }

    @Test fun physicalPackIdentityMustBeCanonicalPositiveInteger() {
        assertThrows(IllegalArgumentException::class.java) { PackId(0) }
        assertThrows(IllegalArgumentException::class.java) { PackId.parse("01") }
        assertThrows(IllegalArgumentException::class.java) {
            PackManifestParser.parse(JSONObject(TestFixtures.manifestJson()).put("packId", "11"))
        }
        assertEquals(ViewingScope.All, ViewingScope.fromStored(0L))
    }

    @Test fun manifestRejectsUnknownKeysAndUnsupportedSchema() {
        assertThrows(Exception::class.java) {
            PackManifestParser.parse(JSONObject(TestFixtures.manifestJson()).put("plugin", true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PackManifestParser.parse(JSONObject(TestFixtures.manifestJson()).put("schemaVersion", 1))
        }
    }

    @Test fun executableTraversalAndAbsolutePayloadsAreRejected() {
        listOf("../escape", "/absolute", "catalog/../../escape", "metadata/code.jar", "plugin.dex")
            .forEach { path ->
                assertThrows(IllegalArgumentException::class.java) {
                    PackManifestParser.validateDataPath(path)
                }
            }
        PackManifestParser.validateDataPath("catalog/items.jsonl")
    }

    @Test fun filtersMustReferenceCompatibleDeclaredFields() {
        val root = JSONObject(TestFixtures.manifestJson())
        root.getJSONArray("filters").getJSONObject(0).put("field", "missing")
        assertThrows(IllegalArgumentException::class.java) { PackManifestParser.parse(root) }
    }

    @Test fun unsafeCoverTemplatesAndHostsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            validatePackCoverUrlTemplate("http://images.example.com/{reference}.jpg",
                setOf("images.example.com"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatePackCoverUrlTemplate("https://evil.example/{reference}.jpg",
                setOf("images.example.com"))
        }
    }

    @Test fun shareBackgroundMustBeIntegrityDeclared() {
        val root = JSONObject(TestFixtures.manifestJson())
        root.getJSONObject("presentation").getJSONObject("shareBackgrounds")
            .put("topSeven", "metadata/share.png")
        assertThrows(IllegalArgumentException::class.java) { PackManifestParser.parse(root) }
    }

    @Test fun appCompatibilityAcceptsDebugSuffixButNotOlderCoreVersion() {
        assertTrue(isCompatiblePackVersion("0.2.2", "0.2.2-debug"))
        assertTrue(isCompatiblePackVersion("0.2.2", "0.2.2-debug.2"))
        assertFalse(isCompatiblePackVersion("0.2.2", "0.2.2-beta"))
        assertTrue(isCompatiblePackVersion("0.2.2", "0.3.0"))
        assertFalse(isCompatiblePackVersion("0.2.2", "0.2.1"))
        assertFalse(isCompatiblePackVersion("invalid", "0.2.2"))
    }

    @Test fun externalAttributionLinksMustBePublicHttpsNames() {
        assertTrue(AttributionUrlPolicy.isSafeExternalHttpsUrl("https://example.com/legal"))
        assertFalse(AttributionUrlPolicy.isSafeExternalHttpsUrl("http://example.com"))
        assertFalse(AttributionUrlPolicy.isSafeExternalHttpsUrl("https://localhost/path"))
        assertFalse(AttributionUrlPolicy.isSafeExternalHttpsUrl("https://127.0.0.1/path"))
    }
}
