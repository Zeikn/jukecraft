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
const queueToggle = document.getElementById("queue-toggle");
const queuePanel = document.getElementById("queue-panel");
const queueList = document.getElementById("queue-list");
const closeBtn = document.getElementById("close-btn");
const shuffleBtn = document.getElementById("shuffle");
const repeatBtn = document.getElementById("repeat");
const compactToggle = document.getElementById("compact-toggle");
const fxToggle = document.getElementById("fx-toggle");

let isSeeking = false;
let isAdjustingVolume = false;
let latestState = null;
let queueOpen = false;
let lastAutoScrolledKey = null;
let isCompact = false;

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

  let currentRow = null;

  for (const item of queue) {
    const row = document.createElement("div");
    row.className = "queue-item" + (item.isCurrent ? " current" : "");
    if (item.isCurrent) currentRow = row;

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
      window.ytm.sendCommand("queue-jump", { index: item.index });
    });

    queueList.appendChild(row);
  }

  const currentKey = currentRow?.querySelector(".qtitle")?.textContent ?? null;
  if (currentRow && currentKey !== lastAutoScrolledKey) {
    lastAutoScrolledKey = currentKey;
    currentRow.scrollIntoView({ block: "start" });
  }
}

function render(state) {
  latestState = state;

  if (!state) {
    card.classList.add("no-track");
    titleEl.textContent = "Not playing";
    artistEl.textContent = "Open YouTube Music in Opera";
    thumbnail.removeAttribute("src");
    playIcon.classList.add("active");
    pauseIcon.classList.remove("active");
    return;
  }

  card.classList.remove("no-track");
  titleEl.textContent = state.title || "Unknown title";
  artistEl.textContent = state.artist || "";
  if (state.thumbnailUrl) thumbnail.src = state.thumbnailUrl;

  playIcon.classList.toggle("active", !state.isPlaying);
  pauseIcon.classList.toggle("active", state.isPlaying);

  if (!isSeeking) {
    const pct = state.duration > 0 ? (state.currentTime / state.duration) * 1000 : 0;
    seek.value = String(pct);
    currentTimeEl.textContent = formatTime(state.currentTime);
    durationEl.textContent = formatTime(state.duration);
  }

  if (!isAdjustingVolume) {
    volume.value = String(Math.round((state.volume ?? 1) * 100));
  }

  const repeatMode = state.repeatMode ?? "off";
  repeatBtn.classList.toggle("active", repeatMode !== "off");
  repeatBtn.title = repeatMode === "one" ? "Repeat one" : repeatMode === "all" ? "Repeat all" : "Repeat off";

  if (queueOpen) renderQueue(state.queue);
}

window.ytm.onState(render);

document.getElementById("play-pause").addEventListener("click", () => {
  window.ytm.sendCommand("toggle-play");
});
document.getElementById("next").addEventListener("click", () => {
  window.ytm.sendCommand("next");
});
document.getElementById("prev").addEventListener("click", () => {
  window.ytm.sendCommand("previous");
});
shuffleBtn.addEventListener("click", () => {
  window.ytm.sendCommand("toggle-shuffle");
});
repeatBtn.addEventListener("click", () => {
  window.ytm.sendCommand("toggle-repeat");
});

seek.addEventListener("input", () => {
  isSeeking = true;
  if (latestState?.duration) {
    currentTimeEl.textContent = formatTime((seek.value / 1000) * latestState.duration);
  }
});
seek.addEventListener("change", () => {
  if (latestState?.duration) {
    window.ytm.sendCommand("seek", { time: (seek.value / 1000) * latestState.duration });
  }
  isSeeking = false;
});

volume.addEventListener("input", () => {
  isAdjustingVolume = true;
  window.ytm.sendCommand("volume", { value: volume.value / 100 });
});
volume.addEventListener("change", () => {
  isAdjustingVolume = false;
});

queueToggle.addEventListener("click", () => {
  queueOpen = !queueOpen;
  queueToggle.setAttribute("aria-expanded", String(queueOpen));
  window.ytm.resizeForQueue(queueOpen);
  if (queueOpen && latestState) renderQueue(latestState.queue);
  queuePanel.classList.toggle("open", queueOpen);
});

closeBtn.addEventListener("click", () => {
  window.ytm.hide();
});

compactToggle.addEventListener("click", () => {
  isCompact = !isCompact;
  card.classList.toggle("compact", isCompact);
  compactToggle.title = isCompact ? "Expand" : "Compact mode";
  window.ytm.setCompact(isCompact);
});

fxToggle.addEventListener("click", () => {
  window.ytm.openFX();
});

render(null);
