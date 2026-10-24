# Decisions referred to in the code

Comments in this app cite decisions as `D-0xx`. The full log lives in Chapar's server repository, which is
not public; this file lists, in one or two lines each, every decision the app's code refers to.

| Code | Decision |
|---|---|
| D-021 | The app's interface is in English only. Strings stay in Android string resources, so a translation can be added later. |
| D-038 | Two modes: **Iran** (the default) and **Tor**. Older links named "Free" are Iran mode. |
| D-039 | The service is named Chapar; the package ID is `org.chapar.vpn`. The server names its links exactly `Chapar-Iran` and `Chapar-Tor`. |
| D-041 | Sign-in only with a verified `@gmail.com` Google account. The server checks this from the verified token, uses the email address only for that check, and never stores it. |
| D-042 | Android app only. The user signs in and taps Connect; the app gets its connection settings from our API. No QR codes, share links, subscriptions, other clients, or importing from other apps. |
| D-043 | Every Connect gets a new connection key; the server cancels the previous one at the same moment. One device per account at a time. When the API cannot be reached at all, the app retries with its last key. |
| D-046 | In Iran mode, Iranian destinations leave through a server in Iran, so they see an Iranian address. The app has no part in this; it is server routing. |
| D-047 | Fixes after a review: links from Connect are checked before import, the local SOCKS port is random and password-protected, the routing preset sends everything through the VPN, no backup and no cleartext, Connect cannot get stuck, the "Block leaks" hint, and "Apps that skip the VPN". |

## Files named in comments that are not in this repository

Some comments name `SECURITY.md`, `ARCHITECTURE`, `backend/chapar/…`, or `server/main/…`. These are in the
server repository. What they say about the app's side is in [README.md](README.md): what the app sends, to
whom, and what it keeps on the phone.
