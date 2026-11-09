/*
 * The operator page (phase 8): the exchange's clock, the background traffic and the system at a glance, read from
 * /api/admin/demo and re-read while the page is visible - the clock every second, the traffic every 2 s, the views
 * every 5 s.
 */
const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;

async function api(path, method = 'GET', body) {
    const headers = { 'Accept': 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (method !== 'GET' && csrfHeader && csrfToken) headers[csrfHeader] = csrfToken;
    const response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: 'same-origin' });
    const text = await response.text();
    const json = text ? JSON.parse(text) : null;
    if (!response.ok) {
        const error = new Error(json?.error || json?.detail || `${response.status} ${response.statusText}`);
        error.status = response.status;
        throw error;
    }
    return json;
}

function setText(id, text) {
    document.getElementById(id).textContent = text;
}

function message(text, tone = 'bad') {
    const box = document.getElementById('messages');
    const item = document.createElement('div');
    item.className = `message ${tone}`;
    item.textContent = text;
    box.prepend(item);
    setTimeout(() => item.remove(), 8000);
}

function poll(fn, millis) {
    const run = async () => {
        if (!document.hidden) {
            try { await fn(); } catch (error) { console.warn(error); }
        }
        setTimeout(run, millis);
    };
    run();
}

function words(text) {
    return String(text).replaceAll('_', ' ').toLowerCase().replace(/^./, c => c.toUpperCase());
}

function buckets(id, counts, empty) {
    const list = document.getElementById(id);
    const entries = Object.entries(counts || {});
    list.innerHTML = '';
    if (entries.length === 0) {
        const dd = document.createElement('dd');
        dd.className = 'muted';
        dd.textContent = empty;
        list.append(dd);
        return;
    }
    for (const [name, count] of entries) {
        const dt = document.createElement('dt');
        dt.textContent = name.includes(':') ? name : words(name);
        const dd = document.createElement('dd');
        dd.textContent = count.toLocaleString();
        list.append(dt, dd);
    }
}

/**
 * At this speed, how long an hour of exchange time takes, in words.
 */
function speedText(speed, running) {
    if (!running) return 'The clock is paused: exchange time stands still.';
    const n = Number(speed);
    if (n === 1) return 'Running at 1x: exchange time runs as fast as real time.';
    const seconds = 3600 / n;
    const amount = seconds >= 60 ? +(seconds / 60).toFixed(1) : +seconds.toFixed(1);
    const hour = `${amount === 1 ? '' : amount + ' '}${seconds >= 60 ? 'minute' : 'second'}${amount === 1 ? '' : 's'}`;
    return `Running at ${n}x: an exchange hour passes every ${hour}.`;
}

let jobRunning = false;

async function refreshClock() {
    const clock = await api('/api/admin/demo/clock');
    setText('phase-text', clock.phaseText);
    document.getElementById('phase-text').dataset.phase = clock.phase;
    setText('speed-text', speedText(clock.speed, clock.running));
    setText('exchange-time', String(clock.newYorkTime).replace('T', ' ').slice(0, 16));
    setText('business-date', clock.openBusinessDate ?? 'not known yet');
    setText('last-close', clock.lastClosedBusinessDate ?? 'none yet');
    for (const button of document.querySelectorAll('#speeds button')) {
        button.classList.toggle('active', Number(button.dataset.speed) === Number(clock.speed));
    }
    const job = clock.job;
    const jobLine = document.getElementById('job');
    jobRunning = job?.state === 'RUNNING';
    jobLine.hidden = !job;
    if (job) {
        jobLine.className = `job ${job.state === 'FAILED' ? 'bad' : job.state === 'RUNNING' ? 'live' : 'good'}`;
        const progress = job.closesTotal > 0 ? ` (${job.closesDone} of ${job.closesTotal} closes)` : '';
        jobLine.textContent = `${job.description}${progress}: ${job.message}`;
    }
    document.getElementById('pause').disabled = jobRunning || !clock.running;
    document.getElementById('resume').disabled = jobRunning || clock.running;
    for (const id of ['skip-open', 'skip-close', 'advance']) document.getElementById(id).disabled = jobRunning;
}

async function refreshTraffic() {
    const traffic = await api('/api/admin/demo/traffic');
    const perSecond = Number(traffic.ordersPerRealSecond).toFixed(1);
    const cap = traffic.capped ? ` - capped at ${traffic.maxOrdersPerRealSecond} a second` : '';
    const state = traffic.running
        ? `Running at ${traffic.ordersPerExchangeMinute} orders per exchange minute (${perSecond} a real second at this speed${cap}), ${traffic.customers} customers. `
        : `Stopped. At ${traffic.ordersPerExchangeMinute} per exchange minute it would send ${perSecond} orders a real second${cap}. `;
    setText('traffic-text', state + traffic.message);
    buckets('traffic-sent', traffic.lastMinute, 'Nothing sent in the last minute.');
    buckets('traffic-outcomes', traffic.outcomesLastMinute, 'No outcomes in the last minute.');
    document.getElementById('traffic-start').disabled = traffic.running;
    document.getElementById('traffic-stop').disabled = !traffic.running;
}

async function refreshOverview() {
    const overview = await api('/api/admin/demo/overview');
    buckets('orders-by-status', overview.ordersByStatus, 'No orders yet.');
    buckets('payments-by-status', overview.paymentsByStatus, 'No payments yet.');
    buckets('cases-by-state', overview.casesByState, 'No reconciliation cases.');
    buckets('locks-by-instance', overview.fencedLocksByInstance, 'No locks held.');
}

function act(id, call, after = refreshClock) {
    document.getElementById(id).addEventListener('click', async event => {
        event.target.disabled = true;
        try {
            await call();
            await after();
        } catch (error) {
            message(error.message);
        } finally {
            event.target.disabled = false;
            after().catch(() => {});
        }
    });
}

act('pause', () => api('/api/admin/demo/clock/pause', 'POST'));
act('resume', () => api('/api/admin/demo/clock/resume', 'POST'));
act('skip-open', () => api('/api/admin/demo/clock/skip', 'POST', { to: 'NEXT_OPEN' }));
act('skip-close', () => api('/api/admin/demo/clock/skip', 'POST', { to: 'NEXT_CLOSE' }));
act('advance', () => api('/api/admin/demo/clock/advance', 'POST', { businessDays: Number(document.getElementById('advance-days').value) }));
for (const button of document.querySelectorAll('#speeds button')) {
    button.addEventListener('click', async () => {
        try {
            await api('/api/admin/demo/clock/speed', 'PUT', { speed: Number(button.dataset.speed) });
            await refreshClock();
        } catch (error) {
            message(error.message);
        }
    });
}
const rate = () => ({ ordersPerExchangeMinute: Number(document.getElementById('rate').value) });
act('traffic-start', () => api('/api/admin/demo/traffic/start', 'POST', rate()), refreshTraffic);
act('traffic-rate', () => api('/api/admin/demo/traffic/rate', 'PUT', rate()), refreshTraffic);
act('traffic-stop', () => api('/api/admin/demo/traffic/stop', 'POST'), refreshTraffic);
act('traffic-burst', () => api('/api/admin/demo/traffic/burst', 'POST'), refreshTraffic);

poll(refreshClock, 1000);
poll(refreshTraffic, 2000);
poll(refreshOverview, 5000);
