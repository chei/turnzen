# Turnzen — visual design notes (MVP)

These notes accompany the SVG mockups in this folder. The mockups are the reference for
layout and hierarchy; this file turns them into tokens and rules for the Compose theme.
Frame size is 390×844 (a common phone width); everything must adapt to other widths.

> Note (September 2026): the SVGs predate several implemented changes and are now only a
> rough reference. The prose below is current. Known drift in the SVGs: the games header is
> now "Turnzen" with "Your turn"/"All games" tabs (not a "Your move" title); list rows have
> no "left" caption; Settings has no Server row, drops Version/License, adds Appearance >
> Theme, and labels the account "Signed in as"; a dark theme now exists. Regenerate the
> SVGs from `generate_mockups.py` when convenient.

## Files

| File | Screen |
|---|---|
| `01-signin.svg` | Sign-in (first launch, or after a failed silent re-login) |
| `02-games-your-move.svg` | Games list, default "Your move" view |
| `02b-games-empty.svg` | Games list, empty state |
| `02c-games-offline.svg` | Games list, server unreachable before anything loaded |
| `03-board-server-position-19.svg` | Board, 19×19, showing the server position |
| `03b-board-trial-19.svg` | Board, 19×19, trial variation in progress (4 trial stones) |
| `03c-board-server-position-9.svg` | Board, 9×9 — shows how the layout adapts below a small board |
| `04-settings.svg` | Settings |

## Principles

1. **The board is the only loud element.** Everything else is flat and quiet: no cards,
   no shadows, no gradients. Structure comes from hairlines, spacing and type weight.
2. **One accent color, one meaning.** Indigo marks actions: the Send button, the active
   nav item, and text actions/links (Sign out, Source code). Never use it decoratively.
   Trial stones are not accented; they read as ordinary stones (see "Trial stones" below).
3. **Urgency is the only other color.** A muted red marks low remaining time and nothing
   else. It comes in two steps of the *same* red: plain red text under three days
   (matching DGS's own status page), and bold red on a soft red pill under twelve hours,
   for a game about to time out. No second hue — only the emphasis grows.
4. **Tapping the board never sends anything.** The Send button is the single path to the
   server, and it always names the move it sends ("Send O15").
5. **Sentence case everywhere. No all-caps labels, no icon-only actions** on the board
   screen (Pass, Send, Undo, Reset are text).

## Color tokens

| Token | Hex | Use |
|---|---|---|
| `surface` | `#F2F3F0` | Screen background |
| `ink` | `#1F2A26` | Primary text, icons |
| `ink2` | `#6B7470` | Secondary text |
| `line` | `#D6DAD5` | Hairlines, disabled fills, outlined buttons |
| `accent` | `#2E54C0` | Send button, active nav item, text actions (Sign out, links). A saturated indigo so text actions stay clearly distinct from `ink` labels while white text on the Send fill stays legible. |
| `accentSoft` | `#E4E9F2` | Reserved for accent backgrounds if ever needed (not used in MVP) |
| `urgent` | `#B8483E` | Low remaining time |
| `board` | `#D9B778` | Board surface (muted kaya) |
| `boardLine` | `#5A4A2E` | Grid lines, hoshi, coordinates |
| `stoneBlack` | `#15171A` | Black stones |
| `stoneWhite` | `#F7F5F0` | White stones (0.6px `stoneBlack` outline) |

### Dark theme

Implemented (September 2026). The board and stone colors are identical in both themes, so a
board looks the same day or night. `surface`/`ink`/`ink2`/`line` are darkened, and `accent`
and `urgent` are lightened so they stay legible on a dark surface. First-pass values, open
to refinement:

| Token | Dark hex | Note |
|---|---|---|
| `surface` | `#1C2124` | Screen background (kept lighter than `stoneBlack` so black stone icons read on it) |
| `ink` | `#E9ECE8` | Primary text |
| `ink2` | `#97A09C` | Secondary text |
| `line` | `#333A38` | Hairlines |
| `accent` | `#93A9D6` | Lightened indigo (legible on dark) |
| `accentSoft` | `#243049` | Reserved |
| `urgent` | `#DB7B70` | Lightened red |
| `onAccent` | `#14181A` | Text on the accent fill (dark, since accent is light) |
| `board`, `boardLine`, `stoneBlack`, `stoneWhite` | unchanged | same as light |

The colors are provided at runtime through a `LocalTurnzenColors` palette (see
`ui/theme/Tokens.kt`), read in composables as `colors.<token>`; the board's `Canvas` takes
the palette as a parameter. The theme is chosen in Settings > Appearance (Light / Dark /
System), stored per device, defaulting to **System** (best practice: follow the OS setting
until the user overrides). The status bar and navigation bar icon colors follow the
effective theme, dark icons on the light surface and light icons on the dark one, via
`isAppearanceLightStatusBars`.

## Typography

One family: **IBM Plex Sans** (OFL, bundle it in the app). Weights 400, 500, 600.

| Role | Size | Weight |
|---|---|---|
| Screen title ("Your move", "Settings") | 26 | 600 |
| Row title / opponent name | 16 | 600 |
| Body, buttons | 15 | 400 / 500 |
| Secondary line | 13 | 400 |
| Nav label, helper text | 12 | 400 (500 when active) |
| Trial stone number | ≈ 1.15 × stone radius | 600 |

Use tabular figures for time and move counts so columns don't jitter.

## Layout rules

- **Bottom navigation**: 80dp, two items in the MVP (Games, Settings). Active item in
  `accent`, others in `ink2`. Icons are simple geometry (two stones / a ring with a dot).
- **Screen padding**: 20dp horizontal for everything except the board.
- **Lists**: rows separated by 1dp hairlines, no cards. Row height ≈ 72dp. The row leads
  with its title: opponent (rank). (No stone in the user's color: a filled disc was the
  loudest thing in the row while saying only which colour you play, which the board tells
  you anyway.) Secondary line: size, move number, and time
  since the last move ("13x13, move 40, 45 h ago"), one line, ellipsised. Right: the
  remaining time (600), urgent red under three days and bold on a soft red pill under
  twelve hours (see rule 3); there is no "left" caption. On the opponent's turn (in the
  "All games" tab) the same figure — your own, paused clock — is shown muted (`ink2`,
  regular weight, never coloured) with no caption; the mute alone marks the row as waiting
  on them, so the eye skips to the games that are actually yours to play.
- **Games screen**: header is the wordmark "Turnzen". Below it, two tabs, "Your turn" and
  "All games", each with a count, above the list (content filter, distinct from the bottom
  destination bar). Times are shown as "N h ago" up to 48 h, then "N d ago", then a date.
- **Board**: full screen width, square, edge to edge. Grid spacing = width / n, first line
  at half a cell from the edge. Hoshi at (3,9,15) for 19, (3,6,9) for 13, (2,4,6) for 9.
  Coordinates optional (Settings), minimum 8sp. Stone radius 0.46 × cell. Last server
  move gets a small dot in the opposite color.
- **Board screen, top to bottom**: status bar → header (opponent name and rank, "You play
  black, move N" — which states the colour, so no stone icon repeats it; right: remaining
  time over "to move") → board →
  info row → action row → flexible space → bottom nav. The space below the action row
  absorbs the difference between 9×9 and 19×19; nothing is pinned to the board's height.
- **Move-navigation row**: five controls across the width — start, step back, the cursor's
  move number, step forward, end; back and forward repeat while held. There is no separate
  info row, trial counter or hint line: the numbered stones and this counter already say
  where the board stands.
  Each control is an outlined pill, 44dp tall, in the action row's shape (one button
  vocabulary, and shorter than the 48dp action row, which outranks it). A control with
  nowhere to go keeps its pill and fades the border to half `line`, so the row never changes
  shape as the cursor moves. Press feedback is a `line` fill, not a ripple: flat, and the
  same for the tap controls and the hold-to-repeat ones.
- **The move counter is the exception**, because it is a readout that is only sometimes an
  action. When the board shows exactly the server position — the counter equals the played
  move count *and* no variation branches out of the past — a tap has nothing to do. That is
  not a disabled state but the opposite: it is the state the whole screen is about, so the
  pill is filled `accentSoft`, bordered and lettered in `accent`, and set in 600 — the
  strongest thing in the row, reading "the board is the game". Anywhere else (rewound, or
  reading a what-if branched off the past, even where the number happens to equal the move
  count) it is an ordinary outlined button that returns to the live position.
- **Action row**: two equal 48dp pill buttons. Skip (outlined) keeps a fixed half-width
  slot on the left in every game, so it never moves; Send (filled accent) fills the right
  slot only on the user's turn, an empty spacer holding it otherwise. Send is disabled
  (`line` fill, `ink2` text) while there is no sendable trial stone, and reads
  "Send <coordinate>" as soon as there is one.
- **Tapping**: the newest stone on the board is a back button — tapping it steps behind
  that move, whether it is the newest trial stone or the last played one. Tapping the point
  the game played next is its mirror and steps forward through the record, so a rewound move
  can be tapped straight back in. Tapping anywhere else starts or extends a variation from
  wherever the cursor is, so a rewound board branches a what-if off the past. A line branched in the past cannot be sent (Send stays disabled),
  and returning to the current position drops it.
- **Trial stones**: drawn like any other stone — full opacity, no ring, no board-edge
  accent. With trial-stone numbering on, the move number is their only marking; with it
  off, only the most recently played trial stone is marked, using the same last-move dot
  the real board uses.
- **Sign-in**: content centered vertically; two underlined text fields; one primary
  button; the explanation that this is the DGS account sits below the button; the "I don't
  have a DGS account" link is the only accent text on the screen.
- **Empty state**: one white stone, "No games waiting for you", "Pull down to check
  again." — this is a good state, not an error.
- **Server problems** come in two tones. The ones the user waits out — the server cannot be
  reached, or it asked us to slow down — are a *state*, not an error: `ink2`, never
  `urgent`, always with a way to retry. A correspondence app that could not reach the server
  for an hour has nothing wrong with it. The rest (signed out, a rejected move, an
  unparseable answer) keep `urgent`, because they need the user to do something.
  Connectivity is never probed: telling "your phone is offline" from "the server is down"
  would cost the ACCESS_NETWORK_STATE permission, so the copy says only what we know.
- **Server problem with nothing else on screen** (the games list before its first successful
  load): built like the empty state — centered title ("Can't reach Dragon Go Server"), one
  explanatory line, and a "Try again" pill. No icon: the offline pictograms (a crossed-out
  cloud, a struck-through signal) belong to a different vocabulary than the stones and rings
  used everywhere else, and the sentence is more precise anyway. With a list already on
  screen the same problem is one quiet line above it instead, and pull-to-refresh is the
  retry. On the board, a load that produced no position gets that line plus a "Try again";
  a send that failed does not, because Send itself is the retry and the variation is intact.
- **A game is not a document.** Which game is open survives a configuration change and
  nothing more: after Android kills the app, the next launch starts on the games list, since
  the list and the record cache died with the process and a restored board would have
  nothing to draw.

## Copy

Plain verbs, sentence case, actions keep the same name through a flow: the button says
"Send O15", the confirmation says "Send O15?", the result says "Sent". Failures say what
happened and what to do ("Can't reach Dragon Go Server" / "Check your connection and try
again."), never apologize, never stay vague, and never blame the user for a connection —
the title names what we observed, the line below it says what to try.
