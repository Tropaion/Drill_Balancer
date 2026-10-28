"""BLE transport (dbsim/ble.py) without Bluetooth.

The GATT objects and the link logic are driven directly with BlueZ-style
option dicts and Device1 property changes; BlueZ method calls are recorded.
The last test puts the objects on a private D-Bus daemon to check the
signatures and error names on the wire.
"""

import asyncio
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
import unittest

from simtest import FIRMWARE_IMAGE, C, dc

from dbsim import DbSimDevice
from dbsim import ble as B
from dbus_fast import DBusError, Message, MessageType, Variant
from dbus_fast.aio import MessageBus

PHONE = "/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF"
PHONE_ADDR = "AA:BB:CC:DD:EE:FF"
OTHER = "/org/bluez/hci0/dev_11_22_33_44_55_66"


def bluez_ecode(name: str, msg: str, perm_err: int) -> int:
    """gatt-database.c dbus_error_to_att_ecode (BlueZ 5.87)."""
    if name == "org.bluez.Error.Failed":
        try:
            code = int(msg, 0)
        except ValueError:
            return 0x80
        return code if 0x80 <= code <= 0x9F else 0x0E
    return {"org.bluez.Error.NotSupported": 0x06, "org.bluez.Error.NotAuthorized": 0x08,
            "org.bluez.Error.InvalidValueLength": 0x0D, "org.bluez.Error.InvalidOffset": 0x07,
            "org.bluez.Error.InProgress": 0xFE, "org.bluez.Error.ImproperlyConfigured": 0xFD,
            "org.bluez.Error.NotPermitted": perm_err}.get(name, 0x0E)


# BlueZ parse_chrc_flags: flag -> (property bits, write needs authentication,
# read needs authentication)
BLUEZ_FLAGS = {"read": (0x02, False, False), "write-without-response": (0x04, False, False),
               "write": (0x08, False, False), "notify": (0x10, False, False),
               "indicate": (0x20, False, False),
               "encrypt-authenticated-write": (0x08, True, False),
               "encrypt-authenticated-read": (0x02, False, True)}
NIMBLE_FLAGS = {"BLE_GATT_CHR_F_READ": 0x02, "BLE_GATT_CHR_F_WRITE_NO_RSP": 0x04,
                "BLE_GATT_CHR_F_WRITE": 0x08, "BLE_GATT_CHR_F_NOTIFY": 0x10,
                "BLE_GATT_CHR_F_INDICATE": 0x20}
def firmware_gatt_table():
    """[(service UUID, [(char UUID, properties, secure write, secure read)])] from
    ble_service.c's s_svcs table."""
    src = (C.COMPONENTS_DIR / "ble/src/ble_service.c").read_text()
    header_path = dc._header_path("ble", "include", "ble_protocol.h")
    ids = dc._header_defines(header_path)

    def uuid(expr):
        m = re.search(r"BLE_UUID16_DECLARE\((0x[0-9A-Fa-f]+)\)", expr)
        if m:
            return B.sig_uuid(int(m[1], 16))
        name = re.search(r"UUID_(SVC|CHR)_(\w+)\.u", expr)
        return B.db_uuid(ids[f"DB_{name[1]}_{name[2]}"])

    table = src[src.index("s_svcs[] = {"):]
    table = table[:table.index("\n};")]
    out = []
    for svc in table.split("BLE_GATT_SVC_TYPE_PRIMARY")[1:]:
        suuid = uuid(re.search(r"\.uuid = ([^,]+),", svc)[1])
        chars = []
        for cuuid, flags in re.findall(r"\{\.uuid = ([^,]+),.*?\.flags = ([^,}]+)", svc, re.S):
            names = [f.strip() for f in flags.split("|")]
            props = sum(NIMBLE_FLAGS.get(f, 0) for f in names)
            chars.append((uuid(cuuid), props, "SEC_WRITE" in names,
                          "SEC_READ" in names or "BLE_GATT_CHR_F_READ_AUTHEN" in names))
        out.append((suuid, chars))
    return out


class TableTest(unittest.TestCase):
    def test_matches_firmware(self):
        ours = []
        for suuid, chars in B.SERVICES:
            rows = []
            for name, cuuid, flags in chars:
                props = 0
                secure = name in B.PROTECTED
                secure_read = name in B.PROTECTED_READ
                for f in flags:
                    bits, auth, read_auth = BLUEZ_FLAGS[f]
                    props |= bits
                    secure |= auth
                    secure_read |= read_auth
                rows.append((cuuid, props, secure, secure_read))
            ours.append((suuid, rows))
        self.assertEqual(ours, firmware_gatt_table())

    def test_error_codes_reach_the_phone_unchanged(self):
        for code in [0x07, 0x0D, 0x0E] + list(range(0x80, 0x8B)):
            e = B.att_error(code)
            self.assertEqual(bluez_ecode(e.type, e.text, 0x03), code, hex(code))


class BleTest(unittest.IsolatedAsyncioTestCase):
    """A device behind a BluezPeripheral without a bus; BlueZ calls are recorded."""

    async def asyncSetUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dev = DbSimDevice(self._tmp.name, seed=1, boot=False)
        self.dev.power_on()
        self.dev.advance(500)
        self.p = B.BluezPeripheral(self.dev, hci=False)
        self.calls = []

        async def call(path, interface, member, signature="", body=(), destination=B.BLUEZ):
            self.calls.append((path, member, list(body)))
            return []
        self.p.call = call
        self.p._started = True
        self.dev.subscribe(self.p._on_device_event)
        self.sent = []
        for c in self.p.chars.values():
            c.send = (lambda c: lambda v: self.sent.append((c.key, bytes(v))))(c)

    async def asyncTearDown(self):
        for t in list(self.p._tasks):
            t.cancel()
        self._tmp.cleanup()

    # ---- helpers ----
    def opts(self, offset=0, mtu=247, **kw):
        o = {"device": PHONE, "mtu": mtu, "link": "LE", **kw}
        if offset:
            o["offset"] = offset
        return o

    def connect(self, **props):
        self.p.devices[PHONE] = {"Address": PHONE_ADDR, "Alias": "Galaxy", "Paired": False,
                                 "Bonded": False, "Connected": False, **props}
        self.p._device_changed(PHONE, {"Connected": True})
        self.assertEqual(self.p.link, PHONE)
        self.assertTrue(self.dev.connected)

    def disconnect(self):
        self.p._device_changed(PHONE, {"Connected": False})
        self.assertIsNone(self.p.link)

    def read(self, name, offset=0, mtu=247):
        return self.p.gatt_read(name, self.opts(offset, mtu))

    def write(self, name, value, **kw):
        self.p.gatt_write(name, value, self.opts(**kw))

    def att(self, fn, *args, **kw) -> int:
        try:
            fn(*args, **kw)
        except DBusError as e:
            return bluez_ecode(e.type, e.text, 0x03)
        return 0

    def bluez_calls(self, member):
        return [c for c in self.calls if c[1] == member]

    async def spin(self, n=3):
        for _ in range(n):
            await asyncio.sleep(0)

    # ---- GATT ----
    async def test_reads(self):
        self.connect()
        self.assertEqual(self.read("manufacturer"), b"DrillBalancer")
        self.assertEqual(self.read("fw_revision"), self.dev.fw_version.encode())
        self.assertEqual(len(self.read("status")), dc.STATUS_SIZE)
        self.assertEqual(len(self.read("battery")), 1)
        self.assertEqual(self.read("model", 9), self.read("model")[9:])
        self.assertEqual(self.att(self.read, "battery", 2), 0x07)
        self.assertEqual(self.read("battery", 1), b"")

    async def test_long_config_read_is_consistent(self):
        self.connect()
        first = self.read("config", mtu=23)[:22]   # Read Request: MTU - 1 bytes
        self.write("command", b"\x03\x02")          # SET_MODE angle between the blobs
        self.dev.advance(10)
        rest = self.read("config", 22, mtu=23)       # Read Blob
        self.assertEqual(json.loads(first + rest)["mode"], "wall")
        self.assertEqual(json.loads(self.read("config"))["mode"], "angle")
        whole = self.read("config")
        self.assertEqual(self.att(self.read, "config", len(whole) + 1), 0x07)

    async def test_write_errors_map_to_att_codes(self):
        self.connect()
        self.assertEqual(self.att(self.write, "config", b"nope"), 0x80)
        self.assertEqual(self.att(self.write, "config", b'{"mode":5}'), 0x81)
        self.assertEqual(self.att(self.write, "command", b""), 0x0D)
        self.assertEqual(self.att(self.write, "command", b"\x99"), 0x84)
        self.assertEqual(self.att(self.write, "command", b"\x03\x07"), 0x85)
        self.dev.busy_next_commands(1)
        self.assertEqual(self.att(self.write, "command", b"\x01"), 0x86)
        self.assertEqual(self.att(self.write, "command", b"\x01"), 0)
        # A long write arrives in one piece at offset 0; anything else is refused.
        self.assertEqual(self.att(self.write, "config", b"{}", offset=18), 0x07)
        self.dev.store_fail = True
        self.assertEqual(self.att(self.write, "config", b'{"tol":0.3}'), 0x83)

    async def test_protected_write_proves_authentication(self):
        self.connect()
        self.assertFalse(self.dev.link_trusted)
        # ota_data is not guarded by BlueZ: dropped on an unauthenticated link.
        self.write("ota_data", b"\x00\x00" + bytes(16), type="command")
        self.assertFalse(self.dev.link_trusted)
        self.write("command", b"\x01")                 # BlueZ checked the link
        self.assertTrue(self.dev.link_trusted)
        self.assertEqual(self.dev.snapshot()["link"]["trusted"], True)

    async def test_recorder_characteristics(self):
        self.connect()
        state = self.p.chars["rec_state"]
        self.p.gatt_subscribe(state, True)
        self.dev.advance(200)
        self.assertNotIn("rec_state", [n for n, _ in self.sent], "not on an untrusted link")
        # BlueZ passes a rec_state read only on an authenticated link: it proves one.
        rs = dc.decode_rec_state(self.read("rec_state"))
        self.assertTrue(self.dev.link_trusted)
        self.assertEqual(rs.state_name, "idle")
        self.write("rec_ctrl", dc.encode_rec("open"))
        self.dev.advance(200)
        notes = [dc.decode_rec_state(v) for n, v in self.sent if n == "rec_state"]
        self.assertEqual(notes[-1].state_name, "wait_client")
        self.assertIn("no_ap", notes[-1].flag_names)
        self.assertEqual(self.att(self.write, "rec_ctrl", dc.encode_rec("take-start")), 0x88)
        self.assertEqual(self.att(self.write, "rec_ctrl", b"\x09"), 0x84)

    async def test_access_from_another_connection_is_refused(self):
        # BlueZ also serves connections the device does not hold (one the PC
        # opened itself, a central _is_ours turned away); the board never
        # sees those. ota_data is the one BlueZ does not guard itself.
        self.connect()
        self.write("command", b"\x01")                 # the link is trusted
        cfg = self.read("config")
        for linked in (True, False):
            other = self.opts(device=OTHER, mtu=100)
            rx, mtu = self.dev.ota_rx_bytes, self.dev.mtu
            self.p.gatt_write("ota_data", b"\x00\x00" + bytes(16), {**other, "type": "command"})
            self.assertEqual(self.dev.ota_rx_bytes, rx)
            self.assertEqual(self.att(self.p.gatt_write, "config", b'{"tol":0.4}', other), 0x0E)
            self.assertEqual(self.att(self.p.gatt_write, "command", b"\x03\x02", other), 0x0E)
            self.assertEqual(self.att(self.p.gatt_read, "config", other), 0x0E)
            self.assertEqual(self.dev.mtu, mtu)
            self.dev.advance(10)
            self.assertEqual(json.loads(self.dev.read_char("config")), json.loads(cfg))
            if linked:
                self.disconnect()
        # Without a link, not even the last peer gets through.
        self.assertEqual(self.att(self.write, "command", b"\x03\x02"), 0x0E)

    async def test_mtu_from_options(self):
        self.connect()
        self.assertEqual(self.dev.mtu, 23)
        self.read("status")
        self.assertEqual(self.dev.mtu, 247)

    async def test_notifications_follow_start_stop_notify(self):
        self.connect()
        angle, status = self.p.chars["angle"], self.p.chars["status"]
        self.p.gatt_subscribe(angle, True)
        self.p.gatt_subscribe(status, True)
        self.assertTrue(angle.notifying)
        self.dev.advance(1000)
        names = [n for n, _ in self.sent]
        self.assertGreaterEqual(names.count("angle"), 9)
        self.assertGreaterEqual(names.count("status"), 1)
        self.assertEqual(len(self.sent[0][1]), dc.ANGLE_SIZE)
        self.p.gatt_subscribe(angle, False)
        self.sent.clear()
        self.dev.advance(1000)
        self.assertNotIn("angle", [n for n, _ in self.sent])

    @unittest.skipUnless(FIRMWARE_IMAGE.exists(), "needs firmware/build/drillbalancer.bin")
    async def test_ota_indications_and_confirm(self):
        self.connect()
        self.write("command", b"\x01")   # authenticated link
        ctrl = self.p.chars["ota_ctrl"]
        self.p.gatt_subscribe(ctrl, True)
        image = FIRMWARE_IMAGE.read_bytes()[:4096]
        self.write("ota_ctrl", dc.encode_ota_start(len(image), hashlib.sha256(image).digest()))
        replies = [v for n, v in self.sent if n == "ota_ctrl"]
        self.assertEqual(len(replies), 1)
        msg = dc.decode_ota_msg(replies[0])
        self.assertEqual(msg["status"], dc.OTA_STATUS_OK)
        self.assertEqual(msg["max_chunk"], 242)        # MTU 247 from the options
        self.assertTrue(self.dev._ind_busy)
        ctrl.Confirm.__dict__["__DBUS_METHOD"].fn(ctrl)
        self.assertFalse(self.dev._ind_busy)

    @unittest.skipUnless(FIRMWARE_IMAGE.exists(), "needs firmware/build/drillbalancer.bin")
    async def test_unsubscribe_ends_the_unconfirmed_indication(self):
        # The app turns indications off right after its ABORT. An indication BlueZ gets
        # after that goes to no one and is never confirmed; the next transfer must still
        # get its replies (the board sends it anyway and the phone confirms it).
        self.connect()
        self.write("command", b"\x01")   # authenticated link
        ctrl = self.p.chars["ota_ctrl"]
        confirm = ctrl.Confirm.__dict__["__DBUS_METHOD"].fn
        image = FIRMWARE_IMAGE.read_bytes()[:4096]
        start = dc.encode_ota_start(len(image), hashlib.sha256(image).digest())
        self.p.gatt_subscribe(ctrl, True)
        self.write("ota_ctrl", start)
        self.write("ota_ctrl", bytes([dc.OTA_OP_ABORT]))   # its reply waits behind START's
        self.p.gatt_subscribe(ctrl, False)              # no Confirm for the START reply
        self.assertFalse(self.dev._ind_busy)
        confirm(ctrl)   # one BlueZ did send after all, confirmed late: changes nothing
        self.sent.clear()
        self.p.gatt_subscribe(ctrl, True)
        self.write("ota_ctrl", start)
        replies = [dc.decode_ota_msg(v) for n, v in self.sent if n == "ota_ctrl"]
        self.assertEqual(len(replies), 1)
        self.assertEqual((replies[0]["op"], replies[0]["status"]), (dc.OTA_OP_START, dc.OTA_STATUS_OK))

    # ---- subscriptions across links ----
    async def test_bonded_peer_gets_its_subscriptions_back(self):
        self.connect(Paired=True, Bonded=True)
        self.p.gatt_subscribe(self.p.chars["angle"], True)
        self.disconnect()
        self.sent.clear()
        # BlueZ kept the CCCD: the phone's identical CCCD write calls nothing.
        self.connect(Paired=True, Bonded=True)
        self.dev.advance(500)
        self.assertIn("angle", [n for n, _ in self.sent])

    async def test_unbonded_peer_starts_over(self):
        self.connect()
        self.p.gatt_subscribe(self.p.chars["angle"], True)
        self.disconnect()
        self.p.gatt_subscribe(self.p.chars["angle"], False)   # BlueZ clearing its CCCDs
        self.sent.clear()
        self.connect()
        self.dev.advance(500)
        self.assertNotIn("angle", [n for n, _ in self.sent])

    # ---- pairing ----
    async def test_passkey_pairing(self):
        self.connect()
        self.p.agent_passkey(PHONE, 123456, 0)
        snap = self.dev.snapshot()
        self.assertTrue(snap["passkey_pending"])
        self.assertEqual(snap["passkey"], 123456)
        self.p._device_changed(PHONE, {"Paired": True, "Bonded": True})
        snap = self.dev.snapshot()
        self.assertFalse(snap["passkey_pending"])
        self.assertTrue(self.dev.link_trusted)
        self.assertEqual(json.loads(self.p.bonds_path.read_text()),
                         {PHONE_ADDR: {"authenticated": True}})
        # Reconnect with the bond: encrypted counts as authenticated again.
        self.disconnect()
        self.connect(Paired=True, Bonded=True)
        self.assertFalse(self.dev.link_trusted)
        self.p._on_encrypted()
        self.assertTrue(self.dev.link_trusted)

    async def test_passkey_pairing_encrypted_before_paired(self):
        # On the phone the kernel reports the link encrypted before BlueZ sets Paired.
        self.connect()
        self.p.agent_passkey(PHONE, 123456, 0)
        self.p._on_encrypted()
        self.assertTrue(self.dev.link_trusted)
        self.p._device_changed(PHONE, {"Paired": True, "Bonded": True})
        self.assertEqual(json.loads(self.p.bonds_path.read_text()),
                         {PHONE_ADDR: {"authenticated": True}})
        self.disconnect()
        await self.spin()
        self.assertEqual(self.bluez_calls("RemoveDevice"), [])   # a passkey bond is kept

    async def test_just_works_bond_is_not_kept(self):
        self.connect()
        self.p._device_changed(PHONE, {"Paired": True, "Bonded": True})
        self.assertFalse(self.dev.link_trusted)
        self.disconnect()
        await self.spin()
        self.assertEqual(self.bluez_calls("RemoveDevice"), [("/org/bluez/hci0", "RemoveDevice", [PHONE])])

    async def test_failed_pairing_clears_the_passkey(self):
        old = B.PASSKEY_TIMEOUT_S
        B.PASSKEY_TIMEOUT_S = 0.01
        try:
            self.connect()
            self.p.agent_passkey(PHONE, 654321, 0)
            await asyncio.sleep(0.05)
        finally:
            B.PASSKEY_TIMEOUT_S = old
        self.assertFalse(self.dev.snapshot()["passkey_pending"])
        self.assertFalse(self.dev.link_trusted)

    async def test_forgotten_bond_is_pointed_out(self):
        # bluetoothd asks a bonded peer to encrypt at once; a phone without the keys
        # does not, and shows a pairing prompt instead.
        class Hci:
            encrypted = False

            def peripheral_link(self, addr):
                return {"encrypted": self.encrypted}

        logs = []
        self.dev.subscribe(lambda name, data: logs.append(data["msg"]) if name == "log" else None)
        hint = lambda: [m for m in logs if "forgotten the bond" in m]
        old = B.BOND_UNUSED_S, B.POLL_S
        B.BOND_UNUSED_S, B.POLL_S = 0.03, 0.005
        self.p.hci = Hci()
        try:
            self.connect()                              # not paired: nothing to point out
            await asyncio.sleep(0.06)
            self.disconnect()
            self.connect(Paired=True, Bonded=True)
            await asyncio.sleep(0.06)
            self.assertEqual(len(hint()), 1)            # once per link
            self.assertIn(f"bluetoothctl remove {PHONE_ADDR}", hint()[0])
            self.disconnect()
            self.p.hci.encrypted = True                 # a phone with the keys
            self.connect(Paired=True, Bonded=True)
            await asyncio.sleep(0.06)
            self.assertEqual(len(hint()), 1)
        finally:
            B.BOND_UNUSED_S, B.POLL_S = old

    async def test_factory_reset_removes_only_sim_bonds(self):
        self.p.devices["/org/bluez/hci0/dev_11_22_33_44_55_66"] = {
            "Address": "11:22:33:44:55:66", "Paired": True, "Connected": False}   # the PC's keyboard
        self.connect()
        self.p.agent_passkey(PHONE, 111111, 0)
        self.p._device_changed(PHONE, {"Paired": True, "Bonded": True})
        self.write("command", b"\x23\xa5")
        self.dev.advance(100)
        await self.spin()
        self.assertEqual(self.bluez_calls("Disconnect"), [(PHONE, "Disconnect", [])])
        self.disconnect()
        await asyncio.sleep(0.6)
        self.assertEqual([c[2] for c in self.bluez_calls("RemoveDevice")], [[PHONE]])
        self.assertEqual(self.p.bonds, {})

    # ---- link policy ----
    async def test_idle_unauthenticated_link_is_dropped(self):
        self.connect()
        self.dev.advance(59_000)
        await self.spin()
        self.assertEqual(self.bluez_calls("Disconnect"), [])
        self.dev.advance(2000)
        await self.spin()
        self.assertEqual(self.bluez_calls("Disconnect"), [(PHONE, "Disconnect", [])])

    async def test_sleep_drops_link_and_advertising(self):
        task = asyncio.ensure_future(self.p._adv_loop())
        try:
            self.p._want_adv(self.dev.advertising)
            await self.spin(5)
            self.assertEqual(len(self.bluez_calls("RegisterAdvertisement")), 1)
            self.connect()
            await self.spin(5)
            self.assertEqual(len(self.bluez_calls("UnregisterAdvertisement")), 1)
            self.disconnect()
            await self.spin(5)
            self.assertEqual(len(self.bluez_calls("RegisterAdvertisement")), 2)
            self.connect()
            self.dev.ui_command(C.APP_CMD_SLEEP)
            self.dev.advance(50)
            await self.spin(5)
            self.assertEqual(self.dev.state, "sleep")
            self.assertEqual(self.bluez_calls("Disconnect"), [(PHONE, "Disconnect", [])])
            self.disconnect()
            await self.spin(5)
            self.assertFalse(self.p.adv.registered)
            self.assertEqual(len(self.bluez_calls("RegisterAdvertisement")), 2)
        finally:
            task.cancel()

    async def test_connection_refused_while_not_advertising(self):
        self.dev.ui_command(C.APP_CMD_SLEEP)
        self.dev.advance(50)
        self.p.devices[PHONE] = {"Address": PHONE_ADDR, "Connected": False}
        self.p._device_changed(PHONE, {"Connected": True})   # not ours: we were not advertising
        self.assertIsNone(self.p.link)


@unittest.skipUnless(shutil.which("dbus-daemon"), "needs dbus-daemon")
class WireTest(unittest.IsolatedAsyncioTestCase):
    """The exported objects on a private bus, called like BlueZ calls them."""

    async def asyncSetUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.daemon = subprocess.Popen(
            ["dbus-daemon", "--session", "--nofork", "--print-address=1"],
            stdout=subprocess.PIPE, text=True)
        address = self.daemon.stdout.readline().strip()
        self.server = await MessageBus(bus_address=address).connect()
        self.client = await MessageBus(bus_address=address).connect()
        self.dev = DbSimDevice(self._tmp.name, seed=1)
        self.dev.advance(500)
        self.p = B.BluezPeripheral(self.dev, hci=False)
        self.p.export(self.server)
        self.p.devices[PHONE] = {"Address": PHONE_ADDR, "Connected": False}
        self.p._device_changed(PHONE, {"Connected": True})

    async def asyncTearDown(self):
        self.client.disconnect()
        self.server.disconnect()
        self.daemon.terminate()
        self.daemon.wait()
        self.daemon.stdout.close()
        self._tmp.cleanup()

    async def call(self, path, interface, member, signature="", body=()):
        return await self.client.call(Message(destination=self.server.unique_name, path=path,
                                              interface=interface, member=member,
                                              signature=signature, body=list(body)))

    async def test_managed_objects_and_calls(self):
        reply = await self.call(B.APP_PATH, "org.freedesktop.DBus.ObjectManager", "GetManagedObjects")
        objects = reply.body[0]
        chars = {o["org.bluez.GattCharacteristic1"]["UUID"].value:
                 o["org.bluez.GattCharacteristic1"]["Flags"].value
                 for o in objects.values() if "org.bluez.GattCharacteristic1" in o}
        services = [o["org.bluez.GattService1"]["UUID"].value
                    for o in objects.values() if "org.bluez.GattService1" in o]
        self.assertEqual(len(services), 5)
        self.assertEqual(len(chars), 12)
        self.assertEqual(chars[B.db_uuid(0x0202)], ["encrypt-authenticated-read", "notify"])
        self.assertEqual(chars[B.db_uuid(0x0102)], ["write-without-response"])
        self.assertNotIn(B.AGENT_PATH, objects)

        cfg = self.p.chars["config"].path
        opts = {"device": Variant("o", PHONE), "mtu": Variant("q", 185), "offset": Variant("q", 2)}
        reply = await self.call(cfg, "org.bluez.GattCharacteristic1", "ReadValue", "a{sv}", [opts])
        self.assertEqual(reply.message_type, MessageType.METHOD_RETURN)
        self.assertEqual(reply.body[0], self.dev.read_char("config")[2:])
        self.assertEqual(self.dev.mtu, 185)

        opts = {"device": Variant("o", PHONE), "type": Variant("s", "request")}
        reply = await self.call(cfg, "org.bluez.GattCharacteristic1", "WriteValue", "aya{sv}",
                                [b"[1]", opts])
        self.assertEqual(reply.message_type, MessageType.ERROR)
        self.assertEqual((reply.error_name, reply.body[0]), ("org.bluez.Error.Failed", "0x80"))

        reply = await self.call(self.p.chars["command"].path, "org.bluez.GattCharacteristic1",
                                "WriteValue", "aya{sv}", [b"", opts])
        self.assertEqual(reply.error_name, "org.bluez.Error.InvalidValueLength")

        reply = await self.call(self.p.chars["angle"].path, "org.bluez.GattCharacteristic1",
                                "StartNotify")
        self.assertEqual(reply.message_type, MessageType.METHOD_RETURN)
        self.assertIn("angle", self.dev._subs)

        reply = await self.call(B.ADV_PATH, "org.freedesktop.DBus.Properties", "GetAll", "s",
                                ["org.bluez.LEAdvertisement1"])
        adv = {k: v.value for k, v in reply.body[0].items()}
        self.assertEqual((adv["Type"], adv["LocalName"], adv["ServiceUUIDs"]),
                         ("peripheral", self.dev.name, [B.SVC_MAIN]))

        reply = await self.call(B.AGENT_PATH, "org.bluez.Agent1", "DisplayPasskey", "ouq",
                                [PHONE, 42, 0])
        self.assertEqual(reply.message_type, MessageType.METHOD_RETURN)
        self.assertEqual(self.dev.snapshot()["passkey"], 42)


if __name__ == "__main__":
    unittest.main()
