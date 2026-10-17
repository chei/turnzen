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

# Server marks: each server's colour, from its own web presence (OGS light blue, DGS sand).
# Only ever a 4dp bar or a small dot, never a fill — see DESIGN.md, "Telling servers apart".
OGS = "#5E9FD6"
DGS = "#C2A66E"

LIGHT = dict(BG=BG, INK=INK, INK2=INK2, LINE=LINE, ACCENT="#2E54C0", ACCENT_SOFT=ACCENT_SOFT,
             URGENT=URGENT, OGS=OGS, DGS=DGS, ON_ACCENT="#FFFFFF")
DARK = dict(BG="#1C2124", INK="#E9ECE8", INK2="#97A09C", LINE="#333A38", ACCENT="#93A9D6",
            ACCENT_SOFT="#243049", URGENT="#DB7B70", OGS="#6F9FC8", DGS="#A99368", ON_ACCENT="#14181A")
ON_ACCENT = "#FFFFFF"


def use_palette(palette):
    """Swap the colour tokens the drawing functions read (they are module globals)."""
    globals().update(palette)

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
        col = ON_ACCENT if not disabled else INK2
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


def nav_triangle(cx, cy, forward, bar, tint):
    """A media-transport glyph: a triangle, optionally with an end bar."""
    w, h = 9, 11
    if forward:
        tri = f"{cx-w/2},{cy-h/2} {cx+w/2},{cy} {cx-w/2},{cy+h/2}"
    else:
        tri = f"{cx+w/2},{cy-h/2} {cx-w/2},{cy} {cx+w/2},{cy+h/2}"
    s = f'<polygon points="{tri}" fill="{tint}"/>'
    if bar:
        bx = cx + w / 2 + 2 if forward else cx - w / 2 - 4
        s += f'<rect x="{bx}" y="{cy-h/2}" width="2.5" height="{h}" rx="1.2" fill="{tint}"/>'
    return s


def move_nav(y, mv, live=True, back_enabled=True, forward_enabled=False):
    """Five outlined pills: start, back, the cursor's move number, forward, end.

    The counter pill is highlighted (accent on accent_soft) when the board shows exactly
    the server position, which is the state the screen is about — see DESIGN.md.
    """
    h, gap = 44, 8
    w = (W - 40 - 4 * gap) / 5
    s = ""
    cells = [
        ("skip_back", back_enabled),
        ("step_back", back_enabled),
        ("counter", True),
        ("step_fwd", forward_enabled),
        ("skip_fwd", forward_enabled),
    ]
    for i, (kind, enabled) in enumerate(cells):
        x = 20 + i * (w + gap)
        cx, cy = x + w / 2, y + h / 2
        highlighted = kind == "counter" and live
        stroke = ACCENT if highlighted else LINE
        fill = ACCENT_SOFT if highlighted else "none"
        s += (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{h/2}" '
              f'fill="{fill}" stroke="{stroke}" stroke-width="1"/>')
        tint = INK if enabled else LINE
        if kind == "counter":
            s += text(cx, cy + 6, f"{mv}", 18, 600 if highlighted else 500,
                      ACCENT if highlighted else INK, "middle")
        else:
            s += nav_triangle(cx, cy, "fwd" in kind, "skip" in kind, tint)
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


def screen_games(empty=False, offline=False):
    s = frame_open() + status_bar()
    s += text(20, 88, "Your move", 26, 600)
    if empty:
        s += stone_icon(W / 2, 370, 22, WHITE)
        s += text(W / 2, 430, "No games waiting for you", 17, 500, INK, "middle")
        return s + bottom_nav("Games") + "</svg>"
    if offline:
        # Built like the empty state, not like an error: nothing is red, and the action
        # that resolves it is on screen. No icon — see DESIGN.md.
        s += text(W / 2, 400, "Can't reach Dragon Go Server", 17, 500, INK, "middle")
        s += text(W / 2, 424, "Check your connection and try again.", 14, 400, INK2, "middle")
        s += button(W / 2 - 90, 460, 180, 48, "Try again")
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


def action_row_y(above=0):
    """The Skip / Send row sits at the bottom edge of the board screen, 16 above it (and above
    whatever line sits at that edge, [above] tall). An open game has no bottom bar."""
    return H - above - 16 - 48


def screen_board(n, stones, last, trial=(), trial_mode=False):
    s = frame_open() + status_bar()
    s += header_game("mkvtl", "7k", "black", 87 if n == 19 else 12, "3 d 4 h" if n == 19 else "22 h")
    b, yend = board(n, 104, stones, last, trial, trial_mode)
    s += b
    # below the board: move navigation, then the action row at the bottom edge. No trial
    # caption or hint line — the numbered stones and the counter already say where the board
    # stands.
    y = yend + 20
    played = 87 if n == 19 else 12
    # On a trial the cursor has walked past the server position, so the counter is a
    # plain pill; on the server position itself it is the highlighted one.
    s += move_nav(y, played + len(trial) if trial_mode else played,
                  live=not trial_mode, forward_enabled=trial_mode)
    y = action_row_y()
    bw = (W - 40 - 12) / 2
    s += button(20, y, bw, 48, "Skip")
    if trial_mode:
        s += button(20 + bw + 12, y, bw, 48, f"Send {trial[0][0]}", primary=True)
    else:
        s += button(20 + bw + 12, y, bw, 48, "Send", primary=True, disabled=True)
    return s + "</svg>"


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


# ---- two servers (OGS support, designed September 2026) -------------------
def server_bar(x, y, h, color):
    """The server mark on a list row: a 4dp rounded bar at the row's leading edge."""
    return f'<rect x="{x}" y="{y}" width="4" height="{h}" rx="2" fill="{color}"/>'


def server_dot(cx, cy, color, r=4):
    return f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{color}"/>'


def games_tabs(y, counts=("3", "7")):
    s = ""
    tabs = [("Your turn", counts[0]), ("All games", counts[1]), ("Finished", None)]
    tw = (W - 40) / 3
    for i, (label, count) in enumerate(tabs):
        cx = 20 + tw * i + tw / 2
        col = ACCENT if i == 0 else INK2
        s += text(cx - (6 if count else 0), y, label, 14, 500 if i == 0 else 400, col, "middle")
        if count:
            s += text(cx + 38, y, count, 13, 400, col, "middle")
        s += (f'<rect x="{20 + tw * i}" y="{y + 12}" width="{tw}" height="2" '
              f'fill="{ACCENT if i == 0 else LINE}"/>')
    return s


def player_icon(cx, cy, name, picture):
    """The opponent's picture in a circle (a stand-in pattern here); their initial without one."""
    s = f'<circle cx="{cx}" cy="{cy}" r="18" fill="{LINE}"/>'
    if picture:
        # A Gravatar-like block pattern, clipped to the circle by staying inside it.
        for dx, dy in ((-8, -8), (2, -8), (-8, 2), (2, 2), (-3, -3)):
            s += f'<rect x="{cx + dx}" y="{cy + dy}" width="6" height="6" fill="{INK2}"/>'
        return s
    return s + text(cx, cy + 5, name[0].upper(), 14, 400, INK2, "middle")


def screen_games_two_servers():
    s = frame_open() + status_bar()
    s += text(20, 92, "Turnzen", 26, 600)
    s += games_tabs(136)
    rows = [
        (OGS, "hana_go", "4k", "13x13, move 34, 18 min ago", "6 h", True),
        (DGS, "mkvtl", "7k", "19x19, move 87, 2 h ago", "3 d 4 h", False),
        (OGS, "kaya_r", "?", "9x9, move 12, yesterday", "2 d 20 h", True),
    ]
    y = 160
    for color, opp, rank, line, left, urgent in rows:
        s += server_bar(8, y + 16, 40, color)
        # OGS sends each player's picture; DGS has none, so its row shows the initial.
        s += player_icon(38, y + 36, opp, picture=color == OGS)
        s += text(68, y + 30, f"{opp} ({rank})", 16, 600)
        s += text(68, y + 51, line, 13, 400, INK2)
        s += text(W - 20, y + 41, left, 15, 600, URGENT if urgent else INK, "end")
        y += 72
        s += hairline(y)
    # One server did not answer: one quiet line, the other server's games stay.
    s += text(20, y + 34, "Online Go Server didn't answer · Try again", 13, 400, INK2)
    return s + bottom_nav("Games") + "</svg>"


def signin_panel(y, color, name, state, form=False, handle=None):
    """One server's half of the sign-in screen. state: out | in."""
    s = server_bar(20, y, 22, color)
    s += text(34, y + 17, name, 17, 600)
    if state == "in":
        s += text(20, y + 50, f"Signed in as {handle}", 15, 400, INK)
        s += text(W - 20, y + 50, "Sign out", 15, 500, ACCENT, "end")
        return s, y + 80
    if form:
        for i, (label, val) in enumerate([("Username", ""), ("Password", "")]):
            yy = y + 56 + i * 66
            s += text(20, yy, label, 13, 400, INK2)
            s += hairline(yy + 34)
        s += button(20, y + 196, W - 40, 48, "Sign in to DGS")
        s += text(20, y + 276, "Register a Dragon Go Server account", 13, 500, ACCENT)
        return s, y + 300
    s += text(20, y + 50, "Signs in on the server's own page; Turnzen never", 13, 400, INK2)
    s += text(20, y + 68, "sees your password.", 13, 400, INK2)
    s += button(20, y + 86, W - 40, 48, "Sign in with Online Go Server")
    s += text(20, y + 162, "Register an Online Go Server account", 13, 500, ACCENT)
    return s, y + 186


def screen_signin_two_servers(ogs_in=False, dgs_in=False):
    s = frame_open() + status_bar()
    s += stone_icon(W / 2 - 12, 92, 14, BLACK) + stone_icon(W / 2 + 12, 92, 14, WHITE)
    s += text(W / 2, 142, "Turnzen", 28, 600, INK, "middle")
    s += text(W / 2, 168, "Sign in to one server or both.", 14, 400, INK2, "middle")
    y = 206
    part, y = signin_panel(y, OGS, "Online Go Server", "in" if ogs_in else "out", handle="kiseido")
    s += part + hairline(y)
    part, y = signin_panel(y + 24, DGS, "Dragon Go Server", "in" if dgs_in else "out", form=True, handle="csaba")
    s += part
    s += button(20, H - 84, W - 40, 50, "Continue", primary=True, disabled=not (ogs_in or dgs_in))
    return s + "</svg>"


def screen_settings_two_servers():
    s = frame_open() + status_bar()
    s += text(20, 88, "Settings", 26, 600)
    y = 120
    sections = [
        (OGS, "Online Go Server", [("Signed in as", "kiseido"), ("Open on the web", None), ("Sign out", None)]),
        (DGS, "Dragon Go Server", [("Signed in as", "csaba"), ("Server", "dragongoserver.net"), ("Sign out", None)]),
        (None, "Board", [("Coordinates", "On"), ("Mark last move", "On")]),
        (None, "Appearance", [("Theme", "System")]),
    ]
    for color, title, rows in sections:
        if color:
            s += server_dot(24, y - 4, color)
            s += text(36, y, title, 13, 500, INK2)
        else:
            s += text(20, y, title, 13, 500, INK2)
        y += 14
        for label, val in rows:
            y += 40
            col = ACCENT if val is None else INK
            s += text(20, y, label, 16, 400, col)
            if val:
                s += text(W - 20, y, val, 15, 400, INK2, "end")
            s += hairline(y + 14)
        y += 44
    return s + bottom_nav("Settings") + "</svg>"


def screen_board_header_two_servers():
    """Top of the board screen: the server dot beside the opponent's name."""
    s = frame_open() + status_bar()
    s += server_dot(24, 61, OGS)
    s += text(36, 66, "hana_go (4k)", 16, 600)
    s += text(20, 86, "You play white, move 34", 13, 400, INK2)
    s += text(W - 20, 66, "6 h", 15, 600, URGENT, "end")
    s += text(W - 20, 86, "to move", 13, 400, INK2, "end")
    b, yend = board(13, 104, [("D4", "B"), ("K10", "W"), ("K4", "B"), ("D10", "W"), ("G7", "B")],
                    ("G7", "B"), coords=False)
    s += b
    s += text(20, yend + 40, "Game info · Online Go Server · game 7000001", 13, 400, INK2)
    return s + "</svg>"


def outbox_line(message, action, bottom=NAV_Y):
    """The line about the move that has just left, above the bottom bar (or, on an open game,
    which has none, at the bottom edge): flat, a hairline over it."""
    y = bottom - 44
    s = f'<rect x="0" y="{y}" width="{W}" height="44" fill="{BG}"/>' + hairline(y, 0, W)
    s += text(20, y + 27, message, 14)
    s += text(W - 20, y + 27, action, 14, 500, ACCENT, "end")
    return s


def screen_board_comment(writing=False):
    """The board with the opponent's message above the action row; writing, the comment field
    open in place above it, and Send, which goes in one tap, at the bottom edge."""
    s = frame_open() + status_bar()
    s += header_game("mkvtl", "7k", "black", 12, "22 h")
    trial = (("E6", "B"),) if writing else ()
    b, yend = board(9, 104, STONES_9, LAST_9, trial, trial_mode=writing)
    s += b
    y = yend + 20
    s += move_nav(y, 13 if writing else 12, live=not writing, forward_enabled=writing)
    y += 44
    # The comment for the next move: a text action, or the field it opens, between the move
    # navigation and the action row, so a slip off the forward buttons never lands on Send.
    if writing:
        s += text(36, y + 34, "Thanks, I think it holds", 16)
        s += f'<rect x="20" y="{y + 50}" width="{W - 40}" height="2" fill="{ACCENT}"/>'
        y += 60
    else:
        s += text(24, y + 30, "Add a comment", 14, 500, ACCENT)
        y += 46
    # Comments: below everything above them that is operated, with the free space under them
    # taking up the difference, so they never move a button.
    y += 4 + 18
    # The writer leads the text on its line; their next part goes without the name.
    s += (f'<text x="20" y="{y}" font-family="{FONT}" font-size="16" fill="{INK}">'
          f'<tspan font-weight="600">mkvtl: </tspan>{esc("Not sure about that one.")}</text>')
    s += text(20, y + 22, "We'll see. Your move!", 16)
    assert y + 22 + 16 <= action_row_y(), "comments run into the action row"
    y = action_row_y()
    bw = (W - 40 - 12) / 2
    s += button(20, y, bw, 48, "Skip")
    s += button(20 + bw + 12, y, bw, 48, "Send E6" if writing else "Send", primary=True, disabled=not writing)
    return s + "</svg>"


def screen_board_send_failed():
    """On the next game, after the move sent from the last one did not get through."""
    s = frame_open() + status_bar()
    s += header_game("hana_go", "9k", "white", 8, "6 h")
    b, yend = board(9, 104, STONES_9, LAST_9)
    s += b
    y = yend + 20
    s += move_nav(y, 8)
    y += 44
    s += text(24, y + 30, "Add a comment", 14, 500, ACCENT)
    y = action_row_y(above=44)
    bw = (W - 40 - 12) / 2
    s += button(20, y, bw, 48, "Skip")
    s += button(20 + bw + 12, y, bw, 48, "Send", primary=True, disabled=True)
    s += outbox_line("E6 to mkvtl wasn't sent", "Open", bottom=H)
    return s + "</svg>"


screens = {
    "01-signin": screen_signin(),
    "02-games-your-move": screen_games(),
    "02b-games-empty": screen_games(empty=True),
    "02c-games-offline": screen_games(offline=True),
    "03-board-server-position-19": screen_board(19, STONES_19, LAST_19),
    "03b-board-trial-19": screen_board(19, STONES_19, LAST_19, TRIAL_19, trial_mode=True),
    "03c-board-server-position-9": screen_board(9, STONES_9, LAST_9),
    "04-settings": screen_settings(),
}
use_palette(LIGHT)
screens.update({
    "05-games-two-servers": screen_games_two_servers(),
    "06-signin-two-servers": screen_signin_two_servers(),
    "06b-signin-ogs-signed-in": screen_signin_two_servers(ogs_in=True),
    "06c-signin-both-signed-in": screen_signin_two_servers(ogs_in=True, dgs_in=True),
    "07-settings-two-servers": screen_settings_two_servers(),
    "08-board-server-dot": screen_board_header_two_servers(),
    "09-board-comment": screen_board_comment(),
    "09b-send-with-comment": screen_board_comment(writing=True),
    "09c-send-failed": screen_board_send_failed(),
})
use_palette(DARK)
screens.update({
    "05d-games-two-servers-dark": screen_games_two_servers(),
    "06d-signin-two-servers-dark": screen_signin_two_servers(ogs_in=True),
    "07d-settings-two-servers-dark": screen_settings_two_servers(),
})
for name, svg in screens.items():
    with open(os.path.join(OUT, f"{name}.svg"), "w") as f:
        f.write(svg)
    print("wrote", name)
