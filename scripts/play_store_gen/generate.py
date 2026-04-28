"""Play Store asset generator for Tzeva Artzi - Home Front Alerts.

Outputs a brand-consistent set of:
  - 512x512 app icon (uses existing source logo)
  - 1024x500 feature graphic
  - 8 phone screenshots (1080x1920)
  - 8 7-inch tablet screenshots (1200x1920)
  - 8 10-inch tablet screenshots (1600x2560)

Brand:
  bg          #0A1428
  panel       #152544
  panel-line  #233860
  orange      #E85A2C
  orange-soft #F08456
  green       #2CC069
  red         #E03B3B
  white       #FFFFFF
  muted       #9AB0CC
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

# ---------- paths ----------
ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "android-app" / "app" / "src" / "main" / "play_store_assets"
SRC_ICON = ASSETS / "icon_512_final.png"
OUT_PHONE = ASSETS / "screenshots" / "phone"
OUT_T7 = ASSETS / "screenshots" / "tablet_7"
OUT_T10 = ASSETS / "screenshots" / "tablet_10"

# ---------- palette ----------
BG_TOP = (10, 20, 40)
BG_BOTTOM = (4, 10, 22)
PANEL = (21, 37, 68)
PANEL_LINE = (35, 56, 96)
ORANGE = (232, 90, 44)
ORANGE_SOFT = (240, 132, 86)
GREEN = (44, 192, 105)
RED = (224, 59, 59)
WHITE = (255, 255, 255)
MUTED = (154, 176, 204)

# ---------- fonts ----------
WIN_FONTS = Path(r"C:\Windows\Fonts")
F_REG = str(WIN_FONTS / "segoeui.ttf")
F_BOLD = str(WIN_FONTS / "segoeuib.ttf")
F_LIGHT = str(WIN_FONTS / "segoeuil.ttf")
F_SEMI = str(WIN_FONTS / "segoeuisl.ttf")
F_HE_REG = str(WIN_FONTS / "david.ttf")
F_HE_BOLD = str(WIN_FONTS / "davidbd.ttf")


def load(font: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(font, size)


# ---------- background ----------
def make_bg(w: int, h: int) -> Image.Image:
    img = Image.new("RGB", (w, h), BG_BOTTOM)
    px = img.load()
    for y in range(h):
        t = y / max(h - 1, 1)
        r = int(BG_TOP[0] * (1 - t) + BG_BOTTOM[0] * t)
        g = int(BG_TOP[1] * (1 - t) + BG_BOTTOM[1] * t)
        b = int(BG_TOP[2] * (1 - t) + BG_BOTTOM[2] * t)
        for x in range(w):
            px[x, y] = (r, g, b)
    # soft orange glow top-right and bottom-left for depth
    glow = Image.new("RGB", (w, h), (0, 0, 0))
    gd = ImageDraw.Draw(glow)
    rad = int(min(w, h) * 0.55)
    gd.ellipse(
        (w - rad, -rad // 2, w + rad // 2, rad),
        fill=(60, 30, 12),
    )
    gd.ellipse(
        (-rad // 2, h - rad, rad // 2, h + rad // 2),
        fill=(30, 18, 8),
    )
    glow = glow.filter(ImageFilter.GaussianBlur(radius=int(min(w, h) * 0.06)))
    img = Image.blend(img, glow, 0.55)
    return img


def add_grid(img: Image.Image, spacing: int, color: tuple = None, alpha: int = 18) -> None:
    """Subtle dotted grid overlay."""
    color = color or PANEL_LINE
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    for y in range(0, img.size[1], spacing):
        for x in range(0, img.size[0], spacing):
            od.ellipse((x - 1, y - 1, x + 1, y + 1), fill=color + (alpha,))
    img.alpha_composite(overlay) if img.mode == "RGBA" else img.paste(
        Image.alpha_composite(img.convert("RGBA"), overlay).convert("RGB")
    )


# ---------- primitives ----------
def rounded(draw: ImageDraw.ImageDraw, xy, radius, fill=None, outline=None, width=1):
    draw.rounded_rectangle(xy, radius=radius, fill=fill, outline=outline, width=width)


def shadow_rounded(base: Image.Image, xy, radius, fill, blur=20, offset=(0, 8), opacity=140):
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    sx, sy = offset
    d.rounded_rectangle(
        (xy[0] + sx, xy[1] + sy, xy[2] + sx, xy[3] + sy),
        radius=radius,
        fill=(0, 0, 0, opacity),
    )
    layer = layer.filter(ImageFilter.GaussianBlur(blur))
    base.alpha_composite(layer)
    d2 = ImageDraw.Draw(base)
    d2.rounded_rectangle(xy, radius=radius, fill=fill)


# ---------- logo ----------
_logo_cache: dict[int, Image.Image] = {}


def logo(size: int) -> Image.Image:
    if size in _logo_cache:
        return _logo_cache[size]
    src = Image.open(SRC_ICON).convert("RGBA")
    img = src.resize((size, size), Image.LANCZOS)
    _logo_cache[size] = img
    return img


def logo_no_bg(size: int) -> Image.Image:
    """Crop the rounded square so the dark navy chip blends with the background."""
    src = Image.open(SRC_ICON).convert("RGBA")
    return src.resize((size, size), Image.LANCZOS)


# ---------- text helpers ----------
def measure(draw: ImageDraw.ImageDraw, text: str, font: ImageFont.FreeTypeFont) -> tuple[int, int]:
    bbox = draw.textbbox((0, 0), text, font=font)
    return bbox[2] - bbox[0], bbox[3] - bbox[1]


def draw_text(img, x, y, text, font, fill=WHITE, anchor="lt"):
    d = ImageDraw.Draw(img)
    d.text((x, y), text, font=font, fill=fill, anchor=anchor)


# Right-to-left visual reordering for Hebrew text drawn with PIL (which doesn't shape RTL).
# PIL renders left-to-right; for Hebrew we reverse the character order so the visual
# output reads correctly. This is sufficient for short marketing strings without bidi mixing.
def he(text: str) -> str:
    return text[::-1]


# ---------- icons (simple line glyphs) ----------
def bell(draw, cx, cy, size, color=WHITE):
    s = size
    draw.rounded_rectangle((cx - s, cy - s, cx + s, cy + s // 3), radius=s, outline=color, width=max(2, s // 6))
    draw.line((cx - s // 2, cy + s // 3, cx + s // 2, cy + s // 3), fill=color, width=max(2, s // 6))
    draw.ellipse((cx - s // 6, cy + s // 3, cx + s // 6, cy + s // 3 + s // 3), fill=color)


def shield_glyph(draw, cx, cy, w, color=ORANGE):
    """Simplified shield + wifi waves outline."""
    h = int(w * 1.1)
    pts = [
        (cx - w // 2, cy - h // 2 + h // 8),
        (cx, cy - h // 2),
        (cx + w // 2, cy - h // 2 + h // 8),
        (cx + w // 2, cy + h // 6),
        (cx, cy + h // 2),
        (cx - w // 2, cy + h // 6),
    ]
    draw.polygon(pts, outline=color, width=max(3, w // 18))
    # wifi arcs
    for i, r in enumerate([w // 5, int(w * 0.32), int(w * 0.46)]):
        draw.arc((cx - r, cy - r // 2 - 4, cx + r, cy + r // 2 + r), start=210, end=330, fill=color, width=max(3, w // 18))
    # dot
    draw.ellipse((cx - w // 22, cy + w // 8, cx + w // 22, cy + w // 8 + w // 11), fill=color)


# ---------- card builders ----------
def status_card(width: int, height: int, mode: str, lang: str = "en") -> Image.Image:
    """Render a phone-like status card centerpiece. mode in {alert, safe}."""
    card = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    d = ImageDraw.Draw(card)

    # outer frame (phone bezel)
    radius = int(width * 0.09)
    bezel_color = (18, 28, 50, 255)
    d.rounded_rectangle((0, 0, width, height), radius=radius, fill=bezel_color)
    inner = (int(width * 0.04), int(width * 0.04), width - int(width * 0.04), height - int(width * 0.04))
    d.rounded_rectangle(inner, radius=int(radius * 0.85), fill=BG_TOP + (255,))

    pad = int(width * 0.07)
    cx = width // 2
    top = inner[1] + pad

    # top header bar
    title_font = load(F_BOLD if lang == "en" else F_HE_BOLD, int(width * 0.075))
    title_text = "Tzeva Artzi" if lang == "en" else he("צבע ארצי")
    d.text((cx, top), title_text, font=title_font, fill=WHITE, anchor="mt")
    top += int(width * 0.13)

    # status panel — sized so it fits everything (shield + labels) regardless of card aspect
    label_font = load(F_REG if lang == "en" else F_HE_REG, int(width * 0.038))
    big_font = load(F_BOLD if lang == "en" else F_HE_BOLD, int(width * 0.085))
    sub_font = load(F_REG if lang == "en" else F_HE_REG, int(width * 0.034))
    shield_w = int(width * 0.34)
    shield_h_visual = int(shield_w * 1.1)
    label_block_h = int(width * 0.05) + int(width * 0.085) + int(width * 0.04) + int(width * 0.12) + int(width * 0.05)
    panel_inner_pad = int(width * 0.05)
    panel_h = shield_h_visual + label_block_h + panel_inner_pad * 2
    # don't let panel exceed half the card height
    panel_h = min(panel_h, int(height * 0.5))
    panel_xy = (inner[0] + pad, top, inner[2] - pad, top + panel_h)

    shield_cy = top + panel_inner_pad + shield_h_visual // 2
    label_y_start = shield_cy + shield_h_visual // 2 + int(width * 0.03)

    if mode == "alert":
        d.rounded_rectangle(panel_xy, radius=int(width * 0.06), fill=(28, 16, 10, 255))
        d.rounded_rectangle(panel_xy, radius=int(width * 0.06), outline=ORANGE, width=max(4, width // 90))
        shield_glyph(d, cx, shield_cy, shield_w, color=ORANGE)
        label = "CURRENT STATUS" if lang == "en" else he("מצב נוכחי")
        big = "THREAT" if lang == "en" else he("איום")
        sub = "Alerts active in your area" if lang == "en" else he("התראות פעילות באזור")
        label_color = ORANGE_SOFT
    else:
        d.rounded_rectangle(panel_xy, radius=int(width * 0.06), fill=(10, 38, 24, 255))
        d.rounded_rectangle(panel_xy, radius=int(width * 0.06), outline=GREEN, width=max(4, width // 90))
        shield_glyph(d, cx, shield_cy, shield_w, color=GREEN)
        label = "CURRENT STATUS" if lang == "en" else he("מצב נוכחי")
        big = "ALL QUIET" if lang == "en" else he("שקט")
        sub = "No active alerts nearby" if lang == "en" else he("אין התראות פעילות")
        label_color = GREEN

    d.text((cx, label_y_start), label, font=label_font, fill=label_color, anchor="mt")
    d.text((cx, label_y_start + int(width * 0.05)), big, font=big_font, fill=WHITE, anchor="mt")
    d.text((cx, label_y_start + int(width * 0.05) + int(width * 0.12)), sub, font=sub_font, fill=MUTED, anchor="mt")

    # recent alerts list
    list_top = panel_xy[3] + pad
    list_h = inner[3] - pad - list_top
    d.rounded_rectangle((inner[0] + pad, list_top, inner[2] - pad, list_top + list_h),
                        radius=int(width * 0.05), fill=PANEL + (255,))
    head = "Recent Alerts" if lang == "en" else he("התראות אחרונות")
    head_font = load(F_BOLD if lang == "en" else F_HE_BOLD, int(width * 0.04))
    d.text((inner[0] + pad * 2, list_top + pad), head, font=head_font, fill=WHITE)

    row_y = list_top + pad + int(width * 0.09)
    if mode == "alert":
        rows_en = [("Tel Aviv – Center", "Active 8m"), ("Ashdod", "Active 4m"), ("Beersheba", "Active 2m")]
        rows_he = [(he("תל אביב – מרכז"), he("פעיל 8 דק'")), (he("אשדוד"), he("פעיל 4 דק'")), (he("באר שבע"), he("פעיל 2 דק'"))]
        rows = rows_en if lang == "en" else rows_he
    else:
        rows = [(("None in the last 24h", "")) if lang == "en" else ((he("אין התראות ב־24 השעות האחרונות"), ""))]

    rf = load(F_REG if lang == "en" else F_HE_REG, int(width * 0.034))
    rf_small = load(F_REG if lang == "en" else F_HE_REG, int(width * 0.028))
    for primary, meta in rows:
        if mode == "alert":
            d.ellipse((inner[0] + pad * 2, row_y + 4, inner[0] + pad * 2 + int(width * 0.025), row_y + 4 + int(width * 0.025)), fill=ORANGE)
            d.text((inner[0] + pad * 2 + int(width * 0.04), row_y), primary, font=rf, fill=WHITE)
            d.text((inner[2] - pad * 2, row_y), meta, font=rf_small, fill=MUTED, anchor="rt")
        else:
            d.text((cx, row_y + int(width * 0.05)), primary, font=rf, fill=MUTED, anchor="mt")
        row_y += int(width * 0.075)

    return card


def settings_card(width: int, height: int, lang: str = "en") -> Image.Image:
    card = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    d = ImageDraw.Draw(card)
    radius = int(width * 0.09)
    d.rounded_rectangle((0, 0, width, height), radius=radius, fill=(18, 28, 50, 255))
    inner = (int(width * 0.04), int(width * 0.04), width - int(width * 0.04), height - int(width * 0.04))
    d.rounded_rectangle(inner, radius=int(radius * 0.85), fill=BG_TOP + (255,))

    pad = int(width * 0.07)
    cx = width // 2
    title_font = load(F_BOLD if lang == "en" else F_HE_BOLD, int(width * 0.07))
    title = "Settings" if lang == "en" else he("הגדרות")
    d.text((cx, inner[1] + pad), title, font=title_font, fill=WHITE, anchor="mt")

    items_en = [
        ("Live GPS Identity", "Continuous location for proximity alerts", True),
        ("Master Alert Volume", "85%", None),
        ("Home Zone", "Set my zone", False),
        ("Language", "English (US) / עברית", None),
        ("Notification History", "View recent", None),
    ]
    items_he = [
        (he("מיקום GPS חי"), he("מיקום רציף להתראות מדויקות"), True),
        (he("עוצמת התראה"), he("85%"), None),
        (he("אזור הבית"), he("הגדר אזור"), False),
        (he("שפה"), he("עברית / English"), None),
        (he("היסטוריית התראות"), he("צפייה אחרונות"), None),
    ]
    items = items_en if lang == "en" else items_he

    y = inner[1] + pad + int(width * 0.13)
    item_h = int(width * 0.16)
    label_font = load(F_BOLD if lang == "en" else F_HE_BOLD, int(width * 0.04))
    sub_font = load(F_REG if lang == "en" else F_HE_REG, int(width * 0.03))

    for label, sub, toggle in items:
        d.rounded_rectangle((inner[0] + pad, y, inner[2] - pad, y + item_h),
                            radius=int(width * 0.04), fill=PANEL + (255,))
        d.text((inner[0] + pad * 2, y + int(item_h * 0.22)), label, font=label_font, fill=WHITE)
        d.text((inner[0] + pad * 2, y + int(item_h * 0.55)), sub, font=sub_font, fill=MUTED)
        if toggle is not None:
            tw = int(width * 0.13)
            th = int(item_h * 0.4)
            tx = inner[2] - pad * 2 - tw
            ty = y + (item_h - th) // 2
            d.rounded_rectangle((tx, ty, tx + tw, ty + th), radius=th // 2,
                                fill=ORANGE if toggle else (60, 70, 90, 255))
            knob_x = tx + tw - th + 2 if toggle else tx + 2
            d.ellipse((knob_x, ty + 2, knob_x + th - 4, ty + th - 2), fill=WHITE)
        y += item_h + int(width * 0.025)

    return card


def legend_card(width: int, height: int, lang: str = "en") -> Image.Image:
    card = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    d = ImageDraw.Draw(card)
    radius = int(width * 0.09)
    d.rounded_rectangle((0, 0, width, height), radius=radius, fill=(18, 28, 50, 255))
    inner = (int(width * 0.04), int(width * 0.04), width - int(width * 0.04), height - int(width * 0.04))
    d.rounded_rectangle(inner, radius=int(radius * 0.85), fill=BG_TOP + (255,))

    pad = int(width * 0.07)
    cx = width // 2
    title_font = load(F_BOLD if lang == "en" else F_HE_BOLD, int(width * 0.065))
    sub_font = load(F_REG if lang == "en" else F_HE_REG, int(width * 0.034))
    title = "Sound Legend" if lang == "en" else he("מקרא צלילים")
    sub_text = "Recognize alerts without looking" if lang == "en" else he("זיהוי התראות ללא מבט במסך")

    d.text((cx, inner[1] + pad), title, font=title_font, fill=WHITE, anchor="mt")
    d.text((cx, inner[1] + pad + int(width * 0.1)), sub_text, font=sub_font, fill=MUTED, anchor="mt")

    items_en = [
        ("Urgent", "Staccato pattern", RED),
        ("Caution", "Two-note melody", ORANGE),
        ("All Clear", "Soft melody", GREEN),
    ]
    items_he = [
        (he("דחוף"), he("דפוס מקוטע"), RED),
        (he("זהירות"), he("מנגינה כפולה"), ORANGE),
        (he("הכל נקי"), he("מנגינה רכה"), GREEN),
    ]
    items = items_en if lang == "en" else items_he

    y = inner[1] + pad + int(width * 0.22)
    item_h = int(width * 0.22)
    name_font = load(F_BOLD if lang == "en" else F_HE_BOLD, int(width * 0.05))
    desc_font = load(F_REG if lang == "en" else F_HE_REG, int(width * 0.034))

    for name, desc, color in items:
        d.rounded_rectangle((inner[0] + pad, y, inner[2] - pad, y + item_h),
                            radius=int(width * 0.045), fill=PANEL + (255,))
        # icon circle
        ic = int(item_h * 0.5)
        ix = inner[0] + pad * 2
        iy = y + (item_h - ic) // 2
        d.ellipse((ix, iy, ix + ic, iy + ic), fill=color + (255,))
        # waveform glyph inside circle
        wf_w = int(ic * 0.5)
        wf_x = ix + (ic - wf_w) // 2
        wf_y = iy + ic // 2
        for i, dy in enumerate([-ic // 4, ic // 6, -ic // 6, ic // 4]):
            d.line((wf_x + i * wf_w // 3, wf_y - abs(dy), wf_x + i * wf_w // 3, wf_y + abs(dy)),
                   fill=WHITE, width=max(2, wf_w // 12))
        d.text((ix + ic + pad, y + int(item_h * 0.22)), name, font=name_font, fill=WHITE)
        d.text((ix + ic + pad, y + int(item_h * 0.55)), desc, font=desc_font, fill=MUTED)
        y += item_h + int(width * 0.03)

    # Got It button
    btn_w = int(width * 0.5)
    btn_h = int(width * 0.13)
    bx = cx - btn_w // 2
    by = inner[3] - pad - btn_h
    d.rounded_rectangle((bx, by, bx + btn_w, by + btn_h), radius=btn_h // 2, fill=ORANGE + (255,))
    btn_font = load(F_BOLD if lang == "en" else F_HE_BOLD, int(width * 0.05))
    btn_text = "Got it" if lang == "en" else he("הבנתי")
    d.text((cx, by + btn_h // 2), btn_text, font=btn_font, fill=WHITE, anchor="mm")

    return card


# ---------- screenshot composer ----------
def compose_screenshot(W: int, H: int, scene: dict) -> Image.Image:
    """scene = { headline, subline, kind: alert|safe|settings|legend, lang: en|he }"""
    img = make_bg(W, H).convert("RGBA")
    add_grid(img, spacing=int(W * 0.06))

    d = ImageDraw.Draw(img)
    margin = int(W * 0.06)
    lang = scene["lang"]

    # top brand
    lh = int(W * 0.075)
    img.alpha_composite(logo(lh), (margin, margin))
    bf = load(F_BOLD if lang == "en" else F_HE_BOLD, int(W * 0.034))
    brand_text = "Tzeva Artzi" if lang == "en" else he("צבע ארצי")
    d.text((margin + lh + int(W * 0.025), margin + lh // 2), brand_text, font=bf, fill=WHITE, anchor="lm")

    # headline area
    head_top = margin + lh + int(H * 0.025)
    head_font = load(F_BOLD if lang == "en" else F_HE_BOLD, int(W * 0.072))
    sub_font = load(F_REG if lang == "en" else F_HE_REG, int(W * 0.04))
    headline = scene["headline"]
    subline = scene["subline"]

    # multi-line headline wrap
    max_text_w = W - margin * 2

    def wrap(text, font, max_w):
        words = text.split()
        lines = []
        cur = []
        for w in words:
            test = " ".join(cur + [w])
            tw = d.textlength(test, font=font)
            if tw <= max_w or not cur:
                cur.append(w)
            else:
                lines.append(" ".join(cur))
                cur = [w]
        if cur:
            lines.append(" ".join(cur))
        return lines

    hl_lines = wrap(headline, head_font, max_text_w) if lang == "en" else [headline]
    y = head_top
    line_h = int(W * 0.085)
    for ln in hl_lines:
        if lang == "he":
            d.text((W - margin, y), ln, font=head_font, fill=WHITE, anchor="rt")
        else:
            d.text((margin, y), ln, font=head_font, fill=WHITE, anchor="lt")
        y += line_h
    y += int(W * 0.01)
    # accent underline
    if lang == "en":
        d.rectangle((margin, y, margin + int(W * 0.12), y + int(W * 0.01)), fill=ORANGE)
    else:
        d.rectangle((W - margin - int(W * 0.12), y, W - margin, y + int(W * 0.01)), fill=ORANGE)
    y += int(W * 0.04)
    if lang == "he":
        d.text((W - margin, y), subline, font=sub_font, fill=MUTED, anchor="rt")
    else:
        d.text((margin, y), subline, font=sub_font, fill=MUTED, anchor="lt")

    # phone/centerpiece card — sized to leave room for headline above and tagline below
    head_block_bottom = y + int(W * 0.05)  # bottom of the subline + a small gap
    tagline_reserve = int(H * 0.06)
    available_h = H - head_block_bottom - tagline_reserve - margin
    card_w = int(W * 0.78)
    # cap aspect ratio at ~1.55, but shrink to fit available height
    card_h = min(int(card_w * 1.55), available_h)
    card_x = (W - card_w) // 2
    card_y = head_block_bottom + (available_h - card_h) // 2

    kind = scene["kind"]
    if kind in ("alert", "safe"):
        card = status_card(card_w, card_h, mode=kind, lang=lang)
    elif kind == "settings":
        card = settings_card(card_w, card_h, lang=lang)
    elif kind == "legend":
        card = legend_card(card_w, card_h, lang=lang)
    else:
        raise ValueError(kind)

    # outer glow
    glow_layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    gl = ImageDraw.Draw(glow_layer)
    gl.rounded_rectangle(
        (card_x - 18, card_y - 18, card_x + card_w + 18, card_y + card_h + 18),
        radius=int(card_w * 0.11),
        fill=(232, 90, 44, 90) if kind == "alert" else (60, 90, 140, 60),
    )
    glow_layer = glow_layer.filter(ImageFilter.GaussianBlur(28))
    img.alpha_composite(glow_layer)

    # drop shadow then card
    shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sd.rounded_rectangle(
        (card_x + 8, card_y + 24, card_x + card_w + 8, card_y + card_h + 24),
        radius=int(card_w * 0.09),
        fill=(0, 0, 0, 180),
    )
    shadow = shadow.filter(ImageFilter.GaussianBlur(28))
    img.alpha_composite(shadow)
    img.alpha_composite(card, (card_x, card_y))

    # bottom tagline
    tag_font = load(F_REG if lang == "en" else F_HE_REG, int(W * 0.034))
    tag_en = "Real-time alerts. Built for Israel."
    tag_he = he("התראות בזמן אמת. נבנה עבור ישראל.")
    tag = tag_en if lang == "en" else tag_he
    d.text((W // 2, H - margin), tag, font=tag_font, fill=MUTED, anchor="mb")

    return img


# ---------- top-level outputs ----------
SCENES = [
    dict(kind="alert", lang="en",
         headline="Real-time rocket alerts",
         subline="Geo-targeted to your exact location"),
    dict(kind="safe", lang="en",
         headline="Stay informed when it's quiet",
         subline="A clear All-Clear, every time"),
    dict(kind="settings", lang="en",
         headline="Tune it to your routine",
         subline="GPS, volume, language and zones — your call"),
    dict(kind="legend", lang="en",
         headline="Eyes-free awareness",
         subline="Distinct sounds for every alert level"),
    dict(kind="alert", lang="he",
         headline=he("התראות בזמן אמת"),
         subline=he("ממוקדות למיקום המדויק שלך")),
    dict(kind="safe", lang="he",
         headline=he("שקט במדינה? תדע מיד"),
         subline=he("הודעת \"הכל נקי\" ברורה, בכל פעם")),
    dict(kind="settings", lang="he",
         headline=he("הגדר לפי השגרה שלך"),
         subline=he("GPS, עוצמה, שפה ואזור — לבחירתך")),
    dict(kind="legend", lang="he",
         headline=he("מודעות גם בלי להסתכל"),
         subline=he("צליל ייחודי לכל רמת התראה")),
]


def write_phone():
    OUT_PHONE.mkdir(parents=True, exist_ok=True)
    for i, scene in enumerate(SCENES, 1):
        img = compose_screenshot(1080, 1920, scene)
        out = OUT_PHONE / f"phone_ss_{i}.png"
        img.convert("RGB").save(out, "PNG", optimize=True)
        print(f"wrote {out}")


def write_tablet(folder: Path, w: int, h: int, prefix: str):
    folder.mkdir(parents=True, exist_ok=True)
    for i, scene in enumerate(SCENES, 1):
        img = compose_screenshot(w, h, scene)
        out = folder / f"{prefix}_ss_{i}.png"
        img.convert("RGB").save(out, "PNG", optimize=True)
        print(f"wrote {out}")


def write_feature_graphic():
    W, H = 1024, 500
    img = make_bg(W, H).convert("RGBA")
    add_grid(img, spacing=int(W * 0.04), alpha=24)

    # subtle israel silhouette accent (geometric drop)
    accent = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ad = ImageDraw.Draw(accent)
    # concentric arcs from the logo position
    cx, cy = int(W * 0.32), H // 2
    for r in range(60, 380, 26):
        ad.ellipse((cx - r, cy - r, cx + r, cy + r), outline=(232, 90, 44, max(20, 110 - r // 4)), width=2)
    accent = accent.filter(ImageFilter.GaussianBlur(0.6))
    img.alpha_composite(accent)

    # logo
    lsize = 280
    img.alpha_composite(logo(lsize), (cx - lsize // 2, cy - lsize // 2))

    # text block (right) — sized so longest line fits in (W - tx - margin)
    tx = int(W * 0.52)
    right_margin = 28
    avail_w = W - tx - right_margin
    title_font = load(F_BOLD, 64)
    sub_font = load(F_REG, 26)
    tag_font = load(F_BOLD, 22)
    d = ImageDraw.Draw(img)
    # auto-shrink title font until "Home Front Alerts" fits
    while d.textlength("Home Front Alerts", font=title_font) > avail_w and title_font.size > 28:
        title_font = load(F_BOLD, title_font.size - 2)
    line_h = title_font.size + 8
    d.text((tx, cy - line_h), "Tzeva Artzi", font=title_font, fill=WHITE)
    d.text((tx, cy + 6), "Home Front Alerts", font=title_font, fill=ORANGE)
    sub = "Real-time alerts • Geo-targeted • Eyes-free"
    while d.textlength(sub, font=sub_font) > avail_w and sub_font.size > 16:
        sub_font = load(F_REG, sub_font.size - 2)
    d.text((tx, cy + line_h + 18), sub, font=sub_font, fill=MUTED)
    # tag pill
    pill = "PRO"
    pw = d.textlength(pill, font=tag_font)
    ph = 34
    py = cy + line_h + 18 + sub_font.size + 14
    d.rounded_rectangle((tx, py, tx + int(pw) + 24, py + ph), radius=ph // 2, fill=ORANGE)
    d.text((tx + 12, py + ph // 2), pill, font=tag_font, fill=WHITE, anchor="lm")

    out = ASSETS / "feature_graphic_1024x500.png"
    img.convert("RGB").save(out, "PNG", optimize=True)
    print(f"wrote {out}")


def write_icon():
    """Re-export the canonical 512 from source so the file is normalized."""
    src = Image.open(SRC_ICON).convert("RGB")
    if src.size != (512, 512):
        src = src.resize((512, 512), Image.LANCZOS)
    out = ASSETS / "play_icon_512.png"
    src.save(out, "PNG", optimize=True)
    print(f"wrote {out}")


def main():
    write_icon()
    write_feature_graphic()
    write_phone()
    write_tablet(OUT_T7, 1200, 1920, "tablet7")
    write_tablet(OUT_T10, 1600, 2560, "tablet10")


if __name__ == "__main__":
    main()
