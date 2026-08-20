const STORAGE_KEY = "embySonic.webSession";
const DEVICE_ID_STORAGE_KEY = "embySonic.browserDeviceId";
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

// Playback session reporting (Sessions/Playing*) — mirrors the Android app's
// PlaybackController so plays from the web app show up in Emby's Now Playing
// dashboard and count toward play history, instead of looking like a plain
// HTTP download. Matches Android's cadence/thresholds for parity.
const PROGRESS_REPORT_INTERVAL_MS = 3_000;
const RESUME_END_PADDING_MS = 5_000;
// A chapter counts as "in progress" only past this position, so a stray
// second or two of playback doesn't make the book resume mid-chapter.
const RESUME_MIN_POSITION_MS = 10_000;
let currentPlaySessionId = null;
let lastProgressReportMs = 0;

// ── DOM refs ─────────────────────────────────────────────

const loginView       = document.querySelector("#loginView");
const loginForm       = document.querySelector("#loginForm");
const appShell        = document.querySelector("#appShell");
const logoutButton    = document.querySelector("#logoutButton");
const settingsButton  = document.querySelector("#settingsButton");
const settingsUserName   = document.querySelector("#settingsUserName");
const settingsServerUrl  = document.querySelector("#settingsServerUrl");
const settingsStatusRows = document.querySelector("#settingsStatusRows");
const greeting        = document.querySelector("#greeting");
const navButtons      = [...document.querySelectorAll(".nav-btn")];
const pageContent     = document.querySelector("#pageContent");

// Home
const mixesRow           = document.querySelector("#mixesRow");
const recentRow          = document.querySelector("#recentRow");
const refreshMixesButton = document.querySelector("#refreshMixesButton");
const buildMixesButton = document.querySelector("#buildMixesButton");
const decadePicker       = document.querySelector("#decadePicker");
const genrePicker        = document.querySelector("#genrePicker");
const genreLoading       = document.querySelector("#genreLoading");
const genreGrid          = document.querySelector("#genreGrid");

// Search
const searchForm      = document.querySelector("#searchForm");
const searchInput     = document.querySelector("#searchInput");
const resultsList     = document.querySelector("#resultsList");
const searchFilters   = document.querySelector("#searchFilters");
const searchChips     = [...document.querySelectorAll(".search-chip")];
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
const artistMixCountInput     = document.querySelector("#artistMixCountInput");
const buildArtistMixButton    = document.querySelector("#buildArtistMixButton");

// Library
const libraryTabArtists     = document.querySelector("#libraryTabArtists");
const libraryTabPlaylists   = document.querySelector("#libraryTabPlaylists");
const libraryTabBooks       = document.querySelector("#libraryTabBooks");
const libraryArtistsPane    = document.querySelector("#libraryArtistsPane");
const libraryPlaylistsPane  = document.querySelector("#libraryPlaylistsPane");
const libraryBooksPane      = document.querySelector("#libraryBooksPane");
const libraryAlphaBar       = document.querySelector("#libraryAlphaBar");
const libraryBooksAlphaBar  = document.querySelector("#libraryBooksAlphaBar");
const libraryArtistsList    = document.querySelector("#libraryArtistsList");
const libraryPlaylistsList  = document.querySelector("#libraryPlaylistsList");
const libraryBooksList      = document.querySelector("#libraryBooksList");
const resumeBooksSection    = document.querySelector("#resumeBooksSection");
const resumeBooksRow        = document.querySelector("#resumeBooksRow");
const homeResumeShelf       = document.querySelector("#homeResumeShelf");
const homeResumeRow         = document.querySelector("#homeResumeRow");
const homeView              = document.querySelector("#homeView");
const recentShelf           = document.querySelector("#recentShelf");
const stationsShelf         = document.querySelector("#stationsShelf");
const mixesShelf            = document.querySelector("#mixesShelf");
const homeLayoutRows        = document.querySelector("#homeLayoutRows");
const libraryDetailBack     = document.querySelector("#libraryDetailBack");
const libraryDetailBackLabel = document.querySelector("#libraryDetailBackLabel");
const libraryDetailArt      = document.querySelector("#libraryDetailArt");
const libraryDetailArtPlaceholder = document.querySelector("#libraryDetailArtPlaceholder");
const libraryDetailEyebrow  = document.querySelector("#libraryDetailEyebrow");
const libraryDetailTitle    = document.querySelector("#libraryDetailTitle");
const libraryDetailSub      = document.querySelector("#libraryDetailSub");
const libraryDetailPlay     = document.querySelector("#libraryDetailPlay");
const libraryDetailShuffle  = document.querySelector("#libraryDetailShuffle");
const libraryDetailDelete   = document.querySelector("#libraryDetailDelete");
const libraryAlbumsGrid     = document.querySelector("#libraryAlbumsGrid");
const libraryDetailTracks   = document.querySelector("#libraryDetailTracks");

// Mini player
const miniPlayer         = document.querySelector("#miniPlayer");
const miniOpenNowPlaying = document.querySelector("#miniOpenNowPlaying");
const miniArtwork        = document.querySelector("#miniArtwork");
const miniTitle          = document.querySelector("#miniTitle");
const miniArtist         = document.querySelector("#miniArtist");
const miniPrevButton     = document.querySelector("#miniPrevButton");
const miniPlayButton     = document.querySelector("#miniPlayButton");
const miniNextButton     = document.querySelector("#miniNextButton");
const miniStopButton     = document.querySelector("#miniStopButton");
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
const savePlaylistButton = document.querySelector("#savePlaylistButton");
const speedButton      = document.querySelector("#speedButton");
const sleepButton      = document.querySelector("#sleepButton");
const sleepMenu        = document.querySelector("#sleepMenu");
const queueList        = document.querySelector("#queueList");

const audio      = document.querySelector("#audio");
const messageEl  = document.querySelector("#message");
const serverBanner = document.querySelector("#serverBanner");

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
  queueLabel: "",       // human label of the current queue (for Save as playlist)
  selectedArtists: [],  // for artist mix creator: [{ id, name }]
  genresLoaded: false,
  activeMixId: null,
  pendingMixDetail: null,
  musicParentIds: null,
  libraryTab: "artists",   // "artists" | "playlists"
  libraryArtists: null,    // cached AlbumArtists items
  libraryPlaylists: null,  // cached Playlist items (null = refetch)
  libraryStack: [],        // drill-down entries for the shared detail view
  libraryScrollTop: 0,     // list scroll position, restored when backing out
  libraryBooks: null,      // cached audiobook authors (null = refetch)
  audiobookLibId: undefined, // undefined = undetected, null = none, else id
  search: { tracks: [], albums: [], artists: [], filter: "tracks" },
  playbackRate: 1,         // audiobook speed; applied to <audio>.playbackRate
  sleepTimer: null,        // { mode: "time"|"chapter", endsAt?, } or null
  pendingSeekMs: 0,        // resume seek applied once on the next metadata load
};

// ── Boot ─────────────────────────────────────────────────
// The boot calls run at the very END of this file (after makeDraggable), so
// every module-level `let`/`const` (e.g. toastTimer) is initialized first.
// Booting here instead crashes on refresh when signed in: renderSession() →
// loadHomeData → loadMixes → setMessage touches `toastTimer` in its temporal
// dead zone (issue #32).

// ── Auth ─────────────────────────────────────────────────

loginForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  const form = new FormData(loginForm);
  await withBusy("Logging in…", async () => {
    const resp = await fetch("/sonic/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        username: form.get("username"),
        password: form.get("password"),
        device_id: browserDeviceId(),
      }),
    });
    const data = await parseJson(resp);
    state.session = {
      token: data.access_token,
      userId: data.user_id,
      userName: data.user_name,
      serverUrl: data.server_url,
      serverUrlExternal: data.server_url_external,
    };
    state.musicParentIds = null;
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state.session));
    loginForm.reset();
    renderSession();
    setMessage(`Signed in as ${state.session.userName || "user"}.`);
  });
});

logoutButton.addEventListener("click", () => {
  const track = state.queue[state.currentIndex];
  if (track && audio.src) reportStopped(track, audio.currentTime * 1000);
  localStorage.removeItem(STORAGE_KEY);
  Object.assign(state, {
    session: null,
    queue: [], originalQueue: [], currentIndex: -1,
    shuffle: false, repeat: "none",
    mixes: [], adventureFrom: null, adventureTo: null,
    selectedArtists: [], genresLoaded: false,
    musicParentIds: null,
    libraryTab: "artists", libraryArtists: null,
    libraryPlaylists: null, libraryStack: [],
    libraryBooks: null, audiobookLibId: undefined,
    search: { tracks: [], albums: [], artists: [], filter: "tracks" },
    playbackRate: 1, sleepTimer: null, pendingSeekMs: 0,
  });
  clearSleepTimer();
  resultsList.replaceChildren();
  searchFilters.classList.add("hidden");
  audio.pause();
  audio.removeAttribute("src");
  audio.playbackRate = 1;
  renderSession();
  renderMini();
  renderNowPlaying();
  renderShuffle();
  renderRepeat();
  renderSpeed();
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
  // Keep the Library nav item lit while inside its drill-down detail view.
  const highlightView = view === "libraryDetail" ? "library" : view;
  navButtons.forEach((btn) => btn.classList.toggle("active", btn.dataset.view === highlightView));
  document.querySelectorAll(".view").forEach((v) => v.classList.toggle("hidden", v.id !== `${view}View`));
  pageContent.scrollTop = 0;
  if (view === "home") loadHomeData();
  if (view === "mixes") loadMixesView();
  if (view === "library") {
    state.libraryStack = [];
    loadLibraryView();
  }
  if (view === "settings") loadSettingsView();
}

// ── Settings ─────────────────────────────────────────────

settingsButton.addEventListener("click", () => switchView("settings"));

async function loadSettingsView() {
  if (!state.session) return;
  settingsUserName.textContent = state.session.userName || "Unknown";
  settingsServerUrl.textContent = activeServerUrl();
  renderHomeLayoutEditor();
  settingsStatusRows.replaceChildren(emptyMsg("Loading status…"));
  try {
    const status = await parseJson(await authedFetch("/sonic/status"));
    const analysed = status.analysed_tracks ?? 0;
    const total = status.total_tracks ?? 0;
    const rows = [
      ["Tracks analysed", `${analysed.toLocaleString()} of ${total.toLocaleString()}`],
      ["Queued", (status.pending_tracks ?? 0).toLocaleString()],
      ["Failed (won't retry)", (status.failed_tracks ?? 0).toLocaleString()],
      ["Scan running", status.scan_running ? "Yes" : "No"],
      ["Index in sync", status.index_in_sync ? "Yes" : "No"],
    ];
    settingsStatusRows.replaceChildren(...rows.map(([label, value]) => {
      const row = document.createElement("div");
      row.className = "settings-row";
      const labelEl = document.createElement("span");
      labelEl.className = "settings-label";
      labelEl.textContent = label;
      const valueEl = document.createElement("span");
      valueEl.className = "settings-value";
      valueEl.textContent = value;
      row.append(labelEl, valueEl);
      return row;
    }));
  } catch (err) {
    settingsStatusRows.replaceChildren(
      emptyMsg("Coordinator status unavailable — is the service running?"),
    );
  }
}

// ── Home ─────────────────────────────────────────────────

// ── Home layout (show/hide + reorder sections) ───────────

const HOME_LAYOUT_KEY = "embySonic.homeLayout";
// id → { label, el }. Order here is the default order.
const HOME_SECTIONS = [
  { id: "resume", label: "Continue listening", el: () => homeResumeShelf },
  { id: "recent", label: "Recent plays", el: () => recentShelf },
  { id: "stations", label: "Stations", el: () => stationsShelf },
  { id: "mixes", label: "Sonic mixes", el: () => mixesShelf },
];

// Persisted [{ id, visible }] in the user's chosen order. Merges with
// HOME_SECTIONS so a new section added in a later release still appears
// (appended, visible) even for users with a saved layout.
function loadHomeLayout() {
  let saved = [];
  try { saved = JSON.parse(localStorage.getItem(HOME_LAYOUT_KEY)) || []; } catch { saved = []; }
  const known = new Map(HOME_SECTIONS.map((s) => [s.id, s]));
  const layout = [];
  for (const item of saved) {
    if (known.has(item.id)) { layout.push({ id: item.id, visible: item.visible !== false }); known.delete(item.id); }
  }
  for (const id of known.keys()) layout.push({ id, visible: true });
  return layout;
}

function saveHomeLayout(layout) {
  localStorage.setItem(HOME_LAYOUT_KEY, JSON.stringify(layout));
}

// Reorder the Home sections in the DOM and apply user hide/show. Content-based
// hiding (e.g. an empty Continue shelf) uses .hidden and composes with this.
function applyHomeLayout() {
  const layout = loadHomeLayout();
  const byId = new Map(HOME_SECTIONS.map((s) => [s.id, s.el()]));
  for (const { id, visible } of layout) {
    const el = byId.get(id);
    if (!el) continue;
    el.classList.toggle("home-hidden", !visible);
    homeView.appendChild(el);   // re-append in configured order (after tagline)
  }
}

async function loadHomeData() {
  if (!state.session) return;
  applyHomeLayout();
  loadMixes(false);
  loadRecentPlays();
  loadHomeResume();
}

// Settings → Home layout editor: a row per section with a show/hide toggle
// and up/down reordering.
function renderHomeLayoutEditor() {
  const layout = loadHomeLayout();
  const labels = new Map(HOME_SECTIONS.map((s) => [s.id, s.label]));
  homeLayoutRows.replaceChildren(...layout.map((item, i) => {
    const row = document.createElement("div");
    row.className = "home-layout-row";

    const moves = document.createElement("div");
    moves.className = "home-layout-moves";
    const up = document.createElement("button");
    up.type = "button"; up.className = "move-btn"; up.textContent = "▲";
    up.setAttribute("aria-label", "Move up");
    up.disabled = i === 0;
    up.addEventListener("click", () => reorderHome(i, i - 1));
    const down = document.createElement("button");
    down.type = "button"; down.className = "move-btn"; down.textContent = "▼";
    down.setAttribute("aria-label", "Move down");
    down.disabled = i === layout.length - 1;
    down.addEventListener("click", () => reorderHome(i, i + 1));
    moves.append(up, down);

    const label = document.createElement("span");
    label.className = "home-layout-label";
    label.textContent = labels.get(item.id) || item.id;

    const toggle = document.createElement("button");
    toggle.type = "button";
    toggle.className = `home-layout-toggle${item.visible ? " on" : ""}`;
    toggle.textContent = item.visible ? "Shown" : "Hidden";
    toggle.addEventListener("click", () => {
      const next = loadHomeLayout();
      next[i].visible = !next[i].visible;
      saveHomeLayout(next);
      renderHomeLayoutEditor();
    });

    row.append(moves, label, toggle);
    return row;
  }));
}

function reorderHome(from, to) {
  const layout = loadHomeLayout();
  if (to < 0 || to >= layout.length) return;
  const [moved] = layout.splice(from, 1);
  layout.splice(to, 0, moved);
  saveHomeLayout(layout);
  renderHomeLayoutEditor();
}

// "Continue listening" on Home — only shown when the server has an audiobooks
// library and something is in progress.
async function loadHomeResume() {
  if (state.audiobookLibId === undefined) {
    state.audiobookLibId = await detectAudiobookLibrary();
  }
  if (!state.audiobookLibId) { renderHomeResume([]); return; }
  renderHomeResume(await fetchResumeBooks());
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
      const items = await fetchMusicEmbyItems({
        SortBy: "Random", Limit: 1,
        IncludeItemTypes: "Audio", Recursive: true,
      });
      if (!items.length) { setMessage("No audio found in your library."); return; }
      await startRadio(items[Math.floor(Math.random() * items.length)]);
    });
  } else if (type === "album") {
    await withBusy("Picking an album…", async () => {
      const albums = await fetchMusicEmbyItems({
        SortBy: "Random", Limit: 1,
        IncludeItemTypes: "MusicAlbum", Recursive: true,
      });
      if (!albums.length) { setMessage("No albums found."); return; }
      const album = albums[Math.floor(Math.random() * albums.length)];
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
    const tracks = await fetchMusicEmbyItems({
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
    const genres = await fetchMusicGenres();
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
    const tracks = await fetchMusicEmbyItems({
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
  // fetchMusicEmbyRawItems fans out one query per music library, each already
  // Limit-capped and sorted server-side — so with more than one music library
  // the combined+deduped pool can exceed 20 (Limit is per-library, not
  // overall). Re-sort by actual last-played time and cap here rather than in
  // the shared helper, since a random-pick station relies on that helper
  // returning every library's full candidate pool, not a pre-truncated one.
  const items = await fetchMusicEmbyRawItems({
    SortBy: "DatePlayed", SortOrder: "Descending",
    Filters: "IsPlayed", IncludeItemTypes: "Audio",
    Recursive: true, Limit: 20, Fields: "RunTimeTicks,UserData",
  }).catch(() => []);
  const tracks = items
    .slice()
    .sort((a, b) => new Date(b?.UserData?.LastPlayedDate || 0) - new Date(a?.UserData?.LastPlayedDate || 0))
    .slice(0, 20)
    .map(embyItemToTrack);

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
    mixesList.replaceChildren(emptyMsg("No mixes yet — analyse your library, then tap Build mixes.", "li"));
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
    // The endpoint expects a JSON body — a bodyless POST 422s (which used to be
    // swallowed silently, so Regenerate appeared to do nothing).
    const mix = state.mixes.find((m) => m.id === state.activeMixId);
    const tracksPerMix = mix?.track_count || 50;
    await parseJson(await authedFetch(`/sonic/mixes/${encodeURIComponent(state.activeMixId)}/regenerate`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ tracks_per_mix: tracksPerMix }),
    }));
    const detail = await parseJson(await authedFetch(`/sonic/mixes/${encodeURIComponent(state.activeMixId)}`));
    renderDetailTrackList(mixTracksList, detail.tracks || []);
    setMessage("Mix regenerated.");
    // Refresh mixes list
    const resp = await authedFetch("/sonic/mixes");
    state.mixes = await parseJson(resp);
    renderMixRow();
  });
});

// Build the whole Sonic Mixes set from scratch (initial clustering). The Android
// app has always had this; the web app only regenerated individual mixes, so an
// iOS-only user on a fresh coordinator had no way to create the set (issue #44).
buildMixesButton.addEventListener("click", async () => {
  const existing = state.mixes.length;
  if (existing && !window.confirm(
    `This replaces all ${existing} existing Sonic Mixes with a fresh set. Continue?`,
  )) return;
  await withBusy("Building Sonic Mixes…", async () => {
    // Coordinator defaults, matching the Android app (30 mixes, 50 tracks each).
    const resp = await authedFetch("/sonic/library/build-mixes", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ n_clusters: 30, tracks_per_mix: 50 }),
    });
    if (resp.status === 409) {
      // Already running (e.g. triggered from Android) — just wait it out.
      setMessage("A mix build is already running — waiting for it to finish…");
    } else if (!resp.ok) {
      await parseJson(resp);   // throw with the coordinator's detail
    }
    await waitForBuildToFinish();
    state.mixes = await parseJson(await authedFetch("/sonic/mixes"));
    renderMixesList();
    renderMixRow();
    setMessage(state.mixes.length
      ? `Built ${state.mixes.length} Sonic Mixes.`
      : "Build finished, but no mixes were created — is your library analysed yet?");
  });
});

/**
 * Poll build-state until the background clustering finishes.
 *
 * build_mixes flips `running` true as its first step, but it runs as a background
 * task AFTER the POST returns — so for a beat build-state can still read false.
 * Don't trust a false as "done" until we've either seen it go true, or waited out
 * a short grace period (a small/fast library can finish before we ever catch it).
 */
async function waitForBuildToFinish() {
  const graceUntil = Date.now() + 4000;
  const deadline = Date.now() + 5 * 60 * 1000;
  let sawRunning = false;
  while (Date.now() < deadline) {
    const running = (await parseJson(await authedFetch("/sonic/library/build-state"))).running;
    if (running) sawRunning = true;
    else if (sawRunning || Date.now() > graceUntil) return;
    await delay(1500);
  }
  throw new Error("Mix build is still running — the list will update once it finishes.");
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

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
    const artists = await fetchMusicEmbyRawItems({
      SearchTerm: query,
      IncludeItemTypes: "MusicArtist",
      Recursive: true,
      Limit: 20,
    });
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
  const tracks = await fetchMusicEmbyItems({
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
  const items = await fetchMusicEmbyRawItems({
    SearchTerm: name,
    IncludeItemTypes: "MusicArtist",
    Recursive: true,
    Limit: 3,
  });
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
  const total = clamp(Number.parseInt(artistMixCountInput.value, 10) || 30, 5, 200);
  artistMixCountInput.value = String(total);
  await withBusy("Building artist mix…", async () => {
    // Draw an even-ish share from each artist so no single one dominates, then
    // pool + shuffle + cap at the requested total. Fetch a small buffer per
    // artist so we can still reach `total` if some artists are light.
    const perArtist = Math.ceil(total / state.selectedArtists.length);
    const allTracks = [];
    for (const artist of state.selectedArtists) {
      const tracks = await fetchMusicEmbyItems({
        ArtistIds: artist.id,
        IncludeItemTypes: "Audio",
        Recursive: true,
        SortBy: "Random",
        Limit: perArtist + 5,
        Fields: "RunTimeTicks",
      });
      allTracks.push(...tracks);
    }
    if (!allTracks.length) { setMessage("No tracks found for selected artists."); return; }
    const shuffled = shuffleArray(allTracks).slice(0, total);
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
  if (!query) {
    resultsList.replaceChildren();
    searchFilters.classList.add("hidden");
    return;
  }
  await withBusy("Searching…", async () => {
    if (state.audiobookLibId === undefined) {
      state.audiobookLibId = await detectAudiobookLibrary();
    }
    const ab = state.audiobookLibId;
    const [tracks, artists, albums, books, authors] = await Promise.all([
      searchTracks(query),
      fetchMusicEmbyRawItems({
        SearchTerm: query, IncludeItemTypes: "MusicArtist", Recursive: "true", Limit: "100",
      }).catch(() => []),
      fetchMusicEmbyRawItems({
        SearchTerm: query, IncludeItemTypes: "MusicAlbum", Recursive: "true", Limit: "100",
      }).catch(() => []),
      ab ? fetchEmbyRawItems({
        ParentId: ab, SearchTerm: query, IncludeItemTypes: "MusicAlbum", Recursive: "true", Limit: "100",
      }).catch(() => []) : Promise.resolve([]),
      ab ? fetchEmbyRawItems({
        ParentId: ab, SearchTerm: query, IncludeItemTypes: "MusicArtist", Recursive: "true", Limit: "100",
      }).catch(() => []) : Promise.resolve([]),
    ]);
    state.search = { tracks, albums, artists, books, authors, filter: "tracks" };
    searchFilters.classList.remove("hidden");
    // Book/author chips only when the server has an audiobooks library.
    document.querySelectorAll(".search-chip-book").forEach((c) => c.classList.toggle("hidden", !ab));
    setSearchFilter("tracks");
    const counts = [`${tracks.length} tracks`, `${albums.length} albums`, `${artists.length} artists`];
    if (ab) counts.push(`${books.length} books`, `${authors.length} authors`);
    const any = tracks.length || albums.length || artists.length || books.length || authors.length;
    setMessage(any ? counts.join(", ") : "No results.");
  });
});

// Chip row selects which result type fills the single list (Android pattern).
searchChips.forEach((chip) => {
  chip.addEventListener("click", () => setSearchFilter(chip.dataset.filter));
});

function setSearchFilter(filter) {
  state.search.filter = filter;
  searchChips.forEach((chip) => {
    const active = chip.dataset.filter === filter;
    chip.classList.toggle("active", active);
    chip.setAttribute("aria-selected", String(active));
  });
  renderSearchResults();
}

function renderSearchResults() {
  const { tracks, albums, artists, books, authors, filter } = state.search;
  if (filter === "tracks") {
    renderTrackList(resultsList, tracks, (t) => startRadio(t), true);
  } else if (filter === "artists") {
    renderCollectionRows(artists, {
      empty: "No artists found",
      glyph: (a) => (a.Name || "?").trim().charAt(0).toUpperCase() || "?",
      title: (a) => a.Name || "Unknown artist",
      meta: () => "Artist",
      onClick: (a) => openArtistDetail(a, "search"),
    });
  } else if (filter === "books") {
    renderCollectionRows(books || [], {
      empty: "No books found",
      glyph: () => "📖",
      title: (b) => b.Name || "Untitled book",
      meta: (b) => ["Book", b.AlbumArtist].filter(Boolean).join(" · "),
      onClick: (b) => openBookDetail(b, b.AlbumArtist, "search"),
    });
  } else if (filter === "authors") {
    renderCollectionRows(authors || [], {
      empty: "No authors found",
      glyph: () => "📖",
      title: (a) => a.Name || "Unknown author",
      meta: () => "Author",
      onClick: (a) => openAuthorDetail(a, "search"),
    });
  } else {
    renderCollectionRows(albums, {
      empty: "No albums found",
      glyph: () => "◉",
      title: (a) => a.Name || "Untitled album",
      meta: (a) => ["Album", a.AlbumArtist].filter(Boolean).join(" · "),
      onClick: (a) => openAlbumDetail(a, a.AlbumArtist, "search"),
    });
  }
}

// Artist/album search hits render as tappable rows that link into the Library
// drill-down views.
function renderCollectionRows(items, { empty, glyph, title, meta, onClick }) {
  if (!items.length) { resultsList.replaceChildren(emptyMsg(empty, "li")); return; }
  resultsList.replaceChildren(...items.map((it) => {
    const item = document.createElement("li");
    item.className = "track-item";
    const thumb = document.createElement("div");
    thumb.className = "track-thumb-placeholder";
    thumb.textContent = glyph(it);
    const info = document.createElement("div");
    info.className = "track-info";
    const name = document.createElement("span");
    name.className = "track-name";
    name.textContent = title(it);
    const metaEl = document.createElement("span");
    metaEl.className = "track-meta";
    metaEl.textContent = meta(it);
    info.append(name, metaEl);
    item.append(thumb, info);
    item.addEventListener("click", () => onClick(it));
    return item;
  }));
}

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

// ── Library (artists / playlists browse + drill-down) ────

libraryTabArtists.addEventListener("click", () => setLibraryTab("artists"));
libraryTabPlaylists.addEventListener("click", () => setLibraryTab("playlists"));
libraryTabBooks.addEventListener("click", () => setLibraryTab("books"));
libraryDetailBack.addEventListener("click", libraryBack);
libraryDetailPlay.addEventListener("click", () => playLibraryDetail(false));
libraryDetailShuffle.addEventListener("click", () => playLibraryDetail(true));
libraryDetailDelete.addEventListener("click", async () => {
  const entry = state.libraryStack[state.libraryStack.length - 1];
  if (!entry || entry.kind !== "playlist") return;
  if (!window.confirm(`Delete playlist "${entry.title}"? This cannot be undone.`)) return;
  await withBusy("Deleting playlist…", async () => {
    await deleteEmbyItem(entry.id);
    state.libraryPlaylists = null;
    switchView("library");
    setMessage(`Deleted "${entry.title}".`);
  });
});

function setLibraryTab(tab) {
  state.libraryTab = tab;
  const tabs = { artists: libraryTabArtists, playlists: libraryTabPlaylists, books: libraryTabBooks };
  const panes = { artists: libraryArtistsPane, playlists: libraryPlaylistsPane, books: libraryBooksPane };
  for (const [key, el] of Object.entries(tabs)) {
    el.classList.toggle("active", key === tab);
    el.setAttribute("aria-selected", String(key === tab));
  }
  for (const [key, el] of Object.entries(panes)) {
    el.classList.toggle("hidden", key !== tab);
  }
  loadLibraryView();
}

async function loadLibraryView() {
  if (!state.session) return;
  // Reveal the Audiobooks tab only when the server actually has such a library.
  if (state.audiobookLibId === undefined) {
    state.audiobookLibId = await detectAudiobookLibrary();
  }
  libraryTabBooks.classList.toggle("hidden", !state.audiobookLibId);

  if (state.libraryTab === "artists") {
    if (!state.libraryArtists) {
      await withBusy("Loading artists…", async () => {
        state.libraryArtists = await fetchAlbumArtists();
      });
    }
    renderLibraryArtists(state.libraryArtists || []);
  } else if (state.libraryTab === "books") {
    if (!state.libraryBooks) {
      await withBusy("Loading audiobooks…", async () => {
        state.libraryBooks = await fetchAuthors();
      });
    }
    renderLibraryBooks(state.libraryBooks || []);
    // Refresh the "Continue listening" shelf each visit (positions change as
    // you listen) — cheap, and it must reflect the latest resume points.
    renderResumeBooks(await fetchResumeBooks());
  } else {
    if (!state.libraryPlaylists) {
      await withBusy("Loading playlists…", async () => {
        state.libraryPlaylists = await fetchPlaylistsList();
      });
    }
    renderLibraryPlaylists(state.libraryPlaylists || []);
  }
}

// The audiobooks library id from the user's own Views (CollectionType
// "audiobooks"). Browser-side detection — no coordinator call — mirroring
// Android's audioLibraries(). Returns the id, or null if there's no such
// library (the tab then stays hidden).
async function detectAudiobookLibrary() {
  const base = activeServerUrl();
  try {
    const resp = await fetch(`${base}/Users/${encodeURIComponent(state.session.userId)}/Views`, {
      headers: embyHeaders(),
    });
    const data = await parseJson(resp);
    const view = (data.Items || []).find((v) => (v.CollectionType || "").toLowerCase() === "audiobooks");
    return view?.Id || null;
  } catch {
    return null;
  }
}

// Authors = AlbumArtists in the audiobooks library. Most have no image of
// their own, so hydrate each author's cover from one of their chapters —
// same trick as album art, keyed by AlbumArtist.
async function fetchAuthors() {
  const base = activeServerUrl();
  const qs = new URLSearchParams({
    UserId: state.session.userId,
    ParentId: state.audiobookLibId,
    SortBy: "SortName",
    Limit: "5000",
  });
  const resp = await fetch(`${base}/Artists/AlbumArtists?${qs}`, { headers: embyHeaders() });
  const data = await parseJson(resp);
  const authors = Array.isArray(data.Items) ? data.Items : [];
  await hydrateAuthorArt(authors);
  return authors;
}

async function hydrateAuthorArt(authors) {
  const missing = new Set();
  for (const author of authors) {
    if (author.ImageTags?.Primary) author._artItemId = author.Id;
    else missing.add(author.Id);
  }
  if (!missing.size) return;
  // One scan of the library's chapters; take the first chapter with art per
  // author (chapters carry AlbumArtists ids in their AlbumArtist... actually
  // in ArtistItems). Emby returns the author id on the chapter's AlbumArtists.
  const chapters = await fetchEmbyRawItems({
    ParentId: state.audiobookLibId,
    IncludeItemTypes: "Audio",
    Recursive: "true",
    Fields: "AlbumArtists",
    Limit: "5000",
  });
  const covers = new Map();
  for (const ch of chapters) {
    if (!ch.ImageTags?.Primary) continue;
    for (const aa of ch.AlbumArtists || []) {
      if (missing.has(aa.Id) && !covers.has(aa.Id)) covers.set(aa.Id, ch.Id);
    }
  }
  for (const author of authors) {
    if (!author._artItemId) author._artItemId = covers.get(author.Id) || null;
  }
}

// In-progress books for the "Continue listening" shelf: resumable chapters
// in the audiobook library, most-recently-played first, deduped to their
// book. Emby's IsResumable filter already means a mid-item position exists.
async function fetchResumeBooks(limit = 12) {
  if (!state.audiobookLibId) return [];
  const chapters = await fetchEmbyRawItems({
    ParentId: state.audiobookLibId,
    IncludeItemTypes: "Audio",
    Recursive: "true",
    Filters: "IsResumable",
    SortBy: "DatePlayed",
    SortOrder: "Descending",
    Fields: "UserData,RunTimeTicks",
    Limit: "200",
  }).catch(() => []);
  const seen = new Set();
  const books = [];
  for (const ch of chapters) {
    const bookId = ch.AlbumId || ch.Id;
    if (!bookId || seen.has(bookId)) continue;
    seen.add(bookId);
    const posTicks = ch.UserData?.PlaybackPositionTicks || 0;
    books.push({
      bookId,
      name: ch.Album || ch.Name || "Untitled book",
      author: ch.AlbumArtist || null,
      artItemId: ch.ImageTags?.Primary ? ch.Id : bookId,
      positionMs: Math.round(posTicks / 10000),
    });
    if (books.length >= limit) break;
  }
  return books;
}

function renderResumeBooks(books) {
  resumeBooksSection.classList.toggle("hidden", !books.length);
  resumeBooksRow.replaceChildren(...books.map(makeResumeCard));
}

// Same shelf on Home. Hidden when there's nothing in progress.
function renderHomeResume(books) {
  homeResumeShelf.classList.toggle("hidden", !books.length);
  homeResumeRow.replaceChildren(...books.map(makeResumeCard));
}

function makeResumeCard(b) {
  const card = document.createElement("button");
  card.type = "button";
  card.className = "resume-card";
  const artWrap = document.createElement("div");
  artWrap.className = "resume-card-art-wrap";
  const placeholder = document.createElement("div");
  placeholder.className = "resume-card-placeholder";
  placeholder.textContent = "📖";
  artWrap.append(placeholder);
  const img = document.createElement("img");
  img.className = "resume-card-art";
  img.loading = "lazy";
  img.alt = "";
  img.addEventListener("load", () => placeholder.classList.add("hidden"));
  img.addEventListener("error", () => img.classList.add("hidden"));
  img.src = artworkUrl(b.artItemId);
  artWrap.append(img);
  const title = document.createElement("span");
  title.className = "resume-card-title";
  title.textContent = b.name;
  const sub = document.createElement("span");
  sub.className = "resume-card-sub";
  sub.textContent = b.positionMs > 0 ? `at ${formatTime(b.positionMs / 1000)}` : (b.author || "");
  card.append(artWrap, title, sub);
  card.addEventListener("click", () => openResumeBook(b));
  return card;
}

// Tapping a Continue card resumes playback immediately — fetch the book's
// chapters (fresh UserData), compute the resume point, and start there. No
// navigation; the mini player takes over.
async function openResumeBook(b) {
  let chapters;
  await withBusy("Resuming…", async () => {
    chapters = await fetchBookChapters(b.bookId);
  });
  if (!chapters?.length) { setMessage("Could not load that book."); return; }
  const entry = { kind: "book", id: b.bookId, title: b.name, tracks: chapters };
  playBook(entry, resumeStartIndex(chapters));
}

async function fetchAuthorBooks(authorId) {
  const books = await fetchEmbyRawItems({
    IncludeItemTypes: "MusicAlbum",
    AlbumArtistIds: authorId,
    Recursive: "true",
    SortBy: "SortName",
    Fields: "ChildCount,ProductionYear",
    Limit: "500",
  });
  // Books rarely carry album-level art either; reuse the same hydration.
  await hydrateAlbumArt(books, authorId);
  return books;
}

// A book's chapters in play order, WITH resume UserData. A single-file book
// returns one item (the whole book); resume then seeks within it.
async function fetchBookChapters(bookId) {
  const base = activeServerUrl();
  const qs = new URLSearchParams({
    UserId: state.session.userId,
    ParentId: bookId,
    IncludeItemTypes: "Audio",
    Recursive: "true",
    SortBy: "ParentIndexNumber,IndexNumber",
    Fields: "RunTimeTicks,UserData",
  });
  const resp = await fetch(`${base}/Users/${encodeURIComponent(state.session.userId)}/Items?${qs}`, {
    headers: embyHeaders(),
  });
  const data = await parseJson(resp);
  return (Array.isArray(data.Items) ? data.Items : []).map((item) => {
    const track = embyItemToTrack(item);
    const ud = item.UserData || {};
    track.playbackPositionMs = ud.PlaybackPositionTicks ? Math.round(ud.PlaybackPositionTicks / 10000) : 0;
    track.played = Boolean(ud.Played);
    // When this chapter was last played, so resume can pick the point you
    // actually left off at last — not the earliest in book order (handles
    // listening across devices, and non-linear seeking).
    track.lastPlayedTs = ud.LastPlayedDate ? Date.parse(ud.LastPlayedDate) || 0 : 0;
    track.isBook = true;
    return track;
  });
}

// Same shapes the Android app's LibraryRepository uses, music-scoped by the
// coordinator's parent-ids so audiobooks never appear. Playlists are the one
// deliberate exception: they live outside the music libraries in Emby, so
// that query is unscoped (they are created MediaType=Audio by this app).
async function fetchAlbumArtists() {
  const base = activeServerUrl();
  const parentIds = await musicParentIds();
  const scopes = parentIds.length ? parentIds.map((parentId) => ({ ParentId: parentId })) : [{}];
  const batches = await Promise.all(scopes.map(async (scope) => {
    const qs = new URLSearchParams({
      UserId: state.session.userId,
      SortBy: "SortName",
      Limit: "5000",
      ...scope,
    });
    const resp = await fetch(`${base}/Artists/AlbumArtists?${qs}`, { headers: embyHeaders() });
    const data = await parseJson(resp);
    return Array.isArray(data.Items) ? data.Items : [];
  }));
  const seen = new Set();
  const artists = batches.flat().filter((artist) => {
    if (!artist?.Id || seen.has(artist.Id)) return false;
    seen.add(artist.Id);
    return true;
  });
  artists.sort((a, b) => (a.Name || "").localeCompare(b.Name || "", undefined, { sensitivity: "base" }));
  return artists;
}

async function fetchPlaylistsList() {
  return fetchEmbyRawItems({
    IncludeItemTypes: "Playlist",
    Recursive: "true",
    SortBy: "SortName",
    Fields: "ChildCount",
    Limit: "1000",
  });
}

async function fetchPlaylistTracks(playlistId) {
  const base = activeServerUrl();
  const qs = new URLSearchParams({ UserId: state.session.userId, Fields: "RunTimeTicks" });
  const resp = await fetch(`${base}/Playlists/${encodeURIComponent(playlistId)}/Items?${qs}`, {
    headers: embyHeaders(),
  });
  const data = await parseJson(resp);
  return (Array.isArray(data.Items) ? data.Items : []).map((item) => {
    const track = embyItemToTrack(item);
    // Removal targets the playlist ENTRY, not the track id (a track can be
    // in a playlist twice) — same as Android's deletePlaylistItems.
    track.playlistItemId = item.PlaylistItemId || null;
    return track;
  });
}

async function deleteEmbyItem(itemId) {
  const base = activeServerUrl();
  const resp = await fetch(`${base}/Items/${encodeURIComponent(itemId)}`, {
    method: "DELETE",
    headers: embyHeaders(),
  });
  if (!resp.ok) throw new Error(`Delete failed (HTTP ${resp.status})`);
}

async function removePlaylistEntries(playlistId, entryIds) {
  const base = activeServerUrl();
  const qs = new URLSearchParams({ EntryIds: entryIds.join(",") });
  const resp = await fetch(`${base}/Playlists/${encodeURIComponent(playlistId)}/Items?${qs}`, {
    method: "DELETE",
    headers: embyHeaders(),
  });
  if (!resp.ok) throw new Error(`Remove failed (HTTP ${resp.status})`);
}

async function fetchArtistAlbums(artistId) {
  const albums = await fetchEmbyRawItems({
    IncludeItemTypes: "MusicAlbum",
    AlbumArtistIds: artistId,
    Recursive: "true",
    SortBy: "ProductionYear,SortName",
    Fields: "ProductionYear,ChildCount",
    Limit: "500",
  });
  await hydrateAlbumArt(albums, artistId);
  return albums;
}

// Most albums have no album-level Primary image — the art lives in the
// tracks' embedded tags — so mirror the Android app's hydration: one query
// of the artist's tracks, taking the first track WITH art per album (not
// just the first track; some tracks have no image either). Sets _artItemId
// to the item whose Primary image the grid should show, or null for the
// placeholder (avoids firing image requests that are known 404s).
async function hydrateAlbumArt(albums, artistId) {
  const missing = new Set();
  for (const album of albums) {
    if (album.ImageTags?.Primary) album._artItemId = album.Id;
    else missing.add(album.Id);
  }
  if (!missing.size) return;
  const tracks = await fetchEmbyRawItems({
    IncludeItemTypes: "Audio",
    AlbumArtistIds: artistId,
    Recursive: "true",
    SortBy: "Album,ParentIndexNumber,IndexNumber",
    Fields: "AlbumId",
    Limit: "2000",
  });
  const covers = new Map();
  for (const track of tracks) {
    const albumId = track.AlbumId;
    if (!albumId || !missing.has(albumId) || covers.has(albumId)) continue;
    if (track.ImageTags?.Primary) covers.set(albumId, track.Id);
  }
  for (const album of albums) {
    if (!album._artItemId) album._artItemId = covers.get(album.Id) || null;
  }
}

async function fetchAlbumTracks(albumId) {
  return fetchEmbyItems({
    ParentId: albumId,
    IncludeItemTypes: "Audio",
    Recursive: "true",
    SortBy: "ParentIndexNumber,IndexNumber",
    Limit: "600",
  });
}

async function fetchArtistTracks(artistId) {
  return fetchEmbyItems({
    IncludeItemTypes: "Audio",
    AlbumArtistIds: artistId,
    Recursive: "true",
    SortBy: "Album,ParentIndexNumber,IndexNumber",
    Limit: "1000",
  });
}

function artistInitial(name) {
  const first = (name || "#").trim().charAt(0).toUpperCase();
  return /[A-Z]/.test(first) ? first : "#";
}

function renderLibraryArtists(artists) {
  renderAlphaList(artists, libraryArtistsList, libraryAlphaBar, {
    empty: "No artists found",
    fallbackName: "Unknown artist",
    onClick: (artist) => openArtistDetail(artist),
  });
}

function renderLibraryBooks(authors) {
  renderAlphaList(authors, libraryBooksList, libraryBooksAlphaBar, {
    empty: "No audiobooks found",
    fallbackName: "Unknown author",
    onClick: (author) => openAuthorDetail(author),
  });
}

// Shared A–Z list with letter headers + a jump strip. Used for both music
// artists and audiobook authors (identical Emby AlbumArtist shape).
function renderAlphaList(items, listEl, alphaEl, { empty, fallbackName, onClick }) {
  if (!items.length) {
    alphaEl.replaceChildren();
    listEl.replaceChildren(emptyMsg(empty, "li"));
    return;
  }
  const rows = [];
  const letterAnchors = new Map();
  for (const it of items) {
    const letter = artistInitial(it.Name);
    if (!letterAnchors.has(letter)) {
      const header = document.createElement("li");
      header.className = "letter-header";
      header.textContent = letter;
      letterAnchors.set(letter, header);
      rows.push(header);
    }
    const item = document.createElement("li");
    item.className = "track-item";
    const thumb = document.createElement("div");
    thumb.className = "track-thumb-placeholder";
    thumb.textContent = (it.Name || "?").trim().charAt(0).toUpperCase() || "?";
    const info = document.createElement("div");
    info.className = "track-info";
    const name = document.createElement("span");
    name.className = "track-name";
    name.textContent = it.Name || fallbackName;
    info.append(name);
    item.append(thumb, info);
    item.addEventListener("click", () => onClick(it));
    rows.push(item);
  }
  listEl.replaceChildren(...rows);
  alphaEl.replaceChildren(...[...letterAnchors.keys()].map((letter) => {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "alpha-btn";
    btn.textContent = letter;
    btn.addEventListener("click", () => letterAnchors.get(letter).scrollIntoView({ block: "start" }));
    return btn;
  }));
}

function renderLibraryPlaylists(playlists) {
  if (!playlists.length) {
    libraryPlaylistsList.replaceChildren(
      emptyMsg("No playlists yet — save a mix or queue to create one", "li"),
    );
    return;
  }
  libraryPlaylistsList.replaceChildren(...playlists.map((pl) => {
    const item = document.createElement("li");
    item.className = "track-item";
    const thumb = document.createElement("div");
    thumb.className = "track-thumb-placeholder";
    thumb.textContent = "♪";
    const info = document.createElement("div");
    info.className = "track-info";
    const name = document.createElement("span");
    name.className = "track-name";
    name.textContent = pl.Name || "Untitled playlist";
    const meta = document.createElement("span");
    meta.className = "track-meta";
    meta.textContent = pl.ChildCount ? `${pl.ChildCount} tracks` : "Playlist";
    info.append(name, meta);
    item.append(thumb, info);
    item.addEventListener("click", () => openPlaylistDetail(pl));
    return item;
  }));
}

async function openArtistDetail(artist, backTo = "library") {
  // Captured before the view switch wipes it, restored by libraryBack().
  state.libraryScrollTop = pageContent.scrollTop;
  const entry = {
    kind: "artist",
    id: artist.Id,
    title: artist.Name || "Unknown artist",
    backTo,
    backLabel: backTo === "search" ? "Search" : "Library",
  };
  await withBusy("Loading albums…", async () => {
    entry.albums = await fetchArtistAlbums(artist.Id);
  });
  if (!entry.albums) return;
  entry.sub = `${entry.albums.length} album${entry.albums.length === 1 ? "" : "s"}`;
  openLibraryDetail(entry);
}

async function openAlbumDetail(album, artistName, backTo = "library") {
  const entry = {
    kind: "album",
    id: album.Id,
    artItemId: album._artItemId || (album.ImageTags?.Primary ? album.Id : null),
    title: album.Name || "Untitled album",
    backTo,
    backLabel: artistName || (backTo === "search" ? "Search" : "Library"),
  };
  await withBusy("Loading tracks…", async () => {
    entry.tracks = await fetchAlbumTracks(album.Id);
  });
  if (!entry.tracks) return;
  const bits = [artistName, album.ProductionYear, `${entry.tracks.length} tracks`].filter(Boolean);
  entry.sub = bits.join(" · ");
  openLibraryDetail(entry);
}

async function openPlaylistDetail(pl) {
  state.libraryScrollTop = pageContent.scrollTop;
  const entry = {
    kind: "playlist",
    id: pl.Id,
    title: pl.Name || "Untitled playlist",
    backTo: "library",
    backLabel: "Library",
  };
  await withBusy("Loading playlist…", async () => {
    entry.tracks = await fetchPlaylistTracks(pl.Id);
  });
  if (!entry.tracks) return;
  entry.sub = `${entry.tracks.length} tracks`;
  openLibraryDetail(entry);
}

async function openAuthorDetail(author, backTo = "library") {
  state.libraryScrollTop = pageContent.scrollTop;
  const entry = {
    kind: "author",
    id: author.Id,
    title: author.Name || "Unknown author",
    backTo,
    backLabel: backTo === "search" ? "Search" : "Audiobooks",
  };
  await withBusy("Loading books…", async () => {
    entry.albums = await fetchAuthorBooks(author.Id);
  });
  if (!entry.albums) return;
  entry.sub = `${entry.albums.length} book${entry.albums.length === 1 ? "" : "s"}`;
  openLibraryDetail(entry);
}

async function openBookDetail(book, authorName, backTo = "library") {
  const entry = {
    kind: "book",
    id: book.Id,
    artItemId: book._artItemId || (book.ImageTags?.Primary ? book.Id : null),
    title: book.Name || "Untitled book",
    backTo,
    backLabel: authorName || (backTo === "search" ? "Search" : "Audiobooks"),
  };
  await withBusy("Loading book…", async () => {
    entry.tracks = await fetchBookChapters(book.Id);
  });
  if (!entry.tracks) return;
  const single = entry.tracks.length === 1;
  entry.resumeIndex = resumeStartIndex(entry.tracks);
  const resumePos = entry.tracks[entry.resumeIndex]?.playbackPositionMs || 0;
  const chapterWord = single ? "1 file" : `${entry.tracks.length} chapters`;
  const resuming = entry.resumeIndex > 0 || resumePos > RESUME_MIN_POSITION_MS;
  // The resume chapter is marked in the chapter list itself, so the subtitle
  // only needs the position (naming a "ch. N" by list order clashed with
  // chapters that are themselves titled "00:", "01:", …).
  entry.sub = resuming && resumePos > RESUME_MIN_POSITION_MS
    ? `${chapterWord} · resume at ${formatTime(resumePos / 1000)}`
    : (resuming ? `${chapterWord} · resume` : chapterWord);
  openLibraryDetail(entry);
}

// Where to resume a book. Prefers the MOST RECENTLY PLAYED chapter that still
// has a mid-chapter position — so listening across devices (car → phone) or
// out of order lands at the latest point you were at, not the earliest in
// book order. Falls back to the first unplayed chapter after the most-recently
// played one, else the start.
function resumeStartIndex(chapters) {
  let best = -1;
  let bestTs = -1;
  chapters.forEach((c, i) => {
    const pos = c.playbackPositionMs || 0;
    const dur = c.duration_ms;
    const midChapter = pos > RESUME_MIN_POSITION_MS && (!dur || pos < dur - RESUME_END_PADDING_MS);
    if (midChapter && (c.lastPlayedTs || 0) >= bestTs) { best = i; bestTs = c.lastPlayedTs || 0; }
  });
  if (best >= 0) return best;
  // No in-progress chapter: pick up after the most-recently played one.
  let lastPlayed = -1;
  let lastPlayedTs = -1;
  chapters.forEach((c, i) => {
    if (c.played && (c.lastPlayedTs || 0) >= lastPlayedTs) { lastPlayed = i; lastPlayedTs = c.lastPlayedTs || 0; }
  });
  if (lastPlayed >= 0 && lastPlayed < chapters.length - 1) {
    const nextUnplayed = chapters.findIndex((c, i) => i > lastPlayed && !c.played);
    if (nextUnplayed >= 0) return nextUnplayed;
  }
  return 0;
}

function openLibraryDetail(entry) {
  state.libraryStack.push(entry);
  renderLibraryDetail(entry);
  switchView("libraryDetail");
}

function libraryBack() {
  const popped = state.libraryStack.pop();
  const prev = state.libraryStack[state.libraryStack.length - 1];
  if (!prev) {
    // Return to wherever the drill-down began (Library list or Search).
    const origin = popped?.backTo || "library";
    const restoreTo = state.libraryScrollTop;
    switchView(origin);
    if (origin === "library") {
      // switchView zeroes pageContent.scrollTop; put the user back where
      // they were in the A-Z/playlists list (cached data renders
      // synchronously, so the list has its height again by the next frame).
      requestAnimationFrame(() => { pageContent.scrollTop = restoreTo; });
    }
    return;
  }
  renderLibraryDetail(prev);
  switchView("libraryDetail");
}

function renderLibraryDetail(entry) {
  const eyebrows = { artist: "Artist", album: "Album", playlist: "Playlist", author: "Author", book: "Book" };
  libraryDetailEyebrow.textContent = eyebrows[entry.kind] || "";
  libraryDetailTitle.textContent = entry.title;
  libraryDetailSub.textContent = entry.sub || "";
  libraryDetailBackLabel.textContent = entry.backLabel || "Library";

  const placeholderGlyph = (entry.kind === "author" || entry.kind === "book") ? "📖" : "♪";
  libraryDetailArtPlaceholder.textContent = placeholderGlyph;
  libraryDetailArt.classList.add("hidden");
  libraryDetailArtPlaceholder.classList.remove("hidden");
  libraryDetailArt.onerror = () => {
    libraryDetailArt.classList.add("hidden");
    libraryDetailArtPlaceholder.classList.remove("hidden");
  };
  libraryDetailArt.onload = () => {
    libraryDetailArt.classList.remove("hidden");
    libraryDetailArtPlaceholder.classList.add("hidden");
  };
  libraryDetailArt.src = artworkUrl(entry.artItemId || entry.id);

  const isGrid = entry.kind === "artist" || entry.kind === "author";
  const isBook = entry.kind === "book";
  const isAuthor = entry.kind === "author";
  libraryAlbumsGrid.classList.toggle("hidden", !isGrid);
  libraryDetailTracks.classList.toggle("hidden", isGrid);
  libraryDetailDelete.classList.toggle("hidden", entry.kind !== "playlist");
  // Author = pick a book (no play-all across books). Book = Resume, no shuffle
  // (chapters are ordered). Everything else keeps Play + Shuffle.
  libraryDetailPlay.classList.toggle("hidden", isAuthor);
  libraryDetailShuffle.classList.toggle("hidden", isBook || isAuthor);
  libraryDetailPlay.textContent = isBook && (entry.resumeIndex > 0 ||
    (entry.tracks?.[entry.resumeIndex]?.playbackPositionMs || 0) > RESUME_MIN_POSITION_MS)
    ? "Resume" : "Play";

  if (isGrid) {
    const openChild = entry.kind === "author"
      ? (album) => openBookDetail(album, entry.title)
      : (album) => openAlbumDetail(album, entry.title);
    renderAlbumGrid(entry.albums || [], openChild, entry.kind === "author");
  } else {
    const tracks = entry.tracks || [];
    renderTrackList(
      libraryDetailTracks,
      tracks,
      (track) => playBookOrTracks(entry, tracks, tracks.indexOf(track)),
      !isBook,   // no per-row Similar on book chapters
      entry.kind === "playlist" ? (track) => removeFromPlaylist(entry, track) : null,
      isBook ? (entry.resumeIndex ?? -1) : -1,   // mark + scroll to resume chapter
    );
  }
}

// Playing a chapter row starts the book from that chapter, seeking to its
// saved position if it has one; a music/playlist row just plays from there.
function playBookOrTracks(entry, tracks, index) {
  if (entry.kind === "book") {
    playBook(entry, index);
  } else {
    loadQueue(tracks, entry.title, true, index);
  }
}

async function removeFromPlaylist(entry, track) {
  if (!track.playlistItemId) {
    setMessage("Emby did not return an entry id for this track.");
    return;
  }
  await withBusy("Removing track…", async () => {
    await removePlaylistEntries(entry.id, [track.playlistItemId]);
    entry.tracks = entry.tracks.filter((t) => t !== track);
    entry.sub = `${entry.tracks.length} tracks`;
    state.libraryPlaylists = null;
    renderLibraryDetail(entry);
    setMessage(`Removed "${track.title}" from ${entry.title}.`);
  });
}

function renderAlbumGrid(albums, onClick, isBooks = false) {
  if (!albums.length) {
    libraryAlbumsGrid.replaceChildren(emptyMsg(isBooks ? "No books found" : "No albums found"));
    return;
  }
  libraryAlbumsGrid.replaceChildren(...albums.map((album) => {
    const card = document.createElement("button");
    card.type = "button";
    card.className = "album-card";
    const artWrap = document.createElement("div");
    artWrap.className = "album-card-art-wrap";
    const placeholder = document.createElement("div");
    placeholder.className = "album-card-placeholder";
    placeholder.textContent = isBooks ? "📖" : "♪";
    artWrap.append(placeholder);
    if (album._artItemId) {
      const img = document.createElement("img");
      img.className = "album-card-art";
      img.loading = "lazy";
      img.alt = "";
      img.addEventListener("load", () => placeholder.classList.add("hidden"));
      img.addEventListener("error", () => img.classList.add("hidden"));
      img.src = artworkUrl(album._artItemId);
      artWrap.append(img);
    }
    const title = document.createElement("span");
    title.className = "album-card-title";
    title.textContent = album.Name || "Untitled";
    const sub = document.createElement("span");
    sub.className = "album-card-sub";
    sub.textContent = album.ProductionYear || "";
    card.append(artWrap, title, sub);
    card.addEventListener("click", () => onClick(album));
    return card;
  }));
}

async function playLibraryDetail(shuffled) {
  const entry = state.libraryStack[state.libraryStack.length - 1];
  if (!entry) return;
  if (entry.kind === "book") {
    playBook(entry, entry.resumeIndex || 0);
    return;
  }
  if (!entry.tracks && entry.kind === "artist") {
    await withBusy("Loading tracks…", async () => {
      entry.tracks = await fetchArtistTracks(entry.id);
    });
  }
  const tracks = entry.tracks || [];
  if (!tracks.length) {
    setMessage("Nothing to play.");
    return;
  }
  const list = shuffled ? shuffleArray(tracks) : tracks;
  loadQueue(list, entry.title, true);
}

// Play a book from [index], seeking into that chapter at its saved position.
// Never shuffles; the queue is the book's chapters in order.
function playBook(entry, index) {
  const chapters = entry.tracks || [];
  if (!chapters.length) { setMessage("Nothing to play."); return; }
  const startPositionMs = chapters[index]?.playbackPositionMs || 0;
  // Book playback is inherently ordered; force shuffle off so loadQueue
  // doesn't scramble the chapters.
  const wasShuffle = state.shuffle;
  state.shuffle = false;
  loadQueue(chapters, entry.title, true, index);
  state.shuffle = wasShuffle;
  if (startPositionMs > RESUME_MIN_POSITION_MS) state.pendingSeekMs = startPositionMs;
}

function loadQueue(tracks, label, autoPlay, startIndex = 0) {
  state.queueLabel = label || "";
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

async function playIndex(index, opts = {}) {
  if (!state.queue[index]) return;
  // Any fresh track load clears a stale resume seek; playBook re-arms it
  // right after this returns for the chapter it actually wants to resume.
  state.pendingSeekMs = 0;
  const previousTrack = state.queue[state.currentIndex];
  if (!opts.skipStopReport && previousTrack && audio.src) {
    reportStopped(previousTrack, audio.currentTime * 1000);
  }
  state.currentIndex = index;
  const track = state.queue[index];
  currentPlaySessionId = playSessionId();
  audio.src = streamUrl(track.id, currentPlaySessionId);
  seekBar.value = "0";
  timeElapsed.textContent = "0:00";
  timeDuration.textContent = "0:00";
  renderMini();
  renderNowPlaying();
  renderQueue();
  updateMediaSession(track);
  try { await audio.play(); reportStarted(track); }
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
  // Drives the waveform along the top of the mini player: it travels while
  // audio is playing and stills when paused, so playback state is readable
  // without looking at the button.
  miniPlayer.classList.toggle("is-playing", !audio.paused);
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
miniStopButton.addEventListener("click", stopPlayback);

// Stop: halt playback and clear the queue, so the mini player dismisses. (Pause
// keeps the track loaded for resume; stop tears the session down entirely.)
function stopPlayback() {
  const track = state.queue[state.currentIndex];
  if (track && audio.src) reportStopped(track, audio.currentTime * 1000);
  audio.pause();
  audio.removeAttribute("src");
  audio.load();
  state.queue = [];
  state.originalQueue = [];
  state.currentIndex = -1;
  state.queueLabel = "";
  if (state.nowPlayingOpen) closeNP();
  renderMini();
  renderNowPlaying();
  renderQueue();
}

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

savePlaylistButton.addEventListener("click", async () => {
  if (!state.queue.length) { setMessage("Nothing in the queue to save."); return; }
  const suggested = (state.queueLabel || "Emby Sonic mix").replace(/^[^:]+:\s*/, "");
  const name = window.prompt("Save queue as playlist named:", suggested);
  if (name == null) return;               // cancelled
  const trimmed = name.trim();
  if (!trimmed) { setMessage("Playlist name can't be empty."); return; }
  await withBusy("Saving playlist…", async () => {
    const count = await createEmbyPlaylist(trimmed, state.queue.map((t) => t.id));
    setMessage(`Saved "${trimmed}" (${count} tracks) to your Emby playlists.`);
  });
});

// Create a server-side Emby playlist from the current queue. Mirrors the Android
// app's POST /Playlists?Name=&Ids=&UserId=&MediaType=Audio — persists in Emby and
// shows up in every client. Hits Emby directly (same as fetchEmbyItems), not the
// coordinator, which has no playlist route.
async function createEmbyPlaylist(name, ids) {
  const base = activeServerUrl();
  const qs = new URLSearchParams({
    Name: name,
    Ids: ids.join(","),
    UserId: state.session.userId,
    MediaType: "Audio",
  });
  const data = await parseJson(await fetch(`${base}/Playlists?${qs}`, {
    method: "POST",
    headers: embyHeaders(),
  }));
  // The Library → Playlists pane caches its list; a newly created playlist
  // must show up there on next visit.
  state.libraryPlaylists = null;
  return typeof data.ItemAddedCount === "number" ? data.ItemAddedCount : ids.length;
}

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

// ── Playback speed + sleep timer (audiobook-oriented, work for anything) ──

const SPEEDS = [0.75, 1, 1.25, 1.5, 1.75, 2];

speedButton.addEventListener("click", () => {
  const next = SPEEDS[(SPEEDS.indexOf(state.playbackRate) + 1) % SPEEDS.length];
  state.playbackRate = next;
  audio.playbackRate = next;
  renderSpeed();
});

function renderSpeed() {
  speedButton.textContent = `Speed ${state.playbackRate}×`;
}

sleepButton.addEventListener("click", () => {
  sleepMenu.classList.toggle("hidden");
});

sleepMenu.querySelectorAll(".sleep-opt").forEach((opt) => {
  opt.addEventListener("click", () => {
    setSleepTimer(opt.dataset.sleep);
    sleepMenu.classList.add("hidden");
  });
});

let sleepInterval = null;

function setSleepTimer(value) {
  clearSleepTimer();
  if (value === "0") { renderSleep(); return; }
  if (value === "chapter") {
    state.sleepTimer = { mode: "chapter" };
  } else {
    const minutes = Number(value);
    state.sleepTimer = { mode: "time", endsAt: Date.now() + minutes * 60_000 };
    sleepInterval = setInterval(() => {
      if (!state.sleepTimer) { clearSleepTimer(); return; }
      if (Date.now() >= state.sleepTimer.endsAt) {
        audio.pause();
        clearSleepTimer();
        setMessage("Sleep timer — paused.");
      } else {
        renderSleep();
      }
    }, 1000);
  }
  renderSleep();
}

function clearSleepTimer() {
  if (sleepInterval) { clearInterval(sleepInterval); sleepInterval = null; }
  state.sleepTimer = null;
  renderSleep();
}

// Called from the "ended" handler: if a chapter-end sleep timer is armed,
// stop after the current chapter finishes rather than rolling to the next.
function sleepTimerStopsAfterChapter() {
  if (state.sleepTimer?.mode === "chapter") {
    clearSleepTimer();
    setMessage("Sleep timer — paused at end of chapter.");
    return true;
  }
  return false;
}

function renderSleep() {
  const t = state.sleepTimer;
  if (!t) {
    sleepButton.textContent = "Sleep off";
    sleepButton.classList.remove("on");
    return;
  }
  sleepButton.classList.add("on");
  if (t.mode === "chapter") {
    sleepButton.textContent = "Sleep: chapter";
  } else {
    const remainMs = Math.max(0, t.endsAt - Date.now());
    const mins = Math.ceil(remainMs / 60_000);
    sleepButton.textContent = `Sleep ${mins}m`;
  }
}

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

audio.addEventListener("play", () => { renderMini(); renderNowPlaying(); reportProgressNow(); });
audio.addEventListener("pause", () => { renderMini(); renderNowPlaying(); reportProgressNow(); });

audio.addEventListener("timeupdate", () => {
  if (!audio.duration || audio.seeking) return;
  const pct = (audio.currentTime / audio.duration) * 100;
  seekBar.value = String(pct);
  timeElapsed.textContent = formatTime(audio.currentTime);
  renderMiniProgress();
  reportProgressIfDue();
});

audio.addEventListener("loadedmetadata", () => {
  // Resume seek (audiobooks): apply once, here, now that duration is known.
  if (state.pendingSeekMs > 0 && audio.duration) {
    audio.currentTime = Math.min(state.pendingSeekMs / 1000, audio.duration - 1);
    state.pendingSeekMs = 0;
  }
  // Keep audiobook speed across chapter changes / new loads.
  audio.playbackRate = state.playbackRate;
  seekBar.value = audio.duration ? String((audio.currentTime / audio.duration) * 100) : "0";
  timeElapsed.textContent = formatTime(audio.currentTime);
  timeDuration.textContent = formatTime(audio.duration);
});

audio.addEventListener("durationchange", () => {
  timeDuration.textContent = formatTime(audio.duration);
});

audio.addEventListener("waiting", () => playButton.classList.add("buffering"));
audio.addEventListener("canplay", () => playButton.classList.remove("buffering"));

audio.addEventListener("ended", () => {
  const track = state.queue[state.currentIndex];
  if (track) reportStopped(track, audio.currentTime * 1000);
  if (state.repeat === "one") {
    audio.currentTime = 0;
    currentPlaySessionId = playSessionId();
    audio.play().then(() => track && reportStarted(track));
    return;
  }
  // An end-of-chapter sleep timer stops here instead of rolling on.
  if (sleepTimerStopsAfterChapter()) {
    renderMini();
    renderNowPlaying();
    return;
  }
  if (state.currentIndex < state.queue.length - 1) {
    playIndex(state.currentIndex + 1, { skipStopReport: true });
  } else if (state.repeat === "all" && state.queue.length) {
    playIndex(0, { skipStopReport: true });
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
    checkEmbyReachable();
  }
}

// ── Emby reachability ────────────────────────────────────
//
// The browser talks to Emby directly, so an Emby address it cannot reach
// produces empty artist/album/playlist lists and broken artwork — output
// identical to a genuinely empty library, with nothing saying a request
// failed. That has now cost real debugging time twice: once from EMBY_URL
// set to loopback, and once from EMBY_URL_EXTERNAL pointing at a port the
// reverse proxy had moved off. Both looked like "my library is empty".
//
// So probe the address explicitly and say what's wrong. /System/Info/Public
// needs no auth, so this also works before or independently of a valid token.
async function checkEmbyReachable() {
  const base = activeServerUrl();
  if (!base) return;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  try {
    const resp = await fetch(`${base}/System/Info/Public`, { signal: controller.signal });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    hideServerBanner();
  } catch (err) {
    showServerBanner(base, err);
  } finally {
    clearTimeout(timer);
  }
}

function showServerBanner(base, err) {
  const mixedContent = window.location.protocol === "https:" && base.startsWith("http:");
  let hint;
  if (mixedContent) {
    // Worth calling out separately: the browser blocks these before they hit
    // the network, so the server is fine and nothing appears in its logs.
    hint = "This page is served over HTTPS but that address is plain HTTP, so your "
      + "browser blocks every request to it. Set EMBY_URL_EXTERNAL to an HTTPS address.";
  } else if (err?.name === "AbortError") {
    hint = "The server did not respond in time. Check it is running and reachable from this device.";
  } else {
    hint = "Check the address is correct — including its port — and reachable from this device. "
      + "If you reach liquidWave over a domain name, this comes from EMBY_URL_EXTERNAL.";
  }
  serverBanner.innerHTML = "";
  const title = document.createElement("strong");
  title.textContent = "Can't reach your Emby server";
  const body = document.createElement("span");
  body.append(document.createTextNode("Tried "));
  const code = document.createElement("code");
  code.textContent = base;
  body.append(code, document.createTextNode(`. ${hint}`));
  serverBanner.append(title, body);
  serverBanner.classList.remove("hidden");
}

function hideServerBanner() {
  serverBanner.classList.add("hidden");
}

// A laptop waking on a different network is exactly when the reachable
// address changes, so re-check rather than leaving a stale banner either way.
window.addEventListener("online", () => {
  if (state.session?.token) checkEmbyReachable();
});

// ── Emby direct API ──────────────────────────────────────

async function fetchEmbyItems(params) {
  return (await fetchEmbyRawItems(params)).map(embyItemToTrack);
}

async function fetchEmbyRawItems(params) {
  if (!state.session) return [];
  const base = activeServerUrl();
  const userId = state.session.userId;
  const qs = new URLSearchParams({
    ...params,
    Fields: params.Fields || "RunTimeTicks",
  });
  const resp = await fetch(`${base}/Users/${encodeURIComponent(userId)}/Items?${qs}`, {
    headers: embyHeaders(),
  });
  const data = await parseJson(resp);
  return Array.isArray(data.Items) ? data.Items : [];
}

async function musicParentIds() {
  if (!state.session) return [];
  if (Array.isArray(state.musicParentIds)) return state.musicParentIds;
  const resp = await authedFetch("/sonic/music/parent-ids");
  const data = await parseJson(resp);
  state.musicParentIds = Array.isArray(data.parent_ids) ? data.parent_ids : [];
  return state.musicParentIds;
}

async function fetchMusicEmbyRawItems(params) {
  if (params.ParentId) return fetchEmbyRawItems(params);
  const parentIds = await musicParentIds();
  if (!parentIds.length) return fetchEmbyRawItems(params);
  const batches = await Promise.all(parentIds.map((parentId) => fetchEmbyRawItems({ ...params, ParentId: parentId })));
  const seen = new Set();
  return batches.flat().filter((item) => {
    const id = item?.Id;
    if (!id || seen.has(id)) return false;
    seen.add(id);
    return true;
  });
}

async function fetchMusicEmbyItems(params) {
  return (await fetchMusicEmbyRawItems(params)).map(embyItemToTrack);
}

async function fetchMusicGenres() {
  if (!state.session) return [];
  const base = activeServerUrl();
  const parentIds = await musicParentIds();
  const scopes = parentIds.length ? parentIds.map((parentId) => ({ ParentId: parentId })) : [{}];
  const batches = await Promise.all(scopes.map(async (scope) => {
    const qs = new URLSearchParams({ SortBy: "SortName", Limit: 100, ...scope });
    const resp = await fetch(`${base}/MusicGenres?${qs}`, { headers: embyHeaders() });
    const data = await parseJson(resp);
    return Array.isArray(data.Items) ? data.Items : [];
  }));
  const seen = new Set();
  return batches.flat().filter((genre) => {
    const key = genre?.Id || genre?.Name;
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
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

function renderTrackList(container, tracks, onPlay, showSimilarBtn, onRemove = null, markIndex = -1) {
  if (!tracks.length) { container.replaceChildren(emptyMsg("No results", "li")); return; }
  let markedEl = null;
  container.replaceChildren(...tracks.map((track, i) => {
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
    if (i === markIndex) {
      item.classList.add("resume-mark");
      const tag = document.createElement("span");
      tag.className = "resume-tag";
      tag.textContent = "Resume";
      name.append(" ", tag);
      markedEl = item;
    }
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
    if (onRemove) {
      const removeBtn = document.createElement("button");
      removeBtn.type = "button";
      removeBtn.className = "row-remove-btn";
      removeBtn.textContent = "✕";
      removeBtn.setAttribute("aria-label", "Remove from playlist");
      removeBtn.title = "Remove from playlist";
      removeBtn.addEventListener("click", (e) => { e.stopPropagation(); onRemove(track); });
      actions.appendChild(removeBtn);
    }

    item.append(thumb, info, actions);
    item.addEventListener("click", () => onPlay(track));
    return item;
  }));
  // Bring the resume chapter into view without yanking the whole page.
  if (markedEl) markedEl.scrollIntoView({ block: "nearest" });
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

function streamUrl(itemId, sessionId) {
  const base = activeServerUrl();
  const params = new URLSearchParams({
    UserId: state.session.userId,
    MaxStreamingBitrate: "140000000",
    Container: "mp3,aac,m4a,mp4,m4b,flac,webma,webm,wav,ogg",
    AudioCodec: "mp3,aac,flac,vorbis,opus",
    TranscodingContainer: "mp3",
    TranscodingProtocol: "http",
    PlaySessionId: sessionId,
    api_key: state.session.token,
  });
  return `${base}/Audio/${encodeURIComponent(itemId)}/universal?${params}`;
}

// ── Playback session reporting ──────────────────────────────
// Best-effort — a failed report must never interrupt playback, so these never
// throw or block on the network.

// Same persistent browser identity the coordinator sends Emby during login.
// Emby also needs it on direct browser requests — like the Android app's
// EmbyAuthInterceptor, which attaches client identity to every request — to
// know which device/session a report belongs to.
function embyClientAuth() {
  return `MediaBrowser Client="liquidWave", Device="Browser", DeviceId="${browserDeviceId()}", Version="web-mvp"`;
}

function embyHeaders(extra = {}) {
  return {
    ...extra,
    "X-Emby-Token": state.session.token,
    "X-Emby-Authorization": embyClientAuth(),
  };
}

function reportEmbySession(path, body) {
  if (!state.session) return;
  const base = activeServerUrl();
  fetch(`${base}/${path}`, {
    method: "POST",
    headers: embyHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(body),
  }).catch(() => {});
}

function updateUserData(itemId, positionMs, played) {
  if (!state.session) return;
  const base = activeServerUrl();
  fetch(`${base}/Users/${encodeURIComponent(state.session.userId)}/Items/${encodeURIComponent(itemId)}/UserData`, {
    method: "POST",
    headers: embyHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify({ PlaybackPositionTicks: ticksFromMs(positionMs), Played: played }),
  }).catch(() => {});
}

function ticksFromMs(ms) {
  return Math.round(ms * 10_000);
}

// Fields Emby needs beyond ItemId/PositionTicks/IsPaused to actually register
// and surface a session — matches the Android app's PlaybackReportDto
// defaults. Without PlayMethod/MediaSourceId in particular, Emby appears to
// accept the call but never shows it on the Now Playing dashboard.
function playbackReportBase(itemId) {
  return {
    ItemId: itemId,
    MediaSourceId: `mediasource_${itemId}`,
    PlaySessionId: currentPlaySessionId,
    QueueableMediaTypes: ["Audio"],
    CanSeek: true,
    IsMuted: audio.muted,
    PlayMethod: "DirectPlay",
  };
}

function reportStarted(track) {
  reportEmbySession("Sessions/Playing", {
    ...playbackReportBase(track.id),
    PositionTicks: 0,
    IsPaused: false,
  });
}

function reportProgressIfDue() {
  if (Date.now() - lastProgressReportMs < PROGRESS_REPORT_INTERVAL_MS) return;
  reportProgressNow();
}

function reportProgressNow() {
  const track = state.queue[state.currentIndex];
  if (!track || !audio.src) return;
  lastProgressReportMs = Date.now();
  reportEmbySession("Sessions/Playing/Progress", {
    ...playbackReportBase(track.id),
    PositionTicks: ticksFromMs(audio.currentTime * 1000),
    IsPaused: audio.paused,
  });
}

// A track is "completed" (vs. skipped) once it's within RESUME_END_PADDING_MS
// of its end — matches the Android app's isCompletedAt so a track finished
// naturally is marked played, while a mid-track skip leaves played/resume
// state untouched (a rolling Played=false write would wipe played status
// earned in another session/client).
//
// Audiobooks are the deliberate exception: a book chapter MUST keep its
// mid-point position on stop so it resumes — for music, reporting position 0
// on stop is intentional (music starts fresh next time). Without this, a
// mid-book stop reported PositionTicks 0 and wiped the resume point in Emby
// (so it also vanished from the Android app and Emby's own clients).
function reportStopped(track, positionMs) {
  const completed = track.duration_ms != null && positionMs >= track.duration_ms - RESUME_END_PADDING_MS;
  const keepPosition = track.isBook && !completed && positionMs > RESUME_MIN_POSITION_MS;
  reportEmbySession("Sessions/Playing/Stopped", {
    ...playbackReportBase(track.id),
    PositionTicks: ticksFromMs(completed || keepPosition ? positionMs : 0),
    IsPaused: true,
  });
  if (completed) updateUserData(track.id, 0, true);
  else if (keepPosition) updateUserData(track.id, positionMs, false);
}

function artworkUrl(itemId) {
  const base = activeServerUrl();
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

function browserDeviceId() {
  const stored = localStorage.getItem(DEVICE_ID_STORAGE_KEY);
  if (stored && UUID_RE.test(stored)) return stored.toLowerCase();
  const id = newUuid();
  localStorage.setItem(DEVICE_ID_STORAGE_KEY, id);
  return id;
}

// Picks the Emby address the browser can actually reach for this page load:
// LAN when the webapp itself was loaded via a raw IP/localhost/.local name,
// external (if configured at login) when it was loaded via a public domain.
// Decided fresh on every call (not cached in the session) so a page that
// stays open across a network change, or a persisted session reused from a
// different network later, doesn't keep streaming from a stale address (#30).
function activeServerUrl() {
  const session = state.session;
  const host = window.location.hostname;
  const isLan = host === "localhost" || host === "127.0.0.1" || host.endsWith(".local")
    || /^(?:\d{1,3}\.){3}\d{1,3}$/.test(host) || host.startsWith("[");
  const base = (isLan ? session.serverUrl : session.serverUrlExternal) || session.serverUrl;
  return base.replace(/\/$/, "");
}

function playSessionId() {
  return newUuid();
}

function newUuid() {
  if (globalThis.crypto?.randomUUID) return crypto.randomUUID();
  const bytes = new Uint8Array(16);
  if (globalThis.crypto?.getRandomValues) {
    crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i += 1) bytes[i] = Math.floor(Math.random() * 256);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, "0"));
  return `${hex.slice(0, 4).join("")}-${hex.slice(4, 6).join("")}-${hex.slice(6, 8).join("")}-${hex.slice(8, 10).join("")}-${hex.slice(10).join("")}`;
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

// ── Boot (runs last, after all module-level declarations) ────────────────
// Must stay at the end: renderSession()'s signed-in path drills into
// loadHomeData → loadMixes → setMessage, which reads `let toastTimer`. Running
// this before that declaration is initialized crashes on refresh (issue #32).
renderSession();
renderShuffle();
renderRepeat();
renderSpeed();
renderSleep();
