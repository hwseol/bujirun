"""Store screenshots: each raw phone capture (store/raw/<name>.png, 1080x2400) on a brand
gradient with a caption above, 1080x1920 (Play wants the long side at most twice the short).
Run: python make_screens.py <repo root>   (needs Pillow)
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

sys.path.insert(0, os.path.dirname(__file__))
from make_art import gradient  # noqa: E402

SHOTS = [
    ("home", "오늘 걸음과 운동을 한눈에", "시작 버튼 하나로 바로 달리기"),
    ("live", "화면 안 봐도 괜찮아요", "갈림길 전에 음성으로 방향 안내"),
    ("course", "코스를 만들고 따라 달리기", "AI 추천 · DIY · GPX, 큰 흐름만 안내"),
    ("summary", "달리고 나면 깔끔한 기록", "페이스, 경로, 운동 사진까지"),
    ("history", "달력으로 보는 나의 기록", "걷기·달리기 자동 기록과 걸음 수"),
    ("account", "개인 최고 기록과 월 목표", "누적 거리와 주간 연속 기록"),
]
W, H = 1080, 1920


def frame(raw, title, sub):
    bg = gradient(W, H).convert("RGBA")
    d = ImageDraw.Draw(bg)
    bold = ImageFont.truetype("C:/Windows/Fonts/malgunbd.ttf", 66)
    reg = ImageFont.truetype("C:/Windows/Fonts/malgun.ttf", 40)
    for text, font, y in ((title, bold, 92), (sub, reg, 192)):
        w = d.textlength(text, font=font)
        d.text(((W - w) / 2, y), text, font=font, fill="white")
    shot = Image.open(raw).convert("RGBA")
    # Drop the status bar, round the corners, scale into the space left.
    shot = shot.crop((0, 80, shot.width, shot.height))
    target_h = H - 300
    scale = target_h / shot.height
    shot = shot.resize((round(shot.width * scale), target_h), Image.LANCZOS)
    mask = Image.new("L", shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, shot.width, shot.height + 60], radius=44, fill=255)
    x = (W - shot.width) // 2
    shadow = Image.new("RGBA", bg.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([x, 300, x + shot.width, H + 60], radius=44, fill=(80, 0, 20, 90))
    bg = Image.alpha_composite(bg, shadow.filter(ImageFilter.GaussianBlur(24)))
    bg.paste(shot, (x, 300), mask)
    return bg.convert("RGB")


if __name__ == "__main__":
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    out = os.path.join(root, "store", "screens")
    os.makedirs(out, exist_ok=True)
    for i, (name, title, sub) in enumerate(SHOTS, 1):
        raw = os.path.join(root, "store", "raw", name + ".png")
        if os.path.exists(raw):
            frame(raw, title, sub).save(os.path.join(out, f"{i}-{name}.png"))
            print("made", name)
        else:
            print("missing", name)
