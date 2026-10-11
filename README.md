# RailTime Calculator

**Know exactly when your train ticket opens.** RailTime Calculator works out the earliest date and time you can book an Indian Railways ticket on IRCTC, for both **general reservation** (Advance Reservation Period) and **Tatkal**. You get a live countdown and a calendar reminder too. After booking, it estimates your **cancellation refund** and tells you when the **reservation chart** will be prepared.

> **Disclaimer.** This is an independent informational calculator and is not affiliated with or operated by IRCTC or Indian Railways. Booking rules can change. Always verify the current rules on the [official IRCTC website](https://www.irctc.co.in) before making travel plans. The app only calculates opening times. It does not book tickets or show seat availability.

---

## Contents

1. [Features](#features)
2. [Business rules](#business-rules)
3. [Languages](#languages)
4. [Architecture](#architecture)
5. [Technology stack](#technology-stack)
6. [API](#api)
7. [Running locally](#running-locally)
8. [Docker](#docker)
9. [Profiles & configuration](#profiles--configuration) (including [Deploying to Render](#deploying-to-render))
10. [Security](#security)
11. [Performance](#performance)
12. [Testing](#testing)
13. [Project structure](#project-structure)
14. [Future enhancements](#future-enhancements)
15. [IRCTC rule references](#irctc-rule-references)

---

## Features

| | |
|---|---|
| **General reservation calculator** | Origin departure date → opening date/time (60-day ARP, 08:00 IST), with an optional boarding date. |
| **Tatkal calculator** | Origin departure date + class → Tatkal opening (10:00 IST AC / 11:00 IST non-AC). |
| **App layout** | A sidebar (Dashboard · Calculators · Tools · Developer) with a compact header. It collapses to icons (remembered per browser), is icon-only on tablets, and on phones becomes a bottom tab bar (Home · General · Tatkal · Refund · Chart · More, with "More" opening a drawer for the other pages). Every page has its own URL (`/`, `/general`, `/tatkal`, `/refund`, `/chart`, `/status`, `/reminders`, `/rules`), so direct links, refresh and back/forward work. |
| **Dashboard** | The next booking opening (live countdown), what is bookable today, Tatkal windows, quick links to each calculator and your recent calculations. |
| **Booking status** | What you can book right now and what opens next, updated live. |
| **Result actions** | Copy a clean text summary, share it (Web Share API, with copy as the fallback) and, for General/Tatkal, add a calendar reminder. |
| **Reminders** | The reminders you added, with re-download and remove. Kept only in this browser. |
| **Usage & traffic** | Anonymous counts on the dashboard: page visits (today, this week, this month, total), successful calculations per calculator, and a 30-day trend. `GET /api/v1/usage` serves them. Counts are kept in memory (the app has no database), so they reset when the server restarts, and the dashboard shows when counting began. Nothing personal is stored: no IP addresses, cookies or user IDs. Crawlers and monitors are not counted. |
| **Cancellation refund estimate** | Train type, class, ticket status, fare, passengers and departure → charge, GST and refund. Uses the refund slabs revised in April 2026 (72 h / 24 h / 8 h), the separate Vande Bharat Sleeper / Amrit Bharat II scale, and the original 2015 slabs for cancellations made before the revision. |
| **Chart preparation time** | Departure date/time → first chart and final chart times, per the Railway Board letter of 16 December 2025, with a countdown. |
| **Aadhaar rules in context** | Opening-day ARP booking (Aadhaar-verified users only until midnight) and the Tatkal Aadhaar OTP / agent restrictions are shown beside the calculators and on the Rules page. |
| **English and Hindi** | A language switcher in the header translates the whole interface, including results, errors and reminders. See [Languages](#languages). |
| **Honest 1A handling** | First AC is reported as *not listed under standard Tatkal classes*. The app never invents a time for it. |
| **Live countdown** | Anchored to the server-computed remaining time, so a wrong browser clock or time zone can't skew it. |
| **Status** | `NOT_OPEN`, `OPEN`, `JOURNEY_DATE_PASSED`, or `NOT_APPLICABLE`. |
| **Add reminder** | Downloads an `.ics` calendar event with a 15-minute alert. |
| **Rule caveats in context** | Shorter-ARP warning, origin-station explanation, Tatkal quota caveat. |
| **REST API + Swagger** | Versioned JSON API, documented with OpenAPI 3.1. |
| **Production-ready** | Spring Security, strict CSP, profiles, ECS JSON logs, layered non-root Docker image. |

---

## Business rules

All rule values live in configuration (`booking.*`), not in code. The rules implemented are:

### General reservation (Advance Reservation Period)

* **ARP = 60 days, excluding the date of journey.**
* Online booking on the opening day starts at **08:00 AM IST**.
* The calculation uses the train's **originating-station departure date**, not the passenger's boarding date.

**How the 60 days are counted.** "60 days, excluding the date of journey" is interpreted as follows. The opening date `D` is the day for which the days `D, D+1, …, J−1` number exactly 60, with the journey date `J` itself not counted. Therefore:

```
D = J − 60 days
```

Worked example:

```
Journey (origin departure) : 20 Dec 2026
Opening date               : 21 Oct 2026, 08:00 AM IST
Check: 21 Oct … 19 Dec inclusive = 11 (Oct) + 30 (Nov) + 19 (Dec) = 60 days; 20 Dec excluded ✔
```

The subtraction uses `LocalDate.minusDays`, which is real calendar arithmetic. Month lengths, February, leap days and year boundaries therefore need no special cases. A unit test checks the counting rule (`opening.datesUntil(journey).count() == 60`) for **every day from 2025 to 2029**, along with named boundary cases:

| Journey | Opens | Scenario |
|---|---|---|
| 2026-12-20 | 2026-10-21 | specification example |
| 2026-01-01 | 2025-11-02 | year boundary |
| 2026-02-28 | 2025-12-30 | end of February |
| 2028-02-29 | 2027-12-31 | leap day |
| 2028-04-29 | 2028-02-29 | opening falls on a leap day |
| 2026-12-31 | 2026-11-01 | 31 December |
| 2027-01-01 | 2026-11-02 | December → January |

⚠️ **Shorter ARP exceptions.** Some trains, including certain intercity/day trains, may have a shorter ARP. The app does not know train-specific ARPs, so it warns about this on every general result instead of guessing.

**Aadhaar-only opening day.** Since 12 January 2026, on the ARP opening day only Aadhaar-authenticated IRCTC users can book general reserved tickets online, from 08:00 IST until midnight (rolled out from the first 15 minutes on 1 Oct 2025, to 08:00–12:00 on 29 Dec 2025, 08:00–16:00 on 5 Jan 2026, then to midnight). Other users can book from the next day, and counter (PRS) booking is not affected. The opening *time* doesn't change, so the calculation is the same; the rule is shown as a note.

### Tatkal

* Opens **one day before the train's origin departure date** (journey date excluded).
* **AC classes, 10:00 AM IST:** 2A, 3A, CC, EC, 3E
* **Non-AC classes, 11:00 AM IST:** SL, FC, 2S

```
Origin 20 Dec 2026, SL → 19 Dec 2026, 11:00 AM IST
Origin 20 Dec 2026, 3A → 19 Dec 2026, 10:00 AM IST
```

* Online Tatkal needs an **Aadhaar-authenticated** IRCTC account (since 1 July 2025) and an **Aadhaar OTP** at booking (since 15 July 2025).
* Authorised agents cannot book in the **first 30 minutes** of each window (10:00–10:30 AC, 11:00–11:30 non-AC); configurable as `booking.tatkal.agent-blackout-minutes`.

### 1A (First AC) handling

1A is not listed among the standard IRCTC Tatkal classes. The domain models this as the business-rule state `TatkalCategory.NOT_LISTED`, not as an error and not as a time. The API returns HTTP 200 with `status: NOT_APPLICABLE`, no date/time fields, and this message:

> 1A Tatkal availability is not listed in the standard IRCTC Tatkal classes. Please verify availability for the specific train.

### Origin station vs boarding station

Opening dates are always based on the train's **originating-station** departure date. If you board at an intermediate station on a later day, the opening date doesn't change. The optional `boardingDate` field is accepted only so the app can explain this to the user.

### Status and date window

| Status | Meaning |
|---|---|
| `NOT_OPEN` | Opening instant is in the future; `millisUntilOpening` is provided. |
| `OPEN` | Opening instant has passed (inclusive) and the journey date hasn't. |
| `JOURNEY_DATE_PASSED` | Origin departure date is before today (IST). |
| `NOT_APPLICABLE` | No standard opening time applies (1A Tatkal). |

The API accepts journey dates from 365 days in the past to 730 days in the future (both configurable). Dates outside that window return `400 INVALID_DATE`.

### Today's booking windows

Journey date `J` opens at 08:00 IST on `J − 60`. So from 08:00 onwards, journeys up to `today + 60` are open and `today + 61` opens tomorrow at 08:00; before 08:00, only up to `today + 59` are open and `today + 60` opens at 08:00 today. Today's Tatkal windows (10:00 AC / 11:00 non-AC) are for journeys departing tomorrow.

### Cancellation refund

Railway Passengers (Cancellation of Ticket and Refund of Fare) Rules, 2015, as amended in 2026. Charges are per passenger. All values live in `booking.cancellation` in `application.yml`. The response says which scale applied in `ruleSet`.

**Standard trains, `STANDARD_2026`** (all trains except the two below; cancellations from 1 April 2026; slabs announced by the Railways Minister on 24 March 2026 and rolled out 1–15 April 2026):

| Ticket | When cancelled | Charge per passenger |
|---|---|---|
| Confirmed | More than 72 h before departure | Flat: ₹240 1A/EC, ₹200 2A/FC, ₹180 3A/CC/3E, ₹120 SL, ₹60 2S |
| Confirmed | 72 h to 24 h | 25% of fare, at least the flat charge |
| Confirmed | Under 24 h to 8 h | 50% of fare, at least the flat charge |
| Confirmed | Within 8 h | No refund |
| Confirmed Tatkal | Any time | No refund |
| RAC / waitlisted | Up to 30 min before departure | ₹60 clerkage |
| RAC / waitlisted | Within 30 min | No refund |

**Earlier cancellations, `STANDARD_2015`.** A cancellation dated before `booking.cancellation.revised-from` (default 2026-04-01; env `REFUND_RULES_REVISED_FROM`) uses the original 2015 slabs: flat above 48 h, 25% from 48 h to 12 h, 50% from 12 h to 4 h, then nothing.

**Vande Bharat Sleeper and Amrit Bharat II, `PREMIUM_SLEEPER_2026`** (`trainType` `VANDE_BHARAT_SLEEPER` / `AMRIT_BHARAT_II`; G.S.R. 41(E) of 16 January 2026, Commercial Circular 08 of 2026):

| Ticket | When cancelled | Charge per passenger |
|---|---|---|
| Confirmed | More than 72 h before departure | 25% of fare (no flat minimum) |
| Confirmed | 72 h to 8 h | 50% of fare |
| Confirmed | Within 8 h | No refund |

Vande Bharat Sleeper has no RAC, so `RAC` with that train type returns `400 VALIDATION_ERROR` on `ticketStatus`. Tatkal and waitlisted tickets follow the common rules above.

On every scale, GST at 5% is added to the charge for AC classes and First Class (1A, EC, 2A, FC, 3A, CC, 3E). Charges never exceed the fare. Amounts use `BigDecimal`, rounded half-up to the paisa. IRCTC convenience fees are not refunded, and the response always says it is an estimate.

### Reservation chart

Railway Board letter of 16 December 2025, which replaced the July 2025 directive (values in `booking.chart`):

* Departures from **05:01 to 14:00** (both inclusive): first chart by **20:00 the previous day**.
* All other departures (14:01 to 05:00): first chart **10 hours before** departure.
* Final chart: **30 minutes before** departure.

Status is `PENDING` (with `millisUntilFirstChart`), `FIRST_CHART_PREPARED`, `FINAL_CHART_PREPARED` or `DEPARTED`.

### Known uncertainties

* **Train-specific ARP.** Shorter ARPs exist for some trains, and the app can only warn about them.
* **Tatkal quota.** Not every train or class carries a Tatkal quota.
* **Class lists.** The Tatkal class lists follow IRCTC's documented Tatkal classes. Some older Indian Railways material lists fewer AC classes. If IRCTC's list changes, update `TravelClass`.
* **Refund edge cases.** Partially confirmed tickets, TDR claims, trains cancelled by the railways and counter (PRS) tickets follow extra rules the estimate doesn't model. IRCTC shows the final amount before you cancel.
* **Charting changes.** Charting times can be revised by Indian Railways. The December 2025 Railway Board letter is used; the app adds a note for departures before it took effect.
* **2026 refund rollout.** The revised standard-train slabs were rolled out between 1 and 15 April 2026. The app switches on 1 April (configurable), so a cancellation in that fortnight may have been charged under either scale.
* **Aadhaar conditions.** Aadhaar-only booking windows change who can book, not when booking opens, so they are shown as notes and not modelled.

---

## Languages

The web interface is available in **English** and **हिन्दी (Hindi)**. The REST API stays in English, because it is a developer interface.

**Choosing the language.** First match wins:

1. `?lang=hi` or `?lang=en` in the URL (what the header's globe menu links to). The choice is saved in the `railtime.lang` cookie (1 year, `HttpOnly`, `SameSite=Lax`, `Secure` on HTTPS). The cookie only stores the language preference.
2. The `railtime.lang` cookie from an earlier choice.
3. The browser's `Accept-Language`, if it names a supported language.
4. English.

Unsupported values (for example `?lang=fr`) are ignored. The page sends `Vary: Cookie, Accept-Language` and `hreflang` alternate links for search engines.

**How it works.**

* Page text comes from `src/main/resources/messages.properties` (English) and `messages_hi.properties`, rendered by Thymeleaf (`#{...}`).
* Keys starting with `js.` are also embedded in the page as JSON (`<script type="application/json" id="page-data">`, which is data and never executed, so the strict CSP is unchanged), together with the rule values the script needs.
* `app.js` renders results from the API's structured fields (ISO dates, enums, amounts), not from its English sentences. Dates use the browser's `Intl` API (for example `21 अक्टूबर 2026`); times keep the `08:00 AM IST` form used on IRCTC.
* Devanagari text uses Noto Sans Devanagari (loaded only on Hindi pages), with letter-spacing removed and slightly larger small labels.

**Adding a language.** Add a constant to `i18n/Language` and a `messages_<code>.properties` file with every key. `MessageBundlesTest` fails if any key or `{0}` placeholder is missing. Have a native speaker review the wording.

---

## Architecture

```
Browser (Thymeleaf page + vanilla JS)
      │  fetch() JSON
      ▼
BookingCalculatorController ──► ReservationCalculatorService ─┐
HealthController                TatkalCalculatorService ──────┤
WebPageController                                             ▼
                                              BookingRuleService  ◄── BookingRulesProperties (booking.*)
                                               (single source of      Clock (injectable)
                                                truth for rules)
GlobalExceptionHandler / JsonSecurityErrorHandler ──► ErrorResponseFactory
```

* **One template, several pages.** `WebPageController` serves every page path (see the `Page` enum) with the same `index.html`; the path decides which view is visible at first paint, the page title, canonical URL and active sidebar item. `app.js` then switches views with the History API, so moving between pages is instant and keeps results. The same `Page` list drives the security allow-list, the ETag filter and language switching.
* **Controllers are thin.** They handle binding, validation and HTTP concerns only.
* **`BookingRuleService`** owns every rule: ARP arithmetic, Tatkal timing, status, the date window and notes. The calculators orchestrate it, and the web page reads from the same rules, so the page and the API can't disagree.
* **Time is injected** as a `java.time.Clock` bean, so every "now" is deterministic in tests. All business logic uses `java.time` (`LocalDate`, `LocalTime`, `ZonedDateTime`, `ZoneId`, `Duration`, `ChronoUnit`) in `Asia/Kolkata`. There is no `Date`, `Calendar` or `SimpleDateFormat`.
* **Immutable DTOs.** Requests and responses are Java records, and constructor injection is used throughout.
* **No database, cache or external calls.** Every calculation is a pure function of its input and the clock.

---

## Technology stack

| Layer | Choice |
|---|---|
| Language | **Java 25** (LTS) |
| Framework | **Spring Boot 4.1.1** (Spring Framework 7, Spring Security 7, Jackson 3) |
| View | Thymeleaf, HTML5, hand-written CSS, vanilla ES-module JavaScript (no frontend framework) |
| API docs | springdoc-openapi 3.1.1 (OpenAPI 3.1, Swagger UI) |
| Build | Maven, JaCoCo |
| Tests | JUnit 5, Mockito, AssertJ, Spring Boot Test, MockMvc |
| Runtime | Docker multi-stage → `eclipse-temurin:25-jre-alpine`, non-root |

---

## API

Base path: `/api/v1/booking`. Every endpoint is JSON and stateless.

### `POST /api/v1/booking/general/calculate`

```json
{ "originDepartureDate": "2026-12-20" }
```
Optional: `"boardingDate": "2026-12-21"`.

```json
{
  "type": "GENERAL_RESERVATION",
  "journeyDate": "2026-12-20",
  "bookingDate": "2026-10-21",
  "bookingTime": "08:00:00",
  "timeZone": "Asia/Kolkata",
  "bookingDateTime": "2026-10-21T08:00:00+05:30",
  "arpDays": 60,
  "status": "NOT_OPEN",
  "millisUntilOpening": 1987200000,
  "calculatedAt": "2026-09-28T08:00:00+05:30",
  "display": {
    "bookingDate": "21 October 2026",
    "bookingWeekday": "Wednesday",
    "bookingTime": "08:00 AM IST",
    "journeyDate": "20 December 2026"
  },
  "notes": ["Advance Reservation Period (ARP): 60 days, excluding the date of journey.", "…"]
}
```

### `POST /api/v1/booking/tatkal/calculate`

```json
{ "originDepartureDate": "2026-12-20", "travelClass": "SL" }
```

```json
{
  "type": "TATKAL",
  "originDepartureDate": "2026-12-20",
  "travelClass": "SL",
  "travelClassName": "Sleeper",
  "tatkalCategory": "NON_AC",
  "bookingDate": "2026-12-19",
  "bookingTime": "11:00:00",
  "timeZone": "Asia/Kolkata",
  "bookingDateTime": "2026-12-19T11:00:00+05:30",
  "status": "NOT_OPEN",
  "millisUntilOpening": 7095600000,
  "calculatedAt": "2026-09-28T08:00:00+05:30",
  "display": { "bookingDate": "19 December 2026", "bookingWeekday": "Saturday", "bookingTime": "11:00 AM IST", "journeyDate": "20 December 2026" },
  "notes": ["…"]
}
```

`travelClass` accepts `SL, 1A, 2A, 3A, CC, EC, 3E, FC, 2S` (case-insensitive). For `1A`, see [1A handling](#1a-first-ac-handling).

### `POST /api/v1/booking/cancellation/calculate`

```json
{"travelClass":"3A","ticketStatus":"CONFIRMED","totalFare":2450,"passengers":2,
 "departureDate":"2026-12-20","departureTime":"18:30","cancellationDate":"2026-12-18","cancellationTime":"10:00"}
```

`ticketStatus` is `CONFIRMED`, `CONFIRMED_TATKAL`, `RAC` or `WAITLISTED`. `totalFare` is the IRCTC "Booking Amount" for all passengers (1–6). The optional `trainType` is `STANDARD` (default), `VANDE_BHARAT_SLEEPER` or `AMRIT_BHARAT_II`. `cancellationDate` and `cancellationTime` are optional and must be given together; if you leave both out, the current time (IST) is used. The response includes `trainType`, `ruleSet` (`STANDARD_2026`, `STANDARD_2015` or `PREMIUM_SLEEPER_2026`), `slab`, `refundable`, per-passenger and total `charge`, `gst` and `totalRefund`, a plain-English `slabDescription` and `notes`. The example above is 56½ hours before departure, so 25% of the fare applies: ₹306.25 plus ₹15.31 GST per passenger, and `totalRefund` is `1806.88`.

### `POST /api/v1/booking/chart/calculate`

```json
{"departureDate":"2026-12-20","departureTime":"10:30"}
```

Returns `firstChartAt` (`2026-12-19T20:00:00+05:30`), `finalChartAt` (`2026-12-20T10:00:00+05:30`), `rule`, `status`, `millisUntilFirstChart` (while pending), `display` strings and `notes`.

### `GET /api/v1/booking/window`

Returns today's booking windows: `latestBookableJourneyDate`, `nextOpening` (the journey date, when it opens and `millisUntilOpening`) and `tatkal` (tomorrow's journey date and whether the AC and non-AC windows are open). It is sent with `Cache-Control: no-store`.

### `GET /api/v1/booking/rules`

Returns the configured rules and per-class Tatkal times. It is cacheable (`Cache-Control: max-age=3600, public`) and sent with an ETag.

### `GET /public/health`

Returns lightweight liveness JSON. For orchestrators, Actuator is available at `/actuator/health` (with `/liveness` and `/readiness`).

### Errors

Every error uses one shape. Stack traces are never returned.
* `error` is a stable code that clients can branch on.
* `message` says what is wrong and how to fix it.
* `details` lists each problem field.
* `requestId` also appears in the server log line for that request, so a reported problem can be traced.

```json
{
  "timestamp": "2026-09-28T21:30:00+05:30",
  "status": 400,
  "error": "VALIDATION_ERROR",
  "message": "totalFare must be a number",
  "path": "/api/v1/booking/cancellation/calculate",
  "details": [{ "field": "totalFare", "message": "totalFare must be a number" }],
  "requestId": "d0a7a82d"
}
```

| HTTP | `error` | When | Example `message` |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` | Missing field, value out of range, wrong type, or unknown enum value. Several problems are all listed in `details`. | `Departure date is required (and 4 more problems, see details)`, `passengers must be a whole number`, `Invalid value for ticketStatus. Allowed values: …` |
| 400 | `INVALID_DATE` | Not a real ISO date or `HH:mm` time, outside the accepted window, boarding before origin, cancellation at or after departure, or only one of cancellation date/time | `Times must be valid 24-hour times (HH:mm)` |
| 400 | `INVALID_TRAVEL_CLASS` | Unknown class code | `Invalid travel class 'XY'. Allowed values: …` |
| 400 | `MALFORMED_REQUEST` | Missing body, invalid JSON (with line and column), or a body that is not a JSON object | `Request body is not valid JSON (problem at line 1, column 29)` |
| 403 | `FORBIDDEN` | Endpoint not exposed (deny-by-default). A browser opening an unknown page gets the friendly 404 page instead. | `Access to this resource is not permitted` |
| 404 | `NOT_FOUND` | Unknown API path | `No API endpoint exists at this path` |
| 405 | `METHOD_NOT_ALLOWED` | Wrong HTTP method (the `Allow` header lists the right one) | `HTTP method GET is not supported for this endpoint. Use POST.` |
| 406 | `NOT_ACCEPTABLE` | `Accept` header asks for something other than JSON | `This API only returns JSON. …` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Body is not `application/json` | `Content-Type 'text/plain' is not supported. …` |
| 500 | `INTERNAL_ERROR` | Unexpected failure: a generic message and a reference, with details only in the log | `Something went wrong on our side. … quote reference 7f3a2c9e.` |

The web page shows these messages too. When the server names specific fields, the message appears under the matching input (in Hindi, a translated message is shown).

### Swagger UI

* UI: <http://localhost:8080/swagger-ui.html>
* OpenAPI JSON: <http://localhost:8080/v3/api-docs>

Swagger is **on in `dev`** and **off in `prod`** unless `API_DOCS_ENABLED=true`.

```bash
curl -s -X POST localhost:8080/api/v1/booking/tatkal/calculate -H 'Content-Type: application/json' -d '{"originDepartureDate":"2026-12-20","travelClass":"3A"}'
```

---

## Running locally

Prerequisites: **JDK 25**. Maven is not required: the Maven Wrapper (`./mvnw`, or `mvnw.cmd` on Windows) downloads the pinned Maven 3.9.11 on first use.

```bash
./mvnw spring-boot:run
```

Then open <http://localhost:8080>. The `dev` profile is active by default. Once the app is ready, the log prints the dashboard, health and (when enabled) API-docs URLs, using the port actually bound.

| Command | Purpose |
|---|---|
| `./mvnw spring-boot:run` | Run in dev mode (Swagger on, DEBUG logs, template hot reload) |
| `./mvnw test` | Run the test suite |
| `./mvnw verify` | Tests, JaCoCo report (`target/site/jacoco/index.html`) and coverage gate |
| `./mvnw package` | Build `target/railtime-calculator.jar` |
| `java -jar target/railtime-calculator.jar --spring.profiles.active=prod` | Run the JAR with the prod profile |

---

## Docker

The multi-stage build has two stages:

1. **build** (`maven:3.9-eclipse-temurin-25`): resolves dependencies into a cached layer, runs the tests, packages the JAR and extracts it into Spring Boot layers (plain JARs, not nested).
2. **runtime** (`eclipse-temurin:25-jre-alpine`): copies the layers ordered by change frequency, then does an **AOT cache training run**. Java 25's AOT cache (JEP 483/514/515) starts the Spring context once and records the loaded classes and method profiles into `app.aot`, and the container starts with `-XX:AOTCache=app.aot`. Measured locally, startup drops from 3.4 s to 1.9 s; the saving is larger on Render's small CPUs, which matters because free instances cold-start after sleeping. The image runs as the non-root user `railtime`, has a `HEALTHCHECK` on `/actuator/health/liveness` (on `$PORT`), and uses the `prod` profile by default. The web pages, CSS and JS are packaged inside the JAR, so the image serves the whole app.

```bash
docker build -t railtime-calculator .
docker run -p 8080:8080 railtime-calculator
```

To enable Swagger in the container:

```bash
docker run -p 8080:8080 -e API_DOCS_ENABLED=true railtime-calculator
```

With Compose (read-only root filesystem, all capabilities dropped, `no-new-privileges`, 512 MB memory cap):

```bash
docker compose up --build
```

To skip tests in the image build, for example when CI has already run them, add `--build-arg SKIP_TESTS=true`.

---

## Profiles & configuration

| | `dev` (default) | `prod` |
|---|---|---|
| Logging | Human-readable, `com.example.railtime` at DEBUG | **ECS JSON** (structured), WARN root / INFO app |
| Swagger | Enabled | Disabled unless `API_DOCS_ENABLED=true` |
| Error detail | Exception type appended to 500 messages | Generic message only |
| Templates/assets | No caching, live reload | Cached; fingerprinted assets cached for 1 year |
| Proxy headers | n/a | `X-Forwarded-*` honoured (TLS terminated at the proxy) |
| Health details | Shown | Hidden |

### Rule configuration (`application.yml`)

```yaml
booking:
  timezone: Asia/Kolkata
  general:
    advance-days: ${BOOKING_ARP_DAYS:60}   # excluding the date of journey
    opening-time: "08:00"
  tatkal:
    days-before-departure: 1
    ac-opening-time: "10:00"
    non-ac-opening-time: "11:00"
  validation:
    max-days-in-past: 365
    max-days-in-future: 730
```

These values bind to the validated record `BookingRulesProperties` (`@ConfigurationProperties` + `@Validated`). An invalid value, such as `advance-days: 0`, stops the app at startup, so a bad deploy fails before it can serve a wrong answer.

### Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` (`prod` in Docker) | Active profile |
| `PORT` | `8080` | HTTP port |
| `API_DOCS_ENABLED` | `true` in dev, `false` in prod | Expose Swagger/OpenAPI |
| `SITE_URL` | `RENDER_EXTERNAL_URL` on Render, else `http://localhost:8080` | Canonical and Open Graph URLs |
| `CORS_ALLOWED_ORIGINS` | *(empty = same-origin only)* | Comma-separated origins allowed to call `/api/**` |
| `BOOKING_ARP_DAYS` | `60` | Override the ARP if IRCTC changes it |
| `REFUND_RULES_REVISED_FROM` | `2026-04-01` | First cancellation date that uses the 2026 refund slabs |
| `SERVER_MAX_THREADS` | `100` | Tomcat worker threads (prod) |
| `SHUTDOWN_TIMEOUT` | `20s` | How long graceful shutdown waits for in-flight requests |
| `JAVA_TOOL_OPTIONS` | set in the Dockerfile | JVM flags (heap sized from the container limit) |

The app has no secrets. [`.env.example`](.env.example) lists the variable names. If secrets are added later (for example a JWT issuer), supply them through environment variables and never commit them.

---

## Deploying to Render

The repository includes a [`render.yaml`](render.yaml) Blueprint: one Docker web service, no database.

1. Push the repository to GitHub (or GitLab).
2. In Render, choose **New → Blueprint** and select the repository. Render reads `render.yaml`, builds the `Dockerfile` (tests run during the build) and deploys.
3. Wait for the health check to pass. Render calls `GET /actuator/health/readiness`, which returns `{"status":"UP"}` once the app can take traffic. The service is then live at `https://<name>.onrender.com`.

On Render:
* **Region.** `singapore`, the closest Render region to India. Change `region` in `render.yaml` before the first deploy if you need another (a service's region can't be changed later).
* **Port.** Render injects `PORT` and the app binds to it (`server.port=${PORT:8080}`).
* **Links.** Canonical and share links use Render's `RENDER_EXTERNAL_URL` automatically. Set `SITE_URL` only for a custom domain.
* **Logs.** Output is structured ECS JSON on the console (the `prod` profile), which Render's log view shows as is.
* **Deploys.** Only changes to `src/`, `pom.xml`, `Dockerfile` or `.dockerignore` trigger a rebuild (`buildFilter`), so README or CI edits don't redeploy. Render waits for readiness before switching traffic. Graceful shutdown finishes in-flight requests (up to `SHUTDOWN_TIMEOUT`, within `maxShutdownDelaySeconds: 30`). The app is stateless, so any instance can serve any request.
* **Memory.** The JVM heap is capped at 65% of the container limit, leaving room for metaspace and the code cache within the 512 MB of the free and starter plans.
* **Free plan.** Instances sleep after about 15 minutes without traffic. The AOT cache keeps the cold start short. To avoid it entirely, use a paid plan, or point an uptime monitor at `/public/health` (GET and HEAD are both allowed).

### Continuous integration

`.github/workflows/ci.yml` runs `./mvnw verify` (tests and the coverage gate) on every push and pull request, then builds the production image and smoke-tests it (readiness, the home page and a calculation). Render deploys `main` on its own, so CI is the early warning before a deploy.

| Variable on Render | Required | Value |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | yes (set by `render.yaml`) | `prod` |
| `API_DOCS_ENABLED` | no (default `false`) | `true` to publish Swagger UI |
| `SITE_URL` | no | Your custom domain, e.g. `https://railtime.example.com` |
| `CORS_ALLOWED_ORIGINS` | no | Only if other sites call the API from the browser |

---

## Security

The calculator is a **public, anonymous utility**. It stores no user data, and every calculation is a pure function. `SecurityConfig` therefore:

* Uses modern `SecurityFilterChain` beans with lambda DSL and no deprecated APIs. There are two chains:
  * **Docs chain:** Swagger/OpenAPI paths, `permitAll` or `denyAll` depending on `railtime.docs.enabled`.
  * **Application chain:** page, static assets, `/public/**`, `/actuator/health`, `/actuator/info` and `/api/v1/booking/**` are public. **Everything else is denied** (for example `/actuator/env` returns 403 JSON).
* Runs **stateless**: no HTTP session, no cookies, no request cache. Basic auth, form login and logout are disabled, and an empty user store stops Spring Boot from creating a default user (and logging its generated password).
* Allows `HEAD` wherever `GET` is public (pages, `/public/**`, health), so uptime monitors work; everything else stays denied.
* Skips **CSRF** only for `/api/**`. The JSON API uses no cookies or ambient credentials, so there is nothing to forge.
* Sets **strict headers**: a Content-Security-Policy with no inline scripts (`script-src 'self'`), `X-Frame-Options: DENY`, `frame-ancestors 'none'`, `Referrer-Policy: strict-origin-when-cross-origin`, a restrictive `Permissions-Policy`, and HSTS (1 year, subdomains) on HTTPS.
* Rejects **CORS** requests by default. Allowed origins must be opted into via `CORS_ALLOWED_ORIGINS`.
* Returns security rejections in the same `ErrorResponse` JSON shape as the rest of the API.
* Renders results on the frontend with `<template>` + `textContent` only, never `innerHTML`, and echoes only a sanitised prefix of user input in error messages.

**Adding JWT later:** add `spring-boot-starter-oauth2-resource-server`, add `.oauth2ResourceServer(o -> o.jwt(withDefaults()))` to the application chain, and move the endpoints to protect from `permitAll()` to `authenticated()`. Nothing else needs rewriting. The entry point can then return 401 for unauthenticated calls.

---

## Performance

* **Pure and in-memory.** There is no database, no cache server and no outbound calls. A calculation is a few `LocalDate` operations. Measured locally, requests take **~3–4 ms median** end-to-end over HTTP.
* **Precomputed.** Rule notes and the rules document are built once at startup and returned as immutable objects.
* **HTTP caching:**
  * `GET /api/v1/booking/rules` and `/` send **ETags** and return `304 Not Modified` on a match. ETags are weak so that Tomcat still gzips the response.
  * Calculation responses send `Cache-Control: no-store`, because they depend on the current time.
  * CSS and JS are **content-fingerprinted** (`app-<md5>.css`) and cached for a year.
* **Compression** (gzip) is on for HTML, CSS, JS, JSON and SVG responses over 1 KB. JS is served as `text/javascript`, so that type is in the list too (without it, `app.js` went out uncompressed).
* **Keep-alive** uses a 20 s timeout and up to 200 requests per connection. Shutdown is graceful.
* **Small frontend.** There are no frontend frameworks: one CSS file, one JS module and an inline SVG icon sprite.
* **Smooth UI:**
  * **No layout shift on load.** The Today strip is rendered on the server; the script only adds its live countdown. Date hints under inputs have reserved space, and an `Inter Fallback` font face matched to Inter's metrics keeps text from reflowing when the web font arrives.
  * **Even countdowns.** Timers fire just after each whole second of the deadline, instead of on a drifting fixed interval.
  * **Gentle transitions.** Result cards glide to their new height, buttons show a spinner (the label never flickers), menus fade in, and theme and language changes cross-fade using the View Transitions API where supported.
  * **Seamless language switch.** The page keeps your scroll position (via the section anchor), your inputs and your last result.
  * **Respects accessibility settings.** Everything honours `prefers-reduced-motion`.

---

## Testing

```bash
./mvnw verify
```

There are **289 test cases** (counting each parameterised case). The service layer has 99% instruction and 99% branch coverage, with ~97% instruction coverage overall. A JaCoCo gate in `pom.xml` fails the build if service-layer coverage drops below 95% of lines or 90% of branches.

| Suite | Covers |
|---|---|
| `BookingRuleServiceTest` | 60-day ARP boundaries (month, year, February, leap day, Dec→Jan, far future), an every-day invariant for 2025–2029, configurable ARP, Tatkal dates and times per category, 1A, status at the exact 08:00 instant ±1 s, the date window, notes |
| `ReservationCalculatorServiceTest` | Full response for the spec example, 07:59:59 vs 08:00:00, journey today, past journey, far future, boarding-date handling |
| `TatkalCalculatorServiceTest` | SL, FC and 2S → 11:00; 2A, 3A, CC, EC and 3E → 10:00; 1A → `NOT_APPLICABLE`; AC vs non-AC at 10:00; year and leap boundaries |
| `BookingApiIntegrationTest` | API contract, 200/400/404/405/415, invalid JSON, missing fields, invalid enum, invalid calendar dates, ETag → 304 |
| `BookingCalculatorControllerTest` | Mockito: binding, invalid input never reaches services, 500 hides internals |
| `SecurityIntegrationTest` | Public vs denied endpoints, HEAD for uptime monitors, security headers, HSTS, CSRF exemption, no session cookie, CORS rejection, OpenAPI document |
| `CancellationRefundServiceTest` | 2026 slab boundaries (72 h / 24 h / 8 h to the minute), the switch from 2015 slabs on the revision date, the Vande Bharat Sleeper / Amrit Bharat II scale (no flat minimum, no RAC on VB Sleeper), GST rounding and caps, a real IRCTC cancellation |
| `ChartPreparationServiceTest` | 05:01–14:00 inclusive band edges, 20:00 previous-day and 10-hour charts, midnight crossing, status over time |
| `ProductionProfileTest` | Swagger denied in prod, calculator still works |
| `WebPageTest` | SEO and Open Graph tags, disclaimer, class options, date bounds, fingerprinted assets, cache headers |
| `TravelClassTest`, `DateTimeUtilTest` | Code parsing and sanitising, category lists, formatting |

Tests pin time with a fixed `Clock` (`@TestBean`), so they are deterministic on any date.

---

## Project structure

```
railtime-calculator/
├── pom.xml · Dockerfile · .dockerignore · docker-compose.yml · README.md
└── src/
    ├── main/java/com/example/railtime/
    │   ├── RailTimeApplication.java
    │   ├── controller/   BookingCalculatorController, HealthController, WebPageController
    │   ├── service/      BookingRuleService, ReservationCalculatorService, TatkalCalculatorService
    │   ├── model/        TravelClass, TatkalCategory, BookingStatus, BookingType
    │   │   ├── request/  ReservationCalculationRequest, TatkalCalculationRequest
    │   │   └── response/ ReservationCalculationResponse, TatkalCalculationResponse, BookingRulesResponse,
    │   │                 DisplayValues, ErrorResponse, HealthResponse
    │   ├── config/       SecurityConfig, OpenApiConfig, WebConfig, TimeConfig, ApiPaths, JsonSecurityErrorHandler
    │   ├── properties/   BookingRulesProperties, ApplicationProperties
    │   ├── exception/    GlobalExceptionHandler, InvalidDateException, InvalidTravelClassException,
    │   │                 ApiErrorCode, ErrorResponseFactory
    │   └── util/         DateTimeUtil
    ├── main/resources/
    │   ├── application.yml · application-dev.yml · application-prod.yml
    │   ├── templates/    index.html, error.html
    │   └── static/       css/app.css, js/app.js, images/og-image.png, favicon.svg, robots.txt, site.webmanifest
    └── test/java/com/example/railtime/   (see Testing)
```

---

## Future enhancements

The design already has the seams for these:

* **Train-aware calculations** (train number, origin/boarding station, schedule, running days, train-specific ARP). This needs an authorised train-information source such as IRCTC's Train Information Enquiry service. Add a provider port that feeds `BookingRuleService`, without changing controllers.
* **Current reservation** and **Premium Tatkal**. Add new `BookingType` values and rule methods, and extend the `booking.*` configuration.
* **Fare calculator** and **PNR status**, as separate services behind new endpoints.
* **Notifications**: browser notifications, email and push reminders. The `.ics` export already exists.
* **PWA**: a manifest is in place, and a service worker can be added for offline use.
* **More languages**: add more Indian languages (the web interface is already translatable; see [Languages](#languages)), and optionally localize API messages via `Accept-Language`.
* **JWT-protected admin endpoints** (see [Security](#security)).
* **Rate limiting** at the edge (reverse proxy or API gateway) if the API is exposed publicly at scale.

---

## IRCTC rule references

Always treat the official sources as authoritative. If they differ from this README, they win.

* IRCTC: <https://www.irctc.co.in> (booking rules, Tatkal and ARP information)
* Indian Railways passenger information: <https://www.indianrail.gov.in>
* IRCTC e-ticket cancellation and refund rules: <https://contents.irctc.co.in/en/eticketCancel.html> and <https://contents.irctc.co.in/en/iticketcancel.html>
* PIB, refund facilities on cancellation (2015 rules): <https://www.pib.gov.in/PressReleasePage.aspx?PRID=1527662&reg=48&lang=2>
* North Central Railway, refund rules: <https://ncr.indianrailways.gov.in/print_section.jsp?lang=0&id=0%2C1%2C283%2C363%2C444>
* Indian Railways GST FAQ (5% GST on cancellation charges for AC / FC): <https://indianrailways.gov.in/GST/General_FAQ.pdf>
* All India Radio, Railways Minister's reforms of 24 March 2026 (refund slabs of 72 h / 24 h / 8 h for all trains; boarding-point change up to 30 minutes before departure): <https://www.newsonair.gov.in/railways-minister-ashwini-vaishnaw-launches-five-reforms-in-railways-52-reforms-in-52-weeks-drive>
* Railway Board Commercial Circular 08 of 2026 (19 January 2026), implementing G.S.R. 41(E) of 16 January 2026: refund scale for Vande Bharat Sleeper and Amrit Bharat II: <https://www.indianrailwayrules.com/wp-content/uploads/2026/03/Introduction-of-Vande-Bharat-Sleeper-and-Amrit-Bharat-II-with-rationalised-refund-provision-Commercial-Circular-No.-08-of-2026-19.01.2026-indianrailwayrules.com_.pdf>
* Railway Board letter of 16 December 2025 on charting (first chart by 20:00 the previous day for 05:01–14:00 departures, 10 hours before otherwise), as reported by Business Standard: <https://www.business-standard.com/india-news/indian-railways-rules-2025-new-chart-updates-for-waiting-list-and-racs-nc-125121800905_1.html>. It replaced the July 2025 directive (21:00 / 8 hours).
* All India Radio, Aadhaar-only online booking in the ARP opening window (from 1 October 2025, extended to midnight from 12 January 2026): <https://www.newsonair.gov.in/railways-to-allow-general-ticket-booking-online-only-for-aadhaar-verified-users-during-first-15-minutes-from-next-month>
* All India Radio, Aadhaar authentication for online Tatkal (OTP from 15 July 2025): <https://www.newsonair.gov.in/aadhaar-authentication-made-mandatory-for-online-tatkal-ticket-booking-from-july-15>
* The 60-day ARP (reduced from 120 days) has applied since 1 November 2024, per the Ministry of Railways announcement of October 2024.

Note: as of October 2026, IRCTC's own e-ticket cancellation page still shows the 2015 slabs. The 2026 slabs above follow the Ministry's announcement and the amendment notification.

If a rule changes, update `booking.*` in `application.yml`, or `TravelClass` for class lists, and extend the tests.

---

© 2026 RailTime Calculator. An independent informational calculator, not an official IRCTC application.
