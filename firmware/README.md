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
  `main`, `ui`). They are applied in name order after `sdkconfig.defaults`.
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
calibration, settings and JSON, the power state machine, OTA protocol, BLE
codec, PMU and IMU decoding, UI logic) is tested on the PC with Unity (and,
for the OTA hash, mbedtls) from the IDF tree. `test_host/stubs` stands in for
the few ESP-IDF headers the driver code includes:

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
taps on a dimmed screen, the settings debounce, and the calibration flows.

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
| `sensor` (main/sensor_task.c) | 10, core 1 | Every 20 ms (50 Hz): drains the IMU FIFO, applies the accel calibration, runs fusion, computes the level reading and publishes it; feeds the calibration collectors. |
| LVGL (esp_lvgl_adapter) | 6 | The UI. Reads `app_state` snapshots about 15 times a second. |
| `control` (main/control.c) | 5 | Executes app commands (from the UI and BLE). Polls the AXP2101 every 200 and 500 ms. Runs the power policy. |
| NimBLE host | IDF default, core 0 | GATT, pairing, OTA writes |
| esp_timer | IDF default | BLE notifications (10 Hz), the OTA timeout, the reboot after OTA |

The UI and BLE never touch the sensor, the PMU or the hardware directly.
They read `app_state` snapshots and post commands, which the control task
executes. The sensor task owns the fusion filter, the level state and the
calibration collectors. The control task reaches them only through
`sensor_*()` calls, which take a mutex. The sensor task holds that mutex only
while it runs one batch of samples through the filter, never during I2C.

**main**: the boot sequence (`app_main.c`). It reads the wake reason first.
After the deep-sleep timer fires, it only powers off. Otherwise it brings up
app_state, then settings/NVS, then the PMU (so the charger is configured
early), the display and brightness, the IMU, the sensor task, the UI, BLE
(which also creates OTA), and the control task. If the image is new after an
OTA update, it runs the OTA self-test last. `sensor_task.c` and `control.c`
are described above.

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

**app_core**: the shared snapshot. Each producer has one setter, and every
consumer gets a consistent copy. The control task also publishes which
calibrations the stored settings hold, so BLE never reads the settings for
its status packet. Also the command queue, the activity timestamp and the
common millisecond clock. Thread-safe, never used from an ISR.

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
LPF, FIFO in stream mode.
- Samples lost while the FIFO is in read mode are found with the TIMESTAMP
  counter and filled in by interpolation. This keeps the fusion dt fixed.
- The real sample rate is measured against esp_timer.
- If leaving FIFO read mode fails, the next read retries it first. If no new
  samples arrive for 200 ms (failed reads, a stuck FIFO, or the chip reset
  itself), the sensor task treats the IMU as lost and re-initialises it, so a
  frozen orientation is never shown as a valid reading.
- Also: wake-on-motion on INT2 (GPIO21) and a self-test.
- The chip-to-board axis map is in `imu.c`.

**power**:
- `power_fsm`: the pure ACTIVE / DIM / SLEEP / OFF state machine,
  host-tested. It takes the activity timestamp as an input, arms the
  low-battery power-off 5 s after it starts, and provides the idle time the
  calibration timeout uses too.
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
- Overlays show the BLE passkey, OTA progress and low battery.
- A touch that wakes a dimmed screen only brightens it, on every screen:
  it is dropped at the input layer before any widget sees it.
- Pure helpers (formatting, scaling, steppers) are in `ui_logic.c` and are
  host-tested.

**ble**: a NimBLE peripheral. It has DIS, battery and the DrillBalancer
service (angle, config, command, status), plus the OTA service.
- Pairing uses LE Secure Connections with a passkey shown on screen, and
  bonds are stored in NVS. Only passkey-authenticated bonds with a 16-byte
  key are kept: a Just Works pairing is not stored, never evicts the owner's
  bond when the store is full, and cannot replace it on a repeated pairing.
- A factory reset also deletes all bonds.
- A link that is neither authenticated nor subscribed to anything is dropped
  after 60 s. Only an authenticated link with a write in the last 10 minutes
  keeps the device out of deep sleep.
- `ble_codec.c` builds and parses the packets and holds the link policy
  checks, and is host-tested.

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

You can read the angle, status and config without pairing. Config writes,
commands and OTA need a bonded link. A factory reset deletes all bonds, so
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
| ACTIVE | Boot, or any activity: touch, BLE write, turning the device faster than 0.3 rad/s, USB plugged in or out, PWR key | Brightness `bright` (default 100 %) |
| DIM | No activity for `dim_s` (default 30 s) | Brightness `dim_bright` (default 20 %); the UI keeps running |
| SLEEP | No activity for `sleep_s` (default 120 s), and no OTA, calibration, authenticated BLE link with a write in the last 10 min, or USB power | BLE off, display at brightness 0, unused rails off, IMU wake-on-motion (150 mg), ESP32 deep sleep. Motion wakes the device (it reboots). After `off_min` (default 60 min) a timer wake powers it off. |
| OFF | Battery below 5 %, not charging and no USB power, even if the charger pauses (armed 5 s after start-up); a PWR key long press; or the BLE power-off command | AXP2101 power-off. The PWR key turns the device on again. |

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

Every `TODO(hw)` in the tree is covered below, with its file. Grep for
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
14. **LPF** (`imu.c`, gyro 24 Hz / accel 12 Hz). Tune it on a running drill:
    the reading should stay steady without lagging too much.
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
      and a factory reset.
