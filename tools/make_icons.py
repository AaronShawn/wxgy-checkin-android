import os
from PIL import Image, ImageDraw, ImageFont

RES = r"C:/Users/Aaron/WorkBuddy/2026-11-24-10-14-10/android/app/src/main/res"
FONT = "C:/Windows/Fonts/msyhbd.ttc"

C1 = (93, 75, 240)    # #5D4BF0
C2 = (139, 123, 255)  # #8B7BFF


def gradient(size):
    img = Image.new("RGB", (size, size))
    d = ImageDraw.Draw(img)
    # 对角渐变，与 bg_logo / bg_hero 保持一致
    for i in range(size * 2):
        t = i / (size * 2 - 1)
        col = tuple(int(C1[k] + (C2[k] - C1[k]) * t) for k in range(3))
        d.line([(i, 0), (0, i)], fill=col, width=2)
    return img


def rounded_mask(size, radius):
    m = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        [0, 0, size * 4 - 1, size * 4 - 1], radius=radius * 4, fill=255)
    return m.resize((size, size), Image.LANCZOS)


def legacy_icon(size):
    radius = int(size * 0.235)
    bg = gradient(size)
    mask = rounded_mask(size, radius)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(bg, (0, 0), mask)

    g = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(g)
    font = ImageFont.truetype(FONT, int(size * 0.56))
    box = d.textbbox((0, 0), "查", font=font)
    w, h = box[2] - box[0], box[3] - box[1]
    d.text(((size - w) / 2 - box[0], (size - h) / 2 - box[1] - size * 0.01),
           "查", font=font, fill=(255, 255, 255, 255))
    out = Image.alpha_composite(out, g)
    return out


def foreground(size):
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(out)
    font = ImageFont.truetype(FONT, int(size * 0.40))
    box = d.textbbox((0, 0), "查", font=font)
    w, h = box[2] - box[0], box[3] - box[1]
    d.text(((size - w) / 2 - box[0], (size - h) / 2 - box[1]),
           "查", font=font, fill=(255, 255, 255, 255))
    return out


for folder, size in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                     ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)]:
    d = os.path.join(RES, folder)
    os.makedirs(d, exist_ok=True)
    icon = legacy_icon(size)
    icon.save(os.path.join(d, "ic_launcher.png"))
    icon.save(os.path.join(d, "ic_launcher_round.png"))

fgdir = os.path.join(RES, "mipmap-xxxhdpi")
foreground(432).save(os.path.join(fgdir, "ic_launcher_foreground.png"))

print("icons written")
