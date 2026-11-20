# CompassLink — Live Android Orientation

![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)
![Node.js](https://img.shields.io/badge/Node.js-18%2B-339933?logo=node.js&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-AGP_builtin-7F52FF?logo=kotlin&logoColor=white)
![Android](https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&logoColor=white)
![Connection](https://img.shields.io/badge/Connection-USB%20%7C%20Wireless-009688)

**CompassLink** is an Android-to-PC **live orientation dashboard**. Built with a **Kotlin + SensorManager** companion and a **Node.js + HTML Canvas** dashboard, it streams fused sensor quaternions through an **ADB reverse tunnel** over **USB debugging or wireless debugging**, turning your phone's movement into a live 3D handset and telemetry display.

The companion reads the sensors; ADB provides the transport. USB streaming needs no Wi-Fi, while wireless debugging uses the same Wi-Fi network as your PC. Neither mode needs internet during streaming. Initial tool installation and build dependency downloads require internet access.

## Demo

<p align = "center">
<img width="480" height="270" alt="demo" src="https://github.com/user-attachments/assets/efe669a1-41f9-46d1-bd96-b5834cdc80b9" />
</p>

## Key Features

- **Two Connection Options:** Connect by USB cable or Android 11+ wireless debugging using dedicated npm commands.
- **Live 3D Orientation:** Quaternion-driven handset rendering with drag-to-orbit controls and double-click camera reset.
- **Touchscreen Controller:** Snapshot 1 sends WASD, Space and Escape to Windows while the dashboard monitors live input.
- **Sensor Telemetry:** Heading, pitch, roll, quaternion components, sample count, sensor accuracy and sample age.
- **Connection-Aware Dashboard:** USB/wireless setup guides, active stream mode, waiting state and stale-data indicators.
- **Fast Reconnect:** Reuse an installed companion; explicitly reinstall after rebuilding the Android app.
- **Resilient Streaming:** Latest-sample queue, automatic socket reconnect attempts and cleanup when the app leaves the foreground.
- **Local Operation:** Local browser assets, Node built-ins and loopback-only HTTP/TCP receivers.

## Tech Stack

| Category | Technology | Badge & Version | Purpose |
| :--- | :--- | :--- | :--- |
| **Frontend** | JavaScript, HTML5 Canvas | ![JavaScript](https://img.shields.io/badge/JavaScript-ES_Modules-F7DF1E) | Handset projection, camera controls and telemetry |
| **Styling** | CSS | ![CSS](https://img.shields.io/badge/CSS-Responsive-663399) | Dark dashboard, cards and connection controls |
| **Backend** | Node.js built-ins | ![Node.js](https://img.shields.io/badge/Node.js-18%2B-339933) | HTTP server, TCP receiver and state API |
| **Android App** | Kotlin, SensorManager | ![Kotlin](https://img.shields.io/badge/Kotlin-AGP_builtin-7F52FF) | Fused orientation, phone UI and lifecycle |
| **Transport** | Android Debug Bridge | ![ADB](https://img.shields.io/badge/ADB-USB%20%2F%20Wireless-009688) | Pairing, connection and reverse tunnel |
| **Android Build** | Android Gradle Plugin, Gradle | ![AGP](https://img.shields.io/badge/AGP-9.5.1-3DDC84) ![Gradle](https://img.shields.io/badge/Gradle-9.9.1-02303A) | APK packaging and reproducible builds |
| **Java Runtime** | JDK 25 (Oracle/OpenJDK) | ![JDK](https://img.shields.io/badge/JDK-25-orange) | Local Android build runtime |
| **Tooling** | PowerShell, Prettier, Node test runner | ![Prettier](https://img.shields.io/badge/Prettier-3.6.2-F7B93E) | Setup/build helpers, formatting and existing tests |

## Getting Started

### Requirements

- **PC:** Windows x64, PowerShell, and [Node.js 18+ with npm](https://nodejs.org/).
- **Phone:** Android 8+ with a rotation-vector or game-rotation-vector sensor.
- **USB:** a USB data cable and USB debugging enabled.
- **Wireless:** Android 11+, Wireless debugging enabled, and both devices on the same Wi-Fi network.
- **Internet:** required for initial tool and build-dependency downloads. Streaming works offline.

Download the source ZIP using GitHub's **Code > Download ZIP** and extract it, or clone the repository:

```powershell
git clone https://github.com/mhsatgit/CompassLink.git
cd CompassLink
```

Run the commands below from the extracted or cloned project folder. The PC dashboard is included in `pc/`. All Android tools are downloaded automatically into the ignored `.tools/` folder; no Android Studio installation, global Java/ADB installation, or manual PATH changes are required.

### Method 1: Prebuilt APK + PC Dashboard

Choose an included APK to use with the PC dashboard:

- **[CompassLink3Dv1.1.apk](apk/CompassLink3Dv1.1.apk)** includes the Snapshot 1 touchscreen controller and sensor mode.
- **[CompassLink3D.apk](apk/CompassLink3D.apk)** is the original sensor companion.

**1. Install the prebuilt APK on the phone.**

Copy your chosen APK from the project folder to the phone, open it, and allow installation from that source when Android prompts. The app must be installed before the prebuilt connection commands run.

**2. Download ADB on the PC.**

```powershell
npm run setup:adb
```

This downloads only Android Platform Tools into `.tools/android-sdk/platform-tools/`. This method needs no JDK, Gradle, full Android SDK, or APK build. The dashboard uses Node.js built-ins, so `npm install` is not required for this method.

**3. Choose a connection.**

For **USB**, connect the phone using a data cable, unlock it, enable USB debugging, and accept the PC authorization prompt:

```powershell
npm run connect:prebuilt:usb
```

For **wireless**, enable Wireless debugging and open **Pair device with pairing code** on the phone. Both devices must be on the same Wi-Fi network:

```powershell
npm run connect:prebuilt:wireless -- -Pair
```

Enter the pairing address and pairing code, then the connection address from the main Wireless debugging page. **The pairing port and connection port are different.**

For later wireless connections:

```powershell
npm run connect:prebuilt:wireless
```

These commands always skip APK installation. They create the ADB tunnel and open the companion already installed on the phone.

**4. Start the dashboard.**

```powershell
npm start
```

Continue with [Start Streaming](#start-streaming) below.

### Method 2: Build the APK from Source + PC Dashboard

Choose this method to create the APK yourself or install Android code changes.

**1. Install dependencies and Android tools, then build.**

```powershell
npm install
npm run setup:android
npm run build:android
```

Setup installs the JDK, Gradle, Android command-line tools, Platform Tools, SDK Platform and Build Tools inside `.tools/`. It verifies downloaded archive checksums and reuses completed installations. The first setup/build can take several minutes. If interrupted, rerun the same command.

The built APK is saved at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

**2. Choose a connection.**

For **USB**, enable USB debugging, connect a data cable, unlock the phone and accept authorization:

```powershell
npm run connect:usb
```

For first-time **wireless** pairing, use Android 11+ on the same Wi-Fi network and open the phone's pairing-code dialog:

```powershell
npm run connect:wireless -- -Pair
```

Use the pairing address/code, followed by the separate connection address from the main Wireless debugging page. For later wireless connections:

```powershell
npm run connect:wireless
```

The source connection commands install the built APK only if the companion is missing. If already installed, it is reused. `npm run connect` is an alias for USB.

After changing Android code and rebuilding, explicitly update the phone app:

```powershell
npm run build:android
npm run connect:usb -- -Reinstall
# Or, for an already paired wireless phone:
npm run connect:wireless -- -Reinstall
```

**3. Start the dashboard.**

```powershell
npm start
```

### Start Streaming

1. Open **[http://127.0.0.1:8000](http://127.0.0.1:8000)** in a browser on the PC.
2. Tap **Start streaming** in the phone app and keep it in the foreground.
3. Rotate the phone to see the 3D handset and telemetry update. Drag the view to orbit; double-click to reset the camera.

To stop, tap **Stop streaming** and press **Ctrl+C** in the server terminal. Streaming also stops when the phone app leaves the foreground. Repeat the connection command when reconnecting.

The dashboard's USB/wireless buttons display instructions; they do not connect a phone by themselves. Sensor connection mode comes from incoming samples. Controller connection mode comes from the ADB launch command. Wireless ports can change after restarting Wireless debugging; use the current connection address and pair again only if needed.

### Controller Mode (Snapshot 1)

Install the [Snapshot 1 prebuilt APK](apk/CompassLink3Dv1.1.apk) (`1.1-snapshot.1`, version code 2) using Method 1. If you build from source using Method 2, install your build once with the matching connection command:

```powershell
npm run connect:usb -- -Reinstall
# Or, for first-time wireless pairing and installation:
npm run connect:wireless -- -Pair -Reinstall
```

On later connections, omit `-Reinstall`. To guarantee connection without APK installation, use `npm run connect:prebuilt:usb` or `npm run connect:prebuilt:wireless` with the already installed app. Add `-- -Pair` if the phone needs wireless pairing.

1. Run `npm start` in a normal Windows desktop session and open the dashboard. Node uses Windows PowerShell 5.1 and the system .NET Framework to compile the input helper on first startup or after a source change, then starts it automatically. A separate .NET SDK, helper build command, terminal or administrator session is unnecessary.
2. Tap **Controller Mode** on the phone. Wait for **Windows input engine ready**, then focus a normal Windows text editor or key-state tester. The **Motion Control** card displays the session, mapped keys, held controls and latest event.
3. Press and release the D-pad and action buttons. Hold **UP + LEFT**, then release **LEFT** while continuing to hold **UP**. Verify both keys are held together and only W remains held afterward. Hold a button while stopping Controller Mode or disconnecting to check key release.
4. Tap **Motion / Sensor** or **Stop Controller** to return to the sensor screen. If you started streaming before opening Controller Mode, the 3D dashboard continues receiving sensor samples during play.

| Touch control | Windows key |
| --- | --- |
| UP / DOWN / LEFT / RIGHT | W / S / A / D |
| A | Space |
| B | Escape |

Windows limits `SendInput` to applications at the same or a lower integrity level. Some games and protected applications ignore synthetic input. Windows shares keyboard state between physical and injected input, so avoid holding the same mapped key from both sources during testing. See [the Microsoft SendInput documentation](https://learn.microsoft.com/windows/win32/api/winuser/nf-winuser-sendinput).

See the [Snapshot 1 implementation and test record](docs/snapshots/SNAPSHOT_01.md) for protocol details and hardware coverage.

### Connection Command Options

Append options after npm's `--` separator:

| Option | Purpose | Example |
| --- | --- | --- |
| `-Address IP:PORT` | Use the phone's current wireless connection address without prompting | `npm run connect:wireless -- -Address 192.168.1.20:37123` |
| `-Pair` | Pair before establishing a wireless connection | `npm run connect:prebuilt:wireless -- -Pair` |
| `-PairAddress IP:PORT` | Supply the pairing address; the pairing code remains interactive | `npm run connect:wireless -- -PairAddress 192.168.1.20:40001 -Address 192.168.1.20:37123` |
| `-Reinstall` | Install the newly built APK over the existing app | `npm run connect:usb -- -Reinstall` |

The `connect:prebuilt:*` commands select `-SkipInstall` automatically. Do not combine them with `-Reinstall`. Example IPs and ports above are placeholders; use the values displayed on the phone.

## Build Configuration

| Component | Configuration |
| --- | --- |
| JDK | 25; fresh setup downloads Temurin 25.0.3+9 |
| Gradle | 9.9.1; version and checksum pinned in the Gradle wrapper properties |
| Android Gradle Plugin | 9.5.1, with built-in Kotlin compilation |
| Compile SDK / Build Tools | Android 37.0 / 37.0.0 |
| Target / minimum Android API | 35 / 26 (Android 8+) |
| Java / Kotlin bytecode target | 17 |
| SDK package manager | Repo-local Android CLI using `android sdk install` |
| Tool and build-dependency location | `.tools/` |

The scripts resolve paths relative to their own location. `android/local.properties` is generated for each checkout and is excluded from source control and source archives. Android preferences and the debug signing key stay in `.tools/android-user/`; only `ANDROID_USER_HOME` is used for the preference location. The helpers do not change Windows user/system environment variables.

For Android Studio, run setup first, open `android/`, and select `.tools/jdk` as the Gradle JDK and `.tools/android-sdk` as the SDK. CLI builds do not require Android Studio.

## Project Structure

| Location | Purpose |
| --- | --- |
| `apk/CompassLink3D.apk` | Included prebuilt Android companion for Method 1 |
| `apk/CompassLink3Dv1.<snapshotnumber>.apk` | Versioned prebuilt Android companion for each tested snapshot |
| `android/app/src/main/` | Android companion: sensor collection, phone UI and TCP streaming |
| `android/*.gradle.kts`, `android/app/build.gradle.kts` | Android project, plugin and SDK configuration |
| `android/gradle/`, `android/gradlew*` | Gradle wrapper and pinned distribution metadata |
| `pc/server.js` | Loopback TCP receiver, packet validation, HTTP server and state API |
| `pc/controller.js`, `pc/windows-input.js`, `pc/windows/` | Controller protocol, held-key state and Windows input helper |
| `pc/dashboard.js`, `pc/orientation.js` | 3D rendering, quaternion math and telemetry updates |
| `pc/index.html`, `pc/styles.css` | Dashboard layout, styling and connection instructions |
| `scripts/setup-android.ps1` | Full Android tool setup or ADB-only setup |
| `scripts/toolchain.ps1`, `scripts/install-sdk.ps1`, `scripts/sdk-repository.ps1` | Portable tool paths, Android CLI installation and SDK feed parsing |
| `scripts/build-android.ps1` | APK build and optional Android checks |
| `scripts/connect-phone.ps1` | USB/wireless connection, optional install, ADB tunnel and companion launch |
| `scripts/download.cjs`, `scripts/clean.ps1` | Tool downloads and generated-file cleanup |
| `tests/` | Protocol, math, setup and cleanup regression tests |
| `.gitignore` | Exclude generated files from Git |

## Streaming Behavior Summary

| Setting | Behavior |
| :--- | :--- |
| **Sensor sampling** | Requested at 50 Hz |
| **Transmission** | Latest available sample sent at up to 50 Hz |
| **Backpressure** | Superseded samples dropped rather than queued |
| **Dashboard polling** | Sequential requests approximately every 50 ms |
| **Stale threshold** | Samples older than two seconds |
| **Socket inactivity** | Connection closed after ten seconds without data |

These are implementation settings, not measured performance guarantees. Sensor delivery, wireless conditions and browser scheduling may reduce the observed rate. The PC runtime uses Node built-ins; Prettier is the only npm development dependency.

## API Endpoints

| Method | Endpoint | Description |
| :---: | :--- | :--- |
| `GET` | `/` | Dashboard with orientation, telemetry and connection guides |
| `GET` | `/api/state` | Latest sensor sample and connection status, plus controller mappings, engine readiness, held keys and the last event |

Sensor data uses newline-delimited JSON on TCP port **8765**, through the selected ADB reverse tunnel.

## Coordinates and quaternion protocol

Android device axes are X right, Y toward the top edge, and Z outward from the screen. These axes follow the physical device's natural orientation, not its rotated UI. The handset mesh uses those same device coordinates. Android's rotation quaternion maps device vectors into East/North/Up world coordinates. The renderer first applies that quaternion, then maps world `(East, North, Up)` into view `(East, Up, -North)`, followed by an independent orbit camera. It does not accumulate Euler angles, so crossing 360 degrees does not cause pose discontinuities. `q` and `-q` render identically.

Heading, pitch and roll follow `SensorManager.getOrientation()` conventions; the heading is clockwise from magnetic north when the top edge projects onto the horizontal plane. Near vertical, heading is undefined and Euler angles have singularities; the 3D quaternion pose remains usable. Rotation-vector fusion and calibration depend on the phone. Nearby magnets and metal can affect heading. If `TYPE_ROTATION_VECTOR` is unavailable, the companion falls back to `TYPE_GAME_ROTATION_VECTOR`; its heading is relative and can drift because it does not use magnetic north.

The stream contains one UTF-8 JSON object per newline:

```json
{
  "t": 1730000000000,
  "timestampNs": "123456789000",
  "sensor": "android.sensor.rotation_vector",
  "accuracy": 3,
  "q": [0, 0, 0, 1],
  "connectionMode": "wireless"
}
```

`q` uses `[x,y,z,w]`, converted from Android's `[w,x,y,z]` output. The server validates finite components and normalizes them. `t` is phone wall-clock milliseconds; `timestampNs` is the monotonic sensor event timestamp, serialized as a string to preserve precision. The dashboard age uses PC receipt time and is not a measurement of transport latency. Accuracy codes are Android's -1/0/1/2/3 (no contact, unreliable, low, medium, high); availability varies by sensor. This version displays fused orientation, not separate raw accelerometer/gyro/magnetometer streams.

## Running Tests and Maintenance

After implementing a snapshot and completing its tests, copy `android/app/build/outputs/apk/debug/app-debug.apk` to `apk/CompassLink3Dv1.<snapshotnumber>.apk`. Use the snapshot number without leading zeros. For example, Snapshot 1 is `apk/CompassLink3Dv1.1.apk`.

Run `npm run clean` with builds stopped to remove project-generated caches, intermediate build files, and downloaded Android command-line tools and Gradle installer ZIPs (`.tools/commandlinetools-win-*_latest.zip` and `.tools/gradle-*-bin.zip`). These ZIPs are no longer needed after installation. It retains source, lockfiles and the debug APK, and retains the repo-local `.tools/` installations, signing key and dependency caches. The next build regenerates project caches. The dashboard can still be started normally with `npm start`.

For a clean Android rebuild, including generated Gradle scripts, transforms and build caches:

```powershell
npm run clean -- -BuildCaches
npm run build:android
```

`-BuildCaches` first stops this clone's Gradle daemons, then removes project caches, all generated Android build output (including any previous APK), generated Gradle caches, temporary files and daemon logs. It keeps downloaded JDK/SDK/Gradle installations, Maven dependencies under `.tools/gradle-cache/caches/modules-2`, and the Android signing key. Gradle regenerates build state on the next build.

To pass diagnostic options through npm to the build helper, use npm's `--` separator:

```powershell
npm run build:android -- -Stacktrace -Info
```

```powershell
npm test
npm run build:android
npm run test:android
npm run format:check
```

The tests cover quaternion normalization, invalid data rejection, fragmented TCP packets reaching the HTTP API, connection shutdown, asset serving, headings, coordinate mapping, downloader fallbacks, SDK feed parsing, installer argument handling and post-install checks, repo-local tool paths and cleanup safety. PowerShell checks run on Windows. They do not verify physical wireless pairing or hardware performance. Hardware verification requires a real phone: stream, rotate each axis, stop/restart, disconnect/reconnect using either mode, and verify relative heading with the game sensor fallback. No phone measurements are simulated in the normal dashboard.

## Troubleshooting

- **Unauthorized/offline phone:** unlock it and accept the authorization prompt. For USB, check the cable; for wireless, check the current debugging address and Wi-Fi connection. Rerun the connection command for your mode.
- **Waiting for phone:** confirm `npm start` is running, rerun the connection command for your chosen method and mode, and tap **Start streaming** on the phone.
- **No supported sensor:** this phone exposes neither rotation-vector sensor. An emulator may lack realistic fused orientation.
- **Port already in use:** stop the other server using port 8000 or 8765 and restart CompassLink3D.
- **Heading moves unexpectedly:** move away from metal/magnets and follow the phone manufacturer's calibration guidance. Game sensor heading is relative.
- **Tool download fails:** rerun `npm run setup:android`. Downloads have no script-imposed time limit and archives are checksum verified. A DNS error (`Could not resolve host`) or unreachable endpoint needs working internet/DNS; removing a timeout cannot fix that. Check access to `downloads.gradle.org`, `dl.google.com` and `api.adoptium.net`. Already installed repo-local tools are retained.
- **Android preferences paths conflict:** rerun the updated npm build helper; it clears deprecated preference-location environment variables for that process and uses only `.tools/android-user`. If you configure Java system properties through an IDE or JVM options, remove conflicting `ANDROID_SDK_HOME` or `ANDROID_PREFS_ROOT` overrides there too. This exception occurs while AGP is configuring the project, before Kotlin compilation.
- **Wireless pairing fails:** open a fresh pairing-code dialog and use its pairing port. Use the separate connection port from the main Wireless debugging page when prompted to connect.
- **Wireless connection fails:** check both devices are on the same Wi-Fi and that the network allows communication between clients. Restart Wireless debugging and use its current address and port.
- **Old app behavior after rebuilding:** connect with `-Reinstall` to install the newly built APK.
- **ADB is not recognized:** run `npm run setup:adb` and use the npm connection commands or the repo-local executable shown above. No global ADB or PATH changes are required.

## References

- [Android Debug Bridge and wireless debugging](https://developer.android.com/tools/adb)
- [Android CLI overview and SDK path configuration](https://developer.android.com/tools/agents/android-cli)
- [Android CLI SDK package identifiers](https://developer.android.com/tools/agents/android-cli/commands/sdk)
- [Android CLI SDK installation](https://developer.android.com/tools/agents/android-cli/commands/sdk_install)
- [Android CLI SDK package listing](https://developer.android.com/tools/agents/android-cli/commands/sdk_list)
- [Android rotation vector sensors and device coordinates](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion)
- [Android orientation and game rotation sensor](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position)
- [Android Gradle Plugin 9.5 compatibility and release notes](https://developer.android.com/build/releases/agp-9-5-0-release-notes)

- [AGP built-in Kotlin migration](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Gradle releases](https://gradle.org/releases/)
- [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html)
- [AGP compile SDK version and minor API level](https://developer.android.com/reference/tools/gradle-api/9.5/com/android/build/api/dsl/CompileSdkSpec)
- [Official Gradle distribution checksums](https://gradle.org/release-checksums/)
