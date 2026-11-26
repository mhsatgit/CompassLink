const form = document.getElementById("input-capture-form");
const code = document.getElementById("input-capture-code");
const target = document.getElementById("input-capture-target");
const status = document.getElementById("input-capture-status");
let active = false;
let timeout;
const keyAliases = {
  ArrowUp: "UP",
  ArrowDown: "DOWN",
  ArrowLeft: "LEFT",
  ArrowRight: "RIGHT",
  BracketLeft: "LEFTBRACKET",
  BracketRight: "RIGHTBRACKET",
  Equal: "EQUALS",
};

function stopCapture(message) {
  window.clearTimeout(timeout);
  active = false;
  target.hidden = true;
  status.textContent = message;
}

form.addEventListener("submit", (event) => {
  event.preventDefault();
  window.clearTimeout(timeout);
  active = true;
  target.hidden = false;
  target.focus();
  status.textContent = "Waiting for one key or mouse button";
  timeout = window.setTimeout(() => stopCapture("Capture expired"), 30_000);
});

async function send(binding) {
  if (!active) return;
  stopCapture("Sending the binding…");
  try {
    const response = await fetch("/input-capture", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code: code.value.toUpperCase(), binding }),
      signal: AbortSignal.timeout(3000),
    });
    if (response.ok) {
      code.value = "";
      stopCapture("Binding sent to the phone");
    } else {
      stopCapture(
        response.status === 400
          ? "Unsupported binding. Start capture to try again."
          : "Capture is unavailable. Check the code on the phone and try again.",
      );
    }
  } catch {
    stopCapture("The desktop receiver is unavailable");
  }
}

target.addEventListener("keydown", (event) => {
  if (!active || event.repeat) return;
  event.preventDefault();
  if (/^(Shift|Control|Alt)(Left|Right)$/.test(event.code)) return;
  const key =
    keyAliases[event.code] ||
    event.code
      .replace(/^(?:Key|Digit)/, "")
      .replace(/(?:Left|Right)$/, "")
      .replace(/^Control$/, "Ctrl")
      .toUpperCase();
  const modifiers = [
    event.shiftKey && "SHIFT",
    event.ctrlKey && "CTRL",
    event.altKey && "ALT",
  ].filter((modifier) => modifier && modifier !== key);
  void send({ type: "KEYBOARD", key, modifiers });
});
target.addEventListener("keyup", (event) => {
  if (!active || !/^(Shift|Control|Alt)(Left|Right)$/.test(event.code)) return;
  event.preventDefault();
  const key = event.code
    .replace(/(?:Left|Right)$/, "")
    .replace(/^Control$/, "Ctrl")
    .toUpperCase();
  void send({ type: "KEYBOARD", key, modifiers: [] });
});
target.addEventListener("pointerdown", (event) => {
  const button = ["LEFT", "MIDDLE", "RIGHT"][event.button];
  if (!active || !button) return;
  event.preventDefault();
  void send({ type: "MOUSE_BUTTON", button });
});
target.addEventListener("contextmenu", (event) => event.preventDefault());
