package com.homehealth.db

import androidx.room.Room
import java.io.File

internal actual fun getDatabaseInstance(context: PlatformContext): HomeDatabase {
    return HomeDatabaseJvm.getInstance()
}

internal actual fun closeDatabaseInstance() {
    HomeDatabaseJvm.closeInstance()
}

private object HomeDatabaseJvm {
    private var instance: HomeDatabase? = null

    fun getInstance(): HomeDatabase {
        return synchronized(this) {
            instance ?: run {
                val dbFile = File(System.getProperty("java.io.tmpdir"), "home_health.db")
                val builder = Room.databaseBuilder<HomeDatabase>(
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
