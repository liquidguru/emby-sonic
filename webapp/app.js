const STORAGE_KEY = "embySonic.webSession";

const loginView = document.querySelector("#loginView");
const mainView = document.querySelector("#mainView");
const loginForm = document.querySelector("#loginForm");
const searchForm = document.querySelector("#searchForm");
const logoutButton = document.querySelector("#logoutButton");
const resultsList = document.querySelector("#resultsList");
const queueList = document.querySelector("#queueList");
const message = document.querySelector("#message");
const audio = document.querySelector("#audio");
const playButton = document.querySelector("#playButton");
const prevButton = document.querySelector("#prevButton");
const nextButton = document.querySelector("#nextButton");
const nowTitle = document.querySelector("#nowTitle");
const nowSubtitle = document.querySelector("#nowSubtitle");
const artwork = document.querySelector("#artwork");

const state = {
  session: loadSession(),
  queue: [],
  currentIndex: -1,
};

renderSession();
renderPlayer();

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
  state.currentIndex = -1;
  audio.pause();
  audio.removeAttribute("src");
  renderSession();
  renderResults([]);
  renderPlayer();
  setMessage("");
});

searchForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const query = document.querySelector("#searchInput").value.trim();
  if (!query) {
    renderResults([]);
    return;
  }
  await withBusy("Searching...", async () => {
    const resp = await authedFetch(`/sonic/search/tracks?q=${encodeURIComponent(query)}`);
    const tracks = await parseJson(resp);
    renderResults(tracks);
    setMessage(tracks.length ? `${tracks.length} tracks found.` : "No tracks found.");
  });
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

audio.addEventListener("play", renderPlayer);
audio.addEventListener("pause", renderPlayer);
audio.addEventListener("ended", () => {
  if (state.currentIndex < state.queue.length - 1) {
    playIndex(state.currentIndex + 1);
  } else {
    renderPlayer();
  }
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

function renderResults(tracks) {
  resultsList.replaceChildren(...tracks.map((track) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "track-row";
    button.addEventListener("click", () => startRadio(track));
    button.append(trackText(track), duration(track.duration_ms));
    const item = document.createElement("li");
    item.append(button);
    return item;
  }));
}

async function startRadio(seed) {
  await withBusy("Building radio...", async () => {
    const resp = await authedFetch(`/sonic/tracks/${encodeURIComponent(seed.id)}/radio`);
    const radio = await parseJson(resp);
    state.queue = Array.isArray(radio.tracks) ? radio.tracks : [];
    state.currentIndex = state.queue.findIndex((track) => track.id === seed.id);
    if (state.currentIndex < 0) state.currentIndex = 0;
    renderQueue();
    await playIndex(state.currentIndex);
  });
}

function renderQueue() {
  queueList.replaceChildren(...state.queue.map((track, index) => {
    const row = document.createElement("li");
    row.className = `queue-row${index === state.currentIndex ? " active" : ""}`;
    row.addEventListener("click", () => playIndex(index));
    row.append(trackText(track));
    return row;
  }));
}

async function playIndex(index) {
  if (!state.queue[index]) return;
  state.currentIndex = index;
  const track = state.queue[index];
  audio.src = streamUrl(track.id);
  renderQueue();
  renderPlayer();
  updateMediaSession(track);
  await audio.play();
}

function renderPlayer() {
  const track = state.queue[state.currentIndex];
  playButton.textContent = audio.paused ? "\u25b6" : "\u23f8";
  playButton.setAttribute("aria-label", audio.paused ? "Play" : "Pause");
  prevButton.disabled = state.currentIndex <= 0;
  nextButton.disabled = state.currentIndex < 0 || state.currentIndex >= state.queue.length - 1;
  if (!track) {
    nowTitle.textContent = "No track loaded";
    nowSubtitle.textContent = "No radio queue yet.";
    artwork.classList.add("hidden");
    artwork.removeAttribute("src");
    return;
  }
  nowTitle.textContent = track.title || "Untitled track";
  nowSubtitle.textContent = [track.artist, track.album].filter(Boolean).join(" - ");
  artwork.src = artworkUrl(track.id);
  artwork.classList.remove("hidden");
}

function trackText(track) {
  const wrap = document.createElement("span");
  const title = document.createElement("span");
  title.className = "track-title";
  title.textContent = track.title || "Untitled track";
  const meta = document.createElement("span");
  meta.className = "track-meta";
  meta.textContent = [track.artist, track.album].filter(Boolean).join(" - ");
  wrap.append(title, meta);
  return wrap;
}

function duration(ms) {
  const node = document.createElement("span");
  node.className = "duration";
  if (!ms) return node;
  const total = Math.round(ms / 1000);
  const minutes = Math.floor(total / 60);
  const seconds = String(total % 60).padStart(2, "0");
  node.textContent = `${minutes}:${seconds}`;
  return node;
}

function playSessionId() {
  // crypto.randomUUID() is secure-context-only (HTTPS / localhost) and is
  // undefined over plain http on a LAN IP — the MVP's intended deployment.
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

// TODO(webapp): Sonic Adventure, Mixes, Similar, library browse, offline,
// service-worker installability, and TLS hardening are intentionally out of
// this first login -> radio -> playback slice.
