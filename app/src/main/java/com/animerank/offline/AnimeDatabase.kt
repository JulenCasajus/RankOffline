package com.animerank.offline

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class AnimeDatabase(private val context: Context) : SQLiteOpenHelper(context, "anime_rank.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE anime(
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                mal_id INTEGER,
                anilist_id INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_anime_title ON anime(title COLLATE NOCASE)")
        db.execSQL("""
            CREATE TABLE rating(
                anime_id TEXT PRIMARY KEY,
                writing REAL,
                characters REAL,
                engagement REAL,
                visuals REAL,
                worldbuilding REAL,
                personal_taste REAL NOT NULL DEFAULT 0,
                base_score REAL,
                final_score REAL,
                notes TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'Completed',
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(anime_id) REFERENCES anime(id) ON DELETE CASCADE
            )
        """.trimIndent())
        seedAnime(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun seedAnime(db: SQLiteDatabase) {
        val jsonText = context.assets.open("anime_catalog.json").use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        }
        val data = JSONArray(jsonText)
        db.beginTransaction()
        try {
            val stmt = db.compileStatement("INSERT OR IGNORE INTO anime(id,title,mal_id,anilist_id) VALUES(?,?,?,?)")
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                stmt.clearBindings()
                stmt.bindString(1, item.getString("id"))
                stmt.bindString(2, item.getString("title"))
                if (item.has("malId") && !item.isNull("malId")) stmt.bindLong(3, item.getLong("malId")) else stmt.bindNull(3)
                if (item.has("anilistId") && !item.isNull("anilistId")) stmt.bindLong(4, item.getLong("anilistId")) else stmt.bindNull(4)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun animeCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM anime", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

    fun searchAnime(query: String, rankedOnly: Boolean = false, limit: Int = 200): List<Anime> {
        val db = readableDatabase
        val q = query.trim()
        val where = if (q.isEmpty()) "" else "WHERE a.title LIKE ? COLLATE NOCASE"
        val ranked = if (rankedOnly) {(if (where.isEmpty()) "WHERE" else "AND") + " r.final_score IS NOT NULL"} else ""
        val order = if (rankedOnly) "r.final_score DESC, a.title COLLATE NOCASE" else "a.title COLLATE NOCASE"
        val sql = """
            SELECT a.id,a.title,a.mal_id,a.anilist_id,r.final_score,r.base_score,r.personal_taste,r.status
            FROM anime a LEFT JOIN rating r ON a.id=r.anime_id
            $where $ranked
            ORDER BY $order LIMIT $limit
        """.trimIndent()
        val args = if (q.isEmpty()) emptyArray() else arrayOf("%$q%")
        return db.rawQuery(sql, args).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Anime(
                        id = c.getString(0),
                        title = c.getString(1),
                        malId = if (c.isNull(2)) null else c.getLong(2),
                        anilistId = if (c.isNull(3)) null else c.getLong(3),
                        finalScore = if (c.isNull(4)) null else c.getDouble(4),
                        baseScore = if (c.isNull(5)) null else c.getDouble(5),
                        personalTaste = if (c.isNull(6)) null else c.getDouble(6),
                        status = if (c.isNull(7)) null else c.getString(7)
                    ))
                }
            }
        }
    }

    fun loadRating(animeId: String): RatingDraft {
        return readableDatabase.rawQuery(
            "SELECT writing,characters,engagement,visuals,worldbuilding,personal_taste,notes,status FROM rating WHERE anime_id=?",
            arrayOf(animeId)
        ).use { c ->
            if (!c.moveToFirst()) return@use RatingDraft()
            val scores = mapOf(
                "writing" to if (c.isNull(0)) null else c.getDouble(0),
                "characters" to if (c.isNull(1)) null else c.getDouble(1),
                "engagement" to if (c.isNull(2)) null else c.getDouble(2),
                "visuals" to if (c.isNull(3)) null else c.getDouble(3),
                "worldbuilding" to if (c.isNull(4)) null else c.getDouble(4)
            )
            RatingDraft(scores, c.getDouble(5), c.getString(6), c.getString(7))
        }
    }

    fun saveRating(animeId: String, draft: RatingDraft) {
        val values = ContentValues().apply {
            put("anime_id", animeId)
            categorySpecs.forEach { spec ->
                val score = draft.categoryScores[spec.key]
                if (score == null) putNull(spec.key) else put(spec.key, score)
            }
            put("personal_taste", draft.personalTaste)
            val base = draft.baseScore()
            val final = draft.finalScore()
            if (base == null) putNull("base_score") else put("base_score", base)
            if (final == null) putNull("final_score") else put("final_score", final)
            put("notes", draft.notes)
            put("status", draft.status)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("rating", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteRating(animeId: String) {
        writableDatabase.delete("rating", "anime_id=?", arrayOf(animeId))
    }

    fun exportRatings(): String {
        val arr = JSONArray()
        readableDatabase.rawQuery("SELECT * FROM rating ORDER BY updated_at DESC", null).use { c ->
            val names = c.columnNames
            while (c.moveToNext()) {
                val obj = JSONObject()
                for (i in names.indices) {
                    when (c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> obj.put(names[i], JSONObject.NULL)
                        android.database.Cursor.FIELD_TYPE_INTEGER -> obj.put(names[i], c.getLong(i))
                        android.database.Cursor.FIELD_TYPE_FLOAT -> obj.put(names[i], c.getDouble(i))
                        else -> obj.put(names[i], c.getString(i))
                    }
                }
                arr.put(obj)
            }
        }
        return JSONObject().put("format", 1).put("ratings", arr).toString(2)
    }
}
