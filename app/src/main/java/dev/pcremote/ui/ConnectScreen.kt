package dev.pcremote.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.pcremote.Mode
import dev.pcremote.PcRemoteApp
import dev.pcremote.remote.ConnState
import dev.pcremote.remote.DiscoveredPc
import dev.pcremote.remote.WifiRemote
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(app: PcRemoteApp, mode: Mode) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Mode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { app.setMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size),
                    icon = { Icon(if (m == Mode.BLUETOOTH) Icons.Rounded.Bluetooth else Icons.Rounded.Wifi, null, Modifier.size(18.dp)) },
                ) { Text(if (m == Mode.BLUETOOTH) "Bluetooth" else "Wi-Fi") }
            }
        }
        when (mode) {
            Mode.BLUETOOTH -> BluetoothSection(app)
            Mode.WIFI -> WifiSection(app)
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private val btPermissions =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    else emptyArray()

@SuppressLint("MissingPermission")
@Composable
private fun BluetoothSection(app: PcRemoteApp) {
    val ctx = LocalContext.current
    val bt = app.bluetooth
    val registered by bt.registered.collectAsState()
    val conn by bt.connection.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }

    fun hasPerms() = btPermissions.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    var granted by remember { mutableStateOf(hasPerms()) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPerms()
        if (granted) bt.start()
    }
    val discoverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh++
        if (bt.isEnabled) bt.start()
    }

    LaunchedEffect(granted) { if (granted && bt.isEnabled) bt.start() }

    InfoCard(
        "No PC software needed",
        "Your phone acts as a Bluetooth keyboard, mouse and media remote. " +
            "Linux supports this out of the box. Contrast, exact volume and \"now playing\" need Wi-Fi mode.",
    )

    if (!bt.isSupported) {
        Text("This phone has no Bluetooth.", color = MaterialTheme.colorScheme.error)
        return
    }
    if (!granted) {
        Button(onClick = { permLauncher.launch(btPermissions) }, Modifier.fillMaxWidth()) { Text("Allow Bluetooth access") }
        return
    }
    if (!bt.isEnabled) {
        Button(onClick = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }, Modifier.fillMaxWidth()) {
            Text("Turn on Bluetooth")
        }
        return
    }

    Text("First time? Pair once:", style = MaterialTheme.typography.titleMedium)
    Text(
        "1. Tap \"Make phone visible\".\n" +
            "2. On the PC, open Bluetooth settings (or run bluetoothctl) and pair with this phone.\n" +
            "3. Come back here and tap the PC below.\n\n" +
            "If the PC was paired before you installed this app, remove it on both sides and pair again, " +
            "so the PC learns that the phone is a keyboard.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            enabled = registered,
            onClick = {
                discoverLauncher.launch(
                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120),
                )
            },
        ) { Text("Make phone visible") }
        OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Bluetooth settings") }
    }
    if (!registered) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(if (conn is ConnState.Failed) (conn as ConnState.Failed).message else "Starting Bluetooth keyboard…")
        }
        if (conn is ConnState.Failed) TextButton(onClick = { bt.start() }) { Text("Retry") }
    }

    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Paired devices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = { refresh++ }) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Refresh") }
    }
    val devices: List<BluetoothDevice> = remember(refresh, registered) { bt.bondedDevices() }
    if (devices.isEmpty()) Text("Nothing paired yet.", style = MaterialTheme.typography.bodyMedium)
    val connectedName = (conn as? ConnState.Connected)?.name
    devices.forEach { d ->
        val name = d.name ?: d.address
        val isLast = d.address == app.prefs.btAddress
        ListItem(
            headlineContent = { Text(name) },
            supportingContent = {
                Text(
                    when {
                        connectedName == name -> "Connected"
                        isLast -> "Last used · ${d.address}"
                        else -> d.address
                    },
                )
            },
            leadingContent = { Icon(Icons.Rounded.Computer, null) },
            colors = ListItemDefaults.colors(
                containerColor = if (connectedName == name) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier.clickable {
                app.prefs.btAddress = d.address
                bt.connect(d)
            },
        )
    }
}

@Composable
private fun WifiSection(app: PcRemoteApp) {
    val wifi = app.wifi
    val conn by wifi.connection.collectAsState()
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(app.prefs.host) }
    var port by remember { mutableStateOf(app.prefs.port.toString()) }
    var code by remember { mutableStateOf(app.prefs.code) }
    var searching by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<DiscoveredPc>>(emptyList()) }

    fun search() {
        searching = true
        scope.launch {
            found = WifiRemote.discover()
            searching = false
        }
    }
    LaunchedEffect(Unit) { search() }

    InfoCard(
        "Needs the small PC agent",
        "Run agent/pc-remote-agent.py on the PC (one Python file, no installs). " +
            "It prints a pairing code. This mode adds contrast, exact volume and brightness, a seek bar and \"now playing\".",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("PCs on this network", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else TextButton(onClick = { search() }) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Search") }
    }
    if (!searching && found.isEmpty()) {
        Text("None found. Is the agent running, and is the phone on the same Wi-Fi? You can enter the IP below.", style = MaterialTheme.typography.bodyMedium)
    }
    found.forEach { pc ->
        ListItem(
            headlineContent = { Text(pc.name) },
            supportingContent = { Text("${pc.host}:${pc.port}") },
            leadingContent = { Icon(Icons.Rounded.Computer, null) },
            modifier = Modifier.clickable { host = pc.host; port = pc.port.toString() },
        )
    }

    HorizontalDivider()
    OutlinedTextField(
        value = host, onValueChange = { host = it.trim() }, label = { Text("PC address") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = code, onValueChange = { code = it.uppercase().filter(Char::isLetterOrDigit).take(12) },
            label = { Text("Pairing code") }, singleLine = true, modifier = Modifier.weight(2f),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        )
        OutlinedTextField(
            value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    val busy = conn is ConnState.Working
    Button(
        enabled = host.isNotBlank() && code.isNotBlank() && !busy,
        onClick = {
            val p = port.toIntOrNull() ?: WifiRemote.DEFAULT_PORT
            app.prefs.host = host; app.prefs.port = p; app.prefs.code = code
            wifi.connect(host, p, code)
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (busy) "Connecting…" else "Connect") }
    if (conn is ConnState.Connected) {
        OutlinedButton(onClick = { wifi.disconnect() }, Modifier.fillMaxWidth()) { Text("Disconnect") }
    }
}
