package dev.pcremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pcremote.Mode
import dev.pcremote.Prefs
import dev.pcremote.remote.Cap
import dev.pcremote.remote.Keys
import dev.pcremote.remote.Media
import dev.pcremote.remote.Mods
import dev.pcremote.remote.MouseButton
import dev.pcremote.remote.PcStatus
import dev.pcremote.remote.Remote
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Touchpad

@Composable
fun TouchpadScreen(remote: Remote, prefs: Prefs) {
    var sensitivity by remember { mutableFloatStateOf(prefs.sensitivity) }
    val sens by rememberUpdatedState(sensitivity)
    val haptic = LocalHapticFeedback.current

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            Box(
                Modifier.fillMaxSize().pointerInput(remote) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.uptimeMillis
                        var maxPointers = 1
                        var travelled = 0f
                        var fx = 0f
                        var fy = 0f
                        var scrollAcc = 0f
                        var hScrollAcc = 0f
                        var end = start
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            maxPointers = maxOf(maxPointers, pressed.size)
                            end = event.changes.maxOf { it.uptimeMillis }
                            if (pressed.size == 1 && maxPointers == 1) {
                                val d = pressed[0].positionChange()
                                travelled += d.getDistance()
                                // Simple acceleration: fast flicks move further.
                                val accel = sens * (1f + d.getDistance() / 40f)
                                fx += d.x * accel
                                fy += d.y * accel
                                val ix = fx.toInt()
                                val iy = fy.toInt()
                                if (ix != 0 || iy != 0) {
                                    remote.move(ix, iy)
                                    fx -= ix; fy -= iy
                                }
                            } else if (pressed.size >= 2) {
                                val dy = pressed.map { it.positionChange().y }.average().toFloat()
                                val dx = pressed.map { it.positionChange().x }.average().toFloat()
                                travelled += abs(dx) + abs(dy)
                                scrollAcc += dy
                                hScrollAcc += dx
                                val step = 36f
                                val v = (scrollAcc / step).toInt()
                                val h = (hScrollAcc / step).toInt()
                                if (v != 0 || h != 0) {
                                    // Natural scrolling: content follows the fingers.
                                    remote.scroll(vertical = v, horizontal = -h)
                                    scrollAcc -= v * step
                                    hScrollAcc -= h * step
                                }
                            }
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                        if (travelled < slop && end - start < 300) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            when (maxPointers) {
                                1 -> remote.click(MouseButton.LEFT)
                                2 -> remote.click(MouseButton.RIGHT)
                                else -> remote.click(MouseButton.MIDDLE)
                            }
                        }
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Drag to move · tap to click\nTwo-finger tap: right-click · two-finger drag: scroll",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MouseKey("Left", MouseButton.LEFT, remote, Modifier.weight(2f))
            MouseKey("Middle", MouseButton.MIDDLE, remote, Modifier.weight(1f))
            MouseKey("Right", MouseButton.RIGHT, remote, Modifier.weight(2f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Speed", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(12.dp))
            Slider(
                value = sensitivity, onValueChange = { sensitivity = it },
                onValueChangeFinished = { prefs.sensitivity = sensitivity },
                valueRange = 0.5f..4f, modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Press-and-hold mouse button, so you can drag with it held while moving on the touchpad. */
@Composable
private fun MouseKey(label: String, b: MouseButton, remote: Remote, modifier: Modifier) {
    var down by remember { mutableStateOf(false) }
    Surface(
        color = if (down) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (down) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxSize().pointerInput(remote) {
            detectTapGestures(onPress = {
                down = true
                remote.button(b, true)
                tryAwaitRelease()
                remote.button(b, false)
                down = false
            })
        },
    ) {
        Box(contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelLarge) }
    }
}

// ---------------------------------------------------------------- Media

@Composable
fun MediaScreen(remote: Remote, caps: Set<Cap>, status: PcStatus) {
    val player = status.player
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.MusicNote, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer) }
                Spacer(Modifier.height(12.dp))
                Text(
                    player?.title?.ifBlank { null } ?: if (Cap.NOW_PLAYING in caps) "Nothing playing" else "Media controls",
                    style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
                val sub = player?.artist?.ifBlank { null }
                    ?: if (Cap.NOW_PLAYING in caps) "" else "Sent as media keys to whatever app is playing"
                if (sub.isNotEmpty()) {
                    Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }

                if (Cap.SEEK_TO in caps && player != null && player.lengthSec > 0) {
                    SeekBar(player.positionSec, player.lengthSec) { remote.seekTo(it) }
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoundIcon(Icons.Rounded.SkipPrevious, "Previous") { remote.media(Media.PREVIOUS) }
                    RoundIcon(Icons.Rounded.Replay10, "Seek back") { remote.seekBy(-10) }
                    FilledIconButton(
                        onClick = { remote.media(Media.PLAY_PAUSE) },
                        modifier = Modifier.size(80.dp),
                        shape = RoundedCornerShape(28.dp),
                    ) {
                        Icon(
                            if (player?.status == "Playing") Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            "Play / pause", Modifier.size(40.dp),
                        )
                    }
                    RoundIcon(Icons.Rounded.Forward10, "Seek forward") { remote.seekBy(10) }
                    RoundIcon(Icons.Rounded.SkipNext, "Next") { remote.media(Media.NEXT) }
                }
                Spacer(Modifier.height(4.dp))
                AssistChip(onClick = { remote.media(Media.STOP) }, label = { Text("Stop") }, leadingIcon = { Icon(Icons.Rounded.Stop, null) })
                if (Cap.SEEK_TO !in caps) {
                    Text(
                        "Seek buttons send ← / → to the focused player.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Volume" + (status.volume?.let { " · $it%" } ?: ""), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (Cap.VOLUME_SET in caps && status.volume != null) {
                    LiveSlider(status.volume, 0..100) { remote.setVolume(it) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundIcon(Icons.AutoMirrored.Rounded.VolumeDown, "Volume down") { remote.media(Media.VOLUME_DOWN) }
                    RoundIcon(
                        Icons.AutoMirrored.Rounded.VolumeOff,
                        if (status.muted == true) "Unmute" else "Mute",
                        selected = status.muted == true,
                    ) { remote.media(Media.MUTE) }
                    RoundIcon(Icons.AutoMirrored.Rounded.VolumeUp, "Volume up") { remote.media(Media.VOLUME_UP) }
                }
            }
        }
    }
}

@Composable
private fun SeekBar(position: Double, length: Double, onSeek: (Double) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: position.toFloat()
    Slider(
        value = shown.coerceIn(0f, length.toFloat()),
        onValueChange = { dragging = it },
        onValueChangeFinished = { dragging?.let { onSeek(it.toDouble()) }; dragging = null },
        valueRange = 0f..length.toFloat(),
    )
    Row(Modifier.fillMaxWidth()) {
        Text(fmt(shown.toDouble()), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        Text(fmt(length), style = MaterialTheme.typography.labelMedium)
    }
}

private fun fmt(sec: Double): String {
    val s = sec.toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * A slider that follows the PC's reported value until the user grabs it, then
 * streams new values while dragging (the Wi-Fi remote coalesces them).
 */
@Composable
private fun LiveSlider(remoteValue: Int, range: IntRange, onChange: (Int) -> Unit) {
    var local by remember { mutableStateOf<Float?>(null) }
    var settleAt by remember { mutableIntStateOf(-1) }
    // After release, keep our value until the PC reports it back (avoids a jump back to the old value).
    LaunchedEffect(remoteValue) { if (local != null && settleAt == remoteValue) { local = null; settleAt = -1 } }
    LaunchedEffect(settleAt) {
        if (settleAt >= 0) { kotlinx.coroutines.delay(2500); local = null; settleAt = -1 }
    }
    Slider(
        value = local ?: remoteValue.toFloat(),
        onValueChange = { local = it; onChange(it.roundToInt()) },
        onValueChangeFinished = { settleAt = local?.roundToInt() ?: -1 },
        valueRange = range.first.toFloat()..range.last.toFloat(),
    )
}

@Composable
private fun RoundIcon(icon: ImageVector, desc: String, selected: Boolean = false, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        colors = if (selected) IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
        ) else IconButtonDefaults.filledTonalIconButtonColors(),
    ) { Icon(icon, desc) }
}

// ---------------------------------------------------------------- Keyboard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardScreen(remote: Remote) {
    var live by remember { mutableStateOf(true) }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var mods by remember { mutableIntStateOf(0) }

    /** Sends a key with any sticky modifiers, then releases them (one-shot, like a phone's Shift). */
    fun press(usage: Int, extra: Int = 0) {
        remote.key(usage, mods or extra)
        mods = 0
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Live typing", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (live) "Every keystroke is sent immediately" else "Type, then tap send",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = live, onCheckedChange = { live = it; field = TextFieldValue("") })
        }
        OutlinedTextField(
            value = field,
            onValueChange = { new ->
                if (live) sendDiff(remote, field.text, new.text)
                field = new
            },
            label = { Text(if (live) "Type here" else "Text to send") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                autoCorrectEnabled = !live,
                imeAction = if (live) ImeAction.Send else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onSend = { remote.key(Keys.ENTER); field = TextFieldValue("") }),
            trailingIcon = {
                if (!live) FilledTonalIconButton(onClick = {
                    remote.text(field.text); field = TextFieldValue("")
                }) { Icon(Icons.AutoMirrored.Rounded.Send, "Send text") }
            },
        )
        Text(
            "Typing uses the PC's keyboard layout; symbols assume a US layout.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Modifiers (apply to the next key)", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Ctrl" to Mods.CTRL, "Alt" to Mods.ALT, "Shift" to Mods.SHIFT, "Super" to Mods.SUPER).forEach { (name, bit) ->
                FilterChip(
                    selected = mods and bit != 0,
                    onClick = { mods = mods xor bit },
                    label = { Text(name) },
                )
            }
        }

        Text("Keys", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("Esc") { press(Keys.ESC) }
            KeyButton("Tab") { press(Keys.TAB) }
            KeyIconButton(Icons.AutoMirrored.Rounded.KeyboardReturn, "Enter") { press(Keys.ENTER) }
            KeyIconButton(Icons.AutoMirrored.Rounded.Backspace, "Backspace") { press(Keys.BACKSPACE) }
            KeyButton("Del") { press(Keys.DELETE) }
            KeyButton("Space") { press(Keys.SPACE) }
            KeyButton("Home") { press(Keys.HOME) }
            KeyButton("End") { press(Keys.END) }
            KeyButton("PgUp") { press(Keys.PAGE_UP) }
            KeyButton("PgDn") { press(Keys.PAGE_DOWN) }
            KeyButton("Ins") { press(Keys.INSERT) }
            KeyButton("PrtSc") { press(Keys.PRINT_SCREEN) }
            KeyButton("Menu") { press(Keys.MENU) }
            KeyButton("Super") { press(Keys.NONE, Mods.SUPER) }
        }

        // Arrow cluster
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            KeyIconButton(Icons.Rounded.KeyboardArrowUp, "Up") { press(Keys.UP) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyIconButton(Icons.Rounded.KeyboardArrowLeft, "Left") { press(Keys.LEFT) }
                KeyIconButton(Icons.Rounded.KeyboardArrowDown, "Down") { press(Keys.DOWN) }
                KeyIconButton(Icons.Rounded.KeyboardArrowRight, "Right") { press(Keys.RIGHT) }
            }
        }

        Text("Function keys", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..12).forEach { n -> KeyButton("F$n") { press(Keys.f(n)) } }
        }

        Text("Shortcuts", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Shortcut("Copy") { remote.key(Keys.C, Mods.CTRL) }
            Shortcut("Paste") { remote.key(Keys.V, Mods.CTRL) }
            Shortcut("Cut") { remote.key(Keys.X, Mods.CTRL) }
            Shortcut("Undo") { remote.key(Keys.Z, Mods.CTRL) }
            Shortcut("Select all") { remote.key(Keys.A, Mods.CTRL) }
            Shortcut("Find") { remote.key(Keys.F, Mods.CTRL) }
            Shortcut("Alt+Tab") { remote.key(Keys.TAB, Mods.ALT) }
            Shortcut("Close window") { remote.key(Keys.F4, Mods.ALT) }
            Shortcut("New tab") { remote.key(Keys.T, Mods.CTRL) }
            Shortcut("Close tab") { remote.key(Keys.W, Mods.CTRL) }
            Shortcut("Address bar") { remote.key(Keys.L, Mods.CTRL) }
            Shortcut("Refresh") { remote.key(Keys.F5) }
            Shortcut("Fullscreen") { remote.key(Keys.F11) }
            Shortcut("Fullscreen (video)") { remote.key(Keys.F) }
            Shortcut("Lock screen") { remote.key(Keys.L, Mods.SUPER) }
        }
    }
}

/** Turns an edit of the text field into backspaces + typed characters. */
private fun sendDiff(remote: Remote, old: String, new: String) {
    var common = 0
    val max = minOf(old.length, new.length)
    while (common < max && old[common] == new[common]) common++
    repeat(old.length - common) { remote.key(Keys.BACKSPACE) }
    val added = new.substring(common)
    if (added.isNotEmpty()) remote.text(added)
}

@Composable
private fun KeyButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, shape = RoundedCornerShape(12.dp)) { Text(label) }
}

@Composable
private fun KeyIconButton(icon: ImageVector, desc: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, shape = RoundedCornerShape(12.dp)) { Icon(icon, desc) }
}

@Composable
private fun Shortcut(label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) })
}

// ---------------------------------------------------------------- Display

@Composable
fun DisplayScreen(remote: Remote, caps: Set<Cap>, status: PcStatus, mode: Mode) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Brightness" + (status.brightness?.let { " · $it%" } ?: ""), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (Cap.BRIGHTNESS_SET in caps && status.brightness != null) {
                    LiveSlider(status.brightness, 0..100) { remote.setBrightness(it) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundIcon(Icons.Rounded.BrightnessLow, "Brightness down") { remote.media(Media.BRIGHTNESS_DOWN) }
                    RoundIcon(Icons.Rounded.BrightnessHigh, "Brightness up") { remote.media(Media.BRIGHTNESS_UP) }
                }
                if (mode == Mode.BLUETOOTH) {
                    Text(
                        "Sends brightness keys. Works on laptop screens; external monitors usually need Wi-Fi mode with ddcutil.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Contrast, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Contrast" + (status.contrast?.let { " · $it%" } ?: ""), style = MaterialTheme.typography.titleMedium)
                }
                if (Cap.CONTRAST_SET in caps && status.contrast != null) {
                    LiveSlider(status.contrast, 0..100) { remote.setContrast(it) }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (mode == Mode.BLUETOOTH) "Contrast has no keyboard key, so it needs Wi-Fi mode."
                        else "The agent didn't find a monitor it can control. Install ddcutil on the PC and enable i2c access.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
