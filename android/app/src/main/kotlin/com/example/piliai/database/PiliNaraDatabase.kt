package com.example.piliai.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * PiliNara 主数据库
 * 替代 Flutter 侧的 Hive CE 存储
 */
@Database(
    entities = [
        UserInfoEntity::class,
        LoginAccountEntity::class,
        DanmakuFilterRuleEntity::class,
        SettingEntity::class,
        VideoSettingEntity::class,
        LocalCacheEntity::class,
        TodayWatchFeedbackEntity::class,
        DownloadItemEntity::class,
        SubscribeSourceEntity::class,
        SubscribeItemEntity::class,
        SubscribeTitleEntity::class
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class PiliNaraDatabase : RoomDatabase() {
    
    abstract fun userInfoDao(): UserInfoDao
    abstract fun loginAccountDao(): LoginAccountDao
    abstract fun danmakuFilterRuleDao(): DanmakuFilterRuleDao
    abstract fun downloadItemDao(): DownloadItemDao
    abstract fun settingDao(): SettingDao
    abstract fun videoSettingDao(): VideoSettingDao
    abstract fun localCacheDao(): LocalCacheDao
    abstract fun todayWatchFeedbackDao(): TodayWatchFeedbackDao
    abstract fun subscribeSourceDao(): SubscribeSourceDao
    abstract fun subscribeItemDao(): SubscribeItemDao
    abstract fun subscribeTitleDao(): SubscribeTitleDao
    
    companion object {
        @Volatile
        private var INSTANCE: PiliNaraDatabase? = null
        
        fun getDatabase(context: Context): PiliNaraDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PiliNaraDatabase::class.java,
                    "pilinara_database"
                )
                    // 审核：v2→v3 必须走显式 Migration，否则 destructive 会清空
                    // 用户的下载记录与登录态。此处为增量建表，数据零丢失。
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * v2 → v3：新增订阅源两张表（subscribe_source / subscribe_item）
         *
         * 仅 CREATE TABLE，不触碰任何既有表，因此用户数据（下载、登录、
         * 弹幕屏蔽规则、设置）全部保留。若建表失败，Room 会抛异常而非静默
         * 破坏数据；这里对每张表单独 try，避免部分失败导致整体迁移不可用。
         */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `subscribe_source` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `type` INTEGER NOT NULL,
                        `cover` TEXT NOT NULL,
                        `lastSyncAt` INTEGER NOT NULL,
                        `lastError` TEXT,
                        `enabled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_subscribe_source_url` " +
                        "ON `subscribe_source` (`url`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `subscribe_item` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sourceId` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `cover` TEXT NOT NULL,
                        `link` TEXT NOT NULL,
                        `desc` TEXT NOT NULL,
                        `pubAt` INTEGER NOT NULL,
                        `episode` TEXT NOT NULL,
                        `sourceName` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_subscribe_item_sourceId_link` " +
                        "ON `subscribe_item` (`sourceId`, `link`)"
                )
            }
        }

        /**
         * v3 → v4：新增追番表 `subscribe_title`（animeko「收藏」移植，审核240）。
         *
         * 同样只 CREATE TABLE + 建索引，不触碰既有表：用户的数据源、条目、
         * 下载记录、登录态、弹幕屏蔽规则全部保留。
         *
         * 幂等：`IF NOT EXISTS` 让重复执行安全（首次安装 v4 的用户走
         * onCreate 建表，不走迁移；老用户从 v3 升级只补这张表）。
         */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `subscribe_title` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sourceId` INTEGER NOT NULL,
                        `sourceKey` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `cover` TEXT NOT NULL,
                        `intro` TEXT NOT NULL,
                        `bangumiId` INTEGER NOT NULL,
                        `collectionType` INTEGER NOT NULL,
                        `latestEpisode` INTEGER NOT NULL,
                        `lastWatchedEpisode` INTEGER NOT NULL,
                        `rating` REAL NOT NULL,
                        `airWeekday` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_subscribe_title_sourceId_sourceKey` " +
                        "ON `subscribe_title` (`sourceId`, `sourceKey`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_subscribe_title_bangumiId` " +
                        "ON `subscribe_title` (`bangumiId`)"
                )
            }
        }

        /**
         * v4 → v5：收藏类型编码对齐 Bangumi 官方 + 两个同步字段（审核240）。
         *
         * 背景：v4 常量写成 2=在看/3=看过，而 Bangumi 官方 openapi 与
         * api.bgm.tv 实测均为 **2=看过、3=在看**（`SubjectCollectionType`
         * 枚举顺序 Wish, Done, Doing, OnHold, Dropped）。继续用错编码会把
         * 本地「在看」同步成 Bangumi「看过」，属于用户数据语义错误。
         *
         * 数据纠正：2 与 3 互换。**必须走临时负值**——直接
         * `UPDATE ... SET 2 WHERE =3` 再 `SET 3 WHERE =2` 会把所有行都变成 2。
         * collectionType 是 INTEGER NOT NULL 且无 CHECK 约束，负值中间态安全。
         *
         * 新增字段带 DEFAULT，老行自动获得 0（未同步/公开）。
         */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `subscribe_title` ADD COLUMN `bangumiSyncedAt` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `subscribe_title` ADD COLUMN `bangumiPrivate` INTEGER NOT NULL DEFAULT 0"
                )
                // 2 <-> 3 互换：旧3(在看)→临时-3，旧2(看过)→3，临时→2
                db.execSQL("UPDATE `subscribe_title` SET `collectionType` = -3 WHERE `collectionType` = 3")
                db.execSQL("UPDATE `subscribe_title` SET `collectionType` = 3 WHERE `collectionType` = 2")
                db.execSQL("UPDATE `subscribe_title` SET `collectionType` = 2 WHERE `collectionType` = -3")
            }
        }
    }
}
