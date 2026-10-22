#!/usr/bin/env python3
"""
Genera le illustrazioni della community demo: copertine degli eventi (1600x900).
Sono SVG originali disegnati qui (forme geometriche, palette Human Signal): nessuna fotografia, nessun asset di terzi,
nessuna licenza da attribuire. Uscita: frontend/public/demo/covers/*.svg.
(Gli avatar dei personaggi sono fotografie WebP in frontend/public/demo/avatars/, non generate da questo script.)
Uso: python3 scripts/generate-demo-art.py   (rieseguirlo sovrascrive i file con lo stesso contenuto)
"""
from pathlib import Path
import math

ROOT = Path(__file__).resolve().parent.parent / "frontend" / "public" / "demo"
INK, CORAL, PEACH, SAGE, SKY, CANVAS = "#181816", "#e84d2f", "#f7c9b8", "#a7c9a8", "#b7d8f5", "#f5f3ee"
CAT = {"music": "#8657D8", "food": "#E59A48", "culture": "#CF709A", "outdoors": "#579F76",
       "nightlife": "#4355BD", "sport": "#428CB5", "social": "#E76A56"}


def mix(a, b, t):
    a, b = [("#" + "".join(ch * 2 for ch in x[1:])) if len(x) == 4 else x for x in (a, b)]
    pa = [int(a[i:i + 2], 16) for i in (1, 3, 5)]
    pb = [int(b[i:i + 2], 16) for i in (1, 3, 5)]
    return "#" + "".join(f"{round(x + (y - x) * t):02x}" for x, y in zip(pa, pb))


def svg(w, h, body, title):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" role="img">'
            f'<title>{title}</title>{body}</svg>\n')


# ---------------------------------------------------------------- copertine
def backdrop(c, dark=False):
    top, bot = (mix(c, INK, .55), mix(c, INK, .8)) if dark else (mix(c, "#ffffff", .55), mix(c, "#ffffff", .86))
    s = f'<defs><linearGradient id="bg" x1="0" y1="0" x2=".4" y2="1"><stop offset="0" stop-color="{top}"/><stop offset="1" stop-color="{bot}"/></linearGradient></defs>'
    s += '<rect width="1600" height="900" fill="url(#bg)"/>'
    s += f'<circle cx="1330" cy="170" r="330" fill="{c}" opacity="{.22 if dark else .16}"/>'
    s += f'<circle cx="180" cy="760" r="270" fill="{CORAL}" opacity="{.18 if dark else .12}"/>'
    # firma Human Signal: due archi che si cercano
    s += f'<path d="M-40 640 C260 520 420 700 700 560" fill="none" stroke="{INK}" stroke-width="10" stroke-linecap="round" opacity=".10"/>'
    s += f'<path d="M900 330 C1200 190 1400 360 1650 230" fill="none" stroke="{CORAL}" stroke-width="10" stroke-linecap="round" opacity=".22"/>'
    return s


def ground(c, y=720, dark=False):
    col = mix(c, INK, .7) if dark else mix(c, "#ffffff", .25)
    return f'<rect y="{y}" width="1600" height="{900 - y}" fill="{col}" opacity=".55"/>'


def glass(x, y, w, h, r=36, op=.55):
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="#fff" opacity="{op}"/>'
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="none" stroke="#fff" stroke-width="3" opacity=".8"/>')


def circle(x, y, r, fill, extra=""):
    return f'<circle cx="{x}" cy="{y}" r="{r}" fill="{fill}" {extra}/>'


def rect(x, y, w, h, fill, r=0, extra=""):
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{fill}" {extra}/>'


def note(x, y, fill=INK, s=1.0):
    return (f'<g transform="translate({x} {y}) scale({s})"><ellipse cx="0" cy="0" rx="26" ry="19" fill="{fill}" transform="rotate(-20)"/>'
            f'<rect x="19" y="-100" width="9" height="100" fill="{fill}"/><path d="M28 -100 q40 14 36 54 q-10 -22 -36 -26z" fill="{fill}"/></g>')


def person(x, y, c, s=1.0, arms=False):
    a = f'<path d="M-30 -60 L-62 -110 M30 -60 L62 -110" stroke="{c}" stroke-width="22" stroke-linecap="round"/>' if arms else ""
    return (f'<g transform="translate({x} {y}) scale({s})">{a}<rect x="-38" y="-80" width="76" height="120" rx="34" fill="{c}"/>'
            f'<circle cx="0" cy="-118" r="30" fill="{c}"/></g>')


def sc_dj():
    c = CAT["music"]
    s = glass(300, 250, 1000, 430, 48)
    for cx in (560, 1040):
        s += circle(cx, 470, 150, INK) + circle(cx, 470, 118, "none", f'stroke="#fff" stroke-width="2" opacity=".18"')
        s += circle(cx, 470, 82, "none", f'stroke="#fff" stroke-width="2" opacity=".18"') + circle(cx, 470, 52, c) + circle(cx, 470, 9, CANVAS)
    s += rect(740, 330, 120, 280, mix(c, INK, .3), 22)
    for i, yy in enumerate((375, 440, 505, 570)):
        s += rect(766, yy - 10, 68, 8, CANVAS, 4, 'opacity=".5"') + circle(770 + (i % 2) * 60, yy - 6, 14, CORAL)
    s += f'<path d="M640 240 C640 130 960 130 960 240" fill="none" stroke="{INK}" stroke-width="22" stroke-linecap="round"/>'
    s += rect(610, 220, 50, 90, CORAL, 22) + rect(940, 220, 50, 90, CORAL, 22)
    return s


def sc_jazz():
    c = CAT["music"]
    s = glass(260, 440, 1080, 250, 40)
    for i in range(14):
        s += rect(290 + i * 74, 470, 66, 190, "#fff", 10, f'stroke="{INK}" stroke-opacity=".25" stroke-width="2"')
    for i in (0, 1, 3, 4, 5, 7, 8, 10, 11, 12):
        s += rect(290 + i * 74 + 48, 470, 44, 120, INK, 8)
    s += note(520, 330, c, 1.3) + note(800, 250, CORAL, 1.6) + note(1090, 340, INK, 1.2)
    s += f'<path d="M380 230 q60 -50 120 0 M1150 230 q60 -50 120 0" fill="none" stroke="{c}" stroke-width="9" stroke-linecap="round" opacity=".6"/>'
    return s


def sc_concert():
    c = CAT["music"]
    s = ""
    for x, col in ((420, CORAL), (800, c), (1180, SKY)):
        s += f'<path d="M{x} 0 L{x - 190} 640 L{x + 190} 640 Z" fill="{col}" opacity=".26"/>'
    s += rect(500, 520, 600, 40, INK, 8) + rect(780, 280, 8, 250, INK, 4)
    s += rect(766, 250, 36, 56, INK, 18) + circle(784, 256, 22, INK)
    for i, x in enumerate(range(120, 1500, 130)):
        s += person(x + (i % 2) * 40, 880, INK if i % 3 else mix(c, INK, .4), 1.25 + (i % 3) * .15, arms=(i % 4 == 0))
    return s


def sc_table():
    s = ground(CAT["food"], 0).replace('opacity=".55"', 'opacity=".25"')
    s += circle(800, 470, 330, "#fff", 'opacity=".75"') + circle(800, 470, 330, "none", f'stroke="{CAT["food"]}" stroke-width="10" opacity=".5"')
    for k in range(6):
        a = k * math.pi / 3 - math.pi / 2
        x, y = 800 + 215 * math.cos(a), 470 + 215 * math.sin(a)
        s += circle(x, y, 74, "#fff", f'stroke="{INK}" stroke-opacity=".2" stroke-width="3"') + circle(x, y, 42, mix(CAT["food"], "#fff", .55))
        gx, gy = 800 + 290 * math.cos(a + .35), 470 + 290 * math.sin(a + .35)
        s += circle(gx, gy, 22, SKY, 'opacity=".85"')
    s += circle(800, 470, 70, CORAL) + circle(800, 470, 40, mix(CORAL, "#fff", .4))
    return s


def sc_streetfood():
    c = CAT["food"]
    s = ground(c, 700)
    s += rect(360, 330, 880, 380, "#fff", 30, 'opacity=".85"') + rect(360, 560, 880, 150, mix(c, INK, .1), 20)
    for i in range(8):
        s += f'<path d="M{360 + i * 110} 220 h110 v110 q-55 50 -110 0z" fill="{CORAL if i % 2 == 0 else "#fff"}"/>'
    for i in range(9):
        s += circle(380 + i * 105, 190 + (i % 2) * 14, 11, "#ffd66b")
    s += f'<path d="M340 190 q460 70 920 0" fill="none" stroke="{INK}" stroke-width="3" opacity=".5"/>'
    s += rect(520, 450, 160, 90, SKY, 16, 'opacity=".8"') + rect(960, 450, 200, 90, PEACH, 16) + circle(800, 500, 40, c)
    return s


def sc_brunch():
    c = CAT["food"]
    s = circle(800, 460, 330, "#fff", 'opacity=".8"') + circle(800, 460, 250, mix(c, "#fff", .75))
    s += f'<ellipse cx="760" cy="440" rx="130" ry="105" fill="#fff"/>' + circle(765, 440, 52, "#ffbd2e")
    s += f'<path d="M930 380 q80 -30 120 30 q-30 40 -70 40 q-60 0 -50 -70z" fill="#d9944b"/>'
    s += circle(1130, 640, 100, "#fff", f'stroke="{INK}" stroke-opacity=".2" stroke-width="3"') + circle(1130, 640, 66, "#7a4a2b")
    s += f'<path d="M1228 640 q50 0 50 -40" fill="none" stroke="#fff" stroke-width="16" stroke-linecap="round"/>'
    s += circle(480, 680, 46, CORAL) + circle(480, 680, 20, mix(CORAL, "#fff", .35))
    return s


def sc_gallery():
    c = CAT["culture"]
    s = rect(0, 640, 1600, 260, mix(c, "#fff", .2), 0, 'opacity=".5"')
    s += rect(300, 200, 330, 260, "#fff", 8, f'stroke="{INK}" stroke-width="10"') + circle(430, 330, 70, c) + rect(470, 290, 120, 140, CORAL, 4, 'opacity=".85"')
    s += rect(700, 160, 200, 320, "#fff", 8, f'stroke="{INK}" stroke-width="10"') + f'<path d="M720 440 L800 220 L880 440z" fill="{SKY}"/>'
    s += rect(970, 230, 330, 220, "#fff", 8, f'stroke="{INK}" stroke-width="10"') + f'<path d="M990 410 C1060 250 1150 480 1280 270" fill="none" stroke="{c}" stroke-width="22" stroke-linecap="round"/>'
    s += rect(560, 620, 500, 40, INK, 14) + rect(600, 660, 18, 70, INK) + rect(1000, 660, 18, 70, INK)
    s += person(1240, 700, mix(c, INK, .35), 1.6)
    return s


def sc_photo():
    c = CAT["culture"]
    s = rect(400, 270, 800, 470, INK, 70) + rect(530, 215, 230, 80, mix(c, INK, .2), 20) + circle(1090, 340, 24, CORAL)
    s += circle(800, 510, 190, "#2b2a28") + circle(800, 510, 150, mix(c, INK, .45)) + circle(800, 510, 96, mix(c, "#fff", .1)) + circle(760, 470, 32, "#fff", 'opacity=".75"')
    s += rect(440, 330, 120, 22, "#fff", 11, 'opacity=".25"')
    for x, y, dx, dy in ((250, 160, 1, 1), (1350, 160, -1, 1), (250, 800, 1, -1), (1350, 800, -1, -1)):
        s += f'<path d="M{x} {y + 90 * dy} V{y} H{x + 90 * dx}" fill="none" stroke="{CORAL}" stroke-width="12" stroke-linecap="round"/>'
    return s


def sc_cinema():
    c = CAT["culture"]
    s = glass(320, 180, 960, 400, 36, .45) + circle(800, 380, 112, CORAL) + f'<path d="M770 320 L850 380 L770 440z" fill="#fff"/>'
    s += rect(420, 640, 760, 130, INK, 16)
    for i in range(10):
        s += rect(450 + i * 74, 660, 40, 24, "#fff", 6, 'opacity=".85"') + rect(450 + i * 74, 726, 40, 24, "#fff", 6, 'opacity=".85"')
    s += f'<g transform="rotate(-8 1180 250)"><rect x="1080" y="220" width="260" height="120" rx="12" fill="{INK}"/><path d="M1080 220 h260 v36 h-260z" fill="#fff"/>'
    s += "".join(f'<path d="M{1090 + i * 52} 220 l30 0 l-24 36 l-30 0z" fill="{INK}"/>' for i in range(5)) + '</g>'
    s += circle(250, 300, 60, c, 'opacity=".3"')
    return s


def sc_park():
    c = CAT["outdoors"]
    s = circle(1250, 200, 90, "#ffd66b") + rect(0, 620, 1600, 280, mix(c, "#fff", .35), 0, 'opacity=".8"')
    s += f'<path d="M900 900 C760 760 1000 700 780 620" fill="none" stroke="{PEACH}" stroke-width="80" stroke-linecap="round" opacity=".9"/>'
    for x, h, r in ((300, 220, 130), (520, 180, 100), (1120, 240, 140), (1360, 190, 105), (200, 150, 80)):
        s += rect(x - 14, 620 - h + 40, 28, h, mix(c, INK, .55), 8) + circle(x, 620 - h, r, mix(c, INK, .1)) + circle(x - r * .3, 620 - h - r * .2, r * .55, mix(c, "#fff", .15), 'opacity=".6"')
    s += rect(640, 560, 200, 18, INK, 8) + rect(650, 520, 180, 14, INK, 6) + rect(660, 578, 12, 50, INK) + rect(808, 578, 12, 50, INK)
    return s


def sc_run():
    c = CAT["outdoors"]
    s = circle(800, 560, 210, "#ffd66b", 'opacity=".9"') + rect(0, 600, 1600, 300, mix(SKY, "#fff", .1), 0, 'opacity=".7"')
    for i in range(6):
        s += f'<path d="M{80 + i * 40} {650 + i * 40} q200 -30 400 0 t400 0 t400 0 t400 0" fill="none" stroke="#fff" stroke-width="8" opacity=".6" stroke-linecap="round"/>'
    s += (f'<g fill="none" stroke="{INK}" stroke-width="30" stroke-linecap="round" stroke-linejoin="round">'
          f'<circle cx="905" cy="300" r="38" fill="{INK}" stroke="none"/>'
          f'<path d="M880 360 L840 470 L890 540"/>'            # busto e gamba posteriore
          f'<path d="M840 470 L760 480 L720 560"/>'              # gamba avanzata
          f'<path d="M885 385 L960 430 L1010 400"/>'             # braccio avanti
          f'<path d="M880 385 L810 430 L780 395"/></g>'          # braccio indietro
          f'<path d="M690 575 h60" stroke="{CORAL}" stroke-width="22" stroke-linecap="round"/>'
          f'<path d="M870 555 h60" stroke="{CORAL}" stroke-width="22" stroke-linecap="round"/>')
    s += circle(1250, 470, 150, c, 'opacity=".25"')
    return s


def sc_hike():
    c = CAT["outdoors"]
    s = circle(1180, 220, 80, "#ffd66b")
    s += f'<path d="M-50 700 L350 280 L720 700z" fill="{mix(c, SKY, .35)}"/><path d="M450 700 L900 200 L1400 700z" fill="{mix(c, INK, .1)}"/><path d="M780 700 L1180 380 L1650 700z" fill="{mix(c, INK, .3)}"/>'
    s += f'<path d="M820 330 L900 200 L980 330 L930 310 L900 350 L870 310z" fill="#fff" opacity=".9"/>'
    s += rect(0, 690, 1600, 210, mix(c, INK, .55)) + f'<path d="M1000 900 C800 800 1100 760 880 690" fill="none" stroke="{PEACH}" stroke-width="40" stroke-linecap="round"/>'
    s += person(700, 790, CORAL, 1.0)
    return s


def sc_cocktail():
    c = CAT["nightlife"]
    s = circle(800, 430, 280, c, 'opacity=".35"') + circle(800, 430, 220, CORAL, 'opacity=".18"')
    s += f'<path d="M560 250 H1040 L800 560z" fill="#fff" opacity=".35" stroke="#fff" stroke-width="12" stroke-linejoin="round"/>'
    s += f'<path d="M610 300 H990 L800 520z" fill="{CORAL}" opacity=".85"/><rect x="788" y="560" width="24" height="200" fill="#fff" opacity=".85"/><rect x="660" y="750" width="280" height="22" rx="11" fill="#fff"/>'
    s += f'<path d="M930 200 L1040 100" stroke="{SAGE}" stroke-width="10" stroke-linecap="round"/>' + circle(900, 280, 26, SAGE) + circle(900, 280, 10, CORAL)
    for x, y, r in ((420, 300, 26), (1180, 380, 36), (1300, 250, 18), (360, 560, 20), (1220, 600, 24)):
        s += circle(x, y, r, "#fff", 'opacity=".28"')
    return s


def sc_disco():
    c = CAT["nightlife"]
    s = ""
    for a in range(0, 360, 30):
        r = math.radians(a)
        s += f'<path d="M800 380 L{800 + 1100 * math.cos(r - .06):.0f} {380 + 1100 * math.sin(r - .06):.0f} L{800 + 1100 * math.cos(r + .06):.0f} {380 + 1100 * math.sin(r + .06):.0f}z" fill="{["#fff", CORAL, SKY][a // 30 % 3]}" opacity=".12"/>'
    s += rect(793, 0, 14, 170, "#fff", 4, 'opacity=".7"') + '<clipPath id="ball"><circle cx="800" cy="380" r="200"/></clipPath>'
    s += circle(800, 380, 200, mix(c, "#fff", .45)) + '<g clip-path="url(#ball)">'
    for i in range(-4, 5):
        s += f'<path d="M{800 + i * 50} 170 v420" stroke="#fff" stroke-width="3" opacity=".7"/><path d="M590 {380 + i * 50} h420" stroke="#fff" stroke-width="3" opacity=".7"/>'
    s += circle(740, 320, 70, "#fff", 'opacity=".4"') + '</g>'
    for i, x in enumerate(range(150, 1500, 120)):
        s += person(x, 890, INK, 1.15 + (i % 3) * .12, arms=(i % 2 == 0))
    return s


def sc_karaoke():
    c = CAT["nightlife"]
    s = glass(300, 160, 1000, 250, 30, .4)
    for i, w in enumerate((720, 560, 640)):
        s += rect(360, 210 + i * 62, w, 24, "#fff", 12, f'opacity="{.9 if i == 1 else .45}"')
    s += rect(360, 272, 270, 24, CORAL, 12)
    s += rect(775, 480, 50, 230, INK, 24) + circle(800, 440, 70, "#2b2a28") + circle(800, 440, 54, mix(c, "#fff", .5))
    for dx in (-30, 0, 30):
        s += rect(795 + dx - 4, 392, 8, 96, "#fff", 4, 'opacity=".35"')
    s += note(520, 640, CORAL, 1.1) + note(1060, 600, c, 1.3) + note(1250, 480, INK, .9)
    return s


def sc_football():
    c = CAT["sport"]
    s = rect(200, 220, 1200, 560, mix("#3f9a5e", "#fff", .15), 36) + f'<rect x="240" y="260" width="1120" height="480" fill="none" stroke="#fff" stroke-width="8" rx="12"/>'
    s += f'<line x1="800" y1="260" x2="800" y2="740" stroke="#fff" stroke-width="8"/>' + circle(800, 500, 90, "none", 'stroke="#fff" stroke-width="8"')
    s += rect(240, 390, 150, 220, "none", 0, 'stroke="#fff" stroke-width="8"') + rect(1210, 390, 150, 220, "none", 0, 'stroke="#fff" stroke-width="8"')
    s += circle(930, 590, 70, "#fff", f'stroke="{INK}" stroke-width="6"') + f'<path d="M930 550 l30 22 -12 36 h-36 l-12 -36z" fill="{INK}"/>'
    return s


def sc_basket():
    c = CAT["sport"]
    s = rect(560, 150, 480, 300, "#fff", 18, f'stroke="{INK}" stroke-width="10"') + rect(710, 290, 180, 120, "none", 4, f'stroke="{CORAL}" stroke-width="10"')
    s += f'<rect x="720" y="450" width="160" height="12" rx="6" fill="{CORAL}"/>'
    for i in range(5):
        s += f'<path d="M{728 + i * 36} 462 L{748 + i * 28} 560" stroke="#fff" stroke-width="5"/>'
    s += circle(1010, 690, 110, "#f0883e") + f'<path d="M900 690 h220 M1010 580 v220 M930 610 q80 80 0 160 M1090 610 q-80 80 0 160" fill="none" stroke="{INK}" stroke-width="7"/>'
    s += circle(1010, 690, 110, "none", f'stroke="{INK}" stroke-width="7"')
    return s


def sc_bike():
    c = CAT["sport"]
    s = rect(0, 700, 1600, 200, mix(c, "#fff", .3), 0, 'opacity=".6"')
    for cx in (560, 1040):
        s += circle(cx, 560, 150, "none", f'stroke="{INK}" stroke-width="22"') + circle(cx, 560, 18, INK)
        s += "".join(f'<line x1="{cx}" y1="560" x2="{cx + 140 * math.cos(math.radians(a)):.0f}" y2="{560 + 140 * math.sin(math.radians(a)):.0f}" stroke="{INK}" stroke-width="3" opacity=".5"/>' for a in range(0, 360, 30))
    s += f'<path d="M560 560 L720 360 L920 360 L1040 560 M720 360 L800 560 L560 560 M800 560 L920 360" fill="none" stroke="{CORAL}" stroke-width="22" stroke-linecap="round" stroke-linejoin="round"/>'
    s += f'<path d="M690 330 h90 M920 360 l-20 -70 h80" fill="none" stroke="{INK}" stroke-width="20" stroke-linecap="round"/>' + circle(800, 560, 30, INK, 'opacity=".9"')
    return s


def sc_fitness():
    c = CAT["sport"]
    s = rect(300, 640, 1000, 60, mix(c, INK, .1), 30, 'transform="rotate(-2 800 670)"')
    s += rect(430, 420, 740, 40, INK, 20) + rect(380, 340, 70, 200, c, 22) + rect(320, 370, 50, 140, INK, 18) + rect(1150, 340, 70, 200, c, 22) + rect(1230, 370, 50, 140, INK, 18)
    s += circle(800, 250, 70, CORAL) + f'<path d="M770 195 q30 -40 60 0" fill="none" stroke="{INK}" stroke-width="14" stroke-linecap="round"/>' + circle(800, 250, 70, "none", f'stroke="{INK}" stroke-width="10"')
    return s


def sc_chat():
    c = CAT["social"]
    s = f'<path d="M280 260 h620 a50 50 0 0 1 50 50 v200 a50 50 0 0 1 -50 50 h-380 l-110 90 v-90 h-130 a50 50 0 0 1 -50 -50 v-200 a50 50 0 0 1 50 -50z" fill="#fff" opacity=".85"/>'
    s += f'<path d="M1320 400 h-380 a50 50 0 0 0 -50 50 v200 a50 50 0 0 0 50 50 h250 l90 80 v-80 h40 a50 50 0 0 0 50 -50 v-200 a50 50 0 0 0 -50 -50z" fill="{c}" opacity=".92"/>'
    for i, w in enumerate((420, 300, 360)):
        s += rect(340, 320 + i * 56, w, 22, mix(c, "#fff", .5), 11)
    for i in range(3):
        s += circle(1050 + i * 70, 560, 20, "#fff", 'opacity=".9"')
    s += circle(1270, 330, 54, SAGE) + circle(330, 700, 48, SKY)
    return s


def sc_games():
    c = CAT["social"]
    s = circle(800, 470, 340, "#fff", 'opacity=".55"')
    for x, y, rot in ((560, 420, -12), (900, 520, 14)):
        s += f'<g transform="rotate({rot} {x} {y})">{rect(x - 100, y - 100, 200, 200, "#fff", 36, f"stroke=\"{INK}\" stroke-width=\"8\"")}'
        pips = [(0, 0), (-50, -50), (50, 50)] if x < 700 else [(-50, -50), (50, -50), (-50, 50), (50, 50), (0, 0)]
        s += "".join(circle(x + dx, y + dy, 16, CORAL if x < 700 else INK) for dx, dy in pips) + '</g>'
    for i, (x, y, col) in enumerate(((1130, 330, c), (1220, 420, SKY), (420, 640, SAGE), (700, 700, CORAL))):
        s += f'<g transform="translate({x} {y})"><circle cy="-34" r="22" fill="{col}"/><path d="M-32 30 q0 -50 32 -50 q32 0 32 50z" fill="{col}"/></g>'
    return s


def sc_languages():
    c = CAT["social"]
    s = ""
    for (x, y, w, text, fill, tc) in ((260, 220, 400, "Ciao!", "#fff", INK), (700, 380, 440, "Hello!", c, "#fff"), (360, 560, 400, "¡Hola!", PEACH, INK), (980, 640, 340, "Salut!", SKY, INK)):
        s += rect(x, y, w, 150, fill, 75, 'opacity=".95"') + f'<path d="M{x + 80} {y + 140} l-24 56 l70 -50z" fill="{fill}" opacity=".95"/>'
        s += f'<text x="{x + w / 2}" y="{y + 98}" text-anchor="middle" font-family="Manrope, system-ui, sans-serif" font-weight="800" font-size="70" fill="{tc}">{text}</text>'
    return s


SCENES = {  # tema -> (categoria, scena, sfondo scuro?)
    "music-dj": ("music", sc_dj, False), "music-jazz": ("music", sc_jazz, False), "music-concert": ("music", sc_concert, False),
    "food-table": ("food", sc_table, False), "food-streetfood": ("food", sc_streetfood, False), "food-brunch": ("food", sc_brunch, False),
    "culture-gallery": ("culture", sc_gallery, False), "culture-photo": ("culture", sc_photo, False), "culture-cinema": ("culture", sc_cinema, False),
    "outdoors-park": ("outdoors", sc_park, False), "outdoors-run": ("outdoors", sc_run, False), "outdoors-hike": ("outdoors", sc_hike, False),
    "nightlife-cocktail": ("nightlife", sc_cocktail, True), "nightlife-disco": ("nightlife", sc_disco, True), "nightlife-karaoke": ("nightlife", sc_karaoke, True),
    "sport-football": ("sport", sc_football, False), "sport-basket": ("sport", sc_basket, False), "sport-bike": ("sport", sc_bike, False), "sport-fitness": ("sport", sc_fitness, False),
    "social-chat": ("social", sc_chat, False), "social-games": ("social", sc_games, False), "social-languages": ("social", sc_languages, False),
}

ALTS = {
    "music-dj": "Illustrazione di due piatti da DJ con cuffie", "music-jazz": "Illustrazione di una tastiera di pianoforte con note musicali",
    "music-concert": "Illustrazione di un palco con luci e pubblico", "food-table": "Illustrazione di una tavola rotonda vista dall'alto con piatti e bicchieri",
    "food-streetfood": "Illustrazione di un chiosco di street food con tendone a strisce", "food-brunch": "Illustrazione di un brunch con uovo, croissant e caffè",
    "culture-gallery": "Illustrazione di una galleria d'arte con tre quadri", "culture-photo": "Illustrazione di una macchina fotografica con mirino",
    "culture-cinema": "Illustrazione di uno schermo, una pellicola e un ciak", "outdoors-park": "Illustrazione di un parco con alberi, panchina e sentiero",
    "outdoors-run": "Illustrazione di una persona che corre al tramonto lungo l'acqua", "outdoors-hike": "Illustrazione di montagne con un sentiero e un'escursionista",
    "nightlife-cocktail": "Illustrazione di un cocktail con oliva su sfondo notturno", "nightlife-disco": "Illustrazione di una palla da discoteca con raggi di luce e persone che ballano",
    "nightlife-karaoke": "Illustrazione di un microfono con note e testo del karaoke", "sport-football": "Illustrazione di un campo da calcio con pallone",
    "sport-basket": "Illustrazione di un canestro con pallone da basket", "sport-bike": "Illustrazione di una bicicletta", "sport-fitness": "Illustrazione di un manubrio e di un tappetino da allenamento",
    "social-chat": "Illustrazione di fumetti di conversazione sovrapposti", "social-games": "Illustrazione di dadi e pedine di un gioco da tavolo",
    "social-languages": "Illustrazione di fumetti con saluti in lingue diverse",
}


def cover(theme):
    cat, scene, dark = SCENES[theme]
    c = CAT[cat]
    return svg(1600, 900, backdrop(c, dark) + scene(), ALTS[theme])


def main():
    (ROOT / "covers").mkdir(parents=True, exist_ok=True)
    for theme in SCENES:
        (ROOT / "covers" / f"{theme}.svg").write_text(cover(theme), encoding="utf-8")
    print(f"{len(SCENES)} copertine in {ROOT}")


if __name__ == "__main__":
    main()
