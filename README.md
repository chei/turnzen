# Turnzen

Android client for correspondence Go on
[Dragon Go Server](https://www.dragongoserver.net/) (DGS) and
[Online Go Server](https://online-go.com/) (OGS), with both servers treated as equals.
Kotlin + Jetpack Compose. MIT.

## Status

The MVP is done (September 2026); work continues on the post-MVP items listed in
[the project brief](docs/turnzen-project-brief.md). What works today:

- **Both servers at once.** Sign in to DGS (username and password) and/or OGS (OAuth in
  the browser; the app never sees an OGS password). Games from both are in one list, each
  row marked with its server's colour. Only correspondence games; OGS live games are ignored.
- **Games list**: "Your turn", the other running games, and the recent finished ones. A game
  that has just begun, just ended, or is in scoring is tinted until it is opened.
- **Board**: try variations without sending anything, then send exactly the first stone of
  the variation. Pass and resign (always confirmed), each with an optional comment. Players'
  comments are read and written on both servers. Replay of finished games.
- **Scoring**: marking dead stones by area (with a first guess), live score, send marks or
  accept the score, on DGS and OGS.
- **Messages** (DGS): new messages at the top of "Your turn", worded by the app; reply,
  dismiss, and accept or decline invitations.
- **Notifications** (off until switched on): your move, time running low (under 24 h), new
  messages, and a signed-out warning. A periodic background check; nothing is pushed.
- **Extras**: ladder standings for DGS ladder games, player icons, SGF share and save.

Creating new games is not in the app: it opens the server's website.

## Build

Requirements: JDK 17+ (21 recommended), Android SDK with platform 37 and build-tools 36.
`local.properties` must point at the SDK (`sdk.dir=...`); Android Studio writes it for you.

```
./gradlew :core-game:test :core-dgs:test :core-ogs:test :core-goserver:test   # JVM tests, no emulator
./gradlew compileIos                                            # iOS compile of the shared code
./gradlew :app-android:testDebugUnitTest                        # view-model tests, JVM
./gradlew ktlintCheck                                           # style (ktlintFormat fixes)
./gradlew :app-android:assembleDebug                            # APK
./gradlew :app-android:assembleDebug -Pturnzen.fakeServer=true  # demo APK against in-memory DGS + OGS servers
./gradlew :app-android:assemblePreview                          # shrunk APK (~2.5 MB) for the phone, test-key signed
```

The demo build installs as an app of its own ("Turnzen demo") beside the real one, so the
two never share data. CI builds the preview APK on every push to `main` and keeps it as a
workflow artifact for three days.

## Modules

| Module | Role |
|---|---|
| `:core-game` | Go rules (captures, suicide, simple ko), trial variations, scoring, SGF read/write, coordinates. |
| `:core-goserver` | `TurnBasedGoServerClient` and the placeholder `LiveGoServerClient`. No server-specific code. |
| `:core-dgs` | DGS implementation: the QuickSuite API, plus the HTML pages for what no API has (ladder standings). |
| `:core-ogs` | OGS implementation: REST, OAuth2 + PKCE with a loopback redirect, and a short-lived realtime socket. |
| `:app-android` | Compose UI. The only module that touches the Android SDK. |

The four `:core-*` modules are pure Kotlin Multiplatform (JVM + iOS), so they build and test
on the JVM without an emulator. Parsers are tested on recorded, scrubbed server responses;
no test touches the network.

## Docs

- [`docs/turnzen-project-brief.md`](docs/turnzen-project-brief.md): research, decisions,
  protocol notes, screen list. The source of truth.
- [`docs/mockups/DESIGN.md`](docs/mockups/DESIGN.md): visual design and tokens.
- [`docs/dgs-ladder-standings.md`](docs/dgs-ladder-standings.md): how ladder positions are
  read from DGS.
- [`docs/remaining-time-estimator.md`](docs/remaining-time-estimator.md): a planned feature,
  not built yet.

## Principles

- Tapping the board never sends anything. Only the explicit Send action does, and it sends
  exactly the first stone of the current trial variation.
- No analytics, no tracking, no ads. Permissions: `INTERNET`; `FOREGROUND_SERVICE` only
  while an OGS sign-in waits for the browser; `POST_NOTIFICATIONS` and WorkManager's own
  (`ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`) for notifications only.
- The DGS password is stored encrypted on the device and sent only to DGS. OGS sign-in goes
  through OGS's own login page; the app keeps only the tokens.
- No code is copied from DragonGoApp, Sente or the DGS server (all copyleft); they are read
  for protocol understanding only.
