# AGENTS.md — working agreement for AI agents on emby-sonic

This file is read automatically by Codex (and other agents) and defines how to
work in this repo. Follow it the same way regardless of which agent or machine
you run on. **`docs/spec.md` is the source of truth** for design decisions —
read it before making architectural changes, and update it when you make one.

---

## What this is

Self-hosted neural audio analysis for Emby — a privacy-first, open-source
equivalent of Plexamp's Sonic Analysis (sonic similarity, radio, adventure,
auto mixes), plus companion mobile apps.

- **Coordinator** (Python/FastAPI): owns SQLite + FAISS + Emby auth; hands out
  analysis work on a lease. Runs on the small Emby box.
- **Workers** (Python): claim tracks, stream audio from Emby, embed (PANNs
  CNN14), post results back. Run anywhere (GPU box on the LAN).
- **Emby plugin** (`plugin/`, C#/.NET 8): thin config/proxy page in Emby.
- **Android app** (`android/`, Kotlin/Compose): browse + sonic features.
  Package `guru.liquid.embysonic`. Phases 1 & 2 complete; Phase 3 in progress.

## Repo layout

- `main.py`, `config.py`, `worker.py`, `analysis/`, `api/` — coordinator/workers.
- `plugin/` — Emby C# plugin (Emby SDK DLLs in `plugin/lib/` are gitignored;
  not redistributable).
- `android/` — the Android app (monorepo). Build details below.
- `docs/spec.md` — design source of truth + resolved decisions + milestones.

---

> If you are reading this file, you are correctly rooted in the repo. If your
> AGENTS.md came from `C:\Users\liqui` (the home dir) instead, your working
> directory is wrong — set the project/working folder to
> `C:\Users\liqui\dev\emby-sonic` so repo rules load.

## Where you're running — pick the right mode

Figure out your capabilities FIRST, then operate accordingly. Probe before you
assume: run `adb devices` and `git status` on the **host** (not a sandbox).

- **Mode A — host command execution on liquidHulk** (you can run real shell
  commands on the host): you CAN build, verify, push, and sync. Do the **full
  routine** below end to end. **The Android verify loop is pure `adb` — install,
  `input tap`, `screencap` — so you do NOT need Computer Use / GUI automation
  for it.** (`adb` is on PATH for newly-opened shells; if `adb` isn't found,
  open a fresh terminal or call it by full path
  `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`. The emulator AVD is
  `Pixel_3a_API_36` — launch it with
  `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -avd Pixel_3a_API_36`
  if `adb devices` is empty.) Use a terminal for everything; Computer Use is
  optional and currently unreliable on this machine — don't depend on it.
- **Mode B — cloud sandbox only** (no host execution, no LAN): you CANNOT build
  the Android app, run the emulator, or reach Emby/the coordinator at
  192.168.1.9. Implement the code, open a PR on a feature branch, and put a
  precise build-and-verify checklist in the PR description for liquidHulk to run.
  **Never claim something is verified on-device when you couldn't run it.**

Note: run host commands **unsandboxed** — the repo is registered as a git
`safe.directory`, but a sandbox may still block LAN/device access.

## Build & verify — Android (the active work)

Toolchain lives on **liquidHulk** (the main dev PC). On the NAS code-server
checkout you can edit and commit, but build/emulator verification happens on
liquidHulk.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # bundled JDK 17
cd C:\Users\liqui\dev\emby-sonic\android
./gradlew :app:assembleDebug
```

- Android SDK at `%LOCALAPPDATA%\Android\Sdk` (platform android-36 only, no
  cmdline-tools). Standalone Gradle dist at `C:\Users\liqui\dev\tools\gradle-8.11.1`.
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

**Verify UI changes on a device — don't ask the user to check by hand.**

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
# Emulator AVD: Pixel_3a_API_36 (often left running — `adb devices` first)
& $adb install -r android\app\build\outputs\apk\debug\app-debug.apk
& $adb shell input tap X Y                 # screen is 1080x2220
& $adb exec-out screencap -p > shot.png    # DOWNSCALE by half before reading (image-size limit)
```

The emulator reaches the LAN directly (Emby + coordinator at 192.168.1.9).

### Real device — Kaj's Pixel 8 Pro over wireless ADB (preferred for audio)

The **emulator's software audio codecs are unreliable** for crossfade and
equalizer work (two simultaneous decoders during a blend exhaust them → silent
playback). **Judge anything audio/crossfade/EQ on the real phone, not the
emulator.** Non-audio UI can still be emulator-tested (with crossfade OFF).

The Pixel 8 Pro is **paired** for wireless debugging (pairing is permanent; only
the connection port changes). To install a build:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices    # phone shows as adb-39151FDJG00670-...._adb-tls-connect._tcp when connected
& $adb -s "<phone-device-id>" install -r android\app\build\outputs\apk\debug\app-debug.apk
```

If it's not connected (sleep / Wi-Fi blip), ask Kaj for the current **IP:port**
from the phone's *Settings → Developer options → Wireless debugging* screen, then
`& $adb connect 192.168.1.151:<port>` (no re-pair needed). First pairing only:
"Pair device with pairing code" → `adb pair 192.168.1.151:<pairport> <code>`.
On first launch the app needs login (Emby URL/creds + coordinator URL).

## Coordinator / workers ops

- Coordinator runs on **liquidBee (192.168.1.9:8765)** as a Windows scheduled
  task `EmbySonicCoordinator` (auto-start at boot). `Start/Stop/Get-ScheduledTask`.
  The task must run `C:\Users\liqui\dev\emby-sonic\.venv\Scripts\python.exe`
  with working directory `C:\Users\liqui\dev\emby-sonic`; plain system `python`
  on liquidBee does not have the coordinator dependencies (`fastapi`, etc.).
- Health check: `curl http://192.168.1.9:8765/sonic/status` (returns
  "Not authenticated" without a token — that just means it's up).
- After a coordinator code change: push from dev → `git pull` on liquidBee →
  kill PID on 8765 → `Start-ScheduledTask -TaskName EmbySonicCoordinator`.
- If the coordinator is unreachable, confirm the scheduled task is `Running`,
  check that `8765` is listening, and test manually from liquidBee with
  `.\.venv\Scripts\python.exe main.py` before recreating the task.
- Emby: 192.168.1.9:8096 (v4.10, ServerName LIQUIDBEE).

---

## Git workflow & backup routine — FOLLOW THIS EVERY TIME

1. **Branch:** work on `master` (default). Keep the working tree clean.
2. **Commit only when the work is verified** (built + device-checked for UI).
   End every commit message with a trailer identifying the agent, e.g.
   `Co-Authored-By: <your-agent> <email>`.
3. **Push** to `origin` (`github.com/liquidguru/emby-sonic`, PRIVATE) once the
   user approves, or as part of the agreed backup routine.
4. **Keep the code-server checkout in sync.** A second checkout lives on the NAS
   at `/volume1/docker/emby-sonic` (visible in code-server :8443). After any
   push, run `git -C /volume1/docker/emby-sonic pull` so both checkouts match.
5. **GitHub auth:** liquidHulk uses Windows Credential Manager (HTTPS); the NAS
   uses SSH with the root key (`git@github.com:liquidguru/emby-sonic.git`).
6. **Leave a handoff** at the end of a session: update `docs/spec.md` (decisions
   + milestone status) so the next agent — Codex or Claude — can resume cold.
   `docs/spec.md` is the durable handoff; this repo is the single source of state.

The backup = GitHub (origin/master) + the in-sync NAS code-server checkout.
There is no other copy; don't rely on a local-only commit.

---

## Conventions & hard-won gotchas

- **Curl the real endpoint shape before writing a DTO — never guess.** Both the
  coordinator's own API and Emby's. e.g. SonicStatus is
  `total_tracks/analysed_tracks/pending_tracks/scan_running/scan_progress`.
- **`.gitignore` is anchored** (`/data/`, `/models/`) so it doesn't swallow
  `android/.../data/`. After scaffolding any new dir into the repo, run
  `git ls-files <newdir>` to confirm files are actually tracked, not just on disk.
- **Emby auth:** validate user tokens against `/System/Info` (Emby 4.10 returns
  500 on `/Users/Me` for a bare `X-Emby-Token`).
- **Audiobooks** are a separate Emby library and have **no sonic features** and
  **no cover art** on album/author items (resolve covers from a child chapter's
  Primary image). Music vs audiobooks are scoped by library/ParentId.
- **Android nav:** two bottom-nav tabs must not share one parameterized route
  pattern (save/restore-state collides); use distinct route prefixes. Plain
  forward `navigate` for drill-down detail levels is fine.
- **Don't bundle Python native binaries in the C# plugin** — it stays a thin proxy.

## Secrets & local config (NOT committed)

- `.env` (gitignored) holds `EMBY_API_KEY` for curl testing. `.env.example`
  shows the shape. Never commit real keys or put them in this file.
- Kaj's Emby UserId: `a356b428d6ae419ea8ef9d7d92bd60ff` (id only, not a secret).

## Current state (2026-06-14)

Phases 1 & 2 complete. Phase 3 (Android "liquidWave") is well advanced and
running on Kaj's Pixel 8 Pro. `docs/spec.md` has the full milestone list; in
short, **shipped and verified on-device:**

- Browse (artists/albums/tracks, authors/books), drill-down, A–Z picker.
- Playback: Media3/ExoPlayer, queue, shuffle/repeat, mini player, MediaSession +
  shade notification, audio focus / becoming-noisy / wake mode.
- Audiobook resume (durable via Emby UserData; `/Audio/{id}/stream` for offset).
- Music **crossfade** with synced **artwork cross-dissolve** (Now Playing + mini
  player). Crossfade only blends direct-play tracks (transcoded → normal cut).
- **Equalizer** (audiofx, shared session, presets, persistence).
- Sonic **mixes** (list/detail/play, per-mix refresh, save-as-playlist, build).
- **Track Radio**, **Sonic Adventure** (A→B), **Stations** (Library/Random
  Album/Decade radios), **Recent plays**, **Search** (music + audiobooks + Home
  all-scopes).
- Home customization (sections, order, compact cards).

**Next candidates** (see spec "M4 — Remaining"): sonic-similar sidebars on
Artist/Album detail; wire up or hide the Guest DJ toggle; mini-player dissolve
polish; coordinator-side dedupe of the adventure walk; offline prefetch buffer.

**Known constraints:** judge audio/crossfade/EQ on the **real phone** (emulator
codecs are unreliable for blends). Coordinator must be running on liquidBee for
mixes/radio/adventure/search-of-sonic features (Emby browse/search/playback work
without it).
