/**
 * RailTime Calculator — front-end controller.
 *
 * All booking maths happens on the server. This script validates input, calls the API, renders the
 * result cards (via <template> + textContent, never innerHTML) and runs display-only countdowns
 * anchored to the server-computed remaining time, so a wrong browser clock or time zone cannot skew them.
 *
 * Every page has its own URL (/, /general, /tatkal, …) rendered by the server, so direct links and
 * refresh work. Here, links between pages switch views in place (History API) without reloading.
 *
 * Language: the page embeds its translations and rule values in <script id="page-data">. The REST
 * API answers in English, so results are rendered here from the API's structured fields (dates,
 * enums, amounts) rather than from its English sentences.
 */

const REQUEST_TIMEOUT_MS = 10_000;
const MIN_LOADING_MS = 300;
const REMINDER_ALARM_MINUTES = 15;
const REMINDER_DURATION_MINUTES = 15;
const FEEDBACK_MS = 3_500;
const RECENT_LIMIT = 6;
const MS = { second: 1_000, minute: 60_000, hour: 3_600_000, day: 86_400_000 };
/** The API's own language: its error messages are shown verbatim only on pages in this language. */
const API_LANGUAGE = 'en';
const STORE = { theme: 'railtime.theme', sidebar: 'railtime.sidebar', recent: 'railtime.recent', reminders: 'railtime.reminders', carry: 'railtime.carry' };

const PAGE = readPageData();
const root = document.documentElement;
const bounds = { min: document.body.dataset.minDate, max: document.body.dataset.maxDate };

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
const slot = (root_, name) => root_.querySelector(`[data-slot="${name}"]`);
const prefersReducedMotion = () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;

function readStore(key, fallback) {
    try {
        return JSON.parse(localStorage.getItem(key)) ?? fallback;
    } catch {
        return fallback;
    }
}

function writeStore(key, value) {
    try {
        localStorage.setItem(key, JSON.stringify(value));
    } catch { /* storage unavailable: this session only */ }
}

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

/** 36_900_000 → "10h 15m" (compact, for "Opens in …"). */
function duration(ms) {
    const d = Math.floor(ms / MS.day);
    const h = Math.floor((ms % MS.day) / MS.hour);
    const m = Math.floor((ms % MS.hour) / MS.minute);
    const s = Math.floor((ms % MS.minute) / MS.second);
    if (d > 0) return t('dur.days', d, h, m);
    if (h > 0) return t('dur.hours', h, pad2(m));
    return t('dur.minutes', m, pad2(s));
}

const className = (code, fallbackName) =>
    (hasMessage(`class.${code}`) ? t(`class.${code}`) : `${fallbackName ?? code} (${code})`);

/* ------------------------------------------------------------------ pages (routing) */

const views = new Map([...document.querySelectorAll('.view')].map((view) => [view.dataset.view, view]));
const sidebarLink = (key) => document.querySelector(`.sb-link[data-nav="${key}"]`);
const pathOf = (key) => sidebarLink(key)?.getAttribute('href') ?? '/';
const CALCULATORS = ['general', 'tatkal', 'refund', 'chart'];
/** Old single-page anchors (/#tatkal) still land on the right page. */
const LEGACY_HASHES = { '#general': 'general', '#tatkal': 'tatkal', '#refund': 'refund', '#chart': 'chart', '#calculators': 'general', '#planning': 'refund', '#result': 'general', '#info': 'rules' };
let currentPage = document.body.dataset.page;

function pageFromPath(pathname) {
    for (const key of views.keys()) {
        if (pathOf(key) === pathname) return key;
    }
    return 'dashboard';
}

/**
 * The General Booking calculator (form + result) exists once and is shown on both the Dashboard and
 * the General Booking page: it is moved into the visible page's slots, keeping input, result and countdown.
 */
function placeGeneralCalculator(key) {
    if (key !== 'dashboard' && key !== 'general') return;
    const card = document.getElementById('general-card');
    const result = document.getElementById('result-general');
    const cardSlot = document.querySelector(`[data-general-slot="${key}-card"]`);
    const resultSlot = document.querySelector(`[data-general-slot="${key}-result"]`);
    if (card && cardSlot && card.parentElement !== cardSlot) cardSlot.append(card);
    if (result && resultSlot && result.parentElement !== resultSlot) resultSlot.append(result);
}

/** Shows one page: view, active states, header title, document title, language links. */
function showPage(key, { focus = false } = {}) {
    if (!views.has(key)) return;
    const changed = key !== currentPage;
    currentPage = key;
    document.body.dataset.page = key;
    placeGeneralCalculator(key);
    views.forEach((view, viewKey) => { view.hidden = viewKey !== key; });
    document.querySelectorAll('.sb-link[data-nav], .calc-tab, .bn-link[data-nav]').forEach((link) => {
        if (link.dataset.nav === key) link.setAttribute('aria-current', 'page');
        else link.removeAttribute('aria-current');
    });
    // Pages without their own tab (Status, Reminders, Rules) live under "More".
    document.querySelector('.bn-more')?.classList.toggle('is-active', key !== 'dashboard' && !CALCULATORS.includes(key));
    document.querySelector('.calc-tabs').hidden = !CALCULATORS.includes(key);

    const link = sidebarLink(key);
    slot(document, 'header-page').textContent = link?.dataset.label ?? '';
    slot(document, 'header-group').textContent = link?.closest('.sb-list')?.previousElementSibling?.textContent ?? '';
    document.title = views.get(key).dataset.docTitle;
    const path = pathOf(key);
    document.querySelectorAll('.lang-option').forEach((option) => {
        option.href = `${path}?lang=${option.dataset.lang}`;
    });

    if (key === 'dashboard') {
        renderRecent();
        loadUsage();
    }
    if (key === 'reminders') renderReminders();
    if (changed) {
        window.scrollTo({ top: 0, behavior: 'instant' });
        if (focus) views.get(key).querySelector('.view-focus')?.focus({ preventScroll: true });
    }
}

function navigate(key, { replace = false } = {}) {
    if (!views.has(key)) return;
    closeDrawer();
    if (key === currentPage) {
        window.scrollTo({ top: 0, behavior: prefersReducedMotion() ? 'auto' : 'smooth' });
        return;
    }
    const url = pathOf(key);
    if (replace) history.replaceState({ page: key }, '', url);
    else history.pushState({ page: key }, '', url);
    showPage(key, { focus: true });
}

function initRouter() {
    history.replaceState({ page: currentPage }, '', window.location.pathname + window.location.search);
    const legacy = LEGACY_HASHES[window.location.hash];
    if (legacy && currentPage === 'dashboard') {
        history.replaceState({ page: legacy }, '', pathOf(legacy));
        showPage(legacy);
    } else {
        showPage(currentPage);
    }
    document.addEventListener('click', (event) => {
        const link = event.target.closest('a[data-nav]');
        if (!link || event.defaultPrevented || event.button !== 0
            || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || link.target) return;
        event.preventDefault();
        navigate(link.dataset.nav);
    });
    window.addEventListener('popstate', () => showPage(pageFromPath(window.location.pathname), { focus: true }));
}

/* ------------------------------------------------------------------ sidebar: drawer (phones) and collapse */

const sidebar = document.getElementById('sidebar');
const backdrop = document.querySelector('.sb-backdrop');
const drawerToggle = document.querySelector('.nav-toggle');
const appMain = document.querySelector('.app-main');
const phoneLayout = window.matchMedia('(max-width: 759px)');

function openDrawer() {
    sidebar.classList.add('is-open');
    backdrop.hidden = false;
    root.classList.add('drawer-open'); // no background scrolling
    appMain.inert = true;
    drawerToggle.setAttribute('aria-expanded', 'true');
    drawerToggle.setAttribute('aria-label', t('menu.close'));
    sidebar.querySelector('.sb-close').focus();
}

function closeDrawer({ restoreFocus = false } = {}) {
    if (!sidebar.classList.contains('is-open')) return;
    sidebar.classList.remove('is-open');
    backdrop.hidden = true;
    root.classList.remove('drawer-open');
    appMain.inert = false;
    drawerToggle.setAttribute('aria-expanded', 'false');
    drawerToggle.setAttribute('aria-label', t('menu.open'));
    if (restoreFocus) drawerToggle.focus();
}

function setCollapsed(collapsed) {
    const button = document.querySelector('.sb-collapse');
    if (collapsed) root.setAttribute('data-sidebar', 'collapsed');
    else root.removeAttribute('data-sidebar');
    const label = collapsed ? button.dataset.labelExpand : button.dataset.labelCollapse;
    button.dataset.label = label;
    button.setAttribute('aria-expanded', String(!collapsed));
    button.querySelector('.sb-label').textContent = label;
}

function initSidebar() {
    drawerToggle.addEventListener('click', () => (sidebar.classList.contains('is-open') ? closeDrawer() : openDrawer()));
    document.querySelector('.bn-more')?.addEventListener('click', openDrawer);
    sidebar.querySelector('.sb-close').addEventListener('click', () => closeDrawer({ restoreFocus: true }));
    backdrop.addEventListener('click', () => closeDrawer({ restoreFocus: true }));
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape' && sidebar.classList.contains('is-open')) closeDrawer({ restoreFocus: true });
    });
    phoneLayout.addEventListener?.('change', (event) => { if (!event.matches) closeDrawer(); });

    const collapse = document.querySelector('.sb-collapse');
    setCollapsed(root.getAttribute('data-sidebar') === 'collapsed'); // theme.js applied the saved state
    collapse.addEventListener('click', () => {
        const collapsed = root.getAttribute('data-sidebar') !== 'collapsed';
        setCollapsed(collapsed);
        try { localStorage.setItem(STORE.sidebar, collapsed ? 'collapsed' : 'expanded'); } catch { /* session only */ }
    });
}

/* ------------------------------------------------------------------ header: language, theme */

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

const THEME_COLOR = { light: '#172554', dark: '#0d1320' };

function applyTheme(theme) {
    root.setAttribute('data-theme', theme);
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
    applyTheme(root.getAttribute('data-theme') === 'dark' ? 'dark' : 'light');
    document.querySelector('.theme-toggle')?.addEventListener('click', () => {
        const next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
        // Cross-fade the whole page between themes where supported (View Transitions API);
        // other browsers, and reduced-motion users, get the instant switch.
        if (document.startViewTransition && !prefersReducedMotion()) {
            document.startViewTransition(() => applyTheme(next));
        } else {
            applyTheme(next);
        }
        try { localStorage.setItem(STORE.theme, next); } catch { /* storage unavailable: session-only */ }
    });
    // Follow OS changes until the user picks a theme explicitly.
    window.matchMedia?.('(prefers-color-scheme: dark)').addEventListener?.('change', (e) => {
        let saved = null;
        try { saved = localStorage.getItem(STORE.theme); } catch { /* ignore */ }
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
        payload.trainType = form.elements.trainType.value;
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
    if (!response.ok) {
        // Keep the server's per-field details so the form can mark the exact fields.
        throw Object.assign(new Error(apiErrorMessage(data, response.status)),
            { code: data?.error, details: Array.isArray(data?.details) ? data.details : [] });
    }
    if (!data) throw new Error(t('err.empty'));
    return data;
}

const postJson = (url, body) => requestJson(url, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
});

/* ------------------------------------------------------------------ countdowns */

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

const countdowns = new Map(); // result region → stop()

function stopCountdown(region) {
    countdowns.get(region)?.();
    countdowns.delete(region);
}

/** Runs the 4-unit countdown inside a result card; anchored to the server's remaining millis. */
function startCountdown(region, container, millis, onDone) {
    stopCountdown(region);
    const units = {};
    container.querySelectorAll('[data-unit]').forEach((el) => { units[el.dataset.unit] = el; });
    const render = (remaining) => {
        units.days.textContent = pad2(Math.floor(remaining / MS.day));
        units.hours.textContent = pad2(Math.floor((remaining % MS.day) / MS.hour));
        units.minutes.textContent = pad2(Math.floor((remaining % MS.hour) / MS.minute));
        units.seconds.textContent = pad2(Math.floor((remaining % MS.minute) / MS.second));
    };
    countdowns.set(region, tickEverySecond(Date.now() + millis, render, () => {
        countdowns.delete(region);
        onDone();
    }));
}

/* ------------------------------------------------------------------ result actions: reminder, copy, share */

const toIcsUtc = (date) => date.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
const escapeIcs = (text) => text.replace(/[\\;,]/g, (c) => `\\${c}`).replace(/\n/g, '\\n');

/** Downloads an .ics calendar event for a general / Tatkal opening. */
function downloadReminder(kind, data) {
    const start = new Date(data.bookingDateTime);
    const end = new Date(start.getTime() + REMINDER_DURATION_MINUTES * MS.minute);
    const opening = isoParts(data.bookingDateTime);
    const journey = longDate(data.journeyDate);
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
    const link = Object.assign(document.createElement('a'), { href: url, download: `railtime-${kind}-${opening.date}.ics` });
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), MS.second);
}

/** Remembers a reminder for the Reminders page (this browser only). */
function saveReminder(entry) {
    const list = readStore(STORE.reminders, []).filter((r) => !(r.kind === entry.kind
        && r.travelClass === entry.travelClass && r.journeyDate === entry.journeyDate));
    list.push(entry);
    list.sort((a, b) => Date.parse(a.bookingDateTime) - Date.parse(b.bookingDateTime));
    writeStore(STORE.reminders, list.slice(-30));
}

async function copyToClipboard(text) {
    try {
        await navigator.clipboard.writeText(text);
        return true;
    } catch {
        // Fallback for browsers / contexts without the async clipboard API.
        const area = Object.assign(document.createElement('textarea'), { value: text });
        area.setAttribute('readonly', '');
        area.style.cssText = 'position:fixed;opacity:0;pointer-events:none';
        document.body.append(area);
        area.select();
        let ok = false;
        try { ok = document.execCommand('copy'); } catch { ok = false; }
        area.remove();
        return ok;
    }
}

/** Wires Add reminder / Copy / Share on a result card. */
function wireActions(node, { text, reminder }) {
    const feedback = node.querySelector('.r-feedback');
    let clearTimer = null;
    const say = (message, isError = false) => {
        feedback.textContent = isError ? message : `✓ ${message}`;
        feedback.classList.toggle('is-error', isError);
        clearTimeout(clearTimer);
        clearTimer = setTimeout(() => { feedback.textContent = ''; }, FEEDBACK_MS);
    };
    const fullText = () => `${text()}\n\n${t('copy.footer')}`;

    const reminderButton = node.querySelector('[data-action="reminder"]');
    reminderButton.hidden = !reminder;
    if (reminder) {
        reminderButton.addEventListener('click', () => {
            reminder();
            say(t('action.reminderAdded'));
        });
    }
    node.querySelector('[data-action="copy"]').addEventListener('click', async () => {
        if (await copyToClipboard(fullText())) say(t('action.copied'));
        else say(t('action.copyFailed'), true);
    });
    node.querySelector('[data-action="share"]').addEventListener('click', async () => {
        if (navigator.share) {
            try {
                await navigator.share({ title: document.title, text: fullText(), url: window.location.href });
                return;
            } catch (err) {
                if (err.name === 'AbortError') return; // the user closed the share sheet
            }
        }
        if (await copyToClipboard(`${fullText()}\n${window.location.href}`)) say(t('action.sharedCopied'));
        else say(t('action.copyFailed'), true);
    });
}

/* ------------------------------------------------------------------ rendering: shared */

const resultRegion = (kind) => document.getElementById(`result-${kind}`);

function newResult(kind) {
    const node = cloneTemplate('tpl-result');
    node.classList.add(`kind-${kind}`);
    return node;
}

function addFact(node, term, value) {
    const fact = cloneTemplate('tpl-fact');
    slot(fact, 'term').textContent = term;
    slot(fact, 'value').textContent = value;
    slot(node, 'facts').append(fact);
}

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

function fillNotes(node, rule, notes) {
    slot(node, 'rule-line').hidden = !rule;
    slot(node, 'rule').textContent = rule ?? '';
    const list = slot(node, 'notes');
    list.replaceChildren(...notes.map((note) => Object.assign(document.createElement('li'), { textContent: note })));
    slot(node, 'notes-box').hidden = notes.length === 0;
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
    node.querySelector('[data-action="reminder"]').hidden = status !== 'NOT_OPEN';
}

function renderBooking(kind, data) {
    const region = resultRegion(kind);
    const node = newResult(kind);
    const opening = isoParts(data.bookingDateTime);
    const journeyIso = data.journeyDate ?? data.originDepartureDate;
    const gap = daysBetween(opening.date, journeyIso);
    const cls = kind === 'tatkal' ? className(data.travelClass, data.travelClassName) : null;

    slot(node, 'date').textContent = longDate(opening.date);
    slot(node, 'time').textContent = `${timeWithZone(opening.time)} · ${weekday(opening.date)}`;
    if (cls) {
        slot(node, 'chip').hidden = false;
        slot(node, 'chip').textContent = cls;
    }
    addFact(node, t('result.departure'), `${longDate(journeyIso)} · ${weekday(journeyIso)}`);
    addFact(node, t('result.daysBefore'), t(gap === 1 ? 'booking.gapOne' : 'booking.gapMany', gap));
    slot(node, 'countdown-label').textContent = t('result.opensIn');

    const reminderData = { kind, travelClass: data.travelClass, bookingDateTime: data.bookingDateTime, journeyDate: journeyIso };
    wireActions(node, {
        text: () => (kind === 'general'
            ? t('copy.general', longDate(opening.date), timeWithZone(opening.time), longDate(journeyIso))
            : t('copy.tatkal', longDate(opening.date), timeWithZone(opening.time), cls, longDate(journeyIso))),
        reminder: () => {
            downloadReminder(kind, reminderData);
            saveReminder(reminderData);
        },
    });
    applyBookingStatus(node, data.status);
    showResult(region, node);

    if (data.status === 'NOT_OPEN' && data.millisUntilOpening > 0) {
        // Display-only transition when the countdown ends; the server remains authoritative.
        startCountdown(region, slot(node, 'countdown'), data.millisUntilOpening, () => applyBookingStatus(node, 'OPEN'));
    }
    return { opening: data.bookingDateTime, travelClass: data.travelClass };
}

function renderNotApplicable(data) {
    const node = newResult('tatkal');
    const cls = className(data.travelClass, data.travelClassName);
    const message = t('tatkal.notListed', data.travelClass);
    slot(node, 'label').textContent = t('kind.tatkal');
    applyView(node, { pill: t('result.verify'), pillClass: 'pill-neutral' });
    slot(node, 'date').textContent = cls;
    slot(node, 'date').classList.add('is-text');
    slot(node, 'time-chip').hidden = true;
    addFact(node, t('result.departure'), longDate(data.originDepartureDate));
    fillNotes(node, message, []);
    wireActions(node, { text: () => `${cls}\n${message}` });
    showResult(resultRegion('tatkal'), node);
    return null;
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

/** Shared refund values with the scale the result used (2026 / 2015 / premium sleeper) laid over them. */
function refundScale(data) {
    const refund = PAGE.rules.refund;
    return { ...refund, ...refund.ruleSets?.[data.ruleSet] };
}

const PREMIUM_SCALE = 'PREMIUM_SLEEPER_2026';

/** The rule that applied, worded from the configured rule values (same wording as the API). */
function refundRule(data) {
    const r = refundScale(data);
    const flat = r.flatCharges?.[data.travelClass];
    const key = `refund.rule.${data.slab}`;
    if (data.ruleSet === PREMIUM_SCALE) {
        // Percentage-only scale: no flat minimum.
        if (data.slab === 'QUARTER_OF_FARE') return t('refund.rule.premiumEarly', r.earlyAboveHours, r.quarterRatePercent);
        if (data.slab === 'HALF_OF_FARE') return t('refund.rule.premiumLate', r.earlyAboveHours, r.cutoffHours, r.halfRatePercent);
    }
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
    const r = refundScale(data);
    if (!data.refundable) return t('refund.pill.none');
    if (data.slab === 'QUARTER_OF_FARE') return t('refund.pill.QUARTER_OF_FARE', r.quarterRatePercent);
    if (data.slab === 'HALF_OF_FARE') return t('refund.pill.HALF_OF_FARE', r.halfRatePercent);
    return t(`refund.pill.${data.slab}`);
}

function refundNotes(data) {
    const r = refundScale(data);
    const notes = [t(`refund.note.source.${data.ruleSet ?? 'STANDARD_2026'}`, longDate(r.revisedFrom))];
    if (r.gstClasses?.includes(data.travelClass)) notes.push(t('refund.note.gst', r.gstRatePercent));
    if (data.ticketStatus === 'CONFIRMED') notes.push(t('refund.note.tdr', r.cutoffHours));
    notes.push(t('refund.note.fees'), t('refund.note.verify'));
    return notes;
}

function renderRefund(data) {
    const node = newResult('refund');
    const fare = Number(data.totalFare);
    const percent = fare > 0 ? Math.round((Number(data.totalRefund) / fare) * 100) : 0;
    const perPassenger = data.passengers > 1 ? ` × ${data.passengers}` : '';
    const cls = className(data.travelClass, data.travelClassName);
    const ticket = `${t(`status.${data.ticketStatus}`)} · `
        + t(data.passengers === 1 ? 'refund.passengersOne' : 'refund.passengersMany', data.passengers);
    const rule = refundRule(data);

    slot(node, 'label').textContent = t('result.estimatedRefund');
    applyView(node, { pill: refundPill(data), pillClass: data.refundable ? 'pill-open' : 'pill-none' });
    slot(node, 'date').textContent = money(data.totalRefund);
    slot(node, 'date').classList.add('is-amount');
    slot(node, 'time').textContent = leadTime(data.minutesBeforeDeparture);
    slot(node, 'chip').hidden = false;
    slot(node, 'chip').textContent = ticket;
    addFact(node, t('result.refunded'), t('result.ofFare', percent));
    addFact(node, t('result.class'), cls);
    if (data.trainType && data.trainType !== 'STANDARD') addFact(node, t('result.train'), t(`train.${data.trainType}`));

    const breakdown = slot(node, 'breakdown');
    breakdown.hidden = false;
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
    fillNotes(node, rule, refundNotes(data));
    if (!data.refundable) node.classList.add('is-none');
    wireActions(node, { text: () => t('copy.refund', money(data.totalRefund), money(data.totalFare), rule, cls, ticket) });
    showResult(resultRegion('refund'), node);
    return { refund: data.totalRefund };
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
    const region = resultRegion('chart');
    const node = newResult('chart');
    const first = isoParts(data.firstChartAt);
    const final = isoParts(data.finalChartAt);
    const dep = isoParts(data.departureDateTime);
    const hoursBefore = Math.round((Date.parse(data.departureDateTime) - Date.parse(data.firstChartAt)) / MS.hour);

    slot(node, 'label').textContent = t('result.firstChart');
    slot(node, 'date').textContent = longDate(first.date);
    slot(node, 'time').textContent = `${timeWithZone(first.time)} · ${weekday(first.date)}`;
    slot(node, 'chip').hidden = false;
    slot(node, 'chip').textContent = data.rule === 'PREVIOUS_EVENING'
        ? t('chart.rulePreviousEvening') : t('chart.ruleHours', hoursBefore);
    addFact(node, t('chart.final'), `${shortDate(final.date)} · ${time12(final.time)}`);
    addFact(node, t('result.departure'), `${shortDate(dep.date)} · ${time12(dep.time)}`);
    slot(node, 'countdown-label').textContent = t('result.firstChartIn');
    fillNotes(node, null, chartNotes(dep.date));
    wireActions(node, {
        text: () => t('copy.chart', longDate(first.date), timeWithZone(first.time),
            `${longDate(final.date)}, ${timeWithZone(final.time)}`, `${longDate(dep.date)}, ${timeWithZone(dep.time)}`),
    });
    applyChartStatus(node, data.status, data);
    showResult(region, node);

    if (data.status === 'PENDING' && data.millisUntilFirstChart > 0) {
        startCountdown(region, slot(node, 'countdown'), data.millisUntilFirstChart,
            () => applyChartStatus(node, 'FIRST_CHART_PREPARED', data));
    }
    return { firstChartAt: data.firstChartAt };
}

function renderError(kind, message) {
    const node = cloneTemplate('tpl-error');
    slot(node, 'message').textContent = message;
    showResult(resultRegion(kind), node);
}

/* ------------------------------------------------------------------ showing results */

const pendingResize = new Map(); // region → { from, to } until presentResult() runs

/** Swaps in the new result card; presentResult() then decides how to bring it into view. */
function showResult(region, node) {
    const from = region.getBoundingClientRect().height;
    region.getAnimations().forEach((animation) => animation.cancel());
    stopCountdown(region);
    region.replaceChildren(node);
    pendingResize.set(region, { from, to: region.getBoundingClientRect().height });
}

/**
 * Glides the region from its old height to the new one, so the content below moves smoothly.
 * Only used when the page doesn't scroll: while it runs the page is shorter than its final length,
 * which would make a scroll to the result stop short.
 */
function animateResultHeight(region, { from, to }) {
    if (Math.abs(to - from) < 2 || prefersReducedMotion() || !region.animate) return;
    region.animate(
        [{ height: `${from}px`, overflow: 'hidden' }, { height: `${to}px`, overflow: 'hidden' }],
        { duration: 300, easing: 'cubic-bezier(.2, .7, .2, 1)' },
    );
}

/**
 * Scrolls so the whole result is visible, centred in the space below the sticky header (or, if it
 * is taller than that space, starting just below the header). Returns false when it is already
 * fully visible, so the page doesn't move needlessly.
 */
function scrollResultIntoView(region) {
    const header = document.querySelector('.app-header')?.getBoundingClientRect().height ?? 0;
    const rect = region.getBoundingClientRect(); // final layout: no height animation is running
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
function presentResult(region, { reveal }) {
    const resize = pendingResize.get(region);
    pendingResize.delete(region);
    if (reveal && scrollResultIntoView(region)) return;
    if (resize) animateResultHeight(region, resize);
}

/* ------------------------------------------------------------------ calculating */

let lastCalculatedForm = null;

async function calculate(form, { reveal = true } = {}) {
    if (form.classList.contains('is-loading')) return;

    const payload = collectPayload(form);
    if (!payload) return;

    const kind = form.dataset.kind;
    const region = resultRegion(kind);
    const button = form.querySelector('button[type="submit"]');
    // The spinner replaces a "Calculating…" label swap, which flickered on fast responses.
    form.classList.add('is-loading');
    form.setAttribute('aria-busy', 'true');
    button.setAttribute('aria-disabled', 'true');

    try {
        const [data] = await Promise.all([postJson(form.dataset.endpoint, payload), delay(MIN_LOADING_MS)]);
        let summary;
        if (kind === 'refund') summary = renderRefund(data);
        else if (kind === 'chart') summary = renderChart(data);
        else if (data.status === 'NOT_APPLICABLE') summary = renderNotApplicable(data);
        else summary = renderBooking(kind, data);
        lastCalculatedForm = form.id;
        if (summary) recordRecent(kind, payload, summary);
        if (currentPage === 'dashboard') setTimeout(() => loadUsage({ force: true }), 400); // counted after the response
    } catch (err) {
        renderError(kind, err.message);
        markServerFieldErrors(form, err);
    } finally {
        form.classList.remove('is-loading');
        form.removeAttribute('aria-busy');
        button.removeAttribute('aria-disabled');
        presentResult(region, { reveal });
    }
}

/**
 * Shows the server's per-field problems next to the matching inputs. English pages show the
 * server's own words; other languages show the translated message for the error code.
 */
function markServerFieldErrors(form, err) {
    let first = null;
    for (const { field, message } of err.details ?? []) {
        const input = form.elements[field];
        if (!(input instanceof Element)) continue;
        setFieldError(input, PAGE.lang === API_LANGUAGE ? message
            : t(hasMessage(`err.${err.code}`) ? `err.${err.code}` : 'err.generic'));
        first ??= input;
    }
    first?.focus({ preventScroll: true });
}

function handleSubmit(event) {
    event.preventDefault();
    calculate(event.currentTarget);
}

/** Reset clears the fields and their messages; the last result stays until the next calculation. */
function handleReset(event) {
    const form = event.currentTarget;
    setTimeout(() => {
        form.querySelectorAll('input, select').forEach((field) => {
            setFieldError(field, null);
            if (field.type === 'date') updateDateDisplay(field);
        });
    });
}

/* ------------------------------------------------------------------ recent calculations (dashboard) */

function recordRecent(kind, payload, summary) {
    const entry = { kind, payload, summary, at: Date.now() };
    const key = JSON.stringify([kind, payload]);
    const list = readStore(STORE.recent, []).filter((item) => JSON.stringify([item.kind, item.payload]) !== key);
    list.unshift(entry);
    writeStore(STORE.recent, list.slice(0, RECENT_LIMIT));
}

function recentText({ kind, payload, summary }) {
    const opening = (iso) => { const p = isoParts(iso); return `${shortDate(p.date)} · ${time12(p.time)}`; };
    switch (kind) {
        case 'general':
            return { sub: longDate(payload.originDepartureDate), result: t('recent.opens', opening(summary.opening)) };
        case 'tatkal':
            return { sub: `${mediumDate(payload.originDepartureDate)} · ${payload.travelClass}`, result: t('recent.opens', opening(summary.opening)) };
        case 'refund':
            return { sub: `${payload.travelClass} · ${t(`status.${payload.ticketStatus}`)} · ${money(payload.totalFare)}`, result: t('recent.refund', money(summary.refund)) };
        default:
            return { sub: `${mediumDate(payload.departureDate)} · ${time12(payload.departureTime)}`, result: t('recent.firstChart', opening(summary.firstChartAt)) };
    }
}

function renderRecent() {
    const section = document.querySelector('.recent');
    const list = readStore(STORE.recent, []).filter((item) => CALCULATORS.includes(item.kind) && item.payload && item.summary);
    section.hidden = list.length === 0;
    section.querySelector('.recent-list').replaceChildren(...list.map((item) => {
        const li = cloneTemplate('tpl-recent-item');
        const link = slot(li, 'link');
        const texts = recentText(item);
        link.classList.add(`kind-${item.kind}`);
        link.href = pathOf(item.kind);
        slot(li, 'icon').setAttribute('href', sidebarLink(item.kind).querySelector('use').getAttribute('href'));
        slot(li, 'title').textContent = sidebarLink(item.kind).dataset.label;
        slot(li, 'sub').textContent = texts.sub;
        slot(li, 'result').textContent = texts.result;
        link.addEventListener('click', (event) => {
            if (event.metaKey || event.ctrlKey || event.shiftKey) return;
            event.preventDefault();
            rerun(item);
        });
        return li;
    }));
}

/** Opens the calculator, fills in the saved inputs and calculates again (times are relative to now). */
function rerun(item) {
    navigate(item.kind);
    const form = document.getElementById(`${item.kind}-form`);
    for (const [name, value] of Object.entries(item.payload)) {
        const field = form.elements[name];
        if (!field) continue;
        field.value = value;
        if (field.type === 'date') updateDateDisplay(field);
    }
    calculate(form);
}

function initRecent() {
    document.querySelector('[data-action="clear-recent"]').addEventListener('click', () => {
        writeStore(STORE.recent, []);
        renderRecent();
    });
    renderRecent();
}

/* ------------------------------------------------------------------ reminders page */

function renderReminders() {
    const reminders = readStore(STORE.reminders, []).filter((r) => r.bookingDateTime && r.journeyDate);
    const list = document.querySelector('.reminder-list');
    list.hidden = reminders.length === 0;
    slot(document, 'reminders-empty').hidden = reminders.length > 0;
    list.replaceChildren(...reminders.map((reminder) => {
        const li = cloneTemplate('tpl-reminder-item');
        const opening = isoParts(reminder.bookingDateTime);
        const passed = Date.parse(reminder.bookingDateTime) <= Date.now();
        li.classList.add(`kind-${reminder.kind}`);
        slot(li, 'title').textContent = reminder.kind === 'general'
            ? t('reminders.titleGeneral') : t('reminders.titleTatkal', className(reminder.travelClass));
        slot(li, 'sub').textContent = t('reminders.sub', `${shortDate(opening.date)} · ${timeWithZone(opening.time)}`,
            mediumDate(reminder.journeyDate));
        const state = slot(li, 'state');
        state.textContent = t(passed ? 'reminders.passed' : 'reminders.upcoming');
        state.classList.add(passed ? 'pill-passed' : 'pill-neutral');
        li.querySelector('[data-action="download"]').addEventListener('click', () => downloadReminder(reminder.kind, reminder));
        li.querySelector('[data-action="remove"]').addEventListener('click', () => {
            const same = (r) => r.kind === reminder.kind && r.travelClass === reminder.travelClass
                && r.journeyDate === reminder.journeyDate && r.bookingDateTime === reminder.bookingDateTime;
            writeStore(STORE.reminders, readStore(STORE.reminders, []).filter((r) => !same(r)));
            renderReminders();
        });
        return li;
    }));
}

/* ------------------------------------------------------------------ live booking windows (dashboard, status) */

const TODAY_REFRESH_MS = 30 * MS.minute;
let todayStop = null;
let todayRefresh = null;

function setToday(name, text, open) {
    document.querySelectorAll(`[data-today="${name}"]`).forEach((el) => {
        el.textContent = text;
        if (open !== undefined) el.classList.toggle('is-open', open);
    });
}

function renderToday(data) {
    const opensAt = isoParts(data.nextOpening.opensAt);
    setToday('latest', mediumDate(data.latestBookableJourneyDate));
    setToday('next', mediumDate(data.nextOpening.journeyDate));
    setToday('next-time', timeWithZone(opensAt.time));
    setToday('next-day', t(opensAt.date === isoParts(data.asOf).date ? 'today.today' : 'today.tomorrow'));
    setToday('tatkal', mediumDate(data.tatkal.journeyDate));
    const tatkalPill = (name, key, open, opensAtIso) =>
        setToday(name, t(key, open ? t('today.open') : time12(isoParts(opensAtIso).time)), open);
    tatkalPill('ac', 'today.ac', data.tatkal.acOpen, data.tatkal.acOpensAt);
    tatkalPill('nonac', 'today.nonAc', data.tatkal.nonAcOpen, data.tatkal.nonAcOpensAt);

    // Every deadline is measured against the server's "asOf", so the browser clock cannot skew it.
    const asOf = Date.parse(data.asOf);
    const anchor = Date.now();
    const deadlines = [data.nextOpening.millisUntilOpening, Date.parse(data.tatkal.acOpensAt) - asOf,
        Date.parse(data.tatkal.nonAcOpensAt) - asOf].filter((ms) => ms > 0);
    const refreshIn = Math.min(TODAY_REFRESH_MS, ...deadlines.map((ms) => ms + MS.second));

    todayStop?.();
    todayStop = tickEverySecond(anchor + data.nextOpening.millisUntilOpening,
        (remaining) => setToday('opens-in', t('today.opensIn', duration(remaining))),
        () => setToday('opens-in', ''));
    clearTimeout(todayRefresh);
    todayRefresh = setTimeout(loadToday, refreshIn);
}

/** The booking windows are rendered by the server; this adds the live countdown and refreshes them. */
async function loadToday() {
    const endpoint = document.body.dataset.windowEndpoint ?? '/api/v1/booking/window';
    try {
        renderToday(await requestJson(endpoint));
    } catch {
        // Keep the server-rendered values; just stop the live parts and try again later.
        todayStop?.();
        setToday('opens-in', '');
        clearTimeout(todayRefresh);
        todayRefresh = setTimeout(loadToday, TODAY_REFRESH_MS);
    }
}

/* ------------------------------------------------------------------ usage & traffic (dashboard) */

const USAGE_REFRESH_MS = 30 * MS.second;
const SVG_NS = 'http://www.w3.org/2000/svg';
const integer = new Intl.NumberFormat(PAGE.locale);
let usageLoadedAt = 0;

function svgEl(name, attributes) {
    const el = document.createElementNS(SVG_NS, name);
    Object.entries(attributes).forEach(([key, value]) => el.setAttribute(key, value));
    return el;
}

/** A minimal two-series line chart (visits with a soft area, calculations as a thin line). */
function drawUsageChart(svg, daily) {
    const width = 600;
    const height = 120;
    const pad = 6;
    const max = Math.max(1, ...daily.map((d) => Math.max(d.visits, d.calculations)));
    const x = (i) => (daily.length === 1 ? width / 2 : (i / (daily.length - 1)) * width);
    const y = (v) => height - pad - (v / max) * (height - 2 * pad);
    const line = (key) => daily.map((d, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(d[key]).toFixed(1)}`).join('');

    const frame = [
        ...[0.5, 1].map((f) => svgEl('line', { x1: 0, x2: width, y1: y(max * f), y2: y(max * f), class: 'uc-grid' })),
        svgEl('line', { x1: 0, x2: width, y1: height - pad, y2: height - pad, class: 'uc-base' }),
    ];
    if (daily.length === 1) {
        // Counting started today: one point per series, no invented history.
        const d = daily[0];
        svg.replaceChildren(...frame,
            // Zero-length round-capped lines stay round even though the chart stretches horizontally.
            svgEl('line', { x1: x(0), x2: x(0), y1: y(d.visits), y2: y(d.visits), class: 'uc-dot-visits' }),
            svgEl('line', { x1: x(0), x2: x(0), y1: y(d.calculations), y2: y(d.calculations), class: 'uc-dot-calcs' }));
    } else {
        svg.replaceChildren(...frame,
            svgEl('path', { d: `${line('visits')}L${width},${height - pad}L0,${height - pad}Z`, class: 'uc-area' }),
            svgEl('path', { d: line('visits'), class: 'uc-line-visits' }),
            svgEl('path', { d: line('calculations'), class: 'uc-line-calcs' }));
    }
    const total = daily.reduce((sum, d) => sum + d.visits, 0);
    const peak = daily.reduce((best, d) => (d.visits > best.visits ? d : best), daily[0]);
    svg.setAttribute('aria-label', t('usage.chartLabel', integer.format(total), integer.format(peak.visits), mediumDate(peak.date)));
}

function renderUsage(section, data) {
    const set = (name, value) => { section.querySelector(`[data-usage="${name}"]`).textContent = value; };
    const since = isoParts(data.since);
    set('since', t('usage.since', `${mediumDate(since.date)} · ${timeWithZone(since.time)}`));
    set('visits-total', integer.format(data.visits.total));
    set('visits-today', integer.format(data.visits.today));
    set('visits-week', integer.format(data.visits.thisWeek));
    set('visits-month', integer.format(data.visits.thisMonth));
    set('calc-total', integer.format(data.calculations.total));
    set('calc-general', integer.format(data.calculations.general));
    set('calc-tatkal', integer.format(data.calculations.tatkal));
    set('calc-refund', integer.format(data.calculations.refund));
    set('calc-chart', integer.format(data.calculations.chart));
    set('first-day', shortDate(data.daily[0].date));
    set('last-day', data.daily.length > 1 ? shortDate(data.daily[data.daily.length - 1].date) : '');
    drawUsageChart(section.querySelector('[data-usage="chart"]'), data.daily);
}

/** Loads the counts when the dashboard is shown (at most every 30 s); hides the section if unavailable. */
async function loadUsage({ force = false } = {}) {
    const section = document.querySelector('.usage');
    if (!section || (!force && Date.now() - usageLoadedAt < USAGE_REFRESH_MS)) return;
    usageLoadedAt = Date.now();
    try {
        renderUsage(section, await requestJson(section.dataset.endpoint));
        section.hidden = false;
    } catch {
        section.hidden = true; // informational only
    }
}

/* ------------------------------------------------------------------ language switch continuity */

// Switching language reloads the page (on the same page). To make it feel seamless, the page keeps
// what you typed and re-runs your last calculation.
function carryStateAcross() {
    const values = {};
    document.querySelectorAll('.calc-form input, .calc-form select').forEach((field) => {
        if (field.id && field.value) values[field.id] = field.value;
    });
    try {
        sessionStorage.setItem(STORE.carry, JSON.stringify({ values, last: lastCalculatedForm }));
    } catch { /* storage unavailable: switch without carrying state */ }
}

function restoreCarriedState() {
    let carried = null;
    try {
        carried = JSON.parse(sessionStorage.getItem(STORE.carry));
        sessionStorage.removeItem(STORE.carry);
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
        link.addEventListener('click', carryStateAcross);
    });
    restoreCarriedState();
}

/* ------------------------------------------------------------------ start */

function init() {
    initTheme();
    initSidebar();
    initRouter();
    initLanguageMenu();
    initRecent();
    loadToday();
    document.querySelectorAll('.calc-form').forEach((form) => {
        form.addEventListener('submit', handleSubmit);
        form.addEventListener('reset', handleReset);
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
