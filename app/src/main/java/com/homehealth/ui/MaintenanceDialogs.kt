package com.homerenderer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.homerenderer.db.ApplianceRecordEntity
import com.homerenderer.db.DocumentEntity
import com.homerenderer.db.UserHomeEntity
import com.homerenderer.model.HiddenAsset
import com.homerenderer.model.HomeSystem
import com.homerenderer.model.RoomItem
import com.homerenderer.model.label
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

    val key: String get() = when (this) {
        is Item    -> instanceKey
        is System  -> system.name
        is Placed  -> "placed:$id"
        is Hidden  -> asset.name
    }
    val displayLabel: String get() = when (this) {
        is Item    -> instanceLabel
        is System  -> when (system) {
            HomeSystem.SOLAR -> "Solar Panels"
            HomeSystem.HVAC  -> "HVAC System"
        }
        is Placed  -> instanceLabel
        is Hidden  -> asset.label
    }
    // Which floor this specific instance lives on (e.g. "F1") — shown alongside displayLabel in
    // the maintenance card so "Sofa 2" is identifiable when a house has several floors/rooms.
    val locationLabel: String? get() = when (this) {
        is Item   -> floorLabel
        is Placed -> floorLabel
        else      -> null
    }
    val lifespan: Int? get() = when (this) {
        is Item    -> item.expectedLifespanYears
        is System  -> system.expectedLifespanYears
        is Placed  -> item.expectedLifespanYears
        is Hidden  -> asset.expectedLifespanYears
    }
}

private val HomeSystem.expectedLifespanYears: Int get() = when (this) {
    HomeSystem.HVAC  -> 15
    HomeSystem.SOLAR -> 25
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
    onSave: (label: String, buildYear: Int?, purchaseYear: Int?) -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val premiumTag = "Premium" + (premiumPrice?.let { " · $it" } ?: "")
    // The claim form only ever runs once, so this dialog is the one place the home can
    // be renamed afterward.
    var labelText by remember { mutableStateOf(entity.label) }
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                       buildYearText.toIntOrNull(), purchaseYearText.toIntOrNull())
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
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
                                isRemoved                             -> "Add to my home"
                                target is MaintenanceTarget.Placed    -> "Remove this item"
                                else                                  -> "Not in my home"
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
