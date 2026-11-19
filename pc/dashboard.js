import { multiply, rotate, toView, orientationAngles } from "./orientation.js";

const canvas = document.querySelector("#view");
const context = canvas.getContext("2d");
const field = (id, value) => {
  document.getElementById(id).textContent = value;
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
    document.getElementById("guide-wireless").hidden = button.dataset.mode !== "wireless";
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
  { indices: [0, 1, 5, 4], color: "#32496d" },
  { indices: [1, 2, 6, 5], color: "#263b5c" },
  { indices: [2, 3, 7, 6], color: "#395377" },
  { indices: [3, 0, 4, 7], color: "#253958" },
  { indices: [0, 3, 2, 1], color: "#172842" },
  { indices: [4, 5, 6, 7], color: "#42638c" },
];
const normalize = (v) => v.map((n) => n / Math.hypot(...v));
const dot = (a, b) => a.reduce((sum, n, i) => sum + n * b[i], 0);
const cross = (a, b) => [
  a[1] * b[2] - a[2] * b[1],
  a[2] * b[0] - a[0] * b[2],
  a[0] * b[1] - a[1] * b[0],
];

function draw() {
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
  context.strokeStyle = "#253653";
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
    context.strokeStyle = "#78a0d2";
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
      "#071323",
    );
    const [x, y] = project(transform([0, 0.56, 0.08]));
    context.fillStyle = "#52d9e9";
    context.beginPath();
    context.arc(x, y, 4, 0, Math.PI * 2);
    context.fill();
  }
  context.fillStyle = "#8ea2c2";
  context.font = "12px system-ui";
  context.fillText(
    "World: East / Up / −North · cyan dot marks top",
    18,
    height - 18,
  );
}

canvas.style.touchAction = "none";
canvas.addEventListener("pointerdown", (event) => {
  pointer = [event.clientX, event.clientY];
  canvas.setPointerCapture(event.pointerId);
});
canvas.addEventListener("pointermove", (event) => {
  if (!pointer) return;
  orbit[0] += (event.clientX - pointer[0]) * 0.008;
  orbit[1] = Math.max(
    -1.4,
    Math.min(1.4, orbit[1] + (event.clientY - pointer[1]) * 0.008),
  );
  pointer = [event.clientX, event.clientY];
  draw();
});
for (const name of ["pointerup", "pointercancel", "lostpointercapture"])
  canvas.addEventListener(name, () => {
    pointer = null;
  });
canvas.addEventListener("dblclick", () => {
  orbit = [0, 0];
  draw();
});
window.addEventListener("resize", draw);

async function poll() {
  try {
    const response = await fetch("/api/state", {
      cache: "no-store",
      signal: AbortSignal.timeout(2500),
    });
    if (!response.ok) throw new Error("Dashboard server unavailable");
    const data = await response.json();
    document
      .getElementById("badge")
      .classList.toggle("on", data.connected && !data.stale);
    field(
      "connection",
      !data.connected
        ? "Waiting for phone"
        : data.stale
          ? "Connected · waiting for samples"
          : "Receiving sensor data",
    );
    const mode = data.last?.connectionMode;
    field(
      "active-mode",
      !data.connected
        ? "No active stream"
        : data.stale
          ? "Waiting for fresh sensor samples"
          : mode === "wireless"
            ? "Streaming via wireless debugging"
            : mode === "usb"
              ? "Streaming via USB cable"
              : "Streaming via ADB tunnel",
    );
    field("count", data.count.toLocaleString());
    if (data.last) {
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
      field("age", `${Math.round(data.age * 1000)} ms ago`);
      draw();
    }
  } catch {
    document.getElementById("badge").classList.remove("on");
    field("connection", "Server unavailable");
    field("active-mode", "Server unavailable");
  } finally {
    window.setTimeout(poll, 50);
  }
}
draw();
poll();

