# BOOT button, GitHub releases and update notices Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** BOOT opens and leaves the settings, a 1 s touch hold zeroes, a version tag publishes firmware and a signed APK on GitHub, and the app offers both updates.

**Architecture:** The button logic is pure C in `ui_logic.c`; the UI polls GPIO0 on its own 20 ms LVGL timer. One root `VERSION` file feeds CMake and Gradle, and a separate `release.yml` publishes on a tag. A new app package `dev.drillbalancer.update` checks GitHub without a token and downloads on a tap. It hands firmware to the existing Firmware screen and APKs to Android's installer.

**Tech Stack:** ESP-IDF 5.5.1, LVGL 9, Unity host tests, Python simulator, Kotlin, Compose, DataStore, `HttpURLConnection`, kotlinx-serialization, MockWebServer, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-02-boot-button-and-github-updates-design.md`

## Global Constraints

- BOOT: GPIO0, input with pull-up, acts on press, debounced; no long-press function; does not wake deep sleep.
- Hold to zero: 1000 ms, not moved, needs a working IMU, fires while held, shows the "ZERO" toast (700 ms, green).
- A plain tap on the level screen does nothing. Swipes are unchanged.
- Tag pattern `vMAJOR.MINOR.PATCH`; it must not match `dataset-v1`.
- `versionCode = MAJOR * 10000 + MINOR * 100 + PATCH`; MINOR and PATCH stay below 100.
- Assets: `drillbalancer-<version>.bin`, `drillbalancer-<version>.apk`.
- `contents: write` only for the publishing job.
- Release check: unauthenticated `GET /repos/Tropaion/Drill_Balancer/releases/latest`, kept about 10 minutes in memory. It never touches `TokenVault`, and no request carries an `Authorization` header.
- Offline, rate limited or any error: no notice and no message.
- Downloads start only from a tap. Firmware stays in memory, at most `FirmwareImage.OTA_SLOT_SIZE` (6 MB), enforced while reading.
- Dismissal is remembered per version in DataStore.
- Debug builds never show the app notice; their firmware notice stays.
- The keystore and its passwords never reach the repo, a log, a terminal or chat. Scripts must not echo them.
- Publishing the release, and each uninstall on the owner's phone, needs the owner's go-ahead at that point.
- Phone rules from earlier in the project apply: only `dev.drillbalancer`; stop if the phone is locked or in use.
- Public docs must not mention a custom PCB, KiCad or a hardware folder.

## Process for every task

Project rule, it replaces a bare commit step. Each code task ends with:

1. `/code-review`, fix what is real.
2. `/simplify`, fix what is real.
3. All suites, run by the implementer: `tools/ci/firmware_host.sh`, `tools/ci/firmware_build.sh`, `tools/ci/simulator.sh`, `tools/ci/dataset.sh`, `tools/ci/test_ci.sh`, `tools/ci/db_client.sh`, `tools/ci/app.sh`.
4. Commit and push to `main`, ending the message with:
   ```
   Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_01YF7XT5Efzfgo2XwtGXLBEm
   ```
5. Confirm CI is green: `https://api.github.com/repos/Tropaion/Drill_Balancer/actions/runs?head_sha=<sha>`.

The Task 1 commit also adds `docs/superpowers/`.

Each task's "Finish" step means this list.

## Review Focus

1. **BOOT pressed while a finger is on the level screen:** the finger must not zero after the switch or step a setting on the new screen. Test in Task 2.
2. **BOOT held for seconds, or bouncing:** exactly one action per press, never settings open and closed again. Tests in Tasks 1 and 2.
3. **GitHub redirects an asset download to another host:** the download follows it, and no request has an `Authorization` header. Test in Task 8.
4. **The session ends during a firmware download:** the download stops, nothing opens the Firmware screen later, and the image is released. Test in Task 9.
5. **Install is cancelled, or "Install unknown apps" is refused:** the notice stays on **Install**, no error, and a later tap works. Test in Task 10.

---

### Task 1: Pure BOOT key logic

**Files:**
- Modify: `firmware/components/ui/src/ui_logic.h`
- Modify: `firmware/components/ui/src/ui_logic.c`
- Test: `firmware/test_host/ui_preview/test_ui_logic.c`

**Interfaces:**
- Produces, in `ui_logic.h`:
  ```c
  #define UI_KEY_POLL_MS 20
  typedef struct { uint8_t down; uint8_t up; bool held; } ui_key_t;   // zeroed = released
  bool ui_key_update(ui_key_t *k, bool down);

  typedef enum { UI_KEY_NONE, UI_KEY_TO_SETTINGS, UI_KEY_TO_LEVEL } ui_key_action_t;
  typedef struct {
      bool on_level, on_settings, dimmed, take, overlay, confirm;
  } ui_key_ctx_t;
  ui_key_action_t ui_key_action(const ui_key_ctx_t *c);
  ```

- [ ] **Step 1: Write the failing tests**

  - `test_key_fires_once_per_press`: `false`, `true` → no fire; second `true` → fires; 200 more `true` → no fire.
  - `test_key_ignores_a_one_poll_glitch`: `true, false, true, false` → never fires.
  - `test_key_rearms_after_two_polls_up`: after a fire, `false, true, true` does not fire again; `false, false, true, true` fires.
  - `test_key_action_table`:

    | ctx | result |
    |---|---|
    | `on_level` | `UI_KEY_TO_SETTINGS` |
    | `on_settings` | `UI_KEY_TO_LEVEL` |
    | neither (calibration) | `UI_KEY_NONE` |
    | `on_settings, confirm` | `UI_KEY_NONE` |
    | any screen plus `dimmed`, `take` or `overlay` | `UI_KEY_NONE` |

- [ ] **Step 2: Run them to see them fail**

  Run: `tools/ci/firmware_host.sh`
  Expected: `test_ui_logic` does not compile, `ui_key_update` undeclared.

- [ ] **Step 3: Implement both functions in `ui_logic.c`**

  A press needs 2 consecutive polls down; the next press needs 2 consecutive polls up first. `ui_key_action` returns `UI_KEY_NONE` first for `dimmed`, `take` and `overlay`. Add comments in the style of the header: why 20 ms (a quick press is about 80 ms, too short for two polls of the 66 ms UI tick).

- [ ] **Step 4: Run them to see them pass**

  Run: `tools/ci/firmware_host.sh`
  Expected: all suites pass.

- [ ] **Step 5: Finish.** Commit message: `Add the BOOT key logic`.

### Task 2: BOOT and hold-to-zero on the device UI

**Files:**
- Modify: `firmware/components/ui/src/ui.c`, `ui_level.c`, `ui_settings.c`, `ui_priv.h`, `ui_display.c`, `ui_logic.c:113`, `ui_logic.h:44`
- Modify: `firmware/components/ui/include/ui.h` (the Level comment, plus BOOT)
- Modify: `firmware/components/ui/CMakeLists.txt` (add `esp_driver_gpio` to `PRIV_REQUIRES`)
- Test: `firmware/test_host/ui_preview/preview.c`, `stubs/preview_stubs.c`, `stubs/preview_stubs.h`, `test_ui_logic.c:160`

**Interfaces:**
- Consumes: `ui_key_update`, `ui_key_action`, `UI_KEY_POLL_MS` (Task 1).
- Produces, in `ui_priv.h`:
  ```c
  bool ui_boot_key_down(void);           // ui_display.c; preview_stubs.c on the host
  bool ui_settings_confirm_open(void);   // ui_settings.c
  ```
- Produces, in the preview stubs: `extern bool g_boot_down;`, cleared by `preview_stubs_reset()`.

- [ ] **Step 1: Change the preview flows to the new behaviour**

  Add to `preview.c`:
  ```c
  static void boot_press(void)
  {
      g_boot_down = true;
      run(100);
      g_boot_down = false;
      run(100);
  }
  ```
  Replace every `hold(CX, CY, 1200)` that means "open the settings" with `boot_press()`, and rewrite these checks:

  - `touch_checks`: `tap(CX, CY)` leaves the ZERO count unchanged. `hold(CX, CY, 600)` does not zero. `hold(CX, CY, 1200)` zeroes once and stays on the level screen. Render `14_zero_flash` right after that hold. Update every absolute ZERO count that follows.
  - A dimmed tap and a dimmed hold still do nothing.
  - `rec_touch_checks`: the 1.2 s hold on the pill and the hold during a take do not zero and do not open the settings. The "hold beneath it is the level" check after a take expects a zero.
  - Add `boot_checks()`, called from the same place as `touch_checks`:
    - level → settings → level with two `boot_press()` calls;
    - `g_boot_down = true; run(3000);` toggles once only;
    - during `APP_REC_STATE_RECORDING`, in `UI_SCREEN_MOUNT_CAL`, in `UI_SCREEN_SENSOR_CAL`, with `g_snap.ota_active` and with `g_snap.passkey_pending`: no screen change;
    - with the factory reset confirmation open (`ui_settings_confirm_reset()`): no screen change;
    - `g_snap.power = APP_POWER_DIM`: no screen change, and `g_activity_count` rises;
    - finger held at `(CX, CY)` for 300 ms, `boot_press()` while still held, finger kept 1000 ms more: `ui_current() == UI_SCREEN_SETTINGS`, ZERO count unchanged, `g_settings_store_count` unchanged.

  Add `int g_activity_count` to the stubs: `app_note_activity()` is empty today and has to count, and `preview_stubs_reset()` clears it.

  In `test_ui_logic.c:160` expect `"Hold to zero for left/right"`.

- [ ] **Step 2: Run them to see them fail**

  Run: `tools/ci/firmware_host.sh`
  Expected: `test_ui_logic` and the preview target fail.

- [ ] **Step 3: Change `ui_level.c`**

  Rename to `HOLD_FOR_ZERO_MS`. In `LV_EVENT_PRESSING`, on the hold: set `press_used`, and when `s.imu_ok` and `app_post_cmd(APP_CMD_ZERO, 0)` succeeds, show the toast. Remove the `LV_EVENT_SHORT_CLICKED` case. Keep the "why one second" comment, reworded for the zero. `press_used` and `TAP_MAX_MOVE_PX` stay. Change the hint in `ui_logic.c:113` and its comment.

- [ ] **Step 4: Add the GPIO to `ui_display.c`**

  In `ui_display_start()`, `gpio_config` GPIO0 as input with the pull-up on, pull-down off, no interrupt. `#define UI_BOOT_GPIO GPIO_NUM_0` with a comment: nothing in the BSP uses it, and held at reset it still selects download mode. `ui_boot_key_down()` returns `gpio_get_level(UI_BOOT_GPIO) == 0`.

- [ ] **Step 5: Add the key timer to `ui.c`**

  A `key_timer_cb` on `lv_timer_create(..., UI_KEY_POLL_MS, NULL)`, created in `ui_init()`:

  - return while `s_power` is SLEEP or OFF;
  - `if (!ui_key_update(&s_key, ui_boot_key_down())) return;`
  - `app_note_activity()`;
  - build `ui_key_ctx_t` from `s_current`, `s_power == APP_POWER_DIM`, `ui_rec_pill_live(&snap.rec)`, `snap.ota_active || snap.passkey_pending` and `ui_settings_confirm_open()`;
  - on an action, call `lv_indev_wait_release()` for every indev from `lv_indev_get_next()` (`lv_indev_active()` is NULL outside an event, so `ui_show()` cannot do this), then `ui_show()`.

  Add `ui_settings_confirm_open()` to `ui_settings.c`. BOOT never calls `recorder_note_touch()`.

  Update the stale comment in `rec_pill_cb` ("arms no settings hold"), and the `ui.h` comment with tap, hold, BOOT and the "first press only wakes" rule.

- [ ] **Step 6: Run the checks**

  Run: `tools/ci/firmware_host.sh`
  Expected: all suites pass, preview "touch checks" and "boot checks" pass.

  Open `firmware/test_host/ui_preview/out/14_zero_flash.png` and the wall screen; check the new hint fits inside the round screen.

- [ ] **Step 7: Build the firmware**

  Run: `tools/ci/firmware_build.sh`
  Expected: builds, "No compiler warnings in components/ or main/."

- [ ] **Step 8: Finish.** Commit message: `Open the settings with BOOT and zero on a one second hold`.

### Task 3: BOOT in the simulator

**Files:**
- Modify: `firmware/tools/sim/dbsim/web/screen.py`, `server.py`, `index.html`, `app.js`, `touch.js`
- Modify: `firmware/tools/sim/README.md:105-118`
- Test: `firmware/tools/sim/tests/test_web.py`

**Interfaces:**
- Produces: panel command `{"cmd": "boot"}` → `WebPanel.c_boot`; `DeviceScreen.boot() -> str`.

- [ ] **Step 1: Write the failing tests**

  In `test_web.py` add `def boot(self): what = self.cmd(cmd="boot"); self.dev.advance(300); return what`. Replace every `self.touch("hold")` that opens the settings with `self.boot()`.

  - `test_tap_does_not_zero`: `touch("tap") == ""`, `zero_set` stays false.
  - `test_hold_zeroes`: `touch("hold") == "zero"`, toast "ZERO", screen stays `level`.
  - `test_boot_opens_and_leaves_settings`.
  - `test_boot_ignored`: during a take, on `mount_cal`, on `sensor_cal`, with an overlay, with the confirmation open; each returns a string starting with `ignored`.
  - `test_boot_only_wakes_dimmed_screen`: on a dimmed screen the screen stays `level` and activity is noted.

- [ ] **Step 2: Run them to see them fail**

  Run: `tools/ci/simulator.sh`
  Expected: `BadRequest` for `boot`, and `zero` failures on the tap.

- [ ] **Step 3: Implement the mirror**

  - `screen.py`: tap on the level screen returns `""`; `hold` runs today's zero code; rename the constant `HOLD_FOR_ZERO_MS`; update the docstring.
  - Add `boot()`, mirroring `ui_key_action`, and calling `self.dev.touch()`'s activity through `self.dev.core.note_activity(self.dev.core_now)`: not `dev.touch()`, because BOOT is no EVENT TOUCH.
  - `server.py`: `c_boot`.
  - `index.html`: a `BOOT button` row beside `PWR key`, button id `boot-key`, and the canvas title.
  - `app.js`: its click handler `post({cmd: "boot"})`.
  - `touch.js`: the comments.
  - `README.md`: the gesture table.

- [ ] **Step 4: Run them to see them pass**

  Run: `tools/ci/simulator.sh`
  Expected: all simulator tests pass.

- [ ] **Step 5: Finish.** Commit message: `Mirror the BOOT button in the simulator`.

### Task 4: BOOT on the real board

Owner task: the owner presses the buttons. No code unless a check fails.

- [ ] **Step 1: Flash**

  Run: `. ~/esp/v5.5.1/esp-idf/export.sh && cd firmware && idf.py -B build-ci -D SDKCONFIG=build-ci/sdkconfig -p /dev/ttyACM0 app-flash`
  Expected: flashed. If no port shows, ask the owner to plug the board in; deep sleep also removes it.

- [ ] **Step 2: Owner checks the table in the spec's "Device behaviour" section**

  - BOOT opens and leaves the settings.
  - A tap does nothing; a 1 s hold zeroes with the toast.
  - BOOT ignored in both calibrations.
  - First press on a dimmed screen only wakes.
  - Screen and touch keep working after 20 BOOT presses.

- [ ] **Step 3: Owner tests the PWR double click on USB, as asked earlier**

  Double-click PWR, then shake the board. If shaking wakes it, it was the expected deep sleep; if not, capture the log with `mon.py`. Add the cause and fix to the spec's "Open: PWR double click" section. A fix is a new task before Task 12, through this same process.

### Task 5: One `VERSION` file

**Files:**
- Create: `VERSION` (content `0.1.0`)
- Modify: `firmware/CMakeLists.txt:3`
- Modify: `app/build.gradle.kts:18-19`
- Test: `app/src/test/java/dev/drillbalancer/update/VersionFileTest.kt`

**Interfaces:**
- Produces: `BuildConfig.VERSION_NAME` and `BuildConfig.VERSION_CODE` from `VERSION`; firmware `PROJECT_VER` from `VERSION`.

- [ ] **Step 1: Write the failing test `VersionFileTest`**

  - `versionNameIsTheFile`: `BuildConfig.VERSION_NAME == File("../VERSION").readText().trim()`.
  - `versionCodeFollowsTheFormula`: `BuildConfig.VERSION_CODE == major * 10000 + minor * 100 + patch`.

  Add `"../VERSION"` to the `firmwareSources` inputs in `app/build.gradle.kts`, so a change re-runs the tests. `FirmwareSources` shows how tests find the repo root.

- [ ] **Step 2: Run it to see it fail**

  Run: `tools/ci/app.sh --tests '*VersionFileTest*'`
  Expected: FAIL, `VERSION` missing and `versionCode` is 1.

- [ ] **Step 3: Implement**

  `firmware/CMakeLists.txt`, replacing the `set`:
  ```cmake
  # The one version of firmware and app; the release tag must match it.
  file(STRINGS ${CMAKE_CURRENT_LIST_DIR}/../VERSION PROJECT_VER LIMIT_COUNT 1)
  set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${CMAKE_CURRENT_LIST_DIR}/../VERSION)
  ```

  `app/build.gradle.kts`, before `android {`:
  ```kotlin
  val appVersion = providers.fileContents(layout.projectDirectory.file("../VERSION")).asText.get().trim()
  val versionParts = Regex("""(\d+)\.(\d{1,2})\.(\d{1,2})""").matchEntire(appVersion)?.destructured?.toList()?.map(String::toInt)
      ?: error("VERSION is not MAJOR.MINOR.PATCH: $appVersion")
  ```
  Then `versionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]` and `versionName = appVersion`.

  The simulator needs no change: it takes its version from the built image (`device.py` `image_version`).

- [ ] **Step 4: Check both builds**

  Run: `tools/ci/app.sh --tests '*VersionFileTest*'`
  Expected: PASS.

  Run: `tools/ci/firmware_build.sh && python3 -c "import json; print(json.load(open('firmware/build-ci/project_description.json'))['project_version'])"`
  Expected: `0.1.0`.

- [ ] **Step 5: Finish.** Commit message: `Keep the version of firmware and app in one file`.

### Task 6: Release signing and workflow

**Files:**
- Create: `tools/ci/release_check.sh`, `tools/ci/release_apk.sh`, `.github/workflows/release.yml`
- Modify: `app/build.gradle.kts` (signing), `tools/ci/test_ci.sh`

**Interfaces:**
- Produces, environment for Gradle: `DB_RELEASE_KEYSTORE` (path), `DB_RELEASE_STORE_PASSWORD`, `DB_RELEASE_KEY_ALIAS`, `DB_RELEASE_KEY_PASSWORD`.
- Produces, GitHub secrets: `DB_RELEASE_KEYSTORE_B64`, `DB_RELEASE_STORE_PASSWORD`, `DB_RELEASE_KEY_ALIAS`, `DB_RELEASE_KEY_PASSWORD`.
- Produces: `tools/ci/release_check.sh TAG`, which exits 0 and prints the version; `tools/ci/release_apk.sh OUT_DIR`, which leaves `OUT_DIR/drillbalancer-<version>.apk`.

- [ ] **Step 1: Write the failing self-tests in `test_ci.sh`**

  Copy a `VERSION` file into the scratch repo and test `release_check.sh`:

  | `VERSION` | tag | exit | output |
  |---|---|---|---|
  | `0.2.0` | `v0.2.0` | 0 | `0.2.0` |
  | `0.2.0` | `v0.2.1` | non-zero | names both versions |
  | `0.2.0` | `dataset-v1` | non-zero | |
  | `0.2` | `v0.2` | non-zero | |
  | `0.100.0` | `v0.100.0` | non-zero | |

- [ ] **Step 2: Run them to see them fail**

  Run: `tools/ci/test_ci.sh`
  Expected: NOT OK, script missing.

- [ ] **Step 3: Implement `release_check.sh`**

  Uses `common.sh` and `$ROOT/VERSION`; regex `^[0-9]+\.[0-9]{1,2}\.[0-9]{1,2}$`. In CI, append `version=<v>` to `$GITHUB_OUTPUT`.

- [ ] **Step 4: Add signing to `app/build.gradle.kts`**

  ```kotlin
  signingConfigs {
      providers.environmentVariable("DB_RELEASE_KEYSTORE").orNull?.let { path ->
          create("release") {
              storeFile = file(path)
              storePassword = providers.environmentVariable("DB_RELEASE_STORE_PASSWORD").get()
              keyAlias = providers.environmentVariable("DB_RELEASE_KEY_ALIAS").get()
              keyPassword = providers.environmentVariable("DB_RELEASE_KEY_PASSWORD").get()
          }
      }
  }
  ```
  In `release`: `signingConfig = signingConfigs.findByName("release")`. Without the variable, the release stays unsigned and needs no secrets.

- [ ] **Step 5: Implement `release_apk.sh`**

  - Fails with a message naming the missing variable, never its value.
  - With `DB_RELEASE_KEYSTORE_B64` set, decode it to a `mktemp` file, with `trap` removing it on exit, and export its path as `DB_RELEASE_KEYSTORE`.
  - Run `./gradlew --no-configuration-cache --no-build-cache assembleRelease`, because `gradle.properties` turns both caches on and they would keep the passwords.
  - No `set -x`.
  - Verify with `$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs build/outputs/apk/release/DrillBalancer-release.apk`; find the SDK as `tools/ci/app.sh` documents.
  - Copy to `OUT_DIR/drillbalancer-$(cat VERSION).apk`.

- [ ] **Step 6: Write `.github/workflows/release.yml`**

  Use the action versions of `ci.yml`.

  ```yaml
  name: Release
  on:
    push:
      tags: ['v[0-9]+.[0-9]+.[0-9]+']
  permissions:
    contents: read
  ```

  Jobs, each step one script as in `ci.yml`:

  | Job | Needs | Does |
  |---|---|---|
  | `check` | | `permissions: {contents: read, actions: read}`. Runs `tools/ci/release_check.sh "$GITHUB_REF_NAME"`. Then fails unless `gh api "repos/$GITHUB_REPOSITORY/actions/workflows/ci.yml/runs?head_sha=$GITHUB_SHA&status=success&per_page=1" --jq .total_count` is above 0. Outputs `version`. |
  | `firmware` | `check` | Container `espressif/idf:v5.5.1`, `tools/ci/firmware_build.sh`, uploads `firmware/build-ci/drillbalancer.bin`. |
  | `apk` | `check` | Java, Android and Gradle as in `ci.yml`, with `gradle/actions/setup-gradle` `cache-disabled: true`. `tools/ci/release_apk.sh "$RUNNER_TEMP/apk"` with the four secrets in `env`. Uploads the APK. |
  | `publish` | `check, firmware, apk` | `permissions: {contents: write}`. Downloads both, renames the bin to `drillbalancer-<version>.bin`. `gh release create "$GITHUB_REF_NAME" --repo "$GITHUB_REPOSITORY" --title "$GITHUB_REF_NAME" --generate-notes` with both files, `GH_TOKEN: ${{ github.token }}`. |

  Add a comment at the top: the suites do not run again; CI on the tagged commit must be green, so push the version bump and wait for CI before tagging.

- [ ] **Step 7: Check everything without a key**

  Run: `tools/ci/test_ci.sh`
  Expected: all ok.

  Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/release.yml'))"`
  Expected: no error.

  Run: `cd app && ./gradlew assembleRelease`
  Expected: BUILD SUCCESSFUL with `DrillBalancer-release-unsigned.apk`. Lint failures from `lintVitalRelease` are real findings: fix them.

- [ ] **Step 8: Finish.** Commit message: `Add release signing and a tag-triggered release workflow`. The workflow's first real run is Task 14.

### Task 7: App, check GitHub for a release

**Files:**
- Create: `app/src/main/java/dev/drillbalancer/update/ReleaseVersion.kt`, `ReleaseSource.kt`, `UpdateChecker.kt`, `UpdateStore.kt`
- Test: `app/src/test/java/dev/drillbalancer/update/ReleaseVersionTest.kt`, `HttpReleaseSourceTest.kt`, `UpdateCheckerTest.kt`, `UpdateStoreTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
      companion object { fun parse(text: String?): ReleaseVersion? }   // "0.2.0" or "v0.2.0" only
  }
  data class ReleaseAsset(val name: String, val url: String, val size: Long)
  data class ReleaseInfo(val version: ReleaseVersion, val firmware: ReleaseAsset?, val apk: ReleaseAsset?)
  sealed interface ReleaseResult {
      data class Found(val info: ReleaseInfo) : ReleaseResult
      data object None : ReleaseResult       // 404, or a tag that does not parse
      data object Failed : ReleaseResult     // offline, rate limited, malformed
  }
  fun interface ReleaseSource { suspend fun latest(): ReleaseResult }
  class HttpReleaseSource(baseUrl: String = "https://api.github.com", repo: String = "Tropaion/Drill_Balancer") : ReleaseSource
  class UpdateChecker(source: ReleaseSource, clock: () -> Long = System::currentTimeMillis) {
      val latest: StateFlow<ReleaseInfo?>
      suspend fun refresh()
  }
  class UpdateStore(store: DataStore<Preferences>) {
      val dismissedFirmware: Flow<String?>
      val dismissedApp: Flow<String?>
      suspend fun dismissFirmware(version: String)
      suspend fun dismissApp(version: String)
  }
  ```

- [ ] **Step 1: Write the failing tests**

  - `ReleaseVersionTest`:
    - `0.2.0`, `v0.2.0` parse;
    - `0.10.0 > 0.9.0` (numbers, not strings);
    - `""`, `null`, `1.2`, `1.2.3.4`, `1.2.x`, `0.2.0-rc1`, `99999999999.0.0` give null.
  - `HttpReleaseSourceTest` on MockWebServer:
    - 200 gives `Found` with both assets, matched by the exact names `drillbalancer-0.2.0.bin` and `.apk`;
    - a missing asset gives `Found` with a null field;
    - 404 gives `None`;
    - 403 gives `Failed`;
    - malformed JSON gives `Failed`;
    - a tag like `dataset-v1` gives `None`;
    - the server closing the connection gives `Failed`;
    - the request path is exactly `/repos/Tropaion/Drill_Balancer/releases/latest`;
    - `request.headers["Authorization"] == null`.
  - `UpdateCheckerTest`:
    - two `refresh()` calls within 10 minutes make one source call;
    - after 10 minutes and 1 ms a second call;
    - concurrent calls make one source call;
    - `Failed` keeps the last value and is not cached (the next `refresh` calls again);
    - `None` is cached and clears `latest`.
  - `UpdateStoreTest`: a dismissal is stored per kind and survives a new `UpdateStore` on the same DataStore.

- [ ] **Step 2: Run them to see them fail**

  Run: `tools/ci/app.sh --tests 'dev.drillbalancer.update.*'`
  Expected: compile errors.

- [ ] **Step 3: Implement**

  - `HttpReleaseSource` on `HttpURLConnection` in `Dispatchers.IO`, connect timeout 10 s, read timeout 10 s, headers `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`, `User-Agent: DrillBalancer-app`.
  - JSON with kotlinx-serialization, `ignoreUnknownKeys = true`, reading `tag_name` and `assets[].{name, browser_download_url, size}`.
  - Catch `IOException` and `SerializationException`; `CancellationException` passes.
  - No dependency on `dev.drillbalancer.upload`.
  - `UpdateChecker`: a `Mutex`, `TTL_MS = 10 * 60 * 1000L`.
  - `UpdateStore`: DataStore named `"updates"`, created as `LastDeviceStore` does.

- [ ] **Step 4: Run them to see them pass**

  Run: `tools/ci/app.sh --tests 'dev.drillbalancer.update.*'`
  Expected: PASS.

- [ ] **Step 5: Finish.** Commit message: `Check GitHub for the latest release`.

### Task 8: App, download a release asset

**Files:**
- Create: `app/src/main/java/dev/drillbalancer/update/ReleaseDownloader.kt`
- Test: `app/src/test/java/dev/drillbalancer/update/ReleaseDownloaderTest.kt`

**Interfaces:**
- Consumes: `ReleaseAsset` (Task 7).
- Produces:
  ```kotlin
  class DownloadException(message: String) : IOException(message)
  class ReleaseDownloader(urlPrefix: String = RELEASE_URL_PREFIX) {
      suspend fun fetch(asset: ReleaseAsset, maxBytes: Long, out: OutputStream, onProgress: (percent: Int) -> Unit)
  }
  const val RELEASE_URL_PREFIX = "https://github.com/Tropaion/Drill_Balancer/releases/download/"
  ```

- [ ] **Step 1: Write the failing tests**

  - `writesTheBody`: bytes equal; `onProgress` ends at 100.
  - `refusesAnAssetLargerThanTheLimit`: `asset.size > maxBytes` throws before any request (`server.requestCount == 0`).
  - `stopsWhenTheBodyGrowsPastTheLimit`: a server sending more than `maxBytes` despite a small `asset.size` throws, and at most `maxBytes` bytes reached `out`.
  - `truncatedBodyFails`: fewer bytes than `asset.size` throws.
  - `serverErrorFails`: 500 throws with a readable message.
  - `refusesAnotherHost`: a URL outside `urlPrefix` throws before any request.
  - `followsARedirect`: 302 with `Location` to a second path gives the body; `Authorization` is null on both requests.
  - `cancelStopsReading`: cancelling the job during a throttled body ends `fetch` with `CancellationException`.

- [ ] **Step 2: Run them to see them fail**

  Run: `tools/ci/app.sh --tests '*ReleaseDownloaderTest*'`
  Expected: compile errors.

- [ ] **Step 3: Implement `fetch`**

  `Dispatchers.IO`, connect timeout 15 s, read timeout 30 s. Read 8 KB at a time, `ensureActive()` per chunk; report only changed percents. Translate `IOException` to `DownloadException`, with `connection.disconnect()` in `finally`. `HttpURLConnection` follows redirects within https, which is GitHub's case. Do not add a host check on the redirect target: GitHub moves it between storage hosts.

- [ ] **Step 4: Run them to see them pass**

  Run: `tools/ci/app.sh --tests '*ReleaseDownloaderTest*'`
  Expected: PASS.

- [ ] **Step 5: Finish.** Commit message: `Download release assets with a size limit`.

### Task 9: App, firmware notice

**Files:**
- Create: `app/src/main/java/dev/drillbalancer/ui/update/UpdatesViewModel.kt`, `UpdateBanner.kt`
- Create: `app/src/main/java/dev/drillbalancer/update/UpdateDeps.kt`
- Modify: `DrillBalancerApp.kt`, `MainActivity.kt`, `ui/AppUi.kt`, `ui/live/LiveScreen.kt`, `ui/device/DeviceScreen.kt`
- Test: `app/src/test/java/dev/drillbalancer/ui/update/UpdatesViewModelTest.kt`, `UpdateBannerScreenshotTest.kt`, extend `ui/device/DeviceScreenshotTest.kt`

**Interfaces:**
- Consumes: `UpdateChecker`, `UpdateStore`, `ReleaseDownloader`, `FirmwareImage.parse`, `DeviceSession.connection`, `DeviceSession.info`, `FirmwareViewModel.onFile`.
- Produces:
  ```kotlin
  class UpdateDeps(
      val checker: UpdateChecker, val store: UpdateStore, val downloader: ReleaseDownloader,
      val appVersion: ReleaseVersion?, val appNotices: Boolean,
  ) {
      companion object { fun disabled(...): UpdateDeps }   // no network, no notice: AppRoot's default for tests
  }
  enum class UpdateKind { FIRMWARE, APP }
  sealed interface UpdateStage {
      data object Available : UpdateStage
      data class Downloading(val percent: Int) : UpdateStage
      data object Ready : UpdateStage              // APP only: Install
      data class Failed(val message: String) : UpdateStage
  }
  data class UpdateNotice(val kind: UpdateKind, val version: String, val stage: UpdateStage)
  data class UpdatesUiState(val firmware: UpdateNotice? = null, val app: UpdateNotice? = null)
  data class DownloadedFirmware(val name: String, val parsed: FirmwareParseResult)
  class UpdatesViewModel(deps: UpdateDeps, session: DeviceSession, busy: Flow<Boolean>) : ViewModel() {
      val state: StateFlow<UpdatesUiState>
      val firmwareReady: StateFlow<Boolean>
      fun downloadFirmware(); fun dismissFirmware()
      fun takeFirmware(): DownloadedFirmware?     // clears it and firmwareReady
  }
  @Composable fun UpdateBanner(notice: UpdateNotice, onDownload: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier)
  ```

- [ ] **Step 1: Write the failing `UpdatesViewModelTest`**

  Uses `FakeDeviceSession`, a fake `ReleaseSource`, an in-memory `DataStore`, and a real `ReleaseDownloader` on a MockWebServer with a `urlPrefix` for it.

  - `showsNoticeWhenReleaseIsNewer`: board `0.1.0`, release `0.2.0` → `firmware.version == "0.2.0"`, stage `Available`.
  - `noNoticeForSameOrHigherBoard`: board `0.2.0` and `0.3.0`.
  - `noNoticeForUnparsableBoardVersion`.
  - `noNoticeWithoutFirmwareAsset`.
  - `noNoticeWhileDisconnected`.
  - `noNoticeWhileBusy`.
  - `sourceFailureShowsNothing`.
  - `checksOnStartAndOnConnect`: source called on start; after 10 minutes on the fake clock, a new connect calls it again.
  - `dismissHidesThatVersionOnly`: dismissed `0.2.0` stays hidden across a new view model; release `0.3.0` shows.
  - `noticeGoesAwayAfterUpdate`: `session.info` turns to `0.2.0` → `firmware == null`.
  - `downloadParsesAndHandsOver`: the image from `firmwareBytes("0.2.0")` in `ui/Fixtures.kt`; `firmwareReady` true; `takeFirmware()` gives `Ok`, then null.
  - `wrongVersionInImageFails`: an image reporting `0.1.9` for release `0.2.0` → `Failed`, `firmwareReady` false.
  - `garbageBodyFails`: `Failed`, never `firmwareReady`.
  - `failedDownloadCanRetry`.
  - `sessionEndCancelsDownload`: a throttled body, then a disconnect without reconnecting → stage back to nothing, `firmwareReady` false, `takeFirmware() == null`.
  - `reconnectKeepsDownload`: a drop with `reconnecting = true` keeps it running.

- [ ] **Step 2: Run them to see them fail**

  Run: `tools/ci/app.sh --tests '*UpdatesViewModelTest*'`
  Expected: compile errors.

- [ ] **Step 3: Implement the view model and `UpdateBanner`**

  - The notice is computed from `combine` of `checker.latest`, `session.connection`, `session.info`, `store.dismissedFirmware`, `busy` and the download stage. Connected only.
  - `init` launches `checker.refresh()`, and again whenever `connection` turns `Connected`.
  - Download buffer: `ByteArrayOutputStream`, limit `FirmwareImage.OTA_SLOT_SIZE`; parse on `Dispatchers.Default`.
  - An image whose `version` differs from the release fails with "This download is not firmware <version>."
  - `UpdateBanner` uses `MessageBanner`:

    | Stage | Message | Kind | Action |
    |---|---|---|---|
    | Available | `Firmware 0.2.0 available` / `App 0.2.0 available` | `INFO` | `Download`, Dismiss |
    | Downloading | `Downloading firmware 0.2.0: 42 %` | `INFO` | none |
    | Ready | `App 0.2.0 downloaded` | `SUCCESS` | `Install`, Dismiss |
    | Failed | the message | `ERROR` | `Retry`, Dismiss |

- [ ] **Step 4: Wire it**

  - `DrillBalancerApp`: `val updates: UpdateDeps by lazy { ... }` with `appVersion = ReleaseVersion.parse(BuildConfig.VERSION_NAME)` and `appNotices = !BuildConfig.DEBUG`.
  - `MainActivity`: pass `app.updates`.
  - `AppRoot(..., updates: UpdateDeps = UpdateDeps.disabled())`: the view model at this level, `viewModel { UpdatesViewModel(updates, session, training.controller.phase.map { it.active }) }`, passed to `ConnectedApp`.
  - `LiveScreen` and `DeviceScreen` get `banner: @Composable () -> Unit = {}`: `LiveScreen` calls it right after `ModeTabs`, `DeviceScreen` first in its scrolling column. The defaults keep the existing screenshots unchanged.
  - The firmware screen shows no banner, and it blocks back during a transfer, so no notice shows during an OTA.
  - In `ConnectedApp`: `LaunchedEffect(firmwareReady) { if (firmwareReady) nav.navigate(Dest.Firmware) { launchSingleTop = true } }`.
  - In `composable<Dest.Firmware>`: `LaunchedEffect(vm) { updates.takeFirmware()?.let { vm.onFile(it.name, it.parsed) } }`.

- [ ] **Step 5: Add the screenshots**

  `UpdateBannerScreenshotTest` records each stage, and `DeviceScreenshotTest` adds one with the banner. Run: `cd app && ./gradlew recordRoborazziDebug --tests '*UpdateBannerScreenshotTest*' --tests '*DeviceScreenshotTest*'`. Look at each new image.

- [ ] **Step 6: Run them to see them pass**

  Run: `tools/ci/app.sh`
  Expected: tests, lint and `verifyRoborazziDebug` pass, and no existing screenshot changed.

- [ ] **Step 7: Finish.** Commit message: `Offer new firmware from GitHub after connecting`.

### Task 10: App, app notice and install

**Files:**
- Create: `app/src/main/java/dev/drillbalancer/update/ApkInstaller.kt`, `app/src/main/res/xml/update_paths.xml`
- Modify: `app/src/main/AndroidManifest.xml`, `update/UpdateDeps.kt`, `ui/update/UpdatesViewModel.kt`, `ui/AppUi.kt`, `ui/connect/ConnectRoute.kt`, `ui/connect/ConnectScreen.kt`, `DrillBalancerApp.kt`
- Test: extend `UpdatesViewModelTest.kt`, `UpdateBannerScreenshotTest.kt`; add one screenshot to `ui/connect`

**Interfaces:**
- Consumes: Task 9.
- Produces:
  ```kotlin
  interface ApkInstaller {
      val cacheDir: File                 // <cacheDir>/updates
      fun canInstall(): Boolean          // canRequestPackageInstalls()
      fun openInstallPermission()        // ACTION_MANAGE_UNKNOWN_APP_SOURCES for this package
      fun install(apk: File)             // FileProvider + ACTION_VIEW, FLAG_GRANT_READ_URI_PERMISSION
  }
  ```
  - `UpdateDeps` gains `installer: ApkInstaller?`.
  - `UpdatesViewModel` gains `downloadApp()`, `installApp()`, `dismissApp()`, `onResume()`.
  - `const val MAX_APK_BYTES = 200L * 1024 * 1024`.

- [ ] **Step 1: Write the failing tests, with a fake `ApkInstaller`**

  - `appNoticeWithoutDevice`: app `0.1.0`, release `0.2.0`, disconnected → `app.version == "0.2.0"`.
  - `noAppNoticeInDebugBuilds`: `appNotices = false` → null, while the firmware notice still shows.
  - `noAppNoticeForSameVersionOrWithoutApkAsset`.
  - `dismissAppIsPerVersion`.
  - `downloadThenInstalls`: the file is `cacheDir/drillbalancer-0.2.0.apk`, stage `Ready`, `install` called once with it.
  - `failedDownloadLeavesNoFile`.
  - `permissionMissingOpensSettingsThenInstallsOnResume`: `canInstall() == false` → `openInstallPermission` called, no install; `canInstall` turns true and `onResume()` → install once; a further `onResume()` does not install again.
  - `permissionRefusedStaysReady`: `onResume()` while refused → stage `Ready`, no error, no second settings launch; `installApp()` tries again.
  - `installCancelledStaysReady`: after `install`, `onResume()` leaves `Ready`, and `installApp()` installs again.
  - `cacheClearedOnStart`: an old file in `cacheDir` is gone after the view model starts.

- [ ] **Step 2: Run them to see them fail**

  Run: `tools/ci/app.sh --tests '*UpdatesViewModelTest*'`
  Expected: compile errors.

- [ ] **Step 3: Implement**

  - Write to `<name>.part`, rename when complete, delete on failure.
  - Manifest: `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />` and:
    ```xml
    <provider
        android:name="androidx.core.content.FileProvider"
        android:authorities="${applicationId}.updates"
        android:exported="false"
        android:grantUriPermissions="true">
        <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/update_paths" />
    </provider>
    ```
  - `update_paths.xml`: `<paths><cache-path name="updates" path="updates/" /></paths>`.
  - `AndroidApkInstaller(context)`: use `startActivitySafely`.
  - In `AppRoot`, a `LifecycleResumeEffect` calls `updatesVm.onResume()`.
  - `ConnectScreen` gets `banner: @Composable () -> Unit = {}` before `state.message`, and `ConnectRoute` passes it through.
  - The Device tab's banner slot shows the app notice as well.

- [ ] **Step 4: Run them to see them pass**

  Run: `tools/ci/app.sh`
  Expected: tests, lint and screenshots pass.

  Run: `cd app && ./gradlew assembleDebug`
  Expected: BUILD SUCCESSFUL; the merged manifest contains the provider.

- [ ] **Step 5: Finish.** Commit message: `Offer a new app version and install it from GitHub`.

### Task 11: Docs

**Files:**
- Modify: `README.md:43,52,188,203,210` plus an updates section, `firmware/README.md:97,277` and its bring-up list, `app/README.md:53` plus an updates and signing section
- Modify: wiki `Using-the-Device`, `Android-App`, `Hardware-Bring-Up`, and a release section. Clone `https://github.com/Tropaion/Drill_Balancer.wiki.git` into the scratchpad.

- [ ] **Step 1: Update the gestures**

  Tap does nothing, hold 1 s zeroes, BOOT opens and leaves the settings, the on-device calibrations start there, and the hint reads "Hold to zero for left/right". `grep -rIn -i 'tap to zero\|tap = zero\|tap zero\|hold 1 s' README.md firmware/README.md app/README.md firmware/tools/sim/README.md` must find no stale line. The app's own "tap Zero" hints in `LiveScreen.kt` mean its Zero button and stay.

- [ ] **Step 2: Add the release path**

  - Bump `VERSION`, push, wait for CI, tag `vX.Y.Z`.
  - What the workflow publishes.
  - How the app checks, and that a debug build shows no app notice.
  - Where signing is set up, without paths to the owner's key.

- [ ] **Step 3: Check the public docs rule**

  Run: `grep -rIn -i 'pcb\|kicad' README.md firmware/README.md app/README.md`, and the same on the wiki clone.
  Expected: no new line.

- [ ] **Step 4: Finish.** Commit message: `Document the BOOT button and the release path`. Push the wiki from its clone. The suites need no re-run for a docs-only change; CI still has to be green.

### Task 12: The release key

Owner-gated. Passwords never appear in a command line, a log or chat.

- [ ] **Step 1: Generate the keystore in `~/Dokumente/Git/DrillBalancer-backup/`**

  Write a script in the scratchpad that creates `release.env` with `umask 077`, filled from `openssl rand -base64 32` without echoing, using the same password for store and key (PKCS12). Then:

  `keytool -genkeypair -keystore drillbalancer-release.jks -storetype PKCS12 -alias drillbalancer -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=DrillBalancer" -storepass:env DB_RELEASE_STORE_PASSWORD -keypass:env DB_RELEASE_KEY_PASSWORD`

  Then `base64 -w0 drillbalancer-release.jks > drillbalancer-release.jks.b64` with `umask 077`.

  Expected: `ls -l` shows the three files as `-rw-------`, and `git -C ~/Dokumente/Git/DrillBalancer status --short` shows none of them.

- [ ] **Step 2: Print only the public certificate fingerprint**

  Run: `keytool -list -keystore drillbalancer-release.jks -storepass:env DB_RELEASE_STORE_PASSWORD`, with the env file sourced.

- [ ] **Step 3: Owner adds the secrets**

  Under Settings → Secrets and variables → Actions, the owner adds `DB_RELEASE_KEYSTORE_B64`, `DB_RELEASE_STORE_PASSWORD`, `DB_RELEASE_KEY_ALIAS`, `DB_RELEASE_KEY_PASSWORD`, pasting from those files. The owner also copies the folder somewhere safe: a lost key means every future update needs an uninstall.

- [ ] **Step 4: Owner confirms the secrets exist.** Wait for that.

### Task 13: Release-signed app on the phone

Owner-gated: this deletes the app's data, including the upload token.

- [ ] **Step 1: Owner confirms** that no recording is waiting for upload and that the phone may be used.

- [ ] **Step 2: Build the signed 0.1.0**

  With `release.env` sourced and `VERSION` at `0.1.0`:
  Run: `DB_RELEASE_KEYSTORE=$HOME/Dokumente/Git/DrillBalancer-backup/drillbalancer-release.jks tools/ci/release_apk.sh firmware/build-ci/release`
  Expected: `firmware/build-ci/release/drillbalancer-0.1.0.apk`, and `apksigner` verifies it.

- [ ] **Step 3: Ask, then uninstall and install**

  Run: `adb -s RFCY70JSEPA uninstall dev.drillbalancer`, then `adb -s RFCY70JSEPA install firmware/build-ci/release/drillbalancer-0.1.0.apk`.
  Expected: both report Success.

- [ ] **Step 4: Owner re-enters the upload token in the app.** Never type it through adb.

- [ ] **Step 5: Check** that the app connects to the board and that no update notice shows, since no release exists yet.

### Task 14: First release, v0.2.0

Owner-gated: publishing is public. Only after Tasks 1 to 13 work.

- [ ] **Step 1: Bump**

  Set `VERSION` to `0.2.0`. Run all suites, commit `Release 0.2.0`, push, and wait for green CI on that commit.

- [ ] **Step 2: Ask the owner for the go-ahead.** Wait for it.

- [ ] **Step 3: Tag**

  Run: `git tag v0.2.0 && git push origin v0.2.0`
  Expected: the Release workflow starts. Tag force-moves are blocked, so a failed run means fixing forward with `0.2.1`, not moving the tag.

- [ ] **Step 4: Verify the release**

  Run: `curl -s https://api.github.com/repos/Tropaion/Drill_Balancer/releases/latest`
  Expected: `tag_name` is `v0.2.0`, with assets `drillbalancer-0.2.0.bin` and `drillbalancer-0.2.0.apk`; `prerelease` false; `dataset-v1` untouched. Download both into new empty scratch directories, and check the APK with `apksigner verify --print-certs`: its digest matches Task 12 Step 2.

### Task 15: End to end on the real devices

- [ ] **Step 1: App update**

  Open the app (0.1.0, release key) on the Connect screen. "App 0.2.0 available" shows. Tap Download. Android asks for "Install unknown apps" once; after coming back the installer opens. Install. Expected: version 0.2.0 on the phone, data kept (a recording and the upload settings are still there).

- [ ] **Step 2: Firmware update**

  Connect to the board on 0.1.0. "Firmware 0.2.0 available" shows on Live and Device. Tap Download. The Firmware screen opens with the image loaded. Start. Expected: the board reports 0.2.0 and the notice is gone.

- [ ] **Step 3: Dismissal**

  Check with a debug build against the release, or by re-flashing 0.1.0: dismiss, reconnect, and no notice returns for 0.2.0.

- [ ] **Step 4: Offline**

  The owner turns off mobile data and Wi-Fi: the app opens, connects to the board, and shows no message. Do not change phone settings through adb.

- [ ] **Step 5: Wrap up**

  Remove our `/sdcard/Download/ui.xml` when the phone is free, restore `svc power stayon false` if it was set, and update the project memory with the BOOT, PWR and release facts.
