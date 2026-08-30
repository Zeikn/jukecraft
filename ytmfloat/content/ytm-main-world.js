(() => {
  function runsText(runs) {
    return Array.isArray(runs) ? runs.map((r) => r.text ?? "").join("") : "";
  }

  function thumbnailFromData(data) {
    const thumbs = data?.thumbnail?.thumbnails;
    if (!thumbs?.length) return "";
    const url = thumbs[thumbs.length - 1].url || "";
    return url.startsWith("//") ? "https:" + url : url;
  }

  function annotate(item) {
    const data = item.data;
    if (!data) return;
    item.dataset.ytmfloatTitle = runsText(data.title?.runs);
    item.dataset.ytmfloatArtist = runsText(data.longBylineText?.runs) || runsText(data.shortBylineText?.runs);
    item.dataset.ytmfloatThumb = thumbnailFromData(data);
    item.dataset.ytmfloatVideoId = data.videoId ?? "";
    item.dataset.ytmfloatSelected = data.selected ? "1" : "0";
  }

  function annotateAll() {
    document.querySelectorAll("ytmusic-player-queue-item").forEach(annotate);
  }

  const observer = new MutationObserver(annotateAll);
  observer.observe(document.documentElement, { childList: true, subtree: true });

  annotateAll();
  setInterval(annotateAll, 1000);
})();

(() => {
  if (window.__ytmfloatFXInstalled) return;
  window.__ytmfloatFXInstalled = true;

  const nativeCreateElement = document.createElement.bind(document);

  const EQ_BANDS = [60, 150, 400, 1000, 2400, 6000, 15000];
  const EQ_PRESETS = {
    flat: [0, 0, 0, 0, 0, 0, 0],
    bassBoost: [6, 5, 3, 0, 0, 0, 0],
    trebleBoost: [0, 0, 0, 0, 2, 4, 6],
    vocal: [-2, -1, 2, 4, 3, 1, 0],
    lofi: [3, 2, 0, -2, -4, -6, -8],
  };

  let sharedContext = null;
  const graphs = [];
  const currentParams = {
    eq: EQ_BANDS.map(() => 0),
    reverbWet: 0,
    width: 1,
  };

  function buildImpulseResponse(ctx, durationSec, decay) {
    const rate = ctx.sampleRate;
    const length = Math.max(1, Math.floor(rate * durationSec));
    const impulse = ctx.createBuffer(2, length, rate);
    for (let ch = 0; ch < 2; ch++) {
      const data = impulse.getChannelData(ch);
      for (let i = 0; i < length; i++) {
        data[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / length, decay);
      }
    }
    return impulse;
  }

  function buildEqChain(ctx) {
    const nodes = EQ_BANDS.map((freq) => {
      const f = ctx.createBiquadFilter();
      f.type = "peaking";
      f.frequency.value = freq;
      f.Q.value = 1;
      f.gain.value = 0;
      return f;
    });
    for (let i = 0; i < nodes.length - 1; i++) nodes[i].connect(nodes[i + 1]);
    return nodes;
  }

  function buildReverb(ctx) {
    const input = ctx.createGain();
    const dry = ctx.createGain();
    dry.gain.value = 1;
    const wet = ctx.createGain();
    wet.gain.value = 0;
    const convolver = ctx.createConvolver();
    convolver.buffer = buildImpulseResponse(ctx, 2.5, 3);
    const output = ctx.createGain();

    input.connect(dry).connect(output);
    input.connect(convolver).connect(wet).connect(output);

    return { input, output, wetParam: wet.gain };
  }

  function buildWidener(ctx) {
    const splitter = ctx.createChannelSplitter(2);
    const merger = ctx.createChannelMerger(2);

    const lToMid = ctx.createGain();
    lToMid.gain.value = 0.5;
    const rToMid = ctx.createGain();
    rToMid.gain.value = 0.5;
    const lToSide = ctx.createGain();
    lToSide.gain.value = 0.5;
    const rToSide = ctx.createGain();
    rToSide.gain.value = -0.5;

    const mid = ctx.createGain();
    const side = ctx.createGain();
    side.gain.value = 1;
    const sideInv = ctx.createGain();
    sideInv.gain.value = -1;

    const outL = ctx.createGain();
    const outR = ctx.createGain();

    splitter.connect(lToMid, 0);
    splitter.connect(rToMid, 1);
    splitter.connect(lToSide, 0);
    splitter.connect(rToSide, 1);

    lToMid.connect(mid);
    rToMid.connect(mid);
    lToSide.connect(side);
    rToSide.connect(side);

    mid.connect(outL);
    side.connect(outL);
    mid.connect(outR);
    side.connect(sideInv);
    sideInv.connect(outR);

    outL.connect(merger, 0, 0);
    outR.connect(merger, 0, 1);

    return { input: splitter, output: merger, widthParam: side.gain };
  }

  function applyCurrentParamsTo(graph) {
    graph.eqNodes.forEach((node, i) => {
      node.gain.value = currentParams.eq[i] ?? 0;
    });
    graph.reverb.wetParam.value = currentParams.reverbWet;
    graph.widener.widthParam.value = currentParams.width;
  }

  function setupGraphForElement(el) {
    try {
      if (!sharedContext) sharedContext = new (window.AudioContext || window.webkitAudioContext)();
      const ctx = sharedContext;
      const source = ctx.createMediaElementSource(el);

      const eqNodes = buildEqChain(ctx);
      const reverb = buildReverb(ctx);
      const widener = buildWidener(ctx);

      source.connect(eqNodes[0]);
      eqNodes[eqNodes.length - 1].connect(reverb.input);
      reverb.output.connect(widener.input);
      widener.output.connect(ctx.destination);

      const graph = { eqNodes, reverb, widener };
      applyCurrentParamsTo(graph);
      graphs.push(graph);

      if (ctx.state === "suspended") ctx.resume().catch(() => {});

      window.postMessage({ source: "ytmfloat-fx-status", ready: true }, "*");
      console.log("[YTM Float] FX graph attached to media element", el);
    } catch (err) {
      console.warn("[YTM Float] FX graph setup failed, falling back to passthrough:", err);
      try {
        if (sharedContext) {
          const fallbackSource = sharedContext.createMediaElementSource(el);
          fallbackSource.connect(sharedContext.destination);
        }
      } catch {}
    }
  }

  function unlockAudioContext() {
    if (sharedContext && sharedContext.state === "suspended") {
      sharedContext.resume().catch(() => {});
    }
  }

  ["click", "keydown", "touchstart"].forEach((evt) => {
    document.addEventListener(evt, unlockAudioContext, { capture: true });
  });

  document.createElement = function (tagName, ...rest) {
    const el = nativeCreateElement(tagName, ...rest);
    if (typeof tagName === "string") {
      const tag = tagName.toLowerCase();
      if (tag === "video" || tag === "audio") {
        setTimeout(() => setupGraphForElement(el), 0);
      }
    }
    return el;
  };

  function handleFXCommand(cmd) {
    switch (cmd.type) {
      case "eq-band":
        currentParams.eq[cmd.index] = cmd.gainDb;
        break;
      case "eq-preset":
        if (EQ_PRESETS[cmd.name]) currentParams.eq = [...EQ_PRESETS[cmd.name]];
        break;
      case "reverb-wet":
        currentParams.reverbWet = cmd.value;
        break;
      case "stereo-width":
        currentParams.width = cmd.value;
        break;
      case "fx-reset":
        currentParams.eq = EQ_BANDS.map(() => 0);
        currentParams.reverbWet = 0;
        currentParams.width = 1;
        break;
      case "sync":
        if (Array.isArray(cmd.eq)) currentParams.eq = [...cmd.eq];
        if (typeof cmd.reverbWet === "number") currentParams.reverbWet = cmd.reverbWet;
        if (typeof cmd.width === "number") currentParams.width = cmd.width;
        break;
      default:
        return;
    }
    graphs.forEach(applyCurrentParamsTo);
  }

  window.addEventListener("message", (event) => {
    if (event.source !== window) return;
    const data = event.data;
    if (!data || data.source !== "ytmfloat-fx-command") return;
    handleFXCommand(data.payload);
  });

  window.__ytmfloatFXDebug = {
    setBand: (index, gainDb) => handleFXCommand({ type: "eq-band", index, gainDb }),
    setPreset: (name) => handleFXCommand({ type: "eq-preset", name }),
    setReverbWet: (value) => handleFXCommand({ type: "reverb-wet", value }),
    setWidth: (value) => handleFXCommand({ type: "stereo-width", value }),
    reset: () => handleFXCommand({ type: "fx-reset" }),
    bands: EQ_BANDS,
    presets: Object.keys(EQ_PRESETS),
  };
})();
