#!/usr/bin/env python3
"""
PC Remote agent: lets the Android app control this Linux PC over Wi-Fi.

Only needed for Wi-Fi mode (Bluetooth mode needs nothing on the PC).
Pure Python 3 standard library. It uses these tools when they're installed:

  input       /dev/uinput (works on X11 and Wayland), or ydotool as a fallback
  media/seek  playerctl              (falls back to media keys)
  volume      wpctl or pactl         (falls back to volume keys)
  brightness  brightnessctl (laptop backlight) or ddcutil (external monitor)
  contrast    ddcutil

Usage:  python3 pc-remote-agent.py [--port 47010] [--bind 0.0.0.0] [--new-code]
"""
from __future__ import annotations

import argparse
import asyncio
import fcntl
import hmac
import json
import os
import re
import secrets
import shutil
import socket
import struct
import sys
import time
from pathlib import Path

DEFAULT_PORT = 47010
DISCOVERY_PORT = 47011
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no 0/O/1/I


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# --------------------------------------------------------------------------- keycodes

# USB HID keyboard usage -> Linux evdev key code.
HID_TO_EVDEV = {
    **{0x04 + i: c for i, c in enumerate(
        [30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44])},  # a..z
    **{0x1E + i: 2 + i for i in range(9)},  # 1..9
    0x27: 11,  # 0
    0x28: 28, 0x29: 1, 0x2A: 14, 0x2B: 15, 0x2C: 57,  # enter esc backspace tab space
    0x2D: 12, 0x2E: 13, 0x2F: 26, 0x30: 27, 0x31: 43, 0x33: 39, 0x34: 40, 0x35: 41,
    0x36: 51, 0x37: 52, 0x38: 53, 0x39: 58,  # , . / capslock
    **{0x3A + i: 59 + i for i in range(10)},  # F1..F10
    0x44: 87, 0x45: 88,  # F11 F12
    0x46: 99, 0x47: 70, 0x48: 119,  # print scrolllock pause
    0x49: 110, 0x4A: 102, 0x4B: 104, 0x4C: 111, 0x4D: 107, 0x4E: 109,  # ins home pgup del end pgdn
    0x4F: 106, 0x50: 105, 0x51: 108, 0x52: 103,  # right left down up
    0x65: 127,  # menu / compose
}
KEY_LEFTCTRL, KEY_LEFTSHIFT, KEY_LEFTALT, KEY_LEFTMETA = 29, 42, 56, 125
MOD_BITS = [(0x01, KEY_LEFTCTRL), (0x02, KEY_LEFTSHIFT), (0x04, KEY_LEFTALT), (0x08, KEY_LEFTMETA)]

KEY_MUTE, KEY_VOLUMEDOWN, KEY_VOLUMEUP = 113, 114, 115
KEY_NEXTSONG, KEY_PLAYPAUSE, KEY_PREVIOUSSONG, KEY_STOPCD = 163, 164, 165, 166
KEY_BRIGHTNESSDOWN, KEY_BRIGHTNESSUP = 224, 225
KEY_LEFT, KEY_RIGHT = 105, 106
BTN = {"left": 0x110, "right": 0x111, "middle": 0x112}

# US layout: character -> (evdev code, shift)
_US_PLAIN = "1234567890-=qwertyuiop[]asdfghjkl;'`\\zxcvbnm,./"
_US_SHIFT = "!@#$%^&*()_+QWERTYUIOP{}ASDFGHJKL:\"~|ZXCVBNM<>?"
_US_CODES = [2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27,
             30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53]
CHAR_TO_KEY = {" ": (57, False), "\n": (28, False), "\t": (15, False)}
for _c, _k in zip(_US_PLAIN, _US_CODES):
    CHAR_TO_KEY[_c] = (_k, False)
for _c, _k in zip(_US_SHIFT, _US_CODES):
    CHAR_TO_KEY[_c] = (_k, True)


# --------------------------------------------------------------------------- input backends

class UinputInput:
    """Creates a virtual keyboard and mouse via /dev/uinput (needs write access to it)."""
    EV_SYN, EV_KEY, EV_REL = 0, 1, 2
    REL_X, REL_Y, REL_HWHEEL, REL_WHEEL = 0, 1, 6, 8
    UI_SET_EVBIT, UI_SET_KEYBIT, UI_SET_RELBIT = 0x40045564, 0x40045565, 0x40045566
    UI_DEV_SETUP, UI_DEV_CREATE = 0x405C5503, 0x5501
    name = "uinput"

    def __init__(self):
        self.kbd = self._create("PC Remote keyboard", keys=range(1, 249), rel=())
        self.mouse = self._create("PC Remote mouse", keys=BTN.values(),
                                  rel=(self.REL_X, self.REL_Y, self.REL_WHEEL, self.REL_HWHEEL))
        time.sleep(0.3)  # give the compositor time to pick the devices up

    def _create(self, name, keys, rel):
        fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
        fcntl.ioctl(fd, self.UI_SET_EVBIT, self.EV_KEY)
        for k in keys:
            fcntl.ioctl(fd, self.UI_SET_KEYBIT, k)
        if rel:
            fcntl.ioctl(fd, self.UI_SET_EVBIT, self.EV_REL)
            for r in rel:
                fcntl.ioctl(fd, self.UI_SET_RELBIT, r)
        setup = struct.pack("<HHHH80sI", 0x06, 0x1209, 0x5052, 1, name.encode(), 0)  # BUS_VIRTUAL
        fcntl.ioctl(fd, self.UI_DEV_SETUP, setup)
        fcntl.ioctl(fd, self.UI_DEV_CREATE)
        return fd

    def _emit(self, fd, events):
        buf = b"".join(struct.pack("llHHi", 0, 0, t, c, v) for t, c, v in events)
        buf += struct.pack("llHHi", 0, 0, self.EV_SYN, 0, 0)
        os.write(fd, buf)

    def keys(self, codes):
        """Press codes in order, release in reverse (for chords like Ctrl+C)."""
        self._emit(self.kbd, [(self.EV_KEY, c, 1) for c in codes])
        self._emit(self.kbd, [(self.EV_KEY, c, 0) for c in reversed(codes)])

    def text(self, s):
        for ch in s:
            k = CHAR_TO_KEY.get(ch)
            if k:
                self.keys([KEY_LEFTSHIFT, k[0]] if k[1] else [k[0]])

    def move(self, dx, dy):
        self._emit(self.mouse, [(self.EV_REL, self.REL_X, dx), (self.EV_REL, self.REL_Y, dy)])

    def button(self, b, down):
        self._emit(self.mouse, [(self.EV_KEY, BTN[b], 1 if down else 0)])

    def scroll(self, v, h):
        ev = []
        if v:
            ev.append((self.EV_REL, self.REL_WHEEL, v))
        if h:
            ev.append((self.EV_REL, self.REL_HWHEEL, h))
        if ev:
            self._emit(self.mouse, ev)


class YdotoolInput:
    """Fallback that shells out to ydotool (needs ydotoold running)."""
    name = "ydotool"

    def _run(self, *args):
        os.spawnvp(os.P_NOWAIT, "ydotool", ["ydotool", *map(str, args)])

    def keys(self, codes):
        self._run("key", *[f"{c}:1" for c in codes], *[f"{c}:0" for c in reversed(codes)])

    def text(self, s):
        self._run("type", "--", s)

    def move(self, dx, dy):
        self._run("mousemove", "-x", dx, "-y", dy)

    def button(self, b, down):
        bit = {"left": 0x00, "right": 0x01, "middle": 0x02}[b]
        self._run("click", hex(bit | (0x40 if down else 0x80)))

    def scroll(self, v, h):
        self._run("mousemove", "-w", "-x", h, "-y", v)


class NoInput:
    name = "none"

    def keys(self, codes): pass
    def text(self, s): pass
    def move(self, dx, dy): pass
    def button(self, b, down): pass
    def scroll(self, v, h): pass


def make_input():
    if os.access("/dev/uinput", os.W_OK):
        try:
            return UinputInput()
        except OSError as e:
            log("uinput failed:", e)
    if shutil.which("ydotool"):
        return YdotoolInput()
    return NoInput()


# --------------------------------------------------------------------------- system helpers

async def run(*args, timeout=5.0) -> tuple[int, str]:
    """Runs a command without a shell. Returns (exit code, stdout)."""
    try:
        p = await asyncio.create_subprocess_exec(
            *map(str, args), stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
    except OSError:
        return 127, ""
    try:
        out, _ = await asyncio.wait_for(p.communicate(), timeout)
    except asyncio.TimeoutError:
        p.kill()
        return 124, ""
    return p.returncode, out.decode(errors="replace").strip()


def clamp(v, lo=0, hi=100):
    return max(lo, min(hi, int(v)))


class Audio:
    def __init__(self):
        self.tool = "wpctl" if shutil.which("wpctl") else "pactl" if shutil.which("pactl") else None

    async def get(self):
        if self.tool == "wpctl":
            rc, out = await run("wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@")
            m = re.search(r"Volume:\s*([\d.]+)", out)
            if rc == 0 and m:
                return round(float(m.group(1)) * 100), "MUTED" in out
        elif self.tool == "pactl":
            rc, out = await run("pactl", "get-sink-volume", "@DEFAULT_SINK@")
            m = re.search(r"(\d+)%", out)
            rc2, mute = await run("pactl", "get-sink-mute", "@DEFAULT_SINK@")
            if rc == 0 and m:
                return int(m.group(1)), "yes" in mute
        return None, None

    async def set(self, pct):
        pct = clamp(pct)
        if self.tool == "wpctl":
            await run("wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", f"{pct / 100:.2f}")
        elif self.tool == "pactl":
            await run("pactl", "set-sink-volume", "@DEFAULT_SINK@", f"{pct}%")

    async def step(self, up: bool) -> bool:
        if self.tool == "wpctl":
            await run("wpctl", "set-volume", "-l", "1.0", "@DEFAULT_AUDIO_SINK@", "5%+" if up else "5%-")
        elif self.tool == "pactl":
            await run("pactl", "set-sink-volume", "@DEFAULT_SINK@", "+5%" if up else "-5%")
        else:
            return False
        return True

    async def toggle_mute(self) -> bool:
        if self.tool == "wpctl":
            await run("wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "toggle")
        elif self.tool == "pactl":
            await run("pactl", "set-sink-mute", "@DEFAULT_SINK@", "toggle")
        else:
            return False
        return True


class Backlight:
    """Laptop screen backlight via brightnessctl."""

    def __init__(self):
        self.ok = False

    async def probe(self):
        if shutil.which("brightnessctl"):
            rc, out = await run("brightnessctl", "-m", "-c", "backlight", "info")
            self.ok = rc == 0 and ",backlight," in out
        return self.ok

    async def get(self):
        rc, out = await run("brightnessctl", "-m", "-c", "backlight", "info")
        m = re.search(r",(\d+)%,", out)
        return int(m.group(1)) if rc == 0 and m else None

    async def set(self, pct):
        await run("brightnessctl", "-q", "-c", "backlight", "set", f"{clamp(pct, 1)}%")


class Ddc:
    """External monitor brightness (VCP 0x10) and contrast (VCP 0x12) via ddcutil. ddcutil is slow, so values are cached."""

    def __init__(self):
        self.ok = False
        self.cache = {}

    async def probe(self):
        if shutil.which("ddcutil"):
            for code in ("10", "12"):
                v = await self._read(code)
                if v is not None:
                    self.ok = True
                    self.cache[code] = v
        return self.ok

    async def _read(self, code):
        rc, out = await run("ddcutil", "getvcp", code, "--brief", timeout=10)
        m = re.search(r"VCP\s+\w+\s+C\s+(\d+)\s+(\d+)", out)
        if rc == 0 and m:
            cur, mx = int(m.group(1)), int(m.group(2)) or 100
            return round(cur * 100 / mx)
        return None

    def get(self, code):
        return self.cache.get(code)

    async def set(self, code, pct):
        self.cache[code] = clamp(pct)
        await run("ddcutil", "setvcp", code, clamp(pct), timeout=10)


class Player:
    def __init__(self):
        self.ok = bool(shutil.which("playerctl"))

    async def info(self):
        if not self.ok:
            return None
        fmt = "{{status}}\t{{xesam:title}}\t{{xesam:artist}}\t{{position}}\t{{mpris:length}}"
        rc, out = await run("playerctl", "metadata", "--format", fmt, timeout=2)
        if rc != 0 or not out:
            return None
        parts = (out.split("\t") + [""] * 5)[:5]
        to_sec = lambda s: int(s) / 1e6 if s.isdigit() else 0.0  # noqa: E731  (microseconds)
        return {"status": parts[0], "title": parts[1], "artist": parts[2],
                "position": to_sec(parts[3]), "length": to_sec(parts[4])}

    async def cmd(self, *args) -> bool:
        if not self.ok:
            return False
        rc, _ = await run("playerctl", *args, timeout=2)
        return rc == 0


class LatestWins:
    """Applies slider values one at a time, skipping stale ones (ddcutil takes ~0.5s per call)."""

    def __init__(self, fn):
        self.fn, self.value, self.task = fn, None, None

    def submit(self, v):
        self.value = v
        if self.task is None or self.task.done():
            self.task = asyncio.ensure_future(self._drain())

    async def _drain(self):
        while self.value is not None:
            v, self.value = self.value, None
            try:
                await self.fn(v)
            except Exception as e:  # keep the agent alive whatever a tool does
                log("setting failed:", e)


# --------------------------------------------------------------------------- the agent

class Agent:
    def __init__(self, code: str, port: int):
        self.code, self.port = code, port
        self.inp = make_input()
        self.audio, self.backlight, self.ddc, self.player = Audio(), Backlight(), Ddc(), Player()
        self.set_volume = LatestWins(self.audio.set)
        self.set_brightness = LatestWins(self._set_brightness)
        self.set_contrast = LatestWins(lambda v: self.ddc.set("12", v))
        self.seek_to = LatestWins(lambda v: self.player.cmd("position", f"{max(0.0, float(v)):.2f}"))

    async def probe(self):
        await asyncio.gather(self.backlight.probe(), self.ddc.probe())
        log("input:", self.inp.name,
            "| audio:", self.audio.tool or "none (volume keys)",
            "| player:", "playerctl" if self.player.ok else "none (media keys)",
            "| brightness:", "brightnessctl" if self.backlight.ok else "ddcutil" if self.ddc.ok else "keys only",
            "| contrast:", "ddcutil" if self.ddc.ok else "unavailable")
        if isinstance(self.inp, NoInput):
            log("WARNING: no way to send keyboard/mouse input. See README: give your user access to /dev/uinput.")

    def caps(self):
        c = ["media"]
        if self.audio.tool:
            c.append("volume")
        if self.backlight.ok or (self.ddc.ok and "10" in self.ddc.cache):
            c.append("brightness")
        if self.ddc.ok and "12" in self.ddc.cache:
            c.append("contrast")
        if self.player.ok:
            c += ["player", "seek_to"]
        return c

    async def _set_brightness(self, v):
        if self.backlight.ok:
            await self.backlight.set(v)
        elif self.ddc.ok:
            await self.ddc.set("10", v)

    async def status(self):
        (vol, muted), player = await asyncio.gather(self.audio.get(), self.player.info())
        bright = await self.backlight.get() if self.backlight.ok else self.ddc.get("10")
        return {"t": "status", "volume": vol, "muted": muted, "brightness": bright,
                "contrast": self.ddc.get("12"), "player": player}

    async def media(self, a):
        player_cmds = {"play_pause": "play-pause", "next": "next", "prev": "previous", "stop": "stop"}
        keys = {"play_pause": KEY_PLAYPAUSE, "next": KEY_NEXTSONG, "prev": KEY_PREVIOUSSONG, "stop": KEY_STOPCD,
                "vol_up": KEY_VOLUMEUP, "vol_down": KEY_VOLUMEDOWN, "mute": KEY_MUTE,
                "bright_up": KEY_BRIGHTNESSUP, "bright_down": KEY_BRIGHTNESSDOWN}
        if a not in keys:
            return
        handled = False
        if a in player_cmds:
            handled = await self.player.cmd(player_cmds[a])
        elif a in ("vol_up", "vol_down"):
            handled = await self.audio.step(a == "vol_up")
        elif a == "mute":
            handled = await self.audio.toggle_mute()
        elif a in ("bright_up", "bright_down") and (self.backlight.ok or self.ddc.ok):
            cur = (await self.backlight.get()) if self.backlight.ok else self.ddc.get("10")
            if cur is not None:
                self.set_brightness.submit(clamp(cur + (10 if a == "bright_up" else -10)))
                handled = True
        if not handled:
            self.inp.keys([keys[a]])

    async def handle(self, m: dict):
        t = m.get("t")
        if t == "move":
            self.inp.move(clamp(m.get("dx", 0), -2000, 2000), clamp(m.get("dy", 0), -2000, 2000))
        elif t == "button" and m.get("b") in BTN:
            self.inp.button(m["b"], bool(m.get("down")))
        elif t == "scroll":
            self.inp.scroll(clamp(m.get("v", 0), -50, 50), clamp(m.get("h", 0), -50, 50))
        elif t == "key":
            mods = int(m.get("mods", 0))
            codes = [k for bit, k in MOD_BITS if mods & bit]
            code = HID_TO_EVDEV.get(int(m.get("code", 0)))
            if code:
                codes.append(code)
            if codes:
                self.inp.keys(codes)
        elif t == "text":
            self.inp.text(str(m.get("s", ""))[:4000])
        elif t == "media":
            await self.media(m.get("a"))
        elif t == "seek":
            by = int(m.get("by", 0))
            if not await self.player.cmd("position", f"{abs(by)}{'+' if by >= 0 else '-'}"):
                self.inp.keys([KEY_RIGHT if by >= 0 else KEY_LEFT])
        elif t == "seek_to":
            self.seek_to.submit(float(m.get("v", 0)))
        elif t == "volume":
            self.set_volume.submit(clamp(m.get("v", 0)))
        elif t == "brightness":
            self.set_brightness.submit(clamp(m.get("v", 0)))
        elif t == "contrast" and self.ddc.ok:
            self.set_contrast.submit(clamp(m.get("v", 0)))
        elif t == "status":
            return await self.status()
        return None

    async def client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        peer = writer.get_extra_info("peername")
        sock = writer.get_extra_info("socket")
        if sock is not None:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

        def send(o):
            writer.write((json.dumps(o) + "\n").encode())

        try:
            line = await asyncio.wait_for(reader.readline(), 10)
            hello = json.loads(line or b"{}")
            given = str(hello.get("code", "")).strip().upper()
            if hello.get("t") != "hello" or not hmac.compare_digest(given.encode(), self.code.encode()):
                log("rejected", peer, "(wrong pairing code)")
                await asyncio.sleep(1.5)  # slow down guessing
                send({"t": "hello", "ok": False, "error": "Wrong pairing code"})
                await writer.drain()
                return
            log("connected:", peer)
            send({"t": "hello", "ok": True, "name": socket.gethostname(), "caps": self.caps()})
            await writer.drain()
            while line := await reader.readline():
                try:
                    reply = await self.handle(json.loads(line))
                except (ValueError, TypeError, KeyError, OSError) as e:
                    log("bad message:", e)
                    continue
                if reply:
                    send(reply)
                    await writer.drain()
        except (asyncio.TimeoutError, ConnectionError, json.JSONDecodeError):
            pass
        finally:
            log("disconnected:", peer)
            writer.close()


class Discovery(asyncio.DatagramProtocol):
    def __init__(self, port):
        self.reply = json.dumps({"app": "pc-remote", "name": socket.gethostname(), "port": port}).encode()

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, addr):
        if data.strip() == b"PCREMOTE_DISCOVER":
            self.transport.sendto(self.reply, addr)


# --------------------------------------------------------------------------- setup

def config_dir() -> Path:
    return Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config") / "pc-remote"


def load_code(new: bool) -> str:
    f = config_dir() / "pairing-code"
    if not new and f.exists():
        code = f.read_text().strip()
        if code:
            return code
    code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(8))
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(code + "\n")
    f.chmod(0o600)
    return code


def local_ips() -> list[str]:
    ips = set()
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))  # no packet is sent; this just picks the LAN interface
            ips.add(s.getsockname()[0])
    except OSError:
        pass
    try:
        ips.update(a[4][0] for a in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET))
    except OSError:
        pass
    return sorted(i for i in ips if not i.startswith("127."))


async def main():
    ap = argparse.ArgumentParser(description="PC Remote agent (Wi-Fi mode)")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--bind", default="0.0.0.0", help="address to listen on (default: all)")
    ap.add_argument("--new-code", action="store_true", help="generate a new pairing code")
    ap.add_argument("--no-discovery", action="store_true", help="don't answer network discovery broadcasts")
    args = ap.parse_args()

    code = load_code(args.new_code)
    agent = Agent(code, args.port)
    await agent.probe()

    server = await asyncio.start_server(agent.client, args.bind, args.port)
    if not args.no_discovery:
        loop = asyncio.get_running_loop()
        try:
            await loop.create_datagram_endpoint(lambda: Discovery(args.port), local_addr=(args.bind, DISCOVERY_PORT),
                                                allow_broadcast=True)
        except OSError as e:
            log("discovery disabled:", e)

    print()
    print(f"  PC Remote agent on {socket.gethostname()}")
    print(f"  Address:      {', '.join(local_ips()) or '?'}  port {args.port}")
    print(f"  Pairing code: {code}")
    print()
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    if sys.version_info < (3, 8):
        sys.exit("Python 3.8 or newer is required")
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
