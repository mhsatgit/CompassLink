import { multiply, rotate, toView, orientationAngles } from "./orientation.js";
import "./binding-ui.js";

const canvas = document.querySelector("#view");
const context = canvas.getContext("2d");
const field = (id, value) => {
  const element = document.getElementById(id);
  if (element.textContent !== value) element.textContent = value;
};
const modeButtons = document.querySelectorAll("[data-mode]");
for (const button of modeButtons) {
  button.addEventListener("click", () => {
    for (const option of modeButtons) {
      const selected = option === button;
      option.classList.toggle("selected", selected);
      option.setAttribute("aria-pressed", String(selected));
    }
    document.getElementById("guide-usb").hidden = button.dataset.mode !== "usb";
    document.getElementById("guide-wireless").hidden =
      button.dataset.mode !== "wireless";
  });
}
let quaternion = [0, 0, 0, 1];
let orbit = [0, 0];
let pointer = null;

const vertices = [
  [-0.43, -0.83, -0.07],
  [0.43, -0.83, -0.07],
  [0.43, 0.83, -0.07],
  [-0.43, 0.83, -0.07],
  [-0.43, -0.83, 0.07],
  [0.43, -0.83, 0.07],
  [0.43, 0.83, 0.07],
  [-0.43, 0.83, 0.07],
];
const faces = [
  { indices: [0, 1, 5, 4], color: "#393239" },
  { indices: [1, 2, 6, 5], color: "#2b262b" },
  { indices: [2, 3, 7, 6], color: "#4f3840" },
  { indices: [3, 0, 4, 7], color: "#3c292f" },
  { indices: [0, 3, 2, 1], color: "#261a1d" },
  { indices: [4, 5, 6, 7], color: "#634149" },
];
const normalize = (v) => v.map((n) => n / Math.hypot(...v));
const dot = (a, b) => a.reduce((sum, n, i) => sum + n * b[i], 0);
const cross = (a, b) => [
  a[1] * b[2] - a[2] * b[1],
  a[2] * b[0] - a[0] * b[2],
  a[0] * b[1] - a[1] * b[0],
];

function draw() {
  if (!context) return;
  const { width, height } = canvas.getBoundingClientRect();
  const ratio = window.devicePixelRatio || 1;
  const pixelWidth = Math.round(width * ratio);
  const pixelHeight = Math.round(height * ratio);
  if (canvas.width !== pixelWidth || canvas.height !== pixelHeight) {
    canvas.width = pixelWidth;
    canvas.height = pixelHeight;
  }
  context.setTransform(ratio, 0, 0, ratio, 0, 0);
  context.clearRect(0, 0, width, height);
  context.strokeStyle = "#34343b";
  for (let i = 0; i < 5; i++) {
    context.beginPath();
    context.ellipse(
      width / 2,
      height * 0.83,
      40 + i * 31,
      9 + i * 7,
      0,
      0,
      Math.PI * 2,
    );
    context.stroke();
  }
  const [yaw, pitch] = orbit;
  const cameraRotation = multiply(
    [Math.sin(pitch / 2), 0, 0, Math.cos(pitch / 2)],
    [0, Math.sin(yaw / 2), 0, Math.cos(yaw / 2)],
  );
  const transform = (v) =>
    rotate(toView(rotate(v, quaternion)), cameraRotation);
  const camera = normalize([0.38, 0.6, 1]);
  const right = normalize(cross([0, 1, 0], camera));
  const up = cross(camera, right);
  const scale = Math.min(width * 0.32, height * 0.34);
  function project(p) {
    const depth = dot(p, camera);
    const perspective = (scale * 3.3) / (3.3 - depth);
    return [
      width / 2 + dot(p, right) * perspective,
      height * 0.48 - dot(p, up) * perspective,
      depth,
    ];
  }
  function polygon(points, color) {
    context.beginPath();
    points.forEach(([x, y], i) =>
      i ? context.lineTo(x, y) : context.moveTo(x, y),
    );
    context.closePath();
    context.fillStyle = color;
    context.fill();
    context.strokeStyle = "#f26778";
    context.lineWidth = 1.3;
    context.stroke();
  }
  const projected = vertices.map(transform).map(project);
  const orderedFaces = faces
    .map((face) => ({
      ...face,
      depth: face.indices.reduce((sum, i) => sum + projected[i][2], 0) / 4,
    }))
    .sort((a, b) => a.depth - b.depth);
  for (const face of orderedFaces)
    polygon(
      face.indices.map((i) => projected[i]),
      face.color,
    );
  // Draw the display only when the outward-facing +Z normal points at the camera.
  const frontCenter = transform([0, 0, 0.07]);
  const eyeDirection = camera.map((v, i) => v * 3.3 - frontCenter[i]);
  if (dot(transform([0, 0, 1]), eyeDirection) > 0) {
    polygon(
      [
        [-0.35, -0.68, 0.075],
        [0.35, -0.68, 0.075],
        [0.35, 0.68, 0.075],
        [-0.35, 0.68, 0.075],
      ]
        .map(transform)
        .map(project),
      "#09090a",
    );
    const [x, y] = project(transform([0, 0.56, 0.08]));
    context.fillStyle = "#ff3b4e";
    context.beginPath();
    context.arc(x, y, 4, 0, Math.PI * 2);
    context.fill();
  }
  context.fillStyle = "#b5b5be";
  context.font = "12px system-ui";
  context.fillText(
    "World: East / Up / −North · cyan dot marks top",
    18,
    height - 18,
  );
}

canvas.addEventListener("pointerdown", (event) => {
  if (pointer || event.button !== 0) return;
  pointer = { id: event.pointerId, x: event.clientX, y: event.clientY };
  canvas.setPointerCapture(event.pointerId);
});
canvas.addEventListener("pointermove", (event) => {
  if (pointer?.id !== event.pointerId) return;
  orbitBy(
    (event.clientX - pointer.x) * 0.008,
    (event.clientY - pointer.y) * 0.008,
  );
  pointer.x = event.clientX;
  pointer.y = event.clientY;
});
function orbitBy(yaw, pitch) {
  orbit[0] += yaw;
  orbit[1] = Math.max(-1.4, Math.min(1.4, orbit[1] + pitch));
  draw();
}
for (const name of ["pointerup", "pointercancel", "lostpointercapture"])
  canvas.addEventListener(name, (event) => {
    if (pointer?.id === event.pointerId) pointer = null;
  });
function resetOrbit() {
  orbit = [0, 0];
  draw();
}
canvas.addEventListener("dblclick", resetOrbit);
const arrowRotation = new Map([
  ["ArrowLeft", [-0.12, 0]],
  ["ArrowRight", [0.12, 0]],
  ["ArrowUp", [0, -0.12]],
  ["ArrowDown", [0, 0.12]],
]);
canvas.addEventListener("keydown", (event) => {
  const delta = arrowRotation.get(event.key);
  if (delta) orbitBy(...delta);
  else if (event.key === "Home") resetOrbit();
  else return;
  event.preventDefault();
});
window.addEventListener("resize", draw);

const controllerControls = document.getElementById("controller-controls");
const controlLabels = {
  DPAD_UP: "D-pad Up",
  DPAD_DOWN: "D-pad Down",
  DPAD_LEFT: "D-pad Left",
  DPAD_RIGHT: "D-pad Right",
  ACTION_A: "Action A",
  ACTION_B: "Action B",
};
const keyLabels = { SPACE: "Space", ESCAPE: "Escape" };
let renderedMappings = null;

function renderController(controller, connected) {
  const link =
    controller?.connectionMode === "wireless" ? "wireless debugging" : "USB";
  const modes = {
    gamepad: "Virtual Gamepad",
    keyboard: "Keyboard / Mouse",
    hybrid: "Hybrid",
  };
  field(
    "controller-mode",
    controller?.active
      ? `${modes[controller.outputMode] ?? "Controller"} · ${link}`
      : "Controller off",
  );
  field(
    "controller-engine",
    (controller?.outputMode === "gamepad"
      ? controller.gamepad?.message
      : controller?.engineMessage) ?? "Server unavailable",
  );
  field("controller-profile", controller?.profile?.name ?? "—");
  document.getElementById("controller-profile").title =
    controller?.profile?.id ?? "";
  field(
    "controller-sync",
    controller?.synchronized
      ? `Synchronized · ${controller.profile.controls} controls${controller.profileRevision ? ` · Rev ${controller.profileRevision}` : ""}`
      : controller?.active
        ? "Waiting for profile"
        : "Waiting for controller",
  );
  field("controller-error", controller?.profileError ?? "");
  document.getElementById("controller-error").hidden =
    !controller?.profileError;
  const held = controller?.held ?? [];
  field(
    "controller-activity",
    !controller
      ? "Server unavailable"
      : controller.active
        ? held.length
          ? `${held.length} held · ${held.map(({ key }) => keyLabels[key] ?? key).join(", ")}`
          : controller.synchronized
            ? "Ready for input"
            : "Input paused"
        : connected
          ? "Open Controller Mode on your phone"
          : "Waiting for phone",
  );
  const currentMappings = controller
    ? `${controller.protocolVersion}:${controller.mappingRevision}`
    : null;
  if (currentMappings !== renderedMappings) {
    renderedMappings = currentMappings;
    controllerControls.replaceChildren();
    const entries =
      controller?.protocolVersion >= 2
        ? controller.controls.flatMap(({ id, label, bindings }) =>
            bindings.map(({ slot, action }) => ({
              id,
              slot,
              label: ["press", "pointer"].includes(slot)
                ? label
                : `${label} · ${slot}`,
              key: action,
            })),
          )
        : Object.entries(controller?.mappings ?? {}).map(([id, key]) => ({
            id,
            label: controlLabels[id],
            key,
          }));
    for (const { id, slot, label, key } of entries) {
      const item = document.createElement("li");
      item.dataset.control = id;
      if (slot) item.dataset.slot = slot;
      const keycap = document.createElement("kbd");
      keycap.textContent = keyLabels[key] ?? key;
      item.append(label, keycap);
      controllerControls.append(item);
    }
  }
  for (const item of controllerControls.children) {
    item.classList.toggle(
      "held",
      held.some(
        ({ control, slot }) =>
          control === item.dataset.control && slot === item.dataset.slot,
      ),
    );
  }
  const event = controller?.lastEvent;
  field(
    "controller-last",
    event
      ? `Last event · ${event.label ?? controlLabels[event.control] ?? event.control} · ${keyLabels[event.key] ?? event.key} ${event.action.toLowerCase()}`
      : "No controller events",
  );
  renderHybrid(controller);
  renderGamepad(controller);
}

function renderHybrid(controller) {
  const hybrid = controller?.hybrid;
  const status = controller?.lastSwitch;
  const activation = hybrid?.gyroActivation;
  const gyroState = controller?.motions?.find(
    ({ source }) => source === "GYROSCOPE",
  )?.fresh;
  const orientation = {
    SYSTEM: "Follow system",
    PORTRAIT: "Portrait lock",
    LANDSCAPE: "Landscape lock",
  };
  field(
    "controller-hybrid",
    hybrid
      ? [
          `Keyboard / mouse ${hybrid.keyboard ? "ready" : "idle"}`,
          `Xbox ${hybrid.gamepad ? "ready" : "idle"}`,
          orientation[hybrid.orientationPolicy],
          hybrid.haptics ? `Haptics ${hybrid.haptics.toLowerCase()}` : "",
          activation
            ? `Gyro ${gyroState ? "on" : "off"} · ${activation.control ? `${activation.control}:${activation.slot}` : "Dedicated"} (${activation.behavior.toLowerCase()})`
            : "",
        ]
          .filter(Boolean)
          .join(" · ")
      : "—",
  );
  field(
    "controller-switch",
    status
      ? `Switch ${status.status} · ${status.profile ?? status.message} · ${status.durationMs} ms on desktop`
      : "—",
  );
  const owners = hybrid?.analog;
  const axis = owners
    ? Object.entries(owners.axes)
        .filter(([, owner]) => owner)
        .map(([target, owner]) => `${target.replaceAll("_", " ")} · ${owner}`)
    : [];
  if (owners?.restored) axis.push(`Restored ${owners.restored.owner}`);
  field("controller-analog-owners", axis.join(" · ") || "Analog owners idle");
  const digital = (controller?.held ?? []).map(
    ({ control, slot, key }) => `${key} · ${control}${slot ? `:${slot}` : ""}`,
  );
  field(
    "controller-digital-owners",
    digital.join(" · ") || "Digital owners idle",
  );
  field(
    "controller-motion-owners",
    controller?.motions
      ?.map(
        ({ mapping, source, fresh }) =>
          `${source === "GYROSCOPE" ? "Gyro" : "Orientation"} to ${mapping.toLowerCase()} · ${fresh ? "fresh" : "idle"}`,
      )
      .join(" · ") || "Motion idle",
  );
}

const digitalLabels = {
  LB: "LB",
  RB: "RB",
  BACK: "Back",
  START: "Start",
  L3: "L3",
  R3: "R3",
  up: "↑",
  down: "↓",
  left: "←",
  right: "→",
  X: "X",
  Y: "Y",
  A: "A",
  B: "B",
};

function gamepadView(id, title) {
  const panel = document.getElementById(id);
  const label = Object.assign(document.createElement("h3"), {
    textContent: title,
  });
  const status = Object.assign(document.createElement("p"), {
    className: "hint",
  });
  const digital = Object.assign(document.createElement("div"), {
    className: "gamepad-buttons",
  });
  const buttons = Object.entries(digitalLabels).map(([key, textContent]) => {
    const cap = Object.assign(document.createElement("span"), {
      textContent,
      className: "gamepad-button",
    });
    digital.append(cap);
    return [key, cap, textContent];
  });
  const analog = Object.assign(document.createElement("div"), {
    className: "gamepad-analog",
  });
  const sticks = ["Left stick", "Right stick"].map((text) => {
    const item = document.createElement("div");
    const caption = Object.assign(document.createElement("div"), {
      className: "label",
      textContent: text,
    });
    const track = Object.assign(document.createElement("div"), {
      className: "gamepad-stick",
    });
    const thumb = document.createElement("span");
    const coordinates = Object.assign(document.createElement("div"), {
      className: "hint gamepad-coordinates",
    });
    track.append(thumb);
    item.append(caption, track, coordinates);
    analog.append(item);
    return { thumb, coordinates };
  });
  const triggers = ["LT", "RT"].map((text) => {
    const item = Object.assign(document.createElement("label"), {
      className: "label",
    });
    const caption = document.createElement("span");
    const meter = Object.assign(document.createElement("meter"), {
      min: 0,
      max: 1,
      value: 0,
    });
    item.append(caption, meter);
    analog.append(item);
    return { meter, caption, text };
  });
  panel.append(label, status, digital, analog);
  let previous;
  return (state, message) => {
    const snapshot = JSON.stringify([state, message]);
    if (snapshot === previous) return;
    previous = snapshot;
    status.textContent = message;
    for (const [key, cap, text] of buttons) {
      const pressed = state?.buttons[key] ?? state?.dpad[key] ?? false;
      cap.classList.toggle("held", pressed);
      cap.setAttribute(
        "aria-label",
        `${text} ${pressed ? "pressed" : "released"}`,
      );
    }
    for (const [index, position] of [
      state?.leftStick,
      state?.rightStick,
    ].entries()) {
      const x = position?.x ?? 0;
      const y = position?.y ?? 0;
      const travel = 28 / Math.max(1, Math.hypot(x, y));
      sticks[index].thumb.style.transform =
        `translate(${x * travel}px, ${-y * travel}px)`;
      sticks[index].coordinates.textContent = position
        ? `${x.toFixed(2)} / ${y.toFixed(2)}`
        : "—";
    }
    for (const [index, value] of [
      state?.leftTrigger,
      state?.rightTrigger,
    ].entries()) {
      const { meter, caption, text } = triggers[index];
      meter.value = value ?? 0;
      caption.textContent =
        value === undefined
          ? `${text} —`
          : `${text} ${Math.round(value * 100)}%`;
    }
  };
}

const commandedView = gamepadView("gamepad-commanded", "Commanded state");
const observedView = gamepadView("gamepad-observed", "Windows XInput readback");

function renderGamepad(controller) {
  const gamepad = controller?.gamepad;
  const acceptsGamepad = ["gamepad", "hybrid"].includes(controller?.outputMode);
  const mode = acceptsGamepad
    ? controller.outputMode === "hybrid"
      ? "Hybrid"
      : "Virtual Gamepad"
    : controller?.outputMode === "keyboard"
      ? "Keyboard / Mouse"
      : "Controller off";
  const status = gamepad?.status ?? "unavailable";
  field("gamepad-status", status[0].toUpperCase() + status.slice(1));
  const driver =
    gamepad?.installed === true
      ? "Driver installed"
      : gamepad?.installed === false
        ? "Driver installation required"
        : "Driver status unavailable";
  field(
    "gamepad-backend",
    [mode, gamepad?.backend, driver, controller?.profile?.name]
      .filter(Boolean)
      .join(" · "),
  );
  const profileError = acceptsGamepad ? controller.profileError : null;
  field(
    "gamepad-message",
    profileError ?? gamepad?.message ?? "CompassLink server unavailable.",
  );
  field(
    "gamepad-activity",
    acceptsGamepad && controller.synchronized
      ? "Accepting gamepad input"
      : "Gamepad input paused",
  );
  const event = controller?.lastGamepadEvent;
  field(
    "gamepad-last",
    event
      ? `${event.label ?? event.control} · ${event.key} ${event.action.toLowerCase()}`
      : "",
  );
  commandedView(
    gamepad?.commanded,
    gamepad?.commanded
      ? "Latest report sent to the Windows helper"
      : "No report sent",
  );
  observedView(
    gamepad?.observed?.state,
    gamepad?.observed?.state
      ? `XInput slot ${gamepad.slot}`
      : "No Windows readback",
  );
}

let displayedSample;
const motionSensorNames = {
  gameRotation: "Game rotation vector",
  rotation: "Rotation vector",
  gyroscope: "Gyroscope",
  accelerometer: "Accelerometer",
};
const motionValues = (value, digits = 2) =>
  value?.map((component) => component.toFixed(digits)).join(" / ") ?? "—";

function motionAngles(q) {
  if (!q) return "—";
  const { pitch, heading, roll, headingDefined } = orientationAngles(q);
  const yaw = ((heading + 180) % 360) - 180;
  return `${pitch.toFixed(1)}° / ${headingDefined ? yaw.toFixed(1) + "°" : "undefined"} / ${roll.toFixed(1)}°`;
}

function renderMotion(data) {
  const motion = data?.motion;
  document.getElementById("motion-panel").hidden = !motion;
  if (!motion) return;
  const health = (mapping) => {
    const gyro = mapping.source === "GYROSCOPE";
    const sensor = motion.sensors[gyro ? "gyroscope" : motion.orientation];
    if (!sensor?.available) return "unavailable";
    if (mapping.stale || motion.age > 2) return "stale";
    return gyro && !mapping.gyroEnabled ? "off" : "live";
  };
  const entries = motion.mappings ?? [motion];
  const calibrated =
    motion.centerAgeMs === null
      ? "Awaiting center"
      : `Centered ${(motion.centerAgeMs / 1000).toFixed(1)} s ago`;
  field(
    "motion-status",
    `${entries.some((mapping) => health(mapping) === "live") ? calibrated : "No live motion mapping"} · ${motion.invalid} rejected samples`,
  );
  field(
    "motion-source",
    motion.orientation === "rotation"
      ? "Rotation vector fallback"
      : motion.orientation === "gameRotation"
        ? "Game rotation vector"
        : "Orientation unavailable",
  );
  const mappings = {
    STICK: "Analog stick",
    MOUSE: "Mouse aim",
    STEERING: "Steering",
  };
  field(
    "motion-mapping",
    entries
      .map(
        (m) =>
          `${m.source === "GYROSCOPE" ? "Gyro" : "Tilt"} → ${mappings[m.mapping]} · ${m.target.replaceAll("_", " ")} · ${health(m)} · ${motionValues(m.output, 2)}`,
      )
      .join(" / "),
  );
  field(
    "motion-rates",
    Object.entries(motionSensorNames)
      .map(([key, label]) => {
        const sensor = motion.sensors[key];
        const rate = !sensor.available
          ? "unavailable"
          : !sensor.active
            ? "inactive"
            : sensor.hz === null
              ? "measuring"
              : `${sensor.hz.toFixed(1)} Hz`;
        return `${label} ${rate}`;
      })
      .join(" · "),
  );
  field("motion-relative-q", motionValues(motion.relative, 4));
  field("motion-absolute", motionAngles(data.stale ? null : data.last?.q));
  field("motion-relative-angles", motionAngles(motion.relative));
  field("motion-gyro", motionValues(motion.gyro));
  field("motion-accel", motionValues(motion.accel));
  field("motion-processed", motionValues(motion.output));
  const sent = data.controller.motion?.last;
  field(
    "motion-emitted",
    sent?.action === "stick"
      ? motionValues([sent.x, sent.y])
      : sent?.action === "mouse"
        ? `${sent.dx} / ${sent.dy} px`
        : sent?.action === "steering"
          ? `${sent.direction} ${sent.down ? "pressed" : "released"}`
          : sent?.action === "neutral"
            ? "Neutral"
            : "—",
  );
}

async function poll() {
  let delay = 250;
  try {
    const response = await fetch("/api/state", {
      cache: "no-store",
      signal: AbortSignal.timeout(2500),
    });
    if (!response.ok) throw new Error("Dashboard server unavailable");
    const data = await response.json();
    if (data.connected && (!data.stale || data.controller.active)) delay = 50;
    document
      .getElementById("badge")
      .classList.toggle(
        "on",
        data.connected && (!data.stale || data.controller.active),
      );
    field(
      "connection",
      !data.connected
        ? "Waiting for phone"
        : data.stale
          ? data.controller.active
            ? "Receiving controller input"
            : "Connected · waiting for samples"
          : "Receiving sensor data",
    );
    const mode = data.last?.connectionMode;
    field(
      "active-mode",
      !data.connected
        ? "No active stream"
        : data.stale
          ? data.controller.active
            ? data.controller.connectionMode === "wireless"
              ? "Controller via wireless debugging"
              : "Controller via USB cable"
            : "Waiting for fresh sensor samples"
          : mode === "wireless"
            ? "Streaming via wireless debugging"
            : mode === "usb"
              ? "Streaming via USB cable"
              : "Streaming via ADB tunnel",
    );
    renderController(data.controller, data.connected);
    renderMotion(data);
    field("count", data.count.toLocaleString());
    renderSample(data);
  } catch {
    document.getElementById("badge").classList.remove("on");
    field("connection", "Server unavailable");
    field("active-mode", "Server unavailable");
    field("age", displayedSample ? "Offline · last sample" : "No samples");
    renderController(null, false);
    renderMotion(null);
  } finally {
    window.setTimeout(poll, document.hidden ? 1000 : delay);
  }
}

function renderSample(data) {
  if (!data.last) {
    if (displayedSample) {
      displayedSample = undefined;
      quaternion = [0, 0, 0, 1];
      for (const id of ["heading", "pitch", "roll", "quat", "sensor"])
        field(id, "—");
      draw();
    }
    field("age", "No samples");
    return;
  }
  field("age", `${Math.round(data.age * 1000)} ms ago`);
  const sample = `${data.receivedAt}:${data.count}`;
  if (sample === displayedSample) return;
  displayedSample = sample;
  const changedPose = data.last.q.some(
    (value, index) => value !== quaternion[index],
  );
  quaternion = data.last.q;
  const angles = orientationAngles(quaternion);
  const relative = data.last.sensor.includes("game_rotation_vector");
  field(
    "heading",
    angles.headingDefined
      ? `${angles.heading.toFixed(1)}°${relative ? " relative" : ""}`
      : "Undefined · vertical",
  );
  field("pitch", `${angles.pitch.toFixed(1)}°`);
  field("roll", `${angles.roll.toFixed(1)}°`);
  field("quat", quaternion.map((v) => v.toFixed(4)).join("  "));
  field(
    "sensor",
    `${data.last.sensor} · accuracy ${data.last.accuracy ?? "unknown"}`,
  );
  if (changedPose) draw();
}
draw();
poll();
