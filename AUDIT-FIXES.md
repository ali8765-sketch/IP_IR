# Code audit of 2026-10-23: what we changed

An outside reviewer audited this repository at tag `v0.1.0`: structure, security, and
bugs in the app module (`V2rayNG/app`). The submodules were not part of it. This file lists every finding
and what we did with it, so you can check each one in the code.

Status in short: **all findings are fixed except seven**, which we left on purpose (reasons below), and two
claims of the audit turned out to be wrong. These changes are in the source, but no APK with them is
released yet: the published 0.1.0 APK does not contain them.

## How we checked the changes

- `./gradlew :app:testPlaystoreDebugUnitTest`: 183 tests, 0 failures (152 before). New tests:
  `ChaparViewModelTest`, `ChaparRoutingTest`, `ChaparManifestTest`, `FmtBaseQueryTest`; `ChaparLinksTest`
  and others extended.
- `:app:assemblePlaystoreDebug`, `:app:assemblePlaystoreRelease`, and `:app:lintVitalPlaystoreRelease` pass.
- The release APK asks for the same permissions as 0.1.0 (`aapt2 dump permissions`), contains none of the
  deleted components, and still carries the routing data files.
- **Not checked yet:** anything that needs a phone or an emulator (starting and stopping the VPN, switching
  networks, switching modes, the bypass restart, the quick-settings tile). We test these on a phone before
  the next release.

## Must fix

| # | Finding | What we did | Where |
|---|---|---|---|
| 1 | A link from the API was trusted after a host-only check | Fixed. A link must now use `security=reality` with non-empty `sni`, `fp`, `pbk`, and `sid`; only the parameters our server sends are allowed (no `allowInsecure`, `extra`, `fm`, `headerType`, `host`, `path`, …); `type` must be `tcp`; a parameter may appear once; the name must be `Chapar-Iran`, `Chapar-Tor`, or `Chapar-Free`, optionally with `-<tag>`. A refused answer is recorded as `link_refused` in "Report a problem". | `ui/chapar/ChaparLinks.kt`, `ChaparLinksTest.kt` |
| 2 | Routing data file missing from the build | Not a bug. The three `.dat` files come inside `libv2ray.aar`, which step 2 of "Build it yourself" builds from `AndroidLibXrayLite`; our APK carries them from there. The README now says so and how to compare them. | `README.md` |
| 3 | Documentation points at missing files and decisions | The tag `v2rayNG-2.3.11` does exist (the reviewer's copy had no tags: `git fetch --tags`). Added the root `AGENTS.md` that the two folder guides refer to (upstream's guide plus a Chapar section), `DECISIONS.md` for the `D-0xx` codes in comments, and fixed the daemon process name (`:daemon`) and the main-screen note in the folder guides. | `AGENTS.md`, `DECISIONS.md`, `service/AGENTS.md`, `ui/AGENTS.md` |
| 4 | Routing helpers dead, routing rule untested | Fixed. `ChaparRoutingTest` loads the shipped preset and checks: everything through the proxy, QUIC blocked, only local addresses direct. Unused members (`isConfigured`, `hasSession`, `serverIdsOf`) removed. | `handler/ChaparRouting.kt`, `ChaparRoutingTest.kt` |

## Should fix

| # | Finding | What we did |
|---|---|---|
| 5 | An app removed from "Apps that skip the VPN" stayed outside the VPN until a reconnect | Fixed: saving a changed list while connected restarts the VPN at once. |
| 6 | A refused VPN start was silent | Fixed: a message explains it (permission refused, or another app's always-on VPN). |
| 7 | Routing preset written by two owners | Fixed: only the migration stores the Chapar preset. |
| 8 | UI process opened an unused SOCKS session | Fixed: removed; only the daemon's services create one. |
| 9 | Local SOCKS: UDP without password; port handed to the tunnel before the core bound it | Port race fixed: the core now binds the port before the tunnel connects to it, so another app holding the port makes the start fail instead of receiving traffic. The UDP part is left (see below). |
| 10 | Removed keys stayed readable in storage | Fixed: sign-out also truncates the profile storage files. |
| 11 | `ChaparViewModel` untestable, tied to a 30-member upstream interface | Fixed: it now uses a small `ChaparPlatform` interface and has its own tests. |
| 12 | Mode read with a regex from the link name | Fixed: only the word after `Chapar-` decides, and the name contract is enforced by finding 1. |
| 13 | "API host equals VPN host" fails without a reason | A refused link is now recorded as `link_refused`. The separate host setting was not added (see below). |
| 14 | Dead upstream entry points kept and even modified | Fixed: deleted the scan, switch, and start shortcuts, the URL-scheme importer, the Tasker plugin, and the boot receiver. `ChaparManifestTest` fails if an upstream merge re-adds an exported component, a second launcher, the boot receiver, or the camera permission. |
| 15 | Launcher activity inherited QR-scanner and file-picker helpers | Fixed: `ChaparActivity` extends `BaseComponentActivity` and holds only a permission helper. |
| 16 | A release built without `chapar.local.properties` | Fixed: the release build stops with an error; debug builds and tests still run without it. |
| 17 | Tests covered the wrong things | Tests added for the ViewModel, the routing preset, the manifest, and the link parser. |
| 18 | The `fdroid` flavor could not work | Fixed: removed (sign-in needs Google Play services), together with the unused `dev` and `pre_release` folders. Task names stay `…Playstore…`. |
| 19 | No certificate pinning | Not done (see below). |

## Nice to have

| # | Finding | What we did |
|---|---|---|
| 20 | Watchdog could fire while the screen was in the background | Fixed: it starts only when the start is really sent. |
| 21 | Unbounded response bodies | Fixed: answers over 64 KiB are refused. |
| 22 | Root-mode tunnel config not deleted | Not done (see below). |
| 23 | Mode label in two places | Fixed: `ChaparMode.label`. |
| 24 | Links checked twice | Kept on purpose (see below); the comment explains both checks. |
| 25 | Two parsers of the same link | Documented: a link that the two parsers could read differently is refused by finding 1. |
| 26 | Settings that Chapar ignores | Each key now has a note so a merge does not wire it back. |
| 27 | Tile and widget named "Switch" | Renamed to "Chapar". |
| 28 | Minify (R8) off | Not done (see below). |

## Runtime bugs

| # | Bug | What we did |
|---|---|---|
| B1 | A mode switch during a network change was dropped | Fixed: core reloads and stops now run one at a time, in order, and each reload reads the newest selected mode. A reload that cannot run reports "not running". |
| B2 | Disconnect racing a reload left a core running | Fixed by the same ordering: a stop always runs after a reload that started before it. |
| B3 | After a failed reload the screen could wait 45 s for the start lock | Fixed: the daemon reports such a service as failed, so the next Connect stops it first. |
| B4 | Connect after a failure threw the start away | Fixed: the "stopped" answer to that stop keeps the screen on Connecting. |
| B5 | A non-numeric usage value crashed the app on every resume | Fixed: it is treated as a bad answer. |
| B6 | "Core already running" after a quick reconnect was silent | Fixed: "stopped" is sent after the core really stopped (the main thread waits at most 3 s), and "already running" is reported as a failure. |
| B7 | Tunnel start result ignored | Fixed: if the tunnel does not start, the VPN stops and the screen shows the failure. |
| B8 | Cancel during a start could be lost | Fixed: a start that goes through after a cancel is stopped again (only for that start). |
| B9 | The screen asked the daemon for its state only once | Fixed: it asks on every resume, and a missing daemon counts as not running. |
| B10 | The stuck-lock reset could tear down a working VPN | Fixed: the lock is reset only when no core is running. |
| B11 | The query parser threw on a parameter without `=` | Fixed (`FmtBase.getQueryParam`), with a test. |
| B12 | Tor chosen but no Tor link: connected in Iran mode silently | Fixed: the app does not start and says the mode is not available. |
| B13 | Bypass by package, applied per Android user ID | Platform behaviour; the bypass screen and the README say so. |

The swallowed exception in `CoreServiceManager.stopCoreLoop` is now logged, and a failed stop is reported
on the next start. The other swallowed exceptions the audit listed (network monitor registration, link
import, base64 noise, profile decoding) are unchanged; none of them can leak traffic.

## Left on purpose

- **Certificate pinning (19).** Our server's certificate is renewed automatically every few months and its
  issuer can change. The app has no update channel yet, so a pin could cut off every user at once. The
  damage a forged API answer could do is now limited by finding 1: it can only describe a REALITY link to
  our own host.
- **R8 / minify (28).** It can break reflection-based code (Gson) at run time and needs a test on a phone
  first.
- **Root-mode file (22).** Root mode cannot be turned on in Chapar.
- **A separate VPN host setting (13).** The API and the VPN use one domain by design; a second setting would
  only add a way to misconfigure the app.
- **One of the two link checks (24).** `ChaparLinks` and `ChaparRepository` check independently; keeping
  both costs nothing.
- **UDP password on the local SOCKS port (9).** Xray accepts UDP for an open association from any local
  app; it cannot check a password per packet. Such packets still leave through the VPN. Turning UDP off
  would break DNS and other UDP traffic. Described under "Known limits" in the README.
- **The audit's tooling (its section 7).** The added workflows need a paid API key; they are not part of
  this repository.

## Also changed

- README: where name lookups (DNS) go, the stricter link check, what the "Report a problem" header contains,
  and a correction: the core's config is never written to a file; the tunnel's config file (with the local
  SOCKS password) is deleted five seconds after the tunnel starts.
- `kotlinx-coroutines-test` added for unit tests only; the app's own dependencies are unchanged.
