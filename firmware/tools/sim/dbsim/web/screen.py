"""The touch UI's screen state, mirrored from components/ui for the web panel.

The firmware draws with LVGL; the browser redraws the same screens from the
snapshot. What LVGL keeps in its own objects lives here: the current screen
(ui.c ui_show), the calibration guides' phases (ui_cal.c), the factory reset
confirmation (ui_settings.c) and the toast. Touch input arrives as gestures
already recognised by the browser and is handled with the firmware's rules:

- ui.c activity_cb: every press is activity; a press that wakes a dimmed
  screen does nothing else.
- ui_level.c: tap = APP_CMD_ZERO (only with a sensor), swipe left / right =
  APP_CMD_NEXT_MODE +1 / -1, 1 s hold = settings.
- The passkey and OTA overlays are modal panels: they swallow touches.
- ui_settings.c: swipe right (no confirmation shown) = back to the level.
- During a recorder take every touch is an EVENT TOUCH in the stream and is
  otherwise ignored; a 2 s long-press on the REC pill stops the take.
- ui_cal.c: a guide's start while OTA or a recording session has the device
  (app_excl_t) shows why instead of collecting, also when the control task
  refused it (app_cal_status_t.refused).

The settings screen is reduced to its action buttons; its steppers are not
mirrored (settings are edited from the app).
"""

from __future__ import annotations

from .. import core as C

HOLD_FOR_SETTINGS_MS = 1000   # ui_level.c (the browser times the hold)
ZERO_FLASH_MS = 700           # ui_level.c
SOLVE_TIMEOUT_MS = 3000       # ui_cal.c
ACCEL_CAL_DIR_ALL = 0x3F      # accel_cal.h

REC_RECORDING = 4             # rec_state_t
UI_C_GREEN = "#00E676"
UI_C_WHITE = "#FFFFFF"

CAL_KIND = {"mount": 1, "accel": 2}   # app_cal_kind_t

MOUNT_READY_TEXT = "Put the bit in a hole\n(horizontal or tilted),\nthen tap Start."
MOUNT_COLLECT_TEXT = ("Roll the drill slowly back\nand forth, at least 45°\n"
                      "each way. Keep the bit\nin the hole.")


class CalGuide:
    """One calibration guide (ui_cal.c cal_ui_t)."""

    def __init__(self, screen: "DeviceScreen", kind: str):
        self.s = screen
        self.kind = kind
        self.code = CAL_KIND[kind]
        if kind == "mount":
            self.start_cmd, self.finish_cmd = C.APP_CMD_MOUNT_CAL_START, C.APP_CMD_MOUNT_CAL_FINISH
        else:
            self.start_cmd, self.finish_cmd = C.APP_CMD_ACCEL_CAL_START, C.APP_CMD_ACCEL_CAL_FINISH
        self.seen_active = False
        self.refused = 0                # app_cal_status_t.refused when the start was posted
        self.finish_ms = 0
        self.count = ""
        self.tiles = 0                  # sensor: coverage shown on the tiles
        self.result_text = ""
        self.result_ok = False
        self.set_phase("ready" if kind == "mount" else "collect")

    def set_phase(self, phase: str):
        self.phase = phase
        self.left_enabled = phase != "solving"
        self.right_enabled = phase in ("result", "ready")

    def view(self) -> dict:
        p = self.phase
        body = None
        if self.kind == "mount":
            body = MOUNT_READY_TEXT if p == "ready" else MOUNT_COLLECT_TEXT
        return {
            "kind": self.kind, "phase": p, "body": body, "count": self.count,
            "tiles": self.tiles, "result": self.result_text, "result_ok": self.result_ok,
            "left": "Retry" if p == "result" else "Cancel",
            "right": {"result": "Done", "solving": "...", "ready": "Start"}.get(p, "Finish"),
            "left_enabled": self.left_enabled, "right_enabled": self.right_enabled,
        }

    def on_show(self):
        self.count = ""
        if self.kind == "mount":
            self.set_phase("ready")     # Start once the bit is in the hole
        else:
            self.start()                # poses only count while still

    def start(self):
        # OTA or a recording session has the device: refused at once; one
        # the control task refuses later shows up as cal.refused (update()).
        snap = self.s.dev.snapshot()
        if snap["ui"]["cal_start_refused"]:
            self.show_refused(snap)
            return
        self.seen_active = False
        self.refused = snap["cal"]["refused"]
        self.set_phase("collect")
        self.s.dev.ui_command(self.start_cmd, 0)

    def show_refused(self, snap: dict):
        self.result_text = snap["ui"]["cal_refused_text"]
        self.result_ok = False
        self.set_phase("result")

    def show_result(self, snap: dict):
        ui = snap["ui"]     # ui_logic.c's verdict on the last result
        have = snap["cal"]["last_kind"] == self.code and ui["cal_result_known"]
        self.result_text = ui["cal_result_text"] if have else "No result.\nPlease try again."
        self.result_ok = have and ui["cal_result_ok"]
        self.set_phase("result")

    def left(self):
        if not self.left_enabled:
            return
        if self.phase == "result":
            self.start()
        elif self.phase == "ready":
            self.s.show("settings")     # nothing started yet
        elif self.phase == "collect":
            self.s.dev.ui_command(C.APP_CMD_CAL_CANCEL, 0)
            self.s.show("settings")

    def right(self):
        if not self.right_enabled:
            return
        if self.phase == "result":
            self.s.show("level")
        elif self.phase == "ready":
            self.start()
        elif self.phase == "collect" and self.seen_active:
            if self.s.dev.ui_command(self.finish_cmd, 0):
                self.finish_ms = self.s.dev.now_ms
                self.set_phase("solving")

    def update(self, snap: dict):
        cal = snap["cal"]
        active = cal["kind"] == self.code
        if self.kind == "mount":
            if active:
                self.count = f"{cal['samples']} samples"
            can_finish = True
        else:
            cov = cal["coverage"] if active else 0
            if self.phase == "collect":
                self.tiles = cov
                if active:
                    self.count = f"{bin(cov).count('1')} / 6 sides"
            can_finish = (cov & ACCEL_CAL_DIR_ALL) == ACCEL_CAL_DIR_ALL
        if self.phase == "collect":
            if active:
                self.seen_active = True
            elif self.seen_active:
                self.show_result(snap)  # ended elsewhere (BLE cancel, power state)
                return
            elif cal["refused"] != self.refused:
                self.show_refused(snap)  # the control task refused the start
                return
            self.right_enabled = self.seen_active and can_finish
        elif self.phase == "solving":
            if not active or self.s.dev.now_ms - self.finish_ms > SOLVE_TIMEOUT_MS:
                self.show_result(snap)


class DeviceScreen:
    """What the round screen shows; reset on every boot (ui_init)."""

    def __init__(self, dev):
        self.dev = dev
        self.reset()

    def reset(self):
        self.screen = "level"
        self.confirm = False
        self.toast = None               # (text, colour, until sim ms)
        self.swallow = False            # the current press woke the screen
        self.guides = {"mount_cal": CalGuide(self, "mount"), "sensor_cal": CalGuide(self, "accel")}

    def show(self, screen: str):
        """ui_show()"""
        self.confirm = False            # only ever shown on the settings screen
        self.screen = screen
        if screen in self.guides:
            self.guides[screen].on_show()

    def toast_show(self, text: str, color: str, ms: int):
        self.toast = (text, color, self.dev.now_ms + ms)

    def update(self, snap: dict):
        """ui.c tick_cb: the current screen follows the snapshot."""
        if self.dev.state != "running" or snap.get("power") in (2, 3):
            return
        if self.screen in self.guides:
            self.guides[self.screen].update(snap)

    def view(self) -> dict:
        toast = None
        if self.toast and self.dev.now_ms < self.toast[2]:
            toast = {"text": self.toast[0], "color": self.toast[1]}
        g = self.guides.get(self.screen)
        return {"screen": self.screen, "confirm": self.confirm, "toast": toast,
                "cal": g.view() if g else None}

    # ---- touch ------------------------------------------------------------------------
    def _modal(self, snap: dict) -> bool:
        return bool(snap.get("passkey_pending") or snap.get("ota_active"))

    def touch(self, action: str, dir: str | None = None, button: str | None = None) -> str:
        """A gesture from the mirror. Returns what it did (for the event log)."""
        if self.dev.state != "running":
            return "ignored: screen off"
        snap = self.dev.core.snapshot()
        if action == "press":
            # activity_cb: LVGL runs it before anything sees the press.
            self.swallow = snap["power"] == 1
            taken = self.dev.touch()
            return "woke the screen" if self.swallow else "EVENT TOUCH (recording)" if taken else ""
        if self.swallow:
            return "ignored: the press woke the screen"
        recording = snap.get("rec", {}).get("state") == REC_RECORDING
        if action == "button" and button == "rec_pill":
            if not recording:
                return ""
            att = self.dev.rec_ui_stop()
            return "take stopped (REC pill held 2 s)" if att == 0 else f"stop refused 0x{att:02x}"
        if recording:
            return "ignored: recording (EVENT TOUCH)"
        if self._modal(snap):
            return "ignored: overlay shown"
        if self.screen == "level":
            return self._level(action, dir, snap)
        if self.screen == "settings":
            return self._settings(action, dir, button)
        g = self.guides[self.screen]
        if action == "button" and button in ("cal_left", "cal_right"):
            left = button == "cal_left"
            v = g.view()
            if not (v["left_enabled"] if left else v["right_enabled"]):
                return "button disabled"
            g.left() if left else g.right()
            return f"{v['left'] if left else v['right']} pressed"
        return ""

    def _level(self, action, dir, snap) -> str:
        if action == "tap":
            if snap["imu_ok"] and self.dev.ui_command(C.APP_CMD_ZERO, 0):
                self.toast_show("ZERO", UI_C_GREEN, ZERO_FLASH_MS)
                return "zero"
            return ""
        if action == "swipe" and dir in ("left", "right"):
            self.dev.ui_command(C.APP_CMD_NEXT_MODE, 1 if dir == "left" else -1)
            return f"next mode {'+1' if dir == 'left' else '-1'}"
        if action == "hold":
            self.show("settings")
            return "settings"
        return ""

    def _settings(self, action, dir, button) -> str:
        if self.confirm:
            if button == "confirm_cancel":
                self.confirm = False
                return "factory reset cancelled"
            if button == "confirm_reset":
                self.confirm = False
                if self.dev.ui_command(C.APP_CMD_FACTORY_RESET, 0):
                    self.toast_show("Reset done", UI_C_WHITE, 1200)
                return "factory reset"
            return ""
        if action == "swipe" and dir == "right":
            self.show("level")
            return "level"
        if action != "button":
            return ""
        if button == "set_mount":
            self.show("mount_cal")
        elif button == "set_sensor":
            self.show("sensor_cal")
        elif button == "set_clear_zero":
            if self.dev.ui_command(C.APP_CMD_CLEAR_ZERO, 0):
                self.toast_show("Zero cleared", UI_C_WHITE, 1000)
        elif button == "set_reset":
            self.confirm = True
        elif button == "set_back":
            self.show("level")
        else:
            return ""
        return button
