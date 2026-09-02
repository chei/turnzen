# Turnzen

Android client for correspondence Go servers, starting with Dragon Go Server (DGS).
Kotlin + Jetpack Compose. MIT license. Explain Android/Compose-specific choices briefly
rather than assuming familiarity with the platform.

## Read before working

- `docs/turnzen-project-brief.md` — research, decisions, protocol notes, screen list.
  Treat it as the source of truth; if you make a decision that contradicts it, update it.
- `docs/mockups/DESIGN.md` + the SVGs next to it — visual reference and design tokens.
  `generate_mockups.py` regenerates the SVGs; update it if the design changes.

## Architecture

```
:core-goserver   interfaces only: TurnBasedGoServerClient, LiveGoServerClient (placeholder).
                 Pure Kotlin. No server-specific code. No Android deps.
:core-dgs        DGS implementation of TurnBasedGoServerClient (QuickSuite + HTML-form
                 clients). Pure Kotlin. Depends on :core-goserver only.
:core-game       Go rules, board state, trial-variation model, SGF. Pure Kotlin.
                 No dependency on any server module.
:app-android     Compose UI. The only module that may depend on the Android SDK.
                 Depends on :core-goserver interfaces, not on :core-dgs types.
```

Rules that follow from this:
- Anything DGS-specific (URLs, response quirks, cookie handling) stays in `:core-dgs`.
- UI never parses server responses and never knows which server it talks to.
- `:core-*` modules must build and test on the JVM without an emulator.

## Non-negotiables

- **Tapping the board never sends anything to the server.** Only the explicit Send action
  does, and it sends exactly the first stone of the current trial variation.
- **Never copy code from DragonGoApp (AGPL-3.0), Sente (GPL-3.0), or the DGS server
  (AGPL-3.0).** They may be read for protocol understanding; describe behavior in prose
  and implement independently. This project is MIT and must stay clean.
- **No credentials in the repo.** DGS username/password live in EncryptedSharedPreferences
  (or equivalent) at runtime only. Test fixtures use fake accounts.
- **No network in unit tests.** Record real DGS responses once into `:core-dgs` test
  fixtures (with credentials/session ids scrubbed) and test the parsers against those.
- No analytics, no tracking, no ads, minimal permissions (INTERNET only for the MVP).

## Conventions

- Kotlin official code style; ktlint. Coroutines + Flow for async; no RxJava.
- State: one `UiState` data class per screen, exposed from a ViewModel as `StateFlow`.
- Compose: Material 3 with the tokens from `DESIGN.md`; no default M3 purple anywhere.
  One font family (IBM Plex Sans, bundled). Sentence case; no all-caps labels.
- Go coordinates: internal model is 0-based `(col, row)` with row 0 at the top; convert to
  DGS / SGF notation only at the `:core-dgs` and SGF boundaries. Column letters skip "I".
- Board sizes 9, 13, 19 are all first-class; never hardcode 19.
- Small, focused commits with imperative messages ("Add trial-variation undo").
  Before the first commit, set `git config user.email` to the GitHub no-reply address.

## Build & test

```
./gradlew :core-game:test :core-dgs:test :core-goserver:test   # fast, JVM only
./gradlew :app-android:assembleDebug                            # APK
./gradlew ktlintCheck
```

Run the JVM tests before every commit; the Android build only when UI changed.

## MVP order

1. Repo skeleton, modules, Gradle, ktlint, CI running the JVM tests.
2. `:core-game`: board state, captures, ko, trial-variation stack (place/undo/reset), SGF.
3. `:app-android`: board Canvas + trial mode against a fake in-memory server client.
4. `:core-dgs`: login, game list, fetch game/SGF, send move — parsers tested on fixtures.
5. Wire the real client; "your move" list sorted by last move; auto-advance after Send.
6. Settings, sign-out, session-expiry re-login.

Post-MVP items (comments, resign, scoring, messages, other servers, live play) are listed
in the brief. Do not start them before the six steps above work end to end.
