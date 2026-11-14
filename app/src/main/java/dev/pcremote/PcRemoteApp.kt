package dev.pcremote

import android.app.Application
import android.content.Context
import dev.pcremote.remote.BluetoothHidRemote
import dev.pcremote.remote.Remote
import dev.pcremote.remote.WifiRemote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Mode { BLUETOOTH, WIFI }

/** Holds both remotes for the lifetime of the process so connections survive screen rotation. */
class PcRemoteApp : Application() {
    lateinit var bluetooth: BluetoothHidRemote
        private set
    val wifi = WifiRemote()
    lateinit var prefs: Prefs
        private set

    private val _mode = MutableStateFlow(Mode.BLUETOOTH)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    val active: Remote get() = if (_mode.value == Mode.BLUETOOTH) bluetooth else wifi

    override fun onCreate() {
        super.onCreate()
        bluetooth = BluetoothHidRemote(this)
        prefs = Prefs(this)
        _mode.value = prefs.mode
    }

    fun setMode(m: Mode) {
        if (m == _mode.value) return
        active.disconnect()
        _mode.value = m
        prefs.mode = m
    }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("pc-remote", Context.MODE_PRIVATE)

    var mode: Mode
        get() = runCatching { Mode.valueOf(sp.getString("mode", null)!!) }.getOrDefault(Mode.BLUETOOTH)
        set(v) = sp.edit().putString("mode", v.name).apply()
    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v).apply()
    var port: Int
        get() = sp.getInt("port", WifiRemote.DEFAULT_PORT)
        set(v) = sp.edit().putInt("port", v).apply()
    var code: String
        get() = sp.getString("code", "") ?: ""
        set(v) = sp.edit().putString("code", v).apply()
    var btAddress: String
        get() = sp.getString("bt", "") ?: ""
        set(v) = sp.edit().putString("bt", v).apply()
    var sensitivity: Float
        get() = sp.getFloat("sens", 1.6f)
        set(v) = sp.edit().putFloat("sens", v).apply()
}
