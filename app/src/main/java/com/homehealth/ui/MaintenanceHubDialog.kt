package com.homehealth.ui

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.homehealth.data.*
import com.homehealth.db.ApplianceRecordEntity
import com.homehealth.db.MaintenanceTaskRecord
import com.homehealth.db.ProContactEntity
import com.homehealth.model.FeatureSide
import com.homehealth.model.HiddenAsset
import com.homehealth.model.HomeFeature
import com.homehealth.model.HomeStyle
import com.homehealth.model.HomeSystem
import com.homehealth.model.RoomItem
import com.homehealth.model.RoomType
import com.homehealth.model.defaultItems
import com.homehealth.model.isApplicable
import com.homehealth.model.items
import com.homehealth.model.label
import java.util.Calendar

// ── Calendar helper ───────────────────────────────────────────────────────────

// Uses the ACTION_INSERT "new event" template, which just opens the device's own calendar app
// pre-filled — the user still taps that app's own save button. Its officially documented extras
// are TITLE/DESCRIPTION/EVENT_LOCATION/EXTRA_EVENT_BEGIN_TIME/EXTRA_EVENT_END_TIME/
// EXTRA_EVENT_ALL_DAY/AVAILABILITY; RRULE is NOT among them. Passing it anyway "worked" on some
// calendar apps but on others (notably Google Calendar) the event would appear to save, then get
// silently discarded once the app's own recurrence UI tried to reconcile the injected rule. The
// frequency is stated in the description instead — the user can add repeat themselves if wanted.
private fun addToCalendar(context: Context, task: MaintenanceTask, nextDueMillis: Long) {
    val startMillis = nextDueMillis
    val endMillis   = nextDueMillis + 3600000L // 1-hour event

    val intent = Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.Events.TITLE, task.title)
        putExtra(CalendarContract.Events.DESCRIPTION,
            buildString {
                if (task.description.isNotBlank()) { append(task.description); append("\n\n") }
                append("Frequency: ${task.frequency.displayName}")
                if (task.estimatedCostDollars > 0f)
                    append("\nEst. cost: \$${task.estimatedCostDollars.toInt()}")
            }
        )
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
    }
    context.startActivity(intent)
}

// ── Due-date helpers ──────────────────────────────────────────────────────────

private val QUARTER_MONTHS  = setOf(Calendar.MARCH, Calendar.JUNE, Calendar.SEPTEMBER, Calendar.DECEMBER)
private val BIANNUAL_MONTHS = setOf(Calendar.MARCH, Calendar.SEPTEMBER)

// Finds the next 1st-of-month occurrence of any month in [months] strictly after [afterMillis],
// wrapping into following year(s) as needed. Truncating to day 1 then always advancing at least
// one month before checking guarantees the result is strictly in the future relative to
// [afterMillis], regardless of what day within its month it fell on — so a task completed ON its
// anchor month always lands on the FOLLOWING occurrence, never the same one (a task done this
// season is never "done forever").
private fun nextSeasonAnchor(afterMillis: Long, months: Set<Int>): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = afterMillis
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    cal.add(Calendar.MONTH, 1)
    while (cal.get(Calendar.MONTH) !in months) {
        cal.add(Calendar.MONTH, 1)
    }
    return cal.timeInMillis
}

// MONTHLY stays a simple rolling "+1 month from last completion" — seasons don't apply at that
// cadence. QUARTERLY/BIANNUAL/ANNUAL instead anchor to real calendar seasons (see
// nextSeasonAnchor) rather than "N months after whenever you happened to tap it."
private fun nextDueMillis(task: MaintenanceTask, lastCompleted: Long): Long {
    if (lastCompleted == 0L) return System.currentTimeMillis()
    return when (task.frequency) {
        TaskFrequency.MONTHLY -> Calendar.getInstance().apply {
            timeInMillis = lastCompleted
            add(Calendar.MONTH, task.frequency.months)
        }.timeInMillis
        TaskFrequency.QUARTERLY -> nextSeasonAnchor(lastCompleted, QUARTER_MONTHS)
        TaskFrequency.BIANNUAL  -> nextSeasonAnchor(lastCompleted, BIANNUAL_MONTHS)
        TaskFrequency.ANNUAL    -> nextSeasonAnchor(lastCompleted, setOf(task.seasonMonth ?: Calendar.SEPTEMBER))
    }
}

private data class DueLabel(val text: String, val color: Color)

private fun dueLabel(nextDue: Long, lastCompleted: Long): DueLabel {
    val now = System.currentTimeMillis()
    if (lastCompleted == 0L) return DueLabel("Not done yet", Color(0xFFF57F17))
    val diffDays = ((nextDue - now) / 86_400_000L).toInt()
    return when {
        diffDays < -14  -> DueLabel("Overdue ${-diffDays}d", Color(0xFFC62828))
        diffDays < 0    -> DueLabel("Overdue", Color(0xFFC62828))
        diffDays == 0   -> DueLabel("Due today", Color(0xFFF57F17))
        diffDays <= 30  -> DueLabel("Due in ${diffDays}d", Color(0xFFF57F17))
        else            -> DueLabel("Due in ${diffDays / 30}mo", Color(0xFF2E7D32))
    }
}

// ── Home system icons (mirrors MainActivity's private mapping) ────────────────

private fun systemIcon(s: HomeSystem) = when (s) {
    HomeSystem.SOLAR -> Icons.Outlined.WbSunny
    HomeSystem.HVAC  -> Icons.Outlined.AcUnit
}

private fun systemIconColor(s: HomeSystem) = when (s) {
    HomeSystem.SOLAR -> Color(0xFFF9A825)   // amber — sun
    HomeSystem.HVAC  -> Color(0xFF0288D1)   // blue  — cooling
}

// ── Priority / DIY colors ─────────────────────────────────────────────────────

private val TaskPriority.color: Color get() = when (this) {
    TaskPriority.HIGH   -> Color(0xFFC62828)
    TaskPriority.MEDIUM -> Color(0xFFF57F17)
    TaskPriority.LOW    -> Color(0xFF2E7D32)
}

private val DiyLevel.color: Color get() = when (this) {
    DiyLevel.EASY      -> Color(0xFF2E7D32)
    DiyLevel.MODERATE  -> Color(0xFFF57F17)
    DiyLevel.HARD      -> Color(0xFFE65100)
    DiyLevel.PRO_ONLY  -> Color(0xFFC62828)
}

// ── Main dialog ───────────────────────────────────────────────────────────────

@Composable
fun MaintenanceHubDialog(
    tasks: List<MaintenanceTask>,
    taskRecords: Map<String, MaintenanceTaskRecord>,
    isClaimed: Boolean,
    onMarkDone: (taskKey: String) -> Unit,
    roomTypes: List<RoomType> = emptyList(),
    instancesByItem: Map<RoomItem, List<MaintenanceTarget>> = emptyMap(),
    applianceRecords: List<ApplianceRecordEntity> = emptyList(),
    homeYear: Int? = null,
    homeStyle: HomeStyle = HomeStyle.HOUSE,
    featurePlacements: Map<HomeFeature, FeatureSide> = emptyMap(),
    // Decks support multiple placed instances now (their own list), not a featurePlacements
    // slot — only presence matters here (gates the Deck inventory entry).
    hasDeck: Boolean = false,
    homeSystems: Set<HomeSystem> = emptySet(),
    removedInstances: Set<String> = emptySet(),
    onTapInstance: (MaintenanceTarget) -> Unit = {},
    onTapHidden: (HiddenAsset) -> Unit = {},
    contacts: List<ProContactEntity> = emptyList(),
    onSaveContact: (ProContactEntity) -> Unit = {},
    onDeleteContact: (Int) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var selectedTab  by remember { mutableIntStateOf(0) }
    var selectedGuide by remember { mutableStateOf<RepairGuide?>(null) }
    val context = LocalContext.current

    // Adds a task directly to the system calendar using an Intent.
    val onAddToCalendar: (MaintenanceTask, Long) -> Unit = { task, nextDue ->
        addToCalendar(context, task, nextDue)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Home Maintenance",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, "Close")
                    }
                }

                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick  = { selectedTab = 0 },
                        text     = { Text("To-Do") },
                        icon     = { Icon(Icons.Outlined.CheckCircle, null, modifier = Modifier.size(18.dp)) },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick  = { selectedTab = 1 },
                        text     = { Text("Guides") },
                        icon     = { Icon(Icons.Outlined.MenuBook, null, modifier = Modifier.size(18.dp)) },
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick  = { selectedTab = 2 },
                        text     = { Text("Score") },
                        icon     = { Icon(Icons.Outlined.Inventory2, null, modifier = Modifier.size(18.dp)) },
                    )
                    Tab(
                        selected = selectedTab == 3,
                        onClick  = { selectedTab = 3 },
                        text     = { Text("Pros") },
                        icon     = { Icon(Icons.Outlined.Engineering, null, modifier = Modifier.size(18.dp)) },
                    )
                }

                when (selectedTab) {
                    0 -> TasksTab(
                        tasks            = tasks,
                        taskRecords      = taskRecords,
                        isClaimed        = isClaimed,
                        onMarkDone       = onMarkDone,
                        onAddToCalendar  = onAddToCalendar,
                    )
                    1 -> GuidesTab { selectedGuide = it }
                    2 -> InventoryTab(
                        roomTypes        = roomTypes,
                        instancesByItem  = instancesByItem,
                        applianceRecords = applianceRecords,
                        homeYear         = homeYear,
                        tasks            = tasks,
                        taskRecords      = taskRecords,
                        homeStyle        = homeStyle,
                        featurePlacements = featurePlacements,
                        hasDeck          = hasDeck,
                        homeSystems      = homeSystems,
                        removedInstances = removedInstances,
                        onTapInstance    = onTapInstance,
                        onTapHidden      = onTapHidden,
                    )
                    3 -> ProsTab(
                        contacts  = contacts,
                        onSave    = onSaveContact,
                        onDelete  = onDeleteContact,
                    )
                }
            }
        }
    }

    selectedGuide?.let { guide ->
        GuideDetailDialog(guide, onDismiss = { selectedGuide = null })
    }
}

// ── Inventory tab ───────────────────────────────────────────────────────────────

private fun ratingFor(itemKey: String, records: List<ApplianceRecordEntity>, homeYear: Int?, lifespan: Int?): LifespanRating? {
    val record = records.firstOrNull { it.itemKey == itemKey }
    val effectiveYear = record?.installYear ?: homeYear ?: return null
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val remaining = (effectiveYear + (lifespan ?: return null)) - currentYear
    return lifespanRating(remaining, lifespan)
}

@Composable
private fun RatingBadge(rating: LifespanRating) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = rating.color.copy(alpha = 0.15f),
    ) {
        Text(
            rating.label,
            style = MaterialTheme.typography.labelSmall,
            color = rating.color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

// ── Upkeep status (green/amber/red) ────────────────────────────────────────────
// Distinct from LifespanRating (A/B/C, "should this be replaced?") — this answers
// "is this item's recurring maintenance current?", derived from its mapped task(s).

private enum class UpkeepStatus(val color: Color) {
    RED(Color(0xFFC62828)),
    AMBER(Color(0xFFF57F17)),
    GREEN(Color(0xFF2E7D32)),
}

private fun upkeepStatusFor(
    relatedTasks: List<MaintenanceTask>,
    taskRecords: Map<String, MaintenanceTaskRecord>,
): UpkeepStatus? {
    if (relatedTasks.isEmpty()) return null
    val now = System.currentTimeMillis()
    val statuses = relatedTasks.map { task ->
        val lastCompleted = taskRecords[task.key]?.lastCompleted ?: 0L
        if (lastCompleted == 0L) return@map UpkeepStatus.AMBER
        val diffDays = ((nextDueMillis(task, lastCompleted) - now) / 86_400_000L).toInt()
        when {
            diffDays < 0   -> UpkeepStatus.RED
            diffDays <= 30 -> UpkeepStatus.AMBER
            else           -> UpkeepStatus.GREEN
        }
    }
    return when {
        UpkeepStatus.RED in statuses   -> UpkeepStatus.RED
        UpkeepStatus.AMBER in statuses -> UpkeepStatus.AMBER
        else                           -> UpkeepStatus.GREEN
    }
}

@Composable
private fun UpkeepDot(status: UpkeepStatus) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(status.color, CircleShape),
    )
}

@Composable
private fun InventoryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    label: String,
    upkeepStatus: UpkeepStatus?,
    rating: LifespanRating?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = iconColor)
        Spacer(Modifier.width(8.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        if (upkeepStatus != null) {
            UpkeepDot(upkeepStatus)
            Spacer(Modifier.width(6.dp))
        }
        if (rating != null) RatingBadge(rating)
    }
}

@Composable
private fun ColumnScope.InventoryTab(
    roomTypes: List<RoomType>,
    instancesByItem: Map<RoomItem, List<MaintenanceTarget>> = emptyMap(),
    applianceRecords: List<ApplianceRecordEntity> = emptyList(),
    homeYear: Int? = null,
    tasks: List<MaintenanceTask> = emptyList(),
    taskRecords: Map<String, MaintenanceTaskRecord> = emptyMap(),
    homeStyle: HomeStyle = HomeStyle.HOUSE,
    featurePlacements: Map<HomeFeature, FeatureSide> = emptyMap(),
    hasDeck: Boolean = false,
    homeSystems: Set<HomeSystem> = emptySet(),
    removedInstances: Set<String> = emptySet(),
    onTapInstance: (MaintenanceTarget) -> Unit = {},
    onTapHidden: (HiddenAsset) -> Unit = {},
) {
    // Items tracked under each room section: only items whose nominal room is that room
    // (instances are keyed house-wide by nominal room, so listing borrowed items — e.g. the
    // powder room's toilet — under a second section would duplicate LazyColumn keys), never
    // vehicles or treasures — those get their own sections below since they aren't bound to
    // one room type. Furniture IS included, but instancesByItem only carries the pieces the
    // user opted into tracking (PlacedItem.tracked), and furniture has no recurring tasks —
    // tracked pieces surface under "Other Items" (lifespan only, no reminders).
    fun trackedItems(room: RoomType) = room.items().filterNot { it.isVehicle || it == RoomItem.TREASURE }
    val rooms = roomTypes.distinct().filter { trackedItems(it).isNotEmpty() }
    if (rooms.isEmpty()) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Text("No inventory in this home yet.", color = MaterialTheme.colorScheme.outline)
        }
        return
    }
    fun tasksFor(ri: RoomItem) = tasks.filter { it.relatedRoomItem == ri }
    val applicableAssets = HiddenAsset.entries.filter {
        it.isApplicable(homeStyle, featurePlacements.keys, hasDeck,
            hasGarage = HomeFeature.GARAGE in featurePlacements.keys || RoomType.GARAGE in roomTypes)
    }
    val presentAssets = applicableAssets.filterNot { it.name in removedInstances }
    val addableAssets = applicableAssets.filter { it.name in removedInstances }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            Text(
                "Tap an item to update its install year, see its condition rating, or remove it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        // ── Needs Maintenance — items with a recurring task tracked against them ────
        // Surfaced first since these are the items this app actually helps you stay on
        // top of; items with no scheduled upkeep are pushed below.
        item {
            Text(
                "Needs Maintenance",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        rooms.forEach { room ->
            val roomItems = trackedItems(room).filter { tasksFor(it).isNotEmpty() }
            val instances = roomItems.flatMap { instancesByItem[it].orEmpty() }
            if (instances.isEmpty()) return@forEach
            item {
                Text(
                    room.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }
            items(instances, key = { it.key }) { target ->
                val item = when (target) {
                    is MaintenanceTarget.Item   -> target.item
                    is MaintenanceTarget.Placed -> target.item
                    else -> return@items
                }
                InventoryRow(
                    icon         = item.icon,
                    iconColor    = item.room.iconColor,
                    label        = target.displayLabel,
                    upkeepStatus = upkeepStatusFor(tasksFor(item), taskRecords),
                    rating       = ratingFor(target.key, applianceRecords, homeYear, item.expectedLifespanYears),
                    onClick      = { onTapInstance(target) },
                )
            }
        }

        // ── Vehicles — everything parked on the lot, tracked individually ("Car 1",
        // "Boat 1", "Motorcycle 1", …) ─────────────────────────────────────────────────
        val vehicleInstances = listOf(RoomItem.CAR, RoomItem.BOAT, RoomItem.MOTORCYCLE)
            .flatMap { vi -> instancesByItem[vi].orEmpty().map { vi to it } }
        if (vehicleInstances.isNotEmpty()) {
            item {
                Text(
                    "Vehicles",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }
            items(vehicleInstances, key = { (_, target) -> target.key }) { (vi, target) ->
                InventoryRow(
                    icon         = vi.icon,
                    iconColor    = vi.room.iconColor,
                    label        = target.displayLabel,
                    upkeepStatus = upkeepStatusFor(tasksFor(vi), taskRecords),
                    rating       = ratingFor(target.key, applianceRecords, homeYear, vi.expectedLifespanYears),
                    onClick      = { onTapInstance(target) },
                )
            }
        }

        // ── Custom — user-placed treasures whose lifespan is tracked ("Treasure 1", …).
        // They live in whatever room the user dropped them in, so they get their own
        // section rather than a nominal-room one. Lifespan only — never tasks/reminders. ──
        val treasureInstances = instancesByItem[RoomItem.TREASURE].orEmpty()
        if (treasureInstances.isNotEmpty()) {
            item {
                Text(
                    "Custom",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }
            items(treasureInstances, key = { it.key }) { target ->
                InventoryRow(
                    icon         = RoomItem.TREASURE.icon,
                    iconColor    = RoomItem.TREASURE.room.iconColor,
                    label        = target.displayLabel,
                    upkeepStatus = null,
                    rating       = ratingFor(target.key, applianceRecords, homeYear,
                                             RoomItem.TREASURE.expectedLifespanYears),
                    onClick      = { onTapInstance(target) },
                )
            }
        }

        // ── Systems — whole-home systems (solar, HVAC); cards open only from here now
        // that the exterior-scene tap no longer opens them ─────────────────────────────
        if (homeSystems.isNotEmpty()) {
            item {
                Text(
                    "Systems",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }
            items(homeSystems.toList(), key = { it.name }) { sys ->
                val target = MaintenanceTarget.System(sys)
                InventoryRow(
                    icon         = systemIcon(sys),
                    iconColor    = systemIconColor(sys),
                    label        = target.displayLabel,
                    upkeepStatus = upkeepStatusFor(tasks.filter { it.relatedHomeSystem == sys }, taskRecords),
                    rating       = ratingFor(target.key, applianceRecords, homeYear, target.lifespan),
                    onClick      = { onTapInstance(target) },
                )
            }
        }

        // ── Structural — home-wide assets currently tracked (not marked "not in my home") ──
        if (presentAssets.isNotEmpty()) {
            item {
                Text(
                    "Structural",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }
            items(presentAssets) { asset ->
                val assetTasks = tasks.filter { it.relatedHiddenAsset == asset }
                InventoryRow(
                    icon         = asset.icon,
                    iconColor    = asset.iconColor,
                    label        = asset.label,
                    upkeepStatus = upkeepStatusFor(assetTasks, taskRecords),
                    rating       = ratingFor(asset.name, applianceRecords, homeYear, asset.expectedLifespanYears),
                    onClick      = { onTapHidden(asset) },
                )
            }
        }

        // ── Available to Add — applicable but marked "not in my home"; tap to add it back,
        // which also restores its associated To-Do task(s) ──────────────────────────
        if (addableAssets.isNotEmpty()) {
            item {
                Text(
                    "Available to Add",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                )
            }
            items(addableAssets) { asset ->
                InventoryRow(
                    icon         = asset.icon,
                    iconColor    = asset.iconColor.copy(alpha = 0.45f),
                    label        = asset.label,
                    upkeepStatus = null,
                    rating       = null,
                    onClick      = { onTapHidden(asset) },
                )
            }
        }

        // ── Other Items — no maintenance schedule tracked for these ─────────────────
        val otherRooms = rooms.filter { room -> trackedItems(room).any { tasksFor(it).isEmpty() } }
        if (otherRooms.isNotEmpty()) {
            item {
                Text(
                    "Other Items",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                )
                Text(
                    "No recurring upkeep is scheduled — tap to record install year and condition.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            otherRooms.forEach { room ->
                val roomItems = trackedItems(room).filter { tasksFor(it).isEmpty() }
                val instances = roomItems.flatMap { instancesByItem[it].orEmpty() }
                if (instances.isEmpty()) return@forEach
                item {
                    Text(
                        room.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                }
                items(instances, key = { it.key }) { target ->
                    val item = when (target) {
                        is MaintenanceTarget.Item   -> target.item
                        is MaintenanceTarget.Placed -> target.item
                        else -> return@items
                    }
                    InventoryRow(
                        icon         = item.icon,
                        iconColor    = item.room.iconColor,
                        label        = target.displayLabel,
                        upkeepStatus = null,
                        rating       = ratingFor(target.key, applianceRecords, homeYear, item.expectedLifespanYears),
                        onClick      = { onTapInstance(target) },
                    )
                }
            }
        }
    }
}

// ── Tasks tab ─────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.TasksTab(
    tasks: List<MaintenanceTask>,
    taskRecords: Map<String, MaintenanceTaskRecord>,
    isClaimed: Boolean,
    onMarkDone: (String) -> Unit,
    onAddToCalendar: (MaintenanceTask, Long) -> Unit,
) {
    val sorted = remember(tasks, taskRecords) {
        tasks.sortedBy { task ->
            val last = taskRecords[task.key]?.lastCompleted ?: 0L
            nextDueMillis(task, last)
        }
    }

    if (!isClaimed) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Home, null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.outline)
                Text(
                    "Claim your home to start tracking maintenance tasks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        return
    }

    val doneCount = taskRecords.count { (_, rec) ->
        val task = tasks.find { it.key == rec.taskKey } ?: return@count false
        val nextDue = nextDueMillis(task, rec.lastCompleted)
        nextDue > System.currentTimeMillis()
    }

    // Progress summary
    LinearProgressIndicator(
        progress = { if (tasks.isEmpty()) 0f else doneCount.toFloat() / tasks.size },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    )
    Text(
        "$doneCount of ${tasks.size} tasks up to date",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 4.dp),
    )

    LazyColumn(
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sorted, key = { it.key }) { task ->
            val record       = taskRecords[task.key]
            val lastCompleted = record?.lastCompleted ?: 0L
            val nextDue      = nextDueMillis(task, lastCompleted)
            val label        = dueLabel(nextDue, lastCompleted)
            TaskCard(task, label, nextDue, onMarkDone, onAddToCalendar)
        }
    }
}

// ── Task card ──────────────────────────────────────────────────────────────────

@Composable
private fun TaskCard(
    task: MaintenanceTask,
    label: DueLabel,
    nextDueMillis: Long,
    onMarkDone: (String) -> Unit,
    onAddToCalendar: (MaintenanceTask, Long) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(task.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FreqChip(task.frequency.displayName)
                        PriorityChip(task.priority.displayName, task.priority.color)
                        if (task.estimatedCostDollars > 0f)
                            FreqChip("~\$${task.estimatedCostDollars.toInt()}")
                    }
                }
                Text(
                    label.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = label.color,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Text(
                task.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { onAddToCalendar(task, nextDueMillis) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Outlined.CalendarMonth, "Add to calendar",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(
                    onClick = { onMarkDone(task.key) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Icon(Icons.Outlined.Check, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Done", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// ── Guides tab ────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.GuidesTab(onSelectGuide: (RepairGuide) -> Unit) {
    var selectedCategory by remember { mutableStateOf<GuideCategory?>(null) }

    val displayed = remember(selectedCategory) {
        if (selectedCategory == null) RepairGuides.all
        else RepairGuides.byCategory[selectedCategory] ?: emptyList()
    }

    // Category filter chips
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = selectedCategory == null,
            onClick  = { selectedCategory = null },
            label    = { Text("All") },
        )
        GuideCategory.entries.forEach { cat ->
            FilterChip(
                selected = selectedCategory == cat,
                onClick  = { selectedCategory = if (selectedCategory == cat) null else cat },
                label    = { Text(cat.displayName) },
            )
        }
    }

    LazyColumn(
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(displayed, key = { it.id }) { guide ->
            GuideCard(guide, onClick = { onSelectGuide(guide) })
        }
    }
}

@Composable
private fun GuideCard(guide: RepairGuide, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(guide.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FreqChip(guide.category.displayName)
                    PriorityChip(guide.diyLevel.displayName, guide.diyLevel.color)
                    FreqChip(guide.diyCost)
                }
                Text(
                    "Est. ${guide.timeEstimate}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

// ── Pros (professionals / contacts) tab ───────────────────────────────────────

private val COMMON_TRADES = listOf(
    "Plumber", "Electrician", "HVAC", "Roofer", "Handyman", "Painter",
    "Landscaper", "Pest Control", "Appliance Repair", "General Contractor",
)

@Composable
private fun ColumnScope.ProsTab(
    contacts: List<ProContactEntity>,
    onSave: (ProContactEntity) -> Unit,
    onDelete: (Int) -> Unit,
) {
    var editing  by remember { mutableStateOf<ProContactEntity?>(null) }
    var adding   by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Your go-to professionals for this home.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { adding = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.height(32.dp),
        ) {
            Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add", style = MaterialTheme.typography.labelMedium)
        }
    }

    if (contacts.isEmpty()) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Engineering, null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.outline)
                Text(
                    "No contacts yet. Add your plumber, electrician,\nHVAC tech and more so they're a tap away.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(contacts, key = { it.id }) { contact ->
                ContactCard(
                    contact = contact,
                    onClick = { editing = contact },
                    onCall  = {
                        context.startActivity(
                            Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${contact.phone}")))
                    },
                    onEmail = {
                        context.startActivity(
                            Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:${contact.email}")))
                    },
                )
            }
        }
    }

    if (adding) {
        ContactEditDialog(
            contact   = null,
            onSave    = { onSave(it); adding = false },
            onDismiss = { adding = false },
        )
    }
    editing?.let { contact ->
        ContactEditDialog(
            contact   = contact,
            onSave    = { onSave(it); editing = null },
            onDelete  = { onDelete(contact.id); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ContactCard(
    contact: ProContactEntity,
    onClick: () -> Unit,
    onCall: () -> Unit,
    onEmail: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(contact.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (contact.trade.isNotBlank()) FreqChip(contact.trade)
                    if (contact.phone.isNotBlank()) FreqChip(contact.phone)
                }
                if (contact.notes.isNotBlank()) {
                    Text(
                        contact.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            if (contact.phone.isNotBlank()) {
                IconButton(onClick = onCall, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Call, "Call",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (contact.email.isNotBlank()) {
                IconButton(onClick = onEmail, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Email, "Email",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun ContactEditDialog(
    contact: ProContactEntity?,
    onSave: (ProContactEntity) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var name  by remember { mutableStateOf(contact?.name ?: "") }
    var trade by remember { mutableStateOf(contact?.trade ?: "") }
    var phone by remember { mutableStateOf(contact?.phone ?: "") }
    var email by remember { mutableStateOf(contact?.email ?: "") }
    var notes by remember { mutableStateOf(contact?.notes ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (contact == null) "Add contact" else "Edit contact") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name or company") }, singleLine = true,
                )
                OutlinedTextField(
                    value = trade, onValueChange = { trade = it },
                    label = { Text("Trade") }, singleLine = true,
                )
                // One-tap trade suggestions
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    COMMON_TRADES.forEach { t ->
                        FilterChip(
                            selected = trade == t,
                            onClick  = { trade = t },
                            label    = { Text(t, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    label = { Text("Phone") }, singleLine = true,
                )
                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    label = { Text("Email") }, singleLine = true,
                )
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notes (rates, past jobs, …)") }, minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        (contact ?: ProContactEntity(name = ""))
                            .copy(name = name.trim(), trade = trade.trim(),
                                  phone = phone.trim(), email = email.trim(), notes = notes.trim())
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// ── Guide detail dialog ───────────────────────────────────────────────────────

@Composable
fun GuideDetailDialog(guide: RepairGuide, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(guide.title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Meta row
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PriorityChip(guide.diyLevel.displayName, guide.diyLevel.color)
                    FreqChip(guide.timeEstimate)
                    FreqChip("DIY ${guide.diyCost}")
                    FreqChip("Pro ${guide.proCost}")
                }

                // Warning signs
                if (guide.warningSigns.isNotEmpty()) {
                    SectionHeader("Warning Signs", Icons.Outlined.Warning, Color(0xFFF57F17))
                    guide.warningSigns.forEach { sign ->
                        BulletRow(sign, Color(0xFFF57F17))
                    }
                }

                HorizontalDivider()

                // Steps
                SectionHeader("Steps", Icons.Outlined.FormatListNumbered, MaterialTheme.colorScheme.primary)
                guide.steps.forEachIndexed { index, step ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${index + 1}.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(20.dp),
                        )
                        Text(step, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }

                HorizontalDivider()

                // Safety warnings
                if (guide.safetyWarnings.isNotEmpty()) {
                    SectionHeader("Safety Warnings", Icons.Outlined.Error, Color(0xFFC62828))
                    guide.safetyWarnings.forEach { warning ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Color(0xFFFFF3E0),
                                    RoundedCornerShape(6.dp),
                                )
                                .padding(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                Icons.Outlined.Warning, null,
                                modifier = Modifier.size(16.dp).padding(top = 1.dp),
                                tint = Color(0xFFC62828),
                            )
                            Text(
                                warning,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF7B1F00),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                HorizontalDivider()

                // When to call a pro
                SectionHeader("When to Call a Pro", Icons.Outlined.Phone, MaterialTheme.colorScheme.secondary)
                Text(guide.whenToCallPro, style = MaterialTheme.typography.bodySmall)

                // Prevention tips
                if (guide.preventionTips.isNotEmpty()) {
                    HorizontalDivider()
                    SectionHeader("Prevention", Icons.Outlined.Shield, Color(0xFF2E7D32))
                    guide.preventionTips.forEach { tip ->
                        BulletRow(tip, Color(0xFF2E7D32))
                    }
                }
            }
        },
    )
}

// ── Shared chip / row composables ─────────────────────────────────────────────

@Composable
private fun FreqChip(text: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun PriorityChip(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SectionHeader(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(16.dp), tint = tint)
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = tint)
    }
}

@Composable
private fun BulletRow(text: String, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text("•", style = MaterialTheme.typography.bodySmall, color = color,
            modifier = Modifier.padding(top = 1.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}
