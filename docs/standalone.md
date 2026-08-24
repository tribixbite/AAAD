# Standalone target — no server, no accounts, no gate

The defining decision of this fork: **AAAD runs entirely on the device.** No backend to operate,
no account, no entitlement, no payment, no license, no telemetry. The only network traffic is
fetching app metadata and downloading APKs from the publishers who host them — which is inherent
to what the app does, not a service anyone has to run.

Upstream's design is documented in [ARCHITECTURE.md](../ARCHITECTURE.md); its PRO/quota gate in
[§ 7](../ARCHITECTURE.md#7-download-gating-via-pro-subscription).

## What was removed

Already applied to the tree:

| Removed | Why | Was |
| --- | --- | --- |
| `com.google.gms.google-services` plugin | Nothing left to configure | `build.gradle` |
| `google-services.json` requirement | No Firebase project | build input |
| Firebase BoM + Database/Storage/Auth/Functions | Entitlement store, anonymous identity, catalog assets, download-authorization calls | `app/build.gradle` |
| `com.stripe:stripe-android` | PRO purchase | `app/build.gradle` |
| `com.github.blikoon:QRCodeScanner` (+ zxing) | QR license transfer | `app/build.gradle` |
| `commons-net` | NTP clock that made the 30.44-day quota tamper-resistant | `app/build.gradle` |
| `com.android.volley` | Redundant with OkHttp | `app/build.gradle` |
| `AboutPaymentActivity.kt`, `EnterProCode.java`, `TransferLicense.java` | Purchase, promo redemption, license transfer | source |
| `User.java` | Dead 12-line stub, referenced by nothing | source |
| `ProVersionActivity`, `LicenseTransferActivity` manifest entries | Never had source; nothing to reimplement now | manifest |
| `CAMERA` permission + camera `uses-feature` | Existed only for QR scanning | manifest |
| `transfer_license` menu item | Target activity is gone | `res/menu/menu.xml` |
| 14 `buildConfigField` secrets | Stripe keys, Firebase coordinates, 8 per-app APK links | `app/build.gradle` |

Also removed once the mechanism was actually known:

| Removed | Why |
| --- | --- |
| `org.bouncycastle:bcpkix/bcprov:1.82` | Upstream used them only for an on-device signing chain that cannot execute on stock Android. This fork uses Android Keystore and in-process signing for parked side-by-side copies. Evidence: [aa-visibility.md](aa-visibility.md) |

Deliberately kept:

- **Shizuku** — optional for unattended on-phone installs. It runs package-manager sessions as
  shell; it does not produce genuine Play-initiated installs. The host harness uses adb instead.
- **Room / DataStore / WorkManager / Glide** — local catalog cache, preferences, background
  refresh, icons. All local; none of them imply a server.
- **Jsoup / OkHttp** — resolving and downloading APKs from publisher pages that have no API.
- **Unused PRO/payment strings in `res/values*`** — 30 translated locales. Deleting them would
  churn every locale file for no functional gain. They are simply unreferenced.

## Configuration surface

Exactly one optional knob remains, `CATALOG_URL` (env var or `local.properties`):

- **unset** — the app uses the catalog bundled in `app/src/main/assets/` and makes zero metadata
  network calls. This is the default and the intended mode.
- **set** — the app fetches that URL and falls back to the bundled catalog when it is
  unreachable. A GitHub raw file is sufficient; there is nothing to host or run.

Release signing is env-var driven (`RELEASE_KEYSTORE` + friends) and falls back to the debug key,
so a build never fails for want of credentials. See [build-setup.md](build-setup.md).

## Catalog format

Replaces both upstream's remote catalog and its eight hardcoded `*_LINK` build fields.
Bundled at `app/src/main/assets/catalog.json`; the same schema serves a remote `CATALOG_URL`.

```json
{
  "schemaVersion": 1,
  "updated": "2026-08-20",
  "apps": [
    {
      "id": "carstream",
      "name": "CarStream",
      "packageName": "maps.jaoloonda.android",
      "category": "multimedia",
      "descriptionRes": "carstream_description",
      "description": "Optional literal text for user-added repositories",
      "installPolicy": "publisher-unchanged",
      "minSdk": 24,
      "source": { "type": "github-release", "repo": "owner/repo", "assetPattern": "\\.apk$" }
    }
  ]
}
```

`source.type` is one of:

| type | fields | resolution |
| --- | --- | --- |
| `github-release` | `repo`, `assetPattern` | GitHub Releases API → newest matching asset |
| `direct` | `url` | fetched as-is |
| `scrape` | `url`, `selector` | Jsoup selector against the publisher page |
| `manual` | `url` | opened in a browser; the user picks a build (Screen2Auto works this way) |

`category` maps to the existing section strings: `multimedia` (`first_section_name`),
`mirroring` (`second_section_name`), `other` (`third_section_name`). `descriptionRes` names an
existing string resource so the 30 translated locales keep working. `description` is optional
plain text used by user-added repositories; markup is stripped before display.

`installPolicy` defaults to `auto-car-compatible`, which lets AAAD create its side-by-side parked
copy when needed. `publisher-unchanged` preserves the downloaded package name, signature, and car
service; use it for apps such as BluMirror whose own service is the feature being installed. A
foreground publisher-unchanged install uses Android's confirmation UI rather than a Shizuku/shell
initiator. This preserves the publisher artifact but does not guarantee current Android Auto will
admit a sideloaded template app.

### Package names

These come from the manifest `<queries>` block and are **verified** — the app tracks installed
state for exactly this set:

| Package | App |
| --- | --- |
| `maps.jaoloonda.android` | CarStream |
| `me.aap.fermata.auto.dear.google.why` | Fermata Auto |
| `com.github.martoreto.aaremote` | AA Passenger (Martoreto) |
| `de.nsvb.android.auto.w4a` | Widgets For Android Auto |
| `nl.frankkie.nav2contacts` | Nav2Contacts |
| `com.mqbcoding.stats` | Performance Monitor (VAG/MIB2) |
| `com.aatorque.stats` | AATorque |
| `com.kododake.aabrowser` | AA Browser |
| `maps.kiao2client.android` | AAMirror |
| `maps.mobilejiohubclient.android` | AAStream |
| `maps.mobilejiohub.android` | AA Mirror Plus |
| `android.loandamaps.it` | legacy — not in the 2.1 catalog; retained for installed-state detection |

**CarStream's publisher uses a third id again.** `thekirankumar/carstream-android-auto` ships as
**`com.google.android.kk`** — verified by reading the released APK — masquerading as a Google
package. Upstream AAAD then renames *that* to `maps.jaoolonda.android`, and its v2.1 catalog called
it `com.carstream`. Three names for one app, which is why `packageName` is only ever filled in from
an artifact that was actually inspected.

Resolved from upstream's own `assets/app_catalog.json` ([aa-visibility.md](aa-visibility.md#also-recovered)),
which also names `com.carstream` (CarStream 2.0.x), `ru.inceptive.screentwoauto` (Screen2Auto),
`com.aapassenger`, and `com.aawidgets`. `maps.jaoloonda.android` in `<queries>` is likewise a
legacy CarStream package.

**Download URLs still have to be established per app.** Upstream's catalog points at its own
Firebase Storage bucket (`appsforaa-1b443`) with embedded access tokens; this fork resolves
publisher sources instead of leeching that bucket ([TASKS.md](../TASKS.md) T-15). Do not invent
URLs to make the file look complete.

## Behavioural differences from upstream

| | Upstream 2.1 | This fork |
| --- | --- | --- |
| Downloads | 1 per 30.44 days free; unlimited with PRO | Unlimited, always |
| Identity | Firebase Anonymous Auth UID | None |
| Network on launch | Auth + RTDB + catalog | Catalog only, and only if `CATALOG_URL` is set |
| Works offline | No — auth failure ends the activity | Yes, up to the point of downloading an APK |
| Accounts / purchase / license | Stripe + RTDB entitlement | None |
| Install path | System installer, Shizuku on 14+ | Same |
| Package id | `sksa.aa.customapps` | Same for release; `.dev` suffix on debug |
| Secrets to build | 15 `local.properties` keys + `google-services.json` | None |

## Constraints

- **Personal use.** This is a modified private build. `LICENSE.md` is MIT since `b374904`, while
  the README's License section still asserts the older restrictive EULA. The two conflict; the
  fork sidesteps it by not redistributing. Do not publish builds.
- **No re-adding a backend.** If something seems to need a server, it belongs in the harness or
  the dash ([testing-harness.md](testing-harness.md), [agent-dash.md](agent-dash.md)), which run
  on your own machine, not in the app.
- **Never write to upstream's Firebase project.** It no longer has a code path to do so — keep it
  that way.
