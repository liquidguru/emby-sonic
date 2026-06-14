# Review prompt (paste into Codex / another agent)

Use this when you want a fresh agent to review a chunk of work. Update the commit
range to whatever you want reviewed. The agent should be rooted at the repo root
(`C:\Users\liqui\dev\emby-sonic`) so it reads `AGENTS.md` automatically.

---

You are reviewing the emby-sonic repo. **Read `AGENTS.md` and `docs/spec.md`
first** — they define the working agreement, architecture, dev environment, and
current state. Do not make code changes, commit, push, or operate the
emulator/phone until you've reported findings.

**Scope:** review the commit range `fec1b96..HEAD` on `master`. (Adjust this range
for future reviews.) That range covers a large Android (liquidWave) feature push
plus a few coordinator changes:

- Playback correctness: removed durable resume for music, Played-on-completion,
  audio focus / becoming-noisy / wake mode, transcoded-seek via `/stream` + a
  fresh `PlaySessionId`, media notification via a self-connected `MediaController`,
  crossfade `fadePlayer` lifecycle (create-per-blend + release), seek-into-tail
  crossfade suppression.
- Crossfade **artwork cross-dissolve** (Now Playing + mini player), position-driven
  via `crossfadeOutgoingAlpha`.
- **Equalizer** (`AudioEffectsController`, `android.media.audiofx.Equalizer`,
  shared audio session across both ExoPlayers, DataStore persistence, session
  broadcast for external EQ apps).
- **Search** (`ui/search/`, Emby `SearchTerm`, music/audiobook/all scopes,
  library-scoped), **Track Radio** (Now Playing tab), **Sonic Adventure**
  (`ui/adventure/`, `/sonic/adventure`), **Stations** strip, **Recent plays**,
  Home customization, mix cover hydration + build-state polling.
- Coordinator: `build_adventure` now includes endpoints; `MixOut.cover_track_id`;
  `/sonic/library/build-state`; `build_mixes` offloaded to `asyncio.to_thread`.

**Assess, findings first, ordered by severity, with exact `file:line` references:**

1. Android architecture & code quality; ViewModel/state/coroutine correctness.
2. Media3 playback: the dual-player crossfade and its cancellation paths, the
   shared audio session + equalizer interaction, audio focus, lifecycle/races,
   the seek/`PlaySessionId` logic.
3. Sonic Adventure correctness (bookending, title+artist dedupe, length
   over-request/even-sample) and search (debounce, library scoping, scope/nav
   behaviour).
4. Navigation: the bottom-nav save/restore-state handling for Search/Adventure
   overlays and drill-down detail.
5. Coordinator changes (adventure, build-state, mix covers, `to_thread`) where
   they affect the app.
6. Bugs, races, leaks, performance, and **missing test coverage** (the repo has
   no tests — propose the highest-value ones).
7. Whether `docs/spec.md` and `README.md` match the implementation.

**Environment constraints:** build on liquidHulk (`$env:JAVA_HOME` = Android
Studio JBR; `cd android; ./gradlew :app:assembleDebug`). The Pixel_3a emulator's
software audio codecs are unreliable for crossfade/EQ — judge audio only on the
real Pixel 8 Pro (wireless ADB, see `AGENTS.md`). The coordinator runs on
liquidBee `192.168.1.9:8765`.

**Return:** findings by severity with file/line refs; verified problems vs
hypotheses; missing tests & residual risks; a prioritized recommendation list;
and questions where product intent is unclear. Be candid and specific.
