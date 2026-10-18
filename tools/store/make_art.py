"""Play Store art from the app icon's design (res/drawable/ic_launcher_*.xml): the 512 px icon
and the 1024x500 feature graphic. Run: python make_art.py <out dir>   (needs Pillow)
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ORANGE = (0xFF, 0x6A, 0x2B)
CORAL = (0xFF, 0x3D, 0x6E)
SS = 4  # supersampling


def gradient(w, h):
    """Orange top-left to coral bottom-right, as the icon background."""
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / w + y / h) / 2
            px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(ORANGE, CORAL))
    return img


def bezier(p0, p1, p2, p3, n=60):
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        yield (u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0],
               u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1])


def mark(draw, ox, oy, scale):
    """The route line and two dots, from the foreground vector (108 units)."""
    def p(x, y):
        return (ox + x * scale, oy + y * scale)
    pts = list(bezier(p(34, 72), p(34, 54), p(54, 62), p(54, 46), 400)) + list(bezier(p(54, 46), p(54, 34), p(70, 34), p(74, 40), 400))
    # A round brush stamped densely along the curve: smooth, round-capped (wide polylines leave
    # notches at the joints).
    r = 3 * scale
    for x, y in pts:
        draw.ellipse([x - r, y - r, x + r, y + r], fill="white")
    for cx, cy in ((34, 72), (74, 38)):
        r = 6 * scale
        x, y = p(cx, cy)
        draw.ellipse([x - r, y - r, x + r, y + r], fill="white")


def light(img, cx, cy, r, alpha):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 255, 255, alpha))
    return Image.alpha_composite(img.convert("RGBA"), layer)


def icon(size=512):
    s = size * SS
    img = gradient(s, s)
    img = light(img, s, s * (30 / 108), s * (60 / 108), 26)
    mark(ImageDraw.Draw(img), 0, 0, s / 108)
    return img.resize((size, size), Image.LANCZOS).convert("RGB")


def feature(w=1024, h=500):
    W, H = w * SS, h * SS
    img = gradient(W, H)
    img = light(img, W * 0.95, H * 0.05, H * 0.9, 26)
    img = light(img, W * 0.05, H * 1.1, H * 0.55, 15)
    d = ImageDraw.Draw(img)
    # The mark on the left, big.
    mark(d, W * 0.06, H * 0.02, H / 108 * 0.95)
    bold = ImageFont.truetype("C:/Windows/Fonts/malgunbd.ttf", round(H * 0.24))
    reg = ImageFont.truetype("C:/Windows/Fonts/malgunbd.ttf", round(H * 0.072))
    x = W * 0.44
    d.text((x, H * 0.2), "부지런", font=bold, fill="white")
    d.text((x, H * 0.56), "이어폰만 끼고 달리세요", font=reg, fill="white")
    d.text((x, H * 0.68), "음성 코칭 · AI 코스 · 자동 기록", font=reg, fill=(255, 255, 255))
    return img.resize((w, h), Image.LANCZOS).convert("RGB")


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)
    icon().save(os.path.join(out, "icon-512.png"))
    feature().save(os.path.join(out, "feature-1024x500.png"))
    print("ok")
