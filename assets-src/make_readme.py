"""Builds the README hero banner and the screenshot strip from emulator captures."""
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import os

HERE = os.path.dirname(__file__)
ROOT = os.path.join(HERE, "..")
SHOTS = os.path.join(ROOT, "docs", "screens")
OUT = os.path.join(ROOT, "docs")
FONT = os.path.join(ROOT, "android", "app", "src", "main", "res", "font", "bricolage.ttf")

def hexc(h): return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))

def sky(w, h, stops):
    col = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / (h - 1)
        for i in range(len(stops) - 1):
            (t0, c0), (t1, c1) = stops[i], stops[i + 1]
            if t0 <= t <= t1:
                f = (t - t0) / (t1 - t0)
                col.putpixel((0, y), tuple(int(c0[k] + (c1[k] - c0[k]) * f) for k in range(3)))
                break
    return col.resize((w, h))

def font(size, wght=800, wdth=86):
    f = ImageFont.truetype(FONT, size)
    want = {"Weight": wght, "Width": wdth, "Optical size": 96 if size > 40 else 24}
    axes = f.get_variation_axes()
    f.set_variation_by_axes([want.get(a["name"].decode() if isinstance(a["name"], bytes) else a["name"], a["default"]) for a in axes])
    return f

def phone(path, width):
    shot = Image.open(path).convert("RGB")
    h = int(width * shot.height / shot.width)
    shot = shot.resize((width, h), Image.LANCZOS)
    pad = int(width * 0.035)
    frame = Image.new("RGBA", (width + 2 * pad, h + 2 * pad), (0, 0, 0, 0))
    r = int(width * 0.12)
    ImageDraw.Draw(frame).rounded_rectangle([0, 0, frame.width - 1, frame.height - 1], r + pad, fill=(6, 7, 16, 255), outline=(246, 241, 231, 40), width=2)
    mask = Image.new("L", shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, width - 1, h - 1], r, fill=255)
    frame.paste(shot, (pad, pad), mask)
    return frame

def drop(canvas, img, xy, blur=28, alpha=150):
    sh = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    m = Image.new("RGBA", img.size, (0, 0, 0, alpha))
    sh.paste(m, (xy[0] + 10, xy[1] + 30), img.split()[3])
    canvas.alpha_composite(sh.filter(ImageFilter.GaussianBlur(blur)))
    canvas.alpha_composite(img, xy)

# ---- hero banner 1600x800
W, H = 1600, 800
hero = sky(W, H, [(0, hexc("#080B1E")), (0.42, hexc("#1B1F4A")), (0.72, hexc("#5B3F70")), (0.9, hexc("#C77E86")), (1, hexc("#FFB877"))]).convert("RGBA")
glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
ImageDraw.Draw(glow).ellipse([1010, 610, 1450, 1050], fill=(255, 209, 102, 200))
hero.alpha_composite(glow.filter(ImageFilter.GaussianBlur(60)))
d = ImageDraw.Draw(hero)
import random
random.seed(7)
for _ in range(140):
    x, y = random.randint(0, W), random.randint(0, int(H * 0.55))
    a = random.randint(80, 220); s = random.choice([1, 1, 2])
    d.ellipse([x, y, x + s, y + s], fill=(255, 255, 255, a))
d.polygon([(0, 720), (260, 680), (520, 705), (820, 665), (1120, 700), (1400, 672), (1600, 690), (1600, 800), (0, 800)], fill=(42, 28, 56, 255))
d.polygon([(0, 760), (300, 735), (640, 752), (980, 728), (1300, 748), (1600, 735), (1600, 800), (0, 800)], fill=(18, 14, 34, 255))

d.ellipse([90, 118, 112, 140], fill=hexc("#FFD166"))
d.text((126, 108), "Rise", font=font(36, 700, 100), fill=hexc("#F6F1E7"))
y = 200
for line in ["The alarm your", "friends are", "betting on."]:
    d.text((88, y), line, font=font(92), fill=hexc("#F6F1E7")); y += 96
body = font(27, 450, 100)
d.text((90, 520), "Wake up and clock in on Solana before your window", font=body, fill=(232, 226, 216))
d.text((90, 556), "closes. Sleep in, and your SKR is split among the", font=body, fill=(232, 226, 216))
d.text((90, 592), "friends who made it.", font=body, fill=(232, 226, 216))
chip = font(19, 600, 100)
x = 90
for t in ["Kotlin + Compose", "Mobile Wallet Adapter", "Anchor", "SKR"]:
    w = d.textlength(t, font=chip) + 32
    d.rounded_rectangle([x, 642, x + w, 680], 19, outline=(246, 241, 231, 110), width=2)
    d.text((x + 16, 661), t, font=chip, fill=(232, 226, 216), anchor="lm"); x += w + 10

p1 = phone(os.path.join(SHOTS, "11-ringing.jpg"), 300)
p2 = phone(os.path.join(SHOTS, "14-clocked-in.jpg"), 320)
p3 = phone(os.path.join(SHOTS, "08-join.jpg"), 300)
drop(hero, p1.rotate(5, expand=True, resample=Image.BICUBIC), (850, 125))
drop(hero, p3.rotate(-5, expand=True, resample=Image.BICUBIC), (1225, 115))
drop(hero, p2, (1030, 60))
hero.convert("RGB").save(os.path.join(OUT, "hero.jpg"), quality=90, optimize=True, progressive=True)

# ---- screenshot strips (each phone 360 wide)
def strip(names, out):
    phones = [phone(os.path.join(SHOTS, n + ".jpg"), 330) for n in names]
    gap = 34
    w = sum(p.width for p in phones) + gap * (len(phones) + 1)
    h = max(p.height for p in phones) + 2 * gap
    c = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    x = gap
    for p in phones:
        c.alpha_composite(p, (x, gap)); x += p.width + gap
    c.save(os.path.join(OUT, out), quality=90, method=6)

strip(["01-welcome", "03-wake", "04-mission", "06-today"], "strip-setup.webp")
strip(["10-sunrise", "11-ringing", "12-mission", "14-clocked-in"], "strip-morning.webp")
strip(["07-pacts", "08-join", "09-you", "05-ready"], "strip-pacts.webp")
print("ok")
