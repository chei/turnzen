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
is **582 457 bytes**. That is the real cost of one fetch, so cache it per tid and never fetch
it on the games list. A trimmed, scrubbed recording of that page is the fixture
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

`TournamentRef.isLadder` is true only when DGS served the kind in English (§1). Do not gate on
it. Instead:

- `isLadder == true` → fetch the ladder page.
- `isLadder == false` **but the kind is not a recognised non-ladder wording** (neither "league"
  nor "round robin" in a language we know) → the tournament group may be translated on this
  account; try the ladder page once and cache the answer per tid.
- A tid that is not a ladder does **not** error — `view.php` does not check the tournament type,
  it just finds no `TournamentLadder` rows and renders an empty table. So "no rows" is the
  negative answer, and it must be cached per tid so it is paid for at most once.

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
- **Cache per tid, not per game.** Every running challenge on one ladder is answered by one
  fetch. A TTL of minutes is plenty; ranks only move when a game ends.
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
- a non-ladder tid, which is expected to render an empty table rather than an error — still
  unverified, since `view.php` never checks the tournament type in the sources.

The session-expired case is recorded above as a redirect, which needs no fixture file: the
transport sees the `302` and its `Location`, and that is the whole signal.

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
