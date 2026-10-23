"""Generate Kavach's launcher icons from the master artwork.

Source: `assets/branding/kavach-logo.png` — the shield-and-handset render.
Run from the repo root:

    python3 tools/make_icons.py

What it produces
----------------
* `mipmap-*/ic_launcher.png` and `ic_launcher_round.png` — real bitmaps for
  launchers older than Android 8, which do not understand adaptive icons.
* `drawable/ic_launcher_foreground.png` — the artwork on transparency, scaled so
  that it fits inside the **safe zone**.
* `drawable/ic_launcher_background.png` — a radial gradient sampled from the
  artwork's own backdrop, so the two layers meet without a seam.
* `drawable/ic_launcher_monochrome.png` — a single-colour silhouette for Android
  13+ themed icons, which the launcher recolours to match the user's wallpaper.

The one thing worth understanding before changing this
------------------------------------------------------
An adaptive icon is 108dp, but only the **central 66dp** is guaranteed to be
visible: launchers scale the layers up and crop, and the mask varies by device
(circle, squircle, rounded square, teardrop). Artwork that fills the frame gets
its corners cut off, and on a circular mask it loses considerably more.

So the artwork is scaled to 66/108 of the canvas and centred, and the surrounding
area is left to the background layer. That is why the foreground PNG looks small
in its own file — it is not a bug.
"""
from __future__ import annotations

import os
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter
import numpy as np

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(REPO, "assets", "branding", "kavach-logo.png")
RES = os.path.join(REPO, "app", "src", "main", "res")

# The adaptive canvas is 108dp; xxxhdpi is 4x, so 432px. The safe zone is 66dp.
ADAPTIVE_PX = 432
SAFE_FRACTION = 66 / 108

# Legacy launcher sizes, per density.
LEGACY = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def load_source() -> Image.Image:
    if not os.path.exists(SOURCE):
        sys.exit(
            f"Master artwork not found at {SOURCE}\n"
            "Put the logo there (or change SOURCE) and run again."
        )
    return Image.open(SOURCE).convert("RGB")


def backdrop_colour(image: Image.Image) -> tuple[int, int, int]:
    """The artwork's own background, sampled from the corners.

    Averaged over four small patches rather than a single pixel, because a JPEG
    round trip or a gradient leaves single pixels unreliable.
    """
    w, h = image.size
    patch = max(4, w // 64)
    total = [0, 0, 0]
    n = 0
    for x0, y0 in ((0, 0), (w - patch, 0), (0, h - patch), (w - patch, h - patch)):
        for x in range(x0, x0 + patch):
            for y in range(y0, y0 + patch):
                px = image.getpixel((x, y))
                for i in range(3):
                    total[i] += px[i]
                n += 1
    return tuple(round(t / n) for t in total)  # type: ignore[return-value]


def radial_background(image: Image.Image, size: int) -> Image.Image:
    """The background layer: the artwork's own glow, blurred outward.

    The first version drew a synthetic radial gradient from a sampled corner
    colour. It looked wrong for a reason worth recording: the artwork carries its
    own backdrop, and a *synthetic* glow that is close to it but not the same
    produces a visible square seam where the foreground layer ends. Two nearly
    matching darks meeting at a hard edge is more noticeable than two obviously
    different ones.

    So the background is the artwork itself — scaled to fill, blurred hard, and
    slightly darkened. The glow then continues outward from the artwork rather
    than restarting beside it, and the seam disappears because there is nothing
    to seam against.
    """
    filled = image.copy()
    # Cover the canvas, cropping the excess: this layer only needs to be a
    # plausible continuation, not the whole picture.
    ratio = max(size / filled.width, size / filled.height)
    resized = filled.resize((round(filled.width * ratio), round(filled.height * ratio)), Image.LANCZOS)
    left = (resized.width - size) // 2
    top = (resized.height - size) // 2
    layer = resized.crop((left, top, left + size, top + size))

    # Blur far enough that no shape survives — only the light does.
    layer = layer.filter(ImageFilter.GaussianBlur(size * 0.09))
    # Darken, so the foreground artwork stays the brightest thing in the icon.
    layer = Image.eval(layer, lambda v: int(v * 0.62))
    return layer


def cut_out_backdrop(image: Image.Image, base: tuple[int, int, int]) -> Image.Image:
    """Remove the artwork's flat backdrop, keeping the interior darks.

    Why this is needed at all: the artwork is a lit object on a dark ground, and
    the ground is opaque. Put it straight onto a glowing background layer and it
    reads as a dark square with a logo in it — the square edge is the most
    noticeable thing in the icon.

    Why a *connected* fill rather than a brightness threshold: the shield has a
    dark interior and the ring has a dark inside. A plain "make dark pixels
    transparent" rule punches those out too, and the white checkmark ends up
    floating on the background with nothing behind it.

    So the fill starts at the four edges and spreads inward through pixels that
    match the backdrop. It cannot reach the interior, because the artwork's own
    bright edges block it. That is exactly the behaviour wanted, and it falls out
    of the connectivity rather than needing a rule.

    A tolerance is used rather than an exact match: the backdrop carries a subtle
    vignette, so no single colour describes all of it. Where the tolerance stops
    is where the artwork's glow begins, which leaves a soft halo attached to the
    logo — the right thing to keep.
    """
    rgb = image.convert("RGB")
    filled = rgb.copy()
    sentinel = (255, 0, 255)
    # Generous on purpose. At 34 the fill stopped inside the artwork's own glow,
    # leaving a dark halo that read as a faint square over the background layer.
    # The background layer is already a blurred copy of this artwork, so it
    # supplies the glow; the foreground only needs the object itself.
    tolerance = 78

    w, h = filled.size
    for seed in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        # Only seed from a corner that is actually backdrop — a corner the artwork
        # reaches would otherwise be eaten.
        if sum(abs(a - b) for a, b in zip(filled.getpixel(seed), base)) <= tolerance:
            ImageDraw.floodfill(filled, seed, sentinel, thresh=tolerance)

    arr = np.array(filled)
    outside = (arr[:, :, 0] == 255) & (arr[:, :, 1] == 0) & (arr[:, :, 2] == 255)

    alpha = np.where(outside, 0, 255).astype(np.uint8)
    mask = Image.fromarray(alpha, "L")
    # Soften by a pixel or two so the cut does not alias against the background.
    mask = mask.filter(ImageFilter.GaussianBlur(1.2))

    out = image.convert("RGBA")
    out.putalpha(mask)
    return out


def feathered_square(image: Image.Image, size: int, inset_fraction: float) -> Image.Image:
    """The artwork scaled to `inset_fraction` of the canvas, edges faded out.

    Two things this has to get right, and the first version got both wrong:

    1. **Draw order.** The shapes are painted outermost-first. Painting them
       innermost-first means the last, most transparent shape covers everything —
       which produced a completely empty foreground layer. It looked like the
       artwork had failed to load; it had not, the mask was zero.
    2. **A square feather, not a circular one.** A circular fade at the artwork's
       inscribed radius clips the shield, which sits in the bottom-right corner.
       The artwork's own backdrop is the same near-black as the background layer,
       so the fade only needs to soften the outer few percent of the *square*.

    The fade exists because the artwork's backdrop and the background gradient are
    close but not identical, and a hard edge between two nearly-matching darks is
    very visible.
    """
    target = max(1, round(size * inset_fraction))
    art = image.copy().resize((target, target), Image.LANCZOS)

    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    offset = ((size - target) // 2, (size - target) // 2)
    canvas.paste(art, offset)

    # The mask is built at the artwork's size, then pasted in the same place, so
    # the feather follows the artwork's own edge rather than the canvas.
    feather = max(3, round(target * 0.16))
    mask = Image.new("L", (target, target), 0)
    md = ImageDraw.Draw(mask)
    # Outer first (transparent), then progressively smaller and more opaque.
    for i in range(feather + 1):
        t = i / feather
        inset = feather - i
        md.rounded_rectangle(
            [inset, inset, target - 1 - inset, target - 1 - inset],
            radius=round(target * 0.06),
            fill=round(255 * t),
        )
    mask = mask.filter(ImageFilter.GaussianBlur(max(1, feather * 0.35)))

    full_mask = Image.new("L", (size, size), 0)
    full_mask.paste(mask, offset)
    canvas.putalpha(ImageChops.multiply(canvas.getchannel("A"), full_mask))
    return canvas


def rounded_square(image: Image.Image, size: int, radius_fraction: float = 0.22) -> Image.Image:
    """Legacy launcher icon: the artwork, cropped to a rounded square."""
    art = image.copy()
    art.thumbnail((size, size), Image.LANCZOS)
    if art.size != (size, size):
        art = art.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1],
        radius=round(size * radius_fraction),
        fill=255,
    )
    out = art.convert("RGBA")
    out.putalpha(mask)
    return out


def circle(image: Image.Image, size: int) -> Image.Image:
    """Legacy round icon, for launchers that ask for one explicitly."""
    art = image.copy()
    art.thumbnail((size, size), Image.LANCZOS)
    if art.size != (size, size):
        art = art.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    out = art.convert("RGBA")
    out.putalpha(mask)
    return out


def monochrome(image: Image.Image, size: int) -> Image.Image:
    """A white silhouette for Android 13+ themed icons.

    Derived from luminance rather than traced: the artwork is a lit object on a
    dark ground, so brightness is a good proxy for "part of the logo". The alpha
    keeps the soft falloff, which is what makes a themed icon look drawn rather
    than cut out.

    Note the shield's interior and the ring's interior are dark, so they read as
    holes. That is correct for a themed icon — the launcher paints the wallpaper
    through the transparent parts.
    """
    grey = image.convert("L")
    # Lift the mid-tones: the glow around the artwork is dim, and without this the
    # silhouette loses the outer edge of the ring.
    grey = grey.point(lambda v: min(255, int((v / 255.0) ** 0.75 * 255)))
    alpha = grey.point(lambda v: 0 if v < 38 else min(255, int((v - 38) * 255 / 150)))

    out = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    scaled = alpha.resize((size, size), Image.LANCZOS)
    out.putalpha(scaled)
    return out


def write(image: Image.Image, path: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path, "PNG", optimize=True)
    print(f"  {os.path.relpath(path, REPO)}  {image.size[0]}x{image.size[1]}")


def main() -> None:
    print("Kavach icons")
    src = load_source()
    base = backdrop_colour(src)
    print(f"  backdrop sampled as rgb{base}")
    # The artwork with its flat ground removed. Everything downstream uses this,
    # so the icon has one consistent edge.
    logo = cut_out_backdrop(src, base)

    # Trim to the artwork's own content before scaling.
    #
    # The render has a margin around the ring, so fitting the *frame* into the
    # safe zone left the logo looking undersized — it occupied about half the
    # circle a launcher actually draws. Trimming first means the safe zone is
    # spent on the artwork rather than on empty backdrop.
    box = logo.getchannel("A").getbbox()
    if box:
        logo = logo.crop(box)
    print(f"  trimmed to {logo.size[0]}x{logo.size[1]}")

    # ---- adaptive layers ----
    write(radial_background(src, ADAPTIVE_PX), os.path.join(RES, "drawable", "ic_launcher_background.png"))
    write(
        feathered_square(logo, ADAPTIVE_PX, SAFE_FRACTION),
        os.path.join(RES, "drawable", "ic_launcher_foreground.png"),
    )
    write(monochrome(logo, ADAPTIVE_PX), os.path.join(RES, "drawable", "ic_launcher_monochrome.png"))

    # ---- legacy bitmaps ----
    for density, size in LEGACY.items():
        folder = os.path.join(RES, f"mipmap-{density}")
        write(rounded_square(src, size), os.path.join(folder, "ic_launcher.png"))
        write(circle(src, size), os.path.join(folder, "ic_launcher_round.png"))

    print("done")


if __name__ == "__main__":
    main()
