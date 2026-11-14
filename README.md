# PC Remote

An Android remote for a Linux PC, built with Material 3 and dynamic colour. It has a touchpad, media keys, a keyboard (live typing, shortcuts, function keys), and controls for volume, brightness and contrast.

It has two modes:

| | **Bluetooth** | **Wi-Fi** |
|---|---|---|
| Needs anything on the PC? | **No** | One Python file (`agent/pc-remote-agent.py`) |
| Typing, shortcuts, touchpad, scroll | ✅ | ✅ |
| Play/pause, next/prev, stop | ✅ media keys | ✅ playerctl (or media keys) |
| Volume | ✅ up/down/mute | ✅ plus a slider for an exact level |
| Brightness | ✅ up/down keys (laptop screens) | ✅ slider (brightnessctl or ddcutil) |
| Contrast | ❌ no key exists | ✅ slider (ddcutil) |
| Seek | ← / → keys to the focused player | ±10 s and a seek bar (playerctl) |
| Now playing | ❌ | ✅ |
| Range | ~10 m | anywhere on your LAN |

Requires Android 9+.

## Bluetooth mode (nothing on the PC)

The phone acts as a standard Bluetooth keyboard, mouse and media remote (Android's `BluetoothHidDevice`). Linux's built-in BlueZ/HID support handles it.

1. Open the app, choose **Bluetooth**, and allow Bluetooth access.
2. Tap **Make phone visible**.
3. On the PC, pair with the phone from Bluetooth settings, or with:
   ```
   bluetoothctl scan on      # wait for the phone to show up
   bluetoothctl pair <MAC>
   bluetoothctl trust <MAC>
   ```
4. Tap the PC in the app's **Paired devices** list.

If the phone and PC were paired **before** you installed the app, remove the pairing on both sides and pair again, so the PC sees the phone as a keyboard.

Some phone vendors (some Samsung and Xiaomi builds) disable the HID device role. The app tells you if that's the case. Use Wi-Fi mode instead.

## Wi-Fi mode (small agent on the PC)

```
python3 agent/pc-remote-agent.py
```

It prints the PC's address and a pairing code. The app finds the PC automatically on the same network; enter the code to connect. To make a new code: `--new-code`.

The agent uses only the Python standard library. It uses these tools if they're installed, and falls back to sending keys if not:

| Feature | Tool | Package (most distros) |
|---|---|---|
| Keyboard/mouse | `/dev/uinput` (X11 and Wayland) or `ydotool` | built in |
| Media, seek, now playing | `playerctl` | `playerctl` |
| Exact volume | `wpctl` (PipeWire) or `pactl` (PulseAudio) | usually already installed |
| Laptop brightness | `brightnessctl` | `brightnessctl` |
| Monitor brightness and contrast | `ddcutil` | `ddcutil` (needs the `i2c-dev` module and access to `/dev/i2c-*`) |

**Keyboard/mouse access.** Your user needs write access to `/dev/uinput`. Many desktops grant this already. If the agent prints `no way to send keyboard/mouse input`, add a udev rule:

```
echo 'KERNEL=="uinput", GROUP="input", MODE="0660", OPTIONS+="static_node=uinput"' | sudo tee /etc/udev/rules.d/60-pc-remote-uinput.rules
sudo usermod -aG input $USER     # then log out and back in
```

**Start on login.** Copy `agent/pc-remote-agent.desktop` to `~/.config/autostart/` and fix the path in it.

**Ports.** TCP 47010 (control) and UDP 47011 (discovery). Open them if you run a firewall.

**Security.** Anyone on your network who has the pairing code can control the PC. The traffic is not encrypted, so only use it on networks you trust. Wrong codes are delayed, to slow down guessing.

## Building

The toolchain installs into `.toolchain/` only: JDK 17, Android SDK and Gradle, plus their caches. Nothing goes into `~/.gradle` or `~/.android`, and `rm -rf .toolchain` removes all of it.

```
tools/setup-toolchain.sh
source tools/env.sh
./gradlew assembleRelease     # → app/build/outputs/apk/release/app-release.apk
```

Or open the project in Android Studio.

Release builds are signed with the debug key so they install directly. Sign with your own key if you publish to a store.

## Layout

```
app/src/main/java/dev/pcremote/
  remote/Remote.kt               shared interface, HID key codes, US keymap
  remote/BluetoothHidRemote.kt   Bluetooth HID device (keyboard + mouse + consumer report descriptor)
  remote/WifiRemote.kt           JSON-lines TCP client + UDP discovery
  ui/                            Compose screens (connect, touchpad, media, keyboard, display)
agent/pc-remote-agent.py         Wi-Fi agent for the PC
```
