"""Draws Rise's icon, the test-SKR token image and the social preview card."""
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import os

OUT = os.path.join(os.path.dirname(__file__), "..", "web", "public")
FONT = os.path.join(os.path.dirname(__file__), "..", "android", "app", "src", "main", "res", "font", "bricolage.ttf")

def sky(w, h, stops):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        t = y / (h - 1)
        for i in range(len(stops) - 1):
            (t0, c0), (t1, c1) = stops[i], stops[i + 1]
            if t0 <= t <= t1:
                f = (t - t0) / (t1 - t0)
                c = tuple(int(c0[k] + (c1[k] - c0[k]) * f) for k in range(3))
                break
        for x in range(w):
            px[x, y] = c
    return img

def hexc(h): return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))

def icon(size=512):
    img = sky(size, size, [(0, hexc("#0E1430")), (0.55, hexc("#4B3B78")), (1, hexc("#F2A0A1"))])
    d = ImageDraw.Draw(img)
    s = size / 108
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([35 * s - 12 * s, 38 * s - 12 * s, 73 * s + 12 * s, 76 * s + 12 * s], fill=(255, 209, 102, 90))
    img.paste(glow.filter(ImageFilter.GaussianBlur(10 * s)), (0, 0), glow.filter(ImageFilter.GaussianBlur(10 * s)))
    d.pieslice([35 * s, 38 * s, 73 * s, 76 * s], 180, 360, fill=hexc("#FFD166"))
    d.rectangle([28 * s, 57 * s, 80 * s, 60.5 * s], fill=hexc("#F6F1E7"))
    d.rectangle([36 * s, 64 * s, 72 * s, 67 * s], fill=(246, 241, 231))
    d.rectangle([44 * s, 70.5 * s, 64 * s, 73 * s], fill=(200, 190, 200))
    return img

icon(512).save(os.path.join(OUT, "icon.png"))

# Test SKR token image: a sun-gold coin, clearly marked as a devnet test token.
c = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
d = ImageDraw.Draw(c)
d.ellipse([16, 16, 496, 496], fill=hexc("#FFD166"))
d.ellipse([46, 46, 466, 466], outline=hexc("#F4A52A"), width=10)
f = ImageFont.truetype(FONT, 150); f.set_variation_by_axes([800, 86, 96]) if hasattr(f, "set_variation_by_axes") else None
d.text((256, 236), "SKR", font=f, fill=hexc("#0E1430"), anchor="mm")
f2 = ImageFont.truetype(FONT, 44)
d.text((256, 348), "devnet test", font=f2, fill=hexc("#0E1430"), anchor="mm")
c.save(os.path.join(OUT, "skr.png"))

# Social card 1200x630.
og = sky(1200, 630, [(0, hexc("#080B1E")), (0.5, hexc("#2B2452")), (0.85, hexc("#A5677E")), (1, hexc("#FFB877"))])
g = Image.new("RGBA", og.size, (0, 0, 0, 0))
ImageDraw.Draw(g).ellipse([820, 430, 1100, 710], fill=(255, 209, 102, 255))
og.paste(g.filter(ImageFilter.GaussianBlur(3)), (0, 0), g.filter(ImageFilter.GaussianBlur(3)))
d = ImageDraw.Draw(og)
d.polygon([(0, 560), (200, 520), (420, 550), (700, 510), (1000, 545), (1200, 520), (1200, 630), (0, 630)], fill=hexc("#120E22"))
big = ImageFont.truetype(FONT, 84)
try: big.set_variation_by_axes([800, 86, 96])
except Exception: pass
d.text((70, 150), "The alarm your friends", font=big, fill=hexc("#F6F1E7"))
d.text((70, 245), "are betting on.", font=big, fill=hexc("#F6F1E7"))
small = ImageFont.truetype(FONT, 34)
d.text((70, 370), "Clock in on Solana before your window closes,", font=small, fill=(230, 224, 214))
d.text((70, 415), "or your SKR is split among the friends who made it.", font=small, fill=(230, 224, 214))
d.ellipse([70, 82, 92, 104], fill=hexc("#FFD166"))
d.text((104, 74), "Rise", font=ImageFont.truetype(FONT, 34), fill=hexc("#F6F1E7"))
og.save(os.path.join(OUT, "og.png"))
print("ok")
