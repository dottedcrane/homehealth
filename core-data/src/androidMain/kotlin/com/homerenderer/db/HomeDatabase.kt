package com.homerenderer.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        HomeModelEntity::class,
        UserHomeEntity::class,
        ApplianceRecordEntity::class,
        DocumentEntity::class,
        MaintenanceTaskRecord::class,
        ProContactEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class HomeDatabase : RoomDatabase() {
    abstract fun userHomeDao(): UserHomeDao

    companion object {
        @Volatile private var instance: HomeDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS user_home (
                        id INTEGER NOT NULL DEFAULT 1,
                        homeStyle TEXT NOT NULL DEFAULT '',
                        featurePlacementsJson TEXT NOT NULL DEFAULT '',
                        homeSystemsJson TEXT NOT NULL DEFAULT '',
                        floorLayoutJson TEXT NOT NULL DEFAULT '',
                        buildYear INTEGER,
                        purchaseYear INTEGER,
                        label TEXT NOT NULL DEFAULT 'My Home',
                        savedAt INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(id))"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS appliance_records (
                        homeId INTEGER NOT NULL DEFAULT 1,
                        itemKey TEXT NOT NULL,
                        installYear INTEGER,
                        notes TEXT NOT NULL DEFAULT '',
                        PRIMARY KEY(homeId, itemKey))"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS documents (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        homeId INTEGER NOT NULL DEFAULT 1,
                        itemKey TEXT NOT NULL,
                        uri TEXT NOT NULL,
                        label TEXT NOT NULL,
                        addedAt INTEGER NOT NULL DEFAULT 0)"""
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS task_records (
                        taskKey TEXT NOT NULL,
                        homeId INTEGER NOT NULL DEFAULT 1,
                        lastCompleted INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(taskKey))"""
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_home ADD COLUMN neighborKey TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_home ADD COLUMN itemOffsetsJson TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_home ADD COLUMN placedItemsJson TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_home ADD COLUMN removedInstancesJson TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS pro_contacts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        homeId INTEGER NOT NULL DEFAULT 1,
                        name TEXT NOT NULL,
                        trade TEXT NOT NULL DEFAULT '',
                        phone TEXT NOT NULL DEFAULT '',
                        email TEXT NOT NULL DEFAULT '',
                        notes TEXT NOT NULL DEFAULT '',
                        addedAt INTEGER NOT NULL DEFAULT 0)"""
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_home ADD COLUMN featureOffsetsJson TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_home ADD COLUMN placedDecksJson TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): HomeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HomeDatabase::class.java,
                    "home_renderer.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                    .build()
                    .also { instance = it }
            }

        // Closes the live connection so its backing .db/-wal/-shm files can be safely
        // overwritten (e.g. during a restore-from-backup) — getInstance() reopens fresh
        // afterwards. The caller is still responsible for restarting the app so every
        // in-memory ViewModel/Compose state re-reads from the swapped-in database.
        fun closeInstance() = synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}
