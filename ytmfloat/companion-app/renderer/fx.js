const BANDS = [
  { freq: 60, label: "60" },
  { freq: 150, label: "150" },
  { freq: 400, label: "400" },
  { freq: 1000, label: "1K" },
  { freq: 2400, label: "2.4K" },
  { freq: 6000, label: "6K" },
  { freq: 15000, label: "15K" },
];

const EQ_PRESETS = {
  flat: [0, 0, 0, 0, 0, 0, 0],
  bassBoost: [6, 5, 3, 0, 0, 0, 0],
  trebleBoost: [0, 0, 0, 0, 2, 4, 6],
  vocal: [-2, -1, 2, 4, 3, 1, 0],
  lofi: [3, 2, 0, -2, -4, -6, -8],
};

const PRESET_LABELS = { flat: "Flat", bassBoost: "Bass", trebleBoost: "Treble", vocal: "Vocal", lofi: "Lo-Fi" };

const eqBandsEl = document.getElementById("eq-bands");
const presetsEl = document.getElementById("fx-presets");
const reverbEl = document.getElementById("reverb");
const reverbValueEl = document.getElementById("reverb-value");
const widthEl = document.getElementById("width");
const widthValueEl = document.getElementById("width-value");

const bandSliders = BANDS.map((band, index) => {
  const wrap = document.createElement("div");
  wrap.className = "eq-band";

  const slider = document.createElement("input");
  slider.type = "range";
  slider.min = "-12";
  slider.max = "12";
  slider.value = "0";
  slider.className = "eq-slider";
  slider.addEventListener("input", () => {
    window.ytm.sendCommand("eq-band", { index, gainDb: Number(slider.value) });
  });

  const label = document.createElement("div");
  label.className = "eq-band-label";
  label.textContent = band.label;

  wrap.appendChild(slider);
  wrap.appendChild(label);
  eqBandsEl.appendChild(wrap);
  return slider;
});

Object.keys(EQ_PRESETS).forEach((name) => {
  const btn = document.createElement("button");
  btn.className = "fx-preset-btn";
  btn.textContent = PRESET_LABELS[name];
  btn.addEventListener("click", () => {
    EQ_PRESETS[name].forEach((value, i) => {
      bandSliders[i].value = String(value);
    });
    window.ytm.sendCommand("eq-preset", { name });
  });
  presetsEl.appendChild(btn);
});

reverbEl.addEventListener("input", () => {
  reverbValueEl.textContent = `${reverbEl.value}%`;
  window.ytm.sendCommand("reverb-wet", { value: Number(reverbEl.value) / 100 });
});

widthEl.addEventListener("input", () => {
  widthValueEl.textContent = `${widthEl.value}%`;
  window.ytm.sendCommand("stereo-width", { value: Number(widthEl.value) / 100 });
});

document.getElementById("fx-reset").addEventListener("click", () => {
  bandSliders.forEach((slider) => (slider.value = "0"));
  reverbEl.value = "0";
  reverbValueEl.textContent = "0%";
  widthEl.value = "100";
  widthValueEl.textContent = "100%";
  window.ytm.sendCommand("fx-reset");
});

document.getElementById("fx-close-btn").addEventListener("click", () => {
  window.ytm.hide();
});

window.ytm.onFXState((state) => {
  if (!state) return;
  (state.eq ?? []).forEach((value, i) => {
    if (bandSliders[i]) bandSliders[i].value = String(value);
  });
  const reverbPct = Math.round((state.reverbWet ?? 0) * 100);
  reverbEl.value = String(reverbPct);
  reverbValueEl.textContent = `${reverbPct}%`;
  const widthPct = Math.round((state.width ?? 1) * 100);
  widthEl.value = String(widthPct);
  widthValueEl.textContent = `${widthPct}%`;
});
