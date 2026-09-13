# Turnzen — Project Brief

This document captures the research and decisions made before starting implementation.
It is meant as the starting context for Claude Code sessions on this project.

## Naming

- **Project name: Turnzen.**
- A deliberately invented, non-dictionary name, not a borrowed Go term. Two naming
  directions were considered and rejected in favor of this:
  - **Dragon/DGS-branded names** — rejected because the project is intended to support
    multiple Go servers over time, not just DGS.
  - **Existing Go terminology** (e.g. "Ponnuki," "Tesuji," "Sente," "Kifu" and variants,
    "Goban") — considered, and an earlier draft of this brief briefly settled on "Ponnuki."
    Rejected in favor of an invented name for a deliberate branding reason: a real Go term
    is not ownable — any other Go developer can pick the same or a similar term, the
    "Go terminology" naming space is already visibly crowded (multiple existing apps use
    "Tesuji," "Kifu," "Sente," etc.), and a borrowed term means nothing to anyone outside
    the Go niche if the project ever grows beyond it. An invented name builds a name that
    belongs only to this project from day one.
  - "Turnstone" (an invented-sounding but real English word, a shorebird species) was also
    considered and rejected because it collides with `turnstonelabs/turnstone`, a
    prominent, actively developed GitHub project (754+ stars) for LLM agent tooling —
    confusingly adjacent given this project is being built with Claude Code.
  - "Sente – Online GO" is worth noting specifically: it's an existing open-source Android
    app that already connects to OGS for both live and correspondence play — almost exactly
    the space this project is aiming at long-term — which is exactly why "Sente" was
    rejected as a name, and why that project is called out again under Prior Art below.
- "Turnzen" evokes turn-based play ("turn") and the unhurried, contemplative pace of
  correspondence Go ("zen") without being a real word, a Go term, or a copy of any existing
  project name. Verified clear of conflicts in the Go-app space, the wider software/app
  space, and the developer-tooling space.
- **Naming conventions to use consistently**:
  - Filesystem directory / repo name: `turnzen`
  - GitHub: `github.com/<username>/turnzen`
  - Android package/application ID: `<reverse-domain>.turnzen` (e.g. `dev.<name>.turnzen`)
  - Play Store / F-Droid listing name: "Turnzen" or "Turnzen: Go Client" if a subtitle is
    wanted for discoverability (search terms like "go," "baduk," "weiqi," "correspondence"
    belong in the store description/keywords, not necessarily the display name).

## Goal

Build a modern, well-designed Go client, launching with support for the
[Dragon Go Server](https://www.dragongoserver.net/) (DGS), a correspondence Go
(Baduk/Weiqi) server. Primary motivation: the existing DGS Android clients are outdated
and have poor UX. Personal daily use is the main success criterion; a wider public release
(F-Droid, possibly Play Store) is a stretch goal, not the primary bar for success.

The project is explicitly designed from day one to eventually support:
- **Additional correspondence Go servers** beyond DGS.
- **Live, real-time Go servers** (polling- or stream-based), which are architecturally
  distinct from correspondence play — see "Multi-server and multi-mode architecture" below.

## Market context (why this is worth doing, and why expectations should stay modest)

- DGS reports roughly **3,000 unique users active in the last 8 weeks** — a small, stable,
  engaged niche community typical of correspondence Go. This is not a mass-market app;
  a realistic mobile audience is likely in the **low hundreds to low thousands**, not more.
- Do not assume this niche skews Android over iOS or vice versa — no reliable platform-split
  data was found. The actual mobile opportunity is best framed as "the existing clients on
  both platforms are mediocre or unmaintained," not "the users are on platform X."
- Success here means being clearly the best option within a small niche, not achieving
  significant download numbers. This is a realistic, achievable bar.
- Monetization potential is low given the niche size. If desired later, an MIT license does
  not prevent the original author from monetizing (donations, optional paid tier), but does
  not prevent forks either. See License section.
- If/when other servers are added (e.g. OGS — Online Go Server), the addressable audience
  grows substantially, since OGS has a much larger and more active user base than DGS. This
  is the single highest-leverage growth item identified so far (see Growth path).

## Prior art / competitive landscape

| Client | Platform | Server(s) | Maintained? | Source available? | License |
|---|---|---|---|---|---|
| **anDGS** (Michael Dobbins) | Android | DGS | Unclear/stale (last known version ~1.27) | No public repo found | Unknown, appears closed |
| **DragonGoApp** ([cerisara/DragonGoApp](https://github.com/cerisara/DragonGoApp)) | Android | DGS | Author confirms (Jan 2025) it's old and no longer actively developed, but still functional | Yes, on GitHub | AGPL-3.0 |
| **Dragon Go Client** (current, dragongoclient.net, maintained by Niclas Eriksson since Nov 2020) | iOS | DGS | Actively maintained | No — "All rights reserved" | Closed source |
| Old **dragongoclient** fork (Justin Weiss / ObjectiveSeki) | iOS | DGS | Abandoned, predates the current app | Yes, but stale/unrelated to current App Store app | Old license, effectively legacy |
| **Sente – Online GO** ([bqv/sente](https://github.com/bqv/sente)) | Android | OGS (live + correspondence) | Appears active | Yes, on GitHub | GPL-3.0 |

Key takeaways:
- **DragonGoApp (AGPL-3.0) is the only relevant open-source, still-functional DGS
  competitor**, and its own README/site honestly documents its limitations.
- The current DGS iOS app is a maintained but closed-source solo indie project — direct
  competition if this project ever expands to iOS.
- **Sente – Online GO already does for OGS roughly what this project wants to eventually do
  more broadly** (multi-mode: live + correspondence, on a modern-ish open-source Android
  base). It is not a DGS client and is not a naming or immediate feature conflict, but it is
  worth knowing about as prior art if/when OGS support is added — study its approach to
  handling both live and correspondence play in one app before designing that layer.

### Lessons from DragonGoApp's approach (author: cerisara)

The author's stance is worth emulating: honest about limitations, functionality-first,
no dark patterns.

- **Bandwidth-consciousness as a first-class UX concern**: the app shows real-time data
  usage, and deliberately limits network calls to only the actions that need them
  (Games/Send/Messages). Less critical in 2026 than 2013, but "don't call the network
  unless the user asked for it" remains a good default (avoids surprise background sync,
  aligns with a clean notification strategy).
- **Local caching with an explicit, user-visible tradeoff**: a "prefer local SGF" mode
  trades comment visibility for speed/bandwidth — the tradeoff is exposed to the user,
  not hidden.
- **Honest scope-limiting**: the README explicitly states the app does *not* (or badly)
  support starting or finishing games, and recommends doing that from a browser instead.
  This is a legitimate, low-risk pattern for an MVP: ship a great experience for the core
  correspondence loop (view pending games, play moves, read/send messages) and punt on
  game creation/termination to a "open in browser" link initially.
- **Dual server/credential support** (production DGS + a dev/test DGS instance, two
  accounts). Worth baking a server-URL parameter into the client abstraction from day one
  rather than hardcoding the production host.
- **No ads, no tracking, minimal permissions, works without Google Play Services** — good
  defaults for this audience (Go players, FOSS-friendly niche).

### What this project should do differently

- Modern, native Compose UI instead of a dated widget-era Android look — this is the main
  differentiator.
- Actually register with and maintain an F-Droid listing (the author of DragonGoApp
  explicitly says he doesn't have the bandwidth to maintain this).
- A dedicated Canvas-based Go board widget instead of embedding an old JS library
  (eidogo) in a WebView.
- A server/backend abstraction designed for multiple correspondence servers **and** live
  servers from the start, unlike any of the single-server clients above.

## Protocol / API research (DGS)

There is no modern REST/JSON API. DGS exposes a **QuickSuite** interface (documented in
the DGS server source under `specs/quick_suite.txt`), which several bots and clients use.
This was studied by reading (not copying) DragonGoApp's `ServerConnection.java` and
`Game.java`. Summary of how it actually works in practice:

- **Auth**: `GET login.php?quick_mode=1&userid=<u>&passwd=<p>`. The server returns a
  session cookie; subsequent requests reuse it via a persistent HTTP client / cookie jar.
  There is no API key or token — it's classic session-cookie auth over HTTP(S).
- **Core operations** follow a consistent pattern:
  `quick_do.php?obj=<entity>&cmd=<action>&<params>`
  Observed examples:
  - `quick_do.php?obj=game&cmd=list&view=status` — list of games awaiting your move
  - `quick_do.php?obj=game&cmd=move&gid=<id>&move_id=<n>&move=<coord>` — play a move
  - `quick_do.php?obj=game&cmd=resign&gid=<id>&move_id=<n>` — resign
  - `quick_do.php?obj=game&cmd=score&gid=<id>&toggle=uniq&move=<...>&move_id=<n>` and
    `cmd=status_score` — scoring phase (marking dead stones)
  - `quick_do.php?obj=message&cmd=list&filter_folders=2&with=user_id` — message list
  - `quick_do.php?obj=message&cmd=send_msg`, `cmd=accept_inv`, `cmd=decline_inv`,
    `cmd=move_msg` — messaging and invitation handling
- **Response format is not clean JSON.** The client reads the HTTP response line by line
  and looks for the first line starting with `{`, then parses that line as a JSON object.
  This implies the raw response mixes HTML/text with an embedded JSON blob. Errors are
  surfaced via an `error` field in that JSON; a `not_logged_in` error value specifically
  means the session expired and requires re-login.
- **SGF export is a separate, non-JSON endpoint**: `sgf.php?gid=<id>&owned_comments=1&quick_mode=1`,
  returning raw SGF text lines (lines starting with `[` are filtered out — likely headers).
- **Game creation/invitation is NOT part of QuickSuite.** It goes through plain HTML form
  POSTs against `message.php` / `login.php` using the regular website session, and requires
  populating a large set of form fields (ruleset, board size, komi, handicap, time
  settings, byoyomi type, etc.). This confirms why existing clients handle game creation
  poorly or not at all — it requires HTML scraping/form-filling, not a clean API call.

### Within-DGS architectural implication

Two distinct client layers are needed inside the DGS integration module:

1. A **QuickSuite client** — relatively structured, used for the core loop (game list,
   move submission, messages, scoring). Lower risk, easier to maintain.
2. An **HTML form client** — used only for game creation/invitations, cookie-session based,
   HTML-scraping/form-encoding. Higher maintenance risk (breaks if DGS changes its HTML),
   and a good candidate to defer to a "open in browser" fallback for the MVP, per the
   DragonGoApp precedent above.

## Multi-server and multi-mode architecture

This is the key structural decision for the whole project: **Turnzen ships with DGS only,
but the backend abstraction must not assume DGS, or even assume correspondence-only play,
anywhere above the DGS-specific module.**

Two independent axes matter here, and they should not be collapsed into one interface:

1. **Which server** (DGS today; other correspondence servers or OGS later).
2. **Which interaction model** — this is the more important distinction:
   - **Turn-based / correspondence**: the client is not connected most of the time. The
     natural interaction is "open app → pull current state → act → close app." Polling on
     demand (and optionally on a schedule/notification trigger) is sufficient and expected.
     This is DGS's model, and the MVP's only target.
   - **Live / real-time**: the client needs to stay connected while a game is in progress
     (WebSocket or equivalent), reflect opponent moves immediately, and manage a live clock.
     This is a fundamentally different interaction model, not just "the same thing, but
     faster polling." UI expectations differ too: a live game screen needs to assume the
     board can change under the user at any moment, where a correspondence game screen does
     not.

**Design decision**: define two separate client-facing abstractions rather than one
generic interface that tries to cover both:

```
TurnBasedGoServerClient   — fetch pending games, fetch board/move history, submit a move,
                             resign, message/invite handling. Pull-based. This is what DGS
                             implements for the MVP, and what any future correspondence
                             server (beyond DGS) would also implement.

LiveGoServerClient        — connect/subscribe to a game, receive move/clock update events,
                             submit a move against a live clock. Not implemented for MVP;
                             defined only as a placeholder interface shape so the module
                             boundaries and UI layer don't have to be reworked later when
                             a live server is added (e.g. OGS live games).
```

The UI layer should be written against these interfaces, not against DGS or QuickSuite
directly, so that:
- Adding a second correspondence server later means writing a new
  `TurnBasedGoServerClient` implementation with no UI changes.
- Adding a live server later means writing a `LiveGoServerClient` implementation and a
  new (but self-contained) live-game screen, without having to retrofit the correspondence
  screens or touch `:core-dgs`.

This does mean slightly more upfront interface design than a single generic abstraction
would need, but it avoids the realistic failure mode of a "turn-based-shaped" interface
being awkwardly stretched to fit live play later (e.g. polling hacks standing in for a
proper subscription model).

## Licensing

- **Chosen license: MIT.**
- DGS itself (the server) is AGPL-3.0, but this does **not** propagate to an independent
  client that only talks to it over HTTP — there is no code-level derivation, only protocol
  compatibility. MIT is a safe choice as long as no code is copied from the DGS server
  source, from DragonGoApp (AGPL-3.0), or from Sente (GPL-3.0). Reading these projects'
  source for protocol/architecture understanding is fine; copying non-trivial code from
  them is not (would obligate copyleft licensing on this project).
- Practical rule for working with Claude Code: when using DragonGoApp, Sente, or any
  AGPL/GPL source as a reference, summarize *what it does* in prose first, and hand that
  summary to Claude Code as the spec — do not paste its source code as generation context.
  This keeps the implementation demonstrably independent.
- MIT does not prevent the original author from monetizing later (donations, optional paid
  tier, etc.), nor does it prevent others from forking a free competing version. Given the
  tiny niche size, this risk is low in practice.

## Technology stack

- **Language/UI**: Kotlin + Jetpack Compose (native Android). Chosen over Flutter/React
  Native/plain Java because: it's the current Google-recommended default, has the deepest
  Claude Code training coverage for high-quality generation, and avoids cross-platform
  build-tooling surprises.
- **Go board rendering**: custom-built using Compose `Canvas` — not a third-party board
  library. No well-maintained Compose-native Go board library exists; the board itself is
  simple 2D geometry (grid lines, stone circles) and is a good, contained, well-scoped
  piece of code to own outright (also avoids inheriting a dependency's license).
- **SGF parsing**: evaluate small, well-maintained Kotlin/Java SGF libraries with permissive
  licenses (MIT/Apache) before writing a custom parser; do not assume one must be
  hand-written.
- **Module structure** (decided now to keep future platform/server/mode expansion cheap,
  even though only Android + DGS + turn-based play ship initially):

  ```
  :core-goserver   — Defines the `TurnBasedGoServerClient` and `LiveGoServerClient`
                      interfaces (the latter unimplemented for now — see Multi-server and
                      multi-mode architecture above). Pure Kotlin, no Android/UI deps,
                      no server-specific code.
  :core-dgs        — DGS-specific implementation of `TurnBasedGoServerClient`: the
                      QuickSuite client + the HTML-form client. Pure Kotlin, depends on
                      :core-goserver only.
  :core-game       — Go rules helpers, SGF read/write, board state. Pure Kotlin, no
                      dependency on :core-goserver or any server-specific module.
  :app-android     — Compose UI. Depends on :core-goserver's interfaces (not on :core-dgs
                      directly, where reasonably avoidable), :core-game, and is the only
                      module allowed to depend on the Android SDK.
  ```

  Adding a second correspondence server later means adding a new `:core-<server>` module
  implementing `TurnBasedGoServerClient` — no changes to `:core-goserver`, `:core-game`, or
  (ideally) `:app-android`.

## Distribution

- **Repository**: GitHub, as `turnzen` (ecosystem fit — DGSLib, DragonGoApp, Sente, and
  most related projects live there; contributors are likely to already have GitHub
  accounts).
- **F-Droid**: an alternative, donation-funded Android app store. It only accepts FOSS
  apps and — unlike a GitHub Releases APK — **builds the APK itself from source** on its
  own infrastructure and signs it, so users don't have to trust the developer's binary,
  only F-Droid's reproducible build process. Good fit for this audience (Go players,
  FOSS-leaning niche). No listing fee. Submission/review can take weeks to months
  (volunteer-run).
  - **Important regulatory context (as of mid/late 2026)**: Google is introducing a
    "verified developer" requirement for apps — including sideloaded and third-party-store
    apps — on "certified Android devices" (i.e., devices with Play Protect / Google apps).
    Rollout starts **September 2026 in Brazil, Indonesia, Singapore, and Thailand only**,
    with **global rollout expected in 2027**. F-Droid has publicly objected, arguing this
    will make third-party distribution harder. This should be watched — if the project's
    public release lands after global rollout, developer verification may become a
    practical requirement even for F-Droid/sideloaded distribution.
- **Play Store**: also viable in parallel; no reason to pick only one. One-time $25
  developer registration fee, stricter review, wider default reach (but irrelevant at this
  audience size). App name "Turnzen," package id per the naming conventions above.
- Sideloading an APK directly from GitHub Releases works identically to F-Droid from
  Android's permission standpoint (both require enabling "install unknown apps"); the
  difference is trust/verification, not mechanism — F-Droid's build pipeline is the
  verification layer DragonGoApp itself says it lacks the resources to maintain via its own
  APK releases.

## Contribution expectations

Realistic for a project this size: bug reports are the most likely contribution type
(niche technical community, likely to have GitHub accounts and to file issues if they hit
problems and see the maintainer respond). Pull requests (small fixes, localization,
possibly a new `TurnBasedGoServerClient` implementation for another server) are plausible
but less frequent. A clear README and CONTRIBUTING.md, plus fast responses to early issues,
matter disproportionately at this scale. The `:core-goserver` interface boundary is also
useful here: it gives external contributors a well-defined extension point (add a new
server) without needing to understand the DGS-specific quirks in `:core-dgs`.

## Growth path (not required for MVP, but the architecture should not block these)

Recommended order, cheapest/lowest-risk first:

1. **Android (Kotlin + Compose), DGS only, turn-based only** — ship first. Best
   documentation, best Claude Code support, matches whichever platform has the weaker
   existing clients today (uncertain which platform has more DGS users, but Android's
   existing clients are clearly the more neglected ones based on maintenance status).
2. **Desktop (Compose Multiplatform for Windows/Mac/Linux)** — cheap follow-on *if* the
   `:core-goserver` / `:core-dgs` / `:core-game` split above is respected, since most
   Compose UI code can be reused directly.
3. **Additional turn-based servers**, and/or **OGS live support via `LiveGoServerClient`**
   — cheap if the interfaces above are respected. OGS in particular has a proper REST API
   and a much larger, more active user base than DGS, making it likely the single
   highest-leverage growth item in terms of reach per unit of engineering effort. Note OGS
   supports both live and correspondence play, so an OGS integration may end up
   implementing both `TurnBasedGoServerClient` and `LiveGoServerClient`. Sente – Online GO
   (GPL-3.0, see Prior art) is worth studying — under the same "read for understanding,
   don't copy code" rule as DragonGoApp — for how it structures live vs. correspondence
   handling in one app.
4. **iOS** — do this last. Kotlin Multiplatform (KMP) can share the `:core-goserver` /
   `:core-dgs` / `:core-game` business logic as genuinely native compiled code on both
   platforms (Kotlin/Native, not an interpreted bridge). Compose Multiplatform can also
   share UI code on iOS, but it renders via its own Skia-based engine rather than wrapping
   native UIKit/SwiftUI components — meaning a "looks native" iOS app is achievable but
   requires deliberate, iOS-specific polish (platform gesture conventions, Cupertino-style
   theming, etc.), not something Compose gives for free. Because this is the most UX-risky
   and highest-polish platform, and because a maintained closed-source competitor already
   exists there for DGS (Dragon Go Client / dragongoclient.net), it should come after the
   lower-risk expansions above, at which point the KMP migration of the `:core-*` modules
   should be close to a non-event since those modules are already pure Kotlin with no
   Android dependency.

## Screens and UX (this list also drives the visual design work)

### Global UX decisions

- **No Turnzen account.** The user signs in with their existing DGS credentials; the app
  offers no functionality without a DGS login in the first version. Link to DGS
  registration (opens in browser) for users without an account.
- **Bottom navigation bar from the MVP onward**, with two destinations: **Games** and
  **Settings**. Later destinations (Messages, Archive) slot into the same bar without
  restructuring navigation.
- **On every app start, fetch the "your move" game list immediately.** No manual step.
- **"Try moves" is the default board behavior.** Tapping the board never sends anything to
  the server; it only places a local trial stone. The only way a move reaches the server
  is the explicit Send action with confirmation.
- **The board view is as clean as possible.** Outside the board itself, show only what is
  strictly necessary. No decorative chrome, no persistent toolbars that compete with the
  board.
- **Board sizes 9×9, 13×13, 19×19 are all first-class. The board always fills the full
  screen width**, square, with grid spacing derived from the size. Layout above and below
  the board must adapt to the remaining vertical space rather than assuming a fixed board
  height.

### MVP screens

**1. Sign-in**
- Fields: DGS username, password. Primary action: Sign in.
- One line explaining that this is the user's DGS account ("Turnzen signs you in with your
  Dragon Go Server account."), plus a "Register a Dragon Go Server account on the web" link
  (browser).
- States: loading, wrong credentials, network error.
- Also shown when the DGS session has expired and silent re-login fails (e.g. password
  changed on the server).
- Advanced (may live only in Settings in the MVP): server URL, defaulting to production
  DGS. Rationale: the DragonGoApp precedent of pointing at a dev/test DGS instance.

**2. Games — "Your move"** (default tab of the Games destination; the home screen)
- Fetch starts on app start and on tab open; pull-to-refresh.
- **Sort: most recent move first** (the game where someone moved most recently is at the
  top), keyed on the `time_lastmove` the QuickSuite game list carries for every game.
- Row content: opponent name (and rank), board size / handicap, remaining thinking time,
  move count, optionally a small board thumbnail. Remaining time is the only true urgency
  signal — give it visual weight.
- States: loading, empty ("No games waiting for you" — this is good news, not an error),
  and unreachable-server with a "Try again" (a full-screen state before the first load, one
  quiet line above the list afterwards).

**3. Board / game screen — the core of the MVP**
- Layout: a thin header (opponent, colors, remaining time), the board at full width, a
  minimal action row below. Nothing else on screen by default.
- Two clearly distinguished modes; the visual design must make the difference obvious:
  - **Server position** — the actual game state as it is on DGS.
  - **Trial variation** — as soon as the user places a stone, they are in a local
    variation. Stones alternate colors automatically (the user plays both sides). There is
    no separate undo, reset or "trial: N moves" indicator: the move-navigation row walks a
    variation backwards and forwards exactly as it walks the played game, tapping the newest
    stone steps behind it, and returning to the current position abandons the line. Trial
    stones are drawn like any other stone, numbered when that setting is on; with it off,
    only the newest carries the last-move mark.
- **Walking the game by tapping**: tapping the newest stone on the board steps behind it —
  the newest trial stone when a variation is shown, otherwise the most recently played move.
  Played stones are of course never removed from the game, only stepped past, so the same
  gesture walks a variation and the game itself backwards. Its mirror: tapping the point the
  game played *next* steps forward into the record, the same as the forward button. Without
  that, rewinding a move and tapping it back in was indistinguishable from proposing it as a
  what-if, and left the board holding a one-stone variation that coincided with the game —
  the server's stones, but marked as a trial and unable to claim the live position. Only that
  exact point replays, so a genuine what-if is never swallowed, and only while the cursor is
  on the played line, since once a variation branches there "forward" means into it.
- **What-ifs about the past**: a variation branches from wherever the cursor is, so after
  rewinding, tapping an empty point asks "what if I had played here instead" and replaces
  the moves that followed for as long as the line is being read. Such a line is *not*
  sendable — it answers a question the game has moved beyond — and returning to the current
  position drops it. Only a variation branching at the current state can be sent.
- **Send move**: only the *first* stone of the current variation (the user's actual move)
  is sent; all later trial stones are discarded. The confirmation states exactly what is
  sent ("Send D4?"). **Pass** is a move like any other and takes the same path, but it does
  not hold a permanent place on the board: it lives in the game-info sheet, and is promoted
  into the action row's send slot exactly when the opponent has passed and no trial stone is
  armed. **Resign** lives only in that sheet, confirmed and coloured urgent.
- After a successful send, automatically advance to the next "your move" game; if none
  remain, return to the (empty) list.
- States: sending, send failed (the variation must not be lost), stale state (the game
  changed on the server meanwhile — e.g. opponent resigned — re-fetch and inform).
- Move history navigation (step backward/forward through played moves with a move
  counter) is likely needed already in the MVP: in a weeks-long correspondence game the
  user has to be able to recover the context of the position.

**3b. Game info sheet** (opened from the board's header)
- Read-only context the board has no room for, all of it already present in the game payload
  the summary is parsed from: both players (rank, rank at the start, captures, and each
  side's own clock), board size, handicap, komi, ruleset, rated, time system, weekend clock,
  start date, and the tournament id when the game belongs to one.
- The tournament's *name and standings are not in that payload*; only `tournament_id` is. A
  ladder is not there at all. Both would need either another QuickSuite object (unverified)
  or the HTML client, so the sheet links out to the game's page on the server instead.
- Also the home of the rare actions: Pass (when it is the user's turn), "Open on <server>",
  and Resign.

**4. Settings**
- Signed-in DGS account, sign out.
- Server URL.
- Board display options: coordinates on/off, last-move marker, trial-stone numbering.
- Appearance: theme (Light / Dark / System), defaulting to System. See `docs/mockups/DESIGN.md`.
- About: version, license (MIT), source repository link, issue tracker link.

### Post-MVP screens (design should anticipate them; no implementation in the MVP)

5. **Move comment** — a text field added to the send confirmation, plus display of
   existing per-move comments / in-game messages on the board screen (DGS supports both).
6. **Resign** — *done (September 2026)*: in the game-info sheet, always confirmed
   (irreversible; must not be reachable by accident). A resigned game leaves both lists and
   the record cache, and the board closes back to the list rather than stepping the queue on,
   because resigning is a deliberate exit, not a move.
7. **Scoring at game end** — a distinct mode of the board screen: tap to mark dead
   stones, show territory, accept / reject. If the opponent disagrees, the game returns to
   normal play. Built on the QuickSuite `score` / `status_score` commands.
8. **SGF export** — one overflow-menu item on the board screen; share/save via the
   Android share sheet.
9. **Games — "Waiting for opponent"** — a second view (tab or filter) in the Games
   destination, view-only.
10. **Messages and invitations** — inbox, accept/decline invitations. Game creation stays
    "open in browser".
11. **Finished games** — archive, view-only, with SGF export.

### Explicitly out of scope for the MVP

Defer to "open in browser" links, following the DragonGoApp precedent:

- Creating new games
- Any operation that would require the HTML-form client
- Any live/real-time server support (architecture should allow it later; no implementation
  work now)

## Implementation decisions (MVP build, September 2026)

Decisions taken while building the MVP that refine or amend the sections above.

- **Toolchain**: Gradle 9.7.1, Android Gradle Plugin 9.4.0 (built-in Kotlin, so the
  `kotlin-android` plugin is not applied), Kotlin 2.4.10, Compose BOM 2026.08.00,
  compileSdk 37 (required by Compose 1.12), minSdk 26, targetSdk 36. ktlint runs through
  its CLI in a Gradle `JavaExec` task rather than a plugin, to stay clear of AGP 9 DSL
  changes. Tests use JUnit 5 through `kotlin-test`.
- **`:core-goserver` depends on `:core-game`** for the shared value types (`Point`,
  `StoneColor`, `Move`, `GameRecord`). The alternative, duplicating those types in the
  interface module, would have forced every client and the UI to convert between two
  vocabularies. `:core-game` still depends on nothing.
- **Server clients return a parsed `GameRecord`, not SGF text.** `:core-dgs` downloads the
  SGF and parses it with `:core-game`'s reader, so the UI never sees a server response.
- **Orientation: not locked, but not specially designed for either.** Correspondence Go on a
  phone is portrait work, and a square board in landscape leaves wide empty margins while the
  header, navigation and action row compete for a short viewport — a dedicated landscape
  layout is not worth the maintenance for this audience. Locking to portrait is not the
  answer either: with `targetSdk 36`, Android 16 ignores orientation restrictions on large
  screens, so a lock would hold on phones and fail on exactly the tablets and foldables where
  landscape actually happens. Instead the one layout is made to behave in any window, the
  board sizing itself to the *short* edge rather than the width. That covers foldables,
  tablets and multi-window, not just a rotated phone.
- **A narrow record prefetch, qualifying "don't call the network unless the user asked"**
  (the stance above). `GamesStore` keeps a small cache of downloaded records and warms
  exactly two things: the game at the top of "Your turn" when the list lands, and the queue's
  *next* game while the current one is on screen. Stepping through the queue — the dominant
  flow — is then instantaneous. Deliberately not a bulk sync of every waiting game: DGS
  meters requests with a quota (`quota_count`/`quota_expire` in every QuickSuite reply), and
  a player can have dozens of games they will never open in a session.
  Opening a game uses the held record when the games list (refreshed whenever it is shown)
  agrees the game has not moved on, which makes a warm game cost *no* request; when the list
  reports a higher move number the record is refetched, and the board shows its loading
  placeholder rather than a stale position. A `StaleGame` answer from the server forces a
  download outright, since there the list is known to be behind.
- **SGF parsing is a small custom reader/writer** in `:core-game` (about 150 lines). No
  maintained, permissively licensed Kotlin SGF library with a small footprint was found,
  and the DGS export uses a plain FF[4] subset.
- **QuickSuite protocol details verified against the live server** (2026-09-02):
  `quick_do.php` answers clean `application/json` (the client still tolerates leading
  text before the first `{`); errors look like `{"error":"not_logged_in", ...}`;
  `login.php?quick_mode=1` errors look like `[#Error: wrong_userid; login.find_player2(x)]`.
  The client posts the login form instead of putting the password in the URL. The
  game list uses `cmd=list&view=status&limit=all&lstyle=json&with=user_id` and moves are
  sent as SGF coordinates (`move=qd`) or `move=pass` with the `move_id` context.
  The client treats "no `[#Error:` line and a session cookie was set" as success, which has
  held against a real account in daily use.
- **DGS times are in the user's profile time zone**, which the QuickSuite does not expose.
  Bare timestamps are therefore read in the device's own zone: a player's phone is set to
  where they live, which is the zone they also chose on DGS. Reading them as UTC instead
  shifts every value by the account's offset — far enough that a move made minutes ago reads
  as lying in the future. A trailing "GMT", where it appears, is honoured as UTC. A player
  whose DGS profile zone differs from their phone's is still off by that gap; reading the
  zone from the profile page would close it.
- **Session persistence**: the app stores the DGS cookies (encrypted) and restores them on
  start; a `not_logged_in` answer triggers one silent re-login with the stored
  credentials. If that fails with a credential error, the sign-in screen explains why.
- **Navigation is hand-rolled** (two tabs plus an open game) instead of navigation-compose;
  three destinations do not justify the dependency yet. The open game is saved with the id
  of the process that opened it, so a configuration change keeps it and a relaunch after
  Android killed the app does not: the games list and the record cache are in memory only,
  so a board restored into a fresh process has no header, no grid and no list behind it —
  which is exactly the screen an offline cold start used to land on.
- **Pass and resign are placed by what they are, not by how often they are used.** Resign is
  an act *about* the game and is irreversible, so it sits in the game-info sheet behind a
  confirmation. A pass is a *move*: it takes the same confirm-and-send path as a stone, it is
  clock-sensitive, and it is how every game ends — so it is reachable in two taps from the
  sheet and is promoted into the action row's send slot when the opponent has passed and
  nothing is armed to send. Scoring is still missing, so passing into a finished game leads
  to a phase the app cannot render; rather than hide the button, the confirmation says so and
  names the server where the game can be finished.
- **The client interface gained `resign` and `gameUrl`.** `gameUrl` is the escape hatch for
  everything a server can do that Turnzen cannot yet, and keeps the UI from knowing which
  server it talks to: the URL comes from the client, not from a constant in a screen.
- **`GameDetail` carries a `GameInfo`** parsed from the *same* `cmd=info` payload as the
  summary — ruleset, komi, rated, time system, weekend clock, start date, tournament id, and
  a `PlayerDetail` per side with rank, rank at the start, captures and that side's clock.
  None of it costs a request; the fields were simply being discarded. It is deliberately not
  on `GameSummary`: the list rows never need it.
- **Failures are shown in two tones** (`ServerProblem`): unreachable and rate-limited are
  states to wait out, shown quietly with a retry; signed-out, rejected moves and protocol
  errors are errors and keep the urgent colour. Connectivity is not probed — that would
  need ACCESS_NETWORK_STATE, and the app ships with INTERNET only.
- **Package and ids**: Kotlin packages `dev.turnzen.*` (code identity, one per module:
  `.game`, `.dgs`, `.goserver`, `.app`); application id `io.github.chei.turnzen` (the
  distribution identity, reverse-DNS of the `chei.github.io` Pages subdomain the source
  lives under). The two are deliberately decoupled — the applicationId need not match the
  package namespace, and the packages need not track a domain. The source-code and issue
  links in Settings come from the `SOURCE_URL` build config field, set to
  `https://github.com/chei/turnzen`.
- **Demo server**: `FakeTurnBasedGoServerClient` lives in the app module for previews and
  for `-Pturnzen.fakeServer=true` builds, which is how the MVP was verified on an emulator.
