"""dbdata.dbrec: the device stream decoder, its self-check, and the export into a recording folder.

The golden stream is written by firmware/test_host/recorder/test_rec_golden.c; its comment lists
the contents checked here field by field.
"""

import contextlib
import dataclasses
import io as _io
import struct
import unittest

import numpy as np
import pandas as pd

from fixtures import HERE, RID, SESSION as SID, TempDirTest

from dbdata import dbrec
from dbdata import io as dio
from dbdata.validate import validate_recording

GOLDEN = HERE.parents[2] / "test_host" / "recorder" / "vectors" / "golden_stream.bin"
T = 1203345000
TS0 = 0x00FFFFFA


def imu_host(chip_ts):
    return T + 100000 + (chip_ts - TS0) * 2230


def imu_pattern(k):
    """test_rec_golden.c imu_frame(): sample k = ts - TS0."""
    k = np.asarray(k, dtype=np.int64)
    return np.stack([100 + k, 8192 - k, -50 + 2 * k, k, -k, 3 * k], axis=-1)


def audio_pattern(block):
    idx = block * 256 + np.arange(256)
    return np.stack([(idx * 37) % 2001 - 1000, (idx * 53) % 4001 - 2000], axis=-1)


def golden():
    return dbrec.decode_file(GOLDEN)


class SelfCheckTest(TempDirTest):
    def test_matches_rec_stream_h(self):
        with contextlib.redirect_stdout(_io.StringIO()):
            self.assertTrue(dbrec.self_check(verbose=False))

    def test_notices_drift(self):
        text = dbrec.HEADER.read_text(encoding="utf-8")
        for old, new in (("#define REC_T_END     0x0F", "#define REC_T_END     0x0E"),
                         ("    int16_t zero_bubble_y_cdeg;\n", "    int16_t zero_bubble_y_cdeg;\n    uint8_t extra;\n"),
                         ("} rec_end_t;                  // 37 bytes", "} rec_end_t;                  // 38 bytes"),
                         ("#define REC_EV_PROFILE   9", "#define REC_EV_PROFILE   9\n#define REC_EV_NEW 10")):
            self.assertIn(old, text)
            h = self.tmp / "rec_stream.h"
            h.write_text(text.replace(old, new), encoding="utf-8")
            out = _io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertFalse(dbrec.self_check(verbose=False, header=h), old)
            self.assertIn("FAIL", out.getvalue())

    def test_notices_level_drift(self):
        for path, kw, old, new in (
                (dbrec.BLE_HEADER, "ble_header", "#define DB_ANGLE_FLAG_IMU_OK     (1u << 6)",
                 "#define DB_ANGLE_FLAG_IMU_OK     (1u << 7)"),
                (dbrec.BLE_HEADER, "ble_header", "#define DB_ANGLE_FLAG_ZERO_SET   (1u << 4)",
                 "#define DB_ANGLE_FLAG_ZERO_SET   (1u << 3)"),
                (dbrec.LEVEL_HEADER, "level_header", "    LEVEL_MODE_FLOOR,", "    LEVEL_MODE_CEILING,\n    LEVEL_MODE_FLOOR,")):
            text = path.read_text(encoding="utf-8")
            self.assertIn(old, text)
            h = self.tmp / path.name
            h.write_text(text.replace(old, new), encoding="utf-8")
            out = _io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertFalse(dbrec.self_check(verbose=False, **{kw: h}), old)
            self.assertIn("FAIL", out.getvalue())


class GoldenStreamTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.records, cls.stats = golden()
        cls.by_seq = {r.seq: r for r in cls.records}

    def test_framing(self):
        self.assertEqual([r.seq for r in self.records], [0, 1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 15, 16, 17])
        s = self.stats
        self.assertEqual((s.records, s.skipped_bytes, s.resyncs, s.unknown, s.malformed), (16, 29, 2, 1, 0))
        self.assertEqual(s.bytes, GOLDEN.stat().st_size)
        self.assertEqual(s.by_type, {"SESSION": 1, "LEVEL": 2, "IMU": 5, "AUDIO": 3, "EVENT": 2, "0x7e": 1,
                                     "STATS": 1, "END": 1})
        # offsets: records follow each other, the junk sits before seq 13, the bad record before 15
        r = self.records
        for a, b in zip(r, r[1:]):
            gap = b.offset - (a.offset + dbrec.HDR_LEN + len(a.payload) + dbrec.CRC_LEN)
            self.assertEqual(gap, {13: 5, 15: 24}.get(b.seq, 0), b.seq)

    def test_chunked_feeding_gives_the_same(self):
        for chunk in (1, 7, 100, 1500):
            recs, st = dbrec.decode_file(GOLDEN, chunk=chunk)
            self.assertEqual([(x.seq, x.type, x.payload, x.offset) for x in recs],
                             [(x.seq, x.type, x.payload, x.offset) for x in self.records], chunk)
            self.assertEqual((st.records, st.skipped_bytes, st.resyncs), (16, 29, 2), chunk)

    def test_session(self):
        s = self.by_seq[0].data
        self.assertIsInstance(s, dbrec.Session)
        self.assertEqual((s.take_id, s.start_us, s.board_id, s.fw), (42, T, "b3c9e1a2", "0.1.0-g0e5105e"))
        self.assertEqual((s.profile, s.profile_name, s.ctrl2, s.ctrl3, s.ctrl5), (1, "RAW448", 0x14, 0x54, 0x00))
        self.assertEqual((s.odr_mhz, s.accel_lsb_per_g, s.gyro_lsb_per_dps), (448400, 8192, 64))
        self.assertEqual(s.axis, ((1, 1), (0, -1), (2, 1)))
        self.assertEqual((s.audio_rate_hz, s.audio_channels, s.audio_bits, s.mic_mask, s.gain_db,
                          s.audio_block_frames, s.max_s), (16000, 2, 16, 3, 6, 256, 300))
        self.assertEqual(s.tail["settings"], {"tol": 0.5, "near": 2.0, "target": 0.0, "mode": "wall"})
        self.assertEqual(s.tail["cal"]["accel"], {"valid": True, "bias_g": [0.004, -0.011, 0.02],
                                                  "scale": [1.002, 0.998, 1.004]})
        self.assertEqual(s.tail["cal"]["mount"], {"valid": True, "drill_axis": [0.012, -0.02, -0.9997]})
        self.assertEqual(s.tail["cal"]["gyro_bias_rad_s"], [0.0011, -0.0004, 0.0002])

    def test_level(self):
        a, b = self.by_seq[1].data, self.by_seq[13].data
        self.assertEqual((a.host_us, a.mode, a.flags, a.elevation_cdeg, a.yaw_cdeg, a.bubble_x_cdeg, a.bubble_y_cdeg,
                          a.deviation_cdeg, a.zero_elevation_cdeg, a.zero_bubble_x_cdeg, a.zero_bubble_y_cdeg),
                         (T + 50000, 0, 0x60, 40, 0, 0, 0, 40, 0, 0, 0))
        self.assertEqual((b.host_us, b.mode, b.flags, b.elevation_cdeg, b.yaw_cdeg, b.bubble_x_cdeg, b.bubble_y_cdeg,
                          b.deviation_cdeg, b.zero_elevation_cdeg, b.zero_bubble_x_cdeg, b.zero_bubble_y_cdeg),
                         (T + 150000, 2, 0x50, 120, -30, 5, -5, 120, 1234, 10, -20))

    def test_imu(self):
        want = {2: (TS0, 8, TS0 + 7, 1), 4: (TS0 + 8, 3, TS0 + 16, 0), 5: (TS0 + 13, 4, TS0 + 16, 0),
                10: (TS0 + 22, 5, TS0 + 26, 0), 15: (TS0 + 70, 4, TS0 + 73, 1)}
        for seq, (first, n, chip, flags) in want.items():
            d = self.by_seq[seq].data
            self.assertIsInstance(d, dbrec.Imu)
            self.assertEqual((d.ts_first, d.n, d.chip_ts, d.flags, d.host_us), (first, n, chip, flags, imu_host(chip)))
            self.assertEqual(d.discont, bool(flags))
            self.assertEqual(d.frames.dtype, np.int16)
            np.testing.assert_array_equal(d.frames, imu_pattern(np.arange(first, first + n) - TS0))

    def test_audio(self):
        for seq, block, flags in ((3, 0, 0), (7, 1, 0), (9, 3, 1)):
            d = self.by_seq[seq].data
            self.assertIsInstance(d, dbrec.Audio)
            self.assertEqual((d.sample_index, d.host_us, d.n, d.flags), (block * 256, T + 90000 + (block + 1) * 16000,
                                                                       256, flags))
            self.assertEqual(d.overflow_before, bool(flags))
            self.assertEqual(d.samples.shape, (256, 2))
            np.testing.assert_array_equal(d.samples, audio_pattern(block))

    def test_events_and_unknown(self):
        m, t = self.by_seq[6].data, self.by_seq[11].data
        self.assertEqual((m.host_us, m.code, m.code_name, m.arg, m.text), (T + 125000, 1, "MARK", 7, "hole 1 – oak"))
        self.assertEqual((t.host_us, t.code, t.code_name, t.arg, t.text), (T + 151000, 2, "TOUCH", 0, ""))
        u = self.by_seq[12]
        self.assertEqual((u.type, u.ver, u.payload, u.data, u.type_name), (0x7E, 1, b"future", None, "0x7e"))

    def test_stats_and_end(self):
        s, e = self.by_seq[16].data, self.by_seq[17].data
        self.assertEqual(s, dbrec.Stats(T + 210000, 24, 45, 2230000, 1024, 1, 16000500, 0, 1234, 4 << 20, 1, 92,
                                        3987, 76, dbrec.STATS_BATTERY_VALID))
        self.assertEqual(e, dbrec.End(T + 220000, 1, 18, 1, 92, 24, 45, 1024, 1))
        self.assertEqual(e.reason_name, "user")

    def test_take(self):
        takes = dbrec.split_takes(self.records)
        self.assertEqual(len(takes), 1)
        t = takes[0]
        self.assertEqual(t.session.take_id, 42)
        self.assertEqual(t.end.records, 18)
        self.assertEqual(t.missing_records, 2)          # seq 8 dropped, seq 14 corrupted
        self.assertEqual(len(t.of(dbrec.Imu)), 5)

    def test_imu_track(self):
        trk = dbrec.imu_track(dbrec.split_takes(self.records)[0])
        k = trk.seq - TS0
        want_k = np.r_[0:8, 8:11, 11:13, 13:17, 22:27, 70:74]
        np.testing.assert_array_equal(k, want_k)
        # the two samples lost in read mode are filled; the pattern is linear, so exactly
        np.testing.assert_array_equal(trk.counts, imu_pattern(want_k))
        filled = (trk.flags & dio.FLAG_FILLED) != 0
        np.testing.assert_array_equal(np.nonzero(filled)[0], [11, 12])
        gap = (trk.flags & dio.FLAG_GAP_BEFORE) != 0
        np.testing.assert_array_equal(k[gap], [22, 70])  # after the dropped record, after the resync
        self.assertFalse(((trk.flags & (dio.FLAG_ACCEL_CLIP | dio.FLAG_GYRO_CLIP)) != 0).any())
        # the reads lie on one line: t_us is exact
        np.testing.assert_array_equal(trk.t_us, imu_host(trk.seq))
        self.assertAlmostEqual(trk.fit[1], 2230.0, places=6)
        # seq 8 (dropped) and seq 14 (bad CRC) may both have held IMU samples
        self.assertEqual(trk.transport_gaps, [[imu_host(TS0 + 16), imu_host(TS0 + 22)],
                                              [imu_host(TS0 + 26), imu_host(TS0 + 70)]])

    def test_audio_track(self):
        a = dbrec.audio_track(dbrec.split_takes(self.records)[0])
        self.assertEqual(a.samples.shape, (1024, 2))
        np.testing.assert_array_equal(a.samples[:256], audio_pattern(0))
        np.testing.assert_array_equal(a.samples[256:512], audio_pattern(1))
        self.assertFalse(a.samples[512:768].any())
        np.testing.assert_array_equal(a.samples[768:], audio_pattern(3))
        self.assertEqual(a.gap_frames, [[512, 768]])
        np.testing.assert_array_equal(a.sync_frame, [256, 512, 1024])
        np.testing.assert_array_equal(a.sync_t_us, [T + 106000, T + 122000, T + 154000])
        self.assertEqual(a.overflows, 1)


class GoldenExportTest(TempDirTest):
    def test_export(self):
        recs, _ = golden()
        dest = dio.recording_dir(self.tmp, RID)
        meta = dbrec.export_take(dbrec.split_takes(recs)[0], dest, recording_id=RID, session_id=SID)
        self.assertEqual(sorted(p.name for p in dest.iterdir()),
                         ["audio.flac", "audio_sync.csv", "device_events.csv", "events.json", "imu.csv.gz",
                          "level.csv.gz", "meta.json"])
        self.assertEqual(meta["device"], {"board": dbrec.BOARD, "fw_version": "0.1.0", "fw_git": "0e5105e",
                                          "board_id": "b3c9e1a2"})
        im = meta["imu"]
        self.assertEqual((im["profile"], im["odr_nominal_hz"], im["accel_range_g"], im["gyro_range_dps"]),
                         ("RAW448", 448.4, 4, 512))
        self.assertEqual((im["accel_lpf"], im["gyro_lpf"], im["axis_map"]), ("off", "off", "x=+cy,y=-cx,z=+cz"))
        self.assertEqual(im["registers"], {"ctrl2": 0x14, "ctrl3": 0x54, "ctrl5": 0})
        self.assertEqual(im["accel_cal"]["bias_g"], [0.004, -0.011, 0.02])
        self.assertEqual(im["mount"]["drill_axis"], [0.012, -0.02, -0.9997])
        self.assertEqual(im["gyro_bias_rad_s"], [0.0011, -0.0004, 0.0002])
        self.assertAlmostEqual(im["odr_measured_hz"], 1e6 / 2230, places=2)
        self.assertEqual(meta["take"], {"take_id": 42, "end_reason": "user",
                                        "stats": {"ring_high_water_pct": round(100 * 1234 / (4 << 20), 2),
                                                  "dropped_records": 1, "dropped_bytes": 92, "audio_overflows": 1,
                                                  "battery_pct_start": 76, "battery_pct_end": 76}})
        # IMU from T+100000 (TS0), audio until T+154000: both cut to that span (common_span), so the
        # IMU keeps TS0..TS0+24 and the audio starts at frame 160
        self.assertEqual(meta["gaps"], {"imu_filled_samples": 2, "imu_missing_samples": 5,
                                        "transport_gaps": [[imu_host(TS0 + 16), imu_host(TS0 + 22)]],
                                        "audio_missing_frames": 256, "audio_gap_frames": [[352, 608]]})
        self.assertEqual(meta["level"], {"mode": "wall", "target_deg": 0.0, "tol_deg": 0.5, "near_deg": 2.0})
        self.assertEqual(meta["audio"]["channels"], ["mic1", "mic2"])
        self.assertEqual((meta["audio"]["gain_db"], meta["audio"]["block_frames"]), (6, 256))
        self.assertEqual(meta["quality"]["flags"], ["imu_gap", "audio_gap"])
        self.assertEqual(meta["device_t0_us"], imu_host(TS0))
        self.assertEqual(meta["start_utc"], "2026-10-26T14:30:12Z")

        rec = dio.read_recording(dest)
        self.assertEqual(rec.imu["seq"].iloc[-1] - TS0, 24)
        np.testing.assert_array_equal(rec.imu_raw, imu_pattern(rec.imu["seq"].to_numpy() - TS0))
        lv = rec.level
        self.assertEqual(list(lv.columns), list(dio.LEVEL_COLUMNS))
        self.assertEqual(lv.iloc[1][list(dio.LEVEL_ZERO_COLUMNS)].tolist(), [1234, 10, -20])
        self.assertEqual(lv["t_us"].tolist(), [T + 50000, T + 150000])
        de = rec.device_events
        self.assertEqual(de["code"].tolist(), ["MARK", "TOUCH"])
        self.assertEqual(de["text"].tolist(), ["hole 1 – oak", ""])
        self.assertEqual(de["arg"].tolist(), [7, 0])
        self.assertEqual(rec.audio.shape, (864, 2))
        np.testing.assert_array_equal(rec.audio[:96], audio_pattern(0)[160:])
        np.testing.assert_array_equal(rec.audio[608:], audio_pattern(3))
        self.assertEqual(rec.audio_sync["frame"].tolist(), [96, 352, 864])
        # the file checks pass; the 0.16 s take is too short for the duration and ODR checks
        rep = validate_recording(dest)
        codes = {i.code for i in rep.errors}
        self.assertFalse(codes - {"E_DURATION"}, rep.to_dict())


# ---- a realistic stream, written here ----------------------------------------------------

def session_payload(take_id=7, profile=1, ctrl5=0, lsb=8192, odr_mhz=448400, mic=3, channels=2, tail=None):
    import json

    fixed = dbrec.SESSION.pack(take_id, T, bytes.fromhex("0badc0de"), b"0.4.0".ljust(32, b"\0"), profile, 0x14,
                               0x54, ctrl5, odr_mhz, lsb, 64, 0, 1, 1, 1, 2, 1, 16000 if channels else 0, channels,
                               16, mic, 6, 256, 300)
    t = {"settings": {"tol": 0.5, "near": 2.0, "target": 0.0, "mode": "wall"}} if tail is None else tail
    return fixed + json.dumps(t).encode()


class Writer:
    """Builds a stream like the device's: seq per record, optional drops."""

    def __init__(self):
        self.out = bytearray()
        self.seq = 0

    def rec(self, rtype, payload, drop=False, ver=1):
        if not drop:
            self.out += dbrec.encode_record(rtype, ver, self.seq, payload)
        self.seq += 1

    def imu(self, ts_first, frames, chip_ts, host_us, flags=0, drop=False):
        f = np.asarray(frames, dtype="<i2")
        self.rec(dbrec.T_IMU, dbrec.IMU.pack(ts_first, chip_ts, host_us, len(f), flags) + f.tobytes(), drop)

    def audio(self, index, host_us, samples, flags=0, drop=False):
        s = np.asarray(samples, dtype="<i2")
        self.rec(dbrec.T_AUDIO, dbrec.AUDIO.pack(index, host_us, len(s), flags) + s.tobytes(), drop)


def realistic(seconds=3.0, loss_every=9, drop_seq=None, end=True, period_us=2230.0, audio_rate=16000.4,
              imu_delay_us=0):
    """IMU reads every ~20 ms with one sample lost in read mode per read (except every loss_every-th),
    audio blocks of 256 frames, LEVEL at 10 Hz, STATS at 1 Hz, a MARK, END. drop_seq: the first
    IMU record from that seq on is dropped (as by a full ring). imu_delay_us: no IMU record before
    (the profile switch at the take start)."""
    w = Writer()
    w.rec(dbrec.T_SESSION, session_payload())
    rng = np.random.default_rng(3)
    ts0 = 0x00FFF000
    n_samples = int(seconds * 1e6 / period_us)
    k = 0
    reads = 0
    a_next = 0
    blocks = int(seconds * audio_rate / 256)
    lvl_next = 0
    while k < n_samples:
        n = int(rng.integers(6, 12))
        n = min(n, n_samples - k)
        frames = np.column_stack([np.full(n, 100), np.full(n, 8192), (np.arange(k, k + n) % 50) - 25,
                                  np.zeros(n), np.zeros(n), np.ones(n)])
        last = ts0 + k + n - 1
        host = int(T + round((k + n - 1) * period_us))
        drop = drop_seq is not None and w.seq >= drop_seq
        if drop:
            drop_seq = None
        if host >= T + imu_delay_us:
            w.imu(ts0 + k, frames, last, host, drop=drop)
        reads += 1
        k += n + (0 if reads % loss_every == 0 else 1)
        t_now = (k * period_us)
        while a_next < blocks and (a_next + 1) * 256 / audio_rate * 1e6 <= t_now:
            idx = a_next * 256 + np.arange(256)
            s = np.column_stack([(np.sin(idx / 7.0) * 3000).astype(int), (np.cos(idx / 5.0) * 2000).astype(int)])
            w.audio(a_next * 256, int(T + round((a_next + 1) * 256 / audio_rate * 1e6)), s)
            a_next += 1
        while lvl_next * 100000 <= t_now:
            w.rec(dbrec.T_LEVEL, dbrec.LEVEL.pack(T + lvl_next * 100000, 0, 0x40, 30, 0, 0, 0, 30, 0, 0, 0))
            if lvl_next % 10 == 5:
                w.rec(dbrec.T_STATS, dbrec.STATS.pack(T + lvl_next * 100000, k, k // 10, 2230000, 0, 0, 0, 0, 5000,
                                                      4 << 20, 0, 0, 3900, 70 - lvl_next // 10, 1))
            if lvl_next == 12:
                w.rec(dbrec.T_EVENT, dbrec.EVENT.pack(T + lvl_next * 100000, 1, 3, ) + "start".encode())
            lvl_next += 1
    if end:
        w.rec(dbrec.T_END, dbrec.END.pack(int(T + seconds * 1e6), 2, w.seq + 1, 0, 0, k, k // 10, blocks * 256, 0))
    return bytes(w.out)


class RealisticExportTest(TempDirTest):
    def export(self, data, rid=RID):
        recs, st = dbrec.decode(data)
        takes = dbrec.split_takes(recs)
        dest = dio.recording_dir(self.tmp, rid)
        return dbrec.export_take(takes[0], dest, recording_id=rid, session_id=SID), dest, st

    def test_validates(self):
        meta, dest, st = self.export(realistic())
        self.assertEqual((st.skipped_bytes, st.unknown, st.malformed), (0, 0, 0))
        rep = validate_recording(dest)
        self.assertEqual(rep.errors, [], rep.to_dict())
        self.assertNotIn("W_GAP_FLAG", rep.codes())
        self.assertNotIn("W_GAPS_META", rep.codes())
        self.assertNotIn("W_ODR_META", rep.codes())
        g = meta["gaps"]
        self.assertGreater(g["imu_filled_samples"], 0)
        self.assertEqual(g["imu_missing_samples"], 0)
        self.assertEqual(meta["take"]["end_reason"], "max_s")
        self.assertEqual(meta["quality"]["flags"], [])
        self.assertAlmostEqual(meta["imu"]["odr_measured_hz"], 1e6 / 2230, delta=0.05)
        self.assertAlmostEqual(meta["audio"]["rate_measured_hz"], 16000.4, delta=0.5)
        # filled samples are about the read-mode loss: 1 in ~9.5
        rec = dio.read_recording(dest)
        self.assertAlmostEqual(rec.filled.mean(), 1 / 9.5, delta=0.03)
        self.assertEqual(rec.device_events["code"].tolist(), ["MARK"])

    def test_dropped_imu_record_is_a_transport_gap(self):
        meta, dest, st = self.export(realistic(drop_seq=40))
        g = meta["gaps"]
        self.assertEqual(len(g["transport_gaps"]), 1)
        self.assertEqual(meta["take"]["stats"]["dropped_records"], 0)    # what END says (written here)
        self.assertGreater(g["imu_missing_samples"], 0)
        self.assertIn("imu_gap", meta["quality"]["flags"])
        rep = validate_recording(dest)
        self.assertNotIn("W_GAP_FLAG", rep.codes())
        self.assertNotIn("W_GAPS_META", rep.codes())
        self.assertFalse([i for i in rep.errors if i.code != "E_DURATION"], rep.to_dict())

    def test_imu_starting_late_is_cut_to_the_common_span(self):
        # audio from T, IMU only from T+180 ms: a 3 s take would differ by 6 % (E_DURATION)
        meta, dest, _ = self.export(realistic(imu_delay_us=180000))
        rep = validate_recording(dest)
        self.assertEqual(rep.errors, [], rep.to_dict())
        rec = dio.read_recording(dest)
        t_imu = rec.imu["t_us"].to_numpy()
        self.assertGreaterEqual(t_imu[0], T + 180000)
        fit = dio.SyncFit.fit(rec.audio_sync["frame"], rec.audio_sync["t_us"])
        self.assertAlmostEqual(float(fit.t_of(0)), t_imu[0], delta=fit.b_us_per_frame)
        self.assertAlmostEqual(len(rec.audio) * fit.b_us_per_frame, t_imu[-1] - t_imu[0], delta=3000)

    def test_take_without_end_is_truncated(self):
        meta, dest, _ = self.export(realistic(end=False))
        self.assertEqual(meta["take"]["end_reason"], "unknown")
        self.assertIn("truncated", meta["quality"]["flags"])
        self.assertEqual(validate_recording(dest).errors, [])


# ---- decoder corner cases -------------------------------------------------------------------

class DecoderTest(unittest.TestCase):
    def test_unknown_version_and_malformed_pass_through(self):
        good = dbrec.encode_record(dbrec.T_EVENT, 1, 0, dbrec.EVENT.pack(5, 2, 0))
        newer = dbrec.encode_record(dbrec.T_EVENT, 2, 1, b"whatever v2 is")
        short = dbrec.encode_record(dbrec.T_LEVEL, 1, 2, b"\0" * 10)
        bad_n = dbrec.encode_record(dbrec.T_IMU, 1, 3, dbrec.IMU.pack(1, 1, 1, 5, 0) + b"\0" * 12)
        longer = dbrec.encode_record(dbrec.T_END, 1, 4, dbrec.END.pack(9, 1, 5, 0, 0, 0, 0, 0, 0) + b"v1.1")
        recs, st = dbrec.decode(good + newer + short + bad_n + longer)
        self.assertEqual([r.seq for r in recs], [0, 1, 2, 3, 4])
        self.assertIsInstance(recs[0].data, dbrec.Event)
        self.assertIsNone(recs[1].data)
        self.assertEqual(recs[1].payload, b"whatever v2 is")
        self.assertIsNone(recs[2].data)
        self.assertIsNone(recs[3].data)
        self.assertEqual(recs[4].data.reason_name, "user")    # appended fields are ignored
        self.assertEqual((st.unknown, st.malformed, st.skipped_bytes), (1, 2, 0))

    def test_resync(self):
        r = [dbrec.encode_record(dbrec.T_EVENT, 1, i, dbrec.EVENT.pack(i, 1, i) + b"x" * i) for i in range(4)]
        bad = bytearray(r[1])
        bad[10] ^= 0x40
        # a plausible header waiting for 4000 bytes that never come, then real records
        lure = struct.pack("<BBHI", 0x02, 1, 4000, 0)
        data = b"\x00\x00\xff" + r[0] + bytes(bad) + lure + r[2] + r[3] + r[3][:9]
        for chunk in (None, 1, 5):
            recs, st = dbrec.decode(data, chunk)
            self.assertEqual([x.seq for x in recs], [0, 2, 3], chunk)
            self.assertEqual(st.skipped_bytes, 3 + len(bad) + len(lure) + 9, chunk)
            self.assertEqual(st.resyncs, 3, chunk)
        # live: while resynchronising, the complete records after the lure are not held back
        d = dbrec.StreamDecoder()
        self.assertEqual([x.seq for x in d.feed(data)], [0, 2, 3])
        self.assertEqual(d.finish(), [])
        self.assertEqual(d.stats.skipped_bytes, 3 + len(bad) + len(lure) + 9)
        # right after a good record a header is trusted: it waits for its payload, and only
        # the end of the stream gives it up
        d = dbrec.StreamDecoder()
        self.assertEqual([x.seq for x in d.feed(r[0] + lure + r[2])], [0])
        self.assertEqual([x.seq for x in d.finish()], [2])
        self.assertEqual(d.stats.skipped_bytes, len(lure))

    def test_large_stream_is_linear(self):
        rec = dbrec.encode_record(dbrec.T_AUDIO, 1, 0, dbrec.AUDIO.pack(0, 0, 256, 0) + bytes(1024))
        recs, st = dbrec.decode(rec * 20000)          # 21 MB at once
        self.assertEqual(st.records, 20000)
        self.assertEqual(recs[-1].offset, 19999 * len(rec))

    def test_empty_and_garbage(self):
        self.assertEqual(dbrec.decode(b"")[0], [])
        recs, st = dbrec.decode(bytes(range(256)) * 4)
        self.assertEqual(recs, [])
        self.assertEqual(st.skipped_bytes, 1024)

    def test_takes_split_on_session_and_seq_restart(self):
        w = Writer()
        w.rec(dbrec.T_SESSION, session_payload(take_id=1))
        w.rec(dbrec.T_LEVEL, dbrec.LEVEL.pack(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        w.rec(dbrec.T_END, dbrec.END.pack(2, 2, 3, 0, 0, 0, 0, 0, 0))
        w.seq = 0
        w.rec(dbrec.T_SESSION, session_payload(take_id=2))
        w.rec(dbrec.T_LEVEL, dbrec.LEVEL.pack(3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), drop=True)
        w.rec(dbrec.T_LEVEL, dbrec.LEVEL.pack(4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        first = bytes(w.out)
        # a take whose SESSION was lost: seq restarts without one
        w2 = Writer()
        w2.rec(dbrec.T_SESSION, session_payload(take_id=3), drop=True)
        w2.rec(dbrec.T_LEVEL, dbrec.LEVEL.pack(5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        takes = dbrec.split_takes(dbrec.decode(first + bytes(w2.out))[0])
        self.assertEqual([t.session.take_id if t.session else None for t in takes], [1, 2, None])
        self.assertEqual([t.end is not None for t in takes], [True, False, False])
        self.assertEqual([t.missing_records for t in takes], [0, 1, 1])

    def test_hello_ok_is_not_part_of_a_take(self):
        # A raw capture of a connection begins with the device's HELLO_OK (seq 0).
        hello_ok = bytes.fromhex("0701000000000000c4dd90c9")    # test_rec_stream.c
        self.assertEqual(dbrec.encode_record(dbrec.T_HELLO_OK, 1, 0, b""), hello_ok)
        w = Writer()
        w.rec(dbrec.T_SESSION, session_payload(take_id=7))
        w.rec(dbrec.T_END, dbrec.END.pack(2, 1, 2, 0, 0, 0, 0, 0, 0))
        recs, st = dbrec.decode(hello_ok + bytes(w.out))
        self.assertEqual((st.unknown, st.malformed), (0, 0))
        self.assertIsInstance(recs[0].data, dbrec.HelloOk)
        takes = dbrec.split_takes(recs)
        self.assertEqual(len(takes), 1)
        self.assertEqual(takes[0].session.take_id, 7)
        self.assertEqual(takes[0].missing_records, 0)


class ImuTrackTest(unittest.TestCase):
    def take(self, *reads):
        w = Writer()
        w.rec(dbrec.T_SESSION, session_payload())
        for first, n, flags, drop, value in reads:
            frames = np.tile(np.array([value, 0, 0, 0, 0, 0]), (n, 1))
            w.imu(first, frames, first + n - 1, T + (first + n - 1) * 2230, flags, drop)
        return dbrec.split_takes(dbrec.decode(bytes(w.out))[0])[0]

    def test_big_fifo_jump_without_discont_is_a_gap(self):
        trk = dbrec.imu_track(self.take((100, 4, 1, False, 0), (104 + 40, 4, 0, False, 0)))
        self.assertEqual(trk.seq.tolist(), [100, 101, 102, 103, 144, 145, 146, 147])
        self.assertEqual((trk.flags & dio.FLAG_GAP_BEFORE).tolist(), [0, 0, 0, 0, 8, 0, 0, 0])
        self.assertEqual(trk.transport_gaps, [])

    def test_fill_interpolates(self):
        trk = dbrec.imu_track(self.take((100, 2, 1, False, 0), (105, 2, 0, False, 400)))
        self.assertEqual(trk.seq.tolist(), list(range(100, 107)))
        self.assertEqual(trk.counts[:, 0].tolist(), [0, 0, 100, 200, 300, 400, 400])
        self.assertEqual((trk.flags & dio.FLAG_FILLED).tolist(), [0, 0, 1, 1, 1, 0, 0])

    def test_backwards_timestamp_moves_on(self):
        trk = dbrec.imu_track(self.take((100, 4, 0, False, 0), (102, 3, 1, False, 0)))
        self.assertEqual(trk.seq.tolist(), list(range(100, 107)))
        self.assertFalse((trk.flags & dio.FLAG_GAP_BEFORE).any())
        # the new segment's fit would start before the old one ends: t_us still goes forward
        self.assertTrue((np.diff(trk.t_us) > 0).all(), trk.t_us.tolist())

    def reinit_take(self, ts_after, flags_after):
        """1 s of IMU from ts 500000 (reads of 32), a 2 s outage, then 1 s on a new count from ts_after."""
        w = Writer()
        w.rec(dbrec.T_SESSION, session_payload())
        p = 1e9 / 448400
        host = T
        for epoch, ts0, flags0 in ((0, 500000, dbrec.IMU_DISCONT), (1, ts_after, flags_after)):
            if epoch:
                host += 2_000_000
            for i in range(14):
                frames = np.tile(np.array([epoch, 0, 8192, 0, 0, 0]), (32, 1))
                w.imu(ts0 + 32 * i, frames, ts0 + 32 * i + 31, int(round(host + (32 * i + 31) * p)),
                      flags0 if i == 0 else 0)
            host += 14 * 32 * p
        return dbrec.split_takes(dbrec.decode(bytes(w.out))[0])[0], p

    def check_reinit(self, take, p):
        trk = dbrec.imu_track(take)
        i = 14 * 32
        self.assertEqual(trk.segments, 2)
        missing = int(trk.seq[i] - trk.seq[i - 1]) - 1
        self.assertAlmostEqual(missing, 2_000_000 / p, delta=2)
        self.assertEqual((trk.flags[i - 1:i + 2] & dio.FLAG_GAP_BEFORE).tolist(), [0, 8, 0])
        self.assertEqual(int(np.count_nonzero(trk.flags & dio.FLAG_GAP_BEFORE)), 1)
        self.assertAlmostEqual(trk.t_us[i] - trk.t_us[i - 1], 2_000_000 + p, delta=2)
        self.assertAlmostEqual(trk.t_us[0], T + 31 * p - 31 * p, delta=1)
        self.assertAlmostEqual(trk.t_us[-1], T + 2_000_000 + (2 * 14 * 32 - 1) * p, delta=2)
        self.assertAlmostEqual(trk.fit[1], p, places=3)
        self.assertTrue((np.diff(trk.seq) > 0).all() and (np.diff(trk.t_us) > 0).all())
        self.assertEqual(trk.counts[i - 1, 0], 0)
        self.assertEqual(trk.counts[i, 0], 1)

    def test_imu_reset_with_epoch_starts_a_new_clock_segment(self):
        # TIMESTAMP restarts near 0 (far below the old count)
        self.check_reinit(*self.reinit_take(3, dbrec.IMU_DISCONT | dbrec.IMU_EPOCH))

    def test_imu_reset_found_without_epoch(self):
        # backwards without the EPOCH flag, and forward by a jump host_us contradicts
        self.check_reinit(*self.reinit_take(3, dbrec.IMU_DISCONT))
        self.check_reinit(*self.reinit_take(500000 + 14 * 32 + 100000, dbrec.IMU_DISCONT))

    def test_epoch_places_the_new_count_by_host_time_alone(self):
        # forward by 10 samples more than the 2 s outage: within the DISCONT tolerance, but
        # EPOCH says the count is unrelated, so the samples missing come from host_us
        self.check_reinit(*self.reinit_take(500000 + 14 * 32 + 897 + 10,
                                            dbrec.IMU_DISCONT | dbrec.IMU_EPOCH))

    def test_common_span_clears_the_gap_flag_of_its_first_sample(self):
        imu = dbrec.ImuTrack(np.array([0, 1, 2, 5, 6, 7]), np.array([0, 10, 20, 50, 60, 70]),
                             np.zeros((6, 6), dtype=np.int16), np.array([0, 0, 0, 8, 0, 0]), [[20, 50]],
                             (0.0, 10.0))
        # audio at 1 frame/us from t = 45: the span is t 45..70, frames 0..25
        frames = np.arange(10, 70, 10)
        audio = dbrec.AudioTrack(np.zeros((60, 2), dtype=np.int16), frames, frames + 45, [], 0)
        imu2, audio2 = dbrec.common_span(imu, audio)
        self.assertEqual(imu2.seq.tolist(), [5, 6, 7])
        self.assertEqual(imu2.flags.tolist(), [0, 0, 0])
        self.assertEqual(imu2.transport_gaps, [])
        self.assertEqual(len(audio2.samples), 25)
        self.assertEqual(audio2.sync_frame.tolist(), [10, 20])

    def test_resync_that_host_time_confirms_keeps_its_jump(self):
        # a DISCONT jump that matches the 2 s outage stays on one count
        trk = dbrec.imu_track(self.reinit_take(500000 + 14 * 32 + 897, dbrec.IMU_DISCONT)[0])
        self.assertEqual(trk.segments, 1)
        self.assertEqual(int(trk.seq[14 * 32] - trk.seq[14 * 32 - 1]), 898)

    def test_imu_records_outside_the_session_profile_are_left_out(self):
        w = Writer()
        w.rec(dbrec.T_SESSION, session_payload(profile=1))
        ev = dbrec.EVENT_CODES.index("PROFILE") + 1
        w.imu(100, np.tile([1, 0, 0, 0, 0, 0], (4, 1)), 103, T + 3 * 2230)       # still LEVEL: before PROFILE
        w.rec(dbrec.T_EVENT, dbrec.EVENT.pack(T + 9000, ev, 1))
        w.imu(200, np.tile([2, 0, 0, 0, 0, 0], (4, 1)), 203, T + 103 * 2230, dbrec.IMU_DISCONT)
        w.rec(dbrec.T_EVENT, dbrec.EVENT.pack(T + 250000, ev, 0))                # back to LEVEL
        w.imu(304, np.tile([3, 0, 0, 0, 0, 0], (4, 1)), 307, T + 207 * 2230)
        trk = dbrec.imu_track(dbrec.split_takes(dbrec.decode(bytes(w.out))[0])[0])
        self.assertEqual(trk.counts[:, 0].tolist(), [2, 2, 2, 2])
        # a take without PROFILE events keeps every IMU record
        self.assertEqual(len(dbrec.imu_track(self.take((100, 4, 1, False, 0), (104, 4, 0, False, 0))).seq), 8)

    def test_clipping_flags(self):
        w = Writer()
        w.rec(dbrec.T_SESSION, session_payload())
        w.imu(10, [[32767, 0, 0, 0, 0, 0], [0, -32768, 0, 0, 0, 0], [0, 0, 0, 0, 0, -32767], [1, 2, 3, 4, 5, 6]],
              13, T)
        w.imu(14, [[0, 0, 0, 0, 0, 0]], 14, T + 2230)
        trk = dbrec.imu_track(dbrec.split_takes(dbrec.decode(bytes(w.out))[0])[0])
        self.assertEqual(trk.flags.tolist(), [2, 2, 4, 0, 0])

    def test_take_across_the_32_bit_wrap(self):
        trk = dbrec.imu_track(self.take((0xFFFFFFFE, 2, 0, False, 0), (0, 3, 0, False, 0)))
        self.assertEqual(trk.seq.tolist(), [0, 1, 2, 3, 4])
        self.assertTrue((np.diff(trk.t_us) > 0).all())


class HelpersTest(unittest.TestCase):
    def test_fw_parts(self):
        self.assertEqual(dbrec.fw_parts("0.3.0-7-g0e5105e"), ("0.3.0", "0e5105e"))
        self.assertEqual(dbrec.fw_parts("v0.3.0-7-g0e5105e-dirty"), ("0.3.0", "0e5105e-dirty"))
        self.assertEqual(dbrec.fw_parts("0.3.0"), ("0.3.0", ""))
        self.assertEqual(dbrec.fw_parts("0.1.0-g0e5105e"), ("0.1.0", "0e5105e"))       # rec_fw_text()
        self.assertEqual(dbrec.fw_parts("0.1.0-g0e5105e-dirty"), ("0.1.0", "0e5105e-dirty"))
        self.assertEqual(dbrec.fw_parts("0.1.0-dirty"), ("0.1.0", ""))
        self.assertEqual(dbrec.fw_parts("weird"), ("weird", ""))

    def test_lpf_and_axis_map(self):
        self.assertEqual(dbrec.lpf_text(0x51, True), "2.66% of ODR")     # the LEVEL profile
        self.assertEqual(dbrec.lpf_text(0x51, False), "5.39% of ODR")
        self.assertEqual(dbrec.lpf_text(0x00, True), "off")
        self.assertEqual(dbrec.axis_map_text(((0, 1), (1, 1), (2, 1))), "x=+cx,y=+cy,z=+cz")

    def test_cli(self):
        out = _io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(dbrec.main(["info", str(GOLDEN)]), 0)
        self.assertIn("take 42 RAW448", out.getvalue())
        self.assertIn("2 missing", out.getvalue())


class BadSessionTest(TempDirTest):
    """A SESSION with a good CRC but unusable scales or axis map is reported,
    not a crash in build_meta."""

    def stream(self, **fields):
        data = realistic(seconds=1.0)
        recs, _ = dbrec.decode(data)
        take = dbrec.split_takes(recs)[0]
        take.session = dataclasses.replace(take.session, **fields)
        return take

    def test_zero_scales_and_bad_axes_raise(self):
        for fields in ({"accel_lsb_per_g": 0}, {"gyro_lsb_per_dps": 0}, {"axis": ((3, 1), (1, 1), (2, 1))},
                       {"axis": ((0, 1), (0, 1), (2, 1))}, {"axis": ((0, 0), (1, 1), (2, 1))}):
            with self.subTest(**{k: str(v) for k, v in fields.items()}):
                take = self.stream(**fields)
                with self.assertRaises(ValueError):
                    dbrec.export_take(take, dio.recording_dir(self.tmp, RID), recording_id=RID, session_id=SID)

    def test_cli_reports_it(self):
        w = Writer()
        w.rec(dbrec.T_SESSION, session_payload(lsb=0))
        for k in range(4):
            w.imu(100 + 10 * k, np.zeros((10, 6)), 100 + 10 * k + 9, T + 22300 * (k + 1))
        src = self.tmp / "bad.dbrec"
        src.write_bytes(bytes(w.out))
        err = _io.StringIO()
        with contextlib.redirect_stdout(_io.StringIO()), contextlib.redirect_stderr(err):
            self.assertEqual(dbrec.main(["export", str(src), str(self.tmp / "root"), "--recording-id", RID,
                                         "--session-id", SID]), 1)
            self.assertEqual(dbrec.main(["export", str(src), str(self.tmp / "root"), "--take", "5",
                                         "--recording-id", RID, "--session-id", SID]), 1)
        self.assertIn("accel_lsb_per_g 0", err.getvalue())
        self.assertIn("no take 5", err.getvalue())

    def test_axis_map_ok(self):
        self.assertTrue(dbrec.axis_map_ok(((1, -1), (0, 1), (2, 1))))
        self.assertFalse(dbrec.axis_map_ok(((1, -1), (0, 1))))


class CliExportTest(TempDirTest):
    def test_export(self):
        src = self.tmp / "s.dbrec"
        src.write_bytes(realistic(seconds=2.0))
        with contextlib.redirect_stdout(_io.StringIO()):
            self.assertEqual(dbrec.main(["export", str(src), str(self.tmp / "root"), "--recording-id", RID,
                                         "--session-id", SID]), 0)
        dest = dio.recording_dir(self.tmp / "root", RID)
        self.assertEqual(validate_recording(dest).errors, [])
        self.assertEqual(pd.read_csv(dest / "audio_sync.csv").columns.tolist(), ["frame", "t_us"])


if __name__ == "__main__":
    unittest.main()
