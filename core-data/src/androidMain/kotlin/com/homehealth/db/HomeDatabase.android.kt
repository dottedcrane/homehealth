package com.homehealth.db

import androidx.room.Room

internal actual fun getDatabaseInstance(context: PlatformContext): HomeDatabase {
    return HomeDatabaseAndroid.getInstance(context)
}

internal actual fun closeDatabaseInstance() {
    HomeDatabaseAndroid.closeInstance()
}

private object HomeDatabaseAndroid {
    private var instance: HomeDatabase? = null

    fun getInstance(context: PlatformContext): HomeDatabase {
        return synchronized(this) {
            instance ?: run {
                val dbFile = context.getDatabasePath("home_health.db")
                val builder = Room.databaseBuilder<HomeDatabase>(
                    context = context,
                    name = dbFile.absolutePath
                )
                getCommonDatabaseBuilder(builder).build().also { instance = it }
            }
        }
    }

    fun closeInstance() {
        synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}
