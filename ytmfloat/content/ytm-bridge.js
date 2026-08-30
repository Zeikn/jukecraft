(() => {
  const SELECTORS = {
    playerBar: "ytmusic-player-bar",
    title: ".title.ytmusic-player-bar",
    byline: ".byline.ytmusic-player-bar",
    thumbnail: ".image.style-scope.ytmusic-player-bar",
    playPauseButton: "#play-pause-button",
    nextButton: ".next-button",
    previousButton: ".previous-button",
    queueItems: "ytmusic-player-queue-item",
    timeInfo: ".time-info.ytmusic-player-bar",
  };

  let video = null;
  let sendTimer = null;
  let port = null;
  let reconnectTimer = null;

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
      port = chrome.runtime.connect({ name: "ytm-bridge" });
    } catch (err) {
      console.warn("[YTM Float] connectPort failed:", err);
      scheduleReconnectPort();
      return;
    }

    port.onMessage.addListener((message) => {
      if (message?.type === "ytm-command") {
        handleCommand(message.command, message.payload);
      }
    });

    port.onDisconnect.addListener(() => {
      port = null;
      scheduleReconnectPort();
    });
  }

  function getPlayerApi() {
    const api = document.getElementById("movie_player");
    return api && typeof api.getPlayerState === "function" ? api : null;
  }

  function findVideo() {
    return document.querySelector("video");
  }

  function text(selector) {
    const el = document.querySelector(selector);
    return el ? el.textContent.trim() : "";
  }

  function normalizeUrl(url) {
    if (url && url.startsWith("//")) return "https:" + url;
    return url;
  }

  function imageUrl(img) {
    if (!img) return "";
    const src = img.currentSrc || img.src || "";
    if (src && !src.startsWith("data:")) return normalizeUrl(src);
    return normalizeUrl(img.getAttribute("data-src") || img.dataset?.thumb || src);
  }

  function thumbnailUrl() {
    return imageUrl(document.querySelector(SELECTORS.thumbnail));
  }

  function isCurrentlyPlaying() {
    const api = getPlayerApi();
    if (api) return api.getPlayerState() === 1;
    if (video) return !video.paused && !video.ended;
    const btn = document.querySelector(SELECTORS.playPauseButton);
    return btn?.getAttribute("aria-label") === "Pause";
  }

  function currentVolume() {
    const api = getPlayerApi();
    if (api) return api.getVolume() / 100;
    return video?.volume ?? 1;
  }

  function findPlayerBarButton(predicate) {
    const bar = document.querySelector(SELECTORS.playerBar);
    return Array.from(bar?.querySelectorAll("button") ?? []).find((b) => predicate(b.getAttribute("aria-label") || ""));
  }

  function shuffleButton() {
    return findPlayerBarButton((label) => label === "Shuffle");
  }

  function repeatButton() {
    return findPlayerBarButton((label) => label.startsWith("Repeat"));
  }

  function repeatMode() {
    const label = repeatButton()?.getAttribute("aria-label") || "";
    if (label.endsWith("one")) return "one";
    if (label.endsWith("all")) return "all";
    return "off";
  }

  function readQueueItem(item) {
    if (item.dataset.ytmfloatTitle) {
      return {
        title: item.dataset.ytmfloatTitle,
        artist: item.dataset.ytmfloatArtist || "",
        thumbnailUrl: item.dataset.ytmfloatThumb || "",
        isCurrent: item.dataset.ytmfloatSelected === "1",
        videoId: item.dataset.ytmfloatVideoId || null,
      };
    }

    return {
      title: item.querySelector(".song-title")?.textContent.trim() ?? "",
      artist: item.querySelector(".byline")?.textContent.trim() ?? "",
      thumbnailUrl: imageUrl(item.querySelector("img")),
      isCurrent: item.hasAttribute("selected") || item.classList.contains("selected"),
      videoId: null,
    };
  }

  function scrapeQueue() {
    const items = document.querySelectorAll(SELECTORS.queueItems);
    const seen = new Set();
    const queue = [];

    items.forEach((item, index) => {
      const { videoId, ...info } = readQueueItem(item);
      const key = videoId ?? `${info.title}|||${info.artist}`;
      if (seen.has(key)) return;
      seen.add(key);
      queue.push({ index, ...info });
    });

    return queue;
  }

  function parseTimeString(str) {
    const parts = (str || "").trim().split(":").map(Number);
    if (parts.length === 0 || parts.some(Number.isNaN)) return null;
    return parts.reduce((acc, val) => acc * 60 + val, 0);
  }

  function scrapedTimes() {
    const text = document.querySelector(SELECTORS.timeInfo)?.textContent ?? "";
    const [currentStr, durationStr] = text.split("/");
    const currentTime = parseTimeString(currentStr);
    const duration = parseTimeString(durationStr);
    return currentTime == null || duration == null ? null : { currentTime, duration };
  }

  function currentTimeAndDuration() {
    const scraped = scrapedTimes();
    if (scraped) return scraped;

    const api = getPlayerApi();
    if (api) return { currentTime: api.getCurrentTime() ?? 0, duration: api.getDuration() || 0 };
    return { currentTime: video?.currentTime ?? 0, duration: video?.duration || 0 };
  }

  function buildState() {
    const { currentTime, duration } = currentTimeAndDuration();
    return {
      title: text(SELECTORS.title),
      artist: text(SELECTORS.byline),
      thumbnailUrl: thumbnailUrl(),
      currentTime,
      duration,
      isPlaying: isCurrentlyPlaying(),
      volume: currentVolume(),
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
      port.postMessage({ type: "ytm-state", state: buildState() });
    } catch (err) {
      console.warn("[YTM Float] pushState failed:", err);
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

  let desiredVolumePct = null;

  function reapplyDesiredVolume() {
    if (desiredVolumePct == null) return;
    const api = getPlayerApi();
    if (api) api.setVolume(desiredVolumePct);
    else if (video) video.volume = desiredVolumePct / 100;
  }

  function attachVideoListeners() {
    const current = findVideo();
    if (!current || current === video) return;

    video = current;
    video.addEventListener("timeupdate", scheduleStatePush);
    video.addEventListener("play", pushState);
    video.addEventListener("pause", pushState);
    video.addEventListener("volumechange", pushState);
    video.addEventListener("loadedmetadata", () => {
      reapplyDesiredVolume();
      pushState();
    });
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
      window.postMessage({ source: "ytmfloat-fx-command", payload: { type: FX_COMMAND_TYPES[command], ...payload } }, "*");
      return;
    }

    attachVideoListeners();
    const api = getPlayerApi();

    switch (command) {
      case "toggle-play":
        if (api) {
          api.getPlayerState() === 1 ? api.pauseVideo() : api.playVideo();
        } else if (video) {
          video.paused ? video.play() : video.pause();
        } else {
          document.querySelector(SELECTORS.playPauseButton)?.click();
        }
        break;
      case "next":
        if (api) api.nextVideo();
        else document.querySelector(SELECTORS.nextButton)?.click();
        break;
      case "previous":
        if (api) api.previousVideo();
        else document.querySelector(SELECTORS.previousButton)?.click();
        break;
      case "seek":
        if (typeof payload?.time === "number") {
          if (api) api.seekTo(payload.time, true);
          else if (video) video.currentTime = payload.time;
        }
        break;
      case "volume":
        if (typeof payload?.value === "number") {
          const pct = Math.min(100, Math.max(0, Math.round(payload.value * 100)));
          desiredVolumePct = pct;
          if (api) api.setVolume(pct);
          else if (video) video.volume = payload.value;
        }
        break;
      case "queue-jump": {
        const items = document.querySelectorAll(SELECTORS.queueItems);
        items[payload?.index]?.click();
        break;
      }
      case "toggle-shuffle":
        shuffleButton()?.click();
        break;
      case "toggle-repeat":
        repeatButton()?.click();
        break;
    }

    scheduleStatePush();
  }

  const observer = new MutationObserver(() => {
    attachVideoListeners();
    scheduleStatePush();
  });
  observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true });

  connectPort();
  attachVideoListeners();
  pushState();
})();
