package dev.pcremote.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

data class DiscoveredPc(val name: String, val host: String, val port: Int)

/**
 * Talks to `agent/pc-remote-agent.py` on the PC over TCP. The protocol is one
 * JSON object per line. The first message must be `hello` with the pairing code.
 */
class WifiRemote : Remote {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connection = MutableStateFlow<ConnState>(ConnState.Idle)
    override val connection: StateFlow<ConnState> = _connection.asStateFlow()
    private val _caps = MutableStateFlow<Set<Cap>>(emptySet())
    override val caps: StateFlow<Set<Cap>> = _caps.asStateFlow()
    private val _status = MutableStateFlow(PcStatus())
    override val status: StateFlow<PcStatus> = _status.asStateFlow()

    private var session: Job? = null
    private var outbox = Channel<String>(Channel.UNLIMITED)

    /** Slider values: only the latest one per setting is sent, at most every 80 ms. */
    private val latestSetting = ConcurrentHashMap<String, Any>()

    fun connect(host: String, port: Int, code: String) {
        session?.cancel()
        outbox = Channel(Channel.UNLIMITED)
        val box = outbox
        session = scope.launch {
            _connection.value = ConnState.Working("Connecting to $host…")
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 4000)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

                writer.write(JSONObject().put("t", "hello").put("code", code.trim().uppercase()).toString())
                writer.write("\n")
                writer.flush()
                socket.soTimeout = 5000
                val hello = JSONObject(reader.readLine() ?: throw IllegalStateException("PC closed the connection"))
                if (!hello.optBoolean("ok")) {
                    throw IllegalStateException(hello.optString("error", "Wrong pairing code"))
                }
                socket.soTimeout = 0
                _caps.value = parseCaps(hello.optJSONArray("caps"))
                _connection.value = ConnState.Connected(hello.optString("name", host))

                val writerJob = launch {
                    for (line in box) {
                        writer.write(line)
                        writer.write("\n")
                        if (box.isEmpty) writer.flush()
                    }
                }
                val settingsJob = launch {
                    while (isActive) {
                        for (k in latestSetting.keys.toList()) {
                            val v = latestSetting.remove(k) ?: continue
                            box.trySend(JSONObject().put("t", k).put("v", v).toString())
                        }
                        delay(80)
                    }
                }
                val pollJob = launch {
                    while (isActive) {
                        box.trySend("""{"t":"status"}""")
                        delay(1000)
                    }
                }
                try {
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        val msg = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        if (msg.optString("t") == "status") _status.value = parseStatus(msg)
                    }
                } finally {
                    writerJob.cancel(); settingsJob.cancel(); pollJob.cancel()
                }
                _connection.value = ConnState.Failed("PC closed the connection")
            } catch (e: SocketTimeoutException) {
                _connection.value = ConnState.Failed("No answer from $host — is the agent running?")
            } catch (e: Exception) {
                if (isActive) _connection.value = ConnState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { socket.close() }
                _caps.value = emptySet()
                _status.value = PcStatus()
            }
        }
    }

    override fun disconnect() {
        session?.cancel()
        session = null
        _connection.value = ConnState.Idle
    }

    private fun send(o: JSONObject) {
        if (_connection.value is ConnState.Connected) outbox.trySend(o.toString())
    }

    private fun cmd(t: String) = JSONObject().put("t", t)

    override fun key(usage: Int, mods: Int) = send(cmd("key").put("code", usage).put("mods", mods))
    override fun text(s: String) = send(cmd("text").put("s", s))
    override fun move(dx: Int, dy: Int) = send(cmd("move").put("dx", dx).put("dy", dy))
    override fun button(b: MouseButton, down: Boolean) = send(cmd("button").put("b", b.name.lowercase()).put("down", down))
    override fun scroll(vertical: Int, horizontal: Int) = send(cmd("scroll").put("v", vertical).put("h", horizontal))
    override fun media(a: Media) = send(cmd("media").put("a", a.wire))
    override fun seekBy(seconds: Int) = send(cmd("seek").put("by", seconds))
    override fun seekTo(seconds: Double) { latestSetting["seek_to"] = seconds }
    override fun setVolume(percent: Int) { latestSetting["volume"] = percent }
    override fun setBrightness(percent: Int) { latestSetting["brightness"] = percent }
    override fun setContrast(percent: Int) { latestSetting["contrast"] = percent }

    private fun parseCaps(a: JSONArray?): Set<Cap> {
        a ?: return emptySet()
        return (0 until a.length()).mapNotNull { i ->
            when (a.optString(i)) {
                "volume" -> Cap.VOLUME_SET
                "brightness" -> Cap.BRIGHTNESS_SET
                "contrast" -> Cap.CONTRAST_SET
                "player" -> Cap.NOW_PLAYING
                "seek_to" -> Cap.SEEK_TO
                "media" -> Cap.MEDIA_CONTROL
                else -> null
            }
        }.toSet()
    }

    private fun parseStatus(m: JSONObject): PcStatus {
        fun intOrNull(k: String) = if (m.has(k) && !m.isNull(k)) m.optInt(k) else null
        val p = m.optJSONObject("player")
        return PcStatus(
            volume = intOrNull("volume"),
            muted = if (m.has("muted") && !m.isNull("muted")) m.optBoolean("muted") else null,
            brightness = intOrNull("brightness"),
            contrast = intOrNull("contrast"),
            player = p?.let {
                PlayerInfo(
                    status = it.optString("status"),
                    title = it.optString("title"),
                    artist = it.optString("artist"),
                    positionSec = it.optDouble("position", 0.0),
                    lengthSec = it.optDouble("length", 0.0),
                )
            },
        )
    }

    companion object {
        const val DEFAULT_PORT = 47010
        const val DISCOVERY_PORT = 47011

        /** Broadcasts on the local network and collects agents that answer within [timeoutMs]. */
        suspend fun discover(timeoutMs: Int = 1500): List<DiscoveredPc> = withContext(Dispatchers.IO) {
            val found = linkedMapOf<String, DiscoveredPc>()
            runCatching {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    s.soTimeout = 300
                    val probe = "PCREMOTE_DISCOVER".toByteArray()
                    s.send(DatagramPacket(probe, probe.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
                    val buf = ByteArray(1024)
                    val end = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < end) {
                        val pkt = DatagramPacket(buf, buf.size)
                        try { s.receive(pkt) } catch (_: SocketTimeoutException) { continue }
                        val o = runCatching { JSONObject(String(pkt.data, 0, pkt.length)) }.getOrNull() ?: continue
                        if (o.optString("app") != "pc-remote") continue
                        val host = pkt.address.hostAddress ?: continue
                        found[host] = DiscoveredPc(o.optString("name", host), host, o.optInt("port", DEFAULT_PORT))
                    }
                }
            }
            found.values.toList()
        }
    }
}
