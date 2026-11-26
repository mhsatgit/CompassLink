# CompassLink — Live Android Orientation and Motion Control

![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)
![Node.js](https://img.shields.io/badge/Node.js-18%2B-339933?logo=node.js&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-AGP_builtin-7F52FF?logo=kotlin&logoColor=white)
![Android](https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&logoColor=white)
![Connection](https://img.shields.io/badge/Connection-USB%20%7C%20Wireless-009688)

**CompassLink** turns an Android phone into a customizable controller for Windows. Use it as a touchpad, keyboard, virtual Xbox controller, or tilt and gyro controller. The local dashboard renders the phone’s orientation in 3D and shows live sensor and controller diagnostics.

The Android companion uses an ADB reverse tunnel over USB or wireless debugging. USB works without Wi-Fi. Wireless debugging uses the same Wi-Fi network as the PC. An internet connection is needed for initial tool and dependency downloads.

**[Download CompassLink3D.apk](apk/CompassLink3D.apk)** · [SHA-256 checksum](apk/CompassLink3D.apk.sha256)

## Key Features

- **Live 3D orientation** — View heading, pitch, roll, quaternion values, measured sensor rates, and motion diagnostics. Drag to orbit the handset and double-click to reset the view.
- **Custom controller layouts** — Place and bind buttons, D-pads, joysticks, triggers, sliders, and touchpads. Save, rename, duplicate, import, or export profiles.
- **Hybrid input** — Combine keyboard keys and chords, mouse movement and buttons, wheel input, and virtual Xbox buttons, sticks, and triggers in one profile.
- **Full-screen touch-look** — Drag in unused screen space to move the mouse while on-screen buttons and sticks keep their own gestures. Tune mouse sensitivity for slow aiming or fast turns.
- **Tilt and gyro controls** — Recenter the phone and map motion to analog sticks, mouse aim, or steering keys. Adjust sensitivity, dead zones, smoothing, and axis inversion for each profile.
- **Profile switching and play options** — Add a one-touch profile switch, choose system rotation or a fixed orientation, and enable optional touch haptics.
- **Local diagnostics** — Check profile synchronization, held inputs, and virtual gamepad output. The dashboard shows commanded state alongside Windows XInput readback.

## Tech Stack

| Category         | Technology                             | Purpose                                                                   |
| ---------------- | -------------------------------------- | ------------------------------------------------------------------------- |
| Android app      | Kotlin, Android SensorManager          | Touch layouts, motion processing, profile storage, and sensor acquisition |
| Dashboard        | JavaScript, HTML Canvas, CSS           | 3D orientation, telemetry, and controller diagnostics                     |
| Desktop receiver | Node.js built-ins                      | Loopback TCP receiver, input routing, and read-only HTTP API              |
| Windows input    | C#, PowerShell, HIDMaestro, usbip-win2 | Keyboard and mouse output, virtual USB devices, and XInput readback       |
| Transport        | Android Debug Bridge                   | Pairing, USB or wireless connection, and reverse tunneling                |
| Android build    | Android Gradle Plugin, Gradle, JDK     | APK builds and verification                                               |

## Getting Started

### Requirements

- **PC** — Windows 10 or 11 on x64 with PowerShell and [Node.js 18 or later](https://nodejs.org/).
- **Phone** — Android 8 or later. Orientation and gyro features require the corresponding sensors.
- **USB** — A USB data cable and USB debugging enabled.
- **Wireless** — Android 11 or later, Wireless debugging enabled, and both devices on the same Wi-Fi network.
- **Virtual Xbox output** — The Windows helper and signed USB/IP driver. See [Virtual Gamepad Setup](#virtual-gamepad-setup).

Download and extract the repository with GitHub’s **Code → Download ZIP** option, or clone it.

```powershell
git clone https://github.com/mhsatgit/CompassLink.git
cd CompassLink
```

Run the following commands from the folder containing `package.json`.

### Method 1 — Prebuilt APK and PC Dashboard

1. Install the [prebuilt CompassLink3D.apk](apk/CompassLink3D.apk) on your phone. Android may ask you to allow installation from the app you opened the file with. The included APK is debug-signed.
2. Download ADB and start the receiver.

   ```powershell
   npm run setup:adb
   npm start
   ```

3. Open a second terminal in the same folder. Choose one of these connection commands.

   | Connection               | Command                                      |
   | ------------------------ | -------------------------------------------- |
   | USB                      | `npm run connect:prebuilt:usb`               |
   | Wireless, first pairing  | `npm run connect:prebuilt:wireless -- -Pair` |
   | Wireless, already paired | `npm run connect:prebuilt:wireless`          |

   For USB, unlock the phone and accept the debugging authorization prompt. For wireless pairing, open **Pair device with pairing code** on the phone and enter its address and code. When prompted to connect, use the separate address and port on the main **Wireless debugging** screen.

4. Open the [local dashboard](http://127.0.0.1:8000). Tap **Check link** in the phone app if needed, then open **Sensors** or **Controllers**.

The prebuilt connection commands open the installed app and set up the tunnel. This method needs only ADB and Node.js.

### Method 2 — Build the APK from Source

1. Install development dependencies and the local Android toolchain, then build the APK.

   ```powershell
   npm ci
   npm run setup:android
   npm run build:android
   ```

   The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. Setup keeps the toolchain under `.tools/`.

2. Start the desktop receiver with `npm start`. In a second terminal, connect the phone and install the build.

   | Connection               | Command                                        |
   | ------------------------ | ---------------------------------------------- |
   | USB                      | `npm run connect:usb -- -Reinstall`            |
   | Wireless, first pairing  | `npm run connect:wireless -- -Pair -Reinstall` |
   | Wireless, already paired | `npm run connect:wireless -- -Reinstall`       |

   For later connections, omit `-Reinstall` to reuse the installed app. Use it again after rebuilding to apply Android code changes. In-place updates require an APK signed with the same key as the installed app.

### Start Streaming

1. Open the [local dashboard](http://127.0.0.1:8000).
2. On the phone, open **Sensors** and tap **Start streaming**. Keep CompassLink in the foreground.
3. Rotate the phone to update the 3D model and telemetry. Expand **Quaternion details** for numerical readings.

Tap **Stop streaming** on the phone to pause sensor updates. Press **Ctrl+C** in the server terminal to stop the desktop receiver.

### Play and Edit Profiles

- Open **Controllers**, select a profile, and tap **Play**. Wait for it to synchronize before using the controls. Open the play options with **Pause**, Android Back, or Escape. From there, choose **Resume Play** or **End session**.
- Choose **Edit** to change a custom profile, or **Edit a copy** to customize a built-in profile. Use **Add** for controls and **Properties** for their bindings, appearance, and behavior. Use the profile’s **More** menu to rename, duplicate, or export the layout.
- For mouse camera control, add **Pointer and motion → Full-screen touch-look**. Start a drag outside other controls. Use separate buttons for mouse clicks and adjust sensitivity in the editor or play options.
- In the layout settings, choose an orientation policy and optional touch haptics. Add **Switch profile** and select a target to change profiles during play. Switching releases held input and waits for the next profile to synchronize.
- Configure motion in the editor and tap **Recenter** in Play Mode while holding the phone comfortably. Gyro aim can use a dedicated control or an existing button such as a shoulder button, with hold or toggle behavior.
- Drag the **Pause** or editor pencil icon to move it. Play and Edit use the same cutout-safe canvas, with a small **Recenter** icon at the top during motion play. Tap outside the editor tools to return to the canvas. The orientation icon in the tools cycles through system rotation, portrait, and landscape. Paused options follow device rotation.
- Drag the white preview handle down to collapse the preview or up to reopen it. Tapping the handle also works. In landscape, use the navigation menu to switch between labels and icons.
- In **Input bindings**, choose a slot and select **Capture keyboard or mouse**. Press a supported key or mouse button on an input device connected to the phone, or enter the displayed code in **Capture a controller binding** on the PC dashboard. The dashboard captures one event while its capture button is focused.
- Use **More → Export profile bundle** to include a custom profile and its switch targets in one file. **Import JSON** accepts a single profile or a bundle. The editor’s **Show saved control colours** option displays personal colours while keeping the default palette available.
- If the library cannot be read, use **Recover saved profiles** to export its original contents or reset it after preserving a backup.

| Built-in profile   | Controls                                                                       |
| ------------------ | ------------------------------------------------------------------------------ |
| Default Controller | Basic keyboard controls                                                        |
| Xbox Controller    | Xbox buttons, sticks, and triggers                                             |
| Tilt Racing        | Tilt steering with keyboard acceleration and reverse                           |
| Driving            | Tilt steering, analog acceleration and braking, and driving actions            |
| FPS                | Movement stick, mouse aim, shoulder-button gyro activation, and action buttons |
| Flight             | Pitch and roll motion, yaw stick, throttle, and flight actions                 |
| Presentation       | Pointer, click, and previous or next slide                                     |
| Desktop            | Touchpad, mouse buttons, scrolling, and keyboard shortcuts                     |

### Virtual Gamepad Setup

For virtual Xbox output, use PowerShell 7.6 or later in an administrator terminal for first-time driver verification and installation.

```powershell
pwsh -NoProfile -File scripts/test-gamepad.ps1 -Verify -InstallDriver
```

With the receiver running, start the helper from a second terminal and approve the Windows prompt. The startup command uses the Windows PowerShell included with Windows.

```powershell
npm run start:gamepad
```

Select a profile with Xbox bindings on the phone. Check the dashboard’s **Virtual Gamepad** card or open Windows Game Controllers with `joy.cpl`.

### Connection Command Options

Pass options after npm’s `--` separator.

| Option                 | Purpose                                                                                 |
| ---------------------- | --------------------------------------------------------------------------------------- |
| `-Address IP:PORT`     | Supply the current wireless connection address                                          |
| `-Pair`                | Pair before connecting wirelessly                                                       |
| `-PairAddress IP:PORT` | Supply the pairing address and enter the code interactively                             |
| `-Reinstall`           | Install the source-built APK again, using a `connect:usb` or `connect:wireless` command |

## Build Configuration

| Component                     | Configuration                          |
| ----------------------------- | -------------------------------------- |
| JDK                           | 25                                     |
| Gradle                        | 9.9.1, pinned in the wrapper           |
| Android Gradle Plugin         | 9.5.1 with built-in Kotlin compilation |
| Compile SDK / Build Tools     | 37.0 / 37.0.0                          |
| Target / minimum Android API  | 35 / 26                                |
| Java / Kotlin bytecode target | 17                                     |

## Project Structure

| Path                                                     | Purpose                                                                           |
| -------------------------------------------------------- | --------------------------------------------------------------------------------- |
| `apk/`                                                   | Prebuilt Android companion and checksum                                           |
| `android/app/src/`                                       | Android UI, profile editor, touch and motion processing, transport, and JVM tests |
| `pc/server.js`                                           | Local HTTP server and TCP receiver                                                |
| `pc/dashboard.js`, `pc/orientation.js`                   | Dashboard rendering, quaternion math, and telemetry                               |
| `pc/controller.js`, `pc/windows-input.js`, `pc/gamepad/` | Input validation, routing, and Windows output state                               |
| `pc/windows/`                                            | Native Windows input and virtual-device helpers                                   |
| `scripts/`                                               | Tool setup, builds, connections, source export, and cleanup                       |
| `tests/`                                                 | Desktop and integration tests                                                     |

## Streaming Behavior

Orientation and accelerometer listeners request a 20 ms interval. The gyro listener requests 10 ms when needed for motion. The dashboard shows measured sensor event rates, which depend on the phone. Telemetry sends the latest sample on a roughly 20 ms cadence. Touch-look reports use a separate ordered path so dashboard polling does not set the pointer update rate.

Android sends newline-delimited JSON over the loopback TCP receiver on port `8765`. Orientation samples use normalized `[x, y, z, w]` quaternions on the PC. The rotation-vector sensor can provide magnetic heading. The game rotation sensor reports relative heading and can drift. Recenter adjusts the relative motion reference while keeping the absolute phone model intact. The dashboard sample age measures elapsed time since PC receipt.

## API Endpoints

The dashboard and API listen on `127.0.0.1:8000`.

| Method | Endpoint     | Description                                                 |
| ------ | ------------ | ----------------------------------------------------------- |
| GET    | `/`          | Dashboard with the live phone model and connection guidance |
| GET    | `/api/state` | Read-only sensor, motion, connection, and controller state  |

## Running Tests and Maintenance

```powershell
npm test
npm run format:check
npm run test:android
npm run start:gamepad -- -BuildOnly
```

Use `npm run test:android -- -Package` to build and verify a debug APK and checksum under `dist/`. Use `npm run export:source` for a source-only ZIP. With builds stopped, `npm run clean` removes generated files. Add `-- -BuildCaches` for a clean Android rebuild.

For optional native tests with the Windows helper and driver running, use PowerShell.

```powershell
$env:COMPASSLINK_NATIVE_GAMEPAD_TEST = '1'
node --test tests/gamepad-native.test.js
```

## Troubleshooting

- **Phone is missing or unauthorized** — Unlock it, check that the USB cable supports data, and accept the debugging prompt. For wireless, reread the current address from the main debugging screen.
- **Dashboard is waiting for the phone** — Confirm the receiver is running, rerun the matching connection command, and tap **Check link**.
- **Virtual gamepad stays inactive** — Verify the driver and helper, then check that an XInput slot is available.
- **A game ignores keyboard or mouse input** — Keep the game and receiver at the same Windows privilege level. Some games ignore software-injected input. The virtual-device helper also provides USB HID keyboard output.
- **Motion output is idle** — Check sensor availability, the active mapping, and the gyro enable control. Tap **Recenter** to capture a new neutral pose.
- **The app behaves like an earlier build** — Reconnect using the source connection command with `-Reinstall`.

## References and Attribution

- [Android Debug Bridge and wireless debugging](https://developer.android.com/tools/adb)
- [Android motion sensors and device coordinates](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion)
- The Windows helper uses pinned HIDMaestro binaries and retrieves their upstream notices during setup.
