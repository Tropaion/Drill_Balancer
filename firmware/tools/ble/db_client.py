#!/usr/bin/env python3
"""DrillBalancer BLE test client.

Talks to the firmware's GATT protocol (components/ble/include/ble_protocol.h
and components/ota/include/ota_proto.h). Run `db_client.py --help`.

Importable: the packet encoders / decoders below have no bleak dependency, and
`python3 db_client.py --dry-run` checks them against the C struct layouts
without any Bluetooth hardware.
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
import re
import struct
import sys
import time
from dataclasses import dataclass
from typing import Optional

try:  # bleak is only needed for the commands that talk to a device
    from bleak import BleakClient, BleakScanner
    from bleak.exc import BleakError
except ImportError:  # pragma: no cover - depends on the environment
    BleakClient = BleakScanner = None

    class BleakError(Exception):
        pass


# ---- protocol constants (ble_protocol.h) -------------------------------------

def db_uuid(x: int) -> str:
    """DB_UUID(x): x goes in bytes 2..3 of the base UUID."""
    return f"d71b{x:04x}-8a3e-4f6c-b5d2-1c9e0a7f4b63"


# The DB_SVC_* / DB_CHR_* ids of ble_protocol.h (self_check compares them).
UUID_IDS = {
    "DB_SVC_MAIN": 0x0001,
    "DB_CHR_ANGLE": 0x0002,
    "DB_CHR_CONFIG": 0x0003,
    "DB_CHR_COMMAND": 0x0004,
    "DB_CHR_STATUS": 0x0005,
    "DB_SVC_OTA": 0x0100,
    "DB_CHR_OTA_CTRL": 0x0101,
    "DB_CHR_OTA_DATA": 0x0102,
    "DB_SVC_REC": 0x0200,
    "DB_CHR_REC_CTRL": 0x0201,
    "DB_CHR_REC_STATE": 0x0202,
}
SVC_MAIN = db_uuid(UUID_IDS["DB_SVC_MAIN"])
CHR_ANGLE = db_uuid(UUID_IDS["DB_CHR_ANGLE"])
CHR_CONFIG = db_uuid(UUID_IDS["DB_CHR_CONFIG"])
CHR_COMMAND = db_uuid(UUID_IDS["DB_CHR_COMMAND"])
CHR_STATUS = db_uuid(UUID_IDS["DB_CHR_STATUS"])
SVC_OTA = db_uuid(UUID_IDS["DB_SVC_OTA"])
CHR_OTA_CTRL = db_uuid(UUID_IDS["DB_CHR_OTA_CTRL"])
CHR_OTA_DATA = db_uuid(UUID_IDS["DB_CHR_OTA_DATA"])
SVC_REC = db_uuid(UUID_IDS["DB_SVC_REC"])
CHR_REC_CTRL = db_uuid(UUID_IDS["DB_CHR_REC_CTRL"])
CHR_REC_STATE = db_uuid(UUID_IDS["DB_CHR_REC_STATE"])
CHR_BATTERY = "00002a19-0000-1000-8000-00805f9b34fb"

NAME_PREFIX = "DrillBalancer-"

ANGLE_FLAGS = {
    "on_target": 1 << 0,
    "near": 1 << 1,
    "yaw_valid": 1 << 2,
    "yaw_stale": 1 << 3,
    "zero_set": 1 << 4,
    "still": 1 << 5,
    "imu_ok": 1 << 6,
}
STATUS_FLAGS = {
    "battery_valid": 1 << 0,
    "charging": 1 << 1,
    "charge_done": 1 << 2,
    "vbus": 1 << 3,
    "mount_cal": 1 << 4,
    "accel_cal": 1 << 5,
    "calibrating": 1 << 6,
    "recording": 1 << 7,
}
MODES = ["wall", "floor", "angle"]                    # level_mode_t
POWER_STATES = ["active", "dim", "sleep", "off"]      # app_power_t
CAL_KINDS = ["idle", "mount", "accel"]                # app_cal_kind_t

CMDS = {
    "zero": 0x01,
    "clear-zero": 0x02,
    "set-mode": 0x03,
    "mount-cal-start": 0x10,
    "mount-cal-finish": 0x11,
    "accel-cal-start": 0x12,
    "accel-cal-finish": 0x13,
    "cal-cancel": 0x14,
    "sleep": 0x20,
    "power-off": 0x21,
    "reboot": 0x22,
    "factory-reset": 0x23,
}
FACTORY_RESET_CONFIRM = 0xA5

ATT_ERRORS = {
    0x05: "insufficient authentication (pair first)",
    0x0C: "insufficient key size (pair again; a 16-byte key is required)",
    0x0D: "invalid length",
    0x0F: "insufficient encryption (pair first)",
    0x80: "config: not a JSON object",
    0x81: "config: a key has the wrong type",
    0x82: "config: value out of range",
    0x83: "config: saving to flash failed",
    0x84: "command: unknown opcode",
    0x85: "command: bad argument",
    0x86: "command: queue full, try again",
    0x87: "OTA not available",
    0x88: "recorder: not in this state, or OTA / calibration running",
    0x89: "recorder: Wi-Fi or the stream buffer could not be set up",
    0x8A: "recorder: battery too low for a take (plug in USB)",
}

# ---- recorder (rec_ctrl / rec_state) -----------------------------------------

REC_OPS = {"open": 0x01, "close": 0x02, "take-start": 0x03, "stop": 0x04, "mark": 0x05}
REC_TAKE_START_LEN = 10
REC_MARK_TEXT_MAX = 40
REC_PROFILES = ["LEVEL", "RAW448", "RAW448_16G", "RAW897"]        # DB_REC_PROFILE_*
REC_MICS = {"mic_1": 1 << 0, "mic_2": 1 << 1}
REC_GAIN_MAX_DB = 37
REC_MAX_S_DEFAULT = 300
REC_MAX_S_LIMIT = 1200
REC_STATES = ["idle", "ap_starting", "wait_client", "ready", "recording", "draining",
              "closing"]                                          # DB_REC_STATE_*
REC_REASONS = ["none", "user", "max_s", "client_lost", "ble_lost", "battery", "imu",
               "shutdown", "ap_fail", "no_client", "idle"]        # DB_REC_REASON_*
REC_FLAGS = {
    "no_ap": 1 << 0,
    "client": 1 << 1,
    "audio": 1 << 2,
    "imu_lost": 1 << 3,
    "battery_low": 1 << 4,
}

# ---- OTA protocol (ota_proto.h) ---------------------------------------------

OTA_OP_START = 0x01
OTA_OP_FINISH = 0x02
OTA_OP_ABORT = 0x03
OTA_MSG_REPLY = 0x80
OTA_MSG_ACK = 0x81
OTA_MSG_NACK = 0x82

OTA_STATUS = [
    "OK", "BAD_REQUEST", "BAD_STATE", "TOO_LARGE", "FLASH_ERROR",
    "SIZE_MISMATCH", "HASH_MISMATCH", "SEQ_ERROR", "TIMEOUT",
]
OTA_STATUS_OK = 0
OTA_STATUS_BAD_REQUEST = 1
OTA_STATUS_SEQ_ERROR = 7

# ATT write header (3) + OTA seq (2): the largest payload one write carries.
OTA_CHUNK_OVERHEAD = 5
# NACK BAD_REQUEST for the same chunk this many times in a row: give up
# instead of resending a chunk the device will never take.
OTA_MAX_BAD_REQUESTS = 3

# app_state.h APP_CAL_RESULT_* (status packet cal_last_result)
CAL_RESULT_NONE = -1
CAL_RESULT_NOT_STORED = -2
CAL_RESULT_OK_MOUNT_DROPPED = -3


# ---- packet codecs -----------------------------------------------------------

ANGLE_FMT = "<BBBBhhhhh"     # db_angle_packet_t, 14 bytes
STATUS_FMT = "<BBHBBBBBb"    # db_status_packet_t, 10 bytes
# db_rec_state_packet_t, 86 bytes: version state reason flags take_id elapsed_s
# max_s sent_kib dropped ring_pct profile channel reserved port ip ssid pass token
REC_STATE_FMT = "<BBBBIHHIIBBBBH4s16s24s16s"
ANGLE_SIZE = struct.calcsize(ANGLE_FMT)
STATUS_SIZE = struct.calcsize(STATUS_FMT)
REC_STATE_SIZE = struct.calcsize(REC_STATE_FMT)


def _flag_names(value: int, table: dict) -> list:
    return [name for name, bit in table.items() if value & bit]


def _name(table, i: int, fallback: str | None = None) -> str:
    """table[i], or fallback (default str(i)) for a value it does not know."""
    return table[i] if 0 <= i < len(table) else (fallback if fallback is not None else str(i))


@dataclass
class Angle:
    version: int
    mode: int
    flags: int
    seq: int
    elevation: float   # degrees
    yaw: float
    bubble_x: float
    bubble_y: float
    deviation: float

    @property
    def mode_name(self) -> str:
        return _name(MODES, self.mode)

    @property
    def flag_names(self) -> list:
        return _flag_names(self.flags, ANGLE_FLAGS)

    @property
    def zero_set(self) -> bool:
        """A zero is set; also with the bit vertical, where yaw stays invalid."""
        return bool(self.flags & ANGLE_FLAGS["zero_set"])

    @property
    def yaw_valid(self) -> bool:
        return bool(self.flags & ANGLE_FLAGS["yaw_valid"])


def encode_angle(a: Angle) -> bytes:
    def cdeg(x: float) -> int:
        return max(-32768, min(32767, round(x * 100)))
    return struct.pack(ANGLE_FMT, a.version, a.mode, a.flags, a.seq & 0xFF,
                       cdeg(a.elevation), cdeg(a.yaw), cdeg(a.bubble_x),
                       cdeg(a.bubble_y), cdeg(a.deviation))


def decode_angle(data: bytes) -> Angle:
    if len(data) != ANGLE_SIZE:
        raise ValueError(f"angle packet is {len(data)} bytes, expected {ANGLE_SIZE}")
    v, mode, flags, seq, el, yaw, bx, by, dev = struct.unpack(ANGLE_FMT, data)
    return Angle(v, mode, flags, seq, el / 100, yaw / 100, bx / 100, by / 100, dev / 100)


@dataclass
class Status:
    version: int
    battery_pct: int
    battery_mv: int
    flags: int
    power_state: int
    cal_kind: int
    cal_samples: int
    cal_coverage: int
    cal_last_result: int

    @property
    def flag_names(self) -> list:
        return _flag_names(self.flags, STATUS_FLAGS)


def encode_status(s: Status) -> bytes:
    return struct.pack(STATUS_FMT, s.version, s.battery_pct, s.battery_mv, s.flags,
                       s.power_state, s.cal_kind, s.cal_samples, s.cal_coverage,
                       s.cal_last_result)


def decode_status(data: bytes) -> Status:
    if len(data) != STATUS_SIZE:
        raise ValueError(f"status packet is {len(data)} bytes, expected {STATUS_SIZE}")
    return Status(*struct.unpack(STATUS_FMT, data))


def encode_cmd(name: str, arg: Optional[str] = None) -> bytes:
    if name not in CMDS:
        raise ValueError(f"unknown command {name!r}; one of: {', '.join(CMDS)}")
    op = CMDS[name]
    if name == "set-mode":
        if arg is None:
            raise ValueError("set-mode needs a mode: " + "|".join(MODES))
        mode = MODES.index(arg) if arg in MODES else int(arg)
        if not 0 <= mode < len(MODES):
            raise ValueError(f"mode out of range: {arg}")
        return bytes([op, mode])
    if arg is not None:
        raise ValueError(f"{name} takes no argument")
    if name == "factory-reset":
        return bytes([op, FACTORY_RESET_CONFIRM])
    return bytes([op])


def encode_rec(op: str, *, profile: int = 1, mic_mask: int = 3, gain_db: int = 6,
               max_s: int = 0, take_id: int = 0, code: int = 0, text: str = "") -> bytes:
    """A rec_ctrl write. take-start and mark use their keyword arguments."""
    if op not in REC_OPS:
        raise ValueError(f"unknown recorder op {op!r}; one of: {', '.join(REC_OPS)}")
    b = bytes([REC_OPS[op]])
    if op == "take-start":
        if not 0 <= profile < len(REC_PROFILES):
            raise ValueError(f"profile out of range: {profile}")
        if mic_mask & ~sum(REC_MICS.values()) or not 0 <= gain_db <= REC_GAIN_MAX_DB:
            raise ValueError("mic_mask or gain_db out of range")
        if not 0 <= max_s <= REC_MAX_S_LIMIT:
            raise ValueError(f"max_s out of range: {max_s}")
        return b + struct.pack("<BBBHI", profile, mic_mask, gain_db, max_s, take_id & 0xFFFFFFFF)
    if op == "mark":
        t = text.encode("utf-8")    # a lone surrogate raises here
        # the firmware refuses C0/C1 controls and DEL (ble_codec.c mark_text_ok)
        if len(t) > REC_MARK_TEXT_MAX or any(ord(c) < 0x20 or 0x7F <= ord(c) <= 0x9F for c in text):
            raise ValueError(f"mark text: at most {REC_MARK_TEXT_MAX} bytes, printable")
        return b + struct.pack("<H", code & 0xFFFF) + t
    return b


@dataclass
class RecState:
    version: int
    state: int
    reason: int
    flags: int
    take_id: int
    elapsed_s: int
    max_s: int
    sent_kib: int
    dropped: int
    ring_pct: int
    profile: int
    channel: int
    port: int
    ip: str
    ssid: str
    password: str
    token: bytes

    @property
    def state_name(self) -> str:
        return _name(REC_STATES, self.state)

    @property
    def reason_name(self) -> str:
        return _name(REC_REASONS, self.reason)

    @property
    def flag_names(self) -> list:
        return _flag_names(self.flags, REC_FLAGS)


def _cstr(b: bytes) -> str:
    return b.split(b"\0", 1)[0].decode("utf-8", "replace")


def decode_rec_state(data: bytes) -> RecState:
    if len(data) != REC_STATE_SIZE:
        raise ValueError(f"rec_state packet is {len(data)} bytes, expected {REC_STATE_SIZE}")
    (v, st, reason, flags, take, el, mx, kib, dropped, ring, prof, ch, _res, port, ip, ssid,
     pw, token) = struct.unpack(REC_STATE_FMT, data)
    return RecState(v, st, reason, flags, take, el, mx, kib, dropped, ring, prof, ch, port,
                    ".".join(str(x) for x in ip), _cstr(ssid), _cstr(pw), token)


def encode_rec_state(r: RecState) -> bytes:
    ip = bytes(int(x) for x in r.ip.split(".")) if r.ip else bytes(4)
    return struct.pack(REC_STATE_FMT, r.version, r.state, r.reason, r.flags, r.take_id,
                       r.elapsed_s, r.max_s, r.sent_kib, r.dropped, r.ring_pct, r.profile,
                       r.channel, 0, r.port, ip, r.ssid.encode(), r.password.encode(), r.token)


def format_rec_state(r: RecState) -> str:
    prof = _name(REC_PROFILES, r.profile)
    return (f"rec: {r.state_name} (last end: {r.reason_name}) take {r.take_id} "
            f"{r.elapsed_s}/{r.max_s} s {prof}, sent {r.sent_kib} KiB, dropped {r.dropped}, "
            f"ring {r.ring_pct}%, {r.ip}:{r.port} ssid {r.ssid!r} ch {r.channel}  "
            f"[{' '.join(r.flag_names)}]")


def encode_ota_start(size: int, sha256: bytes) -> bytes:
    if len(sha256) != 32:
        raise ValueError("sha256 must be 32 bytes")
    return struct.pack("<BI", OTA_OP_START, size) + sha256


def encode_ota_data(seq: int, payload: bytes) -> bytes:
    return struct.pack("<H", seq & 0xFFFF) + payload


def decode_ota_msg(data: bytes) -> dict:
    """Decodes an ota_ctrl indication into a dict with a 'type' key."""
    if not data:
        raise ValueError("empty OTA message")
    t = data[0]
    if t == OTA_MSG_REPLY and len(data) >= 3:
        msg = {"type": "reply", "op": data[1], "status": data[2]}
        if data[1] == OTA_OP_START and data[2] == OTA_STATUS_OK:
            if len(data) != 7:
                raise ValueError(f"START reply is {len(data)} bytes, expected 7")
            msg["max_chunk"], msg["ack_every"] = struct.unpack_from("<HH", data, 3)
        elif len(data) != 3:
            raise ValueError(f"reply is {len(data)} bytes, expected 3")
        return msg
    if t == OTA_MSG_ACK and len(data) == 7:
        next_seq, received = struct.unpack_from("<HI", data, 1)
        return {"type": "ack", "next_seq": next_seq, "received": received}
    if t == OTA_MSG_NACK and len(data) == 4:
        expected, status = struct.unpack_from("<HB", data, 1)
        return {"type": "nack", "expected_seq": expected, "status": status}
    raise ValueError(f"malformed OTA message {data.hex()}")


def status_name(code: int) -> str:
    return _name(OTA_STATUS, code, f"status {code}")


def parse_config_value(text: str):
    """key=value parsing for set-config: bools, ints, floats, else strings."""
    low = text.lower()
    if low in ("true", "on", "yes"):
        return True
    if low in ("false", "off", "no"):
        return False
    try:
        return int(text)
    except ValueError:
        pass
    try:
        return float(text)
    except ValueError:
        return text


def describe_ble_error(e: Exception) -> str:
    text = str(e)
    for m in re.finditer(r"0x([0-9a-fA-F]{2})\b", text):
        code = int(m.group(1), 16)
        if code in ATT_ERRORS:
            return f"{text} -> {ATT_ERRORS[code]}"
    return text


def format_angle(a: Angle) -> str:
    rel = "rel" if a.zero_set else "abs"   # angles relative to the zero, or absolute
    return (f"#{a.seq:3d} {a.mode_name:5s} {rel} elev {a.elevation:7.2f} yaw {a.yaw:7.2f} "
            f"bubble {a.bubble_x:6.2f},{a.bubble_y:6.2f} dev {a.deviation:6.2f}  "
            f"[{' '.join(a.flag_names)}]")


def cal_result_name(code: int) -> str:
    if code == CAL_RESULT_NONE:
        return "none"
    if code == CAL_RESULT_NOT_STORED:
        return "not stored"
    if code == CAL_RESULT_OK_MOUNT_DROPPED:
        return "ok, mount cal reset"
    return str(code)


def format_status(s: Status) -> str:
    power = _name(POWER_STATES, s.power_state)
    kind = _name(CAL_KINDS, s.cal_kind)
    return (f"status: battery {s.battery_pct}% {s.battery_mv} mV, power {power}, "
            f"cal {kind} samples {s.cal_samples} coverage 0x{s.cal_coverage:02x} "
            f"last {cal_result_name(s.cal_last_result)}  [{' '.join(s.flag_names)}]")


# ---- self-check ----------------------------------------------------------------

def _header_path(*parts) -> str:
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(here, "..", "..", "components", *parts)


def _header_defines(path: str) -> dict:
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = re.match(r"\s*#define\s+(\w+)\s+\(?(0x[0-9A-Fa-f]+|\d+)(?:u\s*<<\s*(\d+))?\)?",
                         line)
            if m:
                value = int(m.group(2), 0)
                if m.group(3):
                    value <<= int(m.group(3))
                out[m.group(1)] = value
    return out


def self_check(verbose: bool = True) -> bool:
    ok = True

    def check(cond: bool, what: str):
        nonlocal ok
        if verbose or not cond:
            print(("ok   " if cond else "FAIL ") + what)
        ok = ok and cond

    check(ANGLE_SIZE == 14, f"db_angle_packet_t is 14 bytes (got {ANGLE_SIZE})")
    check(STATUS_SIZE == 10, f"db_status_packet_t is 10 bytes (got {STATUS_SIZE})")
    check(REC_STATE_SIZE == 86, f"db_rec_state_packet_t is 86 bytes (got {REC_STATE_SIZE})")

    a = Angle(1, 2, ANGLE_FLAGS["imu_ok"] | ANGLE_FLAGS["near"], 255,
              -12.34, 179.99, 0.01, -0.01, 327.67)
    raw = encode_angle(a)
    check(len(raw) == 14, "encode_angle gives 14 bytes")
    check(raw[4:6] == bytes([0x2E, 0xFB]), "elevation -1234 little-endian")
    b = decode_angle(raw)
    check(b == a, "angle round trip")
    z = decode_angle(encode_angle(Angle(1, 1, ANGLE_FLAGS["zero_set"], 0, 0, 0, 0, 0, 0)))
    check(z.zero_set and not z.yaw_valid, "zero_set decoded without yaw_valid")
    check(" rel " in format_angle(z) and " abs " in format_angle(a), "format shows rel/abs")
    check(decode_angle(encode_angle(Angle(1, 0, 0, 0, 999.0, -999.0, 0, 0, 0))).elevation == 327.67,
          "angle encoder saturates like the firmware")

    s = Status(1, 87, 4012, STATUS_FLAGS["charging"] | STATUS_FLAGS["vbus"], 1, 2, 255, 0x3F, -1)
    raw = encode_status(s)
    check(len(raw) == 10, "encode_status gives 10 bytes")
    check(raw[2:4] == (4012).to_bytes(2, "little"), "battery_mv little-endian at offset 2")
    check(decode_status(raw) == s, "status round trip")
    check(raw[9] == 0xFF, "cal_last_result is signed (-1 = 0xff)")
    ns = decode_status(encode_status(Status(1, 50, 3900, 0, 0, 0, 0, 0, CAL_RESULT_NOT_STORED)))
    check(ns.cal_last_result == -2 and "last not stored" in format_status(ns),
          "cal_last_result -2 = not stored")
    ns = decode_status(encode_status(Status(1, 50, 3900, 0, 0, 0, 0, 0,
                                            CAL_RESULT_OK_MOUNT_DROPPED)))
    check(ns.cal_last_result == -3 and "mount cal reset" in format_status(ns),
          "cal_last_result -3 = ok, mount cal reset")

    for bad, fn in ((b"\x00" * 13, decode_angle), (b"\x00" * 11, decode_status)):
        try:
            fn(bad)
            check(False, f"{fn.__name__} rejects {len(bad)} bytes")
        except ValueError:
            check(True, f"{fn.__name__} rejects {len(bad)} bytes")

    check(encode_cmd("zero") == b"\x01", "cmd zero")
    check(encode_cmd("set-mode", "angle") == b"\x03\x02", "cmd set-mode angle")
    check(encode_cmd("factory-reset") == b"\x23\xa5", "cmd factory-reset carries 0xA5")
    try:
        encode_cmd("set-mode", "3")
        check(False, "set-mode 3 rejected")
    except ValueError:
        check(True, "set-mode 3 rejected")

    check(encode_rec("open") == b"\x01", "rec open")
    ts = encode_rec("take-start", profile=2, mic_mask=3, gain_db=6, max_s=1200, take_id=0x12345678)
    check(ts == bytes([3, 2, 3, 6, 0xB0, 0x04, 0x78, 0x56, 0x34, 0x12]) and
          len(ts) == REC_TAKE_START_LEN, "rec take-start is 10 bytes, LE max_s and take_id")
    check(encode_rec("mark", code=0xBEEF, text="hole 1") == b"\x05\xef\xbehole 1", "rec mark")
    for bad in ({"profile": 4}, {"mic_mask": 4}, {"gain_db": 38}, {"max_s": 1201}):
        try:
            encode_rec("take-start", **bad)
            check(False, f"take-start {bad} rejected")
        except ValueError:
            check(True, f"take-start {bad} rejected")
    rs = RecState(1, 4, 2, REC_FLAGS["client"] | REC_FLAGS["audio"], 0xA1B2C3D4, 83, 300, 5120, 7,
                  12, 1, 6, 47100, "192.168.4.1", "DB-1a2b-k7q2", "abcdefghijklmnopqrst",
                  bytes(range(0xF0, 0x100)))
    raw = encode_rec_state(rs)
    check(len(raw) == 86 and raw[24:30] == bytes([0xFC, 0xB7, 192, 168, 4, 1]),
          "rec_state port and ip at offset 24")
    check(raw[30:46] == b"DB-1a2b-k7q2".ljust(16, b"\0") and raw[70:] == rs.token,
          "rec_state ssid at 30, token at 70")
    check(decode_rec_state(raw) == rs, "rec_state round trip")
    check("recording" in format_rec_state(rs) and "max_s" in format_rec_state(rs),
          "rec_state formats state and reason")

    sha = hashlib.sha256(b"abc").digest()
    start = encode_ota_start(0x01020304, sha)
    check(len(start) == 37 and start[:5] == b"\x01\x04\x03\x02\x01", "OTA START is 37 bytes, LE size")
    check(encode_ota_data(0x10002, b"xy") == b"\x02\x00xy", "OTA data seq wraps at 65536")
    check(decode_ota_msg(bytes([0x80, 1, 0, 242, 0, 16, 0])) ==
          {"type": "reply", "op": 1, "status": 0, "max_chunk": 242, "ack_every": 16},
          "OTA START reply")
    check(decode_ota_msg(bytes([0x80, 2, 6])) == {"type": "reply", "op": 2, "status": 6},
          "OTA FINISH reply")
    check(decode_ota_msg(bytes([0x81, 0x34, 0x12, 0x78, 0x56, 0x34, 0x12])) ==
          {"type": "ack", "next_seq": 0x1234, "received": 0x12345678}, "OTA ACK")
    check(decode_ota_msg(bytes([0x82, 5, 0, 7])) ==
          {"type": "nack", "expected_seq": 5, "status": 7}, "OTA NACK")
    check(db_uuid(0x0101) == "d71b0101-8a3e-4f6c-b5d2-1c9e0a7f4b63", "OTA ctrl UUID")

    # Drift check against the C contracts when run from the repo.
    proto = _header_path("ble", "include", "ble_protocol.h")
    if os.path.exists(proto):
        d = _header_defines(proto)
        for name, op in CMDS.items():
            cname = "DB_CMD_" + name.upper().replace("-", "_")
            check(d.get(cname) == op, f"{cname} = 0x{op:02x} matches ble_protocol.h")
        for name, bit in ANGLE_FLAGS.items():
            cname = "DB_ANGLE_FLAG_" + name.upper()
            check(d.get(cname) == bit, f"{cname} matches")
        for name, bit in STATUS_FLAGS.items():
            cname = "DB_STATUS_FLAG_" + name.upper()
            check(d.get(cname) == bit, f"{cname} matches")
        for cname, x in UUID_IDS.items():
            check(d.get(cname) == x, f"{cname} matches")
        check(d.get("DB_CMD_FACTORY_RESET_CONFIRM") == FACTORY_RESET_CONFIRM,
              "factory reset confirmation byte matches")
        for code in ATT_ERRORS:
            if code >= 0x80:
                check(code in d.values(), f"ATT error 0x{code:02x} defined in ble_protocol.h")
        with open(proto, encoding="utf-8") as f:
            text = f.read()
        check("} db_angle_packet_t;          // 14 bytes" in text, "header says angle = 14 bytes")
        check("} db_status_packet_t;         // 10 bytes" in text, "header says status = 10 bytes")
        check("} db_rec_state_packet_t;      // 86 bytes" in text, "header says rec_state = 86 bytes")
        for name, op in REC_OPS.items():
            cname = "DB_REC_OP_" + name.upper().replace("-", "_")
            check(d.get(cname) == op, f"{cname} = 0x{op:02x} matches")
        for i, name in enumerate(REC_PROFILES):
            check(d.get("DB_REC_PROFILE_" + name) == i, f"DB_REC_PROFILE_{name} = {i}")
        for i, name in enumerate(REC_STATES):
            check(d.get("DB_REC_STATE_" + name.upper()) == i, f"DB_REC_STATE_{name.upper()} = {i}")
        for i, name in enumerate(REC_REASONS):
            check(d.get("DB_REC_REASON_" + name.upper()) == i, f"DB_REC_REASON_{name.upper()} = {i}")
        for name, bit in REC_FLAGS.items():
            check(d.get("DB_REC_FLAG_" + name.upper()) == bit, f"DB_REC_FLAG_{name.upper()} matches")
        for name, bit in REC_MICS.items():
            check(d.get("DB_REC_" + name.upper()) == bit, f"DB_REC_{name.upper()} matches")
        for cname, v in (("DB_REC_TAKE_START_LEN", REC_TAKE_START_LEN),
                         ("DB_REC_MARK_TEXT_MAX", REC_MARK_TEXT_MAX),
                         ("DB_REC_GAIN_MAX_DB", REC_GAIN_MAX_DB),
                         ("DB_REC_MAX_S_DEFAULT", REC_MAX_S_DEFAULT),
                         ("DB_REC_MAX_S_LIMIT", REC_MAX_S_LIMIT)):
            check(d.get(cname) == v, f"{cname} = {v}")
        att = {k: v for k, v in d.items() if k.startswith("DB_ATT_ERR_")}
        check(sorted(att.values()) == sorted(c for c in ATT_ERRORS if c >= 0x80),
              "every DB_ATT_ERR_* has a description")
    else:
        print("skip ble_protocol.h drift check (not run from the repo)")

    ota_h = _header_path("ota", "include", "ota_proto.h")
    if os.path.exists(ota_h):
        d = _header_defines(ota_h)
        for cname, v in (("OTA_OP_START", OTA_OP_START), ("OTA_OP_FINISH", OTA_OP_FINISH),
                         ("OTA_OP_ABORT", OTA_OP_ABORT), ("OTA_MSG_REPLY", OTA_MSG_REPLY),
                         ("OTA_MSG_ACK", OTA_MSG_ACK), ("OTA_MSG_NACK", OTA_MSG_NACK)):
            check(d.get(cname) == v, f"{cname} matches ota_proto.h")
        with open(ota_h, encoding="utf-8") as f:
            text = f.read()
        for i, name in enumerate(OTA_STATUS):
            check(f"OTA_STATUS_{name} = {i}," in text, f"OTA_STATUS_{name} = {i}")

    print("self-check " + ("passed" if ok else "FAILED"))
    return ok


# ---- device commands ---------------------------------------------------------------

def _need_bleak():
    if BleakClient is None:
        sys.exit("bleak is not installed: pip install -r requirements.txt")


def is_drillbalancer(dev, adv) -> bool:
    """Scan filter: the advertised name prefix or the main service UUID."""
    name = adv.local_name or dev.name or ""
    return name.startswith(NAME_PREFIX) or SVC_MAIN in (u.lower() for u in adv.service_uuids)


async def find_device(args):
    _need_bleak()
    if args.address:
        return args.address
    print(f"scanning for {args.name or NAME_PREFIX + '*'} ...", file=sys.stderr)
    want = args.name

    def match(dev, adv):
        if want:
            return (adv.local_name or dev.name or "") == want
        return is_drillbalancer(dev, adv)

    dev = await BleakScanner.find_device_by_filter(match, timeout=args.timeout)
    if dev is None:
        sys.exit("no DrillBalancer found")
    print(f"found {dev.name} {dev.address}", file=sys.stderr)
    return dev


async def cmd_scan(args):
    _need_bleak()
    found = await BleakScanner.discover(timeout=args.timeout, return_adv=True)
    n = 0
    for dev, adv in found.values():
        if is_drillbalancer(dev, adv):
            name = adv.local_name or dev.name or ""
            print(f"{dev.address}  {name:22s} RSSI {adv.rssi}")
            n += 1
    if n == 0:
        print("no DrillBalancer found")


async def cmd_pair(args):
    target = await find_device(args)
    async with BleakClient(target, timeout=args.timeout) as client:
        print("pairing: enter the 6-digit passkey shown on the device in the "
              "BlueZ agent prompt", file=sys.stderr)
        await client.pair()
        print("paired")


async def cmd_stream(args):
    target = await find_device(args)
    async with BleakClient(target, timeout=args.timeout) as client:
        def on_angle(_, data: bytearray):
            try:
                print(format_angle(decode_angle(bytes(data))))
            except ValueError as e:
                print(f"bad angle packet: {e}")

        def on_status(_, data: bytearray):
            try:
                print(format_status(decode_status(bytes(data))))
            except ValueError as e:
                print(f"bad status packet: {e}")

        print(format_status(decode_status(bytes(await client.read_gatt_char(CHR_STATUS)))))
        await client.start_notify(CHR_STATUS, on_status)
        if not args.status_only:
            await client.start_notify(CHR_ANGLE, on_angle)
        try:
            if args.duration:
                await asyncio.sleep(args.duration)
            else:
                while True:
                    await asyncio.sleep(3600)
        finally:
            if client.is_connected:
                await client.stop_notify(CHR_STATUS)
                if not args.status_only:
                    await client.stop_notify(CHR_ANGLE)


async def cmd_get_config(args):
    target = await find_device(args)
    async with BleakClient(target, timeout=args.timeout) as client:
        raw = bytes(await client.read_gatt_char(CHR_CONFIG))   # long read by BlueZ
        print(json.dumps(json.loads(raw.decode()), indent=2))


async def cmd_set_config(args):
    update = {}
    for kv in args.pairs:
        if "=" not in kv:
            sys.exit(f"expected key=value, got {kv!r}")
        k, v = kv.split("=", 1)
        update[k] = parse_config_value(v)
    payload = json.dumps(update, separators=(",", ":")).encode()
    target = await find_device(args)
    async with BleakClient(target, timeout=args.timeout) as client:
        try:
            await client.write_gatt_char(CHR_CONFIG, payload, response=True)
        except BleakError as e:
            sys.exit("config write failed: " + describe_ble_error(e))
        raw = bytes(await client.read_gatt_char(CHR_CONFIG))
        print(json.dumps(json.loads(raw.decode()), indent=2))


async def cmd_cmd(args):
    try:
        payload = encode_cmd(args.name, args.arg)
    except ValueError as e:
        sys.exit(str(e))
    if args.name == "factory-reset" and not args.yes:
        sys.exit("factory-reset erases settings, calibration and all BLE bonds "
                 "(pair again afterwards): add --yes")
    target = await find_device(args)
    async with BleakClient(target, timeout=args.timeout) as client:
        try:
            await client.write_gatt_char(CHR_COMMAND, payload, response=True)
        except BleakError as e:
            sys.exit("command failed: " + describe_ble_error(e))
        print(f"{args.name}: ok")


class OtaError(Exception):
    pass


async def _ota_wait(queue: asyncio.Queue, timeout: float, op: Optional[int] = None) -> dict:
    """Next OTA message; with op, the next reply to that request (ACK/NACK skipped)."""
    deadline = time.monotonic() + timeout
    while True:
        left = deadline - time.monotonic()
        if left <= 0:
            raise asyncio.TimeoutError
        msg = await asyncio.wait_for(queue.get(), left)
        if op is None or (msg["type"] == "reply" and msg["op"] == op):
            return msg


def _progress(done: int, total: int, t0: float):
    width = 32
    frac = done / total if total else 1.0
    rate = done / max(time.monotonic() - t0, 1e-3) / 1024
    bar = "#" * int(frac * width)
    print(f"\r[{bar:<{width}}] {frac * 100:5.1f}%  {done}/{total} B  {rate:5.1f} KiB/s",
          end="", file=sys.stderr, flush=True)


async def ota_transfer(client, image: bytes, stall_s: float = 3.0, max_stalls: int = 5) -> None:
    """Sends image over ota_ctrl / ota_data. Raises OtaError on failure."""
    queue: asyncio.Queue = asyncio.Queue()

    def on_ind(_, data: bytearray):
        try:
            queue.put_nowait(decode_ota_msg(bytes(data)))
        except ValueError as e:
            print(f"\nignoring {e}", file=sys.stderr)

    await client.start_notify(CHR_OTA_CTRL, on_ind)
    size = len(image)
    sha = hashlib.sha256(image).digest()
    await client.write_gatt_char(CHR_OTA_CTRL, encode_ota_start(size, sha), response=True)
    rep = await _ota_wait(queue, 10.0, OTA_OP_START)
    if rep["status"] != OTA_STATUS_OK:
        raise OtaError(f"START rejected: {status_name(rep['status'])}")
    chunk = rep["max_chunk"]
    # Never more than the link carries: an older firmware could announce the
    # previous connection's chunk size on a link still at the default MTU.
    mtu = getattr(client, "mtu_size", None)
    if mtu and mtu - OTA_CHUNK_OVERHEAD < chunk:
        chunk = max(1, mtu - OTA_CHUNK_OVERHEAD)
    window = 2 * max(rep["ack_every"], 1)
    nchunks = (size + chunk - 1) // chunk
    print(f"OTA: {size} bytes, {nchunks} chunks of {chunk}, window {window}, "
          f"sha256 {sha.hex()[:16]}...", file=sys.stderr)

    acked = 0      # chunks the device has accepted
    nxt = 0        # next chunk to send
    stalls = 0
    bad = {"idx": -1, "count": 0}   # consecutive BAD_REQUEST NACKs for one chunk
    t0 = time.monotonic()

    def handle(msg: dict):
        nonlocal acked, nxt
        if msg["type"] == "ack":
            done = nchunks if msg["received"] >= size else msg["received"] // chunk
            acked = max(acked, done)
            nxt = max(nxt, acked)
        elif msg["type"] == "nack":
            st = msg["status"]
            if st not in (OTA_STATUS_SEQ_ERROR, OTA_STATUS_BAD_REQUEST):
                raise OtaError(f"device aborted: {status_name(st)}")
            # expected_seq is a 16-bit chunk index at or after the last ACK.
            idx = acked + ((msg["expected_seq"] - acked) & 0xFFFF)
            if st == OTA_STATUS_BAD_REQUEST:
                bad["count"] = bad["count"] + 1 if bad["idx"] == idx else 1
                bad["idx"] = idx
                if bad["count"] >= OTA_MAX_BAD_REQUESTS:
                    raise OtaError(f"device rejects chunk {idx} ({chunk} bytes) as malformed")
            acked = max(acked, idx)
            nxt = idx
        elif msg["type"] == "reply":
            raise OtaError(f"unexpected reply during transfer: {msg}")

    while acked < nchunks:
        while nxt < nchunks and nxt - acked < window:
            payload = image[nxt * chunk:(nxt + 1) * chunk]
            await client.write_gatt_char(CHR_OTA_DATA, encode_ota_data(nxt, payload),
                                         response=False)
            nxt += 1
            while not queue.empty():   # react to a NACK without waiting
                handle(queue.get_nowait())
        if acked >= nchunks:
            break   # the final ACK was already drained above
        try:
            handle(await _ota_wait(queue, stall_s))
            stalls = 0
        except asyncio.TimeoutError:
            # Lost writes at the end produce no NACK: resend from the last ACK.
            stalls += 1
            if stalls > max_stalls:
                raise OtaError("device stopped acknowledging")
            print(f"\nstall, resending from chunk {acked}", file=sys.stderr)
            nxt = acked
        _progress(min(acked * chunk, size), size, t0)
    print(file=sys.stderr)

    await client.write_gatt_char(CHR_OTA_CTRL, bytes([OTA_OP_FINISH]), response=True)
    rep = await _ota_wait(queue, 30.0, OTA_OP_FINISH)   # image verification takes a while
    if rep["status"] != OTA_STATUS_OK:
        raise OtaError(f"FINISH failed: {status_name(rep['status'])}")
    print(f"OTA ok in {time.monotonic() - t0:.1f} s; the device reboots into the new image",
          file=sys.stderr)


async def cmd_ota(args):
    with open(args.file, "rb") as f:
        image = f.read()
    if not image:
        sys.exit("empty image")
    if image[0] != 0xE9:
        sys.exit("not an ESP app image (first byte must be 0xE9); use build/drillbalancer.bin")
    target = await find_device(args)
    async with BleakClient(target, timeout=args.timeout) as client:
        try:
            await ota_transfer(client, image)
        except (OtaError, asyncio.TimeoutError, BleakError, KeyboardInterrupt) as e:
            what = "timeout waiting for the device" if isinstance(e, asyncio.TimeoutError) else e
            print(f"\nOTA failed: {describe_ble_error(what) if isinstance(e, BleakError) else what}",
                  file=sys.stderr)
            if client.is_connected:
                try:
                    await client.write_gatt_char(CHR_OTA_CTRL, bytes([OTA_OP_ABORT]),
                                                 response=True)
                except BleakError:
                    pass
            sys.exit(1)


# ---- CLI ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="DrillBalancer BLE test client")
    p.add_argument("--dry-run", action="store_true",
                   help="check the packet encoders/decoders and exit (no Bluetooth)")
    p.add_argument("-a", "--address", help="device address (skips the scan)")
    p.add_argument("-n", "--name", help="exact device name, e.g. DrillBalancer-1A2B")
    p.add_argument("-t", "--timeout", type=float, default=10.0, help="scan/connect timeout, s")
    sub = p.add_subparsers(dest="command")

    sub.add_parser("scan", help="list DrillBalancers in range")
    sub.add_parser("pair", help="pair and bond (enter the passkey shown on the device)")
    s = sub.add_parser("stream", help="print angle and status notifications")
    s.add_argument("-d", "--duration", type=float, help="stop after this many seconds")
    s.add_argument("--status-only", action="store_true")
    sub.add_parser("get-config", help="read the settings JSON")
    s = sub.add_parser("set-config", help="write settings, e.g. tol=0.2 mode=floor ble=true")
    s.add_argument("pairs", nargs="+", metavar="key=value")
    s = sub.add_parser("cmd", help="send a command: " + ", ".join(CMDS))
    s.add_argument("name", choices=list(CMDS))
    s.add_argument("arg", nargs="?", help="set-mode: wall|floor|angle")
    s.add_argument("--yes", action="store_true", help="confirm factory-reset")
    s = sub.add_parser("ota", help="firmware update over BLE")
    s.add_argument("file", help="app image, e.g. build/drillbalancer.bin")
    return p


COMMANDS = {
    "scan": cmd_scan,
    "pair": cmd_pair,
    "stream": cmd_stream,
    "get-config": cmd_get_config,
    "set-config": cmd_set_config,
    "cmd": cmd_cmd,
    "ota": cmd_ota,
}


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    if args.dry_run:
        return 0 if self_check() else 1
    if not args.command:
        build_parser().print_help()
        return 2
    try:
        asyncio.run(COMMANDS[args.command](args))
    except KeyboardInterrupt:
        pass
    except BleakError as e:
        print("error: " + describe_ble_error(e), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
