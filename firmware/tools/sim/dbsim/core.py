"""ctypes wrapper of libdbsim.so (tools/sim/core): the firmware's own C code.

The library keeps its state in C statics, so there is one simulated device
per process; Core.init() boots (or reboots) it. The shared library is built
on demand with cmake/ninja when it is missing or older than its sources.
"""

from __future__ import annotations

import ctypes
import json
import os
import shutil
import subprocess
from array import array
from pathlib import Path

SIM_DIR = Path(__file__).resolve().parent.parent
CORE_DIR = SIM_DIR / "core"
BUILD_DIR = CORE_DIR / "build"
LIB_PATH = BUILD_DIR / "libdbsim.so"
FIRMWARE_DIR = SIM_DIR.parent.parent
COMPONENTS_DIR = FIRMWARE_DIR / "components"
# Components compiled into the library (CMakeLists.txt), and the header stubs.
_SOURCE_COMPONENTS = ("level", "settings", "ota", "ble", "power", "ui", "app_core", "board", "imu",
                      "recorder")
_STUBS_DIR = FIRMWARE_DIR / "test_host" / "stubs"

# sim_core.h
EV_SLEEP = 0x01
EV_POWER_OFF = 0x02
EV_REBOOT = 0x04
EV_CLEAR_BONDS = 0x08
EV_BLE_ENABLED = 0x10
EV_SETTINGS = 0x20
EV_CAL = 0x40
EV_OTA = 0x80
EV_REC_AP = 0x100
EV_REC_AUDIO = 0x200
EV_REC_TAKE = 0x400

TX_ANGLE = 0x01
TX_STATUS = 0x02
TX_BATTERY = 0x04
TX_KICK = 0x08
TX_CONN_FAST = 0x10
TX_CONN_SLOW = 0x20

SUB_ANGLE = 0x01
SUB_STATUS = 0x02
SUB_BAT = 0x04
SUB_OTA = 0x08
SUB_REC = 0x10

FAULT_STORE = 0x01
FAULT_FLASH = 0x02

STATES = ("running", "sleep", "off", "reboot")   # SIM_STATE_*

MAX_PASS_SAMPLES = 256
REC_RING_DEFAULT = 512 * 1024
NET_UP = 0
NET_FAIL = 1
NET_HELLO = 2
NET_CLIENT_LOST = 3
NET_DOWN = 4
AUDIO_RATE = 16000
AUDIO_BLOCK = 256
MAX_IMPACTS = 8
REC_STATE_SIZE = 86   # db_rec_state_packet_t


class Motor(ctypes.Structure):
    """sim_motor_t: the motor during one audio block (sim_audio_block)."""

    _fields_ = [("f0_hz", ctypes.c_float * 2), ("hum", ctypes.c_float * 2),
                ("noise", ctypes.c_float * 2), ("ambient", ctypes.c_float),
                ("n_impacts", ctypes.c_int32),
                ("impact_s", ctypes.c_float * MAX_IMPACTS),
                ("impact_amp", ctypes.c_float * MAX_IMPACTS),
                ("impact_tau_s", ctypes.c_float * MAX_IMPACTS)]

# app_state.h app_cmd_id_t
APP_CMD_ZERO = 0
APP_CMD_CLEAR_ZERO = 1
APP_CMD_SET_MODE = 2
APP_CMD_NEXT_MODE = 3
APP_CMD_MOUNT_CAL_START = 4
APP_CMD_MOUNT_CAL_FINISH = 5
APP_CMD_ACCEL_CAL_START = 6
APP_CMD_ACCEL_CAL_FINISH = 7
APP_CMD_CAL_CANCEL = 8
APP_CMD_SETTINGS_CHANGED = 9
APP_CMD_SLEEP = 10
APP_CMD_POWER_OFF = 11
APP_CMD_REBOOT = 12
APP_CMD_FACTORY_RESET = 13
APP_CMD_ARG_FROM_BLE = 1

POWER_NAMES = ("active", "dim", "sleep", "off")   # app_power_t


def _tool_env() -> dict:
    """PATH with ESP-IDF's cmake/ninja in front, where installed."""
    env = dict(os.environ)
    tools = Path.home() / ".espressif" / "tools"
    extra = [str(p) for p in (tools / "cmake" / "3.30.2" / "bin", tools / "ninja" / "1.12.1")
             if p.is_dir()]
    env["PATH"] = os.pathsep.join(extra + [env.get("PATH", "")])
    return env


def _sources():
    for name in ("sim_core.c", "sim_core.h", "sim_audio.c", "sim_audio.h", "sim_rand.h",
                 "CMakeLists.txt"):
        yield CORE_DIR / name
    for d in [COMPONENTS_DIR / c for c in _SOURCE_COMPONENTS] + [_STUBS_DIR]:
        for p in d.rglob("*"):
            if p.suffix in (".c", ".h"):
                yield p


def lib_is_stale() -> bool:
    if not LIB_PATH.exists():
        return True
    built = LIB_PATH.stat().st_mtime
    return any(p.stat().st_mtime > built for p in _sources())


def build_lib(quiet: bool = True) -> Path:
    """Configures (once) and builds libdbsim.so; returns its path."""
    env = _tool_env()
    cmake = shutil.which("cmake", path=env["PATH"])
    if cmake is None:
        raise RuntimeError("cmake not found (install it or ESP-IDF's tools)")
    out = subprocess.DEVNULL if quiet else None
    if not (BUILD_DIR / "CMakeCache.txt").exists():
        gen = ["-G", "Ninja"] if shutil.which("ninja", path=env["PATH"]) else []
        subprocess.run([cmake, "-S", str(CORE_DIR), "-B", str(BUILD_DIR), *gen],
                       env=env, check=True, stdout=out)
    r = subprocess.run([cmake, "--build", str(BUILD_DIR)], env=env,
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if r.returncode != 0:
        raise RuntimeError("building libdbsim.so failed:\n" + r.stdout)
    return LIB_PATH


_fw_git = None


def firmware_git() -> str:
    """`git describe --always --dirty --abbrev=7` of the firmware tree, as the
    build's REC_FW_GIT (components/recorder/CMakeLists.txt); "" outside git."""
    global _fw_git
    if _fw_git is None:
        try:
            r = subprocess.run(["git", "describe", "--always", "--dirty", "--abbrev=7", "--match=-"],
                               cwd=COMPONENTS_DIR / "recorder", capture_output=True, text=True,
                               timeout=10)
            _fw_git = r.stdout.strip() if r.returncode == 0 else ""
        except (OSError, subprocess.SubprocessError):
            _fw_git = ""
    return _fw_git


_lib = None


def _load():
    global _lib
    if _lib is not None:
        return _lib
    if lib_is_stale():
        build_lib()
    lib = ctypes.CDLL(str(LIB_PATH))
    u8p = ctypes.POINTER(ctypes.c_uint8)
    sig = {
        "sim_init": (ctypes.c_int, [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p,
                                    ctypes.c_uint32, ctypes.c_int]),
        "sim_state": (ctypes.c_int, []),
        "sim_fw_version": (ctypes.c_char_p, []),
        "sim_dis_string": (ctypes.c_char_p, [ctypes.c_int]),
        "sim_ble_enabled": (ctypes.c_int, []),
        "sim_off_after_s": (ctypes.c_uint32, []),
        "sim_take_events": (ctypes.c_uint32, []),
        "sim_log_pop": (ctypes.c_int, [ctypes.c_char_p, ctypes.c_int]),
        "sim_snapshot_json": (ctypes.c_int, [ctypes.c_char_p, ctypes.c_int]),
        "sim_sensor_pass": (None, [ctypes.c_uint32, ctypes.POINTER(ctypes.c_float), ctypes.c_int,
                                   ctypes.c_float, ctypes.c_int]),
        "sim_control_step": (None, [ctypes.c_uint32]),
        "sim_post_cmd": (ctypes.c_int, [ctypes.c_int, ctypes.c_int]),
        "sim_note_activity": (None, [ctypes.c_uint32]),
        "sim_set_battery": (None, [ctypes.c_int] * 6),
        "sim_pwrkey": (None, [ctypes.c_int]),
        "sim_power": (ctypes.c_int, []),
        "sim_config_read": (ctypes.c_int, [ctypes.c_int, ctypes.c_char_p, ctypes.c_int]),
        "sim_settings_json": (ctypes.c_int, [ctypes.c_char_p, ctypes.c_int]),
        "sim_config_write": (ctypes.c_int, [u8p, ctypes.c_int, ctypes.c_uint32]),
        "sim_ui_settings_edit": (ctypes.c_int, [ctypes.c_char_p, ctypes.c_int, ctypes.c_uint32]),
        "sim_command_write": (ctypes.c_int, [u8p, ctypes.c_int, ctypes.c_uint32]),
        "sim_ota_ctrl_write": (ctypes.c_int, [u8p, ctypes.c_int, ctypes.c_uint32]),
        "sim_ota_data_write": (ctypes.c_int, [u8p, ctypes.c_int, ctypes.c_uint32]),
        "sim_ota_reply_pop": (ctypes.c_int, [u8p]),
        "sim_ota_set_mtu": (None, [ctypes.c_int]),
        "sim_ota_tick": (None, [ctypes.c_uint32]),
        "sim_ota_reboot_pending": (ctypes.c_int, []),
        "sim_link_connect": (ctypes.c_int, [ctypes.c_uint32]),
        "sim_link_disconnect": (None, [ctypes.c_uint32]),
        "sim_link_passkey": (None, [ctypes.c_uint32, ctypes.c_uint32]),
        "sim_link_enc_change": (None, [ctypes.c_int, ctypes.c_int, ctypes.c_uint32]),
        "sim_link_subscribe": (None, [ctypes.c_int, ctypes.c_int]),
        "sim_link_trusted": (ctypes.c_int, []),
        "sim_notify_tick": (ctypes.c_uint32, [ctypes.c_uint32, u8p, u8p, u8p]),
        "sim_angle_packet": (None, [ctypes.c_uint8, ctypes.c_uint32, u8p]),
        "sim_status_packet": (None, [u8p]),
        "sim_battery_pct": (ctypes.c_int, []),
        "sim_set_faults": (None, [ctypes.c_uint32]),
        "sim_fault_busy": (None, [ctypes.c_int]),
        "sim_ui_text": (ctypes.c_char_p, [ctypes.c_int, ctypes.c_int]),
        "sim_seed": (None, [ctypes.c_uint32]),
        "sim_set_identity": (None, [u8p, ctypes.c_char_p]),
        "sim_imu_odr_hz": (ctypes.c_float, []),
        "sim_fault_profile_lag": (None, [ctypes.c_int]),
        "sim_rec_ctrl_write": (ctypes.c_int, [u8p, ctypes.c_int, ctypes.c_uint32]),
        "sim_rec_state_read": (None, [u8p]),
        "sim_rec_notify": (ctypes.c_int, [ctypes.c_uint32, u8p]),
        "sim_rec_set_endpoint": (None, [ctypes.c_int, u8p, ctypes.c_int, ctypes.c_int,
                                        ctypes.c_char_p, ctypes.c_char_p]),
        "sim_rec_set_ring_size": (None, [ctypes.c_uint32]),
        "sim_rec_net": (ctypes.c_int, [ctypes.c_int, u8p, ctypes.c_int, ctypes.c_uint32]),
        "sim_rec_ap_wanted": (ctypes.c_int, []),
        "sim_rec_audio_on": (ctypes.c_int, []),
        "sim_rec_tick": (None, [ctypes.c_uint32]),
        "sim_rec_pop": (ctypes.c_int, [u8p, ctypes.c_int]),
        "sim_rec_note_touch": (ctypes.c_int, [ctypes.c_uint32]),
        "sim_rec_ui_stop": (ctypes.c_int, [ctypes.c_uint32]),
        "sim_audio_block": (ctypes.c_int, [ctypes.c_uint32, ctypes.POINTER(Motor)]),
        "sim_set_mic_gain": (None, [ctypes.c_int, ctypes.c_float]),
    }
    for name, (res, args) in sig.items():
        fn = getattr(lib, name)
        fn.restype = res
        fn.argtypes = args
    _lib = lib
    return lib


def _u32(t) -> int:
    return int(t) & 0xFFFFFFFF


def _bytes_arg(data: bytes):
    data = bytes(data)
    buf = (ctypes.c_uint8 * max(1, len(data))).from_buffer_copy(data or b"\0")
    return buf, len(data)


class Core:
    """Thin typed access to libdbsim. Times are core milliseconds (app_now_ms)."""

    def __init__(self):
        self._lib = _load()
        self._pop_buf = None

    # ---- boot and state ----
    def init(self, state_path, ota_image_path, default_version: str, now_ms: int,
             imu_present: bool = True) -> None:
        rc = self._lib.sim_init(str(state_path).encode(), str(ota_image_path).encode(),
                                default_version.encode(), _u32(now_ms), int(imu_present))
        if rc != 0:
            raise ValueError("sim_init failed (path too long?)")

    @property
    def state(self) -> str:
        return STATES[self._lib.sim_state()]

    @property
    def fw_version(self) -> str:
        return self._lib.sim_fw_version().decode()

    def dis_string(self, which: int) -> str:
        return self._lib.sim_dis_string(which).decode()

    @property
    def ble_enabled(self) -> bool:
        return bool(self._lib.sim_ble_enabled())

    @property
    def off_after_s(self) -> int:
        return self._lib.sim_off_after_s()

    def take_events(self) -> int:
        return self._lib.sim_take_events()

    def log_lines(self) -> list:
        """Firmware log lines since the last call, as (level 'I'/'W'/'E', text)."""
        out = []
        buf = ctypes.create_string_buffer(256)
        while self._lib.sim_log_pop(buf, len(buf)) > 0:
            line = buf.value.decode(errors="replace")
            out.append((line[0], line[2:]))
        return out

    def snapshot(self) -> dict:
        buf = ctypes.create_string_buffer(16384)
        n = self._lib.sim_snapshot_json(buf, len(buf))
        if n < 0:
            raise RuntimeError("snapshot does not fit")
        return json.loads(buf.value.decode())

    def seed(self, seed: int) -> None:
        """The simulated randomness (FIFO read timing, microphone noise); before init()."""
        self._lib.sim_seed(_u32(seed))

    def set_identity(self, board_id: bytes, fw_git: str) -> None:
        """SESSION.board_id (4 bytes) and the git revision in SESSION.fw; before init()."""
        buf, _ = _bytes_arg(bytes(board_id[:4]).ljust(4, b"\0"))
        self._lib.sim_set_identity(buf, fw_git.encode())

    # ---- sensor task ----
    def sensor_pass(self, now_ms: int, samples, dt: float, imu_present: bool = True) -> None:
        """samples: what arrived at the chip since the last pass, a sequence of
        (ax, ay, az, gx, gy, gz) at imu_odr_hz; empty = nothing arrived."""
        flat = array("f", (v for s in samples for v in s))
        n = min(len(flat) // 6, MAX_PASS_SAMPLES)
        ptr = (ctypes.c_float * max(1, len(flat))).from_buffer(flat) if n else None
        self._lib.sim_sensor_pass(_u32(now_ms), ptr, n, dt, int(imu_present))

    @property
    def imu_odr_hz(self) -> float:
        """The sample rate of the profile the simulated chip runs."""
        return float(self._lib.sim_imu_odr_hz())

    def fault_profile_lag(self, passes: int) -> None:
        """The next passes read before they take a requested profile."""
        self._lib.sim_fault_profile_lag(int(passes))

    # ---- control task ----
    def control_step(self, now_ms: int) -> None:
        self._lib.sim_control_step(_u32(now_ms))

    def post_cmd(self, cmd_id: int, arg: int = 0) -> bool:
        return bool(self._lib.sim_post_cmd(cmd_id, arg))

    def note_activity(self, now_ms: int) -> None:
        self._lib.sim_note_activity(_u32(now_ms))

    def set_battery(self, valid: bool, percent: int, voltage_mv: int, charging: bool,
                    charge_done: bool, vbus: bool) -> None:
        self._lib.sim_set_battery(int(valid), int(percent), int(voltage_mv), int(charging),
                                  int(charge_done), int(vbus))

    def pwrkey(self, long_press: bool = False) -> None:
        self._lib.sim_pwrkey(int(long_press))

    @property
    def power(self) -> int:
        """app_power_t; POWER_NAMES has the names."""
        return self._lib.sim_power()

    # ---- BLE service ----
    def config_read(self, offset: int = 0) -> bytes:
        """The config characteristic's whole value: captured at offset 0 and
        kept for the blobs of a long read (ble_service.c config_read)."""
        buf = ctypes.create_string_buffer(512)
        n = self._lib.sim_config_read(int(offset), buf, len(buf))
        if n < 0:
            raise RuntimeError("settings JSON does not fit")
        return buf.raw[:n]

    def settings_json(self) -> str:
        """The stored settings (leaves the config read capture alone)."""
        buf = ctypes.create_string_buffer(512)
        n = self._lib.sim_settings_json(buf, len(buf))
        if n < 0:
            raise RuntimeError("settings JSON does not fit")
        return buf.raw[:n].decode()

    def config_write(self, data: bytes, now_ms: int) -> int:
        buf, n = _bytes_arg(data)
        return self._lib.sim_config_write(buf, n, _u32(now_ms))

    def ui_settings_edit(self, json_text: str, now_ms: int) -> int:
        data = json_text.encode()
        return self._lib.sim_ui_settings_edit(data, len(data), _u32(now_ms))

    def command_write(self, data: bytes, now_ms: int) -> int:
        buf, n = _bytes_arg(data)
        return self._lib.sim_command_write(buf, n, _u32(now_ms))

    def ota_ctrl_write(self, data: bytes, now_ms: int) -> int:
        buf, n = _bytes_arg(data)
        return self._lib.sim_ota_ctrl_write(buf, n, _u32(now_ms))

    def ota_data_write(self, data: bytes, now_ms: int) -> int:
        buf, n = _bytes_arg(data)
        return self._lib.sim_ota_data_write(buf, n, _u32(now_ms))

    def ota_replies(self) -> list:
        out = []
        buf = (ctypes.c_uint8 * 8)()
        while (n := self._lib.sim_ota_reply_pop(buf)) > 0:
            out.append(bytes(buf[:n]))
        return out

    def ota_set_mtu(self, mtu: int) -> None:
        self._lib.sim_ota_set_mtu(int(mtu))

    def ota_tick(self, now_ms: int) -> None:
        self._lib.sim_ota_tick(_u32(now_ms))

    @property
    def ota_reboot_pending(self) -> bool:
        return bool(self._lib.sim_ota_reboot_pending())

    def link_connect(self, now_ms: int) -> bool:
        """Returns False if the link must be terminated at once (BLE disabled)."""
        return self._lib.sim_link_connect(_u32(now_ms)) == 0

    def link_disconnect(self, now_ms: int) -> None:
        self._lib.sim_link_disconnect(_u32(now_ms))

    def link_passkey(self, passkey: int, now_ms: int) -> None:
        self._lib.sim_link_passkey(int(passkey), _u32(now_ms))

    def link_enc_change(self, status: int, trusted: bool, now_ms: int) -> None:
        self._lib.sim_link_enc_change(int(status), int(trusted), _u32(now_ms))

    def link_subscribe(self, sub_bit: int, on: bool) -> None:
        self._lib.sim_link_subscribe(sub_bit, int(on))

    @property
    def link_trusted(self) -> bool:
        return bool(self._lib.sim_link_trusted())

    def notify_tick(self, now_ms: int):
        """Returns (TX_* bits, angle bytes, status bytes, battery percent)."""
        a = (ctypes.c_uint8 * 14)()
        s = (ctypes.c_uint8 * 10)()
        b = (ctypes.c_uint8 * 1)()
        tx = self._lib.sim_notify_tick(_u32(now_ms), a, s, b)
        return tx, bytes(a), bytes(s), b[0]

    def angle_packet(self, seq: int, now_ms: int) -> bytes:
        a = (ctypes.c_uint8 * 14)()
        self._lib.sim_angle_packet(seq & 0xFF, _u32(now_ms), a)
        return bytes(a)

    def status_packet(self) -> bytes:
        s = (ctypes.c_uint8 * 10)()
        self._lib.sim_status_packet(s)
        return bytes(s)

    @property
    def battery_pct(self) -> int:
        return self._lib.sim_battery_pct()

    # ---- faults and texts ----
    def set_faults(self, mask: int) -> None:
        self._lib.sim_set_faults(mask)

    def fault_busy(self, n: int) -> None:
        self._lib.sim_fault_busy(n)

    def ui_text(self, which: int, code: int) -> str:
        return self._lib.sim_ui_text(which, code).decode()

    # ---- recorder ----
    def rec_ctrl_write(self, data: bytes, now_ms: int) -> int:
        buf, n = _bytes_arg(data)
        return self._lib.sim_rec_ctrl_write(buf, n, _u32(now_ms))

    def rec_state(self) -> bytes:
        out = (ctypes.c_uint8 * REC_STATE_SIZE)()
        self._lib.sim_rec_state_read(out)
        return bytes(out)

    def rec_notify(self, now_ms: int) -> bytes | None:
        """The rec_state notification due now (100 ms timer), or None."""
        out = (ctypes.c_uint8 * REC_STATE_SIZE)()
        return bytes(out) if self._lib.sim_rec_notify(_u32(now_ms), out) else None

    def rec_set_endpoint(self, no_ap: bool, ip: str, port: int, channel: int = 0, ssid: str = "",
                         password: str = "") -> None:
        buf, _ = _bytes_arg(bytes(int(x) for x in ip.split(".")))
        self._lib.sim_rec_set_endpoint(int(no_ap), buf, int(port), int(channel), ssid.encode(),
                                       password.encode())

    def rec_set_ring_size(self, size: int) -> None:
        self._lib.sim_rec_set_ring_size(int(size))

    def rec_net(self, kind: int, data: bytes = b"", now_ms: int = 0) -> bool:
        buf, n = _bytes_arg(data)
        return bool(self._lib.sim_rec_net(kind, buf, n, _u32(now_ms)))

    @property
    def rec_ap_wanted(self) -> bool:
        return bool(self._lib.sim_rec_ap_wanted())

    @property
    def rec_audio_on(self) -> bool:
        return bool(self._lib.sim_rec_audio_on())

    def rec_tick(self, now_ms: int) -> None:
        self._lib.sim_rec_tick(_u32(now_ms))

    def rec_pop(self, cap: int = 65536) -> bytes:
        """Stream bytes the sender takes out of the ring now (at most cap)."""
        if self._pop_buf is None or len(self._pop_buf) < cap:
            self._pop_buf = (ctypes.c_uint8 * cap)()
        n = self._lib.sim_rec_pop(self._pop_buf, cap)
        return ctypes.string_at(self._pop_buf, n) if n > 0 else b""

    def rec_note_touch(self, now_ms: int) -> bool:
        return bool(self._lib.sim_rec_note_touch(_u32(now_ms)))

    def rec_ui_stop(self, now_ms: int) -> int:
        return self._lib.sim_rec_ui_stop(_u32(now_ms))

    def audio_block(self, now_ms: int, motor: Motor) -> int:
        """One 16 ms microphone block (into the stream). Returns the frames."""
        return self._lib.sim_audio_block(_u32(now_ms), ctypes.byref(motor))

    def set_mic_gain(self, mic: int, db: float) -> None:
        self._lib.sim_set_mic_gain(int(mic), float(db))
