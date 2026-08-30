const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("ytm", {
  onState(callback) {
    ipcRenderer.on("ytm-state", (_event, state) => callback(state));
  },
  sendCommand(command, payload) {
    ipcRenderer.send("ytm-command", { command, payload });
  },
  hide() {
    ipcRenderer.send("hide-window");
  },
  resizeForQueue(isOpen) {
    ipcRenderer.send("resize-for-queue", isOpen);
  },
  setCompact(isCompact) {
    ipcRenderer.send("set-compact", isCompact);
  },
  openFX() {
    ipcRenderer.send("toggle-fx-window");
  },
  onFXState(callback) {
    ipcRenderer.on("fx-state", (_event, state) => callback(state));
  },
});
