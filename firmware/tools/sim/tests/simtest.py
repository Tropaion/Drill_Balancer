"""Shared helpers for the simulator tests (no Bluetooth).

Packets are checked with tools/ble/db_client.py's decoders, which were
written against the C structs independently of the simulator.
"""

import asyncio
import contextlib
import hashlib
import io
import os
import struct
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SIM_DIR = HERE.parent
sys.path.insert(0, str(SIM_DIR))

from dbsim import DbSimDevice, DrillPhysics  # noqa: E402
from dbsim import core as C  # noqa: E402
from dbsim.wire import dc  # noqa: E402

FIRMWARE_IMAGE = SIM_DIR.parent.parent / "build" / "drillbalancer.bin"


class DeviceTest(unittest.TestCase):
    """A fresh device with its own state directory per test."""

    seed = 1

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.state_dir = Path(self._tmp.name)
        self.events = []
        self.dev = self.new_device()

    def tearDown(self):
        self._tmp.cleanup()

    def new_device(self, **kw):
        dev = DbSimDevice(self.state_dir, seed=self.seed, boot=False, **kw)
        dev.subscribe(lambda name, data: self.events.append((dev.now_ms, name, data)))
        dev.power_on()
        dev.advance(500)
        return dev

    # ---- helpers ----
    def settle(self, ms=2500):
        self.dev.advance(ms)

    def settle_imu(self):
        self.dev.settle_imu()           # DbSimDevice.IMU_TRUSTED_MS

    def snap(self):
        return self.dev.snapshot()

    def angle(self):
        """The angle packet a notification would carry now, decoded by db_client."""
        return dc.decode_angle(self.dev.core.angle_packet(0, self.dev.core_now))

    def status(self):
        return dc.decode_status(self.dev.read_char("status"))

    def cmd(self, name, arg=None, settle_ms=20):
        att = self.dev.write_char("command", dc.encode_cmd(name, arg))
        self.dev.advance(settle_ms)
        return att

    def config(self):
        import json
        return json.loads(self.dev.read_char("config"))

    def write_config(self, **kw):
        import json
        att = self.dev.write_char("config", json.dumps(kw).encode())
        self.dev.advance(20)
        return att

    def connect(self, trusted=True):
        self.assertTrue(self.dev.connect())
        if trusted:
            self.dev.encryption_changed(True, True)

    def names(self, name):
        return [(t, d) for t, n, d in self.events if n == name]

    def assertClose(self, a, b, tol, msg=None):
        self.assertLessEqual(abs(a - b), tol, msg or f"{a} != {b} within {tol}")


# ---- OTA over the simulated GATT, through db_client.ota_transfer ---------------------

class SimClient:
    """The BleakClient surface ota_transfer() uses, wired to a DbSimDevice."""

    CHARS = {dc.CHR_OTA_CTRL: "ota_ctrl", dc.CHR_OTA_DATA: "ota_data",
             dc.CHR_CONFIG: "config", dc.CHR_COMMAND: "command"}

    def __init__(self, dev, mtu=247):
        self.dev = dev
        self.mtu_size = mtu
        self.indications = []
        self._cb = None
        dev.subscribe(self._on_event)
        assert dev.connect()
        dev.encryption_changed(True, True)
        dev.set_mtu(mtu)

    def _on_event(self, name, data):
        if name != "indicate":
            return
        self.indications.append(data["value"])
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            self.dev.indication_confirmed()
            return
        if self._cb:
            loop.call_soon(self._cb, None, bytearray(data["value"]))
        loop.call_soon(self.dev.indication_confirmed)

    async def start_notify(self, uuid, cb):
        assert uuid == dc.CHR_OTA_CTRL
        self._cb = cb
        self.dev.subscribe_char("ota_ctrl", True)

    async def write_gatt_char(self, uuid, data, response=False):
        att = self.dev.write_char(self.CHARS[uuid], bytes(data))
        if att:
            raise dc.BleakError(f"ATT error 0x{att:02x}")
        await asyncio.sleep(0)


def run_ota(client, image, **kw):
    """db_client.ota_transfer() with its progress output swallowed."""
    with contextlib.redirect_stderr(io.StringIO()):
        asyncio.run(dc.ota_transfer(client, image, **kw))


def make_image(version="9.9.9-sim", project="drillbalancer", payload=4096, seed=0):
    """A minimal valid ESP32-S3 app image: one segment with the app descriptor,
    XOR checksum and appended SHA-256 (what esp_ota_end() verifies)."""
    desc = bytearray(256)
    struct.pack_into("<I", desc, 0, 0xABCD5432)
    desc[16:16 + len(version)] = version.encode()
    desc[48:48 + len(project)] = project.encode()
    data = bytes(desc) + hashlib.sha256(str(seed).encode()).digest() * (payload // 32)
    header = bytearray(24)
    header[0] = 0xE9
    header[1] = 1                               # one segment
    struct.pack_into("<H", header, 12, 9)       # chip id: ESP32-S3
    header[23] = 1                              # hash appended
    img = bytearray(header) + struct.pack("<II", 0x3C000020, len(data)) + data
    return finish_image(img)


def finish_image(img: bytearray) -> bytes:
    """Recomputes the checksum and SHA-256 of an image without them."""
    csum, pos = 0xEF, 24
    for _ in range(img[1]):
        length = struct.unpack_from("<I", img, pos + 4)[0]
        pos += 8
        for b in img[pos:pos + length]:
            csum ^= b
        pos += length
    img = img[:pos] + bytes(15 - pos % 16) + bytes([csum])
    return bytes(img) + hashlib.sha256(img).digest()


def set_image_version(image: bytes, version: str) -> bytes:
    """A copy of a real app image with another app_desc version, re-checksummed."""
    img = bytearray(image)
    assert img[23] == 1, "expects an image with an appended hash"
    img[48:80] = version.encode().ljust(32, b"\0")
    # Strip checksum + hash: walk the segments to find the end of the data.
    pos = 24
    for _ in range(img[1]):
        pos += 8 + struct.unpack_from("<I", img, pos + 4)[0]
    return finish_image(img[:pos])


__all__ = ["C", "dc", "DbSimDevice", "DrillPhysics", "DeviceTest", "SimClient", "run_ota",
           "make_image", "set_image_version", "FIRMWARE_IMAGE", "os"]
