# FAQ & troubleshooting

Real problems hit while running this, and what actually fixed them. If something
here saves you an afternoon, it probably cost someone one.

---

## Setup

### The app logs in fine, but my library is empty

Almost always `EMBY_URL`. It is **not** private to the coordinator — the web app
hands that address to your **browser** so it can fetch audio directly. If it's
`localhost` or `127.0.0.1`, every browser dutifully looks for Emby on *its own*
machine and finds nothing.

Use the LAN address, even when Emby and the coordinator are on the same box:

```
EMBY_URL=http://192.168.1.10:8096
```

The symptom is confusing because **login still works** — that hop happens
server-side, coordinator to Emby, and never involves the browser. Sonic Mixes
also keep appearing, because those come from the coordinator. Only the parts
that need Emby directly break.

If you use the web app from outside your LAN, also set `EMBY_URL_EXTERNAL` to
your public address; the web app picks whichever matches how you loaded the page.

### I fixed EMBY_URL but the web app is still empty

Log out and back in. The web app stores the server address in `localStorage` at
login, so an existing session keeps using the old one. Clearing site data works
too.

### Everything returns HTTP 500 after restarting the coordinator

The coordinator validates your Emby token by calling Emby. If it can't reach
Emby, **every authenticated request 500s** and clients report the coordinator as
unreachable. Check `EMBY_URL` resolves and responds *from the coordinator host*:

```
curl http://<your-emby>:8096/System/Info/Public
```

A restart often exposes this even though nothing seemed broken before — a
long-running process may be holding a cached token, so the Emby call only
happens again once that cache is cold.

### The scan command returns 422

Older versions required a JSON body. Current versions don't. Either update, or
send an explicit body:

```
curl -X POST http://<coordinator>:8765/sonic/library/scan \
  -H "X-Emby-Token: <token>" -H "Content-Type: application/json" -d '{}'
```

### First analysis is very slow

It's a one-time cost: every track is decoded and run through a neural model.
Leave it overnight. Sonic features appear progressively as tracks complete — you
don't have to wait for 100%.

Workers also download a ~327 MB model checkpoint on first run.

---

## Performance

### Analysis is far slower than expected — check you're actually on the GPU

Look in `worker.log` for the device line:

```
[worker] device=cpu     <-- not using your GPU
[worker] device=cuda    <-- using it
```

If you have an NVIDIA GPU and see `device=cpu`, the usual cause is that **a plain
`pip install torch` on Windows resolves to the CPU-only wheel**. Confirm:

```
python -c "import torch; print(torch.__version__, torch.cuda.is_available())"
```

`2.x.x+cpu` and `False` means torch simply cannot see the GPU — no amount of
configuration will help, because the auto-detection is working correctly; there's
just nothing to detect. Reinstall from a CUDA index matching your setup:

```
pip install --force-reinstall torch --index-url https://download.pytorch.org/whl/cu126
```

Pick the `cuXXX` that has your torch version — check
[pytorch.org](https://pytorch.org/get-started/locally/). Matching your existing
torch version exactly avoids disturbing the other packages.

This one hides well: the worker logs `device=cpu` and carries on working, just
several times slower, so nothing looks broken.

### The GPU worker never seems to run (Windows scheduled task)

The installer sets an **idle trigger** — it starts after ~10 minutes of no
activity and stops the moment you touch the machine (`StopOnIdleEnd`). That's
deliberate, so analysis doesn't fight you for the GPU. To clear a backlog now,
start the task manually.

---

## Playback

### Some tracks never crossfade, and leave a gap instead

Tracks that Emby has to **transcode** (WMA is the common one) are deliberately
excluded from crossfade. Blending a transcoded stream means opening a second
stream of the same track, and the two fight — the symptom was a track looping
from about three-quarters through until you skipped it. A clean cut is the safe
alternative.

Two knock-on effects worth knowing:

- **One transcoded track kills the fade on *both* its transitions**, in and out.
- Silence-trimming currently only runs as part of a crossfade, so a skipped
  transition plays the outgoing track's silent outro *and* the incoming track's
  silent intro in full — audibly a gap rather than a tight cut. Improving that is
  on the list.

### A track restarted from the beginning when my phone changed network

Same root cause. A transcode is a live server-side session, not a static file, so
it can't be resumed with a byte-range request the way a direct-play MP3 can. When
the connection drops — a Wi-Fi to mobile handover, typically — Emby starts a
fresh transcode from zero and the track restarts.

### Should I just convert my WMA files?

If you have more than a handful, it's worth considering: it removes the cause of
all three problems above rather than working around them. Things to know first:

- Match the target to the source. Most old WMA is low-bitrate lossy — MP3 V0 is
  comfortably transparent. Only genuine **WMA Lossless** warrants FLAC. Decide per
  file using ffprobe's `codec_name`, **not** bitrate: high-bitrate lossy WMA looks
  like lossless if you only check the number.
- **Replacing a file changes its Emby item ID.** Play counts, favourites and
  playlist entries for those tracks are lost, and they drop out of Sonic Mixes
  until re-analysed. There's no way around this — Emby sees a new file.
- Keep the originals until you've verified the results.
- Expect some duds. In one 1,376-file library, 16 sources turned out to be
  genuinely corrupt (seconds of undecodable audio, already unplayable) and one was
  DRM-protected and unreadable.

### Audiobooks don't get sonic features

By design — only music libraries are analysed. Spoken word would pollute mixes.

---

## After changing your library

### Failed track count climbs after deleting or replacing files

Known limitation: **tracks removed from Emby aren't currently removed from the
coordinator's database or search index.** Their rows linger, and workers keep
trying to download items that no longer exist — you'll see 404s in
`/sonic/status/errors` and a rising `failed_tracks`.

It doesn't affect playback: results are re-resolved through Emby before you see
them, so anything Emby no longer returns is filtered out silently. But the
database and index carry dead weight until cleanup lands.

### Mixes look thin, or are missing tracks I know I have

Mixes are built from a snapshot. After a large import — or a bulk change — the
new tracks are analysed but not yet clustered. Rebuild once analysis has
finished:

```
curl -X POST http://<coordinator>:8765/sonic/library/build-mixes \
  -H "X-Emby-Token: <token>"
```

Rebuilding *during* analysis just produces mixes missing whatever hasn't been
processed yet, so wait for `pending_tracks` to reach zero in `/sonic/status`.

### I have several music libraries — which one do the sonic features use?

Browsing follows the library picker on the Library screen, and the choice is
remembered. Sonic features (mixes, radio, similar) currently draw from **all**
your music libraries combined, not just the selected one. Scoping those to the
selected library is a possible future change — say so if it matters to you.

---

## Useful commands

```bash
# What's the coordinator doing?
curl -H "X-Emby-Token: <token>" http://<coordinator>:8765/sonic/status

# Why did tracks fail?
curl -H "X-Emby-Token: <token>" http://<coordinator>:8765/sonic/status/errors

# Is a track one Emby will transcode? (check its container)
curl "http://<emby>:8096/Items?Recursive=true&IncludeItemTypes=Audio\
&SearchTerm=<title>&Fields=Container&api_key=<key>"
```

Interactive API docs live at `http://<coordinator>:8765/docs`.
