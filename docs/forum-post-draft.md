# Forum post — DRAFT

> Working draft for the Emby forum "looking for testers" post. Voice = Kaj, first
> person, enthusiast-to-enthusiast. `[SCREENSHOT: …]` marks where to drop images.
> Nothing here is final — we'll keep polishing.

---

## Title options

- **liquidWave — Sonic Analysis for your Emby music library (private beta, looking for testers)**
- **I built the Plexamp "Sonic Analysis" experience for Emby — self-hosted. Want to test it?**
- **Sonic similarity / radio / auto-mixes for Emby music — beta testers wanted**

---

## Post body

**TL;DR:** I've been building a self-hosted system that adds Plexamp-style *sonic
analysis* to an Emby music library — sonically similar tracks, track radio, "sonic
adventures" between two songs, and auto-curated mixes — plus a companion Android app
(**liquidWave**). It's fully self-hosted, nothing leaves your network, and I'm looking
for a few testers who run Emby and don't mind a bit of setup.

[SCREENSHOT: liquidWave Home screen — mixes / stations / recent plays]

### Why I built it

I've always loved the **Sonic Analysis** feature in Plex/Plexamp. The problem is, I
don't want to run Plex — I much prefer **Emby** for all of my other media — and there
has never been an Emby solution for doing sonic analysis on the music in an Emby
library. It's the one thing that kept pulling me back to Plex, and I didn't want it to.

My music collection is old, big, and honestly not very well organised, and I'm lazy
about fixing that. What I love about sonic analysis is that it lets me build
listenable, automated playlists from a single starting point — a track, a mood, a
vibe — *without* me having to tag and curate everything by hand. That's the whole
reason I wanted this.

So with the recent rise of AI tooling, it finally felt like the right time to actually
attempt it.

### A note on how it was built

I'll be upfront: I built this with heavy use of AI (Claude and Codex). I know a lot of
people see "made with AI" and assume junk — but it genuinely isn't that easy. It takes
a long time, and you essentially have to become a good project manager: guiding the AI,
making the architecture calls, testing on real hardware, and dragging it back on track
when it wanders. I've been working hard on this for a while. I built it **for myself,
from scratch**, and deliberately didn't go looking at other apps — I just wanted to
build in the features *I* actually use.

One of those is **audiobook support**, because my wife loves audiobooks and that was
important to her — so liquidWave handles books (with durable resume) alongside music.

### What it does

[SCREENSHOT: Now Playing screen]

- **Sonically similar tracks / artists / albums** — based on how the music *sounds*
  (neural audio analysis), not genre tags.
- **Track Radio** — an endless queue seeded from any track.
- **Sonic Adventure** — pick a start track and an end track, and it builds a journey
  that morphs from one to the other.
- **Auto-curated Mixes** — sonically consistent mixes built from your library; refresh
  any mix, or save it as a real playlist.
- **Stations & Guest DJ** — Library / Random Album / Decade radios, plus a Guest DJ
  that injects sonically matched tracks into your queue.
- **A proper player** — Media3/ExoPlayer, queue, shuffle/repeat, crossfade with an
  artwork cross-dissolve, an in-app equalizer, a Now Playing home-screen widget, and
  **Google Cast** to a Chromecast / Android TV / Shield.
- **Audiobooks** — browse by book/author, durable resume, speed control.
- **Search** across music, audiobooks, or everything.

[SCREENSHOT: Mixes screen] [SCREENSHOT: Sonic Adventure builder]

### Self-hosted and private — by design

This is built privacy-first. **It's your music, your server, your network.**

- Everything runs on **your own hardware** — there's no account, no cloud service, no
  analytics, and **no telemetry**. Nothing about your library or listening is sent to
  me or anyone else.
- The audio analysis happens **locally**. The similarity index lives in a small file on
  your own machine and never leaves it.
- The **only** outbound connection the system makes is a one-time download of the
  neural model (~327 MB) on first run — after that the analysis stack can run fully
  air-gapped.
- It's designed to live **inside your LAN** (behind your VPN if you want remote access,
  exactly like you'd treat Emby itself). I'll be clear in the docs about not exposing it
  to the public internet.

### It runs on whatever you've got

This was honestly one of the hardest parts to get right, so I'm a bit proud of it. The
system is split into a tiny **coordinator** (runs anywhere — Windows, Linux, macOS, or a
Docker container on an ARM NAS) and a separate **worker** that does the heavy analysis
on whatever box has spare power. **A GPU makes it faster but is not required** — CPU
works, it just takes longer.

| Your setup | Coordinator | Worker |
|---|---|---|
| NAS (Synology/QNAP/ARM) runs Emby | Docker on the NAS | Your desktop/gaming PC |
| One Windows box does everything | On that box | Same box (CPU or GPU) |
| Linux server + a separate GPU rig | On the server | On the GPU box |
| No GPU anywhere | Either | Any box, CPU-only — just slower |

You should never have to compile a native dependency — the painful cross-platform bits
(PyTorch builds, optional Essentia, the model download) are handled by the defaults and
Docker images.

### The honest state of it

It's a **private beta**, and I want to be straight about that:

- **Android only** for now (iOS is a much later maybe).
- The first library analysis is a **one-time slow job** — roughly 10–15 s/track on a
  GPU, 20–30 s CPU-only. A few thousand tracks is an overnight run; after that it's
  automatic as you add music.
- A couple of known rough edges I'm still working on (e.g. on-device token encryption).
- The app is a debug build for now (installs fine by sideloading).

It's running on my own gear every day and is genuinely usable — but you'll be early.

### Want to test?

You're a good fit if you **run Emby, can install a plugin and restart your server, and
don't mind following a setup checklist.** You'll stand up three small pieces
(coordinator, a plugin, a worker) and install the Android app. There's a one-page
quickstart that walks through all of it for any OS.

[LINK: tester quickstart] · [LINK: GitHub repo]

If you're interested, reply here or [HOW TO REACH KAJ — forum DM / email / GitHub issue].
Tell me roughly your setup (Emby host OS, whether you've got a GPU, library size) and I'll
get you the files. All I ask back is honest feedback — what worked, what didn't, what
felt slow or weird, and any crashes.

Thanks for reading — this has been a real labour of love. 🌊

---

## Notes for Kaj (not part of the post)

- **Decide how hard to lean on the AI-built angle.** I've written it as honest +
  defensive-but-proud, which fits you. Some forum folks react badly to "made with AI" —
  but being upfront usually lands better than hiding it and getting found out. Your call
  on tone/length of that section.
- **Reach-me line:** pick one — forum DM, your email, or "open a GitHub issue."
- **Screenshots:** 4–6 good ones beat a wall of them. Suggested: Home, Now Playing
  (with art), Mixes, Sonic Adventure, and the Settings/analysis-status card to show the
  self-hosted machinery.
- **Links go live** once you cut the GitHub Release (plugin zip + APK) and the repo is
  public.
- **Check the forum's rules** on self-promo / linking before posting.
