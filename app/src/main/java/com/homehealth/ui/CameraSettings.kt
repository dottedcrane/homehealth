package com.homehealth.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.filament.utils.Manipulator
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.math.Transform

// ── Per-scene camera configuration ────────────────────────────────────────────
// Each scene (House, Neighborhood, Room, Floor Plan) keeps its own independent
// CameraSettings so adjusting one scene's camera feel never affects another.

enum class CameraMode { ORBIT, MAP }

data class CameraSettings(
    val mode: CameraMode = CameraMode.ORBIT,
    val rotateEnabled: Boolean = true,
    val panEnabled: Boolean = true,
    val zoomEnabled: Boolean = true,
    val orbitSpeed: Float = 0.003f,
    val zoomSpeed: Float = 0.05f,
    // Bumped to force the camera back to its default framing without touching any other field.
    val resetTick: Int = 0,
)

// Wraps a manipulator so pan / rotate(orbit) / zoom can be switched on and off live, without
// rebuilding the underlying Filament Manipulator — mode and speed changes are baked in at
// Manipulator.Builder time, so those still go through a full rebuild (see [rememberConfigurableCameraManipulator]).
// A single-finger drag arrives via grabBegin(strafe = false) (orbit); a two-finger drag arrives
// via grabBegin(strafe = true) (pan) — the flag is latched for the matching grabUpdate/grabEnd calls.
private class GatedCameraManipulator(
    private val inner: CameraGestureDetector.CameraManipulator,
    private val rotateEnabled: () -> Boolean,
    private val panEnabled: () -> Boolean,
    private val zoomEnabled: () -> Boolean,
) : CameraGestureDetector.CameraManipulator {
    private var grabAllowed = false

    override fun setViewport(width: Int, height: Int) = inner.setViewport(width, height)
    override fun getTransform(): Transform = inner.getTransform()

    override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
        grabAllowed = if (strafe) panEnabled() else rotateEnabled()
        if (grabAllowed) inner.grabBegin(x, y, strafe)
    }

    override fun grabUpdate(x: Int, y: Int) {
        if (grabAllowed) inner.grabUpdate(x, y)
    }

    override fun grabEnd() {
        if (grabAllowed) inner.grabEnd()
        grabAllowed = false
    }

    override fun scrollBegin(x: Int, y: Int, separation: Float) {
        if (zoomEnabled()) inner.scrollBegin(x, y, separation)
    }

    override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) {
        if (zoomEnabled()) inner.scrollUpdate(x, y, prevSeparation, currSeparation)
    }

    override fun scrollEnd() {
        if (zoomEnabled()) inner.scrollEnd()
    }

    override fun update(deltaTime: Float) = inner.update(deltaTime)
}

/**
 * Builds a camera manipulator from per-scene [settings]. The Filament Manipulator is rebuilt
 * whenever [settings]'s mode/speed/resetTick change (those are baked in at Builder time), plus
 * any [rebuildKeys] the caller already rebuilds on (e.g. floor, active zone). Gesture on/off
 * toggles apply live via [GatedCameraManipulator] and never trigger a rebuild.
 */
@Composable
fun rememberConfigurableCameraManipulator(
    settings: CameraSettings,
    orbitHomePosition: Float3,
    targetPosition: Float3,
    mapExtent: Float = 40f,
    vararg rebuildKeys: Any?,
): CameraGestureDetector.CameraManipulator {
    val settingsState = rememberUpdatedState(settings)
    return key(settings.mode, settings.orbitSpeed, settings.zoomSpeed, settings.resetTick, *rebuildKeys) {
        // Builds the manipulator directly via `remember` rather than the library's
        // `rememberCameraManipulator` wrapper — that wrapper is just `remember(creator)` with
        // no other independent behavior, and since we always supply our own `creator` (never
        // relying on its default), calling it added nothing except a real crash: on this
        // project's toolchain the wrapper's default-value handling for `creator` throws
        // ClassCastException: CameraSettingsKt$$ExternalSyntheticLambda0 cannot be cast to
        // CameraGestureDetector$CameraManipulator (reliably reproducible via "Reset view").
        remember {
            val builder = Manipulator.Builder()
                .targetPosition(targetPosition.x, targetPosition.y, targetPosition.z)
                .orbitHomePosition(orbitHomePosition.x, orbitHomePosition.y, orbitHomePosition.z)
                .orbitSpeed(settings.orbitSpeed, settings.orbitSpeed)
                .zoomSpeed(settings.zoomSpeed)
            val filamentMode = when (settings.mode) {
                CameraMode.ORBIT -> Manipulator.Mode.ORBIT
                CameraMode.MAP -> {
                    builder.mapExtent(mapExtent, mapExtent)
                    Manipulator.Mode.MAP
                }
            }
            val default = CameraGestureDetector.DefaultCameraManipulator(builder.build(filamentMode))
            GatedCameraManipulator(
                inner = default,
                rotateEnabled = { settingsState.value.rotateEnabled },
                panEnabled = { settingsState.value.panEnabled },
                zoomEnabled = { settingsState.value.zoomEnabled },
            )
        }
    }
}

// ── Gear icon + settings dialog, shared by every scene's pane ────────────────

@Composable
fun CameraGearButton(
    settings: CameraSettings,
    onSettingsChange: (CameraSettings) -> Unit,
    modifier: Modifier = Modifier,
    // Room view only — when provided, the dialog gains an "Angle" preset row (Eye/Corner/Top).
    camAngle: RoomCamAngle? = null,
    onCamAngleChange: ((RoomCamAngle) -> Unit)? = null,
) {
    var showDialog by remember { mutableStateOf(false) }
    IconButton(onClick = { showDialog = true }, modifier = modifier) {
        Icon(Icons.Outlined.Settings, contentDescription = "Camera settings")
    }
    if (showDialog) {
        CameraSettingsDialog(
            settings = settings,
            onSettingsChange = onSettingsChange,
            onDismiss = { showDialog = false },
            camAngle = camAngle,
            onCamAngleChange = onCamAngleChange,
        )
    }
}

@Composable
private fun CameraSettingsDialog(
    settings: CameraSettings,
    onSettingsChange: (CameraSettings) -> Unit,
    onDismiss: () -> Unit,
    camAngle: RoomCamAngle? = null,
    onCamAngleChange: ((RoomCamAngle) -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Camera") },
        text = {
            // Scrollable: with the Angle presets the panel is taller than a landscape dialog.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CameraSettingsPanel(
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    camAngle = camAngle,
                    // Picking an angle closes the dialog right away — the whole point of the
                    // preset is the new framing, and the dialog's scrim would hide it.
                    onCamAngleChange = onCamAngleChange?.let { change ->
                        { angle: RoomCamAngle -> change(angle); onDismiss() }
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
internal fun CameraSettingsPanel(
    settings: CameraSettings,
    onSettingsChange: (CameraSettings) -> Unit,
    camAngle: RoomCamAngle? = null,
    onCamAngleChange: ((RoomCamAngle) -> Unit)? = null,
) {
    // Not scrollable itself — each host (room dialog, floor-plan settings card) provides its
    // own scroll container sized to its space, so scrolling never nests.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (camAngle != null && onCamAngleChange != null) {
            Text("Angle", style = MaterialTheme.typography.labelLarge)
            Column {
                RoomCamAngle.entries.forEach { angle ->
                    CameraRadioRow(
                        icon = angle.icon,
                        label = angle.label,
                        selected = camAngle == angle,
                    ) { onCamAngleChange(angle) }
                }
            }
        }

        Text("Mode", style = MaterialTheme.typography.labelLarge)
        Column {
            CameraMode.entries.forEach { m ->
                CameraRadioRow(
                    icon = m.icon,
                    label = m.displayLabel,
                    selected = settings.mode == m,
                ) { onSettingsChange(settings.copy(mode = m)) }
            }
        }

        Text("Gestures", style = MaterialTheme.typography.labelLarge)
        CameraToggleRow("Rotate", Icons.Outlined.RotateLeft, settings.rotateEnabled) { onSettingsChange(settings.copy(rotateEnabled = it)) }
        CameraToggleRow("Pan", Icons.Outlined.OpenWith, settings.panEnabled) { onSettingsChange(settings.copy(panEnabled = it)) }
        CameraToggleRow("Zoom", Icons.Outlined.ZoomIn, settings.zoomEnabled) { onSettingsChange(settings.copy(zoomEnabled = it)) }

        Text("Sensitivity", style = MaterialTheme.typography.labelLarge)
        Column {
            Text("Orbit speed", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = settings.orbitSpeed,
                onValueChange = { onSettingsChange(settings.copy(orbitSpeed = it)) },
                valueRange = 0.001f..0.010f,
                enabled = settings.mode == CameraMode.ORBIT,
            )
        }
        Column {
            Text("Zoom speed", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = settings.zoomSpeed,
                onValueChange = { onSettingsChange(settings.copy(zoomSpeed = it)) },
                valueRange = 0.01f..0.20f,
            )
        }

        OutlinedButton(onClick = { onSettingsChange(settings.copy(resetTick = settings.resetTick + 1)) }) {
            Icon(Icons.Outlined.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("Reset view", modifier = Modifier.padding(start = 6.dp))
        }
    }
}

// Icons for the room-view camera angle presets.
private val RoomCamAngle.icon: ImageVector get() = when (this) {
    RoomCamAngle.DOORWAY  -> Icons.Outlined.Visibility   // eye level, standing in the door
    RoomCamAngle.CORNER   -> Icons.Outlined.Videocam     // elevated corner overview
    RoomCamAngle.OVERHEAD -> Icons.Outlined.Map          // straight down, floor-plan style
}

private val CameraMode.icon: ImageVector get() = when (this) {
    CameraMode.ORBIT -> Icons.Outlined.RotateLeft
    CameraMode.MAP   -> Icons.Outlined.OpenWith
}

private val CameraMode.displayLabel: String get() = when (this) {
    CameraMode.ORBIT -> "Orbit"
    CameraMode.MAP   -> "Pan"
}

// One radio option: tap anywhere on the row to select.
@Composable
private fun CameraRadioRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CameraToggleRow(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
