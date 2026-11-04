package org.rankoffline.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogFormatContractTest {
    private val fields = mapOf(
        "genre" to CatalogFieldSpec("genre", CatalogFieldType.CHOICE, true, setOf("drama", "comedy")),
        "year" to CatalogFieldSpec("year", CatalogFieldType.INTEGER),
        "date" to CatalogFieldSpec("date", CatalogFieldType.DATE)
    )

    @Test fun itemRoundTripPreservesAliasesAndDynamicAttributes() {
        val original = MediaCatalogRecord("item:1", "Title", "provider", "remote-1", 2.0, 1,
            "cover", mapOf("genre" to JsonArray(listOf(JsonPrimitive("drama"))),
                "year" to JsonPrimitive(2025)), listOf(MediaCatalogAlias("Alias", "en", "title")))
        assertEquals(original, parseMediaCatalogRecord(encodeMediaCatalogRecord(original), fields))
    }

    @Test fun unknownRecordKeysAreRejected() {
        assertThrows(Exception::class.java) {
            parseMediaCatalogRecord("""{"id":"x","title":"X","extra":true}""", fields)
        }
    }

    @Test fun unknownFieldsWrongCardinalityAndChoicesAreRejected() {
        listOf(
            MediaCatalogRecord("x", "X", attributes = mapOf("unknown" to JsonPrimitive("x"))),
            MediaCatalogRecord("x", "X", attributes = mapOf("year" to JsonArray(listOf(JsonPrimitive(1))))),
            MediaCatalogRecord("x", "X", attributes = mapOf("genre" to JsonPrimitive("horror")))
        ).forEach { assertThrows(IllegalArgumentException::class.java) { validateMediaCatalogRecord(it, fields) } }
    }

    @Test fun datesPopularityAndAliasesAreStrictlyValidated() {
        assertThrows(IllegalArgumentException::class.java) {
            validateMediaCatalogRecord(MediaCatalogRecord("x", "X",
                attributes = mapOf("date" to JsonPrimitive("2025-02-30"))), fields)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateMediaCatalogRecord(MediaCatalogRecord("x", "X", popularity = Double.NaN), fields)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateMediaCatalogRecord(MediaCatalogRecord("x", "X",
                aliases = listOf(MediaCatalogAlias("A"), MediaCatalogAlias("A"))), fields)
        }
    }

    @Test fun entityRowsAlwaysCarryContainingPackIdentity() {
        val rows = MediaCatalogRecord("shared", "X", attributes = mapOf("year" to JsonPrimitive(2025)))
            .toEntityRows(PackId(99), fields)
        assertEquals(99L, rows.item.packId)
        assertTrue(rows.attributes.all { it.packId == 99L && it.mediaId == "shared" })
    }

    @Test fun validGroupPreservesOrderedMembersAndRepresentative() {
        val manifest = TestFixtures.manifest(merge = true)
        val record = MediaGroupCatalogRecord("group:1",
            listOf(MediaGroupCatalogMember("item:1"), MediaGroupCatalogMember("item:2")),
            "Group", "relations", "item:2")
        val parsed = parseMediaGroupCatalogRecord(encodeMediaGroupCatalogRecord(record), manifest)
        assertEquals(record, parsed)
        val rows = parsed.toEntityRows(manifest)
        assertEquals(listOf(0, 1), rows.members.map { it.position })
        assertEquals("item:2", rows.representative?.mediaId)
    }

    @Test fun duplicateGroupMembersAndUndeclaredGroupsAreRejected() {
        val duplicate = MediaGroupCatalogRecord("g",
            listOf(MediaGroupCatalogMember("x"), MediaGroupCatalogMember("x")))
        assertThrows(IllegalArgumentException::class.java) {
            validateMediaGroupCatalogRecord(duplicate, TestFixtures.manifest(merge = true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateMediaGroupCatalogRecord(MediaGroupCatalogRecord("g", listOf(MediaGroupCatalogMember("x"))),
                TestFixtures.manifest())
        }
    }
}
