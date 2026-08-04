package com.homehealth.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UserHomeDao {

    // ── User home ──────────────────────────────────────────────────────────────

    @Query("SELECT * FROM user_home WHERE id = 1")
    fun observeHome(): Flow<UserHomeEntity?>

    @Upsert
    suspend fun upsertHome(home: UserHomeEntity)

    @Query("DELETE FROM user_home WHERE id = 1")
    suspend fun clearHome()

    // ── Appliance records ──────────────────────────────────────────────────────

    @Query("SELECT * FROM appliance_records WHERE homeId = 1")
    fun observeAppliances(): Flow<List<ApplianceRecordEntity>>

    @Upsert
    suspend fun upsertAppliance(record: ApplianceRecordEntity)

    @Query("DELETE FROM appliance_records WHERE homeId = 1 AND itemKey = :key")
    suspend fun deleteAppliance(key: String)

    // Re-keying for one-time migrations (e.g. default washer/dryer/water-heater instances
    // becoming placed items) so install years and documents follow the item to its new key.
    @Query("UPDATE appliance_records SET itemKey = :newKey WHERE homeId = 1 AND itemKey = :oldKey")
    suspend fun rekeyAppliance(oldKey: String, newKey: String)

    @Query("UPDATE documents SET itemKey = :newKey WHERE homeId = 1 AND itemKey = :oldKey")
    suspend fun rekeyDocuments(oldKey: String, newKey: String)

    // ── Documents ──────────────────────────────────────────────────────────────

    @Query("SELECT * FROM documents WHERE homeId = 1 ORDER BY addedAt DESC")
    fun observeDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE homeId = 1 AND itemKey = :key ORDER BY addedAt DESC")
    fun observeDocumentsForItem(key: String): Flow<List<DocumentEntity>>

    @Insert
    suspend fun insertDocument(doc: DocumentEntity): Long

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: Int)

    @Query("DELETE FROM documents WHERE homeId = 1 AND itemKey = :key")
    suspend fun deleteDocumentsForItem(key: String)

    @Query("SELECT * FROM documents WHERE homeId = 1 ORDER BY addedAt DESC")
    suspend fun getAllDocuments(): List<DocumentEntity>

    @Query("UPDATE documents SET uri = :newUri WHERE id = :id")
    suspend fun updateDocumentUri(id: Int, newUri: String)

    @RawQuery
    suspend fun rawQuery(query: androidx.room.RoomRawQuery): Int

    // ── Professional contacts ──────────────────────────────────────────────────

    @Query("SELECT * FROM pro_contacts WHERE homeId = 1 ORDER BY name COLLATE NOCASE")
    fun observeContacts(): Flow<List<ProContactEntity>>

    @Upsert
    suspend fun upsertContact(contact: ProContactEntity)

    @Query("DELETE FROM pro_contacts WHERE id = :id")
    suspend fun deleteContact(id: Int)

    // ── Task records ───────────────────────────────────────────────────────────

    @Query("SELECT * FROM task_records WHERE homeId = 1")
    fun observeTaskRecords(): Flow<List<MaintenanceTaskRecord>>

    @Upsert
    suspend fun upsertTaskRecord(record: MaintenanceTaskRecord)

    // A task_records row now carries three independent pieces of per-task state (completion,
    // snooze, mute), so nothing may write it as a whole entity: constructing a fresh
    // MaintenanceTaskRecord to stamp one field resets the other two to their defaults. That is
    // the same split-path-write bug documented on saveHome, where a floor-plan autosave rebuilt
    // the home row without the furniture columns and silently wiped them.
    //
    // Each setter below therefore touches exactly its own column. The paired INSERT OR IGNORE
    // seeds the row first (a task may be snoozed or muted before it has ever been completed),
    // and is a no-op once the row exists, so the UPDATE never silently matches nothing.
    @Query("INSERT OR IGNORE INTO task_records (taskKey, homeId, lastCompleted, snoozedUntil, muted) VALUES (:key, 1, 0, 0, 0)")
    suspend fun ensureTaskRecord(key: String)

    @Query("UPDATE task_records SET lastCompleted = :millis WHERE homeId = 1 AND taskKey = :key")
    suspend fun setTaskLastCompleted(key: String, millis: Long)

    @Query("UPDATE task_records SET snoozedUntil = :millis WHERE homeId = 1 AND taskKey = :key")
    suspend fun setTaskSnoozedUntil(key: String, millis: Long)

    @Query("UPDATE task_records SET muted = :muted WHERE homeId = 1 AND taskKey = :key")
    suspend fun setTaskMuted(key: String, muted: Boolean)
}
