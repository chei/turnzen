# Turnzen

Android client for correspondence Go servers, starting with Dragon Go Server (DGS).
Kotlin + Jetpack Compose. MIT license. Explain Android/Compose-specific choices briefly
rather than assuming familiarity with the platform.

## Read before working

- `docs/turnzen-project-brief.md` — research, decisions, protocol notes, screen list.
  Treat it as the source of truth; if you make a decision that contradicts it, update it.
- `docs/mockups/DESIGN.md` + the SVGs next to it — visual reference and design tokens.
  `generate_mockups.py` regenerates the SVGs; update it if the design changes.
- `docs/dgs-ladder-standings.md` — only when working on tournaments or ladders: the full
  protocol research for showing a ladder position, and the traps in it.

## Architecture

```
:core-goserver   interfaces only: TurnBasedGoServerClient, LiveGoServerClient (placeholder).
                 Pure Kotlin. No server-specific code. No Android deps. Depends on
                 :core-game for the shared value types (Point, StoneColor, GameRecord).
:core-dgs        DGS implementation of TurnBasedGoServerClient (QuickSuite + HTML-form
                 clients). Pure Kotlin. Depends on :core-goserver only.
:core-ogs        OGS implementation of TurnBasedGoServerClient (REST + OAuth/PKCE with a
                 loopback redirect + a short-lived realtime socket). Pure Kotlin.
                 Depends on :core-goserver only.
:core-game       Go rules, board state, trial-variation model, SGF. Pure Kotlin.
                 No dependency on any server module.
:app-android     Compose UI. The only module that may depend on the Android SDK.
                 Depends on :core-goserver interfaces, not on :core-dgs / :core-ogs types;
                 the one exception is AppContainer (the composition root), which
                 instantiates DgsClient and OgsClient.
```

Rules that follow from this:
- Anything DGS-specific (URLs, response quirks, cookie handling) stays in `:core-dgs`;
  anything OGS-specific in `:core-ogs`.
- UI never parses server responses. It may know *which* server a game is on (an opaque
  `serverId`, for colour and name), never *how* to talk to it.
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
- No analytics, no tracking, no ads, minimal permissions: INTERNET, plus FOREGROUND_SERVICE
  only while an OGS sign-in waits for the browser, plus POST_NOTIFICATIONS and WorkManager's
  own (ACCESS_NETWORK_STATE, WAKE_LOCK, RECEIVE_BOOT_COMPLETED) for notifications only
  (see the brief).

## Conventions

- Kotlin official code style; ktlint. Coroutines + Flow for async; no RxJava.
- State: one `UiState` data class per screen, exposed from a ViewModel as `StateFlow`.
- Compose: Material 3 with the tokens from `DESIGN.md`; no default M3 purple anywhere.
  One font family (IBM Plex Sans, bundled). Sentence case; no all-caps labels.
- Go coordinates: internal model is 0-based `(col, row)` with row 0 at the top; convert to
  DGS / SGF notation only at the `:core-dgs` and SGF boundaries. Column letters skip "I".
- Board sizes 9, 13, 19 are all first-class; never hardcode 19.
- Small, focused commits with imperative messages ("Add trial-variation undo").

## Build & test

```
./gradlew :core-game:test :core-dgs:test :core-ogs:test :core-goserver:test   # fast, JVM only
./gradlew :app-android:testDebugUnitTest                        # view-model tests, JVM only
./gradlew :app-android:assembleDebug                            # APK
./gradlew :app-android:assembleDebug -Pturnzen.fakeServer=true  # APK using the in-memory demo servers (DGS + OGS)
./gradlew ktlintCheck                                           # ktlintFormat fixes most findings
```

Toolchain notes: AGP 9 compiles Kotlin itself (no `kotlin-android` plugin); compileSdk 37
is required by the Compose version in use. See the "Implementation decisions" section of
the brief before changing versions.

Run the JVM tests before every commit; the Android build only when UI changed.

The six MVP steps are all implemented (September 2026); work continues on the post-MVP
items listed in the brief (comments, resign, scoring, messages, other servers, live play).
