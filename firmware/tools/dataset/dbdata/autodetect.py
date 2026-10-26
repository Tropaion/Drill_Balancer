"""The auto-detector: point events, spans and holes from a recording's signals.

    python -m dbdata.autodetect REC [REC...]            # print what it finds
    python -m dbdata.autodetect REC --write [--force]    # (re)label: events.json + meta review/derived
    python -m dbdata.autodetect REC --score              # compare with the labels in events.json
    python -m dbdata.autodetect --check-fixtures [DIR]   # replay the golden fixtures
    python -m dbdata.autodetect --update-fixtures [DIR]  # rewrite their expected output (no simulator)

This module is the reference for the app's Kotlin port (recorder/label,
milestone 6), which replays the golden fixtures in fixtures/autodetect/
(AutoDetectVectorsTest, see the README there). Everything below is plain
arithmetic over arrays: biquads, sums, medians, one real FFT per frame. The
steps are spelled out so that the port can follow them one to one. Every
parameter comes from contract/autodetect_params.json (`P`, its sections in
brackets below); events.json records the detector version and the sha256
of that file (CRLF normalised to LF).

Definitions
-----------
percentile(v, q): sort the finite values, pos = q/100 * (n - 1), linear
interpolation between the two neighbours (numpy's default); NaN for no
values. median = percentile 50 (for an even count numpy's linear rule
gives b - (b - a) * 0.5 of the middle pair a <= b). medN(v)[k] = the median
of v over [k - N//2, k + N//2], clipped at the ends (a centred median).
round(x) = floor(x + 0.5). biquad: the RBJ cookbook 2nd-order
high-/low-pass, Q = 1/sqrt(2) (Butterworth), at the IMU's nominal ODR,
direct form I, started in the steady state of the first input (x1 = x2 =
x[0]; y1 = y2 = 0 for a high-pass, x[0] for a low-pass): the port follows
biquad_reference() sample by sample; biquad() computes the same filter in
blocks (equal within rounding). sigmoid(x) = 1 / (1 + exp(-x)). nf(ms) =
max(1, round(ms / frame_ms)) frames. A ratio is "available" when its inputs
are finite. Times of frames are their starts.

1. Frames. T0 = the first IMU t_us, F = frame_ms * 1000 us. Frame k is
   [T0 + k F, T0 + (k + 1) F), K = (t_last - T0) // F + 1 frames; an IMU
   sample belongs to frame (t_us - T0) // F.

2. IMU features [features]. a = counts / accel_lsb_per_g (g), w = counts /
   gyro_lsb_per_dps (dps); d = meta.imu.mount.drill_axis when valid, else
   (0, 0, -1), normalised (the bit direction, board frame). Per sample:
     h      = HP(hp_hz) of each accel axis; e = h.h (g^2)
     imp    = h.d                              (forward along the bit, g)
     thrust = LP(thrust_band[1]) of HP(thrust_band[0]) of a.d
     roll   = w.d                              (rate about the bit, dps)
     wr     = w - roll d                       (radial rate, 3 axes)
     gr     = |HP(hp_hz) of each wr axis|
     wb     = sum over the axes of (LP(wobble_band[1]) of HP(wobble_band[0]) of wr)^2
   Per frame with samples: e_imu = 10 log10(mean e + eps_g2) dB, thrust =
   mean, imp = max, roll = mean, gyro_rad = max of gr; NaN without samples.
   wob = sqrt(sum wb / count) over the samples of frames k - n .. k + n, n
   = nf(wobble_window_ms) // 2. ham (hammer periodicity): env = |imp| per
   sample; the samples with |t - (T0 + k F + F/2)| < hammer_window_ms / 2
   (strictly; at least 4, else 0); x = env - mean(env); ham = max over the
   lags L = round(odr / hammer_rate[1]) .. round(odr / hammer_rate[0])
   (L < len(x)) of sum_n x[n] x[n + L] / sum_n x[n]^2 (0 for a zero
   denominator).

3. Audio features [features] (the file's first channel: MIC1, or MIC2 in a
   take that recorded MIC2 alone; counts / 32768). Sync t = a + b * frame
   (io.SyncFit from audio_sync.csv, adc_delay_us subtracted); s_k =
   round((T0 + k F - a) / b) for k = 0 .. K; frame k has audio when 0 <= s_k
   < s_{k+1} <= M (M samples) and no sample of [s_k, s_{k+1}) lies in a gap
   [g0, g1) of meta.gaps.audio_gap_frames (the transport lost those; the
   file holds silence there). e_aud = 10 log10(mean of x^2 over
   [s_k, s_{k+1}) + eps_fs2) dBFS. Spectrum: c = (s_k + s_{k+1}) // 2, the
   samples [c - N/2, c + N/2) (N = fft_window; 0 outside the audio) times
   the periodic Hann window 0.5 - 0.5 cos(2 pi n / N), zero-padded to
   fft_size, |X_j| of the real FFT, j = 0 .. fft_size / 2, bin width D =
   rate_nominal / fft_size. A band [lo, hi] is the bins ceil(lo / D) ..
   floor(hi / D).
     e_cut    = 10 log10(sum of |X_j|^2 over cut_band + eps_fs2)
     e_speech = the same over speech_band
     flux     = mean over flux_band of max(0, ln(|X_j| + 1e-6) -
                ln(|X'_j| + 1e-6)), X' the spectrum of frame k - 1 (all 0
                when frame k - 1 has no audio)
     f0       = harmonic product spectrum: L_j = ln(|X_j| + 1e-9), S(j) =
                sum_{h=1..H} L_{h j}, H = hps_harmonics, for j = ceil(f0_lo /
                D) .. min(floor(f0_hi / D), (fft_size / 2) // H); j* = the
                first maximum. harm = mean over h of L_{h j*} - median of L
                over [h j* - B, h j* + B] (B = prominence_bins, clipped at
                0): the harmonics' prominence over their neighbourhood.
                Voiced when harm >= voiced_min and j* is not at either end
                of the range; then f0 = (j* + delta) D with the parabolic
                delta = 0.5 (S(j*-1) - S(j*+1)) / (S(j*-1) - 2 S(j*) +
                S(j*+1)) (0 for a zero denominator, clamped to +-0.5);
                unvoiced: NaN.

4. Noise floor [noise]. The quiet block of a series: frames cut into blocks
   of nf(block_ms) from frame 0 (a partial last block is left out); a block
   counts with at least half its frames finite; the one with the lowest
   median (the first on a tie); all frames when none counts. floor =
   percentile(block, floor_pct), spread = max(1.4826 * median|block -
   median(block)|, spread_min_db), z = (x - floor) / spread. z_imu from
   e_imu's quiet block; z_aud and z_speech from e_aud's quiet block (the
   same frames for both). s = the larger of med5(z_imu) and med5(z_aud) (5
   = smooth_z_frames) where either is finite, NaN where neither is (a
   transport gap).

5. Motor runs [motor]. Hysteresis over s; a frame with s NaN leaves the
   counts as they are (neither adds nor breaks a row). While off: nf(on_ms) frames in a
   row with s > on_z start a run; its onset walks back from the first of
   them while the frame before has s > back_z, never before the frame
   after the previous run's confirmation. While on: nf(off_ms) frames in a
   row with s < off_z end it; k_d = the first of them. A run whose
   med5(z_imu) never reaches on_z is dropped when there is any IMU data
   (the board is clipped to the drill: a motor always shakes it; sound
   alone is speech or another machine). The motor_off frame, walking back
   from k_d no lower than lo = max(on + 1, k_d - nf(off_search_ms)), the
   louder stream (audio when its median z_aud over the conf_ms before k_d
   is >= that of z_imu) giving E = med3(e_aud) or med3(e_imu) (3 =
   smooth_e_frames):
     a. energy knee k_en: the first frame k from k_d down to lo with s(k) >=
        on_z, E(k) >= median(E over the run [on, k_d)) - off_level_db and
        E(k) >= median(E over the off_hold_ms before k) - off_drop_db (at
        least 3 finite), k_en = min(k_d, k + 1); k_d when none;
     b. f0 knee: the first frame k from k_en down to max(lo, k_en -
        nf(off_f0_ms)) that is voiced, has s(k) >= on_z and f0(k) >= (1 - off_f0_drop) *
        median of the voiced f0 over the off_hold_ms before k (at least 3);
        motor_off = min(k_en, k + 1); k_en when none.
   A run without a low stretch at the end has no motor_off (an open end);
   one from frame 0 gets motor_on at T0 with confidence open_motor_on.

6. Spin-up end k_su [spinup]: the first k from on + nf(min_ms) up to on +
   nf(max_ms) (exclusive, and before the run's end) whose next nf(stable_ms)
   frames are stable: with audio, at least voiced_frac of them voiced and
   max/min of their f0 - 1 <= stable_f0; without audio, all e_imu finite
   and max - min <= stable_db; else on + nf(fallback_ms). References over
   [k_su, k_su + nf(ref_ms)) (and before the end): f_ref = median f0, c_ref
   = median e_cut, e_ref = median e_imu, t_ref = median thrust, h_ref =
   median ham. A restart in the hole keeps the previous run's references,
   and its k_su is at most on + nf(restart.spinup_ms) (it spins up into the
   load: a quiet motor's f0 need not settle in the cutting noise, and the
   taxonomy's spinup phase ends by then).

7. Contact [contact] (motor on, bit out). Ratios at frame k, each clipped to
   [0, ratio_max]: r_f0 = (1 - f0 / f_ref) / f0_drop (voiced), r_cut =
   (e_cut - c_ref) / cut_db, and with an IMU sample r_imu = (e_imu - e_ref)
   / imu_db, r_thrust = (t_ref - thrust) / thrust_g, r_hammer = (ham -
   h_ref) / hammer. score = max(S_drill, S_hammer): S_drill = sum w r / sum
   w over the available of f0, cut, imu, thrust (w = weights; 0 when none);
   S_hammer the same over hammer and cut. The score is computed from the
   search start to the run's end and smoothed by med(smooth_frames). A
   contact is nf(hold_ms) frames in a row with a smoothed score > score;
   its frame walks back from the first of them while the smoothed score of
   the frame before is > back_frac * the median smoothed score of the hold
   (not before the search start). The search starts at k_su, after a load
   end at k_e + nf(load_end.hold_ms).

8. In the hole [load_end, stuck], frame by frame from contact +
   nf(hold_ms) (after a restart in the hole: from its k_su) until the
   motor_off. Levels at frame k over [k - nf(level_gap_ms) - nf(level_ms),
   k - nf(level_gap_ms)): f_dr = median f0, c_dr = median e_cut, h_dr =
   median ham, e_dr = median med9(e_imu) (9 = stuck.smooth_frames).
   Stuck ratio at k (0 while s(k) < motor.on_z): (1 - f0 / f_ref) /
   stuck.f0_drop when voiced, else (e_ref - med9(e_imu)) / stuck.imu_db
   when both are finite, else 0. (The IMU against its free-run level: a
   stalling motor shakes the board less than a free one; a load end, the
   bit pulled out or the hammer blows stopping, brings it back to that
   level, not below it.) nf(stuck.hold_ms) frames in a row with a ratio > 1
   start a stuck span at the first of them, k_s; with the levels of k_s
   frozen, k_s walks back (not before contact + 1) while the looser ratio
   of the frame before is > 1: the larger of (1 - f0 / f_dr) / back_drop
   (voiced) and (e_dr - med9(e_imu)) / back_db. The span ends where
   nf(stuck.hold_ms) frames in a row have a ratio (frozen levels) <= 1, or
   at the motor_off. When its first stuck span starts, the hole keeps h_hole
   = median ham over [contact + nf(contact.hold_ms), k_s) (section 9). No
   load end is looked for during a stuck span.
   Load end: R_f = (f0 - f_dr) / (f_ref - f_dr) when voiced and (f_ref -
   f_dr) / f_ref >= min_sag; R_c = (c_dr - e_cut) / (c_dr - c_ref) when c_dr
   - c_ref >= min_cut_db; R_h = (h_dr - ham) / (h_dr - h_ref) when h_dr >=
   contact.hammer / 2 and h_dr - h_ref >= contact.hammer / 2; R = the mean
   of the available (none: no end). nf(load_end.hold_ms) frames in a row
   with R >= recover and a stuck ratio <= 1 (a stalling motor is no load
   end, though the cutting and the blows stop with it) end the load at the
   first of them, k_e; with the levels of k_e frozen, the recovery starts
   at k_r: from k_e walk back (not before contact + 1) while R of the frame
   before is > start.

9. Breakthrough or retract [load_end]. Window W = [max(contact + 1, k_r -
   nf(window_ms[0])), k_e + nf(window_ms[1])]; baseline B = [contact +
   nf(contact.hold_ms), W start) (at least 3 frames, else [contact, W
   start)); hammering when the larger of the finite ones of median(ham over
   B) and the hole's h_hole (section 8) is >= contact.hammer / 2 (neither
   finite: not hammering; a restart's short load leans on how the hole was
   cut before the stall).
   r_imp = (max imp over W - median imp over B) / imp_g (not while
   hammering: the blows put axial peaks into every frame), r_gyro = (max
   gyro_rad over W - median over B) / gyro_dps, r_flux = (max flux over W /
   median over B - 1) / (flux_ratio - 1) (median > 0); r_i = max of the
   available (0 if none). Breakthrough when r_i >= 1, else a retract at the
   frame k_r. Breakthrough time: the IMU onset when r_imp >= 1 (the IMU
   samples in W: thr = median imp over B + imp_onset_frac * (peak - that),
   the first sample up to the peak with imp > thr); the audio onset when
   r_flux >= 1 (k_f = the first frame of W with the largest flux; samples
   [round(index(t(k_f) - audio_search_ms[0])), round(index(t(k_f) +
   audio_search_ms[1]))), base = RMS of the round(audio_base_ms * 1000 / b)
   samples before; the first sample with |x| > audio_onset_ratio * base; t
   = round(a + b * i)); the audio onset when both exist and agree within
   audio_imu_agree_ms, else the IMU one, else the audio one, else frame k_r.

10. Restart [restart]: a run after a motor_off in the hole cuts again when
   the median smoothed contact score (old references) over [k_su, k_su +
   nf(check_ms)) is > score (k_su capped as in section 6): the hole goes
   on (contact := k_su), no event. Otherwise a retract (the bit pulled out with the motor off) goes
   at the frame before the motor_on, confidence restart_retract.

11. Kickback [kickback], within motor runs: at frame k, x = max(|roll(k)| /
   rate_dps, |sum of roll over the nf(roll_ms) frames up to k (not before
   the run) * F / 1e6| / roll_deg); x > 1 triggers (not within
   nf(refractory_ms) frames of the last trigger). Sign: of roll(k) when
   |roll(k)| > rate_dps, else of the sum. Walk back from k while sign *
   roll of the frame before > back_dps (within the run): j; the time is the
   first IMU sample in [t(j), t(k + 1)) with sign * roll > back_dps (else
   t(j)). Confidence from max(x, max |roll| over the next nf(roll_ms)
   frames / rate_dps). Hole: the one the bit is in at that time, or the
   one it left (breakthrough, retract) at most hole_after_ms before.

12. Suggestions, confidence = confidence_max * sigmoid((x - 1) /
   conf_scale). wobble [wobble]: frames inside a hole with the motor on and
   wob > rms_dps, runs of at least nf(min_ms) in one hole; x = median wob /
   rms_dps. In a hole with the motor on: walk the events found (kickbacks
   left out) by time; the frames from (t_a - T0) // F up to (t_b - T0) // F
   (exclusive) between an event at t_a and the next at t_b (the last: up to
   K) are in the state after the event at t_a (a contact: in its hole, the
   motor on; a breakthrough or retract: out; a motor_off / motor_on: the
   motor off / on). wander [wander]: per contact, the LEVEL rows with IMU_OK from
   the contact to the earlier of contact + after_contact_ms and the hole's
   next breakthrough, retract or motor_off (at least 2 rows), skipped when
   median ham over those frames >= contact.hammer / 2 (the blows shake the
   device's level); drift = the largest |elevation - first| or |yaw -
   first| (yaw wrapped to +-180; elevation = labels.absolute_elevation_deg);
   x = drift / deg > 1. speech [speech]: windows of nf(window_ms) frames
   every nf(hop_ms) from frame 0, all of them motor-off (farther than
   nf(motor_pad_ms) from a run) with e_speech finite and median z_speech >
   z; env = 10^(e_speech / 20) - its mean; the window's DFT at the integer
   bins b (1 <= b < n/2) with mod_hz[0] <= b * 1000 / (frame_ms n) <=
   mod_hz[1]: frac = 2 * sum |A_b|^2 / (n * sum env^2) > mod_frac; touching
   or overlapping windows merge (frac = their largest); x = frac /
   mod_frac; an ignore span with reason speech.

13. Confidence of the events: sigmoid((x - threshold) / conf_scale):
   motor_on x = the median s of its nf(on_ms) trigger frames vs on_z;
   motor_off x = the median s of the nf(conf_ms) frames before k_d vs off_z
   (both conf_scale_z), times confidence.disagree when audio exists and
   z_imu and z_aud (their medians over the run's first nf(conf_ms) frames)
   are on different sides of on_z, or one is NaN. contact: the hold's
   median smoothed score vs score, times disagree when the hold's mean
   audio ratio (mean of r_f0, r_cut per frame) and IMU ratio (mean of
   r_imu, r_thrust, r_hammer per frame) are >= 1 and < 0.5 (either way).
   load end [load_end]: breakthrough x = r_i vs 1, retract 1 - r_i vs 0,
   times disagree when the IMU (max of r_imp, r_gyro) and r_flux are on
   different sides of 1. stuck [stuck]: the median ratio of the hold vs 1.
   kickback [kickback]: x vs 1. All rounded to 3 decimals, clipped to
   [0, 1].

The output is a complete events.json: source auto, ids e1.. (events
sorted by t_us, then by type name) and s1.. (spans sorted by t0_us, then by
type name), holes numbered from 1 by contact, holes[] with through (true
after a breakthrough, false after a retract, null when the take ends in the
hole) and outcome tags: bit_stuck / kickback where found, else clean for a
hole that ended (none for one the take ends in); coverage_us from the
recording; it follows the label grammar by construction. The derived labels for meta.json come from
labels.compute_derived over the LEVEL rows.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from . import contract, labels
from .contract import CONTRACT_DIR, read_json
from .io import EVENTS_FILE, LEVEL_FLAG_IMU_OK, META_FILE, Recording, _write_csv_gz, dump_json, read_recording

DETECTOR = "dbauto"
PARAMS_FILE = CONTRACT_DIR / "autodetect_params.json"
FIXTURES_DIR = Path(__file__).resolve().parents[1] / "fixtures" / "autodetect"
FIXTURE_SCHEMA = "dbauto.fixture/1"
FEATURE_COLUMNS = ("frame", "t_us", "e_imu", "thrust", "imp", "roll", "gyro_rad", "wob", "ham",
                   "e_aud", "f0", "harm", "e_cut", "flux", "e_speech", "z_imu", "z_aud", "z_speech", "s")
# event-F1 tolerances (us): the contract's for motor, contact and breakthrough; the rest reported
TOLERANCES_US = {"motor_on": 30_000, "motor_off": 30_000, "contact": 100_000, "breakthrough": 50_000,
                 "retract": 100_000, "kickback": 100_000}
SQRT1_2 = 1.0 / math.sqrt(2.0)


# ---- parameters ---------------------------------------------------------------------

@dataclass(frozen=True)
class Params:
    doc: dict
    sha256: str

    @property
    def version(self) -> str:
        return f"{DETECTOR}/{self.doc['version']}"

    def __getitem__(self, key):
        return self.doc[key]


def params_sha256(raw: bytes) -> str:
    """sha256 of the params file with CRLF normalised to LF (a Windows checkout hashes the same)."""
    return hashlib.sha256(raw.replace(b"\r\n", b"\n")).hexdigest()


def load_params(path: str | Path | None = None) -> Params:
    raw = Path(path or PARAMS_FILE).read_bytes()
    doc = json.loads(raw.decode("utf-8"))
    if doc.get("schema") != "dbauto.params/1":
        raise ValueError(f"{path or PARAMS_FILE}: not a dbauto.params/1 file")
    return Params(doc, params_sha256(raw))


# ---- small helpers (the Kotlin port mirrors these) -----------------------------------

def rnd(x: float) -> int:
    return int(math.floor(x + 0.5))


def pct(v, q: float) -> float:
    a = np.asarray(v, dtype=np.float64)
    a = a[np.isfinite(a)]
    return float(np.percentile(a, q)) if len(a) else math.nan


def med(v) -> float:
    """pct(v, 50) to the bit (np.percentile's linear rule: for an even count the
    upper middle value minus half the gap), without its per-call overhead."""
    a = np.asarray(v, dtype=np.float64)
    a = np.sort(a[np.isfinite(a)])
    n = len(a)
    if n == 0:
        return math.nan
    if n % 2:
        return float(a[n // 2])
    lo, hi = a[n // 2 - 1], a[n // 2]
    return float(hi - (hi - lo) * 0.5)


def median_rows(w: np.ndarray) -> np.ndarray:
    """med() of every row of the 2-D w (NaN or inf: no value there), vectorised."""
    s = np.sort(np.where(np.isfinite(w), w, np.nan), axis=1)    # the values first, NaN last
    n = np.isfinite(s).sum(axis=1)
    out = np.full(len(s), np.nan)
    r = np.nonzero(n)[0]
    nn = n[r]
    lo, hi = s[r, (nn - 1) // 2], s[r, nn // 2]
    out[r] = np.where(nn % 2 == 1, lo, hi - (hi - lo) * 0.5)
    return out


def sigmoid(x: float) -> float:
    return 1.0 / (1.0 + math.exp(-max(-60.0, min(60.0, x))))


def biquad_coeffs(kind: str, fc: float, fs: float, q: float = SQRT1_2) -> tuple[float, ...]:
    """(b0, b1, b2, a1, a2), RBJ cookbook, normalised by a0."""
    w = 2.0 * math.pi * fc / fs
    al = math.sin(w) / (2.0 * q)
    c = math.cos(w)
    a0 = 1.0 + al
    if kind == "hp":
        b = ((1.0 + c) / 2.0, -(1.0 + c), (1.0 + c) / 2.0)
    elif kind == "lp":
        b = ((1.0 - c) / 2.0, 1.0 - c, (1.0 - c) / 2.0)
    else:
        raise ValueError(kind)
    return b[0] / a0, b[1] / a0, b[2] / a0, -2.0 * c / a0, (1.0 - al) / a0


def biquad_reference(x, coeffs, kind: str) -> np.ndarray:
    """The biquad as the port writes it: direct form I, sample by sample, started
    in the steady state of x[0]. biquad() is the same filter, vectorised."""
    b0, b1, b2, a1, a2 = coeffs
    xs = np.asarray(x, dtype=np.float64).tolist()
    out = [0.0] * len(xs)
    if not xs:
        return np.zeros(0)
    x1 = x2 = xs[0]
    y1 = y2 = 0.0 if kind == "hp" else xs[0]
    for i, v in enumerate(xs):
        y = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, v, y1, y
        out[i] = y
    return np.asarray(out)


BIQUAD_BLOCK = 128


def biquad(x, coeffs, kind: str) -> np.ndarray:
    """biquad_reference(), vectorised: equal within rounding (about 1e-12 of the
    output's scale; the fixtures' features agree to 1e-6). The steady state of
    x[0] is the filter's DC gain times x[0] (0 for a high-pass, x[0] for a
    low-pass), so the output is that plus the response from rest to u = x -
    x[0]. Its FIR part v[n] = b0 u[n] + b1 u[n-1] + b2 u[n-2] is one array
    expression; the all-pole part y[n] = v[n] - a1 y[n-1] - a2 y[n-2] runs
    block by block: within a block v convolved with the all-pole impulse
    response g (one matrix product for all blocks), plus the two outputs before
    the block times the responses to them (h_a, h_b), carried from block to
    block."""
    b0, b1, b2, a1, a2 = coeffs
    x = np.asarray(x, dtype=np.float64)
    n = len(x)
    if n == 0:
        return np.zeros(0)
    u = x - x[0]
    v = b0 * u
    v[1:] += b1 * u[:-1]
    v[2:] += b2 * u[:-2]
    nb = BIQUAD_BLOCK
    g = [1.0, -a1]
    for _ in range(2, nb):
        g.append(-a1 * g[-1] - a2 * g[-2])
    g = np.asarray(g)
    h_a = -a1 * g                       # the response to y[-1] = 1
    h_a[1:] -= a2 * g[:-1]
    h_b = -a2 * g                       # the response to y[-2] = 1
    lag = np.arange(nb)[None, :] - np.arange(nb)[:, None]
    conv = np.where(lag >= 0, g[np.clip(lag, 0, None)], 0.0)
    blocks = -(-n // nb)
    vb = np.zeros(blocks * nb)
    vb[:n] = v
    z = vb.reshape(blocks, nb) @ conv                 # every block from rest
    carry = np.zeros((blocks, 2))
    y1 = y2 = 0.0
    for i, (z1, z2) in enumerate(zip(z[:, -1].tolist(), z[:, -2].tolist())):
        carry[i] = y1, y2
        y1, y2 = z1 + y1 * h_a[-1] + y2 * h_b[-1], z2 + y1 * h_a[-2] + y2 * h_b[-2]
    y = (z + carry[:, :1] * h_a + carry[:, 1:] * h_b).reshape(-1)[:n]
    return y if kind == "hp" else y + x[0]


def hp(x, fc, fs):
    return biquad(x, biquad_coeffs("hp", fc, fs), "hp")


def lp(x, fc, fs):
    return biquad(x, biquad_coeffs("lp", fc, fs), "lp")


def centred_median(v: np.ndarray, n: int) -> np.ndarray:
    """Median of v over [k - n//2, k + n//2] (clipped at the ends; NaN ignored)."""
    h = n // 2
    v = np.asarray(v, dtype=np.float64)
    if len(v) == 0:
        return np.zeros(0)
    padded = np.concatenate([np.full(h, np.nan), v, np.full(h, np.nan)])
    return median_rows(np.lib.stride_tricks.sliding_window_view(padded, 2 * h + 1))


def ms_frames(ms: float, frame_ms: float) -> int:
    return max(1, rnd(ms / frame_ms))


# ---- inputs and features ------------------------------------------------------------

@dataclass
class Inputs:
    """What the detector reads: the IMU (t_us, counts), MIC1 and its sync fit, LEVEL rows."""

    t_us: np.ndarray                  # int64, IMU sample times
    counts: np.ndarray                # (N, 6) ax ay az gx gy gz
    accel_lsb_per_g: float
    gyro_lsb_per_dps: float
    odr_hz: float
    drill_axis: tuple[float, float, float]
    audio: np.ndarray | None          # (M,) int16, MIC1
    audio_rate_hz: float
    sync_a_us: float
    sync_b_us: float
    level: object | None              # level.csv.gz DataFrame
    coverage_us: tuple[int, int]
    recording_id: str
    meta: dict
    audio_gaps: list = field(default_factory=list)   # [[f0, f1), ...] silence filled in for missing audio

    @classmethod
    def from_recording(cls, rec: Recording) -> "Inputs":
        m = rec.meta["imu"]
        mount = m.get("mount") or {}
        axis = mount.get("drill_axis") if mount.get("valid") else None
        d = np.asarray(axis if axis else (0.0, 0.0, -1.0), dtype=np.float64)
        d = d / np.linalg.norm(d)
        audio = None
        rate = 16000.0
        a_us = b_us = 0.0
        if rec.audio is not None and rec.sync is not None and len(rec.audio):
            audio = np.asarray(rec.audio[:, 0], dtype=np.int16)
            rate = float(rec.meta["audio"]["rate_nominal_hz"])
            a_us = rec.sync.a_us - rec.sync.adc_delay_us
            b_us = rec.sync.b_us_per_frame
        t = rec.t_us
        cov = rec.events.get("coverage_us") if rec.events else None
        coverage = (int(cov[0]), int(cov[1])) if cov else (int(t[0]), int(t[-1]) + 1)
        gaps = [(int(g[0]), int(g[1])) for g in (rec.meta.get("gaps") or {}).get("audio_gap_frames") or []]
        return cls(t, np.asarray(rec.imu_raw, dtype=np.float64), float(m["accel_lsb_per_g"]),
                   float(m["gyro_lsb_per_dps"]), float(m["odr_nominal_hz"]), tuple(float(c) for c in d),
                   audio, rate, a_us, b_us, rec.level, coverage, rec.id, rec.meta, gaps)


@dataclass
class Features:
    t0_us: int
    frame_us: int
    cols: dict                        # name -> (K,) float64 (FEATURE_COLUMNS without frame/t_us)
    imu_t: np.ndarray                 # sample level, for onsets
    imp_s: np.ndarray
    roll_s: np.ndarray
    audio: np.ndarray | None          # float MIC1
    sync_a_us: float
    sync_b_us: float

    @property
    def n(self) -> int:
        return len(self.cols["e_imu"])

    @property
    def has_audio(self) -> bool:
        """Any frame with audio (e_aud is all NaN without it)."""
        return bool(np.isfinite(self.cols["e_aud"]).any())

    def t(self, k: int) -> int:
        return self.t0_us + k * self.frame_us

    def __getitem__(self, name):
        return self.cols[name]

    def audio_t(self, i) -> float:
        return self.sync_a_us + self.sync_b_us * i

    def audio_index(self, t_us) -> float:
        return (t_us - self.sync_a_us) / self.sync_b_us

    def table(self) -> dict:
        """The per-frame features as columns (the fixture's features.csv.gz)."""
        k = np.arange(self.n)
        out = {"frame": k, "t_us": self.t0_us + k * self.frame_us}
        out.update({c: self.cols[c] for c in FEATURE_COLUMNS[2:]})
        return out


def _band(lo: float, hi: float, d: float, nbins: int) -> tuple[int, int]:
    return max(0, math.ceil(lo / d)), min(nbins - 1, math.floor(hi / d))


def imu_features(inp: Inputs, p: Params, t0: int, frame_us: int, k_frames: int) -> tuple[dict, np.ndarray, np.ndarray]:
    fp = p["features"]
    fs = inp.odr_hz
    a = inp.counts[:, :3] / inp.accel_lsb_per_g
    w = inp.counts[:, 3:] / inp.gyro_lsb_per_dps
    d = np.asarray(inp.drill_axis)
    h = np.column_stack([hp(a[:, i], fp["hp_hz"], fs) for i in range(3)])
    e = (h * h).sum(axis=1)
    imp = h @ d
    lo, hi = fp["thrust_band_hz"]
    thrust = lp(hp(a @ d, lo, fs), hi, fs)
    roll = w @ d
    wr = w - np.outer(roll, d)
    gr = np.sqrt(sum(hp(wr[:, i], fp["hp_hz"], fs) ** 2 for i in range(3)))
    wlo, whi = fp["wobble_band_hz"]
    wb = sum(lp(hp(wr[:, i], wlo, fs), whi, fs) ** 2 for i in range(3))

    t = inp.t_us
    b = ((t - t0) // frame_us).astype(np.int64)
    cnt = np.bincount(b, minlength=k_frames).astype(np.float64)
    has = cnt > 0
    nan = np.full(k_frames, np.nan)

    def mean_of(v):
        out = nan.copy()
        out[has] = np.bincount(b, weights=v, minlength=k_frames)[has] / cnt[has]
        return out

    def max_of(v):
        out = np.full(k_frames, -np.inf)
        np.maximum.at(out, b, v)
        out[~has] = np.nan
        return out

    # wobble: RMS over the samples of the wobble_window_ms centred on the frame (a cycle of the band)
    sw = np.concatenate([[0.0], np.cumsum(np.bincount(b, weights=wb, minlength=k_frames))])
    sc = np.concatenate([[0.0], np.cumsum(cnt)])
    hw = ms_frames(fp["wobble_window_ms"], frame_us / 1000.0) // 2
    lo_k = np.clip(np.arange(k_frames) - hw, 0, k_frames)
    hi_k = np.clip(np.arange(k_frames) + hw + 1, 0, k_frames)
    n_w = sc[hi_k] - sc[lo_k]
    wob = np.full(k_frames, np.nan)
    wob[n_w > 0] = np.sqrt((sw[hi_k] - sw[lo_k])[n_w > 0] / n_w[n_w > 0])
    cols = {"e_imu": 10.0 * np.log10(mean_of(e) + fp["eps_g2"]), "thrust": mean_of(thrust), "imp": max_of(imp),
            "roll": mean_of(roll), "gyro_rad": max_of(gr), "wob": wob}

    # hammer periodicity: autocorrelation of |imp| at the blow rates
    env = np.abs(imp)
    half = fp["hammer_window_ms"] * 500.0
    r_lo, r_hi = fp["hammer_rate_hz"]
    lag_lo, lag_hi = rnd(fs / r_hi), rnd(fs / r_lo)
    ham = np.zeros(k_frames)
    tc = t0 + np.arange(k_frames) * frame_us + frame_us / 2.0        # the frames' centres
    i0s = np.searchsorted(t, tc - half, side="right").tolist()
    i1s = np.searchsorted(t, tc + half, side="left").tolist()
    for k in range(k_frames):
        i0, i1 = i0s[k], i1s[k]
        if i1 - i0 < 4:
            continue
        x = env[i0:i1] - env[i0:i1].mean()
        den = float(x @ x)
        if den <= 0.0:
            continue
        n = len(x)
        if lag_lo < n:
            # c[n - 1 + L] = sum x[i] x[i + L]: the lags [lag_lo, lag_hi] below n, in one call
            c = np.correlate(x, x, "full")[n - 1 + lag_lo:n + min(lag_hi, n - 1)]
            ham[k] = float(c.max()) / den
    cols["ham"] = ham
    return cols, imp, roll


def audio_features(inp: Inputs, x: np.ndarray | None, p: Params, t0: int, frame_us: int, k_frames: int) -> dict:
    """x: inp.audio as float (full scale 1), or None."""
    fp = p["features"]
    names = ("e_aud", "f0", "harm", "e_cut", "flux", "e_speech")
    cols = {c: np.full(k_frames, np.nan) for c in names}
    if x is None:
        return cols
    m = len(x)
    s = np.array([rnd((t0 + k * frame_us - inp.sync_a_us) / inp.sync_b_us) for k in range(k_frames + 1)])
    ok = (s[:-1] >= 0) & (s[1:] <= m) & (s[1:] > s[:-1])
    for g0, g1 in inp.audio_gaps:          # the transport lost these samples: no audio there
        ok &= ~((s[:-1] < g1) & (s[1:] > g0))
    csum = np.concatenate([[0.0], np.cumsum(x * x)])
    n_win, n_fft = int(fp["fft_window"]), int(fp["fft_size"])
    nb = n_fft // 2 + 1
    dbin = inp.audio_rate_hz / n_fft
    win = 0.5 - 0.5 * np.cos(2.0 * np.pi * np.arange(n_win) / n_win)
    kk = np.nonzero(ok)[0]
    seg = np.zeros((len(kk), n_win))
    for r, k in enumerate(kk):
        c = (s[k] + s[k + 1]) // 2
        a0, a1 = c - n_win // 2, c + n_win // 2
        lo, hi = max(0, a0), min(m, a1)
        seg[r, lo - a0:hi - a0] = x[lo:hi]
        cols["e_aud"][k] = 10.0 * math.log10((csum[s[k + 1]] - csum[s[k]]) / (s[k + 1] - s[k]) + fp["eps_fs2"])
    mag = np.abs(np.fft.rfft(seg * win, n_fft, axis=1)) if len(kk) else np.zeros((0, nb))
    pw = mag * mag

    def band_db(band):
        j0, j1 = _band(band[0], band[1], dbin, nb)
        return 10.0 * np.log10(pw[:, j0:j1 + 1].sum(axis=1) + fp["eps_fs2"])

    cols["e_cut"][kk] = band_db(fp["cut_band_hz"])
    cols["e_speech"][kk] = band_db(fp["speech_band_hz"])

    lmag = np.log(mag + 1e-6)
    f0, f1 = _band(fp["flux_band_hz"][0], fp["flux_band_hz"][1], dbin, nb)
    prev_ok = np.concatenate([[False], ok[:-1]])
    # with prev_ok[k], frame k - 1 is in kk: the row before
    band = lmag[:, f0:f1 + 1]
    prev = np.where(prev_ok[kk][:, None], np.roll(band, 1, axis=0), math.log(1e-6))
    cols["flux"][kk] = np.maximum(0.0, band - prev).mean(axis=1)

    hh = int(fp["hps_harmonics"])
    j0, j1 = math.ceil(fp["f0_range_hz"][0] / dbin), math.floor(fp["f0_range_hz"][1] / dbin)
    j1 = min(j1, (nb - 1) // hh)
    jj = np.arange(j0, j1 + 1)
    lh = np.log(mag + 1e-9)
    hps = sum(lh[:, h * jj] for h in range(1, hh + 1))
    pb = int(fp["prominence_bins"])
    best = np.argmax(hps, axis=1) if len(kk) else np.zeros(0, dtype=np.int64)   # the first maximum
    rows = np.arange(len(kk))
    harms = np.zeros(len(kk))
    for h in range(1, hh + 1):
        # L at the harmonic minus the median of L over [h j - B, h j + B] (clipped at the ends)
        c = h * jj[best]
        win = c[:, None] + np.arange(-pb, pb + 1)
        vals = np.where((win >= 0) & (win < nb), lh[rows[:, None], np.clip(win, 0, nb - 1)], np.nan)
        harms += lh[rows, c] - median_rows(vals)
    harms /= hh
    for r, k in enumerate(kk):
        v = hps[r]
        i = int(best[r])
        harm = float(harms[r])
        cols["harm"][k] = harm
        if harm < fp["voiced_min"] or i == 0 or i == len(v) - 1:
            continue
        den = v[i - 1] - 2.0 * v[i] + v[i + 1]
        delta = max(-0.5, min(0.5, 0.5 * (v[i - 1] - v[i + 1]) / den)) if den != 0.0 else 0.0
        cols["f0"][k] = (jj[i] + delta) * dbin
    return cols


def quiet_block(v: np.ndarray, block: int) -> slice:
    """The frames of v's quietest block (section 4)."""
    best, best_med = None, math.inf
    for i in range(0, len(v) - block + 1, block):
        b = v[i:i + block]
        if np.isfinite(b).sum() * 2 < block:
            continue
        mm = med(b)
        if mm < best_med:
            best, best_med = i, mm
    return slice(0, len(v)) if best is None else slice(best, best + block)


def noise_z(v: np.ndarray, ref_block: np.ndarray, p: Params) -> np.ndarray:
    fl = pct(ref_block, p["noise"]["floor_pct"])
    mad = med(np.abs(ref_block - med(ref_block)))
    spread = max(1.4826 * mad, p["noise"]["spread_min_db"]) if np.isfinite(mad) else p["noise"]["spread_min_db"]
    return (v - fl) / spread


def compute_features(inp: Inputs, p: Params | None = None) -> Features:
    p = p or load_params()
    frame_us = int(p["frame_ms"] * 1000)
    t0 = int(inp.t_us[0])
    k_frames = int((int(inp.t_us[-1]) - t0) // frame_us) + 1
    cols, imp_s, roll_s = imu_features(inp, p, t0, frame_us, k_frames)
    audio = inp.audio.astype(np.float64) / 32768.0 if inp.audio is not None else None
    cols.update(audio_features(inp, audio, p, t0, frame_us, k_frames))
    block = ms_frames(p["noise"]["block_ms"], p["frame_ms"])
    cols["z_imu"] = noise_z(cols["e_imu"], cols["e_imu"][quiet_block(cols["e_imu"], block)], p)
    if np.isfinite(cols["e_aud"]).any():      # all NaN without audio
        sl = quiet_block(cols["e_aud"], block)    # also the reference of e_speech
        cols["z_aud"] = noise_z(cols["e_aud"], cols["e_aud"][sl], p)
        cols["z_speech"] = noise_z(cols["e_speech"], cols["e_speech"][sl], p)
    else:
        cols["z_aud"] = np.full(k_frames, np.nan)
        cols["z_speech"] = np.full(k_frames, np.nan)
    nz = int(p["features"]["smooth_z_frames"])
    # the larger of the two where either exists, NaN where neither does (a transport gap)
    cols["s"] = np.fmax(centred_median(cols["z_imu"], nz), centred_median(cols["z_aud"], nz))
    return Features(t0, frame_us, cols, inp.t_us, imp_s, roll_s, audio, inp.sync_a_us, inp.sync_b_us)


# ---- rules ---------------------------------------------------------------------------

@dataclass
class Event:
    type: str
    t_us: int
    confidence: float
    hole: int | None = None


@dataclass
class Span:
    type: str
    t0_us: int
    t1_us: int
    confidence: float
    hole: int | None = None
    reason: str | None = None


@dataclass
class Run:
    """One motor run: frames [on, off) (off None: still running at the end)."""

    on: int
    off: int | None
    on_conf: float
    off_conf: float = 0.0


@dataclass
class Detection:
    events: dict                      # events.json
    derived: dict                     # meta.derived
    features: Features


class _Detector:
    def __init__(self, inp: Inputs, f: Features, p: Params):
        self.inp, self.f, self.p = inp, f, p
        self.fm = float(p["frame_ms"])
        self.K = f.n
        self.events: list[Event] = []
        self.spans: list[Span] = []
        self.holes: dict[int, dict] = {}
        self.has_audio = f.has_audio
        self.voiced = np.isfinite(f["f0"])
        ns = int(p["features"]["smooth_e_frames"])
        self.e_imu_s = centred_median(f["e_imu"], ns)
        self.e_aud_s = centred_median(f["e_aud"], ns)
        self.e_stall = centred_median(f["e_imu"], int(p["stuck"]["smooth_frames"]))
        self.zi_s = centred_median(f["z_imu"], int(p["features"]["smooth_z_frames"]))

    def nf(self, ms):
        return ms_frames(ms, self.fm)

    # ---- 5. motor runs ----
    def motor_runs(self) -> list[Run]:
        mp = self.p["motor"]
        s = self.f["s"]
        n_on, n_off = self.nf(mp["on_ms"]), self.nf(mp["off_ms"])
        runs, state, cnt, lower = [], "off", 0, 0
        cur = None
        for k in range(self.K):
            if not np.isfinite(s[k]):
                continue                  # no data (a transport gap): the counts stand
            if state == "off":
                cnt = cnt + 1 if s[k] > mp["on_z"] else 0
                if cnt == n_on:
                    kd = k - n_on + 1
                    on = kd
                    while on > lower and s[on - 1] > mp["back_z"]:
                        on -= 1
                    conf = sigmoid((med(s[kd:kd + n_on]) - mp["on_z"]) / mp["conf_scale_z"])
                    if on == 0:
                        conf = self.p["confidence"]["open_motor_on"]
                    cur = Run(on, None, conf)
                    state, cnt = "on", 0
            else:
                cnt = cnt + 1 if s[k] < mp["off_z"] else 0
                if cnt == n_off:
                    kd = k - n_off + 1
                    cur.off = kd          # the low stretch; motor_off_time refines it
                    w = s[max(cur.on, kd - self.nf(mp["conf_ms"])):kd]
                    cur.off_conf = sigmoid((med(w) - mp["off_z"]) / mp["conf_scale_z"])
                    runs.append(cur)
                    cur, state, cnt, lower = None, "off", 0, k + 1
        if cur is not None:
            runs.append(cur)
        # the board is clipped to the drill, so a running motor always shakes it: a run the IMU
        # never confirms is sound alone (speech, another machine in the room)
        zi = self.zi_s
        if np.isfinite(zi).any():
            runs = [r for r in runs if np.nanmax(np.append(zi[r.on:r.off if r.off is not None else self.K], -np.inf))
                    >= mp["on_z"]]
        return runs

    def motor_disagree(self, run: Run) -> bool:
        if not self.has_audio:
            return False
        sl = slice(run.on, min(self.K, run.on + self.nf(self.p["motor"]["conf_ms"])))
        zi, za = med(self.f["z_imu"][sl]), med(self.f["z_aud"][sl])
        on = self.p["motor"]["on_z"]
        return not (np.isfinite(zi) and np.isfinite(za)) or (zi < on) != (za < on)

    def motor_off_time(self, run: Run) -> int:
        """The frame the motor stops, walking back from the low stretch run.off: first the
        louder stream's energy knee, then the f0 knee within off_f0_ms before it."""
        mp, f = self.p["motor"], self.f
        kd = run.off
        lo = max(run.on + 1, kd - self.nf(mp["off_search_ms"]))
        hold = self.nf(mp["off_hold_ms"])
        s, on_z = f["s"], mp["on_z"]
        w = slice(max(run.on, kd - self.nf(mp["conf_ms"])), kd)
        loud_aud = self.has_audio and med(f["z_aud"][w]) >= med(f["z_imu"][w])
        e = self.e_aud_s if loud_aud else self.e_imu_s
        level = med(e[run.on:kd]) - mp["off_level_db"]
        k_en = kd
        for k in range(kd, lo - 1, -1):
            past = e[max(0, k - hold):k]
            if s[k] >= on_z and np.isfinite(e[k]) and e[k] >= level and np.isfinite(past).sum() >= 3 and \
                    e[k] >= med(past) - mp["off_drop_db"]:
                k_en = min(kd, k + 1)
                break
        f0 = f["f0"]
        for k in range(k_en, max(lo, k_en - self.nf(mp["off_f0_ms"])) - 1, -1):
            if not self.voiced[k] or s[k] < on_z:
                continue
            past = f0[max(0, k - hold):k]
            past = past[np.isfinite(past)]
            if len(past) >= 3 and f0[k] >= (1.0 - mp["off_f0_drop"]) * med(past):
                return min(k_en, k + 1)
        return k_en

    # ---- 6. spin-up end and references ----
    def spinup_end(self, run: Run, end: int) -> int:
        sp = self.p["spinup"]
        n = self.nf(sp["stable_ms"])
        f0, e = self.f["f0"], self.f["e_imu"]
        for k in range(run.on + self.nf(sp["min_ms"]), min(end, run.on + self.nf(sp["max_ms"]))):
            w = f0[k:k + n]
            if self.has_audio:
                v = w[np.isfinite(w)]
                if len(v) >= sp["voiced_frac"] * n and v.max() / v.min() - 1.0 <= sp["stable_f0"]:
                    return k
            else:
                v = e[k:k + n]
                if np.isfinite(v).all() and len(v) == n and v.max() - v.min() <= sp["stable_db"]:
                    return k
        return min(end, run.on + self.nf(sp["fallback_ms"]))

    def references(self, k_su: int, end: int) -> dict:
        sl = slice(k_su, min(end, k_su + self.nf(self.p["spinup"]["ref_ms"])))
        f = self.f
        return {"f": med(f["f0"][sl]), "c": med(f["e_cut"][sl]), "e": med(f["e_imu"][sl]),
                "t": med(f["thrust"][sl]), "h": med(f["ham"][sl])}

    # ---- 7. contact score ----
    def contact_ratios(self, ref: dict, k: int) -> dict:
        cp, f = self.p["contact"], self.f
        rm = cp["ratio_max"]
        r = {}

        def put(name, v):
            if np.isfinite(v):
                r[name] = max(0.0, min(rm, float(v)))

        if self.voiced[k] and ref["f"] > 0:
            put("f0", (1.0 - f["f0"][k] / ref["f"]) / cp["f0_drop"])
        put("cut", (f["e_cut"][k] - ref["c"]) / cp["cut_db"])
        if np.isfinite(f["e_imu"][k]):
            put("imu", (f["e_imu"][k] - ref["e"]) / cp["imu_db"])
            put("thrust", (ref["t"] - f["thrust"][k]) / cp["thrust_g"])
            put("hammer", (f["ham"][k] - ref["h"]) / cp["hammer"])
        return r

    def score_of(self, r: dict) -> float:
        wt = self.p["contact"]["weights"]

        def mix(names):
            ws = sum(wt[n] for n in names if n in r)
            return sum(wt[n] * r[n] for n in names if n in r) / ws if ws > 0 else 0.0
        return max(mix(("f0", "cut", "imu", "thrust")), mix(("hammer", "cut")))

    def scores(self, ref: dict, k0: int, k1: int) -> tuple[np.ndarray, list]:
        rs = [self.contact_ratios(ref, k) for k in range(k0, k1)]
        sc = np.array([self.score_of(r) for r in rs])
        return centred_median(sc, int(self.p["contact"]["smooth_frames"])) if len(sc) else sc, rs

    def find_contact(self, ref: dict, k0: int, k1: int):
        """(frame, confidence, disagree) of the first contact in [k0, k1), or None."""
        cp = self.p["contact"]
        sm, rs = self.scores(ref, k0, k1)
        n = self.nf(cp["hold_ms"])
        cnt = 0
        for i in range(len(sm)):
            cnt = cnt + 1 if sm[i] > cp["score"] else 0
            if cnt == n:
                first = i - n + 1
                plateau = med(sm[first:first + n])
                j = first
                while j > 0 and sm[j - 1] > cp["back_frac"] * plateau:
                    j -= 1
                aud = [np.mean([r[x] for x in ("f0", "cut") if x in r]) for r in rs[first:first + n]
                       if any(x in r for x in ("f0", "cut"))]
                imu = [np.mean([r[x] for x in ("imu", "thrust", "hammer") if x in r]) for r in rs[first:first + n]
                       if "imu" in r]
                a_m, i_m = (float(np.mean(aud)) if aud else None), (float(np.mean(imu)) if imu else None)
                disagree = a_m is not None and i_m is not None and (
                    (a_m >= 1.0 and i_m < 0.5) or (i_m >= 1.0 and a_m < 0.5))
                conf = sigmoid((plateau - cp["score"]) / cp["conf_scale"])
                return k0 + j, conf, disagree
        return None

    # ---- 8. load end and stuck ----
    def levels(self, k: int) -> dict:
        lp_ = self.p["load_end"]
        a = max(0, k - self.nf(lp_["level_gap_ms"]) - self.nf(lp_["level_ms"]))
        b = max(a, k - self.nf(lp_["level_gap_ms"]))
        f = self.f
        return {"f": med(f["f0"][a:b]), "c": med(f["e_cut"][a:b]), "h": med(f["ham"][a:b]),
                "e": med(self.e_stall[a:b])}

    def recovery(self, ref: dict, lv: dict, k: int) -> float:
        lp_, f = self.p["load_end"], self.f
        rs = []
        if self.voiced[k] and np.isfinite(ref["f"]) and np.isfinite(lv["f"]) and \
                (ref["f"] - lv["f"]) / ref["f"] >= lp_["min_sag"]:
            rs.append((f["f0"][k] - lv["f"]) / (ref["f"] - lv["f"]))
        if np.isfinite(f["e_cut"][k]) and np.isfinite(ref["c"]) and np.isfinite(lv["c"]) and \
                lv["c"] - ref["c"] >= lp_["min_cut_db"]:
            rs.append((lv["c"] - f["e_cut"][k]) / (lv["c"] - ref["c"]))
        # only while hammering: a drilling level just above a free run whose ham came out negative is no hammer
        h2 = self.p["contact"]["hammer"] / 2
        if np.isfinite(ref["h"]) and np.isfinite(lv["h"]) and lv["h"] >= h2 and lv["h"] - ref["h"] >= h2:
            rs.append((lv["h"] - f["ham"][k]) / (lv["h"] - ref["h"]))
        return float(np.mean(rs)) if rs else math.nan

    def stuck_ratio(self, ref: dict, lv: dict, k: int, back: bool = False) -> float:
        """> 1 where the motor runs but has (nearly) stalled: voiced f0 below (1 - f0_drop) f_ref,
        or, with the harmonics gone, the IMU more than imu_db below its free-run level ref["e"]
        (the motor shakes the board less than when it runs free; a load end, the bit pulled out
        or the hammer blows stopping, brings it back to that level, not below it).
        back: the looser walk-back test (back_drop, back_db against the drilling level lv)."""
        stp, f = self.p["stuck"], self.f
        if f["s"][k] < self.p["motor"]["on_z"]:
            return 0.0
        r = 0.0
        e = self.e_stall[k]
        if self.voiced[k]:
            if back and np.isfinite(lv["f"]):
                r = (1.0 - f["f0"][k] / lv["f"]) / stp["back_drop"]
            elif not back and np.isfinite(ref["f"]):
                r = (1.0 - f["f0"][k] / ref["f"]) / stp["f0_drop"]
        elif not back and np.isfinite(ref["e"]) and np.isfinite(e):
            r = (ref["e"] - e) / stp["imu_db"]
        if back and np.isfinite(lv["e"]) and np.isfinite(e):
            r = max(r, (lv["e"] - e) / stp["back_db"])
        return r

    def classify_end(self, k_c: int, k_r: int, k_e: int, hole: int):
        """(through, time us, confidence) of the load end recovering from k_r (detected at k_e)."""
        lp_, f = self.p["load_end"], self.f
        w0 = max(k_c + 1, k_r - self.nf(lp_["window_ms"][0]))
        w1 = min(self.K, k_e + self.nf(lp_["window_ms"][1]) + 1)
        b0 = min(k_c + self.nf(self.p["contact"]["hold_ms"]), w0 - 1)
        base = slice(max(k_c, b0), w0)
        if base.stop - base.start < 3:
            base = slice(k_c, w0)
        r = {}
        imp_b = med(f["imp"][base])
        # hammer blows put axial peaks into every frame: only the gyro and the sound tell then
        # (a restart's short load leans on how the hole was cut before the stall)
        h = [v for v in (med(f["ham"][base]), self.holes[hole].get("ham", math.nan)) if np.isfinite(v)]
        hammering = max(h, default=-math.inf) >= self.p["contact"]["hammer"] / 2
        if np.isfinite(imp_b) and np.isfinite(f["imp"][w0:w1]).any():
            if not hammering:
                r["imp"] = (np.nanmax(f["imp"][w0:w1]) - imp_b) / lp_["imp_g"]
            gb = med(f["gyro_rad"][base])
            r["gyro"] = (np.nanmax(f["gyro_rad"][w0:w1]) - gb) / lp_["gyro_dps"]
        fb = med(f["flux"][base])
        if np.isfinite(fb) and fb > 0 and np.isfinite(f["flux"][w0:w1]).any():
            r["flux"] = (np.nanmax(f["flux"][w0:w1]) / fb - 1.0) / (lp_["flux_ratio"] - 1.0)
        r = {k: float(v) for k, v in r.items() if np.isfinite(v)}
        ri = max(r.values()) if r else 0.0
        through = ri >= 1.0
        imu_r = max([r[x] for x in ("imp", "gyro") if x in r], default=None)
        aud_r = r.get("flux")
        disagree = imu_r is not None and aud_r is not None and (imu_r >= 1.0) != (aud_r >= 1.0)
        dis = self.p["confidence"]["disagree"] if disagree else 1.0
        if not through:
            return False, f.t(k_r), sigmoid((1.0 - ri) / lp_["conf_scale"]) * dis
        t_imu = t_aud = None
        if r.get("imp", -1) >= 1.0:
            ta, tb = f.t(w0), f.t(w1)
            i0 = int(np.searchsorted(f.imu_t, ta, side="left"))
            i1 = int(np.searchsorted(f.imu_t, tb, side="left"))
            if i1 > i0:
                seg = f.imp_s[i0:i1]
                pk = int(np.argmax(seg))
                thr = imp_b + lp_["imp_onset_frac"] * (seg[pk] - imp_b)
                first = int(np.argmax(seg[:pk + 1] > thr))
                t_imu = int(f.imu_t[i0 + first])
        if aud_r is not None and aud_r >= 1.0 and f.audio is not None:
            kf = w0 + int(np.nanargmax(f["flux"][w0:w1]))
            a_lo, a_hi = lp_["audio_search_ms"]
            t_aud = self.audio_onset(f.t(kf) - a_lo * 1000, f.t(kf) + a_hi * 1000)
        if t_aud is not None and t_imu is not None:
            t = t_aud if abs(t_aud - t_imu) <= lp_["audio_imu_agree_ms"] * 1000 else t_imu
        else:
            t = t_imu if t_imu is not None else t_aud if t_aud is not None else f.t(k_r)
        return True, int(t), sigmoid((ri - 1.0) / lp_["conf_scale"]) * dis

    def audio_onset(self, t_lo: int, t_hi: int):
        lp_, f = self.p["load_end"], self.f
        x = f.audio
        i_lo, i_hi = rnd(f.audio_index(t_lo)), rnd(f.audio_index(t_hi))
        b0 = i_lo - rnd(lp_["audio_base_ms"] * 1000 / f.sync_b_us)
        if b0 < 0 or i_hi > len(x) or i_hi <= i_lo:
            return None
        base = math.sqrt(float(np.mean(x[b0:i_lo] ** 2)))
        if base <= 0:
            return None
        hit = np.nonzero(np.abs(x[i_lo:i_hi]) > lp_["audio_onset_ratio"] * base)[0]
        return int(rnd(f.audio_t(i_lo + int(hit[0])))) if len(hit) else None

    # ---- the walk over the runs ----
    def run(self):
        f, cp = self.f, self.p["contact"]
        runs = self.motor_runs()
        state = labels.OFF
        hole = 0
        ref = None
        k_c = 0                       # the current hole's contact (or restart) frame
        for run in runs:
            off = self.motor_off_time(run) if run.off is not None else None
            end = off if off is not None else self.K
            dis = self.p["confidence"]["disagree"] if self.motor_disagree(run) else 1.0
            k_su = self.spinup_end(run, end)
            if state == labels.HOLE_OFF:
                # a restart in the hole spins up into the load: its f0 need not settle (the
                # cutting noise hides a quiet motor), and the taxonomy's spinup ends by then
                k_su = min(k_su, run.on + self.nf(self.p["restart"]["spinup_ms"]), end)
                sm, _ = self.scores(ref, k_su, min(end, k_su + self.nf(self.p["restart"]["check_ms"])))
                if len(sm) and med(sm) > self.p["restart"]["score"]:
                    state = labels.HOLE          # restart in the hole
                    k_c = k_su
                else:
                    self.events.append(Event("retract", f.t(run.on - 1), self.p["confidence"]["restart_retract"],
                                             hole))
                    self.holes[hole]["through"] = False
                    state = labels.OFF
            self.events.append(Event("motor_on", f.t(run.on), run.on_conf * dis))
            if state == labels.OFF:
                state = labels.ON
                ref = self.references(k_su, end)
            k = k_su
            while k < end:
                if state == labels.ON:
                    hit = self.find_contact(ref, k, end)
                    if hit is None:
                        break
                    k_c, conf, disagree = hit
                    hole += 1
                    self.holes[hole] = {"through": None, "tags": []}
                    conf *= self.p["confidence"]["disagree"] if disagree else 1.0
                    self.events.append(Event("contact", f.t(k_c), conf, hole))
                    state = labels.HOLE
                    k = k_c + self.nf(cp["hold_ms"])
                    continue
                # in the hole: stuck or the load end
                k = self.in_hole(ref, k_c, k, end, hole)
                if k is None:
                    break
                state = labels.ON
            if off is not None:
                self.events.append(Event("motor_off", f.t(off), run.off_conf * dis))
                run.off = off
                state = labels.HOLE_OFF if state == labels.HOLE else labels.OFF
                # (a stuck span still open here already ends at f.t(off): in_hole() opens it to `end`)
        self.kickbacks(runs)
        self.wobble()
        self.wander()
        self.speech(runs)

    def in_hole(self, ref, k_c, k, end, hole):
        """Walks the drilling frames from k: returns the frame after a load end, or None (the run ends in the hole)."""
        f, lp_, stp = self.f, self.p["load_end"], self.p["stuck"]
        n_end, n_st = self.nf(lp_["hold_ms"]), self.nf(stp["hold_ms"])
        cnt_end = cnt_st = 0
        stuck_from = lv0 = None
        while k < end:
            if stuck_from is not None:
                cnt_st = cnt_st + 1 if not self.stuck_ratio(ref, lv0, k) > 1.0 else 0
                if cnt_st == n_st:
                    self.spans[stuck_from].t1_us = f.t(k - n_st + 1)
                    stuck_from, cnt_st = None, 0
                k += 1
                continue
            lv = self.levels(k)
            cnt_st = cnt_st + 1 if self.stuck_ratio(ref, lv, k) > 1.0 else 0
            if cnt_st == n_st:
                k_s = k - n_st + 1
                lv0 = self.levels(k_s)
                while k_s > k_c + 1 and self.stuck_ratio(ref, lv0, k_s - 1, back=True) > 1.0:
                    k_s -= 1
                x = med([self.stuck_ratio(ref, lv0, j) for j in range(k - n_st + 1, k + 1)])
                conf = sigmoid((x - 1.0) / stp["conf_scale"])
                self.spans.append(Span("stuck", f.t(k_s), f.t(end), conf, hole))
                self.holes[hole]["tags"].append("bit_stuck")
                self.holes[hole].setdefault("ham", med(f["ham"][k_c + self.nf(self.p["contact"]["hold_ms"]):k_s]))
                stuck_from, cnt_st, cnt_end = len(self.spans) - 1, 0, 0
                k += 1
                continue
            # a stalling motor is no load end, though the cutting and the blows stop with it
            rv = self.recovery(ref, lv, k)
            cnt_end = cnt_end + 1 if cnt_st == 0 and np.isfinite(rv) and rv >= lp_["recover"] else 0
            if cnt_end == n_end:
                k_e = k - n_end + 1
                lv = self.levels(k_e)
                k_r = k_e
                while k_r > k_c + 1:
                    rv = self.recovery(ref, lv, k_r - 1)
                    if not (np.isfinite(rv) and rv > lp_["start"]):
                        break
                    k_r -= 1
                through, t, conf = self.classify_end(k_c, k_r, k_e, hole)
                self.events.append(Event("breakthrough" if through else "retract", t, conf, hole))
                self.holes[hole]["through"] = through
                return k_e + n_end
            k += 1
        return None

    # ---- 11. kickback ----
    def kickbacks(self, runs):
        kp, f = self.p["kickback"], self.f
        roll = f["roll"]
        n = self.nf(kp["roll_ms"])
        refr = self.nf(kp["refractory_ms"])
        last = -10 ** 9
        for run in runs:
            end = run.off if run.off is not None else self.K
            for k in range(run.on, end):
                if k - last < refr or not np.isfinite(roll[k]):
                    continue
                w = roll[max(run.on, k - n + 1):k + 1]
                integ = float(np.nansum(w)) * self.fm / 1000.0
                x = max(abs(roll[k]) / kp["rate_dps"], abs(integ) / kp["roll_deg"])
                if x <= 1.0:
                    continue
                sign = 1.0 if (roll[k] if abs(roll[k]) / kp["rate_dps"] > 1.0 else integ) > 0 else -1.0
                j = k
                while j > run.on and np.isfinite(roll[j - 1]) and sign * roll[j - 1] > kp["back_dps"]:
                    j -= 1
                i0 = int(np.searchsorted(f.imu_t, f.t(j), side="left"))
                i1 = int(np.searchsorted(f.imu_t, f.t(k + 1), side="left"))
                seg = sign * f.roll_s[i0:i1]
                hit = np.nonzero(seg > kp["back_dps"])[0]
                t = int(f.imu_t[i0 + hit[0]]) if len(hit) else f.t(j)
                peak = max(float(np.nanmax(np.abs(roll[k:min(end, k + n)]))) / kp["rate_dps"], x)
                self.events.append(Event("kickback", t, sigmoid((peak - 1.0) / kp["conf_scale"]), self.hole_at(t)))
                if self.events[-1].hole:
                    self.holes[self.events[-1].hole]["tags"].append("kickback")
                last = k

    def hole_at(self, t_us: int) -> int | None:
        """The hole the bit is in at t_us, or the one it left at most kickback.hole_after_ms
        before (a bit catching as it breaks through), from the grammar events so far."""
        h, left = None, None
        for e in sorted((e for e in self.events if e.type != "kickback"), key=lambda e: e.t_us):
            if e.t_us > t_us:
                break
            if e.type == "contact":
                h, left = e.hole, None
            elif e.type in ("breakthrough", "retract") and h is not None:
                left = e.t_us
        if h is None or (left is not None and t_us - left > self.p["kickback"]["hole_after_ms"] * 1000):
            return None
        return h

    def drilling_mask(self) -> tuple[np.ndarray, np.ndarray]:
        """Per frame: in a hole with the motor on, and the hole number."""
        f = self.f
        mask = np.zeros(self.K, dtype=bool)
        hole = np.zeros(self.K, dtype=np.int64)
        evs = sorted((e for e in self.events if e.type != "kickback"), key=lambda e: e.t_us)
        cur, on = None, False
        t_prev = f.t0_us
        for e in evs + [Event("end", f.t(self.K), 0.0)]:
            if cur is not None and on:
                a = max(0, (t_prev - f.t0_us) // f.frame_us)
                b = min(self.K, (e.t_us - f.t0_us) // f.frame_us)
                mask[a:b] = True
                hole[a:b] = cur
            t_prev = e.t_us
            if e.type == "contact":
                cur, on = e.hole, True
            elif e.type in ("breakthrough", "retract"):
                cur = None
            elif e.type == "motor_off":
                on = False
            elif e.type == "motor_on":
                on = True
        return mask, hole

    # ---- 12. suggestions ----
    def wobble(self):
        wp, f = self.p["wobble"], self.f
        mask, hole = self.drilling_mask()
        wob = f["wob"]
        hi = mask & (wob > wp["rms_dps"])
        n = self.nf(wp["min_ms"])
        k = 0
        while k < self.K:
            if not hi[k]:
                k += 1
                continue
            j = k
            while j < self.K and hi[j] and hole[j] == hole[k]:
                j += 1
            if j - k >= n:
                conf = wp["confidence_max"] * sigmoid((med(wob[k:j]) / wp["rms_dps"] - 1.0) / wp["conf_scale"])
                self.spans.append(Span("wobble", f.t(k), f.t(j), conf, int(hole[k])))
            k = j

    def wander(self):
        wp, f = self.p["wander"], self.f
        lvl = self.inp.level
        if lvl is None or len(lvl) == 0:
            return
        ok = (lvl["flags"].to_numpy(np.int64) & LEVEL_FLAG_IMU_OK) != 0
        t = lvl["t_us"].to_numpy(np.int64)[ok]
        el = labels.absolute_elevation_deg(lvl)[ok]
        yaw = lvl["yaw_cdeg"].to_numpy(np.float64)[ok] / 100.0
        if not len(t):
            return
        evs = sorted(self.events, key=lambda e: e.t_us)
        for i, e in enumerate(evs):
            if e.type != "contact":
                continue
            t_end = e.t_us + wp["after_contact_ms"] * 1000
            for x in evs[i + 1:]:
                if x.type in ("breakthrough", "retract", "motor_off"):
                    t_end = min(t_end, x.t_us)
                    break
            sel = (t >= e.t_us) & (t < t_end)
            a, b = (e.t_us - f.t0_us) // f.frame_us, (t_end - f.t0_us) // f.frame_us
            # the device level is no reference while hammer blows shake it
            if sel.sum() < 2 or med(f["ham"][a:b]) >= self.p["contact"]["hammer"] / 2:
                continue
            de = np.abs(el[sel] - el[sel][0])
            dy = np.abs((yaw[sel] - yaw[sel][0] + 180.0) % 360.0 - 180.0)
            drift = float(max(de.max(), dy.max()))
            if drift > wp["deg"]:
                conf = wp["confidence_max"] * sigmoid((drift / wp["deg"] - 1.0) / wp["conf_scale"])
                self.spans.append(Span("wander", e.t_us, int(t_end), conf, e.hole))

    def speech(self, runs):
        sp, f = self.p["speech"], self.f
        if not self.has_audio:
            return
        off = np.ones(self.K, dtype=bool)
        pad = self.nf(sp["motor_pad_ms"])
        for run in runs:
            end = run.off if run.off is not None else self.K
            off[max(0, run.on - pad):min(self.K, end + pad)] = False
        n, hop = self.nf(sp["window_ms"]), self.nf(sp["hop_ms"])
        z = f["z_speech"]
        env = 10.0 ** (f["e_speech"] / 20.0)
        fr = 1000.0 / self.fm
        bins = [b for b in range(1, n // 2) if sp["mod_hz"][0] <= b * fr / n <= sp["mod_hz"][1]]
        wins = []
        for k in range(0, self.K - n + 1, hop):
            if not off[k:k + n].all() or not np.isfinite(env[k:k + n]).all():
                continue
            if med(z[k:k + n]) <= sp["z"]:
                continue
            x = env[k:k + n] - env[k:k + n].mean()
            tot = float(x @ x)
            if tot <= 0:
                continue
            ph = 2.0 * np.pi * np.arange(n) / n
            mod = sum(float(x @ np.cos(ph * b)) ** 2 + float(x @ np.sin(ph * b)) ** 2 for b in bins)
            frac = 2.0 * mod / (n * tot)
            if frac > sp["mod_frac"]:
                wins.append((k, k + n, frac))
        merged = []
        for a, b, fr_ in wins:
            if merged and a <= merged[-1][1]:
                merged[-1] = (merged[-1][0], b, max(merged[-1][2], fr_))
            else:
                merged.append((a, b, fr_))
        for a, b, fr_ in merged:
            conf = sp["confidence_max"] * sigmoid((fr_ / sp["mod_frac"] - 1.0) / sp["conf_scale"])
            self.spans.append(Span("ignore", f.t(a), f.t(b), conf, reason="speech"))


def events_doc(det: _Detector) -> dict:
    inp, p = det.inp, det.p
    c0, c1 = inp.coverage_us
    evs = sorted((e for e in det.events if c0 <= e.t_us < c1), key=lambda e: (e.t_us, e.type))
    out = []
    for i, e in enumerate(evs):
        d = {"id": f"e{i + 1}", "type": e.type, "t_us": int(e.t_us), "source": "auto",
             "confidence": round(float(min(1.0, max(0.0, e.confidence))), 3)}
        if e.hole:
            d["hole"] = int(e.hole)
        out.append(d)
    spans = []
    for sp in sorted(det.spans, key=lambda s: (s.t0_us, s.type)):
        a, b = max(c0, sp.t0_us), min(c1, sp.t1_us)
        if b <= a:
            continue
        d = {"id": f"s{len(spans) + 1}", "type": sp.type, "t0_us": int(a), "t1_us": int(b), "source": "auto",
             "confidence": round(float(min(1.0, max(0.0, sp.confidence))), 3)}
        if sp.reason:
            d["reason"] = sp.reason
        if sp.hole:
            d["hole"] = int(sp.hole)
        spans.append(d)
    used = {d["hole"] for d in out if "hole" in d} | {d["hole"] for d in spans if "hole" in d}
    holes = []
    for h in sorted(used):
        info = det.holes.get(h, {"through": None, "tags": []})
        holes.append(contract.hole_entry(h, info["through"], sorted(set(info["tags"]))))
    return contract.events_document(inp.recording_id, inp.coverage_us, revision=1, events=out, spans=spans,
                                    holes=holes, detector={"version": p.version, "params_sha256": p.sha256})


def detect(rec: Recording | Inputs, params: Params | None = None) -> Detection:
    """Runs the detector on a recording (or prepared Inputs): events.json + derived labels."""
    p = params or load_params()
    inp = rec if isinstance(rec, Inputs) else Inputs.from_recording(rec)
    f = compute_features(inp, p)
    det = _Detector(inp, f, p)
    det.run()
    doc = events_doc(det)
    derived = {"orientation": "unknown", "off_angle_deg": None, "tags": []}
    if inp.level is not None:
        derived, holes = labels.compute_derived(inp.level, doc, inp.meta)
        for h in doc["holes"]:
            if h["hole"] in holes:
                h["off_angle_deg"] = holes[h["hole"]]["off_angle_deg"]
    return Detection(doc, derived, f)


# ---- scoring against labels ---------------------------------------------------------

def _greedy(pairs) -> list[tuple]:
    """One-to-one matches from (cost, i, j) candidates: the cheapest first, each i and j once."""
    ui, uj, out = set(), set(), []
    for c, i, j in sorted(pairs):
        if i not in ui and j not in uj:
            ui.add(i)
            uj.add(j)
            out.append((c, i, j))
    return out


def match(truth_us, det_us, tol_us: int) -> tuple[int, int, int, list[int]]:
    """(tp, fp, fn, errors det - truth of the matches): one-to-one, closest pairs first."""
    m = _greedy((abs(d - t), i, j) for i, t in enumerate(truth_us) for j, d in enumerate(det_us)
                if abs(d - t) <= tol_us)
    tp = len(m)
    return tp, len(det_us) - tp, len(truth_us) - tp, [int(det_us[j] - truth_us[i]) for _, i, j in m]


def iou(a: tuple[int, int], b: tuple[int, int]) -> float:
    inter = max(0, min(a[1], b[1]) - max(a[0], b[0]))
    union = max(a[1], b[1]) - min(a[0], b[0])
    return inter / union if union > 0 else 0.0


def score(truth: dict, det: dict, tolerances: dict | None = None, span_iou: float = 0.5) -> dict:
    """Per event type: tp, fp, fn, errors (us), at the type's tolerance; per span type
    (stuck, wobble, wander, ignore): tp, fp, fn with a match at IoU >= span_iou."""
    tol = tolerances or TOLERANCES_US
    out = {}
    for typ, t in tol.items():
        a = [e["t_us"] for e in truth["events"] if e["type"] == typ]
        b = [e["t_us"] for e in det["events"] if e["type"] == typ]
        tp, fp, fn, err = match(a, b, t)
        out[typ] = {"tp": tp, "fp": fp, "fn": fn, "errors_us": err}
    for typ in ("stuck", "wobble", "wander", "ignore"):
        a = [(s["t0_us"], s["t1_us"]) for s in truth["spans"] if s["type"] == typ]
        b = [(s["t0_us"], s["t1_us"]) for s in det["spans"] if s["type"] == typ]
        m = _greedy((-v, i, j) for i, x in enumerate(a) for j, y in enumerate(b) if (v := iou(x, y)) >= span_iou)
        out[typ] = {"tp": len(m), "fp": len(b) - len(m), "fn": len(a) - len(m), "iou": [-c for c, _, _ in m]}
    return out


def f1(counts: dict) -> float:
    tp, fp, fn = counts["tp"], counts["fp"], counts["fn"]
    return 1.0 if tp + fp + fn == 0 else 2.0 * tp / (2.0 * tp + fp + fn)


def phase_accuracy(truth: dict, det: dict, frame_us: int = 10_000) -> float:
    """Fraction of frame centres inside the truth's coverage (truth not unknown) where the phases agree."""
    c0, c1 = truth["coverage_us"]
    t = np.arange(c0 + frame_us // 2, c1, frame_us, dtype=np.int64)
    a = labels.phase_track(t, truth, boundary_policy="hard")
    b = labels.phase_track(t, {**det, "coverage_us": truth["coverage_us"]}, boundary_policy="hard")
    sel = a.codes != a.phases.index("unknown")
    return float((a.codes[sel] == b.codes[sel]).mean()) if sel.any() else 1.0


# ---- writing labels -----------------------------------------------------------------

def write_labels(folder: str | Path, det: Detection, *, force: bool = False) -> dict:
    """Writes det as the recording's labels: events.json (revision + 1), meta.review
    (auto_only, this detector) and meta.derived. Refuses reviewed or edited labels unless force."""
    folder = Path(folder)
    meta = read_json(folder / META_FILE)
    old = read_json(folder / EVENTS_FILE) if (folder / EVENTS_FILE).is_file() else None
    if not force:
        if meta["review"]["status"] != "auto_only":
            raise ValueError(f"{folder.name}: review.status is {meta['review']['status']}; --force replaces it")
        if old and any(labels.touched_by_person(x) for x in old.get("events", []) + old.get("spans", [])):
            raise ValueError(f"{folder.name}: events.json holds user labels; --force replaces them")
    events = json.loads(json.dumps(det.events))
    events["recording_id"] = meta["recording_id"]
    events["revision"] = (old or {}).get("revision", 0) + 1
    meta["review"] = {"status": "auto_only", "detector": det.events["detector"]["version"],
                      "revision": events["revision"], "edited_events": 0, "reviewer": None}
    meta["derived"] = det.derived
    dump_json(events, folder / EVENTS_FILE)
    dump_json(meta, folder / META_FILE)
    return events


# ---- golden fixtures (the Kotlin port's AutoDetectVectorsTest) ---------------------

def _fmt(v) -> str:
    if v is None or (isinstance(v, float) and not math.isfinite(v)):
        return ""
    return f"{float(v):.9g}"


def write_expected(folder: str | Path, params: Params | None = None) -> Detection:
    """features.csv.gz and expected.json of one fixture folder, from its recording files."""
    import pandas as pd

    folder = Path(folder)
    det = detect(read_recording(folder), params)
    tab = det.features.table()
    df = pd.DataFrame({c: ([int(x) for x in tab[c]] if c in ("frame", "t_us") else [_fmt(x) for x in tab[c]])
                       for c in FEATURE_COLUMNS})
    _write_csv_gz(df, folder / "features.csv.gz")
    spec = read_json(folder / "spec.json") if (folder / "spec.json").is_file() else None
    dump_json({"schema": FIXTURE_SCHEMA, "detector": det.events["detector"], "frames": det.features.n,
               "t0_us": det.features.t0_us, "frame_us": det.features.frame_us, "spec": spec,
               "events": det.events, "derived": det.derived}, folder / "expected.json")
    return det


def fixture_folders(root: str | Path | None = None) -> list[Path]:
    r = Path(root or FIXTURES_DIR)
    return sorted(p.parent for p in r.glob("*/expected.json")) if r.is_dir() else []


def write_fixtures(root: str | Path | None = None) -> list[Path]:
    """Regenerates every fixture: synth's "fixtures" set (needs the simulator), then the expected output."""
    from . import synth

    root = Path(root or FIXTURES_DIR)
    out = []
    for i, spec in enumerate(synth.SETS["fixtures"]):
        folder = root / spec.name
        if folder.is_dir():
            for p in folder.iterdir():
                p.unlink()
        synth.synthesize(spec, root, rid=synth.recording_id(900 + i), folder=folder)
        dump_json(spec.to_dict(), folder / "spec.json")
        write_expected(folder)
        out.append(folder)
    return out


def check_fixture(folder: str | Path, params: Params | None = None) -> list[str]:
    """Differences between a fresh detection and the fixture's expected output (empty: it matches)."""
    import pandas as pd

    folder = Path(folder)
    missing = [n for n in ("expected.json", "features.csv.gz") if not (folder / n).is_file()]
    if missing:
        return [f"{n} is missing" for n in missing]
    exp = read_json(folder / "expected.json")
    det = detect(read_recording(folder), params)
    problems = []
    if exp["detector"] != det.events["detector"]:
        problems.append(f"detector {exp['detector']} != {det.events['detector']}")
    for key in ("events", "spans", "holes"):
        if exp["events"][key] != det.events[key]:
            problems.append(f"{key} differ")
    if exp["derived"] != det.derived:
        problems.append("derived labels differ")
    want = pd.read_csv(folder / "features.csv.gz")
    tab = det.features.table()
    if len(want) != det.features.n:
        problems.append(f"{len(want)} frames expected, {det.features.n} computed")
        return problems
    for c in FEATURE_COLUMNS[2:]:
        if c not in want.columns:
            problems.append(f"feature {c}: missing in features.csv.gz")
            continue
        a = want[c].to_numpy(np.float64)
        b = np.array([float(_fmt(x)) if _fmt(x) else np.nan for x in tab[c]])
        bad = ~((np.isnan(a) & np.isnan(b)) | (np.abs(a - b) <= 1e-6 + 1e-6 * np.abs(a)))
        if bad.any():
            problems.append(f"feature {c}: {int(bad.sum())} frames differ, first {int(np.argmax(bad))}")
    return problems


# ---- CLI ----------------------------------------------------------------------------

def _describe(det: Detection, t0: int) -> list[str]:
    lines = []
    for e in det.events["events"]:
        h = f" hole {e['hole']}" if "hole" in e else ""
        lines.append(f"  {(e['t_us'] - t0) / 1e6:8.3f} s  {e['type']}{h}  ({e['confidence']:.2f})")
    for s in det.events["spans"]:
        extra = f" {s['reason']}" if "reason" in s else f" hole {s['hole']}" if "hole" in s else ""
        lines.append(f"  {(s['t0_us'] - t0) / 1e6:8.3f}-{(s['t1_us'] - t0) / 1e6:.3f} s  {s['type']}{extra}"
                     f"  ({s['confidence']:.2f})")
    lines.append(f"  derived: {det.derived}")
    return lines


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m dbdata.autodetect", description=__doc__.split("\n\n")[0])
    ap.add_argument("recordings", nargs="*", help="recording folders (or trees holding them)")
    ap.add_argument("--write", action="store_true", help="write events.json and meta review/derived")
    ap.add_argument("--force", action="store_true", help="with --write: replace reviewed or edited labels")
    ap.add_argument("--score", action="store_true", help="compare with the recording's labels (event F1, phases)")
    ap.add_argument("--params", help="another params file (default contract/autodetect_params.json)")
    ap.add_argument("--check-fixtures", nargs="?", const="", metavar="DIR", help="replay the golden fixtures")
    ap.add_argument("--update-fixtures", nargs="?", const="", metavar="DIR",
                    help="rewrite the fixtures' expected output from their recordings")
    a = ap.parse_args(argv)
    p = load_params(a.params)
    if a.check_fixtures is not None or a.update_fixtures is not None:
        root = (a.check_fixtures if a.check_fixtures is not None else a.update_fixtures) or None
        folders = fixture_folders(root)
        if not folders:
            print(f"no fixtures under {root or FIXTURES_DIR}", file=sys.stderr)
            return 2
        bad = False
        for fo in folders:
            if a.update_fixtures is not None:
                write_expected(fo, p)
                print(f"updated {fo}")
                continue
            probs = check_fixture(fo, p)
            print(f"{fo.name}: {'ok' if not probs else 'DIFFERS'}")
            for pr in probs:
                print(f"  {pr}")
            bad |= bool(probs)
        return 1 if bad else 0
    if not a.recordings:
        ap.print_usage(sys.stderr)
        return 2
    from .validate import find_recordings, validate_recording

    folders = [f for r in a.recordings for f in find_recordings(r)]
    if not folders:
        print("no recordings found", file=sys.stderr)
        return 2
    rc = 0
    for fo in folders:
        rec = read_recording(fo)
        det = detect(rec, p)
        print(f"{rec.id}: {len(det.events['events'])} events, {len(det.events['spans'])} spans")
        for line in _describe(det, int(rec.t_us[0])):
            print(line)
        if a.score:
            sc = score(rec.events, det.events)
            for typ, c in sc.items():
                if c["tp"] + c["fp"] + c["fn"]:
                    if "iou" in c:
                        extra = f", mean IoU {np.mean(c['iou']):.2f}" if c["iou"] else ""
                    else:
                        err = c["errors_us"]
                        extra = f", mean |err| {np.mean(np.abs(err)) / 1000:.1f} ms" if err else ""
                    print(f"  score {typ}: F1 {f1(c):.2f} (tp {c['tp']} fp {c['fp']} fn {c['fn']}{extra})")
            print(f"  score phases: {phase_accuracy(rec.events, det.events):.3f} of the frames")
        if a.write:
            try:
                write_labels(fo, det, force=a.force)
            except ValueError as e:
                print(f"  not written: {e}", file=sys.stderr)
                rc = 1
                continue
            rep = validate_recording(fo)
            print(f"  wrote events.json; validate: {'ok' if rep.ok else 'FAIL'}")
            for i in rep.issues:
                print(f"    {i.code} {i.message}")
            rc |= 0 if rep.ok else 1
    return rc


if __name__ == "__main__":
    sys.exit(main())
