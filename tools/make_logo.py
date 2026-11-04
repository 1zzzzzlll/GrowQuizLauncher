#!/usr/bin/env python3
"""从 docs/img/logo-source.jpg 生成应用图标、自适应图标前景和 TV 横幅。

用法:
    python tools/make_logo.py

源图是白底上的圆形徽章。脚本裁到圆形边界, 用圆形蒙版把四个角去掉(透明),
再按各密度输出到 app/src/main/res/mipmap-*/。横幅放在 drawable-xhdpi。

改了源图或应用名后重跑本脚本即可, 不用手工改 PNG。
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "docs" / "img" / "logo-source.jpg"
RES = ROOT / "app" / "src" / "main" / "res"

# 圆形徽章在白底上的包围盒(用 tools/measure 一遍得来); 略有椭圆, 拉伸成正方。
CIRCLE = (252, 204, 1669, 1653)

MASTER = 1024  # 中间图, 再由它缩到各密度

# 徽章内圈的天蓝, 从源图取色: 方形图标用它铺满, 四角不留黑边。
BLUE = (50, 162, 212)
# 徽章外圈深蓝, 只用于横幅底色。
NAVY = (16, 30, 62)
NAME = "宝贝成长桌面"
TAGLINE = "儿童定时答题桌面"

DENSITIES = {  # 目录后缀: (图标 48dp 的倍数, 自适应前景 108dp 的倍数)
    "mdpi": 1,
    "hdpi": 1.5,
    "xhdpi": 2,
    "xxhdpi": 3,
    "xxxhdpi": 4,
}


def badge_master() -> Image.Image:
    """裁出圆形徽章, 缩到 MASTER 见方, 圆外透明。"""
    im = Image.open(SRC).convert("RGBA").crop(CIRCLE).resize((MASTER, MASTER), Image.LANCZOS)
    mask = Image.new("L", (MASTER * 4, MASTER * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, MASTER * 4 - 1, MASTER * 4 - 1), fill=255)
    mask = mask.resize((MASTER, MASTER), Image.LANCZOS)
    im.putalpha(mask)
    return im


def icon(badge: Image.Image, px: int) -> Image.Image:
    """方形图标, 铺满: 徽章内圈的天蓝铺满整个正方形, 徽章压上去, 四角没有黑边。"""
    out = Image.new("RGBA", (px, px), BLUE + (255,))
    art = badge.resize((px, px), Image.LANCZOS)
    out.paste(art, (0, 0), art)
    return out


def foreground(badge: Image.Image, px: int) -> Image.Image:
    """自适应图标前景: 108dp 画布, 徽章占中间 72dp(安全区)。"""
    out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    inner = round(px * 72 / 108)
    small = badge.resize((inner, inner), Image.LANCZOS)
    off = (px - inner) // 2
    out.paste(small, (off, off), small)
    return out


def gradient(w: int, h: int, a: tuple, b: tuple) -> Image.Image:
    """从左上 a 到右下 b 的斜向渐变。"""
    top = Image.new("RGBA", (w, h), a + (255,))
    bottom = Image.new("RGBA", (w, h), b + (255,))
    mask = Image.new("L", (w, h))
    mp = mask.load()
    for y in range(h):
        for x in range(w):
            mp[x, y] = int((x / w * 0.45 + y / h * 0.55) * 255)
    return Image.composite(bottom, top, mask)


def banner(badge: Image.Image) -> Image.Image:
    """Android TV 横幅 320x180dp, 出 2x。"""
    w, h = 640, 360
    out = gradient(w, h, (20, 36, 70), (9, 17, 38))
    d = ImageDraw.Draw(out)

    size = 232
    art = badge.resize((size, size), Image.LANCZOS)
    out.paste(art, (44, (h - size) // 2), art)

    bold = ImageFont.truetype("C:/Windows/Fonts/msyhbd.ttc", 46)
    light = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 22)
    d.text((300, 118), NAME, font=bold, fill=(255, 255, 255, 255))
    d.text((302, 186), TAGLINE, font=light, fill=(160, 190, 235, 255))
    return out


def readme_banner(badge: Image.Image) -> Image.Image:
    """README 头图 1200x320: 徽章 + 名字 + 一句话 + 三个标签。"""
    w, h = 1200, 320
    out = gradient(w, h, (28, 51, 88), (14, 25, 46))
    d = ImageDraw.Draw(out)

    size = 208
    art = badge.resize((size, size), Image.LANCZOS)
    out.paste(art, (72, (h - size) // 2), art)

    bold = ImageFont.truetype("C:/Windows/Fonts/msyhbd.ttc", 54)
    light = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 23)
    mono = ImageFont.truetype("C:/Windows/Fonts/consola.ttf", 25)

    x = 312
    d.text((x, 74), NAME, font=bold, fill=(255, 255, 255, 255))
    d.text((x + d.textlength(NAME, font=bold) + 20, 100), "GrowQuiz", font=mono, fill=(255, 212, 121, 255))
    d.text((x + 2, 152), "给孩子一台会提问的电视 —— 到点答题，答对才回到桌面", font=light, fill=(201, 216, 239, 255))

    tags = ["Android TV", "8000 道离线题", "GPL-3.0"]
    tag_font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 17)
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    tx = x
    for t in tags:
        tw = round(tag_font.getlength(t)) + 36
        ld.rounded_rectangle((tx, 206, tx + tw, 242), radius=18, fill=(255, 255, 255, 30))
        ld.text((tx + 18, 224), t, font=tag_font, fill=(232, 240, 255, 255), anchor="lm")
        tx += tw + 14
    out.alpha_composite(layer)
    return out


def save(im: Image.Image, path: Path) -> None:
    """WebP: same picture as PNG at about one fifth of the size (API 26 reads it natively)."""
    im.save(path, "WEBP", quality=88, method=6)


def main() -> None:
    badge = badge_master()
    for suffix, scale in DENSITIES.items():
        d = RES / f"mipmap-{suffix}"
        d.mkdir(parents=True, exist_ok=True)
        save(icon(badge, round(48 * scale)), d / "ic_launcher.webp")
        save(foreground(badge, round(108 * scale)), d / "ic_launcher_fg.webp")
        print("wrote", d.name)

    d = RES / "drawable-xhdpi"
    d.mkdir(parents=True, exist_ok=True)
    save(banner(badge), d / "banner.webp")
    print("wrote", d.name, "banner.webp")

    readme_banner(badge).save(ROOT / "docs" / "img" / "banner.png", optimize=True)
    print("wrote docs/img/banner.png")


if __name__ == "__main__":
    main()
