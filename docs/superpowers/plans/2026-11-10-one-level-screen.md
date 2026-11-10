# One Level Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the three level modes with one screen that shows the crosshair or the bubble depending on how the board sits, and remove the target angle and every way to pick a mode.

**Architecture:** The level core (`firmware/components/level`) picks the view from the drill axis's absolute elevation, with hysteresis, and keeps it in `level_t.mode`. Everything downstream (angle packet, LEVEL records, device screen, app, simulator) already follows `level_reading_t.mode`, so the behaviour change is mostly in the core. After it, the target angle and the mode selection come out of the settings, the BLE protocol and the app in cross-language tasks. The app's conformance tests parse the firmware headers, so each protocol change lands on both sides in the same task.

**Tech Stack:**
- Firmware: C (ESP-IDF 5.5.1, LVGL 9) with Unity host tests (`firmware/test_host`).
- Simulator: C core (`firmware/tools/sim/core/sim_core.c`) driven by Python `unittest`.
- App: Kotlin/Compose with JUnit and Roborazzi.
- Dataset and PC tools: Python (`firmware/tools/dataset`, `firmware/tools/ble/db_client.py`).

**Spec:** `docs/superpowers/specs/2026-11-10-one-level-screen-design.md`

## Global Constraints

- **View thresholds:**
  - The bubble (`LEVEL_MODE_FLOOR`) when the bit is within 35° of vertical: |elevation| ≥ 55°.
  - The crosshair (`LEVEL_MODE_WALL`) when the bit is more than 55° from vertical: |elevation| < 35°.
  - In between, the current view stays.
  - With no view yet: the bubble if |elevation| ≥ 45°, else the crosshair.
- The view uses the absolute elevation of `level_config_t.drill_axis`, mount calibration included, never the zero-relative one.
- With no fresh IMU reading, the view stays as it is.
- The bubble measures the board's face (−Z), without the mount calibration. The crosshair measures the drill axis.
- Zero only in the crosshair view (1 s hold, BLE `DB_CMD_ZERO`, app button). In the bubble view it is refused and nothing changes. There is no bubble zero.
- Titles: `WALL` for the crosshair, `LEVEL` for the bubble.
- `LEVEL_MODE_ANGLE` stays in `level_mode_t` as reserved value 2 and is never produced. `dbrec.py` checks the enum names against `["wall", "floor", "angle"]`.
- `DB_CMD_SET_MODE` (0x03) is removed. The opcode stays reserved, and a write gets `DB_ATT_ERR_UNKNOWN_CMD` (0x84).
- The config JSON no longer has `mode` or `target`. When an older app writes them, they are ignored: unknown keys are already skipped.
- `settings_t` keeps its layout and size. The two fields are renamed `legacy_target_deg` and `legacy_mode`, keep what was stored and are never used, so an OTA rollback still loads the blob with its calibrations.
- `meta.json` `level`: `mode` is the first LEVEL row's view, `target_deg` is 0. No schema change, and no edit under `firmware/tools/dataset/dbdata/`.
- **Update order:** the app first, then the firmware over the air from the new app. An older app can't connect to the new firmware.
- Commit messages are plain sentences, as in `git log`, ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Nothing is pushed before Task 6.

## Review Focus

- **Vibration near a threshold:** the motor shakes the elevation by a few degrees around 45° or 55°. The view changes at most once and does not flicker. Test: Task 1 `test_view_ignores_jitter_inside_the_band`.
- **IMU lost while the bubble shows:** the angle packet and the screen keep `mode` = FLOOR. They don't fall back to WALL. Test: Task 1, simulator `test_view_kept_while_imu_lost`.
- **Older app writes `{"mode":"angle","target":30}`:** the write succeeds (no ATT error), the stored settings don't change, and the JSON read back has neither key. Test: Task 4 `test_settings_json_ignores_mode_and_target` (host) and the simulator config test.
- **Settings blob from the old firmware with mode ANGLE and target 30:** it loads with both calibrations, and saving another setting writes the two legacy fields back unchanged. Test: Task 3 `test_legacy_fields_survive_a_save`.
- **New app against old firmware that reports ANGLE (2):** the live view shows the wall view, Zero is enabled, and nothing crashes. Test: Task 4 `angleFromOldFirmwareShowsTheWallView`.

---

### Task 1: The level core picks the view

**Files:**
- Modify: `firmware/components/level/include/level.h`, `firmware/components/level/src/level.c`
- Modify: `firmware/main/sensor_task.c` (zero, view update, apply settings), `firmware/main/control.c` (the `APP_CMD_ZERO` log line)
- Modify: `firmware/components/settings/src/settings.c` (`settings_level_config()` no longer copies `target_deg`)
- Modify: `firmware/tools/sim/core/sim_core.c` (the same glue as `sensor_task.c`)
- Test: `firmware/test_host/test_level.c`; simulator tests in `firmware/tools/sim/tests/test_commands.py` and `test_web.py` that expect SET_MODE or a swipe to change `reading.mode`

**Interfaces:**
- Produces:
  - `level.h`:
    ```c
    #define LEVEL_VIEW_FLOOR_DEG 55.0f   // |elevation| at or above: the bubble
    #define LEVEL_VIEW_WALL_DEG  35.0f   // |elevation| below: the crosshair
    #define LEVEL_VIEW_FIRST_DEG 45.0f   // with no view yet: the bubble at or above
    bool level_update_view(level_t *lv, quat_t q);              // true if lv->mode changed
    bool level_zero(level_t *lv, quat_t q, uint32_t now_ms);     // false (nothing changed) in the bubble view
    ```
  - `level_t` gains `bool view_set` and loses `zero_bubble_x_deg` and `zero_bubble_y_deg`. `level_t.mode` is the view in use.
  - `level_config_t` loses `target_deg`.
  - `level_set_mode()` is removed.
  - `level_zero_of()` writes `{zero elevation, 0, 0}`.
  - `level_mode_t` comments: WALL is the crosshair view, FLOOR the bubble view, ANGLE reserved and never used. Values unchanged.
  - `sensor_zero()` returns false when the fusion isn't ready or the bubble shows.

- [ ] **Step 1: Write the failing tests in `test_level.c`**

  Delete the tests that switch modes or use the target: `test_angle_mode_measures_against_target`, `test_init_starts_in_wall_mode`, and every `test_*_cleared_when_switching_*` / `test_zero_kept_*`.

  Floor and ceiling tests get the view first with `level_update_view(&lv, q)`. Helpers: `pose_floor()` = `quat_identity()` (screen up, bit down), and `pose_ceiling()` = `rot_deg(AXIS_X, 180)`. Elevations come from `pose_wall_aimed(elev, 0)`.

  ```c
  test_view_first_pick_is_the_nearer_one:
      update(pose_wall_aimed(-46,0)) -> true, lv.mode == FLOOR   (fresh lv)
      fresh lv: update(pose_wall_aimed(-44,0)) -> lv.mode == WALL
  test_view_switches_to_floor_at_55:
      from WALL: update(aimed(-54.9)) -> false, WALL; update(aimed(-55)) -> true, FLOOR
  test_view_switches_back_to_wall_below_35:
      from FLOOR: update(aimed(-35)) -> FLOOR; update(aimed(-34.9)) -> true, WALL
  test_view_counts_bit_up_as_vertical:
      update(aimed(+80)) -> FLOOR; update(pose_ceiling()) -> FLOOR, reading on target
  test_view_ignores_jitter_inside_the_band:
      from WALL, 40 updates alternating aimed(-52) / aimed(-58) -> exactly one change;
      then alternating aimed(-38) / aimed(-32) -> exactly one more
  test_zero_refused_in_the_bubble_view:
      update(pose_floor()); level_zero(...) == false; lv.zero_set == false
  test_wall_zero_survives_a_bubble_trip:
      WALL, zero at aimed(0,0); update(pose_floor()); update(aimed(0,0));
      reading at aimed(2, 5): zero_set, elevation 2 (+-0.01), yaw_valid, yaw -5 (+-0.01)
  test_bubble_reads_the_board_face:
      cfg.drill_axis = normalize(0, sin 5deg, -cos 5deg); level_set_config; update(pose_floor());
      reading: mode FLOOR, bubble_x 0 and bubble_y 0 (+-0.01), deviation 0, ON_TARGET
  test_crosshair_reads_the_drill_axis:
      same axis; a pose that puts that axis horizontal; reading elevation 0 (+-0.01)
  test_zero_of_has_no_bubble_zero:
      zero at aimed(3,0); level_zero_of -> {3 (+-0.01), 0, 0}
  ```

- [ ] **Step 2: Run them and see them fail**

  Run: `tools/ci/firmware_host.sh`
  Expected: `test_level` fails to compile: `level_update_view` is undeclared and `level_zero` returns void.

- [ ] **Step 3: Implement the core in `level.h` and `level.c`**

  - `level_update_view()` takes `raw_angles().elevation_deg` (absolute) and applies the thresholds from Global Constraints. The first call sets `view_set`.
  - `raw_angles()` builds the bubble from the fixed face axis `{0, 0, -1}` with the same formula it uses today. Elevation and heading keep `cfg->drill_axis`.
  - `level_compute()`:
    - FLOOR: `deviation = hypot(bubble_x, bubble_y)`, absolute.
    - WALL (and any other value): `deviation = elevation`, relative after a zero.
    - The ANGLE case goes.
  - `level_init()` starts as WALL with `view_set = false`.

- [ ] **Step 4: Update the glue in `sensor_task.c`, `control.c`, `settings.c` and `sim_core.c`**

  - **`sensor_task.c` main loop:** under the lock, call `level_update_view(&s_level, q)` before `level_compute()`, only when `ready`. Log `"view %d -> %d"` after the unlock when it returns true.
  - **`sensor_task.c` start and settings:** `sensor_start()` and `sensor_apply_settings()` drop their `level_set_mode()` calls.
  - **`sensor_task.c` zero:** `sensor_zero()` becomes `ok = s_fusion_ready && level_zero(...)`.
  - **`control.c`:** the `APP_CMD_ZERO` failure log becomes `"zero ignored: no orientation or the bubble shows"`.
  - **`settings.c`:** `settings_level_config()` drops the `target_deg` line.
  - **`sim_core.c`:** make the same changes at its `level_set_mode` calls (around lines 811 and 969), its zero and its sample loop.

- [ ] **Step 5: Update the simulator tests to the new behaviour**

  - **`test_commands.py`:** the SET_MODE test (around line 42) now asserts that `config()["mode"]` changes while `angle().mode` stays the view of the simulated pose.
  - **`test_web.py`:** the swipe test (around line 74) now asserts that swipes leave `reading.mode` alone.
  - **New `test_view_follows_the_pose`:** tilt the simulated board from flat to upright. The view goes from 1 to 0, and the zero survives going back to flat.
  - **New `test_view_kept_while_imu_lost`:** with the board flat, lose the IMU. `angle().mode` stays 1.

  Both new tests go in `test_commands.py`, using its existing pose and IMU-loss helpers.

- [ ] **Step 6: Run the suites and see them pass**

  Run: `bash .superpowers/sdd/2026-11-02-boot-button-and-github-updates/all.sh firmware_host firmware_build simulator dataset`
  Expected: `ALL rc=0`.

- [ ] **Step 7: Commit**

  ```bash
  git add -A firmware
  git commit -m "Pick the level view from how the board sits"
  ```

---

### Task 2: The device screen follows the view

**Files:**
- Modify: `firmware/components/ui/src/ui_logic.c` and `.h`
  - `ui_mode_title()`: FLOOR becomes `"LEVEL"`, ANGLE `"WALL"`.
  - `ui_level_title(level_mode_t mode, char *buf, size_t len)` loses `target_deg`.
  - `ui_aim_sub()` loses its ANGLE branch (`"now 46.3°"`).
- Modify: `firmware/components/ui/src/ui_level.c`
  - The `LV_EVENT_GESTURE` case goes.
  - `s.can_zero` also needs `mode == LEVEL_MODE_WALL`.
  - The title call follows the new signature.
- Modify: `firmware/components/ui/include/ui.h` (the header comment: no swipe; the hold zeroes in WALL only)
- Modify: `firmware/components/app_core/include/app_state.h`, `firmware/main/control.c`, `firmware/tools/sim/core/sim_core.c`: `APP_CMD_NEXT_MODE` and its `change_mode(true, …)` path go.
- Modify: `firmware/test_host/ui_preview/preview.c`
  - The ANGLE screen goes.
  - The floor screen shows `LEVEL`.
- Modify: `firmware/tools/sim/dbsim/web/screen.py`
  - The swipe left/right on the level screen goes (the module docstring too).
  - The 1 s hold posts `APP_CMD_ZERO` only while the reading's mode is WALL, as `ui_level.c` does.
  - The settings screen's swipe right stays.
- Test: `firmware/test_host/ui_preview/test_ui_logic.c`, `firmware/tools/sim/tests/test_web.py`

**Interfaces:**
- Consumes: `level_t.mode` as the view (Task 1).
- Produces: `void ui_level_title(level_mode_t mode, char *buf, size_t len);`

- [ ] **Step 1: Write the failing tests**

  - **`test_ui_logic.c`:**
    - `ui_level_title(LEVEL_MODE_FLOOR, …)` gives `"LEVEL"`.
    - `ui_level_title(LEVEL_MODE_WALL, …)` gives `"WALL"`.
    - `ui_level_title(LEVEL_MODE_ANGLE, …)` gives `"WALL"`.
    - The ANGLE `ui_aim_sub` tests are deleted.
  - **`test_web.py`:**
    - A swipe left on the level screen returns no action and posts no command.
    - A hold with the board flat (bubble) posts no `APP_CMD_ZERO`; the snapshot's `zero_set` stays false.
    - A hold with the board upright zeroes, as before.

- [ ] **Step 2: Run them and see them fail**

  Run: `tools/ci/firmware_host.sh && tools/ci/simulator.sh`
  Expected: the title tests fail (`"FLOOR / CEILING"`), and the sim swipe test sees `next mode +1`.

- [ ] **Step 3: Implement the changes in the files above**

  The hold in the bubble view does nothing and shows no toast.

- [ ] **Step 4: Run the suites and look at the preview PNGs**

  Run: `bash .superpowers/sdd/2026-11-02-boot-button-and-github-updates/all.sh firmware_host firmware_build simulator`
  Expected: `ALL rc=0`. The preview output in `firmware/test_host/ui_preview/out/` has no ANGLE screen, and the floor screen reads `LEVEL`.

- [ ] **Step 5: Commit**

  ```bash
  git add -A firmware
  git commit -m "Show WALL or LEVEL by the view and drop the mode swipe"
  ```

---

### Task 3: The target angle goes

**Files:**
- **Firmware settings:** `firmware/components/settings/include/settings.h` and `src/settings.c`
  - `target_deg` becomes `legacy_target_deg`, defaulting to `45.0f`.
  - `SETTINGS_TARGET_MAX_DEG` leaves `settings.h` and becomes a private `LEGACY_TARGET_MAX_DEG 90.0f` in `settings.c`. `settings_validate()` keeps the old range check on the legacy field.
  - `settings_equal()` drops the field.
  - The JSON writer drops `"target"`, and `apply_object()` drops `READ(read_float(o, "target", …))`.
- **Device settings screen:** `firmware/components/ui/src/ui_logic.c`/`.h` and `ui_settings.c`. `UI_FIELD_TARGET` and `TARGET_MAX_T` go, and the screen loses its Target row.
- **Simulator:** `firmware/tools/sim/dbsim/web/screen.py`, if it mirrors the settings fields; and its tests that read `"target"` (`test_commands.py` around line 129, `test_ble.py`).
- **App protocol and settings:** `protocol/DeviceSettings.kt` (`targetDeg`, `SettingsField.TARGET`, `SettingsLimits.TARGET_MAX_DEG`), `ui/settings/SettingsFields.kt` and `SettingsScreen.kt`.
- **App live view:** `ui/live/LiveViewModel.kt` (`LiveUiState.targetDeg`) and `ui/live/LiveScreen.kt`. `AngleMode` goes, and its `when` branch becomes the wall view.
- **App tests:** `FirmwareConformanceTest.kt` (`settingsDefaults`, the keys and limits checks), `DeviceSettingsTest.kt`, `SettingsFieldsTest.kt`, `SettingsViewModelTest.kt`, `LiveScreenshotTest.kt`, `FakeDeviceSession.kt`.
- **Firmware tests:** `firmware/test_host/settings/test_settings.c`, `test_ui_logic.c`.

All app paths are under `app/src/main/java/dev/drillbalancer/` and `app/src/test/java/dev/drillbalancer/`.

**Interfaces:**
- Consumes: Task 1's `level_config_t` without `target_deg`.
- Produces: `settings_t.legacy_target_deg` (never read outside `settings.c`). The config JSON has no `"target"` key. `DeviceSettings` has no `targetDeg`.

- [ ] **Step 1: Write the failing tests**

  - **`test_settings.c`:**
    - `test_settings_json_has_no_target`: `settings_to_json(defaults)` doesn't contain `"target"`.
    - `test_target_write_is_ignored`: `{"target":30}` applies OK and leaves the settings unchanged.
    - `test_legacy_fields_survive_a_save`: a settings with `legacy_target_deg = 30`, `legacy_mode = LEVEL_MODE_ANGLE` and both calibrations valid stays valid. A JSON write of `{"bright":50}` keeps `legacy_target_deg == 30`, `legacy_mode == LEVEL_MODE_ANGLE` and both calibrations. (`legacy_mode` comes from Task 4; until then the test uses `mode`.)
  - **`DeviceSettingsTest.kt`:**
    - `parsesWithoutTarget`: the device JSON without `target` parses `Valid`.
    - The `TARGET` field cases are deleted.
  - **`LiveScreenshotTest.kt`:** the `angle*` screenshots are deleted.

- [ ] **Step 2: Run them and see them fail**

  Run: `tools/ci/firmware_host.sh`, then `cd app && ./gradlew testDebugUnitTest --tests 'dev.drillbalancer.protocol.*'`
  Expected: the new host tests fail, because the JSON still has `"target"`. `parsesWithoutTarget` fails because `targetDeg` is required.

- [ ] **Step 3: Implement both sides as listed above**

  `FirmwareConformanceTest` drops its `target_deg`, `TARGET_MAX_DEG` and `SETTINGS_TARGET_MAX_DEG` lines. Its key-order checks then match `SettingsField` without `TARGET`.

- [ ] **Step 4: Re-record the screenshots and run the suites**

  Run: `cd app && ./gradlew recordRoborazziDebug --tests 'dev.drillbalancer.ui.settings.*' --tests 'dev.drillbalancer.ui.live.*'`, then `bash .superpowers/sdd/2026-11-02-boot-button-and-github-updates/all.sh firmware_host firmware_build simulator db_client app`
  Expected: `ALL rc=0`. The settings screenshots show no Target row.

- [ ] **Step 5: Commit**

  ```bash
  git add -A firmware app
  git commit -m "Remove the target angle from the device, the protocol and the app"
  ```

---

### Task 4: Nothing picks a mode any more

**Files:**
- **Firmware protocol:** `firmware/components/ble/include/ble_protocol.h`
  - `DB_CMD_SET_MODE` goes, replaced by the comment `// 0x03 reserved (was SET_MODE)`.
  - The `DB_ATT_ERR_BAD_ARG` comment becomes "FACTORY_RESET without 0xA5".
- **Firmware codec:** `firmware/components/ble/src/ble_codec.c`. The `DB_CMD_SET_MODE` case and its argument check go.
- **Firmware commands:** `firmware/components/app_core/include/app_state.h`, `firmware/main/control.c` and `firmware/tools/sim/core/sim_core.c`. `APP_CMD_SET_MODE` and `change_mode()` go.
- **Firmware settings:** `firmware/components/settings/include/settings.h` and `src/settings.c`
  - `mode` becomes `legacy_mode`, defaulting to `LEVEL_MODE_WALL`.
  - Validation keeps `legacy_mode < LEVEL_MODE_COUNT`.
  - The JSON writer drops `"mode"`, and `apply_object()` drops `READ(read_mode(…))`.
  - `read_mode()` and `MODE_NAMES` go.
- **PC client:** `firmware/tools/ble/db_client.py`. The `set-mode` command, its `self_check` lines and its argparse help go. `MODES` stays, for decoding the angle packet.
- **App protocol:** `protocol/Commands.kt` (`Command.SetMode`, `CommandOpcode.SET_MODE`) and `protocol/DeviceSettings.kt` (`mode`, `SettingsField.MODE`).
- **App mode enum:** `protocol/Packets.kt`. `LevelMode` keeps WALL, FLOOR and ANGLE, so `fromWire(2)` still parses old firmware.
- **App session:** `ble/BleDeviceSession.kt`. The SetMode settings update (around line 256) and the mode copy (around line 424) go.
- **App live view:** `ui/live/LiveViewModel.kt` and `ui/live/LiveScreen.kt`
  - `pendingMode` and `setMode()` go; `mode` comes from the angle packet, else WALL.
  - `ModeTabs` becomes `ViewLabel`, a title text: "Wall" or "Level".
  - The `onMode` parameter goes.
- **App shared components:** `ui/components/Common.kt`. `ModeSelector` goes, and `LevelMode.title` becomes WALL→"Wall", FLOOR→"Level", ANGLE→"Wall".
- **App settings:** `ui/settings/SettingsScreen.kt` (`ModeRow`, `setMode`) and `SettingsViewModel.kt` (`setMode`).
- **App wiring:** `ui/AppUi.kt` (the `LiveScreen` and settings call sites).
- **App tests:**
  - `FirmwareConformanceTest.kt`:
    - `commandOpcodes` loses SET_MODE.
    - The `MODE_NAMES` check becomes a check that the `level_mode_t` names in `level.h` are `LEVEL_MODE_` + `LevelMode` names in order.
  - `WireVectorsTest.kt`: the `SetMode` vector goes; the angle vectors with `LevelMode.ANGLE` stay as old-firmware input.
  - `LiveViewModelTest.kt`, `LiveScreenshotTest.kt`, `SettingsViewModelTest.kt`, `FakeDeviceSession.kt`, `FakeDeviceSessionTest.kt`, and `GattErrorsTest.kt` where it uses SET_MODE.
- **Recording export test:** `app/src/test/java/dev/drillbalancer/recorder/export/FinalizerTest.kt`.
- **Firmware and simulator tests:**
  - `firmware/test_host/ble/test_ble_codec.c`
  - `firmware/test_host/settings/test_settings.c`
  - `firmware/tools/sim/tests/test_commands.py`, `test_ble.py` and `test_web.py`, in the places that write SET_MODE or `"mode"`
- **Dataset test:** `firmware/tools/dataset/tests/test_dbrec.py`.

All app paths are under `app/src/main/java/dev/drillbalancer/` and `app/src/test/java/dev/drillbalancer/`.

**Interfaces:**
- Consumes: Task 3's settings without target, and Task 1's `level_t.mode` as the view.
- Produces:
  - `settings_t.legacy_mode` (never read outside `settings.c`).
  - The config JSON has no `"mode"`, and command 0x03 answers 0x84.
  - `LiveScreen(state, angle, onZero, onClearZero, onDismissMessage, modifier, banner)` without `onMode`.
  - `LevelMode.title` as above.

- [ ] **Step 1: Write the failing tests**

  - **`test_ble_codec.c`:**
    - `test_set_mode_is_unknown`: `{0x03, 0x00}` and `{0x03}` return `DB_ATT_ERR_UNKNOWN_CMD`.
  - **`test_settings.c`:**
    - `test_settings_json_ignores_mode_and_target`: `{"mode":"angle","target":30}` applies OK and leaves the settings equal to before, and `settings_to_json()` contains neither `"mode"` nor `"target"`.
    - `test_legacy_fields_survive_a_save` (from Task 3) now uses `legacy_mode`.
  - **Simulator:**
    - `test_commands.py`: writing `b"\x03\x00"` answers 0x84.
    - `test_ble.py`: a config write of `{"mode":"floor"}` succeeds, and the config read back has no `"mode"`.
  - **`LiveViewModelTest.kt`:**
    - `followsTheDevicesView`: the fake reports FLOOR, then WALL. `state.mode` follows, with no pending request.
    - `angleFromOldFirmwareShowsTheWallView`: the fake reports `LevelMode.ANGLE`. The screen shows the wall view, and Zero is enabled.
  - **`FinalizerTest.kt`:**
    - `levelMetaFromTheFirstLevelRow`: the SESSION settings have no `mode` or `target`, and the LEVEL rows go FLOOR then WALL. The result is `meta.level.mode == "floor"` and `target_deg == 0.0`, and the meta passes `SchemaCheck`.
  - **`test_dbrec.py`:**
    - `test_level_meta_without_mode_in_settings`: the same input exported with `dbrec.py` gives the same `meta.level`, and `validate` passes with the mixed-mode LEVEL rows.

- [ ] **Step 2: Run them and see them fail**

  Run: `tools/ci/firmware_host.sh`, then `cd app && ./gradlew testDebugUnitTest --tests 'dev.drillbalancer.ui.live.*' --tests 'dev.drillbalancer.recorder.export.FinalizerTest'`
  Expected: `test_set_mode_is_unknown` fails (it is still accepted), and the JSON test sees `"mode"`. `followsTheDevicesView` doesn't compile yet if it uses the new constructor, or fails on the pending mode.

- [ ] **Step 3: Implement both sides as listed above**

  `dbdata/` isn't touched. If `test_level_meta_without_mode_in_settings` fails there, stop and report it: the spec says `dbdata/` needs no edit.

- [ ] **Step 4: Re-record the screenshots and run all seven suites**

  Run: `cd app && ./gradlew recordRoborazziDebug --tests 'dev.drillbalancer.ui.live.*' --tests 'dev.drillbalancer.ui.settings.*'`, then `bash .superpowers/sdd/2026-11-02-boot-button-and-github-updates/all.sh firmware_host firmware_build simulator dataset test_ci db_client app`
  Expected: `ALL rc=0`. The live screenshots show a "Wall" or "Level" title instead of tabs, and the settings screenshots have no Mode row.

- [ ] **Step 5: Commit**

  ```bash
  git add -A firmware app
  git commit -m "Stop choosing a level mode on the device, over BLE and in the app"
  ```

---

### Task 5: Zero is refused while the bubble shows

**Files:**
- **Firmware protocol:** `firmware/components/ble/include/ble_protocol.h` adds `#define DB_ATT_ERR_NO_ZERO 0x8B // command: ZERO while the level shows the bubble`. Next to the existing Android names note, add that Android names 0x8B GATT_INVALID_CFG.
- **Firmware codec:** `firmware/components/ble/src/ble_codec.c` and `.h`. `ble_codec_cmd_excl_err()` becomes `uint8_t ble_codec_cmd_state_err(app_cmd_id_t id, uint8_t excl, level_mode_t view)`. It returns `DB_ATT_ERR_BUSY` as before, and `DB_ATT_ERR_NO_ZERO` for `APP_CMD_ZERO` when `view == LEVEL_MODE_FLOOR`.
- **Firmware service:** `firmware/components/ble/src/ble_service.c` (around line 725) passes `snap.reading.mode`.
- **Simulator:** `firmware/tools/sim/core/sim_core.c` (around line 1434) makes the same call, with the sim's reading mode.
- **App errors:** `protocol/AttError.kt` adds `NO_ZERO(0x8B, "Zero works on the wall view only: hold the drill upright.")`. `GattErrors` maps 0x8B before any Android stack name, as it does for 0x88..0x8A.
- **App live view:** `ui/live/LiveScreen.kt`. The Zero button is enabled when `!state.busy && state.hasAngle && state.mode != LevelMode.FLOOR`.
- **Tests:**
  - firmware: `test_host/ble/test_ble_codec.c`
  - simulator: `firmware/tools/sim/tests/test_commands.py`
  - app (under `app/src/test/java/dev/drillbalancer/`): `ble/GattErrorsTest.kt`, `protocol/FirmwareConformanceTest.kt` (`attErrors` picks up the new code by itself), `ui/live/LiveScreenshotTest.kt` and `LiveViewModelTest.kt`

**Interfaces:**
- Consumes: Task 1's `level_reading_t.mode` as the view, and Task 4's `LiveScreen` signature.
- Produces: `ble_codec_cmd_state_err()` as above, and `AttError.NO_ZERO`.

- [ ] **Step 1: Write the failing tests**

  - **`test_ble_codec.c`:**
    - `test_zero_refused_in_the_bubble_view`: ZERO with view FLOOR gives `DB_ATT_ERR_NO_ZERO`.
    - `test_zero_allowed_in_the_wall_view`: ZERO with view WALL gives 0. With view ANGLE it also gives 0.
    - The cal-start BUSY cases keep passing through the new name.
  - **`test_commands.py`:**
    - `test_zero_refused_while_flat`: with the board flat, writing `b"\x01"` answers 0x8B and `zero_set` stays false.
    - Upright, it answers 0 and zeroes.
  - **`GattErrorsTest.kt`:** a command write failing with 0x8B maps to `AttError.NO_ZERO`, not to a pairing error.
  - **`LiveScreenshotTest.kt`:** `floorZeroDisabled` shows the bubble with Zero greyed out.

- [ ] **Step 2: Run them and see them fail**

  Run: `tools/ci/firmware_host.sh`
  Expected: `ble_codec_cmd_state_err` is undeclared.

- [ ] **Step 3: Implement as listed above**

- [ ] **Step 4: Re-record the screenshot and run all seven suites**

  Run: `cd app && ./gradlew recordRoborazziDebug --tests 'dev.drillbalancer.ui.live.LiveScreenshotTest'`, then `bash .superpowers/sdd/2026-11-02-boot-button-and-github-updates/all.sh firmware_host firmware_build simulator dataset test_ci db_client app`
  Expected: `ALL rc=0`.

- [ ] **Step 5: Commit**

  ```bash
  git add -A firmware app
  git commit -m "Refuse a zero while the level shows the bubble"
  ```

---

### Task 6: Docs, the whole-branch check, the push and the board

**Files:**
- `README.md`: the feature list says one level screen.
- `firmware/README.md`:
  - The BLE table: no SET_MODE, and NO_ZERO 0x8B.
  - The settings keys: no `mode` or `target`.
  - The level section: the view thresholds, the bubble on the board's face, and the mount calibration correcting wall holes only.
- `app/README.md`: the update order (app first, then the firmware from the new app).
- `firmware/tools/sim/README.md`: no mode swipe or SET_MODE, and the view follows the pose.
- Wiki, in `scratchpad/wiki-new` (the clone of `Drill_Balancer.wiki`, branch `master`): Using-the-Device, BLE-Protocol, Android-App, Simulator, Architecture and Training-Data (meta.level).
- Spec: `docs/superpowers/specs/2026-11-10-one-level-screen-design.md`. Mark any decision that changed during the work.

- [ ] **Step 1: Find every stale mention**

  Run: `grep -rnE 'ANGLE|SET_MODE|set-mode|target angle|FLOOR / CEILING|swipe' --include='*.md' . <wiki clone>`
  Expected: every match describes the new behaviour, or it is history in a spec or plan.

- [ ] **Step 2: Update the READMEs and wiki pages listed above**

- [ ] **Step 3: Run the whole-branch review and simplify**

  Run /code-review on `git diff origin/main...HEAD`, then /simplify, and fix what they find.

- [ ] **Step 4: Run all seven suites**

  Run: `bash .superpowers/sdd/2026-11-02-boot-button-and-github-updates/all.sh firmware_host firmware_build simulator dataset test_ci db_client app`
  Expected: `ALL rc=0`.

- [ ] **Step 5: Push and verify CI**

  Run: `git push origin main`, then `python3 <scratchpad>/ci.py <full sha>` until it reports completion.
  Expected: `CI completed success`. Push the wiki with `git -C <wiki clone> push origin master`.

- [ ] **Step 6: Flash the board and hand over the board check**

  Flash: `openocd -f board/esp32s3-builtin.cfg -c "program_esp firmware/build-ci/drillbalancer.bin 0x20000 verify reset exit"` (ESP-IDF env loaded). Capture the boot log with `usbcap.py` and expect no `E (` lines.

  Then ask the owner to check, on the board:
  - floor to wall and back;
  - holding the drill near 45°;
  - a ceiling hole;
  - zero against the wall, lay the drill down, pick it up again within a minute;
  - the device flat on a table reading level.

  The app on the phone is updated before that, because an older app can't connect to the new firmware. That install needs the owner's go-ahead.
