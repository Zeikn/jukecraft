// Standalone test harness for the /display bridge channel added to main.js.
// Doesn't require Electron -- just verifies the WS routing/state/command
// protocol the Minecraft mod (YtmBridgeClient) actually speaks to.
const { WebSocketServer } = require("ws");

const WS_PORT = 38214;
const displaySockets = new Set();

let fakeState = {
  title: "Test Track Title",
  artist: "Test Artist",
  thumbnailUrl: "https://picsum.photos/id/237/200/200",
  isPlaying: true,
  currentTime: 12,
  duration: 215,
  volume: 0.8,
  repeatMode: "off",
  queue: [
    { index: 0, title: "Current Song", artist: "Artist A", thumbnailUrl: "https://picsum.photos/id/1025/100/100", isCurrent: true },
    { index: 1, title: "Next Song", artist: "Artist B", thumbnailUrl: "https://picsum.photos/id/1035/100/100", isCurrent: false },
    { index: 2, title: "Another One", artist: "Artist C", thumbnailUrl: "https://picsum.photos/id/1043/100/100", isCurrent: false },
  ],
};

const fxState = { eq: [0, 0, 0, 0, 0, 0, 0], reverbWet: 0, width: 1 };

function broadcastState() {
  const message = JSON.stringify({ type: "state", state: fakeState });
  for (const ws of displaySockets) ws.send(message);
}

const wss = new WebSocketServer({ host: "127.0.0.1", port: WS_PORT });
console.log("[test-bridge] listening on", WS_PORT);

wss.on("connection", (ws, req) => {
  console.log("[test-bridge] connection on", req.url);
  if (!(req.url || "").startsWith("/display")) return;

  displaySockets.add(ws);
  ws.send(JSON.stringify({ type: "state", state: fakeState }));
  ws.send(JSON.stringify({ type: "command", command: "fx-sync", payload: fxState }));

  ws.on("message", (data) => {
    let message;
    try {
      message = JSON.parse(data.toString());
    } catch {
      return;
    }
    console.log("[test-bridge] received:", JSON.stringify(message));

    if (message.type !== "command") return;
    const { command, payload } = message;
    if (command === "toggle-play") fakeState.isPlaying = !fakeState.isPlaying;
    else if (command === "next") fakeState.title = "Skipped To Next";
    else if (command === "previous") fakeState.title = "Skipped To Previous";
    else if (command === "seek") fakeState.currentTime = payload.time;
    else if (command === "volume") fakeState.volume = payload.value;
    else if (command === "toggle-shuffle") console.log("[test-bridge] shuffle toggled");
    else if (command === "toggle-repeat") {
      fakeState.repeatMode = fakeState.repeatMode === "off" ? "all" : fakeState.repeatMode === "all" ? "one" : "off";
    } else if (command === "queue-jump") fakeState.title = "Jumped to queue #" + payload.index;
    else if (command === "eq-band") fxState.eq[payload.index] = payload.gainDb;
    else if (command === "eq-preset") console.log("[test-bridge] preset:", payload.name);
    else if (command === "reverb-wet") fxState.reverbWet = payload.value;
    else if (command === "stereo-width") fxState.width = payload.value;
    else if (command === "fx-reset") {
      fxState.eq = [0, 0, 0, 0, 0, 0, 0];
      fxState.reverbWet = 0;
      fxState.width = 1;
    }

    broadcastState();
  });

  ws.on("close", () => displaySockets.delete(ws));
});

setInterval(() => {
  if (fakeState.isPlaying) {
    fakeState.currentTime = Math.min(fakeState.duration, fakeState.currentTime + 1);
    broadcastState();
  }
}, 1000);
