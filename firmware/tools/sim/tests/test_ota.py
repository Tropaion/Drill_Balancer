"""OTA through the firmware's ota_proto with a file sink, driven by
db_client.ota_transfer() (the reference client's encoders and decoders)."""

import asyncio
import contextlib
import hashlib
import io
import json
import unittest

from simtest import (FIRMWARE_IMAGE, DeviceTest, SimClient, dc, make_image, run_ota,
                     set_image_version)
from dbsim import image_version


def acks(client):
    return [dc.decode_ota_msg(m) for m in client.indications
            if dc.decode_ota_msg(m)["type"] == "ack"]


@unittest.skipUnless(FIRMWARE_IMAGE.exists(), "needs firmware/build/drillbalancer.bin")
class FirmwareImageOtaTest(DeviceTest):
    def setUp(self):
        super().setUp()
        self.image = FIRMWARE_IMAGE.read_bytes()

    def reboot_done(self):
        self.settle(600)                     # REBOOT_DELAY_MS after the confirmed reply
        self.assertEqual(self.dev.state, "booting")
        self.settle(1500)
        self.assertEqual(self.dev.state, "running")

    def test_full_image(self):
        client = SimClient(self.dev, mtu=247)
        run_ota(client, self.image)
        start = dc.decode_ota_msg(client.indications[0])
        self.assertEqual((start["max_chunk"], start["ack_every"]), (242, 16))
        self.assertEqual(acks(client)[-1]["received"], len(self.image))
        self.assertEqual(dc.decode_ota_msg(client.indications[-1]),
                         {"type": "reply", "op": dc.OTA_OP_FINISH, "status": 0})
        self.assertEqual(self.snap()["ota_state"], 2)          # DONE, reboot pending
        self.assertFalse(self.dev.advertising)
        self.reboot_done()
        self.assertEqual(self.dev.ota_image_path.read_bytes(), self.image)
        self.assertEqual(self.dev.fw_version, image_version(FIRMWARE_IMAGE))

    def test_new_version_after_reboot(self):
        image = set_image_version(self.image, "9.9.9-sim")
        run_ota(SimClient(self.dev), image)
        self.assertNotEqual(self.dev.fw_version, "9.9.9-sim")  # still the old image
        self.reboot_done()
        self.assertEqual(self.dev.read_char("fw_revision"), b"9.9.9-sim")
        self.assertEqual(self.names("boot")[-1][1], {"version": "9.9.9-sim", "reason": "reboot"})
        state = json.loads(self.dev.state_path.read_text())
        self.assertEqual(state["fw_version"], "9.9.9-sim")
        self.assertNotIn("fw_pending", state)
        self.dev = self.new_device()                           # a later run
        self.assertEqual(self.dev.fw_version, "9.9.9-sim")

    def test_dropped_acks_are_resent(self):
        client = SimClient(self.dev, mtu=247)
        # Let the START reply and two ACKs through, then lose two ACKs.
        self.dev.drop_ota_indications(2, after=3)
        run_ota(client, self.image, stall_s=0.2)
        a = acks(client)
        seqs = [m["next_seq"] for m in a]
        # 48 and 64 were lost; 64 came again for the client's retransmission.
        self.assertEqual(seqs[:4], [16, 32, 64, 80])
        self.assertEqual(len(seqs), len(set(seqs)), "no duplicate in what arrived")
        self.assertEqual(a[-1]["received"], len(self.image))
        logs = [d["msg"] for _, n, d in self.events if n == "log"]
        self.assertEqual(sum("dropped (injected)" in m for m in logs), 2)
        self.reboot_done()
        self.assertEqual(self.dev.ota_image_path.read_bytes(), self.image)

    def test_mtu_23_chunks(self):
        client = SimClient(self.dev, mtu=23)
        image = make_image(payload=8192)
        run_ota(client, image)
        start = dc.decode_ota_msg(client.indications[0])
        self.assertEqual(start["max_chunk"], 18)


class OtaErrorTest(DeviceTest):
    def expect_failure(self, image, text, **kw):
        client = SimClient(self.dev)
        with self.assertRaises(dc.OtaError) as cm:
            run_ota(client, image, **kw)
        self.assertIn(text, str(cm.exception))
        self.assertEqual(self.snap()["ota_state"], 0)
        self.assertFalse(self.dev.ota_image_path.exists())
        self.assertEqual(list(self.state_dir.glob("*.part")), [])
        return client

    def test_wrong_project(self):
        self.expect_failure(make_image(project="otherproject"), "FLASH_ERROR")

    def test_bad_checksum(self):
        img = bytearray(make_image())
        img[-33] ^= 0x55                                       # the checksum byte
        self.expect_failure(bytes(img), "FINISH failed: FLASH_ERROR")

    def test_bad_appended_hash(self):
        img = bytearray(make_image())
        img[-1] ^= 0x01
        self.expect_failure(bytes(img), "FINISH failed: FLASH_ERROR")

    def test_flash_write_failure(self):
        self.dev.flash_fail = True
        self.expect_failure(make_image(), "FLASH_ERROR")

    def test_hash_mismatch_and_bad_requests(self):
        client = SimClient(self.dev)
        self.dev.subscribe_char("ota_ctrl", True)
        img = make_image(payload=1024)
        w = lambda c, d: self.dev.write_char(c, d)
        self.assertEqual(w("ota_ctrl", b""), 0x0D)
        self.assertEqual(w("ota_ctrl", b"\x02"), 0)            # FINISH without START
        self.assertEqual(w("ota_ctrl", dc.encode_ota_start(len(img), bytes(32))), 0)
        seq = 0
        for off in range(0, len(img), 200):
            self.assertEqual(w("ota_data", dc.encode_ota_data(seq, img[off:off + 200])), 0)
            seq += 1
        self.assertEqual(w("ota_ctrl", b"\x02"), 0)
        msgs = [dc.decode_ota_msg(m) for m in client.indications]
        self.assertEqual(msgs[0], {"type": "reply", "op": 2, "status": 2})        # BAD_STATE
        self.assertEqual(msgs[-1], {"type": "reply", "op": 2, "status": 6})       # HASH_MISMATCH

    def test_disconnect_aborts(self):
        SimClient(self.dev)
        self.dev.subscribe_char("ota_ctrl", True)
        img = make_image(payload=4096)
        self.dev.write_char("ota_ctrl", dc.encode_ota_start(len(img), hashlib.sha256(img).digest()))
        self.dev.write_char("ota_data", dc.encode_ota_data(0, img[:200]))
        self.assertTrue(self.snap()["ota_active"])
        self.dev.disconnect()
        snap = self.snap()
        self.assertEqual((snap["ota_state"], snap["ota_active"]), (0, False))
        self.assertEqual(list(self.state_dir.glob("*.part")), [])

    def test_timeout(self):
        client = SimClient(self.dev)
        self.dev.subscribe_char("ota_ctrl", True)
        img = make_image(payload=4096)
        self.dev.write_char("ota_ctrl", dc.encode_ota_start(len(img), hashlib.sha256(img).digest()))
        self.settle(11_000)                                    # TIMEOUT_MS without data
        self.assertEqual(dc.decode_ota_msg(client.indications[-1]),
                         {"type": "nack", "expected_seq": 0, "status": 8})
        self.assertFalse(self.snap()["ota_active"])

    def test_slow_flash_loses_writes_and_recovers(self):
        self.dev.ota_write_delay_ms = 6
        image = make_image("1.2.3-slow", payload=40_000)
        client = SimClient(self.dev)

        async def go():
            task = asyncio.create_task(self.dev.run(speed=4))
            try:
                with contextlib.redirect_stderr(io.StringIO()):
                    await dc.ota_transfer(client, image, stall_s=0.3)
            finally:
                self.dev.stop()
                await task
        asyncio.run(go())
        logs = [d["msg"] for _, n, d in self.events if n == "log"]
        self.assertTrue(any("ACL buffers full" in m for m in logs))
        self.assertTrue(any(dc.decode_ota_msg(m)["type"] == "nack" for m in client.indications))
        self.settle(2500)
        self.assertEqual(self.dev.fw_version, "1.2.3-slow")


if __name__ == "__main__":
    unittest.main()
