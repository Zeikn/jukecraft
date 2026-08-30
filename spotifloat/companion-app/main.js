const { app, BrowserWindow, Tray, Menu, ipcMain, screen } = require("electron");
const path = require("node:path");
const fs = require("node:fs");
const { WebSocketServer } = require("ws");

const WS_PORT = 38215;
const POSITION_FILE = path.join(app.getPath("userData"), "window-position.json");
const FX_POSITION_FILE = path.join(app.getPath("userData"), "fx-window-position.json");
const FX_STATE_FILE = path.join(app.getPath("userData"), "fx-state.json");
const WINDOW_WIDTH = 336;
const WINDOW_HEIGHT = 186;
const QUEUE_EXPAND_HEIGHT = 180;
const COMPACT_WIDTH = 180;
const COMPACT_HEIGHT = 150;
const FX_WINDOW_WIDTH = 260;
const FX_WINDOW_HEIGHT = 360;

const DEFAULT_FX_STATE = { eq: [0, 0, 0, 0, 0, 0, 0], reverbWet: 0, width: 1 };
const EQ_PRESETS = {
  flat: [0, 0, 0, 0, 0, 0, 0],
  bassBoost: [6, 5, 3, 0, 0, 0, 0],
  trebleBoost: [0, 0, 0, 0, 2, 4, 6],
  vocal: [-2, -1, 2, 4, 3, 1, 0],
  lofi: [3, 2, 0, -2, -4, -6, -8],
};

let mainWindow = null;
let fxWindow = null;
let tray = null;
let extensionSocket = null;
let isQuitting = false;

// External "display" clients (e.g. the Jukeraft Minecraft mod) connect to the
// same port at the /display path. They get a read replica of state/fx-state
// and can send the same commands a real widget button click would send, but
// they never take over the `extensionSocket` slot the browser extension uses.
const displaySockets = new Set();
let lastState = null;

function loadFXState() {
  try {
    return { ...DEFAULT_FX_STATE, ...JSON.parse(fs.readFileSync(FX_STATE_FILE, "utf-8")) };
  } catch {
    return { ...DEFAULT_FX_STATE };
  }
}

let fxState = loadFXState();
let fxSaveTimer = null;

function saveFXState() {
  if (fxSaveTimer) return;
  fxSaveTimer = setTimeout(() => {
    fxSaveTimer = null;
    try {
      fs.writeFileSync(FX_STATE_FILE, JSON.stringify(fxState));
    } catch {}
  }, 300);
}

function sendFXSync() {
  const message = JSON.stringify({ type: "command", command: "fx-sync", payload: fxState });
  if (extensionSocket && extensionSocket.readyState === extensionSocket.OPEN) {
    extensionSocket.send(message);
  }
  for (const displaySocket of displaySockets) {
    if (displaySocket.readyState === displaySocket.OPEN) displaySocket.send(message);
  }
}

function loadSavedPosition(file) {
  try {
    return JSON.parse(fs.readFileSync(file, "utf-8"));
  } catch {
    return null;
  }
}

function savePosition(file, bounds) {
  try {
    fs.writeFileSync(file, JSON.stringify({ x: bounds.x, y: bounds.y }));
  } catch {}
}

function createWindow() {
  const saved = loadSavedPosition(POSITION_FILE);
  const primary = screen.getPrimaryDisplay().workArea;
  const x = saved?.x ?? primary.x + primary.width - WINDOW_WIDTH - 24;
  const y = saved?.y ?? primary.y + primary.height - WINDOW_HEIGHT - 24;

  mainWindow = new BrowserWindow({
    width: WINDOW_WIDTH,
    height: WINDOW_HEIGHT,
    x,
    y,
    frame: false,
    transparent: true,
    alwaysOnTop: true,
    resizable: false,
    skipTaskbar: true,
    hasShadow: false,
    thickFrame: false,
    roundedCorners: false,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  mainWindow.setAlwaysOnTop(true, "screen-saver");
  mainWindow.loadFile(path.join(__dirname, "renderer", "index.html"));

  mainWindow.once("ready-to-show", () => mainWindow.show());

  let moveTimer = null;
  mainWindow.on("moved", () => {
    clearTimeout(moveTimer);
    moveTimer = setTimeout(() => savePosition(POSITION_FILE, mainWindow.getBounds()), 300);
  });

  mainWindow.on("close", (event) => {
    if (!isQuitting) {
      event.preventDefault();
      mainWindow.hide();
    }
  });
}

function createFXWindow() {
  const saved = loadSavedPosition(FX_POSITION_FILE);
  const mainBounds = mainWindow?.getBounds();
  const x = saved?.x ?? (mainBounds ? mainBounds.x - FX_WINDOW_WIDTH - 12 : 100);
  const y = saved?.y ?? (mainBounds ? mainBounds.y : 100);

  fxWindow = new BrowserWindow({
    width: FX_WINDOW_WIDTH,
    height: FX_WINDOW_HEIGHT,
    x,
    y,
    frame: false,
    transparent: true,
    alwaysOnTop: true,
    resizable: false,
    skipTaskbar: true,
    hasShadow: false,
    thickFrame: false,
    roundedCorners: false,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  fxWindow.setAlwaysOnTop(true, "screen-saver");
  fxWindow.loadFile(path.join(__dirname, "renderer", "fx.html"));

  fxWindow.webContents.once("did-finish-load", () => {
    fxWindow?.webContents.send("fx-state", fxState);
  });

  let moveTimer = null;
  fxWindow.on("moved", () => {
    clearTimeout(moveTimer);
    moveTimer = setTimeout(() => savePosition(FX_POSITION_FILE, fxWindow.getBounds()), 300);
  });

  fxWindow.on("close", (event) => {
    if (!isQuitting) {
      event.preventDefault();
      fxWindow.hide();
    }
  });
}

function toggleFXWindow() {
  if (!fxWindow) {
    createFXWindow();
    fxWindow.once("ready-to-show", () => fxWindow.show());
    return;
  }
  if (fxWindow.isVisible()) {
    fxWindow.hide();
  } else {
    fxWindow.webContents.send("fx-state", fxState);
    fxWindow.show();
  }
}

function toggleVisibility() {
  if (!mainWindow) return;
  if (mainWindow.isVisible()) {
    mainWindow.hide();
  } else {
    mainWindow.show();
  }
}

function createTray() {
  const iconPath = path.join(__dirname, "icons", "tray.png");
  tray = new Tray(iconPath);
  tray.setToolTip("SpotiFloat");
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: "Show/Hide", click: toggleVisibility },
      { type: "separator" },
      {
        label: "Quit",
        click: () => {
          isQuitting = true;
          app.quit();
        },
      },
    ])
  );
  tray.on("click", toggleVisibility);
}

function broadcastState(state) {
  lastState = state;
  mainWindow?.webContents.send("spotify-state", state);

  const message = JSON.stringify({ type: "state", state });
  for (const displaySocket of displaySockets) {
    if (displaySocket.readyState === displaySocket.OPEN) displaySocket.send(message);
  }
}

const FX_COMMANDS = new Set(["eq-band", "eq-preset", "reverb-wet", "stereo-width", "fx-reset"]);

// Shared by the Electron FX window (via ipcMain below) and external display
// clients (via the /display WebSocket path) so both drive the exact same
// state and forward to the real browser extension identically.
function handleCommand(command, payload) {
  if (FX_COMMANDS.has(command)) {
    if (command === "eq-band") fxState.eq[payload.index] = payload.gainDb;
    else if (command === "eq-preset" && EQ_PRESETS[payload.name]) fxState.eq = [...EQ_PRESETS[payload.name]];
    else if (command === "reverb-wet") fxState.reverbWet = payload.value;
    else if (command === "stereo-width") fxState.width = payload.value;
    else if (command === "fx-reset") fxState = { ...DEFAULT_FX_STATE };
    saveFXState();
  }

  if (command === "toggle-visibility") {
    toggleVisibility();
    return;
  }

  if (extensionSocket && extensionSocket.readyState === extensionSocket.OPEN) {
    extensionSocket.send(JSON.stringify({ type: "command", command, payload }));
  }
}

function startWebSocketServer() {
  const wss = new WebSocketServer({ host: "127.0.0.1", port: WS_PORT });
  console.log("[SpotiFloat] WS server listening on", WS_PORT);

  wss.on("connection", (ws, req) => {
    const isDisplay = (req.url || "").startsWith("/display");

    if (isDisplay) {
      console.log("[SpotiFloat] display client connected");
      displaySockets.add(ws);
      ws.send(JSON.stringify({ type: "state", state: lastState }));
      ws.send(JSON.stringify({ type: "command", command: "fx-sync", payload: fxState }));

      ws.on("message", (data) => {
        let message;
        try {
          message = JSON.parse(data.toString());
        } catch {
          return;
        }
        if (message?.type === "command") {
          handleCommand(message.command, message.payload);
        }
      });

      ws.on("close", () => {
        console.log("[SpotiFloat] display client disconnected");
        displaySockets.delete(ws);
      });
      return;
    }

    console.log("[SpotiFloat] extension connected");
    extensionSocket = ws;
    sendFXSync();

    ws.on("message", (data) => {
      let message;
      try {
        message = JSON.parse(data.toString());
      } catch {
        return;
      }

      if (message?.type === "state") {
        broadcastState(message.state);
      } else if (message?.type === "command" && message.command === "toggle-visibility") {
        toggleVisibility();
      }
    });

    ws.on("close", () => {
      console.log("[SpotiFloat] extension disconnected");
      if (extensionSocket === ws) extensionSocket = null;
      broadcastState(null);
    });
  });
}

ipcMain.on("spotify-command", (_event, { command, payload }) => {
  handleCommand(command, payload);
});

ipcMain.on("hide-window", (event) => {
  BrowserWindow.fromWebContents(event.sender)?.hide();
});

ipcMain.on("toggle-fx-window", toggleFXWindow);

let queueExpandYDelta = 0;
let resizeAnimationTimer = null;

function easeOutQuad(t) {
  return 1 - (1 - t) * (1 - t);
}

function animateBounds(from, to, durationMs = 220) {
  if (resizeAnimationTimer) clearInterval(resizeAnimationTimer);

  const start = Date.now();
  resizeAnimationTimer = setInterval(() => {
    const t = Math.min(1, (Date.now() - start) / durationMs);
    const eased = easeOutQuad(t);

    mainWindow?.setBounds({
      x: Math.round(from.x + (to.x - from.x) * eased),
      y: Math.round(from.y + (to.y - from.y) * eased),
      width: Math.round(from.width + (to.width - from.width) * eased),
      height: Math.round(from.height + (to.height - from.height) * eased),
    });

    if (t >= 1) {
      clearInterval(resizeAnimationTimer);
      resizeAnimationTimer = null;
    }
  }, 10);
}

ipcMain.on("resize-for-queue", (_event, isOpen) => {
  if (!mainWindow) return;
  const bounds = mainWindow.getBounds();

  if (isOpen) {
    const workArea = screen.getDisplayMatching(bounds).workArea;
    const newHeight = bounds.height + QUEUE_EXPAND_HEIGHT;
    const spaceBelow = workArea.y + workArea.height - bounds.y;

    const desiredY = spaceBelow >= newHeight ? bounds.y : Math.max(workArea.y, bounds.y - QUEUE_EXPAND_HEIGHT);
    queueExpandYDelta = desiredY - bounds.y;

    animateBounds(bounds, { x: bounds.x, y: desiredY, width: bounds.width, height: newHeight });
  } else {
    const target = {
      x: bounds.x,
      y: bounds.y - queueExpandYDelta,
      width: bounds.width,
      height: bounds.height - QUEUE_EXPAND_HEIGHT,
    };
    queueExpandYDelta = 0;
    animateBounds(bounds, target);
  }
});

let baseBounds = null;

ipcMain.on("set-compact", (_event, isCompact) => {
  if (!mainWindow) return;
  const bounds = mainWindow.getBounds();

  if (isCompact) {
    baseBounds = bounds;
    animateBounds(bounds, { x: bounds.x, y: bounds.y, width: COMPACT_WIDTH, height: COMPACT_HEIGHT });
  } else {
    const target = baseBounds ?? { x: bounds.x, y: bounds.y, width: WINDOW_WIDTH, height: WINDOW_HEIGHT };
    baseBounds = null;
    animateBounds(bounds, target);
  }
});

app.whenReady().then(() => {
  createWindow();
  createTray();
  startWebSocketServer();
});

app.on("window-all-closed", (event) => {
  event.preventDefault();
});
