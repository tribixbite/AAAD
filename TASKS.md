# AAAD fork — Task Backlog

Working file. `go` = pick the next unchecked task in the lowest incomplete phase, do it fully,
commit, and tick it here. Reorder if priority genuinely changed; add tasks as they surface.

Task ids are stable — cite them in commits (`feat(build): add gradle scaffolding (T-01)`).

Status: `[ ]` todo · `[~]` in progress · `[x]` done · `[!]` blocked (say why inline)

---

## Phase 0 — Repo navigation (done)

- [x] **T-00** Agent-facing docs: `CLAUDE.md`, `ARCHITECTURE.md` (incl. the PRO gating spec),
  `TASKS.md`, `docs/build-setup.md`, `docs/testing-harness.md`, `docs/agent-dash.md`, `.gitignore`.

## Phase 1 — Make the tree build

Nothing downstream is testable until an APK comes out of this repo. Detail:
[docs/build-setup.md](docs/build-setup.md).

- [ ] **T-01** Add Gradle scaffolding: `settings.gradle`, `gradle.properties`, Gradle wrapper
  matching AGP 8.13.1, `app/proguard-rules.pro`, `local.properties.example`.
  *Done when:* `./gradlew projects` succeeds and lists `:app`.
- [ ] **T-02** Create a private Firebase project; drop in `app/google-services.json`; fill
  `local.properties` with the 15 keys (Stripe values may be dummies at this stage).
  *Done when:* `./gradlew :app:processDebugResources` succeeds. **Never commit either file.**
- [ ] **T-03** Decide the recovery strategy for the 12 missing classes and record it in
  `docs/build-setup.md`: reimplement against the `res/`+manifest contract, or recover from the
  official release APK for reference (personal use only, MIT `LICENSE.md`).
  *Done when:* the decision and its rationale are written down, not just made.
- [ ] **T-04** Reimplement the minimum spine to a running app: `AuthManager`, `utils/Logger`,
  `utils/applyBottomInsetPadding`, `LauncherActivity`, `MainActivityNew`,
  `receivers/PackageInstallReceiver`. Stub the rest to `finish()` so the manifest resolves.
  *Done when:* `assembleDebug` produces an APK that launches to a catalog screen on device.
- [ ] **T-05** Re-add a debug `signingConfig` so builds don't require the upstream keystore.
  Note the consequence: a different signing key means a different `ANDROID_ID` and no upgrade
  path over an installed official build — the dev build must use a distinct `applicationId`
  suffix so both can coexist.

## Phase 2 — De-gate for personal use

Cut points and the hard constraint about upstream's database:
[ARCHITECTURE.md § 10](ARCHITECTURE.md#10-cut-points-for-a-de-gated-dev-build).

- [ ] **T-10** Introduce product flavors `dev` and `upstream`, with `BuildConfig.GATING_ENABLED`.
  *Done when:* both flavors assemble.
- [ ] **T-11** Put entitlement behind an interface (`EntitlementSource`) with two implementations:
  Firebase-backed, and a local always-PRO one for `dev`. Route every download entry point through it.
  *Done when:* the `dev` flavor performs unlimited downloads with **zero** network calls to Firebase.
- [ ] **T-12** Resolve the three open questions in
  [ARCHITECTURE.md § 7.3](ARCHITECTURE.md#73-v21--what-changed): the callable function name and
  payload, whether `lastdownload` is still the storage key, and the unit of the `"date"` extra.
  Evidence: decompile of the official APK, or a logcat/network capture of a real 2.1 build.
  *Done when:* those claims move from **[I]** to **[V]** in ARCHITECTURE.md with citations.
- [ ] **T-13** Make the `dev` flavor fully offline-capable: local catalog JSON fallback when
  `APP_CATALOG_URL` is unreachable, so the harness can run without network.
- [ ] **T-14** Strip or neutralize telemetry-ish writes in `dev` (`stripe_transactions`,
  `user_emails`) so a misconfigured build cannot post to any live backend.

## Phase 3 — Android Auto app testing platform

Design: [docs/testing-harness.md](docs/testing-harness.md).

- [ ] **T-20** `harness/` skeleton (TypeScript, bun): device discovery, adb wrapper with the
  rotating-port reconnect this box needs, structured run logging to JSONL.
- [ ] **T-21** Catalog-driven install matrix: for each app in the catalog × each connected device,
  install → launch → screenshot → record result.
- [ ] **T-22** Android Auto visibility check — the assertion that actually matters: after install,
  determine whether AA lists the app. Decide the probe (AA app-list dump vs. DHU vs. on-screen
  capture) and document why.
- [ ] **T-23** Screenshot pipeline honouring this device's constraints: no dimension ≥ 2000 px,
  file < 4 MB, auto-compress, per-run directory.
- [ ] **T-24** Regression baselines: store per-app expected outcomes, diff each run, report
  newly-broken apps.
- [ ] **T-25** Instrumented tests for the parts of the app worth pinning (`Version` comparator,
  entitlement interface, catalog parsing) — `androidTest` deps are already declared.

## Phase 4 — Agent dash

Design: [docs/agent-dash.md](docs/agent-dash.md).

- [ ] **T-30** Dash skeleton served from Termux: device inventory + live adb status.
- [ ] **T-31** Surface harness run history: matrix view, per-app timeline, screenshot gallery.
- [ ] **T-32** Task queue view backed by this file, so agent progress is visible without reading md.
- [ ] **T-33** Catalog inspector: upstream catalog vs. installed state per device, update deltas.
- [ ] **T-34** Decide integration depth with `../operad` (separate service vs. registered session)
  and record it in `docs/agent-dash.md`.

## Phase 5 — App changes worth making for personal use

- [ ] **T-40** Update checker for installed AA apps — README calls it out as never-implemented;
  `Version.java` and the catalog make it straightforward.
- [ ] **T-41** Shizuku-first install path with a clean fallback, to skip the system installer
  entirely during harness runs.
- [ ] **T-42** Local catalog override (a file on the device) so new/unlisted APKs can be tested
  without waiting on the upstream catalog.
- [ ] **T-43** Structured logging to a file that the harness can pull with `adb pull`.

---

## Notes and decisions

Append dated entries as decisions are made — this is the fork's decision log.

- **2026-08-20** — Fork initialized for documentation. No code changes yet; every commit in
  history is upstream's. Established: docs mark claims **[V]**/**[H]**/**[I]**; dev builds must
  never write to upstream's Firebase project; personal use only, no redistribution.
