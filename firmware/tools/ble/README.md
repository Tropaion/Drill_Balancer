# db_client.py: DrillBalancer BLE test client

A PC-side client for the firmware's GATT protocol
(`components/ble/include/ble_protocol.h`, OTA in
`components/ota/include/ota_proto.h`). Python 3.9+ and
[bleak](https://github.com/hbldh/bleak).

```sh
python3 -m venv .venv && . .venv/bin/activate   # fish: . .venv/bin/activate.fish
pip install -r requirements.txt
```

## Commands

```sh
./db_client.py --dry-run                 # checks the packet codecs, no Bluetooth needed
./db_client.py scan                      # lists DrillBalancer-XXXX devices
./db_client.py pair                      # pairs and bonds (see below)
./db_client.py stream                    # angle (10 Hz) and status notifications
./db_client.py stream --status-only -d 30
./db_client.py get-config
./db_client.py set-config tol=0.2 near=1.5 mode=floor ble=true
./db_client.py cmd zero
./db_client.py cmd set-mode angle
./db_client.py cmd accel-cal-start       # ... turn the device face by face ... then accel-cal-finish
./db_client.py cmd factory-reset --yes
./db_client.py ota ../../build/drillbalancer.bin
./db_client.py rec open                  # recorder session: rec_state with the stream endpoint
./db_client.py rec start --profile RAW448 --mics 3 --gain 6 --max-s 60
./db_client.py rec mark --code 1 "hole 1"
./db_client.py rec stop
./db_client.py rec state --watch
./db_client.py rec pull --start --duration 20 -o take.dbrec
./db_client.py rec close
```

Without `-a/--address` the client scans and takes the first
`DrillBalancer-*` it finds; `-n DrillBalancer-1A2B` picks one by name.

## Pairing

Reading angle, status and config works without pairing. Config writes,
commands and OTA need an encrypted, authenticated link (LE Secure Connections
with passkey). The device shows a 6-digit passkey on its screen while pairing,
and you type it in on the PC.

On Linux, bleak pairs through BlueZ, and BlueZ needs a pairing agent to ask
for the passkey. A desktop environment usually has one (a passkey dialog pops
up). Otherwise, run an agent in a second terminal:

```sh
bluetoothctl
[bluetooth]# agent KeyboardOnly
[bluetooth]# default-agent
```

Then run `./db_client.py pair` (or any write command; BlueZ pairs on demand
when the device answers "insufficient authentication") and enter the passkey
shown on the device in the agent prompt. The bond is stored on both sides; after
that no passkey is needed. If you remove the device in BlueZ, the device drops
its old bond the next time you pair.

Alternatively pair once by hand: `bluetoothctl`, `scan on`, `pair <address>`,
type the passkey.

## OTA

`ota` sends START (size + SHA-256), then the image as `u16 seq | payload`
chunks (write without response, chunk size from the device's START reply,
which follows the negotiated MTU), and keeps at most `2 * ack_every` chunks
ahead of the last ACK. On a NACK it resends from `expected_seq`. If no ACK
arrives for 3 s (lost writes at the end produce no NACK), it resends from the
last ACK. Then FINISH, and the device reboots after confirming the reply.
Ctrl-C sends ABORT.

The device refuses OTA (FLASH_ERROR on START) until the running image has been
confirmed after an update, and an image built for a different project fails
the descriptor check.

## Recorder

`rec pull` opens a session if none is open, waits for `rec_state`
WAIT_CLIENT, connects to the stream address it gives and sends HELLO (the
session token and the stream version). On the board's soft-AP it first asks
you to join the Wi-Fi network shown; the simulator's LAN mode (`NO_AP`)
needs nothing. With `--start` it starts a take once the connection is up,
`--duration` stops it after that many seconds. It saves everything to the
`.dbrec` file until `--takes` END records arrived (1 by default; 0: until
Ctrl-C or the device closes the stream) and prints what came: records by
type, takes, END reasons, records the device dropped. Ctrl-C sends STOP and
waits 5 s for the END. `tools/dataset`'s `python -m dbdata.dbrec` decodes
the file and exports a take as a dataset recording.

`rec pull --host IP [--port P] --token HEX` pulls without Bluetooth from an
endpoint known otherwise: the simulator run with `--no-ble`, whose panel
opens the session, shows its endpoint and token and starts the takes (see
the simulator's README). `--start`, `--duration` and `--close` need the
BLE link and are refused there.

## Errors

Writes fail with standard ATT errors (0x05/0x0F: pair first, 0x0D: wrong
length) or the application errors 0x80..0x8A listed in `ble_protocol.h`; the
client prints what they mean.

## Tests

```sh
python3 -m unittest test_db_client      # OTA sender against a simulated device, no bleak needed
```

The recorder pull (`rec_pull_stream`) is tested against the simulator's
stream server in `tools/sim/tests/test_recorder.py`.
