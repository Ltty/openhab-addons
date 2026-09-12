# ATAG ONE binding — openHAB guideline conformance review

Reviewed 2026-09-12 against `atagone-binding-5.3.x` @ `763956d37e` (the PR #21481 content), inspected
via `git show`/a temporary worktree, not by switching the working branch. No code was changed by this
review. Sources: the published
[openHAB Coding Guidelines](https://www.openhab.org/docs/developer/guidelines.html) (sections A–G),
the [Bindings](https://www.openhab.org/docs/developer/bindings/) and
[Add-on FAQ](https://www.openhab.org/docs/developer/addons/faq.html) developer docs, the
[Review Checklist](https://github.com/openhab/openhab-addons/wiki/Review-Checklist), the repo's actual
shipped ruleset (`tools/static-code-analysis/`, SAT plugin 0.18.0, `.github/markdownlint.yaml`), and a
direct comparison against lsiepel-authored/recently-merged bindings in this repo.

**Bottom line:** the binding is in materially better shape than the open review threads suggest. The
full build (`clean install`, no `-DskipChecks`) is **green** on the PR branch — 74/74 tests pass,
markdownlint passes, XML validates, and SAT reports zero priority-1/2 findings. Most of the 19 review
threads were already resolved by commit `763956d37e`; they remain open only because no one replied
per-thread. Three threads are genuinely unaddressed and need a decision, not just a reply.

---

## 1. The 19 review threads — verdict for each

| # | File | Comment (abridged) | Verdict |
|---|---|---|---|
| 1 | `AtagOneDiscoveryService.java:75` | "Remove this kind of lines" (banner comment) | **Addressed.** No `//` banners remain on the PR branch; `grep` for `//` in the file finds only 6 genuine why-comments. |
| 2 | `README.md` (table alignment) | "align all tables with your favorite markdown tool" | **Addressed.** All 6 tables in the current README are column-aligned. |
| 3 | `README.md:6` | "each sentence on its own line (apply everywhere)" | **Addressed.** Verified line by line; every prose paragraph is one sentence per line. Table cells (which can't be split) are the only multi-clause lines, which is standard. |
| 4 | `README.md:54` | "wouldn't expect a breaking change for an initial contribution, remove" | **Addressed.** The `## BREAKING CHANGE` section is gone entirely from the current 211-line README. |
| 5 | `README.md:232` (suggestion: add `java` to a bare fence) | | **Addressed.** All 5 fenced blocks now declare a language (`java` ×4, `javascript` ×1). |
| 6 | `CHANGELOG.md` | "we have centralised release notes, please remove this file" | **Addressed.** Not present in the PR branch (confirmed via `git diff upstream/main...atagone-binding-5.3.x`); it lives untracked in `dev-notes/` on the dev branch only. |
| 7 | `DEVELOPERS.md` | "please remove this file" | **Addressed.** Same as above. |
| 8 | `thing-types.xml:36` | "remove these comments in the xml" | **Addressed.** No `<!-- -->` comments anywhere in the PR-branch `thing-types.xml`. |
| 9 | `thing-types.xml:8` | thing-type label restates the brand; labels should be 2–3 words, <23 chars | **Addressed exactly as suggested.** `<label>Thermostat</label>` — matches lsiepel's own suggested diff verbatim. |
| 10 | `thing-types.xml:9` | description shouldn't mention discovery/manual as options | **Addressed.** Current description is one clause: `ATAG ONE smart thermostat.` |
| 11 | `thing-types.xml:118` | (suggestion: remove a line) | **Presumed addressed** — thread is GitHub-flagged `outdated` (diff position shifted); no reason found to doubt it given the surrounding cleanup. Give it a reply rather than re-deriving. |
| 12 | `thing-types.xml:148` | "looks weird" | Same as #11 — `outdated`, presumed addressed, reply and close. |
| 13 | `thing-types.xml:162` | "weird line break (adapt all)" | Same as #11. |
| 14 | `thing-types.xml:224` | "bit verbose, duplicate readOnly info" | Same as #11 — current channel descriptions in this region are all single clauses with no `readOnly` restatement. |
| 15 | `thing-types.xml:316` (suggested exact text: `Total duration the burner has been running.`) | unit shouldn't be named since `unitHint` covers it | **Addressed verbatim.** `burning-hours`'s description now reads exactly `Total duration the burner has been running.` |
| 16 | `addon.xml:11` | "could add a suggestion-finder snippet, UDP broadcast is known" | **Not addressed — open, needs a decision.** See §2. |
| 17 | `thing-types.xml` (~241) | "Property mode ?" — is `Mode` a valid semantic tag | **Resolved by verification.** `Mode` is a standard openHAB Property tag; confirmed in use on 30+ other bindings' channel-types. `preset-mode`'s `<tag>Mode</tag>` (`thing-types.xml:241`, current numbering) is correct and idiomatic. No code change needed — reply confirming this. |
| 18 | `thing-types.xml` (~260) | "+ mode" | Same topic as #17 — likely asking whether an adjacent channel (`heating#control-mode`) should carry the same tag. `control-mode` is currently read-only and unrelated to `preset-mode`'s Control/Mode pairing; recommend tagging it `<tag>Mode</tag>` too for consistency once it's revisited (it's slated to become writable in the plan's Phase E anyway). |
| 19 | `AtagOneHandlerFactory.java:43` | "better to cache the factory and materialize the client on calling the handler constructor" | **Confirmed already fixed.** Current code caches `HttpClientFactory` as a constructor-injected final field and calls `httpClientFactory.getCommonHttpClient()` inside `createHandler()` — exactly the suggested shape. Needs a reply, not a change. |

**Action for the PR:** reply to each thread with its verdict (most can point at the already-shipped
commit `763956d37e`), resolve threads 1–10, 15, 17, 19, and request maintainer input on 16 and 18 rather
than guessing.

---

## 2. Open item — `addon.xml` discovery-methods (thread #16)

No `<discovery-methods>` block exists, despite the UDP broadcast on port 11000 being fully documented
and already implemented in `AtagOneDiscoveryService`. 20+ bindings in this repo ship one; the closest
template is the `ip`/`ipBroadcast` pattern (`org.openhab.binding.wiz/.../addon.xml:11-43`,
`org.openhab.binding.danfossairunit`, `emby`, `jellyfin` all use the same shape).

**Why this isn't a drop-in fix:** every `ipBroadcast` example found sends a `requestPlain` payload and
matches the reply. The ATAG ONE device does the opposite — it broadcasts unsolicited every ~10s and the
binding only listens (`AtagOneDiscoveryService.java`, confirmed passive). Whether core's IP-based addon
finder supports a listen-only mode (no `requestPlain`) wasn't confirmed from this repo alone — the
finder implementation lives in `openhab-core`, not `openhab-addons`. **Recommend asking lsiepel directly**
whether a listen-only variant exists or is expected to be added, rather than shipping a finder
configuration that might silently never match. This is a genuine open question, not a style nit — flag
it as such in the reply rather than resolving the thread unilaterally.

---

## 3. Guideline sections A–G — walkthrough

**A. Directory and file layout.** All required files present in the right place (NOTICE, feature.xml,
addon.xml, config.xml, i18n). License header text, `/*` style, and `2010-2026` year range all verified
correct on every `.java` file. No stray `.classpath`/`.project`/IDE files. `CHANGELOG.md`/`DEVELOPERS.md`
correctly absent from the bundle (see §1, threads 6–7).

**B. Formatting and naming.** Thing/channel/channel-group/channel-type IDs are `lower-case-hyphen`
throughout (`preset-mode`, `control`, `ch-water-temperature`, …). Config params are `camelCase`
(`hostname`, `refreshInterval`, `clientId`). `thing-types.xml` validates against
`thing-description-1.0.0.xsd` (confirmed — the build's `xml:validate` goal passed clean). One
**genuine, low-priority finding**: SAT's `MemberNameCheck` flags 75 fields across 10 DTOs
(`ch_control_mode`, `dhw_temp_setp`, `start_vacation`, …) for not matching `^[a-z][a-zA-Z0-9]*$`. These
are deliberate — they mirror the device's raw JSON field names exactly so Gson can bind them without
`@SerializedName` boilerplate, the same pattern DTOs everywhere in this repo use, which is presumably why
this repo's own DTO suppressions list already exempts `NullAnnotationsCheck` and javadoc checks for
`dto/**` — `MemberNameCheck` just isn't in that exemption list. All 75 are priority 3 (informational),
don't fail the build, and don't warrant renaming (that would break Gson's implicit field mapping without
adding `@SerializedName` to every field for no real benefit). **No action needed**, but worth a one-line
note in a PR reply if lsiepel raises it, since it could otherwise look like an oversight.

**C. Documentation / Javadoc.** `@author Florian Lettner - Initial contribution` present on all 18 main
classes and correctly suppressed for DTOs per this repo's own rules — appropriate, not a gap. Method
javadoc coverage is uneven but that's expected under this repo's suppression of `JavadocMethod`/
`MissingJavadocFilterCheck` inside `internal/`: `AtagOneApiClient`'s public entry points are documented,
`AtagOneHandlerFactory`'s two `@Override`s are not (correct — matches `ZwaveJSBridgeHandler`'s pattern of
zero method javadoc on trivial overrides).

**D. Language levels and libraries.** Java 21 only; `HttpClientFactory` injected via `@Reference`, never
self-instantiated (`AtagOneHandlerFactory.java` — also the exact pattern thread #19 confirmed). No
forbidden-package imports found (no Guava, Apache Commons, JUnit 4, `tech.units`, `si.uom`).

**E. Runtime behavior.** `initialize()` schedules `connect()` via `scheduler.submit()` rather than
blocking — correct. Polling uses `scheduleWithFixedDelay`, not `scheduleAtFixedRate`, matching the
guideline's explicit preference. The generation-token pattern in `AtagOneHandler` (bumped in
`initialize()`, checked after every blocking call) is exactly the kind of lifecycle-safety pattern the
guideline's "bundles must start/stop cleanly" rule is aiming at, and is a level above what most reviewed
bindings bother with.

**F. Logging.** Logger is `private final Logger logger = LoggerFactory.getLogger(...)` — correct,
non-static (confirmed no SpotBugs `SLF4J_LOGGER_SHOULD_BE_NON_STATIC` finding). Connection drops are
reported via `updateStatus(OFFLINE, COMMUNICATION_ERROR, ...)`, not `logger.warn`/`error` — matches the
guideline exactly. All user-visible `updateStatus` detail strings use `@text/` i18n keys
(`offline.conf-error.hostname-missing`, etc.); only 3 `@text/` refs total, on the low side compared to
`zwavejs` (12) and `remehaheating` (6) — worth adding i18n'd status text for more of the OFFLINE paths
in a future phase, but not a blocker.

**G. Attributions.** No copied code identified beyond openHAB scaffolding; `NOTICE` is the standard
EPL-2.0 template.

**Null annotations.** `@NonNullByDefault` present on every non-DTO class; DTOs correctly left
unannotated (Gson-friendly, matches the `zwavejs`/`homewizard` pattern exactly).

---

## 4. Bindings guide / FAQ conformance

- **Handler lifecycle** — non-blocking init, resource cleanup in `dispose()` (poll job cancelled,
  in-flight `connect()` future cancelled), meaningful `ThingStatusDetail` values throughout. Matches
  guide expectations.
- **Discovery `representationProperty`** — `deviceId` is set (`thing-types.xml:20`), preventing
  duplicate Inbox entries across restarts. Correct.
- **Highest-capability channel rule (FAQ)** — no violations found; `preset-mode` is a single `String`
  channel with the full option set rather than separate per-mode switches, which is the right shape.
- **Units of Measurement** — every dimensioned channel is a proper `Number:<Dimension>` with
  `QuantityType`, not a bare `DecimalType` (Copilot's original `dhw-flow-rate` finding was fixed in
  `425ed48`, confirmed still correct on the PR branch: `Number:VolumetricFlowRate` +
  `Units.LITRE_PER_MINUTE`). **One residual instance of the same underlying pattern**, found by applying
  lsiepel's own "don't restate the unit when `unitHint` exists" rule (already applied to `burning-hours`,
  thread #15) to the three sibling duration channels that weren't touched:
  - `vacation-duration` (`thing-types.xml`, `unitHint="d"`): description still says *"in whole days"*.
  - `fireplace-duration` (`unitHint="h"`): description still says *"in whole hours"*.
  - `extend-duration` (`unitHint="h"`): description still says *"in whole hours"*.

  **Recommend applying the exact same fix lsiepel already approved for `burning-hours` to these three** —
  drop the unit mention, e.g. `"How long holiday mode runs for. Setting this stores the duration for next
  time — it does not start holiday mode. Resets to 0 each time holiday mode ends."` This is a genuinely
  new, concrete, low-risk finding (not from the existing threads) worth fixing before the next push,
  since it's the identical pattern the maintainer already flagged once.

---

## 5. Comment and javadoc audit

Consistent with the earlier structural survey: **no narrative or step-by-step comments found anywhere in
the PR-branch code.** `AtagOneDiscoveryService.java` and all 10 DTOs are comment-free except protocol
notes (matches house style rule #5 from the exemplar comparison — DTOs and discovery are comment-free in
every reference binding surveyed). Two low-value leftovers, both in test code, not shipped binding logic:

- `AtagOneApiClientLiveTest.java:216` — `// Should complete without throwing` (restates the assertion).
- `DtoParsingTest.java:127` — `// Renamed fields` (near-meaningless without more context).

**Density finding:** `AtagOneHandler.java` carries roughly 1 `//` comment per 20 lines on the PR branch,
denser than even the heaviest reference binding surveyed (`zwavejs`'s `ZwaveJSNodeHandler.java` at 1 per
29 lines, itself flagged as the *ceiling*, not the target, in the exemplar comparison). Individually,
every comment in `AtagOneHandler` passes the why-not-what test — Agent-verified samples cite protocol
quirks, concurrency invariants, and deliberate deviations, never narration. This is very likely why
lsiepel's specific ask was about volume/clutter rather than quality: **recommend a pass to consolidate
the longest 5–7 line rationale blocks** (e.g. the generation-token and commandLock explanations) into
`dev-notes/DEVELOPERS.md`, leaving a one-line pointer comment in the code — this keeps the invariant
documented without the current per-block essay length, addressing "cause clutter" literally rather than
by removing information.

---

## 6. Structural comparison against maintainer-authored bindings

Compared against `zwavejs` (lsiepel's newest, primary exemplar), `homewizard` (closest structural
match — polling local-IP device, mDNS discovery, HTTP client), and `remehaheating` (newest boiler
binding, same domain):

| Metric | atagone | zwavejs | homewizard | remehaheating |
|---|---|---|---|---|
| README lines | 211 (post-cleanup) | 112 | 193 | 145 |
| `@text/` i18n refs | 3 | 12 | 12 | 6 |
| Ships unit tests | Yes (7 classes) | Yes | Yes | Yes |
| DTO comments | 0 | 0 | ~1 | 0 |
| Discovery comments | 0 | 0 | 0 | — |

README length (211) is now within a reasonable range of the largest exemplar (`homewizard`, 193) —
not a concern after the cleanup already applied. The i18n gap (3 refs vs. 6–12 in exemplars) is the one
number worth acting on: several `OFFLINE` paths in `AtagOneHandler` likely still log plain-English
detail strings where an exemplar would use `@text/offline.comm-error-...`. Low priority, good Phase I
backlog item — not blocking this PR.

Test structure matches the established pattern well: `AtagOneHandlerTest` and `DtoParsingTest` mirror
the `*HandlerMock` + fixture-JSON approach used by every lsiepel-adjacent exemplar (`homewizard`,
`zwavejs`). `AtagOneApiClientLiveTest` (gated on `-Datag.host`, 3 tests skipped when unset) is unusual —
none of the four exemplars ship a live-device-gated test class — but it's a reasonable, opt-in addition
given this project's live-verification discipline, not a deviation to fix.

---

## 7. Mechanical checks — results

Ran directly against the PR branch content via a temporary worktree (`atagone-binding-5.3.x` @
`763956d37e`), not the working `atagone-retest-5.2.x` checkout:

```
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
.\mvnw.cmd clean install -pl :org.openhab.binding.atagone
```

- **BUILD SUCCESS.** Markdownlint: 0 issues. `xml:validate`: passed. Tests: **74 run, 0 failures, 0
  errors, 3 skipped** (the 3 skipped are `AtagOneApiClientLiveTest`'s `@EnabledIfSystemProperty` gate,
  correctly inactive without a real device).
- **SAT report** (`target/code-analysis/report.html`): **zero priority-1 or priority-2 findings.** 79
  total findings, all priority-3 (informational): 75 `MemberNameCheck` (the deliberate snake_case DTO
  fields, §3.B above), 2 `ImplicitDefaultLocale` (`s.toString().toLowerCase()` at
  `AtagOneHandler.java:309` and `AtagOneApiClientLiveTest.java:108`), 2 `ImplicitDefaultTimeZone`
  (`ZonedDateTime.now()` at `AtagOneHandler.java:479,763`). None of these fail the build or need fixing
  before merge; the `ImplicitDefaultLocale`/`TimeZone` ones are cheap to silence later
  (`Locale.ROOT`/`ZoneId` explicit) if picked up as Phase I polish.
- **Contrast with the dev branch:** `atagone-retest-5.2.x`'s equivalent full build currently **fails**
  — markdownlint errors on `DEVELOPERS.md:601` (`MD049/emphasis-style`: asterisk used where underscore
  is required). This file doesn't exist on the PR branch, so it doesn't affect PR #21481, but it means
  `mvn clean install` (no skip flags) is currently red on the branch new development happens on. Fix
  before the next phase's live-test gate, since that gate requires a clean full build.
- `mvn i18n:generate-default-translations` and a full `spotless:check` were not run separately in this
  pass — `spotless:apply` was already applied as part of `clean install`'s `initialize` phase (bound at
  that phase in the parent POM) and produced no diff, which is equivalent confirmation for this bundle.

---

## 8. Repo hygiene (outside the bundle, PR-adjacent)

- `portal-mode-transitions.har` and `portal.atag-one.com.har` sit untracked at the repo root, covered by
  neither `.gitignore` nor `.git/info/exclude`. A `git add -A` would sweep them into any future commit.
  **Recommend adding `*.har` to `.git/info/exclude`.**
- `.claude/` is present, untracked, and unignored by either file (only specific runtime sub-paths inside
  it are excluded). Low risk since it's currently empty, but worth the same treatment if it starts
  collecting session state.
- The `CLAUDE.md` entry in `.git/info/exclude:9` is now **dead** on the PR branch specifically — upstream
  added its own tracked `CLAUDE.md` there on 2026-08-14, so exclusion can never apply to it (exclusion
  only affects untracked paths). Not harmful, but reads as protection it no longer provides on that
  branch. (The local agent guide for this project now lives at `C:\public\CLAUDE.md`, outside the repo
  entirely, specifically to avoid this collision — see project `CLAUDE.md` there for details.)

---

## 9. Dev-branch (`atagone-retest-5.2.x`) divergence — report only, not fixed here

Per the agreed workflow, these ride along with the next squash-port rather than being fixed on the dev
branch now:

- **24 Unicode box-drawing section banners** (`// ── Name ───…`) across 6 files — the exact pattern
  lsiepel's thread #1 flagged. Already removed on the PR branch; still present on
  `AtagOneHandler.java`, `AtagOneBindingConstants.java`, `AtagOneDiscoveryService.java`,
  `DeviceConfigDTO.java`, and `AtagOneApiClientLiveTest.java`.
- `CHANGELOG.md` (93 lines) and `DEVELOPERS.md` (603 lines) — correctly kept out of the bundle on the PR
  branch; still present in the bundle root on the dev branch (not just `dev-notes/`). Confirm these
  don't get re-added to the bundle on the next port; the `dev-notes/` copies are the ones to keep.
- The README's `## BREAKING CHANGE` section and the unfixed unit-in-description wording on
  vacation/fireplace/extend-duration channels (§4 above) are present on both branches — fix once, then
  port, rather than fixing twice.
- The dev branch's full build is currently red (markdownlint on `DEVELOPERS.md`, §7) — fix before the
  next live-test-gated phase.
