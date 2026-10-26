"""BlueZ peripheral for the simulated DrillBalancer (dbus-fast, asyncio).

A thin transport between BlueZ and DbSimDevice: it exports the firmware's GATT
table (ble_service.c s_svcs) as a BlueZ GATT application, advertises like the
board, shows pairing passkeys through a DisplayOnly agent and feeds the link,
its security and the GATT traffic into the device. Behaviour lives in the
device; this module only translates.

How the board's behaviour maps onto BlueZ (5.87):
- Security: config, command, ota_ctrl and rec_ctrl carry
  'encrypt-authenticated-write', rec_state 'encrypt-authenticated-read', so
  BlueZ itself answers 0x05 before such an access reaches us. ota_data is
  write without response only (no reply either way); its check is done here,
  as that flag would add the 'write' property. BlueZ's KeySize (main.conf)
  defaults to no minimum, so a short key is not refused with 0x0C. The
  device notifies rec_state (it carries the stream credentials) only to an
  authenticated link.
- ATT errors: org.bluez.Error.Failed with "0xNN" becomes 0x80..0x9f,
  InvalidValueLength 0x0D, InvalidOffset 0x07, any other name 0x0E
  (gatt-database.c dbus_error_to_att_ecode).
- Long reads: BlueZ asks ReadValue per blob with 'offset'; the device keeps the
  JSON captured at offset 0. Long writes: BlueZ queues the prepare writes and
  hands a contiguous run over as one WriteValue at offset 0 on execute.
- Links: BlueZ has no signal for "encrypted". The kernel's connection list
  (HCIGETCONNLIST, no privileges needed) tells which LE link has us as
  peripheral and when it gets encrypted. Authentication is inferred: with our
  DisplayOnly agent a pairing that showed a passkey is authenticated, a bond
  keeps what its pairing was, and a protected write proves it (BlueZ checked).
- Subscriptions: BlueZ calls StartNotify/StopNotify only on changes and keeps a
  bonded peer's CCCDs across reconnects (NimBLE restores them too), so they are
  remembered per peer here and restored into the device on reconnect.
  BlueZ drops an indication for a client whose CCCD is off without a Confirm,
  so turning ota_ctrl off ends the indication in flight.
- Advertising: BlueZ puts the service UUID in the advertising data and the name
  in the scan response (the board does it the other way round); both reach an
  active scanner such as Android's.
- BlueZ's own GAP/GATT services stay in front of ours. Its Device Information
  service (PnP ID) comes first too unless main.conf has DeviceID = false (root).
"""

# No "from __future__ import annotations": dbus-fast reads the D-Bus
# signatures from the annotation strings.
import asyncio
import fcntl
import json
import socket
import struct
from pathlib import Path

from dbus_fast import BusType, DBusError, Message, MessageType, PropertyAccess, Variant
from dbus_fast.aio import MessageBus
from dbus_fast.service import ServiceInterface, dbus_property, method

BLUEZ = "org.bluez"
APP_PATH = "/dev/drillbalancer/gatt"            # GattManager1 application (ObjectManager root)
ADV_PATH = "/dev/drillbalancer/advertisement"
AGENT_PATH = "/dev/drillbalancer/agent"
IO_CAPABILITY = "DisplayOnly"      # ble_service_init(): BLE_HS_IO_DISPLAY_ONLY


def sig_uuid(x: int) -> str:
    return f"0000{x:04x}-0000-1000-8000-00805f9b34fb"


def db_uuid(x: int) -> str:
    """DB_UUID_STR(x) from ble_protocol.h."""
    return f"d71b{x:04x}-8a3e-4f6c-b5d2-1c9e0a7f4b63"


SVC_MAIN = db_uuid(0x0001)

# ble_service.c s_svcs in its order: (service UUID, ((device char name, UUID, BlueZ flags), ...)).
# The NimBLE flags in BlueZ terms; SEC_WRITE is 'encrypt-authenticated-write', READ_ENC |
# READ_AUTHEN (SEC_READ) 'encrypt-authenticated-read'. tests/test_ble.py TableTest
# compares it with ble_service.c's table.
SERVICES = (
    (sig_uuid(0x180A), (
        ("manufacturer", sig_uuid(0x2A29), ("read",)),
        ("model", sig_uuid(0x2A24), ("read",)),
        ("fw_revision", sig_uuid(0x2A26), ("read",)),
    )),
    (sig_uuid(0x180F), (
        ("battery", sig_uuid(0x2A19), ("read", "notify")),
    )),
    (SVC_MAIN, (
        ("angle", db_uuid(0x0002), ("notify",)),
        ("config", db_uuid(0x0003), ("read", "encrypt-authenticated-write")),
        ("command", db_uuid(0x0004), ("encrypt-authenticated-write",)),
        ("status", db_uuid(0x0005), ("read", "notify")),
    )),
    (db_uuid(0x0100), (
        ("ota_ctrl", db_uuid(0x0101), ("encrypt-authenticated-write", "indicate")),
        # Authenticated link required as well, checked in _gatt_write.
        ("ota_data", db_uuid(0x0102), ("write-without-response",)),
    )),
    (db_uuid(0x0200), (
        ("rec_ctrl", db_uuid(0x0201), ("encrypt-authenticated-write",)),
        ("rec_state", db_uuid(0x0202), ("encrypt-authenticated-read", "notify")),
    )),
)
PROTECTED = {"config", "command", "ota_ctrl", "ota_data", "rec_ctrl"}
PROTECTED_READ = {"rec_state"}

# ble_service.c ADV_*: fast right after enable / disconnect, slow after 30 s.
ADV_FAST_S = 30
ADV_FAST_MS = (100, 110)
ADV_SLOW_MS = (1000, 1050)

# A passkey shown longer than this is gone: SMP times out after 30 s, and a
# failed passkey entry reaches no D-Bus API (DisplayPasskey has no Cancel).
PASSKEY_TIMEOUT_S = 35
# Without the kernel's connection list: a bonded peer counts as encrypted
# this long after connecting (bluetoothd asks it to encrypt right away).
ASSUME_ENCRYPTED_S = 0.3
POLL_S = 0.1

# Kernel HCI ioctl (include/net/bluetooth/hci.h, hci_sock.h).
HCIGETCONNLIST = 0x800448D4        # _IOR('H', 212, int)
LE_LINK = 0x80
HCI_LM_MASTER = 0x0001
HCI_LM_ENCRYPT = 0x0004
_CONN_INFO = struct.Struct("=H6sBBHI")   # struct hci_conn_info, 16 bytes


def att_error(code: int) -> DBusError:
    """The D-Bus error BlueZ turns into this ATT error code."""
    if code == 0x0D:
        return DBusError("org.bluez.Error.InvalidValueLength", "0x0d")
    if code == 0x07:
        return DBusError("org.bluez.Error.InvalidOffset", "0x07")
    if code == 0x06:
        return DBusError("org.bluez.Error.NotSupported", "0x06")
    if 0x80 <= code <= 0x9F:
        return DBusError("org.bluez.Error.Failed", f"0x{code:02x}")
    # Any other name is 0x0E "unlikely error".
    return DBusError("org.bluez.Error.Unlikely", f"0x{code:02x}")


def unwrap(value):
    """Variants (also nested in dicts and lists) to plain values."""
    if isinstance(value, Variant):
        return unwrap(value.value)
    if isinstance(value, dict):
        return {k: unwrap(v) for k, v in value.items()}
    if isinstance(value, list):
        return [unwrap(v) for v in value]
    return value


class HciConnections:
    """The kernel's connection list of one adapter (HCIGETCONNLIST on a raw
    HCI socket; works without privileges)."""

    def __init__(self, dev_id: int):
        self.dev_id = dev_id
        self._sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_RAW, socket.BTPROTO_HCI)
        self.list()   # fails here if the ioctl is not allowed

    def list(self, max_conn=16) -> list[dict]:
        buf = bytearray(struct.pack("=HH", self.dev_id, max_conn) + bytes(_CONN_INFO.size * max_conn))
        fcntl.ioctl(self._sock.fileno(), HCIGETCONNLIST, buf)
        n = struct.unpack_from("=H", buf, 2)[0]
        out = []
        for i in range(n):
            handle, addr, typ, _out, _state, mode = _CONN_INFO.unpack_from(buf, 4 + _CONN_INFO.size * i)
            out.append({"handle": handle, "address": ":".join(f"{b:02X}" for b in reversed(addr)),
                        "le": typ == LE_LINK, "central": bool(mode & HCI_LM_MASTER),
                        "encrypted": bool(mode & HCI_LM_ENCRYPT), "link_mode": mode})
        return out

    def peripheral_link(self, address: str) -> dict | None:
        """The LE link to address on which we are the peripheral, or None."""
        for c in self.list():
            if c["le"] and not c["central"] and c["address"] == address.upper():
                return c
        return None

    def close(self):
        self._sock.close()


# ---- D-Bus objects -------------------------------------------------------------------

class Service(ServiceInterface):
    def __init__(self, uuid: str, path: str):
        super().__init__("org.bluez.GattService1")
        self.uuid = uuid
        self.path = path

    @dbus_property(access=PropertyAccess.READ)
    def UUID(self) -> "s":
        return self.uuid

    @dbus_property(access=PropertyAccess.READ)
    def Primary(self) -> "b":
        return True


class Characteristic(ServiceInterface):
    """One characteristic; the logic is in BluezPeripheral (read/write/notify)."""

    def __init__(self, peripheral, name: str, uuid: str, flags, service_path: str, path: str):
        super().__init__("org.bluez.GattCharacteristic1")
        self.peripheral = peripheral
        self.key = name          # the device's name for it ("config"); .name is the D-Bus interface
        self.uuid = uuid
        self.flags = list(flags)
        self.service_path = service_path
        self.path = path
        self.value = b""
        self.notifying = False

    @dbus_property(access=PropertyAccess.READ)
    def UUID(self) -> "s":
        return self.uuid

    @dbus_property(access=PropertyAccess.READ)
    def Service(self) -> "o":
        return self.service_path

    @dbus_property(access=PropertyAccess.READ)
    def Flags(self) -> "as":
        return self.flags

    @dbus_property(access=PropertyAccess.READ)
    def Value(self) -> "ay":
        return self.value

    @dbus_property(access=PropertyAccess.READ)
    def Notifying(self) -> "b":
        return self.notifying

    # Synchronous handlers: dbus-fast runs them in arrival order, so writes
    # reach the device in the order BlueZ sent them.
    @method()
    def ReadValue(self, options: "a{sv}") -> "ay":
        return self.peripheral.gatt_read(self.key, unwrap(options))

    @method()
    def WriteValue(self, value: "ay", options: "a{sv}"):
        self.peripheral.gatt_write(self.key, bytes(value), unwrap(options))

    @method()
    def StartNotify(self):
        self.peripheral.gatt_subscribe(self, True)

    @method()
    def StopNotify(self):
        self.peripheral.gatt_subscribe(self, False)

    @method()
    def Confirm(self):
        self.peripheral.gatt_confirmed(self)

    def send(self, value: bytes):
        """Notification or indication: BlueZ picks by the client's CCCD."""
        self.value = bytes(value)
        self.emit_properties_changed({"Value": self.value})

    def set_notifying(self, on: bool):
        if on != self.notifying:
            self.notifying = on
            self.emit_properties_changed({"Notifying": on})


class Advertisement(ServiceInterface):
    def __init__(self, name: str):
        super().__init__("org.bluez.LEAdvertisement1")
        self.local_name = name
        self.interval = ADV_FAST_MS
        self.registered = False

    @dbus_property(access=PropertyAccess.READ)
    def Type(self) -> "s":
        return "peripheral"

    @dbus_property(access=PropertyAccess.READ)
    def ServiceUUIDs(self) -> "as":
        return [SVC_MAIN]

    @dbus_property(access=PropertyAccess.READ)
    def LocalName(self) -> "s":
        return self.local_name

    @dbus_property(access=PropertyAccess.READ)
    def Discoverable(self) -> "b":
        return True     # LE General Discoverable, BR/EDR not supported, like the board

    @dbus_property(access=PropertyAccess.READ)
    def MinInterval(self) -> "u":
        return self.interval[0]

    @dbus_property(access=PropertyAccess.READ)
    def MaxInterval(self) -> "u":
        return self.interval[1]

    @method()
    def Release(self):
        self.registered = False


class Agent(ServiceInterface):
    """DisplayOnly pairing agent: shows the passkey BlueZ generated."""

    def __init__(self, peripheral):
        super().__init__("org.bluez.Agent1")
        self.peripheral = peripheral

    @method()
    def Release(self):
        self.peripheral.log("agent released by BlueZ", "W")

    @method()
    def DisplayPasskey(self, device: "o", passkey: "u", entered: "q"):
        self.peripheral.agent_passkey(device, passkey, entered)

    @method()
    def RequestConfirmation(self, device: "o", passkey: "u"):
        # Not used with DisplayOnly (no numeric comparison); NimBLE would accept.
        self.peripheral.log(f"numeric comparison {passkey:06d} accepted", "W")

    @method()
    def RequestAuthorization(self, device: "o"):
        # Just Works: NimBLE accepts it; the bond is not kept (_on_paired).
        self.peripheral.log("Just Works pairing accepted (unauthenticated)", "W")

    @method()
    def AuthorizeService(self, device: "o", uuid: "s"):
        pass

    @method()
    def RequestPinCode(self, device: "o") -> "s":
        raise DBusError("org.bluez.Error.Rejected", "display only")

    @method()
    def DisplayPinCode(self, device: "o", pincode: "s"):
        raise DBusError("org.bluez.Error.Rejected", "display only")

    @method()
    def RequestPasskey(self, device: "o") -> "u":
        raise DBusError("org.bluez.Error.Rejected", "display only")

    @method()
    def Cancel(self):
        self.peripheral.agent_cancel()


# ---- transport -----------------------------------------------------------------------

class BluezPeripheral:
    """The simulated device on a BlueZ adapter. start() / stop() from asyncio."""

    def __init__(self, device, adapter="hci0", bus: MessageBus | None = None, hci=True):
        self.dev = device
        self.adapter = adapter
        self.adapter_path = f"/org/bluez/{adapter}"
        self.bus = bus
        self._hci_wanted = hci
        self.hci: HciConnections | None = None

        self.services = []
        self.chars: dict[str, Characteristic] = {}
        for si, (suuid, chars) in enumerate(SERVICES):
            spath = f"{APP_PATH}/service{si}"
            self.services.append(Service(suuid, spath))
            for ci, (name, cuuid, flags) in enumerate(chars):
                self.chars[name] = Characteristic(self, name, cuuid, flags, spath, f"{spath}/char{ci}")
        self.adv = Advertisement(device.name)
        self.agent = Agent(self)

        self.devices: dict[str, dict] = {}    # Device1 path -> properties
        self.link: str | None = None          # Device1 path of our central
        self._link_addr = ""
        self._encrypted = False               # the kernel reported the link encrypted
        self._encrypted_reported = False      # the device was told (once per link)
        self._disc_reason = ""
        self._passkey = None                  # shown passkey (pending pairing)
        self._passkey_checked = False         # this link's encryption followed a shown passkey
        self._passkey_timer = None
        self._pair_auth = {}                  # path -> authenticated, pairings seen this run
        self._ccc: dict[str, set] = {}        # path -> subscribed chars (BlueZ's CCC state)
        self._tasks = set()
        self._poll_task = None
        self._adv_wake = asyncio.Event()
        self._adv_mode = None                 # wanted: None, "fast", "slow"
        self._adv_slow_timer = None
        self._restore = {}
        self._unsubscribe = None
        self._started = False
        self.bonds_path = Path(device.state_dir) / "bonds.json"
        self.bonds = self._load_bonds()       # address -> {"authenticated": bool}, made via the sim

    # ---- helpers ----------------------------------------------------------------------
    def log(self, msg, level="I"):
        self.dev.emit("log", {"level": level, "msg": msg, "source": "ble"})

    def _event(self, event, **data):
        self.dev.emit("ble", {"event": event, **data})

    def _spawn(self, coro):
        task = asyncio.ensure_future(coro)
        self._tasks.add(task)
        task.add_done_callback(self._task_done)
        return task

    def _task_done(self, task):
        self._tasks.discard(task)
        if not task.cancelled() and task.exception() is not None:
            self.log(f"{type(task.exception()).__name__}: {task.exception()}", "E")

    async def call(self, path, interface, member, signature="", body=(), destination=BLUEZ):
        """A method call on BlueZ; raises DBusError on an error reply."""
        reply = await self.bus.call(Message(destination=destination, path=path, interface=interface,
                                            member=member, signature=signature, body=list(body)))
        if reply.message_type == MessageType.ERROR:
            raise DBusError(reply.error_name, reply.body[0] if reply.body else "")
        return reply.body

    async def _set_adapter(self, prop, sig, value):
        await self.call(self.adapter_path, "org.freedesktop.DBus.Properties", "Set", "ssv",
                        ["org.bluez.Adapter1", prop, Variant(sig, value)])

    def _peer(self, path) -> str:
        p = self.devices.get(path, {})
        return p.get("Address") or path.rsplit("/", 1)[-1]

    def _load_bonds(self) -> dict:
        try:
            return json.loads(self.bonds_path.read_text())
        except (OSError, ValueError):
            return {}

    def _save_bonds(self):
        tmp = self.bonds_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.bonds, indent=1))
        tmp.replace(self.bonds_path)

    # ---- start / stop -----------------------------------------------------------------
    def export(self, bus: MessageBus):
        """Puts the GATT application, advertisement and agent on the bus."""
        self.bus = bus
        for s in self.services:
            bus.export(s.path, s)
        for c in self.chars.values():
            bus.export(c.path, c)
        bus.export(ADV_PATH, self.adv)
        bus.export(AGENT_PATH, self.agent)

    async def start(self):
        if self.bus is None:
            self.bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
        objects = unwrap((await self.call("/", "org.freedesktop.DBus.ObjectManager",
                                          "GetManagedObjects"))[0])
        props = objects.get(self.adapter_path, {}).get("org.bluez.Adapter1")
        if props is None:
            raise RuntimeError(f"no Bluetooth adapter {self.adapter} (is bluetoothd running?)")
        if "peripheral" not in props.get("Roles", ["peripheral"]):
            raise RuntimeError(f"{self.adapter} cannot act as a BLE peripheral")
        if self._hci_wanted:
            try:
                self.hci = HciConnections(int(self.adapter.removeprefix("hci")))
            except (OSError, ValueError) as e:
                self.log(f"kernel connection list unavailable ({e}): link security is guessed", "W")

        # Adapter settings for the run, restored by stop().
        self._restore = {"Alias": "" if props["Alias"] == props["Name"] else props["Alias"]}
        if not props["Powered"]:
            self._restore["Powered"] = False
            await self._set_adapter("Powered", "b", True)
        if not props["Pairable"]:
            self._restore["Pairable"] = False
            await self._set_adapter("Pairable", "b", True)
        # BlueZ's GAP service answers Device Name with the alias: the board's name.
        await self._set_adapter("Alias", "s", self.dev.name)

        self.export(self.bus)
        self.bus.add_message_handler(self._on_message)
        await self.call("/org/freedesktop/DBus", "org.freedesktop.DBus", "AddMatch", "s",
                        [f"type='signal',sender='{BLUEZ}'"], destination="org.freedesktop.DBus")
        await self.call("/org/bluez", "org.bluez.AgentManager1", "RegisterAgent", "os",
                        [AGENT_PATH, IO_CAPABILITY])
        # Ours wins over the desktop's agent while the simulator runs; BlueZ
        # falls back to the previous default when we unregister.
        await self.call("/org/bluez", "org.bluez.AgentManager1", "RequestDefaultAgent", "o",
                        [AGENT_PATH])
        await self.call(self.adapter_path, "org.bluez.GattManager1", "RegisterApplication",
                        "oa{sv}", [APP_PATH, {}])
        self._started = True
        self.log(f"GATT application registered on {self.adapter} ({props['Address']}), "
                 f"agent {IO_CAPABILITY}")

        for path, ifaces in objects.items():
            if "org.bluez.Device1" in ifaces and path.startswith(self.adapter_path + "/"):
                self.devices[path] = ifaces["org.bluez.Device1"]
        # A central still connected from an earlier run: the device just booted.
        for path, p in list(self.devices.items()):
            if p.get("Connected") and self.hci and self.hci.peripheral_link(p.get("Address", "")):
                self.log(f"dropping a stale link to {self._peer(path)}", "W")
                self._spawn(self._disconnect(path))

        self._unsubscribe = self.dev.subscribe(self._on_device_event)
        self._spawn(self._adv_loop())
        if self.dev.advertising:
            self._want_adv(True)

    async def stop(self):
        """Drops the link, unregisters everything and restores the adapter."""
        self._started = False
        if self._unsubscribe:
            self._unsubscribe()
            self._unsubscribe = None
        if self.link:
            await self._disconnect(self.link, quiet=True)
        if self.bus is not None:
            # Whatever start() got to; "does not exist" errors are fine here.
            for path, iface, member, arg in (
                    (self.adapter_path, "org.bluez.LEAdvertisingManager1", "UnregisterAdvertisement",
                     ADV_PATH),
                    (self.adapter_path, "org.bluez.GattManager1", "UnregisterApplication", APP_PATH),
                    ("/org/bluez", "org.bluez.AgentManager1", "UnregisterAgent", AGENT_PATH)):
                try:
                    await self.call(path, iface, member, "o", [arg])
                except DBusError:
                    pass
        self.adv.registered = False
        for prop, value in self._restore.items():
            try:
                await self._set_adapter(prop, "s" if prop == "Alias" else "b", value)
            except DBusError as e:
                self.log(f"restoring {prop}: {e.text}", "W")
        self._restore = {}
        for t in list(self._tasks):
            t.cancel()
        if self.hci:
            self.hci.close()
            self.hci = None
        self.log("stopped, adapter settings restored")

    def status(self) -> dict:
        """The link as BlueZ sees it (web UI)."""
        props = self.devices.get(self.link, {}) if self.link else {}
        return {"peer": self._link_addr if self.link else None,
                "peer_name": props.get("Alias") or props.get("Name"),
                "paired": bool(props.get("Paired")), "bonded": bool(props.get("Bonded")),
                "encrypted": self._encrypted_reported, "passkey": self._passkey,
                "advertising": self._adv_mode, "bonds": sorted(self.bonds)}

    # ---- GATT (called by the D-Bus objects) -------------------------------------------
    def _from_link(self, opts: dict) -> bool:
        """Whether BlueZ got this access over the link the device holds (it names
        the peer in options["device"]). The board never sees one from another
        connection: one the PC opened itself, or a central _is_ours turned away."""
        return self.link is not None and self.dev.connected and opts.get("device") == self.link

    def _note_access(self, opts: dict):
        mtu = opts.get("mtu")
        if mtu and mtu != self.dev.mtu:
            self.dev.set_mtu(int(mtu))
            self._event("mtu", peer=self._link_addr, mtu=int(mtu))

    def gatt_read(self, name: str, opts: dict) -> bytes:
        offset = int(opts.get("offset", 0))
        if not self._from_link(opts):
            self.log(f"{name} read from {self._peer(opts.get('device') or '?')}, "
                     "not the link: refused", "W")
            raise att_error(0x0E)
        self._note_access(opts)
        if name in PROTECTED_READ and not self.dev.link_trusted:
            # BlueZ only lets this through on an authenticated, encrypted link.
            self._link_trusted("protected read")
        if self.dev.state != "running":
            self._event("read", peer=self._link_addr, char=name, offset=offset, att=0x0E)
            raise att_error(0x0E)
        try:
            value = self.dev.read_char(name, offset)
        except IndexError:
            self._event("read", peer=self._link_addr, char=name, offset=offset, att=0x07)
            raise att_error(0x07) from None
        if offset == 0:   # the blobs of a long read are not worth an event each
            self._event("read", peer=self._link_addr, char=name, offset=0, size=len(value), att=0)
        return value

    def gatt_write(self, name: str, value: bytes, opts: dict):
        if not self._from_link(opts):
            self.log(f"{name} write from {self._peer(opts.get('device') or '?')}, not the link: "
                     f"{'dropped' if name == 'ota_data' else 'refused'}", "W")
            if name == "ota_data":
                return   # write without response
            raise att_error(0x0E)
        self._note_access(opts)
        if opts.get("offset", 0):
            # BlueZ hands a long write over in one piece at offset 0; NimBLE
            # refuses anything else as well.
            raise att_error(0x07)
        if name in PROTECTED and not self.dev.link_trusted:
            if name == "ota_data":
                # Write without response: nothing to answer, the chunk is just gone.
                self.log("ota_data write on an unauthenticated link dropped", "W")
                return
            # BlueZ only lets this through on an authenticated, encrypted link.
            self._link_trusted("protected write")
        rc = self.dev.write_char(name, value)
        if name != "ota_data" or rc:
            self._event("write", peer=self._link_addr, char=name, value=bytes(value[:32]).hex(),
                        size=len(value), att=rc)
        if rc:
            if opts.get("type") == "command":
                return   # no response to carry the error
            raise att_error(rc)

    def gatt_subscribe(self, char: Characteristic, on: bool):
        char.set_notifying(on)
        if self.link is None:
            return   # BlueZ clearing a gone peer's CCCDs
        subs = self._ccc.setdefault(self.link, set())
        (subs.add if on else subs.discard)(char.key)
        if self.dev.connected:
            self.dev.subscribe_char(char.key, on)
            if char.key == "ota_ctrl" and not on:
                # BlueZ sends an indication only while the client's CCCD is on and
                # never calls Confirm for one it did not send. One in flight now
                # would keep the device's queue busy until the next connection; on
                # the board NimBLE sends it anyway and the phone confirms it.
                self.dev.indication_confirmed()
        self._event("subscribe", peer=self._link_addr, char=char.key, on=on)

    def gatt_confirmed(self, char: Characteristic):
        if self.dev.connected:
            self.dev.indication_confirmed()

    # ---- pairing (agent) ----------------------------------------------------------------
    def agent_passkey(self, path: str, passkey: int, entered: int):
        if passkey == self._passkey:
            return   # a keypress notification for the same pairing
        self._passkey = passkey
        self._cancel_passkey_timer()
        self._passkey_timer = asyncio.get_running_loop().call_later(
            PASSKEY_TIMEOUT_S, self._passkey_expired)
        self.log(f"pairing with {self._peer(path)}: passkey {passkey:06d}")
        self._event("passkey", peer=self._peer(path), passkey=passkey)
        if self.dev.connected:
            self.dev.pairing_passkey(passkey)

    def agent_cancel(self):
        if self._passkey is not None:
            self.log("pairing cancelled", "W")
            self._pairing_failed()

    def _passkey_expired(self):
        self._passkey_timer = None
        if self._passkey is not None:
            self.log("pairing timed out", "W")
            self._pairing_failed()

    def _cancel_passkey_timer(self):
        if self._passkey_timer is not None:
            self._passkey_timer.cancel()
            self._passkey_timer = None

    def _pairing_failed(self):
        self._passkey = None
        self._cancel_passkey_timer()
        self._event("pairing_failed", peer=self._link_addr)
        if self.dev.connected:
            self.dev.encryption_changed(False)

    def _encryption_on(self, authenticated: bool, why: str):
        """The link is encrypted: tell the device (once per link)."""
        if self._encrypted_reported:
            return
        self._encrypted_reported = True
        self._passkey = None
        self._cancel_passkey_timer()
        self.log(f"link encrypted ({why}){'' if authenticated else ', not authenticated'}",
                 "I" if authenticated else "W")
        self._event("security", peer=self._link_addr, authenticated=authenticated, why=why)
        if self.dev.connected:
            self.dev.encryption_changed(True, authenticated=authenticated)

    def _link_trusted(self, why: str):
        self._encrypted_reported = False   # report the authenticated state now
        self._encryption_on(True, why)

    def _on_encrypted(self):
        """The kernel reports the link encrypted."""
        if self._passkey is not None:
            # Encryption only starts once the passkey checked out. The kernel often
            # reports it before BlueZ sets Paired: _on_paired must still know.
            self._passkey_checked = True
            self._encryption_on(True, "paired with passkey")
        elif self.devices.get(self.link, {}).get("Paired"):
            bond = self.bonds.get(self._link_addr, {})
            self._encryption_on(bond.get("authenticated", True), "bond")
        # Otherwise a Just Works pairing is running: _on_paired decides.

    def _on_paired(self, path: str):
        auth = self._passkey is not None or self._passkey_checked
        self._pair_auth[path] = auth
        addr = self._peer(path)
        self.bonds[addr] = {"authenticated": auth}
        self._save_bonds()
        self.log(f"paired with {addr} ({'passkey' if auth else 'Just Works'})")
        self._event("paired", peer=addr, authenticated=auth)
        self._encryption_on(auth, "paired with passkey" if auth else "Just Works")

    # ---- links --------------------------------------------------------------------------
    def _on_message(self, msg: Message):
        if msg.message_type != MessageType.SIGNAL:
            return
        try:
            if msg.member == "PropertiesChanged" and msg.body[0] == "org.bluez.Device1":
                self._device_changed(msg.path, unwrap(msg.body[1]))
            elif msg.member == "InterfacesAdded":
                path, ifaces = msg.body[0], unwrap(msg.body[1])
                if "org.bluez.Device1" in ifaces and path.startswith(self.adapter_path + "/"):
                    self.devices[path] = {}
                    self._device_changed(path, ifaces["org.bluez.Device1"])
            elif msg.member == "InterfacesRemoved":
                path, ifaces = msg.body
                if "org.bluez.Device1" in ifaces:
                    if path == self.link:
                        self._on_disconnected(path, "device removed")
                    self.devices.pop(path, None)
                    self._ccc.pop(path, None)
            elif msg.member == "Disconnected" and msg.interface == "org.bluez.Device1" \
                    and msg.path == self.link:
                self._disc_reason = str(msg.body[0])
        except Exception as e:   # a bad signal must not stop the bus reader
            self.log(f"signal {msg.member}: {type(e).__name__}: {e}", "E")

    def _device_changed(self, path: str, changed: dict):
        props = self.devices.setdefault(path, {})
        was_paired = props.get("Paired", False)
        props.update(changed)
        if "Connected" in changed:
            if changed["Connected"]:
                self._on_connected(path)
            elif path == self.link:
                self._on_disconnected(path, self._disc_reason)
        if path == self.link and changed.get("Paired") and not was_paired:
            self._on_paired(path)
        elif path == self.link and "Address" in changed:
            self._link_addr = changed["Address"]   # identity address learnt from the IRK

    def _is_ours(self, path: str) -> bool:
        """Whether a new connection is a central connecting to us (and not
        one of the PC's own links)."""
        props = self.devices.get(path, {})
        if self.hci:
            return self.hci.peripheral_link(props.get("Address", "")) is not None
        return self.dev.advertising and "Class" not in props

    def _on_connected(self, path: str):
        if self.link is not None or not self._is_ours(path):
            return
        self.link = path
        self._link_addr = self._peer(path)
        self._encrypted = False
        self._encrypted_reported = False
        self._disc_reason = ""
        self._passkey = None
        self._passkey_checked = False
        self._cancel_passkey_timer()
        name = self.devices[path].get("Alias", "")
        self.log(f"connected: {self._link_addr} {name}".rstrip())
        self._event("connected", peer=self._link_addr, name=name)
        if not self.dev.connect():
            self.log("device not accepting connections: link dropped", "W")
            self._spawn(self._disconnect(path))
            return
        # BlueZ kept this bonded peer's CCCDs; NimBLE restores them as well.
        for name in sorted(self._ccc.get(path, ())):
            self.dev.subscribe_char(name, True)
        self._poll_task = self._spawn(self._poll_link(path))

    def _on_disconnected(self, path: str, reason: str):
        self.link = None
        if self._poll_task:
            self._poll_task.cancel()
            self._poll_task = None
        self._passkey = None
        self._cancel_passkey_timer()
        props = self.devices.get(path, {})
        if not props.get("Bonded"):
            self._ccc.pop(path, None)    # BlueZ drops a non-bonded peer's CCCDs
        self.log(f"disconnected: {self._link_addr}{f' ({reason})' if reason else ''}")
        self._event("disconnected", peer=self._link_addr, reason=reason)
        self.dev.disconnect()
        if self._pair_auth.get(path) is False and props.get("Paired"):
            # Only passkey bonds are kept (ble_service.c on_enc_change).
            self._pair_auth.pop(path, None)
            self._spawn(self._remove_bond(path, "unauthenticated bond deleted"))

    async def _poll_link(self, path: str):
        """Watches the kernel's view of the link for encryption."""
        if self.hci is None:
            if self.devices.get(path, {}).get("Paired"):
                await asyncio.sleep(ASSUME_ENCRYPTED_S)
                if self.link == path and self._passkey is None:
                    self._on_encrypted()
            return
        while self.link == path and not self._encrypted:   # stays encrypted until the link goes
            conn = self.hci.peripheral_link(self._link_addr)
            if conn and conn["encrypted"]:
                self._encrypted = True
                self._on_encrypted()
            await asyncio.sleep(POLL_S)

    async def _disconnect(self, path: str, quiet=False):
        try:
            await self.call(path, "org.bluez.Device1", "Disconnect")
        except DBusError as e:
            if not quiet:
                self.log(f"disconnect {self._peer(path)}: {e.text}", "W")

    async def _remove_bond(self, path: str, why: str):
        addr = self._peer(path)
        try:
            await self.call(self.adapter_path, "org.bluez.Adapter1", "RemoveDevice", "o", [path])
            self.log(f"{why}: {addr}", "W")
        except DBusError as e:
            self.log(f"removing bond {addr}: {e.text}", "W")
        if self.bonds.pop(addr, None) is not None:
            self._save_bonds()

    async def _clear_bonds(self):
        """Factory reset: forget the bonds made with the simulator (and no others)."""
        await asyncio.sleep(0.5)   # after the link is gone
        for path, props in list(self.devices.items()):
            if props.get("Address") in self.bonds and props.get("Paired"):
                await self._remove_bond(path, "bond deleted (factory reset)")
        if self.bonds:
            self.bonds = {}
            self._save_bonds()

    # ---- device events ------------------------------------------------------------------
    def _on_device_event(self, name: str, data: dict):
        if name in ("notify", "indicate"):
            self.chars[data["char"]].send(data["value"])
        elif name == "advertising":
            self._want_adv(data["on"])
        elif name == "disconnect_request":
            if self.link:
                self.log(f"dropping the link ({data['reason']})")
                self._spawn(self._disconnect(self.link))
        elif name == "clear_bonds":
            self._spawn(self._clear_bonds())
        elif name == "conn_params":
            # A peripheral cannot ask for connection parameters through BlueZ.
            self._event("conn_params", peer=self._link_addr, fast=data["fast"])

    # ---- advertising --------------------------------------------------------------------
    def _want_adv(self, on: bool):
        if self._adv_slow_timer:
            self._adv_slow_timer.cancel()
            self._adv_slow_timer = None
        self._adv_mode = "fast" if on else None
        if on:
            self._adv_slow_timer = asyncio.get_running_loop().call_later(ADV_FAST_S, self._adv_slow)
        self._adv_wake.set()

    def _adv_slow(self):
        self._adv_slow_timer = None
        if self._adv_mode == "fast":
            self._adv_mode = "slow"
            self._adv_wake.set()

    async def _adv_loop(self):
        """Brings BlueZ's advertisement in line with _adv_mode, one call at a time."""
        mgr = "org.bluez.LEAdvertisingManager1"
        registered_mode = None
        while True:
            await self._adv_wake.wait()
            self._adv_wake.clear()
            want = self._adv_mode if self._started else None
            if not self.adv.registered:
                registered_mode = None
            if want == registered_mode:
                continue
            try:
                if self.adv.registered:
                    self.adv.registered = False
                    await self.call(self.adapter_path, mgr, "UnregisterAdvertisement", "o", [ADV_PATH])
                if want:
                    self.adv.interval = ADV_FAST_MS if want == "fast" else ADV_SLOW_MS
                    await self.call(self.adapter_path, mgr, "RegisterAdvertisement", "oa{sv}",
                                    [ADV_PATH, {}])
                    self.adv.registered = True
                registered_mode = want
                if want:
                    self.log(f"advertising as {self.adv.local_name} ({want})")
                else:
                    self.log("advertising stopped")
                self._event("advertising", on=bool(want), mode=want)
            except DBusError as e:
                self.log(f"advertising: {e.type}: {e.text}", "E")
                registered_mode = None
                await asyncio.sleep(1)
                self._adv_wake.set()


async def adapter_address(bus: MessageBus, adapter="hci0") -> str:
    """The adapter's Bluetooth address (the device name takes its last 2 bytes)."""
    reply = await bus.call(Message(destination=BLUEZ, path=f"/org/bluez/{adapter}",
                                   interface="org.freedesktop.DBus.Properties", member="Get",
                                   signature="ss", body=["org.bluez.Adapter1", "Address"]))
    if reply.message_type == MessageType.ERROR:
        raise RuntimeError(f"no Bluetooth adapter {adapter}: {reply.error_name}")
    return reply.body[0].value
