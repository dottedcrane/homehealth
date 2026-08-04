package com.homehealth.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.homehealth.data.MaintenanceTask
import com.homehealth.data.TaskFrequency
import com.homehealth.data.toMaintenanceTask
import com.homehealth.db.ApplianceRecordEntity
import com.homehealth.db.DocumentEntity
import com.homehealth.db.MaintenanceTaskRecord
import com.homehealth.db.UserHomeEntity
import com.homehealth.model.AtticType
import com.homehealth.model.CustomTask
import com.homehealth.model.HiddenAsset
import com.homehealth.model.HomeSystem
import com.homehealth.model.RoomItem
import com.homehealth.model.label
import java.util.Calendar

// ── Maintenance target (room item or home system) ─────────────────────────────

sealed class MaintenanceTarget {
    // instanceKey anchors an auto-populated ("default") item to one specific room — see
    // FloorLayout.defaultInstanceKeys — since a house can have several rooms of the same type,
    // each with its own copy of an item (e.g. 3 living rooms => 3 independently tracked sofas).
    data class Item(val item: RoomItem, val instanceKey: String, val instanceLabel: String, val floorLabel: String? = null) : MaintenanceTarget()
    data class System(val system: HomeSystem) : MaintenanceTarget()
    data class Placed(val id: String, val item: RoomItem, val instanceLabel: String, val floorLabel: String? = null) : MaintenanceTarget()
    data class Hidden(val asset: HiddenAsset) : MaintenanceTarget()
    // A user-named CustomAppliance — see the tracked-vs-placed-items skill and CustomAppliance
    // (core-model). Behaves like Placed (real delete, no "not in my home" soft-hide): there's no
    // predefined taxonomy slot to restore it from once gone.
    data class Custom(val id: String, val name: String) : MaintenanceTarget()

    val key: String get() = when (this) {
        is Item    -> instanceKey
        is System  -> system.name
        is Placed  -> "placed:$id"
        is Hidden  -> asset.name
        is Custom  -> "custom:$id"
    }
    val displayLabel: String get() = when (this) {
        is Item    -> instanceLabel
        is System  -> when (system) {
            HomeSystem.SOLAR      -> "Solar Panels"
            HomeSystem.HVAC       -> "HVAC System"
            HomeSystem.EV_BATTERY -> "EV Battery"
        }
        is Placed  -> instanceLabel
        is Hidden  -> asset.label
        is Custom  -> name
    }
    // Which floor this specific instance lives on (e.g. "F1") — shown alongside displayLabel in
    // the maintenance card so "Sofa 2" is identifiable when a house has several floors/rooms.
    val locationLabel: String? get() = when (this) {
        is Item   -> floorLabel
        is Placed -> floorLabel
        else      -> null
    }
    // No fixed category for a user-named appliance, so no expected-lifespan guess — the
    // maintenance dialog already handles a null lifespan (no rating shown, just install
    // year/documents).
    val lifespan: Int? get() = when (this) {
        is Item    -> item.expectedLifespanYears
        is System  -> system.expectedLifespanYears
        is Placed  -> item.expectedLifespanYears
        is Hidden  -> asset.expectedLifespanYears
        is Custom  -> null
    }
}

private val HomeSystem.expectedLifespanYears: Int get() = when (this) {
    HomeSystem.HVAC       -> 15
    HomeSystem.SOLAR      -> 25
    HomeSystem.EV_BATTERY -> 10
}

// ── Lifespan rating (A/B/C) ────────────────────────────────────────────────────
// A = within expected lifespan, B = 100-200% of lifespan elapsed, C = over 200% elapsed.

enum class LifespanRating(val label: String, val color: Color) {
    A("A", Color(0xFF2E7D32)),
    B("B", Color(0xFFF57F17)),
    C("C", Color(0xFFC62828)),
}

fun lifespanRating(remaining: Int?, lifespan: Int?): LifespanRating? {
    if (lifespan == null || remaining == null || lifespan <= 0) return null
    val elapsedYears = lifespan - remaining
    val ratio = elapsedYears.toFloat() / lifespan.toFloat()
    return when {
        ratio <= 1.0f -> LifespanRating.A
        ratio <= 2.0f -> LifespanRating.B
        else          -> LifespanRating.C
    }
}

// ── Claim dialog ─────────────────────────────────────────────────────────────

// The first-claim form — asks for the home's name and dates exactly once. Later claims
// (model switches) reuse the saved info directly and never reopen this; the info stays
// editable behind the Home icon (HomeDatesDialog).
@Composable
fun ClaimDialog(
    neighborLabel: String,
    onConfirm: (label: String, buildYear: Int?, purchaseYear: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember(neighborLabel) { mutableStateOf(neighborLabel) }
    var buildYearText by remember { mutableStateOf("") }
    var purchaseYearText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Narrower than the default AlertDialog width so the home preview stays
        // partially visible behind the popup instead of being fully covered.
        modifier = Modifier.fillMaxWidth(0.8f),
        title = { Text("Claim This Home") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Home name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = buildYearText,
                    onValueChange = { if (it.length <= 4) buildYearText = it.filter { c -> c.isDigit() } },
                    label = { Text("Year built (optional)") },
                    placeholder = { Text("e.g. 1998") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = purchaseYearText,
                    onValueChange = { if (it.length <= 4) purchaseYearText = it.filter { c -> c.isDigit() } },
                    label = { Text("Year purchased (optional)") },
                    placeholder = { Text("e.g. 2015") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Saved privately on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(
                    label.ifBlank { neighborLabel },
                    buildYearText.toIntOrNull(),
                    purchaseYearText.toIntOrNull(),
                )
            }) { Text("Save & Claim") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ── Home dates dialog ─────────────────────────────────────────────────────────

@Composable
fun HomeDatesDialog(
    entity: UserHomeEntity,
    isPremium: Boolean,
    // Live summary of the CURRENTLY claimed model — built by the caller from the in-memory
    // home state (not the entity row) so the dialog always reflects the latest claim and
    // edits: [specs] = style/floors/beds/baths, [equipment] = features + systems.
    specs: String = "",
    equipment: String = "",
    // Localized Premium price from Play (e.g. "$1.99") — shown on the locked buttons so
    // the cost is visible before the purchase sheet opens; null until Play responds.
    premiumPrice: String? = null,
    // The home's RESOLVED attic (never the raw nullable) — the caller has already applied the
    // roof's default, so this dialog always shows the space the home actually has.
    atticType: AtticType = AtticType.FULL,
    onSave: (label: String, buildYear: Int?, purchaseYear: Int?, atticType: AtticType) -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val premiumTag = "Premium" + (premiumPrice?.let { " · $it" } ?: "")
    // The claim form only ever runs once, so this dialog is the one place the home can
    // be renamed afterward.
    var labelText by remember { mutableStateOf(entity.label) }
    var attic by remember { mutableStateOf(atticType) }
    var buildYearText by remember { mutableStateOf(entity.buildYear?.toString() ?: "") }
    var purchaseYearText by remember { mutableStateOf(entity.purchaseYear?.toString() ?: "") }
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)

    AlertDialog(
        onDismissRequest = onDismiss,
        // Titled with the CURRENT model (neighborKey updates on every claim) — the user's
        // own home name persists across model switches, so titling with it would read as
        // stale after switching models; the name lives in the editable field below instead.
        title = {
            Text(entity.neighborKey.ifBlank {
                entity.homeStyle.lowercase().replaceFirstChar { it.uppercase() }
            })
        },
        text = {
            // Scrollable: with the model summary, three fields, the attic choice and the two
            // data buttons this body outgrows the dialog's height cap in landscape and at large
            // font scales — unscrolled, the tail (Restore) is simply unreachable.
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Current model at a glance, then the saved household data below.
                if (specs.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(specs, style = MaterialTheme.typography.bodyMedium)
                        if (equipment.isNotBlank()) {
                            Text(equipment,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                }
                val purchaseYear = purchaseYearText.toIntOrNull()
                if (purchaseYear != null) {
                    Text(
                        "Owned for ${currentYear - purchaseYear} years",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedTextField(
                    value = labelText,
                    onValueChange = { labelText = it },
                    label = { Text("Home name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = buildYearText,
                    onValueChange = { if (it.length <= 4) buildYearText = it.filter { c -> c.isDigit() } },
                    label = { Text("Year built") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = purchaseYearText,
                    onValueChange = { if (it.length <= 4) purchaseYearText = it.filter { c -> c.isDigit() } },
                    label = { Text("Year purchased") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                // What's above the top storey. Also settable by tapping the attic's own floor from
                // inside; both write the same state. This changes the SPACE, never what the home is
                // on the hook for — a closet still declares its ducts and insulation, from the
                // attic pane's own row (see AtticType).
                AtticTypeChoices(attic) { attic = it }
                OutlinedButton(
                    onClick = onBackup,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = if (isPremium) Icons.Outlined.Backup else Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (isPremium) "Backup App Data" else "Backup ($premiumTag)")
                }
                OutlinedButton(
                    onClick = onRestore,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = if (isPremium) Icons.Outlined.SettingsBackupRestore else Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (isPremium) "Restore App Data" else "Restore ($premiumTag)")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(labelText.ifBlank { entity.label },
                       buildYearText.toIntOrNull(), purchaseYearText.toIntOrNull(), attic)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ── Attic type ────────────────────────────────────────────────────────────────
//
// What the home has above its top storey. Offered in two places that share this radio row: the
// home-info dialog above, and [AtticTypeDialog] below, opened by tapping the attic's bare floor —
// the same gesture that asks a room what it is from inside it.

// One line, both options, no explanatory sub-captions: the two labels ("Attic", "Utility Closet")
// say the whole choice, and the height they used to cost is what pushed the home dialog's data
// buttons off the bottom.
@Composable
private fun AtticTypeChoices(selected: AtticType, onSelect: (AtticType) -> Unit) {
    // FlowRow, not Row: the options are content-sized rather than half-width each, so "Utility
    // Closet" always gets the room it needs, and at very large font scales the pair drops to two
    // lines instead of clipping — the failure this whole dialog was just fixed for.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AtticType.entries.forEach { type ->
            Row(
                modifier = Modifier.clickable { onSelect(type) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = type == selected, onClick = { onSelect(type) })
                Text(type.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// Opened from inside the attic by tapping its bare boards. Deliberately carries no warning copy:
// the choice changes what the space looks like, never what the home is on the hook for — which of
// the mechanicals this home actually has stays the pane row's question.
@Composable
fun AtticTypeDialog(
    current: AtticType,
    onSelect: (AtticType) -> Unit,
    onDismiss: () -> Unit,
) {
    var choice by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What's up here?") },
        text  = { AtticTypeChoices(choice) { choice = it } },
        confirmButton = { Button(onClick = { onSelect(choice) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Appliance maintenance dialog ──────────────────────────────────────────────

@Composable
fun ApplianceMaintenanceDialog(
    target: MaintenanceTarget,
    record: ApplianceRecordEntity?,
    homeYear: Int?,
    documents: List<DocumentEntity>,
    onSave: (installYear: Int?) -> Unit,
    onAddDocument: () -> Unit,
    onDeleteDocument: (Int) -> Unit,
    // Only ever non-empty/non-null when target is MaintenanceTarget.Custom — the caller
    // pre-filters customTasks to this target, same as documents above.
    customTasks: List<CustomTask> = emptyList(),
    onAddCustomTask: (() -> Unit)? = null,
    onDeleteCustomTask: ((String) -> Unit)? = null,
    // This item's built-in recurring tasks (resolved by tasksForTarget) with the records that
    // date them, shown above the user-created ones. Without these the card can't answer the
    // question the room pane's red icon just raised — it used to list custom tasks only, so
    // "why is this overdue?" lived two screens away in the hub's To-Do tab.
    dueTasks: List<MaintenanceTask> = emptyList(),
    taskRecords: Map<String, MaintenanceTaskRecord> = emptyMap(),
    onMarkTaskDone: ((String) -> Unit)? = null,
    onNotInHome: (() -> Unit)? = null,
    isRemoved: Boolean = false,
    onDismiss: () -> Unit,
) {
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    var installYearText by remember(record) {
        mutableStateOf(record?.installYear?.toString() ?: "")
    }

    val installYear = installYearText.toIntOrNull()
    val effectiveYear = installYear ?: homeYear
    val lifespan = target.lifespan
    val remaining = if (lifespan != null && effectiveYear != null) {
        (effectiveYear + lifespan) - currentYear
    } else null

    val statusColor = when {
        remaining == null         -> Color.Gray
        remaining > 5             -> Color(0xFF2E7D32)
        remaining > 0             -> Color(0xFFF57F17)
        else                      -> Color(0xFFC62828)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                target.locationLabel?.let { "${target.displayLabel} - $it" } ?: target.displayLabel,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ── Lifespan info ──────────────────────────────────────────
                if (lifespan != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Expected lifespan", style = MaterialTheme.typography.labelSmall)
                            Text("$lifespan yrs", style = MaterialTheme.typography.bodyLarge)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Remaining", style = MaterialTheme.typography.labelSmall)
                            if (remaining != null) {
                                val label = when {
                                    remaining > 0 -> "$remaining yrs"
                                    remaining == 0 -> "Expires this year"
                                    else -> "${-remaining} yrs overdue"
                                }
                                Text(label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = statusColor,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            } else {
                                Text("—", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
                            }
                        }
                    }
                    if (installYear == null && homeYear != null) {
                        Text(
                            "Using home build year ($homeYear) as install estimate",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    lifespanRating(remaining, lifespan)?.let { rating ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = rating.color.copy(alpha = 0.15f),
                            ) {
                                Text(
                                    "Rating ${rating.label}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = rating.color,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (rating) {
                                    LifespanRating.A -> "Within expected lifespan"
                                    LifespanRating.B -> "Past expected lifespan"
                                    LifespanRating.C -> "Well past expected lifespan"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }

                HorizontalDivider()

                // ── Install year input ─────────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = installYearText,
                        onValueChange = { if (it.length <= 4) installYearText = it.filter { c -> c.isDigit() } },
                        label = { Text("Install year") },
                        placeholder = { Text("e.g. 2018") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        installYearText = currentYear.toString()
                    }) { Text("Now") }
                }

                HorizontalDivider()

                // ── Documents ──────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Documents & Warranties", style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = onAddDocument, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Add, "Add document",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (documents.isEmpty()) {
                    Text(
                        "No documents attached",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    documents.forEach { doc ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Outlined.Description, null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Text(doc.label,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                            IconButton(
                                onClick = { onDeleteDocument(doc.id) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(Icons.Outlined.Delete, "Remove",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                // ── Custom tasks — create/view/delete only; marking done stays in the Tasks
                // tab, which already has the due-date/mark-done UI (see CustomTask.
                // toMaintenanceTask, merged into that tab's live task list). Available for
                // every target, not just Custom appliances — onAddCustomTask/onDeleteCustomTask
                // are null only if the caller genuinely has nowhere to route them. ──
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Tasks", style = MaterialTheme.typography.labelMedium)
                    onAddCustomTask?.let { add ->
                        IconButton(onClick = add, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.Add, "Add task",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                // Built-in tasks first — these are what drive the room pane's status color,
                // so they answer "why is this red?" before the user-created ones below.
                dueTasks.forEach { task ->
                    val rec  = taskRecords[task.key]
                    val done = rec?.lastCompleted ?: 0L
                    val due  = dueLabel(effectiveDueMillis(task, rec), done)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Outlined.Schedule, null,
                            modifier = Modifier.size(18.dp),
                            tint = due.color)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(task.title, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            Text("${task.frequency.displayName} · ${due.text}",
                                style = MaterialTheme.typography.labelSmall,
                                color = due.color)
                        }
                        onMarkTaskDone?.let { markDone ->
                            IconButton(
                                onClick  = { markDone(task.key) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(Icons.Outlined.CheckCircle, "Mark done",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                if (customTasks.isEmpty() && dueTasks.isEmpty()) {
                    Text(
                        "No recurring tasks yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    customTasks.forEach { task ->
                        // Dated exactly like the built-in rows above — a user-created task can
                        // drive this item's status colour too, so it has to be able to explain
                        // it. toMaintenanceTask is the same conversion the To-Do tab uses, so
                        // the two read one shared task_records row rather than diverging.
                        val asTask = task.toMaintenanceTask()
                        val rec    = taskRecords[asTask.key]
                        val due    = dueLabel(effectiveDueMillis(asTask, rec), rec?.lastCompleted ?: 0L)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Icons.Outlined.Schedule, null,
                                modifier = Modifier.size(18.dp),
                                tint = due.color)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(task.title, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                Text("${task.frequency.displayName} · ${due.text}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = due.color)
                            }
                            onMarkTaskDone?.let { markDone ->
                                IconButton(
                                    onClick  = { markDone(asTask.key) },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(Icons.Outlined.CheckCircle, "Mark done",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            IconButton(
                                onClick = { onDeleteCustomTask?.invoke(task.id) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(Icons.Outlined.Delete, "Remove",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                // ── Not in my home / add to my home — toggle whether this item is tracked ──
                onNotInHome?.let { toggle ->
                    HorizontalDivider()
                    OutlinedButton(
                        onClick  = toggle,
                        modifier = Modifier.fillMaxWidth(),
                        colors   = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (isRemoved) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.error),
                    ) {
                        Icon(
                            if (isRemoved) Icons.Outlined.AddCircleOutline else Icons.Outlined.RemoveCircleOutline,
                            null, modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                isRemoved                                                        -> "Add to my home"
                                target is MaintenanceTarget.Placed || target is MaintenanceTarget.Custom -> "Remove this item"
                                else                                                              -> "Not in my home"
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(installYearText.toIntOrNull()) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

// (The "add another copy" dialog is gone — furniture is placed by dragging an icon from
// the room pane's tray straight into the 3D room.)

// ── Add custom appliance dialog ───────────────────────────────────────────────

@Composable
fun AddCustomApplianceDialog(
    onAdd: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Appliance") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("e.g. Water Softener") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onAdd(name.trim()) }, enabled = name.isNotBlank()) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ── Add custom task dialog ────────────────────────────────────────────────────

@Composable
fun AddCustomTaskDialog(
    onAdd: (title: String, frequency: TaskFrequency) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf(TaskFrequency.MONTHLY) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task") },
                    placeholder = { Text("e.g. Check salt level") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Repeats", style = MaterialTheme.typography.labelMedium)
                    TaskFrequency.entries.forEach { freq ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { frequency = freq },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = frequency == freq, onClick = { frequency = freq })
                            Text(freq.displayName, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(title.trim(), frequency) }, enabled = title.isNotBlank()) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
