package com.haruhi.haruhibox.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库迁移。
 *
 * **规则：每次改动 `@Database(entities = ...)` 就必须同时**
 * 1. 把 `@Database(version = ...)` 加一；
 * 2. 在 [ALL] 里补一条迁移。
 *
 * 不这么做的后果不是"少个字段"，而是**应用一启动就抛**
 * `IllegalStateException: Room cannot verify the data integrity`——
 * 因为 Room 会比对编译期生成的 identity hash 与运行期库里的 `room_master_table`，
 * 对不上就直接崩。P2 加 `t_jm_recent_album` 时漏了这一步，覆盖安装才暴露
 * （全新安装不会有这个问题，所以只跑新装很容易漏掉）。
 *
 * 刻意**不使用** `fallbackToDestructiveMigration()`：用户的下载记录、
 * 章节计数与书库是真实资产，一次 destructive 迁移就是数据全丢。
 */
object Migrations {

    /**
     * v1 → v2：新增最近浏览表 `t_jm_recent_album`。
     *
     * 字段与 `t_jm_album` 一致再加 `viewed_time`；表名与列名必须与
     * `JmRecentAlbumEntity` 的 `@Entity` / `@ColumnInfo` 完全对应，
     * 否则 Room 的迁移后校验一样会失败。
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `t_jm_recent_album` (
                    `id` INTEGER NOT NULL,
                    `name` TEXT,
                    `album_folder_name` TEXT,
                    `images` TEXT,
                    `add_time` TEXT,
                    `description` TEXT,
                    `total_views` TEXT,
                    `likes` TEXT,
                    `series` TEXT,
                    `series_id` TEXT,
                    `comment_total` TEXT,
                    `author` TEXT,
                    `tags` TEXT,
                    `works` TEXT,
                    `actors` TEXT,
                    `related_list` TEXT,
                    `liked` INTEGER,
                    `is_favorite` INTEGER,
                    `is_aids` INTEGER,
                    `price` TEXT,
                    `purchased` TEXT,
                    `raw` TEXT,
                    `create_time` TEXT,
                    `modify_time` TEXT,
                    `viewed_time` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_t_jm_recent_album_viewed_time` " +
                    "ON `t_jm_recent_album` (`viewed_time`)",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
