// DrillBalancer simulator panel: state over a websocket, commands as JSON,
// and a canvas mirror of the round 466x466 screen drawn like components/ui.
"use strict";

const $ = (id) => document.getElementById(id);

// ---- transport ------------------------------------------------------------------------

let ws = null;
let state = null;          // last {"type": "state"} message
let wsBackoff = 500;

function connect() {
  ws = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws`);
  ws.onopen = () => { wsBackoff = 500; setPill($("hdr-ws"), "live", "ok"); };
  ws.onclose = () => {
    setPill($("hdr-ws"), "disconnected", "bad");
    setTimeout(connect, wsBackoff);
    wsBackoff = Math.min(wsBackoff * 2, 5000);
  };
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.type === "hello") {
      $("hdr-name").textContent = `${m.name} · ${m.mac}`;
      logEntries.length = 0;
      addLog(m.log);
    } else if (m.type === "state") {
      state = m;
      if (m.log.length) addLog(m.log);
      updatePanel();
      requestDraw();
    } else if (m.type === "reply" && !m.ok) {
      showError(m.error);
    }
  };
}

// Controls: POST /api/cmd. Touch gestures go over the websocket to keep their order.
async function post(cmd) {
  try {
    const r = await fetch("/api/cmd", {
      method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(cmd),
    });
    const j = await r.json();
    if (!r.ok) showError(j.error || r.statusText);
    return j;
  } catch (e) {
    showError(String(e));
  }
}

function wsSend(cmd) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(cmd));
}

let toastTimer = 0;
function showError(text) {
  const t = $("toast");
  t.textContent = text;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 4000);
}

function setPill(el, text, cls) {
  el.textContent = text;
  el.className = "pill" + (cls ? " " + cls : "");
}

// ---- formatting (ui_logic.c) ----------------------------------------------------------

function fmtFixed(v, dec, plus, suffix = "") {
  if (!Number.isFinite(v)) return "--" + suffix;
  const scale = 10 ** dec;
  const n = Math.round(Math.min(Math.abs(v) * scale, 9999 * scale));
  const sign = n > 0 ? (v < 0 ? "-" : plus ? "+" : "") : "";   // never "-0.0"
  const body = dec === 0 ? String(n) : `${Math.floor(n / scale)}.${String(n % scale).padStart(dec, "0")}`;
  return sign + body + suffix;
}
const fmtDeg = (v, plus = false) => fmtFixed(v, 1, plus, "°");
const saneNear = (n) => (n > 0.01 ? n : 1);

function bubbleOffset(bx, by, near, radius, bubbleR) {
  if (!Number.isFinite(bx) || !Number.isFinite(by)) return [0, 0];
  const ppd = radius / 3 / saneNear(near);
  let px = bx * ppd, py = -by * ppd;
  const lim = Math.max(0, radius - bubbleR);
  const d = Math.hypot(px, py);
  if (d > lim) { px *= lim / d; py *= lim / d; }
  return [Math.round(px), Math.round(py)];
}

function burninOffset(step, amp) {
  const p = [[0, 0], [1, 0], [1, 1], [0, 1], [-1, 1], [-1, 0], [-1, -1], [0, -1], [1, -1]][step % 9];
  return [p[0] * amp, p[1] * amp];
}

function roundRow(dy, h, nominal, minW, radius) {
  const outer = Math.abs(dy) + h / 2;
  const half = outer < radius ? Math.sqrt(radius * radius - outer * outer) : 0;
  const w = Math.max(minW, Math.min(nominal, Math.floor(2 * half)));
  const over = Math.hypot(w / 2, outer) - radius;
  if (over <= 0) return [w, 1];
  return [w, Math.max(40, 255 - (over * (255 - 40)) / 40) / 255];
}

// ---- the round screen -----------------------------------------------------------------

const W = 466, CX = 233, SAFE_R = 225;
// ui_priv.h UI_LOWBAT_* and UI_REC_PILL_* (the pill sits at the banner's y,
// below the banner when both show).
const LOWBAT = {w: 224, h: 42, y: 46};
const REC_PILL = {w: 160, h: 40, gap: 6};
const COL = {
  green: "#00E676", amber: "#FFB300", white: "#FFFFFF", grey: "#9E9E9E", dark: "#2A2A2A",
  button: "#1C1C1C", red: "#FF5252", redBg: "#C62828", blue: "#448AFF",
};
const FONT = '"Montserrat", "Inter", "Noto Sans", "DejaVu Sans", system-ui, sans-serif';
const statusColor = (st) => (st === 0 ? COL.green : st === 1 ? COL.amber : COL.white);

const canvas = $("screen");
const ctx = canvas.getContext("2d");
let regions = [];          // touch targets of the last frame: {id, x, y, w, h, enabled}
let settingsScroll = 0;
let settingsContentH = 0;
let drawPending = false;

function requestDraw() {
  if (!drawPending) {
    drawPending = true;
    requestAnimationFrame(() => { drawPending = false; draw(); });
  }
}

function font(px, weight = 500) { return `${weight} ${px}px ${FONT}`; }

// Text centred at x, with its middle at y; shrinks to maxW if needed.
function text(str, x, y, px, color, opts = {}) {
  ctx.save();
  const weight = opts.num ? 600 : opts.weight || 500;
  ctx.font = font(px, weight);
  if (opts.maxW) {
    const w = ctx.measureText(str).width;
    if (w > opts.maxW) {
      const p2 = Math.floor((px * opts.maxW) / w);
      ctx.font = font(p2, weight);
    }
  }
  ctx.fillStyle = color;
  ctx.textAlign = opts.align || "center";
  ctx.textBaseline = "middle";
  if (opts.alpha !== undefined) ctx.globalAlpha *= opts.alpha;
  ctx.fillText(str, x, y);
  const w = ctx.measureText(str).width;
  ctx.restore();
  return w;
}

function textWidth(str, px) {
  ctx.save();
  ctx.font = font(px);
  const w = ctx.measureText(str).width;
  ctx.restore();
  return w;
}

// Multi-line label: lines centred, block top at y (LVGL label, centre aligned), wrapped at maxW.
function label(str, x, top, px, color, maxW, lineH) {
  lineH = lineH || Math.round(px * 1.2);
  const lines = [];
  ctx.save();
  ctx.font = font(px);
  for (const para of str.split("\n")) {
    let cur = "";
    for (const word of para.split(" ")) {
      const t = cur ? cur + " " + word : word;
      if (maxW && ctx.measureText(t).width > maxW && cur) { lines.push(cur); cur = word; } else cur = t;
    }
    lines.push(cur);
  }
  ctx.restore();
  lines.forEach((l, i) => text(l, x, top + lineH * (i + 0.5), px, color));
  return lines.length * lineH;
}

function pill(x, y, w, h, color) {   // x, y = top left
  ctx.beginPath();
  ctx.roundRect(x, y, w, h, Math.min(w, h) / 2);
  ctx.fillStyle = color;
  ctx.fill();
}

function circle(cx, cy, r, stroke, lw, fill) {
  ctx.beginPath();
  ctx.arc(cx, cy, r, 0, Math.PI * 2);
  if (fill) { ctx.fillStyle = fill; ctx.fill(); }
  if (stroke) { ctx.strokeStyle = stroke; ctx.lineWidth = lw; ctx.stroke(); }
}

// LVGL arc: angles in degrees, 0 = 3 o'clock, clockwise; width drawn inward from radius r.
function arc(cx, cy, r, width, a0, a1, color) {
  ctx.beginPath();
  ctx.arc(cx, cy, r - width / 2, (a0 * Math.PI) / 180, (a1 * Math.PI) / 180);
  ctx.strokeStyle = color;
  ctx.lineWidth = width;
  ctx.lineCap = "round";
  ctx.stroke();
}

function button(id, cx, cy, w, h, str, opts = {}) {
  const enabled = opts.enabled !== false;
  ctx.save();
  ctx.globalAlpha *= (enabled ? 1 : 0.4) * (opts.alpha ?? 1);
  ctx.beginPath();
  ctx.roundRect(cx - w / 2 + 1, cy - h / 2 + 1, w - 2, h - 2, h / 2);
  ctx.fillStyle = opts.bg || COL.button;
  ctx.fill();
  ctx.strokeStyle = opts.border || COL.dark;
  ctx.lineWidth = 2;
  ctx.stroke();
  ctx.restore();
  if (opts.icon) opts.icon(cx, cy, enabled);
  else text(str, cx, cy, opts.px || 24, opts.color || COL.white, {alpha: (enabled ? 1 : 0.4) * (opts.alpha ?? 1)});
  if (id) regions.push({id, x: cx - w / 2, y: cy - h / 2, w, h, enabled});
}

// ---- icons (LVGL symbols, drawn) ----

function chevron(x, cy, up, color) {   // x = left edge, 44 px wide (LV_SYMBOL_UP at 48 px)
  ctx.save();
  ctx.strokeStyle = color;
  ctx.lineWidth = 10;
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.beginPath();
  const d = up ? 1 : -1;
  ctx.moveTo(x + 5, cy + 11 * d);
  ctx.lineTo(x + 22, cy - 9 * d);
  ctx.lineTo(x + 39, cy + 11 * d);
  ctx.stroke();
  ctx.restore();
}

function batteryIcon(x, cy, level, color) {   // 34 x 17
  ctx.save();
  ctx.strokeStyle = color;
  ctx.fillStyle = color;
  ctx.lineWidth = 2.5;
  ctx.beginPath();
  ctx.roundRect(x + 1, cy - 8, 28, 16, 3);
  ctx.stroke();
  ctx.fillRect(x + 30, cy - 4, 3, 8);
  for (let i = 0; i < level; i++) ctx.fillRect(x + 4 + i * 6.2, cy - 5, 4.6, 10);
  ctx.restore();
  return 34;
}

function boltIcon(x, cy, color) {   // 14 wide
  ctx.save();
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.moveTo(x + 9, cy - 12);
  ctx.lineTo(x + 1, cy + 2);
  ctx.lineTo(x + 6.5, cy + 2);
  ctx.lineTo(x + 4.5, cy + 12);
  ctx.lineTo(x + 13, cy - 2);
  ctx.lineTo(x + 7.5, cy - 2);
  ctx.closePath();
  ctx.fill();
  ctx.restore();
  return 14;
}

function btIcon(x, cy, color, s = 1) {   // 14 wide at s = 1
  ctx.save();
  ctx.strokeStyle = color;
  ctx.lineWidth = 2.4 * s;
  ctx.lineJoin = "round";
  ctx.lineCap = "round";
  ctx.beginPath();
  const X = (v) => x + v * s, Y = (v) => cy + v * s;
  ctx.moveTo(X(1.5), Y(-6));
  ctx.lineTo(X(12), Y(5));
  ctx.lineTo(X(6.5), Y(11));
  ctx.lineTo(X(6.5), Y(-11));
  ctx.lineTo(X(12), Y(-5));
  ctx.lineTo(X(1.5), Y(6));
  ctx.stroke();
  ctx.restore();
  return 14 * s;
}

function warnIcon(cx, cy, size, color) {
  ctx.save();
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.moveTo(cx, cy - size / 2);
  ctx.lineTo(cx + size * 0.56, cy + size / 2);
  ctx.lineTo(cx - size * 0.56, cy + size / 2);
  ctx.closePath();
  ctx.fill();
  ctx.fillStyle = "#000";
  ctx.fillRect(cx - size * 0.05, cy - size * 0.18, size * 0.1, size * 0.4);
  ctx.fillRect(cx - size * 0.05, cy + size * 0.3, size * 0.1, size * 0.1);
  ctx.restore();
}

function gearIcon(cx, cy, r, color) {
  ctx.save();
  ctx.strokeStyle = color;
  ctx.lineWidth = 3;
  circle(cx, cy, r * 0.55, color, 3);
  for (let i = 0; i < 8; i++) {
    const a = (i * Math.PI) / 4;
    ctx.beginPath();
    ctx.moveTo(cx + Math.cos(a) * r * 0.62, cy + Math.sin(a) * r * 0.62);
    ctx.lineTo(cx + Math.cos(a) * r, cy + Math.sin(a) * r);
    ctx.stroke();
  }
  ctx.restore();
}

// ---- screens ----

function drawLevel(s) {
  const cfg = s.settings, r = s.reading, ui = s.ui;
  const [dx, dy] = burninOffset(Math.floor(s.now_ms / 180000), 4);
  ctx.save();
  ctx.translate(dx, dy);

  // status ring, open at the bottom; fills as the deviation goes to zero
  arc(CX, CX, SAFE_R, 12, 135, 405, COL.dark);
  if (s.imu_ok) {
    const v = Math.round(ui.ring * 270);
    if (v > 0) arc(CX, CX, SAFE_R, 12, 135, 135 + v, statusColor(r.status));
  }
  text(ui.title, CX, 52 + 14, 24, COL.grey);

  if (!s.imu_ok) {
    warnIcon(CX, CX - 10 - 30, 44, COL.amber);
    text("NO SENSOR", CX, CX - 10 + 28, 48, COL.amber);
  } else {
    drawAim(r, cfg, ui);
  }
  drawStatusBar(s);
  ctx.restore();
}

// ui_level.c: one crosshair for every mode. Floor: the dot is the bubble;
// wall and angle: the aim point (ui_dot_deg).
function drawAim(r, cfg, ui) {
  const c = statusColor(r.status);
  const vx = CX, vy = CX - 40, R = 100, DR = 16;
  circle(vx, vy, R - 1, "#5A5A5A", 2);
  pill(vx - (R - 4), vy - 1, 2 * R - 8, 2, COL.dark);
  pill(vx - 1, vy - (R - 4), 2, 2 * R - 8, COL.dark);
  circle(vx, vy, Math.round(R / 3) + DR - 1, "#8A6200", 2);
  const ppd = R / 3 / saneNear(cfg.near);
  circle(vx, vy, Math.round(cfg.tol * ppd) + DR - 1, "#00873F", 2);
  const [dx, dy] = bubbleOffset(ui.dot_x, ui.dot_y, cfg.near, R, DR + 2);
  circle(vx + dx, vy + dy, DR, null, 0, statusColor(ui.dot_status));

  const value = fmtDeg(Math.abs(r.deviation));
  const vw = textWidth(value, 48);
  const aw = ui.arrow ? 44 + 10 : 0;
  const x0 = CX - (aw + vw) / 2;
  if (ui.arrow) chevron(x0, CX + 92, ui.arrow > 0, c);
  text(value, x0 + aw + vw / 2, CX + 92, 48, c);

  // ui_aim_sub (ui_logic.c): the yaw, then a hint or the current angle
  const parts = [];
  if (ui.sub_yaw) parts.push([ui.sub_yaw, ui.sub_stale ? COL.amber : statusColor(r.yaw_status)]);
  if (ui.sub_info) parts.push([ui.sub_info, COL.grey]);
  const widths = parts.map(([t]) => textWidth(t, 24));
  let x = CX - (widths.reduce((a, b) => a + b, 0) + 16 * Math.max(0, parts.length - 1)) / 2;
  parts.forEach(([t, col], i) => {
    text(t, x + widths[i] / 2, CX + 136, 24, col);
    x += widths[i] + 16;
  });
}

function drawStatusBar(s) {
  const b = s.battery;
  const items = [];
  if (b.valid) {
    const lvl = s.ui.battery_level;
    const col = lvl === 0 ? COL.red : COL.grey;
    items.push({w: 34, draw: (x) => batteryIcon(x, cy, lvl, col)});
    const pct = `${b.percent}%`;
    items.push({w: textWidth(pct, 24), draw: (x, w) => text(pct, x + w / 2, cy, 24, col)});
  }
  if (b.valid && (b.charging || b.charge_done)) {
    items.push({w: 14, draw: (x) => boltIcon(x, cy, b.charge_done ? COL.green : COL.amber)});
  }
  if (s.ble_connected) items.push({w: 14, draw: (x) => btIcon(x, cy, COL.blue)});
  const cy = W - 38 - 14;
  const total = items.reduce((a, it) => a + it.w, 0) + 8 * Math.max(0, items.length - 1);
  let x = CX - total / 2;
  for (const it of items) { it.draw(x, it.w); x += it.w + 8; }
}

// Settings (ui_settings.c): the scrolling round list. Steppers show their values
// (ui_settings_format(), in the snapshot) but are not mirrored; the action
// buttons work. In ui_field_t order.
const FIELDS = [
  "Tolerance (green)", "Near band (amber)", "Target angle", "Brightness", "Dim after",
  "Sleep after", "Power off after sleep",
];

function drawSettings(s, view) {
  const cfg = s.settings;
  // flex column: pad top 70, row gap 30, pad bottom 150
  const rows = [
    {h: 27, kind: "title"}, {h: 19, kind: "hint"},
    ...FIELDS.map((label, i) => ({h: 92, kind: "step", label, i, nominal: 330, min: 240})),
    {h: 72, kind: "switch", str: "Bluetooth", key: "ble", icon: true, color: COL.blue, nominal: 330, min: 280},
    {h: 66, kind: "act", id: "set_mount", str: "Calibrate mount", nominal: 300, min: 240},
    {h: 66, kind: "act", id: "set_sensor", str: "Calibrate sensor", nominal: 300, min: 240},
    {h: 66, kind: "act", id: "set_clear_zero", str: "Clear zero", nominal: 300, min: 240},
    {h: 66, kind: "act", id: "set_reset", str: "Factory reset", color: COL.red, nominal: 300, min: 240},
    {h: 19, kind: "ver"},
    {h: 66, kind: "act", id: "set_back", str: "Back", back: true, color: COL.green, nominal: 300, min: 240},
  ];
  // ui_settings.c: the dot switch follows the target stepper
  rows.splice(2 + 3, 0, {h: 72, kind: "switch", str: "Invert dot", key: "dot_inv", color: COL.green, nominal: 330, min: 280});
  let y = 70;
  for (const row of rows) { row.y = y; y += row.h + 30; }
  settingsContentH = y - 30 + 150;
  settingsScroll = Math.max(0, Math.min(settingsScroll, settingsContentH - W));

  for (const row of rows) {
    const top = row.y - settingsScroll;
    const cy = top + row.h / 2;
    if (top > W || top + row.h < 0) continue;
    const [w, opa] = row.nominal ? roundRow(cy - CX, row.h, row.nominal, row.min, SAFE_R)
      : roundRow(cy - CX, row.h, 300, 300, SAFE_R);
    ctx.save();
    ctx.globalAlpha = opa;
    if (row.kind === "title") {
      const tw = textWidth("SETTINGS", 24);
      gearIcon(CX - tw / 2 - 22, cy, 11, COL.grey);
      text("SETTINGS", CX + 12, cy, 24, COL.grey);
    } else if (row.kind === "hint") {
      text("Swipe right or tap Back to leave", CX, cy, 16, COL.grey);
    } else if (row.kind === "step") {
      text(row.label, CX, top + 9, 16, COL.grey);
      const by = top + row.h - 32;
      button(null, CX - w / 2 + 32, by, 64, 64, "−", {alpha: opa});
      button(null, CX + w / 2 - 32, by, 64, 64, "+", {alpha: opa});
      text(s.ui.settings_text[row.i], CX, by, 24, COL.white);
      regions.push({id: "stepper", x: CX - w / 2, y: by - 32, w, h: 64, enabled: true});
    } else if (row.kind === "switch") {
      // ui_settings.c switch_row_new()
      if (row.icon) btIcon(CX - w / 2 + 16, cy, COL.white, 1);
      text(row.str, CX - w / 2 + (row.icon ? 44 : 16), cy, 24, COL.white, {align: "left"});
      const on = cfg[row.key];
      pill(CX + w / 2 - 8 - 96, cy - 26, 96, 52, on ? row.color : COL.dark);
      circle(on ? CX + w / 2 - 8 - 26 : CX + w / 2 - 8 - 70, cy, 31, null, 0, "#fff");
      regions.push({id: "stepper", x: CX - w / 2, y: top, w, h: row.h, enabled: true});
    } else if (row.kind === "ver") {
      text(`Firmware ${s.fw_version}`, CX, cy, 16, COL.grey);
    } else if (row.kind === "act") {
      button(row.id, CX, cy, w, row.h, row.str, {
        color: row.color, alpha: opa,
        icon: row.back ? (x, yy) => {
          const tw = textWidth("Back", 24);
          chevronLeft(x - tw / 2 - 18, yy, COL.green);
          text("Back", x + 10, yy, 24, COL.green, {alpha: opa});
        } : null,
      });
    }
    ctx.restore();
  }

  if (view.confirm) {
    regions = [];
    circle(CX, CX, CX, null, 0, "#000");
    warnIcon(CX, CX - 120, 44, COL.red);
    text("Factory reset?", CX, CX - 62, 24, COL.white);
    label("All settings and both\ncalibrations will be lost.", CX, CX - 16 - 20, 16, COL.grey);
    button("confirm_cancel", CX - 82, CX + 70, 150, 66, "Cancel");
    button("confirm_reset", CX + 82, CX + 70, 150, 66, "Reset", {bg: COL.redBg});
  }
}

function chevronLeft(x, cy, color) {
  ctx.save();
  ctx.strokeStyle = color;
  ctx.lineWidth = 4;
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.beginPath();
  ctx.moveTo(x + 9, cy - 9);
  ctx.lineTo(x, cy);
  ctx.lineTo(x + 9, cy + 9);
  ctx.stroke();
  ctx.restore();
}

const POSES = [[16, "Screen up"], [32, "Screen down"], [4, "Top edge up"], [8, "Bottom edge up"],
  [2, "Left edge up"], [1, "Right edge up"]];

function drawCal(s, cal) {
  const mount = cal.kind === "mount";
  text(mount ? "Mount calibration" : "Sensor calibration", CX, 56 + 14, 24, COL.grey);
  const p = cal.phase;
  if (p !== "result") {
    if (mount) {
      label(cal.body, CX, 94, 24, COL.white, 350, 29);
    } else {
      text("Hold still in each position", CX, 94 + 10, 16, COL.grey);
      const gx = CX - 156, gy = 94 + 28;
      POSES.forEach(([bit, name], i) => {
        const x = gx + (i % 2) * 162, y = gy + Math.floor(i / 2) * 58;
        const done = (cal.tiles & bit) !== 0;
        ctx.beginPath();
        ctx.roundRect(x + 1, y + 1, 148, 48, 14);
        ctx.fillStyle = done ? COL.green : "#000";
        ctx.fill();
        ctx.strokeStyle = done ? COL.green : "#5A5A5A";
        ctx.lineWidth = 2;
        ctx.stroke();
        text(name, x + 75, y + 25, 16, done ? "#000" : COL.white);
      });
    }
    if (p !== "ready") {
      if (mount) text(cal.count, CX, CX + 62, 24, COL.white);
      else text(cal.count, CX, CX + 72, 16, COL.grey);
    }
  } else {
    const lines = cal.result.split("\n").length;
    label(cal.result, CX, CX - 30 - (lines * 29) / 2, 24, cal.result_ok ? COL.green : COL.amber, 340, 29);
  }
  button("cal_left", CX - 76, CX + 125, 140, 64, cal.left, {enabled: cal.left_enabled});
  button("cal_right", CX + 76, CX + 125, 140, 64, cal.right,
    {enabled: cal.right_enabled, border: COL.green});
}

// Overlays on lv_layer_top(): passkey, OTA (above it), low battery, toast.
function drawOverlays(s, view) {
  if (s.passkey_pending) {
    regions = [];
    circle(CX, CX, CX, null, 0, "#000");
    const tw = textWidth("Pairing", 24);
    btIcon(CX - (tw + 30) / 2, CX - 100, COL.grey, 0.9);
    text("Pairing", CX + 15, CX - 100, 24, COL.grey);
    text(String(s.passkey % 1000000).padStart(6, "0"), CX, CX, 112, COL.white, {num: true, maxW: 400});
    label("Enter this code\non your phone", CX, CX + 104 - 29, 24, COL.grey, 0, 29);
  }
  if (s.ota_active) {
    regions = [];
    circle(CX, CX, CX, null, 0, "#000");
    const pct = Math.min(100, s.ota_percent);
    ctx.save();
    ctx.beginPath();
    ctx.arc(CX, CX, 160 - 8, 0, Math.PI * 2);
    ctx.strokeStyle = COL.dark;
    ctx.lineWidth = 16;
    ctx.stroke();
    ctx.restore();
    if (pct > 0) arc(CX, CX, 160, 16, 270, 270 + pct * 3.6, COL.green);
    text("Updating", CX, CX - 52, 24, COL.grey);
    text(`${pct} %`, CX, CX + 4, 48, COL.white);
    text("Do not switch off", CX, CX + 56, 16, COL.amber);
  }
  const lowBattery = s.ui.low_battery && !s.ota_active && !s.passkey_pending;
  if (lowBattery) {
    const b = LOWBAT, mid = b.y + b.h / 2;
    pill(CX - b.w / 2, b.y, b.w, b.h, COL.redBg);
    const str = `LOW ${s.battery.percent}%`;
    const tw = textWidth(str, 24);
    batteryIcon(CX - (tw + 48) / 2, mid, 0, COL.white);
    text(str, CX + 24, mid, 24, COL.white);
  }
  const rec = s.rec;
  if (rec && rec.state >= Touch.REC_READY && rec.state <= Touch.REC_DRAINING && !s.ota_active &&
      !s.passkey_pending) {
    // The REC pill (ui.c, ui_priv.h UI_REC_PILL_*): not modal, over the mode
    // title, below the low-battery banner when both show; held 2 s it stops
    // the take. Text as ui_logic.c ui_rec_pill_text().
    const live = rec.state === Touch.REC_RECORDING;
    const el = rec.elapsed_s;
    const str = live ? `REC ${String(Math.floor(el / 60)).padStart(2, "0")}:${String(el % 60).padStart(2, "0")}`
      : "REC ready";
    const p = REC_PILL, px = CX - p.w / 2;
    const py = lowBattery ? LOWBAT.y + LOWBAT.h + p.gap : LOWBAT.y;
    pill(px, py, p.w, p.h, live ? COL.red : COL.redBg);
    text(str, CX, py + p.h / 2, 24, COL.white);
    const hitRegion = Touch.recPillRegion(rec, px, py, p.w, p.h);
    if (hitRegion) regions.push(hitRegion);
  }
  if (view.toast) {
    const t = view.toast;
    const w = Math.max(340, textWidth(t.text, 48) + 60), h = 116;
    ctx.beginPath();
    ctx.roundRect(CX - w / 2, CX - 22 - h / 2, w, h, h / 2);
    ctx.fillStyle = "#000";
    ctx.fill();
    ctx.strokeStyle = t.color;
    ctx.lineWidth = 3;
    ctx.stroke();
    text(t.text, CX, CX - 22, 48, t.color);
  }
}

function draw() {
  const scale = canvas.width / W;
  ctx.setTransform(scale, 0, 0, scale, 0, 0);
  ctx.fillStyle = "#000";
  ctx.fillRect(0, 0, W, W);
  regions = [];
  const s = state && state.snap;
  const on = screenOn();
  // AMOLED brightness, roughly perceptual; dark when asleep or off
  canvas.style.filter = on ? `brightness(${Math.sqrt(Math.max(5, s.brightness) / 100).toFixed(3)})` : "";
  if (!on) {
    text(s ? deviceStateText(s) : "no connection", CX, CX, 16, "#333");
    return;
  }
  ctx.save();
  ctx.beginPath();
  ctx.arc(CX, CX, CX, 0, Math.PI * 2);
  ctx.clip();
  const view = state.screen;
  if (view.screen === "level") drawLevel(s);
  else if (view.screen === "settings") drawSettings(s, view);
  else drawCal(s, view.cal);
  drawOverlays(s, view);
  ctx.restore();
}

function deviceStateText(s) {
  if (s.device_state === "booting") return "rebooting...";
  if (s.device_state === "sleep") return "deep sleep";
  if (s.device_state === "off") return "off";
  return "screen off";
}

// ---- touch on the mirror (ui_level.c gesture rules) --------------------------------------

const TAP_MAX_MOVE = 24;        // ui_level.c TAP_MAX_MOVE_PX
const GESTURE_LIMIT = 50;       // LVGL gesture_limit
const LONG_PRESS_MS = 400;      // LVGL long_press_time: a tap is shorter
let press = null;               // the long-presses: touch.js

function devPoint(e) {
  const r = canvas.getBoundingClientRect();
  return {x: ((e.clientX - r.left) / r.width) * W, y: ((e.clientY - r.top) / r.height) * W};
}

function hit(p) {
  for (let i = regions.length - 1; i >= 0; i--) {
    const g = regions[i];
    if (p.x >= g.x && p.x <= g.x + g.w && p.y >= g.y && p.y <= g.y + g.h) return g;
  }
  return null;
}

function screenOn() {
  const s = state && state.snap;
  return s && s.device_state === "running" && s.power !== 2 && s.power !== 3;
}

canvas.addEventListener("pointerdown", (e) => {
  if (!screenOn()) return;
  canvas.setPointerCapture(e.pointerId);
  const p = devPoint(e);
  press = {x0: p.x, y0: p.y, x: p.x, y: p.y, t0: performance.now(), used: false,
    scroll0: settingsScroll, region: hit(p)};
  wsSend({cmd: "touch", action: "press"});
  const hold = Touch.holdFor(press.region);
  press.timer = setTimeout(() => {
    if (press && !press.used && (!hold.levelOnly || state.screen.screen === "level") && !movedFar(press)) {
      press.used = true;
      wsSend(hold.msg);
    }
  }, hold.ms);
});

function movedFar(pr) {
  return Math.hypot(pr.x - pr.x0, pr.y - pr.y0) > TAP_MAX_MOVE;
}

canvas.addEventListener("pointermove", (e) => {
  if (!press) return;
  const p = devPoint(e);
  press.x = p.x;
  press.y = p.y;
  const dx = p.x - press.x0, dy = p.y - press.y0;
  const scr = state.screen;
  if (scr.screen === "settings" && !scr.confirm && (press.scrolling ||
      (Math.abs(dy) > 10 && Math.abs(dy) > Math.abs(dx)))) {
    press.scrolling = true;   // a vertical drag scrolls the list instead of a gesture
    press.used = true;
    settingsScroll = press.scroll0 - dy;
    requestDraw();
    return;
  }
  if (!press.used && (Math.abs(dx) > GESTURE_LIMIT || Math.abs(dy) > GESTURE_LIMIT)) {
    press.used = true;
    const dir = Math.abs(dx) >= Math.abs(dy) ? (dx < 0 ? "left" : "right") : (dy < 0 ? "up" : "down");
    wsSend({cmd: "touch", action: "swipe", dir});
  }
});

function endPress(e, cancelled) {
  if (!press) return;
  clearTimeout(press.timer);
  const pr = press;
  press = null;
  if (cancelled || pr.used || movedFar(pr)) return;
  const g = hit({x: pr.x, y: pr.y});
  if (g && pr.region && g.id === pr.region.id) {
    if (g.id === "stepper") {
      showError("The settings steppers are not simulated: change settings from the app.");
    } else if (g.id === "rec_pill") {
      // only the 2 s hold does something
    } else if (g.enabled) {
      wsSend({cmd: "touch", action: "button", button: g.id});
    }
  } else if (!pr.region && performance.now() - pr.t0 < LONG_PRESS_MS) {
    wsSend({cmd: "touch", action: "tap"});
  }
}
canvas.addEventListener("pointerup", (e) => endPress(e, false));
canvas.addEventListener("pointercancel", (e) => endPress(e, true));
canvas.addEventListener("wheel", (e) => {
  if (state && state.screen.screen === "settings") {
    e.preventDefault();
    settingsScroll += e.deltaY;
    requestDraw();
  }
}, {passive: false});

// ---- controls -----------------------------------------------------------------------

const editing = new Set();   // inputs the user is touching: not overwritten from the state
function guard(el) {
  el.addEventListener("focus", () => editing.add(el));
  el.addEventListener("blur", () => editing.delete(el));
  el.addEventListener("pointerdown", () => editing.add(el));
  el.addEventListener("pointerup", () => setTimeout(() => editing.delete(el), 300));
}

function setValue(el, v) {
  if (!editing.has(el) && document.activeElement !== el) el.value = v;
}
function setChecked(el, v) {
  if (!editing.has(el)) el.checked = v;
}

// orientation sliders, throttled
let poseTimer = 0, posePending = {};
function sendPose(key, v) {
  posePending[key] = v;
  if (poseTimer) return;
  poseTimer = setTimeout(() => {
    poseTimer = 0;
    const cmd = {cmd: "pose", ...posePending};
    posePending = {};
    post(cmd);
  }, 40);
}
for (const key of ["elevation", "yaw", "roll"]) {
  const r = $(key), n = $(key + "-n");
  guard(r); guard(n);
  r.addEventListener("input", () => { n.value = r.value; sendPose(key, Number(r.value)); });
  n.addEventListener("change", () => {
    const v = Number(n.value);
    if (n.value === "" || !Number.isFinite(v)) return;
    r.value = v;
    sendPose(key, v);
  });
}
document.querySelectorAll("[data-preset]").forEach((b) =>
  b.addEventListener("click", () => post({cmd: "preset", name: b.dataset.preset})));
document.querySelectorAll("[data-face]").forEach((b) =>
  b.addEventListener("click", () => post({cmd: "face", face: b.dataset.face})));

// drill
function sendVib() {
  post({cmd: "vibration", g: $("vib-on").checked ? Number($("vib").value) : 0});
}
$("vib-on").addEventListener("change", sendVib);
guard($("vib"));
$("vib").addEventListener("input", () => {
  $("vib-v").textContent = `${Number($("vib").value).toFixed(2)} g`;
  if ($("vib-on").checked) sendVib();
});
guard($("noise"));
$("noise").addEventListener("input", () => {
  $("noise-v").textContent = `x${$("noise").value}`;
  post({cmd: "noise", level: Number($("noise").value)});
});

// calibration helpers
$("roll-start").addEventListener("click", () => post({cmd: "roll", on: true,
  amplitude: Number($("roll-amp").value), period: Number($("roll-period").value)}));
$("roll-stop").addEventListener("click", () => post({cmd: "roll", on: false}));
for (const id of ["mo-x", "mo-y", "mo-z"]) {
  guard($(id));
  $(id).addEventListener("change", () => post({cmd: "mount_offset",
    x: Number($("mo-x").value), y: Number($("mo-y").value), z: Number($("mo-z").value)}));
}
const vec = (p) => ["x", "y", "z"].map((a) => Number($(`${p}-${a}`).value));
["ab-x", "ab-y", "ab-z", "as-x", "as-y", "as-z"].forEach((id) => guard($(id)));
$("err-apply").addEventListener("click", () => post({cmd: "accel_error", bias: vec("ab"), scale: vec("as")}));
$("err-typical").addEventListener("click", () =>
  post({cmd: "accel_error", bias: [0.04, -0.03, 0.05], scale: [1.03, 0.97, 1.02]}));
$("err-clear").addEventListener("click", () =>
  post({cmd: "accel_error", bias: [0, 0, 0], scale: [1, 1, 1]}));

// power
guard($("bat"));
$("bat").addEventListener("input", () => { $("bat-v").textContent = `${$("bat").value} %`; });
$("bat").addEventListener("change", () => post({cmd: "battery", percent: Number($("bat").value)}));
for (const [id, key] of [["bat-vbus", "vbus"], ["bat-chg", "charging"], ["bat-done", "charge_done"],
  ["bat-valid", "valid"]]) {
  $(id).addEventListener("change", () => post({cmd: "battery", [key]: $(id).checked}));
}
$("pwr-short").addEventListener("click", () => post({cmd: "pwrkey", long: false}));
$("pwr-long").addEventListener("click", () => post({cmd: "pwrkey", long: true}));
$("nudge").addEventListener("click", () => post({cmd: "nudge"}));

// faults
$("f-imu").addEventListener("change", () => post({cmd: "imu", present: !$("f-imu").checked}));
$("f-busy").addEventListener("click", () => post({cmd: "busy", n: Number($("f-busy-n").value)}));
$("f-drop").addEventListener("click", () => post({cmd: "drop_ind",
  count: Number($("f-drop-n").value), after: Number($("f-drop-after").value)}));
$("f-slow-set").addEventListener("click", () => post({cmd: "slow_flash", ms: Number($("f-slow").value)}));
$("f-store").addEventListener("change", () => post({cmd: "store_fail", on: $("f-store").checked}));
$("f-flash").addEventListener("change", () => post({cmd: "flash_fail", on: $("f-flash").checked}));
$("f-disc").addEventListener("click", () => post({cmd: "disconnect"}));

// recorder
["sc-holes", "sc-drill", "sc-seed", "rec-stall-s", "mic-db"].forEach((id) => guard($(id)));
$("sc-start").addEventListener("click", () => {
  const cmd = {cmd: "scenario", mode: $("sc-mode").value, outcome: $("sc-outcome").value,
    holes: Number($("sc-holes").value), drill_s: Number($("sc-drill").value),
    stuck: $("sc-stuck").checked, kickback: $("sc-kick").checked};
  if ($("sc-seed").value !== "") cmd.seed = Number($("sc-seed").value);
  post(cmd);
});
$("sc-stop").addEventListener("click", () => post({cmd: "scenario", stop: true}));
$("rec-stall").addEventListener("click", () => post({cmd: "rec_stall", s: Number($("rec-stall-s").value)}));
$("rec-drop").addEventListener("click", () => post({cmd: "rec_client_drop"}));
$("mic-set").addEventListener("click", () => post({cmd: "mic_gain", db: Number($("mic-db").value)}));
$("rec-open").addEventListener("click", () => post({cmd: "rec", op: "open"}));
$("rec-start").addEventListener("click", () => post({cmd: "rec", op: "take_start",
  profile: $("rec-profile").value, mic_mask: Number($("rec-mics").value)}));
$("rec-stop").addEventListener("click", () => post({cmd: "rec", op: "stop"}));
$("rec-mark").addEventListener("click", () => post({cmd: "rec", op: "mark", text: "panel"}));
$("rec-close").addEventListener("click", () => post({cmd: "rec", op: "close"}));

// ---- panel update ---------------------------------------------------------------------

const POWER = ["active", "dim", "sleep", "off"];
const MODES = ["wall", "floor", "angle"];
const STATUS = ["on target", "near", "off"];
const CAL_KINDS = ["idle", "mount", "accel"];
const OTA_STATES = ["idle", "receiving", "done"];   // ota_state_t
const REC_STATES = ["idle", "AP starting", "waiting for client", "ready", "recording", "draining",
  "closing"];                                       // rec_state_t
const REC_REASONS = ["none", "user", "max_s", "client lost", "BLE lost", "battery", "IMU",
  "shutdown", "AP failed", "no client", "idle"];    // REC_REASON_*
const REC_PROFILES = ["LEVEL", "RAW448", "RAW448_16G", "RAW897"];

const f3 = (v) => v.map((x) => x.toFixed(3)).join("  ");

function updatePanel() {
  const st = state, s = st.snap, link = st.link;
  const running = s.device_state === "running";

  // header
  setPill($("hdr-adv"), s.advertising ? `advertising${link && link.advertising ? " (" + link.advertising + ")" : ""}`
    : "not advertising", s.advertising ? "blue" : "");
  if (!st.ble) setPill($("hdr-link"), "no BLE (--no-ble)", "warn");
  else if (s.connected) setPill($("hdr-link"), s.link && s.link.trusted ? "connected, paired" : "connected",
    s.link && s.link.trusted ? "ok" : "warn");
  else setPill($("hdr-link"), "no phone", "");
  $("hdr-dot").style.background = running ? COL.green : s.device_state === "booting" ? COL.amber : "#555";

  const power = running ? POWER[s.power] : s.device_state;
  const cap = document.createElement("b");
  cap.textContent = s.device_state;
  $("caption").replaceChildren(cap, ` · ${running ? `${power} · brightness ${s.brightness}%` : "screen dark"}` +
    ` · ${st.screen.screen.replace("_", " ")} screen`);

  // passkey
  const pk = running && s.passkey_pending;
  $("passkey").hidden = !pk;
  if (pk) {
    const code = String(s.passkey % 1000000).padStart(6, "0");
    $("passkey-code").textContent = `${code.slice(0, 3)} ${code.slice(3)}`;
  }

  // status
  $("st-device").textContent = `${s.device_state} · ${s.name} · sim ${(s.sim_ms / 1000).toFixed(1)} s`;
  const b = s.battery_input;
  $("st-power").textContent = running ? `${power}, battery ${b.percent}%${b.vbus ? ", USB" : ""}` +
    `${b.charging ? ", charging" : ""}${b.charge_done ? ", full" : ""}` : s.device_state;
  $("st-fw").textContent = s.fw_version + (s.reboot_armed ? " (reboot armed)" : "");
  if (running) {
    const r = s.reading;
    $("st-reading").textContent = !s.imu_ok ? "no sensor" :
      `${MODES[r.mode]}: elev ${fmtDeg(r.elevation, true)}, dev ${fmtDeg(r.deviation, true)}` +
      ` (${STATUS[r.status]})${r.yaw_valid ? `, yaw ${fmtDeg(r.yaw, true)}${r.yaw_stale ? " stale" : ""}` : ""}` +
      `${s.zero_set ? ", zero set" : ""}${s.still ? ", still" : ", moving"}`;
  } else {
    $("st-reading").textContent = "-";
  }
  if (link && link.peer) {
    $("st-peer").textContent = `${link.peer}${link.peer_name ? " (" + link.peer_name + ")" : ""}`;
  } else {
    $("st-peer").textContent = s.connected ? "connected" : "none";
  }
  const sec = [];
  if (s.connected) {
    if (link) sec.push(link.encrypted ? "encrypted" : "not encrypted");
    sec.push(s.link && s.link.trusted ? "authenticated (trusted)" : "untrusted");
    if (link) sec.push(link.paired ? "paired" : "not paired", link.bonded ? "bonded" : "no bond");
  }
  if (link && link.bonds.length) sec.push(`sim bonds: ${link.bonds.join(", ")}`);
  $("st-sec").textContent = sec.join(" · ") || "-";
  $("st-mtu").textContent = s.connected ? String(s.mtu) : "-";
  $("st-subs").textContent = s.subscriptions.length ? s.subscriptions.join(", ") : "-";
  if (running) {
    const c = s.cal;
    let t = `mount ${s.mount_cal_valid ? "✓" : "–"}, sensor ${s.accel_cal_valid ? "✓" : "–"}`;
    if (c.kind) t += ` · ${CAL_KINDS[c.kind]} running: ${c.samples} samples` +
      (c.kind === 2 ? `, ${popcount(c.coverage)}/6 sides` : "");
    if (s.ui.cal_result_known) t += ` · last: ${s.ui.cal_result_text.replace(/\n/g, " ")}`;
    $("st-cal").textContent = t;
    $("cal-axis").textContent = f3(s.drill_axis);
    $("cal-bias").textContent = f3(s.accel_bias);
    $("cal-scale").textContent = f3(s.accel_scale);
  }

  // orientation
  const p = s.physics;
  setPill($("pose-mode"), p.board_face ? `on the table, ${p.board_face} up` : p.moving ? "drill, moving" : "drill",
    p.board_face ? "warn" : p.moving ? "blue" : "");
  for (const key of ["elevation", "yaw", "roll"]) {
    setValue($(key), p[key]);
    setValue($(key + "-n"), Number(p[key].toFixed(1)));
  }
  setValue($("mo-x"), p.mount_offset[0]);
  setValue($("mo-y"), p.mount_offset[1]);
  setValue($("mo-z"), p.mount_offset[2]);
  ["x", "y", "z"].forEach((a, i) => {
    setValue($(`ab-${a}`), st.sim.accel_bias[i]);
    setValue($(`as-${a}`), st.sim.accel_scale[i]);
  });
  const cov = running && s.cal.kind === 2 ? s.cal.coverage : 0;
  document.querySelectorAll("[data-face]").forEach((btn) => {
    btn.classList.toggle("done", (cov & Number(btn.dataset.bit)) !== 0);
    btn.classList.toggle("on", p.board_face === btn.dataset.face);
  });
  $("roll-start").classList.toggle("on", st.sim.roll_running);

  // drill
  setPill($("vib-state"), running ? (s.vibrating ? "detected" : "quiet") : "-", running && s.vibrating ? "ok" : "");
  setChecked($("vib-on"), p.vibration_g > 0);
  if (p.vibration_g > 0) {
    setValue($("vib"), p.vibration_g);
    if (!editing.has($("vib"))) $("vib-v").textContent = `${p.vibration_g.toFixed(2)} g`;
  }
  setValue($("noise"), st.sim.noise);
  if (!editing.has($("noise"))) $("noise-v").textContent = `x${st.sim.noise}`;

  // power
  setValue($("bat"), b.percent);
  if (!editing.has($("bat"))) $("bat-v").textContent = `${b.percent} %`;
  setChecked($("bat-vbus"), b.vbus);
  setChecked($("bat-chg"), b.charging);
  setChecked($("bat-done"), b.charge_done);
  setChecked($("bat-valid"), b.valid);

  // faults
  const f = s.faults;
  setChecked($("f-imu"), !f.imu_present);
  setChecked($("f-store"), f.store_fail);
  setChecked($("f-flash"), f.flash_fail);
  $("f-disc").disabled = !s.connected;
  const armed = [];
  if (!f.imu_present) armed.push("IMU lost");
  if (f.drop_ota_indications) armed.push(`${f.drop_ota_indications} OTA indication(s) to drop`);
  if (f.ota_write_delay_ms) armed.push(`flash ${f.ota_write_delay_ms} ms/write`);
  if (f.store_fail) armed.push("store fails");
  if (f.flash_fail) armed.push("flash fails");
  $("f-armed").textContent = armed.length ? "Armed: " + armed.join(", ") : "No faults armed.";

  // OTA
  const active = running && s.ota_active;
  setPill($("ota-state"), active ? "receiving" : s.reboot_armed ? "rebooting" :
    running ? OTA_STATES[s.ota_state] || `state ${s.ota_state}` : "idle", active ? "ok" : "");
  $("ota-bar").style.width = `${active ? s.ota_percent : 0}%`;
  $("ota-pct").textContent = active ? `${s.ota_percent} % · ${(st.ota.bytes / 1024).toFixed(0)} KB received` : "-";
  $("ota-rate").textContent = active ? `${st.ota.kbps.toFixed(1)} KB/s` : "-";
  const last = st.ota.last;
  $("ota-last").textContent = last.bytes ? `${(last.bytes / 1024).toFixed(0)} KB in ${last.seconds.toFixed(1)} s` +
    ` (${(last.bytes / 1024 / Math.max(0.1, last.seconds)).toFixed(1)} KB/s)` : "-";

  // recorder
  updateRecorder(s, running);

  // settings
  const json = running ? JSON.stringify(s.settings, null, 2) : "(device not running)";
  if ($("settings").textContent !== json) $("settings").textContent = json;
}

function updateRecorder(s, running) {
  const r = s.rec, dev = s.recorder;
  if (!running || !r) {
    setPill($("rec-state"), "-", "");
    return;
  }
  setPill($("rec-state"), REC_STATES[r.state] || `state ${r.state}`,
    r.state === Touch.REC_RECORDING ? "bad" : r.state === 0 ? "" : r.state === Touch.REC_READY ? "ok" : "warn");
  $("rec-take").textContent = r.take_valid ? `${r.take_id} · ${r.elapsed_s}/${r.max_s} s · ` +
    `${REC_PROFILES[r.profile] || r.profile} · mics 0x${r.mic_mask.toString(16)} ${r.gain_db} dB` +
    (r.state === Touch.REC_RECORDING ? "" : ` · ended: ${REC_REASONS[r.reason] || r.reason}`) :
    (r.reason ? `last session ended: ${REC_REASONS[r.reason] || r.reason}` : "-");
  const ep = dev.endpoint;
  $("rec-stream").textContent = ep ? `${ep.mode.toUpperCase()} ${ep.ip}:${ep.port} · ` +
    `${ep.client ? "client " + ep.peer : "no client"} · ${ep.kbps.toFixed(1)} kB/s · ` +
    `${(r.sent_bytes / 1048576).toFixed(2)} MB${ep.stalled ? " · STALLED" : ""}` :
    dev.unavailable ? `unavailable: ${dev.unavailable}` : "no stream server (--no-rec)";
  const ss = dev.session;
  $("rec-session").textContent = ss ? `${ss.ip}:${ss.port}${ss.no_ap ? " (LAN)" : ` SSID ${ss.ssid}`}` +
    ` · token ${ss.token}` : "none (Open starts one)";
  $("rec-ring").textContent = `${r.ring_pct} % of ${(r.ring_size / 1024).toFixed(0)} KiB, high ` +
    `${(r.high_water / 1024).toFixed(0)} KiB · dropped ${r.dropped} records (${r.dropped_bytes} B)`;
  const lostPct = r.imu_frames ? (100 * r.imu_lost / (r.imu_frames + r.imu_lost)).toFixed(1) : "0";
  $("rec-data").textContent = `IMU ${r.imu_frames} (${lostPct} % lost` +
    `${r.imu_period_ns ? `, ${(1e9 / r.imu_period_ns).toFixed(1)} Hz` : ""}) · audio ${r.audio_frames}` +
    `${r.audio_rate_mhz ? ` (${(r.audio_rate_mhz / 1000).toFixed(0)} Hz)` : ""}` +
    `${r.audio_clipped ? `, ${r.audio_clipped} clipped` : ""}`;
  const sc = dev.scenario;
  $("rec-scenario").textContent = sc ? `${sc.params.mode}, ${sc.params.holes} hole(s), ` +
    `${sc.params.outcome}${sc.params.stuck ? ", stuck" : ""}${sc.params.kickback ? ", kickback" : ""} · ` +
    `${sc.elapsed_s.toFixed(1)}/${sc.duration_s.toFixed(1)} s · seed ${sc.params.seed}` : "none running";
  const tr = dev.truth;
  $("rec-truth").textContent = tr ? `take ${tr.take_id}${tr.running ? " (running)" : ""}: ` +
    (Object.entries(tr.events).map(([k, n]) => `${n} ${k}`).join(", ") || "no events") +
    (tr.spans ? `, ${tr.spans} span(s)` : "") : "-";
  $("rec-drop").disabled = !(ep && ep.client);
}

function popcount(v) { let n = 0; for (; v; v &= v - 1) n++; return n; }

// ---- event log ------------------------------------------------------------------------

const logEntries = [];
const LOG_MAX = 600;
let logRender = false;

function addLog(entries) {
  for (const e of entries) logEntries.push(e);
  if (logEntries.length > LOG_MAX) logEntries.splice(0, logEntries.length - LOG_MAX);
  if (!logRender) { logRender = true; setTimeout(renderLog, 150); }
}

function renderLog() {
  logRender = false;
  const show = {};
  document.querySelectorAll("[data-src]").forEach((c) => { show[c.dataset.src] = c.checked; });
  const reads = $("log-reads").checked;
  const box = $("log");
  const frag = document.createDocumentFragment();
  let n = 0;
  for (let i = logEntries.length - 1; i >= 0 && n < 400; i--) {
    const e = logEntries[i];
    if (show[e.src] === false || (e.kind === "read" && !reads)) continue;
    n++;
    const row = document.createElement("div");
    row.className = `e ${e.level} src-${e.src} ${e.kind}`;
    const d = new Date(e.t * 1000);
    const ts = d.toTimeString().slice(0, 8) + "." + String(d.getMilliseconds()).padStart(3, "0");
    for (const [cls, txt] of [["t", ts], ["s", e.src], ["m", e.msg]]) {
      const span = document.createElement("span");
      span.className = cls;
      span.textContent = txt;
      row.appendChild(span);
    }
    frag.appendChild(row);
  }
  box.replaceChildren(frag);
  $("log-count").textContent = String(logEntries.length);
}
document.querySelectorAll("[data-src], #log-reads").forEach((c) => c.addEventListener("change", renderLog));
$("log-clear").addEventListener("click", () => { logEntries.length = 0; renderLog(); });

// First paint from one HTTP fetch; the websocket takes over from there.
fetch("/api/state").then((r) => r.json()).then((m) => {
  if (!state) { state = m; updatePanel(); requestDraw(); }
}).catch(() => {});
connect();
draw();
