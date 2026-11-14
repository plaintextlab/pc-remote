package dev.pcremote.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * Makes the phone a Bluetooth keyboard + mouse + media-key remote.
 * Linux's built-in HID support (BlueZ `input` plugin + hid-generic) handles it,
 * so nothing needs to be installed on the PC.
 *
 * All Bluetooth calls need BLUETOOTH_CONNECT on Android 12+; the UI requests it
 * before calling [start].
 */
@SuppressLint("MissingPermission")
class BluetoothHidRemote(private val context: Context) : Remote {

    private val _connection = MutableStateFlow<ConnState>(ConnState.Idle)
    override val connection: StateFlow<ConnState> = _connection.asStateFlow()
    // Over Bluetooth the PC can't send state back, and only key presses are possible.
    override val caps: StateFlow<Set<Cap>> = MutableStateFlow(setOf(Cap.MEDIA_CONTROL))
    override val status: StateFlow<PcStatus> = MutableStateFlow(PcStatus())

    private val _registered = MutableStateFlow(false)
    /** True once Android accepted our HID app registration (the phone can now be paired / connected as a keyboard). */
    val registered: StateFlow<Boolean> = _registered.asStateFlow()

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private val executor = Executors.newSingleThreadExecutor()
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var pendingConnect: BluetoothDevice? = null
    private var buttons = 0

    val isSupported get() = adapter != null
    val isEnabled get() = adapter?.isEnabled == true

    fun bondedDevices(): List<BluetoothDevice> =
        runCatching { adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
            .sortedBy { it.name ?: it.address }

    /** Registers the HID app with Android. Safe to call repeatedly. */
    fun start() {
        val a = adapter ?: run { fail("This phone has no Bluetooth"); return }
        if (!a.isEnabled) { fail("Bluetooth is off"); return }
        if (hid != null) return
        _connection.value = ConnState.Working("Starting Bluetooth keyboard…")
        val ok = a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val dev = proxy as BluetoothHidDevice
                hid = dev
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "PC Remote", "Keyboard, mouse and media keys", "PC Remote",
                    BluetoothHidDevice.SUBCLASS1_COMBO, HidDescriptor.BYTES,
                )
                if (!dev.registerApp(sdp, null, null, executor, callback)) {
                    fail("Android refused the Bluetooth keyboard role (another app may be using it)")
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
                host = null
                _registered.value = false
                _connection.value = ConnState.Idle
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) fail("This phone doesn't support acting as a Bluetooth keyboard")
    }

    /** Connects to a PC that is already paired with the phone. */
    fun connect(device: BluetoothDevice) {
        val dev = hid
        if (dev == null || !_registered.value) {
            pendingConnect = device
            start()
            return
        }
        _connection.value = ConnState.Working("Connecting to ${device.name ?: device.address}…")
        if (!dev.connect(device)) fail("Couldn't start a connection to ${device.name ?: device.address}")
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            _registered.value = registered
            if (!registered) {
                host = null
                _connection.value = ConnState.Idle
                return
            }
            if (_connection.value is ConnState.Working) {
                _connection.value = ConnState.Working("Ready — connect from a paired PC")
            }
            val target = pendingConnect ?: pluggedDevice
            pendingConnect = null
            if (target != null) connect(target)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    _connection.value = ConnState.Connected(device.name ?: device.address)
                }
                BluetoothProfile.STATE_CONNECTING ->
                    _connection.value = ConnState.Working("Connecting to ${device.name ?: device.address}…")
                BluetoothProfile.STATE_DISCONNECTED -> if (host == null || host == device) {
                    host = null
                    _connection.value = if (_registered.value) ConnState.Working("Ready — connect from a paired PC") else ConnState.Idle
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val size = HidDescriptor.reportSize(id.toInt())
            if (size > 0) hid?.replyReport(device, type, id, ByteArray(size))
            else hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    private fun send(id: Int, data: ByteArray) {
        val dev = hid ?: return
        val h = host ?: return
        executor.execute {
            if (!runCatching { dev.sendReport(h, id, data) }.getOrDefault(false)) {
                Log.w(TAG, "sendReport failed for report $id")
            }
        }
    }

    private fun fail(msg: String) {
        _connection.value = ConnState.Failed(msg)
    }

    // ---- Remote ----

    override fun key(usage: Int, mods: Int) {
        send(HidDescriptor.ID_KEYBOARD, byteArrayOf(mods.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0))
        send(HidDescriptor.ID_KEYBOARD, ByteArray(8))
    }

    override fun text(s: String) {
        for (c in s) {
            val (usage, shift) = Keys.forChar(c) ?: continue
            key(usage, if (shift) Mods.SHIFT else 0)
        }
    }

    override fun move(dx: Int, dy: Int) {
        var x = dx
        var y = dy
        // Each report carries a signed byte per axis, so split large moves.
        while (x != 0 || y != 0) {
            val sx = x.coerceIn(-127, 127)
            val sy = y.coerceIn(-127, 127)
            send(HidDescriptor.ID_MOUSE, byteArrayOf(buttons.toByte(), sx.toByte(), sy.toByte(), 0, 0))
            x -= sx
            y -= sy
        }
    }

    override fun button(b: MouseButton, down: Boolean) {
        buttons = if (down) buttons or b.bit else buttons and b.bit.inv()
        send(HidDescriptor.ID_MOUSE, byteArrayOf(buttons.toByte(), 0, 0, 0, 0))
    }

    override fun scroll(vertical: Int, horizontal: Int) {
        send(
            HidDescriptor.ID_MOUSE,
            byteArrayOf(buttons.toByte(), 0, 0, vertical.coerceIn(-127, 127).toByte(), horizontal.coerceIn(-127, 127).toByte()),
        )
    }

    override fun media(a: Media) {
        val u = a.consumerUsage
        send(HidDescriptor.ID_CONSUMER, byteArrayOf((u and 0xFF).toByte(), (u shr 8).toByte()))
        send(HidDescriptor.ID_CONSUMER, byteArrayOf(0, 0))
    }

    /** Bluetooth can only send arrow keys, which most players (mpv, VLC, YouTube, Spotify) use for seeking. */
    override fun seekBy(seconds: Int) {
        key(if (seconds >= 0) Keys.RIGHT else Keys.LEFT)
    }

    override fun disconnect() {
        val dev = hid
        host?.let { h -> dev?.disconnect(h) }
        host = null
    }

    /** Fully unregisters so other apps (or a fresh start) can use the HID role. */
    fun shutdown() {
        disconnect()
        hid?.let { dev ->
            runCatching { dev.unregisterApp() }
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, dev)
        }
        hid = null
        _registered.value = false
        _connection.value = ConnState.Idle
    }

    private companion object { const val TAG = "BtHid" }
}

/** HID report descriptor: keyboard (id 1), mouse with wheel + pan (id 2), consumer control (id 3). */
object HidDescriptor {
    const val ID_KEYBOARD = 1
    const val ID_MOUSE = 2
    const val ID_CONSUMER = 3

    fun reportSize(id: Int) = when (id) { ID_KEYBOARD -> 8; ID_MOUSE -> 5; ID_CONSUMER -> 2; else -> 0 }

    val BYTES: ByteArray = intArrayOf(
        // Keyboard
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, ID_KEYBOARD,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, // modifier bits
        0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,                         // reserved byte
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,             // 6 key slots
        0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
        0xC0,
        // Mouse
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, ID_MOUSE,
        0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, // 3 buttons
        0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x05, 0x81, 0x01,                         // padding
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,             // X, Y, wheel
        0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
        0x05, 0x0C, 0x0A, 0x38, 0x02,                               // AC Pan (horizontal scroll)
        0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
        0xC0, 0xC0,
        // Consumer control (media, volume, brightness)
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, ID_CONSUMER,
        0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03,
        0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
        0xC0,
    ).map { it.toByte() }.toByteArray()
}
