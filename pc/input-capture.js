const { timingSafeEqual } = require("node:crypto");
const { parseBinding } = require("./controller-profile");

const OFFER_WINDOW_MS = 45_000;
const MAX_BODY_BYTES = 512;
const validCode = (code) =>
  typeof code === "string" && /^[0-9A-F]{8}$/.test(code);

function createInputCapture(readAuthorization) {
  let offer;

  function authorized(token, mode) {
    const grant = readAuthorization();
    return (
      grant?.mode === mode &&
      typeof grant.token === "string" &&
      /^[0-9a-f]{64}$/.test(grant.token) &&
      typeof token === "string" &&
      /^[0-9a-f]{64}$/.test(token) &&
      timingSafeEqual(Buffer.from(grant.token), Buffer.from(token))
    );
  }

  function offerCapture(socket, packet) {
    if (
      packet.version !== 1 ||
      !validCode(packet.code) ||
      !["usb", "wireless"].includes(packet.mode) ||
      !authorized(packet.token, packet.mode)
    ) {
      socket.end('{"type":"capture.unavailable"}\n');
      return;
    }
    const previous = offer;
    const current = {
      socket,
      code: packet.code,
      token: packet.token,
      mode: packet.mode,
      expiresAt: Date.now() + OFFER_WINDOW_MS,
      attempts: 0,
    };
    offer = current;
    previous?.socket.end('{"type":"capture.unavailable"}\n');
    socket.once("close", () => {
      if (offer === current) offer = undefined;
    });
    socket.setTimeout(OFFER_WINDOW_MS);
    socket.write('{"type":"capture.ready"}\n');
  }

  async function submitCapture(req, res, host) {
    const bytes = Number(req.headers["content-length"]);
    if (
      req.headers.origin !== `http://${host}` ||
      req.headers["content-type"] !== "application/json"
    ) {
      res.writeHead(403).end();
      return;
    }
    if (!Number.isInteger(bytes) || bytes < 1 || bytes > MAX_BODY_BYTES) {
      res.writeHead(413).end();
      return;
    }
    let data;
    try {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      data = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    } catch {
      res.writeHead(400).end();
      return;
    }
    const binding = parseBinding(data?.binding, "hold");
    if (!validCode(data?.code) || !binding) {
      res.writeHead(400).end();
      return;
    }
    const current = offer;
    if (
      !current ||
      current.attempts++ >= 12 ||
      current.code !== data.code ||
      current.expiresAt <= Date.now() ||
      current.socket.destroyed ||
      !authorized(current.token, current.mode)
    ) {
      res.writeHead(404).end();
      return;
    }
    offer = undefined;
    current.socket.end(
      JSON.stringify({ type: "capture.binding", binding }) + "\n",
    );
    res.writeHead(204).end();
  }

  return { offerCapture, submitCapture };
}

module.exports = { createInputCapture };
