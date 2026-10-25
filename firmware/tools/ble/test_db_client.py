#!/usr/bin/env python3
"""Tests for db_client.py without Bluetooth: python3 -m unittest test_db_client

The OTA sender runs against a simulated device that follows ota_proto.h
(ACK every ack_every chunks and after the last one, one NACK per gap, old seq
ignored, a lost ACK repeated for resent chunks) and can drop writes and ACKs,
as a congested link would.
"""

import asyncio
import hashlib
import random
import struct
import unittest
from types import SimpleNamespace

import db_client as dc


class FakeOtaDevice:
    def __init__(self, max_chunk=20, ack_every=4, drop=None, finish_status=0,
                 accept_max=None, mtu_size=None, lose_ack=None):
        self.max_chunk = max_chunk          # announced in the START reply
        # Longest payload actually accepted (a link at a smaller MTU, or a
        # device whose limit shrank); longer chunks get NACK BAD_REQUEST.
        self.accept_max = max_chunk if accept_max is None else accept_max
        if mtu_size is not None:
            self.mtu_size = mtu_size        # BleakClient.mtu_size
        self.bad_requests = 0
        self.ack_every = ack_every
        self.drop = drop or (lambda seq, n: False)
        # An ACK sent but never delivered (a full indication queue on the device).
        self.lose_ack = lose_ack or (lambda next_seq, received: False)
        self.finish_status = finish_status
        self.cb = None
        self.reset()

    def reset(self):
        self.active = False
        self.size = 0
        self.sha = b""
        self.buf = bytearray()
        self.next_seq = 0
        self.since_ack = 0
        self.nack_sent = False
        self.dup_seq = None     # seq of the last retransmission since an accepted chunk
        self.writes_per_seq = {}
        self.aborted = False

    # BleakClient surface used by ota_transfer
    async def start_notify(self, uuid, cb):
        assert uuid == dc.CHR_OTA_CTRL
        self.cb = cb

    def _send(self, msg: bytes):
        asyncio.get_running_loop().call_soon(self.cb, None, bytearray(msg))

    async def write_gatt_char(self, uuid, data, response=False):
        data = bytes(data)
        if uuid == dc.CHR_OTA_CTRL:
            assert response
            self._ctrl(data)
        elif uuid == dc.CHR_OTA_DATA:
            assert not response
            self._data(data)
        else:
            raise AssertionError(uuid)
        await asyncio.sleep(0)

    def _ctrl(self, d: bytes):
        op = d[0]
        if op == dc.OTA_OP_START:
            assert len(d) == 37
            self.reset()
            self.size = struct.unpack_from("<I", d, 1)[0]
            self.sha = d[5:37]
            self.active = True
            self._send(bytes([0x80, op, 0]) + struct.pack("<HH", self.max_chunk, self.ack_every))
        elif op == dc.OTA_OP_FINISH:
            ok = (len(self.buf) == self.size and hashlib.sha256(self.buf).digest() == self.sha)
            st = self.finish_status if ok else dc.OTA_STATUS.index("HASH_MISMATCH")
            self._send(bytes([0x80, op, st]))
        elif op == dc.OTA_OP_ABORT:
            self.aborted = True
            self.active = False
            self._send(bytes([0x80, op, 0]))

    def _data(self, d: bytes):
        seq = struct.unpack_from("<H", d)[0]
        n = self.writes_per_seq.get(seq, 0)
        if self.drop(seq, n):
            self.writes_per_seq[seq] = n + 1
            return
        if not self.active:
            return
        diff = (seq - self.next_seq) & 0xFFFF
        if diff >= 0x8000:
            # Retransmission of an accepted chunk. Right after an ACK the client
            # lost it: the ACK goes again, once per resend round.
            if self.since_ack == 0:
                step = None if self.dup_seq is None else (seq - self.dup_seq) & 0xFFFF
                if step is None or step == 0 or step >= 0x8000:
                    self._ack()
                self.dup_seq = seq
            return
        if diff != 0:
            if not self.nack_sent:
                self.nack_sent = True
                self._send(bytes([0x82]) + struct.pack("<HB", self.next_seq, 7))
            return
        payload = d[2:]
        assert len(payload) >= 1
        if len(payload) > self.accept_max:
            self.bad_requests += 1
            self._send(bytes([0x82]) + struct.pack("<HB", self.next_seq, 1))
            return
        self.writes_per_seq[seq] = n + 1
        self.buf += payload
        self.next_seq = (self.next_seq + 1) & 0xFFFF
        self.nack_sent = False
        self.dup_seq = None
        self.since_ack += 1
        if self.since_ack >= self.ack_every or len(self.buf) >= self.size:
            self.since_ack = 0
            self._ack()

    def _ack(self):
        if not self.lose_ack(self.next_seq, len(self.buf)):
            self._send(bytes([0x81]) + struct.pack("<HI", self.next_seq, len(self.buf)))


def run(dev, image, **kw):
    asyncio.run(dc.ota_transfer(dev, image, **kw))


class OtaTransferTest(unittest.TestCase):
    def image(self, n):
        rnd = random.Random(n)
        return bytes(rnd.getrandbits(8) for _ in range(n))

    def test_clean_transfer(self):
        dev = FakeOtaDevice()
        img = self.image(1000)
        run(dev, img)
        self.assertEqual(bytes(dev.buf), img)

    def test_clean_transfer_never_stalls(self):
        # A stall wait after the final ACK would add stall_s before FINISH.
        for size in (1, 20, 100, 1000):
            dev = FakeOtaDevice()
            t0 = asyncio.run(self._timed(dev, self.image(size)))
            self.assertLess(t0, 1.0, f"size {size}")

    async def _timed(self, dev, img):
        loop = asyncio.get_running_loop()
        t = loop.time()
        await dc.ota_transfer(dev, img, stall_s=2.0, max_stalls=0)
        return loop.time() - t

    def test_exact_multiple_of_chunk(self):
        dev = FakeOtaDevice(max_chunk=25, ack_every=4)
        img = self.image(25 * 8)
        run(dev, img)
        self.assertEqual(bytes(dev.buf), img)

    def test_single_byte_image(self):
        dev = FakeOtaDevice()
        run(dev, b"\xe9")
        self.assertEqual(bytes(dev.buf), b"\xe9")

    def test_dropped_chunks_are_resent_after_nack(self):
        dev = FakeOtaDevice(drop=lambda seq, n: seq in (3, 17, 30) and n == 0)
        img = self.image(1000)
        run(dev, img)
        self.assertEqual(bytes(dev.buf), img)

    def test_dropped_last_chunk_recovers_by_stall_timeout(self):
        img = self.image(205)   # 11 chunks of 20; the last one gets no NACK
        last = (len(img) + 19) // 20 - 1
        dev = FakeOtaDevice(drop=lambda seq, n: seq == last and n == 0)
        run(dev, img, stall_s=0.05)
        self.assertEqual(bytes(dev.buf), img)

    def test_lost_final_ack_recovers_by_stall_timeout(self):
        # Every chunk arrived, the final ACK (and its first repeat) did not.
        img = self.image(205)
        lost = []
        dev = FakeOtaDevice(lose_ack=lambda seq, rx: rx == len(img) and len(lost) < 2
                            and not lost.append(rx))
        run(dev, img, stall_s=0.05)
        self.assertEqual(bytes(dev.buf), img)
        self.assertEqual(len(lost), 2)

    def test_random_loss(self):
        rnd = random.Random(7)
        dev = FakeOtaDevice(max_chunk=16, ack_every=8, drop=lambda seq, n: rnd.random() < 0.1)
        img = self.image(5000)
        run(dev, img, stall_s=0.05, max_stalls=50)
        self.assertEqual(bytes(dev.buf), img)

    def test_seq_wraps(self):
        dev = FakeOtaDevice(max_chunk=1, ack_every=64,
                            drop=lambda seq, n: seq in (0xFFFF, 2) and n == 0)
        img = self.image(70000)
        run(dev, img, stall_s=0.05)
        self.assertEqual(bytes(dev.buf), img)

    def test_finish_failure_raises(self):
        dev = FakeOtaDevice(finish_status=dc.OTA_STATUS.index("FLASH_ERROR"))
        with self.assertRaisesRegex(dc.OtaError, "FLASH_ERROR"):
            run(dev, self.image(100))

    def test_chunk_clamped_to_link_mtu(self):
        # START announces a chunk size the current link cannot carry (e.g.
        # left over from a previous connection): the client uses MTU - 5.
        dev = FakeOtaDevice(max_chunk=242, accept_max=18, mtu_size=23)
        img = self.image(1000)
        run(dev, img)
        self.assertEqual(bytes(dev.buf), img)
        self.assertEqual(dev.bad_requests, 0)

    def test_repeated_bad_request_aborts(self):
        # A chunk the device keeps rejecting must not be resent forever.
        dev = FakeOtaDevice(max_chunk=100, accept_max=50)
        with self.assertRaisesRegex(dc.OtaError, "rejects chunk"):
            run(dev, self.image(1000), stall_s=0.05)
        self.assertLessEqual(dev.bad_requests, dc.OTA_MAX_BAD_REQUESTS + 2)

    def test_window_never_exceeds_two_ack_periods(self):
        dev = FakeOtaDevice(max_chunk=10, ack_every=4)
        peak = [0]
        orig = dev._data

        def watch(d):
            seq = struct.unpack_from("<H", d)[0]
            ahead = (seq - dev.next_seq) & 0xFFFF
            peak[0] = max(peak[0], ahead + 1)
            orig(d)

        dev._data = watch
        run(dev, self.image(2000))
        self.assertLessEqual(peak[0], 2 * dev.ack_every)


class CodecTest(unittest.TestCase):
    def test_self_check(self):
        self.assertTrue(dc.self_check(verbose=False))

    def test_parse_config_value(self):
        self.assertIs(dc.parse_config_value("true"), True)
        self.assertEqual(dc.parse_config_value("20"), 20)
        self.assertIsInstance(dc.parse_config_value("20"), int)
        self.assertEqual(dc.parse_config_value("0.25"), 0.25)
        self.assertEqual(dc.parse_config_value("floor"), "floor")

    def test_describe_ble_error(self):
        self.assertIn("value out of range",
                      dc.describe_ble_error(Exception("ATT error: 0x82 (Unknown)")))

    def test_is_drillbalancer(self):
        def adv(name=None, uuids=()):
            return SimpleNamespace(local_name=name, service_uuids=list(uuids))
        dev = SimpleNamespace(name=None)
        self.assertTrue(dc.is_drillbalancer(dev, adv("DrillBalancer-1A2B")))
        # The UUID is only in the scan response; BlueZ may report it upper case.
        self.assertTrue(dc.is_drillbalancer(dev, adv(uuids=[dc.SVC_MAIN.upper()])))
        self.assertTrue(dc.is_drillbalancer(SimpleNamespace(name="DrillBalancer-0001"), adv()))
        self.assertFalse(dc.is_drillbalancer(dev, adv("Other", [dc.SVC_OTA])))
        self.assertFalse(dc.is_drillbalancer(dev, adv()))


if __name__ == "__main__":
    unittest.main()
