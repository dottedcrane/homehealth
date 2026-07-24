package com.homehealth.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

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

    companion object
}

expect fun getDatabaseBuilder(): RoomDatabase.Builder<HomeDatabase>

val MIGRATIONS: Array<Migration> = arrayOf(
    object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
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
            connection.execSQL(
                """CREATE TABLE IF NOT EXISTS appliance_records (
                    homeId INTEGER NOT NULL DEFAULT 1,
                    itemKey TEXT NOT NULL,
                    installYear INTEGER,
                    notes TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY(homeId, itemKey))"""
            )
            connection.execSQL(
                """CREATE TABLE IF NOT EXISTS documents (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    homeId INTEGER NOT NULL DEFAULT 1,
                    itemKey TEXT NOT NULL,
                    uri TEXT NOT NULL,
                    label TEXT NOT NULL,
                    addedAt INTEGER NOT NULL DEFAULT 0)"""
            )
        }
    },
    object : Migration(2, 3) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """CREATE TABLE IF NOT EXISTS task_records (
                    taskKey TEXT NOT NULL,
                    homeId INTEGER NOT NULL DEFAULT 1,
                    lastCompleted INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(taskKey))"""
            )
        }
    },
    object : Migration(3, 4) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE user_home ADD COLUMN neighborKey TEXT NOT NULL DEFAULT ''")
        }
    },
    object : Migration(4, 5) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE user_home ADD COLUMN itemOffsetsJson TEXT NOT NULL DEFAULT ''")
        }
    },
    object : Migration(5, 6) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE user_home ADD COLUMN placedItemsJson TEXT NOT NULL DEFAULT ''")
        }
    },
    object : Migration(6, 7) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE user_home ADD COLUMN removedInstancesJson TEXT NOT NULL DEFAULT ''")
        }
    },
    object : Migration(7, 8) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
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
    },
    object : Migration(8, 9) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE user_home ADD COLUMN featureOffsetsJson TEXT NOT NULL DEFAULT ''")
        }
    },
    object : Migration(9, 10) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE user_home ADD COLUMN placedDecksJson TEXT NOT NULL DEFAULT ''")
        }
    }
)
