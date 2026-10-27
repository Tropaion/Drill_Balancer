"""Synthetic recordings with ground truth, from the device simulator.

There is one drilling model: the simulator's (firmware/tools/sim,
dbsim.physics.DrillScenario for the motion, the motor and the impacts;
core/sim_audio.c for the microphones). synth drives a whole simulated
device the way the app does: it opens a recorder session over the
simulated BLE link, starts a take, plays a DrillScenario during it, stops
the take and pulls the stream (rec_pop, in process). The take then goes
through dbdata.dbrec's decoder and export like a real one, so a synthetic
recording carries everything a device recording carries: the FIFO-loss
model's filled samples, the profile switch at the take start, the DMA
block timestamps, the device's own LEVEL rows. The scenario's ground truth
(DbSimDevice.rec_truth, device microseconds) becomes events.json with
source "user" and confidence 1, and meta.app.tool is "dbdata.synth".

    python -m dbdata.synth ROOT [--set NAME] [--count N]     # write recordings under ROOT
    python -m dbdata.synth --list                            # the scenario sets
    python -m dbdata.synth fixtures DIR                      # regenerate the autodetect fixtures

The simulator needs its C core (libdbsim.so, built on demand with cmake
and the ESP-IDF tree, see tools/sim/README.md) but none of its Python
requirements: DbSimDevice uses only the standard library.
"""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
from dataclasses import asdict, dataclass, field
from pathlib import Path

from . import contract

SIM_DIR = Path(__file__).resolve().parents[2] / "sim"
TOOL = "dbdata.synth"
INSTALL = "synt"                       # the install4 of synthetic recording ids
STEP_MS = 20                           # the client pops the stream every pass
TRUSTED_MS = 3000                      # settle the booted device a little past DbSimDevice.IMU_TRUSTED_MS


class SimUnavailable(RuntimeError):
    """The simulator (or its C core) cannot be loaded here."""


_SIM = None


def sim_modules():
    """(dbsim.device, dbsim.core, db_client as dbsim.wire.dc), imported from firmware/tools/sim."""
    global _SIM
    if _SIM is not None:
        return _SIM
    if not (SIM_DIR / "dbsim" / "device.py").is_file():
        raise SimUnavailable(f"no simulator at {SIM_DIR}")
    if str(SIM_DIR) not in sys.path:
        sys.path.insert(0, str(SIM_DIR))
    try:
        from dbsim import core, device
        from dbsim.wire import dc

        core.Core()            # builds / loads libdbsim.so
    except (ImportError, OSError, RuntimeError) as e:
        raise SimUnavailable(f"the simulator cannot be loaded: {e}") from e
    _SIM = (device, core, dc)
    return _SIM


def sim_unavailable_reason() -> str | None:
    """None when synth can run, else why not (for unittest.skipIf)."""
    try:
        sim_modules()
    except SimUnavailable as e:
        return str(e)
    return None


# ---- what to simulate --------------------------------------------------------------

@dataclass(frozen=True)
class Spec:
    """One synthetic take.

    scenario: physics.DrillScenario arguments (seed, mode, holes, outcome,
    free_hz, sag, spinup_s, free_s, drill_s, gap_s, lead_s, stuck, kickback,
    level, stuck_exit). profile: the take's IMU profile (None: RAW448_16G
    for hammer, else RAW448). noise: factor on the simulated sensor noise
    (accel, gyro) and on the room's sound; room: a further factor on the
    room's sound alone. elevation_deg / yaw_deg: the drill pose.
    mount_offset: the board's tilt on the drill (degrees about drill X, Y,
    Z); when set, the device's mount calibration runs first, so meta.imu.mount
    is valid. lead_s: take start to scenario start (negative: the scenario
    starts first, so the motor may already run when the data begins); tail_s:
    scenario end to take stop (negative: the take stops before the scenario
    ends).
    ring_kib / stall: a stream ring of that size (0: the simulator's) and a
    client that stops reading for (at_s, duration_s) from the scenario start,
    so the device drops records (a transport gap). inject: signals the
    simulator has no model for, added to the exported files (INJECTIONS, LOUD_LOAD).
    drill_type / motor: meta.session.drill (None: for hammer an SDS rotary
    hammer with a masonry_sds bit, else a combi drill; a drill/driver
    otherwise; the simulator models one brushless drill).
    """

    name: str
    scenario: dict = field(default_factory=dict)
    profile: str | None = None
    gain_db: int = 6
    mic_mask: int = 3
    noise: float = 1.0
    room: float = 1.0
    elevation_deg: float = 0.0
    yaw_deg: float = 0.0
    device_seed: int = 1
    material: str = "wood/softwood"
    bit_type: str = "brad_point"
    bit_diameter_mm: float = 8.0
    drill_type: str | None = None
    motor: str = "brushless"
    lead_s: float = 0.4
    tail_s: float = 0.4
    mount_offset: tuple = (0.0, 0.0, 0.0)
    ring_kib: int = 0
    stall: tuple = ()
    inject: tuple = ()

    @property
    def mode(self) -> str:
        return self.scenario.get("mode", "drill")

    @property
    def drill_type_id(self) -> str:
        if self.drill_type:
            return self.drill_type
        if self.mode == "hammer":     # an SDS bit fits only an SDS rotary hammer
            return "rotary_hammer" if self.bit_type == "masonry_sds" else "combi_drill"
        return "drill"

    @property
    def profile_name(self) -> str:
        return self.profile or ("RAW448_16G" if self.mode == "hammer" else "RAW448")

    def to_dict(self) -> dict:
        return asdict(self)


def _s(name, **kw):
    sc = {k: kw.pop(k) for k in list(kw) if k not in Spec.__dataclass_fields__}
    return Spec(name=name, scenario=sc, **kw)


# Signals the simulator does not model, for the auto-detector's suggestion rules:
# what each adds to the exported files, and the span it is labelled with.
INJECTIONS = ("wobble", "wander", "speech")
WOBBLE_DPS, WOBBLE_HZ = 15.0, 4.0          # a radial gyro swing (board X) while cutting
WANDER_CDEG_PER_S = 300                    # the elevation (and deviation) drift 3 deg in the first second of the hole
SPEECH_FS, SPEECH_MOD_HZ = 0.03, 4.0       # band noise, 4 Hz syllable rate, from 1 s after motor_off
# Not for a suggestion rule: the cut louder than the free run by more than motor.off_level_db
# (hard material), so that a take ending in the free run after it looks like a spin-down by energy.
LOUD_LOAD = "loud_load"
LOUD_LOAD_DB = 12.0                        # the sound from hole 1's contact to its load end


# Scenario sets. "eval": the auto-detector's evaluation (tests/test_autodetect.py);
# "fixtures": short takes exported as golden fixtures for the app's Kotlin port.
SETS: dict[str, list[Spec]] = {
    "eval": [
        _s("wood_233", seed=101, holes=2, free_hz=233.0, sag=0.15),
        _s("wood_147", seed=102, holes=2, free_hz=147.0, sag=0.12, device_seed=2),
        _s("hard_sag30", seed=103, holes=1, free_hz=233.0, sag=0.30, drill_s=3.5,
           material="wood/hardwood"),
        _s("metal_slow", seed=104, holes=2, free_hz=147.0, sag=0.25, drill_s=3.0,
           material="metal/steel", bit_type="twist_hss", bit_diameter_mm=6.0),
        _s("blind_retract", seed=105, holes=2, outcome="retract", sag=0.18),
        _s("mixed_3", seed=106, holes=3, outcome="mixed", sag=0.2, gap_s=0.8),
        _s("stuck", seed=107, holes=1, stuck=True, sag=0.25),
        _s("stuck_kick", seed=108, holes=2, stuck=True, kickback=True, sag=0.25, device_seed=3),
        _s("kick_breakthrough", seed=109, holes=1, kickback=True, sag=0.2),
        _s("hammer_concrete", seed=110, mode="hammer", holes=2, sag=0.2,
           material="masonry/concrete", bit_type="masonry_sds", bit_diameter_mm=10.0),
        _s("hammer_brick", seed=111, mode="hammer", holes=1, outcome="retract", sag=0.15,
           material="masonry/brick", bit_type="masonry_cyl", bit_diameter_mm=6.0, device_seed=4),
        _s("quiet_drill", seed=112, holes=2, level=0.5, sag=0.15, device_seed=5),
        _s("noisy_room", seed=113, holes=2, noise=4.0, sag=0.2, device_seed=6),
        _s("noisy_quiet", seed=114, holes=1, noise=3.0, level=0.6, sag=0.2, device_seed=7),
        _s("floor", seed=115, holes=2, elevation_deg=-88.0, sag=0.2, device_seed=8),
        _s("ceiling_fast", seed=116, holes=1, elevation_deg=85.0, spinup_s=0.3, free_s=0.6,
           drill_s=1.5, sag=0.1, device_seed=9),
        _s("long_spinup", seed=117, holes=1, spinup_s=0.8, free_s=1.5, sag=0.2),
        _s("raw897", seed=118, holes=1, sag=0.2, profile="RAW897"),
        # the review's regressions: a stall while hammering, a quiet slow motor's restart in the
        # hole, blind holes with a small sag
        _s("hammer_stuck", seed=119, mode="hammer", holes=1, stuck=True, sag=0.25, material="masonry/concrete",
           bit_type="masonry_sds", bit_diameter_mm=10.0, device_seed=10),
        _s("stuck_quiet_slow", seed=120, holes=1, stuck=True, sag=0.25, level=0.5, free_hz=147.0,
           device_seed=11),
        _s("blind_small_sag", seed=507, holes=2, outcome="retract", sag=0.109, level=0.654, free_hz=234.6,
           noise=0.846, device_seed=7),
    ],
    "fixtures": [   # MIC1 only (the channel the detector reads): half the size
        _s("fx_drill_through", seed=201, holes=1, sag=0.2, free_s=0.8, drill_s=1.8, lead_s=0.6, mic_mask=1),
        _s("fx_hammer_retract", seed=202, mode="hammer", holes=1, outcome="retract", sag=0.18,
           free_s=0.8, drill_s=1.5, lead_s=0.6, mic_mask=1, material="masonry/concrete",
           bit_type="masonry_sds"),
        _s("fx_stuck_kick", seed=203, holes=1, stuck=True, kickback=True, sag=0.25, free_s=0.8,
           drill_s=1.5, lead_s=0.6, mic_mask=1),
        # RAW897, two holes (a breakthrough, then a retract), a calibrated mount axis
        _s("fx_raw897_mount", seed=204, holes=2, outcome="mixed", sag=0.2, free_s=0.6, drill_s=1.2,
           gap_s=0.6, lead_s=0.6, profile="RAW897", mic_mask=1, mount_offset=(-3.0, 2.5, 1.0)),
        # no microphone: only the motor runs are found (dataset README, known limits)
        # (the motor already runs when the data begins: motor_on at T0)
        _s("fx_noaudio", seed=208, holes=1, sag=0.2, free_s=0.8, drill_s=1.5, lead_s=-0.6, mic_mask=0),
        # a stall, the bit pulled out with the motor off, a spin-up cut off by the take's end
        _s("fx_pulled_open", seed=205, holes=1, stuck=True, stuck_exit="pull", sag=0.25, free_s=0.8,
           drill_s=1.5, lead_s=0.6, tail_s=-1.9, mic_mask=1),
        # a transport gap (the ring overflows while the client stalls) in the middle of a hole, and
        # a kickback as the bit breaks through
        _s("fx_gap", seed=206, holes=1, sag=0.2, free_s=0.8, drill_s=3.0, lead_s=0.6, mic_mask=1,
           ring_kib=32, stall=(2.3, 1.6), kickback=True),
        # wobble, wander and speech, which the simulator has no model for, injected
        _s("fx_suggestions", seed=207, holes=1, sag=0.2, free_s=0.8, drill_s=1.8, lead_s=0.6, tail_s=3.0,
           mic_mask=1, inject=INJECTIONS),
        # the take ends 0.4 s after the motor_off, in its spin-down: no low stretch in the data
        _s("fx_spindown_end", seed=209, holes=1, sag=0.2, free_s=0.8, drill_s=1.5, lead_s=0.6, tail_s=-0.7,
           mic_mask=1),
        # a cut 12 dB louder than the free run, and the take ends 0.3 s before the motor_off, in the
        # free run after the breakthrough: the motor still runs (no motor_off)
        _s("fx_loud_free_end", seed=210, holes=1, sag=0.2, free_s=0.5, drill_s=3.5, lead_s=0.6, tail_s=-1.4,
           mic_mask=1, inject=(LOUD_LOAD,)),
        # a 147 Hz motor, the take ends 0.5 s after the motor_off: its pitch has fallen out of the
        # f0 tracker's range, so the last off_hold_ms has no voiced frame (a slowing motor too)
        _s("fx_slow_spindown_end", seed=211, holes=1, free_hz=147.0, sag=0.15, free_s=0.8, drill_s=1.5,
           lead_s=0.6, tail_s=-0.6, mic_mask=1),
    ],
}


@dataclass
class Synth:
    """What synthesize() wrote: the folder, its meta and events (the truth), the raw truth."""

    path: Path
    meta: dict
    events: dict
    truth: dict
    spec: Spec


def recording_id(n: int) -> str:
    """A valid id for the n-th synthetic recording (2026-10-26, one second apart)."""
    s = 14 * 3600 + 30 * 60 + n
    return f"20261026T{s // 3600:02d}{s // 60 % 60:02d}{s % 60:02d}Z_{INSTALL}_{n % 10000:04d}"


def mount_calibration(dev, dc, offset):
    """The board tilted on the drill by offset (degrees), then the device's mount
    calibration as a user does it: hold still, roll the drill +-50 deg about the
    bit three times, hold still, finish."""
    ph = dev.physics
    ph.mount_offset = tuple(float(v) for v in offset)
    ph.set_drill(elevation=0.0, yaw=0.0, roll=0.0)
    dev.advance(3000)

    def cmd(name):
        if dev.write_char("command", dc.encode_cmd(name)):
            raise RuntimeError(f"command {name} refused")
        dev.advance(20)

    cmd("mount-cal-start")
    dev.advance(1500)
    ph.roll_back_and_forth(50.0, period_s=4.0, cycles=3)
    dev.advance(13000)
    cmd("mount-cal-finish")
    dev.advance(500)
    if not dev.snapshot()["mount_cal_valid"]:
        raise RuntimeError("the mount calibration failed")


def truth_events(truth: dict, coverage: list[int], rid: str) -> dict:
    """events.json from a DbSimDevice truth (device us): every label source "user",
    confidence 1. Holes: through from the scenario, None when the take ends in the
    hole; bit_stuck / kickback tags where the truth has a stuck span / a kickback in
    that hole, else clean for a hole the take saw end (contract.hole_entry, as the detector). A motor
    already running at the coverage start gets motor_on there."""
    c0, c1 = coverage
    evs = [e for e in truth["events"] if c0 <= e["t_us"] < c1]
    # The bit is in a hole from its contact to its breakthrough / retract, the motor running or
    # not (a stall, the spin-up of a restart in the hole): a take that begins there would start
    # with a hole's events but no contact, which no label set can say.
    in_hole = None
    for e in sorted((e for e in truth["events"] if e["t_us"] < c0), key=lambda e: e["t_us"]):
        if e["type"] == "contact":
            in_hole = e.get("hole")
        elif e["type"] in ("breakthrough", "retract"):
            in_hole = None
    # A DbSimDevice truth has no events before the take's start: there it shows as a hole's event
    # (a motor_on / motor_off in it, its end) before any contact.
    for e in sorted(evs, key=lambda e: e["t_us"]):
        if e["type"] == "contact":
            break
        if e.get("hole") is not None:
            in_hole = e["hole"]
            break
    at_c0 ={sg["state"] for sg in truth.get("segments", ()) if sg["t0_us"] <= c0 < sg["t1_us"]}
    if in_hole is not None or at_c0 & {"load", "hammer", "stuck"}:
        raise ValueError("the data begins with the bit in a hole: no truth for that")
    # a motor already running when the data begins: motor_on there (as the detector labels an open run)
    if at_c0 - {"off", "spindown"}:
        evs.insert(0, {"type": "motor_on", "t_us": c0})
    out = []
    for i, e in enumerate(sorted(evs, key=lambda e: e["t_us"])):
        d = {"id": f"e{i + 1}", "type": e["type"], "t_us": int(e["t_us"]), "source": "user", "confidence": 1.0}
        if e.get("hole") and e["type"] not in ("motor_on", "motor_off"):
            d["hole"] = int(e["hole"])
        out.append(d)
    spans = []
    for sp in truth["spans"]:
        a, b = max(c0, int(sp["t0_us"])), min(c1, int(sp["t1_us"]))
        if b > a:
            spans.append({"id": f"s{len(spans) + 1}", "type": sp["type"], "t0_us": a, "t1_us": b, "hole": int(sp["hole"]),
                          "source": "user", "confidence": 1.0})
    used = {d["hole"] for d in out if "hole" in d}
    holes = []
    for h in truth["holes"]:
        n = int(h["hole"])
        if n not in used:
            continue
        ended = any(d["type"] in ("breakthrough", "retract") and d.get("hole") == n for d in out)
        tags = []
        if any(s["type"] == "stuck" and s["hole"] == n for s in spans):
            tags.append("bit_stuck")
        if any(d["type"] == "kickback" and d.get("hole") == n for d in out):
            tags.append("kickback")
        holes.append(contract.hole_entry(n, bool(h["through"]) if ended else None, tags))
    return contract.events_document(rid, coverage, revision=1, events=out, spans=spans, holes=holes)


def record_take(spec: Spec, state_dir: Path) -> tuple[bytes, dict]:
    """Runs spec on a fresh simulated device: (the stream bytes, the take's ground truth)."""
    device, core, dc = sim_modules()
    dev = device.DbSimDevice(state_dir, seed=spec.device_seed, boot=False)
    ph = dev.physics
    ph.accel_noise_g *= spec.noise
    ph.gyro_noise_rad_s *= spec.noise
    ph.audio_ambient *= spec.noise * spec.room
    ph.set_drill(elevation=spec.elevation_deg, yaw=spec.yaw_deg)
    if spec.ring_kib:
        dev.rec_ring_size = spec.ring_kib * 1024
    dev.power_on()
    dev.advance(500)
    if not dev.connect():
        raise RuntimeError("the simulated device refused the link")
    dev.encryption_changed(True, True)
    dev.settle_imu()                    # the IMU rate measured, then a margin
    dev.advance(max(0, TRUSTED_MS - dev.core_now))

    def ctrl(op, **kw):
        att = dev.write_char("rec_ctrl", dc.encode_rec(op, **kw))
        if att:
            raise RuntimeError(f"rec_ctrl {op}: ATT error 0x{att:02x}")

    if any(spec.mount_offset):
        mount_calibration(dev, dc, spec.mount_offset)
        ph.set_drill(elevation=spec.elevation_deg, yaw=spec.yaw_deg)
        dev.advance(2000)

    buf = bytearray()
    t_scn = None

    def run(ms):
        for _ in range(int(round(ms / STEP_MS))):
            dev.advance(STEP_MS)
            if spec.stall and t_scn is not None and \
                    0 <= dev.now_ms / 1000 - t_scn - spec.stall[0] < spec.stall[1]:
                continue                # the client does not read: the ring fills, records drop
            buf.extend(dev.rec_pop())

    ctrl("open")
    dev.advance(STEP_MS)
    rs = dc.decode_rec_state(dev.read_char("rec_state"))
    if not dev.rec_hello(dc.encode_hello(rs.token)):
        raise RuntimeError("the simulated device refused the stream client")
    dev.advance(STEP_MS)
    def take_start():
        ctrl("take-start", profile=dc.REC_PROFILES.index(spec.profile_name), mic_mask=spec.mic_mask,
             gain_db=spec.gain_db, take_id=1)

    if spec.lead_s >= 0:
        take_start()
        run(spec.lead_s * 1000)
    scn = dev.start_scenario(**spec.scenario)
    t_scn = dev.now_ms / 1000
    if spec.lead_s < 0:
        run(-spec.lead_s * 1000)
        take_start()
    run((scn.duration + spec.tail_s + min(0.0, spec.lead_s)) * 1000)
    ctrl("stop")
    run(400)
    truth = dev.rec_truth()
    if truth is None or truth.get("running"):
        raise RuntimeError("the take did not end")
    return bytes(buf), truth


def inject(folder: Path, names, events: dict, seed: int = 3) -> tuple[dict, dict]:
    """Adds the INJECTIONS (and LOUD_LOAD) named to an exported recording (rewritten with
    io.write_recording, derived labels recomputed) and their spans to events: (meta, events)."""
    import numpy as np

    from .io import (DEVICE_EVENTS_FILE, IMU_FILE, LEVEL_FILE, LEVEL_MODES, SYNC_FILE, read_audio,
                     read_device_events_csv, read_imu_csv, read_level_csv, read_sync_csv, write_recording)
    from .io import SyncFit
    from .contract import read_json

    # what each injection needs: the anchors below, and the audio
    need = {"wobble": ("contact",), "wander": ("contact",), "speech": ("motor_off", "audio"),
            LOUD_LOAD: ("contact", "audio")}
    bad = set(names) - need.keys()
    if bad:
        raise ValueError(f"unknown injections {sorted(bad)}")
    folder = Path(folder)
    meta = read_json(folder / "meta.json")
    events = json.loads(json.dumps(events))
    c0, c1 = events["coverage_us"]
    # the anchors: hole 1 from its contact to its load end (breakthrough or retract; the coverage
    # end for a take that ends in the hole), and the first motor_off
    ev = {}
    for e in sorted(events["events"], key=lambda e: e["t_us"]):
        ev.setdefault(e["type"], e["t_us"])
        if e["type"] in ("breakthrough", "retract") and "contact" in ev:
            ev.setdefault("load_end", e["t_us"])
    if "contact" in ev:
        ev.setdefault("load_end", c1)
    have = set(ev) | ({"audio"} if meta.get("audio") else set())
    for name in names:
        missing = [a for a in need[name] if a not in have]
        if missing:
            raise ValueError(f"inject {name}: the take has no {', '.join(missing)}")
    imu = read_imu_csv(folder / IMU_FILE)
    level = read_level_csv(folder / LEVEL_FILE)
    audio = sync = ta = None
    if meta.get("audio"):
        audio, _ = read_audio(folder / meta["audio"]["file"])
        sync = read_sync_csv(folder / SYNC_FILE)
        ta = SyncFit.fit(sync["frame"], sync["t_us"]).t_of(np.arange(len(audio)))   # each sample's time

    def pcm16(x):
        return np.clip(np.round(x), -32768, 32767).astype(np.int16)
    de_path = folder / DEVICE_EVENTS_FILE
    dev_ev = read_device_events_csv(de_path) if de_path.is_file() else None
    spans = events["spans"]

    def span(typ, a, b, **kw):            # (the id first, numbered below)
        spans.append({"id": None, "type": typ, "t0_us": int(a), "t1_us": int(b), **kw,
                      "source": "user", "confidence": 1.0})

    if "wobble" in names:
        a, b = ev["contact"] + 200_000, ev["load_end"] - 100_000
        t = imu["t_us"].to_numpy()
        sel = (t >= a) & (t < b)
        lsb = meta["imu"]["gyro_lsb_per_dps"]
        w = WOBBLE_DPS * lsb * np.sin(2 * np.pi * WOBBLE_HZ * (t[sel] - c0) / 1e6)
        imu.loc[sel, "gx"] = np.clip(np.round(imu.loc[sel, "gx"].to_numpy() + w), -32768, 32767).astype(np.int64)
        span("wobble", a, b, hole=1)
    if "wander" in names:
        t = level["t_us"].to_numpy()
        ramp = np.round(np.clip((t - ev["contact"]) / 1e6, 0.0, 1.0) * WANDER_CDEG_PER_S).astype(np.int64)
        level["elevation_cdeg"] = level["elevation_cdeg"].to_numpy() + ramp
        # WALL and ANGLE judge the elevation (deviation = elevation - target,
        # level.c), so the device's deviation wanders with it.
        tilt = np.isin(level["mode"].to_numpy(), [LEVEL_MODES.index("wall"), LEVEL_MODES.index("angle")])
        level["deviation_cdeg"] = level["deviation_cdeg"].to_numpy() + np.where(tilt, ramp, 0)
        span("wander", ev["contact"], min(ev["contact"] + 1_500_000, ev["load_end"]), hole=1)
    if "speech" in names:
        a = ev["motor_off"] + 1_000_000
        sel = ta >= a
        noise = np.random.default_rng(seed).normal(0.0, 1.0, len(audio))
        band = np.convolve(noise, np.ones(3) / 3, "same") - np.convolve(noise, np.ones(50) / 50, "same")
        env = 0.5 * (1.0 + np.sin(2 * np.pi * SPEECH_MOD_HZ * ta / 1e6))
        x = audio.astype(np.float64)
        x[sel] += (SPEECH_FS * 32768 * band * env)[sel][:, None]
        audio = pcm16(x)
        span("ignore", a, c1, reason="speech")
    if LOUD_LOAD in names:
        sel = (ta >= ev["contact"]) & (ta < ev["load_end"])
        x = audio.astype(np.float64)
        x[sel] *= 10.0 ** (LOUD_LOAD_DB / 20.0)
        audio = pcm16(x)
    spans.sort(key=lambda s: s["t0_us"])     # events["spans"] itself
    for i, s in enumerate(spans):
        s["id"] = f"s{i + 1}"
    meta = write_recording(folder, meta, events, imu, level=level, audio=audio, audio_sync=sync,
                           device_events=dev_ev, derive=True)
    return meta, read_json(folder / "events.json")


def synthesize(spec: Spec, root: str | Path, *, rid: str, session_id: str | None = None,
               folder: str | Path | None = None) -> Synth:
    """Simulates spec and writes the recording under root/recordings/... (or into folder)."""
    from . import __version__, dbrec
    from .io import recording_dir

    with tempfile.TemporaryDirectory(prefix="dbsynth_") as tmp:
        data, truth = record_take(spec, Path(tmp))
    recs, _ = dbrec.decode(data)
    takes = dbrec.split_takes(recs)
    if len(takes) != 1:
        raise RuntimeError(f"expected one take in the stream, found {len(takes)}")
    take = takes[0]
    imu, _ = dbrec.common_span(dbrec.imu_track(take), dbrec.audio_track(take), take.session)
    coverage = [int(imu.t_us[0]), int(imu.t_us[-1]) + 1]
    events = truth_events(truth, coverage, rid)
    session = {"material": spec.material, "layers": [], "mode": spec.mode,
              "bit": {"type": spec.bit_type, "diameter_mm": spec.bit_diameter_mm},
              "drill": {"type": spec.drill_type_id, "motor": spec.motor, "model": "dbsim", "gear": 2,
                        "speed_setting": None},
              "outcome_tags": [],
              "notes": f"synthetic: {spec.name}"}
    dest = Path(folder) if folder is not None else recording_dir(root, rid)
    meta = dbrec.export_take(take, dest, recording_id=rid, session_id=session_id or f"20261026T1428_{INSTALL}",
                             events=events, session_labels=session,
                             app={"version": __version__, "tool": TOOL},
                             review={"status": "reviewed", "detector": "truth", "revision": 1,
                                     "edited_events": 0, "reviewer": None})
    if spec.inject:
        meta, events = inject(dest, spec.inject, events)
    return Synth(dest, meta, events, truth, spec)


def synthesize_set(name: str, root: str | Path, *, count: int | None = None, first_id: int = 1) -> list[Synth]:
    specs = SETS[name][:count] if count else SETS[name]
    return [synthesize(s, root, rid=recording_id(first_id + i)) for i, s in enumerate(specs)]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m dbdata.synth", description=__doc__.split("\n\n")[0])
    ap.add_argument("root", nargs="?", help="data root to write recordings under (or 'fixtures DIR')")
    ap.add_argument("dir", nargs="?", help=argparse.SUPPRESS)
    ap.add_argument("--set", default="eval", choices=sorted(SETS), help="scenario set (default eval)")
    ap.add_argument("--count", type=int, help="only the first N scenarios of the set")
    ap.add_argument("--list", action="store_true", help="list the scenario sets")
    a = ap.parse_args(argv)
    if a.list:
        for n, specs in SETS.items():
            print(f"{n}:")
            for s in specs:
                print(f"  {s.name}: {json.dumps(s.scenario, sort_keys=True)} profile {s.profile_name}"
                      f" noise {s.noise} elevation {s.elevation_deg}")
        return 0
    if a.root is None:
        ap.print_usage(sys.stderr)
        return 2
    try:
        sim_modules()
    except SimUnavailable as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    if a.root == "fixtures":
        if a.dir is None:
            print("error: fixtures needs a directory", file=sys.stderr)
            return 2
        from . import autodetect

        for p in autodetect.write_fixtures(a.dir):
            print(f"wrote {p}")
        return 0
    for s in synthesize_set(a.set, a.root, count=a.count):
        print(f"wrote {s.path} ({s.spec.name}, {s.meta['duration_s']} s, {len(s.events['events'])} events)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
