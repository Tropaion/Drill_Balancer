# Finalizer cross-check fixtures

The app rebuilds each take's dataset files from its journal (the stream as
received, `stream.dbr`, and `take.json`) in
`app/src/main/java/dev/drillbalancer/recorder/export/Finalizer.kt`. It must
write what the reference exporter, `dbdata.dbrec.export_take`, writes from
the same bytes. These fixtures are that reference output; the app's
`FinalizerCrossCheckTest` finalizes the same takes and compares.

| Fixture | Stream | What it covers |
|---|---|---|
| `golden` | `firmware/test_host/recorder/vectors/golden_stream.bin` | every record type, a record dropped on the device, a FIFO loss filled in, a resynchronised IMU run, an unknown record type, junk and a bad CRC |
| `rich` | `rich.dbr` (written by `make_fixtures.py`) | 3.4 s, two microphones: audio before and after the IMU (the common span), an IMU read before the PROFILE event, read-mode losses, a confirmed DISCONT jump, an IMU record and an audio block dropped on the device (transport gaps, audio gap frames), an I2S overflow, an IMU reset (EPOCH, a new clock segment), clipping in both streams, a zero set mid-take, STATS with battery, MARK text with a comma, quotes and non-ASCII, END max_s; `other` material with its text in the setup, drill type `rotary_hammer`, motor `other` with its text (`other_text.motor`) |
| `rich_cut` | the first 62 % of `rich.dbr`, cut mid-record | an app that died while recording: no END, recovery cuts the journal to its last complete record (`truncated`); an empty setup (drill type and motor `unknown`) |

Each folder holds the recording files `dbrec.export_take` wrote and
`spec.json`: the phone side of the take as `take.json` has it (recording
and session ids, `take_id`, `start_utc`, the setup screen's `setup`) and
the `session` labels that setup gives (the exporter takes them as given).

What the test compares:

- `imu.csv.gz`, `level.csv.gz`: the CSV bytes after decompression (zlib's
  and java.util.zip's gzip streams may differ);
- `audio_sync.csv`, `device_events.csv`, `events.json`: the bytes;
- `audio.flac`: the decoded samples and the STREAMINFO MD5 (libFLAC and the
  app's encoder encode differently);
- `meta.json`: the text, except `app` (the phone there, `dbdata.dbrec` here)
  and the sha256 / size of the compressed and FLAC files, which the test
  checks against the files the app wrote.

Regenerate after a change to the export (and check, also a unit test of
`tests/test_dbrec.py`):

```sh
cd firmware/tools/dataset
.venv/bin/python fixtures/finalize/make_fixtures.py
.venv/bin/python fixtures/finalize/make_fixtures.py --check
```

Opt-in, the app's finalized takes through the validator:

```sh
cd app && DB_FINALIZE_OUT=/tmp/finalized ./gradlew testDebugUnitTest --tests '*FinalizerCrossCheckTest*'
cd ../firmware/tools/dataset && .venv/bin/python -m dbdata.validate /tmp/finalized
```
