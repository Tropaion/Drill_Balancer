"""python -m dbsim: the simulated DrillBalancer as a BLE peripheral on BlueZ,
with the web control panel (dbsim/web/, http://127.0.0.1:8765 by default).

The device runs in real time. Passkeys, BLE events and the firmware log are
printed here; Ctrl+C drops the link, unregisters from BlueZ and restores the
adapter settings (alias, pairable, powered).
"""

import argparse
import asyncio
import signal
import sys
import traceback
from pathlib import Path

from .device import DEFAULT_MAC, DbSimDevice

_LEVEL_TAG = {"I": " ", "W": "!", "E": "E"}


def _printer(quiet: bool):
    """Prints device and BLE events to the terminal."""
    last_ota = [-1]

    def out(text):
        print(text, flush=True)

    def on_event(name, data):
        if name == "passkey" and data["pending"]:
            code = f"{data['passkey']:06d}"
            out(f"\n  ************************************\n"
                f"  *  PAIRING PASSKEY:  {code[:3]} {code[3:]}       *\n"
                f"  *  type it into the phone's dialog *\n"
                f"  ************************************\n")
        elif name == "log":
            if quiet and data["level"] == "I":
                return
            out(f"{_LEVEL_TAG.get(data['level'], ' ')} [{data['source']:>3}] {data['msg']}")
        elif name == "state":
            out(f"* device {data['state']} ({data['reason']})")
        elif name == "boot":
            out(f"* firmware {data['version']} running ({data['reason']})")
        elif name == "power" and not quiet:
            out(f"* power {data['state']}")
        elif name == "ota":
            pct = data["percent"] if data["active"] else -1
            if pct == -1 or pct // 10 != last_ota[0] // 10:
                out(f"* OTA {data['state']} {pct}%" if data["active"] else f"* OTA {data['state']}")
            last_ota[0] = pct
    return on_event


async def run(args) -> int:
    loop = asyncio.get_running_loop()
    stop = asyncio.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)

    bus = None
    mac = DEFAULT_MAC
    if not args.no_ble:
        from dbus_fast import BusType
        from dbus_fast.aio import MessageBus
        from .ble import BluezPeripheral, adapter_address
        bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
        mac = await adapter_address(bus, args.adapter)

    dev = DbSimDevice(state_dir=args.state_dir, mac=mac, boot=False)
    ble = None if args.no_ble else BluezPeripheral(dev, args.adapter, bus=bus)
    dev.subscribe(_printer(args.quiet))
    print(f"DrillBalancer simulator: {dev.name} ({dev.mac}), state in {dev.state_dir}", flush=True)
    dev.power_on()
    runner = asyncio.create_task(dev.run())

    web = None
    try:
        if ble is not None:
            await ble.start()
        if not args.no_web:
            from . import web as web_mod
            web = await web_mod.start(dev, ble, args.host, args.port)
            print(f"web UI: http://{args.host}:{args.port}/", flush=True)
        stopper = asyncio.create_task(stop.wait())
        await asyncio.wait({runner, stopper}, return_when=asyncio.FIRST_COMPLETED)
        stopper.cancel()
        if runner.done() and runner.exception() is not None:
            # The device clock died (a bug): say so instead of freezing.
            traceback.print_exception(runner.exception())
            return 1
    except Exception as e:   # BlueZ refused something: report it plainly
        print(f"error: {type(e).__name__}: {e}", file=sys.stderr, flush=True)
        return 1
    finally:
        print("stopping...", flush=True)
        if web is not None:
            await web.close()
        if ble is not None:
            await ble.stop()
        if not runner.done():
            dev.stop()
            await runner
        if bus is not None:
            bus.disconnect()
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="python -m dbsim", description=__doc__.split("\n\n")[0])
    p.add_argument("--adapter", default="hci0", help="BlueZ adapter (default hci0)")
    p.add_argument("--state-dir", type=Path, default=None,
                   help="settings, calibration, OTA image and bonds (default tools/sim/state)")
    p.add_argument("--no-web", action="store_true", help="no web UI")
    p.add_argument("--no-ble", action="store_true", help="no Bluetooth: device (and web UI) only")
    p.add_argument("--host", default="127.0.0.1", help="web UI address (default 127.0.0.1)")
    p.add_argument("--web-port", "--port", dest="port", type=int, default=8765,
                   help="web UI port (default 8765)")
    p.add_argument("-q", "--quiet", action="store_true", help="only passkeys, warnings and errors")
    return asyncio.run(run(p.parse_args(argv)))


if __name__ == "__main__":
    sys.exit(main())
