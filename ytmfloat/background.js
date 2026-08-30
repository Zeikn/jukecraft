const WS_URL = "ws://127.0.0.1:38214";
const HEARTBEAT_ALARM = "ytm-ws-heartbeat";

let lastState = null;
let socket = null;
let reconnectTimer = null;

let bridgePort = null;

function connectWS() {
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
    return;
  }

  try {
    socket = new WebSocket(WS_URL);
  } catch {
    scheduleReconnect();
    return;
  }

  socket.addEventListener("open", () => {
    if (lastState) {
      socket.send(JSON.stringify({ type: "state", state: lastState }));
    }
  });

  socket.addEventListener("message", (event) => {
    let message;
    try {
      message = JSON.parse(event.data);
    } catch {
      return;
    }
    if (message?.type !== "command") return;

    if (bridgePort) {
      try {
        bridgePort.postMessage({ type: "ytm-command", command: message.command, payload: message.payload });
      } catch {
        bridgePort = null;
      }
    }
  });

  socket.addEventListener("close", () => {
    socket = null;
    scheduleReconnect();
  });

  socket.addEventListener("error", () => {
    socket?.close();
  });
}

function scheduleReconnect() {
  if (reconnectTimer) return;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connectWS();
  }, 3000);
}

function sendState(state) {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify({ type: "state", state }));
  }
}

chrome.runtime.onConnect.addListener((port) => {
  if (port.name !== "ytm-bridge") return;

  port.onMessage.addListener((message) => {
    if (message?.type === "ytm-state") {
      bridgePort = port;
      lastState = message.state;
      sendState(lastState);
    }
  });

  port.onDisconnect.addListener(() => {
    if (bridgePort === port) {
      bridgePort = null;
      lastState = null;
      sendState(null);
    }
  });
});

chrome.action.onClicked.addListener(() => {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify({ type: "command", command: "toggle-visibility" }));
  } else {
    connectWS();
  }
});

chrome.alarms.create(HEARTBEAT_ALARM, { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === HEARTBEAT_ALARM) {
    connectWS();
  }
});

connectWS();
