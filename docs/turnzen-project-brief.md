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
  - `quick_do.php?obj=game&cmd=list&view=running&uid=mine&limit=100` — all running games.
    Without `limit` DGS returns only the first 10 (the same fallback as below), so a player
    with more running games would silently miss some.
  - `quick_do.php?obj=game&cmd=list&view=finished&uid=mine&limit=N` — finished games, most
    recently finished first (`list_order=time_lastmove-`), so a small `limit` takes the tail
    of the archive rather than its beginning. `limit` is honoured only in 1..100; anything
    else silently falls back to 10. A finished entry omits `move_color`, `move_uid`,
    `move_opp` and `move_last` (nobody is to move) and carries `score` instead — the same
    format as the scoring commands' ("W+7.5", "B+R", "0" for jigo, "VOID")
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
  `owned_comments` decides whose view the file is written for: `1` a player's (their move
  messages, private parts included, plus their private game notes), `0` an observer's (public
  parts only), `N` none at all. How the messages sit in the file is under "Comments".
- **The scoring phase** (verified against `specs/quick_suite.txt`, September 2026):
  `quick_do.php?obj=game&cmd=status_score&gid=&move_id=&move=<coords>&toggle=uniq&fmt=,`
  is a *read*: it returns `ruleset`, `score` ("B+2.5", "0" for jigo, "VOID", or "W+R"/"B+T"/
  "W+F"), and the full point state — `dame`, `neutral`, `black_stones`, `white_stones`,
  `black_dead`, `white_dead`, `black_territory`, `white_territory`. The coordinates it takes
  are toggles, so the same call previews what marking a group would be worth without
  committing anything. `cmd=score` commits: with `move=<coords>` it is a *disagreement*
  (the marks change and the turn passes), with `move=&agree=1` it is agreement, and the
  spec asks for that agreement to be explicit so a client cannot agree by slip. `toggle=uniq`
  makes each named group flip exactly once (the default "all" would undo a group named
  twice). Statuses are `KOMI | SETUP | PLAY | PASS | SCORE | SCORE2 | FINISHED`, with SCORE
  the first player's scoring step and SCORE2 the other's.
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

## Online Go Server (OGS) support — design (September 2026)

Agreed in a design session on 2026-09-26 and implemented the same day (see "Implementation
notes" at the end of this section). It turns Turnzen
from "a DGS client" into a client for correspondence Go on DGS and OGS, with both servers
treated as equals. A client for more than one server is unusual, so most of the decisions
below are about letting the user tell the two apart without making a one-server user pay
for it.

### Scope

- **Correspondence only.** OGS live games (`time_control.speed` of `blitz`, `rapid` or
  `live`) are ignored: not listed, not counted. Playing a live game with Turnzen open is not
  a use case.
- **Parity with what DGS already has**, as far as OGS allows: running and finished games,
  board and replay, send move, resign, rating change of a finished game. Ladder standings
  stay DGS-only (OGS has no equivalent in this form).
- **Scoring opens on the web in the first release.** OGS's stone-removal phase is
  concurrent (both players mark at once) rather than DGS's alternating proposals, runs only
  over the websocket, and the server auto-scores for a player who is not connected — so it
  does not fit `scoreState` / `disputeScore` as they stand, and gets its own design round.
- **Non-square boards** (OGS allows e.g. 19×9) are not supported: such a game is listed,
  but its row opens the game on the web instead of the board.
- Conditional moves and game chat exist on OGS (websocket `game/conditional_moves/set`,
  `game/chat`). Conditional moves are future work; the players' chat is read and written as
  of September 2026 — see "Comments".

### Accounts and sign-in

- **At most one account per server.** DGS keeps its advanced server-URL override (the
  dev/test instance); OGS gets none until one is needed (beta.online-go.com exists).
- **OGS sign-in never touches a password**: OAuth2 authorization code with PKCE, as a
  public client (no secret). The browser (Custom Tab) shows OGS's own login page, which also
  covers users who signed up with Google/GitHub/Apple and have no OGS password. The redirect
  is a **loopback address** — the app listens on `http://127.0.0.1:<fixed port>` while the
  browser is open — because OGS rejects custom URI schemes, and the alternative (an https
  App Link) would need a hosted `assetlinks.json` listing the fingerprint of every signing
  key, F-Droid's included. If OGS turns out to refuse the loopback redirect in practice, the
  fallback is that App Link on `chei.github.io`; a password form is not. Access and refresh
  tokens last 30 days each; the token pair is the OGS session that `exportSession` persists.
- The OAuth application is registered under the maintainer's own OGS account (Chei); its
  client id is public in the repository, which PKCE makes harmless.
- DGS still signs in with username and password — it offers nothing else.
- **The DGS fields autofill with the password saved for the website.** Password managers
  look an app's fields up under its package name unless a field names a web domain, so the
  two fields report the server's `passwordDomain` (`www.dragongoserver.net`) and are marked
  as username and password. Compose cannot attach a web domain to its fields, and Bitwarden
  reads it only from the field or its direct parent, so these two are classic `EditText`s
  styled like the rest (`CredentialField`). Verified with Bitwarden; Google's autofill
  insists on Digital Asset Links, which DGS does not publish, so it keeps using the package.
- **Sign-in screen**: two panels, both servers equal. **OGS on top** — its panel is a single
  "Sign in with Online Go Server" button, so it stays visible above the DGS form when the
  keyboard opens; the DGS panel below keeps its form. Each panel shows its own state
  (signed out / signing in / signed in as X, with sign out) and its registration link. A
  "Continue" action becomes available once at least one server is signed in; the user signs
  in to both, or to one and continues. The screen appears only when no server is signed in;
  Settings reuses the same two panels to add or remove the other server later.
- **An expired session on one server does not bounce the user to sign-in.** The games list
  keeps the other server's games and shows one quiet line: "Online Go Server signed you out ·
  Sign in again".

### Telling the servers apart

- **Each server has a colour**, taken from its own web presence: OGS a light blue, DGS the
  light sand of its web pages. Both need muted dark-theme variants. Values are to be chosen
  in `DESIGN.md` against the existing tokens, because both sit close to something already
  there: the light blue next to `accentSoft` (the refresh wash), the sand next to `board`.
- **The colour is a 4dp rounded bar at the leading edge of every game row**, in the server's
  colour — never a row background, so it competes neither with the refresh wash nor with the
  board. It is shown **always**, even when only one server is signed in; with a single server
  it reads as a design accent rather than a label.
- **The same bar or dot marks a server everywhere** a game or account appears: the Running
  and Finished tabs, the board screen (a dot beside the opponent's name), the game info sheet,
  the sign-in panels, the Settings account sections, and error lines.
- **Colour is never the only carrier**: the server's name is written out once per screen
  where it fits naturally — the sign-in panels, the Settings sections, the board's info line
  ("Online Go Server · game 1234567") — so the mapping is learnt there; list rows carry the
  colour only, plus a content description ("on Online Go Server") for TalkBack.

### Games list

- **One merged list per tab**, sorted by last move across servers as today — the question
  the list answers is "what do I have to play", not "what is on DGS". No per-server
  sections, no server filter.
- **One server failing does not hide the other**: the games that loaded are shown, with a
  quiet line naming the server that did not answer ("Online Go Server didn't answer · Try
  again"). The full-screen unreachable state appears only when every signed-in server fails.

### Formatting

- **Remaining time is in the app's language for both servers**, through the same formatter
  (`Formatting.remaining`, "3 d 20 h"). DGS sends it localised and the DGS client parses it
  back into a duration; OGS sends raw clocks (epoch-ms `last_move` / `expiration`, per-player
  seconds per time system: fischer, byoyomi, canadian, simple, absolute, none), from which
  the OGS client computes the duration. The byo-yomi / increment part ("+ 1 d × 10") needs
  the same treatment — today it is still DGS's own text. Paused games (`pause_control`:
  vacation, weekend) must not look like running clocks.
- **Ranks are shown each server's own way**: OGS as its website shows them — whole ranks
  ("7k", "2d") computed from the rating, "?" when provisional — and DGS as the server sends
  them. The two styles differ slightly; neither is rewritten to match the other.

### Settings

- One section per signed-in-or-not server, each with its colour mark and name: signed in as
  X / sign out (or sign in).
  - DGS: plus the server URL.
  - OGS: plus "Open on the web" for the account; vacation and everything else stays on the
    web.

### Architecture

- **The UI knows *which* server, never *how* to talk to it.** `TurnBasedGoServerClient`
  gains an opaque `serverId` ("dgs", "ogs"); the app maps it to a colour token and a display
  name. The colour is a design token and lives in `DESIGN.md` / the app, not in the
  interface. (This amends the "UI never knows which server it talks to" rule in CLAUDE.md.)
- **Games are keyed by (server, game id)** — OGS game ids are integers and can collide with
  DGS ids. The app holds **one client per signed-in server**; the games ViewModel fans out to
  each and merges, and the board screen is handed the game's own client. A combined client
  hiding both servers behind one `TurnBasedGoServerClient` was rejected: it would hide which
  server failed, and the UI needs the server for the colour anyway.
- **`:core-ogs`**, a new pure-Kotlin module implementing `TurnBasedGoServerClient`, depending
  on `:core-goserver` only — same shape as `:core-dgs`.
- **OGS needs its websocket even for correspondence.** Resign (and stone removal) exist only
  there; moves have a REST endpoint (`POST /api/v1/games/{id}/move/`) that appeared recently
  and whose request body is unverified. `:core-ogs` therefore opens a short-lived connection
  for an action — fetch a `user_jwt` from `/api/v1/ui/config/`, `authenticate`,
  `game/connect`, `game/move` or `game/resign`, `game/disconnect` — behind the existing
  suspend functions. Pull-based stays true above the module boundary. Holding a connection
  for the scoring phase is part of the scoring design round.
- Useful endpoints found so far: `/api/v1/ui/overview/` (active games with full game data;
  whose turn it is from `clock.current_player`), `/api/v1/players/{id}/game_history/`
  (finished games), and `/termination-api/player/{id}/v5-rating-history` (TSV of rating per
  game, from which a per-game rating change is the difference of consecutive rows —
  undocumented, so a failure there simply leaves the rating change out). No published rate
  limits; the `authenticate` message carries `client` / `client_version`, which Turnzen fills
  in as it does its DGS User-Agent.
- **Test fixtures** are recorded from the maintainer's OGS account (registered September
  2026): currently one game won on time and several running games, none awaiting a move — a
  "my turn" capture waits until an opponent moves. Same scrubbing rules as for DGS.
- The demo server (`-Pturnzen.fakeServer=true`) gets a fake OGS alongside the fake DGS, so
  mixed lists can be seen without accounts.

### Distribution consequence

F-Droid will probably add the **NonFreeNet** anti-feature label, because OGS's backend is
not free software (DGS's is AGPL). Accepted: it is a disclosure label, not a rejection.

### Implementation notes (26 September 2026)

- **`GameId` carries its server**: `GameId(server: ServerId, value)`. This is the "(server,
  game id)" key; it keeps every existing `GameId` signature in the app, and a saved open game
  is stored as `"server:id"`.
- **Interface additions** in `TurnBasedGoServerClient`: `serverId`, `signInMethod`
  (`PASSWORD` / `BROWSER`) with `signInInBrowser(openPage)`, `scoresInApp`, `accountUrl()`;
  `GameSummary` gains `boardHeight` and `onlyOnWeb`.
- **App**: `Accounts` holds one `SessionManager` per server (OGS first). The DGS session keeps
  the original secrets file, so an existing sign-in survives the update; OGS has its own.
  `SessionManager` re-persists the session after every call, because OGS renews its tokens
  during ordinary calls. A lapsed OGS session signs that server out with a reason instead of
  retrying (there is no password to retry with). `GamesStore` fans out over the signed-in
  sessions, merges, and returns failures per server instead of throwing.
- **OGS client**: overview for running games (live speeds dropped), the public game list for
  finished ones (live ones dropped, then the tab's fifteen across both servers), rating
  changes from the rating-history TSV in ranks. Moves and resignations go over a realtime
  connection opened for the one action; a move whose game has moved on since (move count ≠
  token) is refused as `StaleGame` before it is sent. An **annulled** OGS game keeps its
  winner — OGS annuls early timeouts, which only makes them unrated.
- **Redirect port 8735**, path `/ogs/signed-in`. The listener answers with a redirect straight
  on to `turnzen://signed-in` (as an intent URL naming the package), which MainActivity accepts
  as a BROWSABLE link (a launcher-style intent URL is refused by browsers and ends up in the
  Play Store). There is no "signed in" page to tap through: the browser follows a redirect to
  an app because the navigation chain began with the user's tap on OGS's authorize button.
  (The first releases served a page with a "Back to Turnzen" link instead.) MainActivity
  is `singleTask`, so returning closes the browser page left in Turnzen's task. That needs the
  page to be *in* Turnzen's task, so the sign-in page opens in a Custom Tab (`androidx.browser`),
  not the browser app: the first release used a plain `ACTION_VIEW`, and the finished sign-in
  page stayed open among the browser's tabs.
- **The app is kept out of Android's freezer while the browser is in front.** Seconds after
  going to the background the process is frozen, and the loopback listener cannot answer the
  redirect — the browser hangs on it. `SignInKeepAwakeService`, a foreground service of the
  "short service" type (at most three minutes), runs for exactly as long as the sign-in waits.
  This adds `FOREGROUND_SERVICE` to the permissions (granted without a prompt), which the user
  accepted over the alternative of switching back to the app by hand.
- **Client id**: `BuildConfig.OGS_CLIENT_ID`, the application registered under Chei and
  committed in `app-android/build.gradle.kts` (public; `-Pturnzen.ogsClientId=…` overrides
  it for a fork). A build with an empty id shows the OGS panel with the button disabled and a
  line saying this build cannot sign in.
- **Fixtures** in `core-ogs/src/test/resources/ogs/` are Chei's public games with players,
  ids and icons scrubbed (`turnzen-test` is id 1001). `overview.json` is assembled from real
  game data in the documented overview shape, because the overview needs a token to record;
  replace it with a real capture after the first sign-in.
- **Not verified against the live server yet**: the OAuth flow itself,
  loopback acceptance, the overview's exact shape, the realtime `authenticate` reply, and the
  rating-history columns.
- The mockup review before UI code was skipped: the user asked for the work to continue
  without waiting. The UI follows the mockups in `docs/mockups/05–08`.

### Order of work

1. Mockups first (`generate_mockups.py`, light and dark): the games list with mixed rows,
   the sign-in screen in its states (nothing signed in / one / both), Settings with both
   servers — plus proposed values for the two server colours. Reviewed before any UI code.
2. In parallel, `:core-ogs` protocol work that does not depend on the look: OAuth with
   loopback, overview parsing, clock-to-duration, rank formatting, websocket send/resign.
3. App wiring (multi-client ViewModels, server-keyed games), then the UI to the approved
   mockups.

## Comments (September 2026)

Post-MVP item 5, on both servers at once. The user asked for it in two steps — the shared
model, DGS and the board first, OGS second — and both are done.

### What the servers have

The two servers mean different things by a comment, and the model is cut to fit both:

- **DGS: a message belongs to a move.** QuickSuite's `move` (and `pass`, `resign`, `score`,
  `set_handicap`) take an optional `msg`; the server stores it with that move. It can only be
  written by the player to move, together with the move. It is read back from the SGF
  download, as the `C` of that move's node — bare text, no name in front, in a two-player
  game. Parts of a message can be addressed differently with tags written into the text:
  `<c>…</c>` (`<comment>`) public, `<h>…</h>` (`<hidden>`) the writer's alone until the game
  ends and public after, `<m>…</m>` (`<mysecret>`) the writer's alone until the end and then
  the opponent's too, never observers'. Untagged text is for the two players. The server has
  already removed what the viewer may not read; what is left keeps its tags. Found by reading
  the server's SGF builder and game-comment helper (AGPL, read only; nothing copied).
- **OGS: chat is its own stream beside the game.** A line is sent with the realtime
  `game/chat` command (`game_id`, `type`, `move_number`, `body`), whoever's turn it is, and
  arrives as a `game/<id>/chat` event (`{channel, line: {chat_id, body, date, move_number,
  player_id, username}}`). There is no REST call for one game's chat: joining the game with
  `game/connect {chat: true}` makes the server replay the history as chat events, with no
  marker for where the replay ends and no promise that it comes before or after `gamedata`.
  Channels: `main` (the players; observers read it), `malkovich` (hidden from the opponent
  until the end), `personal` (a note to self), `spectator` (observers). Read from the
  protocol types of the goban library (Apache-2.0) and the OGS web client (AGPL, read only).

### Decisions

- **One shared shape**: `GameComment(afterMove, by, text, visibility)`, pinned to a move
  count of the record (0 = before the first move), written by one of the two players.
  `CommentVisibility` names the reader's situation — `EVERYONE`, `PLAYERS`,
  `HIDDEN_UNTIL_END`, `SECRET_UNTIL_END`, `WRITER_ONLY` — and each server maps onto it. A DGS
  message with tagged parts becomes several comments in the order written.
- **Only the players' own words.** DGS's scoring summary on the result node, the root
  comment of a shape game and the appended game notes are dropped; OGS observers (the
  `spectator` channel, and anyone but the two players in `main`) are left out. Showing
  observers' chat would be a separate, deliberate feature.
- **Reading: free where it is free, asked for where it costs.** `GameDetail.comments` is
  filled by DGS, whose messages come in the SGF already downloaded. OGS leaves it null and
  `TurnBasedGoServerClient.comments(id)` fetches them: a realtime connection per game
  opening, which is why it is not part of `game()`. The board asks for them after the game
  loads, every time it is opened (a chat line can arrive without a move), and a failure shows
  nothing rather than a problem — they are decoration to the job of the screen.
- **The end of the OGS replay is found by silence**: after joining, the client reads until
  the socket has been quiet for a second (at most eight). A correspondence game sends no
  clock ticks to break that silence. A `reset-chats` event discards what came before; lines
  are de-duplicated by `chat_id`.
- **Writing: a comment goes with a move**, typed in a field above the board's action row
  ("Add a comment" opens it) and sent with the next Send. Pass and resign are reached from
  the info sheet, away from that field, so their confirmation dialogs hold a comment field of
  their own, filled in from the board's: what the dialog shows is what goes, and cancelling
  leaves the board's draft alone. `sendMove(…, comment)` and `resign(…, comment)`. On DGS
  it is the move's `msg`, in the same request. On OGS it is a `main` chat line sent right
  after the server has echoed the move, with `move_number` = the move just played, and the
  client waits (at most ten seconds) for it to come back. The move is already made by then,
  so a chat line that fails or does not come back is not reported as a failed send. A resignation's comment is the DGS `resign` command's `msg`; on OGS it is
  a `main` chat line sent *before* `game/resign`, pinned to the last move, so it is said while
  the game is still being played. Off-turn chat (possible on OGS only) is not offered yet.
- **DGS's own view needs `owned_comments=1`**, which also appends the user's private game
  notes to the last node as `Notes - <name as in PB/PW>:`. They are cut off under the user's
  own name only, so a message that happens to contain the words stays whole.

### Not verified against the live servers

Neither server could be reached from the environment this was built in, so the fixtures
are written from the sources above rather than recorded: `game_1000001_comments.sgf` in
`:core-dgs`, and the chat frames in `OgsClientTest`. To confirm with real captures: DGS's
notes block and tag handling; on OGS, that a replay really arrives within the quiet window,
the exact `move_number` convention in handicap games, and that a player's own `main` line is
echoed back to them.

## Notifications (September 2026)

Correspondence play means waiting days for a move, so the app says when one has arrived. It
is the one exception to "no network unless the user asked": the user asks by switching a
notification on, and both switches start off. Specified in a design interview on
26 September 2026.

### What is shown

- **Your move**: one summary notification for every game on both servers, never one per
  game or per server. It alerts (sound, channel at default importance, no pop-up) only when a
  game has *newly* become the user's move since the last check; otherwise it changes
  silently. Title "Your move in N games" (one game: "Your move against X"); one line per game
  — opponent, the user's remaining time only when under a day, server; the games that caused
  the latest alerts come first, marked "new" unless every game is. Plain text: Android drops
  colour and weight from the text of an ordinary notification, so the server is named rather
  than shown in its colour, and "new" is a word rather than bold. Tapping opens that game's board
  when there is exactly one, the "Your turn" list otherwise. The launcher dot comes with it
  (Pixel shows only a dot; the count is in the long-press menu); Android has no badge-number
  API of its own.
- **Time running low**: one notification per game, on its own channel at high importance
  (sound and a pop-up), when the user's own remaining main time is at most **24 hours**
  (hardcoded: urgent in correspondence terms, still actionable after a night's sleep). The
  text names opponent, time left and server. Once per game; it can fire again only after the
  user has moved in that game. Tapping opens the board. The opponent's clock is never
  watched.
- **Signed out**: when a check finds a server signed the user out and the app cannot sign in
  again by itself, one "Turnzen can't check X — sign in again" notification, once per
  sign-out.
- **Opening the app clears everything** on any screen coming to the foreground, and every
  game that is the user's move then counts as seen: it does not alert again. A game that
  stops being the user's move (played on the website, ended) is removed silently; at zero
  games the notification goes.
- Not notified: finished games, messages, comments (messages later, with the messages
  feature). No quiet hours of Turnzen's own: that is Do Not Disturb / Bedtime mode, which
  silences everything alike; Android offers no way to read the user's bedtime.

### How it checks

- Neither server pushes to third-party apps, so a WorkManager periodic job asks every
  signed-in server for the games awaiting the user (the same call as the Games screen),
  with a network constraint (any network — a check is a few kilobytes). Interval in Settings:
  15 min, 30 min (default), 1 h or 3 h; it runs while either switch is on.
- The low-time moment is not polled for. The user's clock runs only on their turn, so a
  check that sees "3 d left" knows the crossing is two days away and schedules a one-off job
  for it (at least 15 minutes out, so a paused clock does not spin). That job is a full check
  like any other, so a clock paused for the weekend or a move made on the website simply
  reschedules or drops it. Deep sleep can delay it by up to about an hour; on a 24-hour
  warning that is fine. DGS rounds remaining time up to the hour, so its value is an upper
  bound and "at most 24 h" is the test. A game that becomes the user's move with less than a
  day already left is caught by the next periodic check.
- A check while the app is in the foreground posts nothing and records what it saw as seen;
  so does every Games-screen refresh.
- What the notification logic decides is plain Kotlin (`TurnWatch`), tested on the JVM; the
  worker only gathers the games and posts what it is told.

### Settings and permissions

- A Notifications group: "Your move" and "Time running low" switches, and "Check every"
  (hidden while both are off). Turning a switch on asks for POST_NOTIFICATIONS (Android 13+)
  there and then — never at first launch. If notifications are blocked (denied, revoked, or
  switched off for the app), the group says so and links to Android's notification settings.
- Permissions grow by POST_NOTIFICATIONS, plus what WorkManager declares for itself:
  ACCESS_NETWORK_STATE, WAKE_LOCK and RECEIVE_BOOT_COMPLETED (scheduled checks survive a
  restart). All for notifications only.

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
   handling in one app. OGS correspondence support is now designed; see "Online Go Server
   (OGS) support" above.
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

## Beyond Go: other turn-based games (assessed September 2026)

Asked on 2026-09-29: should Turnzen also cover correspondence chess (Lichess, Chess.com)?
**Decision: not now.** Turnzen stays a Go client. If another game is ever added, it is
Lichess chess only, and it starts as the read-only inbox described below, not as full play.

### Why this is a bigger step than a second Go server

OGS was a second *server* for the same game. Chess would be a second *game*, and Go is
built into every layer above the server modules:

- **`:core-game`** is Go rules (captures, ko, scoring), SGF and the trial-variation model.
  Chess needs its own rules module: legal moves, castling, en passant, promotion, check and
  mate, repetition and the fifty-move rule, and PGN/FEN instead of SGF.
- **`:core-goserver`** speaks `Point`, `StoneColor` and Go `Move`, and has Go concepts in
  it (scoring, ladders). Making every type generic over the game would be a large refactor
  with little payoff. A chess server would get its own parallel interface
  (`TurnBasedChessServerClient`) instead.
- **The game screen**, which is most of the app's work, is Go-specific: the board drawing,
  tap-to-place trial variations, "Send sends the first stone of the variation", captures and
  scoring. Chess needs its own board with piece graphics, drag or tap-tap input and a
  promotion picker.

Several pieces would carry over: the games list and "Your turn" queue, the OAuth/PKCE
loopback sign-in built for OGS (Lichess uses the same flow), the WorkManager notifications,
session storage, the design tokens, and the idea that a game belongs to an opaque
`serverId`.

### The servers

- **Lichess**: technically a good fit. It has an official, free API, and its Board API lets
  third-party apps play correspondence games, with OAuth PKCE sign-in and an event stream
  for pending moves.
- **Chess.com**: ruled out. Its public API is read-only, with no official way to submit a
  move in a daily game. Supporting it would mean reverse-engineering private endpoints,
  which risks breaking without notice and may violate their terms.

### Why the case is weaker than for Go

- The project exists because the DGS Android clients are outdated and hard to use (see
  Goal). Chess has no such gap: Lichess's own mobile app is modern, free, open source and
  handles correspondence well.
- The licensing rule is the same as for the Go projects under Prior art. Lichess mobile is
  GPL and the Lichess server is AGPL, so they may be read to understand the protocol, never
  copied. Stockfish (GPL) is out too. Chess rules would be written independently or come
  from a permissively licensed library.
- The Go roadmap still has open post-MVP items (comments, scoring, messages, live play).

### The cheap version, if it is ever wanted

The real value for a player of both games is **one "your turn" list across Go and chess**,
not a second full game client:

1. A `:core-lichess` module that only reads the games waiting on the user's move. It
   depends on its own small interface rather than on `TurnBasedGoServerClient`.
2. Those games appear in the existing inbox with Lichess's colour and name, and count
   towards notifications.
3. Tapping one opens it in the Lichess app or website; Turnzen never plays a chess move.

That reuses the list, notifications and sign-in, needs no chess rules or board, and keeps
the rule that the UI never touches server specifics. Full chess play would be considered
only if this is used a lot. The name already allows for it: an invented name was chosen
partly so the project could grow beyond Go (see Naming).

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
  is the explicit Send action, which names the move and sits at the bottom of the screen,
  away from the board (see "Send move").
- **The board view is as clean as possible.** Outside the board itself, show only what is
  strictly necessary. No decorative chrome, no persistent toolbars that compete with the
  board.
- **Board sizes 9×9, 13×13, 19×19 are all first-class. The board always fills the full
  screen width**, square, with grid spacing derived from the size. Layout above and below
  the board must adapt to the remaining vertical space rather than assuming a fixed board
  height.

### MVP screens

**1. Sign-in**
(Superseded once OGS lands by the two-panel sign-in in "Online Go Server (OGS) support".)
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
- **A third tab, "Finished"** (September 2026), showing the fifteen most recently finished
  games — a fixed number, scrolling like the other tabs. Sizing it to the screen instead was
  tried and dropped: the count then means something different on every device, and hiding
  games already fetched buys nothing. It is still a glance at how the last games went rather
  than an archive to browse — no paging, no search — and the tab carries no count, since
  fifteen would only ever be the limit. Rows put the outcome where a running game has its
  clock ("Won by 12.5"), with what the game did to the reader's rating under it.
- The archive is fetched from `view=finished` the first time the tab is opened and then left
  alone — a finished game does not change, so polling it with every refresh would spend the
  server's quota on an answer already known. Ending a game (a resignation, an accepted
  score) drops the held list, so the next visit fetches one that includes it.
- Opening a finished game is the board screen in read-only form: it replays, it takes trial
  stones (a what-if on a game that is over is exactly what a player wants), and it offers no
  Send, Pass, Resign or Skip. Its header carries the result instead of a clock, and the info
  sheet gains "Ended" and "Result" rows while losing the resign action.
- **What the game did to the rating**, under the outcome, and in the info sheet for both
  players, in the corner their clocks had. DGS reports it only when the request asks
  (`with=ratingdiff`, on `cmd=info` and on the finished list) and only for finished games.
- **The unit is ranks, shown to two places: "rating +0.23".** DGS counts a rating move in
  hundredths of a stone — a player at 4k exactly who wins +0.23 stands at 4k +0.23, and two
  more such wins put them past 3k. Those hundredths are the whole of what a single game is
  worth, so they are never rounded away.
- **The figure is DGS's own `rating_diff`, never the gap between `rating_start` and
  `rating_end`.** Those two are the ranks the player held at the two ends of *this game*, and
  a correspondence game runs for months: the gap between them is mostly other games. Showing
  it as this game's doing produced "3k → 4k" on an unrated ladder game, which is how the
  mistake was caught.
- **An unrated game gets no line at all** — not "±0.00", which reads like a rounding error
  rather than "this game was never going to move anything".
- **A ladder position change is not shown, and cannot honestly be.** The ladder page carries
  today's standings, not the standings as they were when a given game ended, and a challenge
  settled weeks ago has had every game since moved through it. See
  [`dgs-ladder-standings.md`](dgs-ladder-standings.md) — the same reason the running-game
  standing is fetched fresh and shown only for a live challenge.

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
  is sent; all later trial stones are discarded. The button states exactly what is sent
  ("Send D4") and one tap sends it, at once — **no confirmation dialog and no undo**
  (October 2026). The Skip / Send row sits at the bottom edge of the screen, as far from the
  board as the layout allows, so a slip off the board or the move navigation cannot land on
  it. A dialog everyone learns to tap through protects little, and the September design's
  four-second undo window cost more than it saved: a user who left the app inside those
  seconds had the move sent just as Android froze or cut off the backgrounded process, so it
  regularly failed and came back as "wasn't sent".
  **Pass** is a move like any other and takes the same path, but it does not hold a
  permanent place on the board: it lives in the game-info sheet, and is promoted into the
  action row's send slot exactly when the opponent has passed and no trial stone is armed.
  It keeps a confirmation, which is also where its comment is written (see "Comments"), and
  which says only that a pass after the opponent's ends the game; after it, it is sent like
  a stone. **Resign** lives only in that sheet, confirmed and coloured urgent.
- On Send, advance straight to the next "your move" game; if none remain, return to the
  (empty) list. No pause first: stepping on is what says the move went.
- States: sending, send failed, stale state (the game changed on
  the server meanwhile — e.g. opponent resigned). A failure is reported wherever the user
  is by then — "D4 to kaya_r wasn't sent · Open" — and puts the game back in the queue;
  opening it restores the variation and the comment, with the problem line, so Send is the
  retry. A stale game reopens on the real position with "The game changed on the server".
- Move history navigation (step backward/forward through played moves with a move
  counter) is likely needed already in the MVP: in a weeks-long correspondence game the
  user has to be able to recover the context of the position.
- **The event line** (September 2026): one reserved line under the board naming what happened
  at the cursor when it leaves no stone — "You passed", "kaya_r resigned", "nori_88 ran out of
  time". Without it the board goes silent exactly where it most needs explaining: a game that
  was counted ends in two passes, so stepping back through it moves the counter and changes
  nothing three times over, and in a running game the opponent's pass is the whole reason a
  Pass button has appeared. The slot keeps its height when empty, so scrubbing never shifts
  the navigation row.
- **A finished game opens on its last stone, not its last move**: the trailing passes that
  took it into counting are stepped over on the way in, so the first Back rewinds something
  visible. Forward still walks into them, now with the event line to say what they are.

**3b. Game info sheet** (opened from the board's header)
- Read-only context the board has no room for, all of it already present in the game payload
  the summary is parsed from: both players (rank, rank at the start, captures, and each
  side's own clock), board size, handicap, how long the game has been running, and what it
  is being played for.
- **A player's display name** sits under their handle, quieter, when the server has one and it
  is more than the handle again (blank, or the same letters in another case, is dropped). DGS
  keeps a free-text name beside the login handle; OGS has only `username`, so an OGS sheet shows
  the handle alone. The handle stays the title and stays the only name everywhere else (list,
  board header, notifications): it is what identifies a player on the server, and the name is
  something looked up once.
- **Only what a player would look up, not everything parsed.** Komi, ruleset, rated, the time
  system and the weekend clock were all shown at first and were all removed (September 2026):
  every one of them is fixed when the game is created, so the sheet answered a question nobody
  asks twice. `GameInfo` still carries them — the scoring screen needs `komi`, and they cost
  nothing to parse — the sheet simply no longer spends a row on them.
- The start date is shown as elapsed time ("13 days ago", "432 days ago"), not as a date: what
  a player wants from it is how long the game has been running. It counts in days for ever and
  never rolls over into months or years — the exact day count is the information; "1 year ago"
  discards most of it. `Formatting.since` is deliberately separate from `Formatting.relative`,
  which serves the last move and gives up for a calendar date after a fortnight.
- **What the game is part of is one row**, and the label carries the category so the value
  stays short: `Ladder  #3 · rated`, `Tournament  #365 · <name>`, or, for a game belonging to
  no tournament, `Game  Friendly`. A ladder is identified by its *number*,
  and nothing else it is called is worth a character: "DGS Ladder (19x19)" is the word Ladder
  again, the server's own name, and the board size the row above already gives. Rated is the
  exception — it is what tells two otherwise identical ladders apart — and it is shown in both
  states, since "rated" alone would make an unrated ladder look like missing data. An ordinary
  tournament keeps its name; that one a person chose. The kind ("Dragon Ladder", "Private Round
  Robin") is never shown: for a ladder it repeats the label, otherwise it repeats the name.
- **"Friendly"** for a game in no competition, in the football and chess sense of a match that
  counts for nothing beyond itself. It was first written as "Waiting room or invitation", which
  tried to be exhaustive about an origin the server never stored, and read as uncertainty. DGS
  keeps nothing on the game that tells a waiting-room pairing from an accepted invitation — not
  `game_type` (always `GO` for a normal game), not `flags`, not `shape_id` — so the row says
  what the game *is* rather than how it came about. Not "casual", which players read as unrated,
  and a friendly game on DGS is usually rated. The row is omitted entirely when the server did
  not describe the game at all (an offline reopen), where any of these words would be a guess.
- The tournament's *name is not in that payload*; only `tournament_id` is. It is in the SGF,
  which the client downloads for the moves anyway: DGS writes the standard SGF header
  properties `EV[(Dragon Ladder) Tournament #3: DGS Ladder (19x19)]` and `RO[Round 1 of 1]`
  for a tournament game and omits both for a casual one. The bracketed prefix is the kind —
  scope plus format, e.g. "Dragon Ladder", "Private Round Robin" — which is also how a ladder
  game is told apart from a bracket or league one. Ladders on DGS *are* tournaments: they
  share the id space and the `tournament_id` field.
- The *standings reach no API at all*: there is no tournament object in the QuickSuite
  (section 1 of `quick_suite.txt` lists `tourney` as a server section, but section 3
  specifies commands for game, user, message, folder, contact, wroom, bulletin and tag only,
  and nothing for tournaments is implemented). A ladder position — "you are #4, challenging
  #1" — exists only on the login-gated ladder page, `tournaments/ladder/view.php?tid=N`, and
  so needs the HTML client. **Researched in full and implemented (September 2026); the
  traps are in [`dgs-ladder-standings.md`](dgs-ladder-standings.md).** The Standing row is
  appended after the sheet is already on screen, because it is the one row that costs a
  request; the sheet also still links out to the game's page on the server.
- Also the home of the rare actions: Pass (when it is the user's turn), "Open on <server>",
  and Resign.

**4. Settings**
- Signed-in DGS account, sign out. (With OGS: one section per server — see "Online Go
  Server (OGS) support".)
- Server URL.
- Board display options: coordinates on/off, last-move marker, trial-stone numbering.
- Appearance: theme (Light / Dark / System), defaulting to System. See `docs/mockups/DESIGN.md`.
- About: version, license (MIT), source repository link, issue tracker link.

### Post-MVP screens (design should anticipate them; no implementation in the MVP)

5. **Move comment** — *done (September 2026)*: a text field above the action row that goes
   with the next move, and the players' comments at the move on show above the action row, on DGS
   and OGS alike. See "Comments".
6. **Resign** — *done (September 2026)*: in the game-info sheet, always confirmed
   (irreversible; must not be reachable by accident). A resigned game leaves both lists and
   the record cache, and the board closes back to the list rather than stepping the queue on,
   because resigning is a deliberate exit, not a move. Its dialog takes an optional comment
   (October 2026), as the pass dialog does.
7. **Scoring at game end** — *done (September 2026)*: a distinct mode of the board screen.
   Tapping marks a group dead, territory and the count are shown live, and the user either
   sends their marks (a disagreement, which passes the turn to the opponent) or accepts the
   score. Built on the QuickSuite `status_score` / `score` commands.
   Not yet: **resuming play** from a disagreement (DGS allows an ordinary `cmd=move` out of
   the scoring phase), and the game-over screen — an accepted score leaves the game list and
   the result is read on the web.
8. **SGF export** — one overflow-menu item on the board screen; share/save via the
   Android share sheet.
9. **Games — "Waiting for opponent"** — a second view (tab or filter) in the Games
   destination, view-only.
10. **Messages and invitations** — inbox, accept/decline invitations. Game creation stays
    "open in browser".
11. **Finished games** — *the recent tail is done (September 2026)*: the "Finished" tab on
    the Games screen, described under screen 2 above, plus the read-only board it opens. Not
    yet: a full browsable archive (paging past what fits on one screen, searching or
    filtering it) and SGF export, which is item 8 and serves running games just as well.
12. **Ladder standings in the game info sheet** — *done (September 2026)*: a running ladder
    challenge shows "You #153 → #108" under the ladder's name and format. The first thing in
    the project to need the HTML client, because the ranks exist in no API: they are read from
    the ladder page, cached per ladder on disk for a day, and fetched only when the sheet is
    opened on a ladder game, so the sheet is never held up by them. All of the research — the
    page, the markup anchors, the extraction, the traps — is in
    [`dgs-ladder-standings.md`](dgs-ladder-standings.md). No other DGS client shows this, the
    web site included.

### Explicitly out of scope for the MVP

Defer to "open in browser" links, following the DragonGoApp precedent:

- Creating new games
- Any operation that would require the HTML-form client (post-MVP item 12, ladder standings,
  was the first thing to cross this line, after the MVP)
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
- **Orientation: not locked; wide windows get a side-by-side layout.** Locking to portrait is
  not an option: with `targetSdk 36`, Android 16 ignores orientation restrictions on large
  screens, so a lock would hold on phones and fail on exactly the tablets and foldables where
  landscape actually happens. The first version kept one stacked layout for every window,
  the board sizing itself to the *short* edge. On a phone turned sideways that left the board
  small and the window scrolling, because the header, the navigation row, the action row and
  the bottom bar all came out of the short edge too. So a window wider than it is tall now
  (a) moves the Games / Settings tabs from the bottom bar to a navigation rail on the left
  edge, and (b) on the board screen, when a full-height board still leaves at least 220dp
  beside it, puts the board on the left at the full height and the header, move navigation,
  comment and Skip / Send in a scrolling column to its right. The games list drops its wordmark
  title and, in a window wide enough for two 340dp rows, shows its games in two columns. A
  wide window also hides the status bar (a swipe from the top brings it back briefly): its
  strip is height the board can use, and the clock is not needed in view. With a game open
  it hides the navigation bar too, so the board has the full height of the screen. Narrower windows (portrait,
  split screen) keep the stacked layout unchanged.
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
  The move number is not the whole game, though: the clock of the player to move runs while
  it stands still, and on a slow game a held record can be days old (one cached at 12 h
  showed 12 h on the board after the list had reached 3 h, 2026-10-01). So a held record
  takes the user's clock from the list, which carries it, at no cost; and on the opponent's
  turn, opening the info sheet fetches the game once for *their* running clock, which no
  list carries.
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
  confirmation. A pass is a *move*: it takes the same send path as a stone, it is
  clock-sensitive, and it is how every game ends — so it is reachable in two taps from the
  sheet and is promoted into the action row's send slot when the opponent has passed and
  nothing is armed to send. The confirmations are short: "Pass?" alone, or, after the
  opponent's pass, that this one ends the game and starts counting — on a server Turnzen
  cannot count for (OGS for now), that the counting is done on its website, named;
  "Resign?" alone, its urgent confirm button saying the rest.
- **Sent moves go through an app-wide outbox** (`MoveOutbox`, September 2026), not the
  board's view model, because the board has stepped on before the server answers. The move
  is transmitted at once (the undo window it first had was dropped in October 2026, see
  "Send move"). While it is on the wire, `GamesStore` shows its game as played (out of "Your
  turn", not-your-turn in "All games"), so a refresh meanwhile does not put it back in the
  queue; opening such a game waits for the answer and shows the result. A move that fails
  is kept in the outbox until its game is opened again, which puts it back on the board.
  Scoring marks and resigning stay on the confirm-then-send path.
- **The client interface gained `resign` and `gameUrl`.** `gameUrl` is the escape hatch for
  everything a server can do that Turnzen cannot yet, and keeps the UI from knowing which
  server it talks to: the URL comes from the client, not from a constant in a screen.
- **`GameDetail` carries a `GameInfo`** parsed from the *same* `cmd=info` payload as the
  summary — ruleset, komi, rated, time system, weekend clock, start date, and a
  `PlayerDetail` per side with rank, rank at the start, captures and that side's clock —
  plus a `TournamentRef` merged in from the SGF's `EV`/`RO` header (id, name, kind, whether
  it is a ladder, round of how many). None of it costs a request; the fields were simply
  being discarded. It is deliberately not on `GameSummary`: the list rows never need it.
  `isLadder` is read out of the kind wording, and measuring DGS's published translation dump
  settled that this is safe: of its 38 languages, none translates `Ladder#T_type` or
  `Dragon#T_scope`, so every account receives "(Dragon Ladder)" whatever its interface
  language — which is why a Hungarian account sees an English kind inside a Hungarian page.
  The parser stays tolerant anyway (an unreadable event line keeps the id and drops the rest),
  because that is a property of DGS's translation data, not of its code. See
  [`dgs-ladder-standings.md`](dgs-ladder-standings.md) §1 and §5.
- **Counting is done twice, on purpose.** The server counts — its number is what an
  agreement agrees to, and it is what the app shows while the marks are untouched. But
  marking has to be instant and offline-tolerant, and the non-negotiable says a board tap
  never reaches the server, so `:core-game` also counts: territory by flood-fill, plus
  prisoners and komi under Japanese rules or stones under Chinese (`ScoringRules`). While the
  user has unsent marks the app shows *that* count, because the server's answer is about a
  different set of stones. The two normally agree on any position whose dead stones are
  agreed, which is the position a player is looking at when they accept — but not always
  (seki, dame left unfilled under area scoring), so when the margins differ by more than
  rounding the screen says so and names both figures. The server's is still the one that
  settles the game.
- **The count is shown as a sum, and that forced a rules fix.** The panel breaks each side's
  total into its addends (September 2026), which means any error in the arithmetic is now on
  screen for the reader to check. One was: under area scoring black's handicap stones sit on
  the board and count themselves, so `Scoring.count` overstated black by the handicap. White
  is now compensated one point per handicap stone — what every server offering Chinese
  counting does, and what makes the two rulesets land on the same result for the same
  finished position. Territory scoring ignores the figure; the free stones were never points.
  DGS reports `komi` unadjusted for handicap games (0.5 in the fixture with five stones), so
  the compensation is the app's own addition, not something already in the komi.
- **Board gestures are separated by distance, never by time.** A horizontal drag walks the
  move timeline (one move per 12dp, relative to where the drag started); touch slop tells it
  apart from a tap, so placing a stone is still instant. A double tap was considered for
  "back to the live position" and rejected on the evidence: Compose's `detectTapGestures`
  wraps the wait for a second finger in `withTimeoutOrNull(doubleTapTimeoutMillis)` and fires
  `onTap` only after it expires, so adding one would delay *every* stone by ~300ms — and
  `awaitSecondDown` compares timestamps only, never positions, so two quick taps on different
  points would register as a double tap and eat the second stone. That is the app's core
  interaction (tap-tap-tap builds a variation), so no time-separated gesture belongs on the
  board. The rate is relative rather than an absolute map of width onto game length: a
  250-move game would otherwise run at under a point per move.
- **Dead stones are sent as groups, not stones.** The server takes one coordinate per group
  and flips it (`toggle=uniq`), so the app tracks the lowest point of each flipped group; a
  group flipped twice cancels and is never mentioned. This keeps the request tiny and makes
  "reset marks" a local operation.
- **The scoring fixture is spec-derived, not recorded.** No game on the test account has
  reached the scoring phase, so `status_score.json` states the documented format instead of a
  captured response; the test says so. Replace it with a real capture when a game gets there.
- **Failures are shown in two tones** (`ServerProblem`): unreachable and rate-limited are
  states to wait out, shown quietly with a retry; signed-out, rejected moves and protocol
  errors are errors and keep the urgent colour. Connectivity is not probed — that would
  need ACCESS_NETWORK_STATE, and the app ships with INTERNET only (plus FOREGROUND_SERVICE for the OGS sign-in, and the
  notification permissions in "Notifications").
- **Package and ids**: Kotlin packages `dev.turnzen.*` (code identity, one per module:
  `.game`, `.dgs`, `.goserver`, `.app`); application id `io.github.chei.turnzen` (the
  distribution identity, reverse-DNS of the `chei.github.io` Pages subdomain the source
  lives under). The two are deliberately decoupled — the applicationId need not match the
  package namespace, and the packages need not track a domain. The source-code and issue
  links in Settings come from the `SOURCE_URL` build config field, set to
  `https://github.com/chei/turnzen`.
- **Demo server**: `FakeTurnBasedGoServerClient` lives in the app module for previews and
  for `-Pturnzen.fakeServer=true` builds, which is how the MVP was verified on an emulator.
- **Kotlin Multiplatform, toward iOS (September 2026).** All four `:core-*` modules are
  Kotlin Multiplatform modules with a JVM target (what the Android app and the tests use) and
  the iOS targets `iosArm64` and `iosSimulatorArm64`. Shared code is in `commonMain` and may
  not use `java.*`:
  - Times in the shared model are `kotlin.time.Instant` and `kotlin.time.Duration`. DGS's
    bare date-times are read with `kotlinx-datetime`, which runs on `java.time` on Android.
    The app's own formatting keeps `java.time` and converts at the edge.
  - The URL encoding the clients need is written out in common code (`Urls` in `:core-dgs`,
    `OgsUrls` in `:core-ogs`), with JVM tests that check it against `URLEncoder`,
    `URLDecoder` and `URI`. It is duplicated, because server modules do not share code.
  - PKCE's SHA-256 and secure random bytes are `expect`/`actual`: `java.security` on the
    JVM, CommonCrypto and `SecRandomCopyBytes` on iOS.
  - Networking stays behind the interfaces the clients already had (`HttpTransport`,
    `OgsHttp`, `OgsSocketConnector`). The OkHttp implementations moved to `jvmMain`
    unchanged, so Android keeps the same HTTP stack. The iOS app will supply its own.
  - The OGS sign-in's loopback listener is JVM-only, behind `RedirectReceiver`. **Open for
    iOS:** an iOS app cannot keep a local listener open while the browser is in front, so
    it would use `ASWebAuthenticationSession`, which returns to the app through a custom URL
    scheme or an https universal link. OGS does not accept custom schemes as redirects. A
    universal link would need an https redirect on a domain the project controls, serving
    Apple's `apple-app-site-association` file, and registered with the OAuth application.
    So iOS sign-in to OGS needs that decision before the iOS app can offer it. The iOS
    `redirectReceiver` throws until then. DGS is unaffected.

  Kotlin cross-compiles the iOS libraries on Linux, so CI runs `compileIos` on every push.
  It has caught JVM-only calls twice (`Map.merge` in the SGF reader, `System.nanoTime` in
  the OGS socket code). A `test` task on each multiplatform module runs `jvmTest`, so the
  usual test command still works. Next: the iOS app itself, planned as SwiftUI over the
  shared core (see "Growth path").