package com.homehealth.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.homehealth.backup.BackupManager
import com.homehealth.db.*
import com.homehealth.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MaintenanceViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = HomeDatabaseFactory.getInstance(application).userHomeDao()

    // Set once restore() succeeds: restoreZip() closes the live RoomDatabase to swap in the
    // backup's files, so `dao` now points at a closed connection until the user restarts the
    // app. Any write still in flight at that moment (e.g. a pending autosave) would otherwise
    // throw inside an unguarded viewModelScope.launch and crash the process — pointless, since
    // the whole app is about to restart and reload from the swapped-in database anyway.
    private var restoreSucceeded = false

    private fun dbLaunch(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: Exception) {
            if (!restoreSucceeded) throw e
        }
    }

    val savedHome: StateFlow<UserHomeEntity?> = dao.observeHome()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val applianceRecords: StateFlow<List<ApplianceRecordEntity>> = dao.observeAppliances()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val documents: StateFlow<List<DocumentEntity>> = dao.observeDocuments()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val taskRecords: StateFlow<List<MaintenanceTaskRecord>> = dao.observeTaskRecords()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val contacts: StateFlow<List<ProContactEntity>> = dao.observeContacts()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun saveContact(contact: ProContactEntity) = dbLaunch { dao.upsertContact(contact) }

    fun deleteContact(id: Int) = dbLaunch { dao.deleteContact(id) }

    // The single write path for the user's home row. Every field is required so a call site
    // can never accidentally persist a partial row — the previous per-field save methods
    // rebuilt the entity from whichever snapshot happened to be in savedHome, so a floor-plan
    // autosave would wipe furniture offsets/placed items/removed instances back to "".
    fun saveHome(
        style: HomeStyle,
        featurePlacements: Map<HomeFeature, FeatureSide>,
        featureOffsets: Map<HomeFeature, Pair<Float, Float>>,
        placedDecks: List<PlacedDeck>,
        hvacPlacement: Pair<FeatureSide, Float>?,
        solarArrayPlacement: Pair<Boolean, Float>?,
        evBatteryPlacement: Pair<FeatureSide, Float>?,
        placedYardDecor: List<PlacedYardDecor>,
        customAppliances: List<CustomAppliance>,
        customTasks: List<CustomTask>,
        floorLayout: FloorLayout,
        itemOffsets: Map<RoomItem, Pair<Float, Float>>,
        placedItems: List<PlacedItem>,
        removedInstances: Set<String>,
        buildYear: Int?,
        purchaseYear: Int?,
        label: String,
        neighborKey: String = "",
        // Null = the owner has never chosen, which resolves to the roof's default on read (see
        // AtticType). Persisted as "" so an untouched home is indistinguishable from one saved
        // before the column existed.
        atticType: AtticType? = null,
    ) = dbLaunch {
        dao.upsertHome(
            UserHomeEntity(
                homeStyle             = style.name,
                featurePlacementsJson = HomeStateSerialization.serializeFeaturePlacements(featurePlacements),
                featureOffsetsJson    = HomeStateSerialization.serializeFeatureOffsets(featureOffsets),
                placedDecksJson       = HomeStateSerialization.serializePlacedDecks(placedDecks),
                hvacPlacementJson     = HomeStateSerialization.serializeHvacPlacement(hvacPlacement),
                solarArrayPlacementJson = HomeStateSerialization.serializeSolarArrayPlacement(solarArrayPlacement),
                evBatteryPlacementJson = HomeStateSerialization.serializeEvBatteryPlacement(evBatteryPlacement),
                placedYardDecorJson   = HomeStateSerialization.serializePlacedYardDecor(placedYardDecor),
                customAppliancesJson  = HomeStateSerialization.serializeCustomAppliances(customAppliances),
                customTasksJson       = HomeStateSerialization.serializeCustomTasks(customTasks),
                floorLayoutJson       = HomeStateSerialization.serializeFloorLayout(floorLayout),
                itemOffsetsJson       = HomeStateSerialization.serializeItemOffsets(itemOffsets),
                placedItemsJson       = HomeStateSerialization.serializePlacedItems(placedItems),
                removedInstancesJson  = HomeStateSerialization.serializeRemovedInstances(removedInstances),
                atticType             = atticType?.name ?: "",
                buildYear             = buildYear,
                purchaseYear          = purchaseYear,
                label                 = label,
                neighborKey           = neighborKey,
            )
        )
    }

    fun upsertAppliance(itemKey: String, installYear: Int?) = dbLaunch {
        dao.upsertAppliance(ApplianceRecordEntity(itemKey = itemKey, installYear = installYear))
    }

    fun removeItemRecords(itemKey: String) = dbLaunch {
        dao.deleteAppliance(itemKey)
        dao.deleteDocumentsForItem(itemKey)
    }

    /** One-time key migration: moves appliance records and documents from each old itemKey to
     *  its new one, so install years/warranties follow items whose tracking key changed
     *  (e.g. default appliance instances converted to placed items). */
    fun migrateItemKeys(mapping: Map<String, String>) = dbLaunch {
        mapping.forEach { (old, new) ->
            dao.rekeyAppliance(old, new)
            dao.rekeyDocuments(old, new)
        }
    }

    fun addDocument(itemKey: String, uri: String, label: String) = dbLaunch {
        dao.insertDocument(DocumentEntity(itemKey = itemKey, uri = uri, label = label))
    }

    fun deleteDocument(id: Int) = dbLaunch { dao.deleteDocument(id) }

    // Each of these touches exactly one column, via seed-then-update rather than a whole-entity
    // upsert: a task_records row holds three independent pieces of state (completion, snooze,
    // mute) and rebuilding it to stamp one would reset the others — see UserHomeDao's setters.
    fun markTaskDone(taskKey: String) = dbLaunch {
        dao.ensureTaskRecord(taskKey)
        dao.setTaskLastCompleted(taskKey, System.currentTimeMillis())
    }

    /** Defers a task without pretending it was done — [untilMillis] of 0 clears the snooze. */
    fun snoozeTask(taskKey: String, untilMillis: Long) = dbLaunch {
        dao.ensureTaskRecord(taskKey)
        dao.setTaskSnoozedUntil(taskKey, untilMillis)
    }

    fun setTaskMuted(taskKey: String, muted: Boolean) = dbLaunch {
        dao.ensureTaskRecord(taskKey)
        dao.setTaskMuted(taskKey, muted)
    }

    // ── Backup ────────────────────────────────────────────────────────────────

    private val _backupResult = MutableStateFlow<BackupResult?>(null)
    val backupResult: StateFlow<BackupResult?> = _backupResult

    fun backup(outputUri: Uri) = viewModelScope.launch {
        try {
            val app = getApplication<Application>()
            val manager = BackupManager(app)
            val allDocs = dao.getAllDocuments()
            val (copied, skipped) = manager.internalize(allDocs, dao)
            val freshDocs = dao.getAllDocuments()
            app.contentResolver.openOutputStream(outputUri)?.use { out ->
                manager.writeZip(out, savedHome.value, freshDocs)
            } ?: throw Exception("Could not open output file")
            _backupResult.value = BackupResult.Success(copied, skipped)
        } catch (e: Exception) {
            _backupResult.value = BackupResult.Failure(e.message ?: "Backup failed")
        }
    }

    fun clearBackupResult() { _backupResult.value = null }

    // ── Restore ───────────────────────────────────────────────────────────────

    private val _restoreResult = MutableStateFlow<BackupManager.RestoreResult?>(null)
    val restoreResult: StateFlow<BackupManager.RestoreResult?> = _restoreResult

    fun restore(inputUri: Uri) = viewModelScope.launch {
        val app = getApplication<Application>()
        val result = try {
            app.contentResolver.openInputStream(inputUri)?.use { BackupManager(app).restoreZip(it) }
                ?: BackupManager.RestoreResult(false, 0, "Could not open backup file")
        } catch (e: Exception) {
            BackupManager.RestoreResult(false, 0, e.message ?: "Restore failed")
        }
        if (result.success) restoreSucceeded = true
        _restoreResult.value = result
    }

    fun clearRestoreResult() { _restoreResult.value = null }
}

sealed class BackupResult {
    data class Success(val copied: Int, val skipped: List<String>) : BackupResult()
    data class Failure(val message: String) : BackupResult()
}
