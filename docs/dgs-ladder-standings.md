# Ladder standings in the game info sheet (DGS)

Status: **researched and specified, not implemented.** The tournament *identity* half shipped
in September 2026; the *standings* half described here has not been built.

Goal: when a game is a ladder challenge, the game info sheet should say where the two players
stand — "You are #4, challenging #1" — instead of only naming the ladder. No other DGS client
does this, including the web site, which shows it only on the ladder page itself.

Everything below was verified in September 2026 against `specs/quick_suite.txt`, against live
`sgf.php` responses, and against the DGS server source (`git.code.sf.net/p/dragongoserver/dgs-main`,
read for protocol understanding only — see "Licensing" at the end).

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
- **The scope and type words are translated** — they go through DGS's `T_()` at build time,
  keyed `Dragon#T_scope`, `Ladder#T_type` and so on. The literal words `Tournament` and
  `Round … of …` are *not*; they are plain strings in the builder.

That last point is why `TournamentRef.isLadder` is documented as best effort: it is a
case-insensitive search for "ladder" in the kind, and a Hungarian (or German, or …) account
will not match. It is a cheap hint, never a gate — see §5.

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

Login required (an anonymous request 302s to `error.php?err=login_if_not_logged_in`). The
session cookies `DgsClient` already holds from `login.php` are the same ones this page wants;
no separate login is needed.

It renders the **entire ladder as one HTML table**. The table is built with
`TABLE_NO_SORT|TABLE_NO_PAGE|TABLE_NO_SIZE` and `set_rows_per_page(null)`, and the source
carries the comment that the HTML "can be quite large" — the DGS Ladder had 327 participants in
the sample game used for this research. There is **no paging, sorting or row-limit parameter**.
You get all of it or none of it.

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

### The two markers that make this parseable

1. **The logged-in user's own row** gets `extra_class = 'TourneyUser'`, rendered as
   `<tr class="Row0 TourneyUser">` (or `Row1`). Language-independent.
2. **Each running challenge appears as a link to its own game**, built by
   `TournamentLadder::build_linked_incoming_games()`:

   ```html
   [<a href="game.php?gid=1234567">#7</a>]
   ```

   In a row belonging to player P, the Challenges-In cell holds one of these per challenge
   *against* P; the link text is the **challenger's** rank and the row's own rank anchor is the
   **defender's** rank. If the challenger is the logged-in user the whole thing is additionally
   wrapped in `<span class="TourneyOpp">`; an annulled (detached) game is wrapped in
   `<span class="TGDetached">`.

That is the whole extraction surface. It is numbers and CSS classes, never translated words.

## 4. Extraction

Given a game id `gid` and the ladder page for its `tid`:

1. Find `<a href="…game.php?gid=<gid>">#<R></a>`. If it is absent, the game is not a running
   challenge on this ladder (finished, annulled, or the wrong tid) — give up quietly.
2. Walk out to the enclosing `<tr>`. Its `<a name="rank<M>">` gives that row's rank.
3. The pair is then `{M, R}`. **Challenges on a DGS ladder only ever go upward** —
   `TournamentLadderProps::fill_ladder_challenge_range()` only marks rows with
   `Rank < user_rank` as challengeable — so the **larger number is the challenger and the
   smaller is the defender**. No need to work out which occurrence was found.
4. Which of the two is the user: the `TourneyUser` class on their row, or simply match against
   the uid we already know from the game summary.

If Challenges-Out happens to be displayed the same gid appears twice, in the challenger's row
too, with the roles swapped. Step 3 yields the same answer from either occurrence, so the
parser can take the first match and stop.

## 5. Deciding a game is on a ladder

`TournamentRef.isLadder` is true only when DGS served the kind in English (§1). Do not gate on
it. Instead:

- `isLadder == true` → fetch the ladder page.
- `isLadder == false` **but the kind is not a recognised non-ladder wording** → the account may
  be on a translated locale; try the ladder page once and cache the answer per tid.
- A tid that is not a ladder does **not** error — `view.php` does not check the tournament type,
  it just finds no `TournamentLadder` rows and renders an empty table. So "no rows" is the
  negative answer, and it must be cached per tid so it is paid for at most once.

## 6. Traps

- **Never send `tournament_ladderadd=` / `tournament_ladderdel=`.** These look like a tempting
  way to ask for fewer columns, but `Table::add_or_del_column()` **writes the change to the
  `ConfigPages` table** — it would permanently alter what the user sees on the web site from
  their own browser. A client must not silently reconfigure the user's account. The page is
  big; cache it instead.
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

`CLAUDE.md`: no network in unit tests. Record the ladder page **once** from a real account into
`:core-dgs` test fixtures, with handles, uids and session ids scrubbed, trimmed to a few dozen
rows around the interesting ones, and keep at least:

- a ladder page where the user is the challenger,
- one where the user is the defender,
- one with Challenges-Out displayed (the doubled-occurrence case),
- one with a `TGDetached` entry,
- a non-ladder tid (empty table),
- a session-expired response.

## 10. Licensing

The DGS server is AGPL-3.0 and Turnzen is MIT. The server source was read to establish the
facts above and **no code was copied**; everything in this document is a description of an
observable wire format — URLs, query parameters, HTML structure, CSS class names — written in
prose. The implementation must be written independently from this description. Same rule as
for DragonGoApp and Sente: read to understand, never to transcribe.

## 11. Open questions

- **Page size.** Never measured. Fetch the real DGS Ladder page once and record the byte count
  before committing to fetching it on a phone connection; if it is genuinely large, consider
  showing the standings only on explicit tap rather than on sheet open.
- **Whether the ranks are worth showing for a defended challenge** or only for one the user
  started. Probably both, but the user has only asked about challenging.
- **Best rank and challenge budget.** The page also states "Your best rank is N" and "You have
  started X of max. Y outgoing challenges", but only as translated prose with no machine-
  readable anchor, so both are out of reach with the parsing approach above.
