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

Sensor errors to find with the calibrations:
  mount_offset  board rotation on the drill, degrees about drill X, Y, Z
  accel_bias / accel_scale  in the firmware's convention
      corrected = (raw - bias) * scale   =>   raw = true / scale + bias
  gyro_bias     rad/s (fusion learns it while still)
"""

from __future__ import annotations

import math
import random

ODR_HZ = 448.4            # imu.c IMU_ODR_HZ (QMI8658 6DOF, 448.4 Hz)
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
        # drill motor: accel vibration amplitude (g) at ~100-300 Hz, aliased by the ODR.
        # No on-chip LPF here: the firmware's vibration detector sees these tones in
        # full, where the board's 11.9 Hz accel LPF would remove most of them.
        self.vibration_g = 0.0
        self.vibration_hz = (147.0, 233.0)
        self.max_rate_dps = 180.0            # how fast the orientation follows its target

        self.t = 0.0
        self._motions = []                   # [(motion, start time)]
        # target() runs every sample: the static pose is kept until a field changes.
        self._mount_key = self._target_key = None
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
        return q_rotate_inv(self.mount_rotation(), (0.0, 0.0, -1.0))

    def target(self):
        """Target orientation (board -> world) and linear acceleration (world, g)."""
        key = None
        if not self._motions:
            key = (self.board_face, tuple(self.face_tilt), self.elevation, self.yaw, self.roll,
                   tuple(self.mount_offset))
            if key == self._target_key:
                return self._target_q, [0.0, 0.0, 0.0]
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
        step = math.radians(self.max_rate_dps) * dt
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
        gauss = self.rng.gauss
        out = []
        for i in range(3):
            f = up[i] + la[i] + gauss(0.0, self.accel_noise_g)
            if self.vibration_g:
                f += self.vibration_g * (
                    0.6 * math.sin(2 * math.pi * self.vibration_hz[0] * self.t + self._phase[i]) +
                    0.4 * math.sin(2 * math.pi * self.vibration_hz[1] * self.t + self._phase[3 + i]))
                f += gauss(0.0, 0.3 * self.vibration_g)
            out.append(f / self.accel_scale[i] + self.accel_bias[i])
        for i in range(3):
            g = w[i] / dt + self.gyro_bias[i] + gauss(0.0, self.gyro_noise_rad_s)
            if self.vibration_g:
                g += gauss(0.0, 0.1 * self.vibration_g)
            out.append(g)
        return tuple(out)

    def samples(self, n, dt=SAMPLE_PERIOD_S):
        return [self.sample(dt) for _ in range(n)]
