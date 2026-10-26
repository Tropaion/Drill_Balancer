# DrillBalancer firmware

A clip-on drill level. The device sits on the back of the drill with its round
screen facing you; the bit points along -Z of the board. It shows the drill's
elevation and yaw (wall mode), a bubble (floor and ceiling mode), or the error
against a target angle (angle mode). Over BLE it streams the angles, takes
settings and commands, and accepts firmware updates.

Hardware: Waveshare ESP32-S3-Touch-AMOLED-1.75-B (ESP32-S3R8, 16 MB flash,
8 MB octal PSRAM, CO5300 466x466 AMOLED, CST9217 touch, QMI8658 IMU, AXP2101
PMIC, 1000 mAh LiPo). ESP-IDF 5.5.1, C11 (C++ only for XPowersLib).

> Nothing here has run on hardware yet. Work through the
> [bring-up checklist](#bring-up-checklist-hardware) when the board arrives.

## Build and flash

The commands are bash. In fish, wrap them in `bash -c '...'`:

```sh
bash -c 'cd firmware && . ~/esp/v5.5.1/esp-idf/export.sh >/dev/null 2>&1 && idf.py build'
bash -c 'cd firmware && . ~/esp/v5.5.1/esp-idf/export.sh >/dev/null 2>&1 && idf.py -p /dev/ttyACM0 flash monitor'
```

The console and flashing both use the native USB-C port (USB Serial/JTAG,
`/dev/ttyACM0`). If the port does not show up, hold BOOT while you press
RESET.

Configuration:

- `sdkconfig.defaults` holds the board-wide options (target, flash and
  partitions, PSRAM, caches, LVGL).
- `sdkconfig.d/*.defaults` holds one fragment per feature (`ble`, `board`,
  `main`, `recorder`, `ui`). They are applied in name order after
  `sdkconfig.defaults`.
- Defaults only apply to options that are not yet in `sdkconfig`. After you
  change a defaults file, delete `sdkconfig` (and `build/`) before you build
  again.
- The target (`esp32s3`) is pinned in `sdkconfig.defaults`. Without it, a
  fresh configure silently builds for the plain ESP32.

To build only some components (plus `main` and their dependencies) in your
own build directory:

```sh
DB_COMPONENTS="board;imu" idf.py -B build-x -D SDKCONFIG=build-x/sdkconfig build
```

The partitions are NVS (24 KB), otadata, phy_init and two 6 MB OTA slots.
Rollback is enabled: after an OTA update, the new image confirms itself only
after a self-test (display up, IMU self-test, PMU readable; the IMU and PMU
checks are retried for up to 5 s, so a transient I2C error at boot does not
count). Otherwise the bootloader goes back to the previous image.

## Host tests

The hardware-independent code (level math, fusion, mount and sensor
calibration, vibration detection, the software LPF, settings and JSON, the
power state machine, OTA protocol, BLE codec, PMU and IMU decoding, UI logic,
and the recorder's stream format, ring, session state machine, time keeping
and glue helpers) is tested
on the PC with Unity (and, for the OTA hash, mbedtls) from the IDF tree.
`test_host/stubs` stands in for the few ESP-IDF headers the driver code
includes:

```sh
export PATH=$HOME/.espressif/tools/cmake/3.30.2/bin:$HOME/.espressif/tools/ninja/1.12.1:$PATH
cmake -S test_host -B build-test-all -G Ninja
cmake --build build-test-all
ctest --test-dir build-test-all --output-on-failure
```

All test code builds with `-Wall -Wextra -Werror -Wdouble-promotion -Wshadow`.

### UI preview

`test_host/ui_preview` renders every screen to a PNG with the real UI
sources, then runs touch and flow checks: tap, swipe, long press, wake
taps on a dimmed screen, the settings debounce, the calibration flows, and
the REC pill (touches during a take, the 2 s hold that stops it).

```sh
cmake --build build-test-all --target ui_preview_png   # writes test_host/ui_preview/out/*.png
```

It prints `OK: 0 check failure(s)` when every check passes. The preview
renders 32-bit colour and the panel runs RGB565, so colours on the device
can band slightly differently.

### BLE client

`tools/ble/db_client.py` is a PC client for the whole GATT protocol (scan,
pair, stream, get/set config, commands, OTA). For setup and pairing on
Linux, see [tools/ble/README.md](tools/ble/README.md).
`python3 db_client.py --dry-run` and
`python3 -m unittest test_db_client` run without Bluetooth.

### Device simulator

`tools/sim` runs the firmware's hardware-independent C code on the PC as a
BLE peripheral on BlueZ, with a web panel. The panel has a live copy of the
round screen and controls for the drill pose, calibration errors, power and
faults. The Android app can't tell it from the board, so the app can be
tested without hardware. See [tools/sim/README.md](tools/sim/README.md).

### Android app

The Android companion app (live view, settings, calibration, firmware
update) is in [../app](../app/README.md). Its conformance tests read the
headers listed under [BLE protocol](#ble-protocol), so run them after a
protocol change.

## Architecture

Tasks:

| Task | Priority / core | Job |
|---|---|---|
| `sensor` (main/sensor_task.c) | 10, core 1 | Every 20 ms (50 Hz): drains the IMU FIFO, applies the accel calibration, runs fusion, computes the level reading and publishes it; feeds the calibration collectors, the vibration detector and the turn detector, and notes a turn as activity. Hands each read's raw frames to the recorder's tap, applies the IMU profile the recorder asks for, and under a RAW profile runs the level path on a filtered copy (`lpf.h`). |
| `rec_mic` (components/recorder/src/rec_audio.c) | 9, core 1 | Only during a take with microphones: reads the I2S DMA blocks (16 ms each) into AUDIO records. |
| LVGL (esp_lvgl_adapter) | 6 | The UI. Reads `app_state` snapshots about 15 times a second. |
| `control` (main/control.c) | 5 | Executes app commands (from the UI and BLE). Polls the AXP2101 every 200 and 500 ms. Runs the power policy. |
| `rec` (components/recorder/src/recorder.c) | 5 | The recorder's session/take state machine: rec_ctrl commands, Wi-Fi, TCP, BLE and IMU events, a 100 ms tick (timeouts, LEVEL records at 10 Hz, STATS at 1 Hz). Short steps only: a command is answered within milliseconds. |
| `rec_tx` (components/recorder/src/rec_wifi.c) | 4, core 0 | Brings the soft-AP up and down (the slow part, asked for by `rec`). The stream server on 192.168.4.1:47100: checks HELLO, sends the ring to the one client. |
| NimBLE host | IDF default, core 0 | GATT, pairing, OTA writes |
| Wi-Fi, lwIP (`tcpip`), event loop | IDF default | Only while a recording session is open |
| esp_timer | IDF default | BLE notifications (10 Hz), the OTA timeout, the reboot after OTA, the recorder tick |

The UI and BLE never touch the sensor, the PMU or the hardware directly.
They read `app_state` snapshots and post commands, which the control task
executes. Recorder commands (BLE rec_ctrl, the REC pill's stop) go to the
recorder's own queue (`recorder_post()`, `recorder_send()`), because a take
id and its parameters do not fit an app command. The sensor task owns the fusion filter, the level state and the
calibration collectors. The control task reaches them only through
`sensor_*()` calls, which take a mutex. The sensor task holds that mutex only
while it runs one batch of samples through the filter, never during I2C.

**main**: the boot sequence (`app_main.c`). It reads the wake reason first.
After the deep-sleep timer fires, it only powers off. Otherwise it brings up
app_state, then settings/NVS, then the PMU (so the charger is configured
early), the display and brightness, the IMU, the sensor task, the UI, BLE
(which also creates OTA), and the control task. The recorder starts before
the sensor task, which reports the IMU to it from its first pass. If the
image is new after an OTA update, it runs the OTA self-test last.
`sensor_task.c` and `control.c` are described above.

**level**: pure C, host-tested.
- `level_math`: vectors and quaternions.
- `fusion`: a Mahony filter. It learns the gyro bias while the device is
  still.
- `level`: the modes, zero, tolerance bands and the mount-calibration fit. The
  drill axis is the normal of a plane fitted through the "up" vectors, which
  lie on a cone while you roll the drill. `level_t` keeps its mode and clears
  the zero itself when it no longer applies (a FLOOR <-> WALL/ANGLE change, a
  new drill axis). The mount collector drops the first second and the last
  half second of a calibration (the Start and Finish taps).
- `accel_cal`: a six-position ellipsoid fit for the accelerometer bias and
  gain on each axis, and the stillness window that turns raw samples into
  poses.
- `vibration`: the running-motor detector on the raw accelerometer. A
  2nd-order high-pass (6 Hz) removes gravity and handling; a 0.5 s window is
  shaking when at least half its samples exceed 0.05 g. Three shaking windows
  in a row read RUNNING, four calm ones QUIET.
- `turn`: the turn detector on the bias-corrected gyro, for the power
  policy. A 2nd-order low-pass (3 Hz) keeps rotation sustained over a tenth
  of a second or more and averages out the motor's vibration; above
  0.3 rad/s the device is turning.
- `lpf`: the software stand-in for the QMI8658's LEVEL-profile LPFs (accel
  11.9 Hz, gyro 24.2 Hz; a 2nd-order Butterworth per axis, primed with the
  first sample). Under a RAW recording profile the chip's LPFs are off, so
  the sensor task runs fusion, level, both detectors and the calibration
  collectors on a filtered copy of every batch; the recorder gets the
  unfiltered frames. Without it the aliased motor tones and hammer blows
  tilt the reading by degrees (bring-up step 31 compares it with the chip).
  Fusion, the level zeros (also the WALL heading) and the detectors go
  through a profile switch (every RAW take starts and ends with one): only
  the decode scale and the filter change, and the filter goes on from the
  last sample fusion got (`lpf_imu_restart()`), so the reading does not
  jump. Only a lost IMU restarts fusion.

**app_core**: the shared snapshot. Each producer has one setter, and every
consumer gets a consistent copy. The control task also publishes which
calibrations the stored settings hold, so BLE never reads the settings for
its status packet. The motion part carries `vibrating`, the detector's
verdict. Also the command queue, the activity timestamp and the common
millisecond clock. Thread-safe, never used from an ISR.

**settings**: `settings_t` with defaults, validation (the `SETTINGS_*` limits
in `settings.h`, which the UI steppers share), a JSON form for the BLE
config characteristic (cJSON, host-tested) and NVS persistence
(`settings_store`: a versioned blob with a CRC, guarded by a mutex). Every
writer (BLE config writes, the UI save, the mode, the calibration results
and the factory reset) uses `settings_update()`, which does the
read-modify-write under the mutex, so two writers cannot overwrite each
other's fields. It stores only a result that differs from the current
settings. Every store posts `APP_CMD_SETTINGS_CHANGED`, and the control task
then reloads the level config, the mode, the accel calibration, the power
timeouts, the brightness and the BLE on/off state. That reload is the only
way a setting reaches the sensor task, so a mode change takes effect once it
is stored.

**board**: the AXP2101 through XPowersLib on the BSP's I2C bus. It sets up the
charger (50 mA precharge, 400 mA, 25 mA termination, 4.2 V, TS pin off), the
fuel gauge, the PWR key timings and the IRQs. It also has the status and
event polling (burst reads of the status, battery voltage and IRQ
registers), and switches off rails before sleep and power-off. Before sleep
and power-off it disables and clears the IRQs, so no event can hold the IRQ
line low while nothing polls it. The register decoding is host-tested; it
clamps the gauge to 0..100 % and treats a battery voltage below 2.5 V as no
battery, so every consumer can use the published values as they are.

**imu**: a register-level QMI8658 driver: ±4 g, ±512 dps at 448.4 Hz, on-chip
LPF, FIFO in stream mode (the LEVEL profile). The recording profiles turn the
LPFs off: RAW448 (±4 g), RAW448_16G (±16 g, for hammer drilling) and RAW897
(896.8 Hz, experimental). `imu_set_profile()` reconfigures the chip (about
180 ms, a new TIMESTAMP epoch); `imu_read_fifo_ex()` also returns a read's
raw frames with their timestamp runs.
- Samples lost while the FIFO is in read mode are found with the TIMESTAMP
  counter and filled in by interpolation. This keeps the fusion dt fixed.
- The real sample rate is measured against esp_timer. A rate measured once
  stays in use across a profile switch or a re-init (scaled to the new ODR),
  so FIFO losses are filled at once instead of after a new 2 s baseline.
- If leaving FIFO read mode fails, the next read retries it first. If no new
  samples arrive for 200 ms (failed reads, a stuck FIFO, or the chip reset
  itself), the sensor task treats the IMU as lost and re-initialises it, so a
  frozen orientation is never shown as a valid reading.
- Also: wake-on-motion on INT2 (GPIO21) and a self-test.
- The chip-to-board axis map is in `imu.c`.

**power**:
- `power_fsm`: the pure ACTIVE / DIM / SLEEP / OFF state machine,
  host-tested. It takes the activity timestamp and the vibration flag as
  inputs (vibration counts for at most 3 min after the last other activity),
  arms the low-battery power-off 5 s after it starts, and provides the idle
  time the calibration timeout uses too. An open recording session blocks
  sleep like OTA and a calibration do (the screen still dims).
- `power.c`: applies the states. Brightness goes through the BSP, under the
  display lock. Before deep sleep or power-off the touch controller gets its
  sleep command. Deep sleep is entered with IMU wake-on-motion on ext1 and an
  optional power-off timer. Power-off goes through the AXP2101.

**ui**: LVGL 9.
- The level screen has one view per mode. Tap zeroes, a swipe changes the
  mode, and a 1 s hold opens the settings.
- The other screens are the settings list and the two calibration guides.
  The settings list scrolls from anywhere on it; rows above and below the
  centred one narrow and fade so nothing is cut by the round panel.
- A settings edit still waiting for the 1 s save debounce is stored before
  sleep, power-off and reboot (also the reboot after an OTA update). Only
  the fields changed on the screen are written, so a BLE config write made
  meanwhile to another field is kept.
- Overlays show the BLE passkey, OTA progress and low battery, and the REC
  pill of a recording session over the mode title: "REC ready" between
  takes, red "REC mm:ss" during one. It is not modal. During a take every
  touch is logged in the recording (EVENT TOUCH) and does nothing else; only
  the pill takes it, and a 2 s hold on it stops the take.
- A touch that wakes a dimmed screen only brightens it, on every screen:
  it is dropped at the input layer before any widget sees it.
- Pure helpers (formatting, scaling, steppers) are in `ui_logic.c` and are
  host-tested.

**ble**: a NimBLE peripheral. It has DIS, battery and the DrillBalancer
service (angle, config, command, status), plus the OTA and recorder
services.
- Pairing uses LE Secure Connections with a passkey shown on screen, and
  bonds are stored in NVS. Only passkey-authenticated bonds with a 16-byte
  key are kept: a Just Works pairing is not stored, never evicts the owner's
  bond when the store is full, and cannot replace it on a repeated pairing.
- A factory reset also deletes all bonds.
- A link that is neither authenticated nor subscribed to anything is dropped
  after 60 s. Only an authenticated link with a write in the last 10 minutes
  keeps the device out of deep sleep.
- rec_ctrl writes go to `recorder_post()`, which answers once the recorder
  has taken them (milliseconds: Wi-Fi comes up and goes down in `rec_tx`).
  BUSY means it was not carried out, not even later. rec_state is readable and notified on an authenticated
  link only (it carries the Wi-Fi credentials and the stream token): on
  every recorder change and once a second during a take. A disconnect is
  BLE_LOST for the recorder (a take goes on for 120 s), an authenticated
  link BLE_BACK.
- `ble_codec.c` builds and parses the packets (also the recorder's LEVEL
  records, so they match the angle notifications) and holds the link policy
  checks, and is host-tested.

**recorder**: the training-data recorder. A session is one soft-AP and one
TCP connection (one Android approval dialog); it holds many takes, each
SESSION ... END on the stream.
- Pure and host-tested: `rec_stream` (the record format, `rec_stream.h` is
  the contract with the app and `tools/dataset`), `rec_ring` (the 4 MB PSRAM
  ring of whole records), `rec_fsm` (sessions, takes, faults and the action
  bitmask), `rec_ts` (TIMESTAMP extension, rate estimates) and `rec_util`
  (AP credentials, HELLO, audio channels, DMA block pairing). The simulator
  builds the same sources.
- `recorder.c`: the `rec` task, the command queue, the gated producers (the
  sensor tap, audio, events, LEVEL and STATS), the board id (a salted
  SHA-256 of the eFuse MAC; the MAC never leaves the device) and the
  firmware text with its git revision.
- `rec_wifi.c`: the soft-AP `DB-XXXX-YYYY` (WPA2, a new 20-character
  passphrase per session, channel 6, one station, RAM storage, torn down
  completely at the end) and the TCP server (HELLO with the session token,
  one client, a send blocked for 10 s or a station leaving loses it).
- `rec_audio.c`: its own RX-only I2S channel on the BSP pins (16 kHz, 16-bit
  stereo, MCLK 256 x fs), the ES7210 through `esp_codec_dev` with the take's
  microphones and gain, powered only during a take; a DMA timestamp per
  block.
- OTA, calibration and a session exclude each other through one arbiter,
  `app_excl_acquire()` in `app_state` (an atomic test and set): a session
  holds the device from OPEN until it is closed and Wi-Fi is down, OTA from
  its START until the transfer ends, a calibration until it ends. A
  calibration start while another one has it is refused where it was asked
  for: the BLE command write answers BUSY (0x86), the screen's guide says
  why; one that slips past both is refused by the control task, and the
  guide shows that too (`app_cal_status_t.refused`).
- Sleep, power-off and reboot call `recorder_shutdown()` first: a take ends
  with END, `rec_tx` drains the ring (200 ms), ends the stream with a FIN
  and gives the client up to 1 s to read up to it and close (lwIP would
  lose what it still holds if Wi-Fi went down right after the close), then
  takes Wi-Fi down. The call returns once that is done (bounded at about
  5 s, typically well under one), and no session opens after it until the
  next boot. A CLOSE and the BLE grace end the stream the same way.
- The sender leaves Nagle on and wakes once a few MSS are queued (or every
  20 ms), so a take goes out in MSS-sized segments; END is pushed at once
  (TCP_NODELAY up to it).

**ota**:
- `ota_proto`: the transport-independent update protocol (START, sequenced
  chunks, ACK/NACK, SHA-256, FINISH), host-tested against mbedtls' SHA-256
  from the IDF tree. The chunk size is set per connection from the ATT MTU
  (`ota_update_set_mtu()`: MTU - `OTA_DATA_OVERHEAD`) and fixed for a
  transfer at START.
- `ota_update`: writes the image with `esp_ota_*`, rejects images built for
  another project, and handles the rollback self-test.

### BLE protocol

The GATT layout, UUIDs, packet formats, commands and error codes are in
[components/ble/include/ble_protocol.h](components/ble/include/ble_protocol.h).
The OTA wire format is in
[components/ota/include/ota_proto.h](components/ota/include/ota_proto.h), and
the reference client is [tools/ble/db_client.py](tools/ble/db_client.py).

| Service | Characteristic | UUID | Properties | Security |
|---|---|---|---|---|
| Device Information | manufacturer, model, firmware revision | `0x180A` / `0x2A29`, `0x2A24`, `0x2A26` | read | open |
| Battery | battery level | `0x180F` / `0x2A19` | read, notify | open |
| DrillBalancer | angle | `d71b0002-…` | notify | open |
| | config | `d71b0003-…` | read, write | read open, write authenticated |
| | command | `d71b0004-…` | write | authenticated |
| | status | `d71b0005-…` | read, notify | open |
| OTA | ota_ctrl | `d71b0101-…` | write, indicate | authenticated |
| | ota_data | `d71b0102-…` | write without response | authenticated |
| Recorder | rec_ctrl | `d71b0201-…` | write | authenticated |
| | rec_state | `d71b0202-…` | read, notify | authenticated (read and notify) |

Authenticated means an encrypted link paired with the passkey and a 16-byte
key. You can read the angle, status and config without pairing. Config
writes, commands, OTA and the recorder need a bonded link. A factory reset deletes all bonds, so
every phone or PC has to pair again afterwards. The device advertises as
`DrillBalancer-XXXX` (the last 4 hex digits of the BT MAC).

## Calibration

Do the **sensor calibration first, then the mount calibration**. The mount
calibration measures the drill axis with the accelerometer, so it inherits
any accelerometer error. A successful sensor calibration therefore resets
the mount calibration (back to the nominal -Z axis), and its result screen
asks you to redo it (BLE status `cal_last_result` = -3). Without a mount
calibration to reset, the result is plain 0 and the screen just points to
the mount calibration as the next step.

If a calibration succeeds but cannot be saved, the result screen says
"Calibrated, but not saved" (BLE status `cal_last_result` = -2) and the old
calibration stays in use.

A calibration that nobody finishes is cancelled after `sleep_s` + 5 minutes
without activity, and one started over BLE also when that client
disconnects (the BLE service tells the control task at once), so it cannot
keep the device out of deep sleep.

### Sensor (six sides)

Settings → *Calibrate sensor*. It corrects the accelerometer's bias and gain
on each axis, like a phone's sensor calibration.

1. Put the device down on a flat, steady surface (off the drill) with one
   side facing up: screen up, screen down, top edge up, bottom edge up, left
   edge up, right edge up.
2. Hold each pose still for about a second. A pose counts once every axis
   stays within 0.02 g for 0.5 s. When a pose has been captured, its tile
   turns green. For "screen down", turn the device back over to check the
   tile.
3. Extra tilted poses are allowed and improve the fit. Poses closer than 20°
   to one already kept are ignored. At most 24 poses are kept.
4. *Finish* becomes active once all six sides are covered.

The fit fails if the bias is above 0.2 g or a gain is outside 0.9 to 1.1;
that would mean a faulty sensor.

### Mount (drill axis)

Settings → *Calibrate mount*. It measures how the device actually sits on
the drill, so a slightly crooked clip does not show up as an angle error.

1. Clip the device on the drill and put the bit into a drilled hole. The hole
   can be horizontal or tilted, but not vertical.
2. Tap *Start* once the bit is in the hole, then roll the drill slowly about
   the bit, at least 45° each way, while you keep the bit in the hole. The
   first second after *Start* and the last half second before *Finish* are
   not used, so the taps do not disturb the fit. *Retry* starts again at
   once.
3. Press *Finish*. The calibration fails if:
   - the roll was too small;
   - the bit moved (the samples do not lie on a cone);
   - the axis is more than 15° from -Z, which means the device is not seated
     properly.

Changing the drill axis or the sensor calibration clears the zero. So does
switching between floor mode and wall/angle mode: a zero taken with the bit
horizontal means nothing for the bubble, and one taken with the bit vertical
nothing for the elevation. Wall and angle mode share their zero. After an
IMU re-initialisation, the yaw reads invalid until you zero again.

## Power states

| State | Entered when | Behaviour |
|---|---|---|
| ACTIVE | Boot, or any activity: touch, BLE write, turning the device faster than 0.3 rad/s (low-passed gyro), USB plugged in or out, PWR key; a running drill (vibration) within 3 min of the last other activity | Brightness `bright` (default 100 %) |
| DIM | No activity for `dim_s` (default 30 s) | Brightness `dim_bright` (default 20 %); the UI keeps running |
| SLEEP | No activity for `sleep_s` (default 120 s), and no OTA, calibration, recording session, authenticated BLE link with a write in the last 10 min, or USB power | BLE off, display at brightness 0, unused rails off, IMU wake-on-motion (150 mg), ESP32 deep sleep. Motion wakes the device (it reboots). After `off_min` (default 60 min) a timer wake powers it off. |
| OFF | Battery below 5 %, not charging and no USB power, even if the charger pauses (armed 5 s after start-up); a PWR key long press; or the BLE power-off command | AXP2101 power-off. The PWR key turns the device on again. |

A running drill held still keeps the screen on. The sensor task's vibration
detector flags steady vibration, and the power FSM counts it as activity for
at most 3 min (`VIB_HOLD_MS` in `control.c`) after the last other activity.
A vibration credit does not extend that hold, so vibration alone never keeps
the device awake: put away in a running van, it dims at most 3 min + `dim_s`
and sleeps at most 3 min + `sleep_s` after it was last touched or moved.
- A running motor can shake the gyro past 0.3 rad/s sample by sample, so
  the turn detector (`components/level/src/turn.c`) low-passes the
  bias-corrected rate at 3 Hz first. Handling is a rotation sustained over a
  tenth of a second or more and passes; the motor's vibration (tens to
  hundreds of Hz, close to no net angle) averages out. A turn therefore
  counts whatever the accelerometer reads: carried or repositioned briskly,
  while the motor runs, straight after it stops, and right after start-up
  or an IMU restart (the filter starts at rest).
- A vehicle turning a corner turns the device as well and counts. A drive
  with a turn every few minutes can therefore keep it awake; step 14
  measures this.
- A wake on motion is a reboot and not activity, so it never arms the hold.
  Woken straight into drilling, the screen can still dim once; a tap fixes
  it.
- The low-battery power-off is checked first and is never delayed.

PWR key:
- A short press puts the device to sleep at once.
- Holding it for 1.5 s shuts down cleanly in software.
- Holding it for 4 s makes the AXP2101 cut the power in hardware.
- For the first 2 s after start-up the key does nothing, so releasing (or
  holding a little longer) the press that switched the device on cannot put
  it straight back to sleep or off.

In deep sleep the PWR key does not wake the ESP32 (its IRQ only reaches the
TCA9554), so either move the device or hold the key for 4 s and press it
again.

## Bring-up checklist (hardware)

Every `TODO(hw)` in the tree is covered below (36 steps), with its file. Grep for
`TODO(hw)` to find the code. Keep a serial monitor open
(`idf.py -p /dev/ttyACM0 monitor`) for all steps.

**Before power**

1. **Battery polarity.** Check it with a multimeter *before* you plug the
   cell in. Measure the cell's connector, then compare it with the BAT+/BAT-
   marking at the board's battery socket. LiPo connectors with the same
   shape come wired both ways. Reverse polarity destroys the AXP2101.
2. First power-up on USB without the battery. Flash it and check the log for
   these lines: `DrillBalancer v...`, the PMU line
   (`battery absent ... VBUS on`), `QMI8658 6DOF 448.4 Hz`, `boot done`, and
   no I2C errors.

**Power (AXP2101)**

3. **Rails.** Measure VCC3V3 (DCDC1) at 3.3 V and A3V3 (ALDO1) at 3.3 V.
   Check the rail table at the top of `components/board/src/pmu.cpp` against
   the schematic.
4. **Charging.** Connect the battery (after step 1) and USB. Use a USB meter,
   or an ammeter in series with the cell, to check that:
   - REG 0x61 reads 0x02 (50 mA precharge; the log says so if it does not);
   - the constant-current phase stays at no more than 400 mA (the cell allows
     500 mA);
   - charging stops at about 25 mA and 4.2 V;
   - the log, BLE status and UI show charging and charge-done;
   - the cell does not get warm.
5. **Fuel gauge at boot** (`main/control.c`, BATTERY_SETTLE_MS). Log
   `pmu_get_status()` for the first 10 s after a cold start and after a
   battery swap. The low-battery power-off is armed after 5 s. If the gauge
   reports nonsense for longer than that, raise the delay.
   `pmu.cpp` reads REG 00-01, 34-35 and 48-4A in bursts: check that the boot
   log's battery voltage matches a meter on the cell, and that the PWR key
   events of step 7 arrive.
6. **Power-off** (`components/power/src/power.c`, POWER_OFF_SETTLE_MS):
   - Measure how long VCC3V3 takes to drop after `pmu_power_off()`.
   - Check what happens with USB connected: does it stay off, or come back?
   - Check that the PWR key turns it on again.
7. **PWR key.** Check the short press (sleep), the 1.5 s hold (clean off) and
   the 4 s hold (hardware off). Power on with presses of about 0.5 s, 1.2 s
   and 2 s and check that the device stays on (`main/control.c`,
   PWRKEY_BOOT_IGNORE_MS; the log shows "ignored: power-on press").

**IMU (QMI8658)**

8. **Byte order** (`components/imu/src/imu.c`, CTRL1.BE). With the screen up
   and the device at rest, the raw Z word must be about +8192 (0x2000), not
   byte-swapped.
9. **FIFO burst** (`imu.c`, FIFO_DATA with ADDR_AI=0). The decoded FIFO
   samples must read about 1 g at rest.
10. **Data registers with the FIFO running** (`imu.c`, `imu_read`). The
    self-test must pass while the FIFO streams.
11. **Axis mapping** (`imu.c`, IMU_AXIS_MAP; also
    `components/ui/src/ui_logic.c`, the tile labels and bubble direction):
    - Accel: screen up gives +Z = +1 g, top edge up gives +Y = +1 g, right
      edge up gives +X = +1 g.
    - Gyro: turning the device counterclockwise (seen from the front) gives
      a positive gyro Z.
    - UI: in floor mode, lowering the right edge must move the bubble to the
      left. The calibration tiles must name the edge that is really up.
12. **FIFO gap filling** (`imu.c`, TIMESTAMP counting in read mode). Compare
    the logged count of interpolated samples with the samples read; expect
    about 10 %. Check that the measured rate is accepted, within 10 % of
    448.4 Hz. In normal running the log must never show `IMU lost: no new
    samples` (`main/sensor_task.c`, IMU_LOST_MS) or `FIFO read mode left on
    retry` (`imu.c`).
13. **Partial FIFO read** (`imu.c`). Check that the samples left unread stay
    queued. This only happens if the 64-sample batch is smaller than the
    FIFO content, for example after a stall.
14. **LPF, vibration and turn detectors** (`imu_frame.c` CTRL5_LEVEL, gyro
    24 Hz / accel 12 Hz; `components/level/src/vibration.c`, hp_hz 6 Hz /
    amp_g 0.05 g;
    `components/level/src/turn.c`, lp_hz 3 Hz / rate_rad_s 0.3 rad/s). Tune
    the LPF first, on a running drill: the reading should stay steady
    without lagging too much. The detectors only see what the LPFs pass, so
    tune them after the LPF, and again whenever it changes:
    - The sensor task logs `vibration running/quiet, duty NN %` on every
      change, and each 0.5 s window's duty and largest low-passed turn rate
      (`vibration duty NN %, turn rate max X.XX rad/s`) at debug level
      (`CONFIG_LOG_MAXIMUM_LEVEL_DEBUG`, then
      `esp_log_level_set("sensor", ESP_LOG_DEBUG)`).
    - Record the duty for: the drill held still at its lowest and highest
      speed, in wood and in masonry; idle on a bench; on a bench next to
      another running machine; picking it up, turning it, walking with it;
      hammering on the bench; a car ride in a toolbox.
    - Set amp_g so that the drill at its lowest speed gives a duty of at
      least 0.8 in nearly every window, and handling, walking and the bench
      0.2 or less. Keep hp_hz at 4 Hz or more (walking).
    - Too little drill signal: lower amp_g first (bench and handling still
      at 0.2 or less), then raise the aLPF one step if the steadiness still
      holds. A gyro channel would be a separate change.
    - The turn rate maximum must stay below 0.3 rad/s with the drill held
      still at every speed, in wood and in masonry, also while the motor
      starts and stops and in hammer mode; on a bench next to a running
      machine; and in a car going straight. If it does not, lower lp_hz
      (short flicks of the wrist stop counting below about 1.5 Hz) before
      raising rate_rad_s.
    - It must pass 0.3 rad/s at least every few seconds while the drill is
      carried, picked up and repositioned briskly between holes, without a
      touch: the screen must not dim during handling.
    - Release the trigger and move straight to the next hole: the move must
      count (no dim 3 min + `dim_s` later).
    - End to end: a 60 s hole held still does not dim; put away in a running
      vehicle, the device sleeps within 3 min + `sleep_s`. On a drive with
      turns, note whether and when it sleeps (a vehicle turning a corner is a
      real turn) and how often wake-on-motion wakes it.
15. **Gyro offset for the sensor calibration** (`accel_cal.h`,
    ACCEL_CAL_MAX_GYRO_RAD_S). Every pose of the six-side calibration must be
    accepted. If a pose never turns green, raise the limit.

**Sleep and wake**

16. **WoM polarity** (`components/imu/include/imu.h` IMU_WAKE_LEVEL,
    `components/power/src/power.c`):
    - In deep sleep, GPIO21 (INT2) must sit low and go high on motion.
    - INT2 must be push-pull. With open-drain, the RTC pull-down is the only
      pull.
    - The device must not wake straight away (a latched interrupt would
      cause a sleep/wake loop).
17. **WoM threshold** (`power.c` WAKE_ON_MOTION_MG = 150, `imu.c` 21 Hz with
    16-sample blanking). A light tap or picking the device up must wake it.
    A drill lying on a bench, or a bench being bumped, should not.
18. **ALDO1 in sleep** (`components/board/src/pmu.cpp`). Measure the sleep
    current with ALDO1 (A3V3, ES7210) on and off. If switching it off saves
    current without upsetting the I2C bus, switch it off after
    `imu_enable_wake_on_motion()`.
19. **Display in sleep** (`power.c`, display_off). The panel only goes to
    brightness 0; no SLPIN is sent because the BSP hides the panel handle.
    If the sleep current is too high, create the display with
    `bsp_display_new()` so that `esp_lcd_panel_disp_sleep()` can be called.
    **Touch in sleep** (`power.c`, touch_sleep): the CST9217 gets 0xD105
    (Hynitron's deep-sleep command) at 0x5A. Check that it is ACKed (no
    "Touch sleep command failed" in the log), measure the sleep current with
    and without it, and check that touch works after a motion wake. TP_RST is
    shared with the LCD reset, so it is not held low instead.
20. **IRQ lines in sleep** (`components/board/src/pmu.cpp`,
    pmu_prepare_sleep). In deep sleep, press PWR and plug/unplug USB:
    AXP_IRQ (TCA9554 P5) and the TCA9554 INT must stay high, and the sleep
    current must not step up.
21. **Current per state.** Measure on the battery side. Write the values down
    to estimate the runtime:
    - ACTIVE at 100 % brightness, with and without BLE connected
    - DIM at 20 %
    - deep sleep
    - power-off (should be µA)

**Display and UI**

22. **Visible area** (`components/ui/src/ui_priv.h`). The CO5300 must show
    the full inscribed circle, radius 233, centred. Nothing may be clipped
    at the rim. Check touch alignment with taps near the edge. In the
    settings list, the rows above and below the centred one must be fully
    visible and their buttons easy to hit; a drag that starts on a label or a
    gap must scroll.

**BLE and OTA**

23. Pair with `tools/ble/db_client.py pair`; the passkey must show on the
    screen. Then run `stream`, `get-config` and `set-config`. Also check:
    - a factory reset (`cmd factory-reset --yes`) drops the link and the PC
      has to pair again;
    - connecting with a scanner app and doing nothing: the link is dropped
      after 60 s, and the device still goes to sleep;
    - **repeated pairing** (`components/ble/src/ble_service.c`): forget the
      device on the phone and pair again; a cancelled or wrong passkey entry
      must only cost another pairing (NimBLE deletes the old bond before the
      new pairing has succeeded).
24. **OTA throughput** (`components/ble/src/ble_service.c`, ACL/msys
    buffers). Do a full OTA and watch for NACKs or resends while flash
    sectors are erased.
25. **Rollback.** OTA a good image: it must confirm itself (`image
    confirmed`) and survive a reboot. Then OTA an image whose self-test fails
    on purpose: it must roll back (after about 5 s: the IMU and PMU checks
    are retried that long, `main/app_main.c`, so a single I2C error at boot
    does not roll back a good image).
26. **Stack high-water marks.** Check `uxTaskGetStackHighWaterMark()` for:
    - the NimBLE host task (6144 bytes, `ble_service.c`), after a config
      write and a full OTA;
    - the sensor task (4096, `main/sensor_task.c`), after a sensor
      calibration and an IMU re-init;
    - the control task (6144, `main/control.c`), after a calibration finish
      and a factory reset;
    - the recorder's tasks (`components/recorder/src/recorder.c`): `rec`
      (4096) after a session with a take and a close, `rec_tx` (4096,
      `rec_wifi.c`) after a long take, `rec_mic` (3072, `rec_audio.c`) after
      a take with both microphones.

**Recorder (training data)**

Use `tools/ble/db_client.py rec open`, `rec start`, `rec stop`, `rec pull`
(see [tools/ble/README.md](tools/ble/README.md)) and export a pulled
`.dbrec` with `python -m dbdata.dbrec export` in `tools/dataset`.

27. **Microphones** (`components/recorder/include/rec_stream.h` REC_MIC_*,
    `components/recorder/src/rec_audio.c`, mic mask and gain):
    - Tap next to each microphone in turn (`rec start --mics 3`): the tap
      must be loudest in its own channel, MIC1 the left one (channel 0 of
      `audio.flac`). Swap the channel order in `rec_audio.c` otherwise.
    - Record a running drill in wood and in masonry at 0, 6, 12 and 18 dB.
      Keep the STATS clip count at 0 with some headroom and make that the
      app's default gain (6 dB now).
    - The microphones are only powered during a take: no MCLK on GPIO42 and
      no ES7210 current between takes.
28. **Audio clock and DMA** (`rec_audio.c`). Over a 5 min take with both
    microphones the STATS audio rate is within 0.1 % of 16 000 Hz, the DMA
    overflow count stays 0 while the AP sends, and the AUDIO sample indices
    have no jumps.
29. **IMU-audio offset** (`rec_audio.c`, the block timestamp). Tap the housing
    hard with the motor off, a few times: the impulse shows in `imu.csv.gz`
    and `audio.flac` once both are on the device clock (the sync fit). Note
    the fixed offset (the ES7210's filter delay) for the dataset's
    alignment constant.
30. **RAW profiles** (`components/imu/src/imu_frame.c` CTRL5_RAW and the
    profile table, `components/imu/include/imu.h` epoch):
    - With the LPFs off, look at the spectrum of a running drill for tones
      folded down by the 448.4 Hz sampling, in RAW448.
    - Hammer drilling in RAW448: does ±4 g clip (STATS, the samples at full
      scale)? If not, RAW448_16G is not needed.
    - RAW897: measure the FIFO loss (STATS imu_lost against imu_frames,
      about 25 % expected) and decide whether the profile stays.
    - After a profile switch TIMESTAMP must restart from 0 (the first IMU
      record of the new epoch).
    - The STATS imu_period_ns of a RAW897 take is half that of a RAW448
      take within 0.1 % (`imu_frame.h` imu_rate_restart() scales a measured
      period to the new ODR on that assumption).
    - In WALL mode with a zero set, a RAW take must leave the yaw valid and
      where it was (fusion goes through the switch).
31. **LPF stand-in** (`components/level/include/lpf.h`). With the drill held
    still, record the same pose in a LEVEL take and in a RAW448 take, motor
    running: the LEVEL records must agree within about 0.2°, a quick 20°
    tilt must lag alike, and the vibration detector (step 14) must decide
    the same in both. Fit the filter to the chip otherwise.
32. **Soft-AP and the phone** (`components/recorder/src/rec_wifi.c`). The app
    joins `DB-XXXX-YYYY` after one approval dialog, the phone keeps its
    mobile data, the log shows `stream client connected`; a second phone
    cannot join; walking out of range during a take ends it (CLIENT_LOST)
    and the session waits for the phone again; CLOSE ends with
    `soft-AP down`. Lost without a word (AP_INACTIVE_S, KEEPALIVE_*):
    switch the phone to flight mode in READY: `stream client lost` within
    about 11 s, and another phone can join within 30 s; a phone left idle
    in READY for 5 min (screen off) must stay connected.
33. **Throughput and coexistence** (`rec_wifi.c` channel 6,
    `sdkconfig.d/recorder.defaults`). A 5 min RAW448 take with both
    microphones at 1, 5 and 10 m: note the kB/s, STATS ring high-water and
    dropped records (none expected). The BLE angle notifications must stay
    at 10 Hz meanwhile (no seq gaps). Try another channel if either is poor.
    ESP-IDF rates a soft-AP with a connected station next to a BLE
    connection "C1: supported but the performance is unstable"
    (`api-guides/coexist.rst`), so a poor result here is possible. It also
    advises the Wi-Fi task and the BT controller and host on different
    cores; all three are on core 0 now. Repeat the take with
    `CONFIG_ESP_WIFI_TASK_PINNED_TO_CORE_1=y` (the Wi-Fi task, prio 23,
    then preempts the sensor task: watch the IMU loss and the angle
    stream) and, separately, with `CONFIG_BT_CTRL_PINNED_TO_CORE_1=y` and
    `CONFIG_BT_NIMBLE_PINNED_TO_CORE_1=y`, and keep the best.
34. **Internal heap** (`components/recorder/src/recorder.c`, the log line
    `AP up: internal heap ... free, largest block ...`, and `rec_audio.c`,
    `rec_mic: internal heap ... free, largest block ...` when a take's
    microphones come on, 8 KB of I2S DMA more). Note both. Keep at least
    20 KB free; otherwise lower CONFIG_ESP_WIFI_STATIC_TX_BUFFER_NUM or the
    recorder's stacks.
35. **Current and heat while recording.** Measure on the battery side with
    the AP up and a take running (BLE connected, both microphones), and the
    case temperature after a 20 min take.
36. **Session end and shutdown** (`rec_wifi.c` wifi_down(), `rec_glue.h`
    REC_WIFI_SETUP_MS, `recorder.c` recorder_shutdown()). With
    `rec pull` running, end a RAW448 take with both microphones by CLOSE, by
    walking the phone's BLE out of range for 2 min (the grace) and by a
    short PWR key press: each pull must end with END and the log must show
    `soft-AP down in N ms` without `did not close`. Note the `soft-AP ...`
    start and the `down` times and the `shut down in N ms` line; keep
    REC_WIFI_SETUP_MS above the slowest start or teardown seen. A rec_ctrl
    write must be answered within a few ms while the AP comes up or goes
    down (db_client's timing).
