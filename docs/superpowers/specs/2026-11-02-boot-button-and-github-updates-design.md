# BOOT button, GitHub releases and update notices

## Why

Two separate annoyances, handled together because they ship in the same first
release.

1. **Handling the device.** Today a tap on the level screen zeroes and a 1 s
   hold opens the settings. Zeroing is the action that must not happen by
   accident, and the settings are better on a real button.
2. **Staying up to date.** Updating means building a file on a PC and picking
   it in the app. The owner wants to open the app, connect, and be told when
   GitHub has something newer, for the firmware and for the app itself, and to
   be able to download it from there.

## Goals

- BOOT button (GPIO0) opens the settings and leaves them again.
- On the level screen, the 1 s hold zeroes. A plain tap does nothing.
- A version tag on GitHub publishes one release with the firmware and the APK.
- After connecting, the app shows when newer firmware is on GitHub, and can
  download it and hand it to the existing update screen.
- The app shows when a newer APK is on GitHub, and can download it and hand it
  to Android's installer.
- A fixed release signing key.
- After everything works: the first public release.

## Non-goals

- Automatic installation. Nothing installs without a tap on Install; the
  firmware update stays a deliberate step on the Firmware screen, and Android
  always asks for the APK.
- Background checks, notifications while the app is closed.
- Google Play or any other store.
- Update checks without a network, or a mirror other than GitHub.
- Changing the OTA protocol or the Firmware screen's transfer logic.
- A long-press function on BOOT.

## Anti-goals (failing even if it technically works)

- The private data repo's token reaches the release check. Those requests are
  unauthenticated, to a public repo, and must never touch `TokenVault`.
- An update notice that nags: one that returns for a version the user
  dismissed, or an error banner because the phone is offline.
- A stray press on BOOT during a take or a calibration that changes anything.
- The keystore or its passwords in the repo, in a log or in chat.

## Current state

- `PWR` key (AXP2101): short = sleep, long = power off (`main/control.c`).
  Unchanged.
- BOOT is GPIO0. Nothing in the firmware or the BSP uses it. It has to be read
  as an input with the pull-up on; held at reset it still selects download
  mode, as it should.
- `components/ui/src/ui_level.c` `screen_event_cb`: tap zeroes
  (`LV_EVENT_SHORT_CLICKED`), hold opens the settings (`HOLD_FOR_SETTINGS_MS`,
  in `LV_EVENT_PRESSING`), swipes change the mode.
- `ui.c` `activity_cb`: a touch while dimmed only wakes; during a take only
  the REC pill reacts.
- Version: `PROJECT_VER "0.1.0"` in `firmware/CMakeLists.txt`,
  `versionCode 1` / `versionName "0.1.0"` in `app/build.gradle.kts`.
- GitHub: the repo is public, it has no releases, only the tag `dataset-v1`.
  `ci.yml` builds firmware and tests the app on every push, and uploads the
  firmware as an artifact that needs a login. `release` has no signing config;
  the phone runs a debug build signed with the PC's debug key.
- App: `FirmwareImage.parse` validates an image; `FirmwareViewModel.onFile`
  takes a parsed file; `DeviceInfo.firmwareRevision` is the board's version;
  `MessageBanner` is the shared banner; `HttpGitHubApi` is the existing
  authenticated client for the data repo and is not reused as such.

## Device behaviour

| Input | Level screen | Settings | Calibration screens | Take running | Screen dimmed |
|---|---|---|---|---|---|
| BOOT short press | opens settings | back to level, as the settings' own exit does (pending edits are kept the same way) | ignored | ignored | only wakes, like a tap |
| Touch tap | nothing | as today | as today | as today | only wakes |
| Touch hold 1 s, not moved | zero, with the "ZERO" toast (needs a working IMU, as the tap did) | as today | as today | as today | only wakes |
| Swipe | mode, as today | as today | as today | as today | only wakes |

A press of BOOT counts as activity. A press is debounced, and acts on press
rather than release. The 1 s hold keeps its reason: the screen gets touched
while handling the drill, and now that protects the zero. It fires while still
held, as the settings hold did, so the user sees the toast and knows when to
let go.

The settings keep their existing touch exit. BOOT becomes the only way into the
settings on the device, and the on-device calibrations start there. The owner
confirmed that BOOT is reachable in the enclosure.

Deep sleep wake stays as it is: BOOT does not wake the device.

## Releases

- **Single version:** one `VERSION` file at the repo root holding e.g. `0.2.0`.
  `firmware/CMakeLists.txt` and `app/build.gradle.kts` read it.
  `versionCode = MAJOR * 10000 + MINOR * 100 + PATCH`. *(builder's call: the
  file instead of injecting the tag, so local builds report the real version.)*
- **Trigger:** pushing a tag `vMAJOR.MINOR.PATCH`. The pattern must not match
  `dataset-v1`, which the data repo's Action pins.
- **Workflow:** a separate `release.yml`:
  1. fails if the tag differs from `VERSION`;
  2. runs the same suites as `ci.yml`, or requires them green for that commit;
  3. builds `drillbalancer.bin` and a signed release APK;
  4. publishes a GitHub release named after the tag with the assets
     `drillbalancer-<version>.bin`, `drillbalancer-<version>.apk` and generated
     notes.
- **Permissions:** `contents: write` only for the publishing job.
- **Signing:** a release `signingConfig` read from environment variables, so a
  build without them still works (unsigned or debug-signed) and CI on branches
  needs no secrets. The key is only available to tag builds.

## Key

- A new RSA 4096 keystore with a 25+ year validity, generated with `keytool`
  on the PC.
- Kept outside the repo in `~/Dokumente/Git/DrillBalancer-backup/`, which
  already holds the factory flash backup. The passwords go into a file beside
  it, readable only by the user. The owner should copy both somewhere safe: a
  lost key means every future update needs an uninstall.
- Four GitHub Actions secrets: keystore as base64, store password, key alias,
  key password. The `gh` CLI is not installed, so the owner pastes them in the
  repo's settings page. They are never printed to a terminal or a chat.

## App: checking

- One unauthenticated `GET /repos/Tropaion/Drill_Balancer/releases/latest`
  (the "latest" endpoint skips drafts and prereleases), with its own small
  client. No token, on any path.
- GitHub allows 60 unauthenticated requests per hour per address. One result
  is kept for about 10 minutes in memory, so opening the app and connecting use
  one request.
- Check when the app opens (for the app notice) and when a device connects
  (for the firmware notice).
- Compare versions as three numbers, never as strings. A tag that does not
  parse, or a release without the expected asset, counts as "nothing new".
- "Newer" only: a board on a higher version than the release shows nothing.
- Offline, rate limited, any error: no notice and no message.
- Must work on metered connections, since it is a small request. Downloads
  start only from a tap.

## App: firmware notice

- Once connected, a banner: "Firmware 0.2.0 available" with **Download** and a
  dismiss. Shown where the user lands after connecting, and on the Device tab.
- Dismissing remembers that version (DataStore), so it returns only for a newer
  one.
- **Download** shows progress, keeps the image in memory (at most the 6 MB OTA
  slot, enforced while reading), runs it through `FirmwareImage.parse`, and
  opens the Firmware screen with it loaded through `FirmwareViewModel`, as if
  the file had been picked. The user starts the install there, and the
  existing behaviour applies (same-version warning, Cancel). The update path
  has no battery check today, and this design adds none.
- A failed download, or a parse error, shows the error in the banner with a
  retry. It never reaches the Firmware screen as a loaded image.
- Hidden during an OTA and during a recording session.

## App: app notice

- A banner on the Connect screen and the Device tab: "App 0.2.0 available" with
  **Download** and a dismiss, remembered per version in the same way.
- **Download** stores the APK in the app's cache, then opens Android's
  installer through a `FileProvider`. Needs `REQUEST_INSTALL_PACKAGES`; the
  first time, Android sends the user to "Install unknown apps" for this app,
  and the flow has to survive coming back from there.
- Debug builds never show this notice: their signature cannot update a release
  install or the other way round. Their firmware notice stays, so it can be
  tested.
- The downloaded APK is deleted from the cache afterwards or at the next start.

## Testing

- **Firmware:** host tests for whatever is pure (press logic, the screen table
  above). `ui_preview` flow checks: tap no longer zeroes, hold zeroes, BOOT
  enters and leaves, BOOT ignored in a calibration and a take, first press only
  wakes.
- **Simulator:** a BOOT button on the web panel, mirroring the firmware.
- **App:** version comparison, the check (`MockWebServer`: 200, 404 with no
  release, 403 rate limit, malformed JSON, missing asset, a higher board
  version), no `Authorization` header on any request, per-version dismissal,
  download with oversize and truncated bodies, the banner states as screenshots.
- **Release:** `tools/ci` self-tests for tag/`VERSION` validation. The first
  real run is the release itself.
- **Real board:** BOOT reads correctly on GPIO0 and has no side effect on the
  bus or the display; the table above by hand.
- **Real phone, end to end:** the board on 0.1.0 is offered 0.2.0, downloads
  and installs it over OTA, and comes back as 0.2.0. An app with the release
  key on 0.1.0 is offered 0.2.0 and updates in place, keeping its data.

## Order and the first release

1. Firmware buttons, tested on the board.
2. `VERSION`, signing config, `release.yml`.
3. App check, notices, downloads.
4. Docs: READMEs, wiki (Using-the-Device, Android-App, Hardware-Bring-Up,
   a release section), the `ui.h` comment, the screenshots.
5. Key generated, the owner adds the secrets.
6. Owner confirms nothing unsaved is in the app (recordings not uploaded); the
   debug app is uninstalled and the release-signed app installed once. This
   deletes the app's data, including the upload token. The Bluetooth bond is
   kept by Android.
7. `VERSION` to `0.2.0`, tag `v0.2.0`: the first public release, version 0.2.0
   so that the 0.1.0 board and an 0.1.0 test app really get offered an update.
8. End-to-end check above.

Publishing the release, and each uninstall on the owner's phone, is only done
after the owner's go-ahead at that point.

## Open: PWR double click

The owner reports that a double click on PWR leaves the screen dark and the
device will not turn on. Not diagnosed yet; the two candidates are the expected
deep sleep (PWR cannot wake it, only motion does) and a hang in the I2C driver
during `touch_sleep()`. The fix is added here once the cause is known. Until
then it changes neither the BOOT table nor the releases.

## Decided by the owner

- BOOT opens and leaves the settings.
- The 1 s hold zeroes.
- The update is shown after: open app, connect.
- The app also shows a newer APK.
- A fixed release key, and the first release, once everything works.
- BOOT is reachable in the enclosure.

## Builder's calls, approved by the owner

Everything else above came from the builder and was approved with this
document, in particular: a plain tap does nothing; BOOT is ignored in
calibrations and takes; dismissing is remembered per version; downloads start
from a tap and installs stay manual; the release is 0.2.0; one `VERSION` file;
the app notice is also shown without a device; no battery check is added to
firmware updates.
