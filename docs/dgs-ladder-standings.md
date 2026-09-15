# Ladder standings in the game info sheet (DGS)

Status: **researched and specified, not implemented.** The tournament *identity* half shipped
in September 2026; the *standings* half described here has not been built.

Goal: when a game is a ladder challenge, the game info sheet should say where the two players
stand — "You are #4, challenging #1" — instead of only naming the ladder. No other DGS client
does this, including the web site, which shows it only on the ladder page itself.

Everything below was verified in September 2026 against `specs/quick_suite.txt`, against live
`sgf.php` responses, against the DGS server source (`git.code.sf.net/p/dragongoserver/dgs-main`,
read for protocol understanding only — see "Licensing" at the end), and finally against a real
ladder page saved from a logged-in browser, which is in the repo as a scrubbed fixture. The
extraction in §4 was run against that page and produces the right answer for all four of the
account's running challenges; the page also disproved one conclusion drawn from the sources
alone, which is called out in §4.

---

## 1. What already works

`GameInfo.tournament: TournamentRef?` (`:core-goserver`) carries id, name, kind, `isLadder`,
round and round count. The QuickSuite game object gives only `tournament_id`; everything else
is merged in from the SGF the client downloads anyway, by `DgsTournament` in `:core-dgs`.

DGS writes these two standard SGF root properties for a tournament game and omits both for a
casual one:

```
EV[(Dragon Ladder) Tournament #3: DGS Ladder (19x19)]        RO[Round 1 of 1]
EV[(Private Round Robin) Tournament #88: …tournament (5x5)]  RO[Round 4 of 4]
```

The format is fixed in `include/sgf_builder.php`: `(<scope> <type>) Tournament #<id>: <title>`
and `Round <current> of <total>`.

- **Scope** is one of Dragon / Public / Private. **Type** is one of Ladder / League / Round
  Robin. There are no others.
- **Ladders are tournaments on DGS.** Same id space, same `tournament_id` field on the game. A
  ladder is simply `Type = Ladder`, so "Tournament #88" for a round robin and "#3" for a ladder
  were always the same kind of id.
- **The scope and type words go through DGS's `T_()`**, keyed `Dragon#T_scope`,
  `Ladder#T_type` and so on, so a translation *may* exist. The literal words `Tournament` and
  `Round … of …` do not; they are plain strings in the SGF builder and are always English
  there.
- **But whether a key is actually translated is per-language and partial.** DGS's translation
  data is volunteer-maintained, and the tournament group is a long way from complete. On the
  Hungarian account whose ladder page is recorded here, `T_()` returns *English* for the scope
  and type — the recorded page's own heading reads `(Dragon Ladder) Verseny #3 - DGS Ladder
  (19x19)`, built by the very same `getScopeText`/`getTypeText` pair the SGF uses: "Tournament"
  became "Verseny", "Dragon Ladder" did not move. The table header in that same page mixes
  `Rank`, `Challenges-In` and `Rank Changed` with `Név`, `Ország` and `Esemény`.

  So the practical position is: `EV` reads as English on many accounts, including ones whose
  UI is not, and reads as something else on any language whose translators have filled the
  tournament group in. Neither can be assumed.

That is why `TournamentRef.isLadder` is documented as best effort: it is a case-insensitive
search for "ladder" in the kind, which works wherever the tournament group is untranslated and
silently fails where it is not. It is a cheap hint, never a gate — see §5. Note the id and name
are unaffected either way: the `EV` parser keys on `#<id>:`, never on the word "Tournament", so
a translated heading still yields the right tournament.

## 2. What DGS does not expose

- **There is no tournament object in the QuickSuite.** Section (1) of `specs/quick_suite.txt`
  lists `tourney` among the server's sections, but section (3) specifies commands for `game`,
  `user`, `message`, `folder`, `contact`, `wroom`, `bulletin` and `tag` only. Nothing for
  tournaments is specified or implemented. Do not go looking again.
- **No ladder rank reaches the client through any API.** Not in the game object, not in the
  SGF, not in `quick_status.php`. `TournamentLadder.Rank` lives only in the database and on the
  rendered ladder page.
- **The ranks are not snapshotted per game.** `TournamentGames` stores `Challenger_uid` and
  `Defender_uid`, not the ranks they held. The ladder page shows *current* ranks. While a
  challenge is running both players still hold their pre-game ranks (a ladder rank only moves
  when a game ends), so for a running game "current" is exactly what we want, and for a
  finished game it is misleading. See §6.

So the only source is the ladder page, fetched as a logged-in HTML request.

## 3. The page

```
GET tournaments/ladder/view.php?tid=<tid>
```

Login required. The session cookies `DgsClient` already holds from `login.php` are the same
ones this page wants; no separate login is needed.

**This page answers in three distinguishable ways, all decided by the redirect alone**, before
any body is read:

| Case | Response |
|---|---|
| A ladder | `200` + the table |
| Not a ladder | `302` → `error.php?err=bad_tournament&debugmsg=Tournament.ladder_view.TCache:load_cache_tlp(<tid>,1)&eid=…` |
| Session gone | `302` → `error.php?err=login_if_not_logged_in&debugmsg=Tournament.ladder.view&page=…` |

Verified against tid 3 (ladder) and tid 365 (round robin) in September 2026. `view.php` never
checks the tournament type explicitly — the type check is implicit two calls earlier, where
`TournamentCache::load_cache_tournament_ladder_props($tid, check_exist: true)` finds no
`TournamentLadderProps` row for a non-ladder tournament and raises `bad_tournament`.

**`bad_tournament` is written to DGS's `Errorlog` table.** `ErrorCode::need_db_errorlog()`
carries a skip-list of codes that are not logged; `login_if_not_logged_in` is on it and
`bad_tournament` is not. So an expired session costs the server nothing, but **every probe of a
non-ladder tid leaves an admin-visible error-log row with an `eid`**. That is somebody else's
error log. It shapes the detection rule in §5 and is the reason this client does not probe
speculatively.

**Session expiry looks nothing like it does in the QuickSuite.** There is no error object to
read — the page answers `302` with a `Location` of

```
error.php?err=login_if_not_logged_in&debugmsg=Tournament.ladder.view&page=tournaments%2Fladder%2Fview.php%3Ftid%3D3
```

so the signal is `err=login_if_not_logged_in` in that header, and it must be mapped to the same
`SessionExpired` the QuickSuite path raises. `OkHttpTransport` is already built with
`followRedirects(false)`, which is what makes this visible; turning redirects on would swallow
the 302 and hand the parser a 4.9 KB HTML login page instead. Do not turn them on. (Verified
anonymously, September 2026 — needs no account.)

It renders the **entire ladder as one HTML table**. The table is built with
`TABLE_NO_SORT|TABLE_NO_PAGE|TABLE_NO_SIZE` and `set_rows_per_page(null)`, and there is **no
paging, sorting or row-limit parameter**: you get all of it or none of it.

**Measured, September 2026:** the DGS Ladder (tid 3, 327 participants, 18 columns displayed)
is **582 457 bytes of HTML — but only ~54 KB on the wire.** DGS serves
`Content-Encoding: gzip` with `Vary: Accept-Encoding`, and this page is hugely repetitive
markup: gzip -6 takes it to 54 325 bytes, a 10.7x reduction. OkHttp adds `Accept-Encoding:
gzip` and decompresses transparently as long as the caller does not set that header itself, so
the saving is free — but do not set `Accept-Encoding` by hand in `HttpRequest`, and measure the
real `Content-Length` when implementing rather than trusting this estimate.

The 582 KB still has to be decompressed, held and scanned on a phone, so parse it in one pass
and do not materialise it more than once.

**There is no HTTP caching to lean on**: DGS sends `Cache-Control: no-store, no-cache,
must-revalidate, max-age=0`, so a conditional GET buys nothing and the only reuse is our own
per-tid cache — which is worth a lot, since one fetch answers every game on that ladder (four,
for the recorded account).

### Cheaper sources: there are none

Checked and rejected, so nobody re-checks them:

- **`game.php?gid=`** — shows the tournament via `build_info(4)` (scope, type, id, status,
  title) and no ranks at all. `TournamentLadder::build_result_info()`, which does carry rank
  data, is only ever called from the tournament director's `edit_results.php`.
- **`tournaments/list_participants.php?tid=`** — a properly paged, *filterable* table (there is
  even a handle filter), which is exactly what we would want. But it has no ladder Rank column:
  Actions, Name, Userid, Country, Rating, Comment, Reg ID, Status, Flags, Round, Tournament
  Rating. Useless for standings.
- **`show_games.php?tid=&uid=`** — small, and its `Col45` even gives the Challenger/Defender
  role, but it carries no ranks either.
- **Narrowing the ladder page itself** — `view.php` registers no filters at all, and its table
  is built `TABLE_NO_PAGE` with `set_rows_per_page(null)`. There is no row-limit, offset, sort
  or filter parameter to reach for.

So: one gzipped fetch per ladder, cached. That is the floor. Never fetch it on the games list. A trimmed, scrubbed recording of that page is the fixture
`core-dgs/src/test/resources/dgs/ladder_view_tid3.html`; the account it came from is
Hungarian, which is deliberate — every header and every piece of cell text in it is localised,
so a parser that passes against it cannot have keyed on an English word.

### Columns

Which columns appear is a per-user saved preference (`ConfigTableColumns`,
`CFGCOLS_TOURNAMENT_LADDER_VIEW`), so **column count and order cannot be relied on**. Four
columns are declared `TABLE_NO_HIDE`, which `is_column_displayed()` short-circuits to always
true, so these are guaranteed present:

| # | Column | Content we care about |
|---|---|---|
| 1 | Rank | `<a name="rank<N>"><N></a>` — the row's rank |
| 4 | Userid | user link carrying the player's uid |
| 7 | Actions | (challenge buttons; not needed) |
| 8 | Challenges-In | one `[#<rank>]` per incoming challenge, **linked to the game** |

Column 16 (Challenges-Out) is the mirror image but is hideable, so nothing may depend on it.

### The three markers that make this parseable

0. **The header row carries the column numbers.** `<tr id="TableHead">` holds one
   `<th id="Col<N>">` per *displayed* column, in the order the cells will appear:

   ```html
   <tr id="TableHead" class=Head>
     <th id="Col2" …><th id="Col1" …><th id="Col19" …> … <th id="Col8" …><th id="Col16" …> …
   ```

   This is the calibration that makes the rest positional and safe. Read the header once to
   learn the displayed column numbers in order, then the *i*-th `<td>` of every data row is
   the *i*-th of those columns. `Col8` is Challenges-In, `Col16` is Challenges-Out. Nothing
   here is a translated word.

1. **The logged-in user's own row** gets `extra_class = 'TourneyUser'`, rendered as
   `<tr class="Row1 TourneyUser">`. Note the row classes cycle `Row1`…`Row4`, not `Row0`/
   `Row1` — do not key on the number, only on the presence of `TourneyUser`.
2. **Each running challenge appears as a link to its own game**, built by
   `TournamentLadder::build_linked_incoming_games()`:

   ```html
   [<a href="game.php?gid=1234567">#7</a>]
   ```

   In a row belonging to player P, the **Challenges-In** cell (`Col8`) holds one of these per
   challenge *against* P: the link text is the **challenger's** rank and the row's own rank
   anchor is the **defender's** rank. The **Challenges-Out** cell (`Col16`) is the mirror: the
   link text is the **defender's** rank and the row is the challenger. If the logged-in user is
   the other party the entry is additionally wrapped in `<span class="TourneyOpp">`; an annulled
   (detached) game is wrapped in `<span class="TGDetached">`.

That is the whole extraction surface: header ids, a row class, an anchor name, and a link. It is
numbers and CSS classes, never translated words.

Two incidentals worth knowing. Tag case is inconsistent — user links come out as `<A href=…>`
and game links as `<a href=…>` — so match case-insensitively. And all hrefs are relative to the
page (`../../game.php?gid=…`), so match on the `game.php?gid=` substring, never on a full URL.

## 4. Extraction

Given a game id `gid` and the ladder page for its `tid`:

1. **Calibrate.** From `<tr id="TableHead">`, read the ordered list of `<th id="Col<N>">`.
   Record the position of `Col8` (Challenges-In) and, if present, `Col16` (Challenges-Out).
2. For each data row: its `<a name="rank<M>">` is that player's rank, and `TourneyUser` in the
   `<tr class>` marks the logged-in user's row.
3. In the row's Challenges-In cell (the `<td>` at the `Col8` position), each
   `<a href="…game.php?gid=<g>">#<C></a>` means **challenger `#C` is challenging defender `#M`
   in game `g`**. In the Challenges-Out cell, each such link means **challenger `#M` is
   challenging defender `#C`**.
4. Look up our `gid`. Absent → the game is not a running challenge on this ladder (finished,
   annulled, or the wrong tid); give up quietly.

With Challenges-Out displayed each game appears twice, once from each side, and the two
readings agree — so a parser may stop at the first, or build the whole index in one pass and
answer every game on that ladder from it.

> **Do not try to infer the direction from the two ranks.** The obvious rule — challenges only
> go upward, so the larger number is the challenger — is true *at the moment a challenge is
> made* (`TournamentLadderProps::fill_ladder_challenge_range()` only offers rows with
> `Rank < user_rank`) and false by the time you read it. Ranks move whenever *other* games
> finish, and a correspondence challenge runs for weeks. The recorded page shows exactly this:
> in game 1501653 the challenger sits at rank 149 and the defender at 153, because the
> challenger climbed past their opponent while the game was still running. Direction comes from
> which cell the link is in, and from nothing else.

## 5. Deciding a game is on a ladder

**Trust `isLadder`. The failure case it was hedged against does not exist on DGS today.**

Measured against DGS's own published translation dump
(`snapshot/Translationdata.mysql.gz`, September 2026), for the text `Ladder#T_type`:

- DGS has **38 languages**;
- **34** have no translation row for it at all;
- the remaining **4** (German, Romanian, Portuguese (Brazil) ×2) have a row holding the
  **empty string**, which is not a translation;
- so **zero** languages translate it.

An empty row is the same as no row: `T_()` returns the stored text only
`if ( (string)$s != '' )`, and otherwise falls through to `strip_translation_label()`, which
drops the `#T_type` suffix and hands back the English source word. `Dragon#T_scope` behaves the
same way — no language moves it. So `(Dragon Ladder)` is what *every* DGS account receives,
whatever its interface language, which is why the Hungarian account in §1 sees an English kind
inside a Hungarian page.

That makes the rule simple:

- **`isLadder == true` → fetch.** Reliable for every account that exists.
- **Anything else → no standings row.** Do not probe: a non-ladder tid answers
  `err=bad_tournament`, and that is written to DGS's error log (§3). Writing to someone else's
  error log to satisfy a guess is not something a client should do.

The hedging is still worth keeping in the parser, because this is a property of DGS's
translation *data*, not of its code: a translator could fill that row in tomorrow and the
heuristic would start missing ladders on that one language. The failure would be silent and
harmless — a missing row, never a wrong one — and the fix would be the escape hatch below. But
do not build the escape hatch until someone reports needing it.

<details>
<summary>Escape hatch, if a language ever does translate it</summary>

Make it **user-initiated**: offer the standings row as something to tap, probe once when they
tap it, and persist the answer so a given tid is never probed twice. One error-log row in
exchange for an action the user asked for is defensible; probing behind their back is not.

Rejected alternatives, so nobody re-derives them:

- **A baked-in list of ladder tids.** Ladders on DGS are few and long-lived, so a shipped list
  would mostly work — but it is server state hardcoded into a client, stale the moment a new
  ladder is created, and it buys nothing over a heuristic that is already correct everywhere.
- **`tournaments/view_tournament.php?tid=N`** answers `200` for every type and logs nothing, so
  it looks like the polite probe. But it has no clean type marker — its menu carries no link to
  the ladder page, and the only structural difference is that a ladder fills the
  `TournamentStandings` section while a round robin leaves it empty. Its TOP-N standings table
  has no Challenges-In/Out columns either, so it cannot map a game to a pair of ranks.
</details>

## 6. Traps

- **Never send `add=` / `del=`.** Every hideable column's header carries a link like
  `view.php?tid=3&del=8`, which looks like a tempting way to ask for a smaller page. It is not
  a view option: `Table::add_or_del_column()` **writes the change to the `ConfigPages` table**,
  permanently altering what the user sees on the web site from their own browser. A client must
  not silently reconfigure the user's account. The page is big; cache it instead.
- **Do not assume a column is displayed.** Only `Col1` (Rank), `Col4` (Userid), `Col7`
  (Actions) and `Col8` (Challenges-In) are non-hideable — in the recorded page they are exactly
  the headers with no hide link. Everything else, `Col16` (Challenges-Out) included, may be
  missing, which is why step 1 exists.
- **Cache per tid, not per game, and make it outlive the process.** See §6a — the naive
  reading of "cache it" costs 1.5 MB a week where the right one costs 371 KB.
- **Fetch lazily**, when the info sheet opens on a ladder game — never on the games list, and
  never as part of `game()`. `game()` is on the path to playing a move and must not grow a
  second-and-a-half round trip for decoration.
- **Finished games**: once a challenge ends the link disappears from the ladder page and both
  ranks have already moved. Show the ladder name and no standings rather than today's ranks
  against a game from last month.
- **Failure is not an error.** No network, a changed page, a withdrawn player: drop the
  standings row. Nothing here is needed to play.
- **The page is HTML meant for humans and may change.** It has been stable for many years
  (`view.php` predates 2014), but the parser must fail soft, and its fixtures must be refreshed
  when it does change.

## 6a. The cache

The thing on screen — "you #153, challenging #108" — changes only when one of those two players
finishes a ladder game. On a correspondence ladder that is days or weeks apart, and being a day
out of date is a cosmetic error in decorative information. So the cache should be biased hard
towards fewer requests, and two decisions do all the work:

**Cache the parsed result, not the page.** What a tid needs to remember is the user's own rank
plus, for each of their running games on that ladder, the defender/challenger pair — four
entries and a timestamp, on the order of a hundred bytes. Never keep the 582 KB of HTML.

**Persist it.** An in-memory cache is emptied every time Android reclaims the process, which on
a phone is constantly, so a process-lifetime cache quietly degrades towards no cache at all.
This belongs in the same on-device store as the game-record cache.

Worked example — four running games on ladder 3, the info sheet opened on all four every day
for a week, at 54 KB a fetch:

| Cache | Fetches / week | Wire / week |
|---|---:|---:|
| None (fetch per sheet open) | 28 | 1.45 MB |
| In memory, short TTL, all four opened in one sitting | 7 | 371 KB |
| In memory, short TTL, a few app sessions a day | 14–28 | 0.74–1.45 MB |
| **Persisted, 24 h TTL** | **7** | **371 KB** |
| Persisted, 72 h TTL | 3 | 163 KB |

The middle row is the trap: with an in-memory cache the cost depends on how the user happens to
use the phone, not on anything we chose. Persisting makes it a flat one fetch per ladder per
day however often the sheet is opened.

Invalidate on two signals besides the TTL, both free:

- **A gid we are asked about is not in the cached index** — a new challenge has appeared since
  the fetch, so the index is genuinely incomplete. Refetch.
- **One of our games on that ladder finished** — that is precisely the event that moves ranks,
  and ours among them. Drop the tid.

## 7. Where the code goes

The module rules in `CLAUDE.md` decide most of this:

- `:core-goserver` — extend the value type, e.g.
  `TournamentRef.standing: LadderStanding?` with `yourRank`, `opponentRank` and
  `youAreChallenger: Boolean`. Server-neutral: a ladder is a ladder on any server.
- `:core-goserver` — the interface gains something like
  `suspend fun ladderStanding(game: GameId, tournament: TournamentRef): LadderStanding?`,
  default-implemented as `null` so no other server has to care. It must be a *separate* call,
  not part of `game()`, precisely so the UI can decide to pay for it.
- `:core-dgs` — an HTML client alongside the QuickSuite one. This is the first thing in the
  project to need it; `CLAUDE.md` already anticipates "QuickSuite + HTML-form clients". It owns
  the URL, the fetch, the per-tid cache and the parser. A small tolerant scanner over the
  markup (find the `gid` link, walk back to the nearest preceding `rank` anchor) beats pulling
  in an HTML parser dependency, and matches how the rest of `:core-dgs` reads responses.
- `:app-android` — `BoardViewModel` asks for the standing when the sheet opens and folds it
  into `BoardUiState`; the sheet renders it. The UI must not learn that any of this came from
  scraping a web page.

## 8. UI

One row in the info sheet, under the existing Ladder / Format rows:

```
Ladder      DGS Ladder (19x19)
Format      Dragon Ladder
Standing    You #4 → #1          (challenging)
Standing    #7 → You #4          (defending)
```

Wording to settle when it is built; the point is that it reads as a direction, not two numbers.
It arrives after the rest of the sheet (one extra request), so it must appear without moving
anything already on screen — append, do not insert above existing rows.

## 9. Testing

`CLAUDE.md`: no network in unit tests. The page has been recorded — **once**, in September
2026 — as `core-dgs/src/test/resources/dgs/ladder_view_tid3.html`: the real DGS Ladder, trimmed
from 327 rows to 20 around the interesting ranks, with every handle, display name and user id
replaced by fakes. There are no session secrets in the markup; the session lives in the cookie.

What that one fixture already covers: the user as challenger (game 1503981, #153 → #108), the
user as defender (three games, including the inverted-rank case 1501653), Challenges-Out
displayed so every game appears twice, and a fully localised page.

Still to record when the situation arises, or to hand-write from this document:

- a page with `Col16` hidden (Challenges-In only),
- an entry wrapped in `<span class="TGDetached">`,
Neither the non-ladder case nor the session-expired case needs a fixture file: both are `302`s,
and the transport sees the status and `Location` header, which is the whole signal. §3 has both
exact shapes.

## 10. Licensing

The DGS server is AGPL-3.0 and Turnzen is MIT. The server source was read to establish the
facts above and **no code was copied**; everything in this document is a description of an
observable wire format — URLs, query parameters, HTML structure, CSS class names — written in
prose. The implementation must be written independently from this description. Same rule as
for DragonGoApp and Sente: read to understand, never to transcribe.

## 11. Open questions

- **Whether 582 KB on a phone connection is acceptable on sheet open**, or whether the
  standings row should load only when tapped. One fetch serves every game on that ladder, and
  in the recorded page that is four games, so the amortised cost is better than it looks.
- **Whether to show the standings for a challenge the user is defending**, or only one they
  started. Probably both — the recorded account is defending three and challenging one — but
  only the challenging case was asked for.
- **Best rank.** Reachable after all, and purely numeric: the Chg cell (`Col2`) carries
  `onmouseover="showTLRankInfo(event,153,35,143,105)"`, whose arguments are, per
  `TournamentLadder::get_rank_info_format()`, current rank, best rank, start-of-period rank and
  previous-period rank. `Col2` is hideable, so treat it as a bonus. The challenge budget ("you
  have started X of max. Y") remains out of reach — translated prose with no anchor.
- **No `TGDetached` sample.** The recorded page has none, so the annulled-game path is
  specified but untested. Record one if a ladder game is ever annulled.
