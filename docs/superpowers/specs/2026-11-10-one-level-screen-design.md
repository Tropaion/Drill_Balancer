# One level screen instead of three modes

## Why

The device has three level modes: WALL, FLOOR / CEILING and ANGLE. You
switch between them by swiping on the level screen or from the app. The
owner asked why there are three windows instead of one where everything is
possible.

The owner uses the device in four ways:

1. Zeroing against a wall: hold the drill straight at the wall, zero, then
   watch up/down and left/right.
2. 0°, a hole straight down into the floor.
3. 90°, a hole straight into a wall, without a zero.
4. As a level, with the board lying flat on the surface.

None of them needs a custom target angle. Uses 2 and 4 are the same picture:
the screen faces up, and the bubble centres when the bit is plumb or the
surface is level. Uses 1 and 3 are the same wall picture, with or without a
zero. Gravity alone tells the device which of the two pictures fits, so the
user doesn't have to choose.

## Goals

- One level screen that shows the bubble or the crosshair depending on how
  the board sits, and switches by itself.
- **Board flat** (bit near vertical, screen up or down): the bubble. This
  covers floor holes, ceiling holes and levelling a surface.
- **Board upright** (bit near horizontal): the crosshair. Up/down shows
  always. Left/right shows after the 1 s hold to zero, as today.
- A wall zero survives a trip to the bubble view, for example while laying
  the drill down to change bits. It still goes stale and can be cleared as
  today.
- ANGLE mode and the target angle are gone from the device, the app, the
  protocol, the simulator and the docs.
- Nothing switches modes any more. The swipe on the level screen goes, and
  the app's mode picker becomes a label that shows the view in use.

## Non-goals

- Angled holes against a set target angle, such as 30° down. They could
  come back later as a separate feature.
- A zero in the bubble view, which levels relative to a sloped surface.
  Today FLOOR mode has one; it goes.
- Any change to the fusion or gyro handling. The dot's slow return and small
  drift are a separate work item.
- An absolute left/right reference. There is no compass.
- Changing how the crosshair and bubble are drawn, their colours,
  tolerance, near band, Invert dot or the stale-yaw rule.
- A new use for the swipe gesture this frees up.

## Anti-goals (failing even if it technically works)

- The view flickers between bubble and crosshair when the drill is held near
  45°, or jumps while drilling with vibration.
- A zero gets lost by accident within the stale time, for example by
  laying the drill down and picking it up again. Deep sleep still ends the
  zero, as today: it comes after 120 s by default, later than the 60 s
  stale time that already asks for a new zero. The left/right heading
  could not survive it anyway, because the fusion restarts at yaw 0.
  *(My call.)*
- Saved settings or calibrations (mount, accelerometer) are lost on the
  update.
- The recording dataset contract breaks. Existing recordings must still
  validate, and the data repo's pinned validator (tag `dataset-v1`) must
  accept new recordings.

## Behaviour

### Picking the view

- The view follows the angle between the drill axis (`level_config_t.drill_axis`,
  mount calibration included) and vertical. That angle comes from gravity
  alone, so it works without a zero and does not drift.
- Bubble when the bit is within 35° of vertical; crosshair when it is more
  than 55° from vertical; in between, the current view stays. That is a
  10° margin on each side of 45°. With no current view yet, at boot, the
  nearer one is taken. *(My call.)*
- Screen up and screen down both count as near vertical (floor and
  ceiling), as FLOOR / CEILING does today.
- With no fresh IMU reading, the view stays as it is. *(My call.)*
- The yaw already needs the bit at least 30° from vertical
  (`YAW_MIN_HORIZONTAL` in `level.c`). The crosshair only shows from 35°,
  so yaw is always computable there.

### What each view measures

- **The crosshair measures along the drill axis**, mount calibration
  included, as WALL does today.
- **The bubble measures the board's own face (−Z)**, without the mount
  calibration. For use 4 the owner takes the device off the drill and lays
  it flat, so a correction for how it sits on the drill would make a level
  surface read as tilted. With an offset of 1–2° it would never turn green
  at the default 0.5° tolerance. *(Decided with the owner.)*
- The trade-off: a floor hole relies on the device sitting square on the
  drill, because the mount calibration no longer corrects the bubble. The
  owner's board has no mount calibration today, so today the two are the
  same. The docs say the mount calibration corrects wall holes only.
- The view choice above stays on the drill axis. The yaw needs the bit at
  least 30° from vertical, and a choice made on the board's face could
  bring up the crosshair with the bit up to 15° closer to vertical than
  that.
- Recordings: in the bubble view `deviation_cdeg` is the board's tilt, so
  a floor hole's off angle is the board's tilt as well. With a square
  mount that is the same thing.

### Zero

- The 1 s hold zeroes only in the crosshair view, the same as today's WALL
  zero: up/down becomes relative, and left/right starts from that heading.
  In the bubble view the hold does nothing. *(My call; the owner didn't
  object to it in the conversation.)*
- The same rule applies to the BLE zero command and to the app's zero
  button. In the bubble view the device refuses the command and the app
  greys out the button.
- The zero is kept while the bubble shows and applies again when the
  crosshair returns. The stale rule (`yaw_stale_s`, default 60 s, counted
  from the zero) and clearing the zero stay as they are.
- The bubble always shows the absolute tilt of the board's face.

### Device screen

- Titles: **WALL** for the crosshair, **LEVEL** for the bubble. That
  replaces "FLOOR / CEILING", which doesn't fit levelling a surface.
  *(My call.)*
- The swipe left/right on the level screen goes.
- Nothing else on the two views changes: the number, the arrow, the ring,
  the yaw line, the dot colours, Invert dot and the stale-yaw amber.

### App

- The settings screen loses its Mode and Target fields.
- The live view follows the view the device reports in the angle packet's
  `mode` field, using the existing wall and floor views. The Angle view goes.
  Older firmware that still reports ANGLE shows as the wall view.
  *(My call.)*
- The mode picker becomes a label showing the view in use.
- The zero button follows the zero rule above.

### Protocol and storage

- **Angle packet** (`db_angle_packet_t.mode`): now the view in use, 0 for
  the crosshair and 1 for the bubble. 2 is no longer sent. The packet's
  size and version stay the same.
- **`DB_CMD_SET_MODE` (0x03)** is removed. The opcode stays reserved, and a
  write gets the error for an unknown command.
- **Config JSON:** `mode` and `target` are no longer sent. If an older app
  writes them, they are accepted and ignored. *(My call.)* The same JSON
  goes into the recorder's SESSION record, so leaving the keys out also
  keeps `meta.level` honest; see Recordings.
- **Saved settings:** the stored mode and target are no longer used. The
  stored layout stays the same, so an automatic OTA rollback to the old
  firmware still finds the other settings and both calibrations.
  *(My call.)*
- `LEVEL_MODE_ANGLE` stays as a reserved value (2) that is never used.
  Removing it would break the enum check in `dbrec.py`, which is inside the
  `dataset-v1` pin. *(My call.)*
- `APP_CMD_SET_MODE` and `APP_CMD_NEXT_MODE` go.

### Recordings (dataset)

- The `mode` of each LEVEL record carries the view in use, row by row. Its
  values, 0 and 1, are already in the existing enum.
- **meta.json `level`:**
  - `mode` is the view when the take starts.
  - `target_deg` is 0, the crosshair's target.
  - There is no schema change, so the data repo keeps its pinned validator.
    *(My call.)*
  - The app's finalizer and `dbrec.py` already produce this when the
    settings JSON has no `mode` and `target`: the first LEVEL row's mode,
    target 0. `dbdata/` needs no edit, so the `dataset-v1` tag stays where
    it is.
- The off angle keeps coming from `deviation_cdeg`. In the bubble view that
  is the total tilt and in the crosshair view the up/down error, the same
  as today's FLOOR and WALL.

### Simulator

- `sim_core` mirrors the firmware as before. The web panel drops its mode
  controls and shows the view in use.

## Constraints

- **Host-testable logic:** the view choice, the zero rule and the screen
  texts stay pure and host-tested (`level.c`, `ui_logic.c`), and are
  mirrored in `sim_core.c` as today.
- **Protocol source of truth:** `ble_protocol.h` stays authoritative. The
  app's `FirmwareConformanceTest` parses it, and the wire vectors are
  shared.
- **Old app:** an older app can't connect to the new firmware at all. It
  reads the settings while connecting, and `DeviceSettings` requires
  `mode` and `targetDeg`.
  - So the app is updated first, then the firmware over the air from the new
    app. The new app must keep working with the old firmware until then.
  - That is acceptable because the app isn't released yet (VERSION 0.1.0,
    no release tag) and the owner updates both. The docs say which order to
    use. *(My call.)*
- **Process for every change:** /code-review, /simplify, all seven suites,
  push, CI green, and the wiki and READMEs updated. The pages are Using the
  device, BLE-Protocol, Android-App, Simulator, Architecture and Training-Data.
- **Board check:** the owner checks the result on the real board.

## Left to the builder

- Where the view state and hysteresis live (`level_t` or a small module of
  their own), and whether `level_compute()` or the sensor task picks the
  view.
- How the settings blob drops the two fields: keep the layout with unused
  fields, or a schema version with a migration. Either way the constraint
  above holds.
- Whether the hold in the bubble view shows a short hint, and its text.
- The test design.

## Verification

- **Host tests:**
  - switching at 35°/55°, with the view held in between and at boot;
  - screen down counted as near vertical;
  - the zero kept across a bubble trip and refused in the bubble view;
  - the bubble reading the board's face with a mount calibration set, and
    the crosshair still reading the drill axis;
  - settings loading from an old blob that still has mode and target;
  - the config JSON ignoring `mode` and `target`.
- **Simulator tests:** the view follows the simulated orientation, and
  `SET_MODE` is refused.
- **App:** unit tests, and screenshots of the live view in both views and of
  the settings screen without Mode and Target.
- **Dataset:** a recording whose view changes mid-take validates, and its
  meta follows the rule above.
- **On the board, by the owner:**
  - floor to wall and back;
  - holding the drill near 45°;
  - a ceiling hole;
  - zero against the wall, lay the drill down, pick it up again within a
    minute.
