"""Generate Kavach's launcher icons: a shield with a call handset inside.

Produces legacy PNG mipmaps for every density (so pre-Android-8 launchers get a
real bitmap) plus the adaptive-icon layers. Run from the repo root:

    python3 tools/make_icons.py
"""
import math
import os

from PIL import Image, ImageDraw

RES = "app/src/main/res"
S = 1024  # master render size; downscaled per density

# Palette — matches the app's Aurora theme.
NAVY = (7, 8, 15)
CYAN = (56, 225, 255)
VIOLET = (154, 107, 255)
MAGENTA = (255, 92, 210)
WHITE = (255, 255, 255)


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient(size, stops):
    """Vertical multi-stop gradient."""
    img = Image.new("RGB", (size, size))
    draw = ImageDraw.Draw(img)
    for y in range(size):
        t = y / max(1, size - 1)
        # find the surrounding stops
        for i in range(len(stops) - 1):
            p0, c0 = stops[i]
            p1, c1 = stops[i + 1]
            if p0 <= t <= p1:
                local = 0 if p1 == p0 else (t - p0) / (p1 - p0)
                draw.line([(0, y), (size, y)], fill=lerp(c0, c1, local))
                break
        else:
            draw.line([(0, y), (size, y)], fill=stops[-1][1])
    return img


def shield_points(size, scale=1.0):
    """A heraldic shield, centred, as a polygon."""
    c = size / 2
    w = size * 0.30 * scale      # half-width at the shoulders
    top = c - size * 0.30 * scale
    shoulder = c - size * 0.10 * scale
    bottom = c + size * 0.32 * scale
    pts = [
        (c, top),
        (c + w, top + size * 0.08 * scale),
        (c + w, shoulder),
    ]
    # curved lower half, approximated with a fan of points
    for i in range(1, 25):
        t = i / 24
        x = c + w * math.cos(t * math.pi / 2) * (1 - 0.0)
        y = shoulder + (bottom - shoulder) * (t ** 0.85)
        pts.append((c + w * (1 - t) ** 0.55, y))
    for i in range(24, 0, -1):
        t = i / 24
        y = shoulder + (bottom - shoulder) * (t ** 0.85)
        pts.append((c - w * (1 - t) ** 0.55, y))
    pts += [(c - w, shoulder), (c - w, top + size * 0.08 * scale)]
    return pts


def rounded_bar(draw, p0, p1, width, fill):
    """A thick line with rounded caps — reads as a handset at any size."""
    draw.line([p0, p1], fill=fill, width=width)
    r = width / 2
    for (x, y) in (p0, p1):
        draw.ellipse([x - r, y - r, x + r, y + r], fill=fill)


def ellipse_pts(cx, cy, rx, ry, angle):
    """Points around a rotated ellipse."""
    pts = []
    ca, sa = math.cos(angle), math.sin(angle)
    for i in range(64):
        t = 2 * math.pi * i / 64
        x, y = rx * math.cos(t), ry * math.sin(t)
        pts.append((cx + x * ca - y * sa, cy + x * sa + y * ca))
    return pts


def handset(size, fill=WHITE):
    """The classic call receiver.

    A short curved bar with a flared pad at each end — the flare is what makes it
    read as a telephone handset rather than a dumbbell.
    """
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    c = size / 2

    # The bar bows gently, the way a real receiver curves around the face.
    a = (c - size * 0.155, c + size * 0.105)
    b = (c + size * 0.155, c - size * 0.105)
    mid = (c + size * 0.012, c + size * 0.012)

    steps = 40
    prev = a
    for i in range(1, steps + 1):
        t = i / steps
        # quadratic Bezier through `mid`
        x = (1 - t) ** 2 * a[0] + 2 * (1 - t) * t * mid[0] + t ** 2 * b[0]
        y = (1 - t) ** 2 * a[1] + 2 * (1 - t) * t * mid[1] + t ** 2 * b[1]
        rounded_bar(d, prev, (x, y), int(size * 0.072), fill)
        prev = (x, y)

    # Flared pads, elongated perpendicular to the bar.
    angle = math.atan2(b[1] - a[1], b[0] - a[0])
    perp = angle + math.pi / 2
    for (px, py) in (a, b):
        d.polygon(
            ellipse_pts(px, py, size * 0.062, size * 0.105, perp),
            fill=fill,
        )
    return layer


def build_master(with_background: bool):
    """Full icon: gradient shield + white handset on a dark rounded base."""
    base = Image.new("RGBA", (S, S), (0, 0, 0, 0))

    if with_background:
        bg = gradient(S, [(0.0, (10, 14, 38)), (0.5, (26, 20, 66)), (1.0, (52, 14, 86))])
        base.alpha_composite(bg.convert("RGBA"))

    # shield with a vivid gradient
    shield_mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(shield_mask).polygon(shield_points(S), fill=255)

    shield_fill = gradient(S, [(0.0, CYAN), (0.52, VIOLET), (1.0, MAGENTA)])
    base.paste(shield_fill, (0, 0), shield_mask)

    # a soft inner shield line so the edge is not flat
    inner_mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(inner_mask).polygon(shield_points(S, scale=0.82), fill=110)
    base.paste(Image.new("RGBA", (S, S), (10, 16, 40, 255)), (0, 0), inner_mask)

    base.alpha_composite(handset(S))
    return base


def write(path, img, size):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(path, "PNG")


def main():
    master = build_master(with_background=True)
    # Adaptive foreground must be drawn larger and without its own background;
    # Android crops the outer 25% of the layer.
    foreground = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    shield_mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(shield_mask).polygon(shield_points(int(S * 0.72)), fill=255)
    shield_layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    sf = gradient(S, [(0.0, CYAN), (0.52, VIOLET), (1.0, MAGENTA)])
    shield_layer.paste(sf, (0, 0), shield_mask)
    inset = (S - int(S * 0.72)) // 2
    foreground.alpha_composite(shield_layer, (inset, inset))
    foreground.alpha_composite(handset(int(S * 0.72)), (inset, inset))

    densities = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }
    for name, px in densities.items():
        write(f"{RES}/mipmap-{name}/ic_launcher.png", master, px)
        write(f"{RES}/mipmap-{name}/ic_launcher_round.png", master, px)

    # Adaptive-icon layers (108dp canvas, matching the vectors' viewport).
    write(f"{RES}/drawable/ic_launcher_foreground.png", foreground, 432)
    write(f"{RES}/drawable/ic_launcher_background.png",
          gradient(S, [(0.0, (10, 14, 38)), (0.5, (26, 20, 66)), (1.0, (52, 14, 86))]), 432)
    print("icons written")


if __name__ == "__main__":
    main()
