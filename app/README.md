# DrillBalancer Android app

The companion app for the DrillBalancer (see [../firmware](../firmware/README.md)).
Over BLE it shows the live level (wall, floor and angle mode), edits all
device settings, runs both calibrations, sends power commands and updates
the firmware.

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
| `ui` | Compose screens and their ViewModels: connect, pairing, live, settings, calibrate, device, firmware update. `SessionViewModel` picks the connect screen or the tabs and owns the `PairingGate`, which pairs on the first protected action. |

`DrillBalancerApp` creates the scanner, the last-device store and one
`BleDeviceSession` for the process, so a rotation keeps the link. Leaving
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

## First run on real hardware

None of this could be checked without a phone and a board.

1. **Permissions and switches**: the permission request, "denied for good"
   leading to app settings, the enable-Bluetooth dialog, location services
   on Android 8–11, and the screen updating when Bluetooth is switched in
   the quick settings.
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
7. **Status 0x85** on the command characteristic is the device's BAD_ARG
   but Android's GATT_ERROR elsewhere; check that a real phone error is not
   shown as "bad argument".
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
    on, the reboot and the reconnect with the new version shown, and a
    failed update leaving the old firmware running.
13. **Reconnect**: walk out of range and back. The tabs stay with a
    "Reconnecting…" bar while the session retries (backoff 1, 2, 4, 8, 15 s)
    and switch to the connect screen once it gives up. After the device
    goes to sleep by itself the app also retries a few times in the
    background, which a sleeping device does not answer.
14. **Lifecycle**: rotate during a live view, a calibration and an update
    (the link and progress survive). Leave the app with Back on Android 11
    or older: the link ends. On Android 12+ Back only moves the app to the
    background and the link stays until the process ends.
