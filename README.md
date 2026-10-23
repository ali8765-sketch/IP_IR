# Chapar for Android

Chapar is a free VPN for Iranians who live outside Iran. This repository is the complete source code of the
Android app, published so that anyone can check what the app does, build it themselves, and compare it with
the APK we publish.

The app is a fork of [v2rayNG](https://github.com/2dust/v2rayNG) 2.3.11 by 2dust and its contributors, and is
licensed under the **GPL-3.0**, like v2rayNG (see [LICENSE](LICENSE)). The servers are run by us and their code
is not part of this repository.

## What the app does

- You sign in with a Google account (a verified `@gmail.com` address) and choose a nickname.
- Each account gets a monthly allowance at a fixed speed. The app shows how much you have used.
- Two modes:
  - **Iran mode** (default): your traffic goes through our server. Iranian websites are reached through a
    server in Iran, so they see an Iranian address (banking and government sites work).
  - **Tor mode**: your traffic leaves through the Tor network, so websites do not see our server's address.
- You can pick apps that should skip the VPN.

## What the app sends, and to whom

The app talks to exactly three places. You can check each one in the code under
`V2rayNG/app/src/main/java/com/v2ray/ang/ui/chapar/`:

| To | What | Where in the code |
|---|---|---|
| Google | Google sign-in on your phone (Android Credential Manager). | `ChaparActivity.kt` |
| Our API (HTTPS only) | `POST /api/v1/auth/google`: the sign-in token from Google and your nickname. The server checks the token, uses the email address once to check it is a verified Gmail address, and stores only a hash of your Google account ID and your nickname. `GET /api/v1/me`: your account status and usage. `POST /api/v1/connect`: a fresh connection key every time you connect. `POST /api/v1/logout` and `DELETE /api/v1/me`: sign out and delete your account. | `ChaparApi.kt`, `ChaparRepository.kt` |
| Our VPN server | Your traffic, encrypted (VLESS + REALITY, TLS 1.3). | the Xray core |

The app accepts connection settings only from our own API, and only for our own server (`ChaparLinks.kt`). It
does not accept links, QR codes, or subscriptions from anywhere else.

It does **not** contain analytics, ads, trackers, or crash reporting, and it turns the core's connection log
off (`CoreConfigManager.kt`). The "Report a problem" screen only shows fixed texts on your phone
(`ChaparDiagnostics.kt`); nothing is uploaded.

### Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE` | To connect, and to notice network changes. |
| `BIND_VPN_SERVICE` | To be a VPN (Android asks you once). |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS` | To keep the VPN running and show its notification. |
| `BIND_QUICK_SETTINGS_TILE` | The quick-settings tile to connect and disconnect. |
| `WAKE_LOCK` | Added by AndroidX WorkManager (part of v2rayNG); keeps the CPU awake while a background task runs. |
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | Added by the AndroidX Biometric library (part of v2rayNG). Chapar never asks for a fingerprint or face. |
| `org.chapar.vpn.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Added by AndroidX Core. It belongs to the app itself, so other apps cannot send to its internal receivers. |

Android grants all of these without asking, except the VPN and notification permissions. The app does not ask
for contacts, location, camera, microphone, storage, or phone permissions, and it does not start at boot.
`aapt2 dump badging <apk>` lists the permissions of any APK.

### What you cannot check here

What our servers do is described in the privacy policy that the app links to (no activity, DNS, or connection
logs; no IP addresses; no email addresses or real names stored). The server code is not public, so for that
part you rely on that policy.

## What we changed in v2rayNG

The first commit of this repository's own work sits directly on top of v2rayNG's `2.3.11` release
(tag `v2rayNG-2.3.11` here), so `git diff v2rayNG-2.3.11 main` shows every change. In short:

- New: the Chapar screens and logic in `ui/chapar/` (sign-in, connect, modes, settings, apps that skip the VPN),
  their tests in `src/test/.../ui/chapar/`, `handler/ChaparRouting.kt`, `handler/SocksSession.kt`,
  `strings_chapar.xml`, the icon, and the build settings in `chapar.local.properties.example`.
- Turned off for safety: importing links, QR codes, and URL schemes from other apps (those screens are no longer
  exported, and the camera permission is gone); starting at boot; exported broadcast receivers; cloud backup of
  app data (`backup_rules.xml`, `data_extraction_rules.xml`); cleartext traffic (`network_security_config.xml`).
- Changed: the local SOCKS port is random and password-protected; the core's access log is off; the generated
  core config file is deleted when the VPN stops; switching modes reloads the core without dropping the tunnel;
  the routing sends everything through the VPN except apps you choose.
- Removed upstream files that do not apply to Chapar: the v2rayNG privacy policy, store metadata, CI workflows,
  and contributor guides.

## Build it yourself

1. Clone with the native dependencies:
   `git clone --recursive <this repository>`
2. Build the two native parts, as v2rayNG does: `libv2ray.aar` from `AndroidLibXrayLite` (Go and gomobile; see
   that project's README) into `V2rayNG/app/libs/`, and the tunnel with `./compile-hevtun.sh` (Android NDK).
3. Copy `V2rayNG/chapar.local.properties.example` to `V2rayNG/chapar.local.properties` and fill in the API
   address and the Google client ID (the values are in our published APK; anyone can read them there).
4. With JDK 17: `cd V2rayNG && ./gradlew :app:testPlaystoreDebugUnitTest :app:assemblePlaystoreRelease`.
   This gives unsigned APKs in `app/build/outputs/apk/playstore/release/`. Our releases are these files,
   aligned with `zipalign -P 16 4` and signed with `apksigner`.

## Check our APK

Each release lists the commit it was built from, the SHA-256 of the APK, and the SHA-256 of our signing
certificate (it stays the same for every release; `apksigner verify --print-certs <apk>` shows it). Your own
build of the same commit is unsigned, so compare contents rather than whole files: for example
[apksigcopier](https://github.com/obfusk/apksigcopier) `compare`, or `diffoscope` on the two APKs. Small
differences can come from different build tools.

## Reporting a security problem

Please open an issue without details and ask for a private contact, or use GitHub's private vulnerability
reporting for this repository.

## Credits

v2rayNG by 2dust and contributors (GPL-3.0); [Xray-core](https://github.com/XTLS/Xray-core) (MPL-2.0) through
[AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) (LGPL-3.0);
[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) (MIT).
