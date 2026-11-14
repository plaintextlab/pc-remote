package dev.pcremote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import dev.pcremote.Mode
import dev.pcremote.Prefs
import dev.pcremote.remote.Cap
import dev.pcremote.remote.ConnState
import dev.pcremote.remote.Media
import dev.pcremote.remote.MouseButton
import dev.pcremote.remote.PcStatus
import dev.pcremote.remote.PlayerInfo
import dev.pcremote.remote.Remote
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/** Renders each screen to a PNG (`./gradlew recordPaparazziDebug`) so layout can be checked without a device. */
class ScreensTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6)

    private class FakeRemote(caps: Set<Cap>, status: PcStatus) : Remote {
        override val connection = MutableStateFlow<ConnState>(ConnState.Connected("omen"))
        override val caps = MutableStateFlow(caps)
        override val status = MutableStateFlow(status)
        override fun key(usage: Int, mods: Int) {}
        override fun text(s: String) {}
        override fun move(dx: Int, dy: Int) {}
        override fun button(b: MouseButton, down: Boolean) {}
        override fun scroll(vertical: Int, horizontal: Int) {}
        override fun media(a: Media) {}
        override fun seekBy(seconds: Int) {}
        override fun disconnect() {}
    }

    private val wifiCaps = setOf(Cap.MEDIA_CONTROL, Cap.VOLUME_SET, Cap.BRIGHTNESS_SET, Cap.CONTRAST_SET, Cap.NOW_PLAYING, Cap.SEEK_TO)
    private val wifiStatus = PcStatus(
        volume = 42, muted = false, brightness = 70, contrast = 50,
        player = PlayerInfo("Playing", "Clair de Lune", "Claude Debussy", 83.0, 301.0),
    )
    private val bt = FakeRemote(setOf(Cap.MEDIA_CONTROL), PcStatus())
    private val wifi = FakeRemote(wifiCaps, wifiStatus)

    @Composable
    private fun Themed(dark: Boolean = false, content: @Composable () -> Unit) {
        val scheme = if (dark) darkColorScheme(primary = Color(0xFFB8C8FF)) else MaterialTheme.colorScheme
        MaterialTheme(colorScheme = scheme) {
            Surface(Modifier.fillMaxSize()) { Box { content() } }
        }
    }

    @Test fun touchpad() = paparazzi.snapshot { Themed { TouchpadScreen(bt, Prefs(paparazzi.context)) } }
    @Test fun mediaWifi() = paparazzi.snapshot { Themed { MediaScreen(wifi, wifi.caps.value, wifi.status.value) } }
    @Test fun mediaBluetoothDark() = paparazzi.snapshot { Themed(dark = true) { MediaScreen(bt, bt.caps.value, bt.status.value) } }
    @Test fun keyboard() = paparazzi.snapshot { Themed { KeyboardScreen(bt) } }
    @Test fun displayWifi() = paparazzi.snapshot { Themed { DisplayScreen(wifi, wifi.caps.value, wifi.status.value, Mode.WIFI) } }
    @Test fun displayBluetooth() = paparazzi.snapshot { Themed { DisplayScreen(bt, bt.caps.value, bt.status.value, Mode.BLUETOOTH) } }
}
