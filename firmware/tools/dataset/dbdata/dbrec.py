"""The recorder's training-data stream: decoder, self-check and dataset export.

A .dbrec file is the raw TCP stream of one recorder session as the client
received it (firmware/components/recorder/include/rec_stream.h is the
contract). This decoder is written independently of the firmware: self_check()
compares its constants and struct layouts with that header.

    python -m dbdata.dbrec info session.dbrec
    python -m dbdata.dbrec export session.dbrec ROOT --recording-id ... --session-id ...
    python -m dbdata.dbrec --self-check
"""

from __future__ import annotations

import argparse
import json
import re
import struct
import sys
import zlib
from dataclasses import dataclass, field
from datetime import timezone
from pathlib import Path

import numpy as np

from . import contract

# ---- the wire format (rec_stream.h) ---------------------------------------------

STREAM_PORT = 47100
STREAM_VERSION = 1
HELLO_TOKEN_LEN = 16
HELLO_LEN = 18
HDR_LEN = 8
CRC_LEN = 4
MAX_PAYLOAD = 4096

T_SESSION, T_IMU, T_AUDIO, T_EVENT, T_STATS, T_LEVEL, T_END = 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x0F
TYPE_NAMES = {T_SESSION: "SESSION", T_IMU: "IMU", T_AUDIO: "AUDIO", T_EVENT: "EVENT",
              T_STATS: "STATS", T_LEVEL: "LEVEL", T_END: "END"}
VERSIONS = {T_SESSION: 1, T_IMU: 1, T_AUDIO: 1, T_EVENT: 1, T_STATS: 1, T_LEVEL: 1, T_END: 1}

HDR = struct.Struct("<BBHI")                                  # rec_hdr_t
SESSION = struct.Struct("<IQ4s32sBBBBIHHBbBbBbIBBBBHH")       # rec_session_t, then JSON
IMU = struct.Struct("<IIQHH")                                 # rec_imu_t, then n x 6 int16
AUDIO = struct.Struct("<QQHH")                                # rec_audio_t, then the samples
EVENT = struct.Struct("<QHH")                                 # rec_event_t, then the text
LEVEL = struct.Struct("<QBB8h")                               # rec_level_t
STATS = struct.Struct("<Q11IHBB")                             # rec_stats_t
END = struct.Struct("<QB7I")                                  # rec_end_t

IMU_DISCONT = 1 << 0
IMU_EPOCH = 1 << 1          # the IMU was reset: its TIMESTAMP counts anew
IMU_FRAME_LEN = 12
IMU_GAP_MAX = 32            # imu_frame.h: larger FIFO gaps are resynchronised (DISCONT)
AUDIO_OVF_BEFORE = 1 << 0
MICS = ("mic1", "mic2")     # REC_MIC_1, REC_MIC_2: mic_mask bits, channel order
STATS_BATTERY_VALID, STATS_VBUS, STATS_CHARGING = 1 << 0, 1 << 1, 1 << 2

PROFILES = ("LEVEL", "RAW448", "RAW448_16G", "RAW897")
EVENT_CODES = contract.DEVICE_EVENT_CODES        # EVENT codes 1.. in this order
EV_PROFILE = EVENT_CODES.index("PROFILE") + 1
EVENT_TEXT_MAX = 64
REASONS = ("none", "user", "max_s", "client_lost", "ble_lost", "battery", "imu", "shutdown",
           "ap_fail", "no_client", "idle")
# meta.take.end_reason accepts the take reasons; the rest is "unknown"
TAKE_REASONS = tuple(r for r in contract.schema(contract.META_SCHEMA_FILE)["properties"]["take"]
                     ["properties"]["end_reason"]["enum"] if r != contract.UNKNOWN)


def crc32(data: bytes) -> int:
    return zlib.crc32(data)


def encode_record(rtype: int, ver: int, seq: int, payload: bytes) -> bytes:
    """One record (header, payload, CRC); for tests and tools that write streams."""
    if len(payload) > MAX_PAYLOAD:
        raise ValueError(f"payload of {len(payload)} bytes > {MAX_PAYLOAD}")
    head = HDR.pack(rtype, ver, len(payload), seq & 0xFFFFFFFF) + payload
    return head + struct.pack("<I", crc32(head))


# ---- typed records ------------------------------------------------------------------

@dataclass
class Session:
    take_id: int
    start_us: int
    board_id: str               # 8 hex digits
    fw: str
    profile: int
    ctrl2: int
    ctrl3: int
    ctrl5: int
    odr_mhz: int
    accel_lsb_per_g: int
    gyro_lsb_per_dps: int
    axis: tuple                 # ((src, sign), ...) for board x, y, z
    audio_rate_hz: int
    audio_channels: int
    audio_bits: int
    mic_mask: int
    gain_db: int
    audio_block_frames: int
    max_s: int
    tail: dict | None           # the settings / calibration JSON

    @property
    def profile_name(self) -> str:
        return PROFILES[self.profile] if self.profile < len(PROFILES) else f"UNKNOWN_{self.profile}"


@dataclass
class Imu:
    ts_first: int
    chip_ts: int
    host_us: int
    n: int
    flags: int
    frames: np.ndarray          # (n, 6) int16, board frame

    @property
    def discont(self) -> bool:
        return bool(self.flags & IMU_DISCONT)

    @property
    def epoch(self) -> bool:
        return bool(self.flags & IMU_EPOCH)


@dataclass
class Audio:
    sample_index: int
    host_us: int
    n: int
    flags: int
    samples: np.ndarray         # (n, channels) int16

    @property
    def overflow_before(self) -> bool:
        return bool(self.flags & AUDIO_OVF_BEFORE)


@dataclass
class Event:
    host_us: int
    code: int
    arg: int
    text: str

    @property
    def code_name(self) -> str | None:
        return EVENT_CODES[self.code - 1] if 1 <= self.code <= len(EVENT_CODES) else None


@dataclass
class Level:
    host_us: int
    mode: int
    flags: int
    elevation_cdeg: int
    yaw_cdeg: int
    bubble_x_cdeg: int
    bubble_y_cdeg: int
    deviation_cdeg: int
    zero_elevation_cdeg: int
    zero_bubble_x_cdeg: int
    zero_bubble_y_cdeg: int


@dataclass
class Stats:
    host_us: int
    imu_frames: int
    imu_lost: int
    imu_period_ns: int
    audio_frames: int
    audio_overflows: int
    audio_rate_mhz: int
    audio_clipped: int
    ring_high_water: int
    ring_size: int
    dropped_records: int
    dropped_bytes: int
    battery_mv: int
    battery_pct: int
    power_flags: int


@dataclass
class End:
    host_us: int
    reason: int
    records: int
    dropped_records: int
    dropped_bytes: int
    imu_frames: int
    imu_lost: int
    audio_frames: int
    audio_overflows: int

    @property
    def reason_name(self) -> str:
        return REASONS[self.reason] if self.reason < len(REASONS) else f"unknown_{self.reason}"


@dataclass
class Record:
    """One record as framed on the wire. data is the typed payload, or None for
    an unknown type/version (passed through) or a payload too short for its type."""

    type: int
    ver: int
    seq: int
    payload: bytes
    offset: int                 # in the stream
    data: object = None

    @property
    def type_name(self) -> str:
        return TYPE_NAMES.get(self.type, f"0x{self.type:02x}")


def _cstr(b: bytes) -> str:
    return b.split(b"\0", 1)[0].decode("utf-8", "replace")


def _parse_session(p: bytes):
    v = SESSION.unpack_from(p)
    text = p[SESSION.size:].decode("utf-8", "replace")
    try:
        tail = json.loads(text) if text else None
    except ValueError:
        tail = None
    if not isinstance(tail, dict):
        tail = None
    axis = ((v[11], v[12]), (v[13], v[14]), (v[15], v[16]))
    return Session(v[0], v[1], v[2].hex(), _cstr(v[3]), v[4], v[5], v[6], v[7], v[8], v[9], v[10],
                   axis, v[17], v[18], v[19], v[20], v[21], v[22], v[23], tail)


def _parse_imu(p: bytes):
    ts, chip, host, n, flags = IMU.unpack_from(p)
    if len(p) != IMU.size + n * IMU_FRAME_LEN:
        return None
    frames = np.frombuffer(p, dtype="<i2", count=n * 6, offset=IMU.size).reshape(n, 6).astype(np.int16, copy=False)
    return Imu(ts, chip, host, n, flags, frames)


def _parse_audio(p: bytes):
    index, host, n, flags = AUDIO.unpack_from(p)
    body = len(p) - AUDIO.size
    if n == 0:
        return Audio(index, host, 0, flags, np.zeros((0, 0), dtype=np.int16)) if body == 0 else None
    if body % (2 * n):
        return None
    ch = body // (2 * n)
    samples = np.frombuffer(p, dtype="<i2", count=n * ch, offset=AUDIO.size).reshape(n, ch).astype(
        np.int16, copy=False)
    return Audio(index, host, n, flags, samples)


def _parse_event(p: bytes):
    host, code, arg = EVENT.unpack_from(p)
    return Event(host, code, arg, p[EVENT.size:].decode("utf-8", "replace"))


_PARSERS = {    # the types of VERSIONS: (fixed part, parser)
    T_SESSION: (SESSION.size, _parse_session),
    T_IMU: (IMU.size, _parse_imu),
    T_AUDIO: (AUDIO.size, _parse_audio),
    T_EVENT: (EVENT.size, _parse_event),
    T_LEVEL: (LEVEL.size, lambda p: Level(*LEVEL.unpack_from(p))),
    T_STATS: (STATS.size, lambda p: Stats(*STATS.unpack_from(p))),
    T_END: (END.size, lambda p: End(*END.unpack_from(p))),
}


def _parse_known(rtype: int, payload: bytes):
    """The typed payload of a type and version in VERSIONS, or None if the
    payload does not fit its type. Fixed-size types accept a longer payload
    (fields appended in a later version of the same layout are ignored)."""
    size, fn = _PARSERS[rtype]
    return fn(payload) if len(payload) >= size else None


# ---- framing ------------------------------------------------------------------------

@dataclass
class DecodeStats:
    records: int = 0            # framed with a good CRC
    bytes: int = 0              # fed in
    skipped_bytes: int = 0      # dropped while resynchronising
    resyncs: int = 0            # runs of skipped bytes
    unknown: int = 0            # unknown type or version, passed through
    malformed: int = 0          # known type, payload does not fit
    by_type: dict = field(default_factory=dict)


def _crc_ok(buf, p: int, length: int) -> bool:
    end = p + HDR_LEN + length
    return struct.unpack_from("<I", buf, end)[0] == crc32(memoryview(buf)[p:end])


class StreamDecoder:
    """Incremental decoder: feed() bytes as they arrive, finish() at the end.

    A record is taken when its header is plausible (version not 0, len <=
    MAX_PAYLOAD) and its CRC matches; otherwise the decoder moves one byte on
    (resynchronisation). While a plausible header waits for its payload, feed()
    keeps the bytes, unless it is resynchronising and a complete record
    follows in what it has; finish() gives up on a waiting header and
    resynchronises through the rest."""

    def __init__(self):
        self._buf = bytearray()
        self._base = 0          # stream offset of _buf[0]
        self._skipping = False
        self.stats = DecodeStats()

    def feed(self, data: bytes) -> list[Record]:
        self._buf += data
        self.stats.bytes += len(data)
        return self._run(final=False)

    def finish(self) -> list[Record]:
        return self._run(final=True)

    @staticmethod
    def _find_record(buf: bytearray, start: int) -> int:
        """Offset of the first complete record with a good CRC from start on, or -1."""
        for q in range(start, len(buf) - HDR_LEN - CRC_LEN + 1):
            _t, ver, length, _s = HDR.unpack_from(buf, q)
            if ver and length <= MAX_PAYLOAD and q + HDR_LEN + length + CRC_LEN <= len(buf) \
                    and _crc_ok(buf, q, length):
                return q
        return -1

    def _skip(self, n: int) -> None:
        if n <= 0:
            return
        if not self._skipping:
            self.stats.resyncs += 1
            self._skipping = True
        self.stats.skipped_bytes += n

    def _run(self, final: bool) -> list[Record]:
        out = []
        buf = self._buf
        n = len(buf)
        p = 0                   # where a record may start
        done = 0                # bytes taken by records or skipped
        while n - p >= HDR_LEN:
            rtype, ver, length, seq = HDR.unpack_from(buf, p)
            if ver == 0 or length > MAX_PAYLOAD:
                p += 1
                continue
            size = HDR_LEN + length + CRC_LEN
            if n - p < size:
                if final:
                    p += 1
                    continue
                # While resynchronising, a complete record further on shows
                # that this header was a false start.
                q = self._find_record(buf, p + 1) if self._skipping or p > done else -1
                if q < 0:
                    break
                p = q
                continue
            if not _crc_ok(buf, p, length):
                p += 1
                continue
            self._skip(p - done)
            self._skipping = False
            payload = bytes(memoryview(buf)[p + HDR_LEN:p + HDR_LEN + length])
            rec = Record(rtype, ver, seq, payload, self._base + p)
            if VERSIONS.get(rtype) == ver:
                rec.data = _parse_known(rtype, payload)
                if rec.data is None:
                    self.stats.malformed += 1
            else:
                self.stats.unknown += 1
            self.stats.records += 1
            self.stats.by_type[rec.type_name] = self.stats.by_type.get(rec.type_name, 0) + 1
            out.append(rec)
            p += size
            done = p
        # what can no longer start a record (at the end: all that is left)
        end = n if final else p
        self._skip(end - done)
        del buf[:end]
        self._base += end
        return out


def decode(data: bytes, chunk: int | None = None) -> tuple[list[Record], DecodeStats]:
    """All records of a stream (fed in chunks of chunk bytes, or at once)."""
    d = StreamDecoder()
    out = []
    step = chunk or max(1, len(data))
    for i in range(0, len(data), step):
        out += d.feed(data[i:i + step])
    out += d.finish()
    return out, d.stats


def decode_file(path: str | Path, chunk: int | None = None) -> tuple[list[Record], DecodeStats]:
    """All records of a .dbrec file, read in chunks of chunk bytes (1 MiB by default)."""
    d = StreamDecoder()
    out = []
    with open(path, "rb") as f:
        for data in iter(lambda: f.read(chunk or 1 << 20), b""):
            out += d.feed(data)
    out += d.finish()
    return out, d.stats


# ---- takes ----------------------------------------------------------------------------

@dataclass
class Take:
    """SESSION ... END. session/end are None when the stream lost them."""

    session: Session | None
    records: list
    end: End | None = None

    def of(self, cls) -> list:
        return [r.data for r in self.records if isinstance(r.data, cls)]

    @property
    def missing_records(self) -> int:
        """Records the seq numbers say are missing (dropped on the device, or
        lost to a CRC error), including before the first one received."""
        seqs = [r.seq for r in self.records]
        missing = seqs[0]
        for a, b in zip(seqs, seqs[1:]):
            missing += max(0, b - a - 1)
        if self.end is not None:
            missing += max(0, self.end.records - 1 - seqs[-1])
        return missing


def split_takes(records: list[Record]) -> list[Take]:
    """Groups records into takes. A SESSION (or a seq restarting below the last
    one) starts a take, END closes it. Records whose SESSION was lost form a
    take with session None."""
    takes = []
    cur = None
    for r in records:
        if r.type == T_SESSION or (cur is not None and r.seq <= cur.records[-1].seq):
            cur = Take(r.data if isinstance(r.data, Session) else None, [r])
            takes.append(cur)
            if r.type == T_SESSION:
                continue
        elif cur is None:
            cur = Take(None, [r])
            takes.append(cur)
        else:
            cur.records.append(r)
        if r.type == T_END:
            cur.end = r.data if isinstance(r.data, End) else None
            cur = None
    return takes


# ---- the dataset tables ------------------------------------------------------------------

@dataclass
class ImuTrack:
    seq: np.ndarray             # int64: the unwrapped chip timestamp
    t_us: np.ndarray            # int64
    counts: np.ndarray          # (N, 6) int16
    flags: np.ndarray           # int64, io.FLAG_*
    transport_gaps: list        # [[t_before, t_after], ...] where records were missing
    fit: tuple                  # (a_us, b_us_per_sample): t_us = a + b * seq in the first clock segment
    segments: int = 1           # counts of the chip clock (a new one after each IMU reset)


# A DISCONT jump that host_us contradicts by more than this (plus 2 % of the
# time between) is an IMU reset the glue did not flag with REC_IMU_EPOCH.
EPOCH_TOL_US = 50000
EPOCH_TOL_REL = 0.02


def imu_records(take: Take) -> list[Record]:
    """The take's IMU records in SESSION.profile (rec_stream.h): only those whose latest PROFILE
    event names SESSION's profile, or all of them in a take without PROFILE events."""
    s = take.session
    use = s is None or not any(isinstance(r.data, Event) and r.data.code == EV_PROFILE for r in take.records)
    out = []
    for r in take.records:
        d = r.data
        if isinstance(d, Event) and d.code == EV_PROFILE and s is not None:
            use = d.arg == s.profile
        elif isinstance(d, Imu) and d.n > 0 and use:
            out.append(r)
    return out


def _wrap32(d: int) -> int:
    return (d + (1 << 31)) % (1 << 32) - (1 << 31)


@dataclass
class _Segment:
    """IMU samples on one count of the chip clock (seq local to it)."""

    lost_before: bool           # records missing just before it
    seqs: list = field(default_factory=list)
    counts: list = field(default_factory=list)
    flags: list = field(default_factory=list)
    pairs: dict = field(default_factory=dict)   # chip_ts -> host_us, one per FIFO read
    gaps: list = field(default_factory=list)    # (seq before, seq after) of transport gaps


def imu_track(take: Take) -> ImuTrack | None:
    """The IMU samples of a take with seq, t_us and imu.csv.gz flags.

    Samples the FIFO lost in read mode (a timestamp jump of at most
    IMU_GAP_MAX without DISCONT, nothing missing in between) are filled in by
    linear interpolation and flagged FLAG_FILLED. A DISCONT record or one
    after missing records leaves the jump (FLAG_GAP_BEFORE on its first
    sample).

    An IMU reset restarts the chip clock: REC_IMU_EPOCH, a timestamp that goes
    backwards, or a DISCONT jump that host_us contradicts starts a new clock
    segment. t_us follows least-squares lines through the (chip_ts, host_us)
    pairs of the reads, one slope for the take and an offset per segment; a
    new segment's seq goes on from the last by the samples host_us says are
    missing (FLAG_GAP_BEFORE when there are any)."""
    from .io import FLAG_ACCEL_CLIP, FLAG_FILLED, FLAG_GAP_BEFORE, FLAG_GYRO_CLIP

    recs = imu_records(take)
    if not recs:
        return None
    if take.session and take.session.odr_mhz:
        b_nominal = 1e9 / take.session.odr_mhz
    else:
        b_nominal = 1e6 / 448.4

    # Seqs of the take that arrived: a record missing between two IMU records
    # may have held IMU samples, so the jump there is not filled.
    present = np.array(sorted(x.seq for x in take.records), dtype=np.int64)

    def missing_between(a: int, b: int) -> bool:
        arrived = np.searchsorted(present, b) - np.searchsorted(present, a, side="right")
        return b - a - 1 > arrived

    segs: list[_Segment] = []
    last_raw = last_ext = last_ts = last_chip = last_host = last_rec_seq = last_frame = None
    for r in recs:
        d = r.data
        new = not segs
        if not new:
            # The 32-bit counter only wraps after 111 days at 448 Hz, but a take may still cross it.
            ts0 = last_ext + _wrap32(d.ts_first - last_raw)
            chip = ts0 + _wrap32(d.chip_ts - d.ts_first)
            host_dt = d.host_us - last_host
            new = (d.epoch or ts0 < last_ts + 1 or
                   (d.discont and abs((chip - last_chip) * b_nominal - host_dt)
                    > EPOCH_TOL_US + EPOCH_TOL_REL * abs(host_dt)))
        lost_records = last_rec_seq is not None and missing_between(last_rec_seq, r.seq)
        if new:
            ts0 = d.ts_first
            chip = ts0 + _wrap32(d.chip_ts - d.ts_first)
            segs.append(_Segment(lost_records))
        seg = segs[-1]
        fl = np.zeros(d.n, dtype=np.int64)
        if not new:
            jump = ts0 - (last_ts + 1)
            if jump > 0 and not d.discont and not lost_records and jump <= IMU_GAP_MAX:
                t = (np.arange(1, jump + 1) / (jump + 1))[:, None]
                fill = np.rint(last_frame + (d.frames[0].astype(np.float64) - last_frame) * t)
                seg.seqs.append(np.arange(last_ts + 1, ts0, dtype=np.int64))
                seg.counts.append(fill.astype(np.int16))
                seg.flags.append(np.full(jump, FLAG_FILLED, dtype=np.int64))
            elif jump > 0:
                fl[0] |= FLAG_GAP_BEFORE
                if lost_records:
                    seg.gaps.append((last_ts, ts0))
        seg.seqs.append(np.arange(ts0, ts0 + d.n, dtype=np.int64))
        seg.counts.append(d.frames)
        seg.flags.append(fl)
        seg.pairs[chip] = d.host_us
        last_raw, last_ext = d.ts_first, ts0
        last_ts = ts0 + d.n - 1
        last_chip, last_host = chip, d.host_us
        last_rec_seq = r.seq
        last_frame = d.frames[-1].astype(np.float64)

    def xy(seg):
        return (np.array(list(seg.pairs), dtype=np.float64),
                np.array(list(seg.pairs.values()), dtype=np.float64))

    # One slope from the variation within the segments, an offset per segment.
    sxy = sxx = 0.0
    for seg in segs:
        x, y = xy(seg)
        sxy += float(((x - x.mean()) * (y - y.mean())).sum())
        sxx += float(((x - x.mean()) ** 2).sum())
    b = sxy / sxx if sxx > 0 else b_nominal
    if not 0.5 * b_nominal < b < 2 * b_nominal:
        b = b_nominal

    seqs, times, counts, flags, gap_spans = [], [], [], [], []
    a0 = 0.0
    for seg in segs:
        x, y = xy(seg)
        c = float((y - b * x).mean())                   # t_us = c + b * local seq
        local = np.concatenate(seg.seqs)
        fl = np.concatenate(seg.flags)
        off = 0
        if seqs:
            t_last = float(times[-1][-1])
            t_first = c + b * local[0]
            missing = max(0, int(round((t_first - t_last) / b)) - 1)
            if t_first < t_last + b / 2:                # never backwards in time
                c += t_last + b - t_first
            off = int(seqs[-1][-1]) + 1 + missing - int(local[0])
            if missing:
                fl[0] |= FLAG_GAP_BEFORE
            if seg.lost_before:
                gap_spans.append([int(round(t_last)), int(round(c + b * local[0]))])
        else:
            a0 = c
        gap_spans += [[int(round(c + b * g0)), int(round(c + b * g1))] for g0, g1 in seg.gaps]
        seqs.append(local + off)
        times.append(c + b * local.astype(np.float64))
        counts += seg.counts
        flags.append(fl)

    seq = np.concatenate(seqs)
    t_us = np.rint(np.concatenate(times)).astype(np.int64)
    cnt = np.concatenate(counts)
    flg = np.concatenate(flags)
    acc = ((cnt[:, :3] >= 32767) | (cnt[:, :3] <= -32767)).any(axis=1)
    gyr = ((cnt[:, 3:] >= 32767) | (cnt[:, 3:] <= -32767)).any(axis=1)
    flg = flg | np.where(acc, FLAG_ACCEL_CLIP, 0) | np.where(gyr, FLAG_GYRO_CLIP, 0)

    # seq is a uint32 column: a take across the wrap starts over at 0
    base = int(seq[0]) if seq.max() > 0xFFFFFFFF or seq.min() < 0 else 0
    return ImuTrack(seq - base, t_us, cnt, flg, sorted(gap_spans), (a0 + b * base, b), len(segs))


@dataclass
class AudioTrack:
    samples: np.ndarray         # (frames, channels) int16, silence in the gaps
    sync_frame: np.ndarray      # frame index just after each block
    sync_t_us: np.ndarray       # host_us at the end of that block
    gap_frames: list            # [[f0, f1), ...] filled with silence
    overflows: int              # blocks flagged OVF_BEFORE


def audio_track(take: Take) -> AudioTrack | None:
    recs = [r.data for r in take.records if isinstance(r.data, Audio) and r.data.n > 0]
    if not recs:
        return None
    ch = recs[0].samples.shape[1]
    base = recs[0].sample_index
    parts, frames, times, gaps = [], [], [], []
    pos = 0                     # frames written so far
    for a in recs:
        if a.samples.shape[1] != ch:
            continue
        start = a.sample_index - base
        if start > pos:
            parts.append(np.zeros((start - pos, ch), dtype=np.int16))
            gaps.append([pos, start])
            pos = start
        skip = pos - start      # overlap with what is there: keep the earlier samples
        if skip >= a.n:
            continue
        parts.append(a.samples[skip:])
        pos = start + a.n
        frames.append(pos)
        times.append(a.host_us)
    samples = np.concatenate(parts) if parts else np.zeros((0, ch), dtype=np.int16)
    return AudioTrack(samples, np.asarray(frames, dtype=np.int64), np.asarray(times, dtype=np.int64),
                      gaps, sum(1 for a in recs if a.overflow_before))


def audio_fit(audio: AudioTrack, session: Session | None):
    """The (frame, t_us) line of the audio blocks (nominal rate with a single block)."""
    from .io import SyncFit

    if len(audio.sync_frame) >= 2:
        return SyncFit.fit(audio.sync_frame, audio.sync_t_us)
    b = 1e6 / (session.audio_rate_hz if session and session.audio_rate_hz else 16000)
    return SyncFit(float(audio.sync_t_us[0] - b * audio.sync_frame[0]), b)


def _has_audio(audio: AudioTrack | None) -> bool:
    """Whether a take's audio track is exported (audio.flac with its sync rows)."""
    return audio is not None and len(audio.sync_frame) > 0 and audio.samples.shape[1] > 0


def _clipped_frac(s: np.ndarray) -> float:
    """The share of int16 samples at full scale (|x| >= 32767), without widening the track."""
    return (np.count_nonzero(s >= 32767) + np.count_nonzero(s <= -32767)) / s.size


def common_span(imu: ImuTrack, audio: AudioTrack | None, session: Session | None = None):
    """IMU and audio cut to the time both cover, so their durations agree (validate: within 1 %).
    The take start switches the IMU profile (a reset, ~180 ms without samples) while the audio
    already runs, and the two stop a read or a block apart. Unchanged when the overlap holds
    fewer than two IMU samples or two audio blocks."""
    if not _has_audio(audio):
        return imu, audio
    from .io import FLAG_GAP_BEFORE

    fit = audio_fit(audio, session)
    n = len(audio.samples)
    t0 = max(float(imu.t_us[0]), float(fit.t_of(0)))
    t1 = min(float(imu.t_us[-1]), float(fit.t_of(n)))
    keep = (imu.t_us >= t0) & (imu.t_us <= t1)
    f0 = int(np.clip(np.ceil(fit.frame_of(t0) - 1e-6), 0, n))
    f1 = int(np.clip(np.floor(fit.frame_of(t1) + 1e-6), f0, n))
    rows = (audio.sync_frame > f0) & (audio.sync_frame <= f1)
    if keep.sum() < 2 or rows.sum() < 2:
        return imu, audio
    fl = imu.flags[keep].copy()
    fl[0] &= ~FLAG_GAP_BEFORE           # nothing before it any more
    t = imu.t_us[keep]
    gaps = [g for g in imu.transport_gaps if g[0] >= t[0] and g[1] <= t[-1]]
    imu = ImuTrack(imu.seq[keep], t, imu.counts[keep], fl, gaps, imu.fit, imu.segments)
    agaps = [[max(g0, f0) - f0, min(g1, f1) - f0] for g0, g1 in audio.gap_frames if min(g1, f1) > max(g0, f0)]
    audio = AudioTrack(audio.samples[f0:f1], audio.sync_frame[rows] - f0, audio.sync_t_us[rows], agaps,
                       audio.overflows)
    return imu, audio


def level_rows(take: Take) -> dict:
    from .io import LEVEL_COLUMNS

    rows = take.of(Level)
    return {c: np.asarray([getattr(lv, "host_us" if c == "t_us" else c) for lv in rows], dtype=np.int64)
            for c in LEVEL_COLUMNS}


def device_event_rows(take: Take) -> dict:
    evs = [e for e in take.of(Event) if e.code_name is not None]
    return {"t_us": [e.host_us for e in evs], "code": [e.code_name for e in evs],
            "arg": [e.arg for e in evs], "text": [e.text[:EVENT_TEXT_MAX] for e in evs]}


# ---- meta ---------------------------------------------------------------------------------

FW_RE = re.compile(r"^v?(\d+\.\d+\.\d+)(?:-(\d+))?(?:-g([0-9a-f]{4,40}))?(-dirty)?")
LPF_PCT = (2.66, 3.63, 5.39, 13.37)     # QMI8658 CTRL5 LPF modes, % of ODR (Tables 11, 13)
BOARD = "waveshare-esp32-s3-touch-amoled-1.75"
IMU_FRAME_TEXT = "board: X right, Y up the screen, Z out of the screen; bit along -Z"


def fw_parts(fw: str) -> tuple[str, str]:
    """SESSION.fw (rec_fw_text: "0.1.0-g0e5105e-dirty", or git describe "0.3.0-7-g0e5105e")
    -> (fw_version, fw_git)."""
    m = FW_RE.match(fw)
    if not m:
        return fw[:32], ""
    return m.group(1), (m.group(3) + (m.group(4) or "")) if m.group(3) else ""


def lpf_text(ctrl5: int, accel: bool) -> str:
    en, mode = (ctrl5 & 0x01, (ctrl5 >> 1) & 0x03) if accel else ((ctrl5 >> 4) & 0x01, (ctrl5 >> 5) & 0x03)
    return f"{LPF_PCT[mode]}% of ODR" if en else "off"


def axis_map_ok(axis) -> bool:
    """Every chip axis once, each with sign +-1 (imu_axis_map_valid() also
    wants a rotation; a mirror map is still described correctly)."""
    return (len(axis) == 3 and all(sign in (1, -1) for _, sign in axis)
            and sorted(src for src, _ in axis) == [0, 1, 2])


def axis_map_text(axis) -> str:
    return ",".join(f"{n}={'+' if sign > 0 else '-'}c{'xyz'[src]}" for n, (src, sign) in zip("xyz", axis))


DEFAULT_SESSION_LABELS = {"material": "unknown", "mode": "unknown",
                          "bit": {"type": "unknown", "diameter_mm": None},
                          "drill": {"model": "", "gear": None, "speed_setting": None}, "notes": ""}


def _cal(tail: dict | None):
    cal = (tail or {}).get("cal") or {}
    acc = cal.get("accel")
    mount = cal.get("mount")
    accel_cal = ({"valid": bool(acc.get("valid")), "bias_g": list(acc["bias_g"]), "scale": list(acc["scale"])}
                 if isinstance(acc, dict) and "bias_g" in acc and "scale" in acc else None)
    mount_cal = ({"valid": bool(mount.get("valid")), "drill_axis": list(mount["drill_axis"])}
                 if isinstance(mount, dict) and "drill_axis" in mount else None)
    gb = cal.get("gyro_bias_rad_s")
    return accel_cal, mount_cal, (list(gb) if isinstance(gb, list) and len(gb) == 3 else None)


def build_meta(take: Take, imu: ImuTrack, audio: AudioTrack | None, *, recording_id: str,
               session_id: str, start_utc: str | None = None, app: dict | None = None,
               session_labels: dict | None = None, adc_delay_us: float | None = None,
               review: dict | None = None) -> dict:
    """meta.json for a take, from its SESSION / STATS / END records (derived,
    files: filled in by io.write_recording). review defaults to auto_only without
    a detector (a take has no labels yet)."""
    from . import __version__
    from .io import FLAG_FILLED, LEVEL_MODES, parse_recording_id

    s = take.session
    if s is None:
        raise ValueError("the take has no SESSION record: profile and scales are unknown")
    if not s.accel_lsb_per_g or not s.gyro_lsb_per_dps:
        raise ValueError(f"SESSION scales accel_lsb_per_g {s.accel_lsb_per_g}, "
                         f"gyro_lsb_per_dps {s.gyro_lsb_per_dps}: the counts cannot be scaled")
    if not axis_map_ok(s.axis):
        raise ValueError(f"SESSION axis map {s.axis} is not a chip-to-board map")
    fw_version, fw_git = fw_parts(s.fw)
    accel_cal, mount_cal, gyro_bias = _cal(s.tail)
    seq, t = imu.seq, imu.t_us
    odr_data = (seq[-1] - seq[0]) / ((t[-1] - t[0]) / 1e6) if len(seq) > 1 and t[-1] > t[0] else s.odr_mhz / 1000

    audio_meta = sync_meta = None
    missing_frames = 0
    if _has_audio(audio):
        fit = audio_fit(audio, s)
        missing_frames = sum(f1 - f0 for f0, f1 in audio.gap_frames)
        chans = [MICS[i] for i in range(len(MICS)) if s.mic_mask & (1 << i)]
        if len(chans) != audio.samples.shape[1]:
            chans = [f"mic{i + 1}" for i in range(audio.samples.shape[1])]
        audio_meta = {"codec": "flac", "file": "audio.flac", "rate_nominal_hz": s.audio_rate_hz,
                      "rate_measured_hz": round(fit.rate_hz, 3), "bits": 16, "channels": chans,
                      "adc": "ES7210", "gain_db": s.gain_db, "muted_spans_us": [],
                      "clipped_frac": round(_clipped_frac(audio.samples), 6) if audio.samples.size else 0.0}
        if s.audio_block_frames:
            audio_meta["block_frames"] = s.audio_block_frames
        sync_meta = {"method": "esp_timer@dma_block", "fit": "t_us = a + b*frame", "a_us": round(fit.a_us, 1),
                     "b_us_per_frame": round(fit.b_us_per_frame, 6), "residual_rms_us": round(fit.residual_rms_us, 1)}
        if adc_delay_us is not None:
            sync_meta["adc_delay_us"] = adc_delay_us

    settings = (s.tail or {}).get("settings") or {}
    lv = take.of(Level)
    mode = settings.get("mode")
    if mode not in LEVEL_MODES:
        mode = LEVEL_MODES[lv[0].mode] if lv and lv[0].mode < len(LEVEL_MODES) else "wall"
    level_meta = {"mode": mode, "target_deg": float(settings.get("target", 0.0)),
                  "tol_deg": float(settings.get("tol", 0.5))}
    if "near" in settings:
        level_meta["near_deg"] = float(settings["near"])

    stats = take.of(Stats)
    end = take.end
    take_stats = {}
    if stats and stats[-1].ring_size:
        take_stats["ring_high_water_pct"] = round(100.0 * max(x.ring_high_water for x in stats) / stats[-1].ring_size, 2)
    src = end if end is not None else (stats[-1] if stats else None)
    if src is not None:
        take_stats["dropped_records"] = src.dropped_records
        take_stats["dropped_bytes"] = src.dropped_bytes
        take_stats["audio_overflows"] = src.audio_overflows
    batt = [x.battery_pct for x in stats if x.power_flags & STATS_BATTERY_VALID]
    if batt:
        take_stats["battery_pct_start"] = batt[0]
        take_stats["battery_pct_end"] = batt[-1]
    reason = end.reason_name if end is not None else "unknown"

    filled = int(((imu.flags & FLAG_FILLED) != 0).sum())
    missing = int(seq[-1] - seq[0] + 1 - len(seq))
    quality = []
    if missing or imu.transport_gaps:
        quality.append("imu_gap")
    if missing_frames:
        quality.append("audio_gap")
    if audio_meta and audio_meta["clipped_frac"] > 0.001:
        quality.append("audio_clip")
    if end is None or reason not in ("user", "max_s"):
        quality.append("truncated")

    if start_utc is None:
        start_utc = parse_recording_id(recording_id).astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    return {
        "schema": "drillbalancer.meta/1", "taxonomy_version": 1,
        "recording_id": recording_id, "session_id": session_id, "start_utc": start_utc,
        "device_t0_us": int(t[0]), "duration_s": round(float(t[-1] - t[0]) / 1e6, 3),
        "device": {"board": BOARD, "fw_version": fw_version, "fw_git": fw_git, "board_id": s.board_id},
        "app": app or {"version": __version__, "tool": "dbdata.dbrec"},
        "imu": {"chip": "QMI8658", "profile": s.profile_name, "odr_nominal_hz": s.odr_mhz / 1000,
                "odr_measured_hz": round(float(odr_data), 3),
                "accel_range_g": 32768 // s.accel_lsb_per_g, "gyro_range_dps": 32768 // s.gyro_lsb_per_dps,
                "accel_lsb_per_g": s.accel_lsb_per_g, "gyro_lsb_per_dps": s.gyro_lsb_per_dps,
                "accel_lpf": lpf_text(s.ctrl5, True), "gyro_lpf": lpf_text(s.ctrl5, False),
                "frame": IMU_FRAME_TEXT, "axis_map": axis_map_text(s.axis),
                "accel_cal": accel_cal, "mount": mount_cal, "gyro_bias_rad_s": gyro_bias,
                "registers": {"ctrl2": s.ctrl2, "ctrl3": s.ctrl3, "ctrl5": s.ctrl5}},
        "take": {"take_id": s.take_id, "end_reason": reason if reason in TAKE_REASONS else "unknown",
                 "stats": take_stats},
        "audio": audio_meta, "sync": sync_meta,
        "gaps": {"imu_filled_samples": filled, "imu_missing_samples": missing,
                 "transport_gaps": imu.transport_gaps, "audio_missing_frames": int(missing_frames),
                 "audio_gap_frames": [[int(a), int(b)] for a, b in (audio.gap_frames if audio_meta else [])]},
        "level": level_meta,
        "session": json.loads(json.dumps(session_labels or DEFAULT_SESSION_LABELS)),
        "derived": {"orientation": "unknown", "off_angle_deg": None, "tags": []},
        "review": json.loads(json.dumps(review)) if review else
        {"status": "auto_only", "detector": "none", "revision": 0, "edited_events": 0, "reviewer": None},
        "quality": {"flags": quality},
        "files": {},
    }


def empty_events(recording_id: str, t_us) -> dict:
    return contract.events_document(recording_id, [t_us[0], int(t_us[-1]) + 1], revision=0)


def export_take(take: Take, dest: str | Path, *, recording_id: str, session_id: str,
                events: dict | None = None, **meta_kw) -> dict:
    """Writes one take as a dataset recording folder (io.write_recording) and
    returns its meta: IMU and audio over the span both cover (common_span).
    events defaults to an empty label set over the IMU span."""
    from .io import write_recording

    imu = imu_track(take)
    if imu is None or len(imu.seq) < 2:
        raise ValueError("the take has fewer than two IMU samples")
    imu, audio = common_span(imu, audio_track(take), take.session)
    meta = build_meta(take, imu, audio, recording_id=recording_id, session_id=session_id, **meta_kw)
    imu_cols = {"seq": imu.seq, "t_us": imu.t_us}
    for i, c in enumerate(("ax", "ay", "az", "gx", "gy", "gz")):
        imu_cols[c] = imu.counts[:, i].astype(np.int64)
    imu_cols["flags"] = imu.flags
    dev = device_event_rows(take)
    has_audio = _has_audio(audio)
    return write_recording(
        dest, meta, events or empty_events(recording_id, imu.t_us), imu_cols, level=level_rows(take),
        audio=audio.samples if has_audio else None,
        audio_sync={"frame": audio.sync_frame, "t_us": audio.sync_t_us} if has_audio else None,
        device_events=dev if dev["t_us"] else None, derive=True)


# ---- self-check against rec_stream.h -------------------------------------------------------

COMPONENTS = Path(__file__).resolve().parents[3] / "components"
HEADER = COMPONENTS / "recorder" / "include" / "rec_stream.h"
BLE_HEADER = COMPONENTS / "ble" / "include" / "ble_protocol.h"     # DB_ANGLE_FLAG_*: LEVEL.flags
LEVEL_HEADER = COMPONENTS / "level" / "include" / "level.h"        # level_mode_t: LEVEL.mode
_C_TYPES = {"uint8_t": "B", "int8_t": "b", "uint16_t": "H", "int16_t": "h", "uint32_t": "I",
            "int32_t": "i", "uint64_t": "Q", "int64_t": "q"}


def header_defines(text: str) -> dict:
    out = {}
    for m in re.finditer(r"^\s*#define\s+(\w+)\s+\(?(0x[0-9A-Fa-f]+|\d+)(?:u\s*<<\s*(\d+))?\)?",
                         text, re.MULTILINE):
        v = int(m.group(2), 0)
        out[m.group(1)] = v << int(m.group(3)) if m.group(3) else v
    return out


def header_structs(text: str) -> dict:
    """name -> (struct format, size from the '// N bytes' comment) of the packed structs."""
    code = re.sub(r"//[^\n]*(?=\n)", lambda m: m.group(0) if "bytes" in m.group(0) else "", text)
    out = {}
    for m in re.finditer(r"typedef struct __attribute__\(\(packed\)\) \{(.*?)\} (\w+);[ \t]*(?://\s*(\d+) bytes)?",
                         code, re.DOTALL):
        body, name, size = m.group(1), m.group(2), m.group(3)
        fmt = ""
        for f in re.finditer(r"^\s*(\w+)\s+(\w+)(?:\[(\d+)\])?;", body, re.MULTILINE):
            ctype, n = f.group(1), int(f.group(3) or 1)
            if ctype == "char" or (ctype == "uint8_t" and f.group(3)):
                fmt += f"{n}s"
            elif ctype in _C_TYPES:
                fmt += _C_TYPES[ctype] * n
            elif ctype + "_fmt" in out:
                fmt += out[ctype + "_fmt"][0] * n
            elif ctype in out:
                fmt += out[ctype][0] * n
            else:
                raise ValueError(f"{name}: unknown field type {ctype}")
        out[name] = (fmt, int(size) if size else None)
    return out


def enum_names(text: str, name: str) -> list[str]:
    """The constants of `typedef enum { ... } name;` in order (no explicit values after the first)."""
    m = re.search(r"typedef enum \{(.*?)\} " + name + ";", text, re.DOTALL)
    body = re.sub(r"//[^\n]*", "", m.group(1)) if m else ""
    return [re.match(r"\s*(\w+)", c).group(1) for c in body.split(",") if c.strip()]


def self_check(verbose: bool = True, header: Path = HEADER, ble_header: Path = BLE_HEADER,
               level_header: Path = LEVEL_HEADER) -> bool:
    ok = True

    def check(cond, what):
        nonlocal ok
        if verbose or not cond:
            print(("ok   " if cond else "FAIL ") + what)
        ok = ok and bool(cond)

    text = header.read_text(encoding="utf-8")
    d = header_defines(text)
    for name, v in (("REC_STREAM_PORT", STREAM_PORT), ("REC_STREAM_VERSION", STREAM_VERSION),
                    ("REC_HELLO_TOKEN_LEN", HELLO_TOKEN_LEN), ("REC_HELLO_LEN", HELLO_LEN),
                    ("REC_HDR_LEN", HDR_LEN), ("REC_CRC_LEN", CRC_LEN), ("REC_MAX_PAYLOAD", MAX_PAYLOAD),
                    ("REC_IMU_DISCONT", IMU_DISCONT), ("REC_IMU_EPOCH", IMU_EPOCH),
                    ("REC_IMU_FRAME_LEN", IMU_FRAME_LEN),
                    ("REC_AUDIO_OVF_BEFORE", AUDIO_OVF_BEFORE), ("REC_EVENT_TEXT_MAX", EVENT_TEXT_MAX),
                    ("REC_MIC_1", 1), ("REC_MIC_2", 2), ("REC_PROFILE_COUNT", len(PROFILES)),
                    ("REC_STATS_BATTERY_VALID", STATS_BATTERY_VALID), ("REC_STATS_VBUS", STATS_VBUS),
                    ("REC_STATS_CHARGING", STATS_CHARGING)):
        check(d.get(name) == v, f"{name} = {v}")
    for t, name in TYPE_NAMES.items():
        check(d.get("REC_T_" + name) == t, f"REC_T_{name} = 0x{t:02x}")
        check(d.get("REC_V_" + name) == VERSIONS[t], f"REC_V_{name} = {VERSIONS[t]}")
    check(sorted(k for k in d if k.startswith("REC_T_")) == sorted("REC_T_" + n for n in TYPE_NAMES.values()),
          "every REC_T_* is known")
    for i, name in enumerate(PROFILES):
        check(d.get("REC_PROFILE_" + name) == i, f"REC_PROFILE_{name} = {i}")
    for i, name in enumerate(EVENT_CODES):
        check(d.get("REC_EV_" + name) == i + 1, f"REC_EV_{name} = {i + 1}")
    check(len([k for k in d if k.startswith("REC_EV_")]) == len(EVENT_CODES), "every REC_EV_* is known")
    for i, name in enumerate(REASONS):
        check(d.get("REC_REASON_" + name.upper()) == i, f"REC_REASON_{name.upper()} = {i}")
    check(len([k for k in d if k.startswith("REC_REASON_")]) == len(REASONS), "every REC_REASON_* is known")

    def expand(fmt):    # "8h" -> "hhhhhhhh" ("32s" stays)
        return re.sub(r"(\d+)([^\ds])", lambda m: m.group(2) * int(m.group(1)), fmt.lstrip("<"))

    s = header_structs(text)
    for cname, ours in (("rec_hdr_t", HDR), ("rec_session_t", SESSION), ("rec_imu_t", IMU),
                        ("rec_audio_t", AUDIO), ("rec_event_t", EVENT), ("rec_level_t", LEVEL),
                        ("rec_stats_t", STATS), ("rec_end_t", END)):
        fmt, size = s.get(cname, ("?", None))
        check(expand(fmt) == expand(ours.format), f"{cname} layout {fmt} matches {ours.format[1:]}")
        check(size == ours.size, f"{cname} is {ours.size} bytes (header says {size})")

    # the names the dataset contract uses for the same values
    from . import io

    # LEVEL.flags / LEVEL.mode feed the derived labels and E_LEVEL_ZERO
    bd = header_defines(ble_header.read_text(encoding="utf-8"))
    check(bd.get("DB_ANGLE_FLAG_ZERO_SET") == io.LEVEL_FLAG_ZERO_SET,
          f"DB_ANGLE_FLAG_ZERO_SET = io.LEVEL_FLAG_ZERO_SET ({io.LEVEL_FLAG_ZERO_SET})")
    check(bd.get("DB_ANGLE_FLAG_IMU_OK") == io.LEVEL_FLAG_IMU_OK,
          f"DB_ANGLE_FLAG_IMU_OK = io.LEVEL_FLAG_IMU_OK ({io.LEVEL_FLAG_IMU_OK})")
    modes = enum_names(level_header.read_text(encoding="utf-8"), "level_mode_t")
    check(modes == ["LEVEL_MODE_" + m.upper() for m in io.LEVEL_MODES] + ["LEVEL_MODE_COUNT"],
          f"level_mode_t = io.LEVEL_MODES {io.LEVEL_MODES}")
    tax = contract.load_taxonomy()
    check(all(tax.lookup("imu_profile", p) for p in PROFILES), "profiles are taxonomy imu_profile ids")
    check(set(TAKE_REASONS) <= set(REASONS), "meta.take.end_reason values are REC_REASON_* names")

    # the CRC is zlib's, as the header says
    check("rec_crc32(0, \"123456789\", 9)" in text and "0xCBF43926" in text and
          crc32(b"123456789") == 0xCBF43926, "CRC-32 check value")
    rec = encode_record(T_EVENT, 1, 0x01020304, b"AB")
    check(rec.hex() == "04010200040302014142b01f75b9", "record framing (test_rec_stream.c vector)")
    recs, st = decode(b"\xff" + rec + rec[:-1] + b"\x00" + rec)
    check(len(recs) == 2 and st.skipped_bytes == 1 + len(rec) and st.resyncs == 2,
          "resynchronises after junk and a bad CRC")
    print("self-check " + ("passed" if ok else "FAILED"))
    return ok


# ---- command line --------------------------------------------------------------------------

def _info(path: str) -> int:
    recs, st = decode_file(path)
    print(f"{st.bytes} bytes, {st.records} records {st.by_type}, skipped {st.skipped_bytes} bytes "
          f"in {st.resyncs} resyncs, {st.unknown} unknown, {st.malformed} malformed")
    for i, t in enumerate(split_takes(recs)):
        s = t.session
        imu = imu_track(t)
        head = (f"take {s.take_id} {s.profile_name} fw {s.fw}" if s else "take without SESSION")
        print(f"[{i}] {head}: {len(t.records)} records, {t.missing_records} missing, "
              f"end {t.end.reason_name if t.end else 'missing'}, "
              f"{len(imu.seq) if imu else 0} IMU samples")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m dbdata.dbrec", description=__doc__.split("\n\n")[0])
    ap.add_argument("--self-check", action="store_true", help="check against rec_stream.h")
    sub = ap.add_subparsers(dest="cmd")
    i = sub.add_parser("info", help="summarise a .dbrec file")
    i.add_argument("file")
    e = sub.add_parser("export", help="write a take as a recording folder under ROOT")
    e.add_argument("file")
    e.add_argument("root")
    e.add_argument("--take", type=int, default=0, help="index of the take (see info)")
    e.add_argument("--recording-id", required=True)
    e.add_argument("--session-id", required=True)
    a = ap.parse_args(argv)
    if a.self_check:
        return 0 if self_check() else 1
    if a.cmd == "info":
        return _info(a.file)
    if a.cmd == "export":
        from .io import recording_dir

        recs, _ = decode_file(a.file)
        takes = split_takes(recs)
        if not 0 <= a.take < len(takes):
            print(f"error: no take {a.take} ({len(takes)} in the file)", file=sys.stderr)
            return 1
        try:
            meta = export_take(takes[a.take], recording_dir(a.root, a.recording_id),
                               recording_id=a.recording_id, session_id=a.session_id)
        except ValueError as e:
            print(f"error: take {a.take}: {e}", file=sys.stderr)
            return 1
        print(f"wrote {recording_dir(a.root, a.recording_id)} ({meta['duration_s']} s)")
        return 0
    ap.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
