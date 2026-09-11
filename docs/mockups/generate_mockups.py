#!/usr/bin/env python3
"""Generate Turnzen MVP screen mockups as SVG (390x844 phone frame)."""
import os

W, H = 390, 844
# Write the SVGs next to this script — that is where they live in the repo.
OUT = os.path.dirname(os.path.abspath(__file__))
os.makedirs(OUT, exist_ok=True)

# ---- tokens ---------------------------------------------------------------
BG = "#F2F3F0"        # cool neutral surface
INK = "#1F2A26"       # primary text
INK2 = "#6B7470"      # secondary text
LINE = "#D6DAD5"      # hairlines
ACCENT = "#2E4A7D"    # indigo: server-changing actions, trial mode
ACCENT_SOFT = "#E4E9F2"
URGENT = "#B8483E"
BOARD = "#D9B778"     # muted kaya
BOARD_LINE = "#5A4A2E"
WHITE = "#F7F5F0"
BLACK = "#15171A"
FONT = "'IBM Plex Sans', 'Segoe UI', Roboto, sans-serif"

STATUS_H = 44
NAV_H = 80
NAV_Y = H - NAV_H


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def text(x, y, s, size=15, weight=400, fill=INK, anchor="start", extra=""):
    return (f'<text x="{x}" y="{y}" font-family="{FONT}" font-size="{size}" '
            f'font-weight="{weight}" fill="{fill}" text-anchor="{anchor}" {extra}>{esc(s)}</text>')


def frame_open():
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
            f'viewBox="0 0 {W} {H}">'
            f'<rect width="{W}" height="{H}" fill="{BG}"/>')


def status_bar():
    return (text(20, 29, "9:41", 14, 600) +
            f'<rect x="{W-42}" y="18" width="22" height="11" rx="2.5" fill="none" stroke="{INK}" stroke-width="1.2"/>'
            f'<rect x="{W-40}" y="20" width="14" height="7" rx="1" fill="{INK}"/>')


def hairline(y, x1=20, x2=W - 20):
    return f'<line x1="{x1}" y1="{y}" x2="{x2}" y2="{y}" stroke="{LINE}" stroke-width="1"/>'


def stone_icon(cx, cy, r, color):
    return f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{color}" stroke="{INK}" stroke-width="1"/>'


def bottom_nav(active):
    s = f'<rect x="0" y="{NAV_Y}" width="{W}" height="{NAV_H}" fill="{BG}"/>' + hairline(NAV_Y, 0, W)
    items = [("Games", W * 0.25), ("Settings", W * 0.75)]
    for label, cx in items:
        col = ACCENT if label == active else INK2
        if label == "Games":
            s += f'<circle cx="{cx-6}" cy="{NAV_Y+30}" r="7" fill="{col}"/>'
            s += f'<circle cx="{cx+7}" cy="{NAV_Y+24}" r="7" fill="none" stroke="{col}" stroke-width="1.6"/>'
        else:
            s += (f'<circle cx="{cx}" cy="{NAV_Y+27}" r="8" fill="none" stroke="{col}" stroke-width="1.6"/>'
                  f'<circle cx="{cx}" cy="{NAV_Y+27}" r="2.5" fill="{col}"/>')
        s += text(cx, NAV_Y + 55, label, 12, 500 if label == active else 400, col, "middle")
    return s


def button(x, y, w, h, label, primary=False, disabled=False):
    if primary:
        fill = ACCENT if not disabled else LINE
        col = "#FFFFFF" if not disabled else INK2
        return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{h/2}" fill="{fill}"/>' +
                text(x + w / 2, y + h / 2 + 5, label, 15, 500, col, "middle"))
    col = INK if not disabled else INK2
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{h/2}" fill="none" stroke="{LINE}" stroke-width="1"/>' +
            text(x + w / 2, y + h / 2 + 5, label, 15, 400, col, "middle"))


# ---- board ---------------------------------------------------------------
LETTERS = "ABCDEFGHJKLMNOPQRST"


def coord(n, s):
    col = LETTERS.index(s[0])
    row = int(s[1:])
    return col, n - row  # x index, y index from top


def board(n, y0, stones, last=None, trial=(), trial_mode=False, coords=True):
    """stones: list of (coord, 'B'|'W'); trial: list of coords, alternating starting with player color."""
    cell = W / n
    off = cell / 2
    r = cell * 0.46
    s = f'<rect x="0" y="{y0}" width="{W}" height="{W}" fill="{BOARD}"/>'
    for i in range(n):
        p = off + i * cell
        s += (f'<line x1="{off}" y1="{y0+p}" x2="{W-off}" y2="{y0+p}" stroke="{BOARD_LINE}" stroke-width="0.8"/>'
              f'<line x1="{p}" y1="{y0+off}" x2="{p}" y2="{y0+W-off}" stroke="{BOARD_LINE}" stroke-width="0.8"/>')
    hoshi = {19: [3, 9, 15], 13: [3, 6, 9], 9: [2, 4, 6]}[n]
    for a in hoshi:
        for b in hoshi:
            s += f'<circle cx="{off+a*cell}" cy="{y0+off+b*cell}" r="{cell*0.1}" fill="{BOARD_LINE}"/>'
    if coords:
        for i in range(n):
            s += text(off + i * cell, y0 + W - 3, LETTERS[i], max(8, cell * 0.32), 400, BOARD_LINE, "middle")
            s += text(3, y0 + off + i * cell + cell * 0.12, str(n - i), max(8, cell * 0.32), 400, BOARD_LINE, "start")
    for c, color in stones:
        x, y = coord(n, c)
        s += (f'<circle cx="{off+x*cell}" cy="{y0+off+y*cell}" r="{r}" '
              f'fill="{BLACK if color=="B" else WHITE}" stroke="{BLACK}" stroke-width="{0.6 if color=="W" else 0}"/>')
    if last:
        x, y = coord(n, last[0])
        dot = WHITE if last[1] == "B" else BLACK
        s += f'<circle cx="{off+x*cell}" cy="{y0+off+y*cell}" r="{r*0.28}" fill="{dot}"/>'
    for i, (c, color) in enumerate(trial):
        x, y = coord(n, c)
        cx, cy = off + x * cell, y0 + off + y * cell
        fillc = BLACK if color == "B" else WHITE
        numc = WHITE if color == "B" else BLACK
        s += (f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{fillc}" opacity="0.9"/>'
              f'<circle cx="{cx}" cy="{cy}" r="{r+1.2}" fill="none" stroke="{ACCENT}" stroke-width="1.6"/>' +
              text(cx, cy + r * 0.4, str(i + 1), r * 1.15, 600, numc, "middle"))
    if trial_mode:
        s += (f'<rect x="0" y="{y0}" width="{W}" height="3" fill="{ACCENT}"/>'
              f'<rect x="0" y="{y0+W-3}" width="{W}" height="3" fill="{ACCENT}"/>')
    return s, y0 + W


# a plausible mid-game 19x19 position
STONES_19 = [
    ("Q16", "B"), ("D4", "W"), ("Q4", "B"), ("D16", "W"), ("R14", "B"), ("C14", "W"),
    ("F17", "B"), ("F3", "W"), ("D3", "B"), ("E3", "W"), ("C3", "B"), ("C4", "W"),
    ("D2", "B"), ("B4", "W"), ("O17", "B"), ("Q10", "W"), ("R10", "B"), ("Q9", "W"),
    ("R9", "B"), ("Q8", "W"), ("R7", "B"), ("P12", "W"), ("O3", "B"), ("K16", "W"),
    ("K17", "B"), ("J16", "W"), ("H17", "B"), ("L16", "W"), ("K4", "B"), ("K3", "W"),
    ("J3", "B"), ("L3", "W"), ("L4", "B"), ("M4", "W"), ("L5", "B"), ("M3", "W"),
    ("R12", "B"), ("Q12", "W"), ("Q13", "B"), ("P13", "W"), ("P14", "B"), ("O14", "W"),
]
LAST_19 = ("O14", "W")
TRIAL_19 = [("O15", "B"), ("N15", "W"), ("N16", "B"), ("M15", "W")]

STONES_9 = [("C7", "B"), ("G3", "W"), ("G7", "B"), ("C3", "W"), ("E7", "B"), ("E3", "W"),
            ("F4", "B"), ("F5", "W"), ("G5", "B"), ("E5", "W"), ("D5", "B"), ("D4", "W")]
LAST_9 = ("D4", "W")


def header_game(opp, rank, you, mv, time_left, urgent=False):
    s = f'<rect x="20" y="52" width="24" height="1" fill="none"/>'
    s += text(20, 66, f"{opp} ({rank})", 16, 600)
    s += text(20, 86, f"You play {you}, move {mv}", 13, 400, INK2)
    s += text(W - 20, 66, time_left, 15, 600, URGENT if urgent else INK, "end")
    s += text(W - 20, 86, "to move", 13, 400, INK2, "end")
    return s


def move_nav(y, mv):
    s = text(20, y, "‹", 22, 400, INK2)
    s += text(46, y - 1, f"{mv}", 15, 500, INK)
    s += text(76, y, "›", 22, 400, INK2)
    return s


# ---- screens -------------------------------------------------------------
def screen_signin():
    s = frame_open() + status_bar()
    s += stone_icon(W / 2 - 12, 190, 16, BLACK) + stone_icon(W / 2 + 12, 190, 16, WHITE)
    s += text(W / 2, 250, "Turnzen", 30, 600, INK, "middle")
    for i, (label, val) in enumerate([("DGS username", "csaba"), ("DGS password", "••••••••••")]):
        y = 340 + i * 76
        s += text(20, y, label, 13, 400, INK2)
        s += text(20, y + 30, val, 17, 400, INK)
        s += hairline(y + 42)
    s += button(20, 520, W - 40, 50, "Sign in", primary=True)
    s += text(W / 2, 606, "Turnzen signs you in with your Dragon Go Server account.", 12, 400, INK2, "middle")
    s += text(W / 2, 680, "Register a Dragon Go Server account on the web", 14, 500, ACCENT, "middle")
    return s + "</svg>"


def screen_games(empty=False):
    s = frame_open() + status_bar()
    s += text(20, 88, "Your move", 26, 600)
    if empty:
        s += stone_icon(W / 2, 370, 22, WHITE)
        s += text(W / 2, 430, "No games waiting for you", 17, 500, INK, "middle")
        s += text(W / 2, 454, "Pull down to check again.", 14, 400, INK2, "middle")
        return s + bottom_nav("Games") + "</svg>"
    s += text(W - 20, 88, "3", 16, 500, INK2, "end")
    rows = [
        ("mkvtl", "7k", "19x19", "black", 87, "3 d 4 h", False, "18 min ago"),
        ("hana_go", "9k", "13x13, H2", "white", 34, "6 h", True, "2 h ago"),
        ("BerndW", "6k", "19x19", "black", 142, "5 d", False, "yesterday"),
    ]
    y = 120
    for opp, rank, size, you, mv, left, urgent, when in rows:
        s += text(20, y + 25, f"{opp} ({rank})", 16, 600)
        s += text(20, y + 46, f"{size}, move {mv}, last move {when}", 13, 400, INK2)
        s += text(W - 20, y + 25, left, 15, 600, URGENT if urgent else INK, "end")
        s += text(W - 20, y + 46, "left", 13, 400, INK2, "end")
        y += 72
        s += hairline(y)
    return s + bottom_nav("Games") + "</svg>"


def screen_board(n, stones, last, trial=(), trial_mode=False):
    s = frame_open() + status_bar()
    s += header_game("mkvtl", "7k", "black", 87 if n == 19 else 12, "3 d 4 h" if n == 19 else "22 h")
    b, yend = board(n, 104, stones, last, trial, trial_mode)
    s += b
    # below the board
    y = yend + 40
    if trial_mode:
        s += f'<circle cx="26" cy="{y-5}" r="5" fill="{ACCENT}"/>'
        s += text(38, y, f"Trial, {len(trial)} moves", 15, 500, ACCENT)
        s += text(W - 130, y, "Undo", 15, 500, INK, "end")
        s += text(W - 20, y, "Reset", 15, 500, INK, "end")
    else:
        s += move_nav(y, 87 if n == 19 else 12)
        s += text(W - 20, y, "Tap the board to try moves", 13, 400, INK2, "end")
    y += 32
    bw = (W - 40 - 12) / 2
    s += button(20, y, bw, 48, "Pass")
    if trial_mode:
        s += button(20 + bw + 12, y, bw, 48, f"Send {trial[0][0]}", primary=True)
    else:
        s += button(20 + bw + 12, y, bw, 48, "Send", primary=True, disabled=True)
    return s + bottom_nav("Games") + "</svg>"


def screen_settings():
    s = frame_open() + status_bar()
    s += text(20, 88, "Settings", 26, 600)
    groups = [
        ("Account", [("Dragon Go Server", "csaba"), ("Server", "dragongoserver.net"), ("Sign out", None)]),
        ("Board", [("Coordinates", "On"), ("Mark last move", "On"), ("Number trial stones", "On")]),
        ("About", [("Version", "0.1.0"), ("License", "MIT"), ("Source code", None), ("Report a problem", None)]),
    ]
    y = 120
    for title, rows in groups:
        s += text(20, y, title, 13, 500, INK2)
        y += 14
        for label, val in rows:
            y += 40
            col = ACCENT if val is None and label in ("Sign out", "Source code", "Report a problem") else INK
            s += text(20, y, label, 16, 400, col)
            if val:
                s += text(W - 20, y, val, 15, 400, INK2, "end")
            s += hairline(y + 14)
        y += 48
    return s + bottom_nav("Settings") + "</svg>"


screens = {
    "01-signin": screen_signin(),
    "02-games-your-move": screen_games(),
    "02b-games-empty": screen_games(empty=True),
    "03-board-server-position-19": screen_board(19, STONES_19, LAST_19),
    "03b-board-trial-19": screen_board(19, STONES_19, LAST_19, TRIAL_19, trial_mode=True),
    "03c-board-server-position-9": screen_board(9, STONES_9, LAST_9),
    "04-settings": screen_settings(),
}
for name, svg in screens.items():
    with open(os.path.join(OUT, f"{name}.svg"), "w") as f:
        f.write(svg)
    print("wrote", name)
