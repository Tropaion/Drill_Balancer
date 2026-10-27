# DrillBalancer Android app

The companion app for the DrillBalancer (see [../firmware](../firmware/README.md)).
Over BLE it shows the live level (wall, floor and angle mode), edits all
device settings, runs both calibrations, sends power commands and updates
the firmware. It also records training data: the device streams its raw IMU
and both microphones over its own Wi-Fi to the phone (see
[Training data](#training-data)).

Kotlin, Jetpack Compose (Material 3), one activity, coroutines/Flow, manual
DI. BLE runs on Nordic's Android-BLE-Library. minSdk 26 (Android 8.0),
target/compile SDK 37. Package `dev.drillbalancer`.

> Nothing here has run on a phone yet. Work through the
> [first-run checklist](#first-run-on-real-hardware) when you try it.

## Requirements

- **Android SDK** with platform `android-37` and build-tools 36 (AGP fetches
  other build-tools itself). Point the build at it in `local.properties`,
  which is machine-specific and gitignored:

  ```properties
  sdk.dir=/home/you/Android/Sdk
  ```

  Setting `ANDROID_HOME` works too. Android Studio writes the file when it
  opens the project.
- **JDK**: any JDK 17+ on `PATH` can run `./gradlew`. The Gradle daemon
  itself runs on JDK 21 (`gradle/gradle-daemon-jvm.properties`). Gradle looks
  for an installed JDK 21 and downloads one (foojay) if it finds none, so
  `JAVA_HOME` does not need to be set. Java/Kotlin bytecode targets 17.
- Network access to Google Maven, Maven Central and services.gradle.org for
  the first build.

The versions are in [gradle/libs.versions.toml](gradle/libs.versions.toml).
Gradle is held at 9.7.x because Kotlin 2.4 is only documented up to 9.7, so
lint's "newer Gradle available" warning is expected.

## Build and install

`app/` is both the Gradle root and the only module. All commands run here.

```sh
./gradlew assembleDebug          # build/outputs/apk/debug/DrillBalancer-debug.apk
./gradlew installDebug           # build and install on a phone with USB debugging on
./gradlew assembleRelease        # unsigned release APK (no signing config yet)
./gradlew lintDebug
```

For `installDebug`, enable *Developer options → USB debugging* on the
phone, connect it and accept the RSA prompt. `adb devices` must list it.

## First connection and pairing

1. Open the app and allow *Nearby devices* (Android 12+) or *Location*
   (Android 8–11; location services must also be on, Android needs that to
   scan for BLE).
2. Wake the device. It shows up as `DrillBalancer-XXXX`; tap it. Reading the
   angles, status and settings works without pairing.
3. The first action that changes something (saving settings, a mode tab, a
   command, a calibration, a firmware update) asks you to pair. Tap *Pair*:
   the device shows a 6-digit code on its screen, and Android's pairing
   dialog asks for it. Type the code shown on the device. The action then
   continues by itself.
4. After a factory reset the device has forgotten every phone. The app then
   asks you to remove `DrillBalancer-XXXX` in the phone's Bluetooth settings
   and pair again (it cannot remove the phone's old pairing itself).

Pairing (and the check that the bond still works) counts as a write, so per
the device's link policy it keeps the device awake for about 10 minutes.

## Tests

```sh
./gradlew testDebugUnitTest      # all JVM and Robolectric tests
./gradlew verifyRoborazziDebug   # screenshot tests against src/test/screenshots
./gradlew recordRoborazziDebug   # re-record the reference screenshots
./gradlew compareRoborazziDebug  # write diff images (build/outputs/roborazzi) for changed screenshots
```

- Everything runs on the JVM: plain unit tests, ViewModel tests with
  `FakeDeviceSession` and coroutines-test virtual time, and Robolectric
  (SDK 37) for the Android glue and the screenshots. No phone or emulator is
  needed.
- The conformance tests (`protocol/FirmwareConformanceTest`) read the
  firmware's C headers from `../firmware`, so they need the whole repository
  checkout.
- Reference screenshots live in `src/test/screenshots`, named
  `<test class>.<method>.png`, and are committed. They are rendered by
  Robolectric's native graphics on Linux; re-record them on Linux after an
  intentional UI change and look at the changed images before committing.
  Unit test tasks never come from the build cache, so a record run always
  renders.
- New screenshot tests: copy an existing one (`ui/*/…ScreenshotTest.kt`) and
  keep the `Themed` wrapper from `Fixtures.kt`. It forces a redraw after the
  first frame, without which Robolectric captures screens with no animation
  as blank.

### Against the simulator's recorder (opt-in)

`recorder/SimulatorEndToEndTest` records one take from a running simulator
through `RecordingController`, `LanLink` and the journal, and checks that the
journal decodes without loss. It is skipped unless `DB_SIM_URL` names the
simulator's web panel; without Bluetooth the panel's API stands in for
`rec_ctrl` / `rec_state`:

```sh
cd ../firmware/tools/sim && ./run.sh --no-ble --rec-host 127.0.0.1     # one terminal
DB_SIM_URL=http://127.0.0.1:8765 DB_SIM_OUT=/tmp/e2e \
  ./gradlew testDebugUnitTest --tests '*SimulatorEndToEndTest'        # another
```

`DB_SIM_OUT` (optional) keeps the journal as `<id>.dbrec` with its
`take.json`, for the dataset tools:
`python -m dbdata.dbrec info /tmp/e2e/<id>.dbrec` (in
`firmware/tools/dataset`).

### Against the device simulator

Without the board, test the app on a phone against
[`firmware/tools/sim`](../firmware/tools/sim/README.md). It runs the
firmware's own logic on a Linux PC and advertises as `DrillBalancer-XXXX`
over the PC's Bluetooth adapter.

```sh
cd ../firmware/tools/sim && ./run.sh   # then open http://127.0.0.1:8765
```

- Pairing works as described above. The passkey shows on the panel's copy
  of the device screen and in the terminal.
- The panel moves the virtual drill and injects faults: BUSY replies,
  dropped OTA indications, a lost IMU, a disconnect, slow flash.
- After a factory reset in the sim, forget `DrillBalancer-XXXX` on the phone,
  as with the board.

## Architecture

| Package | What it holds |
| --- | --- |
| `protocol` | The wire contract, no Android: UUIDs, angle/status packets, commands, ATT errors, settings JSON with the firmware's ranges and cross-field rules, firmware image checks, the OTA wire format and `OtaClient` (the transfer loop of `db_client.py`). |
| `device` | `DeviceSession`: what the UI sees of one link (StateFlows for connection, angle, status, battery, info, settings; suspend calls for settings, commands, pairing; a cold OTA flow) and `DeviceError`. |
| `ble` | The real implementation: `BleDeviceSession` (connect, setup, pairing probe, reconnect, OTA transport) on `DrillBalancerManager` (Nordic `BleManager`), `DeviceScanner`, permissions, GATT error mapping, `ReconnectPolicy`, `LastDeviceStore`. |
| `recorder` | The training-data recorder, at app scope: `RecordingController` (session, takes, stop and reconnect paths), `RecordingStore` (the journals, recovery), `SessionSetup` / `Taxonomy` / `RecordingIds` (the dataset contract's labels and ids), `LivePreview`, `RecorderService` (foreground service). `recorder/stream`: the stream's constants, records, the incremental `StreamDecoder` and `StreamStats`. `recorder/net`: `StreamLink` with `ApLink` (the device's soft-AP) and `LanLink` (the simulator), `WifiPermissions`. |
| `upload` | The upload of reviewed recordings to the data repo: `HttpGitHubApi` (Git Data API on HttpURLConnection), `DatasetUploader` (one commit per recording, `upload.json`), `UploadWorker` / `WorkUploadScheduler` (WorkManager), `UploadQueue` (stages, Upload now), `TokenVault` (Android Keystore AES-GCM) and `UploadSettingsStore`. `recorder/export/AudioMute` mutes the audio while the motor is off. |
| `ui` | Compose screens and their ViewModels: connect, pairing, live, settings, calibrate, device, firmware update, training data (list, setup, record, review, upload settings). `SessionViewModel` picks the connect screen or the tabs and owns the `PairingGate`, which pairs on the first protected action. |

`DrillBalancerApp` creates the scanner, the last-device store, one
`BleDeviceSession` and the recorder (`RecordingController`, its store and
setup) for the process, so a rotation keeps the link and a recording
outlives the screens. Leaving
the app (the activity finishes) ends the link. The connected screens'
ViewModels are cleared when the app returns to the connect screen.
`FakeDeviceSession` exists only in the test sources.

### Protocol contract

The firmware is the source of truth:
[firmware/components/ble/include/ble_protocol.h](../firmware/components/ble/include/ble_protocol.h)
(GATT layout, packets, commands, errors, security and link policy) and
[firmware/components/ota/include/ota_proto.h](../firmware/components/ota/include/ota_proto.h)
(OTA), plus `settings.h`, `level.h`, `accel_cal.h` and `app_state.h` for the
config keys and enums. `FirmwareConformanceTest` parses those headers and
fails when a constant, limit, default or packet layout in `protocol` no
longer matches, and `FakeOtaDeviceTest` replays the cases of the firmware's
host tests for `ota_proto.c`. Change the firmware header first, then the
app, and run the tests.

## Training data

*Device → Recordings* (under Training data; or *Training data* on the connect screen, where the
list works without a device) lists the recordings on the phone. *New
recording* asks for the session's labels (material, thickness, mode, bit,
drill type, motor, drill model; the taxonomy of the dataset contract,
remembered for the next session; a setup kept by an older app loads the
drill type and motor as unknown) and the recording settings (hammer mode records ±16 g, the
microphones, their gain, the longest take), then opens a session. The
drill type is *Drill/driver*, *Combi drill (hammer)*, *SDS rotary
hammer* or *Impact driver*; the motor *Brushed*, *Brushless* or *Corded
(AC)* (a corded drill is said by its motor); both also *Unknown* or
*Other…* with a text. Hammer mode with a drill/driver or an impact
driver, and screw mode with an SDS rotary hammer, show the hint the
dataset validator gives as its warning `W_MODE_TOOL`; Start still records.
A session opens in three steps:

1. **OPEN** over BLE (the first time this pairs, as any protected action).
   The device starts its Wi-Fi soft-AP; `rec_state` (an authenticated read,
   then notifications) carries the SSID, passphrase, address and a session
   token.
2. **Join**: on Android 10+ the app requests that network for itself
   (`WifiNetworkSpecifier`, without internet, so mobile data stays the
   default and the process is never bound to it). The first time Android
   asks "connect to device?"; the record screen says so (not on a join
   again after a drop: Android remembers the approval). Android 8-9 cannot
   do this: the screen shows the network and password to join in the
   system settings, and the app connects once the user has. The simulator
   (`NO_AP`) is reached over the network the phone is on.
3. **TCP + HELLO** to the stream server; READY once the device answers
   HELLO_OK on that connection (rec_state cannot tell a new connection from
   the one before a drop): *Start take* / *Stop take*, as many takes as
   wanted; *Finish session* closes it.

Each take is one folder `files/recordings/<recording id>/` with
`stream.dbr`, the stream's bytes exactly as received (synced to storage
once a second), and `take.json` (ids, labels, the phone's clock, how the
take ended). A take whose END never came is kept as *Interrupted*. After
a crash the journals the app was writing are cut to their last complete
record and marked interrupted at the next start.

### Finalize and auto-labels

When a take ends (and at the next start for any take left without them)
the app writes the take's **dataset files** next to its journal
(`recorder/export/Finalizer`): `imu.csv.gz`, `level.csv.gz`, `audio.flac`,
`audio_sync.csv`, `device_events.csv`, `meta.json` and `events.json`, as
the dataset contract has them and as `dbdata.dbrec export` writes them
from the same bytes (`FinalizerCrossCheckTest`). It then **labels** the
take (`recorder/label`): a Kotlin port of `dbdata.autodetect` (detector
`dbauto/1.1.0`; its parameters are the contract's `autodetect_params.json`,
shipped as an asset) finds the motor runs, contacts, breakthroughs and
retracts, stalls and kickbacks, and suggests wobble, wander and speech.
events.json gets its labels (source auto, `detector` with the parameters'
sha256), `meta.review` says `auto_only`, and `meta.derived` holds the
orientation and off angle from the device's LEVEL during drilling. The
row then reads *Needs review*. A 20-minute take labels in about 6 s on a
desktop JVM. Phone-only files sit next to them and are never uploaded:
`events.auto.json` (the detector's labels, for *Reset all to auto*),
`events.draft.json` (an unsaved review) and `lanes.bin` (the review's
signals).

The heavy part of labelling (the recording read whole, the detector, the
review's signal lanes) runs one at a time for the whole app, whether the
background labeller or a review asks for it, so no two detector or lane
computations hold a long take at once (the finalize of another take is
not part of it and can run meanwhile); opening a labelled recording does
not wait for it. When the phone runs out of memory finalizing or labelling a take, the
take is not tried again at the next start (it would fail again, every
time); its review says so and offers *Try again*.

### Review

A row, or *Review* under the last take on the record screen, opens the
review (it waits there if the labels are not ready yet): the session's
labels and tool under the title, four signal
lanes (IMU energy, sound, motor pitch, cutting noise; min/max per column
of the visible range) with the phases the labels give as coloured bands,
and one 56 dp handle per marker above them (in a second row where two
would overlap).

- **Move** a marker by dragging its handle (it snaps to the 10 ms frames)
  or with the -100 / -10 / +10 / +100 ms buttons; *Reset* puts a moved
  auto marker back. **Zoom** (- / +, down to 0.2 s) and **pan** (‹ ›)
  with the buttons; *All* shows the whole take.
- **Add** a marker of any type at the cursor (a tap on the lanes sets it);
  **Delete** the selected one. Holes are numbered again by contact after
  every change and keep their tags.
- **Uncertain** auto markers (confidence below 0.6) are drawn dashed amber
  and need a tap to confirm before Save; *Accept all* confirms every one
  and saves.
- **Tags** per hole: the outcome tags (*clean* and the problem tags exclude
  each other; an *auto* badge where the detector set one), the off-angle
  tag, set automatically from the device's level, and the detector's
  suggestions (stuck, wobble, wander, speech) to keep or drop.
- **Save** checks the event order first (the grammar `dbdata.validate`
  enforces; the message names the marker and the fix, for example
  "Contact at 2.14 s: the motor is off there. Add Motor on before it, or
  move it."). It then writes events.json (revision + 1; moved markers
  `edited` with `auto_t_us`, added ones source user), `meta.review`
  (reviewed, by the install id, with the number of changed labels) and
  `meta.derived` recomputed for the new labels. The row reads *Saved*.
  Every change is kept as a draft at once, so Back (or the app dying)
  loses nothing; *Discard* drops the draft. *Discard* asks first when
  something changed, and *Reset all to auto* when it would drop edits.
- The review needs no device: when the Bluetooth link ends it stays open
  (on the connect screen's side), and the link coming back shows it on the
  tabs again. It is the same review on both sides, and across a rotation
  too: the edits, the view and a Save under way go on.
- With TalkBack or Switch Access a handle is a button (select, or confirm
  an uncertain marker), and the lanes have *Cursor back* / *Cursor
  forward* actions.
- A recording whose files could not be written (the phone full) reads
  *Interrupted* in the list and its review says so with *Try again*; the
  app also tries again at its next start. Every row but the take being
  recorded opens the review.

Checks against the Python reference: `AutoDetectVectorsTest` replays the
eight golden fixtures of `firmware/tools/dataset/fixtures/autodetect`
(features within 1e-6, the same events, spans and holes, times within a
frame, confidences within 0.01; also the app's FLAC reader against the
tests' full decoder). `PhasesCrossCheckTest` replays `fixtures/labels`:
the phases, grammar issues and derived labels `dbdata.labels` gives for
279 label sets, which must match exactly. Opt-in, reviewed recordings for
the validator:

```sh
DB_REVIEW_OUT=/tmp/review_out ./gradlew testDebugUnitTest --tests "*ReviewValidatorExportTest"
cd ../firmware/tools/dataset && .venv/bin/python -m dbdata.validate /tmp/review_out
```

What survives what: a **BLE drop** does not stop a take (the device keeps it
120 s; *Stop* then closes the stream, which stops it too). A **dropped
stream** (Wi-Fi gone, the phone out of range, 10 s without data) ends the
take as interrupted and reconnects to the same session. *Stop* waits 5 s
for the take's END, then does the same.

A foreground service (type `connectedDevice`) keeps the session alive with
the screen off; its notification shows the take time and has a *Stop*
button. The record screen keeps the display on.

Permissions: Android 13+ *Nearby devices* (Wi-Fi, never for location) and
*Notifications*; Android 17+ also the local network permission
(`ACCESS_LOCAL_NETWORK`, for the stream's TCP connection); Android 10-12
*Location* with location services on (Android needs it to join a network);
Android 8-9 none. *Start* asks only while a required one is missing (the
notification permission with it, so a refusal is not asked again each
time); a refused one, or location services off on Android 10-12, stops
*Start* with a button to the settings that fix it. A recording needs 500 MB
free.

### Upload

A saved review is uploaded on its own to a **private GitHub repository**
(default `Tropaion/Drill_Balancer_Data`, the dataset contract's data repo,
already bootstrapped with its template from the PC), one commit per
recording. Recordings that still *Need review* wait; *Upload settings*
(the line under *New recording*) has *Upload now* for every reviewed one not
uploaded yet. Each row shows its upload next to its local stage: *Not
uploaded*, *Queued*, *Uploading 42 %*, *Uploaded* or *Upload failed* with the
reason and *Retry upload* (or *Upload settings* when only they fix it: no
token, a rejected one, a repository name that is not owner/name; upload.json
keeps the failure's kind next to its words). Saving a review again after its upload sends only
what changed (events.json and meta.json, plus audio.flac when muting changed
it) as a second commit "Update labels of <id>". When the data repo's copy of
a recording was changed since this phone uploaded it (labels edited on the
PC, `dbdata.autodetect --write --force`), a later Save on the phone is not
put over it: the row says so, and *Retry upload* then replaces it with the
phone's labels (a Retry after any other failure keeps that check). An update
removes from the folder only files this phone committed there before; a
file added to it on the PC stays.

**Token setup** (once): on GitHub *Settings > Developer settings >
Fine-grained tokens*, a token with access to **only the data repo** and the
permission **Contents: read and write** (nothing else; no Actions or
Workflows permission is needed). In *Upload settings* tap *Set token* and
paste it. The app seals it with AES-256-GCM under a key in the Android
Keystore (DataStore "upload" holds only the ciphertext and IV); the screen
only ever shows *Token set*, *Replace* and *Remove*, and the token appears in
no log, message or file. *Test connection* checks that the repository exists
and is private, that the token may write to it, that the dataset template
(`schema/meta.v1.schema.json`) is there, and warns from 1 GB of repository
size; it tests the saved repository (save an edited name first). GitHub
rejecting the token (401) stops the whole queue ("Token rejected", *Upload
now* off) until it is replaced; a new token sends the queue again, and so
does a *Test connection* that GitHub answers again (a 401 that went away).
A 401 for a token replaced while an upload used it does not mark the new one.

**What is uploaded**: `recordings/YYYY/YYYY-MM/<id>/` with meta.json,
events.json and the data files meta.json lists (imu.csv.gz, level.csv.gz,
audio.flac, audio_sync.csv, device_events.csv), checked against their sha256
in meta.json first. Never the journal, take.json or the phone-only files
(events.auto.json, events.draft.json, lanes.bin, upload.json), never a
dataset-level file (index.csv and stats.md are the data repo's Action's).
A file over the dataset's 95 MB limit is refused before anything is sent.

**How** (`upload/`): `HttpGitHubApi` (HttpURLConnection, the Git Data API:
the branch's commit, blobs streamed as base64 with a fixed length, a tree
over the commit's tree, the commit, the branch moved with `force: false`;
when another push moved it meanwhile the tree and commit are made again on
the new commit, up to 5 times; a branch GitHub will not move at all, such as
a protected one, fails at once with GitHub's reason) and
`DatasetUploader`. `upload.json` in the recording folder keeps the git blob
sha of every file sent and the last commit, so an upload cut short sends
only the rest; when GitHub has dropped blobs no commit held, they are sent
again once. A recording whose meta.json
is on the branch already with the same blob sha is marked uploaded without
a commit. The upload runs in WorkManager (`UploadWorker`, one unique job per
recording, exponential backoff, Wi-Fi only or any network) as foreground
work with a progress notification (type dataSync), so uploads of tens of MB
are not cut at 10 minutes; network, storage and server errors are retried
(8 runs), rate limits waited out (Retry-After, x-ratelimit-reset, and a
minute for GitHub's secondary limit). Recordings upload one at a time (the
others show *Queued*), which keeps a backlog under GitHub's secondary rate
limit; the settings and the token are read when an upload's turn comes, so
a change made while it waited (mute switched on) applies to it. It takes
the recording's folder lock while it mutes and hashes the files, so it
never overlaps a finalize, a relabel or a Save; a Save during an upload
does not wait for it and is uploaded right after it, also when
that upload fails. Switching *Wi-Fi only* either way puts every queued
upload under the new rule: one running is stopped too and resumes from
upload.json (the blob in flight is sent again), so none goes on over mobile
data and none waits for Wi-Fi once it is off. An upload replaced in the
queue (by this, a Save or *Upload now*) keeps a rate-limit wait and the
overwrite of a Retry after a conflict.

**Known limit, uploads started in the background** (Android 12+): Android
does not let a worker that starts with the app in the background start its
foreground service, and such a worker may be stopped after a few minutes
(a blob cut short is sent again from its start: GitHub has no resumable
blob upload). Such a worker sends at most 24 MB (`BACKGROUND_MAX_BYTES`, a
few minutes on a slow uplink); a larger upload shows *Queued* and goes when
the app is next opened (`UploadQueue.appVisible` from `MainActivity.onStart`),
with its notification. Android 14's user-initiated data transfer jobs would
lift this but WorkManager does not offer them.

**Keep local copies** (off by default): after a successful upload the take's
raw stream (`stream.dbr`) is deleted; the dataset files stay, so the review
can still be opened, edited and saved (and uploaded again).

**Privacy, mute audio while the motor is off** (off by default): the data
repo must stay private while recordings may hold voices (its README says
so). With this on, the upload first silences audio.flac on the phone
outside the motor's runs: every sample before a motor_on and after the end
of its spin-down (the phases of the saved events, 50 ms boundary guard on
both sides) is zero, `meta.audio.muted_spans_us` lists those spans (t_us) and
meta.files has the new hash. The phone and the repository hold the same
muted file; `dbdata.windows` marks windows over muted spans `audio_muted`.
Muting cannot be undone from the file: labels moved later can mute more,
never bring sound back. A recording whose audio cannot be muted (no usable
sync fit, no labels, audio.flac missing) is not uploaded, and the row says
why (no retries). Opt-in check with the validator:

```sh
DB_MUTE_OUT=/tmp/mute_out ./gradlew testDebugUnitTest --tests "*MuteValidatorExportTest" --rerun
cd ../firmware/tools/dataset && .venv/bin/python -m dbdata.validate /tmp/mute_out
```

**Live test** (opt-in, against the real repository): with a token in
`DB_GITHUB_TOKEN` and a new branch name in `DB_GITHUB_LIVE_BRANCH`,
`GitHubLiveTest` makes that branch from main, uploads the small golden take
to it, then a label change (a second commit), uploads again (no third
commit), compares the blob shas read back and deletes the branch. It
refuses main and a branch that exists. The token is no Gradle input (never
stored by Gradle): run it with `--rerun`.

```sh
DB_GITHUB_TOKEN=github_pat_... DB_GITHUB_LIVE_BRANCH=upload-live-test \
  ./gradlew testDebugUnitTest --tests "*GitHubLiveTest" --rerun
```

## First run on real hardware

None of this could be checked without a phone and a board.

1. **Permissions and switches**: the permission request; a dismissed
   dialog keeping *Allow* next to *Open app settings*; "denied for good"
   leaving only *Open app settings* (the second "Don't allow", or Android
   refusing within 0.5 s without a dialog,
   `PermissionOutcome.ANSWERED_WITHOUT_ASKING_MS`); the enable-Bluetooth
   dialog, location services on Android 8–11, and the screen updating when
   Bluetooth is switched in the quick settings.
2. **Scan**: the device is found by name or by the service UUID in the scan
   response, the list ages out devices after 10 s, and scanning stops when
   the app goes to the background or a connection starts.
3. **Connect**: timeout and retries, MTU 247, 2M PHY and connection priority
   (check the Nordic log, tag `DrillBalancerBle`), service validation,
   notifications for angle (10 Hz), status and battery.
4. **Long config read and write**: the settings JSON is longer than one ATT
   packet. Check that the read (Android continues it with Read Blob) parses,
   and that a save with many changed keys (a long write) is applied once.
5. **Pairing**: the system passkey dialog appears on the first protected
   action, a correct code continues the action, a wrong code or a cancelled
   dialog shows "Pairing failed", and the `{}` probe write succeeds after
   pairing.
6. **Stale bond**: factory-reset the device from the app, reconnect, try a
   protected action. Note which GATT status Android reports and whether the
   app shows the "remove the pairing in Bluetooth settings" steps
   (`GattErrors` guesses 0x05/0x0F/0x0C/0x08/137, or HCI 0x05/0x06/0x3D on
   the drop).
7. **Status 0x85** is the device's BAD_ARG on the command characteristic,
   but also Android's GATT_ERROR; the app always shows it as a phone
   Bluetooth error. Note when a real phone reports it on a command.
8. **Live data**: mode tabs, zero / clear zero, colours against the device
   screen, the NO SENSOR panel, yaw drift warning.
9. **Calibration**: sensor calibration tile progress and result, mount
   calibration sample count (shows "255+", the status byte saturates), the
   -3 "redo the mount calibration" result, and cancellation when the link
   drops.
10. **Settings**: every field saves; `ble=false` disconnects with the right
    message; cross-field fixups (raising the tolerance moves *near*).
11. **Power commands**: sleep, power off, reboot (the app reconnects about
    4 s after the device is back), factory reset.
12. **Firmware update**: picking a `.bin` through the system file picker,
    the image checks, throughput (KB/s), cancel (ABORT), the screen staying
    on, the reboot and the reconnect with the new version shown, a link
    drop while the device checks the image (the app decides from the
    version after the reconnect, and the reconnect sees the new GATT
    table), how long the FINISH write takes on a full-size image (the app
    gives that write its own 30 s timeout, the device checks the image
    before it answers; a check over 10 s must still end in a definite
    answer), how long after the reboot the phone sees the drop (the app
    waits 15 s), and a failed update leaving the old firmware running.
13. **Reconnect**: walk out of range and back. The tabs stay with a
    "Reconnecting…" bar while the session retries (backoff 1, 2, 4, 8, 15 s)
    and switch to the connect screen once it gives up. After the device
    goes to sleep by itself the app also retries a few times in the
    background, which a sleeping device does not answer.
14. **Lifecycle**: rotate during a live view, a calibration and an update
    (the link and progress survive). Leave the app with Back on Android 11
    or older: the link ends. On Android 12+ Back only moves the app to the
    background and the link stays until the process ends.
15. **Training data**: the join dialog on Android 10+ (approve, decline:
    *Try again* reuses the device's session), whether mobile data stays the
    default with the soft-AP joined, the foreground notification and its
    *Stop* with the screen off, a 20 min take (no stall, the journal's
    size), Bluetooth out of range during a take (the take goes on; after
    2 min the device ends it), walking out of Wi-Fi range (interrupted,
    reconnect), the manual join on Android 8-9 (which network the socket
    takes), and the `rec_state` subscription after a reconnect. On an
    Android 17 phone: the local network permission dialog on the first
    *Start*, the stream with it granted, and what a refusal does (whether a
    network the app requested itself is exempt is not documented).
16. **Upload**: a token set and tested, a saved review uploading on Wi-Fi
    with the notification (and with the screen off), a 20 min take's upload
    (tens of MB: the foreground service on Android 14+ with type dataSync,
    whether it starts from the background: a big one waits for the app to
    be opened, then uploads with its notification), Wi-Fi only holding
    uploads on mobile data (switched on during an upload over mobile data:
    it stops), a revoked token (401: the queue stops), the journal gone
    after the upload, and the commits in the data repo (one per recording,
    "Update labels" after a second Save).
