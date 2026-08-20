# liquidWave / Emby Sonic — Tester Quickstart

> **Private beta — repo access is by invite.** If you've found this page before
> reaching out, head back to the forum thread and reply or send a DM first. Once
> you're confirmed I'll send you the repo link, APK, and plugin zip directly.

Thanks for testing! This is a private beta. liquidWave brings Plexamp-style
**sonic analysis** (similar tracks, track radio, sonic adventures, auto-curated
mixes, a Guest DJ) to **your own Emby server** — fully self-hosted, nothing leaves
your network. See [Privacy & security](#privacy--security) below for exactly what
runs where.

You'll stand up three pieces, all on machines you already own:

1. **Coordinator** — a small service that holds the analysis index (runs anywhere,
   even a NAS).
2. **Emby plugin** — tells Emby to notify the coordinator when music is added.
   Ships as a **prebuilt zip** — no compiling.
3. **Worker** — does the actual audio analysis (run it on a box with spare CPU, or
   a GPU for speed). Streams audio from Emby over your LAN — no file shares needed.

Then you install the **Android app** and point it at your Emby server + coordinator.

---

## Fastest path (most testers: NAS/Docker + Android)

If you just want to get moving, this is the common case — a NAS or Linux box
running Docker, plus your phone. Everything below is explained in full further
down; come back here if you get stuck.

```bash
git clone https://github.com/liquidguru/emby-sonic.git
cd emby-sonic
cp .env.example .env
# Edit .env: set EMBY_URL and EMBY_API_KEY (Emby Dashboard → Advanced → API Keys)
chmod +x ./install.sh
./install.sh
```

Then: install the Emby plugin ([step 3](#3-install-the-emby-plugin-prebuilt--no-compiling)),
kick off a library scan and run a worker ([step 4](#4-analyse-your-library)),
and install the Android app ([step 5](#5-install-the-android-app-liquidwave)).

> **In a hurry, or just want to try the app first?** You can install the
> Android app ([step 5](#5-install-the-android-app-liquidwave)) on its own,
> skipping everything above — it's a fully working Emby player by itself.
> You just won't have the sonic features (mixes, radio, similar tracks) until
> the coordinator's running. See
> [You can start with just the app](#you-can-start-with-just-the-app) below
> for exactly what works without it.

Running Windows instead, or want a GPU worker on a separate box? See
[Pick your setup](#pick-your-setup) below.

---

## Prerequisites — check before you start

- [ ] An **Emby server you administer** (you can install plugins and restart it),
      **version 4.8 or newer** (the plugin won't load on older servers).
- [ ] An **Emby API key** — Emby Dashboard → Advanced → **API Keys** → new key.
- [ ] A machine to run the **coordinator** (Docker, *or* Python 3.11+). A NAS is fine.
- [ ] A machine to run a **worker** (can be the same box; a GPU makes it ~2× faster).
- [ ] An **Android phone**, Android 8 / API 26 or newer.
- [ ] Everything on the **same LAN** (or reachable over your own VPN — nothing here is
      meant to be exposed to the public internet).
- [ ] **Patience for the first scan.** Analysing a library is a one-time job:
      ~**10–15 s/track on a GPU**, ~**20–30 s/track CPU-only**. A few thousand tracks
      is an overnight run; after that it's automatic and incremental as you add music.

---

## You can start with just the app

The coordinator (the sonic backend) is **optional** — the Android app is a fully
working **Emby music + audiobook player on its own**. You can install the APK,
leave the coordinator URL blank, and have a usable player on day one, then stand up
the analysis backend later at your own pace (no need to wait out the first scan
before you can play anything).

**Works with no backend at all (straight Emby):** browse artists / albums / tracks /
genres and audiobooks; full playback with queue, shuffle/repeat, crossfade, the
in-app equalizer, mini player, the Now Playing widget, Google Cast, and **Android
Auto**; durable audiobook resume + speed control; Emby playlists; Recent Plays;
**offline playlist downloads** (download a playlist's original files, then browse +
play it with no network); search; and the metadata Stations (Library / Random Album /
Decade radios).

**Needs the coordinator (the sonic features):** Sonic Mixes, Track Radio, Similar
tracks / artists / albums, Sonic Adventure, the Guest DJ, the Artist Mix Creator, and
**volume normalisation** (levels playback loudness across tracks — it uses the loudness
the backend measures during analysis; toggle in Settings, on by default). Without the
backend these simply stay empty / inactive — the app degrades gracefully, it doesn't
break.

> **Audiobooks aren't analysed.** Only your **music** library is scanned for sonic
> features — audiobooks (a separate Emby library) are left out, so spoken word never
> turns up in Track Radio or Similar. You still browse and play them as normal.

---

## Pick your setup

liquidWave is **hardware- and OS-agnostic by design.** The coordinator is tiny and
runs anywhere — Windows, Linux, macOS, or a Docker container on an ARM NAS. The
heavy lifting (the neural analysis) is a separate **worker** you run wherever you
have spare horsepower. A GPU makes it faster; **it is not required** — CPU works, it
just takes longer. Because the two talk over plain HTTP and the worker streams audio
from Emby, they can be the same machine or three different boxes, on any mix of OSes.

Find yourself below:

| Your setup | Coordinator | Worker |
|---|---|---|
| **Synology / QNAP / ARM NAS** runs Emby | Docker on the NAS | Your desktop / gaming PC (its GPU if it has one) |
| **One Windows box** does everything | Native Python on it | Same box — CPU (run overnight) or its GPU |
| **Linux server + a separate GPU rig** | On the Linux server | On the GPU box (`--gpus all`) |
| **No GPU anywhere** | Either | Any box, CPU-only — just slower |

> **You should never have to compile a native dependency.** Cross-platform audio
> libraries are genuinely fiddly (Essentia has no Windows/ARM wheel, PyTorch needs the
> right build, the model is ~327 MB) — so the defaults handle it for you: CPU-only
> PyTorch wheels, Essentia made optional (librosa fills in), the model auto-downloads
> on first run, and Docker images for the NAS/Linux crowd.

---

## 1. Get the code + configure

```bash
git clone https://github.com/liquidguru/emby-sonic.git
cd emby-sonic
cp .env.example .env      # then edit it (next step)
```

Set in `.env`:

```ini
EMBY_URL=http://<your-emby-host>:8096
EMBY_API_KEY=<your-emby-api-key>
WORKER_SECRET=<any-long-random-string>     # shared secret for workers (see Security)
```

If you access the **web app** from outside your LAN (a public domain / reverse proxy),
also set `EMBY_URL_EXTERNAL=https://<your-public-emby-domain>` — the web app streams
audio straight from Emby to your browser, so it needs Emby's externally-reachable
address when you're not on the LAN, or streaming will fail.

---

## 2. Run the coordinator

**Docker (easiest — great on a NAS):**

Run the guided installer — it detects your GPU and CUDA version, pulls the right prebuilt images, and starts everything:

```bash
chmod +x ./install.sh
./install.sh
```

Or pull and start manually:

```bash
docker compose up -d coordinator
```

**Or native Python (3.11+) — no Docker or Linux box needed, works the same on
Windows, macOS, or Linux:**

```bash
pip install torch --index-url https://download.pytorch.org/whl/cpu
pip install -r requirements.txt
python main.py
```

> **On Windows specifically:** if this is a "one Windows box does everything"
> setup (see the table above), there's a wrapper that does the above for you
> and also runs the test suite: `.\dev.ps1 bootstrap` (first run) or
> `.\dev.ps1 test`. It creates its own `.venv`, so it won't touch anything you
> already have installed. See
> [README → Setup](../README.md#setup) for details.

Either way the coordinator listens on **`http://<host>:8765`**. Check it:

```bash
curl http://<host>:8765/docs      # interactive API docs should load
```

### Updating the coordinator later

```bash
git pull
```

Native: restart the coordinator. Docker: `docker compose up -d --build`.

> **If you run it as a Windows scheduled task, also re-run the installers:**
>
> ```powershell
> .\deploy\coordinator-install.ps1
> .\deploy\worker-install.ps1 -Mode service
> ```
>
> Do this **once** after updating to beta.35 or later, even if everything looks
> fine. Before beta.35 the task launcher left the old process running when the
> task was stopped, so it kept its port and every later restart quietly failed
> to take effect — a coordinator could run for weeks on stale code while
> reporting perfectly healthy. Re-running the installer clears that and
> replaces the launcher with one that can't do it again.
>
> To confirm you're not affected, check nothing outlives a stop:
>
> ```powershell
> Stop-ScheduledTask -TaskName EmbySonicCoordinator
> Get-NetTCPConnection -LocalPort 8765 -State Listen   # should return nothing
> Start-ScheduledTask -TaskName EmbySonicCoordinator
> ```

---

## 3. Install the Emby plugin (prebuilt — no compiling)

Emby has no plugin-zip upload in its dashboard, so the plugin DLL is dropped into
Emby's plugins folder by a script. You don't need the .NET SDK — grab the
**prebuilt release zip**:

1. Download `EmbysonicPlugin_<version>.zip` from the
   [GitHub Releases page](https://github.com/liquidguru/emby-sonic/releases)
   (or the link I send you).
2. Unzip it **on your Emby host** and run the bundled installer — it auto-detects
   Emby, copies the DLL into `programdata/plugins/`, and restarts Emby:

   ```powershell
   ./install.ps1     # Windows
   ```
   ```bash
   ./install.sh      # Linux
   ```

   (Or copy `EmbysonicPlugin.dll` into `…/Emby-Server/programdata/plugins/` by hand
   and restart Emby.)
3. In Emby: **Dashboard → Plugins → Emby Sonic**. Set:
   - **Python Service URL** → your coordinator's `http://<host>:8765`
   - **API Key** → your Emby API key (lets the plugin auto-scan on new imports)

   …and Save.

> Building from source is only needed if you're modifying the plugin — see
> [README → Phase 2](../README.md#phase-2--emby-plugin).

---

## 4. Analyse your library

```bash
# Queue every track (no audio work yet — just populates the list)
curl -X POST http://<coordinator-host>:8765/sonic/library/scan \
  -H "X-Emby-Token: <your-emby-api-key>"

# Run a worker (this machine or any other on the LAN; auto-detects CUDA)
COORDINATOR_URL=http://<coordinator-host>:8765 \
WORKER_SECRET=<same-secret-as-.env> \
WORKER_ID=worker-1 \
python worker.py
```

Run more workers in parallel for speed. Watch progress:

```bash
curl http://<coordinator-host>:8765/sonic/status -H "X-Emby-Token: <your-emby-api-key>"
```

Sonic features (mixes, radio, similar) light up as tracks finish — you don't have to
wait for 100 %. For hands-off operation later, install the worker as a service
(Windows scheduled task or Linux systemd) — see
[README → Automatic analysis](../README.md#automatic-analysis-worker-as-a-service).

### Already analysed before beta.18? (crossfade edge trimming)

Crossfade can now skip a song's silent tail so the blend lands on the music. That
needs one extra measurement per track. **New music picks it up automatically** —
this is only to fill in a library analysed before beta.18:

**Docker** — run it in the **worker** container, with the database volume attached:

```bash
docker compose run --rm -v emby-sonic-data:/app/data \
    worker python tools/backfill_edges.py

# try a small batch first:
docker compose run --rm -v emby-sonic-data:/app/data \
    worker python tools/backfill_edges.py --limit 50
```

**Bare metal** — run it wherever you installed `requirements.txt`:

```bash
python tools/backfill_edges.py            # whole library, resumable
python tools/backfill_edges.py --limit 50 # try a small batch first
```

> **Why the worker and not the coordinator?** (#40) It needs librosa *and* the
> database, and neither container has both: the coordinator image ships without
> librosa on purpose — that's what keeps it small enough for an ARM NAS — and the
> worker has librosa but doesn't normally mount the database. Attaching the volume
> to the worker gives it both. Running it in the coordinator fails with
> `ModuleNotFoundError: librosa`.
>
> **Coordinator and worker on different hosts?** A Docker named volume only
> exists on its own host, so run this one-off container **on the coordinator's
> host** (where the database volume is), not on your separate worker box — even
> though your long-running worker lives elsewhere.

It is **not** a re-analysis — the neural model never loads, so it's ~10× cheaper.
Measured on an Intel N100 streaming from Emby over LAN: **~42 tracks/minute**
(~1,000 tracks ≈ 25 min, ~5,000 ≈ 2 h, ~25,000 ≈ 10 h). Safe to stop and re-run;
it only touches tracks that still need it, skips audiobooks, and is safe to run
while the coordinator is live.

Entirely optional: without it, crossfade simply behaves as it did before. The
transition-glitch fixes in beta.18 are in the app and need no backfill.

---

## 5. Install the Android app (liquidWave)

Grab `liquidWave-<version>.apk` from the
[Releases page](https://github.com/liquidguru/emby-sonic/releases) (or the link I
send you), or build it yourself — see
[README → Phase 3](../README.md#phase-3--android-app-liquidwave). To install: copy
the APK to your phone, tap it, and allow "install from unknown sources" when
prompted.

> The APK is a **release build** — R8-minified and resource-shrunk, the same
> optimised code a Play Store build would run — and since **beta.19** it's signed
> with a **private release key** (`CN=liquidguru`). Verify with
> `apksigner verify --print-certs <apk>`. Only install builds you got directly from me.

> **Want Android Auto?** Sideloaded apps are hidden from Android Auto by default.
> To use liquidWave in the car, enable AA developer mode once on the phone:
> Android Auto settings → tap **Version** repeatedly to unlock *Developer settings*
> → enable **Unknown sources**. liquidWave then shows up in the AA app list.

On first launch enter:

- **Emby server URL** + your Emby username/password
- **Coordinator URL** (`http://<coordinator-host>:8765`)

The phone must be on the **same LAN** as Emby and the coordinator.

---

## Privacy & security

This is built privacy-first: **it's your music, your server, your network.** Nothing
about your library, listening, or credentials is sent to me or any third party.

**Completely non-destructive — your library is never modified:**

- Your music and audiobook **files are never changed, re-tagged, moved, renamed, or
  deleted.** The analysis only ever **reads** (streams) audio from Emby; its results
  go into the coordinator's **own separate database**, not back into your files or
  Emby's library metadata.
- The only things ever written are the **normal playback state any Emby app writes**
  — play counts and resume position (so playback resumes where you left off) — and an
  Emby **playlist only when you explicitly tap "save"** on a mix. It never auto-creates
  or alters existing playlists.

**Fully self-hosted, no cloud:**

- All three pieces — coordinator, worker, plugin — run on **your own hardware**.
  There is no Emby Sonic account, no SaaS backend, no analytics, and **no telemetry**.
- The audio analysis happens **locally** on your worker. Embeddings and the
  similarity index live in a **SQLite file + FAISS index on your coordinator** — they
  never leave it.
- The **only** outbound internet connection the system makes is a **one-time download
  of the neural model** (~327 MB PANNs checkpoint) plus normal `pip`/Docker pulls. After
  that the analysis stack can run fully **air-gapped**. No audio or metadata is uploaded.

**Stays on your LAN:**

- Workers fetch audio straight from **Emby's own HTTP API** over your LAN — no SMB/NFS
  shares, no extra ports opened, nothing new exposed.
- The coordinator is designed to live **inside your network**. It speaks plain HTTP
  (no TLS of its own) and is meant to sit behind your LAN/VPN — **don't port-forward
  `:8765` to the public internet.** If you want remote access, use your existing VPN or
  reverse proxy, exactly as you'd protect Emby itself.

**Authentication & secrets:**

- Every user-facing coordinator route requires an **`X-Emby-Token`**, validated against
  *your* Emby server (`/System/Info`) — the coordinator trusts Emby, not its own user list.
- Workers use a **separate `WORKER_SECRET`** (in `X-Worker-Token`), so the worker
  credential can be rotated independently of your Emby API key.
- The Emby API key is **no longer embedded in audio download URLs** (closed in hardening).

**Android app hardening:**

- Login **defaults to `https`** so credentials aren't sent in cleartext by accident
  (a plain-HTTP LAN server still works if you type an explicit `http://` URL).
- **`allowBackup=false`** — your Emby session token is not swept into cloud or `adb`
  backups.
- The APK is **R8-minified and resource-shrunk**, like a Play Store build.
- Since **beta.19** the APK is **signed with a private release key** (`CN=liquidguru`,
  RSA 4096), not the Android debug key. Verify any build yourself — no password needed:
  `apksigner verify --print-certs <apk>` should show `CN=liquidguru` (earlier betas
  showed `CN=Android Debug`).

**Permissions — what the app asks for, and why:**

- liquidWave requests only: **internet + network state** (reach Emby), **foreground
  service + wake lock** (keep playback and the media notification alive), and
  **notifications**. **No location, no contacts, no microphone** — nothing like that.
- **Notifications** are requested **only when you start a download**, so the app can
  tell you when the download finishes. Say no and downloads still work — you just
  won't get the "download complete" message. (The playback controls in your shade /
  lock screen / car are a foreground-service notification and appear either way.)
- On **Android 16** you may see a **"Local network"** permission. That's the system's
  new local-network access — liquidWave uses it to reach your Emby server on your LAN.
  It is **not** GPS/location; the app declares no location permission at all.

**Honest beta caveats (transparency):**

- The Emby token is stored in **app-private storage on the phone, encrypted at rest** using Android Keystore.
- The coordinator's CORS policy is currently permissive — harmless for a LAN-only
  service, and another reason not to expose `:8765` to the internet.

---

## What to try (and what to report)

Once some of your library is analysed:

- Open a track → **Track Radio** and **Similar** tab.
- **Mixes** tab — auto-curated mixes; try "refresh" on one, and "save as playlist".
- **Sonic Adventure** — pick a start and end track, hear it morph between them.
- **Artist Mix Creator** — pick an artist, add a few of the similar artists it
  suggests, then **build** and hear the shuffled cross-artist mix.
- **Stations** on Home, **Guest DJ** queue injection, **crossfade**, the in-app **EQ**.
- **Customize Home** (sliders icon, top-right) — reorder or hide any row, **including
  Stations**, and toggle small cards. Your layout is remembered.
- **Offline downloads** — open a playlist **or an audiobook**, tap the **download**
  icon (top-right), let it finish, then turn on **airplane mode**: the Home bar's
  cloud goes red, the "Recent plays" row drops away, and your downloads appear in a
  **Downloads row on Home** (and Settings → Downloads, split into Playlists /
  Audiobooks) to play with no signal. Audiobooks resume where you left off even
  offline, then sync your position back to Emby when you reconnect. Downloads are
  **Wi-Fi-only by default** (toggle in Settings → Downloads).
- **Volume normalisation** (Settings → Volume normalisation, on by default) — play a
  quiet track and a loud one back to back, then toggle it off and replay: with it on
  they sit at a similar volume; off, the loud one jumps. (Needs the backend, since it
  uses the loudness measured during analysis.)
- **Crossfade** (Settings → Crossfade) — blends one song into the next. Most songs
  fade out at the end, so a blend that starts a fixed 6 s before the file ends spends
  much of that mixing near-silence. With **Skip silent endings** on (default), the
  blend instead lands on where the music actually stops — see the backfill note under
  [Analyse your library](#4-analyse-your-library) if your library was analysed before
  this landed.
- **Cast** to a Chromecast/Android TV/SHIELD if you have one.

**Please report:** what you tried, what device/Android version, what you expected vs.
what happened, and any crash. Screenshots help. Rough edges and "this felt slow/weird"
notes are exactly what I'm after.

### Known limitations (beta)

- **Android only** for now (iOS is a later phase).
- First-time library analysis is slow (see Prerequisites). It's a one-time cost.
- **No crossfade on tracks Emby has to transcode** (WMA is the common one). Blending
  those reliably isn't possible — a second stream of the same track fights the first
  and the track can restart — so they get a clean cut instead. The silence-trimming
  runs as part of a crossfade, so a skipped transition can also leave an audible gap.
  Improving that is on the list.

Thanks again — your feedback shapes what ships. 🌊
