# Snapshot 2 — Custom Touchscreen Controller Builder

Version `1.2-snapshot.2` (Android version code 3) adds an Android profile library, a normalized layout editor, and native Windows keyboard and mouse output. The build produces `android/app/build/outputs/apk/debug/app-debug.apk`. The versioned prebuilt copy is `apk/CompassLink3Dv1.2.apk`.

## Creating and using a layout

Open **Controller Mode → Profiles → New**, then start blank or from the built-in layout. The editor and Play Mode use the same canvas geometry. The canvas stores normalized centers and dimensions and accounts for system bars, cutouts, physical touch target size, and rotated edges. Selection uses the topmost visible geometry at the touch point. **Layout → Select a control** can select overlapping or hidden controls by name. Circle and joystick geometry stays square.

Drag the selected object to move it. Use **↔** to resize it and **↻** to rotate it. **Properties** offers precise geometry, appearance, input bindings, and behavior. **Arrange** sets z-order, duplicates or deletes a control, and toggles uniform resizing. Undo and redo retain 40 edit steps. Exiting an unsaved editor prompts to save, discard, or cancel.

| Control | Play Mode behavior |
| --- | --- |
| Button | Tap or hold a key, key combination, or mouse button. |
| D-pad | Independent direction bindings with diagonal combinations. |
| Joystick | Center-return thumb, configurable dead zone, travel radius, actuation threshold and hysteresis. Analog samples remain available separately from keyboard directions. |
| Trigger | Vertical travel controls a digital press threshold; lifting releases the binding. |
| Slider | Horizontal or vertical value with configurable minimum and maximum. A bound output is held while touching within the configured range. An optional wheel binding scrolls as the thumb moves. |
| Touchpad | Relative motion with sensitivity and X/Y inversion. Optional tap-to-click uses the selected mouse button. |

**Save** writes to Android app-private storage with `AtomicFile` and selects the saved profile. **Play** waits for profile acknowledgement from the PC. The profile library supports selection, renaming, duplication, deletion and import/export with the Android Storage Access Framework. The six-button **Default Controller** is protected and can be selected after editing or deleting a custom profile.

## Profile JSON schema

The root has `schemaVersion: 2`, a stable `profileId`, a `name`, millisecond `createdAt` and `modifiedAt` timestamps, a preferred `orientation` of `landscape` or `portrait`, and a z-ordered `controls` array. A control has `id`, `type`, `label`, `geometry`, `style`, `settings`, and `bindings`:

```json
{
  "schemaVersion": 2,
  "profileId": "example",
  "name": "Example",
  "createdAt": 1795190400000,
  "modifiedAt": 1795190400000,
  "orientation": "landscape",
  "controls": [
    {
      "id": "sprint",
      "type": "BUTTON",
      "label": "Sprint",
      "geometry": { "x": 0.8, "y": 0.6, "width": 0.16, "height": 0.32, "rotation": 0 },
      "style": {
        "background": "#1C2C47", "border": "#476487", "text": "#EDF4FF", "pressed": "#167B8D",
        "opacity": 1, "shape": "ROUNDED_RECTANGLE", "cornerRadius": 0.18, "visible": true
      },
      "settings": {
        "deadZone": 0.2, "radius": 0.74, "threshold": 0.48, "hysteresis": 0.08,
        "minimum": 0, "maximum": 100, "rangeStart": 0.7, "rangeEnd": 1, "vertical": false,
        "sensitivity": 1, "invertX": false, "invertY": false, "tapToClick": true
      },
      "bindings": { "press": { "type": "KEYBOARD", "key": "W", "modifiers": ["SHIFT"] } }
    }
  ]
}
```

- Types are `BUTTON`, `DPAD`, `JOYSTICK`, `TRIGGER`, `SLIDER`, and `TOUCHPAD`. Supported slots are `press`, the four directional slots, `pointer`, `tap`, and `wheel`, depending on the control.
- Geometry stores normalized center X/Y and normalized width/height with a degree rotation. Colors are `#RRGGBB`. Shape options are `CIRCLE`, `ROUNDED_RECTANGLE`, and `RECTANGLE`.
- Bindings are `KEYBOARD` with a key and optional modifiers, `MOUSE_BUTTON` with a button, `POINTER`, and `WHEEL`. An absent slot is unbound. Reserved `GAMEPAD` bindings persist a symbolic `target` locally and are excluded from the PC descriptor.
- Import limits profiles to 64 KiB, 32 controls, 48-character names, bounded finite geometry and settings, known styling and bindings, and unique safe IDs. Imported profiles receive a new ID. Up to 32 custom profiles are stored.

## Transport and native output

The existing loopback ADB reverse connection continues to carry newline-delimited JSON. Legacy Snapshot 1 packets retain version 1 and the original fixed mappings, and V1 sensor packets retain their original format. Snapshot 2 uses version 2 with the same authorization grant, session UUID, heartbeat lease, and strictly increasing sequence.

| Frame | Snapshot 2 behavior |
| --- | --- |
| `controller.start` / `controller.ready` | Authenticate the authorized Android session. |
| `controller.profile` | Send profile ID, name, visible control descriptors and binding slots. Geometry and style stay on Android. |
| `controller.profile.ready` / `controller.profile.error` | Acknowledge or reject the profile sequence as its revision. |
| `controller.input` | Ordered digital DOWN/UP for a control and slot in an acknowledged revision. |
| `controller.axis` | Coalesced, normalized analog sample. Joystick uses X/Y; trigger and slider use X for their level. |
| `controller.pointer` / `controller.wheel` | Coalesced relative mouse deltas or wheel detents, accepted only for the matching bound control. |
| `controller.neutral` | Clear held outputs and continuous state on gesture cancellation or focus loss. |

Standard frames are capped at 4 KiB and profile frames at 32 KiB. Android prioritizes digital transitions and the session heartbeat while coalescing axes, pointer deltas, and scroll. The server validates the profile and applies its revision before accepting input. Switching profiles or editing closes the old Android session. A new or rejected desktop profile clears all previous outputs and invalidates old revisions.

The Windows helper retains the existing standalone process and private pipes. Node tracks ownership of each key or mouse button across active controls. A duplicate assignment produces one down event and releases only after the last owner releases. Modifier chords press modifiers first and release ordinary keys before modifiers. The helper independently validates native commands and releases keyboard and mouse state on reset, pipe EOF, or lease expiry. Supported names come from [the keyboard whitelist](../../android/app/src/main/assets/controller-keys.json) and include letters, digits, navigation/editing keys, modifiers, common punctuation and F1–F12. Mouse output includes left, right and middle button transitions, relative movement, and vertical wheel.

The dashboard shows the synchronized profile, mapped control slots, held controls, engine state, and profile errors. Its sensor and orientation routes are unchanged. Windows PowerShell still compiles the helper on demand. JUnit 4 and a JVM JSON implementation are Android test dependencies only.

## Files changed

Android production Kotlin files live in `android/app/src/main/java/com/compasslink3d/`.

| File | Purpose |
| --- | --- |
| `ControllerProfile.kt` | Versioned layout data, built-in profile and strict JSON codec. |
| `ControllerProfileStore.kt` | Atomic storage and persisted profile selection. |
| `ControllerProfiles.kt` | Profile library, creation, duplication, renaming and deletion. |
| `ControllerEditor.kt` | Editor navigation and control, appearance and binding dialogs. |
| `ControllerEditorCanvas.kt` | Pointer-owned editing gestures, placement and bounded edit history. |
| `ControllerGeometry.kt` | Normalized viewport, rotated bounds and hit testing. |
| `ControllerPad.kt` | Shared visual canvas and locked Play Mode view with accessible control labels. |
| `ControllerTouchEngine.kt` | Multi-touch state and digital, analog, pointer and wheel signals. |
| `ControllerUi.kt` | Shared Android builder buttons, dialogs and property field validation. |
| `ControllerProtocol.kt` | Version 2 messages and continuous input coalescing. |
| `UsbSensorTransport.kt` | Profile handshake and ordered controller output on the existing socket. |
| `MainActivity.kt` | Activity lifecycle, profile and editor navigation, and Android document picker integration. |
| `android/app/build.gradle.kts` | Version bump and JVM test dependencies. |
| `android/app/src/main/assets/controller-keys.json` | Shared app and server keyboard name whitelist. |
| `android/app/src/test/java/com/compasslink3d/ControllerProfileTest.kt` | Schema, import validation and history tests. |
| `android/app/src/test/java/com/compasslink3d/ControllerTouchTest.kt` | Geometry, touch and coalescing tests. |
| `pc/controller-profile.js` | Desktop mapping schema, validation and descriptions. |
| `pc/controller.js` | Revision-gated controller sessions, native input ownership and dashboard state. |
| `pc/server.js` | Bounded profile-frame support on the existing receiver. |
| `pc/windows/InputEngine.cs` | Expanded keyboard whitelist, mouse output and shared cleanup. |
| `pc/index.html`, `pc/dashboard.js`, `pc/styles.css` | Read-only active profile, sync status and control diagnostics. |
| `tests/controller.test.js`, `tests/controller-profile.test.js` | Legacy and version 2 transport regressions and mapping validation. |
| `README.md`, `docs/snapshots/SNAPSHOT_02.md` | Usage, build, schema and verification documentation. |
| `apk/CompassLink3Dv1.2.apk` | Versioned prebuilt debug APK. |

## Build and verify

```powershell
npm install
npm run setup:android
npm run build:android
npm run test:android
npm test
npm start
# In a second terminal, install the new source build once using the required connection:
npm run connect:usb -- -Reinstall
npm run connect:wireless -- -Pair -Reinstall
```

For future reconnections to an already installed APK, use `npm run connect:prebuilt:usb` or `npm run connect:prebuilt:wireless` with `-- -Pair` only when pairing is needed. These commands skip installation.

The 23 Node tests use a test input helper and cover the legacy protocol, validation, profile revisions, ownership, keyboard and mouse commands, sensor streaming and disconnects. Nine Android JVM tests cover schema round-trips and rejection, normalized and rotated geometry, undo and redo limits, overlap, multi-touch ownership, joystick direction thresholds, touchpad inversion, sliders and coalescing.

Android assembly and lint passed. The repository-wide format check reported the same nine formatting failures as the local baseline. The changed desktop JavaScript, CSS and test files passed the targeted check.

The packaged APK matches the tested debug build byte-for-byte. Android's signature verifier accepted it. Its SHA-256 is `78cd5e7f9b4a92702e574bc9c81953beeea23a3c38b5d65a34b9bb8e63f2a5ff`.

A Windows input probe recorded 77 supported keys, all three mouse buttons, relative movement, vertical wheel output and held-input release on pipe closure and watchdog expiry. It intercepted only CompassLink's tagged events to keep the verification isolated from the foreground application.

On a physical Android phone over wireless ADB, the built-in controller produced W while real sensor samples continued. A blank layout was created in the editor with all six control types. Undo and redo were exercised, and dragging, resizing and rotating a control produced the expected normalized values after saving. The profile reloaded after an Android process restart. Play Mode synchronized the saved mappings and produced a Shift+A chord, joystick W while the button was held, touchpad movement and left click, trigger right click, and slider wheel input. Backgrounding the phone released the held mouse button.

The same phone exported a profile through the document picker and imported it with identical controls and a new profile ID. Renaming, duplicating and deleting a test profile persisted in the Android profile store. Removing the final test profile selected the built-in controller again.

Physical USB and first-time wireless pairing remain unverified. The already paired wireless connection and the prebuilt no-install path were exercised on the phone. Automated Android tests verified malformed and unsupported profiles. The phone's document picker previewed the invalid test file during the negative import check.

For manual acceptance, create a blank profile and add each control. Move, resize, rotate, restyle, remap, order, delete, undo, and redo. Save, restart the app, export and import a copy, and try an invalid file. In Play Mode, focus a Windows input tester and verify the assigned keys, mouse buttons, motion and wheel. Hold two controls with the same key, release them separately, then test disconnect or backgrounding while a key and a mouse button are held. Repeat on USB and wireless, and check the live 3D sensor feed.

Snapshot 3 can extend the reserved gamepad binding type and consume the normalized joystick and trigger values. Use a slider with a wheel binding for scrolling in Snapshot 2. The touchpad supports single-finger input.
