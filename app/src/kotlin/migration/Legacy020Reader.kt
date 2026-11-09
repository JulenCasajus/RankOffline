package org.rankoffline.app

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Reads only user-owned rows from the published v0.2.0 database. Never changes that database. */
internal class Legacy020Reader(private val file: File) {
    private data class Key(val pack: Long, val id: String)
    private data class Row(
        val key: Key, val additive: Double, val quality: Double?, val final: Double?,
        val notes: String, val status: String, val updated: Long, val profile: String?,
        val legacy: Boolean
    )
    private data class Score(val category: String, val value: Double?, val applicable: Boolean)

    fun read(): PersonalRestoreData = SQLiteDatabase.openDatabase(
        file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
    ).use { db ->
        db.rawQuery("PRAGMA user_version", null).use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 14) { "Expected v0.2.0 database schema 14" }
        }
        val items = parents(db, "media_rating", "rating", "media_id", "anime_id")
        val groups = parents(db, "media_group_rating", "group_rating", "group_id", "group_id")
        val itemScores = scores(db, "media_rating_score", "rating_score", "media_id", "anime_id", items)
        val groupScores = scores(db, "media_group_rating_score", "group_rating_score", "group_id", "group_id", groups)
        PersonalRestoreData(
            ratings = items.values.map { row -> MediaRatingEntity(row.key.pack, row.key.id,
                row.additive, row.quality, row.final, row.notes, row.status, row.updated, row.profile) },
            ratingScores = itemScores.flatMap { (key, values) -> values.map { score ->
                MediaRatingScoreEntity(key.pack, key.id, score.category, score.value, score.applicable)
            } },
            groupRatings = groups.values.map { row -> MediaGroupRatingEntity(row.key.pack, row.key.id,
                row.additive, row.quality, row.final, row.notes, row.status, row.updated, row.profile) },
            groupRatingScores = groupScores.flatMap { (key, values) -> values.map { score ->
                MediaGroupRatingScoreEntity(key.pack, key.id, score.category, score.value, score.applicable)
            } }
        ).also(::validateRestore)
    }

    private fun parents(
        db: SQLiteDatabase, common: String, legacy: String, idColumn: String, oldIdColumn: String
    ): Map<Key, Row> {
        val result = linkedMapOf<Key, Row>()
        listOf(false, true).forEach { old ->
            val table = if (old) legacy else common
            val id = if (old) oldIdColumn else idColumn
            val pack = if (old) "'animes'" else "pack_id"
            val sql = "SELECT $pack AS pack_id,$id AS entry_id,additive_score,quality_score," +
                "final_score,notes,status,updated_at,profile_id FROM $table"
            db.rawQuery(sql, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val key = Key(packId(cursor.getString(0)), canonicalId(cursor.getString(0), cursor.getString(1)))
                    val row = Row(key, cursor.getDouble(2), cursor.doubleOrNull(3), cursor.doubleOrNull(4),
                        cursor.getString(5), cursor.getString(6), cursor.getLong(7), cursor.stringOrNull(8), old)
                    if (result[key]?.updated?.let { it < row.updated } != false) result[key] = row
                }
            }
        }
        return result
    }

    private fun scores(
        db: SQLiteDatabase, common: String, legacy: String, idColumn: String,
        oldIdColumn: String, parents: Map<Key, Row>
    ): Map<Key, List<Score>> {
        val result = mutableMapOf<Key, MutableList<Score>>()
        listOf(false, true).forEach { old ->
            val table = if (old) legacy else common
            val id = if (old) oldIdColumn else idColumn
            val pack = if (old) "'animes'" else "pack_id"
            db.rawQuery("SELECT $pack AS pack_id,$id AS entry_id,category_id,score,applicable FROM $table", null)
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        val key = Key(packId(cursor.getString(0)), canonicalId(cursor.getString(0), cursor.getString(1)))
                        if (parents[key]?.legacy == old) result.getOrPut(key) { mutableListOf() }
                            .add(Score(cursor.getString(2), cursor.doubleOrNull(3), cursor.getInt(4) != 0))
                    }
                }
        }
        return result
    }

    private fun packId(value: String): Long = when (value) {
        "animes" -> 1L; "mangas" -> 2L; "movies" -> 3L; "series" -> 4L; "books" -> 5L
        else -> error("Unknown v0.2.0 pack ID: $value")
    }

    /** Matches the published Anime pack's idReplacements; other official IDs are unchanged. */
    private fun canonicalId(pack: String, value: String): String = if (pack == "animes") {
        value.replace('+', '-').replace("~", "").replace("!", "")
            .replace("(", "").replace(")", "").replace(",", "")
    } else value

    private fun Cursor.doubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)
    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
}
