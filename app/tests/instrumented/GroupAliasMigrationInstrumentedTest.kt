package org.rankoffline.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupAliasMigrationInstrumentedTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), RankOfflineDatabase::class.java
    )

    @Test fun versionOneRatingsSurviveGroupAliasMigration() {
        val name = "group-alias-migration-test.db"
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO media_item(pack_id,id,title) VALUES(1,'item','Item')")
            execSQL("INSERT INTO media_group(pack_id,id,group_type,display_title) " +
                "VALUES(1,'group','relations','Group')")
            execSQL("INSERT INTO media_rating(pack_id,media_id,additive_score,quality_score," +
                "final_score,notes,status,updated_at,profile_id) " +
                "VALUES(1,'item',0.5,7,7.5,'private note','done',1,'general')")
            execSQL("INSERT INTO media_rating_score(pack_id,media_id,category_id,score,applicable) " +
                "VALUES(1,'item','overall',7,1)")
            execSQL("INSERT INTO media_group_rating(pack_id,group_id,additive_score,quality_score," +
                "final_score,notes,status,updated_at,profile_id) " +
                "VALUES(1,'group',0.5,8,8.5,'group note','done',1,'general')")
            execSQL("INSERT INTO media_group_rating_score(pack_id,group_id,category_id,score,applicable) " +
                "VALUES(1,'group','overall',8,1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 2, true, RankOfflineDatabase.MIGRATION_1_2).use { db ->
            fun scalar(sql: String): String = db.query(sql).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
            fun numeric(sql: String): Double = db.query(sql).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getDouble(0)
            }
            assertEquals("private note", scalar("SELECT notes FROM media_rating WHERE pack_id=1"))
            assertEquals("group note", scalar("SELECT notes FROM media_group_rating WHERE pack_id=1"))
            assertEquals(7.0, numeric("SELECT score FROM media_rating_score WHERE pack_id=1"), 0.0)
            assertEquals(8.0, numeric("SELECT score FROM media_group_rating_score WHERE pack_id=1"), 0.0)
            assertEquals("0", scalar("SELECT COUNT(*) FROM media_group_alias"))
        }
    }
}
