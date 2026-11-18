/*
 * The customer UI's shared script (phase 7 design §2, §4, §5): JSON over fetch with the session's CSRF token,
 * formatting, messages, polling while the page is visible, and the header's account picker.
 */

const csrfToken  = document.querySelector('meta[name="_csrf"]')?.content;
const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;

export class ApiError extends Error {
    constructor(status, message) {
        super(message);
        this.status = status;
    }
}

/**
 * fetch + JSON. A write carries the CSRF token; a lost session (401, or a 403 from a token the server no longer
 * knows) sends the browser to the login page. Every other failure is an ApiError with the server's message.
 */
export async function api(path, { method = 'GET', body } = {}) {
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (method !== 'GET' && csrfToken) headers[csrfHeader] = csrfToken;

    let response;
    try {
        response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: 'same-origin' });
    } catch (cause) {
        throw new ApiError(0, 'The brokerage could not be reached');
    }
    const text = await response.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { /* not JSON: an error page */ }

    if (response.status === 401 || (response.status === 403 && json === null)) {
        window.location.assign('/login');
        throw new ApiError(response.status, 'Your session has ended');
    }
    if (!response.ok) {
        throw new ApiError(response.status, json?.message || json?.error || `${response.status} ${response.statusText}`);
    }
    return json;
}

export const accountId = document.body.dataset.accountId;
export const accountPath = accountId ? `/api/accounts/${encodeURIComponent(accountId)}` : null;

/** A new id for something the client creates (order, payment, account). */
export function newId(prefix) {
    const uuid = globalThis.crypto?.randomUUID
        ? crypto.randomUUID()
        : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
              const r = Math.random() * 16 | 0;
              return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
          });
    return prefix ? `${prefix}-${uuid}` : uuid;
}

// ---- formatting

export function esc(value) {
    return String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

export function money(m) {
    if (m == null || m.amount == null) return '–';
    const amount = Number(m.amount);
    try {
        return amount.toLocaleString('en-US', { style: 'currency', currency: m.currency || 'USD' });
    } catch {
        return `${amount.toFixed(2)} ${m.currency ?? ''}`;
    }
}

export function signedMoney(amount, currency) {
    if (amount == null) return '';
    const n = Number(amount);
    const text = money({ amount: Math.abs(n), currency });
    return n < 0 ? `−${text}` : n > 0 ? `+${text}` : text;
}

export function qty(q) {
    return q == null ? '–' : Number(q).toLocaleString('en-US');
}

export function time(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    return isNaN(d) ? esc(iso) : d.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function words(enumValue) {
    if (!enumValue) return '';
    const s = String(enumValue).replaceAll('_', ' ').toLowerCase();
    return s.charAt(0).toUpperCase() + s.slice(1);
}

export function setText(id, text) {
    const el = document.getElementById(id);
    if (el) el.textContent = text;
}

// ---- messages

export function message(text, kind = 'info', ttlMillis = 6000) {
    const box = document.getElementById('messages');
    if (!box) return;
    const item = document.createElement('div');
    item.className = `message ${kind}`;
    item.textContent = text;
    box.append(item);
    if (ttlMillis) setTimeout(() => item.remove(), ttlMillis);
}

export function failure(error) {
    message(error instanceof ApiError ? error.message : String(error), 'error', 10000);
}

// ---- polling (design §4): only while the page is visible, again at once when it becomes visible

/**
 * Calls fn now and every intervalMillis while the page is visible. fn may return false to stop.
 * @returns a function that stops the polling
 */
export function poll(fn, intervalMillis) {
    let timer = null;
    let stopped = false;
    const tick = async () => {
        clearTimeout(timer);
        if (stopped) return;
        if (!document.hidden) {
            try {
                if ((await fn()) === false) { stop(); return; }
            } catch (error) {
                if (error instanceof ApiError && error.status === 0) message(error.message, 'error', intervalMillis);
                else failure(error);
            }
        }
        if (!stopped) timer = setTimeout(tick, intervalMillis);
    };
    const onVisible = () => { if (!document.hidden) tick(); };
    const stop = () => { stopped = true; clearTimeout(timer); document.removeEventListener('visibilitychange', onVisible); };
    document.addEventListener('visibilitychange', onVisible);
    tick();
    return stop;
}

// ---- the header's account picker

export async function accountPicker() {
    const select = document.getElementById('account-select');
    if (!select) return;
    const page = select.dataset.page;
    try {
        const accounts = await api('/api/accounts');
        select.replaceChildren(...accounts.map(a => new Option(a.accountId, a.accountId, false, a.accountId === select.dataset.accountId)));
        if (!accounts.some(a => a.accountId === select.dataset.accountId)) {
            select.prepend(new Option(select.dataset.accountId, select.dataset.accountId, true, true));
        }
        select.append(new Option('Open another account…', '__open__'));
    } catch (error) {
        failure(error);
    }
    select.addEventListener('change', () => {
        window.location.assign(select.value === '__open__' ? '/app?open' : `/app/accounts/${encodeURIComponent(select.value)}/${page}`);
    });
}

/** Disables a form's submit button while action runs; reports a failure as a message. */
export function onSubmit(form, action) {
    form.addEventListener('submit', async event => {
        event.preventDefault();
        const button = form.querySelector('button[type="submit"]');
        if (button) button.disabled = true;
        try {
            await action(new FormData(form));
        } catch (error) {
            failure(error);
        } finally {
            if (button) button.disabled = false;
        }
    });
}

/** Repeats read every intervalMillis until done(result) or the deadline; resolves with the last result. */
export async function follow(read, done, { intervalMillis = 500, timeoutMillis = 30000, onEach } = {}) {
    const deadline = Date.now() + timeoutMillis;
    let last;
    while (Date.now() < deadline) {
        try {
            last = await read();
            onEach?.(last);
            if (done(last)) return last;
        } catch (error) {
            if (!(error instanceof ApiError && error.status === 404)) throw error;  // a projection not there yet
        }
        await new Promise(resolve => setTimeout(resolve, intervalMillis));
    }
    return last;
}
