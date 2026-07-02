const STORAGE_KEY = "embySonic.webSession";

// ── DOM refs ─────────────────────────────────────────────

const loginView       = document.querySelector("#loginView");
const loginForm       = document.querySelector("#loginForm");
const appShell        = document.querySelector("#appShell");
const logoutButton    = document.querySelector("#logoutButton");
const greeting        = document.querySelector("#greeting");
const navButtons      = [...document.querySelectorAll(".nav-btn")];
const pageContent     = document.querySelector("#pageContent");

// Home
const mixesRow           = document.querySelector("#mixesRow");
const recentRow          = document.querySelector("#recentRow");
const refreshMixesButton = document.querySelector("#refreshMixesButton");
const decadePicker       = document.querySelector("#decadePicker");
const genrePicker        = document.querySelector("#genrePicker");
const genreLoading       = document.querySelector("#genreLoading");
const genreGrid          = document.querySelector("#genreGrid");

// Search
const searchForm      = document.querySelector("#searchForm");
const searchInput     = document.querySelector("#searchInput");
const resultsList     = document.querySelector("#resultsList");
const similarPanel    = document.querySelector("#similarPanel");
const similarTitle    = document.querySelector("#similarTitle");
const similarList     = document.querySelector("#similarList");

// Mixes view
const mixesList           = document.querySelector("#mixesList");
const mixDetailPanel      = document.querySelector("#mixDetailPanel");
const mixDetailArt        = document.querySelector("#mixDetailArt");
const mixDetailTitle      = document.querySelector("#mixDetailTitle");
const mixDetailCount      = document.querySelector("#mixDetailCount");
const playMixButton       = document.querySelector("#playMixButton");
const regenerateMixButton = document.querySelector("#regenerateMixButton");
const closeMixDetail      = document.querySelector("#closeMixDetail");
const mixTracksList       = document.querySelector("#mixTracksList");

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

// Artist Mix Creator
const artistSearchForm        = document.querySelector("#artistSearchForm");
const artistSearchInput       = document.querySelector("#artistSearchInput");
const artistResultsList       = document.querySelector("#artistResultsList");
const suggestedArtistsPanel   = document.querySelector("#suggestedArtistsPanel");
const suggestedArtistsList    = document.querySelector("#suggestedArtistsList");
const selectedArtistsWrap     = document.querySelector("#selectedArtistsWrap");
const selectedArtistChips     = document.querySelector("#selectedArtistChips");
const buildArtistMixButton    = document.querySelector("#buildArtistMixButton");

// Mini player
const miniPlayer         = document.querySelector("#miniPlayer");
const miniOpenNowPlaying = document.querySelector("#miniOpenNowPlaying");
const miniArtwork        = document.querySelector("#miniArtwork");
const miniTitle          = document.querySelector("#miniTitle");
const miniArtist         = document.querySelector("#miniArtist");
const miniPrevButton     = document.querySelector("#miniPrevButton");
const miniPlayButton     = document.querySelector("#miniPlayButton");
const miniNextButton     = document.querySelector("#miniNextButton");
const miniProgressFill   = document.querySelector("#miniProgressFill");
const miniProgressTrack  = document.querySelector(".mini-progress-track");

// Now Playing overlay
const npOverlay        = document.querySelector("#nowPlayingOverlay");
const closeNowPlaying  = document.querySelector("#closeNowPlaying");
const npArtwork        = document.querySelector("#npArtwork");
const npTitle          = document.querySelector("#npTitle");
const npSubtitle       = document.querySelector("#npSubtitle");
const seekBar          = document.querySelector("#seekBar");
const timeElapsed      = document.querySelector("#timeElapsed");
const timeDuration     = document.querySelector("#timeDuration");
const shuffleButton    = document.querySelector("#shuffleButton");
const prevButton       = document.querySelector("#prevButton");
const playButton       = document.querySelector("#playButton");
const nextButton       = document.querySelector("#nextButton");
const repeatButton     = document.querySelector("#repeatButton");
const nowSimilarButton = document.querySelector("#nowSimilarButton");
const queueList        = document.querySelector("#queueList");

const audio      = document.querySelector("#audio");
const messageEl  = document.querySelector("#message");

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
  selectedArtists: [],  // for artist mix creator: [{ id, name }]
  genresLoaded: false,
  activeMixId: null,
  pendingMixDetail: null,
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
    selectedArtists: [], genresLoaded: false,
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

// Back buttons in subviews (adventure, artistMixView)
document.querySelectorAll(".back-btn[data-back]").forEach((btn) => {
  btn.addEventListener("click", () => switchView(btn.dataset.back));
});

async function switchView(view) {
  state.activeView = view;
  // Only highlight nav for top-level nav views
  const navViews = ["home", "search", "mixes"];
  navButtons.forEach((btn) => btn.classList.toggle("active", btn.dataset.view === view));
  document.querySelectorAll(".view").forEach((v) => v.classList.toggle("hidden", v.id !== `${view}View`));
  pageContent.scrollTop = 0;
  if (view === "home") loadHomeData();
  if (view === "mixes") loadMixesView();
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
      const showing = !decadePicker.classList.contains("hidden");
      decadePicker.classList.toggle("hidden", showing);
      genrePicker.classList.add("hidden");
      btn.classList.toggle("active", !showing);
      return;
    }
    if (station === "genres") {
      const showing = !genrePicker.classList.contains("hidden");
      genrePicker.classList.toggle("hidden", showing);
      decadePicker.classList.add("hidden");
      btn.classList.toggle("active", !showing);
      if (!showing && !state.genresLoaded) loadGenres();
      return;
    }
    if (station === "adventure") { switchView("adventure"); return; }
    if (station === "artistmix") { switchView("artistMix"); return; }

    // Close pickers when picking library/album
    decadePicker.classList.add("hidden");
    genrePicker.classList.add("hidden");
    playStation(station);
  });
});

document.querySelectorAll(".decade-btn").forEach((btn) => {
  btn.addEventListener("click", () => {
    decadePicker.classList.add("hidden");
    document.querySelector('.station-card[data-station="decade"]')?.classList.remove("active");
    playDecadeRadio(Number(btn.dataset.decade));
  });
});

refreshMixesButton.addEventListener("click", () => loadMixes(true));

// ── Stations: Library / Album ─────────────────────────────

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

// ── Genres station ────────────────────────────────────────

async function loadGenres() {
  state.genresLoaded = true;
  genreLoading.classList.remove("hidden");
  genreGrid.replaceChildren();
  try {
    const base = state.session.serverUrl.replace(/\/$/, "");
    const qs = new URLSearchParams({ api_key: state.session.token, SortBy: "SortName", Limit: 100 });
    const resp = await fetch(`${base}/MusicGenres?${qs}`);
    const data = await parseJson(resp);
    const genres = Array.isArray(data.Items) ? data.Items : [];
    genreLoading.classList.add("hidden");
    if (!genres.length) { genreGrid.appendChild(emptyMsg("No genres found")); return; }
    genreGrid.replaceChildren(...genres.map((g) => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "genre-btn";
      btn.textContent = g.Name;
      btn.addEventListener("click", () => {
        genrePicker.classList.add("hidden");
        document.querySelector('.station-card[data-station="genres"]')?.classList.remove("active");
        playGenreRadio(g.Name);
      });
      return btn;
    }));
  } catch (err) {
    genreLoading.classList.add("hidden");
    genreGrid.appendChild(emptyMsg("Failed to load genres"));
    state.genresLoaded = false;
  }
}

async function playGenreRadio(genre) {
  await withBusy(`Loading ${genre} radio…`, async () => {
    const tracks = await fetchEmbyItems({
      Genres: genre, SortBy: "Random", Limit: 50,
      IncludeItemTypes: "Audio", Recursive: true,
      Fields: "RunTimeTicks",
    });
    if (!tracks.length) { setMessage(`No ${genre} tracks found.`); return; }
    const seed = tracks[Math.floor(Math.random() * tracks.length)];
    const resp = await authedFetch(`/sonic/tracks/${encodeURIComponent(seed.id)}/radio`);
    const radio = await parseJson(resp);
    const radioTracks = Array.isArray(radio.tracks) ? radio.tracks : [];
    if (radioTracks.length) {
      loadQueue([seed, ...radioTracks.filter((t) => t.id !== seed.id)], `${genre} Radio`, true);
    } else {
      loadQueue(tracks, `${genre} Radio`, true);
    }
  });
}

// ── Mixes (home shelf row) ────────────────────────────────

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
  mixesRow.replaceChildren(...state.mixes.map((mix) => makeShelfCard({
    id: mix.cover_track_id,
    title: mixName(mix),
    sub: `${mix.track_count} tracks`,
    onPlay: () => openMixDetailFromHome(mix),
    onClick: () => openMixDetailFromHome(mix),
  })));
  makeDraggable(mixesRow);
}

// ── Recent Plays (home shelf row) ─────────────────────────

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
  recentRow.replaceChildren(...tracks.map((track) => makeShelfCard({
    id: track.id,
    title: track.title,
    sub: track.artist || track.album || "",
    onPlay: () => startRadio(track),
    onClick: () => startRadio(track),
  })));
  makeDraggable(recentRow);
}

// ── Shared shelf card builder ─────────────────────────────

function makeShelfCard({ id, title, sub, onPlay, onClick }) {
  const card = document.createElement("div");
  card.className = "shelf-card";

  const artWrap = document.createElement("div");
  artWrap.className = "shelf-card-art-wrap";

  if (id) {
    const img = document.createElement("img");
    img.alt = "";
    img.src = artworkUrl(id);
    img.onerror = () => { img.replaceWith(makePlaceholder()); };
    artWrap.appendChild(img);
  } else {
    artWrap.appendChild(makePlaceholder());
  }

  const playOverlay = document.createElement("button");
  playOverlay.type = "button";
  playOverlay.className = "play-overlay";
  playOverlay.setAttribute("aria-label", `Play ${title}`);
  playOverlay.innerHTML = `<svg viewBox="0 0 24 24" fill="currentColor" width="16" height="16"><path d="M8 5v14l11-7z"/></svg>`;
  playOverlay.addEventListener("click", (e) => { e.stopPropagation(); const r = onPlay(); if (r?.catch) r.catch((err) => setMessage(err.message)); });

  artWrap.appendChild(playOverlay);

  const meta = document.createElement("div");
  meta.className = "shelf-card-meta";
  const titleEl = document.createElement("span");
  titleEl.className = "shelf-card-title";
  titleEl.textContent = title || "Untitled";
  const subEl = document.createElement("span");
  subEl.className = "shelf-card-sub";
  subEl.textContent = sub || "";
  meta.append(titleEl, subEl);

  card.append(artWrap, meta);
  card.addEventListener("click", () => { const r = onClick(); if (r?.catch) r.catch((err) => setMessage(err.message)); });
  return card;
}

function makePlaceholder() {
  const ph = document.createElement("div");
  ph.className = "art-placeholder";
  ph.textContent = "♪";
  return ph;
}

// ── Mixes view (tab) ──────────────────────────────────────

function openMixDetailFromHome(mix) {
  state.pendingMixDetail = mix;
  switchView("mixes");
}

async function loadMixesView() {
  if (!state.session) return;
  if (!state.mixes.length) await loadMixes(false);
  if (state.pendingMixDetail) {
    const mix = state.pendingMixDetail;
    state.pendingMixDetail = null;
    mixesList.classList.add("hidden");
    await openMixDetail(mix);
  } else {
    mixDetailPanel.classList.add("hidden");
    mixesList.classList.remove("hidden");
    renderMixesList();
  }
}

function renderMixesList() {
  if (!state.mixes.length) {
    mixesList.replaceChildren(emptyMsg("No mixes yet — run a worker to analyse your library.", "li"));
    return;
  }
  mixesList.replaceChildren(...state.mixes.map((mix) => {
    const item = document.createElement("li");
    item.className = "track-item";
    item.addEventListener("click", () => openMixDetail(mix));

    if (mix.cover_track_id) {
      const img = document.createElement("img");
      img.className = "track-thumb";
      img.alt = "";
      img.src = artworkUrl(mix.cover_track_id);
      item.appendChild(img);
    } else {
      const thumb = document.createElement("div");
      thumb.className = "track-thumb-placeholder";
      thumb.textContent = "♪";
      item.appendChild(thumb);
    }

    const info = document.createElement("div");
    info.className = "track-info";
    const name = document.createElement("span");
    name.className = "track-name";
    name.textContent = mixName(mix);
    const meta = document.createElement("span");
    meta.className = "track-meta";
    meta.textContent = `${mix.track_count} tracks`;
    info.append(name, meta);

    item.appendChild(info);
    return item;
  }));
}

async function openMixDetail(mix) {
  state.activeMixId = mix.id;
  mixesList.classList.add("hidden");
  mixDetailPanel.classList.remove("hidden");
  mixDetailTitle.textContent = mixName(mix);
  mixDetailCount.textContent = `${mix.track_count} tracks`;
  if (mix.cover_track_id) mixDetailArt.src = artworkUrl(mix.cover_track_id);

  // Load track list
  mixTracksList.replaceChildren(emptyMsg("Loading…", "li"));
  try {
    const detail = await parseJson(await authedFetch(`/sonic/mixes/${encodeURIComponent(mix.id)}`));
    renderDetailTrackList(mixTracksList, detail.tracks || []);
  } catch (err) {
    mixTracksList.replaceChildren(emptyMsg("Failed to load tracks.", "li"));
  }
}

closeMixDetail.addEventListener("click", () => {
  mixDetailPanel.classList.add("hidden");
  mixesList.classList.remove("hidden");
  state.activeMixId = null;
});

playMixButton.addEventListener("click", () => {
  if (state.activeMixId) playMixById(state.activeMixId);
});

regenerateMixButton.addEventListener("click", async () => {
  if (!state.activeMixId) return;
  await withBusy("Regenerating mix…", async () => {
    await authedFetch(`/sonic/mixes/${encodeURIComponent(state.activeMixId)}/regenerate`, { method: "POST" });
    const detail = await parseJson(await authedFetch(`/sonic/mixes/${encodeURIComponent(state.activeMixId)}`));
    renderDetailTrackList(mixTracksList, detail.tracks || []);
    setMessage("Mix regenerated.");
    // Refresh mixes list
    const resp = await authedFetch("/sonic/mixes");
    state.mixes = await parseJson(resp);
    renderMixRow();
  });
});

async function playMixById(mixId) {
  await withBusy("Loading mix…", async () => {
    const detail = await parseJson(await authedFetch(`/sonic/mixes/${encodeURIComponent(mixId)}`));
    const mix = detail.mix || state.mixes.find((m) => m.id === mixId) || {};
    loadQueue(detail.tracks || [], `Mix: ${mixName(mix)}`, true);
  });
}

function renderDetailTrackList(container, tracks) {
  if (!tracks.length) { container.replaceChildren(emptyMsg("No tracks.", "li")); return; }
  container.replaceChildren(...tracks.map((track) => {
    const item = document.createElement("li");
    item.className = "track-item";
    item.addEventListener("click", () => startRadio(track));

    const thumb = document.createElement("div");
    thumb.className = "track-thumb-placeholder";
    thumb.textContent = "♪";
    const info = document.createElement("div");
    info.className = "track-info";
    const name = document.createElement("span");
    name.className = "track-name";
    name.textContent = track.title || "Untitled";
    const meta = document.createElement("span");
    meta.className = "track-meta";
    const dur = track.duration_ms ? formatTime(track.duration_ms / 1000) : null;
    meta.textContent = [track.artist, dur].filter(Boolean).join(" · ");
    info.append(name, meta);
    item.append(thumb, info);
    return item;
  }));
}

// ── Artist Mix Creator ────────────────────────────────────

artistSearchForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  const query = artistSearchInput.value.trim();
  if (!query) return;
  await withBusy("Searching artists…", async () => {
    const base = state.session.serverUrl.replace(/\/$/, "");
    const qs = new URLSearchParams({
      SearchTerm: query,
      IncludeItemTypes: "MusicArtist",
      Recursive: true,
      Limit: 20,
      api_key: state.session.token,
    });
    const resp = await fetch(`${base}/Users/${encodeURIComponent(state.session.userId)}/Items?${qs}`);
    const data = await parseJson(resp);
    const artists = Array.isArray(data.Items) ? data.Items : [];
    renderArtistResults(artists);
  });
});

function renderArtistResults(artists) {
  if (!artists.length) {
    artistResultsList.replaceChildren(emptyMsg("No artists found.", "li"));
    return;
  }
  artistResultsList.replaceChildren(...artists.map((a) => {
    const item = document.createElement("li");
    item.className = "track-item";

    const thumb = document.createElement("div");
    thumb.className = "track-thumb-placeholder";
    thumb.textContent = "♪";

    const info = document.createElement("div");
    info.className = "track-info";
    const name = document.createElement("span");
    name.className = "track-name";
    name.textContent = a.Name;
    info.appendChild(name);

    const addBtn = document.createElement("button");
    addBtn.type = "button";
    addBtn.className = "secondary-btn";
    addBtn.textContent = "+";
    addBtn.addEventListener("click", (e) => {
      e.stopPropagation();
      addArtistToMix({ id: a.Id, name: a.Name });
    });

    item.append(thumb, info, addBtn);
    item.addEventListener("click", () => addArtistToMix({ id: a.Id, name: a.Name }));
    return item;
  }));
}

function addArtistToMix(artist) {
  if (state.selectedArtists.some((a) => a.id === artist.id)) {
    setMessage(`${artist.name} is already in the mix.`);
    return;
  }
  state.selectedArtists.push(artist);
  artistResultsList.replaceChildren();
  artistSearchInput.value = "";
  renderArtistChips();
  discoverSimilarArtists(artist).catch(() => {});
}

async function discoverSimilarArtists(artist) {
  // Grab one track by this artist to seed the similarity search
  const tracks = await fetchEmbyItems({
    ArtistIds: artist.id,
    IncludeItemTypes: "Audio",
    Recursive: true,
    SortBy: "Random",
    Limit: 1,
  });
  if (!tracks.length) return;

  const similar = await parseJson(
    await authedFetch(`/sonic/tracks/${encodeURIComponent(tracks[0].id)}/similar?n=60`)
  );

  // Collect unique artist names not already in the mix
  const selectedNames = new Set(state.selectedArtists.map((a) => a.name.toLowerCase()));
  const seen = new Set();
  const suggestions = [];
  for (const r of (similar || [])) {
    const name = r.track?.artist;
    if (!name) continue;
    const key = name.toLowerCase();
    if (selectedNames.has(key) || seen.has(key)) continue;
    seen.add(key);
    suggestions.push(name);
    if (suggestions.length >= 12) break;
  }

  showSuggestedArtists(suggestions);
}

function showSuggestedArtists(names) {
  if (!names.length) { suggestedArtistsPanel.classList.add("hidden"); return; }
  suggestedArtistsPanel.classList.remove("hidden");
  suggestedArtistsList.replaceChildren(...names.map((name) => {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "suggested-artist-btn";
    btn.textContent = `+ ${name}`;
    btn.addEventListener("click", async () => {
      btn.disabled = true;
      // Look up the artist ID in Emby so we can fetch their tracks later
      const found = await findArtistByName(name);
      if (found) addArtistToMix(found);
      else setMessage(`Couldn't find "${name}" in your library.`);
    });
    return btn;
  }));
}

async function findArtistByName(name) {
  const base = state.session.serverUrl.replace(/\/$/, "");
  const qs = new URLSearchParams({
    SearchTerm: name,
    IncludeItemTypes: "MusicArtist",
    Recursive: true,
    Limit: 3,
    api_key: state.session.token,
  });
  const resp = await fetch(`${base}/Users/${encodeURIComponent(state.session.userId)}/Items?${qs}`);
  const data = await parseJson(resp);
  const items = Array.isArray(data.Items) ? data.Items : [];
  return items.length ? { id: items[0].Id, name: items[0].Name } : null;
}

function removeArtistFromMix(artistId) {
  state.selectedArtists = state.selectedArtists.filter((a) => a.id !== artistId);
  renderArtistChips();
}

function renderArtistChips() {
  selectedArtistsWrap.classList.toggle("hidden", !state.selectedArtists.length);
  selectedArtistChips.replaceChildren(...state.selectedArtists.map((a) => {
    const chip = document.createElement("div");
    chip.className = "artist-chip";
    chip.textContent = a.name;
    const x = document.createElement("button");
    x.type = "button";
    x.className = "artist-chip-remove";
    x.setAttribute("aria-label", `Remove ${a.name}`);
    x.textContent = "×";
    x.addEventListener("click", () => removeArtistFromMix(a.id));
    chip.appendChild(x);
    return chip;
  }));
}

buildArtistMixButton.addEventListener("click", async () => {
  if (!state.selectedArtists.length) return;
  await withBusy("Building artist mix…", async () => {
    const allTracks = [];
    for (const artist of state.selectedArtists) {
      const tracks = await fetchEmbyItems({
        ArtistIds: artist.id,
        IncludeItemTypes: "Audio",
        Recursive: true,
        SortBy: "Random",
        Limit: 40,
        Fields: "RunTimeTicks",
      });
      allTracks.push(...tracks);
    }
    if (!allTracks.length) { setMessage("No tracks found for selected artists."); return; }
    const shuffled = shuffleArray(allTracks);
    const label = state.selectedArtists.map((a) => a.name).join(", ");
    loadQueue(shuffled, `Artist Mix: ${label}`, true);
    state.selectedArtists = [];
    renderArtistChips();
    artistResultsList.replaceChildren();
    suggestedArtistsPanel.classList.add("hidden");
    suggestedArtistsList.replaceChildren();
    artistSearchInput.value = "";
    switchView("home");
  });
});

// ── Search ───────────────────────────────────────────────

searchForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  const query = searchInput.value.trim();
  if (!query) { resultsList.replaceChildren(); return; }
  await withBusy("Searching…", async () => {
    const tracks = await searchTracks(query);
    renderTrackList(resultsList, tracks, (t) => startRadio(t), true);
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

    const thumb = document.createElement("div");
    thumb.className = "track-thumb-placeholder";
    thumb.textContent = "♪";

    const info = document.createElement("div");
    info.className = "track-info";
    const name = document.createElement("span");
    name.className = "track-name";
    name.textContent = track.title || "Untitled";
    const meta = document.createElement("span");
    meta.className = "track-meta";
    meta.textContent = [track.artist, track.album, formatTime((track.duration_ms || 0) / 1000)].filter(Boolean).join(" · ");
    info.append(name, meta);

    const score = document.createElement("span");
    score.className = "track-duration";
    score.textContent = `${Math.round((r.score || 0) * 100)}%`;

    const simBtn = document.createElement("button");
    simBtn.type = "button";
    simBtn.className = "secondary-btn";
    simBtn.textContent = "Similar";
    simBtn.addEventListener("click", (e) => { e.stopPropagation(); showSimilar(track); });

    item.addEventListener("click", () => startRadio(track));
    item.append(thumb, info, score, simBtn);
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
    switchView("home");
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
    const item = document.createElement("li");
    item.className = `track-item${i === state.currentIndex ? " playing" : ""}`;
    item.addEventListener("click", () => playIndex(i));

    const thumb = document.createElement("div");
    thumb.className = "track-thumb-placeholder";
    thumb.textContent = "♪";

    const info = document.createElement("div");
    info.className = "track-info";
    const name = document.createElement("span");
    name.className = "track-name";
    name.textContent = track.title || "Untitled";
    const meta = document.createElement("span");
    meta.className = "track-meta";
    meta.textContent = [track.artist, formatTime((track.duration_ms || 0) / 1000)].filter(Boolean).join(" · ");
    info.append(name, meta);

    item.append(thumb, info);
    return item;
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
miniProgressTrack.addEventListener("click", (e) => {
  if (!audio.duration) return;
  const rect = miniProgressTrack.getBoundingClientRect();
  audio.currentTime = ((e.clientX - rect.left) / rect.width) * audio.duration;
});
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
  shuffleButton.classList.toggle("on", state.shuffle);
  shuffleButton.setAttribute("aria-label", state.shuffle ? "Shuffle on" : "Shuffle off");
  shuffleButton.title = state.shuffle ? "Shuffle on" : "Shuffle off";
}

function renderRepeat() {
  repeatButton.classList.toggle("on", state.repeat !== "none");
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
  appShell.classList.toggle("hidden", !signedIn);
  if (signedIn) {
    const name = state.session.userName || "there";
    const cap = name.charAt(0).toUpperCase() + name.slice(1).toLowerCase();
    greeting.textContent = `Hi, ${cap}`;
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

function renderTrackList(container, tracks, onPlay, showSimilarBtn) {
  if (!tracks.length) { container.replaceChildren(emptyMsg("No results", "li")); return; }
  container.replaceChildren(...tracks.map((track) => {
    const item = document.createElement("li");
    item.className = "track-item";

    const thumb = document.createElement("div");
    thumb.className = "track-thumb-placeholder";
    thumb.textContent = "♪";

    const info = document.createElement("div");
    info.className = "track-info";
    const name = document.createElement("span");
    name.className = "track-name";
    name.textContent = track.title || "Untitled";
    const dur = track.duration_ms ? formatTime(track.duration_ms / 1000) : null;
    const meta = document.createElement("span");
    meta.className = "track-meta";
    meta.textContent = [track.artist, track.album, dur].filter(Boolean).join(" · ");
    info.append(name, meta);

    const actions = document.createElement("div");
    if (showSimilarBtn) {
      const simBtn = document.createElement("button");
      simBtn.type = "button";
      simBtn.className = "secondary-btn";
      simBtn.textContent = "Similar";
      simBtn.addEventListener("click", (e) => { e.stopPropagation(); showSimilar(track); });
      actions.appendChild(simBtn);
    }

    item.append(thumb, info, actions);
    item.addEventListener("click", () => onPlay(track));
    return item;
  }));
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

// ── Drag-to-scroll for horizontal shelves ─────────────────

function makeDraggable(el) {
  // Guard: only attach once
  if (el._draggable) return;
  el._draggable = true;

  let startX = 0, startScroll = 0, active = false, moved = false;

  el.addEventListener("mousedown", (e) => {
    if (e.button !== 0) return;
    active = true;
    moved = false;
    startX = e.pageX;
    startScroll = el.scrollLeft;
    el.classList.add("dragging");
  });

  window.addEventListener("mousemove", (e) => {
    if (!active) return;
    const delta = e.pageX - startX;
    if (Math.abs(delta) > 4) moved = true;
    if (moved) el.scrollLeft = startScroll - delta;
  });

  window.addEventListener("mouseup", () => {
    if (!active) return;
    active = false;
    el.classList.remove("dragging");
  });

  el.addEventListener("click", (e) => {
    if (moved) { e.stopPropagation(); e.preventDefault(); moved = false; }
  }, true);
}

// Attach to static shelves on boot (dynamic ones attached after render)
document.querySelectorAll(".shelf-scroll").forEach(makeDraggable);
