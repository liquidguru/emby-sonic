const STORAGE_KEY = "embySonic.webSession";

// ── DOM refs ─────────────────────────────────────────────

const loginView       = document.querySelector("#loginView");
const loginForm       = document.querySelector("#loginForm");
const appView         = document.querySelector("#appView");
const logoutButton    = document.querySelector("#logoutButton");
const navButtons      = [...document.querySelectorAll(".nav-btn")];

// Home
const mixesRow        = document.querySelector("#mixesRow");
const recentRow       = document.querySelector("#recentRow");
const refreshMixesButton = document.querySelector("#refreshMixesButton");
const decadePicker    = document.querySelector("#decadePicker");

// Search
const searchForm      = document.querySelector("#searchForm");
const searchInput     = document.querySelector("#searchInput");
const resultsList     = document.querySelector("#resultsList");
const similarPanel    = document.querySelector("#similarPanel");
const similarTitle    = document.querySelector("#similarTitle");
const similarList     = document.querySelector("#similarList");

// Adventure
const adventureStartForm    = document.querySelector("#adventureStartForm");
const adventureEndForm      = document.querySelector("#adventureEndForm");
const adventureBuildForm    = document.querySelector("#adventureBuildForm");
const adventureStartInput   = document.querySelector("#adventureStartInput");
const adventureEndInput     = document.querySelector("#adventureEndInput");
const adventureLengthInput  = document.querySelector("#adventureLengthInput");
const adventureStartChoice  = document.querySelector("#adventureStartChoice");
const adventureEndChoice    = document.querySelector("#adventureEndChoice");
const adventureStartResults = document.querySelector("#adventureStartResults");
const adventureEndResults   = document.querySelector("#adventureEndResults");

// Mini player
const miniPlayer      = document.querySelector("#miniPlayer");
const miniOpenNowPlaying = document.querySelector("#miniOpenNowPlaying");
const miniArtwork     = document.querySelector("#miniArtwork");
const miniTitle       = document.querySelector("#miniTitle");
const miniArtist      = document.querySelector("#miniArtist");
const miniPrevButton  = document.querySelector("#miniPrevButton");
const miniPlayButton  = document.querySelector("#miniPlayButton");
const miniNextButton  = document.querySelector("#miniNextButton");
const miniProgressFill = document.querySelector("#miniProgressFill");

// Now Playing overlay
const npOverlay       = document.querySelector("#nowPlayingOverlay");
const closeNowPlaying = document.querySelector("#closeNowPlaying");
const npArtwork       = document.querySelector("#npArtwork");
const npTitle         = document.querySelector("#npTitle");
const npSubtitle      = document.querySelector("#npSubtitle");
const seekBar         = document.querySelector("#seekBar");
const timeElapsed     = document.querySelector("#timeElapsed");
const timeDuration    = document.querySelector("#timeDuration");
const shuffleButton   = document.querySelector("#shuffleButton");
const prevButton      = document.querySelector("#prevButton");
const playButton      = document.querySelector("#playButton");
const nextButton      = document.querySelector("#nextButton");
const repeatButton    = document.querySelector("#repeatButton");
const nowSimilarButton = document.querySelector("#nowSimilarButton");
const queueList       = document.querySelector("#queueList");

const audio           = document.querySelector("#audio");
const messageEl       = document.querySelector("#message");

// ── State ────────────────────────────────────────────────

const state = {
  session: loadSession(),
  activeView: "home",
  queue: [],
  originalQueue: [],
  currentIndex: -1,
  shuffle: false,
  repeat: "none",   // "none" | "one" | "all"
  mixes: [],
  adventureFrom: null,
  adventureTo: null,
  nowPlayingOpen: false,
};

// ── Boot ─────────────────────────────────────────────────

renderSession();
renderShuffle();
renderRepeat();

// ── Auth ─────────────────────────────────────────────────

loginForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  const form = new FormData(loginForm);
  await withBusy("Logging in…", async () => {
    const resp = await fetch("/sonic/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: form.get("username"), password: form.get("password") }),
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
    setMessage(`Signed in as ${state.session.userName || "user"}.`);
  });
});

logoutButton.addEventListener("click", () => {
  localStorage.removeItem(STORAGE_KEY);
  Object.assign(state, {
    session: null,
    queue: [], originalQueue: [], currentIndex: -1,
    shuffle: false, repeat: "none",
    mixes: [], adventureFrom: null, adventureTo: null,
  });
  audio.pause();
  audio.removeAttribute("src");
  renderSession();
  renderMini();
  renderNowPlaying();
  renderShuffle();
  renderRepeat();
  setMessage("");
});

// ── Navigation ───────────────────────────────────────────

navButtons.forEach((btn) => {
  btn.addEventListener("click", () => switchView(btn.dataset.view));
});

async function switchView(view) {
  state.activeView = view;
  navButtons.forEach((btn) => btn.classList.toggle("active", btn.dataset.view === view));
  document.querySelectorAll(".view").forEach((v) => v.classList.toggle("hidden", v.id !== `${view}View`));
  if (view === "home") loadHomeData();
}

// ── Home ─────────────────────────────────────────────────

async function loadHomeData() {
  if (!state.session) return;
  loadMixes(false);
  loadRecentPlays();
}

// Station cards
document.querySelectorAll(".station-card").forEach((btn) => {
  btn.addEventListener("click", () => {
    const station = btn.dataset.station;
    if (station === "decade") {
      decadePicker.classList.toggle("hidden");
    } else {
      playStation(station);
    }
  });
});

document.querySelectorAll(".decade-btn").forEach((btn) => {
  btn.addEventListener("click", () => {
    decadePicker.classList.add("hidden");
    playDecadeRadio(Number(btn.dataset.decade));
  });
});

refreshMixesButton.addEventListener("click", () => loadMixes(true));

async function playStation(type) {
  if (type === "library") {
    await withBusy("Picking a track…", async () => {
      const items = await fetchEmbyItems({
        SortBy: "Random", Limit: 1,
        IncludeItemTypes: "Audio", Recursive: true,
      });
      if (!items.length) { setMessage("No audio found in your library."); return; }
      await startRadio(items[0]);
    });
  } else if (type === "album") {
    await withBusy("Picking an album…", async () => {
      const albums = await fetchEmbyItems({
        SortBy: "Random", Limit: 1,
        IncludeItemTypes: "MusicAlbum", Recursive: true,
      });
      if (!albums.length) { setMessage("No albums found."); return; }
      const album = albums[0];
      const tracks = await fetchEmbyItems({
        ParentId: album.id,
        IncludeItemTypes: "Audio",
        SortBy: "ParentIndexNumber,IndexNumber",
        Fields: "RunTimeTicks",
      });
      if (!tracks.length) { setMessage("Album has no tracks."); return; }
      loadQueue(tracks, `Album: ${album.title}`, true);
    });
  }
}

async function playDecadeRadio(decade) {
  const years = Array.from({ length: 10 }, (_, i) => decade + i).join(",");
  await withBusy(`Loading ${decade}s radio…`, async () => {
    const tracks = await fetchEmbyItems({
      Years: years, SortBy: "Random", Limit: 50,
      IncludeItemTypes: "Audio", Recursive: true,
      Fields: "RunTimeTicks",
    });
    if (!tracks.length) { setMessage(`No tracks from the ${decade}s found.`); return; }
    const seed = tracks[Math.floor(Math.random() * tracks.length)];
    const resp = await authedFetch(`/sonic/tracks/${encodeURIComponent(seed.id)}/radio`);
    const radio = await parseJson(resp);
    const radioTracks = Array.isArray(radio.tracks) ? radio.tracks : [];
    if (radioTracks.length) {
      loadQueue([seed, ...radioTracks.filter((t) => t.id !== seed.id)], `${decade}s Radio`, true);
    } else {
      loadQueue(tracks, `${decade}s Radio`, true);
    }
  });
}

// ── Mixes ────────────────────────────────────────────────

async function loadMixes(force) {
  if (state.mixes.length && !force) { renderMixRow(); return; }
  await withBusy("Loading mixes…", async () => {
    const resp = await authedFetch("/sonic/mixes");
    state.mixes = await parseJson(resp);
    renderMixRow();
    if (!state.mixes.length) setMessage("No mixes yet — run a worker to analyse your library.");
  });
}

function renderMixRow() {
  if (!state.mixes.length) {
    mixesRow.replaceChildren(emptyMsg("No mixes yet"));
    return;
  }
  mixesRow.replaceChildren(...state.mixes.map((mix) => {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "mix-card";

    const art = document.createElement("img");
    art.className = "mix-card-art";
    art.alt = "";
    if (mix.cover_track_id) art.src = artworkUrl(mix.cover_track_id);

    const name = document.createElement("span");
    name.className = "mix-card-name";
    name.textContent = mixName(mix);

    const meta = document.createElement("span");
    meta.className = "mix-card-meta";
    meta.textContent = `${mix.track_count} tracks`;

    btn.append(art, name, meta);
    btn.addEventListener("click", async () => {
      await withBusy(`Loading ${mixName(mix)}…`, async () => {
        const detail = await parseJson(await authedFetch(`/sonic/mixes/${encodeURIComponent(mix.id)}`));
        loadQueue(detail.tracks, `Mix: ${mixName(detail.mix)}`, true);
      });
    });
    return btn;
  }));
}

// ── Recent Plays ─────────────────────────────────────────

async function loadRecentPlays() {
  const tracks = await fetchEmbyItems({
    SortBy: "DatePlayed", SortOrder: "Descending",
    Filters: "IsPlayed", IncludeItemTypes: "Audio",
    Recursive: true, Limit: 20, Fields: "RunTimeTicks",
  }).catch(() => []);

  if (!tracks.length) {
    recentRow.replaceChildren(emptyMsg("No recent plays yet"));
    return;
  }
  recentRow.replaceChildren(...tracks.map((track) => {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "recent-card";

    const art = document.createElement("img");
    art.className = "recent-card-art";
    art.alt = "";
    art.src = artworkUrl(track.id);

    const title = document.createElement("span");
    title.className = "recent-card-title";
    title.textContent = track.title;

    const artist = document.createElement("span");
    artist.className = "recent-card-artist";
    artist.textContent = track.artist || "";

    btn.append(art, title, artist);
    btn.addEventListener("click", () => startRadio(track));
    return btn;
  }));
}

// ── Search ───────────────────────────────────────────────

searchForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  const query = searchInput.value.trim();
  if (!query) { resultsList.replaceChildren(); return; }
  await withBusy("Searching…", async () => {
    const tracks = await searchTracks(query);
    renderTrackList(resultsList, tracks, (t) => startRadio(t), true, true);
    setMessage(tracks.length ? `${tracks.length} tracks found.` : "No tracks found.");
  });
});

async function searchTracks(query, limit = 60) {
  return parseJson(await authedFetch(`/sonic/search/tracks?q=${encodeURIComponent(query)}&limit=${limit}`));
}

async function startRadio(seed) {
  await withBusy("Building radio…", async () => {
    const radio = await parseJson(await authedFetch(`/sonic/tracks/${encodeURIComponent(seed.id)}/radio`));
    const tracks = Array.isArray(radio.tracks) ? radio.tracks : [];
    if (!tracks.length) {
      loadQueue([seed], `Radio: ${seed.title || "track"}`, true);
      setMessage(`"${seed.title}" isn't analysed yet — playing the track on its own.`);
      return;
    }
    loadQueue([seed, ...tracks.filter((t) => t.id !== seed.id)], `Radio: ${seed.title || "track"}`, true);
  });
}

async function showSimilar(seed) {
  await switchView("search");
  await withBusy("Finding similar tracks…", async () => {
    const similar = await parseJson(await authedFetch(`/sonic/tracks/${encodeURIComponent(seed.id)}/similar?n=25`));
    similarTitle.textContent = seed.title || "track";
    similarPanel.classList.toggle("hidden", !similar.length);
    renderSimilarList(similar);
    setMessage(similar.length ? `${similar.length} similar tracks.` : "No similar tracks found.");
  });
}

function renderSimilarList(results) {
  if (!results.length) {
    similarList.replaceChildren(emptyMsg("No similar tracks found", "li"));
    return;
  }
  similarList.replaceChildren(...results.map((r) => {
    const track = r.track;
    const item = document.createElement("li");
    item.className = "track-item";

    const art = document.createElement("img");
    art.className = "track-art";
    art.alt = "";
    art.src = artworkUrl(track.id);

    const body = document.createElement("button");
    body.type = "button";
    body.className = "track-body";
    body.addEventListener("click", () => startRadio(track));
    body.append(nameEl(track.title), subEl([track.artist, track.album, formatTime(track.duration_ms / 1000)].filter(Boolean).join(" · ")));

    const score = document.createElement("span");
    score.className = "score-badge";
    score.textContent = `${Math.round((r.score || 0) * 100)}%`;

    const similar = smallBtn("Similar", () => showSimilar(track));

    item.append(art, body, score, similar);
    return item;
  }));
}

// ── Adventure ────────────────────────────────────────────

adventureStartForm.addEventListener("submit", (e) => { e.preventDefault(); searchPicker("from", adventureStartInput.value.trim()); });
adventureEndForm.addEventListener("submit", (e) => { e.preventDefault(); searchPicker("to", adventureEndInput.value.trim()); });
adventureBuildForm.addEventListener("submit", async (e) => { e.preventDefault(); await buildAdventure(); });

async function searchPicker(kind, query) {
  const list = kind === "from" ? adventureStartResults : adventureEndResults;
  if (!query) { list.replaceChildren(); return; }
  await withBusy("Searching…", async () => {
    const tracks = await searchTracks(query, 20);
    renderTrackList(list, tracks, (t) => choosePicker(kind, t), false);
  });
}

function choosePicker(kind, track) {
  if (kind === "from") { state.adventureFrom = track; adventureStartResults.replaceChildren(); }
  else { state.adventureTo = track; adventureEndResults.replaceChildren(); }
  adventureStartChoice.textContent = state.adventureFrom
    ? `Start: ${state.adventureFrom.title}` : "No start selected.";
  adventureEndChoice.textContent = state.adventureTo
    ? `End: ${state.adventureTo.title}` : "No end selected.";
}

async function buildAdventure() {
  if (!state.adventureFrom || !state.adventureTo) { setMessage("Choose a start and end track first."); return; }
  const length = clamp(Number.parseInt(adventureLengthInput.value, 10) || 20, 5, 100);
  adventureLengthInput.value = String(length);
  await withBusy("Building adventure…", async () => {
    const adventure = await parseJson(await authedFetch("/sonic/adventure", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ from_id: state.adventureFrom.id, to_id: state.adventureTo.id, length }),
    }));
    const tracks = Array.isArray(adventure.tracks) ? adventure.tracks : [];
    loadQueue(tracks, `Adventure: ${state.adventureFrom.title} → ${state.adventureTo.title}`, true);
  });
}

// ── Queue + playback ─────────────────────────────────────

function loadQueue(tracks, label, autoPlay, startIndex = 0) {
  state.originalQueue = Array.isArray(tracks) ? [...tracks] : [];
  state.queue = state.shuffle ? shuffleArray(state.originalQueue) : [...state.originalQueue];
  state.currentIndex = state.queue.length ? clamp(startIndex, 0, state.queue.length - 1) : -1;
  if (!state.queue.length) { audio.pause(); audio.removeAttribute("src"); }
  renderMini();
  renderNowPlaying();
  renderQueue();
  setMessage(state.queue.length ? `${label}: ${state.queue.length} tracks.` : label);
  if (autoPlay && state.currentIndex >= 0) playIndex(state.currentIndex);
}

async function playIndex(index) {
  if (!state.queue[index]) return;
  state.currentIndex = index;
  const track = state.queue[index];
  audio.src = streamUrl(track.id);
  seekBar.value = "0";
  timeElapsed.textContent = "0:00";
  timeDuration.textContent = "0:00";
  renderMini();
  renderNowPlaying();
  renderQueue();
  updateMediaSession(track);
  try { await audio.play(); }
  catch { setMessage("Queue ready — tap play to start."); }
}

function renderQueue() {
  queueList.replaceChildren(...state.queue.map((track, i) => {
    const row = document.createElement("li");
    row.className = `queue-row${i === state.currentIndex ? " active" : ""}`;
    row.addEventListener("click", () => playIndex(i));

    const name = document.createElement("span");
    name.className = "queue-track-name";
    name.textContent = track.title || "Untitled";

    const sub = document.createElement("span");
    sub.className = "queue-track-sub";
    sub.textContent = [track.artist, formatTime((track.duration_ms || 0) / 1000)].filter(Boolean).join(" · ");

    row.append(name, sub);
    return row;
  }));
}

// ── Mini player ──────────────────────────────────────────

function renderMini() {
  const track = state.queue[state.currentIndex];
  miniPlayer.classList.toggle("hidden", !track);
  if (!track) return;
  miniTitle.textContent = track.title || "Untitled";
  miniArtist.textContent = [track.artist, track.album].filter(Boolean).join(" · ");
  miniArtwork.src = artworkUrl(track.id);
  miniPlayButton.textContent = audio.paused ? "▶" : "⏸";
  miniPlayButton.setAttribute("aria-label", audio.paused ? "Play" : "Pause");
  miniPrevButton.disabled = state.currentIndex <= 0;
  miniNextButton.disabled = state.currentIndex >= state.queue.length - 1;
}

function renderMiniProgress() {
  if (!audio.duration) { miniProgressFill.style.width = "0%"; return; }
  miniProgressFill.style.width = `${(audio.currentTime / audio.duration) * 100}%`;
}

miniOpenNowPlaying.addEventListener("click", openNowPlaying);
miniPrevButton.addEventListener("click", () => playIndex(Math.max(0, state.currentIndex - 1)));
miniNextButton.addEventListener("click", () => playIndex(Math.min(state.queue.length - 1, state.currentIndex + 1)));
miniPlayButton.addEventListener("click", togglePlay);

// ── Now Playing overlay ──────────────────────────────────

function openNowPlaying() {
  state.nowPlayingOpen = true;
  npOverlay.classList.remove("hidden");
  requestAnimationFrame(() => npOverlay.classList.add("open"));
  renderNowPlaying();
  renderQueue();
}

function closeNP() {
  state.nowPlayingOpen = false;
  npOverlay.classList.remove("open");
  npOverlay.addEventListener("transitionend", () => npOverlay.classList.add("hidden"), { once: true });
}

closeNowPlaying.addEventListener("click", closeNP);
nowSimilarButton.addEventListener("click", () => {
  const track = state.queue[state.currentIndex];
  if (track) { closeNP(); showSimilar(track); }
});

function renderNowPlaying() {
  const track = state.queue[state.currentIndex];
  playButton.textContent = audio.paused ? "▶" : "⏸";
  playButton.setAttribute("aria-label", audio.paused ? "Play" : "Pause");
  prevButton.disabled = state.currentIndex <= 0;
  nextButton.disabled = state.currentIndex >= state.queue.length - 1;
  nowSimilarButton.disabled = !track;
  if (!track) {
    npTitle.textContent = "No track loaded";
    npSubtitle.textContent = "";
    npArtwork.removeAttribute("src");
    seekBar.value = "0";
    timeElapsed.textContent = "0:00";
    timeDuration.textContent = "0:00";
    return;
  }
  npTitle.textContent = track.title || "Untitled";
  npSubtitle.textContent = [track.artist, track.album].filter(Boolean).join(" · ");
  npArtwork.src = artworkUrl(track.id);
}

// Player controls
prevButton.addEventListener("click", () => playIndex(Math.max(0, state.currentIndex - 1)));
nextButton.addEventListener("click", () => playIndex(Math.min(state.queue.length - 1, state.currentIndex + 1)));
playButton.addEventListener("click", togglePlay);

shuffleButton.addEventListener("click", () => {
  state.shuffle = !state.shuffle;
  if (state.queue.length) {
    if (state.shuffle) {
      const current = state.queue[state.currentIndex];
      const rest = shuffleArray(state.queue.filter((_, i) => i !== state.currentIndex));
      state.queue = current ? [current, ...rest] : rest;
      state.currentIndex = current ? 0 : -1;
    } else {
      const currentId = state.queue[state.currentIndex]?.id;
      state.queue = [...state.originalQueue];
      state.currentIndex = currentId != null ? state.queue.findIndex((t) => t.id === currentId) : -1;
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

seekBar.addEventListener("input", () => {
  if (!audio.duration) return;
  audio.currentTime = (Number(seekBar.value) / 100) * audio.duration;
  timeElapsed.textContent = formatTime(audio.currentTime);
});

function renderShuffle() {
  shuffleButton.classList.toggle("active", state.shuffle);
  shuffleButton.setAttribute("aria-label", state.shuffle ? "Shuffle on" : "Shuffle off");
  shuffleButton.title = state.shuffle ? "Shuffle on" : "Shuffle off";
}

function renderRepeat() {
  repeatButton.classList.toggle("active", state.repeat !== "none");
  repeatButton.textContent = state.repeat === "one" ? "↻¹" : "↻";
  const labels = { none: "Repeat off", one: "Repeat one", all: "Repeat all" };
  repeatButton.setAttribute("aria-label", labels[state.repeat]);
  repeatButton.title = labels[state.repeat];
}

function togglePlay() {
  if (!audio.src && state.queue.length) { playIndex(Math.max(0, state.currentIndex)); return; }
  audio.paused ? audio.play() : audio.pause();
}

// ── Audio events ─────────────────────────────────────────

audio.addEventListener("play", () => { renderMini(); renderNowPlaying(); });
audio.addEventListener("pause", () => { renderMini(); renderNowPlaying(); });

audio.addEventListener("timeupdate", () => {
  if (!audio.duration || audio.seeking) return;
  const pct = (audio.currentTime / audio.duration) * 100;
  seekBar.value = String(pct);
  timeElapsed.textContent = formatTime(audio.currentTime);
  renderMiniProgress();
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
  if (state.repeat === "one") { audio.currentTime = 0; audio.play(); return; }
  if (state.currentIndex < state.queue.length - 1) {
    playIndex(state.currentIndex + 1);
  } else if (state.repeat === "all" && state.queue.length) {
    playIndex(0);
  } else {
    renderMini();
    renderNowPlaying();
  }
});

// ── Session ──────────────────────────────────────────────

function renderSession() {
  const signedIn = Boolean(state.session?.token);
  loginView.classList.toggle("hidden", signedIn);
  appView.classList.toggle("hidden", !signedIn);
  if (signedIn) {
    switchView("home");
  }
}

// ── Emby direct API ──────────────────────────────────────

async function fetchEmbyItems(params) {
  if (!state.session) return [];
  const base = state.session.serverUrl.replace(/\/$/, "");
  const userId = state.session.userId;
  const qs = new URLSearchParams({
    ...params,
    api_key: state.session.token,
    Fields: params.Fields || "RunTimeTicks",
  });
  const resp = await fetch(`${base}/Users/${encodeURIComponent(userId)}/Items?${qs}`);
  const data = await parseJson(resp);
  const items = Array.isArray(data.Items) ? data.Items : [];
  return items.map(embyItemToTrack);
}

function embyItemToTrack(item) {
  return {
    id: item.Id,
    title: item.Name || "Untitled",
    artist: item.AlbumArtist || (item.Artists && item.Artists[0]) || null,
    album: item.Album || null,
    duration_ms: item.RunTimeTicks ? Math.round(item.RunTimeTicks / 10000) : null,
  };
}

// ── Render helpers ───────────────────────────────────────

function renderTrackList(container, tracks, onPlay, showSimilarBtn, asList = false) {
  if (!tracks.length) { container.replaceChildren(emptyMsg("No results", asList ? "li" : "div")); return; }
  container.replaceChildren(...tracks.map((track) => {
    const item = document.createElement("li");
    item.className = "track-item";

    const art = document.createElement("img");
    art.className = "track-art";
    art.alt = "";
    art.src = artworkUrl(track.id);

    const body = document.createElement("button");
    body.type = "button";
    body.className = "track-body";
    body.addEventListener("click", () => onPlay(track));
    const dur = track.duration_ms ? formatTime(track.duration_ms / 1000) : null;
    body.append(
      nameEl(track.title),
      subEl([track.artist, track.album, dur].filter(Boolean).join(" · ")),
    );

    const actions = document.createElement("div");
    actions.className = "track-actions";
    actions.append(smallBtn("Radio", () => startRadio(track)));
    if (showSimilarBtn) actions.append(smallBtn("Similar", () => showSimilar(track)));

    item.append(art, body, actions);
    return item;
  }));
}

function nameEl(text) {
  const el = document.createElement("span");
  el.className = "track-name";
  el.textContent = text || "Untitled";
  return el;
}

function subEl(text) {
  const el = document.createElement("span");
  el.className = "track-sub";
  el.textContent = text;
  return el;
}

function smallBtn(text, onClick) {
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "secondary-btn";
  btn.textContent = text;
  btn.addEventListener("click", (e) => {
    e.stopPropagation();
    const result = onClick();
    if (result?.catch) result.catch((err) => setMessage(err instanceof Error ? err.message : String(err)));
  });
  return btn;
}

function emptyMsg(text, tag = "div") {
  const el = document.createElement(tag);
  el.className = "empty-msg";
  el.textContent = text;
  return el;
}

function mixName(mix) { return mix?.name || "Untitled mix"; }

// ── Toast message ────────────────────────────────────────

let toastTimer = null;
function setMessage(text) {
  if (!text) { messageEl.classList.add("hidden"); return; }
  messageEl.textContent = text;
  messageEl.classList.remove("hidden");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => messageEl.classList.add("hidden"), 4000);
}

// ── URLs ─────────────────────────────────────────────────

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
  return `${base}/Items/${encodeURIComponent(itemId)}/Images/Primary?api_key=${state.session.token}`;
}

// ── Media Session ─────────────────────────────────────────

function updateMediaSession(track) {
  if (!("mediaSession" in navigator) || !track) return;
  navigator.mediaSession.metadata = new MediaMetadata({
    title: track.title || "Untitled",
    artist: track.artist || "",
    album: track.album || "",
    artwork: [{ src: artworkUrl(track.id), sizes: "512x512", type: "image/jpeg" }],
  });
  navigator.mediaSession.setActionHandler("play", () => audio.play());
  navigator.mediaSession.setActionHandler("pause", () => audio.pause());
  navigator.mediaSession.setActionHandler("previoustrack", () => playIndex(Math.max(0, state.currentIndex - 1)));
  navigator.mediaSession.setActionHandler("nexttrack", () => playIndex(Math.min(state.queue.length - 1, state.currentIndex + 1)));
  navigator.mediaSession.setActionHandler("seekto", (d) => { if (d.seekTime != null) audio.currentTime = d.seekTime; });
}

// ── Utils ─────────────────────────────────────────────────

function loadSession() {
  try { return JSON.parse(localStorage.getItem(STORAGE_KEY)); } catch { return null; }
}

function playSessionId() {
  if (globalThis.crypto?.randomUUID) return crypto.randomUUID();
  return `ps-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
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

function clamp(v, min, max) { return Math.max(min, Math.min(max, v)); }

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
  if (!resp.ok) throw new Error(data.detail || `Request failed (${resp.status})`);
  return data;
}

async function withBusy(text, task) {
  setMessage(text);
  try { await task(); }
  catch (err) { setMessage(err instanceof Error ? err.message : String(err)); }
}
