# Turnzen

Android client for correspondence Go servers, starting with
[Dragon Go Server](https://www.dragongoserver.net/) (DGS). Kotlin + Jetpack Compose. MIT.

Status: MVP. Sign in with your DGS account, see the games where it is your move, try
variations on the board, send one move, move on to the next game.

## Build

Requirements: JDK 17+ (21 recommended), Android SDK with platform 37 and build-tools 36.
`local.properties` must point at the SDK (`sdk.dir=...`); Android Studio writes it for you.

```
./gradlew :core-game:test :core-goserver:test :core-dgs:test   # JVM tests, no emulator
./gradlew :app-android:testDebugUnitTest                        # view-model tests, JVM
./gradlew ktlintCheck                                           # style (ktlintFormat fixes)
./gradlew :app-android:assembleDebug                            # APK
./gradlew :app-android:assembleDebug -Pturnzen.fakeServer=true  # APK against an in-memory demo server
```

## Modules

| Module | Role |
|---|---|
| `:core-game` | Go rules (captures, suicide, simple ko), trial variations, SGF read/write, coordinates. Pure Kotlin. |
| `:core-goserver` | `TurnBasedGoServerClient` and the placeholder `LiveGoServerClient`. Pure Kotlin. |
| `:core-dgs` | DGS implementation over the QuickSuite (`quick_do.php`, `login.php`, `sgf.php`). Parsers are tested on recorded fixtures; no network in tests. |
| `:app-android` | Compose UI. The only module that touches the Android SDK. |

See `docs/turnzen-project-brief.md` for the research and decisions, and
`docs/mockups/DESIGN.md` for the visual design.

## Principles

- Tapping the board never sends anything. Only the explicit Send action does, and it sends
  exactly the first stone of the current trial variation.
- No analytics, no tracking, no ads. `INTERNET` is the only permission.
- Credentials are stored encrypted on the device and never leave it except to sign in to DGS.
