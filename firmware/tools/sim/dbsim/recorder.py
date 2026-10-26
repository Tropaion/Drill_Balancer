"""The recorder's network side for the simulator: the stream endpoint.

On the board the recorder opens a Wi-Fi soft-AP and a TCP server at
192.168.4.1:47100 (rec_wifi.c). The simulator serves the same stream from
the PC instead, on its LAN address (--rec-host, found without changing any
network setting), and rec_state says so with DB_REC_FLAG_NO_AP, an empty
SSID and that address: the app connects over the network it is on. With
--rec-ap the PC opens a hotspot with the session's credentials instead
(NetworkManager's `nmcli device wifi hotspot`), to try the app's soft-AP
join without the board.

RecorderServer is the device's rec_endpoint (device.py): START_AP ->
ap_start(), STOP_AP -> ap_stop(). One client at a time; it must send HELLO
(the 16-byte session token from rec_state, then u16 REC_STREAM_VERSION)
within HELLO_TIMEOUT_S, or it is closed. Then the server sends whatever the
device's sender takes out of the ring (DbSimDevice.rec_pop), as fast as the
client takes it. The bytes on their way are bounded as on the board: at
most TCP_SND_BUF of them wait on the PC side (the socket's unsent queue
plus asyncio's buffer), so a client that stops reading backs the stream up
into the ring once its own receive buffer and those bytes are full (the
board's LWIP_TCP_SND_BUF; the app's 256 KB receive buffer holds about
3.5 s of stream on either). A send that waits for room for SEND_STALL_S,
an EOF or a reset is CLIENT_LOST. The socket is then closed at once (the
board closes it before RING_RESET): what the sender had taken out of the
ring is dropped, only the socket's own queue still goes out before the FIN,
as on lwIP. stall() and drop_client() inject the faults the web panel
offers.

The device's timings (HELLO_TIMEOUT_S, SEND_STALL_S, STOP_AP_S, LINGER_S, a stall)
run on the device's clock, DbSimDevice.now_ms, which dev.run(speed) runs at
speed times the wall clock: they are the board's timeouts. What only the
PC does is on the wall clock: CLOSE_S, the socket's close, and the kB/s
the panel shows.
"""

from __future__ import annotations

import asyncio
import base64
import collections
import fcntl
import ipaddress
import secrets
import socket
import struct
import subprocess
import termios
import time

from .wire import dc

STREAM_PORT = dc.REC_STREAM_PORT
HELLO_LEN = dc.REC_HELLO_LEN
HELLO_TIMEOUT_S = 5.0
SEND_STALL_S = 10.0          # firmware_sim.md: a send blocked > 10 s loses the client
STOP_AP_S = 0.2              # REC_ACT_STOP_AP: what is queued goes out within 200 ms
LINGER_S = 1.0               # REC_WIFI_LINGER_MS: then the client gets this to read to the FIN
CHUNK = 16384                # one send()'s worth (the board peeks a TCP send buffer)
TCP_SND_BUF = 23360          # the board's LWIP_TCP_SND_BUF_DEFAULT (sdkconfig recorder.defaults)
SNDBUF = 32768               # the kernel's part stays small where SIOCOUTQ is missing
CLOSE_S = 1.0                # a closed connection is gone within this (else it is aborted)
POLL_S = 0.005
AP_CHANNEL = 6               # rec_wifi.c (TODO(hw) there)
HOTSPOT_CON = "dbsim-rec"    # the NetworkManager connection --rec-ap creates


def lan_address() -> str:
    """The address this PC uses towards the LAN: the source address the kernel
    picks for a route to a documentation address (a UDP connect sends nothing
    and changes nothing). 127.0.0.1 without a route."""
    for probe in ("192.0.2.1", "198.51.100.1"):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.connect((probe, 9))
                ip = s.getsockname()[0]
        except OSError:
            continue
        if ip and not ipaddress.ip_address(ip).is_loopback and ip != "0.0.0.0":
            return ip
    return "127.0.0.1"


def ap_credentials(mac: str) -> tuple[str, str]:
    """rec_wifi.c: SSID DB-<mac4>-<rand4>, a 20-character base32 passphrase."""
    b = mac.upper().split(":")
    rand4 = secrets.token_hex(2).upper()
    password = base64.b32encode(secrets.token_bytes(15)).decode()[:20]
    return f"DB-{b[4]}{b[5]}-{rand4}", password


def unsent_bytes(writer: asyncio.StreamWriter) -> int:
    """What the client has not taken yet on the PC side: asyncio's buffer plus
    the socket's send queue (SIOCOUTQ, Linux; 0 where it is missing)."""
    n = writer.transport.get_write_buffer_size()
    sock = writer.get_extra_info("socket")
    if sock is not None:
        try:
            n += struct.unpack("i", fcntl.ioctl(sock.fileno(), termios.TIOCOUTQ, b"\0" * 4))[0]
        except OSError:
            pass
    return n


class RecorderServer:
    def __init__(self, dev, host: str | None = None, port: int = STREAM_PORT, ap: bool = False,
                 ap_ifname: str | None = None):
        self.dev = dev
        self.host = host            # None: the LAN address
        self.port = port
        self.ap = ap
        self.ap_ifname = ap_ifname
        self.announce_ip = None     # what rec_state says
        self._server = None
        self._client = None         # (reader, writer) after a good HELLO
        self._pending = b""         # taken out of the ring, not yet sent (the board's peek)
        self._drop = None           # asyncio.Event: drop the client now
        self._closing = False       # ap_stop(): the session ends, not the client
        self._stall = None          # (from_ms, until_ms) on the device clock
        self._tasks = set()
        self._hotspot = False
        self.sent_bytes = 0
        self.clients = 0
        self.last_peer = None
        self._rate = collections.deque()   # (monotonic, sent_bytes) for kB/s

    # ---- lifecycle ----------------------------------------------------------------------
    async def start(self):
        """Listens (LAN mode) and becomes the device's endpoint."""
        if not self.ap:
            await self._listen(self.host or lan_address())
        self.dev.rec_endpoint = self
        return self

    async def _listen(self, host: str):
        bind = host
        self._server = await asyncio.start_server(self._on_client, bind, self.port,
                                                  reuse_address=True)
        # rec_state carries an IPv4 address: a name (localhost) is served on
        # its IPv4 socket, an IPv6 address is refused here rather than at OPEN.
        v4 = [s.getsockname() for s in self._server.sockets if s.family == socket.AF_INET]
        if not v4:
            self._server.close()
            await self._server.wait_closed()
            self._server = None
            raise ValueError(f"the stream address must be IPv4 (rec_state carries 4 bytes): {host}")
        ip, self.port = v4[0][:2]
        self.announce_ip = lan_address() if ip in ("0.0.0.0", "") else ip

    async def stop(self):
        if self.dev.rec_endpoint is self:
            self.dev.rec_endpoint = None
        await self._close_client(lost=False, now=True)
        # the connections' handlers too: one waiting for its HELLO on a device
        # clock that no longer runs would keep wait_closed() waiting
        for t in list(self._tasks):
            t.cancel()
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()
            self._server = None
        if self._hotspot:
            await self._hotspot_down()

    def _spawn(self, coro):
        t = asyncio.get_running_loop().create_task(coro)
        self._tasks.add(t)
        t.add_done_callback(self._tasks.discard)
        return t

    # ---- the device's endpoint (device.py rec_endpoint) -----------------------------------
    def ap_start(self):
        """START_AP: announce the endpoint (LAN) or bring the hotspot up first."""
        self._closing = False
        if not self.ap:
            self.dev.rec_endpoint_up(self.announce_ip, self.port, no_ap=True)
            return
        self._spawn(self._ap_start())

    def ap_stop(self):
        """STOP_AP: what is queued goes out (<= 200 ms), the stream ends (FIN) and the
        client gets LINGER_S to read up to it and close, then the endpoint is down."""
        self._closing = True
        self._spawn(self._ap_stop())

    async def _ap_start(self):
        ssid, password = ap_credentials(self.dev.mac)
        try:
            ip = await self._hotspot_up(ssid, password)
            await self._listen(ip)
        except (OSError, RuntimeError, subprocess.SubprocessError) as e:
            self.dev.emit("log", {"level": "E", "msg": f"hotspot failed: {e}", "source": "sim"})
            self.dev.rec_endpoint_failed()
            return
        self.dev.rec_endpoint_up(self.announce_ip, self.port, no_ap=False, channel=AP_CHANNEL,
                                 ssid=ssid, password=password)

    async def _ap_stop(self):
        end = self._now_ms() + STOP_AP_S * 1000
        if self._client is not None:
            w = self._client[1]
            while self._now_ms() < end:
                data, self._pending = self._pending or self.dev.rec_pop(CHUNK), b""
                if not data:
                    break
                w.write(data)
                self._count(len(data))
                try:
                    await self._within(w.drain(), (end - self._now_ms()) / 1000)
                except (asyncio.TimeoutError, ConnectionError):
                    break
        await self._finish_stream()
        await self._close_client(lost=False)
        if self.ap:
            if self._server is not None:
                self._server.close()
                self._server = None
            await self._hotspot_down()
        self.dev.rec_endpoint_down()

    async def _finish_stream(self):
        """rec_wifi.c finish_stream(): FIN after what is queued. The board's lwIP would
        lose what it still holds if Wi-Fi went down right after close(), so the client
        gets LINGER_S (device time) to read up to the FIN and close (its EOF ends
        _serve(), which closes the connection). The PC's kernel would deliver it anyway;
        this keeps the board's timing. A device clock that does not move (halted, or a
        test's) is capped by CLOSE_S of wall time, as stop()."""
        if self._client is None:
            return
        w = self._client[1]
        try:
            if not w.can_write_eof():
                return
            w.write_eof()
        except (OSError, RuntimeError):
            return
        end = self._now_ms() + LINGER_S * 1000
        wall_end = time.monotonic() + max(LINGER_S, CLOSE_S)
        while (self._client is not None and self._client[1] is w and self._now_ms() < end
               and time.monotonic() < wall_end):
            await asyncio.sleep(POLL_S)

    # ---- hotspot (--rec-ap) ---------------------------------------------------------------
    async def _nmcli(self, *args) -> str:
        proc = await asyncio.create_subprocess_exec(
            "nmcli", *args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        out, err = await proc.communicate()
        if proc.returncode != 0:
            raise RuntimeError(f"nmcli {' '.join(args[:3])}: {err.decode().strip()}")
        return out.decode()

    async def _hotspot_up(self, ssid: str, password: str) -> str:
        args = ["device", "wifi", "hotspot", "con-name", HOTSPOT_CON, "ssid", ssid, "password",
                password, "band", "bg", "channel", str(AP_CHANNEL)]
        if self.ap_ifname:
            args[3:3] = ["ifname", self.ap_ifname]
        await self._nmcli(*args)
        self._hotspot = True
        # NetworkManager's shared mode: the PC is the hotspot's gateway.
        out = await self._nmcli("-g", "IP4.ADDRESS", "connection", "show", HOTSPOT_CON)
        addr = out.strip().split("|")[0].split("/")[0]
        return addr or "10.42.0.1"

    async def _hotspot_down(self):
        self._hotspot = False
        try:
            await self._nmcli("connection", "down", HOTSPOT_CON)
        except (OSError, RuntimeError):
            pass

    # ---- faults (web panel) -------------------------------------------------------------
    def stall(self, seconds: float):
        """The link stops taking data for that long (a Wi-Fi stall, device
        seconds): nothing is sent, the ring fills. A client whose sends it
        blocks for SEND_STALL_S or more is lost then."""
        now = self._now_ms()
        self._stall = (now, now + round(seconds * 1000))

    # ---- the device's clock ---------------------------------------------------------------
    def _now_ms(self) -> int:
        return self.dev.now_ms

    async def _within(self, aw, seconds: float):
        """await aw for at most `seconds` of device time; asyncio.TimeoutError after."""
        t = asyncio.ensure_future(aw)
        end = self._now_ms() + seconds * 1000
        try:
            while not t.done():
                if self._now_ms() >= end:
                    raise asyncio.TimeoutError
                await asyncio.wait({t}, timeout=POLL_S)
            return t.result()
        finally:
            t.cancel()

    def drop_client(self) -> bool:
        """Closes the client's connection (the phone walked away)."""
        if self._client is None or self._drop is None:
            return False
        self._drop.set()
        return True

    # ---- the client ---------------------------------------------------------------------
    async def _on_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        me = asyncio.current_task()
        self._tasks.add(me)
        try:
            await self._serve(reader, writer)
        finally:
            self._tasks.discard(me)

    async def _serve(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        peer = writer.get_extra_info("peername")
        if self._client is not None or self._closing:
            writer.close()          # one client per session (max_connection 1 on the board)
            return
        try:
            hello = await self._within(reader.readexactly(HELLO_LEN), HELLO_TIMEOUT_S)
        except (asyncio.TimeoutError, asyncio.IncompleteReadError, ConnectionError, asyncio.CancelledError):
            writer.close()
            return
        if self._client is not None or not self.dev.rec_hello(hello):
            writer.close()
            return
        # The send loop keeps at most TCP_SND_BUF unsent (the board's buffer);
        # a small kernel buffer keeps that near it where SIOCOUTQ is missing.
        sock = writer.get_extra_info("socket")
        if sock is not None:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, SNDBUF)
        writer.transport.set_write_buffer_limits(high=CHUNK)
        self._client = (reader, writer)
        self._pending = b""
        self._drop = asyncio.Event()
        self.clients += 1
        self.last_peer = f"{peer[0]}:{peer[1]}" if peer else None
        lost = True
        sender = asyncio.ensure_future(self._send_loop(writer))
        eof = asyncio.ensure_future(self._wait_eof(reader))
        drop = asyncio.ensure_future(self._drop.wait())
        try:
            await asyncio.wait({sender, eof, drop}, return_when=asyncio.FIRST_COMPLETED)
            lost = not self._closing
        except asyncio.CancelledError:
            lost = False
        finally:
            for t in (sender, eof, drop):
                t.cancel()
            if self._client is not None and self._client[1] is writer:
                await self._close_client(lost=lost, now=True)

    async def _wait_eof(self, reader):
        # After HELLO only the device sends: anything else is ignored, EOF ends it.
        try:
            while await reader.read(4096):
                pass
        except ConnectionError:
            pass

    async def _send_loop(self, writer):
        start = self._now_ms()
        blocked = None                         # since when a send waits for room (device ms)
        while True:
            now = self._now_ms()
            stall_ms = SEND_STALL_S * 1000
            if self._closing:
                await asyncio.sleep(POLL_S)    # _ap_stop() sends the rest
                continue
            if self._stall is not None:
                # A stall blocks this client's sends from its start (or the client's) to its
                # end: SEND_STALL_S or more of that loses the client, decided by the stall's
                # length, not by whether a poll lands before it ends.
                b0, b1 = max(self._stall[0], start), self._stall[1]
                if b1 - b0 >= stall_ms and now >= b0 + stall_ms:
                    return
                if now < b1:
                    await asyncio.sleep(POLL_S)
                    continue
            if not self._pending:
                self._pending = self.dev.rec_pop(CHUNK)
                if not self._pending:
                    blocked = None
                    await asyncio.sleep(POLL_S)
                    continue
            room = TCP_SND_BUF - unsent_bytes(writer)
            if room <= 0:
                if blocked is None:
                    blocked = now
                elif now - blocked >= stall_ms:
                    return
                await asyncio.sleep(POLL_S)
                continue
            blocked = None
            data, self._pending = self._pending[:room], self._pending[room:]
            writer.write(data)
            self._count(len(data))
            try:
                await self._within(writer.drain(), SEND_STALL_S)
            except (asyncio.TimeoutError, ConnectionError):
                return

    async def _close_client(self, lost: bool, now: bool = False):
        """Closes the client's connection: at once (now, or a lost client),
        dropping what asyncio still holds, else once that went out (at most
        CLOSE_S). A lost client is CLIENT_LOST for the device after its
        socket is gone."""
        if self._client is None:
            return
        _, writer = self._client
        self._client = None
        self._pending = b""
        if now or lost:
            writer.transport.abort()
        else:
            writer.close()
        try:
            await asyncio.wait_for(writer.wait_closed(), CLOSE_S)
        except asyncio.TimeoutError:
            writer.transport.abort()           # a client that does not read keeps it open
        except (ConnectionError, OSError):
            pass
        if lost:
            self.dev.rec_client_lost()

    def _count(self, n: int):
        self.sent_bytes += n
        now = time.monotonic()
        self._rate.append((now, self.sent_bytes))
        while self._rate and now - self._rate[0][0] > 2.0:
            self._rate.popleft()

    # ---- state --------------------------------------------------------------------------
    def status(self) -> dict:
        r = self._rate
        kbps = 0.0
        if len(r) > 1 and r[-1][0] > r[0][0] and time.monotonic() - r[-1][0] < 1.0:
            kbps = (r[-1][1] - r[0][1]) / (r[-1][0] - r[0][0]) / 1000
        stalled = self._stall is not None and self._stall[1] > self._now_ms()
        return {"mode": "ap" if self.ap else "lan", "ip": self.announce_ip, "port": self.port,
                "listening": self._server is not None, "client": self._client is not None,
                "peer": self.last_peer, "clients": self.clients, "sent_bytes": self.sent_bytes,
                "kbps": round(kbps, 1), "stalled": stalled}
