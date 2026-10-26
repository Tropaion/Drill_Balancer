// The mirror's rules for which press does what (ui_level.c's hold for the
// settings, the REC pill on lv_layer_top), kept free of the DOM: app.js uses
// them and tests/test_web.py runs them under node.
"use strict";

const Touch = {
  HOLD_MS: 1000,            // ui_level.c HOLD_FOR_SETTINGS_MS
  REC_PILL_HOLD_MS: 2000,   // the REC pill's long-press stops the take
  REC_READY: 3,             // rec_state_t REC_STATE_READY ..
  REC_RECORDING: 4,         // .. REC_STATE_RECORDING ..
  REC_DRAINING: 5,          // .. REC_STATE_DRAINING

  // The REC pill's hit region at (x, y, w, h), or null. It takes touches only
  // while RECORDING: as "REC ready" (and while DRAINING) it is a label, and a
  // tap goes to what lies under it.
  recPillRegion(rec, x, y, w, h) {
    if (!rec || rec.state !== Touch.REC_RECORDING) return null;
    return {id: "rec_pill", x, y, w, h, enabled: true};
  },

  // The long-press a press arms. On the pill: its 2 s stop, and nothing else
  // (the pill is an object on lv_layer_top, the level screen under it never
  // sees the press). Anywhere else: the level screen's 1 s hold for the
  // settings (levelOnly: only if the level screen is still shown then).
  holdFor(region) {
    if (region && region.id === "rec_pill") {
      return {ms: Touch.REC_PILL_HOLD_MS, levelOnly: false,
        msg: {cmd: "touch", action: "button", button: "rec_pill"}};
    }
    return {ms: Touch.HOLD_MS, levelOnly: true, msg: {cmd: "touch", action: "hold"}};
  },
};

if (typeof module !== "undefined") module.exports = Touch;
