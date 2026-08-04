package com.homehealth.db

import androidx.room.*

@Database(
    entities = [
        UserHomeEntity::class,
        ApplianceRecordEntity::class,
        DocumentEntity::class,
        MaintenanceTaskRecord::class,
        ProContactEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
@ConstructedBy(HomeDatabaseConstructor::class)
abstract class HomeDatabase : RoomDatabase() {
    abstract fun userHomeDao(): UserHomeDao
}

// The Room compiler generates the `actual` implementation of this interface
expect object HomeDatabaseConstructor : RoomDatabaseConstructor<HomeDatabase> {
    override fun initialize(): HomeDatabase
}
