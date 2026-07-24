package com.homehealth.db

import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

actual fun getDatabaseBuilder(): RoomDatabase.Builder<HomeDatabase> {
    val dbFile = File(System.getProperty("java.io.tmpdir"), "home_health.db")
    return Room.databaseBuilder<HomeDatabase>(
        name = dbFile.absolutePath,
    )
}
