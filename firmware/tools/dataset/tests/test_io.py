"""write_recording / read_recording round trip, SI units, calibration, mount, sync fit."""

import importlib.util
import json
import sys
import unittest
from unittest import mock

import numpy as np

import fixtures as F
from dbdata import io as dio


class FirmwareContractTest(unittest.TestCase):
    def test_level_flags_and_modes_match_the_firmware(self):
        # level.csv.gz holds the BLE angle packet's flags and level_mode_t as the device sends them;
        # db_client mirrors both and checks them against the header (dbdata cannot import it at run time)
        fw = F.HERE.parents[2]
        header = fw / "components" / "ble" / "include" / "ble_protocol.h"
        client = fw / "tools" / "ble" / "db_client.py"
        if not (header.is_file() and client.is_file()):
            self.skipTest("not run from the main repo")
        spec = importlib.util.spec_from_file_location("db_client", client)
        db_client = importlib.util.module_from_spec(spec)
        with mock.patch.dict(sys.modules, {"db_client": db_client}):   # its dataclasses look it up
            spec.loader.exec_module(db_client)
        d = db_client._header_defines(str(header))
        self.assertEqual(dio.LEVEL_FLAG_ZERO_SET, d["DB_ANGLE_FLAG_ZERO_SET"])
        self.assertEqual(dio.LEVEL_FLAG_IMU_OK, d["DB_ANGLE_FLAG_IMU_OK"])
        self.assertEqual(dio.LEVEL_MODES, tuple(db_client.MODES))


class RoundTripTest(F.TempDirTest):
    def setUp(self):
        super().setUp()
        self.parts = F.build()
        self.path = F.write(self.tmp / "rec", self.parts)

    def test_files_and_hashes(self):
        meta = dio.load_json(self.path / dio.META_FILE)
        self.assertEqual(sorted(meta["files"]),
                         ["audio.flac", "audio_sync.csv", "imu.csv.gz", "level.csv.gz"])
        for name, info in meta["files"].items():
            self.assertEqual(dio.sha256_file(self.path / name), info["sha256"])
            self.assertEqual((self.path / name).stat().st_size, info["bytes"])
        self.assertNotIn("events.json", meta["files"])  # labels can change without touching data

    def test_raw_counts_flags_and_events_round_trip(self):
        rec = dio.read_recording(self.path)
        imu = self.parts["imu"]
        np.testing.assert_array_equal(rec.imu_raw, imu[list(dio.IMU_AXES)].to_numpy())
        np.testing.assert_array_equal(rec.imu["seq"], imu["seq"])
        np.testing.assert_array_equal(rec.t_us, imu["t_us"])
        np.testing.assert_array_equal(rec.imu["flags"], imu["flags"])
        exp = json.loads(json.dumps(self.parts["events"]))
        self.assertEqual({k: v for k, v in rec.events.items() if k != "holes"},
                         {k: v for k, v in exp.items() if k != "holes"})
        # derive=True filled in the computed per-hole off angles
        self.assertEqual([h["off_angle_deg"] is not None for h in rec.events["holes"]], [True, True])
        np.testing.assert_array_equal(rec.level.to_numpy(), self.parts["level"].to_numpy())
        np.testing.assert_array_equal(rec.audio, self.parts["audio"])
        self.assertEqual(rec.audio.shape[1], 2)
        self.assertEqual(rec.id, F.RID)

    def test_si_units(self):
        rec = dio.read_recording(self.path)
        raw = rec.imu_raw.astype(float)
        np.testing.assert_allclose(rec.imu["ay"], raw[:, 1] / 8192 * dio.G)
        np.testing.assert_allclose(rec.imu["gz"], np.deg2rad(raw[:, 5] / 64))
        self.assertAlmostEqual(float(rec.imu["ay"].mean()), dio.G, delta=0.01)

    def test_calibration_and_mount(self):
        im = self.parts["meta"]["imu"]
        cal = dio.read_recording(self.path, calibrate=True)
        raw = cal.imu_raw.astype(float)
        exp_ax = (raw[:, 0] / 8192 - 0.004) * 1.002 * dio.G
        np.testing.assert_allclose(cal.imu["ax"], exp_ax)
        np.testing.assert_allclose(cal.imu["gx"], np.deg2rad(raw[:, 3] / 64) - 0.0011)
        # the mount rotation takes the measured drill axis to -Z
        r = dio.rotation_to_minus_z(im["mount"]["drill_axis"])
        a = np.asarray(im["mount"]["drill_axis"]) / np.linalg.norm(im["mount"]["drill_axis"])
        np.testing.assert_allclose(r @ a, [0, 0, -1], atol=1e-12)
        np.testing.assert_allclose(r @ r.T, np.eye(3), atol=1e-12)
        np.testing.assert_allclose(dio.rotation_to_minus_z([0, 0, 1]) @ [0, 0, 1], [0, 0, -1])
        mounted = dio.read_recording(self.path, mount=True)
        plain = dio.read_recording(self.path)
        np.testing.assert_allclose(np.linalg.norm(mounted.imu[["ax", "ay", "az"]], axis=1),
                                   np.linalg.norm(plain.imu[["ax", "ay", "az"]], axis=1))

    def test_sync_fit_recovers_the_audio_clock(self):
        rec = dio.read_recording(self.path)
        self.assertAlmostEqual(rec.sync.rate_hz, 16000.9, delta=0.05)
        self.assertLess(rec.sync.residual_rms_us, 100)
        f = np.array([0, 16000, 80000])
        np.testing.assert_allclose(rec.sync.frame_of(rec.sync.t_of(f)), f)

    def test_writes_are_deterministic(self):
        again = F.write(self.tmp / "rec2", F.build())
        a = dio.load_json(self.path / dio.META_FILE)["files"]
        b = dio.load_json(again / dio.META_FILE)["files"]
        self.assertEqual(a, b)

    def test_without_audio_and_wav_fallback(self):
        p = F.write(self.tmp / "noaudio", F.build(audio=False))
        rec = dio.read_recording(p)
        self.assertIsNone(rec.audio)
        self.assertIsNone(rec.sync)
        self.assertEqual(sorted(rec.meta["files"]), ["imu.csv.gz", "level.csv.gz"])

        parts = F.build()
        parts["meta"]["audio"].update(codec="wav", file="audio.wav")
        p = F.write(self.tmp / "wav", parts)
        np.testing.assert_array_equal(dio.read_recording(p).audio, parts["audio"])

    def test_write_rejects_bad_input(self):
        parts = F.build()
        bad = parts["imu"].copy()
        bad.loc[0, "ax"] = 40000
        with self.assertRaises(ValueError):
            F.write(self.tmp / "x", dict(parts, imu=bad))
        with self.assertRaises(ValueError):
            F.write(self.tmp / "y", dict(parts, audio=None))

    def test_recording_paths(self):
        self.assertEqual(dio.recording_relpath(F.RID), "recordings/2026/2026-10/" + F.RID)
        with self.assertRaises(ValueError):
            dio.parse_recording_id("2026-10-26_x")


if __name__ == "__main__":
    unittest.main()
