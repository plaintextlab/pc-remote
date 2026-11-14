package dev.pcremote.remote

import kotlinx.coroutines.flow.StateFlow

/** Connection state shown in the top bar. */
sealed interface ConnState {
    data object Idle : ConnState
    data class Working(val message: String) : ConnState
    data class Connected(val name: String) : ConnState
    data class Failed(val message: String) : ConnState
}

/** Features that need the Wi-Fi agent; Bluetooth HID can only send key presses. */
enum class Cap { VOLUME_SET, BRIGHTNESS_SET, CONTRAST_SET, SEEK_TO, NOW_PLAYING, MEDIA_CONTROL }

enum class MouseButton(val bit: Int) { LEFT(1), RIGHT(2), MIDDLE(4) }

/** Actions sent as HID "consumer control" usages over Bluetooth, or as named actions over Wi-Fi. */
enum class Media(val consumerUsage: Int, val wire: String) {
    PLAY_PAUSE(0xCD, "play_pause"),
    NEXT(0xB5, "next"),
    PREVIOUS(0xB6, "prev"),
    STOP(0xB7, "stop"),
    VOLUME_UP(0xE9, "vol_up"),
    VOLUME_DOWN(0xEA, "vol_down"),
    MUTE(0xE2, "mute"),
    BRIGHTNESS_UP(0x6F, "bright_up"),
    BRIGHTNESS_DOWN(0x70, "bright_down"),
}

data class PlayerInfo(
    val status: String,   // Playing / Paused / Stopped
    val title: String,
    val artist: String,
    val positionSec: Double,
    val lengthSec: Double,
)

/** Live PC state reported by the Wi-Fi agent. Null fields mean "unknown / unsupported". */
data class PcStatus(
    val volume: Int? = null,
    val muted: Boolean? = null,
    val brightness: Int? = null,
    val contrast: Int? = null,
    val player: PlayerInfo? = null,
)

/** One way of reaching the PC: Bluetooth HID or the Wi-Fi agent. */
interface Remote {
    val connection: StateFlow<ConnState>
    val caps: StateFlow<Set<Cap>>
    val status: StateFlow<PcStatus>

    /** Press and release one key. [usage] is a USB HID keyboard usage (see [Keys]); [mods] is a [Mods] bitmask. */
    fun key(usage: Int, mods: Int = 0)
    fun text(s: String)
    fun move(dx: Int, dy: Int)
    fun button(b: MouseButton, down: Boolean)
    fun click(b: MouseButton) { button(b, true); button(b, false) }
    /** Positive [vertical] scrolls up. */
    fun scroll(vertical: Int, horizontal: Int = 0)
    fun media(a: Media)
    fun seekBy(seconds: Int)

    // Only meaningful when the matching [Cap] is present.
    fun setVolume(percent: Int) {}
    fun setBrightness(percent: Int) {}
    fun setContrast(percent: Int) {}
    fun seekTo(seconds: Double) {}

    fun disconnect()
}

object Mods {
    const val CTRL = 0x01
    const val SHIFT = 0x02
    const val ALT = 0x04
    const val SUPER = 0x08
}

/** USB HID keyboard usage IDs (HID Usage Tables, page 0x07). */
object Keys {
    const val NONE = 0x00
    const val A = 0x04
    const val C = 0x06
    const val F = 0x09
    const val L = 0x0F
    const val T = 0x17
    const val V = 0x19
    const val W = 0x1A
    const val X = 0x1B
    const val Z = 0x1D
    const val ENTER = 0x28
    const val ESC = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val F1 = 0x3A // F1..F12 are consecutive
    const val F4 = 0x3D
    const val F5 = 0x3E
    const val F11 = 0x44
    const val PRINT_SCREEN = 0x46
    const val INSERT = 0x49
    const val HOME = 0x4A
    const val PAGE_UP = 0x4B
    const val DELETE = 0x4C
    const val END = 0x4D
    const val PAGE_DOWN = 0x4E
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
    const val MENU = 0x65

    fun f(n: Int) = F1 + (n - 1)

    /**
     * Maps a character to (usage, needsShift) for a US keyboard layout.
     * The PC interprets HID keys with its own layout, so non-US layouts may
     * produce different symbols.
     */
    fun forChar(c: Char): Pair<Int, Boolean>? {
        when (c) {
            in 'a'..'z' -> return (A + (c - 'a')) to false
            in 'A'..'Z' -> return (A + (c - 'A')) to true
            in '1'..'9' -> return (0x1E + (c - '1')) to false
            '0' -> return 0x27 to false
            '\n' -> return ENTER to false
            '\t' -> return TAB to false
            ' ' -> return SPACE to false
        }
        val i = PLAIN.indexOf(c)
        if (i >= 0) return SYMBOL_USAGES[i] to false
        val j = SHIFTED.indexOf(c)
        if (j >= 0) return SYMBOL_USAGES[j] to true
        val k = SHIFTED_DIGITS.indexOf(c)
        if (k >= 0) return (if (k == 9) 0x27 else 0x1E + k) to true
        return null
    }

    private const val PLAIN = "-=[]\\;',./`"
    private const val SHIFTED = "_+{}|:\"<>?~"
    private val SYMBOL_USAGES = intArrayOf(0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x36, 0x37, 0x38, 0x35)
    private const val SHIFTED_DIGITS = "!@#$%^&*()"
}
