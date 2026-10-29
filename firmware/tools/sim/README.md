# DrillBalancer device simulator

A dummy DrillBalancer that runs on a Linux PC. To the Android app it looks
like the real board. Use it to test the app before (or without) the hardware.

- **Firmware logic:** the device runs the firmware's own hardware-independent
  C code, compiled for the host into `core/build/libdbsim.so`: level math,
  fusion, both calibrations, settings/JSON, the OTA protocol, the BLE codec,
  the power state machine, the UI logic, the IMU frame decoding and FIFO gap
  handling, the recorder (`rec_fsm`, `rec_ring`, `rec_stream`, `rec_ts`) and
  the AXP2101 register decoding. `core/sim_core.c` stands in for the ESP glue
  (`control.c`, `sensor_task.c`, the FIFO read of `imu.c`, `ble_service.c`,
  `ota_update.c`, `power.c`, and the recorder's `recorder.c`, `rec_wifi.c`,
  `rec_audio.c`), with the same constants. `core/sim_audio.c` makes the
  microphones' sound.
- **Python layer** (`dbsim/`):
  - `physics.py`: a virtual drill with its IMU, and `DrillScenario`, a
    scripted drilling session with its ground truth.
  - `device.py`: the device clock and its tasks.
  - `ble.py`: the BLE peripheral on BlueZ (D-Bus). It provides the
    firmware's GATT table, the advertisement and a DisplayOnly pairing agent.
  - `recorder.py`: the training-data stream server (TCP on the LAN).
  - `web/`: the control panel.
- **Persistent state:** settings, calibration, the OTA-installed version,
  the sim's bond records and the recorder's ground truth
  (`rec_truth_<take>.json`) are kept in `state/`.

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
| `--rec-host`, `--rec-port` | the PC's LAN address, `47100` | the recorder's stream server (see [Recorder](#recorder)) |
| `--rec-ap`, `--rec-ap-ifname` | off | serve the stream on a Wi-Fi hotspot instead (NetworkManager) |
| `--rec-ring-kib` | `512` | the recorder's stream ring (the board has 4096) |
| `--no-rec` | | no stream server |
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
- the crosshair's dot and the yaw line, the battery, charging and BLE icons;
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
- the passkey and OTA overlays swallow touches;
- during a take the REC pill takes touches only while it reads
  "REC mm:ss" (held 2 s it stops the take, and that press opens no
  settings); as "REC ready" it is a label and a tap goes to what lies
  under it. The press rules are in `dbsim/web/touch.js`.

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
- **Recorder:** the session and take state, the stream (address, client,
  kB/s), the ring and its drops, the IMU and audio counters, the running
  scenario and the take's ground truth. Controls: the session as the app
  would drive it over BLE (open, start a take with a profile and the mics,
  stop, mark, close; for runs without Bluetooth, the endpoint and token
  are shown), start a drilling scenario (drill or hammer, holes,
  breakthrough / retract / mixed, stuck, kickback, seed), stall the stream
  for N seconds, drop the client, and the microphones' sensitivity. See
  [Recorder](#recorder).
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
# a drilling scenario, and the ground truth of the running (or last) take
curl -s -XPOST localhost:8765/api/cmd -d '{"cmd":"scenario","mode":"hammer","holes":2,"seed":7}'
curl -s localhost:8765/api/rec/truth
```

The recorder commands: `rec` (`op` open|close|take_start|stop|mark; for
take_start `profile` LEVEL|RAW448|RAW448_16G|RAW897, `mic_mask`, `gain_db`,
`max_s`, `take_id`; for mark `code`, `text`), the app's rec_ctrl writes,
checked by the device as a BLE write is (the session's endpoint and token
are in `GET /api/state` under `snap.recorder.session`); `scenario` (`mode`
drill|hammer, `holes` 1..20, `outcome` breakthrough|retract|mixed, `stuck`,
`stuck_exit` restart|pull, `kickback`, `seed`, `free_hz`, `sag`, `spinup_s`,
`free_s`, `drill_s`, `gap_s`, `lead_s`, `level`; or `"stop": true`),
`rec_stall` (`s` 0.1..60), `rec_client_drop` and `mic_gain` (`db` -40..40,
optional `mic` 1|2).

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
5. *Training data* records from the sim as from the board (see
   [Recorder](#recorder)). In LAN mode the phone must be on the same network
   as the PC and reach its TCP port 47100 (or the one given with
   `--rec-port`); with `--rec-ap` the app joins
   the PC's hotspot as it would join the board's soft-AP. Start a drilling
   scenario on the panel during a take to give the auto-labeller something
   to find.

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
- **Forgotten on the phone only:** the PC keeps its half of the bond, and
  BlueZ asks a bonded phone to encrypt as soon as it connects. The phone
  no longer has the keys, so Android shows "Pair with DrillBalancer-XXXX?"
  on every connection and the app cannot reconnect (the board never asks).
  The sim log points this out after a few seconds (not when the kernel's
  connection list is unavailable); run `bluetoothctl remove <phone
  address>` on the PC, then pair again from the app.

## Recorder

The training-data recorder runs as on the board: the app writes `rec_ctrl`
(OPEN, TAKE_START, STOP, MARK, CLOSE), reads and subscribes `rec_state`,
connects to the stream and saves the records (format:
`components/recorder/include/rec_stream.h`).

**LAN mode (the default).** The board opens a Wi-Fi soft-AP; the simulator
serves the stream from the PC instead, on its LAN address and port 47100.
The address is found by asking the kernel which source address it would
use towards the network, which sends nothing and changes no setting.
`rec_state` then carries the flag `NO_AP`, an empty SSID and passphrase,
that address and port, and the session token: the app connects over the
network the phone is on, with no join dialog. The client sends HELLO (the
16-byte token, then u16 version 1) within 5 s, or the connection is closed;
one client at a time. A client the device takes gets the HELLO_OK record
first (the app waits for it before a take). A firewall on the PC must let the phone reach that TCP
port; `--rec-host 127.0.0.1` keeps the stream on the PC (scripts, tests).
The address must be IPv4 (`rec_state` carries 4 bytes): a name is served
on its IPv4 address. A server that cannot start (an IPv6 address, the port
in use) is a warning: the simulator runs on, and every OPEN fails with
`ap_fail` (the panel says why). The device's timeouts (the HELLO's 5 s, a
send blocked 10 s, a stall, STOP_AP's 200 ms, the 1 s linger) run on the
device's clock: real time in `python -m dbsim`, faster in tests
(`DbSimDevice.run(speed)`).

**How a session ends**, as on the board: CLOSE, the BLE grace, sleep,
power-off and reboot let the sender drain the ring for 200 ms, then the
stream ends with a FIN and the client gets 1 s to read up to it and close
its side (on the board lwIP would lose what it still holds if Wi-Fi went
down right after the close; a client should close on EOF, as `db_client`
does); only then is the endpoint down. Until it is, the session keeps the
device: an OPEN is refused (`REC_STATE`) even when `rec_state` already
says idle. After sleep, power-off or reboot no session opens until the next
boot. OTA, a calibration and a session exclude each other: a calibration
start over BLE answers `BUSY` (0x86) while a session or an update has the
device, and the panel's guides say why.

**Hotspot mode (`--rec-ap`).** For the app's soft-AP join without the board:
at OPEN the sim runs `nmcli device wifi hotspot` with the session's
credentials (SSID `DB-<mac4>-<rand4>`, a 20-character passphrase, channel 6)
and serves the stream on the hotspot's address; CLOSE takes the hotspot
down. This changes the PC's Wi-Fi: the interface leaves its network for the
session.

**Scenarios and ground truth.** A drilling scenario (panel, or
`DbSimDevice.start_scenario()`) drives the IMU and the microphones from one
motor state: off, spin-up, free run (233 Hz by default), cutting (the motor
sags about 20 % (`sag`, 0.05..0.4, ±15 % per hole), more broadband, the axial
push), breakthrough (the speed jumps
back, a 1.5-2 g lunge along the bit and a wobble of the bit that swings
back within about 0.1 s) or retract (a slow recovery, no
impulse), spin-down and off, per hole. The hammer mode adds 5-10 g blows at
about 45 Hz while cutting, each followed by the housing's slower swing
back, so that they average out (a held drill does not drift; they clip a
±4 g take, RAW448_16G keeps them);
*stuck* stalls the first hole (the motor stops in the hole, restarts and
pulls out; with `stuck_exit` `pull` the bit is pulled out with the motor
off and the motor then runs free), *kickback* twists the drill about the
bit where it catches.
Seeded: the same arguments give the same session. Its ground truth uses
the dataset contract's names (events `motor_on`, `contact`, `breakthrough`,
`retract`, `motor_off`, `kickback`, the span `stuck`, holes) plus the
scenario's states, in device time (esp_timer µs, like the stream). A
scenario stopped early (Stop, or another one started) ends there in the
truth too: nothing after the stop, a `motor_off` at it when the motor was
running (in the hole if the bit was in one), and `stopped_us` on that
scenario. It is
written to `state/rec_truth_<take>.json` when a take ends and served at
`GET /api/rec/truth` while it runs; it never goes into the stream.

**What the stream carries**, as the firmware design has it:
- IMU records from the active profile's raw FIFO frames, one per timestamp
  run. The simulated QMI8658 loses the samples that arrive while a FIFO
  read is in progress (about 12 % at 448 Hz, 20 % at 897 Hz) and its
  TIMESTAMP counts them, so the driver's real gap code runs: the level
  pipeline gets the interpolated floats, the recorder the runs. A profile
  switch or an IMU re-init resets the chip (180 ms without samples,
  TIMESTAMP from 0, `REC_IMU_EPOCH`), and EVENT PROFILE comes before the
  first IMU record of the profile. Fusion and the level zeros (also the
  WALL heading) go through a profile switch; a measured sample rate stays
  in use across it.
- AUDIO: 16 kHz stereo blocks of 256 frames. The sound is the motor's
  harmonics, the cutting noise and the impact bursts of the scenario; MIC2
  hears it 3 frames later and 1.5 dB quieter. The take's `gain_db` is the
  ES7210's gain.
- LEVEL at 10 Hz, STATS at 1 Hz, and the EVENTs: TOUCH (a touch on the
  panel's screen during a take, which the UI otherwise ignores), MARK,
  BLE_LOST/BACK, IMU_LOST/BACK, VIB_START/STOP, PROFILE.
- END with the reason: STOP or CLOSE, `max_s`, the 120 s BLE grace, the
  battery below 8 %, the IMU lost for 5 s, sleep / power-off / reboot. A
  lost client gets no END; the session waits for it again.

The ring is 512 KiB (`--rec-ring-kib`), so a stall fills it within seconds:
*stall the stream* stops sending, and a stall of 10 s or more (device
time) loses the client 10 s into it, as a blocked send does on the board. Records that do not fit are
dropped and counted, and their seq numbers stay used. A client that stops
reading backs the stream up the same way, once its own receive buffer and
23 KB on the PC side (the board's TCP send buffer) are full: with the
app's 256 KB receive buffer that is about 3.5 s of stream, so it is lost
about 14 s after it stopped reading, on the board as here.

**Pulling a take on the PC:** `tools/ble/db_client.py rec pull --start
--duration 20 -o take.dbrec` (over BLE to the sim). Without Bluetooth
(`--no-ble`), the panel plays the app's part:

```sh
./run.sh --no-ble --rec-host 127.0.0.1
curl -s -XPOST localhost:8765/api/cmd -d '{"cmd":"rec","op":"open"}'
curl -s localhost:8765/api/state | jq .snap.recorder.session    # ip, port, token
../ble/db_client.py rec pull --host 127.0.0.1 --port 47100 --token TOKEN -o take.dbrec &
curl -s -XPOST localhost:8765/api/cmd -d '{"cmd":"rec","op":"take_start","profile":"RAW448","max_s":20}'
```

The pull ends with the take's END. Then
`tools/dataset`'s `python -m dbdata.dbrec export take.dbrec ROOT
--recording-id ID --session-id SID` writes a dataset recording.
`tools/dataset`'s `dbdata.synth` does the same in process (no BLE, no
network): it plays scenarios on a `DbSimDevice`, exports each take and
writes its ground truth as the labels, for the auto-detector's tests and
golden fixtures (`DrillPhysics.audio_ambient` and the sensor noise set how
loud the room is).

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
- **Recorder:** no Wi-Fi soft-AP of its own: the stream is served on the
  LAN, or on a NetworkManager hotspot with `--rec-ap` (see
  [Recorder](#recorder)); the stream ring is smaller than the board's 4 MB;
  the sensor clock and the host clock run at exactly the nominal rates; the
  microphones' sound is synthetic (`core/sim_audio.c`), so the auto-detector's
  thresholds tuned on it still need real takes.
- **Not simulated:**
  - the post-OTA self-test and rollback;
  - the fuel gauge (the battery is what the panel says);
  - lever-arm acceleration;
  - the accelerometer's low-pass filter, by default. The vibration detector
    therefore sees the motor tones (147 and 233 Hz) in full in a LEVEL take
    or without a session, where the board's 11.9 Hz filter removes most of
    them. Under a RAW recording profile the firmware's software stand-in for
    the chip's LPFs (`components/level/include/lpf.h`) filters the level
    path, here as on the board, so there the detector does not see them
    either. `Core.chip_lpf(True)` (`sim_chip_lpf()`) models the chip's LPFs
    under LEVEL with the same filter, which the tests use to compare a RAW
    take with a LEVEL one. The gyro vibration is white noise, 0.1 rad/s
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

The tests run without Bluetooth and change no network setting: the core and
physics, every characteristic and command, calibration, power, OTA, the BLE
layer (with BlueZ calls recorded, plus one private D-Bus daemon), the web
panel, and the recorder (`test_recorder.py`: stream continuity and counted
drops, the FIFO losses, IMU and audio rates, every END reason, `rec_state`
in LAN mode, the stream server and `db_client`'s pull over loopback TCP).
The packets are decoded with `tools/ble/db_client.py`, which was written
against the C structs independently of the simulator. One recorder test
exports a take with `tools/dataset`'s venv (`dbdata.dbrec` and
`dbdata.validate`, through `tests/dataset_probe.py`) and checks that the
breakthrough shows in the IMU and in the audio within 5 ms of the ground
truth; it is skipped without `tools/dataset/.venv`. `tools/ci/simulator.sh`
sets that venv up and builds the core first.

The app's opt-in `SimulatorEndToEndTest` records takes from a running sim
through the panel's API, with no Bluetooth (see
[app/README.md](../../../app/README.md#against-the-simulators-recorder-opt-in)).
