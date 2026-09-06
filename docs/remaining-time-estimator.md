# Minute-resolution remaining-time estimator (design, not yet built)

A planned post-MVP feature: show correspondence players how many minutes they really have
left when a game is close to timing out, which no DGS client and not even the DGS website
do. Captured here for later; deliberately deferred to keep the MVP focused.

## The problem

DGS reports remaining time rounded **up** to whole hours, and never shows "0 h" (verified
from real games lost to timeout, 2026-09-06). A game sits at "1 h" for the entire final
hour and then times out. So a displayed `N h` means the true remaining time is in the
interval `(N-1, N]` hours:

- `"1 h"` = 0 to 60 minutes left (can be 2 minutes)
- `"3 h"` = just over 2, up to 3 hours

The last hour, exactly when minutes matter most, is where the display is least precise.
The seconds are tracked internally by DGS but are not exposed through any interface we can
use (QuickSuite `remtime`, RSS/WAP feeds, and the web UI all show the same coarse value).

## The idea: triangulate the deadline

We cannot read the minutes, but we can pin the one exact thing, the timeout **deadline**,
by intersecting repeated coarse readings over time. Each reading constrains the deadline:

> a reading of `N h` observed at time `t` means the deadline is in `(t + (N-1)h, t + N·h]`.

Successive readings intersect to a shrinking window. The single most valuable event is the
tick from `"2 h"` to `"1 h"`: at that instant the true remaining is exactly 60 minutes, so
the deadline is pinned to (that moment, + 60 min]. Once the window is tight, the app counts
down locally to the second, no further polling needed to tick.

Example:

| observed at | shows | deadline is in        |
|-------------|-------|-----------------------|
| 10:00       | 3 h   | (12:00, 13:00]        |
| 12:30       | 1 h   | (12:30, 13:30]        |
| intersection|       | **(12:30, 13:00]** (30 min wide) |

Catching the 2 h -> 1 h transition:

| observed at | shows | deadline is in   |
|-------------|-------|------------------|
| 12:00       | 2 h   | (13:00, 14:00]   |
| 12:05       | 1 h   | (12:05, 13:05]   |
| intersection|       | **(13:00, 13:05]** (5 min wide) |

## Estimator contract (stage 1, pure Kotlin, heavily unit-tested)

Presentation-agnostic; lives in a pure module (candidate: `:core-goserver`), no Android, no
network, so the logic is provable in isolation.

- **Input:** the timestamped ceiling readings for the *current turn* of one game. Each is
  `(observedAt: Instant, shownHours: Int)` with `shownHours >= 1`.
- **Output:** a `DeadlineWindow(earliest, latest)` meaning the deadline is in
  `(earliest, latest]`, or `null` when there is too little or contradictory data.
- **Rule:** window of one reading = `(observedAt + (shownHours-1)h, observedAt + shownHours·h]`.
  Combine readings by intersecting: `earliest = max(lower bounds)`,
  `latest = min(upper bounds)`.
- **Reset on contradiction:** observations belong to one turn (keyed by last-move time /
  move token). If intersecting an older reading empties the window, the clock was paused or
  the turn changed; drop the older readings and keep the most recent consistent run.

Test cases to cover: single reading (1 h wide), two readings narrowing, the 2 h -> 1 h
transition pinning to a few minutes, contradiction causing a reset, empty input.

## Later stages

2. Persist a few readings per game locally so the estimator has history across app opens.
   While the game screen is open in the final hour, poll about once a minute (within DGS
   limits) to catch the transition and tighten the window.
3. Surface it. See presentation below.

## Presentation (progressive disclosure, confidence-gated)

Not an "easter egg": the value is exactly what a panicking player needs to find, so it must
not be hidden behind an undiscoverable gesture. Instead:

- **List stays coarse and honest:** the server value, e.g. `"1 h"` in the urgent red. DGS
  players already read that as "within the last hour".
- **Surface the estimate where the user acts,** the game screen header, and only when it is
  both trustworthy (tight window) and relevant (time is short): `"~= 12 min"`, optionally
  with the deadline `"deadline ~22:47"`.
- **Honesty rules:** never show a bare authoritative `"12 min"` unless the window is tight
  (say < 2 min). Wider, show a range (`"8-15 min"`) or a `~`. Show nothing extra when only
  one reading exists or a pause is suspected; fall back to the server value.
- If an on-demand reveal is still wanted, prefer tap-to-toggle on the label (value is clear
  once the finger lifts) over long-press (finger obstructs the value).

## Caveats to handle before trusting minutes

- **Clock pauses:** the weekend clock (`time_weekend_clock` per game) and vacation
  (from user info) stop the countdown; unmodeled, the estimate drifts. Detect contradictions
  and widen/re-triangulate.
- **Cold start:** one reading is still +/- an hour; the window tightens only with repeated
  readings or by catching the hour tick. Present as an estimate until it is tight.
- **Natural extension:** a local notification when a game is near timeout is arguably the
  real fix for losing games unattended; needs background scheduling and is out of scope here.

## References

- Rounding behaviour: memory `dgs-remtime-rounds-up`.
- Field localisation caveat (do not reformat server times blindly): memory
  `dgs-localises-some-fields`.
