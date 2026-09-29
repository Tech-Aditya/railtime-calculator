/**
 * RailTime Calculator — front-end controller.
 *
 * All booking maths happens on the server. This script validates input, calls the API, renders the
 * shared result card (via <template> + textContent, never innerHTML) and runs a display-only
 * countdown anchored to the server-computed remaining time, so a wrong browser clock or time zone
 * cannot skew it.
 *
 * Language: the page embeds its translations and rule values in <script id="page-data">. The REST
 * API answers in English, so results are rendered here from the API's structured fields (dates,
 * enums, amounts) rather than from its English sentences.
 */

const REQUEST_TIMEOUT_MS = 10_000;
const MIN_LOADING_MS = 300;
const REMINDER_ALARM_MINUTES = 15;
const REMINDER_DURATION_MINUTES = 15;
const MS = { second: 1_000, minute: 60_000, hour: 3_600_000, day: 86_400_000 };
/** The API's own language: its error messages are shown verbatim only on pages in this language. */
const API_LANGUAGE = 'en';

const PAGE = readPageData();
const bounds = { min: document.body.dataset.minDate, max: document.body.dataset.maxDate };
const resultRegion = document.getElementById('result-region');
let countdown = null; // { units, stop }

/* ------------------------------------------------------------------ i18n */

function readPageData() {
    try {
        return JSON.parse(document.getElementById('page-data').textContent);
    } catch {
        return { lang: 'en', locale: 'en-IN', messages: {}, rules: { refund: {}, chart: {} } };
    }
}

/** Translates a key, filling {0}, {1}… placeholders. Unknown keys fall back to the key itself. */
function t(key, ...args) {
    const template = PAGE.messages[key] ?? key;
    return template.replace(/\{(\d+)\}/g, (match, index) => (args[index] ?? match));
}

const hasMessage = (key) => Object.hasOwn(PAGE.messages, key);

// Dates are formatted in UTC from yyyy-MM-dd values, so the browser's time zone never shifts them.
// English uses en-US month names ("Sep", not "Sept"); Hindi uses full month names (no awkward abbreviations).
const intlDate = (locale, options) => new Intl.DateTimeFormat(locale, { timeZone: 'UTC', ...options });
const MONTH_LOCALE = PAGE.lang === 'en' ? 'en-US' : PAGE.locale;
const FORMAT = {
    monthLong: intlDate(MONTH_LOCALE, { month: 'long' }),
    monthShort: intlDate(MONTH_LOCALE, { month: PAGE.lang === 'en' ? 'short' : 'long' }),
    weekday: intlDate(PAGE.locale, { weekday: 'long' }),
};
const rupees = new Intl.NumberFormat(PAGE.locale, { style: 'currency', currency: 'INR' });
const money = (value) => rupees.format(Number(value));

/* ------------------------------------------------------------------ helpers */

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const pad2 = (n) => String(n).padStart(2, '0');
const cloneTemplate = (id) => document.getElementById(id).content.firstElementChild.cloneNode(true);
const slot = (root, name) => root.querySelector(`[data-slot="${name}"]`);

/** Parses yyyy-MM-dd without going through the browser's time zone; null if not a real date. */
function parseIsoDate(iso) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(iso)) return null;
    const [y, m, d] = iso.split('-').map(Number);
    const date = new Date(Date.UTC(y, m - 1, d));
    return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d ? date : null;
}

/** "2026-10-28" → "28 October 2026" / "28 अक्टूबर 2026" */
function longDate(iso) {
    const date = parseIsoDate(iso);
    return date ? `${date.getUTCDate()} ${FORMAT.monthLong.format(date)} ${date.getUTCFullYear()}` : iso;
}

/** "2026-11-27" → "27 Nov 2026" */
function mediumDate(iso) {
    const date = parseIsoDate(iso);
    return date ? `${date.getUTCDate()} ${FORMAT.monthShort.format(date)} ${date.getUTCFullYear()}` : iso;
}

/** "2026-10-21" → "21 Oct" */
function shortDate(iso) {
    const date = parseIsoDate(iso);
    return date ? `${date.getUTCDate()} ${FORMAT.monthShort.format(date)}` : iso;
}

const weekday = (iso) => FORMAT.weekday.format(parseIsoDate(iso));

/** "21:00" → "09:00 PM" */
function time12(hhmm) {
    const [h, m] = hhmm.split(':').map(Number);
    return `${pad2(h % 12 || 12)}:${pad2(m)} ${t(h < 12 ? 'time.am' : 'time.pm')}`;
}

const timeWithZone = (hhmm) => t('time.withZone', time12(hhmm));

/** Splits an IST ISO date-time ("2026-09-29T21:00:00+05:30") into its local date and time. */
const isoParts = (iso) => ({ date: iso.slice(0, 10), time: iso.slice(11, 16) });

/** Whole days between two ISO dates (UTC arithmetic, no time-zone drift). */
function daysBetween(fromIso, toIso) {
    return Math.round((parseIsoDate(toIso) - parseIsoDate(fromIso)) / MS.day);
}

const className = (code, fallbackName) =>
    (hasMessage(`class.${code}`) ? t(`class.${code}`) : `${fallbackName ?? code} (${code})`);

/* ------------------------------------------------------------------ header: menu, language */

function initMenu() {
    const toggle = document.querySelector('.nav-toggle');
    const nav = document.getElementById('site-nav');
    if (!toggle || !nav) return;
    const setOpen = (open) => {
        nav.classList.toggle('is-open', open);
        toggle.setAttribute('aria-expanded', String(open));
        toggle.setAttribute('aria-label', t(open ? 'menu.close' : 'menu.open'));
    };
    toggle.addEventListener('click', () => setOpen(toggle.getAttribute('aria-expanded') !== 'true'));
    nav.addEventListener('click', (e) => { if (e.target.closest('a')) setOpen(false); });
    document.addEventListener('click', (e) => {
        if (nav.classList.contains('is-open') && !nav.contains(e.target) && !toggle.contains(e.target)) setOpen(false);
    });
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && nav.classList.contains('is-open')) {
            setOpen(false);
            toggle.focus();
        }
    });
}

/** The switcher is a plain <details> with links; this only adds outside-click and Escape to close it. */
function initLanguageMenu() {
    const menu = document.querySelector('.lang-menu');
    if (!menu) return;
    document.addEventListener('click', (e) => { if (menu.open && !menu.contains(e.target)) menu.open = false; });
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && menu.open) {
            menu.open = false;
            menu.querySelector('summary').focus();
        }
    });
}

/* ------------------------------------------------------------------ theme */

const THEME_KEY = 'railtime.theme';
const THEME_COLOR = { light: '#172554', dark: '#0e131d' };

function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLOR[theme]);
    const toggle = document.querySelector('.theme-toggle');
    if (toggle) {
        const label = t(theme === 'dark' ? 'theme.toLight' : 'theme.toDark');
        toggle.setAttribute('aria-label', label);
        toggle.title = label;
    }
}

function initTheme() {
    // theme.js already set the initial theme before first paint; sync the button and meta tag to it.
    applyTheme(document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light');
    document.querySelector('.theme-toggle')?.addEventListener('click', () => {
        const next = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
        // Cross-fade the whole page between themes where supported (View Transitions API);
        // other browsers, and reduced-motion users, get the instant switch.
        if (document.startViewTransition && !prefersReducedMotion()) {
            document.startViewTransition(() => applyTheme(next));
        } else {
            applyTheme(next);
        }
        try { localStorage.setItem(THEME_KEY, next); } catch { /* storage unavailable: session-only */ }
    });
    // Follow OS changes until the user picks a theme explicitly.
    window.matchMedia?.('(prefers-color-scheme: dark)').addEventListener?.('change', (e) => {
        let saved = null;
        try { saved = localStorage.getItem(THEME_KEY); } catch { /* ignore */ }
        if (!saved) applyTheme(e.matches ? 'dark' : 'light');
    });
}

/* ------------------------------------------------------------------ fields */

function setFieldError(input, message) {
    const errorEl = document.getElementById(`${input.id}-error`);
    if (errorEl) errorEl.textContent = message || '';
    if (message) input.setAttribute('aria-invalid', 'true');
    else input.removeAttribute('aria-invalid');
}

/** Shows "28 October 2026 · Wednesday" under a date input. */
function updateDateDisplay(input) {
    const display = document.getElementById(`${input.id}-display`);
    if (!display) return;
    display.textContent = parseIsoDate(input.value) ? `${longDate(input.value)} · ${weekday(input.value)}` : '';
}

function validateDate(input) {
    if (input.validity.badInput || !input.value) return t('err.selectDate');
    if (!parseIsoDate(input.value)) return t('err.invalidDate');
    if (bounds.min && input.value < bounds.min) return t('err.dateMin', longDate(bounds.min));
    if (bounds.max && input.value > bounds.max) return t('err.dateMax', longDate(bounds.max));
    return null;
}

const FARE_PATTERN = /^\d{1,7}(\.\d{1,2})?$/;

const validateTime = (input) => (input.validity.badInput || !input.value ? t('err.time') : null);
const validateSelected = (key) => (input) => (input.value ? null : t(key));

function validateFare(input) {
    const value = input.value.trim().replace(/,/g, '');
    if (!value) return t('err.fareRequired');
    if (!FARE_PATTERN.test(value)) return t('err.fareFormat');
    const fare = Number(value);
    if (fare < 1) return t('err.fareMin');
    if (fare > 1_000_000) return t('err.fareMax');
    return null;
}

/** Per-form field checks: [field name, validator] pairs, run in order. */
const FIELD_RULES = {
    general: [['originDepartureDate', validateDate]],
    tatkal: [
        ['originDepartureDate', validateDate],
        ['travelClass', validateSelected('err.class')],
    ],
    refund: [
        ['travelClass', validateSelected('err.class')],
        ['ticketStatus', validateSelected('err.status')],
        ['totalFare', validateFare],
        ['departureDate', validateDate],
        ['departureTime', validateTime],
    ],
    chart: [
        ['departureDate', validateDate],
        ['departureTime', validateTime],
    ],
};

/** Returns the request payload, or null after marking invalid fields (focusing the first). */
function collectPayload(form) {
    const kind = form.dataset.kind;
    const payload = {};
    let invalid = null;
    const check = (field, error) => {
        setFieldError(field, error);
        invalid ??= error ? field : null;
    };

    for (const [name, validate] of FIELD_RULES[kind]) {
        const field = form.elements[name];
        check(field, validate(field));
        payload[name] = field.value.trim();
    }

    if (kind === 'refund') {
        payload.totalFare = payload.totalFare.replace(/,/g, '');
        payload.passengers = Number(form.elements.passengers.value);
        // Cancellation moment is optional (server uses "now"), but date and time go together.
        const cancelDate = form.elements.cancellationDate;
        const cancelTime = form.elements.cancellationTime;
        const hasDate = Boolean(cancelDate.value) || cancelDate.validity.badInput;
        const hasTime = Boolean(cancelTime.value) || cancelTime.validity.badInput;
        const dateError = hasTime && !hasDate ? t('err.cancelDateMissing')
            : hasDate && !parseIsoDate(cancelDate.value) ? t('err.validDate') : null;
        let timeError = hasDate && !hasTime ? t('err.cancelTimeMissing')
            : hasTime && cancelTime.validity.badInput ? t('err.validTime') : null;
        if (!dateError && !timeError && hasDate && hasTime && !invalid
            && `${cancelDate.value}T${cancelTime.value}` >= `${payload.departureDate}T${payload.departureTime}`) {
            timeError = t('err.cancelAfterDeparture');
        }
        check(cancelDate, dateError);
        check(cancelTime, timeError);
        if (hasDate && hasTime) {
            payload.cancellationDate = cancelDate.value;
            payload.cancellationTime = cancelTime.value;
        }
    }

    if (invalid) {
        invalid.focus();
        return null;
    }
    return payload;
}

/* ------------------------------------------------------------------ API */

/** A message for a failed API call: the API's own text on English pages, a translated one otherwise. */
function apiErrorMessage(data, status) {
    if (data?.message && PAGE.lang === API_LANGUAGE) return data.message;
    if (data?.error && hasMessage(`err.${data.error}`)) return t(`err.${data.error}`);
    return data ? t('err.generic') : t('err.http', status);
}

async function requestJson(url, init) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    let response;
    try {
        response = await fetch(url, {
            ...init,
            headers: { Accept: 'application/json', ...init?.headers },
            credentials: 'same-origin',
            signal: controller.signal,
        });
    } catch (err) {
        throw new Error(t(err.name === 'AbortError' ? 'err.timeout' : 'err.network'));
    } finally {
        clearTimeout(timer);
    }
    let data = null;
    try {
        data = await response.json();
    } catch {
        /* non-JSON body — handled below */
    }
    if (!response.ok) throw new Error(apiErrorMessage(data, response.status));
    if (!data) throw new Error(t('err.empty'));
    return data;
}

const postJson = (url, body) => requestJson(url, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
});

/* ------------------------------------------------------------------ countdown */

const prefersReducedMotion = () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;

/**
 * Calls onTick(remaining) right after each whole second of a countdown, so the digits change at an
 * even one-second rhythm (a fixed interval drifts and makes ticks look uneven). Returns a stop function.
 */
function tickEverySecond(deadline, onTick, onDone) {
    let timer = null;
    const step = () => {
        const remaining = deadline - Date.now();
        if (remaining <= 0) {
            onDone?.();
            return;
        }
        onTick(remaining);
        timer = setTimeout(step, (remaining % MS.second) + 15);
    };
    step();
    return () => clearTimeout(timer);
}

function stopCountdown() {
    countdown?.stop();
    countdown = null;
}

function renderCountdown(remaining) {
    const units = countdown?.units;
    if (!units) return;
    units.days.textContent = pad2(Math.floor(remaining / MS.day));
    units.hours.textContent = pad2(Math.floor((remaining % MS.day) / MS.hour));
    units.minutes.textContent = pad2(Math.floor((remaining % MS.hour) / MS.minute));
    units.seconds.textContent = pad2(Math.floor((remaining % MS.minute) / MS.second));
}

function startCountdown(container, millis, onDone) {
    stopCountdown();
    const units = {};
    container.querySelectorAll('[data-unit]').forEach((el) => { units[el.dataset.unit] = el; });
    // Anchor to the server-computed remaining time, not to the browser's absolute clock.
    countdown = { units };
    const stop = tickEverySecond(Date.now() + millis, renderCountdown, () => {
        countdown = null;
        onDone();
    });
    if (countdown) countdown.stop = stop;
}

/* ------------------------------------------------------------------ reminder (.ics) */

const toIcsUtc = (date) => date.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
const escapeIcs = (text) => text.replace(/[\\;,]/g, (c) => `\\${c}`).replace(/\n/g, '\\n');

function downloadReminder(kind, data) {
    const start = new Date(data.bookingDateTime);
    const end = new Date(start.getTime() + REMINDER_DURATION_MINUTES * MS.minute);
    const opening = isoParts(data.bookingDateTime);
    const journey = longDate(data.journeyDate ?? data.originDepartureDate);
    const summary = kind === 'general'
        ? t('reminder.general', journey)
        : t('reminder.tatkal', data.travelClass, journey);
    const description = t('reminder.description', longDate(opening.date), timeWithZone(opening.time));
    const lines = [
        'BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//RailTime Calculator//EN', 'CALSCALE:GREGORIAN', 'METHOD:PUBLISH',
        'BEGIN:VEVENT',
        `UID:${crypto.randomUUID()}@railtime-calculator`,
        `DTSTAMP:${toIcsUtc(new Date())}`,
        `DTSTART:${toIcsUtc(start)}`,
        `DTEND:${toIcsUtc(end)}`,
        `SUMMARY:${escapeIcs(summary)}`,
        `DESCRIPTION:${escapeIcs(description)}`,
        'URL:https://www.irctc.co.in',
        'BEGIN:VALARM', 'ACTION:DISPLAY', `TRIGGER:-PT${REMINDER_ALARM_MINUTES}M`,
        `DESCRIPTION:${escapeIcs(summary)}`, 'END:VALARM',
        'END:VEVENT', 'END:VCALENDAR',
    ];
    const url = URL.createObjectURL(new Blob([lines.join('\r\n')], { type: 'text/calendar;charset=utf-8' }));
    const link = Object.assign(document.createElement('a'), { href: url, download: `railtime-${kind}-${data.bookingDate}.ics` });
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), MS.second);
}

/* ------------------------------------------------------------------ rendering: shared */

/** Sets the pill, state class and banner of a result card from a view description. */
function applyView(node, view, bannerText) {
    const pill = slot(node, 'pill');
    pill.textContent = view.pill;
    pill.className = `status-pill ${view.pillClass ?? ''}`.trim();
    node.classList.remove('is-open', 'is-passed');
    if (view.stateClass) node.classList.add(view.stateClass);
    const banner = slot(node, 'banner');
    banner.hidden = !view.bannerTitle;
    if (view.bannerTitle) {
        slot(node, 'banner-title').textContent = view.bannerTitle;
        slot(node, 'banner-text').textContent = bannerText;
    }
}

function fillNotes(node, notes) {
    const list = slot(node, 'notes');
    list.replaceChildren(...notes.map((note) => Object.assign(document.createElement('li'), { textContent: note })));
    list.closest('details').hidden = notes.length === 0;
}

function fillCalendarTile(node, iso) {
    const date = parseIsoDate(iso);
    slot(node, 'cal-month').textContent = FORMAT.monthShort.format(date);
    slot(node, 'cal-day').textContent = String(date.getUTCDate());
    slot(node, 'cal-year').textContent = String(date.getUTCFullYear());
    slot(node, 'weekday').textContent = weekday(iso);
}

/* ------------------------------------------------------------------ rendering: general / Tatkal */

function bookingView(status) {
    switch (status) {
        case 'OPEN':
            return {
                pill: t('booking.pillOpen'), pillClass: 'pill-open', label: t('booking.labelOpened'), stateClass: 'is-open',
                bannerTitle: t('booking.openTitle'), bannerText: t('booking.openText'),
            };
        case 'JOURNEY_DATE_PASSED':
            return {
                pill: t('booking.pillPassed'), pillClass: 'pill-passed', label: t('booking.labelOpened'), stateClass: 'is-passed',
                bannerTitle: t('booking.passedTitle'), bannerText: t('booking.passedText'),
            };
        default:
            return { pill: t('booking.pillUpcoming'), label: t('booking.labelOpens') };
    }
}

function applyBookingStatus(node, status) {
    const view = bookingView(status);
    applyView(node, view, view.bannerText);
    slot(node, 'label').textContent = view.label;
    slot(node, 'countdown').hidden = status !== 'NOT_OPEN';
    slot(node, 'actions').hidden = status !== 'NOT_OPEN';
}

function renderBooking(kind, data) {
    const node = cloneTemplate('tpl-result');
    const opening = isoParts(data.bookingDateTime);
    const journeyIso = data.journeyDate ?? data.originDepartureDate;
    const gap = daysBetween(opening.date, journeyIso);

    node.classList.add(`kind-${kind}`);
    slot(node, 'kind').textContent = t(`kind.${kind}`);
    fillCalendarTile(node, opening.date);
    slot(node, 'date').textContent = longDate(opening.date);
    slot(node, 'time').textContent = timeWithZone(opening.time);
    slot(node, 'tl-open').textContent = shortDate(opening.date);
    slot(node, 'tl-journey').textContent = longDate(journeyIso);
    slot(node, 'tl-gap').textContent = t(gap === 1 ? 'booking.gapOne' : 'booking.gapMany', gap);
    if (kind === 'tatkal') {
        const cls = slot(node, 'class');
        cls.hidden = false;
        cls.textContent = className(data.travelClass, data.travelClassName);
    }
    applyBookingStatus(node, data.status);
    slot(node, 'reminder').addEventListener('click', () => downloadReminder(kind, data));
    showResult(node);

    if (data.status === 'NOT_OPEN' && data.millisUntilOpening > 0) {
        // Display-only transition when the countdown ends; the server remains authoritative.
        startCountdown(slot(node, 'countdown'), data.millisUntilOpening, () => applyBookingStatus(node, 'OPEN'));
    }
}

function renderNotApplicable(data) {
    const node = cloneTemplate('tpl-not-applicable');
    slot(node, 'code').textContent = data.travelClass;
    slot(node, 'class').textContent = className(data.travelClass, data.travelClassName);
    slot(node, 'message').textContent = t('tatkal.notListed', data.travelClass);
    slot(node, 'origin').textContent = longDate(data.originDepartureDate);
    showResult(node);
}

/* ------------------------------------------------------------------ rendering: refund */

/** 3390 → "2 days 8 h before departure" */
function leadTime(minutes) {
    const d = Math.floor(minutes / 1440);
    const h = Math.floor((minutes % 1440) / 60);
    const m = minutes % 60;
    const text = d > 1 ? t('refund.leadDays', d, h)
        : d === 1 ? t('refund.leadDay', d, h)
            : h > 0 ? t('refund.leadHours', h, m) : t('refund.leadMinutes', m);
    return t('refund.leadSuffix', text);
}

/** The rule that applied, worded from the configured rule values (same wording as the API). */
function refundRule(data) {
    const r = PAGE.rules.refund;
    const flat = r.flatCharges?.[data.travelClass];
    const key = `refund.rule.${data.slab}`;
    switch (data.slab) {
        case 'FLAT_CHARGE': return t(key, r.flatAboveHours, flat);
        case 'QUARTER_OF_FARE': return t(key, r.flatAboveHours, r.quarterFromHours, r.quarterRatePercent, flat);
        case 'HALF_OF_FARE': return t(key, r.quarterFromHours, r.cutoffHours, r.halfRatePercent, flat);
        case 'CLERKAGE': return t(key, r.racCutoffMinutes, r.clerkage);
        case 'NO_REFUND_CONFIRMED': return t(key, r.cutoffHours);
        case 'NO_REFUND_RAC_WAITLIST': return t(key, r.racCutoffMinutes);
        default: return t(key);
    }
}

function refundPill(data) {
    const r = PAGE.rules.refund;
    if (!data.refundable) return t('refund.pill.none');
    if (data.slab === 'QUARTER_OF_FARE') return t('refund.pill.QUARTER_OF_FARE', r.quarterRatePercent);
    if (data.slab === 'HALF_OF_FARE') return t('refund.pill.HALF_OF_FARE', r.halfRatePercent);
    return t(`refund.pill.${data.slab}`);
}

function refundNotes(data) {
    const r = PAGE.rules.refund;
    const notes = [t('refund.note.source')];
    if (r.gstClasses?.includes(data.travelClass)) notes.push(t('refund.note.gst', r.gstRatePercent));
    if (data.ticketStatus === 'CONFIRMED') notes.push(t('refund.note.tdr', r.cutoffHours));
    notes.push(t('refund.note.fees'), t('refund.note.verify'));
    return notes;
}

function renderRefund(data) {
    const node = cloneTemplate('tpl-refund');
    const fare = Number(data.totalFare);
    const percent = fare > 0 ? Math.round((Number(data.totalRefund) / fare) * 100) : 0;
    const perPassenger = data.passengers > 1 ? ` × ${data.passengers}` : '';

    slot(node, 'percent').textContent = `${percent}%`;
    slot(node, 'class-name').textContent = className(data.travelClass, data.travelClassName);
    const pill = slot(node, 'pill');
    pill.textContent = refundPill(data);
    pill.className = `status-pill ${data.refundable ? 'pill-open' : 'pill-none'}`;
    slot(node, 'refund-big').textContent = money(data.totalRefund);
    slot(node, 'lead').textContent = leadTime(data.minutesBeforeDeparture);
    slot(node, 'ticket').textContent = `${t(`status.${data.ticketStatus}`)} · `
        + t(data.passengers === 1 ? 'refund.passengersOne' : 'refund.passengersMany', data.passengers);

    slot(node, 'fare').textContent = money(data.totalFare);
    slot(node, 'charge').textContent = `− ${money(data.totalCharge)}`;
    slot(node, 'charge-note').textContent = data.refundable
        ? `${money(data.chargePerPassenger)}${perPassenger}` : t('refund.fullFare');
    const hasGst = Number(data.totalGst) > 0;
    slot(node, 'gst-row').hidden = !hasGst;
    if (hasGst) {
        slot(node, 'gst').textContent = `− ${money(data.totalGst)}`;
        slot(node, 'gst-note').textContent = t('refund.gstNote', data.gstRatePercent);
    }
    slot(node, 'refund').textContent = money(data.totalRefund);
    slot(node, 'rule').textContent = refundRule(data);
    fillNotes(node, refundNotes(data));
    if (!data.refundable) node.classList.add('is-none');
    showResult(node);
}

/* ------------------------------------------------------------------ rendering: chart */

function chartView(status) {
    switch (status) {
        case 'FIRST_CHART_PREPARED':
            return { pillClass: 'pill-open', stateClass: 'is-open', bannerTitle: t('chart.firstTitle'), text: 'chart.firstText' };
        case 'FINAL_CHART_PREPARED':
            return { pillClass: 'pill-open', stateClass: 'is-open', bannerTitle: t('chart.finalTitle'), text: 'chart.finalText' };
        case 'DEPARTED':
            return { pillClass: 'pill-passed', stateClass: 'is-passed', bannerTitle: t('chart.departedTitle'), text: 'chart.departedText' };
        default:
            return {};
    }
}

function applyChartStatus(node, status, data) {
    const view = { ...chartView(status), pill: t(`chart.pill.${status}`) };
    applyView(node, view, view.text ? t(view.text, timeWithZone(isoParts(data.finalChartAt).time)) : '');
    slot(node, 'countdown').hidden = status !== 'PENDING';
}

function chartNotes(departureDate) {
    const c = PAGE.rules.chart;
    const notes = [t('chart.note.directive', longDate(c.effectiveFrom)), t('chart.note.final', c.finalChartMinutes)];
    if (departureDate < c.effectiveFrom) notes.push(t('chart.note.beforeDirective'));
    notes.push(t('chart.note.verify'));
    return notes;
}

function renderChart(data) {
    const node = cloneTemplate('tpl-chart');
    const first = isoParts(data.firstChartAt);
    const final = isoParts(data.finalChartAt);
    const dep = isoParts(data.departureDateTime);
    const hoursBefore = Math.round((Date.parse(data.departureDateTime) - Date.parse(data.firstChartAt)) / MS.hour);

    fillCalendarTile(node, first.date);
    slot(node, 'date').textContent = longDate(first.date);
    slot(node, 'time').textContent = timeWithZone(first.time);
    slot(node, 'rule-chip').textContent = data.rule === 'PREVIOUS_EVENING'
        ? t('chart.rulePreviousEvening') : t('chart.ruleHours', hoursBefore);
    slot(node, 'tl-first').textContent = shortDate(first.date);
    slot(node, 'tl-first-time').textContent = time12(first.time);
    slot(node, 'tl-final').textContent = shortDate(final.date);
    slot(node, 'tl-final-time').textContent = time12(final.time);
    slot(node, 'tl-dep').textContent = shortDate(dep.date);
    slot(node, 'tl-dep-time').textContent = time12(dep.time);
    fillNotes(node, chartNotes(dep.date));
    applyChartStatus(node, data.status, data);
    showResult(node);

    if (data.status === 'PENDING' && data.millisUntilFirstChart > 0) {
        startCountdown(slot(node, 'countdown'), data.millisUntilFirstChart,
            () => applyChartStatus(node, 'FIRST_CHART_PREPARED', data));
    }
}

/* ------------------------------------------------------------------ today's booking windows */

const TODAY_REFRESH_MS = 30 * MS.minute;
let todayStop = null;
let todayRefresh = null;

function renderToday(section, data) {
    const set = (name, text) => { slot(section, name).textContent = text; };
    const opensAt = isoParts(data.nextOpening.opensAt);
    set('latest', mediumDate(data.latestBookableJourneyDate));
    set('next', mediumDate(data.nextOpening.journeyDate));
    set('next-when', t(opensAt.date === isoParts(data.asOf).date ? 'today.today' : 'today.tomorrow',
        timeWithZone(opensAt.time)));
    set('tatkal', mediumDate(data.tatkal.journeyDate));
    const pill = (name, key, open, opensAtIso) => {
        const el = slot(section, name);
        el.textContent = t(key, open ? t('today.open') : time12(isoParts(opensAtIso).time));
        el.classList.toggle('is-open', open);
    };
    pill('ac', 'today.ac', data.tatkal.acOpen, data.tatkal.acOpensAt);
    pill('nonac', 'today.nonAc', data.tatkal.nonAcOpen, data.tatkal.nonAcOpensAt);

    // Every deadline is measured against the server's "asOf", so the browser clock cannot skew it.
    const asOf = Date.parse(data.asOf);
    const anchor = Date.now();
    const deadlines = [data.nextOpening.millisUntilOpening, Date.parse(data.tatkal.acOpensAt) - asOf,
        Date.parse(data.tatkal.nonAcOpensAt) - asOf].filter((ms) => ms > 0);
    const refreshIn = Math.min(TODAY_REFRESH_MS, ...deadlines.map((ms) => ms + MS.second));
    const count = slot(section, 'next-count');

    todayStop?.();
    todayStop = tickEverySecond(anchor + data.nextOpening.millisUntilOpening, (remaining) => {
        count.textContent = t('today.in', `${pad2(Math.floor(remaining / MS.hour))}:`
            + `${pad2(Math.floor((remaining % MS.hour) / MS.minute))}:${pad2(Math.floor((remaining % MS.minute) / MS.second))}`);
    }, () => { count.textContent = ''; });
    clearTimeout(todayRefresh);
    todayRefresh = setTimeout(() => loadToday(section), refreshIn);
}

/** The strip is rendered by the server; this only adds the live countdown and refreshes it when a window opens. */
async function loadToday(section) {
    try {
        renderToday(section, await requestJson(section.dataset.endpoint));
    } catch {
        // Keep the server-rendered values; just stop the live parts and try again later.
        todayStop?.();
        slot(section, 'next-count').textContent = '';
        clearTimeout(todayRefresh);
        todayRefresh = setTimeout(() => loadToday(section), TODAY_REFRESH_MS);
    }
}

/* ------------------------------------------------------------------ errors and scrolling */

function renderError(message) {
    const node = cloneTemplate('tpl-error');
    slot(node, 'message').textContent = message;
    showResult(node);
}

let pendingResize = null; // { from, to } heights of the last swap, until presentResult() runs

/** Swaps in the new result card; presentResult() then decides how to bring it into view. */
function showResult(node) {
    const from = resultRegion.getBoundingClientRect().height;
    resultRegion.getAnimations().forEach((animation) => animation.cancel());
    resultRegion.replaceChildren(node);
    pendingResize = { from, to: resultRegion.getBoundingClientRect().height };
}

/**
 * Glides the region from its old height to the new one, so the content below moves smoothly.
 * Only used when the page doesn't scroll: while it runs the page is shorter than its final length,
 * which would make a scroll to the result stop short.
 */
function animateResultHeight({ from, to }) {
    if (Math.abs(to - from) < 2 || prefersReducedMotion() || !resultRegion.animate) return;
    resultRegion.animate(
        [{ height: `${from}px`, overflow: 'hidden' }, { height: `${to}px`, overflow: 'hidden' }],
        { duration: 320, easing: 'cubic-bezier(.2, .7, .2, 1)' },
    );
}

/**
 * Scrolls so the whole result is visible, centred in the space below the sticky header (or, if it
 * is taller than that space, starting just below the header). Returns false when it is already
 * fully visible, so the page doesn't move needlessly.
 */
function scrollResultIntoView() {
    const header = document.querySelector('.site-header')?.getBoundingClientRect().height ?? 0;
    const rect = resultRegion.getBoundingClientRect(); // final layout: no height animation is running
    const available = window.innerHeight - header;
    const margin = 16;
    if (rect.top >= header + margin && rect.bottom <= window.innerHeight - margin) return false;
    const offset = rect.height + 2 * margin <= available ? (available - rect.height) / 2 : margin;
    window.scrollTo({
        top: Math.max(0, window.scrollY + rect.top - header - offset),
        behavior: prefersReducedMotion() ? 'auto' : 'smooth',
    });
    return true;
}

/** Brings a new result into view: one smooth scroll when needed, otherwise a smooth resize in place. */
function presentResult({ reveal }) {
    const resize = pendingResize;
    pendingResize = null;
    if (reveal && scrollResultIntoView()) return;
    if (resize) animateResultHeight(resize);
}

/* ------------------------------------------------------------------ wiring */

let lastCalculatedForm = null;

async function calculate(form, { reveal = true } = {}) {
    if (form.classList.contains('is-loading')) return;

    const payload = collectPayload(form);
    if (!payload) return;

    const kind = form.dataset.kind;
    const button = form.querySelector('button[type="submit"]');
    stopCountdown();
    // The spinner replaces the old "Calculating…" label swap, which flickered on fast responses.
    form.classList.add('is-loading');
    form.setAttribute('aria-busy', 'true');
    button.setAttribute('aria-disabled', 'true');

    try {
        const [data] = await Promise.all([postJson(form.dataset.endpoint, payload), delay(MIN_LOADING_MS)]);
        if (kind === 'refund') renderRefund(data);
        else if (kind === 'chart') renderChart(data);
        else if (data.status === 'NOT_APPLICABLE') renderNotApplicable(data);
        else renderBooking(kind, data);
        lastCalculatedForm = form.id;
    } catch (err) {
        renderError(err.message);
    } finally {
        form.classList.remove('is-loading');
        form.removeAttribute('aria-busy');
        button.removeAttribute('aria-disabled');
        presentResult({ reveal });
    }
}

function handleSubmit(event) {
    event.preventDefault();
    calculate(event.currentTarget);
}

/* ------------------------------------------------------------------ field behaviour */

/**
 * Some browsers (for example Firefox on Windows) change a focused dropdown's value when you scroll
 * the mouse wheel over it. Scrolling down then quietly walks the class to the last option (1A).
 * Here the wheel always scrolls the page instead; the keyboard and the dropdown itself still work.
 */
function guardSelectsAgainstWheel(form) {
    form.querySelectorAll('select').forEach((select) => {
        select.addEventListener('wheel', (event) => {
            if (document.activeElement !== select) return;
            event.preventDefault();
            const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? window.innerHeight : 1;
            window.scrollBy({ top: event.deltaY * unit, left: event.deltaX * unit, behavior: 'instant' });
        }, { passive: false });
    });
}

/** Opens the date/time picker on a click anywhere in the field, not only on its small icon. */
function openPickersOnClick(form) {
    form.querySelectorAll('input[type="date"], input[type="time"]').forEach((input) => {
        input.addEventListener('click', () => {
            try {
                input.showPicker?.();
            } catch {
                /* already open, or not allowed here: the field still works by typing */
            }
        });
    });
}

/* ------------------------------------------------------------------ language switch continuity */

// Switching language reloads the page. To make it feel seamless, the page keeps your place (via the
// URL fragment of the section in view), keeps what you typed, and re-runs your last calculation.
const CARRY_KEY = 'railtime.carry';
const SECTION_ANCHORS = ['top', 'calculators', 'planning', 'result', 'info'];

function sectionInView() {
    let current = 'top';
    for (const id of SECTION_ANCHORS) {
        const el = document.getElementById(id);
        if (el && el.getBoundingClientRect().top <= window.innerHeight * 0.4) current = id;
    }
    return current;
}

function carryStateAcross(link) {
    const values = {};
    document.querySelectorAll('.calc-form input, .calc-form select').forEach((field) => {
        if (field.id && field.value) values[field.id] = field.value;
    });
    try {
        sessionStorage.setItem(CARRY_KEY, JSON.stringify({ values, last: lastCalculatedForm }));
    } catch { /* storage unavailable: switch without carrying state */ }
    const url = new URL(link.href, window.location.href);
    const section = sectionInView();
    url.hash = section === 'top' ? '' : section;
    link.href = url.toString();
}

function restoreCarriedState() {
    let carried = null;
    try {
        carried = JSON.parse(sessionStorage.getItem(CARRY_KEY));
        sessionStorage.removeItem(CARRY_KEY);
    } catch { /* nothing to restore */ }
    if (!carried) return;
    for (const [id, value] of Object.entries(carried.values ?? {})) {
        const field = document.getElementById(id);
        if (!field || !field.closest('.calc-form')) continue;
        field.value = value;
        if (field.type === 'date') updateDateDisplay(field);
    }
    const form = carried.last && document.getElementById(carried.last);
    if (form?.classList.contains('calc-form')) calculate(form, { reveal: false });
}

function initLanguageContinuity() {
    document.querySelectorAll('.lang-option').forEach((link) => {
        if (link.getAttribute('aria-current') === 'true') return;
        link.addEventListener('click', () => carryStateAcross(link));
    });
    restoreCarriedState();
}

function init() {
    initTheme();
    initMenu();
    initLanguageMenu();
    const today = document.getElementById('today');
    if (today) loadToday(today);
    document.querySelectorAll('.calc-form').forEach((form) => {
        form.addEventListener('submit', handleSubmit);
        guardSelectsAgainstWheel(form);
        openPickersOnClick(form);
        form.querySelectorAll('input, select').forEach((field) => {
            const onChange = () => {
                if (field.type === 'date') updateDateDisplay(field);
                if (field.hasAttribute('aria-invalid')) setFieldError(field, null);
            };
            field.addEventListener('input', onChange);
            field.addEventListener('change', onChange);
        });
    });
    initLanguageContinuity();
}

init();
