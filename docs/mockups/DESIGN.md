# Turnzen — visual design notes (MVP)

These notes accompany the SVG mockups in this folder. The mockups are the reference for
layout and hierarchy; this file turns them into tokens and rules for the Compose theme.
Frame size is 390×844 (a common phone width); everything must adapt to other widths.

> Note (September 2026): the SVGs predate several implemented changes and are now only a
> rough reference. The prose below is current. Known drift in the SVGs: the games screen has
> no title, just the "Your turn"/"All games"/"Finished" tabs (not a "Your move" title); list rows have
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
| `05-games-two-servers.svg`, `05d-…-dark.svg` | Games list with DGS and OGS games mixed, one server not answering |
| `06-signin-two-servers.svg`, `06b-…`, `06c-…`, `06d-…-dark.svg` | Two-panel sign-in: nothing signed in, OGS signed in, both, dark |
| `07-settings-two-servers.svg`, `07d-…-dark.svg` | Settings with one account panel per server |
| `08-board-server-dot.svg` | Board header with the server dot |

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
   screen (Pass, Send, Reset are text).
6. **No help text.** The reader is a Go player who knows what they are doing: no lines that
   explain how to use a screen ("Tap a stone to…", "Pull down to…"), and confirmations ask
   their question without a paragraph under it. Text that reports a state — waiting for the
   opponent, a server that cannot be reached — is not help and stays.

## Color tokens

| Token | Hex | Use |
|---|---|---|
| `surface` | `#F2F3F0` | Screen background |
| `ink` | `#1F2A26` | Primary text, icons |
| `ink2` | `#6B7470` | Secondary text |
| `line` | `#D6DAD5` | Hairlines, disabled fills, outlined buttons |
| `accent` | `#2E54C0` | Send button, active nav item, text actions (Sign out, links). A saturated indigo so text actions stay clearly distinct from `ink` labels while white text on the Send fill stays legible. |
| `accentSoft` | `#E4E9F2` | Accent backgrounds: the live move-counter pill, and the wash over a row a refresh has just brought into the games list |
| `urgent` | `#B8483E` | Low remaining time |
| `board` | `#D9B778` | Board surface (muted kaya) |
| `boardLine` | `#5A4A2E` | Grid lines, hoshi, coordinates |
| `stoneBlack` | `#15171A` | Black stones |
| `stoneWhite` | `#F7F5F0` | White stones (0.6px `stoneBlack` outline) |

### Server marks (OGS support, September 2026)

Each server has a colour taken from its own web presence. It is only ever a **4dp rounded
bar** at a list row's leading edge (in the 20dp margin, 8dp in, so the text column never
moves) or a **small dot** (8dp) beside a name or a heading — never a fill, so it competes
neither with the `accentSoft` refresh wash nor with the board. The marks show even when only
one server is signed in; with one server they are a quiet accent.

| Token | Light | Dark |
|---|---|---|
| `serverOgs` | `#5E9FD6` (light blue) | `#6F9FC8` |
| `serverDgs` | `#C2A66E` (sand) | `#A99368` |

Colour is never the only carrier: the server is named in the sign-in and Settings panels
and under the game-info sheet's title ("Online Go Server · game 7000001"), and every list
row tells TalkBack "on Online Go Server".

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
  Not on an open game: the board screen is a detail of the Games tab, left with the back
  gesture, and needs the height more than a way to Settings.
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
- **Opponents' pictures** lead the text where a server has them (OGS sends every player's
  avatar): a 36dp square with 4dp corners, as OGS shows it (round is
  for stones), 12dp before the name, two text lines tall so the row keeps its
  72dp. Once any row in a list has a picture, every row gets the slot — a row without one
  (a DGS game, an OGS player with none) shows the initial in `ink2` on a `line` square — so
  the names stay in one column. A list with no pictures at all (DGS only) has no slot.
  Decorative: TalkBack reads the name, not the picture.
- **The title line never wraps.** A long outcome on the right ("Won by resignation") leaves
  the name little room; the handle is ellipsised and the rank kept, rather than the name
  breaking onto a second line and pushing the details out of the row.
- **New rows flash once.** A refresh that happens while the games list is on screen — the
  one after returning from a game, or on resume — lights the rows it brought in with an
  `accentSoft` wash that arrives in 120ms and drains over 700ms, then plays no more. The
  timing is lopsided on purpose: an equal-length flash reads as a rendering glitch, and one
  short enough to be "quick" (about 300ms end to end) is gone before a gaze that started
  elsewhere on the screen can arrive. The rise being near-instant is what makes it feel
  brief anyway. A row qualifies when the list did not have that game before, or when it has
  advanced a move and is now waiting on the user — so the opponent's move flashes and the
  user's own move never flashes back at them. The first load of a list flashes nothing:
  every row would light up and the highlight would mean nothing.
- **The rows under it move out of the way**, over 300ms, rather than the list changing
  between two frames. Rows are opaque for this: they are drawn in list order, so the row
  sliding down covers the arriving row and uncovers it on the way, and the list reads as
  making room. Placement only — rows that appear or leave do so at once. A fade would also
  play every time the list is arrived at and on every tab switch, on top of the navigation
  transition already running, which is why each tab has its own list rather than one list
  re-filled: a game on both "Your turn" and "All games" shares a key, and rows would
  otherwise animate from their place in one list to their place in the other.
- **Games screen**: no title — a wordmark there cost a fifth of a landscape screen and
  repeated what the launcher showed. Three tabs at the top, directly above the list
  (a content filter, distinct from the bottom destination bar): "Your turn" and "All games",
  each with a count, and "Finished" without one — it holds the last fifteen games, so the
  count would only ever be the limit. A finished row puts the outcome where a running row
  has its clock, on one line: "Won by 12.5" in `accent` for the reader's own win, "Lost by
  resignation" in `ink2` otherwise. Under it, what the game did to their rating, in DGS's own
  unit of hundredths of a rank: "rating +0.23". An unrated game leaves that line empty. The line under the name gives the move count
  ("19x19, 117 moves, 3 d ago") where the server reports it; OGS's game list does not, and
  the row then says only "19x19, 3 d ago" — never "finished", which the tab and the outcome
  already say. A window wide enough for two
  rows of at least 340dp (a phone turned sideways) lays the games out in two columns instead
  of stretching each row across it. Times are shown as "N h ago" up to 48 h, then "N d ago", then a date.
- **Board**: full screen width, square, edge to edge. Grid spacing = width / n, first line
  at half a cell from the edge. Hoshi at (3,9,15) for 19, (3,6,9) for 13, (2,4,6) for 9.
  Coordinates optional (Settings), minimum 8sp. Stone radius 0.46 × cell. Last server
  move gets a small dot in the opposite color.
- **Board screen, top to bottom**: status bar → header (opponent name and rank, "You play
  black, move N" — which states the colour, so no stone icon repeats it; right: remaining
  time over "to move", or, for a finished game, the outcome over the rating change) → board →
  event line → move-navigation row → action row → flexible space. No bottom nav.
- **Event line**: one 24dp slot under the board for what happened at the cursor that leaves
  no stone — "You passed", "kaya_r resigned", "nori_88 ran out of time". The height is
  reserved whether or not there is anything to say, so scrubbing never shifts the navigation
  row under the reader's thumb. Without it the board simply goes quiet at the moments that
  most need explaining: the two passes at the end of a counted game, and the opponent's pass
  that is the reason a Pass button has appeared. Over the count it shrinks to a 12dp margin:
  the count's panel replaces the navigation row anyway, and has nothing to say there. The space above the action row, which
  sits at the bottom edge, absorbs the difference between 9×9 and 19×19; nothing is pinned
  to the board's height.
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
  ends of the timeline stay the skip buttons' job. The drag works while the game is being
  counted too: it is the way from the count back into the game.
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
- **Action row**: two equal 48dp pill buttons at the bottom edge of the screen (16dp above
  it; the board screen has no bottom bar), as far from the board as the screen allows: Send goes in one tap with no
  undo, so a slip off the board must not reach it. Only a screen too short for everything
  scrolls it up with the rest. Skip (outlined) keeps a fixed half-width
  slot on the left in every game, so it never moves; the right slot is *the move that would
  go to the server*, and is filled only on the user's turn (an empty spacer holds it
  otherwise). Normally that is Send (filled accent), disabled (`line` fill, `ink2` text)
  while there is no sendable trial stone and reading "Send <coordinate>" as soon as there is
  one. **Pass takes that slot** once the opponent has passed and Send has nothing armed: a
  pass is then the move on offer, and that is the moment the player looks for the button. It
  is outlined, not filled — passing is an available answer, not the recommended one — and an
  armed Send outranks it, so trying a stone puts "Send D10" back and stepping off it brings
  Pass back. Everywhere else passing lives in the game-info sheet, costing the board
  nothing. Send goes in one tap, at once; Pass is confirmed first (see
  "No confirm, no undo").
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
- **Sign-in**: one panel per server, OGS on top (its panel is a single "Sign in with Online
  Go Server" button, so it stays visible above the DGS form when the keyboard opens). Each
  panel starts with its server mark and name and shows that server's own state: signed in as
  someone with Sign out, or its way of signing in (DGS: two underlined fields and an outlined
  "Sign in to Dragon Go Server"; OGS: one line saying the sign-in happens on OGS's own page
  and an outlined button), then "No account? Register on …". The one primary button is
  Continue at the bottom, enabled once any server is signed in. Settings shows the same panels
  in place of its old Account group.
- **Empty state**: one white stone and "No games waiting for you" — this is a good state,
  not an error.
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
  for one. Tapping a stone marks its whole area dead or brings it back — every stone of that
  colour reachable through empty points, so a dead corner is one tap — and a board nobody has
  marked yet opens with the device's guess already marked. **Marking is local**, like every
  other board gesture: nothing reaches the server until the user sends it.
  While the board stands on the final position, the count takes the move-navigation row's
  place. Dragging back across the board reviews the game as usual: the navigation row returns,
  taps try moves rather than mark, the territory and dead-stone marks are hidden, and Send
  marks / Accept are disabled, so what is agreed to is always what is in view. The row's
  current-move button returns to the count with the marks as they were left. The count: the
  headline in title weight and the two sides' sums below it, nothing else — the screen has no
  height to spare. The event line, which has nothing to say over the count, shrinks to a 12dp
  margin. No line explains how to mark (principle 6), and no key names the addends: the
  player knows how to count.
  A "Reset marks" text action appears beside the count once there is something to undo,
  without vertical padding so it fits the headline's line and the panel never grows.
  The action row keeps Skip, and the right slot settles the score: **Send marks** while the
  user has marked something (it is a move — the opponent reviews it and the turn passes),
  **Accept score** when the marks are untouched. Both are confirmed, with the question only.
  While the opponent has the turn the slot says "Waiting for opponent" in `ink2`, in place of
  the button it explains the absence of.
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
  ```

  The addends come in a fixed order — territory, prisoners, komi — and only the ones that
  are not zero are shown. Under area scoring the second addend is standing stones rather than prisoners,
  and a handicap game adds white's compensation, one point per handicap stone. Dame are not
  shown: they are worth nothing to anyone and a permanently zero row is noise. The opponent
  is named by handle, with the colour in parentheses — the board's territory marks are in
  stone colours, so the breakdown is where that thread is tied.
- **The breakdown is always the device's own arithmetic**, even when the headline is the
  server's: the server sends a result and its territory but no per-side totals, so there is
  nothing of its own to take apart. When the two answers differ by more than rounding — seki,
  or dame left unfilled under area scoring — the breakdown is left out and only the server's
  headline stays, rather than leaving numbers on screen that refuse to add up.
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
- **Comments** sit between the comment field and the action row, and show what
  the players wrote with the move on show: at the current position of a running game that is
  the opponent's message with their last move, which is the one that matters. Inside a trial
  the move it branched from stays the one on show, so the message remains readable while the
  answer is worked out. Below everything above them that is operated, with the free space
  under them taking up the difference, because a comment comes and goes while scrubbing and
  must never move a button under the thumb. Each opens with its writer
  in `ink` 600 and a colon — "You:" or the opponent's handle, never a colour — and the text
  follows on the same line in body size, so a short message costs one line, not two;
  consecutive parts by the same writer name them only once. A part not everyone can
  read yet carries one `ink2` line saying so ("hidden until the game ends", "secret until the
  game ends", "only you can see this"), because its writer is the one person who sees it and
  must not mistake it for said; the line goes once the game is over. Public and players-only
  parts carry nothing: the difference is real but not worth a label on every message.
- **A comment is written in place**, between the move navigation and the action row: an
  "Add a comment" text action (`accent`) that opens a one-row underlined field whose
  placeholder, in `ink2`, says "Comment, sent with your next move", focused as it opens. Above the buttons rather than under them, so Send is
  never the neighbour of the forward buttons and a slip off those lands on the comment, not
  on a move. The row is there from the moment the game opens on the user's turn, so it never
  pushes Send down under a finger; opening the field does move the action row, deliberately
  and in answer to the user's own tap.
  A tap anywhere else ends the editing: an empty field folds back into "Add a comment", one
  holding text stays open, unfocused, so what will go out stays in sight. Leaving the game
  drops an unsent comment rather than keeping it hidden for some later move; a move that
  fails brings its own comment back.
  It goes with the next stone sent. It is cleared once the move leaves and comes back with a
  move that fails. See `09-board-comment.svg` and `09b-send-with-comment.svg`.
- **Pass and resign take their comment in their dialog.** Both are reached from the info
  sheet, out of sight of the board's field, so the confirmation itself holds an underlined
  field like the board's, placeholder "Comment (optional)", already filled in with whatever
  the board's field holds. What the dialog shows is what is sent; Cancel leaves the board's
  draft as it was. The dialogs ask the question and nothing more (principle 6): "Pass?" and
  "Resign?" alone, even when the opponent has passed.
- **No confirm, no undo.** Send has no dialog — the button already names the move — and
  no undo window after it (dropped in October 2026: leaving the app inside the window made
  the move fail). Its place at the bottom edge is the guard against a slip. The board steps
  on to the next game at once and the move goes to the server straight away. A move that
  fails is reported on one line above the bottom bar, wherever the user is by then — "E6 to
  mkvtl wasn't sent" in `ink`, "Open" in `accent` — until that game is opened, where the
  move is back on the board. Flat: a hairline above, the surface below, no card or shadow,
  and in the layout rather than over it, so it never covers the next game's Send. See
  `09c-send-failed.svg`.
- **A game is not a document.** Which game is open survives a configuration change and
  nothing more: after Android kills the app, the next launch starts on the games list, since
  the list and the record cache died with the process and a restored board would have
  nothing to draw.

## Copy

Plain verbs, sentence case, actions keep the same name through a flow: the button says
"Send O15", a failure "O15 to … wasn't sent". Failures say what
happened and what to do ("Can't reach Dragon Go Server" / "Check your connection and try
again."), never apologize, never stay vague, and never blame the user for a connection —
the title names what we observed, the line below it says what to try.
