// Quick one-shot test: connects to the mod's embedded Spotify bridge (like the
// real extension would) and pushes a fake state including the fields unique
// to Spotify (shuffleActive, queue items without isCurrent) to verify parsing.
const WebSocket = require("ws");

const ws = new WebSocket("ws://127.0.0.1:38215");

ws.on("open", () => {
  console.log("[test] connected, pushing fake Spotify state");
  ws.send(JSON.stringify({
    type: "state",
    state: {
      title: "Test Spotify Track",
      artist: "Test Artist",
      thumbnailUrl: "https://picsum.photos/id/1035/200/200",
      currentTime: 42,
      duration: 210,
      isPlaying: true,
      volume: 0.7,
      shuffleActive: true,
      repeatMode: "all",
      queue: [
        { index: 0, title: "Next Track", artist: "Someone", thumbnailUrl: "https://picsum.photos/id/1025/100/100" },
        { index: 1, title: "Another Track", artist: "Someone Else", thumbnailUrl: "https://picsum.photos/id/1043/100/100" },
      ],
    },
  }));
});

ws.on("message", (data) => {
  console.log("[test] received:", data.toString());
});

ws.on("error", (err) => {
  console.error("[test] error:", err.message);
});

setTimeout(() => {
  ws.close();
  process.exit(0);
}, 3000);
