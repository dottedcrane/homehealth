package com.homerenderer.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

actual fun getDatabaseBuilder(): RoomDatabase.Builder<HomeDatabase> {
    throw UnsupportedOperationException("Call HomeDatabase.getInstance(context) on Android")
}

private var instance: HomeDatabase? = null

fun HomeDatabase.Companion.getInstance(context: Context): HomeDatabase =
    instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
            context.applicationContext,
            HomeDatabase::class.java,
            "home_renderer.db"
        )
            .addMigrations(*MIGRATIONS)
            .build()
            .also { instance = it }
    }

fun HomeDatabase.Companion.closeInstance() = synchronized(this) {
    instance?.close()
    instance = null
}
