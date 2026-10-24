# Chapar for Android

**Download:** [latest version](https://github.com/ali8765-sketch/IP_IR/releases/latest)

<div dir="rtl">

## چاپار به زبان ساده

چاپار یک اپ اندروید رایگان برای **ایرانیانی است که خارج از ایران زندگی می‌کنند**.

خیلی از سایت‌ها و خدمات ایرانی، مثل بانک‌ها، سامانه‌های دولتی، فروشگاه‌ها و درگاه‌های پرداخت، کسی را که از خارج وصل می‌شود راه نمی‌دهند. با روشن کردن چاپار، سایت‌های ایرانی شما را با **آی‌پی ایران** می‌بینند، انگار داخل ایران هستید.

- ثبت‌نام رایگان است و فقط با حساب Gmail انجام می‌شود.
- هر حساب هر ماه حجم رایگان دارد و اپ مصرف شما را نشان می‌دهد.
- سایت‌های غیرایرانی از سرور ما در اروپا باز می‌شوند.
- حالت Tor برای حریم خصوصی بیشتر اختیاری است.
- ممکن است چند سایت یا درگاه پرداخت ایرانی آی‌پی سرور ما را قبول نکنند.
- چاپار برای استفاده از داخل ایران ساخته نشده است.

**دانلود:** [آخرین نسخه](https://github.com/ali8765-sketch/IP_IR/releases/latest)

</div>

## In short

Chapar is a free Android VPN for Iranians who live outside Iran. Its main purpose: Iranian websites and services
that refuse visitors from abroad (banks, government services, shops, payment gateways) see an **Iranian IP
address**, as if you were in Iran. Other websites are reached through our server in Europe. It is not meant for
use inside Iran, and a few Iranian sites or payment gateways may still refuse our server's address.

This repository is the complete source code of the Android app, published so that anyone can check what the
app does, build it themselves, and compare it with the APK we publish.

The app is a fork of [v2rayNG](https://github.com/2dust/v2rayNG) 2.3.11 by 2dust and its contributors, and is
licensed under the **GPL-3.0**, like v2rayNG (see [LICENSE](LICENSE)). The servers are run by us and their code
is not part of this repository.

An outside code audit and what we changed after it: [AUDIT-FIXES.md](AUDIT-FIXES.md).

## What the app does

- You sign in with a Google account (a verified `@gmail.com` address) and choose a nickname.
- Each account gets a monthly allowance at a fixed speed. The app shows how much you have used.
- Two modes:
  - **Iran mode** (default): your traffic goes through our server. Iranian websites are reached through a
    server in Iran, so they see an Iranian address (banking and government sites work).
  - **Tor mode**: your traffic leaves through the Tor network, so websites do not see our server's address.
- You can pick apps that should skip the VPN.

## What the app sends, and to whom

The app talks to three places. You can check each one in the code under
`V2rayNG/app/src/main/java/com/v2ray/ang/ui/chapar/`:

| To | What | Where in the code |
|---|---|---|
| Google | Google sign-in on your phone (Android Credential Manager). | `ChaparActivity.kt` |
| Our API (HTTPS only) | `POST /api/v1/auth/google`: the sign-in token from Google and your nickname. The server checks the token, uses the email address once to check it is a verified Gmail address, and stores only a hash of your Google account ID and your nickname. `GET /api/v1/me`: your account status and usage. `POST /api/v1/connect`: a fresh connection key every time you connect. `POST /api/v1/logout` and `DELETE /api/v1/me`: sign out and delete your account. | `ChaparApi.kt`, `ChaparRepository.kt` |
| Our VPN server | Your traffic, encrypted (VLESS + REALITY, TLS 1.3). | the Xray core |

Name lookups (DNS), so you know who sees them:

- To reach our API and our VPN server, the phone looks up their name with your network's normal DNS, outside
  the VPN, each time you sign in or connect (`HttpUtil.kt`, `CoreConfigManager.kt`). Your network sees that
  you look up that name.
- While connected, your apps' lookups (sent to `1.1.1.1` on the VPN interface) are taken by the core, which
  asks Cloudflare over DNS over HTTPS (`cloudflare-dns.com`) **through the VPN**. Cloudflare sees our
  server's address, not yours (`AppConfig.kt`: `DNS_VPN`, `DNS_PROXY`; `CoreConfigManager.kt`:
  `configureDns`, and the port-53 rule to `dns-out`).

The app accepts connection settings only from our own API, only for our own server, and only with REALITY
and the exact settings our server sends; anything that would turn off encryption or certificate checks, or
send traffic to another host, is refused (`ChaparLinks.kt`, tested in `ChaparLinksTest.kt`). It does not
accept links, QR codes, or subscriptions from anywhere else. The server names its two links `Chapar-Iran`
and `Chapar-Tor`; the app reads the mode from that name (`ChaparModes.kt`).

It does **not** contain analytics, ads, trackers, or crash reporting, and it turns the core's connection log
off (`CoreConfigManager.kt`). The "Report a problem" screen shows fixed texts on your phone
(`ChaparDiagnostics.kt`), under a header with the app and Android version, your nickname, the mode, and
whether you are connected; nothing is uploaded unless you copy it and send it yourself.

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
  their tests in `src/test/.../ui/chapar/`, `handler/ChaparRouting.kt` (tested against the shipped preset in
  `ChaparRoutingTest.kt`), `handler/SocksSession.kt`, `strings_chapar.xml`, the icon, and the build settings
  in `chapar.local.properties.example`.
- Turned off for safety: importing links, QR codes, and URL schemes from other apps (those screens are no longer
  exported, and the camera permission is gone); starting at boot; exported broadcast receivers; cloud backup of
  app data (`backup_rules.xml`, `data_extraction_rules.xml`); cleartext traffic (`network_security_config.xml`).
- Deleted: upstream's scan, switch, and start shortcuts, its URL-scheme importer, the Tasker plugin, the boot
  receiver, and the F-Droid build flavor (sign-in needs Google Play services).
- Changed: the local SOCKS port is random and password-protected, and the core opens it before the tunnel
  connects to it; the core's access log is off; the core's config is never written to a file, and the
  tunnel's config file (which holds the local SOCKS password) is deleted five seconds after the tunnel
  starts; switching modes reloads the core without dropping the tunnel; core starts, stops, and reloads run
  one at a time; the routing sends everything through the VPN except apps you choose.
- Removed upstream files that do not apply to Chapar: the v2rayNG privacy policy, store metadata, and CI
  workflows. Upstream's agent guide is kept as [AGENTS.md](AGENTS.md) with a Chapar section on top; the
  decision codes (`D-0xx`) in comments are explained in [DECISIONS.md](DECISIONS.md).

### Known limits

- The local SOCKS inbound checks the password for TCP only. Xray accepts UDP packets on that port from any app
  on the phone once the tunnel has opened its UDP session; those packets still leave through the VPN, and count
  toward your monthly data (`SocksSession.kt`).
- "Apps that skip the VPN" works per Android user ID: an app that shares its ID with a chosen app (rare, mostly
  system apps) skips the VPN too. The screen says so.

## Build it yourself

1. Clone with the native dependencies:
   `git clone --recursive <this repository>`
2. Build the two native parts, as v2rayNG does: `libv2ray.aar` from `AndroidLibXrayLite` (Go and gomobile; see
   that project's README) into `V2rayNG/app/libs/`, and the tunnel with `./compile-hevtun.sh` (Android NDK).
   The AAR also carries the routing data (`geoip.dat`, `geosite.dat`, `geoip-only-cn-private.dat`), which
   that project's build downloads; the app has no copy of its own. Compare them with ours with
   `unzip -l <apk> 'assets/*.dat'` and `sha256sum`.
3. Copy `V2rayNG/chapar.local.properties.example` to `V2rayNG/chapar.local.properties` and fill in the API
   address and the Google client ID (the values are in our published APK; anyone can read them there). A
   release build stops with an error without them; debug builds and the tests run without them.
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
