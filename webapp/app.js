const STORAGE_KEY = "embySonic.webSession";

const loginView = document.querySelector("#loginView");
const mainView = document.querySelector("#mainView");
const loginForm = document.querySelector("#loginForm");
const logoutButton = document.querySelector("#logoutButton");
const tabButtons = [...document.querySelectorAll(".tab-button")];
const viewPanels = [...document.querySelectorAll(".view-panel")];

const searchForm = document.querySelector("#searchForm");
const searchInput = document.querySelector("#searchInput");
const resultsList = document.querySelector("#resultsList");
const similarPanel = document.querySelector("#similarPanel");
const similarTitle = document.querySelector("#similarTitle");
const similarList = document.querySelector("#similarList");

const refreshMixesButton = document.querySelector("#refreshMixesButton");
const mixesList = document.querySelector("#mixesList");
const mixDetailPanel = document.querySelector("#mixDetailPanel");
const mixDetailTitle = document.querySelector("#mixDetailTitle");
const mixDetailMeta = document.querySelector("#mixDetailMeta");
const mixTracksList = document.querySelector("#mixTracksList");
const playMixButton = document.querySelector("#playMixButton");
const regenerateMixButton = document.querySelector("#regenerateMixButton");

const adventureStartForm = document.querySelector("#adventureStartForm");
const adventureEndForm = document.querySelector("#adventureEndForm");
const adventureBuildForm = document.querySelector("#adventureBuildForm");
const adventureStartInput = document.querySelector("#adventureStartInput");
const adventureEndInput = document.querySelector("#adventureEndInput");
const adventureLengthInput = document.querySelector("#adventureLengthInput");
const adventureStartChoice = document.querySelector("#adventureStartChoice");
const adventureEndChoice = document.querySelector("#adventureEndChoice");
const adventureStartResults = document.querySelector("#adventureStartResults");
const adventureEndResults = document.querySelector("#adventureEndResults");

const queueList = document.querySelector("#queueList");
const message = document.querySelector("#message");
const audio = document.querySelector("#audio");
const playButton = document.querySelector("#playButton");
const prevButton = document.querySelector("#prevButton");
const nextButton = document.querySelector("#nextButton");
const shuffleButton = document.querySelector("#shuffleButton");
const repeatButton = document.querySelector("#repeatButton");
const seekBar = document.querySelector("#seekBar");
const timeElapsed = document.querySelector("#timeElapsed");
const timeDuration = document.querySelector("#timeDuration");
const nowSimilarButton = document.querySelector("#nowSimilarButton");
const nowTitle = document.querySelector("#nowTitle");
const nowSubtitle = document.querySelector("#nowSubtitle");
const artwork = document.querySelector("#artwork");

const state = {
  session: loadSession(),
  activeView: "search",
  queue: [],
  originalQueue: [],
  currentIndex: -1,
  shuffle: false,
  repeat: "none", // "none" | "one" | "all"
  mixes: [],
  selectedMix: null,
  adventureFrom: null,
  adventureTo: null,
};

renderSession();
renderView();
renderPlayer();
renderShuffle();
renderRepeat();

tabButtons.forEach((button) => {
  button.addEventListener("click", () => switchView(button.dataset.view));
});

loginForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = new FormData(loginForm);
  await withBusy("Logging in...", async () => {
    const resp = await fetch("/sonic/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        username: form.get("username"),
        password: form.get("password"),
      }),
    });
    const data = await parseJson(resp);
    state.session = {
      token: data.access_token,
      userId: data.user_id,
      userName: data.user_name,
      serverUrl: data.server_url,
    };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state.session));
    loginForm.reset();
    renderSession();
    setMessage(`Signed in${state.session.userName ? ` as ${state.session.userName}` : ""}.`);
  });
});

logoutButton.addEventListener("click", () => {
  localStorage.removeItem(STORAGE_KEY);
  state.session = null;
  state.queue = [];
  state.originalQueue = [];
  state.currentIndex = -1;
  state.shuffle = false;
  state.repeat = "none";
  state.mixes = [];
  state.selectedMix = null;
  state.adventureFrom = null;
  state.adventureTo = null;
  audio.pause();
  audio.removeAttribute("src");
  renderSession();
  renderResults([]);
  renderSimilar(null, []);
  renderMixes();
  renderMixDetail(null);
  renderAdventureChoices();
  renderQueue();
  renderPlayer();
  renderShuffle();
  renderRepeat();
  setMessage("");
});

searchForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const query = searchInput.value.trim();
  if (!query) {
    renderResults([]);
    return;
  }
  await withBusy("Searching...", async () => {
    const tracks = await searchTracks(query);
    renderResults(tracks);
    setMessage(tracks.length ? `${tracks.length} tracks found.` : "No tracks found.");
  });
});

refreshMixesButton.addEventListener("click", () => loadMixes(true));
playMixButton.addEventListener("click", () => {
  if (state.selectedMix?.tracks?.length) {
    loadQueue(state.selectedMix.tracks, `Mix: ${mixName(state.selectedMix.mix)}`, true);
  }
});
regenerateMixButton.addEventListener("click", () => regenerateSelectedMix());

adventureStartForm.addEventListener("submit", (event) => {
  event.preventDefault();
  searchAdventurePicker("from", adventureStartInput.value.trim());
});

adventureEndForm.addEventListener("submit", (event) => {
  event.preventDefault();
  searchAdventurePicker("to", adventureEndInput.value.trim());
});

adventureBuildForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  await buildAdventure();
});

playButton.addEventListener("click", () => {
  if (!audio.src && state.queue.length) {
    playIndex(Math.max(0, state.currentIndex));
    return;
  }
  if (audio.paused) {
    audio.play();
  } else {
    audio.pause();
  }
});

prevButton.addEventListener("click", () => playIndex(Math.max(0, state.currentIndex - 1)));
nextButton.addEventListener("click", () => playIndex(Math.min(state.queue.length - 1, state.currentIndex + 1)));
nowSimilarButton.addEventListener("click", () => {
  const track = state.queue[state.currentIndex];
  if (track) showSimilar(track);
});

shuffleButton.addEventListener("click", () => {
  state.shuffle = !state.shuffle;
  if (state.queue.length) {
    if (state.shuffle) {
      const current = state.queue[state.currentIndex];
      const rest = state.queue.filter((_, i) => i !== state.currentIndex);
      const shuffled = shuffleArray(rest);
      state.queue = current ? [current, ...shuffled] : shuffled;
      state.currentIndex = current ? 0 : -1;
    } else {
      const currentId = state.queue[state.currentIndex]?.id;
      state.queue = [...state.originalQueue];
      state.currentIndex = currentId != null
        ? state.queue.findIndex((t) => t.id === currentId)
        : -1;
    }
    renderQueue();
  }
  renderShuffle();
});

repeatButton.addEventListener("click", () => {
  const modes = ["none", "one", "all"];
  state.repeat = modes[(modes.indexOf(state.repeat) + 1) % modes.length];
  renderRepeat();
});

audio.addEventListener("play", renderPlayer);
audio.addEventListener("pause", renderPlayer);

audio.addEventListener("timeupdate", () => {
  if (!audio.duration || audio.seeking) return;
  seekBar.value = String((audio.currentTime / audio.duration) * 100);
  timeElapsed.textContent = formatTime(audio.currentTime);
});

audio.addEventListener("loadedmetadata", () => {
  seekBar.value = "0";
  timeElapsed.textContent = "0:00";
  timeDuration.textContent = formatTime(audio.duration);
});

audio.addEventListener("durationchange", () => {
  timeDuration.textContent = formatTime(audio.duration);
});

audio.addEventListener("waiting", () => playButton.classList.add("buffering"));
audio.addEventListener("canplay", () => playButton.classList.remove("buffering"));

audio.addEventListener("ended", () => {
  if (state.repeat === "one") {
    audio.currentTime = 0;
    audio.play();
    return;
  }
  if (state.currentIndex < state.queue.length - 1) {
    playIndex(state.currentIndex + 1);
  } else if (state.repeat === "all" && state.queue.length) {
    playIndex(0);
  } else {
    renderPlayer();
  }
});

seekBar.addEventListener("input", () => {
  if (!audio.duration) return;
  audio.currentTime = (Number(seekBar.value) / 100) * audio.duration;
  timeElapsed.textContent = formatTime(audio.currentTime);
});

function loadSession() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function renderSession() {
  const signedIn = Boolean(state.session?.token);
  loginView.classList.toggle("hidden", signedIn);
  mainView.classList.toggle("hidden", !signedIn);
  logoutButton.classList.toggle("hidden", !signedIn);
}

async function switchView(view) {
  state.activeView = view;
  renderView();
  if (view === "mixes" && !state.mixes.length) {
    await loadMixes(false);
  }
}

function renderView() {
  tabButtons.forEach((button) => {
    button.classList.toggle("active", button.dataset.view === state.activeView);
  });
  viewPanels.forEach((panel) => {
    panel.classList.toggle("hidden", panel.id !== `${state.activeView}View`);
  });
}

async function searchTracks(query, limit = 60) {
  const resp = await authedFetch(`/sonic/search/tracks?q=${encodeURIComponent(query)}&limit=${limit}`);
  return parseJson(resp);
}

function renderResults(tracks) {
  if (!tracks.length) {
    resultsList.replaceChildren();
    return;
  }
  resultsList.replaceChildren(...tracks.map((track) => {
    const item = document.createElement("li");
    item.className = "result-row";

    const main = trackButton(track, () => startRadio(track), "Start radio");
    const actions = document.createElement("div");
    actions.className = "action-row";
    actions.append(
      actionButton("Radio", () => startRadio(track)),
      actionButton("Similar", () => showSimilar(track)),
    );

    item.append(main, actions);
    return item;
  }));
}

async function startRadio(seed) {
  await withBusy("Building radio...", async () => {
    const resp = await authedFetch(`/sonic/tracks/${encodeURIComponent(seed.id)}/radio`);
    const radio = await parseJson(resp);
    const tracks = Array.isArray(radio.tracks) ? radio.tracks : [];
    if (!tracks.length) {
      loadQueue([], "No radio queue yet.", false);
      setMessage(`No radio for "${seed.title || "this track"}" - it may not be analysed yet.`);
      return;
    }
    // Match the Android app: radio starts with the seed track itself, then the
    // sonic neighbours returned by build_radio.
    loadQueue([seed, ...tracks.filter((track) => track.id !== seed.id)], `Radio: ${seed.title || "Untitled track"}`, true);
  });
}

async function showSimilar(seed) {
  await switchView("search");
  await withBusy("Finding similar tracks...", async () => {
    const resp = await authedFetch(`/sonic/tracks/${encodeURIComponent(seed.id)}/similar?n=25`);
    const similar = await parseJson(resp);
    renderSimilar(seed, similar);
    setMessage(similar.length ? `${similar.length} similar tracks found.` : "No similar tracks found.");
  });
}

function renderSimilar(seed, similar) {
  similarPanel.classList.toggle("hidden", !seed);
  similarTitle.textContent = seed ? seed.title || "Untitled track" : "No track selected";
  if (!similar.length) {
    similarList.replaceChildren(emptyState("No similar tracks found"));
    return;
  }
  similarList.replaceChildren(...similar.map((result) => {
    const item = document.createElement("li");
    item.className = "result-row";
    const track = result.track;
    const main = trackButton(track, () => startRadio(track), "Start radio");
    const actions = document.createElement("div");
    actions.className = "action-row";
    const score = document.createElement("span");
    score.className = "score";
    score.textContent = `${Math.round((result.score || 0) * 100)}%`;
    actions.append(score, actionButton("Radio", () => startRadio(track)));
    item.append(main, actions);
    return item;
  }));
}

async function loadMixes(force) {
  if (state.mixes.length && !force) return;
  await withBusy("Loading mixes...", async () => {
    const resp = await authedFetch("/sonic/mixes");
    state.mixes = await parseJson(resp);
    renderMixes();
    setMessage(state.mixes.length ? `${state.mixes.length} mixes loaded.` : "No mixes yet.");
  });
}

function renderMixes() {
  if (!state.mixes.length) {
    mixesList.replaceChildren(emptyState("No mixes yet — run the worker to analyse your library"));
    return;
  }
  mixesList.replaceChildren(...state.mixes.map((mix) => {
    const item = document.createElement("li");
    item.className = "result-row";

    const cover = document.createElement("img");
    cover.className = "cover-thumb";
    cover.alt = "";
    if (mix.cover_track_id) cover.src = artworkUrl(mix.cover_track_id);

    const main = document.createElement("button");
    main.type = "button";
    main.className = "mix-main";
    main.addEventListener("click", () => openMix(mix.id));
    main.append(trackText({
      title: mixName(mix),
      artist: `${mix.track_count} tracks`,
      album: mix.cluster_id == null ? null : `Cluster ${mix.cluster_id}`,
    }));

    const actions = document.createElement("div");
    actions.className = "action-row";
    actions.append(
      actionButton("Open", () => openMix(mix.id)),
      actionButton("Play", async () => {
        const detail = await getMix(mix.id);
        loadQueue(detail.tracks, `Mix: ${mixName(detail.mix)}`, true);
      }),
    );

    item.append(cover, main, actions);
    return item;
  }));
}

async function openMix(mixId) {
  await withBusy("Opening mix...", async () => {
    const detail = await getMix(mixId);
    state.selectedMix = detail;
    renderMixDetail(detail);
    setMessage(`${mixName(detail.mix)} loaded.`);
  });
}

async function getMix(mixId) {
  const resp = await authedFetch(`/sonic/mixes/${encodeURIComponent(mixId)}`);
  return parseJson(resp);
}

function renderMixDetail(detail) {
  mixDetailPanel.classList.toggle("hidden", !detail);
  if (!detail) {
    mixDetailTitle.textContent = "No mix selected";
    mixDetailMeta.textContent = "";
    mixTracksList.replaceChildren();
    return;
  }
  mixDetailTitle.textContent = mixName(detail.mix);
  mixDetailMeta.textContent = `${detail.tracks.length} tracks`;
  mixTracksList.replaceChildren(...detail.tracks.map((track, index) => queueRow(track, index, () => {
    loadQueue(detail.tracks, `Mix: ${mixName(detail.mix)}`, false, index);
    playIndex(index);
  })));
}

async function regenerateSelectedMix() {
  const detail = state.selectedMix;
  if (!detail) return;
  await withBusy("Regenerating mix...", async () => {
    const resp = await authedFetch(`/sonic/mixes/${encodeURIComponent(detail.mix.id)}/regenerate`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ tracks_per_mix: detail.tracks.length || 50 }),
    });
    state.selectedMix = await parseJson(resp);
    renderMixDetail(state.selectedMix);
    state.mixes = [];
    await loadMixes(true);
    setMessage(`${mixName(state.selectedMix.mix)} regenerated.`);
  });
}

async function searchAdventurePicker(kind, query) {
  const list = kind === "from" ? adventureStartResults : adventureEndResults;
  if (!query) {
    list.replaceChildren();
    return;
  }
  await withBusy("Searching...", async () => {
    const tracks = await searchTracks(query, 20);
    renderAdventureResults(kind, tracks);
    setMessage(tracks.length ? `${tracks.length} tracks found.` : "No tracks found.");
  });
}

function renderAdventureResults(kind, tracks) {
  const list = kind === "from" ? adventureStartResults : adventureEndResults;
  list.replaceChildren(...tracks.map((track) => {
    const item = document.createElement("li");
    item.className = "result-row";
    const button = trackButton(track, () => chooseAdventureTrack(kind, track), "Choose track");
    item.append(button, actionButton("Choose", () => chooseAdventureTrack(kind, track)));
    return item;
  }));
}

function chooseAdventureTrack(kind, track) {
  if (kind === "from") {
    state.adventureFrom = track;
    adventureStartResults.replaceChildren();
  } else {
    state.adventureTo = track;
    adventureEndResults.replaceChildren();
  }
  renderAdventureChoices();
}

function renderAdventureChoices() {
  adventureStartChoice.textContent = state.adventureFrom
    ? `Start: ${trackLabel(state.adventureFrom)}`
    : "No start selected.";
  adventureEndChoice.textContent = state.adventureTo
    ? `End: ${trackLabel(state.adventureTo)}`
    : "No end selected.";
}

async function buildAdventure() {
  if (!state.adventureFrom || !state.adventureTo) {
    setMessage("Choose a start and end track first.");
    return;
  }
  const length = clamp(Number.parseInt(adventureLengthInput.value, 10) || 20, 5, 100);
  adventureLengthInput.value = String(length);
  await withBusy("Building adventure...", async () => {
    const resp = await authedFetch("/sonic/adventure", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        from_id: state.adventureFrom.id,
        to_id: state.adventureTo.id,
        length,
      }),
    });
    const adventure = await parseJson(resp);
    const tracks = Array.isArray(adventure.tracks) ? adventure.tracks : [];
    loadQueue(tracks, `Adventure: ${trackLabel(state.adventureFrom)} to ${trackLabel(state.adventureTo)}`, true);
  });
}

function loadQueue(tracks, label, autoPlay, startIndex = 0) {
  state.originalQueue = Array.isArray(tracks) ? [...tracks] : [];
  state.queue = state.shuffle ? shuffleArray(state.originalQueue) : [...state.originalQueue];
  state.currentIndex = state.queue.length ? clamp(startIndex, 0, state.queue.length - 1) : -1;
  if (!state.queue.length) {
    audio.pause();
    audio.removeAttribute("src");
  }
  renderQueue();
  renderPlayer();
  setMessage(state.queue.length ? `${label}: ${state.queue.length} tracks.` : label);
  if (autoPlay && state.currentIndex >= 0) {
    playIndex(state.currentIndex);
  }
}

function renderQueue() {
  queueList.replaceChildren(...state.queue.map((track, index) => queueRow(track, index, () => playIndex(index))));
}

function queueRow(track, index, onClick) {
  const row = document.createElement("li");
  row.className = `queue-row${index === state.currentIndex ? " active" : ""}`;
  row.addEventListener("click", onClick);
  row.append(trackText(track));
  return row;
}

async function playIndex(index) {
  if (!state.queue[index]) return;
  state.currentIndex = index;
  const track = state.queue[index];
  audio.src = streamUrl(track.id);
  seekBar.value = "0";
  timeElapsed.textContent = "0:00";
  timeDuration.textContent = "0:00";
  renderQueue();
  renderPlayer();
  updateMediaSession(track);
  try {
    await audio.play();
  } catch (err) {
    // Browsers can reject play() when it follows a network await (the user
    // gesture has "expired") - the queue is loaded, so prompt to hit play.
    setMessage("Queue ready - tap play to start playback.");
  }
}

function renderPlayer() {
  const track = state.queue[state.currentIndex];
  playButton.textContent = audio.paused ? "▶" : "⏸";
  playButton.setAttribute("aria-label", audio.paused ? "Play" : "Pause");
  prevButton.disabled = state.currentIndex <= 0;
  nextButton.disabled = state.currentIndex < 0 || state.currentIndex >= state.queue.length - 1;
  nowSimilarButton.disabled = !track;
  if (!track) {
    nowTitle.textContent = "No track loaded";
    nowSubtitle.textContent = "No radio queue yet.";
    artwork.classList.add("hidden");
    artwork.removeAttribute("src");
    seekBar.value = "0";
    timeElapsed.textContent = "0:00";
    timeDuration.textContent = "0:00";
    return;
  }
  nowTitle.textContent = track.title || "Untitled track";
  nowSubtitle.textContent = [track.artist, track.album].filter(Boolean).join(" - ");
  artwork.src = artworkUrl(track.id);
  artwork.classList.remove("hidden");
}

function renderShuffle() {
  shuffleButton.classList.toggle("active", state.shuffle);
  shuffleButton.setAttribute("aria-label", state.shuffle ? "Shuffle on" : "Shuffle off");
  shuffleButton.title = state.shuffle ? "Shuffle on" : "Shuffle off";
}

function renderRepeat() {
  repeatButton.classList.toggle("active", state.repeat !== "none");
  repeatButton.textContent = state.repeat === "one" ? "↻\xb9" : "↻";
  const labels = { none: "Repeat off", one: "Repeat one", all: "Repeat all" };
  repeatButton.setAttribute("aria-label", labels[state.repeat]);
  repeatButton.title = labels[state.repeat];
}

function trackButton(track, onClick, label) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "track-main";
  button.setAttribute("aria-label", label);
  button.addEventListener("click", onClick);
  button.append(trackText(track));
  return button;
}

function actionButton(text, onClick) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "secondary-button";
  button.textContent = text;
  button.addEventListener("click", () => {
    const result = onClick();
    if (result?.catch) {
      result.catch((error) => {
        setMessage(error instanceof Error ? error.message : String(error));
      });
    }
  });
  return button;
}

function trackText(track) {
  const wrap = document.createElement("span");
  const title = document.createElement("span");
  title.className = "track-title";
  title.textContent = track.title || "Untitled track";
  const meta = document.createElement("span");
  meta.className = "track-meta";
  const dur = track.duration_ms ? formatTime(track.duration_ms / 1000) : null;
  meta.textContent = [track.artist, track.album, dur].filter(Boolean).join(" · ");
  wrap.append(title, meta);
  return wrap;
}

function emptyState(text) {
  const li = document.createElement("li");
  li.className = "empty-state";
  li.textContent = text;
  return li;
}

function formatTime(seconds) {
  if (!seconds || !isFinite(seconds)) return "0:00";
  const m = Math.floor(seconds / 60);
  const s = Math.floor(seconds % 60);
  return `${m}:${s.toString().padStart(2, "0")}`;
}

function shuffleArray(arr) {
  const copy = [...arr];
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy;
}

function trackLabel(track) {
  return [track.title || "Untitled track", track.artist].filter(Boolean).join(" - ");
}

function mixName(mix) {
  return mix?.name || "Untitled mix";
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function playSessionId() {
  // crypto.randomUUID() is secure-context-only (HTTPS / localhost) and is
  // undefined over plain http on a LAN IP - the MVP's intended deployment.
  // PlaySessionId only needs to be unique per playback, not cryptographic.
  if (globalThis.crypto?.randomUUID) return crypto.randomUUID();
  return `ps-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

function streamUrl(itemId) {
  const base = state.session.serverUrl.replace(/\/$/, "");
  const params = new URLSearchParams({
    UserId: state.session.userId,
    MaxStreamingBitrate: "140000000",
    Container: "mp3,aac,m4a,mp4,m4b,flac,webma,webm,wav,ogg",
    AudioCodec: "mp3,aac,flac,vorbis,opus",
    TranscodingContainer: "mp3",
    TranscodingProtocol: "http",
    PlaySessionId: playSessionId(),
    api_key: state.session.token,
  });
  return `${base}/Audio/${encodeURIComponent(itemId)}/universal?${params}`;
}

function artworkUrl(itemId) {
  const base = state.session.serverUrl.replace(/\/$/, "");
  const params = new URLSearchParams({ api_key: state.session.token });
  return `${base}/Items/${encodeURIComponent(itemId)}/Images/Primary?${params}`;
}

function updateMediaSession(track) {
  if (!("mediaSession" in navigator) || !track) return;
  navigator.mediaSession.metadata = new MediaMetadata({
    title: track.title || "Untitled track",
    artist: track.artist || "",
    album: track.album || "",
    artwork: [
      { src: artworkUrl(track.id), sizes: "512x512", type: "image/jpeg" },
    ],
  });
  navigator.mediaSession.setActionHandler("play", () => audio.play());
  navigator.mediaSession.setActionHandler("pause", () => audio.pause());
  navigator.mediaSession.setActionHandler("previoustrack", () => playIndex(Math.max(0, state.currentIndex - 1)));
  navigator.mediaSession.setActionHandler("nexttrack", () => playIndex(Math.min(state.queue.length - 1, state.currentIndex + 1)));
  navigator.mediaSession.setActionHandler("seekto", (details) => {
    if (details.seekTime != null) {
      audio.currentTime = details.seekTime;
    }
  });
}

async function authedFetch(url, options = {}) {
  if (!state.session?.token) throw new Error("Not signed in");
  return fetch(url, {
    ...options,
    headers: {
      ...(options.headers || {}),
      "X-Emby-Token": state.session.token,
      "X-Emby-User-Id": state.session.userId,
    },
  });
}

async function parseJson(resp) {
  const data = await resp.json().catch(() => ({}));
  if (!resp.ok) {
    throw new Error(data.detail || `Request failed (${resp.status})`);
  }
  return data;
}

async function withBusy(text, task) {
  setMessage(text);
  try {
    await task();
  } catch (error) {
    setMessage(error instanceof Error ? error.message : String(error));
  }
}

function setMessage(text) {
  message.textContent = text;
}

// TODO(webapp): library browse, save-mix-as-Emby-playlist, audiobooks,
// artist/album-similar screens, service-worker installability, and TLS
// hardening are intentionally out of this focused sonic-features slice.
