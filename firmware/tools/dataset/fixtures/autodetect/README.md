# Auto-detector golden fixtures

Inputs and expected output of `dbdata.autodetect` (the reference
implementation) for the app's Kotlin port (`recorder/label`,
`AutoDetectVectorsTest`, milestone 6). The algorithm is the module docstring
of `firmware/tools/dataset/dbdata/autodetect.py`; its parameters are
`firmware/tools/dataset/dbdata/contract/autodetect_params.json`.

| Fixture | Take | What it covers |
|---|---|---|
| `fx_drill_through` | drill, one hole, contact and breakthrough | the plain path, MIC1 |
| `fx_hammer_retract` | hammer (RAW448_16G), one blind hole, retract | the hammer cue, the gyro/flux load end |
| `fx_stuck_kick` | drill, the bit stalls (stuck span, kickback), motor off in the hole, restart, retract | the stall, a restart in the hole, a kickback in the hole |
| `fx_raw897_mount` | RAW897, two holes (breakthrough, then retract), the board tilted on the drill and mount-calibrated | the 897 Hz filters and hammer lags, `mount.valid` with a drill axis, the contact search after a load end |
| `fx_noaudio` | drill without microphones; the motor already runs when the data begins | no audio (z_aud NaN, the spin-up from the IMU), motor_on at T0 (`open_motor_on`); only the motor runs are found |
| `fx_pulled_open` | drill, the bit stalls, is pulled out with the motor off, and the take ends during the next spin-up | the restart check failing (a retract before the motor_on, `restart_retract`), the spin-up fallback, an open end |
| `fx_gap` | drill, a transport gap of about 0.8 s in the middle of the hole, a kickback as the bit breaks through | `gaps.audio_gap_frames`, frames without data (s NaN), a kickback just after a hole (`hole_after_ms`) |
| `fx_suggestions` | drill with wobble, wander and speech injected (`synth.INJECTIONS`: the simulator has no model for them) | the three suggestion rules, a long motor-off tail |

Each folder is a recording as the dataset contract has it (synthetic,
from the simulator through `dbdata.dbrec`'s export; MIC1 only where there
is audio):

- input: `meta.json` (`imu.accel_lsb_per_g`, `gyro_lsb_per_dps`,
  `odr_nominal_hz`, `mount`, `audio` (null without audio),
  `audio.rate_nominal_hz`, `gaps.audio_gap_frames`), `imu.csv.gz`,
  `audio.flac`, `audio_sync.csv` (the sync fit is the least-squares line
  through it, as `io.SyncFit`), `level.csv.gz`; `events.json` supplies
  only `coverage_us` (it holds the scenario's ground truth);
- `spec.json`: the `synth.Spec` that made the take;
- `expected.json`: `detector` (version and params sha256), `frames`,
  `t0_us`, `frame_us`, `events` (the complete `events.json` the detector
  writes) and `derived` (`meta.derived`);
- `features.csv.gz`: one row per frame, `frame,t_us` and the features of
  the docstring's sections 2-4 (`e_imu thrust imp roll gyro_rad wob ham
  e_aud f0 harm e_cut flux e_speech z_imu z_aud z_speech s`), 9 significant
  digits, empty where the value is NaN (`s` too, where neither stream has
  data).

The port should match the features within 1e-6 relative (+ 1e-6 absolute)
and NaN where NaN, and produce the same events, spans and holes: the same
types, holes, outcome tags and order, times within one frame (10 ms; a
rule's threshold can flip by a rounding difference) and confidences within
0.01.

Not covered: clock drift (the simulator has none, so every sync fit is
62.5 µs per frame exactly; the port's sync arithmetic is the same either
way), a stuck span that ends before the motor stops (the simulator's
stalls last until motor_off), and the rules' per-frame internals (contact
score, recovery R, stuck ratio): only their results are in
`expected.json`.

Regenerate (needs the simulator's C core):

```sh
cd firmware/tools/dataset
.venv/bin/python -m dbdata.synth fixtures fixtures/autodetect     # new takes + expected output
.venv/bin/python -m dbdata.autodetect --update-fixtures          # expected output only (no simulator)
.venv/bin/python -m dbdata.autodetect --check-fixtures           # replay
```
