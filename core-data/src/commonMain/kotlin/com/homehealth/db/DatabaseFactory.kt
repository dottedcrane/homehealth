package com.homehealth.db

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

expect abstract class PlatformContext

object HomeDatabaseFactory {
    fun getInstance(context: PlatformContext): HomeDatabase = getDatabaseInstance(context)
    fun closeInstance() = closeDatabaseInstance()
}

internal expect fun getDatabaseInstance(context: PlatformContext): HomeDatabase

internal expect fun closeDatabaseInstance()

// No migrations: the schema history was collapsed to version 1, since every install creates a
// fresh home_health.db and nothing in the field was ever written by an earlier version.
//
// ⚠ BEFORE THE FIRST UPDATE THAT REAL USERS RECEIVE, replace the destructive fallback below with
// `version = 2` plus a real Migration. While it is here, ANY schema change wipes the database
// instead of failing the build — which is what you want pre-release and a silent data-loss bug
// the moment someone has data worth keeping.
//
// It is here because staying on `version = 1` across a schema edit is otherwise unrecoverable:
// same version + different identity hash makes Room throw "cannot verify the data integrity" on
// open, crash-looping every device that already installed an earlier build. Downgrade-only
// fallback does not catch that case, because 1 → 1 is not a downgrade.
fun getCommonDatabaseBuilder(builder: RoomDatabase.Builder<HomeDatabase>): RoomDatabase.Builder<HomeDatabase> {
    return builder
        .fallbackToDestructiveMigration(dropAllTables = true)
        .setDriver(BundledSQLiteDriver())
}
