"""Generate the Android Auto tile artwork shipped as drawable-nodpi PNGs.

Auto draws media browse screens itself, so a row's artwork is the only
thing the app controls about how a tile looks. Emby has real covers for
albums, playlists and mixes, but a *station* (Library Radio, 1980s, a genre)
has none, and Auto shows a grey music-note box in its place. These tiles
fill that gap in the liquidWave look: a deep gradient, soft waves, and one
simple glyph. The label is NOT drawn on the tile -- Auto prints the title
under it -- except for decades, where the number is the whole point.

    python tools/make_auto_art.py

Output goes to android/app/src/main/res/drawable-nodpi/ (auto_art_*.png).
Re-run after changing the palette; commit the PNGs it writes.
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

SIZE = 512
OUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "android", "app", "src", "main", "res", "drawable-nodpi",
)
FONT_BOLD = r"C:\Windows\Fonts\segoeuib.ttf"

# (top-left, bottom-right) gradient pairs. Deep and saturated, so white
# glyphs read at small sizes and the tiles sit well beside real album covers.
PALETTE = {
    "ocean":   ((18, 52, 120), (0, 170, 190)),
    "violet":  ((58, 28, 120), (180, 80, 200)),
    "sunset":  ((150, 40, 70), (250, 150, 60)),
    "forest":  ((14, 70, 70), (90, 190, 120)),
    "night":   ((16, 22, 60), (70, 90, 200)),
    "rose":    ((120, 30, 90), (240, 110, 150)),
    "amber":   ((130, 70, 10), (250, 190, 70)),
    "steel":   ((30, 50, 70), (110, 150, 180)),
}
GENRE_KEYS = list(PALETTE)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient(c1, c2):
    img = Image.new("RGB", (SIZE, SIZE))
    px = img.load()
    assert px is not None
    for y in range(SIZE):
        for x in range(SIZE):
            px[x, y] = lerp(c1, c2, (x + y) / (2 * SIZE - 2))
    return img.convert("RGBA")


def waves(img, seed):
    """Three soft translucent sine bands across the lower half."""
    layer = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for i, (amp, freq, base, alpha) in enumerate(
        [(26, 1.6, 0.66, 46), (20, 2.4, 0.76, 60), (14, 3.3, 0.87, 80)]
    ):
        pts: list[tuple[float, float]] = [(0, SIZE)]
        for x in range(0, SIZE + 4, 4):
            y = SIZE * base + amp * math.sin(x / SIZE * math.tau * freq + seed + i * 1.7)
            pts.append((x, y))
        pts.append((SIZE, SIZE))
        d.polygon(pts, fill=(255, 255, 255, alpha))
    return Image.alpha_composite(img, layer)


def glow(img):
    """A faint highlight top-left so the tile has depth."""
    layer = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    ImageDraw.Draw(layer).ellipse((-140, -140, 300, 300), fill=(255, 255, 255, 40))
    layer = layer.filter(ImageFilter.GaussianBlur(40))
    return Image.alpha_composite(img, layer)


def base(palette, seed):
    return glow(waves(gradient(*PALETTE[palette]), seed))


WHITE = (255, 255, 255, 235)


def glyph_bars(d):
    """Waveform: the app's own motif."""
    heights = [90, 170, 250, 150, 300, 190, 120, 230, 100]
    w, gap = 26, 14
    total = len(heights) * w + (len(heights) - 1) * gap
    x = (SIZE - total) // 2
    for h in heights:
        d.rounded_rectangle((x, SIZE // 2 - h // 2 - 24, x + w, SIZE // 2 + h // 2 - 24), 13, fill=WHITE)
        x += w + gap


def glyph_disc(d):
    """A record: the 'random album' station."""
    c = SIZE // 2
    r = 150
    d.ellipse((c - r, c - r - 24, c + r, c + r - 24), fill=(255, 255, 255, 60), outline=WHITE, width=10)
    for rr in (105, 70):
        d.ellipse((c - rr, c - rr - 24, c + rr, c + rr - 24), outline=(255, 255, 255, 120), width=4)
    d.ellipse((c - 34, c - 34 - 24, c + 34, c + 34 - 24), fill=WHITE)


def glyph_calendar(d):
    """Decade Radio: a calendar page."""
    x0, y0, x1, y1 = 130, 120, 382, 372
    d.rounded_rectangle((x0, y0, x1, y1), 28, outline=WHITE, width=12)
    d.rectangle((x0, y0 + 56, x1, y0 + 68), fill=WHITE)
    for cx in (x0 + 70, x1 - 70):
        d.rounded_rectangle((cx - 9, y0 - 24, cx + 9, y0 + 30), 9, fill=WHITE)
    for row in range(2):
        for col in range(3):
            cx = x0 + 62 + col * 64
            cy = y0 + 120 + row * 66
            d.rounded_rectangle((cx - 16, cy - 16, cx + 16, cy + 16), 6, fill=WHITE)


def glyph_tag(d):
    """Genres: a stack of rounded bars of different lengths, like tags."""
    for i, (w, y) in enumerate([(250, 130), (190, 210), (300, 290)]):
        d.rounded_rectangle((SIZE // 2 - w // 2, y, SIZE // 2 + w // 2, y + 56), 28, fill=WHITE if i != 1 else (255, 255, 255, 170))


def glyph_clock(d):
    """Recent plays: a clock."""
    c = SIZE // 2
    cy = c - 24
    d.ellipse((c - 150, cy - 150, c + 150, cy + 150), outline=WHITE, width=16)
    d.line((c, cy, c, cy - 90), fill=WHITE, width=16)
    d.line((c, cy, c + 64, cy + 36), fill=WHITE, width=16)
    d.ellipse((c - 12, cy - 12, c + 12, cy + 12), fill=WHITE)


def glyph_mix(d):
    """Sonic Mixes: overlapping circles."""
    c = SIZE // 2
    for dx, a in ((-70, 110), (70, 110)):
        d.ellipse((c + dx - 110, c - 134, c + dx + 110, c + 86), fill=(255, 255, 255, a), outline=WHITE, width=8)


def glyph_albums(d):
    """Albums: two stacked squares."""
    d.rounded_rectangle((190, 100, 400, 310), 22, fill=(255, 255, 255, 70), outline=(255, 255, 255, 150), width=8)
    d.rounded_rectangle((120, 170, 330, 380), 22, fill=(255, 255, 255, 110), outline=WHITE, width=10)
    d.ellipse((190, 240, 260, 310), outline=WHITE, width=10)


def glyph_book(d):
    """Books: an open book."""
    c = SIZE // 2
    top, bot = 130, 370
    d.polygon([(c - 8, top + 20), (c - 150, top), (c - 150, bot - 20), (c - 8, bot)], fill=(255, 255, 255, 120), outline=WHITE)
    d.polygon([(c + 8, top + 20), (c + 150, top), (c + 150, bot - 20), (c + 8, bot)], fill=WHITE)
    for i in range(3):
        y = top + 60 + i * 50
        d.line((c - 120, y, c - 40, y + 8), fill=(255, 255, 255, 170), width=8)


def glyph_headphones(d):
    """Resume listening: headphones."""
    c = SIZE // 2
    d.arc((c - 150, 90, c + 150, 390), 180, 360, fill=WHITE, width=22)
    d.line((c - 150, 240, c - 150, 320), fill=WHITE, width=22)
    d.line((c + 150, 240, c + 150, 320), fill=WHITE, width=22)
    d.rounded_rectangle((c - 190, 290, c - 100, 400), 30, fill=WHITE)
    d.rounded_rectangle((c + 100, 290, c + 190, 400), 30, fill=WHITE)


def glyph_person(d):
    """Artists: head and shoulders."""
    d.ellipse((196, 110, 316, 230), fill=WHITE)
    d.pieslice((126, 250, 386, 510), 180, 360, fill=WHITE)


def glyph_list(d):
    """Playlists: lines with a note-ish marker."""
    for i, w in enumerate((250, 250, 170)):
        y = 130 + i * 80
        d.rounded_rectangle((130, y, 130 + w, y + 36), 18, fill=WHITE)
    d.ellipse((330, 290, 392, 350), fill=WHITE)
    d.rectangle((380, 180, 392, 320), fill=WHITE)


# Each decade gets its own hue so a grid of them is easy to scan.
DECADE_PALETTES = {
    "60": "sunset", "70": "amber", "80": "rose", "90": "violet",
    "00": "ocean", "10": "forest", "20": "night",
}


def save(img, name):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f"auto_art_{name}.png")
    img.convert("RGB").save(path, optimize=True)
    print(f"{name:20s} {os.path.getsize(path) // 1024:4d} KB")


def glyph_tile(palette, seed, glyph):
    # Draw on a separate transparent layer and composite: ImageDraw on an
    # opaque RGBA image REPLACES pixels (alpha included), so translucent
    # shapes would come out solid white once the alpha is dropped.
    img = base(palette, seed)
    layer = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    glyph(ImageDraw.Draw(layer))
    return Image.alpha_composite(img, layer)


def main():
    save(glyph_tile("ocean", 0.3, glyph_bars), "station_library")
    save(glyph_tile("violet", 1.1, glyph_disc), "station_random")
    save(glyph_tile("sunset", 2.0, glyph_calendar), "station_decades")
    save(glyph_tile("forest", 0.8, glyph_tag), "station_genres")
    save(glyph_tile("steel", 1.4, glyph_clock), "see_recent")
    save(glyph_tile("rose", 2.6, glyph_mix), "see_mixes")
    save(glyph_tile("night", 0.9, glyph_albums), "lib_albums")
    save(glyph_tile("amber", 1.9, glyph_person), "lib_artists")
    save(glyph_tile("ocean", 2.9, glyph_list), "lib_playlists")
    save(glyph_tile("ocean", 0.5, glyph_headphones), "ab_resume")
    save(glyph_tile("amber", 1.3, glyph_book), "ab_books")
    save(glyph_tile("forest", 2.2, glyph_person), "ab_authors")
    for dec, pal in DECADE_PALETTES.items():
        img = base(pal, int(dec) * 0.37)
        layer = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        font = ImageFont.truetype(FONT_BOLD, 250)
        ImageDraw.Draw(layer).text((SIZE // 2, SIZE // 2 - 14), f"{dec}s", font=font, fill=WHITE, anchor="mm")
        save(Image.alpha_composite(img, layer), f"decade_{dec}")
    for i, key in enumerate(GENRE_KEYS):
        save(base(key, i * 0.8), f"genre_{i}")


if __name__ == "__main__":
    if not os.path.exists(FONT_BOLD):
        sys.exit(f"font not found: {FONT_BOLD}")
    main()
