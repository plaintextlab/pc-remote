package dev.pcremote.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import dev.pcremote.Mode
import dev.pcremote.PcRemoteApp
import dev.pcremote.remote.ConnState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as PcRemoteApp
        setContent { PcRemoteTheme { RemoteApp(app) } }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Leaving the app (not rotating) releases the Bluetooth keyboard role.
        if (isFinishing) (application as PcRemoteApp).bluetooth.shutdown()
    }
}

@Composable
fun PcRemoteTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFB8C8FF), secondary = Color(0xFFC1C5DD), tertiary = Color(0xFFE2BBDB))
        else -> lightColorScheme(primary = Color(0xFF475D92), secondary = Color(0xFF585E72), tertiary = Color(0xFF725470))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Tab(val label: String, val icon: ImageVector) {
    TOUCHPAD("Touchpad", Icons.Rounded.Mouse),
    MEDIA("Media", Icons.Rounded.MusicNote),
    KEYBOARD("Keyboard", Icons.Rounded.Keyboard),
    DISPLAY("Display", Icons.Rounded.LightMode),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteApp(app: PcRemoteApp) {
    val mode by app.mode.collectAsState()
    val remote = if (mode == Mode.BLUETOOTH) app.bluetooth else app.wifi
    val conn by remote.connection.collectAsState()
    var showConnect by rememberSaveable { mutableStateOf(true) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // Jump to the controls as soon as a connection comes up.
    var wasConnected by remember { mutableStateOf(conn is ConnState.Connected) }
    LaunchedEffect(conn) {
        val now = conn is ConnState.Connected
        if (now && !wasConnected) showConnect = false
        wasConnected = now
    }
    BackHandler(enabled = showConnect && conn is ConnState.Connected) { showConnect = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (showConnect) "Connect" else "PC Remote")
                        Text(
                            statusText(conn),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (conn is ConnState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showConnect = !showConnect }) {
                        Icon(
                            when {
                                showConnect -> Icons.Rounded.Close
                                mode == Mode.BLUETOOTH -> Icons.Rounded.Bluetooth
                                else -> Icons.Rounded.Wifi
                            },
                            contentDescription = if (showConnect) "Close connection settings" else "Connection settings",
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (!showConnect) {
                NavigationBar {
                    Tab.entries.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (showConnect) {
                ConnectScreen(app, mode)
            } else {
                val caps by remote.caps.collectAsState()
                val status by remote.status.collectAsState()
                when (Tab.entries[tab]) {
                    Tab.TOUCHPAD -> TouchpadScreen(remote, app.prefs)
                    Tab.MEDIA -> MediaScreen(remote, caps, status)
                    Tab.KEYBOARD -> KeyboardScreen(remote)
                    Tab.DISPLAY -> DisplayScreen(remote, caps, status, mode)
                }
            }
        }
    }
}

private fun statusText(c: ConnState) = when (c) {
    ConnState.Idle -> "Not connected"
    is ConnState.Working -> c.message
    is ConnState.Connected -> "Connected to ${c.name}"
    is ConnState.Failed -> c.message
}
