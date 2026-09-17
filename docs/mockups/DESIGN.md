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
| `accentSoft` | `#E4E9F2` | Accent backgrounds: the live move-counter pill |
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
- **Games screen**: header is the wordmark "Turnzen". Below it, three tabs above the list
  (a content filter, distinct from the bottom destination bar): "Your turn" and "All games",
  each with a count, and "Finished" without one — it holds the last fifteen games, so the
  count would only ever be the limit. A finished row puts the outcome where a running row
  has its clock, on one line: "Won by 12.5" in `accent` for the reader's own win, "Lost by
  resignation" in `ink2` otherwise. Times are shown as "N h ago" up to 48 h, then "N d ago", then a date.
- **Board**: full screen width, square, edge to edge. Grid spacing = width / n, first line
  at half a cell from the edge. Hoshi at (3,9,15) for 19, (3,6,9) for 13, (2,4,6) for 9.
  Coordinates optional (Settings), minimum 8sp. Stone radius 0.46 × cell. Last server
  move gets a small dot in the opposite color.
- **Board screen, top to bottom**: status bar → header (opponent name and rank, "You play
  black, move N" — which states the colour, so no stone icon repeats it; right: remaining
  time over "to move", or, for a finished game, the outcome) → board →
  info row → action row → flexible space → bottom nav.
- **Move-navigation row**: five controls across the width — start, step back, the cursor's
  move number, step forward, end; back and forward repeat while held. There is no separate
  info row, trial counter or hint line: the numbered stones and this counter already say
  where the board stands.
  Each control is an outlined pill, 44dp tall, in the action row's shape (one button
  vocabulary, and shorter than the 48dp action row, which outranks it). A control with
  nowhere to go keeps its pill and fades the border to half `line`, so the row never changes
  shape as the cursor moves. Press feedback is a `line` fill, not a ripple: flat, and the
  same for the tap controls and the hold-to-repeat ones.
- **A horizontal drag on the board walks the same timeline**, right towards the live
  position and left towards the opening, matching the way the navigation row is laid out. It
  is a fixed rate — one move per 12dp of travel — not a mapping of the board's width onto the
  game's length: an absolute mapping would put a 250-move game at under a point per move and
  make "back two" unhittable, while a fixed rate feels the same on a 9×9 and a 19×19. The
  ends of the timeline stay the skip buttons' job. The drag is off while the game is being
  counted, where the count has taken the navigation row's place.
- **Board gestures must be separable by distance, not by time.** A drag is told apart from a
  tap by touch slop, so a touch that does not travel stays a tap and places its stone with no
  delay. A double tap is told apart only by a timeout, which would hold *every* tap for it —
  on a surface whose single tap is the primary action, and which players use in quick
  succession to build a variation, that is disqualifying. It is also why no gesture may reach
  the server: the one on the board that does is the Send button, and it is a button.
- **The move counter is the exception**, because it is a readout that is only sometimes an
  action. When the board shows exactly the server position — the counter equals the played
  move count *and* no variation branches out of the past — a tap has nothing to do. That is
  not a disabled state but the opposite: it is the state the whole screen is about, so the
  pill is filled `accentSoft`, bordered and lettered in `accent`, and set in 600 — the
  strongest thing in the row, reading "the board is the game". Anywhere else (rewound, or
  reading a what-if branched off the past, even where the number happens to equal the move
  count) it is an ordinary outlined button that returns to the live position.
- **Action row**: two equal 48dp pill buttons. Skip (outlined) keeps a fixed half-width
  slot on the left in every game, so it never moves; the right slot is *the move that would
  go to the server*, and is filled only on the user's turn (an empty spacer holds it
  otherwise). Normally that is Send (filled accent), disabled (`line` fill, `ink2` text)
  while there is no sendable trial stone and reading "Send <coordinate>" as soon as there is
  one. **Pass takes that slot** once the opponent has passed and Send has nothing armed: a
  pass is then the move on offer, and that is the moment the player looks for the button. It
  is outlined, not filled — passing is an available answer, not the recommended one — and an
  armed Send outranks it, so trying a stone puts "Send D10" back and stepping off it brings
  Pass back. Everywhere else passing lives in the game-info sheet, costing the board
  nothing. Both go through the same confirmation any move does.
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
- **Counting a finished game** is a mode of the board screen, entered whenever the server
  says the game is in its scoring phase. The board keeps its stones; dead ones are drawn as
  ghosts of themselves (32% opacity) and each side's territory gets a small square in that
  side's stone colour — the same vocabulary as the stones, at a size that cannot be mistaken
  for one. Tapping a group marks it dead or brings it back, and **marking is local**, like
  every other board gesture: nothing reaches the server until the user sends it.
  The move-navigation row is replaced by the count, because the board is the final position
  and there is nothing left to walk through: the headline in title weight, the two sides'
  sums below it, the key that names their addends, and one quiet line of guidance — "Tap a
  group to mark it dead", "Your marks are on this device until you send them", or "Waiting
  for your opponent to agree".
  A "Reset marks" text action appears beside the count once there is something to undo.
  The action row keeps Skip, and the right slot settles the score: **Send marks** while the
  user has marked something (it is a move — the opponent reviews it and the turn passes),
  **Accept score** when the marks are untouched. Both are confirmed.
  The figure shown is the server's own count while the marks are untouched — it is the count
  an agreement agrees to — and the device's own count as soon as the user marks something,
  because the server's answer is then about a different set of stones.
- **The count is written from the reader's seat.** Every game in the app is one of theirs, so
  the headline says "You're winning by 7.5" or "You're losing by 7.5", never "Black leads by
  7.5" — naming a colour makes the reader translate. Present tense and "winning" rather than
  "leads": the game is not over until both players have agreed, and "leads" belongs to a game
  still being played. A jigo is "A draw"; a void game, "No result".
- **The sums are shown as sums**, one line per side, the reader's own first:

  ```
  You (black)              42 + 3 = 45
  kirisame (white)   30 + 2 + 6.5 = 38.5
  Territory + prisoners + komi
  ```

  The key underneath names the addends in the order they are added, and names only the ones
  actually on screen — a game with no prisoners and no komi does not promise figures that are
  not there. Under area scoring the second addend is standing stones rather than prisoners,
  and a handicap game adds white's compensation, one point per handicap stone. Dame are not
  shown: they are worth nothing to anyone and a permanently zero row is noise. The opponent
  is named by handle, with the colour in parentheses — the board's territory marks are in
  stone colours, so the breakdown is where that thread is tied.
- **The breakdown is always the device's own arithmetic**, even when the headline is the
  server's: the server sends a result and its territory but no per-side totals, so there is
  nothing of its own to take apart. When the two answers differ by more than rounding — seki,
  or dame left unfilled under area scoring — a line under the count says so and names both
  figures, rather than leaving two numbers on screen that refuse to add up.
- **A game being counted says so in the list**: the row reads "9x9, counting, 40 min ago"
  rather than naming a move number that nobody is waiting for.
- **Game info sheet**: everything the server already told us that the board has no room for
  — both players with their rank, captures and *both* clocks (the header can only show the
  one that is running), then board size, handicap, komi, rules, rated, time system, weekend
  clock, start date and the tournament id where there is one. Rows in the Settings screen's
  vocabulary: label left, value right in `ink2`, hairline between. A modal sheet rather than
  a screen, because it is reading material *about* the board behind it and dismissing it is
  a swipe. It opens from the header, which is the game's name plate; a small `ink2` chevron
  after the opponent's name is the only affordance — a disclosure marker is navigation, not
  one of the named text actions the board screen insists on, and it costs no vertical space.
  At the bottom sit the rare actions: Pass (accent) when it is the user's turn, "Open on
  <server>" (accent), and Resign last, the only `urgent` thing in the sheet, confirmed with
  an `urgent` confirm button because it cannot be undone. Nothing in the sheet is needed to
  play a move.
- **Say what the app cannot do, before the move rather than after it.** Passing when the
  opponent has already passed ends the game and starts scoring, which Turnzen has no screen
  for; the confirmation says so and names the server where it can be finished.
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
