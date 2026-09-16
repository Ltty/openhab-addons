# ATAG ONE Local API — Developer Reference

This document specifies the ATAG ONE thermostat's local HTTP/JSON API as reverse-engineered and
live-tested during development of this binding. It is the authoritative reference for anyone
extending the binding — README.md covers user-facing setup and channels; this covers the wire
protocol underneath.

## Verification status legend

Every field and behavioural claim below carries exactly one status:

- **VERIFIED** — observed on a live device with a specific, reproducible result. The observation is
  cited.
- **INFERRED** — from the cloud portal's HAR capture, a reference library (`pyatag`, the Home
  Assistant ATAG integration, `kozmoz/atag-one-api`), or field naming. Plausible, not device-tested.
  Treat as a hypothesis, not a fact, until retested.
- **UNKNOWN** — the field's value is recorded but its meaning is undetermined. No source, live or
  documented, explains it.

**An INFERRED item is never stated as fact in this document or in code comments derived from it.**
That discipline is not procedural box-ticking — extend mode's write semantics went through three
contradictory theories in one day earlier in this project specifically because an inferred
assumption (`control.extend_duration` mirrors `configuration.ch_mode_extend`) was carried forward
as settled fact. Retest before relying on anything marked INFERRED.

All data below is cross-checked against a full `/retrieve` snapshot taken 2026-08-27
(`atag-full-retrieve-snapshot.json`, not committed to the repository — device-specific capture, kept
alongside this doc during development only).

## Sources

- Live device testing against a real ATAG ONE, throughout this project.
- Cloud portal HAR captures (`portal-mode-transitions.har`, `portal.atag-one.com.har`, both untracked
  at the repo root).
- `pyatag`, the Home Assistant ATAG integration, `kozmoz/atag-one-api` (reference libraries/wikis).
  The latter's [Thermostat Protocol wiki page](https://github.com/kozmoz/atag-one-api/wiki/Thermostat-Protocol)
  is what corrected the `boiler_status` bit assignments (2026-09-14) — see that section — this
  binding's own values had never been independently checked against it before.
- **The official _ATAG ONE App and Portal User Guide_** (v171116, 48 pp.), read in full 2026-09-13
  (Phase G). The vendor's own description of every setting, with ranges, defaults, and behavior —
  added late in this project but the single highest-value source once found: it resolved the
  outdoor-temperature-correction ambiguity, corrected ten wrong channel bounds, found a real
  extend-duration granularity bug, and gave `control.dhw_mode` its first sourced hypothesis. Cited by
  page number throughout (e.g. "manual p. 20") wherever it is the basis for a claim.

## Transport

| | |
|---|---|
| Base URL | `http://<hostname>:<port>` — `port` defaults to `10000` |
| Method | `POST`, all three endpoints |
| Content type | `application/json` |
| HTTP version | **HTTP/1.0**, verified |
| Connection | **`Connection: close`**, verified — the device closes every connection after responding |
| Rate limit | **1000 ms minimum between requests** (`MIN_INTERVAL_MS` in `AtagOneApiClient`), enforced by a synchronized rate limiter |
| Timeout | 15 s per request (`REQUEST_TIMEOUT_S`) |
| Retries | Up to 7 (`MAX_RETRIES`) on transient `EOFException`/`SocketTimeoutException`. The first `EOFException` on a request is treated as a free retry (stale pooled connection from the device's HTTP/1.0 close-per-request behaviour) and does not count against the limit |

**Updated, Phase A (2026-09-13):** the original constants (2000 ms / 5 s / 5 retries) were
live-verified against the deployed jar and found more conservative than needed; the values above
(1000 ms / 15 s / 7 retries) shipped after passing that verification with no regression in
`OFFLINE`/`COMMUNICATION_ERROR` frequency. The device's actual minimum inter-request tolerance is
still not independently isolated — 1000 ms is simply what the shipped code now enforces, not a
device-confirmed floor. Open question #9 below tracks finding the real figure.

Curl commands used for manual testing must explicitly force `--http1.0 -H "Connection: close"` —
curl's default (HTTP/1.1, keep-alive) does not match this device's behaviour and was not used
consistently during earlier manual testing sessions, which is a plausible confound for some
intermittent instability observed during that testing.

## Discovery (UDP)

| | |
|---|---|
| Port | `11000` |
| Payload | ASCII, prefix `"ONE "` followed by `<device_id>` and an optional status suffix, e.g. `ONE 6808-1500-1805_17-09-001-042 ...` |
| Broadcast interval | ~10 s, VERIFIED |
| Background scan interval | 30 s (`BACKGROUND_SCAN_INTERVAL_S`) |
| Manual scan window | 30 s (`MANUAL_DISCOVERY_TIME_S`), socket timeout 15 s (`SOCKET_TIMEOUT_MS`) |

## Endpoints

### `POST /pair`

```json
{"pair_message": {"seqnr": 0, "accounts": [
  {"user_account": "", "mac_address": "<client_id>", "device_name": "openHAB", "account_type": 0}
]}}
```

Response: `{"pair_reply": {"seqnr": 0, "acc_status": <1|2|3>}}`

`account_type` VERIFIED present in every pairing request this binding sends; its meaning beyond `0`
is UNKNOWN — `kozmoz/atag-one-api`'s wiki documents `0 = user, 1 = service` but no local-API test has
ever paired with `1` (INFERRED only, from that library's docs, not from this device).

### `POST /retrieve`

```json
{"retrieve_message": {"seqnr": 0, "account_auth": {"user_account": "", "mac_address": "<client_id>"}, "info": <bitmask>}}
```

Response: `{"retrieve_reply": {"seqnr": 0, "acc_status": 2, "status": {...}, "report": {...}, "control": {...}, "schedules": {...}, "configuration": {...}}}`

### `POST /update`

```json
{"update_message": {"seqnr": 0, "account_auth": {"user_account": "", "mac_address": "<client_id>"}, "control": {...}, "configuration": {...}}}
```

Response: `{"update_reply": {"seqnr": 0, "acc_status": <1|2|3>}}` — `configuration` block is optional
and omitted from the request entirely when there is nothing to change in it.

### `acc_status` values (all three endpoints)

| Value | Meaning |
|---|---|
| 1 | Pending — user must press Accept on the thermostat display |
| 2 | Granted / write accepted |
| 3 | Denied |

A response body can also arrive empty (`curl: (52) Empty reply from server`) — VERIFIED to happen
both as a transient artifact unrelated to write success (for `/retrieve`, reliably so — reads recover
within 1-2 retries essentially every time), and as a symptom of a broader device unresponsiveness
episode (see Known instability, below). For **writes specifically**, a live session on 2026-09-13
found the correlation clean in every case observed: an empty reply on `/update` always meant the write
had not applied; every write that returned an immediate `acc_status:2` had applied. Still worth
confirming a write via a follow-up `/retrieve` when it matters, but an empty reply on `/update` is a
much stronger failure signal in practice than "must never be treated as definitive" suggests.

**Correction, VERIFIED 2026-09-13 (Phase E):** a client-side timeout on `/update` (`curl: (28) Operation
timed out`, no response at all within 15s) is *not* the same as an empty reply, and does not reliably
mean failure — a 19-field config-bundle write that timed out client-side had, per a follow-up
`/retrieve`, actually applied correctly. Treat a timeout the same as an empty reply: inconclusive on
its own, always confirm via `/retrieve`, never assume it failed just because assuming it succeeded
would be wrong to do unconditionally either.

## The `info` bitmask

```text
control(1) + schedules(2) + configuration(4) + report(8) + status(16) + details(64) = 95
```

`wifi_scan(32)` is deliberately excluded — VERIFIED to trigger a nearby-AP scan that delays the
response by several seconds. The binding's `wifi-signal` channel is unaffected by this exclusion;
`rssi` is reported in the `report` block regardless. **Bit 2 (`schedules`) is requested on every
poll and its ~40%-by-size payload is parsed by Gson and silently discarded** — see Schedules, below.

## ATAG epoch

All device timestamps (`status.date_time`, `report.report_time`, `configuration.start_vacation`) are
seconds since **2000-01-01T00:00:00 UTC**. Offset from Unix epoch: `946684800` (`10957 * 86400`).
VERIFIED, round-trip tested (`AtagEpochTest`).

---

## Field reference

Columns: **Field** (device JSON key) · **Type** · **Unit** · **Access** (see Writability policy) ·
**Exposed as** (channel id or Thing property; `—` = not exposed) · **Status** (verification tier).

### `status` block (4 fields) — entirely unexposed

| Field | Type | Unit | Access | Exposed as | Status |
|---|---|---|---|---|---|
| `device_id` | string | — | R | — (used only during discovery, not read from `/retrieve`) | VERIFIED |
| `device_status` | int (bitmask) | — | R | — | UNKNOWN |
| `connection_status` | int (bitmask) | — | R | — | UNKNOWN |
| `date_time` | long (ATAG epoch) | s | R | — | VERIFIED (format only; not consumed) |

This entire block is parsed into `StatusDTO` and then never read by the handler. Not a bug — simply
unused. Candidate for exposure; see Gap analysis.

### `report` block (28 scalar fields)

| Field | Type | Unit | Access | Exposed as | Status |
|---|---|---|---|---|---|
| `report_time` | long (ATAG epoch) | s | R | `device#report-time` | VERIFIED |
| `burning_hours` | double | h | R | `heating#burning-hours` | VERIFIED |
| `device_errors` | string (CSV) | — | R | `alerts#device-errors` | VERIFIED |
| `boiler_errors` | string (CSV) | — | R | `alerts#boiler-errors` | VERIFIED |
| `room_temp` | double | °C | R | `heating#room-temperature` | VERIFIED |
| `outside_temp` | double | °C | R | `heating#outside-temperature` | VERIFIED — boiler's own estimate; documented to go stale outside the heating season |
| `dbg_outside_temp` | double | °C | R | — | UNKNOWN — a second outdoor reading alongside `outside_temp`; relationship between the two undetermined |
| `pcb_temp` | double | °C | R | `device#pcb-temperature` | VERIFIED |
| `ch_setpoint` | double | °C | R | `heating#water-setpoint` | VERIFIED — reads 0 when no heating demand active |
| `dhw_water_temp` | double | °C | R | `hotwater#temperature` | VERIFIED |
| `ch_water_temp` | double | °C | R | `heating#water-temperature` | VERIFIED |
| `dhw_water_pres` | double | bar | R | — (removed, channel audit 2026-09-15 — reads a constant `0` on this device, no DHW pressure sensor on a combi; same "cannot distinguish unsupported from genuinely zero" reasoning that already kept `lmuc_burner_starts` unexposed) | VERIFIED shape, but see removal reason |
| `ch_water_pres` | double | bar | R | `heating#water-pressure` | VERIFIED |
| `ch_return_temp` | double | °C | R | `heating#return-temperature`, also feeds `heating#delta-temperature` (Phase I: `ch_water_temp − ch_return_temp`) | VERIFIED |
| `boiler_status` | int (bitmask) | — | R | `heating#flame`, plus `heating#central-heating-active`/`hotwater#hot-water-active` (Phase I, the same two bits split into standalone channels) — `heating#burner-target` (the third, prioritized-string view) removed in the 2026-09-15 channel audit as strictly derivable from, and lossier than, the other two | Bit assignments CORRECTED 2026-09-14 (were wrong since Phase 3) — see below |
| `boiler_config` | int (bitmask) | — | R | — | UNKNOWN |
| `ch_time_to_temp` | int | s | R | `heating#time-to-target` (published in minutes, 2026-09-14 — raw seconds is an awkward display unit and `Number:Time` items don't format sub-hour durations well in whole hours) | VERIFIED |
| `shown_set_temp` | double | °C | R | — (removed, final-review sweep 2026-09-13 — every live sample matched `heating#target-temperature` exactly and no ATAG surface justified keeping a second channel for it) | VERIFIED, but redundant with `control.ch_mode_temp` in every case observed |
| `power_cons` | int | ? | R | — (deliberately not exposed) | UNKNOWN unit — see below |
| `tout_avg` | double | °C | R | `heating#average-outside-temperature` | VERIFIED |
| `rssi` | int | dBm (negated) | R | `device#wifi-signal` (bucketed into a 0-4 quality scale, 2026-09-14 — see below) | VERIFIED |
| `current` | int | ? | R | — (deliberately not exposed) | UNKNOWN unit — see below |
| `voltage` | int | mV or V | R | `device#voltage` | VERIFIED shape (auto-scales: >1000 treated as mV) |
| `charge_status` | int | — | R | — | UNKNOWN |
| `lmuc_burner_starts` | int | count | R | — | VERIFIED reads 0 on this device — cannot distinguish "unsupported" from "genuinely zero" |
| `dhw_flow_rate` | double | L/min | R | `hotwater#flow-rate` | VERIFIED |
| `resets` | int | count | R | `device#resets` | VERIFIED — used throughout live testing as the controller-reboot indicator |
| `memory_allocation` | int | ? | R | — (`device#memory-allocation` channel removed, channel audit 2026-09-15 — unknown unit/scale, nothing to alert on) | UNKNOWN unit |

**`boiler_status` bitmask — CH/DHW/flame bit assignments were wrong, corrected 2026-09-14.** The
binding had decoded, since Phase 3 (2026-08-20), never independently verified against a real device
cycle:

```java
BOILER_STATUS_CH_ACTIVE  = 0x004   // WRONG
BOILER_STATUS_BURNER_ON  = 0x008   // right value, but never wired to anything
BOILER_STATUS_DHW_ACTIVE = 0x010   // WRONG — not a real bit at all
BOILER_STATUS_FLAME      = 0x100   // WRONG
```

**Real-world bug report (2026-09-14, from the deployed production instance, a separate host running
a built jar from this branch):** the ATAG ONE's physical display showed DHW actively heating while
the binding reported CH active for the same period. Confirmed via InfluxDB persistence across two
independent burner cycles the same day: `CHWaterTemperature` stayed flat (23.7→23.8°C,
23.9→24.0°C) while `DHWtemperature` rose sharply (48.6→54.1°C, 41.8→54.2°C) and `BoilerTemperature`
spiked 30+ degrees each time — an unambiguous DHW-heating signature, misclassified as `"ch"` both
times. Separately, `heating#flame` recorded **zero ON events for the entire day**, despite
`ModulationLevel` hitting 91–100% and `BoilerTemperature` spiking during both cycles — i.e. the
burner was very clearly firing while `flame` stayed OFF throughout.

Corroborated against an independent, commonly-cited community protocol reference,
[kozmoz/atag-one-api's Thermostat Protocol wiki](https://github.com/kozmoz/atag-one-api/wiki/Thermostat-Protocol):
*"boiler_status: Int: &512 = dhw_schema, &256 = ch_schema, &8 = boilerHeating, &4 = dhwHeating, &2 =
chHeating."* This maps bit-for-bit onto every symptom: this binding's `CH_ACTIVE` (0x004) was
actually testing the *DHW*-heating bit, so a real DHW event set it and got labeled `"ch"`; the
binding's `DHW_ACTIVE` (0x010) isn't a real bit at all, so it could never go ON; and `FLAME` (0x100)
was actually testing `ch_schema` (a schedule-governance flag, not burner activity), while the real
burner-firing bit (0x008, `boilerHeating`) had been defined as `BOILER_STATUS_BURNER_ON` since Phase
3 but never once wired to any channel — dead code sitting right next to the bug.

**Corrected:**

```java
BOILER_STATUS_CH_ACTIVE  = 0x002   // was 0x004
BOILER_STATUS_DHW_ACTIVE = 0x004   // was 0x010
BOILER_STATUS_FLAME      = 0x008   // was 0x100 (BOILER_STATUS_BURNER_ON constant removed, superseded)
BOILER_STATUS_CH_SCHEMA  = 0x100   // new — not exposed as a channel
BOILER_STATUS_DHW_SCHEMA = 0x200   // new — resolves the previously-unknown 0x200 bit (below)
```

A read-only live check on 2026-09-14 found `boiler_status = 512 = 0x200` while idle (no CH/DHW
demand) — consistent with `dhw_schema` being a static "which schedule currently governs" flag rather
than a transient activity indicator (it doesn't clear just because nothing is actively firing),
which is corroborating but not conclusive. **Not yet re-verified against a live CH or DHW heating
cycle on this project's own test device** — the field bug report came from a different, separately
deployed instance. Redeploying this fix and repeating that report's own verification steps (trigger
a DHW cycle, confirm `DHWstatus` goes ON / `BurnerTarget` reads `dhw` / `Flame` goes ON with
`ModulationLevel` > 0; repeat for CH) is the outstanding confirmation step.

This also **closes the previously-unknown 0x200 bit** open question below — it's `dhw_schema`, not
an undecoded activity bit.

**`current` and `power_cons` units are UNKNOWN — not exposed as channels (2026-08-27 decision).** An
earlier ÷100000 m³ gas-consumption conversion for `power_cons` was researched and never confirmed
against this specific device. `power_cons` sits in the report block alongside `current`, `voltage`,
`rssi`, and `charge_status` — device power-supply telemetry, not gas-metering fields, casting real
doubt on the "gas counter" theory; more likely both relate to the thermostat's own electrical supply,
paired with `voltage`. With no way to verify either interpretation against this device, both fields
are read into the DTO but deliberately not published as channels, rather than exposing a raw number
with a misleading or absent unit. Revisit if a verification method turns up.

**`device#wifi-signal` bucketed into a 0-4 quality scale instead of raw dBm (2026-09-14).** Published
as `new QuantityType<>(-rssi, Units.DECIBEL_MILLIWATTS)` on a `Number:Power` item since Phase 3 — this
looked correct in source but rendered wrong in Main UI: `Number:Power`'s system/base unit is the watt,
and without something pinning the display unit to dBm specifically, the widget silently converted the
logarithmic dBm value as if it were linear watts (confirmed against a live report: the math for two
observed values matched exactly). Rather than fight openHAB's UoM system over a unit conversion that's
only valid because both units happen to share a "Power" dimension despite being physically
incompatible representations, retyped the channel entirely to `Number:Dimensionless` with a 0-4
`state.options` scale (no signal/weak/average/good/excellent), mirroring an existing convention from a
different binding's WiFi-signal item (category `QualityOfService`). Thresholds
(`classifyWifiSignal()`): ≥−50 dBm excellent, ≥−60 good, ≥−70 average, ≥−80 weak, else no signal —
reasonable, commonly-used RSSI bands, not device-specific-verified. Existing openHAB items linked to
this channel need deleting and recreating (`Number`/`Number:Dimensionless`) and relinking — same
breaking-change note as any other channel type change in this binding.

### `report.details` block (25 fields) — boiler regulation internals

None of these have any counterpart in the cloud portal's EditDevice form or the app's settings tree.
Per the writability policy, **none are writable**. Most have no externally observable behaviour to
verify against, no vendor documentation, and several read as `0` or near-zero on this boiler with no
way to distinguish "not supported by this boiler model" from "genuinely zero" from a single snapshot.

| Field | Type | Unit | Access | Exposed as | Status |
|---|---|---|---|---|---|
| `boiler_temp` | double | °C | R | `heating#boiler-temperature` | VERIFIED |
| `boiler_return_temp` | double | °C | R | `heating#boiler-return-temperature` | VERIFIED |
| `min_mod_level` | int | % | R | — (removed, channel audit 2026-09-15 — a fixed burner hardware characteristic that never changes; arguably Thing-property-shaped but not worth adding, nobody looks it up) | VERIFIED |
| `rel_mod_level` | int | % | R | `heating#modulation-level` | VERIFIED |
| `boiler_capacity` | int | kW (presumed) | R | — | UNKNOWN — reads 0 |
| `target_temp` | double | °C | R | — | UNKNOWN — regulation-algorithm internal target, distinct from `ch_mode_temp` |
| `overshoot` | double | K | R | — | UNKNOWN |
| `max_boiler_temp` | double | °C | R | — (removed, channel audit 2026-09-15 — fixed installer configuration, not something anyone monitors; duplicates `ch_temp_max`'s role, see below) | VERIFIED |
| `alpha_used` | double | — | R | — | UNKNOWN — regulation coefficient |
| `regulation_state` | int | — | R | — (removed, channel audit 2026-09-15 — still only INFERRED `0=off, 1=on` from naming, never independently tested; an unverifiable Switch is worse than no channel — someone builds a rule on it and it may be inverted) | INFERRED `0=off, 1=on` from naming; not tested |
| `ch_m_dot_c` | double | — | R | — | UNKNOWN |
| `c_house` | long | — | R | — | UNKNOWN |
| `r_rad` | double | — | R | — | UNKNOWN |
| `r_env` | double | — | R | — | UNKNOWN |
| `alpha` | double | — | R | — | UNKNOWN |
| `alpha_max` | double | — | R | — | UNKNOWN |
| `delay` | int | — | R | — | UNKNOWN |
| `mu` | double | — | R | — | UNKNOWN — reads 0 here; also appears in `configuration.mu` |
| `threshold_offs` | double | K (presumed) | R | — | UNKNOWN |
| `wd_k_factor` | double | — | R | — | UNKNOWN — duplicates `configuration.wd_k_factor` |
| `wd_exponent` | double | — | R | — | UNKNOWN — duplicates `configuration.wd_exponent` |
| `lmuc_burner_hours` | double | h | R | — | VERIFIED reads 0 — cannot distinguish unsupported from genuinely zero |
| `lmuc_dhw_hours` | double | h | R | — | VERIFIED reads 0 — same caveat |
| `KP` | double | — | R | — | UNKNOWN — PID proportional gain |
| `KI` | double | — | R | — | UNKNOWN — PID integral gain |

### `control` block (14 fields)

This is the block most incidents this project has had originate from — every field here is
VERIFIED present, but several have write semantics that took multiple live-test rounds to establish
correctly, documented in full under Write semantics below.

| Field | Type | Unit | Access | Exposed as | Status |
|---|---|---|---|---|---|
| `ch_status` | int (bitmask) | — | R | — | UNKNOWN |
| `ch_control_mode` | int enum | — | **W** (bundle only — see below) | `heating#control-mode` | VERIFIED `0=thermostat, 1=weather-dependent` (values renamed from `room`/`weather`, Phase G, to match the app/manual) |
| `ch_mode` | int enum | — | **W** | `control#preset-mode` | VERIFIED `1=manual, 2=auto, 3=holiday, 4=extend, 5=fireplace` — `manual` writable as of this session, see the Open questions resolution below |
| `ch_mode_duration` | long | s | R for its value; **must be written as `0` to cancel any timed preset**, and **must be present (any value) to activate fireplace specifically** | `control#extend-remaining`/`control#fireplace-remaining` (mode-gated, same field — see Double-mapping inventory) | VERIFIED, mode-dependent meaning — see below |
| `ch_mode_temp` | double | °C | **W** | `heating#target-temperature`, `control#vacation-temperature` (mode-dependent — see Double-mapping inventory) | VERIFIED |
| `dhw_temp_setp` | double | °C | R | `hotwater#target-temperature`, read-only (Phase I: previously writable via a redirect to `schedules.dhw_schedule.base_temp`, which duplicated `hotwater#schedule-base-temperature`'s field — the write moved there instead, see the DHW read/write asymmetry note below) | Tracks whichever `schedules.dhw_schedule` entry/fallback is currently active |
| `dhw_status` | int (bitmask) | — | R | — | UNKNOWN |
| `dhw_mode` | int enum | — | R | — (removed from channel list — see Gap analysis) | UNKNOWN values for this device, but Phase G's manual read gives it a plausible meaning on a **combi** boiler: `0=ECO, 1=COMFORT` DHW schedule mode (manual pp. 23, 34). Still not exposed — this device's DHW schedule holds real temperatures, which is system-boiler-shaped, not combi-shaped, so the meaning may not transfer as-is |
| `dhw_mode_temp` | double | °C (presumed) | R | — | UNKNOWN — reads `150.0`, looks like a sentinel/unused value rather than a real temperature |
| `weather_temp` | double | °C | R | `heating#weather-temperature` (Phase I, advanced) | VERIFIED reads — the weather-service outdoor reading, distinct from `report.outside_temp` (the boiler's own estimate) |
| `weather_status` | int enum | — | R | `heating#weather-status` | VERIFIED, 14-value enum (sunny…unknown) |
| `vacation_duration` | long | s | **W** — value-setter only, does not activate holiday mode when written alone (binding design, matches confirmed device behavior) | `control#vacation-duration` | VERIFIED — genuinely honored by the device once `start_vacation` is present, see Write semantics |
| `extend_duration` | long | s | **W** — value-setter only, does not activate extend mode when written alone | `control#extend-duration` | VERIFIED — stored/echoed correctly, additive not absolute, see Write semantics |
| `fireplace_duration` | long | s | **W** — value-setter only, does not activate fireplace mode when written alone | `control#fireplace-duration` | VERIFIED |

### `configuration` block (45 fields)

| Field | Type | Unit | Access | Exposed as | Status |
|---|---|---|---|---|---|
| `report_url` | string | — | R | — | VERIFIED (read-only, do not write) |
| `download_url` | string | — | R | `deviceId`/`serialNumber`/`vendor`/`firmwareVersion`/`installerId` Thing properties (Phase H) | VERIFIED — firmware version embeddable (`…/R60` → `R60`) |
| `boiler_id` | string | — | R | `serialNumber` Thing property (Phase H) | VERIFIED |
| `boiler_det_type` | int | — | R | — (`boilerDetectType` Thing property removed, channel audit 2026-09-15) | UNKNOWN meaning — the name says *detection* type (how was the boiler detected, e.g. auto vs. manual OpenTherm handshake), not a model identifier; the app's own info screen shows a real model string ("A160S" on this device) that appears nowhere in the local API, checked against a full `/retrieve` and this project's own portal HAR captures. `boiler_det_type=1` was never actually confirmed to be that string's source, and one device gives one data point regardless — not enough to build a mapping even if it were |
| `language` | int enum | — | **W** (app-verified 2026-09-15) | `device#language`, writable, decoded to/from a name (`english`/`dutch`/`french`/`italian`/`german`) | VERIFIED `0=English, 1=Dutch, 2=French, 3=Italian, 4=German` — device reads `4`, display confirmed set to German; write path added and verified against the app 2026-09-15 |
| `pressure_unit` | int enum | — | INFERRED W (app) | **Decision (2026-09-13): leave unexposed** | INFERRED `0=bar, 1=psi` from javadoc; reads 0, alternate branch untested |
| `temp_unit` | int enum | — | INFERRED W (app) | **Decision (2026-09-13): leave unexposed** | INFERRED `0=°C, 1=°F`; reads 0, alternate branch untested |
| `time_format` | int enum | — | INFERRED W (app) | **Decision (2026-09-13): leave unexposed** | INFERRED `0=24h, 1=12h`; reads 1 |
| `time_zone` | int enum | — | **W** (cloud) | `device#time-zone`, **writable 2026-09-14** (was read-only — reversed on explicit owner request, see the decision note below) | PARTIAL — `1=Berlin` VERIFIED (cloud form + device agree); other 9 values INFERRED from dropdown order only |
| `summer_eco_mode` | int (bool-ish) | — | **W** (cloud) | `heating#summer-eco-mode` (Phase F) | VERIFIED shape; `1=on` INFERRED |
| `summer_eco_temp` | double | °C | **W** (cloud) | `heating#summer-eco-temperature` (Phase F) | VERIFIED |
| `shower_time_mode` | int | — | R | — | UNKNOWN — no cloud/app surface found |
| `comfort_settings` | int (bitmask) | — | R | — | UNKNOWN |
| `room_temp_offs` | double | °C | **W** (app) | `heating#room-temperature-correction` (Phase J) | VERIFIED — matches app's "Inside temperature correction" exactly (reads −1.0) |
| `outs_temp_offs` | double | °C | **W** (app) | `heating#outside-temperature-correction` (Phase J) | VERIFIED range (±5°C, manual p. 20); which of this or `wd_temp_offs` is the field the cloud's `wdr_temps_offset` submits to is still open — see Open questions |
| `ch_temp_max` | double | °C | R (installer) | — | VERIFIED reads; duplicates `report.details.max_boiler_temp`'s role, neither exposed as a channel (see the removal note above) |
| `ch_vacation_temp` | double | °C | **W** (cloud) | `control#vacation-temperature` (read side, when not in holiday) | VERIFIED. Its write case was the only `configuration` write missing `fillConfigBundle()` — fixed and live-verified Phase J (see Temperature corrections below) |
| `start_vacation` | long (ATAG epoch) | s | **W** (implicit, via vacation-duration write) | `control#vacation-start` (derived) | VERIFIED |
| `wd_k_factor` | double | — | R | — | UNKNOWN — duplicates `report.details.wd_k_factor`; one of the fields the device itself double-maps, see Double-mapping inventory |
| `wd_exponent` | double | — | R | — | UNKNOWN — duplicates `report.details.wd_exponent`; see Double-mapping inventory |
| `climate_zone` | double | °C | **W** (cloud) | `heating#climate-zone` (Phase F) | VERIFIED reads; manual's guidance value is −12°C (this device reads −10) |
| `wd_temp_offs` | double | °C | **W** (cloud) | `heating#wd-temperature-shift` (Phase J) | VERIFIED range (±10°C, manual p. 17/39, "Temperature shift"/"Temperature correction") — a wider range than the other two offsets, which is the live discriminator Phase J uses |
| `dhw_legion_day` | int enum | — | **W** (cloud) | `hotwater#legionella-protection-day` (Phase F) | VERIFIED `1=Monday…7=Sunday` — cloud form shows `7` as "Sonntag", device agrees |
| `dhw_legion_time` | int | min since midnight | **W** (cloud) | `hotwater#legionella-protection-time`, shown as `HH:mm` (2026-09-14 — was a raw minutes-since-midnight `Number:Time`, unreadable as a time of day; `String` with `formatTimeOfDay()`/`parseTimeOfDay()` instead, since openHAB has no clock-time item type) | VERIFIED — `420` = 07:00, matches cloud form |
| `dhw_boiler_cap` | int | kW (presumed) | R | — | UNKNOWN — reads 0 |
| `ch_building_size` | int enum | — | **W** (cloud) | `heating#building-size` (Phase F) | VERIFIED `1=small, 2=medium, 3=large` — device=2, cloud shows "medium"; matches manual p. 14 exactly |
| `ch_heating_type` | int enum | — | **W** (cloud) | `heating#heating-type` (Phase F) | VERIFIED 6-value enum — device=5, cloud shows "underfloor"; matches manual p. 14 exactly |
| `ch_isolation` | int enum | — | **W** (cloud) | `heating#insulation` (Phase F; **renamed from `isolation`, Phase G** — "isolation" was a false friend, the manual and app say "insulation" throughout) | VERIFIED `1=poor, 2=average, 3=good` — device=3, cloud shows "good" |
| `installer_id` | string | — | R | `installerId` Thing property (Phase H), only when non-empty | VERIFIED (reads empty on this device) |
| `disp_brightness` | int | % | **W** (app) | `device#display-brightness` (Phase F) | VERIFIED reads (30); write live-gated 30→50→30, zero drift — manual's range is 10–100% (Phase G corrected the bound from 0–100) |
| `ch_mode_vacation` | long | s | **W** (cloud, unit-translated) | — (`control#vacation-duration-default` channel removed, channel audit 2026-09-15 — still read every poll and feeds `defaultVacationDurationSeconds` internally, purely as the binding's own activation fallback; the device itself never applies this fallback, see the Write semantics table below) | VERIFIED — cloud form is **days** (7), local API is **seconds** (604800) |
| `ch_mode_extend` | long | s | **W** (cloud, unit-translated) | — (`control#extend-duration-default` channel removed, channel audit 2026-09-15 — same reasoning as `ch_mode_vacation` above) | VERIFIED value (3600) but **not the extend session length** — see Write semantics |
| `support_contact` | string | — | R | — | VERIFIED (read-only, do not write) |
| `privacy_mode` | int (bool-ish) | — | R (installer) | — | UNKNOWN — `1=on, disables cloud reporting` per earlier research, not device-tested |
| `ch_max_set` | double | °C | R | Boiler *water* limit — deliberately NOT wired to `target-temperature` (Phase H) | VERIFIED reads (85.0) — setpoint bound, not a user setting |
| `ch_min_set` | double | °C | R | Same as `ch_max_set` (Phase H) | VERIFIED reads (20.0) |
| `dhw_max_set` | double | °C | R | `AtagOneStateDescriptionProvider` supplies real bounds for `hotwater#target-temperature` (Phase H) | VERIFIED reads (65.0) — was hardcoded `max="65"` in thing-types.xml, now dynamic |
| `dhw_min_set` | double | °C | R | Same as `dhw_max_set` (Phase H) | VERIFIED reads (10.0) — **resolved**: was hardcoded `min="40"`, device reports `10`; now dynamic, so the discrepancy no longer exists |
| `mu` | double | — | R | — | UNKNOWN — duplicates `report.details.mu`; see Double-mapping inventory |
| `dhw_legion_enabled` | int (bool-ish) | — | **W** (cloud) | `hotwater#legionella-protection` (Phase F) | VERIFIED shape |
| `frost_prot_enabled` | int enum | — | **W** (cloud) | `heating#frost-protection` (Phase F; values renamed `outdoor`/`indoor`→`outside`/`inside`, Phase G, to match manual p. 18/39) | VERIFIED `0=off,1=outside,2=inside,3=both` — device=0, cloud shows "off" |
| `frost_prot_temp_outs` | double | °C | **W** (cloud) | `heating#frost-protection-temperature-outside` (Phase F) | VERIFIED reads; manual range −10–5°C (Phase G corrected the bound from −20–10) |
| `frost_prot_temp_room` | double | °C | **W** (cloud) | `heating#frost-protection-temperature-room` (Phase F) | VERIFIED reads; manual range 4–10°C (Phase G corrected the bound from 0–15) |
| `wdr_temps_influence` | int enum | — | **W** (cloud) | `heating#wdr-temperature-influence` (Phase F; values renamed `average`/`room-regulation`→`medium`/`room-control`, Phase G) | VERIFIED `0=off,1=less,2=medium,3=more,4=room-control` — device=2, cloud already showed "medium" even before the rename, an independent confirmation the old "average" value was wrong |
| `max_preheat` | int | min | **W** (cloud) | `heating#max-preheat`, modelled as an enum (Phase F/G) | **VERIFIED** (upgraded from PARTIAL, user-confirmed 2026-09-13): `0/60/120/180/1440` = Off/1h/2h/3h/Automatic, matching manual p. 21/40 exactly |

**Outdoor-temperature-correction — resolved by the manual, live discriminator pending (Phase J).**
The manual (pp. 17–20, 39) documents **three** distinct offsets, not two: *Inside temperature
correction* (±5°C) = `room_temp_offs`, VERIFIED; *Outside temperature correction* (±5°C, app-only) =
`outs_temp_offs`; and *Temperature shift* / *Temperature correction* (**±10°C**, offsets the
*calculated flow water temperature*, not a sensor reading) = `wd_temp_offs`. The differing range
(±10 vs ±5) is the live discriminator Phase J uses to confirm `wd_temp_offs` against the device,
since both `outs_temp_offs` and `wd_temp_offs` still read `0.0` in every capture so far.

---

## `schedules` block

**Present in every `/retrieve` response (bit 2 of the info bitmask).** `base_temp` for both schedules
is read and exposed as `heating#schedule-base-temperature`/`hotwater#schedule-base-temperature`
(advanced, read-only) as of Phase B. `entries` is parsed but not yet surfaced anywhere — full
per-entry read/write support is a later phase; this section specifies the structure as observed,
since that work will build directly on it.

```json
"schedules": {
  "ch_schedule":  { "base_temp": 22.5, "entries": [ [days 0-6] ] },
  "dhw_schedule": { "base_temp": 55.0, "entries": [ [days 0-6] ] }
}
```

| | |
|---|---|
| `base_temp` | double, °C — VERIFIED to answer a previously open question: this is the cloud EditDevice form's `ch_base_temp` (22.5) and `dhw_base_temp` (55.0), which are absent from the `configuration` block entirely. Confirmed by exact value match. |
| `entries` | array of 7 elements, one per weekday. **`entries[0]` = Monday — VERIFIED 2026-09-13**: the user edited only Monday's DHW schedule via the app; index 0 was the sole array that changed, the other 6 stayed identical. Index-to-weekday for 1–6 (presumably Tue–Sun in order) is inferred from this, not separately tested. |
| each day | array of `[start, end, temp]` triples — **variable length per day**, not fixed. `ch_schedule` has 2 triples/day in this capture, `dhw_schedule` has 3. Per the user (device owner), the number of periods is user-configurable and can be arbitrary |
| `start`, `end` | int, minutes since midnight (0–1440) |
| `temp` | double, °C — the setpoint for that window |

**Fallback semantics — VERIFIED for both schedules (2026-09-13).** Within a triple's `[start, end)`
window, `temp` applies. Outside every triple — whether that's a partial gap inside an otherwise-
scheduled day, or a whole day with no entries at all — `base_temp` is the fallback. Confirmed for
`ch_schedule` (thermostat applies `base_temp` 22.5°C during the 04:00–20:30 gap, not the entries'
`20.5°C`) and independently for `dhw_schedule`: the user edited Monday's DHW schedule via the app to
cover only 04:30–24:00 (`entries[0] = [[270,1440,48.5]]`), explicitly leaving 00:00–04:30 uncovered
rather than writing an entry at `55.0` for it — then confirmed via the app that the thermostat applies
`base_temp` (55.0°C) during that uncovered window. Two things fall out of this: the fallback mechanism
is the same for both schedules, and **the app itself represents "use base_temp here" by omitting the
period from `entries` entirely, never by writing an explicit entry at `base_temp`'s value.**

**Write-composition rule for any future entries-writing code:** when composing a day's `entries` from
a set of desired (time-range, temperature) periods, omit any period whose temperature equals the
schedule's `base_temp` — write only the periods that differ from it. Writing an explicit entry at
`base_temp`'s value would still be accepted by the device (nothing suggests otherwise), but would not
match how the official app constructs schedules, and — per the boundary-timing dependency documented
above for extend mode — could conceivably shift where the device considers "the next schedule
boundary" to be, in a way an omitted period wouldn't. Not separately tested; matching the app's own
convention is the safer default regardless.

**Write shape: VERIFIED for `base_temp`, and now for `entries` too.** Writing `dhw_schedule.base_temp`
requires sending the complete `dhw_schedule` object (`entries` resent unchanged, `base_temp` changed) —
a partial write of `base_temp` alone was not accepted. Confirmed live, twice. An entries-changing write
(splitting a triple to open a temporary gap, base_temp changed to a distinct test value) was also
confirmed live (2026-09-13) — accepted (`acc_status:2`), echoed back correctly, and cleanly reverted.
The top-level request shape is `{"update_message": {..., "schedules": {"dhw_schedule": {...}}}}` — a
key parallel to `control`/`configuration`, not previously recorded here. The temporary gap was
independently corroborated in the ATAG web portal/app's own schedule view during the test — the app
rendered the edited schedule shape correctly, confirming the write was structurally valid to the
official software too, not just superficially accepted (`acc_status:2`) by the device's API layer.

**New risk, confirmed live (2026-09-13): writing `entries` (not just `base_temp` alone) appears to
trigger brief device unresponsiveness** — ~100 s of empty `/retrieve` replies immediately after the
write, then an elevated empty-reply rate for a few minutes after that. Resembles the documented
"boiler restarts its API subsystem" pattern from a missing `ch_mode_duration`, though shorter here and
`resets` did not increment. The prior `base_temp`-only writes never touched `entries` and apparently
never hit this. Any future entries-write test should budget for this recovery window before trying to
observe an effect inside a short gap.

**Per-entry schedule editing (final-review sweep, 2026-09-13).** Implemented as four Thing Actions
(`setChSchedulePeriod`/`clearChSchedulePeriod`/`setDhwSchedulePeriod`/`clearDhwSchedulePeriod`), not
channels — a per-slot channel design (7 days × periods × fields) would have added 60+ channels, against
the channel-surface-size concern already raised on the PR; Actions add zero. Each action is a thin
wrapper: it reads the last-polled `entries` for that schedule, replaces/inserts/removes exactly one
period in one weekday's array (`AtagOneHandler.composeSchedulePeriodChange()`), and resends the whole
schedule object unchanged apart from that. It does **not** implement the write-composition rule above
(omitting periods equal to `base_temp`) — that's a convention for matching the app's own construction,
not a device requirement, and enforcing it here would silently second-guess a caller who deliberately
wants an explicit entry at that temperature. Callers get exactly what they compose.

Weekday is a name (`monday`..`sunday`), not a raw index — deliberately, because this device's protocol
has two different, unrelated weekday numbering schemes (this array is 0-indexed from Monday; the
unrelated `configuration.dhw_legion_day` field, see the Gap analysis table above, is 1-indexed) and a
name sidesteps that ambiguity for anyone calling these actions rather than risking the two being
confused.

**Verification status: VERIFIED live, 2026-09-13.** The compose logic (period replace/append/remove,
bounds checking, unknown-weekday/no-prior-poll rejection, a null day-entries slot rejected gracefully
rather than throwing) is covered by tests in `AtagOneHandlerTest` and `AtagOneActionsTest`. Live-tested
against the real device with explicit approval, both branches of the compose logic:

- **Append** (`setChSchedulePeriod("monday", 2, 600, 615, 8.0)` — Monday had exactly 2 existing periods,
  so index 2 appends): applied exactly (`acc_status:2`), verified via `/retrieve`, all other 6 days and
  `base_temp` byte-for-byte unchanged, `resets` unchanged (6 throughout).
- **Replace** (`setChSchedulePeriod("monday", 0, 0, 240, 9.0)` — index 0 already existed): applied
  exactly, same unchanged-elsewhere and `resets`-unchanged result.
- Both restored via the mirroring clear/re-set call; a final `/retrieve` matched the original baseline
  exactly.
- **Unresponsiveness confirmed but shorter than the earlier ~100 s observation**: each write was
  followed by 10–40 s of empty `/update`/`/retrieve` replies (one write needed a retry before even
  `acc_status:2` came back), always recovering on retry. Budget for it, but it was not the full 100 s
  every time.
- DHW's transport path (`updateDhwSchedule`) was not separately live-tested this round — it was already
  VERIFIED in Phase D via the CH/DHW base_temp tests above, and `composeDhwSchedulePeriodSet/Clear` share
  the exact same `composeSchedulePeriodChange()` logic just proven for CH, differing only in which
  already-proven transport method they call.

**Gap-fallback experiment (2026-09-13) — a false negative, now explained.** Opened a temporary
15-minute partial intra-day gap in `dhw_schedule.entries` (all 7 days identically) with a distinctive
test `base_temp` of `48.0`. Sampled `control.dhw_temp_setp` at minutes 561/563/563/564 of a [563,578)
gap — it stayed at `50.0` throughout, never showing `48.0`. This looked like a real negative result at
the time, but DHW's fallback is now independently confirmed (see above, via the user's real Monday
schedule edit) — so this experiment's negative reading was a false negative, not evidence the
mechanism differs from CH. Most likely explanation: only ~1 of the 15 sampled minutes actually landed
inside the gap before the write-triggered unresponsiveness (above) ate the rest of the window — one
sample is a thin basis to have expected a positive reading on regardless. Not worth re-running; the
question it was trying to answer is settled by other means.

**`ch_schedule.base_temp` write, VERIFIED live (2026-09-13, Phase D).** Written both directions
(22.5 → 23.0 → 22.5) via `updateChSchedule()`, `entries` intact throughout, `resets` unchanged. First
attempt got an empty reply (did not apply); the immediate retry got `acc_status:2` and applied
correctly — consistent with the empty-reply-means-failure signal established during Phase C's gate.
Much cleaner than DHW's gate: no extended unresponsiveness window this time, just the ordinary
occasional empty reply.

**Whole-schedule JSON read channel + write actions (2026-09-15).** Added `heating#schedule` /
`hotwater#schedule` (read-only `String`, publishing the full polled week as JSON) and
`setChSchedule(json)` / `setDhwSchedule(json)` (Thing Actions, one device write for the whole week)
via a new static codec, `ScheduleJson`. Driven by a schedule-editing UI on another host that needs to
render a week before editing it, and needs to save more than one period per interaction without
paying the per-period actions' one-write-per-period cost at the firmware's 2 s floor (a 6-period day
is ≥ 12 s of sequential writes, a worst-case full week ≥ 84 s, on a device already known to
intermittently stop responding for minutes at a time — see above).

JSON shape:

```json
{
  "baseTemp": 22.5,
  "days": {
    "monday": [ { "start": 360, "end": 1260, "temp": 20.5 } ],
    "tuesday": [], "wednesday": [], "thursday": [], "friday": [], "saturday": [], "sunday": []
  }
}
```

`start`/`end` are minutes since midnight, matching `setChSchedulePeriod`'s own units exactly; `temp`
is °C; `baseTemp` mirrors `ch_schedule.base_temp`/`dhw_schedule.base_temp`. All 7 weekday keys are
always emitted, in monday..sunday order — matching the case-insensitive weekday-name strings the
existing per-period actions already accept, per the two-numbering-schemes rationale documented above.

**The contract that matters most: `days.<weekday>` array order is `entries[dayIndex]` order,
verbatim — never sorted, never filtered.** A UI resolves "which period did I just edit" purely from
its position in this array, then calls `setChSchedulePeriod`/`clearChSchedulePeriod` with that same
index; diverging here would silently misdirect a caller's next edit onto the wrong period on the live
boiler. Covered by a dedicated test (`AtagOneHandlerTest.publishedScheduleArrayOrderMatchesPeriodIndexOrder`)
that reads the published JSON, picks a non-zero position, and confirms a period-index write at that
position lands on the period that sat there.

**Write input is a partial-days merge, not a required-full-week document.** `setChSchedule`/
`setDhwSchedule` accept JSON naming only the day(s) actually being changed; weekdays absent from
`days` are resent unchanged from the last poll (`ScheduleJson.parse`'s `currentEntries` parameter).
The device still requires the whole schedule object on every write regardless — the binding does that
merge, not the caller. A caller wanting a genuine whole-week replacement (e.g. round-tripping the read
channel's own output back through `setChSchedule`) still works unchanged, since that JSON already
names every day. `baseTemp` follows the same optional-with-fallback pattern.

**Validation tightened for both write paths, not just the new one.** Per an explicit decision this
round: `ScheduleJson.isValidPeriod` (`0 <= start < end <= 1440`) is now enforced by
`AtagOneHandler.composeSchedulePeriodChange` too, so the four pre-existing per-period actions
(shipped in `v0.3.0-beta`) reject the same malformed period a whole-schedule write would. No
legitimate device data is affected — every observed real schedule has `start < end` and never wraps
midnight. `temp` stays unvalidated; its legitimate range is installation-dependent (see
`dhw-target-temperature`'s bounds discussion above) and the device remains the authority on it.

**Post-ack republish, not just a fast re-poll.** `sendChScheduleUpdate`/`sendDhwScheduleUpdate` now
adopt the just-sent schedule as `lastXxxScheduleEntries` and republish the read channel immediately
once `AtagOneApiClient.updateXxxSchedule` returns without throwing (i.e. `acc_status == 2`) — not
just after the next poll. This was **required**, not just a nice-to-have, for two independent
reasons discovered while implementing this: (1) the existing `POST_COMMAND_DELAY_S = 2` fast re-poll
lands well inside the 10–100 s unresponsiveness window documented above for an `entries` write, so
without this the read channel would go stale for that whole window; (2) `lastChScheduleEntries`
itself would stay stale for the same window, so a second rapid per-period edit issued before the next
successful poll would silently compose against pre-first-edit data and lose the first edit. Both are
now fixed by the same one change. Not yet live-gated — see the plan file's verification section for
the required device test before this ships to the PR branch.

**Return value contract for the two new actions is "queued", not "confirmed".** Both return `true` as
soon as the JSON is parsed, validated, and handed to `sendComposedChSchedule`/`sendComposedDhwSchedule`
— they do not block for the device's actual reply, matching every other action in this class. A caller
wanting write confirmation watches the `schedule` channel, which (per the point above) now updates
within about a second of the device's acknowledgement rather than waiting for the next poll interval.

**Overlap rejection on all four schedule-write actions (2026-09-15, Indoor page spec §D).** Driven by
the same UI's need for a server-side backstop: its own client-side overlap resolution only protects
edits made through that one widget, and every write action here is directly callable over REST by
anything, bypassing it entirely. Before this change, **no path — per-period or whole-schedule —
checked for overlap at all**; `isValidPeriod` only bounds-checked a single period
(`0 <= start < end <= 1440`), never compared two periods against each other. Confirmed by reading the
source before starting, per the spec's own explicit ask to check first rather than assume.

- New `ScheduleJson.periodsOverlap(aStart, aEnd, bStart, bEnd)`: half-open interval overlap test
  (`aStart < bEnd && aEnd > bStart`), so a period ending exactly when the next starts is not an
  overlap — matches the spec's definition exactly.
- Whole-schedule path (`ScheduleJson.parseDayPeriods`): after parsing a weekday's periods, an O(n²)
  pairwise check over just that day's array (bounded by `MAX_PERIODS_PER_DAY` = 100, so at most 4950
  comparisons) rejects the whole write if any two overlap. Scoped to the day(s) actually present in
  the input JSON — a day resent unchanged from the last poll isn't re-validated, since that's already
  live device data from a successful poll, not new caller input.
- Per-period path (new `AtagOneHandler.overlapsAnyOtherPeriod`): compares the new/edited period
  against every *other* period already on that weekday, excluding the slot at `periodIndex` itself —
  otherwise replacing a period with a slightly-shifted version of itself would always "overlap" its
  own prior value. The same exclusion-by-index trick handles append (`periodIndex ==
  dayEntries.length`) for free: nothing in the loop ever matches an index that doesn't exist yet, so
  an appended period is compared against the whole day with nothing excluded.
- **Reject, never resolve** — both paths return `null` (actions return `false`) on a conflict, no
  trimming or reordering. Per the spec: deciding *how* to resolve a conflict is a UI-policy decision,
  not the binding's to make; the UI already does its own resolution before ever calling these actions,
  this exists purely as a backstop for callers that don't.
- **Answering the spec's "what error type/message" question:** there isn't a distinct one. This
  binding's entire write-validation surface — unknown weekday, out-of-range index, malformed JSON,
  invalid bounds, and now overlap — collapses to the same generic `null`/`false` rejection signal
  it already used for everything else; introducing a dedicated exception type or error code for just
  this one failure mode would be inconsistent with how every other rejection on these four actions
  already works, and wasn't asked for beyond "note it here." Documented in the `@RuleAction`
  descriptions and README so this is discoverable without reading source, but a caller that needs to
  distinguish "overlap" from "any other rejection reason" cannot do so via the return value alone —
  it has to pre-check its own input against `heating#schedule`/`hotwater#schedule` first, same as it
  already must for the other rejection cases.
- Hardening cap interaction: `MAX_PERIODS_PER_DAY` (from the previous round's hardening pass) is
  checked before the overlap loop runs, not after — an oversized period array is rejected for size
  before ever paying for the pairwise comparison.

Not yet live-gated — no device write is required to exercise this (it's pure input validation, same
class of change as the earlier bounds/malformed-input checks), but it's untested against how the real
`atag_schedule_card` UI's own overlap-resolved input behaves once it starts calling the whole-schedule
actions (its own follow-up, tracked in the Indoor page project, not this binding).

**CRITICAL — entries write silently wipes the schedule to empty, root-caused and fixed
(2026-09-16).** A live-gate test of the post-ack-republish change above (a single-value
`setChSchedulePeriod` edit) reported success (`acc_status:2`) but a follow-up *independent* `/retrieve`
— bypass curl, not the binding's own read-back — showed `ch_schedule.entries` wiped to `[[],[],[],[],[],[],[]]`
on every day. `resets` did **not** increment and `device_errors` stayed empty, so nothing about the
device's own health signals flagged this — the only way to catch it was an independent read-back that
didn't trust the write's own "success."

Root cause: `ScheduleDTO.entries` is `double[][][]` because a period's three positions
(`start`/`end`/`temp`) have no separate Java fields to carry distinct types. Gson's default double
serialization has no way to know that only `temp` is meant to be fractional, so it emitted every
position identically — `[0.0,240.0,20.5]` — where the device's own wire format, confirmed by every
`/retrieve` reply and every previously-working write in this project, is `[0,240,20.5]`: bare integers
for `start`/`end`, a float only for `temp`. The device accepts the malformed request, silently no-ops
it (wiping to empty rather than rejecting or leaving the old schedule alone), and still reports
`acc_status:2` — indistinguishable from a real success without an independent check.

Confirmed via direct methodology, not guesswork: the exact TRACE-logged request the binding sent was
captured, then replayed character-for-character via raw curl to isolate the float-vs-integer question
from everything else in the binding's request pipeline (the composed `entries` content, the envelope
shape, the transport layer) — no other difference exists between the binding's request and a
known-good one. This also ruled out two earlier hypotheses from this same investigation: a stray
`device_id` field in the envelope (never actually present in this binding's code, confirmed by
grepping `AtagOneApiClient.java` — that theory didn't apply here even though it may be a real issue for
some other client) and a named-object vs. positional-array structure mismatch (ruled out because the
positional-array shape is exactly what the device emits on every read, and was itself the same shape
the 2026-09-13 test below used).

**Fix:** `AtagOneApiClient.scheduleToJson()` builds the schedule's JSON by hand instead of via
`gson.toJsonTree(schedule)`, casting `start`/`end` to `long` and leaving `temp` as-is. Confined
entirely to the serialization boundary in `AtagOneApiClient` — `ScheduleDTO`'s field stays
`double[][][]`, and none of `AtagOneHandler`'s compose logic, `ScheduleJson`, or any existing test
literals needed to change, since the read/deserialization path was never affected (Gson parses a JSON
integer into a `double` field with no loss, regardless of which way the device formats it — only
*writing* got this wrong). Covered by two new unit tests in `AtagOneApiClientValidationTest`, asserting
the exact string shape (`[0,240,20.5]`, never `0.0`) and that empty/null day slots serialize safely.

**Open question, not fully resolved:** the 2026-09-13 per-period live test above (`setChSchedulePeriod`,
Monday, append and replace) reports success verified via an independent `/retrieve` — "all other 6 days
and `base_temp` byte-for-byte unchanged." That test used the exact same buggy `double[][][]`
serialization (unchanged in this codebase since long before this session), so by the theory above it
should have hit the identical failure. It's not yet clear why it didn't. The two tests differ in one
concrete way: 2026-09-13 touched only Monday, with every other day already empty at the time (per the
HAR baseline captured separately); 2026-09-16's failing test wrote real, non-empty period data to all 7
days simultaneously. One plausible reconciliation is that the device's parser only chokes on the
float-formatting defect when multiple days carry real (non-empty) float-formatted data in the same
request — an empty day has no numbers to mis-parse, so a single-changed-day write with the rest
genuinely empty may have simply never exercised the bug. This is a plausible theory, not a confirmed
one — the fix is correct and worth keeping regardless of which theory is right, since it makes every
write match the device's own confirmed wire format instead of leaving this ambiguity open, but the
2026-09-13 result shouldn't be read as having ruled out the serialization defect at the time; it may
simply not have triggered it.

**Recommended follow-up live gate:** repeat the exact scenario that failed on 2026-09-16 (writing real
data to all 7 days in one request) with this fix applied, and confirm via independent bypass-curl
`/retrieve` — not the binding's own state — that it now lands correctly. That's the direct,
falsifiable test of this fix; nothing here should be treated as confirmed-fixed on the live device
until that's run.

---

## Write semantics

### Mode activation (`ch_mode`)

All of the following are VERIFIED by an exhaustive, isolated, single-variable-at-a-time manual API
test (raw curl, binding disabled, HTTP/1.0 + `Connection: close`, 2000 ms inter-request gap) — not
inferred. This supersedes the pre-test version of this table entirely.

| Mode | `ch_mode` | Fields required together to activate | Duration source when not explicit |
|---|---|---|---|
| Manual | 1 | `ch_mode` alone is sufficient; `ch_mode_temp` optional (reuses the current target temperature if omitted) | — |
| Auto | 2 | `ch_mode` alone is sufficient | — |
| Holiday/vacation | 3 | `ch_mode` + `configuration.start_vacation` **must be in the same write** — confirmed to never activate without `start_vacation`, regardless of whether `vacation_duration` is preset (three independent failed attempts without it) | `control.vacation_duration` if non-zero, else `configuration.ch_mode_vacation` (7 days) — the device never applies this fallback itself, the binding does |
| Extend | 4 | `ch_mode` alone is sufficient | `control.extend_duration` — **persists across cancel**, a genuinely stable stored default |
| Fireplace | 5 | `ch_mode` alone is sufficient; `ch_mode_duration` **must additionally be present** on this mode specifically — its absence causes a confirmed ~4 minute boiler API restart | `control.fireplace_duration` — **reverts to the factory default (3600) on every cancel**, not stable the way extend's is |

**Manual-mode resolved (2026-09-13).** The same test report found `{"ch_mode":1}` applied cleanly
with no restart; a second, deliberate live test this session (auto→manual→auto→manual, including a
delayed 60s stability recheck) confirmed the same result independently. The original basis for the
binding's hard rejection was never traceable — two clean live tests now outweigh it. `preset-mode=manual`
is writable as of this session; see the Open questions resolution below for the full test record.

`start_vacation` also supports genuine **future-scheduled** activation — confirmed by directly
observing the physical thermostat switch into vacation mode at the scheduled time, from a clean
baseline. But rewriting `start_vacation` while a schedule from an earlier write is already
pending/active is a different, unsupported operation — confirmed to trigger a real device reset
(`resets` incremented). Treat `start_vacation` as write-once until the vacation it scheduled is
cancelled.

### Cancellation

- **`ch_mode_duration` is the field that must be zeroed to cancel any timed preset — not the
  mode-specific duration field.** Isolated directly for extend: cancelling with
  `{"ch_mode":2,"extend_duration":0}` (`ch_mode_duration` omitted) left the countdown stale and
  uncleared, even though `ch_mode` itself flipped to auto correctly. None of the three mode-specific
  duration fields need to be included in a cancel write at all.
- **Fireplace cancel cannot be completed via the API alone, in any tested payload variant.**
  Confirmed three ways, including directly watching the physical display: the write is accepted
  (`acc_status:2`) but has no effect until a button is pressed on the thermostat itself. This is a
  device protocol requirement, not a payload issue.
- **Vacation's cancel payload depends on active vs. pending.** For an actively-running vacation,
  `{"ch_mode":2,"ch_mode_duration":0}` alone is sufficient — `vacation_duration` and `start_vacation`
  both self-clear automatically. For a pending/future-scheduled vacation that hasn't started counting
  down yet, `ch_mode_duration` is already `0` throughout, so that 2-field payload touches nothing and
  leaves the schedule armed — cancelling it requires explicitly adding
  `"configuration":{"start_vacation":0}`.
- Extend cancels cleanly via the API alone in every tested case, no caveats.

### Duration-field persistence across cancel

| Field | Survives cancel? |
|---|---|
| `extend_duration` | VERIFIED yes — stays at whatever was last written |
| `fireplace_duration` | VERIFIED no — always reverts to the factory default (3600) |
| `vacation_duration` | VERIFIED no — resets to `0` |

Device behavior, observed consistently — not something the binding tries to normalize. A custom
fireplace or vacation duration only survives until the next cancel; extend's does not have this
limitation.

### `ch_mode_duration` — a single field with mode-dependent meaning, VERIFIED

This field is not a uniform "requested duration" write target. It is better understood as the
device's own live "time remaining until this mode's end-criterion" computation:

- **Holiday** supplies an explicit end-criterion (`start_vacation` + `vacation_duration`), and the
  device genuinely tracks it in `ch_mode_duration`.
- **Extend** has no equivalent end-criterion field anywhere in `configuration` — no `start_extend`
  exists. `ch_mode_duration` instead reads as **(time remaining until the next `ch_schedule`
  boundary) + `extend_duration`** — confirmed three independent ways: server-side timing math
  (predicted vs. actual within 5 s, twice, using different requested durations), the cloud portal's
  own EditDevice form wording ("extend current temperature by hours", with a "New entry time" field
  computed as current schedule entry time + hours), and the raw `ch_schedule` data itself (the
  20:30 boundary from the math matches minute 1230 in the schedule exactly). Plain auto mode with no
  extend session active also shows a nonzero `ch_mode_duration` — consistent with the same
  generic "time to next transition" computation, not an explicit session length.
- **Fireplace**: VERIFIED the value is honored when non-zero and matching `fireplace_duration`; `0`
  triggers a fallback-to-stored-default rather than a literal zero-length session.

**This directly contradicts an earlier (superseded) project conclusion** that extend's duration was
"entirely device-computed and not controllable by anything the client sends" — that conclusion was
reached before the schedule-additive mechanism was identified and should be treated as historical
context, not current fact.

### `ch_control_mode` (thermostat/weather-dependent)

**Not writable as a bare or lightly-bundled field** — VERIFIED, multiple attempts. Writable **only**
as part of the full ~19-field configuration bundle matching the cloud portal's `/Device/EditDevice`
form shape (every writable `configuration` field above, resent at its current value, alongside the
changed `ch_control_mode`). VERIFIED working in both directions (room→weather and weather→room) via
this exact shape. **Implemented (Phase E, 2026-09-13)**: `heating#control-mode` is writable, composing
the bundle from the last polled configuration via `AtagOneHandler.fillConfigBundle()`.

**Live write gate, VERIFIED 2026-09-13.** Full round-trip room→weather→room via the actual bundle
shape: a complete `/retrieve` was captured before and after, and every field in `configuration` was
programmatically diffed — zero differences, only `control.ch_control_mode` changed in either
direction. `resets` never moved. The room→weather write returned a client-side timeout (no response
within 15s) rather than an empty reply or `acc_status`, yet had still applied — see the timeout note
above.

### Settings channels (Phase F)

**Implemented, 2026-09-13** — all remaining "future settings channels" from the placement table above
(except the still-ambiguous outdoor-temp correction fields, deliberately deferred) are wired as
channels, following the exact same `fillConfigBundle` full-bundle write pattern as
`ch_control_mode`: every write composes the one changed field alongside the ~19 other confirmed
`configuration` fields at their last-polled value.

**Live write gate, VERIFIED 2026-09-13.** One representative field per group, each written then
restored, with a full `configuration` diff before/after every step:

- Heating settings group: `climate_zone` -10 → -11 → -10.
- Hot water / legionella group: `dhw_legion_day` 7 → 3 → 7.
- Control defaults group: `ch_mode_extend` 3600 → 7200 → 3600.
- `disp_brightness` (dedicated test, outside the 19-field bundle): 30 → 50 → 30.

All four: `acc_status:2`, only the targeted field changed in either direction, zero drift on any other
field, `resets` never moved (stayed at 6 throughout). A final diff against the very first pre-test
baseline confirms the device ended in exactly its starting configuration.

### Manual-sourced correctness pass (Phase G, 2026-09-13)

The official _ATAG ONE App and Portal User Guide_ (v171116, 48 pp.) was read in full and used to
correct several things shipped in Phases B–F, none of which needed live testing to fix (bounds and
vocabulary, not behavior) except the extend-duration granularity bug, which did:

- **Ten channel bounds were wrong** against the manual's stated ranges: `target-temperature`,
  `ch-schedule-base-temperature`, `summer-eco-temperature`, `vacation-temperature` (all 4–27°C, not
  their previous narrower/wider ranges), `frost-protection-temperature-room` (4–10°C, not 0–15),
  `frost-protection-temperature-outside` (−10–5°C, not −20–10), `display-brightness` (10–100%, not
  0–100), `fireplace-duration` (max 24h, previously unbounded), `extend-duration` (15 min – 6 h in
  15-minute steps, not whole hours) — `extend-duration-default` had the same bound at the time but was
  itself removed in the 2026-09-15 channel audit, see below.
- **Extend duration was a real bug, not just a bound.** The manual (pp. 7, 29) documents extend as
  15 minutes to 6 hours in 15-minute increments; the binding rejected anything that wasn't a whole
  hour. Fixed in `AtagOneHandler`'s `CHANNEL_EXTEND_DURATION` case and
  `AtagOneActions.activateExtend()`, both now checking `SECONDS_PER_15_MINUTES` (900L) instead of
  `SECONDS_PER_HOUR`. `fireplace` (whole hours, 1–24) and `vacation` (whole days) are unaffected — the
  manual confirms both, and the whole-unit constraint was only ever *proven* live for fireplace and
  extrapolated to the other two; extend turned out to be the extrapolation that was wrong.
- **Vocabulary aligned with the manual/app/portal**, including channel IDs and enum values (breaking
  for existing item links on the ID rename, and for rule logic on the enum-value renames):
  - `heating#isolation` → `heating#insulation` (channel ID rename)
  - `heating#control-mode` values `room`/`weather` → `thermostat`/`weather-dependent`
  - `heating#frost-protection` values `outdoor`/`indoor` → `outside`/`inside`
  - `heating#wdr-temperature-influence` values `average`/`room-regulation` → `medium`/`room-control`,
    label "Weather Influence" → "Room Influence" (it's the *room* temperature's influence on the
    weather curve)
  - `heating#max-preheat` changed from a free-range `Number:Time` to a `String` enum
    (`off`/`1h`/`2h`/`3h`/`automatic`), since all five values are now VERIFIED
  - `heating-type` and `building-size` needed **no change** — the manual's lists (p. 14) matched our
    integer mappings exactly, independently corroborating both
- **The three-offset model resolved outdoor-temperature-correction** (Open question, now closed —
  see the `configuration` table above and Open questions below).
- **`max_preheat` upgraded from PARTIAL to VERIFIED** (user-confirmed against the live device,
  2026-09-13).
- **`control.dhw_mode` gained a sourced hypothesis** (manual pp. 23, 34: combi-boiler ECO/COMFORT DHW
  schedule mode) but stays unexposed — this device's DHW schedule shape doesn't match a combi boiler,
  so the meaning may not transfer; see the `control` table above.

No live write gate needed for the bounds/vocabulary changes (XML/text only). Extend-duration's
granularity fix was live-verified: 15, 30, and 90-minute writes were each confirmed stored by the
device.

### Thing properties and dynamic state description (Phase H, 2026-09-13)

Static identity exposed as Thing properties, matching the portal's Account → Devices screen, set via
`AtagOneHandler.updateDeviceProperties()` (same `updateProperty` pattern as `persistClientId()`):
`deviceId` (from `status.device_id` — also the representation-property, now populated for a
manually-added Thing too, not just a discovered one), `serialNumber` (`configuration.boiler_id`,
using the standard `Thing.PROPERTY_SERIAL_NUMBER` key), `vendor` (static `"ATAG"`),
`firmwareVersion` (parsed from `configuration.download_url`'s last path segment), `installerId` (only
set when non-empty). A fifth property, `boilerDetectType` (raw integer, no model name invented), was
removed in the 2026-09-15 channel audit — see below — after turning out to likely be a
detection-method flag, not a boiler model type as its name assumed.

`AtagOneStateDescriptionProvider` (new class, mirrors the `WizStateDescriptionProvider` pattern)
supplies `hotwater#target-temperature`'s bounds from `configuration.dhw_min_set`/`dhw_max_set` at
runtime instead of the hardcoded `min="10" max="65"` in thing-types.xml — resolves the discrepancy
this doc flagged (device reports `dhw_min_set=10`, matching a combi boiler; a system boiler with a
3-port valve kit has a wider 17–70°C range per the manual, so a static bound is wrong for one
installation type by construction). Wired through `AtagOneHandlerFactory` → `AtagOneHandler`
constructor (now 3-arg). `ch_min_set`/`ch_max_set` deliberately **not** wired to anything — they are
boiler *water* limits (20–85°C), not room setpoint bounds.

No live write gate — read-only properties and bounds, no `/update` call involved.

### Exposure reconciliation (Phase I, 2026-09-13)

Cross-referenced the binding's full 63-channel inventory against what the ATAG portal's own screens
show (user-supplied inventory) and added what was missing:

- `heating#delta-temperature` — derived, `ch_water_temp − ch_return_temp`, no new device field.
- `heating#central-heating-active` / `hotwater#hot-water-active` — `report.boiler_status` bits
  `0x004`/`0x010` (these bit values were themselves wrong, corrected 2026-09-14 to `0x002`/`0x004` —
  see the `boiler_status` bitmask section above), already decoded into locals for `burner-target` but
  previously discarded. Mirrors the portal's own "Status" section, which lists these two separately.
  (`burner-target` itself — the prioritized "which one" string these two booleans were decoded
  alongside — was removed in the 2026-09-15 channel audit, once these two made it redundant and
  lossier; see below.)
- ~~`hotwater#water-pressure`~~ ← `report.dhw_water_pres` — pairs with the existing
  `heating#water-pressure` (which stays; only the DHW side was removed). **Removed in the 2026-09-15
  channel audit** — reads a constant 0 on this device, no DHW pressure sensor.
- `heating#weather-temperature` ← `control.weather_temp` — advanced.
- ~~`heating#regulation-state`~~ ← `report.details.regulation_state` — advanced, and the one
  `report.details` field exposed despite being INFERRED, not VERIFIED. **Removed in the 2026-09-15
  channel audit** for exactly that reason — a Switch whose ON/OFF meaning was never more than a
  naming guess.
- `control#next-schedule-time` / `control#next-schedule-temperature` — the portal's automatic-mode
  "next time target"/"next time target temperature". Computed from `schedules.ch_schedule.entries`
  (already parsed, no new request) plus the current time, via
  `AtagOneHandler.updateNextScheduleChannels()`. **Deliberately a simpler model than the full
  gap-fallback timeline**: it reports the next `entries` *start* time/temperature, not every
  base_temp-revert transition. Chosen because it's what the manual describes the portal as actually
  showing, and because a wrong revert-transition model would repeat the kind of confusion the
  base_temp fallback semantics already caused earlier in this project. Package-private and takes
  `now` as a parameter for testability (not `ZonedDateTime.now()` internally).

**DHW read/write asymmetry, fixed.** Before this phase: `hotwater#target-temperature` *read*
`control.dhw_temp_setp` but *wrote* `schedules.dhw_schedule.base_temp`, while
`hotwater#schedule-base-temperature` read that same `base_temp` read-only — one field, two channels,
opposite directions, the exact confusion that cost time earlier in this project. Fixed by swapping
writability: `hotwater#schedule-base-temperature` is now writable (mirrors
`heating#schedule-base-temperature`, which already wrote `ch_schedule.base_temp` directly and is
live-verified), and `hotwater#target-temperature` is read-only again, reporting whichever schedule
period is active — a genuine status, since DHW has no live-setpoint write the way CH does via
`control.ch_mode_temp`. The dispatch in `AtagOneHandler.handleCommand()` moved from
`CHANNEL_DHW_TARGET_TEMPERATURE` to `CHANNEL_DHW_SCHEDULE_BASE_TEMPERATURE`; `composeDhwScheduleUpdate()`
itself is unchanged.

**Live write gate, VERIFIED 2026-09-13**, same before/after `configuration`-diff protocol as
Phases C–F, applied to the moved write via `hotwater#schedule-base-temperature`.

### Temperature corrections (Phase J, 2026-09-13)

Closes the field Phase F deliberately deferred, using the three-offset model the manual established
(see the `configuration` table above): `heating#wd-temperature-shift` (`wd_temp_offs`, ±10°C),
`heating#outside-temperature-correction` (`outs_temp_offs`, ±5°C), and
`heating#room-temperature-correction` (`room_temp_offs`, ±5°C, VERIFIED — matches the app's "Inside
temperature correction" exactly, reads −1.0 on this device). All three use the `fillConfigBundle`
pattern; `room_temp_offs` and `outs_temp_offs` were added to the bundle (`wd_temp_offs` was already
there from Phase E).

**Live write gate, VERIFIED 2026-09-13.** Wrote `wd_temp_offs = 3.0`, confirmed via a full
`configuration` diff that only that field changed (`outs_temp_offs` and `room_temp_offs` stayed put)
and restored; then wrote `outs_temp_offs = 2.0` and confirmed the mirror image. Also confirmed the
`vacation-temperature` bundling fix from the backlog below: wrote `ch_vacation_temp` 14→13→14 with
the bundle now included, `acc_status:2` both times, zero drift, restored. All three: `resets`
unchanged (stayed at 6 across the whole session's testing). **Not yet cross-checked against the
app's own "Temperature shift"/"Outside temperature correction" readouts** — the device-side field
independence is confirmed, but a visual app confirmation of the label mapping is still open;
low-priority since the manual's range-based reasoning (±10 vs ±5°C) is already a strong
discriminator on its own.

## Writability policy

**Writable = only what ATAG's own app or cloud portal exposes as user-changeable.** This is a
deliberate safety boundary, not just a documentation convenience — the binding does not touch
boiler regulation/commissioning parameters (`report.details.*`, several `configuration` internals)
regardless of whether a write to them might technically succeed. Everything in `report` and
`report.details` is read-only by nature (telemetry). Within `control` and `configuration`, the
Access column above reflects this policy: **W** = confirmed present in the cloud form or app;
INFERRED W = plausible from a reference library or field grouping but unconfirmed; unmarked = no
ATAG-surfaced UI found, read-only regardless of technical writability.

Two unit translations the cloud performs, which any future write path must replicate exactly:

- `ch_mode_vacation`: cloud form is **days** (e.g. `7`); local API is **seconds** (`604800`)
- `ch_mode_extend`: cloud form is **hours** (e.g. `1`); local API is **seconds** (`3600`)

## Known instability — unresolved, not mode-specific

Across extensive live testing, the device's embedded HTTP server has intermittently become fully
unresponsive (empty replies on every request) for periods ranging from ~45 seconds to ~4.5 minutes.
VERIFIED to occur during extend-mode testing, vacation-mode testing, and idle periods with no writes
at all — **not correlated with any specific mode or duration value**. Sometimes coincides with the
`resets` counter incrementing (a real controller reboot); sometimes does not. Three untested
candidate contributors, none isolated:

1. Concurrent polling — openHAB's own scheduled poll and manual `curl` testing hitting the device
   in the same window
1. The HTTP/1.0 vs. HTTP/1.1 mismatch noted under Transport — manual `curl` testing did not
   consistently force HTTP/1.0 until late in this investigation
1. The 2000 ms rate limit not being respected by manual testing, particularly on same-second retries
   after an empty-reply failure

**A related but distinct pattern, VERIFIED 2026-09-13**: writes specifically failing intermittently
over a much longer stretch (~15 minutes) while `/retrieve` kept succeeding throughout (aside from its
own normal 1-2-retry flakiness) — not the same as the fully-unresponsive episodes above, where reads
fail too. Roughly 5 of 6 `/update schedules` calls returned empty during this window; the ones that
returned an immediate `acc_status:2` all applied correctly, confirming this wasn't a case of writes
silently succeeding despite an empty reply (see the `acc_status`/empty-reply note above). `resets`
never incremented. No trigger identified — the window began and ended without any corresponding
change in write shape, mode, or other observable state.

## Gap analysis — what the binding should expose but doesn't

Historical record — every item below is now **done** (Phases F–J, 2026-09-13) except the three
explicitly marked otherwise. Kept for the reasoning trail, not as a live task list.

**Read-only channels — done:**

- `control.weather_temp` → `heating#weather-temperature` (Phase I) — the weather-service outdoor
  temperature, distinct from `report.outside_temp` (the boiler's own estimate, documented to go stale
  outside the heating season). The two are genuinely different data sources.
- `report.dhw_water_pres` → `hotwater#water-pressure` (Phase I) — pairs with the already-exposed
  `heating#water-pressure`.
- `report.details.regulation_state` → ~~`heating#regulation-state`~~ (Phase I) — cheap, useful "is the
  regulation algorithm active" status, unlike the other `report.details` internals which have no
  external meaning; still INFERRED, not VERIFIED. **Removed in the 2026-09-15 channel audit** for
  exactly that reason — a Switch whose ON/OFF meaning was never more than a naming guess and never
  got verified.
- `schedules.ch_schedule.base_temp` / `schedules.dhw_schedule.base_temp` — **done (Phase B)**, exposed
  as `heating#schedule-base-temperature`/`hotwater#schedule-base-temperature`. **Both writable now**:
  `heating#schedule-base-temperature` writes `ch_schedule.base_temp` directly (Phase D), and
  `hotwater#schedule-base-temperature` writes `dhw_schedule.base_temp` (moved there from
  `hotwater#target-temperature` in Phase I — see the DHW read/write asymmetry note above). Both resend
  `entries` unchanged, per the confirmed write shape.

**Thing properties (static identity, not channels) — done, Phase H:**

`configuration.boiler_id` → `serialNumber`, `configuration.installer_id` → `installerId`,
`configuration.download_url` → parsed into `firmwareVersion`, plus `status.device_id` → `deviceId` and
a static `vendor` = `"ATAG"`. (`configuration.boiler_det_type` → `boilerDetectType` was also part of
this phase originally; removed in the 2026-09-15 channel audit, see below.)

**Dynamic state description provider, not separate channels — done, Phase H:**

`configuration.dhw_min_set`/`dhw_max_set` now supply the real bounds for
`hotwater#target-temperature` at runtime via `AtagOneStateDescriptionProvider`, resolving the
`min="40"`-vs-device-reports-`10` discrepancy this doc used to flag. `configuration.ch_min_set`/
`ch_max_set` remain deliberately unwired to anything — boiler _water_ limits (20–85°C), not room
setpoint bounds.

**Remain unexposed (decision unchanged):**

- All `report.details` regulation internals except `regulation_state` (no cloud/app surface, no
  external meaning established)
- `configuration.shower_time_mode`, `comfort_settings`, `report_url`, `support_contact` (no
  cloud/app surface)
- `control.dhw_mode` — Phase G's manual read gives it a plausible combi-boiler meaning (ECO/COMFORT
  DHW schedule mode, pp. 23/34) but this device's DHW schedule shape doesn't match a combi boiler, so
  it stays unexposed rather than guessing at a mapping that may not transfer — see the `control` table
  above
- Counters reading 0 with undetermined hardware support (`boiler_capacity`, `lmuc_burner_hours`,
  `lmuc_dhw_hours`, `lmuc_burner_starts`)
- `pressure_unit`, `temp_unit`, `time_format` — **decision: leave unexposed** (2026-09-13). Near-zero
  automation value (changing the thermostat's own display unit from openHAB), and adding three more
  channels plus enum maps for it works against the channel-surface-size concern the PR reviewer
  already raised. Revisit only if actually requested.

**Channel-group placement rule (Phase 1 revisit, 2026-08-28):** group = domain/subsystem
(`heating`/`hotwater`/`device`/`alerts`), with `control` as the one deliberate exception — it holds
the operating-mode/preset concern, which is cross-cutting and belongs to no single subsystem. The
status-vs-configuration axis is carried by `advanced="true"` on the channel-type, never by a separate
group — a settings group was tried and abandoned (see [[project_atagone_binding]] memory) because it
ended up holding a single channel while every other settings-shaped field belonged with its subsystem.
A measurement and its setpoint always live together (`heating#target-temperature` next to
`heating#room-temperature`; `hotwater#target-temperature` next to `hotwater#temperature`). Drop a
subsystem prefix from a channel id once its group already carries it (`hotwater#temperature`, not
`hotwater#dhw-temperature`); keep a qualifier that disambiguates within the group
(`heating#room-temperature` keeps `room`, since `heating` holds both room-air and boiler-water
readings).

**Settings channels — placement, per the rule above.** All done (Phases F/J, 2026-09-13), using the
`fillConfigBundle` full-configuration-bundle write pattern Phase E established for
`heating#control-mode`.

| Fields | Group | Notes |
|---|---|---|
| `frost_prot_*`, `summer_eco_*`, `ch_heating_type`, `ch_isolation`, `ch_building_size`, `wdr_temps_influence`, `climate_zone`, `max_preheat` | `heating` | advanced, writable |
| `wd_temp_offs`, `outs_temp_offs`, `room_temp_offs` | `heating` | advanced, writable — **Phase J**, closing the field Phase F deferred; three offsets, not two, per the manual (see `configuration` table above) |
| `dhw_legion_enabled`/`_day`/`_time` | `hotwater` | advanced, writable |
| `ch_mode_vacation`, `ch_mode_extend` | `control` | **channels removed, channel audit 2026-09-15** — genuine device-persisted fields (VERIFIED), but only the binding's own `composeVacationActivation()`/`composeExtendActivation()` ever consult them (see the write-semantics note above); no evidence the device applies them as a fallback on its own, e.g. via the physical display. Still read every poll into `defaultVacationDurationSeconds`/`defaultExtendDurationSeconds` for that internal use — the fields themselves aren't going anywhere, just the channels that let a user write them directly |
| `disp_brightness` | `device` | advanced, writable — VERIFIED via its own dedicated live test, separate from the rest of the Phase F group |
| `time_zone` | `device` | advanced, **made writable 2026-09-14** (was read-only) — only `1=Berlin` is device-verified; the other 9 enum values are inferred from dropdown order only, which is the exact risk class that has caused live incidents in this project before, so **the binding never treats them as verified even though it now accepts writing them**. Reversed at the explicit request of this device's own owner, who accepts that risk for their own device; the field comment on `AtagOneBindingConstants.TIME_ZONE_BY_NAME`'s use site and the channel description both carry the caveat forward |
| `language` | `device` | advanced, **made writable 2026-09-15** (was read-only) — reversed once the device owner confirmed against the app that all 5 values are real, selectable options, unlike `time_zone`'s partial-verification situation |
| `dhw_min_set`/`dhw_max_set`, `ch_min_set`/`ch_max_set` | — | Not channels: dynamic state description provider (see above) |
| `boiler_id`, `installer_id`, firmware version | — | Not channels: Thing properties (see above) |

### Double-mapping inventory (Phase I3, deliberate, documented not fixed)

Per decision: fix only the DHW read/write asymmetry (done, above); document the rest as legitimate.

| Field | Mapped to | Why this is fine |
|---|---|---|
| `control.ch_mode_duration` | `control#extend-remaining` + `control#fireplace-remaining` | Mode-gated — only one of the two ever reads non-UNDEF at a time (see `updateChannels()`'s if/else-if chain on `ch_mode`). Same field, two differently-named views for discoverability per active mode. (A third view, `control#preset-mode-duration`, was removed in the final-review sweep — it was a literal duplicate of whichever of these two was active, with no distinct use case found.) |
| `control.ch_mode_temp` | `heating#target-temperature` + `control#vacation-temperature` (during active holiday) | The device itself reuses this field as "whatever the currently active mode's live setpoint is" — reflecting that faithfully means both channels legitimately show it during holiday |
| `report.boiler_status` | `heating#flame` (bit `0x008`) + `heating#central-heating-active`/`hotwater#hot-water-active` (bits `0x002`/`0x004`) | Disjoint bits of one bitmask, decoded into two views (a flame indicator and two independent booleans). A third view, `heating#burner-target` (a prioritized "which one" string built from the same two bits), was removed in the 2026-09-15 channel audit — strictly derivable from, and lossier than, the two booleans it duplicated. Bit values corrected 2026-09-14, see above |
| `control.vacation_duration` | `control#vacation-duration` directly, plus a derivation input to `control#vacation-end`/`control#vacation-remaining` | One raw value feeding one direct channel and two computed ones — standard derivation, not duplication |
| `configuration.start_vacation` | `control#vacation-start` directly, plus a derivation input to `control#vacation-end`/`control#vacation-remaining` | Same pattern as above |
| **The device's own duplicates**: `wd_k_factor`, `wd_exponent`, `mu` | Each appears in both `report.details` and `configuration` | Not the binding's doing — the device itself reports these three fields in two places. Neither location is exposed as a channel (all UNKNOWN, no cloud/app surface), so this causes no user-facing confusion, only a documentation note |

### Channels the ATAG portal doesn't show (decision: keep all, Phase I)

The user supplied a full inventory of what the ATAG cloud portal's screens expose. 15 of the
binding's 63 channels aren't in that list. This count has moved three times since the original
decision: 18 of 63 at the time (Phase I); 17 of 69 after the final-review sweep removed
`heating#shown-set-temperature` and `control#preset-mode-duration`; 15 of 61 after the same-day
channel audit removed three more portal-absent channels outright
(`heating#min-modulation-level`, `heating#max-boiler-temperature`, `device#memory-allocation` — see
the removal notes above); 15 of 63 after the whole-schedule read channels were added — `heating#schedule`/
`hotwater#schedule` aren't counted as portal-absent below, since the portal does expose the underlying
schedule, just through its own graphical editor rather than as JSON. Decision: keep the rest — most surface on the app's own
_Diagnosis_ screen (manual p. 10: "shows more details of status and readings on the boiler") or the
ONE controller's own SYSTEM DIAGNOSTICS menu (manual p. 46), neither of which the portal inventory
covered; a few are genuinely binding-only diagnostics with no ATAG-side surface at all, called out
below.

| Channel | ATAG-side surface |
|---|---|
| `device#voltage`, `device#wifi-signal`, `device#resets`, `device#pcb-temperature` | Controller's own SYSTEM DIAGNOSTICS menu (manual p. 46) — hardware self-diagnostics, not user settings |
| `heating#boiler-temperature`, `heating#boiler-return-temperature`, `heating#modulation-level` | App's _Diagnosis_ screen (manual p. 10) — boiler-side detail beyond the portal's summary view |
| `heating#time-to-target` | No direct ATAG-side screen found; genuinely binding-only, kept as a low-cost useful diagnostic with clear, unambiguous meaning |
| `heating#weather-status` | Displayed as an icon on the ONE/app/portal front screen (manual pp. 5, 16), not as a named settings-screen field — the portal inventory's screen-by-screen list didn't capture front-screen icons |
| `hotwater#flow-rate` | App's _Diagnosis_ screen |
| `control#vacation-remaining`, `control#extend-remaining`, `control#fireplace-remaining` | Split out per mode for discoverability — see Double-mapping inventory above; the portal shows this as one countdown next to the active mode |
| `alerts#device-errors`, `alerts#boiler-errors` | Controller's own notification history (manual p. 26, "Notifications") and SUPPORT/DIAGNOSTICS menu, not a portal screen field |

## Resolved (2026-08-27) — no longer open

The exhaustive manual test report referenced throughout the Write semantics section above settled
these:

- `ch_mode_duration` presence/value for cancellation — resolved: it's specifically
  `ch_mode_duration` (not the mode-specific field) that must be zeroed to cancel, for all three
  timed presets; the mode-specific duration fields are irrelevant to cancellation.
- Vacation's activation requirement — fully mapped: `ch_mode` + `start_vacation` is the hard
  requirement, `vacation_duration` follows the same stored-or-fresh pattern the other two modes use.
- `end_vacation` — confirmed **not** to exist as a field, checked twice via full-field greps of
  separate `/retrieve` captures. Settled negative; don't re-investigate.
- `configuration.language` — resolved: the app's language dropdown is English/Niederländisch
  (Dutch)/Französisch (French)/Italienisch (Italian)/Deutsch (German), 0-indexed. This device reads
  `language:4` and its display is confirmed set to Deutsch — index 4 lands on German, matching
  exactly. `4=German` treated as VERIFIED on the strength of that consistency check, even without a
  live test cycling every other value.

## Open questions

Every item below needs a live retest before being treated as settled. None require code changes to
investigate — all are either read-only checks or reuse an already-proven write shape with one
deliberately varied field.

1. ~~Manual mode (`ch_mode:1`) applied cleanly with no restart~~ **CLOSED, VERIFIED 2026-09-13.**
   Second independent confirmation, this time deliberate: cancelled the device's own live manual-mode
   session (a real physical adjustment, 19.5°C) to auto, confirmed clean (`acc_status:2`, `resets`
   unchanged), then wrote `ch_mode:1` + `ch_mode_temp:19.5` directly to return to manual. Applied
   immediately, `resets` unchanged, and a delayed recheck 60s later showed no instability. The
   rejection's original basis was never traceable in this project — two clean live tests now
   outweigh an unsourced caution. **`preset-mode=manual` is writable as of this session**, composing
   `ch_mode=1` plus whichever temperature `heating#target-temperature` last reported (see
   `composeManualActivation()`), matching how the app behaves when switching to manual from another
   mode.
1. Why does `fireplace_duration` revert to its factory default specifically after the
   physical-confirmation cancel path — would it also revert after a hypothetically successful
   API-only cancel? Not isolated; API-only cancel for fireplace has never been observed to actually
   take effect on its own.
1. Which transitions are expected to bump the `resets` counter as a normal artifact (e.g. a scheduled
   vacation's actual activation moment) versus signal a real problem? Observed inconsistently; no
   complete list exists.
1. ~~Which field is the outdoor-temperature correction: `wd_temp_offs` or `outs_temp_offs`?~~
   **CLOSED, Phase G/J (2026-09-13).** The manual documents three distinct offsets, not two —
   `wd_temp_offs` is "Temperature shift"/"Temperature correction" (±10°C, offsets the calculated flow
   water temperature), `outs_temp_offs` is "Outside temperature correction" (±5°C, app-only),
   `room_temp_offs` is "Inside temperature correction" (±5°C, VERIFIED). Live-tested Phase J: each
   writes independently with zero cross-field drift. **Residual, low-priority**: not yet visually
   cross-checked against the app's own "Temperature shift"/"Outside temperature correction" labels —
   the range-based reasoning is already a strong discriminator on its own, so this is a nice-to-have,
   not a blocker.
1. `max_preheat`'s non-Automatic values are now **VERIFIED** (user-confirmed against the live device,
   2026-09-13): `0/60/120/180/1440` = Off/1h/2h/3h/Automatic, matching the manual exactly. `time_zone`'s
   10-city order (Amsterdam, Berlin, Brussels, Dublin, Edinburgh, Frankfurt, London, Luxembourg, Paris,
   Rome) remains INFERRED — corroborated by both the app UI and the cloud form's dropdown order, but
   neither source is a live device read at each individual non-Berlin setting. `TIME_ZONE_NAMES` now
   maps all 10 (final-review sweep, 2026-09-13; previously only index 1 was mapped, so 9 of the 10 cases
   this ordering already applied to displayed as "unknown"), still read-only per the reasoning in the
   Gap analysis table above.
1. What does `control.dhw_mode` (reads `1`) enumerate? Still UNKNOWN for this device, but Phase G's
   manual read gives it a **plausible, sourced hypothesis for the first time**: on a combi boiler, the
   DHW schedule switches between COMFORT and ECO mode, stored as `1` for COMFORT and (implicitly) ECO
   otherwise (manual pp. 23, 34: "the schedule will be stored as ECO and 1 for COMFORT, default
   schedule is ECO 24/7"). This device's DHW schedule holds real temperatures rather than
   COMFORT/ECO tokens, which is system-boiler-shaped, not combi-shaped — so the hypothesis may simply
   not apply to this installation type. Still no app or cloud surface exposing a `dhw_mode` setting on
   *this* device, so it remains unexposed. (The `base_temp`-vs-active-schedule-entry precedence
   question this was once suspected to gate is resolved by other means — see the `schedules` section —
   and no longer motivates resolving this one.)
1. ~~What is `boiler_status` bit `0x200`~~ **CLOSED, 2026-09-14.** It's `dhw_schema` (which schedule
   currently governs, not an activity flag) — see the `boiler_status` bitmask correction above. Also
   corrected two other wrong bit assignments (`CH_ACTIVE`, `FLAME`) found while investigating this.
1. What are the true units of `report.current` and `report.power_cons`?
1. What do `control.ch_status` (reads `1`) and `control.dhw_status` (reads `53`) enumerate? Neither
   is a simple boolean — `dhw_status=53` in particular suggests a bitmask or small state machine, not
   an on/off flag. No cloud/app surface found for either. Distinct from `report.boiler_status` (the
   field `heating#flame`/`heating#central-heating-active`/`hotwater#hot-water-active` decode) — these
   two `control` fields have never been parsed or acted on
   at all.
1. What is the actual device-required minimum inter-request interval? Phase A live-verified 1000 ms
   (down from an earlier 2000 ms) with no regression in `OFFLINE`/`COMMUNICATION_ERROR` frequency, so
   1000 ms is confirmed *sufficient*, but not confirmed as the device's true floor. Findable
   read-only: with the binding disabled, send a burst of `/retrieve` calls at progressively shorter
   gaps (e.g. 1000 → 750 → 500 → 250 ms) and find where empty replies start appearing consistently
   rather than intermittently. Confounded by the device's general flakiness (empty replies happen at
   any interval), so look for a change in _rate_, not a hard cutoff.
1. ~~What is the write payload shape for a single `entries` triple?~~ **CLOSED** — VERIFIED live
   2026-09-13, see the `schedules` section's Write shape above. Also surfaced a **new risk**: writing
   `entries` (not just `base_temp` alone) appears to trigger ~100 s of device unresponsiveness
   afterward. No phase currently does per-entry schedule editing — this is foundation for a future one,
   tracked in the plan file's backlog, not an open question needing further investigation on its own.
