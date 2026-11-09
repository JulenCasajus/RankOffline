package org.rankoffline.app

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Prevents SQLite from multiplying catalog rows by every matching attribute value. */
@RunWith(AndroidJUnit4::class)
class CatalogFilterQueryPlanInstrumentedTest {
    @Test fun itemAndGroupFiltersProbeAttributesByMediaIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, RankOfflineDatabase::class.java).build()
        try {
            val manifest = TestFixtures.manifest(merge = true)
            val filters = CatalogFilters(mapOf(
                "genre" to CatalogFilterValue.Choices(setOf("drama")),
                "year" to CatalogFilterValue.Choices(setOf("2020"))))
            val query = CatalogQueries.catalog(manifest, SearchUiState(filters = filters), true, 200)
            val explain = object : SupportSQLiteQuery {
                override val sql: String = "EXPLAIN QUERY PLAN ${query.sql}"
                override val argCount: Int = query.argCount
                override fun bindTo(statement: SupportSQLiteProgram) = query.bindTo(statement)
            }
            val details = database.openHelper.readableDatabase.query(explain).use { cursor ->
                buildList {
                    val column = cursor.getColumnIndexOrThrow("detail")
                    while (cursor.moveToNext()) add(cursor.getString(column))
                }
            }
            val indexedProbes = details.count { it.contains("media_item_attribute_1") &&
                it.contains("media_id=?") && it.contains("field_id=?") }
            assertTrue("Both item and group filters must use indexed media-ID probes: $details",
                indexedProbes >= 2)
        } finally { database.close() }
    }
}
