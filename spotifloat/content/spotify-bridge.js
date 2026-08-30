(() => {
  const SELECTORS = window.__SPOTIFLOAT_SELECTORS__;

  let port = null;
  let reconnectTimer = null;
  let sendTimer = null;

  function scheduleReconnectPort() {
    if (reconnectTimer) return;
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      connectPort();
      pushState();
    }, 2000);
  }

  function connectPort() {
    try {
      port = chrome.runtime.connect({ name: "spotify-bridge" });
    } catch (err) {
      console.warn("[SpotiFloat] connectPort failed:", err);
      scheduleReconnectPort();
      return;
    }

    port.onMessage.addListener((message) => {
      if (message?.type === "spotify-command") {
        handleCommand(message.command, message.payload);
      }
    });

    port.onDisconnect.addListener(() => {
      port = null;
      scheduleReconnectPort();
    });
  }

  function text(selector) {
    const el = document.querySelector(selector);
    return el ? el.textContent.trim() : "";
  }

  function progressInput() {
    return document.querySelector(SELECTORS.progressBar)?.querySelector('input[type="range"]') ?? null;
  }

  function volumeInput() {
    return document.querySelector(SELECTORS.volumeBar)?.querySelector('input[type="range"]') ?? null;
  }

  function parseTimeString(str) {
    const parts = (str || "").trim().split(":").map(Number);
    if (parts.length === 0 || parts.some(Number.isNaN)) return null;
    return parts.reduce((acc, val) => acc * 60 + val, 0);
  }

  function currentTimeAndDuration() {
    const currentTime = parseTimeString(text(SELECTORS.positionText));
    const duration = parseTimeString(text(SELECTORS.durationText));
    if (currentTime != null && duration != null) {
      return { currentTime, duration };
    }
    const input = progressInput();
    if (input && input.max) {
      return { currentTime: Number(input.value) / 1000, duration: Number(input.max) / 1000 };
    }
    return { currentTime: 0, duration: 0 };
  }

  function currentVolume() {
    const input = volumeInput();
    return input ? Number(input.value) : 1;
  }

  function isCurrentlyPlaying() {
    return document.querySelector(SELECTORS.playPauseButton)?.getAttribute("aria-label") === "Pause";
  }

  function albumArtUrl() {
    const img = document.querySelector(SELECTORS.albumArt);
    return img?.currentSrc || img?.src || "";
  }

  function shuffleActive() {
    return document.querySelector(SELECTORS.shuffleButton)?.getAttribute("aria-checked") === "true";
  }

  function repeatMode() {
    const btn = document.querySelector(SELECTORS.repeatButton);
    if (!btn) return "off";
    if (btn.getAttribute("data-testid") === "control-button-repeat-track") return "one";
    return btn.getAttribute("aria-checked") === "true" ? "all" : "off";
  }

  function scrapeQueue() {
    const rows = document.querySelectorAll(SELECTORS.queueUpcomingRows);
    const queue = [];
    rows.forEach((row, index) => {
      const img = row.querySelector(SELECTORS.queueRowImage);
      const artists = Array.from(row.querySelectorAll(SELECTORS.queueRowArtistLinks)).map((a) => a.textContent.trim());
      queue.push({
        index,
        title: row.querySelector(SELECTORS.queueRowTitle)?.textContent.trim() ?? "",
        artist: artists.join(", "),
        thumbnailUrl: img?.currentSrc || img?.src || "",
      });
    });
    return queue;
  }

  function buildState() {
    const { currentTime, duration } = currentTimeAndDuration();
    return {
      title: text(SELECTORS.title),
      artist: text(SELECTORS.artist),
      thumbnailUrl: albumArtUrl(),
      currentTime,
      duration,
      isPlaying: isCurrentlyPlaying(),
      volume: currentVolume(),
      shuffleActive: shuffleActive(),
      repeatMode: repeatMode(),
      queue: scrapeQueue(),
    };
  }

  function pushState() {
    if (!port) {
      scheduleReconnectPort();
      return;
    }
    try {
      port.postMessage({ type: "spotify-state", state: buildState() });
    } catch (err) {
      console.warn("[SpotiFloat] pushState failed:", err);
      port = null;
      scheduleReconnectPort();
    }
  }

  function scheduleStatePush() {
    if (sendTimer) return;
    sendTimer = setTimeout(() => {
      sendTimer = null;
      pushState();
    }, 200);
  }

  function setReactSliderValue(input, value) {
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
    setter.call(input, String(value));
    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.dispatchEvent(new Event("change", { bubbles: true }));
  }

  const FX_COMMAND_TYPES = {
    "eq-band": "eq-band",
    "eq-preset": "eq-preset",
    "reverb-wet": "reverb-wet",
    "stereo-width": "stereo-width",
    "fx-reset": "fx-reset",
    "fx-sync": "sync",
  };

  function handleCommand(command, payload) {
    if (FX_COMMAND_TYPES[command]) {
      window.postMessage({ source: "spotifloat-fx-command", payload: { type: FX_COMMAND_TYPES[command], ...payload } }, "*");
      return;
    }

    switch (command) {
      case "toggle-play":
        document.querySelector(SELECTORS.playPauseButton)?.click();
        break;
      case "next":
        document.querySelector(SELECTORS.nextButton)?.click();
        break;
      case "previous":
        document.querySelector(SELECTORS.previousButton)?.click();
        break;
      case "seek": {
        if (typeof payload?.time !== "number") break;
        const input = progressInput();
        if (input) {
          const ms = Math.min(Number(input.max), Math.max(Number(input.min), Math.round(payload.time * 1000)));
          setReactSliderValue(input, ms);
        }
        break;
      }
      case "volume": {
        if (typeof payload?.value !== "number") break;
        const input = volumeInput();
        if (input) {
          const value = Math.min(1, Math.max(0, payload.value));
          setReactSliderValue(input, value);
        }
        break;
      }
      case "toggle-shuffle":
        document.querySelector(SELECTORS.shuffleButton)?.click();
        break;
      case "toggle-repeat":
        document.querySelector(SELECTORS.repeatButton)?.click();
        break;
      case "queue-jump": {
        const rows = document.querySelectorAll(SELECTORS.queueUpcomingRows);
        rows[payload?.index]?.querySelector(SELECTORS.queueRowPlayButton)?.click();
        break;
      }
    }

    scheduleStatePush();
  }

  const observer = new MutationObserver(scheduleStatePush);
  observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true });

  setInterval(pushState, 1000);

  connectPort();
  pushState();
})();
