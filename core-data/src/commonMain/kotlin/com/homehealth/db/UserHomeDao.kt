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
}
