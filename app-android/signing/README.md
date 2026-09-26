# Debug signing key

`turnzen-debug.keystore` signs every **debug** build of Turnzen, wherever it is built: on a
laptop, in Android Studio, or in CI. Android only installs an update over an app signed with
the same key, so sharing one key means a CI APK installs over a laptop build and the other way
round, and the app keeps its data (sign-ins included).

It is public on purpose. It signs nothing but test builds of this app, so anyone holding it
can do no more than make a Turnzen debug build — which anyone can do from the source anyway.

| | |
|---|---|
| Store type | PKCS12 |
| Store password | `android` |
| Key alias | `turnzen-debug` |
| Key password | `android` |
| SHA-256 | `2A:A8:2C:00:55:B1:8C:4B:AA:90:58:7E:81:E5:F2:58:99:79:87:DE:80:16:7E:DC:4F:26:42:DB:42:55:90:E5` |

**Release builds must never use it.** The release key (for GitHub Releases or F-Droid) is a
secret, stays out of the repository, and is configured outside it.

Switching to this key from Android Studio's per-machine default key needs one uninstall of
the Turnzen already on the phone; after that, debug builds from anywhere update each other.
