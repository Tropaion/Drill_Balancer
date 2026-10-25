# DrillBalancer device simulator

A dummy DrillBalancer that runs on a Linux PC. To the Android app it looks
like the real board. Use it to test the app before (or without) the hardware.

- **Firmware logic:** the device runs the firmware's own hardware-independent
  C code, compiled for the host into `core/build/libdbsim.so`: level math,
  fusion, both calibrations, settings/JSON, the OTA protocol, the BLE codec,
  the power state machine, the UI logic, the IMU frame decoding (so samples
  are quantised and clipped like the QMI8658's) and the AXP2101 register
  decoding. `core/sim_core.c` stands in for
  the ESP glue (`control.c`, `sensor_task.c`, `ble_service.c`,
  `ota_update.c`, `power.c`), with the same constants.
- **Python layer** (`dbsim/`):
  - `physics.py`: a virtual drill with its IMU, at 448 Hz.
  - `device.py`: the device clock and its tasks.
  - `ble.py`: the BLE peripheral on BlueZ (D-Bus). It provides the
    firmware's GATT table, the advertisement and a DisplayOnly pairing agent.
  - `web/`: the control panel.
- **Persistent state:** settings, calibration, the OTA-installed version and
  the sim's bond records are kept in `state/`.

## Setup

What you need:
- Linux with BlueZ 5.x (tested with 5.87) and `bluetoothd` running.
- An adapter that supports the LE peripheral role.
- Python 3.10 or newer (tested with 3.14).
- cmake, ninja and a C compiler. ESP-IDF's cmake and ninja under
  `~/.espressif/tools` are found automatically.
- ESP-IDF itself at `~/esp/v5.5.1/esp-idf`, for cJSON and SHA-256.

```sh
cd firmware/tools/sim
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

`run.sh` does these two steps itself the first time it runs. The C core
builds itself on first use, and again whenever a firmware source (or a
`test_host/stubs` header) is newer than the library. To build it by hand
(this finds ESP-IDF's cmake and ninja too):

```sh
.venv/bin/python -c 'from dbsim import core; print(core.build_lib(quiet=False))'
```

With cmake and ninja on `PATH`, plain
`cmake -S core -B core/build -G Ninja && cmake --build core/build` works as
well.

No root is needed: the default D-Bus policy lets a desktop user talk to
BlueZ.

## Start

```sh
./run.sh                    # BLE on hci0 + web panel on http://127.0.0.1:8765
./run.sh --no-ble           # device and web panel only (no Bluetooth)
.venv/bin/python -m dbsim -h
```

| Flag | Default | Meaning |
|---|---|---|
| `--adapter` | `hci0` | BlueZ adapter |
| `--state-dir` | `tools/sim/state` | settings, calibration, OTA image, bond records |
| `--no-web` / `--no-ble` | | leave out the panel / Bluetooth |
| `--host`, `--web-port` | `127.0.0.1`, `8765` | panel address. The panel has no login: keep it on localhost. |
| `-q` | | print only passkeys, warnings and errors |

The terminal prints:
- passkeys, in a box;
- the firmware, BLE and simulator log;
- state changes and OTA progress.

Ctrl+C drops the link, unregisters from BlueZ and puts the adapter back as
it was (alias, pairable, powered).

While the sim runs, the adapter's alias is `DrillBalancer-XXXX`. BlueZ's GAP
name is the alias, and the PC's other Bluetooth devices see that name too.
After a crash, reset it with `bluetoothctl system-alias ''`.

## Web panel

Open <http://127.0.0.1:8765>.

**Left: the screen.** A live copy of the round 466x466 screen, drawn from
the firmware's state the way `components/ui` draws it:
- the wall, floor and angle views, with green, amber and white from the same
  status flags;
- the yaw bar, the battery, charging and BLE icons;
- the low-battery banner and the ZERO toast;
- the calibration guides;
- the pairing passkey and OTA progress overlays;
- dim (lower brightness); sleep and off (black).

Mouse or touch input on the copy follows the firmware's rules:

| Gesture | Action |
|---|---|
| Tap | zero |
| Swipe left / right | next / previous mode |
| Hold 1 s | settings |

As on the device:
- a press that wakes a dimmed screen does nothing else;
- the passkey and OTA overlays swallow touches.

Settings screen: the list scrolls (drag or mouse wheel). Its action buttons
work: *Calibrate mount*, *Calibrate sensor*, *Clear zero*, *Factory reset*
and *Back*. Its steppers only show the current values; change settings from
the app.

**Right: the controls.**
- **Orientation:** sliders and number boxes for elevation (-90..90°), yaw
  and roll about the bit. Presets: wall 0°, floor (bit down), ceiling (bit
  up), 45°.
- **Drill:** a *drill running* vibration toggle with intensity, a
  *detected* / *quiet* pill for what the firmware's vibration detector reads,
  and the noise level. From about 0.06 g the detector reads it as running
  (about 1.5 s after the toggle), and it counts as activity for up to 3 min
  after the last touch or movement, as on the board. Vibration alone, steady
  or switched on and off, does not keep the sim awake: it dims and sleeps
  3 min later than without it, and then the 150 mg wake on motion reboots
  it at once (the van case). The firmware's turn detector low-passes the
  gyro, so its vibration noise is no turn, while moving the drill counts
  with the drill running, right after switching it off, or out of sleep.
- **Calibration helpers:**
  - the six table poses, which light up as the sensor calibration captures
    them;
  - *roll in hole*: rolls back and forth for the mount calibration;
  - the mount offset, and accelerometer bias and scale errors;
  - the corrections the firmware found.
- **Power:**
  - battery % and the USB, charging and charge-done toggles;
  - PWR key: short press = sleep, hold = off. The firmware ignores the key
    for 2 s after a boot;
  - *motion nudge*: wakes the device from deep sleep.
- **Faults:**
  - IMU lost;
  - BUSY on the next N commands;
  - drop N OTA indications;
  - slow flash: a delay per OTA write; writes past 24 buffers are lost;
  - the settings store fails;
  - OTA flash writes fail;
  - disconnect now.
- **Status:**
  - power and screen state;
  - the phone's address, paired/bonded, encryption, MTU and subscriptions;
  - firmware version;
  - OTA progress with KB/s;
  - the stored settings (read-only).
- **Event log**, newest first: connects, pairing, GATT writes with decoded
  commands, the ATT errors returned, subscriptions, and the firmware log.
  Filters by source.

For scripts: `POST /api/cmd` takes the same JSON commands the page sends,
and `GET /api/state` returns one state frame. The command names and their
arguments are in `dbsim/web/server.py`. Every value is range-checked; a bad
command gets a 400 with the reason. Requests from other web pages get a 403:
the server takes only a browser Origin that is its own address, and a Host
that is an IP address or `localhost`, so no site in your browser can drive
the sim. Scripts send no Origin and are not affected.

```sh
curl -s -XPOST localhost:8765/api/cmd -d '{"cmd":"pose","elevation":12.5}'
curl -s -XPOST localhost:8765/api/cmd -d '{"cmd":"busy","n":2}'
# as if changed on the device's own settings screen (e.g. to provoke a 0x82 in the app)
curl -s -XPOST localhost:8765/api/cmd -d '{"cmd":"settings","set":{"tol":3,"near":3}}'
```

## Using it with the app

1. Start the sim (`./run.sh`). It advertises as `DrillBalancer-XXXX`, where
   XXXX is the last two bytes of the adapter's address. It advertises fast
   for 30 s, then slow.
2. In the app, scan and tap `DrillBalancer-XXXX`. Live angles, status and
   settings work without pairing.
3. The first write asks you to pair, as with the board (LESC, MITM, the
   device displays a code). When you tap *Pair*:
   - the 6-digit passkey appears on the panel's screen, in a banner under
     it, and in the terminal;
   - type it into Android's pairing dialog.

   A pairing where no passkey was shown (Just Works) stays unauthenticated,
   and that bond is deleted when the phone disconnects, as on the board.
4. Move the sliders and watch the app follow. OTA works with a real
   `build/drillbalancer.bin`. The image is checked like `esp_ota_end()` does,
   and the sim then reboots into the new version.

**Forget device on the phone:**
- **After a factory reset** (device menu, panel or app): the sim removes the
  BlueZ bonds it made, as the board forgets its bonds. Remove
  `DrillBalancer-XXXX` in the phone's Bluetooth settings and pair again. The
  app asks you to do this.
- **After deleting `tools/sim/state`:** settings and calibration go back to
  defaults, but BlueZ keeps the bond (in `/var/lib/bluetooth`). The sim no
  longer knows it made that bond, so a later factory reset will not remove
  it. To start clean on both sides:
  - on the PC, run `bluetoothctl remove <phone address>`;
  - on the phone, forget the device.

## Differences from the real board

- **Radio and timing:** the PC's adapter and BlueZ settle connection
  parameters, MTU, PHY and data length with the phone. A BLE peripheral on
  BlueZ cannot request connection parameters, so the firmware's fast/slow
  request is only logged. OTA throughput and notification timing therefore
  differ from the board.
- **Address:** the sim uses the PC adapter's public address, which never
  changes. That address is also the one the PC uses for its own Bluetooth
  devices. The phone caches the GATT table per address.
- **Bond store:** bonds live in BlueZ's store, next to the PC's own
  devices. The sim removes only the bonds it made itself.
- **Pairing:**
  - A wrong or cancelled passkey is not reported over D-Bus. The passkey
    screen clears after 35 s or on disconnect.
  - After a wrong passkey the PC's kernel drops the link about 2 s later
    (the phone sees 0x13); the board keeps it. After a cancelled dialog
    Android drops the link itself, with the board as well.
  - There is no 0x0C for short keys (that is BlueZ's `KeySize`, which needs
    root).
  - A write on an unencrypted link from a bonded phone gets 0x05 instead of
    0x0F. The app treats both as "pair first".
- **GATT:**
  - BlueZ adds its own GAP/GATT characteristics.
  - BlueZ puts its own Device Information service (PnP ID only) ahead of
    the firmware's. The app reads the strings from whichever instance has
    them. Setting `DeviceID = false` under `[General]` in
    `/etc/bluetooth/main.conf` removes it (needs root and a BlueZ restart).
  - The service UUID goes in the advertising data and the name in the scan
    response, the reverse of the board. Android scans actively and gets
    both.
  - A disconnect made by the device arrives at the phone as 0x13.
- **Not simulated:**
  - the post-OTA self-test and rollback;
  - the fuel gauge (the battery is what the panel says);
  - lever-arm acceleration;
  - the accelerometer's low-pass filter. The vibration detector therefore
    sees the motor tones (147 and 233 Hz) in full, where the board's 11.9 Hz
    filter removes most of them. The gyro vibration is white noise, 0.1 rad/s
    per g on each axis: 3 % of the samples pass 0.3 rad/s at 1 g and half of
    them at 2 g, which the turn detector must reject. The intensity slider
    is a stand-in for what reaches the detectors on the board, not for a
    real drill: the sim proves the detectors' logic and the power policy,
    not the 0.05 g and 0.3 rad/s thresholds (bring-up step 14 tunes those);
  - the settings steppers on the screen.

## Tests

```sh
.venv/bin/python -m unittest discover -s tests
```

The tests run without Bluetooth: the core and physics, every characteristic
and command, calibration, power, OTA, the BLE layer (with BlueZ calls
recorded, plus one private D-Bus daemon) and the web panel. The packets are
decoded with `tools/ble/db_client.py`, which was written against the C
structs independently of the simulator.
