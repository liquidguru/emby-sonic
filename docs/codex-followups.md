# Codex follow-ups (emby-sonic)

Self-contained tasks parked for Codex. Each is independent — do them in any order,
one PR per task. Repo conventions: commit trailer
`Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`; `.gitattributes` enforces
LF; never commit `.env`, `data/`, `worker.log`, or `worker_run.generated.ps1`.

Context you need:
- **Coordinator**: FastAPI on `:8765` (`main.py`), SQLite at `data/sonic.db`, config in
  `config.py` (pydantic settings from `.env`). Auth in `api/auth.py`:
  `X-Emby-Token` (user routes, validated against Emby `/System/Info` or `== emby_api_key`)
  and `X-Worker-Token` (worker routes, currently `== emby_api_key`).
- **Worker**: `worker.py` — stateless loop, claims pending tracks, streams audio from
  Emby, embeds (PANNs CNN14, GPU auto-detect), posts results. Env: `COORDINATOR_URL`,
  `WORKER_ID`, `WORKER_BATCH`; reads `EMBY_URL`/`EMBY_API_KEY` from `.env`.
- **Windows worker install**: `deploy/worker-install.ps1` (`-Mode service|idle`).
- **Android app**: `android/` (Kotlin, Compose, Media3, Hilt, DataStore, Coil, Cast).
- Security review history lives in `docs/spec.md` (H1/H2 done; H3, M4, M6, L1, L3 deferred).

---

## Task A — Cross-platform worker deploy (Docker + Linux systemd)

**Why:** `deploy/worker-install.ps1` is Windows-only; the project is meant to be
host-agnostic. Give Linux/Docker users the same one-command hands-off worker.

**Do:**
1. Confirm the root `Dockerfile` can run the worker: `docker run ... python worker.py`
   with `-e COORDINATOR_URL -e EMBY_URL -e EMBY_API_KEY`. Add a `worker` service to a
   `docker-compose.yml` (separate from the coordinator service) with a named volume for
   the panns model cache (`/root/panns_data` or wherever `panns_inference` writes), so
   the ~300MB checkpoint isn't re-downloaded each run. Document GPU use via the nvidia
   runtime (`--gpus all`) as optional.
2. Add `deploy/worker-install.sh` + a `systemd` unit (`deploy/emby-sonic-worker.service`)
   mirroring PS1 `service` mode: always-on, restart on failure, `COORDINATOR_URL`
   configurable, runs from the repo venv.
3. Update README's "Automatic analysis" section with the Linux/Docker equivalents.

**Acceptance:** worker container drains the queue against a running coordinator; systemd
unit installs/enables/starts and survives reboot; README covers Windows + Linux + Docker.

---

## Task B — Dedicated WORKER_SECRET (security L3)

**Why:** workers currently authenticate with the same `EMBY_API_KEY` used for user
routes. Split them so a worker credential can be rotated/scoped independently.

**Do:**
1. `config.py`: add `worker_secret: str = ""`. Effective value = `worker_secret` if set,
   else fall back to `emby_api_key` (back-compat — no existing deploy breaks).
2. `api/auth.py`: the worker-token check (the `verify_worker_token` path, ~line 39) must
   accept the effective worker secret.
3. `worker.py:37`: `_HEADERS` uses the effective worker secret.
4. Document `WORKER_SECRET` in README env table.

**Acceptance:** with `WORKER_SECRET` set, workers authenticate with it and the user
`EMBY_API_KEY` is rejected on worker routes; with it unset, current behaviour is
unchanged.

---

## Task C — Encrypt the Emby token at rest on Android (security H3)

**Why:** the app persists the Emby access token (and server URL) in DataStore in
plaintext; on a rooted/backed-up device it's readable. (`allowBackup=false` is already
set — H2.)

**Do:** locate the credential/token persistence under `android/.../data/` (the DataStore
that stores the Emby token after login — start from `AuthRepository`). Encrypt at rest
using the Android Keystore (e.g. `androidx.security:security-crypto`
EncryptedSharedPreferences/EncryptedFile, or a Keystore-derived key wrapping the DataStore
value). Migrate any existing plaintext value on first run.

**Acceptance:** login persists an encrypted token; logout clears it; app restart stays
logged in; no plaintext token in `adb run-as` shared_prefs/datastore dumps.

---

## Task D — R8 minify + release signing on Android (security/release L1)

**Why:** ship a shrunk, obfuscated, properly-signed release build.

**Do:** in `android/app/build.gradle(.kts)` enable `minifyEnabled true` +
`shrinkResources true` for `release`; add ProGuard/R8 keep rules for Media3/ExoPlayer,
Hilt, Coil, and the Cast SDK (`proguard-rules.pro`). Add a `release` signing config that
reads keystore path/passwords from `~/.gradle/gradle.properties` or env (never commit the
keystore or secrets; add to `.gitignore`).

**Acceptance:** `./gradlew assembleRelease` produces a signed, minified APK that installs
and runs with casting, the Now Playing widget, themes, and playback all intact.

---

## Task E (optional) — Broken-track maintenance tooling

**Why:** ~616 tracks sit in `analysis_status='error'` (mostly Emby HTTP-500-on-download =
stale/missing/miscategorised library files). They never retry (workers only claim
`pending`).

**Do:** add a small maintenance script (or admin route) to (a) export error tracks to CSV
(`id,title,artist,album,file_path,error` from the `tracks` table in `data/sonic.db`), and
(b) requeue selected/all errors (`analysis_status` `error`→`pending`) so a worker retries
them. Keep it auth-gated if exposed as a route.

**Acceptance:** CSV export lists the error tracks; requeue flips status and a running
worker re-attempts them.
