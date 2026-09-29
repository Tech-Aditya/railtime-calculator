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
9. [Profiles & configuration](#profiles--configuration)
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
| **Today's booking windows** | A live strip showing the furthest journey date open today, the next date to open (with countdown), and today's Tatkal windows. |
| **Cancellation refund estimate** | Class, ticket status, fare, passengers and departure → charge, GST and refund, per the 2015 refund rules. |
| **Chart preparation time** | Departure date/time → first chart and final chart times, per the July 2025 Railway Board directive, with a countdown. |
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

### Tatkal

* Opens **one day before the train's origin departure date** (journey date excluded).
* **AC classes, 10:00 AM IST:** 2A, 3A, CC, EC, 3E
* **Non-AC classes, 11:00 AM IST:** SL, FC, 2S

```
Origin 20 Dec 2026, SL → 19 Dec 2026, 11:00 AM IST
Origin 20 Dec 2026, 3A → 19 Dec 2026, 10:00 AM IST
```

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

Railway Passengers (Cancellation of Tickets and Refund of Fares) Rules, 2015, as published by IRCTC. Charges are per passenger. All values live in `booking.cancellation` in `application.yml`.

| Ticket | When cancelled | Charge per passenger |
|---|---|---|
| Confirmed | More than 48 h before departure | Flat: ₹240 1A/EC, ₹200 2A/FC, ₹180 3A/CC/3E, ₹120 SL, ₹60 2S |
| Confirmed | 48 h to 12 h | 25% of fare, at least the flat charge |
| Confirmed | Under 12 h to 4 h | 50% of fare, at least the flat charge |
| Confirmed | Within 4 h | No refund (TDR only in specific circumstances) |
| Confirmed Tatkal | Any time | No refund |
| RAC / waitlisted | Up to 30 min before departure | ₹60 clerkage |
| RAC / waitlisted | Within 30 min | No refund |

GST at 5% is added to the charge for AC classes and First Class (1A, EC, 2A, FC, 3A, CC, 3E). Charges never exceed the fare. Amounts use `BigDecimal`, rounded half-up to the paisa. IRCTC convenience fees are not refunded, and the response always says it is an estimate.

### Reservation chart

Railway Board directive effective 10 July 2025 (values in `booking.chart`):

* Departures from 05:00 up to (not including) 14:00: first chart by **21:00 the previous day**.
* All other departures: first chart **8 hours before** departure. A departure at exactly 14:00 falls in this band.
* Final chart: **30 minutes before** departure.

Status is `PENDING` (with `millisUntilFirstChart`), `FIRST_CHART_PREPARED`, `FINAL_CHART_PREPARED` or `DEPARTED`.

### Known uncertainties

* **Train-specific ARP.** Shorter ARPs exist for some trains, and the app can only warn about them.
* **Tatkal quota.** Not every train or class carries a Tatkal quota.
* **Class lists.** The Tatkal class lists follow IRCTC's documented Tatkal classes. Some older Indian Railways material lists fewer AC classes. If IRCTC's list changes, update `TravelClass`.
* **Refund edge cases.** Partially confirmed tickets, TDR claims, trains cancelled by the railways and counter (PRS) tickets follow extra rules the estimate doesn't model. IRCTC shows the final amount before you cancel.
* **Charting changes.** Charting times can be revised by Indian Railways. Only the officially published July 2025 directive is used; unconfirmed reports of further changes are not.
* **Additional conditions.** IRCTC may apply extra conditions to opening-window bookings, such as account or Aadhaar verification. The app mentions this as a note and doesn't model it.

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

`ticketStatus` is `CONFIRMED`, `CONFIRMED_TATKAL`, `RAC` or `WAITLISTED`. `totalFare` is the total for all passengers (1–6). `cancellationDate` and `cancellationTime` are optional and must be given together; if you leave both out, the current time (IST) is used. The response includes `slab`, `refundable`, per-passenger and total `charge`, `gst` and `totalRefund`, a plain-English `slabDescription` and `notes`. For the example above, the result is a flat ₹180 plus ₹9 GST per passenger, so `totalRefund` is `2072.00`.

### `POST /api/v1/booking/chart/calculate`

```json
{"departureDate":"2026-12-20","departureTime":"10:30"}
```

Returns `firstChartAt` (`2026-12-19T21:00:00+05:30`), `finalChartAt` (`2026-12-20T10:00:00+05:30`), `rule`, `status`, `millisUntilFirstChart` (while pending), `display` strings and `notes`.

### `GET /api/v1/booking/window`

Returns today's booking windows: `latestBookableJourneyDate`, `nextOpening` (the journey date, when it opens and `millisUntilOpening`) and `tatkal` (tomorrow's journey date and whether the AC and non-AC windows are open). It is sent with `Cache-Control: no-store`.

### `GET /api/v1/booking/rules`

Returns the configured rules and per-class Tatkal times. It is cacheable (`Cache-Control: max-age=3600, public`) and sent with an ETag.

### `GET /public/health`

Returns lightweight liveness JSON. For orchestrators, Actuator is available at `/actuator/health` (with `/liveness` and `/readiness`).

### Errors

Every error uses one shape. Stack traces are never returned.

```json
{
  "timestamp": "2026-09-28T21:30:00+05:30",
  "status": 400,
  "error": "VALIDATION_ERROR",
  "message": "Origin departure date is required",
  "path": "/api/v1/booking/general/calculate",
  "details": [{ "field": "originDepartureDate", "message": "Origin departure date is required" }]
}
```

| HTTP | `error` | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Missing required field, value out of range, or unknown enum value (the message lists the allowed values) |
| 400 | `INVALID_DATE` | Not a real ISO date (for example `2026-02-30`) or `HH:mm` time, outside the accepted window, boarding before origin, cancellation at or after departure, or only one of cancellation date/time |
| 400 | `INVALID_TRAVEL_CLASS` | Unknown class code |
| 400 | `MALFORMED_REQUEST` | Missing body or invalid JSON |
| 403 | `FORBIDDEN` | Endpoint not exposed (deny-by-default) |
| 404 | `NOT_FOUND` | Unknown API path |
| 405 | `METHOD_NOT_ALLOWED` | Wrong HTTP method |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Body is not `application/json` |
| 500 | `INTERNAL_ERROR` | Unexpected failure (generic message only) |

### Swagger UI

* UI: <http://localhost:8080/swagger-ui.html>
* OpenAPI JSON: <http://localhost:8080/v3/api-docs>

Swagger is **on in `dev`** and **off in `prod`** unless `API_DOCS_ENABLED=true`.

```bash
curl -s -X POST localhost:8080/api/v1/booking/tatkal/calculate -H 'Content-Type: application/json' -d '{"originDepartureDate":"2026-12-20","travelClass":"3A"}'
```

---

## Running locally

Prerequisites: **JDK 25** and **Maven 3.9+**.

```bash
mvn spring-boot:run
```

Then open <http://localhost:8080>. The `dev` profile is active by default.

| Command | Purpose |
|---|---|
| `mvn spring-boot:run` | Run in dev mode (Swagger on, DEBUG logs, template hot reload) |
| `mvn test` | Run the test suite |
| `mvn verify` | Tests, JaCoCo report (`target/site/jacoco/index.html`) and coverage gate |
| `mvn package` | Build `target/railtime-calculator.jar` |
| `java -jar target/railtime-calculator.jar --spring.profiles.active=prod` | Run the JAR with the prod profile |

---

## Docker

The multi-stage build has two stages:

1. **build** (`maven:3.9-eclipse-temurin-25`): resolves dependencies into a cached layer, runs the tests, packages the JAR and extracts Spring Boot layers.
2. **runtime** (`eclipse-temurin:25-jre-alpine`): copies the layers ordered by change frequency, runs as the non-root user `railtime`, has a `HEALTHCHECK` on `/actuator/health/liveness`, and uses the `prod` profile by default.

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
| `SITE_URL` | `http://localhost:8080` | Canonical and Open Graph URLs |
| `CORS_ALLOWED_ORIGINS` | *(empty = same-origin only)* | Comma-separated origins allowed to call `/api/**` |
| `BOOKING_ARP_DAYS` | `60` | Override the ARP if IRCTC changes it |
| `SERVER_MAX_THREADS` | `100` | Tomcat worker threads (prod) |

The app has no secrets. If some are added later (for example a JWT issuer), supply them through environment variables and never commit them.

---

## Security

The calculator is a **public, anonymous utility**. It stores no user data, and every calculation is a pure function. `SecurityConfig` therefore:

* Uses modern `SecurityFilterChain` beans with lambda DSL and no deprecated APIs. There are two chains:
  * **Docs chain:** Swagger/OpenAPI paths, `permitAll` or `denyAll` depending on `railtime.docs.enabled`.
  * **Application chain:** page, static assets, `/public/**`, `/actuator/health`, `/actuator/info` and `/api/v1/booking/**` are public. **Everything else is denied** (for example `/actuator/env` returns 403 JSON).
* Runs **stateless**: no HTTP session, no cookies, no request cache. Basic auth, form login and logout are disabled.
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
mvn verify
```

There are **132 tests**. The service layer has 100% line and 97% branch coverage, with ~98% line coverage overall. A JaCoCo gate in `pom.xml` fails the build if service-layer coverage drops below 95% of lines or 90% of branches.

| Suite | Covers |
|---|---|
| `BookingRuleServiceTest` | 60-day ARP boundaries (month, year, February, leap day, Dec→Jan, far future), an every-day invariant for 2025–2029, configurable ARP, Tatkal dates and times per category, 1A, status at the exact 08:00 instant ±1 s, the date window, notes |
| `ReservationCalculatorServiceTest` | Full response for the spec example, 07:59:59 vs 08:00:00, journey today, past journey, far future, boarding-date handling |
| `TatkalCalculatorServiceTest` | SL, FC and 2S → 11:00; 2A, 3A, CC, EC and 3E → 10:00; 1A → `NOT_APPLICABLE`; AC vs non-AC at 10:00; year and leap boundaries |
| `BookingApiIntegrationTest` | API contract, 200/400/404/405/415, invalid JSON, missing fields, invalid enum, invalid calendar dates, ETag → 304 |
| `BookingCalculatorControllerTest` | Mockito: binding, invalid input never reaches services, 500 hides internals |
| `SecurityIntegrationTest` | Public vs denied endpoints, security headers, HSTS, CSRF exemption, no session cookie, CORS rejection, OpenAPI document |
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
* Central Railway, "Charting timings revised" (first chart by 21:00 the previous day for 05:00–14:00 departures, 8 hours before otherwise; effective 10 July 2025): <https://cr.indianrailways.gov.in/view_detail.jsp?lang=0&dcd=9856&id=0%2C4%2C268>
* The 60-day ARP (reduced from 120 days) has applied since 1 November 2024, per the Ministry of Railways announcement of October 2024.

If a rule changes, update `booking.*` in `application.yml`, or `TravelClass` for class lists, and extend the tests.

---

© 2026 RailTime Calculator. An independent informational calculator, not an official IRCTC application.
