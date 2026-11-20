# Snapshot 1 — Touchscreen Keyboard Controller

CompassLink Motion Control adds a landscape Android controller that sends WASD, Space and Escape to a compatible foreground Windows application. Version `1.1-snapshot.1` (version code 2) builds to `android/app/build/outputs/apk/debug/app-debug.apk` and is available as [CompassLink3Dv1.1.apk](../../apk/CompassLink3Dv1.1.apk).

The implementation is complete and has passed physical wireless phone-to-Windows testing. Hardware acceptance remains open for USB and first-time wireless pairing.

## Integration

The original Kotlin activity uses Android's fused rotation-vector sensor, falling back to the game rotation vector. It streams the latest quaternion as newline-delimited JSON through `adb reverse tcp:8765 tcp:8765`. The loopback Node server normalizes samples and serves the orientation canvas and telemetry on port 8000.

`ControllerPad` tracks Android pointer IDs and emits transitions when a control gains its first pointer or loses its last one. Moving between controls releases the previous control and can engage the next. Sliding outside, touch cancellation and losing window focus release contacts. The existing transport class writes ordered controller transitions alongside coalesced sensor samples on one socket. Opening or leaving Controller Mode opens a fresh socket on the same tunnel. Backgrounding, stopping, or leaving the mode closes its session. Starting sensor streaming before entering Controller Mode keeps samples flowing in that mode.

### Protocol and authorization

Frames are one UTF-8 JSON object per line on port 8765. Legacy quaternion frames retain their existing fields. Controller frames use version 1 and a per-socket UUID. For example, after authorization:

```json
{"version":1,"type":"controller.key","sessionId":"12345678-aaaa-bbbb-cccc-123456789012","sequence":42,"control":"DPAD_UP","action":"DOWN"}
```

| Frame | Purpose |
| --- | --- |
| `controller.start` | Sequence zero. Presents the latest ADB connection grant in `token`. The PC replies with `controller.ready` or `controller.error`. |
| `controller.key` | A whitelisted control with a strictly increasing sequence and a `DOWN` or `UP` action. |
| `controller.heartbeat` | Sent about every 500 ms once ready. The PC acknowledges it and expires the session after two seconds without a valid heartbeat. |
| `controller.stop` | Explicit termination. The Android app terminates by closing the socket, which has the same release behavior. |

The USB and wireless connection scripts create a random 256-bit grant after ADB authorizes the phone and configures the reverse tunnel. They pass the grant to the app at launch and store the host copy under the ignored `.tools/` directory. If Android only brings an existing task forward, the command retries delivery to the foreground activity. A new grant invalidates the previous session by its next input or heartbeat. The TCP and HTTP listeners bind to loopback. The dashboard API is read-only and omits the grant.

The PC accepts input from one authorized socket at a time. The state manager rejects invalid controls and stale sequences, keeps overlapping holds independent and resets input on termination, replacement and shutdown. The standalone Windows helper receives only fixed key operations, reset and lease renewal messages over private standard input. It calls Win32 `SendInput` and tracks the keys it owns. It releases them on reset, parent pipe EOF or a three-second lease expiry and reports Windows input errors to the backend. Running independently of Node's Windows process job lets the helper handle pipe EOF after an abrupt backend exit.

### Files and dependencies

| File | Change |
| --- | --- |
| `android/app/build.gradle.kts` | Snapshot APK version. |
| `android/app/src/main/AndroidManifest.xml` | Keeps the existing activity through controller orientation changes. |
| `MainActivity.kt` | Mode selection, controller screen, landscape layout and lifecycle cleanup. |
| `ControllerPad.kt` | Six controls, independent pointer tracking and pressed states. |
| `ControllerProtocol.kt` | Control identifiers and versioned Android frames. |
| `UsbSensorTransport.kt` | Shared transport, controller ordering, replies and neutral reconnects. |
| `pc/controller.js` | Validation, mappings, authorization, held state and diagnostics. |
| `pc/server.js` | Multiplexed TCP receiver and controller state in the existing dashboard API. |
| `pc/windows-input.js` | Helper build cache, launch, private IPC and process cleanup. |
| `pc/windows/InputEngine.ps1` | Compiles the standalone C# helper with Windows PowerShell 5.1. |
| `pc/windows/InputEngine.cs` | Native keyboard input, lease watchdog and key cleanup. |
| `pc/index.html`, `pc/dashboard.js`, `pc/styles.css` | Motion Control card and live state display alongside the existing canvas. |
| `scripts/connect-phone.ps1` | Generates a grant after either ADB workflow without changing installation conditions. |
| `scripts/toolchain.ps1` | Escapes the generated SDK path for Java properties and Android lint. |
| `tests/controller.test.js` | TCP tests for mappings, authorization, sequencing, sensor coexistence and cleanup. |
| `README.md` | Installation, controller connection and testing instructions. |
| `docs/snapshots/SNAPSHOT_01.md` | Architecture and verification record. |

The four Kotlin files are in `android/app/src/main/java/com/compasslink3d/`. Windows PowerShell 5.1 and its system .NET Framework compile the C# helper on demand into the ignored `.tools/windows-input/` directory. There are no additional npm, Android or .NET SDK dependencies. The snapshot uses the existing npm commands.

## Verification

- `npm install` and `npm run setup:android` succeeded.
- `npm run build:android` assembled the Snapshot 1 APK. `npm run test:android` passed assembly and lint after correcting the generated SDK path. Android unit-test discovery reported `NO-SOURCE`.
- `npm test` passed all 17 tests, including the existing quaternion and loopback receiver tests and four new TCP controller tests. The TCP tests verify protocol, routing and state with a test engine.
- `npm start` served the dashboard and reported the real Windows engine ready. The updated APK installed on a physical Android phone over wireless ADB. The prebuilt wireless alias reused that installed APK without installing again.
- A Windows key-state probe observed native W, S, A, D, Space and Escape transitions generated by real Android touch events. Scripted multi-touch on the phone verified simultaneous W+A, releasing A independently, sliding W off its button, repeated Space while holding D, and touch cancellation. Real rotation-vector samples continued while Controller Mode was active.
- On that phone, the held button changed color, backgrounding and Android process exit released keys, disconnecting wireless released Escape, and reopening Controller Mode started neutral. After terminating the Windows server with D held, the probe observed D up and the phone reconnected to the new server with a neutral session. The Stop Controller action returned to the sensor screen.
- The native Windows probe observed an independent watchdog release after lease expiry and a key release on helper pipe EOF.
- Prettier passed for the changed JavaScript, CSS and controller tests. The repository-wide format check flags existing formatting in the README, HTML, package file and tooling files.

Repeat the [README controller checks](../../README.md#controller-mode-snapshot-1) with a USB debugging cable and a wireless-paired phone. With a Windows key-state tester in the foreground, verify all six mappings and that the held set clears when you unplug, disconnect, background the app, stop Controller Mode, or restart the desktop server. Reopen Controller Mode to confirm it starts neutral. USB hardware, first-time wireless pairing and individual game compatibility require separate verification. Windows integrity levels and applications that ignore synthetic input can restrict output.

## Future snapshots

- **Snapshot 2:** A layout editor can supply control positions and mapping data to the existing touch and mapping layers.
- **Snapshot 3:** The desktop output interface can target a virtual gamepad driver.
- **Snapshot 4:** The existing orientation stream can feed motion-to-input mapping.
- **Snapshot 5:** Touch and motion sources can share session-scoped input state and output.
