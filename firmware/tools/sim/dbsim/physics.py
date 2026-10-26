"""Virtual drill with the board clipped on: IMU samples for the sensor task.

Board frame as in components/level/include/fusion.h: X right, Y up the
screen, Z out of the screen towards the user. World Z is up. The drill bit
points along -Z of the drill frame; with a perfect mount the board frame is
the drill frame.

Drill pose (degrees): elevation (bit above horizontal positive), yaw
(turned right positive) and roll about the bit. Instead of the drill pose the
board can lie flat on a table with one axis up (the six poses of the sensor
calibration). Scripted motions (roll back and forth for the mount
calibration, pick up / put down) run on top. The orientation follows its
target at a limited rate, and the gyro reads exactly the rotation between
two samples, so fusion sees a consistent motion.

DrillScenario scripts a drilling session on top (spin-up, free run,
cutting, breakthrough, hammer blows, spin-down): the motor's vibration and
impacts in the IMU samples, the motor's sound for the microphones
(motor_block(), turned into samples by the core's sim_audio.c), and the
ground truth of what happened when.

Sensor errors to find with the calibrations:
  mount_offset  board rotation on the drill, degrees about drill X, Y, Z
  accel_bias / accel_scale  in the firmware's convention
      corrected = (raw - bias) * scale   =>   raw = true / scale + bias
  gyro_bias     rad/s (fusion learns it while still)
"""

from __future__ import annotations

import bisect
import math
import random

ODR_HZ = 448.4            # imu_frame.c PROFILES[IMU_PROFILE_LEVEL].odr_mhz (QMI8658 6DOF)
SAMPLE_PERIOD_S = 1.0 / ODR_HZ
G = 9.80665
ACCEL_NOISE_G = 0.0015    # default sensor noise (the panel's noise level 1)
GYRO_NOISE_RAD_S = 0.002

# ---- quaternions (w, x, y, z), body -> world -----------------------------------------


def q_mul(a, b):
    aw, ax, ay, az = a
    bw, bx, by, bz = b
    return (aw * bw - ax * bx - ay * by - az * bz,
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw)


def q_conj(q):
    return (q[0], -q[1], -q[2], -q[3])


def q_norm(q):
    n = math.sqrt(sum(c * c for c in q))
    return tuple(c / n for c in q)


def q_axis_angle(axis, angle_rad):
    n = math.sqrt(sum(c * c for c in axis))
    if n == 0.0:
        return (1.0, 0.0, 0.0, 0.0)
    s = math.sin(angle_rad / 2) / n
    return (math.cos(angle_rad / 2), axis[0] * s, axis[1] * s, axis[2] * s)


def q_rotate(q, v):
    """Body vector -> world."""
    w, x, y, z = q
    vx, vy, vz = v
    # v + 2 w (u x v) + 2 u x (u x v), u = (x, y, z)
    tx = 2 * (y * vz - z * vy)
    ty = 2 * (z * vx - x * vz)
    tz = 2 * (x * vy - y * vx)
    return (vx + w * tx + (y * tz - z * ty),
            vy + w * ty + (z * tx - x * tz),
            vz + w * tz + (x * ty - y * tx))


def q_rotate_inv(q, v):
    """World vector -> body."""
    return q_rotate(q_conj(q), v)


def q_to_rotvec(q):
    """Rotation vector (axis * angle, rad) of a unit quaternion, shortest way."""
    w, x, y, z = q
    if w < 0:
        w, x, y, z = -w, -x, -y, -z
    s = math.sqrt(x * x + y * y + z * z)
    if s < 1e-12:
        return (2 * x, 2 * y, 2 * z)
    angle = 2 * math.atan2(s, w)
    return (x / s * angle, y / s * angle, z / s * angle)


def q_angle_between(a, b) -> float:
    d = abs(sum(x * y for x, y in zip(a, b)))
    return 2 * math.acos(min(1.0, d))


def q_from_to(v, to=(0.0, 0.0, 1.0)):
    """Shortest rotation taking unit vector v onto `to`."""
    c = (v[1] * to[2] - v[2] * to[1], v[2] * to[0] - v[0] * to[2], v[0] * to[1] - v[1] * to[0])
    d = sum(a * b for a, b in zip(v, to))
    if d < -0.999999:
        # Opposite: half a turn about any axis perpendicular to v.
        axis = (1.0, 0.0, 0.0) if abs(v[0]) < 0.9 else (0.0, 1.0, 0.0)
        return q_axis_angle(axis, math.pi)
    return q_norm((1.0 + d, c[0], c[1], c[2]))


def q_euler_xyz(rx_deg, ry_deg, rz_deg):
    """Rx * Ry * Rz (intrinsic X, then Y, then Z)."""
    r = math.radians
    return q_mul(q_mul(q_axis_angle((1, 0, 0), r(rx_deg)), q_axis_angle((0, 1, 0), r(ry_deg))),
                 q_axis_angle((0, 0, 1), r(rz_deg)))


# Drill frame -> world at elevation 0, yaw 0, roll 0: the bit (-Z) points
# along world +X, drill Y is world up, drill X (the user's right) is world -Y.
Q_NEUTRAL = (0.5, 0.5, -0.5, -0.5)

FACES = {
    "+X": (1.0, 0.0, 0.0), "-X": (-1.0, 0.0, 0.0),
    "+Y": (0.0, 1.0, 0.0), "-Y": (0.0, -1.0, 0.0),
    "+Z": (0.0, 0.0, 1.0), "-Z": (0.0, 0.0, -1.0),
}


def drill_to_world(elevation_deg: float, yaw_deg: float, roll_deg: float):
    r = math.radians
    q = q_axis_angle((0, 0, 1), -r(yaw_deg))          # yaw right = clockwise from above
    q = q_mul(q, Q_NEUTRAL)
    q = q_mul(q, q_axis_angle((1, 0, 0), r(elevation_deg)))   # about drill X: bit up
    return q_mul(q, q_axis_angle((0, 0, 1), r(roll_deg)))     # about the bit


# ---- scripted motions -------------------------------------------------------------------

class RollMotion:
    """Rolls the drill back and forth about the bit (mount calibration)."""

    def __init__(self, amplitude_deg=50.0, period_s=4.0, cycles=3.0):
        self.amplitude = amplitude_deg
        self.period = period_s
        self.duration = cycles * period_s

    def at(self, t):
        """(roll offset deg, wobble quaternion or None, linear accel world g or None)."""
        return self.amplitude * math.sin(2 * math.pi * t / self.period), None, None


class LiftMotion:
    """Picks the device up (up=True) or puts it down: a vertical move with a wobble."""

    def __init__(self, up=True, height_m=0.3, duration_s=0.8, wobble_deg=8.0, rng=None):
        self.sign = 1.0 if up else -1.0
        self.height = height_m
        self.duration = duration_s
        self.wobble = wobble_deg
        rng = rng or random.Random()
        a = rng.uniform(0, 2 * math.pi)
        self.axis = (math.cos(a), math.sin(a), 0.0)   # world, horizontal

    def at(self, t):
        T = self.duration
        # z(t) = H (t/T - sin(2 pi t/T) / 2 pi): rest to rest.
        acc = self.sign * self.height * 2 * math.pi / (T * T) * math.sin(2 * math.pi * t / T) / G
        wob = q_axis_angle(self.axis, math.radians(self.wobble) * math.sin(math.pi * t / T))
        return 0.0, wob, (0.0, 0.0, acc)


class _Seg:
    """One state of a DrillScenario timeline: the motor frequency moves from
    f0 towards f1 (exponentially with tau, or along a smoothstep for a
    spin-up), the cutting noise towards noise."""

    __slots__ = ("state", "t0", "t1", "f0", "f1", "tau", "noise0", "noise", "load", "hole")

    def __init__(self, state, t0, t1, f0, f1, tau, noise0, noise, load, hole):
        self.state, self.t0, self.t1 = state, t0, t1
        self.f0, self.f1, self.tau = f0, f1, tau
        self.noise0, self.noise, self.load, self.hole = noise0, noise, load, hole

    def freq(self, t):
        if self.state == "spinup":
            u = min(1.0, max(0.0, (t - self.t0) / (self.t1 - self.t0)))
            return self.f0 + (self.f1 - self.f0) * u * u * (3 - 2 * u)
        return self.f1 + (self.f0 - self.f1) * math.exp(-(t - self.t0) / self.tau)

    def noise_at(self, t):
        return self.noise + (self.noise0 - self.noise) * math.exp(-(t - self.t0) / NOISE_TAU_S)


NOISE_TAU_S = 0.03
HAMMER_HZ = 45.0

# Levels at the default level=1: audio as full scale before the ES7210's gain,
# IMU in g.
AUDIO_HUM = 0.05            # harmonics at the free-run speed
AUDIO_FREE_NOISE = 0.004
AUDIO_LOAD_NOISE = 0.035    # cutting
AUDIO_HAMMER_NOISE = 0.05
AUDIO_STUCK_NOISE = 0.02
AUDIO_AMBIENT = 0.0015      # the room, motor off
AUDIO_HAMMER_HIT = (0.12, 0.004)        # amplitude, decay tau s
AUDIO_BREAKTHROUGH = (0.30, 0.015)
VIB_TONE_G = 0.30           # motor vibration at the free-run speed, radial
VIB_LOAD_BB_G = 0.10        # broadband during cutting (sigma)
VIB_HAMMER_G = (5.0, 10.0)  # recoil per blow
VIB_HAMMER_TAU_S = 0.0025
VIB_HAMMER_RETURN_S = 0.012  # the housing swings back after a blow: zero mean (a held drill does not drift)
VIB_BREAKTHROUGH_G = (1.5, 2.0)         # forward lunge along the bit
VIB_BREAKTHROUGH_TAU_S = 0.008
GYRO_BREAKTHROUGH = 1.5     # rad/s, a wobble about a radial axis
GYRO_BREAKTHROUGH_TAU_S = 0.015
GYRO_BREAKTHROUGH_BACK_S = 0.03  # and back: the rate integrates to 0 (the pose ends where it was)
CONTACT_PULSE = (0.3, 0.04)             # g back along the bit, s (half sine)
PUSH = (0.05, 3.0)          # g, Hz: the hand's axial push while cutting
KICK_DEG = (22.0, 30.0)     # kickback: the drill twists about the bit
KICK_RISE_S = 0.06
KICK_HOLD_S = 0.6
KICK_BACK_S = 0.4


SCENARIO_MODES = ("drill", "hammer")
SCENARIO_OUTCOMES = ("breakthrough", "retract", "mixed")
SCENARIO_STUCK_EXITS = ("restart", "pull")


class DrillScenario:
    """A scripted drilling session, a motion for DrillPhysics.start_motion().

    OFF -> SPINUP -> FREE -> CONTACT/LOAD (the motor sags, more broadband,
    the axial push) -> BREAKTHROUGH (the speed jumps back, a 1.5-2 g lunge
    along the bit) or RETRACT (a slow recovery, no impulse) -> FREE ->
    COAST/SPINDOWN -> OFF, for each hole. mode "hammer" adds 5-10 g blows at
    ~45 Hz while cutting. stuck: the first hole stalls (the motor stops in
    the hole, restarts, and the bit is pulled out; stuck_exit "pull": the
    bit is pulled out with the motor off, and the motor runs free once
    more); kickback: the drill
    twists about the bit where the bit catches (the stall, or else the first
    breakthrough). Seeded: the same arguments give the same session.

    The ground truth (truth()) uses the dataset contract's names (events
    motor_on, contact, breakthrough, retract, motor_off, kickback; the span
    stuck; holes) and never reaches the stream. Times are seconds from the
    scenario's start.
    """

    max_rate_dps = 900.0       # a kickback twists faster than the hand moves

    def __init__(self, seed=0, mode="drill", holes=1, outcome="breakthrough", free_hz=233.0,
                 sag=0.2, spinup_s=0.5, free_s=1.0, drill_s=2.5, gap_s=1.0, lead_s=0.5,
                 stuck=False, kickback=False, level=1.0, stuck_exit="restart"):
        if mode not in SCENARIO_MODES:
            raise ValueError("mode must be drill or hammer")
        if outcome not in SCENARIO_OUTCOMES:
            raise ValueError("outcome must be breakthrough, retract or mixed")
        if stuck_exit not in SCENARIO_STUCK_EXITS:
            raise ValueError("stuck_exit must be restart or pull")
        self.params = {"seed": seed, "mode": mode, "holes": holes, "outcome": outcome,
                       "free_hz": free_hz, "sag": sag, "spinup_s": spinup_s, "free_s": free_s,
                       "drill_s": drill_s, "gap_s": gap_s, "lead_s": lead_s, "stuck": stuck,
                       "kickback": kickback, "level": level, "stuck_exit": stuck_exit}
        self.stuck_exit = stuck_exit
        self.free_hz = float(free_hz)
        self.level = float(level)
        self.hammer = mode == "hammer"
        rng = random.Random(seed)
        self._noise_rng = random.Random(rng.random())
        self.segs = []
        self.events = []           # (t, type, hole or None)
        self.spans = []            # (t0, t1, type, hole)
        self.holes = []
        self.impacts = []          # (t, imu g, imu tau, direction +1 forward / -1 back, audio amp, tau)
        self.kicks = []            # (t, degrees)
        self._build(rng)
        self.impacts.sort()
        self.duration = self.segs[-1].t1
        self._starts = [s.t0 for s in self.segs]
        self._imp_t = [i[0] for i in self.impacts]
        self._seg_i = 0
        self._imp_i = 0
        self._ph = [rng.uniform(0, 2 * math.pi) for _ in range(4)]
        self._last_t = None
        self._radial_key = None

    # ---- timeline ---------------------------------------------------------------------
    def _build(self, rng):
        p = self.params
        holes, outcome, stuck, kickback = int(p["holes"]), p["outcome"], bool(p["stuck"]), bool(p["kickback"])
        sag, spinup_s, free_s, drill_s, gap_s, lead_s = (
            float(p[k]) for k in ("sag", "spinup_s", "free_s", "drill_s", "gap_s", "lead_s"))
        jit = lambda x: x * rng.uniform(0.85, 1.15)   # noqa: E731
        f_free = self.free_hz
        t = 0.0
        f = 0.0
        noise = 0.0

        def seg(state, dur, f1, tau, n1, load=False, hole=None):
            nonlocal t, f, noise
            s = _Seg(state, t, t + dur, f, f1, tau, noise, n1, load, hole)
            self.segs.append(s)
            t += dur
            f = s.freq(t)
            noise = s.noise_at(t)
            return s

        def event(kind, hole=None):
            self.events.append((t, kind, hole))

        def cut(dur):           # cutting hole h (the blows too when hammering)
            self._hammer(rng, seg("hammer" if self.hammer else "load", dur, f_load, 0.12, cut_noise,
                                  True, h))

        def retract_out():      # the bit pulled out of hole h, the motor running
            event("retract", h)
            seg("retract", 0.6, f_free, 0.15, AUDIO_FREE_NOISE, False, h)

        seg("off", lead_s, 0.0, 0.05, 0.0)
        kicked = False
        for h in range(1, holes + 1):
            through = outcome == "breakthrough" or (outcome == "mixed" and h % 2 == 1)
            stall = stuck and h == 1
            f_load = f_free * (1.0 - jit(sag))
            cut_noise = AUDIO_HAMMER_NOISE if self.hammer else AUDIO_LOAD_NOISE
            event("motor_on")
            seg("spinup", jit(spinup_s), f_free, 1.0, AUDIO_FREE_NOISE)
            seg("free", jit(free_s), f_free, 0.05, AUDIO_FREE_NOISE)
            event("contact", h)
            self.impacts.append((t, CONTACT_PULSE[0], CONTACT_PULSE[1], 0, 0.0, 0.0))
            load_s = jit(drill_s)
            if stall:
                cut(0.6 * load_s)
                t_stuck = t
                if kickback:
                    self.kicks.append((t, rng.uniform(*KICK_DEG)))
                    event("kickback", h)
                    kicked = True
                seg("stuck", jit(0.6), 0.25 * f_free, 0.06, AUDIO_STUCK_NOISE, True, h)
                self.spans.append((t_stuck, t, "stuck", h))
                event("motor_off", h)
                seg("spindown", 0.25, 0.0, 0.06, 0.0, False, h)
                seg("off", jit(0.5), 0.0, 0.05, 0.0, False, h)
                if self.stuck_exit == "pull":
                    event("retract", h)    # pulled out with the motor off, just before the restart
                    seg("off", 0.05, 0.0, 0.05, 0.0)
                    event("motor_on")
                    seg("spinup", jit(spinup_s), f_free, 1.0, AUDIO_FREE_NOISE)
                else:
                    event("motor_on", h)   # a restart with the bit in the hole
                    seg("spinup", jit(0.3), f_load, 1.0, cut_noise, True, h)
                    cut(jit(0.5))
                    retract_out()
                through = False
            else:
                cut(load_s)
                if through:
                    event("breakthrough", h)
                    lunge = rng.uniform(*VIB_BREAKTHROUGH_G)
                    self.impacts.append((t, lunge, VIB_BREAKTHROUGH_TAU_S, 1,
                                         AUDIO_BREAKTHROUGH[0], AUDIO_BREAKTHROUGH[1]))
                    if kickback and not kicked:
                        self.kicks.append((t + 0.01, rng.uniform(*KICK_DEG)))
                        self.events.append((t + 0.01, "kickback", h))
                        kicked = True
                    seg("breakthrough", 0.25, f_free, 0.025, AUDIO_FREE_NOISE, False, h)
                else:
                    retract_out()
            self.holes.append({"hole": h, "through": through})
            seg("free", jit(0.6), f_free, 0.05, AUDIO_FREE_NOISE)
            event("motor_off")
            seg("spindown", 0.6, 0.0, 0.18, 0.0)
            seg("off", gap_s if h < holes else 0.5, 0.0, 0.05, 0.0)
        self.events.sort(key=lambda e: e[0])

    def _hammer(self, rng, s):
        if not self.hammer:
            return
        t = s.t0 + rng.uniform(0.02, 0.05)
        while t < s.t1:
            self.impacts.append((t, -rng.uniform(*VIB_HAMMER_G), VIB_HAMMER_TAU_S, -1,
                                 AUDIO_HAMMER_HIT[0] * rng.uniform(0.8, 1.2), AUDIO_HAMMER_HIT[1]))
            t += 1.0 / (HAMMER_HZ * rng.uniform(0.95, 1.05))

    def _seg(self, t) -> _Seg:
        i = self._seg_i
        if not (self._starts[i] <= t < self.segs[i].t1):
            i = max(0, min(len(self.segs) - 1, bisect.bisect_right(self._starts, t) - 1))
            self._seg_i = i
        return self.segs[i]

    def motor(self, t):
        """(frequency Hz, harmonics level, cutting noise level, loaded) at t."""
        if t < 0 or t >= self.duration:
            return 0.0, 0.0, 0.0, False
        s = self._seg(t)
        f = max(0.0, s.freq(t))
        if s.load and s.state in ("load", "hammer"):
            f *= 1.0 + 0.015 * math.sin(2 * math.pi * 1.3 * t + self._ph[3])
        r = f / self.free_hz
        hum = AUDIO_HUM * self.level * r ** 1.5 * (1.3 if s.load else 1.0)
        return f, hum, s.noise_at(t) * self.level, s.load

    # ---- motion (DrillPhysics) ----------------------------------------------------------
    def at(self, t):
        roll = 0.0
        for tk, deg in self.kicks:
            d = t - tk
            if d < 0:
                continue
            if d < KICK_HOLD_S:
                roll += deg * (1.0 - math.exp(-d / KICK_RISE_S))
            else:
                roll += deg * (1.0 - math.exp(-KICK_HOLD_S / KICK_RISE_S)) * \
                    math.exp(-(d - KICK_HOLD_S) / KICK_BACK_S)
        return roll, None, None

    def _radials(self, axis):
        key = tuple(round(c, 6) for c in axis)
        if key != self._radial_key:
            ref = (1.0, 0.0, 0.0) if abs(axis[0]) < 0.9 else (0.0, 1.0, 0.0)
            r1 = q_norm(_cross(axis, ref))
            self._radial_key, self._r = key, (r1, _cross(axis, r1))
        return self._r

    def vibration(self, t, dt, axis):
        """What the motor and the bit add to one IMU sample at t: (accel g,
        gyro rad/s), board frame; axis is the bit direction in the board frame."""
        f, hum, _, load = self.motor(t)
        acc = [0.0, 0.0, 0.0]
        gyr = [0.0, 0.0, 0.0]
        r1, r2 = self._radials(axis)
        lvl = self.level
        if f > 0.0:
            self._ph[0] = (self._ph[0] + 2 * math.pi * f * dt) % (2 * math.pi)
            self._ph[1] = (self._ph[1] + 4 * math.pi * f * dt) % (2 * math.pi)
            amp = VIB_TONE_G * lvl * (f / self.free_hz) * (1.3 if load else 1.0)
            p1, p2 = self._ph[0], self._ph[1]
            c1 = amp * (math.sin(p1) + 0.4 * math.sin(p2))
            c2 = amp * (math.cos(p1) + 0.4 * math.cos(p2 + 0.7))
            ca = 0.3 * amp * math.sin(p1 + 1.1)
            g = self._noise_rng.gauss
            bb = VIB_LOAD_BB_G * lvl if load else 0.0
            for i in range(3):
                acc[i] += c1 * r1[i] + c2 * r2[i] + ca * axis[i] + (g(0.0, bb) if bb else 0.0)
                gyr[i] += g(0.0, 0.1 * amp)
        if load:
            push = PUSH[0] * lvl * math.sin(2 * math.pi * PUSH[1] * t)
            for i in range(3):
                acc[i] -= push * axis[i]
        # Impacts: the contact (back along the bit), hammer blows (recoil) and
        # the breakthrough (forward, with a wobble of the bit that swings back).
        imp = self.impacts
        j = self._imp_i
        while j < len(imp) and t - imp[j][0] > 8 * max(
                imp[j][2], 0.01, GYRO_BREAKTHROUGH_BACK_S if imp[j][3] == 1 else 0.0):
            j += 1
        self._imp_i = j
        while j < len(imp) and imp[j][0] <= t:
            ti, a, tau, kind = imp[j][:4]
            d = t - ti
            if kind == 0:           # contact: a half sine of tau seconds
                v = -a * math.sin(math.pi * d / tau) if d < tau else 0.0
            elif kind < 0:          # hammer blow: the recoil, then the slower return (integral 0)
                v = a * (math.exp(-d / tau) - tau / VIB_HAMMER_RETURN_S * math.exp(-d / VIB_HAMMER_RETURN_S))
            else:
                v = a * math.exp(-d / tau)
            for i in range(3):
                acc[i] += v * lvl * axis[i]
            if kind == 1:
                # GYRO_BREAKTHROUGH at d = 0, then the swing back (integral 0)
                k = GYRO_BREAKTHROUGH_TAU_S / GYRO_BREAKTHROUGH_BACK_S
                w = GYRO_BREAKTHROUGH / (1.0 - k) * (math.exp(-d / GYRO_BREAKTHROUGH_TAU_S) -
                                                     k * math.exp(-d / GYRO_BREAKTHROUGH_BACK_S))
                for i in range(3):
                    gyr[i] += w * r1[i]
            j += 1
        return acc, gyr

    def audio_impacts(self, t0, t1):
        """Impact bursts starting in [t0, t1): (t, amplitude, tau)."""
        lo = bisect.bisect_left(self._imp_t, t0)
        hi = bisect.bisect_left(self._imp_t, t1)
        return [(self.impacts[i][0], self.impacts[i][4] * self.level, self.impacts[i][5])
                for i in range(lo, hi) if self.impacts[i][4] > 0]

    # ---- ground truth -------------------------------------------------------------------
    def truth(self, until=None):
        """Events, spans, holes and the timeline's states (seconds from the start).

        until: the scenario was stopped then (DrillPhysics.stop_scenario(), or
        another scenario started): what it had not reached yet is left out,
        spans and states end there, and a motor that was still running stops
        there (motor_off, in the hole when the bit was in one; that hole's
        through is None, as for a take that ends in the hole)."""
        if until is None or until >= self.duration:
            until, stopped = self.duration, False
        else:
            until, stopped = max(0.0, until), True
        events = [(t, k, h) for t, k, h in self.events if t < until or not stopped]
        if stopped:
            motor = in_hole = None
            for _, k, h in events:
                if k in ("motor_on", "motor_off"):
                    motor = k
                if k == "contact":
                    in_hole = h
                elif k in ("breakthrough", "retract"):
                    in_hole = None
            if motor == "motor_on":
                events.append((until, "motor_off", in_hole))
        ended = {h for _, k, h in events if k in ("breakthrough", "retract")}
        return {
            "params": dict(self.params),
            "duration_s": until,
            **({"stopped_s": until} if stopped else {}),
            "events": [{"type": k, "t": t, **({"hole": h} if h else {})} for t, k, h in events],
            "spans": [{"type": k, "t0": a, "t1": min(b, until), "hole": h}
                      for a, b, k, h in self.spans if a < until],
            "holes": [h if h["hole"] in ended else {**h, "through": None} for h in map(dict, self.holes)],
            "segments": [{"state": s.state, "t0": s.t0, "t1": min(s.t1, until)}
                         for s in self.segs if s.t0 < until],
        }


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


class DrillPhysics:
    def __init__(self, seed=None):
        self.rng = random.Random(seed)
        # drill pose, degrees
        self.elevation = 0.0
        self.yaw = 0.0
        self.roll = 0.0
        # board lying on a table: face that points up, or None for the drill pose
        self.board_face = None
        self.face_tilt = (0.0, 0.0)          # table tilt, degrees about world X, Y
        self.mount_offset = (0.0, 0.0, 0.0)  # degrees about drill X, Y, Z
        # sensor errors and noise
        self.accel_bias = [0.0, 0.0, 0.0]
        self.accel_scale = [1.0, 1.0, 1.0]
        self.gyro_bias = [0.0, 0.0, 0.0]
        self.accel_noise_g = ACCEL_NOISE_G
        self.gyro_noise_rad_s = GYRO_NOISE_RAD_S
        self.audio_ambient = AUDIO_AMBIENT    # the room at the microphones, full scale
        # drill motor: accel vibration amplitude (g) at ~100-300 Hz, aliased by the ODR.
        # No on-chip LPF here: the firmware's vibration detector sees these tones in
        # full, where the board's 11.9 Hz accel LPF would remove most of them.
        self.vibration_g = 0.0
        self.vibration_hz = (147.0, 233.0)
        self.max_rate_dps = 180.0            # how fast the orientation follows its target

        self.t = 0.0
        self._motions = []                   # [(motion, start time)]
        # target() runs every sample: the static pose is kept until a field changes.
        self._mount_key = self._target_key = self._axis_q = None
        self._q = self.target()[0]
        self._phase = [self.rng.uniform(0, 2 * math.pi) for _ in range(6)]

    # ---- pose -----------------------------------------------------------------------
    def set_drill(self, elevation=None, yaw=None, roll=None):
        """Drill pose in degrees (None keeps a value); leaves any table pose."""
        if elevation is not None:
            self.elevation = float(elevation)
        if yaw is not None:
            self.yaw = float(yaw)
        if roll is not None:
            self.roll = float(roll)
        self.board_face = None

    def set_board_face(self, face, tilt_deg=(0.0, 0.0)):
        """Board flat on a table with axis `face` ("+X", "-Z", ...) up."""
        if face not in FACES:
            raise ValueError(f"face must be one of {', '.join(FACES)}")
        self.board_face = face
        self.face_tilt = tuple(tilt_deg)

    def mount_rotation(self):
        """Drill frame <- board frame."""
        key = tuple(self.mount_offset)      # tests assign lists
        if key != self._mount_key:
            self._mount_key, self._mount_q = key, q_euler_xyz(*key)
        return self._mount_q

    def expected_drill_axis(self):
        """The bit direction in the board frame: what the mount calibration should find."""
        q = self.mount_rotation()
        if q is not self._axis_q:
            self._axis_q, self._axis = q, q_rotate_inv(q, (0.0, 0.0, -1.0))
        return self._axis

    def target(self):
        """Target orientation (board -> world) and linear acceleration (world, g)."""
        roll = self.roll
        wobble = None
        lin = [0.0, 0.0, 0.0]
        for m, t0 in self._motions:
            dr, wob, acc = m.at(self.t - t0)
            roll += dr
            if wob is not None:
                wobble = wob if wobble is None else q_mul(wob, wobble)
            if acc is not None:
                lin = [a + b for a, b in zip(lin, acc)]
        key = None
        if wobble is None and not any(lin):
            # a pose alone (a scenario adds only roll, and only in a kickback): kept
            key = (self.board_face, tuple(self.face_tilt), self.elevation, self.yaw, roll,
                   tuple(self.mount_offset))
            if key == self._target_key:
                return self._target_q, lin
        if self.board_face is not None:
            tilt = q_mul(q_axis_angle((1, 0, 0), math.radians(self.face_tilt[0])),
                         q_axis_angle((0, 1, 0), math.radians(self.face_tilt[1])))
            q = q_mul(tilt, q_from_to(FACES[self.board_face]))
        else:
            q = q_mul(drill_to_world(self.elevation, self.yaw, roll), self.mount_rotation())
        if wobble is not None:
            q = q_mul(wobble, q)
        if key is not None:
            self._target_key, self._target_q = key, q
        return q, lin

    @property
    def orientation(self):
        """Current board -> world quaternion."""
        return self._q

    def settled(self, tol_deg=0.01) -> bool:
        """At the target and no scripted motion running."""
        return not self._motions and math.degrees(q_angle_between(self._q, self.target()[0])) < tol_deg

    # ---- scripted motions ---------------------------------------------------------
    def start_motion(self, motion):
        self._motions.append((motion, self.t))
        return motion

    def roll_back_and_forth(self, amplitude_deg=50.0, period_s=4.0, cycles=3.0):
        """Mount calibration: roll about the bit, +-amplitude."""
        return self.start_motion(RollMotion(amplitude_deg, period_s, cycles))

    def pick_up(self, **kw):
        return self.start_motion(LiftMotion(True, rng=self.rng, **kw))

    def put_down(self, **kw):
        return self.start_motion(LiftMotion(False, rng=self.rng, **kw))

    @property
    def moving(self) -> bool:
        return bool(self._motions)

    def stop_motions(self, kind=None):
        """Stops all scripted motions, or only those of class `kind`."""
        self._motions = [(m, t0) for m, t0 in self._motions if kind and not isinstance(m, kind)]

    def running(self, kind) -> bool:
        """Whether a scripted motion of class `kind` is running."""
        return any(isinstance(m, kind) for m, _ in self._motions)

    # ---- samples ------------------------------------------------------------------
    def sample(self, dt=SAMPLE_PERIOD_S):
        """Advances by dt and returns one raw IMU sample (ax, ay, az [g], gx, gy, gz [rad/s])."""
        self.t += dt
        self._motions = [(m, t0) for m, t0 in self._motions if self.t - t0 < m.duration]
        target, lin = self.target()

        q_prev = self._q
        rate = max([self.max_rate_dps] + [getattr(m, "max_rate_dps", 0.0) for m, _ in self._motions])
        step = math.radians(rate) * dt
        diff = q_mul(q_conj(q_prev), target)
        rv = q_to_rotvec(diff)
        ang = math.sqrt(rv[0] ** 2 + rv[1] ** 2 + rv[2] ** 2)
        if ang > step:
            k = step / ang
            diff = q_axis_angle(rv, ang * k)
        q_new = q_norm(q_mul(q_prev, diff))
        self._q = q_new
        w = q_to_rotvec(q_mul(q_conj(q_prev), q_new))   # body frame, rad per sample

        up = q_rotate_inv(q_new, (0.0, 0.0, 1.0))
        la = q_rotate_inv(q_new, lin) if any(lin) else (0.0, 0.0, 0.0)
        va, vg = self._scenario_vibration(dt)
        gauss = self.rng.gauss
        out = []
        for i in range(3):
            f = up[i] + la[i] + va[i] + gauss(0.0, self.accel_noise_g)
            if self.vibration_g:
                f += self.vibration_g * (
                    0.6 * math.sin(2 * math.pi * self.vibration_hz[0] * self.t + self._phase[i]) +
                    0.4 * math.sin(2 * math.pi * self.vibration_hz[1] * self.t + self._phase[3 + i]))
                f += gauss(0.0, 0.3 * self.vibration_g)
            out.append(f / self.accel_scale[i] + self.accel_bias[i])
        for i in range(3):
            g = w[i] / dt + vg[i] + self.gyro_bias[i] + gauss(0.0, self.gyro_noise_rad_s)
            if self.vibration_g:
                g += gauss(0.0, 0.1 * self.vibration_g)
            out.append(g)
        return tuple(out)

    def samples(self, n, dt=SAMPLE_PERIOD_S):
        return [self.sample(dt) for _ in range(n)]

    # ---- drilling scenarios -------------------------------------------------------
    def start_scenario(self, **kw) -> DrillScenario:
        """Starts a DrillScenario now (stopping a running one)."""
        self.stop_motions(DrillScenario)
        return self.start_motion(DrillScenario(**kw))

    def stop_scenario(self):
        self.stop_motions(DrillScenario)

    @property
    def scenario(self):
        """(running DrillScenario, its start in physics seconds), or None."""
        for m, t0 in self._motions:
            if isinstance(m, DrillScenario):
                return m, t0
        return None

    def _scenario_vibration(self, dt):
        sc = self.scenario          # at most one (start_scenario)
        if sc is None:
            return (0.0, 0.0, 0.0), (0.0, 0.0, 0.0)
        return sc[0].vibration(self.t - sc[1], dt, self.expected_drill_axis())

    def motor_block(self, t0, t1) -> dict:
        """The motor for the microphones from t0 to t1 (physics seconds): the
        fields of the core's sim_motor_t. Without a scenario the "drill
        running" vibration hums at its lower tone, else the room is quiet."""
        sc = self.scenario
        if sc is not None:
            m, start = sc
            f0, h0, n0, _ = m.motor(t0 - start)
            f1, h1, n1, _ = m.motor(t1 - start)
            hits = [(t + start - t0, a, tau) for t, a, tau in m.audio_impacts(t0 - start, t1 - start)]
        elif self.vibration_g > 0:
            f0 = f1 = self.vibration_hz[0]
            h0 = h1 = AUDIO_HUM * min(3.0, self.vibration_g / VIB_TONE_G)
            n0 = n1 = AUDIO_FREE_NOISE
            hits = []
        else:
            f0 = f1 = h0 = h1 = n0 = n1 = 0.0
            hits = []
        return {"f0_hz": (f0, f1), "hum": (h0, h1), "noise": (n0, n1), "ambient": self.audio_ambient,
                "impacts": hits}
