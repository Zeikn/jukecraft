const card = document.getElementById("card");
const thumbnail = document.getElementById("thumbnail");
const titleEl = document.getElementById("title");
const artistEl = document.getElementById("artist");
const seek = document.getElementById("seek");
const currentTimeEl = document.getElementById("current-time");
const durationEl = document.getElementById("duration");
const playIcon = document.getElementById("play-icon");
const pauseIcon = document.getElementById("pause-icon");
const volume = document.getElementById("volume");
const closeBtn = document.getElementById("close-btn");
const compactToggle = document.getElementById("compact-toggle");
const fxToggle = document.getElementById("fx-toggle");
const shuffleBtn = document.getElementById("shuffle");
const repeatBtn = document.getElementById("repeat");
const queueToggle = document.getElementById("queue-toggle");
const queuePanel = document.getElementById("queue-panel");
const queueList = document.getElementById("queue-list");

let isSeeking = false;
let isAdjustingVolume = false;
let latestState = null;
let isCompact = false;
let queueOpen = false;

let anchorTime = 0;
let anchorDuration = 0;
let anchorReceivedAt = 0;

function tick() {
  if (!latestState?.isPlaying || isSeeking) return;
  const elapsed = (performance.now() - anchorReceivedAt) / 1000;
  const estimated = Math.min(anchorDuration, Math.max(0, anchorTime + elapsed));
  const pct = anchorDuration > 0 ? (estimated / anchorDuration) * 1000 : 0;
  seek.value = String(pct);
  currentTimeEl.textContent = formatTime(estimated);
}

setInterval(tick, 250);

function formatTime(seconds) {
  if (!Number.isFinite(seconds) || seconds < 0) seconds = 0;
  const m = Math.floor(seconds / 60);
  const s = Math.floor(seconds % 60)
    .toString()
    .padStart(2, "0");
  return `${m}:${s}`;
}

function renderQueue(queue) {
  queueList.innerHTML = "";
  if (!queue || queue.length === 0) {
    queueList.textContent = "Queue unavailable";
    return;
  }

  for (const item of queue) {
    const row = document.createElement("div");
    row.className = "queue-item";

    const img = document.createElement("img");
    img.src = item.thumbnailUrl || "";
    row.appendChild(img);

    const meta = document.createElement("div");
    meta.style.minWidth = "0";
    const qtitle = document.createElement("div");
    qtitle.className = "qtitle";
    qtitle.textContent = item.title || "Unknown";
    const qartist = document.createElement("div");
    qartist.className = "qartist";
    qartist.textContent = item.artist || "";
    meta.appendChild(qtitle);
    meta.appendChild(qartist);
    row.appendChild(meta);

    row.addEventListener("click", () => {
      window.spotifloat.sendCommand("queue-jump", { index: item.index });
    });

    queueList.appendChild(row);
  }
}

function render(state) {
  latestState = state;

  if (!state) {
    card.classList.add("no-track");
    titleEl.textContent = "Not playing";
    artistEl.textContent = "Open Spotify in your browser";
    thumbnail.removeAttribute("src");
    playIcon.hidden = false;
    pauseIcon.hidden = true;
    return;
  }

  card.classList.remove("no-track");
  titleEl.textContent = state.title || "Unknown title";
  artistEl.textContent = state.artist || "";
  if (state.thumbnailUrl) thumbnail.src = state.thumbnailUrl;

  playIcon.hidden = state.isPlaying;
  pauseIcon.hidden = !state.isPlaying;

  anchorTime = state.currentTime;
  anchorDuration = state.duration;
  anchorReceivedAt = performance.now();

  if (!isSeeking) {
    durationEl.textContent = formatTime(state.duration);
    tick();
  }

  if (!isAdjustingVolume) {
    volume.value = String(Math.round((state.volume ?? 1) * 100));
  }

  shuffleBtn.classList.toggle("active", !!state.shuffleActive);

  const repeatMode = state.repeatMode ?? "off";
  repeatBtn.classList.toggle("active", repeatMode !== "off");
  repeatBtn.title = repeatMode === "one" ? "Repeat one" : repeatMode === "all" ? "Repeat all" : "Repeat off";

  if (queueOpen) renderQueue(state.queue);
}

window.spotifloat.onState(render);

document.getElementById("play-pause").addEventListener("click", () => {
  window.spotifloat.sendCommand("toggle-play");
});
document.getElementById("next").addEventListener("click", () => {
  window.spotifloat.sendCommand("next");
});
document.getElementById("prev").addEventListener("click", () => {
  window.spotifloat.sendCommand("previous");
});
shuffleBtn.addEventListener("click", () => {
  window.spotifloat.sendCommand("toggle-shuffle");
});
repeatBtn.addEventListener("click", () => {
  window.spotifloat.sendCommand("toggle-repeat");
});

seek.addEventListener("input", () => {
  isSeeking = true;
  if (latestState?.duration) {
    currentTimeEl.textContent = formatTime((seek.value / 1000) * latestState.duration);
  }
});
seek.addEventListener("change", () => {
  if (latestState?.duration) {
    window.spotifloat.sendCommand("seek", { time: (seek.value / 1000) * latestState.duration });
  }
  isSeeking = false;
});

volume.addEventListener("input", () => {
  isAdjustingVolume = true;
  window.spotifloat.sendCommand("volume", { value: volume.value / 100 });
});
volume.addEventListener("change", () => {
  isAdjustingVolume = false;
});

queueToggle.addEventListener("click", () => {
  queueOpen = !queueOpen;
  queueToggle.setAttribute("aria-expanded", String(queueOpen));
  window.spotifloat.resizeForQueue(queueOpen);
  if (queueOpen && latestState) renderQueue(latestState.queue);
  queuePanel.classList.toggle("open", queueOpen);
});

closeBtn.addEventListener("click", () => {
  window.spotifloat.hide();
});

fxToggle.addEventListener("click", () => {
  window.spotifloat.openFX();
});

compactToggle.addEventListener("click", () => {
  isCompact = !isCompact;
  card.classList.toggle("compact", isCompact);
  compactToggle.title = isCompact ? "Expand" : "Compact mode";
  window.spotifloat.setCompact(isCompact);
});

render(null);
