/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Essentials admin UI.
 *
 * Plain ES modules-free vanilla JavaScript: no framework, no bundler, no npm. Every byte of data comes
 * from the published admin API, so this file is a client of the same contract any other UI would use.
 * Endpoint paths below are asserted against the committed OpenAPI document by AdminUiContractParityTest —
 * adding a call to a path the contract does not declare fails the build.
 */
'use strict';

const API = document.body.dataset.api;
const CAN = {
    writeLocks: document.body.dataset.canWriteLocks === 'true',
    writeQueues: document.body.dataset.canWriteQueues === 'true',
    readPayloads: document.body.dataset.canReadPayloads === 'true'
};

/* ── HTTP ────────────────────────────────────────────────────────────────────────────────────
   Every non-2xx is normalised into the contract's Error shape, so views render one state model
   rather than each inventing its own error handling. */
async function api(path, options = {}) {
    let response;
    try {
        response = await fetch(API + path, {
            headers: { Accept: 'application/json', ...(options.body ? { 'Content-Type': 'application/json' } : {}) },
            ...options
        });
    } catch (cause) {
        throw { status: 0, error: 'Unreachable', message: null, cause };
    }

    if (response.status === 204) return null;

    const body = await response.json().catch(() => null);
    if (!response.ok) {
        throw { status: response.status, error: body?.error ?? response.statusText, message: body?.message ?? null };
    }
    return body;
}

/* ── Helpers ─────────────────────────────────────────────────────────────────────────────── */
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const nil = (t = '—') => `<span class="nil" title="not set">${t}</span>`;
const num = (v) => (v == null ? nil() : Number(v).toLocaleString('en-US'));
const ts = (v) => (v == null ? nil() : esc(String(v).replace('T', ' ').replace(/(\.\d+)?Z?$/, '')));
const epoch = (ms) => (ms == null ? nil() : ts(new Date(ms).toISOString().slice(0, 19)));

function badge(kind, label, icon) {
    const icons = { good: '●', warning: '▲', serious: '▲', critical: '■', neutral: '○' };
    return `<span class="badge badge-${kind}"><span class="dot" aria-hidden="true">${icon || icons[kind]}</span>${esc(label)}</span>`;
}

function bar(value, max, formatted) {
    const pct = max > 0 ? Math.max(2, Math.round((value / max) * 100)) : 0;
    return `<div class="bar-cell"><span class="bar-num">${formatted}</span>
            <span class="bar-track" role="img" aria-label="${formatted}"><span class="bar-fill" style="width:${pct}%"></span></span></div>`;
}

function table(cols, rows, opts = {}) {
    if (!rows.length) {
        return `<div class="empty"><div class="empty-icon" aria-hidden="true">◌</div>
              <div>${esc(opts.empty || 'Nothing to show')}</div></div>`;
    }
    return `<div class="table-wrap"><table><thead><tr>${cols
        .map((c) => `<th${c.sticky ? ' class="sticky"' : c.num ? ' class="num"' : ''}${c.width ? ` style="width:${c.width}"` : ''}>${esc(c.label)}</th>`)
        .join('')}</tr></thead><tbody>${rows.join('')}</tbody></table></div>`;
}

function card(title, body, note, flush) {
    return `<div class="card"><div class="card-head"><span class="card-title">${esc(title)}</span>
            ${note ? `<span class="card-note">${esc(note)}</span>` : ''}</div>
          <div class="card-body${flush ? ' flush' : ''}">${body}</div></div>`;
}

function tile(label, value, sub, critical) {
    return `<div class="tile"><div class="tile-label">${esc(label)}</div>
            <div class="tile-value${critical ? ' is-critical' : ''}">${value}</div>
            ${sub ? `<div class="tile-sub">${sub}</div>` : ''}</div>`;
}

function loadingRows(count, widths) {
    return Array.from({ length: count }, () =>
        `<div class="sk-row">${widths.map((w) => `<span class="skeleton" style="width:${w}"></span>`).join('')}</div>`).join('');
}

/*
 * Follows the contract's Error schema, where status and error are required and message is nullable.
 * The adapter withholds detail on 5xx by design, so this must read sensibly with a status alone.
 */
function errorState(err, requiredRole) {
    const map = {
        401: { cls: 'state-401', icon: '▲', title: 'Not signed in',
               detail: 'The request was not authenticated. Sign in to the host application, then reload.' },
        403: { cls: 'state-403', icon: '▲', title: 'Not permitted',
               detail: 'Your roles do not cover this operation.' },
        500: { cls: 'state-5xx', icon: '■', title: 'Server error',
               detail: 'The server failed to answer. No detail is returned on a 5xx by design — check the application logs.' },
        0:   { cls: 'state-5xx', icon: '■', title: 'Cannot reach the server',
               detail: 'The request did not complete. The service may be restarting, or a gateway between you and it is down.' }
    };
    const m = map[err.status] ?? map[500];
    return `<div class="state ${m.cls}">
      <div class="state-icon" aria-hidden="true">${m.icon}</div>
      <div class="state-title">${err.status ? err.status + ' · ' : ''}${esc(m.title)}</div>
      <div class="state-detail">${esc(m.detail)}${err.message ? `<br><span class="mono" style="font-size:12px">${esc(err.message)}</span>` : ''}</div>
      <div class="state-actions">
        <button class="btn btn-sm" data-retry>Retry</button>
        ${err.status === 403 && requiredRole ? `<span class="chip">requires ${esc(requiredRole)}</span>` : ''}
      </div>
    </div>`;
}

/* ── Views ───────────────────────────────────────────────────────────────────────────────────
   Each view is an async function returning HTML. Failures surface as an in-card state rather than
   an empty page, so one failing panel never blanks the console. */
const views = {};

views.overview = async () => {
    const settled = await Promise.allSettled([
        api('/fenced-locks'),
        api('/durable-queues'),
        api('/event-store/subscriptions'),
        api('/event-store/cdc/status'),
        api('/event-store/statistics/table-cache-hit-ratio')
    ]);
    const [locks, queues, subs, cdc, cacheHit] = settled.map((r) => (r.status === 'fulfilled' ? r.value : null));
    const firstError = settled.find((r) => r.status === 'rejected')?.reason;

    const held = locks ? locks.filter((l) => l.currentToken != null).length : null;

    return `
    ${firstError ? `<div class="banner banner-warning"><span aria-hidden="true">▲</span>
      <div>Some panels could not be loaded (${firstError.status || 'unreachable'}). The rest of the dashboard is unaffected.</div></div>` : ''}

    <div class="kpi-row">
      ${tile('Locks held', locks ? `${held} <span class="tile-sub" style="font-size:13px">/ ${locks.length}</span>` : nil())}
      ${tile('Queues', queues ? queues.length : nil())}
      ${tile('Subscriptions', subs ? subs.length : nil())}
      ${tile('CDC', cdc ? badge(cdc.availability.state === 'ACTIVE' ? 'good' : 'warning', cdc.availability.state) : nil(),
             cdc ? 'slot ' + esc(cdc.availability.slotName ?? '—') : '')}
      ${tile('CDC fallbacks', cdc ? num(cdc.availability.fallbackCount) : nil(), 'since start',
             cdc ? cdc.availability.fallbackCount > 0 : false)}
    </div>

    <div class="grid-2">
      ${card('Subscription lag', subs
        ? table([{ label: 'Subscriber' }, { label: 'Aggregate type' }, { label: 'Current global order', num: true }],
                subs.map((s) => `<tr><td>${esc(s.subscriberId)}</td><td>${esc(s.aggregateType)}</td>
                  <td class="num">${num(s.currentGlobalOrder)}</td></tr>`),
                { empty: 'No active subscriptions' })
        : errorState(settled[2].reason, 'essentials_subscription_reader'), 'highest order loaded on demand', true)}

      ${card('Cache hit ratio', cacheHit
        ? Object.entries(cacheHit).map(([t, v]) => `
          <div class="meter-row"><span class="mono">${esc(t)}</span>
            <span class="meter-track" role="img" aria-label="${v.cacheHitRatio}%"><span class="meter-fill" style="width:${v.cacheHitRatio}%"></span></span>
            <span class="meter-val">${v.cacheHitRatio}%</span></div>`).join('') || '<div class="empty">No statistics reported</div>'
        : errorState(settled[4].reason, 'essentials_postgresql_stats_reader'), 'ratio against 100%')}
    </div>`;
};

views.locks = async () => {
    let locks;
    try {
        locks = await api('/fenced-locks');
    } catch (e) {
        return card('Fenced locks', errorState(e, 'essentials_lock_reader'), 'GET /fenced-locks', true);
    }

    const rows = locks.map((l) => {
        const held = l.currentToken != null;
        return `<tr>
      <td class="mono">${esc(l.lockName)}</td>
      <td>${held ? badge('good', 'Held') : badge('neutral', 'Free')}</td>
      <td class="num">${num(l.currentToken)}</td>
      <td>${l.lockedByLockManagerInstanceId ? esc(l.lockedByLockManagerInstanceId) : nil()}</td>
      <td>${ts(l.lockAcquiredTimestamp)}</td>
      <td>${ts(l.lockLastConfirmedTimestamp)}</td>
      <td class="actions"><button class="btn btn-sm btn-danger" data-act="release" data-name="${esc(l.lockName)}"
        ${held && CAN.writeLocks ? '' : 'disabled'}
        ${CAN.writeLocks ? '' : 'title="Requires essentials_lock_writer"'}>Release</button></td>
    </tr>`;
    });

    return card('Fenced locks', table([
        { label: 'Lock name' }, { label: 'Status' }, { label: 'Token', num: true }, { label: 'Held by' },
        { label: 'Acquired' }, { label: 'Last confirmed' }, { label: '', width: '96px', sticky: true }
    ], rows, { empty: 'No fenced locks exist yet' }), 'GET /fenced-locks', true);
};

let queueState = { queue: null, tab: 'queued', sortOrder: 'ASC' };

/* The parameterised aggregate endpoints need an aggregate type and a logical aggregate id. The type comes from the
   statistics endpoints, which report per aggregate type; there is no endpoint listing logical aggregate ids, so that
   one is typed in. generation is set by picking a row. */
let aggregateState = { type: null, logicalId: '', generation: null, archivedGeneration: null, includePayload: false };

views.queues = async () => {
    let names;
    try {
        names = await api('/durable-queues');
    } catch (e) {
        return card('Durable queues', errorState(e, 'essentials_queue_reader'), 'GET /durable-queues', true);
    }
    if (!names.length) return card('Durable queues', '<div class="empty">No queues exist yet</div>', null, true);

    if (!queueState.queue || !names.includes(queueState.queue)) queueState.queue = names[0];
    const q = queueState.queue;
    const dead = queueState.tab === 'dead';
    const sort = queueState.sortOrder;

    /* Both list endpoints are called literally, query string included, rather than assembled from
       fragments — the contract parity gate verifies these paths by static inspection, so anything it
       cannot read is effectively unverified. */
    const settled = await Promise.allSettled([
        dead ? api(`/durable-queues/queues/${encodeURIComponent(q)}/dead-letter-messages?sortOrder=${sort}&startIndex=0&pageSize=100`)
             : api(`/durable-queues/queues/${encodeURIComponent(q)}/messages?sortOrder=${sort}&startIndex=0&pageSize=100`),
        api(`/durable-queues/queues/${encodeURIComponent(q)}/messages/count`),
        api(`/durable-queues/queues/${encodeURIComponent(q)}/dead-letter-messages/count`),
        api(`/durable-queues/queues/${encodeURIComponent(q)}/statistics`)
    ]);
    const [messages, queuedCount, deadCount, stats] = settled.map((r) => (r.status === 'fulfilled' ? r.value : null));

    const msgRow = (m) => `<tr data-msg="${esc(m.id)}">
      <td><button class="link" data-msg="${esc(m.id)}">${esc(String(m.id).slice(0, 18))}…</button></td>
      <td class="truncate mono">${m.payload == null
            ? `<span class="nil" title="Requires essentials_queue_payload_reader">redacted</span>`
            : `<button class="link" data-msg="${esc(m.id)}" title="View full payload">${esc(m.payload)}</button>`}</td>
      <td>${ts(m.addedTimestamp)}</td>
      <td class="num">${m.totalDeliveryAttempts}</td>
      <td class="num">${m.redeliveryAttempts}</td>
      <td class="truncate">${m.lastDeliveryError ? badge('serious', m.lastDeliveryError) : nil()}</td>
      <td>${m.isBeingDelivered ? badge('warning', 'Delivering') : m.isDeadLetterMessage ? badge('critical', 'Dead letter') : badge('neutral', 'Queued')}</td>
      <td class="actions">
        ${m.isDeadLetterMessage
            ? `<button class="btn btn-sm" data-act="resurrect" data-name="${esc(m.id)}" ${CAN.writeQueues ? '' : 'disabled'}>Resurrect</button>`
            : `<button class="btn btn-sm" data-act="dlq" data-name="${esc(m.id)}" ${CAN.writeQueues ? '' : 'disabled'}>Dead-letter</button>`}
        <button class="btn btn-sm btn-danger" data-act="delete" data-name="${esc(m.id)}" ${CAN.writeQueues ? '' : 'disabled'}>Delete</button>
      </td>
    </tr>`;

    const cols = [
        { label: 'Entry id' }, { label: 'Payload' }, { label: 'Added' },
        { label: 'Attempts', num: true }, { label: 'Redel.', num: true }, { label: 'Last error' },
        { label: 'State' }, { label: '', width: '170px', sticky: true }
    ];

    return `
    <div class="toolbar">
      <label>Queue
        <select id="queueSelect">${names.map((n) => `<option ${n === q ? 'selected' : ''}>${esc(n)}</option>`).join('')}</select>
      </label>
      <div class="tabs" role="tablist">
        <button class="tab" role="tab" aria-selected="${!dead}" data-qtab="queued">Queued${queuedCount ? ` (${queuedCount.total})` : ''}</button>
        <button class="tab" role="tab" aria-selected="${dead}" data-qtab="dead">Dead letters${deadCount ? ` (${deadCount.total})` : ''}</button>
      </div>
      <label><input type="text" id="entryLookup" placeholder="Find by queue entry id" size="26"></label>
      <div class="spacer"></div>
      <label>Sort
        <select id="sortSelect">
          <option ${queueState.sortOrder === 'ASC' ? 'selected' : ''}>ASC</option>
          <option ${queueState.sortOrder === 'DESC' ? 'selected' : ''}>DESC</option>
        </select>
      </label>
      <button class="btn btn-sm btn-danger" data-act="purge" data-name="${esc(q)}" ${CAN.writeQueues ? '' : 'disabled'}>Purge queue</button>
    </div>

    <div class="kpi-row">
      ${tile('Queued', queuedCount ? num(queuedCount.total) : nil())}
      ${tile('Dead letters', deadCount ? num(deadCount.total) : nil(), null, deadCount ? deadCount.total > 0 : false)}
      ${tile('Delivered', stats ? num(stats.totalMessagesDelivered) : nil())}
      ${tile('Avg delivery latency', stats ? `${stats.avgDeliveryLatencyMs} <span class="tile-sub" style="font-size:13px">ms</span>` : nil())}
      ${tile('Last delivery', stats ? esc(String(stats.lastDelivery).slice(11, 19)) : nil(),
             stats ? esc(String(stats.lastDelivery).slice(0, 10)) : null)}
    </div>

    ${card(dead ? 'Dead-letter messages' : 'Queued messages',
        messages ? table(cols, messages.map(msgRow), { empty: dead ? 'No dead-letter messages' : 'No queued messages' })
                 : errorState(settled[0].reason, 'essentials_queue_reader'),
        dead ? 'GET /durable-queues/queues/{queueName}/dead-letter-messages'
             : 'GET /durable-queues/queues/{queueName}/messages', true)}`;
};

views.subscriptions = async () => {
    let subs;
    try {
        subs = await api('/event-store/subscriptions');
    } catch (e) {
        return card('Subscriptions', errorState(e, 'essentials_subscription_reader'), 'GET /event-store/subscriptions', true);
    }

    const rows = subs.map((s, i) => `<tr>
      <td>${esc(s.subscriberId)}</td>
      <td><span class="chip">${esc(s.aggregateType)}</span></td>
      <td class="num">${num(s.currentGlobalOrder)}</td>
      <td class="num" id="hi${i}">${nil('not loaded')}</td>
      <td>${ts(s.lastUpdated)}</td>
      <td class="actions"><button class="btn btn-sm" data-hi="${i}" data-agg="${esc(s.aggregateType)}">Load highest</button></td>
    </tr>`);

    return `
    <div class="banner banner-warning">
      <span aria-hidden="true">▲</span>
      <div><strong>Loading the highest global order is expensive.</strong> It scans per aggregate type, so it
      stays on demand per row — calling it frequently affects event-store performance.</div>
    </div>
    ${card('Subscriptions', table([
        { label: 'Subscriber' }, { label: 'Aggregate type' }, { label: 'Current global order', num: true },
        { label: 'Highest persisted', num: true }, { label: 'Last updated' }, { label: '', width: '120px', sticky: true }
    ], rows, { empty: 'No active subscriptions' }), 'GET /event-store/subscriptions', true)}`;
};

views.scheduler = async () => {
    const settled = await Promise.allSettled([
        api('/scheduler/pg-cron-jobs?startIndex=0&pageSize=100'),
        api('/scheduler/pg-cron-jobs/count'),
        api('/scheduler/executor-jobs?startIndex=0&pageSize=100'),
        api('/scheduler/executor-jobs/count')
    ]);
    const [jobs, jobCount, execJobs] = settled.map((r) => (r.status === 'fulfilled' ? r.value : null));

    const jobRows = (jobs ?? []).map((j) => `<tr>
      <td class="num">${j.jobId}</td>
      <td>${esc(j.jobName ?? '—')}</td>
      <td class="mono">${esc(j.schedule)}</td>
      <td class="truncate mono" title="${esc(j.command)}">${esc(j.command)}</td>
      <td>${esc(j.nodeName)}:${j.nodePort}</td>
      <td>${esc(j.database)}</td>
      <td>${j.active ? badge('good', 'Active') : badge('neutral', 'Paused')}</td>
      <td class="actions"><button class="btn btn-sm" data-runs="${j.jobId}" data-job="${esc(j.jobName ?? j.jobId)}">Run details</button></td>
    </tr>`);

    const execRows = (execJobs ?? []).map((e) => `<tr>
      <td>${esc(e.name)}</td>
      <td class="num">${num(e.initialDelay)}</td>
      <td class="num">${num(e.period)}</td>
      <td>${esc(e.unit)}</td>
      <td>${ts(e.scheduledAt)}</td>
    </tr>`);

    return `
    ${card('pg_cron jobs', jobs
        ? table([
            { label: 'Job', num: true }, { label: 'Name' }, { label: 'Schedule' }, { label: 'Command' },
            { label: 'Node' }, { label: 'Database' }, { label: 'State' }, { label: '', width: '110px', sticky: true }
        ], jobRows, { empty: 'pg_cron is not installed or exposes no jobs' })
        : errorState(settled[0].reason, 'essentials_scheduler_reader'),
        jobCount ? `${jobCount.total} total` : 'GET /scheduler/pg-cron-jobs', true)}

    <div id="runDetails"></div>

    ${card('Executor jobs', execJobs
        ? table([
            { label: 'Name' }, { label: 'Initial delay', num: true }, { label: 'Period', num: true },
            { label: 'Unit' }, { label: 'Scheduled at' }
        ], execRows, { empty: 'No executor jobs registered' })
        : errorState(settled[2].reason, 'essentials_scheduler_reader'), 'GET /scheduler/executor-jobs', true)}`;
};

views.postgresql = async () => {
    const settled = await Promise.allSettled([
        api('/postgresql/query-statistics/top-ten-slowest'),
        api('/event-store/statistics/table-sizes'),
        api('/event-store/statistics/table-activity'),
        api('/event-store/statistics/table-cache-hit-ratio')
    ]);
    const [slow, sizes, activity, cacheHit] = settled.map((r) => (r.status === 'fulfilled' ? r.value : null));

    const maxMean = slow?.length ? Math.max(...slow.map((q) => q.meanTime)) : 0;
    const qRows = (slow ?? []).map((q) => `<tr>
      <td class="truncate mono" title="${esc(q.query)}">${esc(q.query)}</td>
      <td class="num">${num(q.calls)}</td>
      <td class="num">${q.totalTime.toLocaleString('en-US', { maximumFractionDigits: 0 })} ms</td>
      <td>${bar(q.meanTime, maxMean, q.meanTime.toFixed(2) + ' ms')}</td>
    </tr>`);

    const mb = (s) => parseFloat(s) || 0;
    const maxSize = sizes ? Math.max(...Object.values(sizes).map((v) => mb(v.totalSize))) : 0;
    const sRows = Object.entries(sizes ?? {}).map(([t, v]) => `<tr>
      <td class="mono">${esc(t)}</td>
      <td>${bar(mb(v.totalSize), maxSize, esc(v.totalSize))}</td>
      <td class="num">${esc(v.tableSize)}</td>
      <td class="num">${esc(v.indexSize)}</td>
    </tr>`);

    const aRows = Object.entries(activity ?? {}).map(([t, v]) => `<tr>
      <td class="mono">${esc(t)}</td>
      <td class="num">${num(v.seq_scan)}</td>
      <td class="num">${num(v.idx_scan)}</td>
      <td class="num">${num(v.idx_tup_fetch)}</td>
      <td class="num">${num(v.n_tup_ins)}</td>
      <td class="num">${num(v.n_tup_upd)}</td>
      <td class="num">${num(v.n_tup_del)}</td>
    </tr>`);

    return `
    ${card('Ten slowest queries', slow
        ? table([{ label: 'Query' }, { label: 'Calls', num: true }, { label: 'Total time', num: true }, { label: 'Mean time', num: true }],
                qRows, { empty: 'pg_stat_statements is not enabled' })
        : errorState(settled[0].reason, 'essentials_postgresql_stats_reader'),
        'GET /postgresql/query-statistics/top-ten-slowest', true)}

    <div class="grid-2">
      ${card('Table sizes', sizes
        ? table([{ label: 'Table' }, { label: 'Total', num: true }, { label: 'Heap', num: true }, { label: 'Indexes', num: true }], sRows)
        : errorState(settled[1].reason, 'essentials_postgresql_stats_reader'), 'table-sizes', true)}

      ${card('Cache hit ratio', cacheHit
        ? Object.entries(cacheHit).map(([t, v]) => {
            const low = v.cacheHitRatio < 90;
            return `<div class="meter-row"><span class="mono">${esc(t)} ${low ? badge('warning', 'low') : ''}</span>
              <span class="meter-track" role="img" aria-label="${v.cacheHitRatio}%"><span class="meter-fill" style="width:${v.cacheHitRatio}%"></span></span>
              <span class="meter-val">${v.cacheHitRatio}%</span></div>`;
          }).join('')
        : errorState(settled[3].reason, 'essentials_postgresql_stats_reader'), 'table-cache-hit-ratio')}
    </div>

    ${card('Table activity', activity
        ? table([
            { label: 'Table' }, { label: 'Seq scans', num: true }, { label: 'Index scans', num: true },
            { label: 'Index tuples', num: true }, { label: 'Inserts', num: true }, { label: 'Updates', num: true }, { label: 'Deletes', num: true }
        ], aRows)
        : errorState(settled[2].reason, 'essentials_postgresql_stats_reader'), 'table-activity', true)}`;
};

views.cdc = async () => {
    let c;
    try {
        c = await api('/event-store/cdc/status');
    } catch (e) {
        return card('Change Data Capture', errorState(e, 'essentials_subscription_reader'), 'GET /event-store/cdc/status', true);
    }

    const a = c.availability;
    const s = c.slot;
    const d = c.dispatcher;
    const stateBadge = { ACTIVE: badge('good', 'ACTIVE'), INACTIVE: badge('neutral', 'INACTIVE'), FAILED: badge('critical', 'FAILED') }[a.state]
        ?? badge('warning', a.state);
    const bool = (v) => (v == null ? nil() : v ? badge('good', 'yes') : badge('neutral', 'no'));

    const kv = (obj, fmt = {}) => `<div class="kv">${Object.entries(obj).map(([k, v]) =>
        `<div class="kv-item"><span class="kv-key">${esc(k)}</span>
       <span class="kv-val">${fmt[k] ? fmt[k](v) : v == null ? nil() : typeof v === 'boolean' ? String(v) : esc(String(v))}</span></div>`).join('')}</div>`;

    return `
    ${a.fallbackCount > 0 ? `<div class="banner banner-warning"><span aria-hidden="true">▲</span>
      <div><strong>CDC has fallen back to polling ${a.fallbackCount === 1 ? 'once' : a.fallbackCount + ' times'}.</strong>
      In <code class="mono">AUTO</code> mode a fallback is silent by design — polling keeps delivering events, so this
      is the only place it surfaces.</div></div>` : ''}

    <div class="kpi-row">
      ${tile('Availability', stateBadge, 'changed ' + epoch(a.lastChangedEpochMs))}
      ${tile('Published events', d ? num(d.publishedEvents) : nil(), d ? 'last batch ' + d.lastBatchSize : 'dispatcher not running')}
      ${tile('Poison rows', d ? num(d.poisonRows) : nil(), d && d.poisonRows > 0 ? 'inspect inbox' : null, d ? d.poisonRows > 0 : false)}
      ${tile('Fallbacks', num(a.fallbackCount), 'since start', a.fallbackCount > 0)}
      ${tile('Slot WAL', `<span class="is-text">${esc(s.walStatus ?? '—')}</span>`,
             s.safeWalSize != null ? 'safe ' + (s.safeWalSize / 1073741824).toFixed(0) + ' GiB' : null)}
    </div>

    <div class="grid-2">
      ${card('Availability', kv({ state: a.state, slotName: a.slotName, reason: a.reason, lastChanged: epoch(a.lastChangedEpochMs), fallbackCount: a.fallbackCount },
        { state: () => stateBadge, lastChanged: (v) => v, reason: (v) => (v == null ? nil('no reason reported') : esc(v)) }))}

      ${card('Replication slot', kv(s, {
        exists: bool, active: bool, expectedPluginMatches: bool, temporary: bool, failover: bool, synced: bool,
        safeWalSize: (v) => num(v), activePid: (v) => num(v),
        invalidationReason: (v) => (v == null ? nil('none') : badge('critical', v))
      }), 'GET /event-store/cdc/status')}
    </div>

    <div class="grid-2">
      ${card('WAL tailer', c.tailer
        ? kv(c.tailer, { slotLockAcquired: bool, started: bool, messagesReceived: (v) => num(v), inboxWrites: (v) => num(v),
                         lastMessageEpochMs: (v) => epoch(v),
                         inboxWriteFailures: (v) => (v > 0 ? badge('critical', num(v)) : num(v)),
                         handlerFailures: (v) => (v > 0 ? badge('critical', num(v)) : num(v)) })
        : `<div class="empty"><div class="empty-icon" aria-hidden="true">◌</div>
             <div><strong>Not running in this instance</strong></div>
             <div class="card-note" style="margin-top:4px">Only the instance holding the slot lock runs the tailer.
             Events still arrive via the inbox dispatcher.</div></div>`,
        c.tailer ? null : 'tailer = null')}

      ${card('Inbox dispatcher', d
        ? kv(d, { started: bool, stopping: bool, ticks: (v) => num(v), publishedEvents: (v) => num(v),
                  lastTickEpochMs: (v) => epoch(v),
                  poisonRows: (v) => (v > 0 ? badge('serious', num(v)) : num(v)),
                  tickFailures: (v) => (v > 0 ? badge('critical', num(v)) : num(v)),
                  conversionFailures: (v) => (v > 0 ? badge('critical', num(v)) : num(v)),
                  gapExtractionFailures: (v) => (v > 0 ? badge('critical', num(v)) : num(v)) })
        : `<div class="empty"><div class="empty-icon" aria-hidden="true">◌</div>
             <div><strong>Not running in this instance</strong></div></div>`,
        d ? null : 'dispatcher = null')}
    </div>

    ${card('Effective configuration', kv(c.configuration, { enabled: bool }), 'reflected from CdcProperties')}`;
};

/* ── Message detail drawer ───────────────────────────────────────────────────────────────────
   Backs GET /durable-queues/messages/{queueEntryId}. A drawer rather than a modal: triaging dead
   letters means stepping through messages, and a modal forces a close/reopen cycle each time. */
function formatPayload(raw) {
    try {
        return { text: JSON.stringify(JSON.parse(raw), null, 2), kind: 'JSON' };
    } catch {
        return { text: raw, kind: 'text' };
    }
}

function payloadField(m) {
    if (m.payload == null) {
        return `<div class="field">
        <div class="field-label"><span>Payload</span><span class="nil">not returned</span></div>
        <div class="banner banner-info" style="margin:0"><span aria-hidden="true">ℹ</span>
          <div>The payload is withheld because the signed-in caller does not hold
          <code class="mono">essentials_queue_payload_reader</code> or <code class="mono">essentials_admin</code>.
          Every other field on the message is still readable.</div></div>
      </div>`;
    }
    const p = formatPayload(m.payload);
    return `<div class="field">
      <div class="field-label">
        <span>Payload <span class="nil">· ${p.kind} · ${new TextEncoder().encode(m.payload).length} bytes</span></span>
        <span class="field-actions"><button class="btn btn-sm" data-copy>Copy</button></span>
      </div>
      <pre class="code" id="payloadCode">${esc(p.text)}</pre>
    </div>`;
}

let drawerPayload = null;

async function openDrawer(id) {
    const drawer = document.getElementById('drawer');
    drawer.innerHTML = `<div class="drawer-head"><div class="drawer-title" id="drawerTitle">Message detail</div>
      <button class="btn btn-sm" id="drawerClose">Close</button></div>
      <div class="drawer-body">${loadingRows(4, ['60%', '90%', '40%', '70%'])}</div>`;
    drawer.classList.add('is-open');
    document.getElementById('scrim').classList.add('is-open');
    document.querySelectorAll('tbody tr[data-msg]').forEach((tr) => tr.classList.toggle('is-selected', tr.dataset.msg === id));

    let m;
    try {
        m = await api(`/durable-queues/messages/${encodeURIComponent(id)}`);
    } catch (e) {
        drawer.querySelector('.drawer-body').innerHTML = errorState(e, 'essentials_queue_reader');
        return;
    }
    drawerPayload = m.payload;

    const state = m.isBeingDelivered ? badge('warning', 'Delivering')
        : m.isDeadLetterMessage ? badge('critical', 'Dead letter') : badge('neutral', 'Queued');
    const kvItem = (k, v) => `<div class="kv-item"><span class="kv-key">${esc(k)}</span><span class="kv-val">${v}</span></div>`;

    drawer.innerHTML = `
    <div class="drawer-head">
      <div><div class="drawer-title" id="drawerTitle">Message detail ${state}</div>
        <div class="drawer-sub mono">${esc(m.id)}</div></div>
      <button class="btn btn-sm" id="drawerClose" aria-label="Close">Close</button>
    </div>
    <div class="drawer-body">
      ${payloadField(m)}
      ${m.lastDeliveryError ? `<div class="field"><div class="field-label"><span>Last delivery error</span></div>
        <pre class="code is-trace">${esc(m.lastDeliveryError)}</pre></div>` : ''}
      <div class="field">
        <div class="field-label"><span>Delivery</span></div>
        <div class="kv" style="grid-template-columns:1fr">
          ${kvItem('queueName', esc(m.queueName))}
          ${kvItem('addedTimestamp', ts(m.addedTimestamp))}
          ${kvItem('nextDeliveryTimestamp', ts(m.nextDeliveryTimestamp))}
          ${kvItem('deliveryTimestamp', ts(m.deliveryTimestamp))}
          ${kvItem('totalDeliveryAttempts', m.totalDeliveryAttempts)}
          ${kvItem('redeliveryAttempts', m.redeliveryAttempts)}
          ${kvItem('isDeadLetterMessage', String(m.isDeadLetterMessage))}
          ${kvItem('isBeingDelivered', String(m.isBeingDelivered))}
        </div>
      </div>
    </div>
    <div class="drawer-foot">
      ${m.isDeadLetterMessage
        ? `<button class="btn btn-sm" data-act="resurrect" data-name="${esc(m.id)}" ${CAN.writeQueues ? '' : 'disabled'}>Resurrect…</button>`
        : `<button class="btn btn-sm" data-act="dlq" data-name="${esc(m.id)}" ${CAN.writeQueues ? '' : 'disabled'}>Mark as dead letter</button>`}
      <div class="spacer"></div>
      <button class="btn btn-sm btn-danger" data-act="delete" data-name="${esc(m.id)}" ${CAN.writeQueues ? '' : 'disabled'}>Delete message</button>
    </div>`;
    document.getElementById('drawerClose').focus();
}

function closeDrawer() {
    document.getElementById('drawer').classList.remove('is-open');
    document.getElementById('scrim').classList.remove('is-open');
    document.querySelectorAll('tbody tr.is-selected').forEach((tr) => tr.classList.remove('is-selected'));
}

/* ── Dialogs ─────────────────────────────────────────────────────────────────────────────── */
let dialogConfirm = null;

function openDialog(opts) {
    dialogConfirm = opts.onConfirm ?? null;
    document.getElementById('modal').innerHTML = `
    <div class="modal-head" id="modalTitle">${esc(opts.title)}</div>
    <div class="modal-body">${opts.body}
      ${opts.requireText ? `<label for="confirmText">Type <strong>${esc(opts.requireText)}</strong> to confirm</label>
        <input type="text" id="confirmText" autocomplete="off" spellcheck="false">` : ''}</div>
    <div class="modal-foot">
      <button class="btn btn-sm" id="dialogCancel">Cancel</button>
      <button class="btn btn-sm ${opts.danger ? 'btn-solid-danger' : ''}" id="dialogConfirm" ${opts.requireText ? 'disabled' : ''}>${esc(opts.confirmLabel)}</button>
    </div>`;
    document.getElementById('modal').classList.add('is-open');
    document.getElementById('modalScrim').classList.add('is-open');

    if (opts.requireText) {
        const input = document.getElementById('confirmText');
        input.addEventListener('input', () => {
            document.getElementById('dialogConfirm').disabled = input.value !== opts.requireText;
        });
        input.focus();
    } else {
        /* Cancel takes focus on a destructive dialog — the safe option should be the default one. */
        document.getElementById(opts.danger ? 'dialogCancel' : 'dialogConfirm').focus();
    }
}

function closeDialog() {
    document.getElementById('modal').classList.remove('is-open');
    document.getElementById('modalScrim').classList.remove('is-open');
    dialogConfirm = null;
}

const actions = {
    release: (name) => ({
        title: 'Release fenced lock?', danger: true, confirmLabel: 'Release lock',
        body: `<p>Releases <code class="mono">${esc(name)}</code>. Whichever instance holds it loses it, and another may
           acquire it immediately — any work in flight under this lock is no longer fenced.</p>`,
        run: () => api(`/fenced-locks/${encodeURIComponent(name)}`, { method: 'DELETE' })
    }),
    delete: (id) => ({
        title: 'Delete message?', danger: true, confirmLabel: 'Delete message',
        body: `<p>Permanently removes <code class="mono">${esc(id)}</code> from its queue. The payload is not
           recoverable afterwards — copy it from the detail drawer first if it may be needed.</p>`,
        run: () => api(`/durable-queues/messages/${encodeURIComponent(id)}`, { method: 'DELETE' })
    }),
    dlq: (id) => ({
        title: 'Mark as dead letter?', danger: false, confirmLabel: 'Mark as dead letter',
        body: `<p>Stops delivery attempts for <code class="mono">${esc(id)}</code> and moves it to the dead-letter
           set. Reversible — it can be resurrected afterwards.</p>`,
        run: () => api(`/durable-queues/messages/${encodeURIComponent(id)}/mark-as-dead-letter`, { method: 'POST' })
    }),
    resurrect: (id) => ({
        title: 'Resurrect dead-letter message', danger: false, confirmLabel: 'Resurrect',
        body: `<p>Re-queues <code class="mono">${esc(id)}</code> for delivery.</p>
           <label for="delay">Delivery delay</label>
           <select id="delay">
             <option value="PT0S">Immediately (PT0S)</option>
             <option value="PT30S">In 30 seconds (PT30S)</option>
             <option value="PT5M">In 5 minutes (PT5M)</option>
             <option value="PT1H">In 1 hour (PT1H)</option>
           </select>`,
        run: () => {
            /* deliveryDelay is required by the contract, so "immediately" is an explicit PT0S. */
            const delay = document.getElementById('delay')?.value ?? 'PT0S';
            return api(`/durable-queues/messages/${encodeURIComponent(id)}/resurrect`, {
                method: 'POST', body: JSON.stringify({ deliveryDelay: delay })
            });
        }
    }),
    purge: (queue) => ({
        title: 'Purge queue?', danger: true, confirmLabel: 'Purge queue', requireText: queue,
        body: `<div class="banner banner-critical" style="margin:0 0 4px"><span aria-hidden="true">■</span>
             <div>Deletes <strong>every</strong> message on <code class="mono">${esc(queue)}</code>, including its dead
             letters. This cannot be undone.</div></div>`,
        run: () => api(`/durable-queues/queues/${encodeURIComponent(queue)}/messages`, { method: 'DELETE' })
    })
};

/* ── Wiring ──────────────────────────────────────────────────────────────────────────────── */

views.aggregates = async () => {
    const settled = await Promise.allSettled([
        api('/aggregate-lifecycle/snapshot-policies'),
        api('/aggregate-lifecycle/closing-books-policies'),
        api('/aggregate-lifecycle-statistics/snapshots'),
        api('/aggregate-lifecycle-statistics/closing-books'),
        api('/aggregate-archive-statistics')
    ]);
    const [snapshotPolicies, closingBooksPolicies, snapshotStats, closingBooksStats, archiveStats] =
        settled.map((r) => (r.status === 'fulfilled' ? r.value : null));
    const failure = (i) => errorState(settled[i].reason, 'essentials_subscription_reader');

    const snapshotPolicyRow = (p) => `<tr>
      <td class="mono">${esc(p.aggregateType)}</td>
      <td class="mono truncate">${esc(p.aggregateImplementationType ?? '')}</td>
      <td>${p.enabled ? badge('good', 'Enabled') : badge('neutral', 'Disabled')}</td>
      <td>${esc(p.mode ?? '')}</td>
      <td class="num">${num(p.everyNEvents)}</td>
      <td>${esc(p.deletionMode ?? '')}</td>
      <td class="num">${num(p.keepLastSnapshots)}</td>
    </tr>`;

    const closingBooksPolicyRow = (p) => `<tr>
      <td class="mono">${esc(p.aggregateType)}</td>
      <td class="mono truncate">${esc(p.aggregateImplementationType ?? '')}</td>
      <td>${p.enabled ? badge('good', 'Enabled') : badge('neutral', 'Disabled')}</td>
      <td>${esc(p.triggerMode ?? '')}</td>
      <td>${esc(p.defaultPolicy ?? '')}</td>
      <td class="num">${num(p.eventThreshold)}</td>
      <td>${esc(p.timeBoundary ?? '')}${p.intervalDays ? esc(` / ${p.intervalDays}d`) : ''}</td>
      <td>${p.zoneId ? esc(p.zoneId) : nil()}</td>
    </tr>`;

    /* timedMetrics, counters and gauges are open maps keyed by metric name, so they are rendered as they arrive
       rather than projected onto fixed columns a future metric would fall outside of. */
    const metrics = (st) => {
        const timed = Object.entries(st.timedMetrics ?? {}).map(([name, m]) =>
            `${esc(name)} ${num(m.count)}<span class="tile-sub" style="font-size:12px"> · avg ${
                m.count > 0 ? num(Math.round(m.totalTimeMs / m.count)) : 0} ms · max ${num(Math.round(m.maxTimeMs))} ms</span>`);
        const counters = Object.entries(st.counters ?? {}).map(([name, v]) => `${esc(name)} ${num(v)}`);
        const gauges   = Object.entries(st.gauges ?? {}).map(([name, v]) => `${esc(name)} ${num(v)}`);
        const all      = [...timed, ...counters, ...gauges];
        return all.length ? all.join('<br>') : nil('no metrics recorded');
    };

    const statsRow = (st) => `<tr>
      <td class="mono">${esc(st.aggregateType)}</td>
      <td class="mono truncate">${esc(st.aggregateImplementationType ?? '')}</td>
      <td>${metrics(st)}</td>
    </tr>`;

    const archiveStatRow = (st) => `<tr>
      <td class="mono">${esc(st.aggregateType)}</td>
      <td class="num">${num(st.archivedGenerationCount)}</td>
      <td class="num">${st.failedGenerationCount > 0
            ? badge('serious', String(st.failedGenerationCount))
            : num(st.failedGenerationCount)}</td>
      <td class="num">${num(st.totalArchivedEventCount)}</td>
      <td>${ts(st.lastArchivedAt)}</td>
    </tr>`;

    return `
    <div class="kpi-row">
      ${tile('Snapshot policies', snapshotPolicies ? num(snapshotPolicies.length) : nil())}
      ${tile('Closing-books policies', closingBooksPolicies ? num(closingBooksPolicies.length) : nil())}
      ${tile('Types with snapshot metrics', snapshotStats ? num(snapshotStats.length) : nil())}
      ${tile('Types with archives', archiveStats ? num(archiveStats.length) : nil())}
    </div>

    ${card('Snapshot policies',
        snapshotPolicies
            ? table([{ label: 'Aggregate type' }, { label: 'Implementation' }, { label: 'State' }, { label: 'Mode' },
                     { label: 'Every N events', num: true }, { label: 'Deletion' }, { label: 'Keep last', num: true }],
                    snapshotPolicies.map(snapshotPolicyRow), { empty: 'No snapshot policies are registered' })
            : failure(0),
        'GET /aggregate-lifecycle/snapshot-policies', true)}

    ${card('Closing-books policies',
        closingBooksPolicies
            ? table([{ label: 'Aggregate type' }, { label: 'Implementation' }, { label: 'State' }, { label: 'Trigger' },
                     { label: 'Default policy' }, { label: 'Event threshold', num: true }, { label: 'Time boundary' },
                     { label: 'Zone' }],
                    closingBooksPolicies.map(closingBooksPolicyRow), { empty: 'No closing-books policies are registered' })
            : failure(1),
        'GET /aggregate-lifecycle/closing-books-policies', true)}

    ${card('Snapshot statistics',
        snapshotStats
            ? table([{ label: 'Aggregate type' }, { label: 'Implementation' }, { label: 'Metrics' }],
                    snapshotStats.map(statsRow), { empty: 'No snapshot metrics have been recorded' })
            : failure(2),
        'GET /aggregate-lifecycle-statistics/snapshots', true)}

    ${card('Closing-books statistics',
        closingBooksStats
            ? table([{ label: 'Aggregate type' }, { label: 'Implementation' }, { label: 'Metrics' }],
                    closingBooksStats.map(statsRow), { empty: 'No closing-books metrics have been recorded' })
            : failure(3),
        'GET /aggregate-lifecycle-statistics/closing-books', true)}

    ${card('Archive statistics',
        archiveStats
            ? table([{ label: 'Aggregate type' }, { label: 'Archived', num: true }, { label: 'Failed', num: true },
                     { label: 'Events', num: true }, { label: 'Last archived' }],
                    archiveStats.map(archiveStatRow), { empty: 'Nothing has been archived' })
            : failure(4),
        'GET /aggregate-archive-statistics', true)}`;
};

/*
 * The per-instance half of the aggregate surface. Aggregate types are offered from the statistics endpoints; the
 * logical aggregate id has to be typed, because the contract exposes no way to enumerate them.
 */
views.aggregateLookup = async () => {
    let types = [];
    try {
        const [snapshotStats, closingBooksStats] = await Promise.all([
            api('/aggregate-lifecycle-statistics/snapshots'),
            api('/aggregate-lifecycle-statistics/closing-books')
        ]);
        types = [...new Set([...snapshotStats, ...closingBooksStats].map((st) => st.aggregateType))].sort();
    } catch (e) {
        return card('Aggregate lookup', errorState(e, 'essentials_subscription_reader'),
                    'GET /aggregate-lifecycle-statistics/snapshots', true);
    }

    if (aggregateState.type && !types.includes(aggregateState.type)) aggregateState.type = null;
    if (!aggregateState.type) aggregateState.type = types[0] ?? null;
    const type      = aggregateState.type;
    const logicalId = aggregateState.logicalId.trim();

    const toolbar = `
    <div class="toolbar">
      <label>Aggregate type
        <select id="aggregateTypeSelect" ${types.length ? '' : 'disabled'}>
          ${types.map((t) => `<option ${t === type ? 'selected' : ''}>${esc(t)}</option>`).join('')}
        </select>
      </label>
      <label><input type="text" id="aggregateIdInput" placeholder="Logical aggregate id" size="30"
                    value="${esc(aggregateState.logicalId)}"></label>
      <label><input type="checkbox" id="includePayloadToggle" ${aggregateState.includePayload ? 'checked' : ''}>
             Include snapshot payload</label>
      <div class="spacer"></div>
      ${aggregateState.generation != null ? `<span class="chip">generation ${esc(String(aggregateState.generation))}</span>` : ''}
    </div>`;

    if (!type) {
        return toolbar + card('Aggregate lookup',
            '<div class="empty">No aggregate type reports snapshot or closing-books activity yet</div>', null, true);
    }
    if (!logicalId) {
        return toolbar + card('Aggregate lookup',
            `<div class="empty"><div class="empty-icon" aria-hidden="true">◌</div>
             <div>Enter a logical aggregate id to inspect its generations, snapshots and archives</div></div>`, null, true);
    }

    const settled = await Promise.allSettled([
        api(`/aggregate-lifecycle/aggregate-types/${encodeURIComponent(type)}/logical-aggregates/${encodeURIComponent(logicalId)}/closing-books-generations`),
        api(`/aggregate-lifecycle/aggregate-types/${encodeURIComponent(type)}/logical-aggregates/${encodeURIComponent(logicalId)}/closing-books-generations/current`),
        api(`/aggregate-lifecycle/aggregate-types/${encodeURIComponent(type)}/aggregates/${encodeURIComponent(logicalId)}/snapshots?includeSnapshotPayload=${aggregateState.includePayload}`),
        api(`/aggregate-archive/aggregate-types/${encodeURIComponent(type)}/logical-aggregates/${encodeURIComponent(logicalId)}/archived-generations`)
    ]);
    const [generations, current, snapshots, archived] = settled.map((r) => (r.status === 'fulfilled' ? r.value : null));
    /* 404 is the contract's answer for "no such generation", which is an outcome here rather than a failure. */
    const notFound = (i) => settled[i].status === 'rejected' && settled[i].reason.status === 404;
    const failure  = (i) => errorState(settled[i].reason, 'essentials_subscription_reader');

    const currentGeneration = current?.generation ?? null;
    const generationRow = (g) => `<tr>
      <td class="num"><button class="link" data-generation="${esc(String(g.generation))}">${esc(String(g.generation))}</button></td>
      <td class="mono truncate">${esc(g.streamAggregateId)}</td>
      <td>${g.state === 'OPEN' || g.generation === currentGeneration ? badge('good', 'Open') : badge('neutral', 'Closed')}</td>
      <td>${ts(g.openedAt)}</td>
      <td>${ts(g.closedAt)}</td>
    </tr>`;

    const snapshotRow = (sn) => `<tr>
      <td class="num">${num(sn.lastIncludedEventOrder)}</td>
      <td class="mono truncate">${esc(sn.aggregateImplementationType ?? '')}</td>
      <td class="truncate mono">${sn.snapshotPayload == null
            ? `<span class="nil" title="Enable 'Include snapshot payload' to load it">not loaded</span>`
            : esc(sn.snapshotPayload)}</td>
    </tr>`;

    const archivedRow = (a) => `<tr>
      <td class="num"><button class="link" data-archived-generation="${esc(String(a.generation))}">${esc(String(a.generation))}</button></td>
      <td>${a.archiveError ? badge('serious', a.status) : badge('good', a.status)}</td>
      <td>${a.format ? esc(a.format) : nil()}</td>
      <td class="num">${num(a.eventCount)}</td>
      <td class="truncate mono">${a.archiveLocation ? esc(a.archiveLocation) : nil()}</td>
      <td>${ts(a.archivedAt)}</td>
    </tr>`;

    let eventStreamCard = '';
    if (aggregateState.generation != null) {
        let stream = null;
        let streamError = null;
        try {
            stream = await api(`/aggregate-lifecycle/aggregate-types/${encodeURIComponent(type)}/logical-aggregates/${encodeURIComponent(logicalId)}/closing-books-generations/${encodeURIComponent(aggregateState.generation)}/event-stream`);
        } catch (e) {
            streamError = e;
        }
        const eventRow = (ev) => `<tr>
          <td class="num">${num(ev.eventOrder)}</td>
          <td class="num">${num(ev.globalEventOrder)}</td>
          <td class="num">${num(ev.eventRevision)}</td>
          <td>${ts(ev.timestamp)}</td>
          <td class="truncate mono">${ev.eventPayload ? esc(ev.eventPayload) : nil()}</td>
        </tr>`;
        eventStreamCard = card(`Event stream · generation ${aggregateState.generation}`
                + (stream?.partialEventStream ? ' · truncated' : ''),
            stream
                ? table([{ label: 'Event order', num: true }, { label: 'Global order', num: true },
                         { label: 'Revision', num: true }, { label: 'Timestamp' }, { label: 'Payload' }],
                        (stream.events ?? []).map(eventRow), { empty: 'The generation holds no events' })
                : streamError?.status === 404
                    ? '<div class="empty">No such generation</div>'
                    : errorState(streamError, 'essentials_subscription_reader'),
            'GET /aggregate-lifecycle/aggregate-types/{aggregateType}/logical-aggregates/{logicalAggregateId}/closing-books-generations/{generation}/event-stream',
            true);
    }

    let archivedDetailCard = '';
    if (aggregateState.archivedGeneration != null) {
        let entry = null;
        let entryError = null;
        try {
            entry = await api(`/aggregate-archive/aggregate-types/${encodeURIComponent(type)}/logical-aggregates/${encodeURIComponent(logicalId)}/archived-generations/${encodeURIComponent(aggregateState.archivedGeneration)}`);
        } catch (e) {
            entryError = e;
        }
        /* Worth its own request rather than reusing the list row: the checksum, the stream aggregate id and above all
           archiveError are what an operator needs when an archive did not complete, and they do not belong in a table. */
        archivedDetailCard = card(`Archive entry · generation ${aggregateState.archivedGeneration}`,
            entry
                ? `<div class="kpi-row">
                     ${tile('Status', entry.archiveError ? badge('serious', entry.status) : badge('good', entry.status))}
                     ${tile('Events', num(entry.eventCount))}
                     ${tile('Format', entry.format ? esc(entry.format) : nil())}
                     ${tile('Archived', ts(entry.archivedAt), entry.closedAt ? `closed ${ts(entry.closedAt)}` : null)}
                   </div>
                   ${table([{ label: 'Field' }, { label: 'Value' }], [
                       `<tr><td>Stream aggregate id</td><td class="mono">${esc(entry.streamAggregateId)}</td></tr>`,
                       `<tr><td>Location</td><td class="mono truncate">${entry.archiveLocation ? esc(entry.archiveLocation) : nil()}</td></tr>`,
                       `<tr><td>Checksum</td><td class="mono truncate">${entry.checksum ? esc(entry.checksum) : nil()}</td></tr>`,
                       `<tr><td>Error</td><td class="mono truncate">${entry.archiveError ? esc(entry.archiveError) : nil('none')}</td></tr>`
                   ])}`
                : entryError?.status === 404
                    ? '<div class="empty">That generation has no archive entry</div>'
                    : errorState(entryError, 'essentials_subscription_reader'),
            'GET /aggregate-archive/aggregate-types/{aggregateType}/logical-aggregates/{logicalAggregateId}/archived-generations/{generation}',
            true);
    }

    return `
    ${toolbar}

    <div class="kpi-row">
      ${tile('Generations', generations ? num(generations.length) : nil())}
      ${tile('Current generation', currentGeneration != null ? num(currentGeneration) : nil(),
             notFound(1) ? 'none open' : null)}
      ${tile('Snapshots', snapshots ? num(snapshots.length) : nil())}
      ${tile('Archived generations', archived ? num(archived.length) : nil())}
    </div>

    ${card('Closing-books generations',
        generations
            ? table([{ label: 'Generation', num: true }, { label: 'Stream aggregate id' }, { label: 'State' },
                     { label: 'Opened' }, { label: 'Closed' }],
                    generations.map(generationRow), { empty: 'No generations exist for this logical aggregate' })
            : failure(0),
        'GET /aggregate-lifecycle/aggregate-types/{aggregateType}/logical-aggregates/{logicalAggregateId}/closing-books-generations', true)}

    ${eventStreamCard}

    ${card('Snapshots',
        snapshots
            ? table([{ label: 'Last included event order', num: true }, { label: 'Implementation' },
                     { label: 'Payload' }],
                    snapshots.map(snapshotRow), { empty: 'No snapshots are stored for this aggregate' })
            : failure(2),
        'GET /aggregate-lifecycle/aggregate-types/{aggregateType}/aggregates/{aggregateId}/snapshots', true)}

    ${card('Archived generations',
        archived
            ? table([{ label: 'Generation', num: true }, { label: 'Status' }, { label: 'Format' },
                     { label: 'Events', num: true }, { label: 'Location' }, { label: 'Archived' }],
                    archived.map(archivedRow), { empty: 'Nothing has been archived for this logical aggregate' })
            : failure(3),
        'GET /aggregate-archive/aggregate-types/{aggregateType}/logical-aggregates/{logicalAggregateId}/archived-generations', true)}

    ${archivedDetailCard}`;
};

const titles = {
    overview: ['Dashboard', 'Current state of the Essentials infrastructure'],
    locks: ['Fenced locks', 'Distributed locks held across service instances'],
    queues: ['Durable queues', 'Queued and dead-letter messages, delivery statistics'],
    scheduler: ['Scheduler', 'pg_cron jobs, run history and executor jobs'],
    subscriptions: ['Subscriptions', 'Event-store subscription resume points'],
    cdc: ['Change Data Capture', 'Replication slot, tailer and dispatcher state'],
    postgresql: ['PostgreSQL statistics', 'Query, size, activity and cache statistics'],
    aggregates: ['Aggregates', 'Snapshot and closing-books policies and statistics'],
    aggregateLookup: ['Aggregate lookup', 'Generations, snapshots and archives of one logical aggregate']
};

let currentView = 'overview';

async function render(view) {
    const host = document.querySelector(`.section[data-section="${view}"]`);
    host.innerHTML = loadingRows(5, ['26%', '18%', '22%', '14%']);
    host.innerHTML = await views[view]();
    document.getElementById('stamp').textContent = 'updated ' + new Date().toTimeString().slice(0, 8);
}

async function show(view) {
    currentView = view;
    document.querySelectorAll('.nav-item').forEach((b) =>
        b.dataset.view === view ? b.setAttribute('aria-current', 'page') : b.removeAttribute('aria-current'));
    document.querySelectorAll('.section').forEach((s) => s.classList.toggle('is-active', s.dataset.section === view));
    document.getElementById('pageTitle').textContent = titles[view][0];
    document.getElementById('pageSub').textContent = titles[view][1];
    await render(view);
}

document.getElementById('nav').addEventListener('click', (e) => {
    const b = e.target.closest('.nav-item');
    if (b && !b.disabled) show(b.dataset.view);
});

document.getElementById('refreshBtn').addEventListener('click', () => render(currentView));

document.getElementById('themeBtn').addEventListener('click', (e) => {
    const dark = document.documentElement.getAttribute('data-theme') === 'dark';
    document.documentElement.setAttribute('data-theme', dark ? 'light' : 'dark');
    e.target.textContent = dark ? 'Dark' : 'Light';
});

document.addEventListener('click', async (e) => {
    if (e.target.closest('#drawerClose') || e.target.closest('#scrim')) return closeDrawer();
    if (e.target.closest('#dialogCancel') || e.target.closest('#modalScrim')) return closeDialog();

    if (e.target.closest('#dialogConfirm')) {
        const fn = dialogConfirm;
        closeDialog();
        await fn?.();
        return;
    }

    if (e.target.closest('[data-copy]')) {
        const btn = e.target.closest('[data-copy]');
        navigator.clipboard?.writeText(drawerPayload ?? '').catch(() => {});
        btn.textContent = 'Copied';
        setTimeout(() => { btn.textContent = 'Copy'; }, 1200);
        return;
    }

    const archivedButton = e.target.closest('[data-archived-generation]');
    if (archivedButton) {
        const picked = Number(archivedButton.dataset.archivedGeneration);
        aggregateState.archivedGeneration = aggregateState.archivedGeneration === picked ? null : picked;
        return render('aggregateLookup');
    }

    const generationButton = e.target.closest('[data-generation]');
    if (generationButton) {
        /* Toggle: clicking the shown generation again collapses the event-stream card. */
        const picked = Number(generationButton.dataset.generation);
        aggregateState.generation = aggregateState.generation === picked ? null : picked;
        return render('aggregateLookup');
    }

    if (e.target.closest('[data-retry]')) return render(currentView);

    const act = e.target.closest('[data-act]');
    if (act && !act.disabled) {
        const spec = actions[act.dataset.act]?.(act.dataset.name);
        if (spec) {
            openDialog({
                ...spec,
                onConfirm: async () => {
                    try {
                        await spec.run();
                    } catch (err) {
                        openDialog({
                            title: 'Operation failed', danger: false, confirmLabel: 'Close',
                            body: errorState(err)
                        });
                        return;
                    }
                    closeDrawer();
                    await render(currentView);
                }
            });
        }
        return;
    }

    /* On-demand highest global order — one call per click, never eagerly. */
    const hi = e.target.closest('[data-hi]');
    if (hi) {
        const cell = document.getElementById('hi' + hi.dataset.hi);
        cell.innerHTML = '<span class="skeleton" style="display:inline-block;width:64px"></span>';
        try {
            const r = await api(`/event-store/aggregate-types/${encodeURIComponent(hi.dataset.agg)}/highest-global-event-order`);
            cell.textContent = num(r.globalEventOrder);
        } catch (err) {
            cell.innerHTML = err.status === 404 ? nil('no events') : badge('critical', String(err.status));
        }
        return;
    }

    const runs = e.target.closest('[data-runs]');
    if (runs) {
        const host = document.getElementById('runDetails');
        host.innerHTML = card('Run details', loadingRows(3, ['20%', '30%', '25%']), null, true);
        try {
            const details = await api(`/scheduler/pg-cron-jobs/${runs.dataset.runs}/run-details?startIndex=0&pageSize=100`);
            const count = await api(`/scheduler/pg-cron-jobs/${runs.dataset.runs}/run-details/count`).catch(() => null);
            host.innerHTML = card(`Run details — job ${esc(runs.dataset.job)}`, table([
                { label: 'Run', num: true }, { label: 'Job', num: true }, { label: 'Status' }, { label: 'Started' },
                { label: 'Ended' }, { label: 'Message' }, { label: 'User' }
            ], details.map((r) => `<tr>
                <td class="num">${r.runId}</td><td class="num">${r.jobId}</td>
                <td>${r.status === 'succeeded' ? badge('good', 'Succeeded') : badge('critical', esc(r.status ?? 'unknown'))}</td>
                <td>${ts(r.startTime)}</td><td>${ts(r.endTime)}</td>
                <td class="truncate" title="${esc(r.returnMessage ?? '')}">${r.returnMessage ? esc(r.returnMessage) : nil()}</td>
                <td>${esc(r.username ?? '—')}</td></tr>`), { empty: 'This job has not run yet' }),
                count ? `${count.total} runs` : null, true);
        } catch (err) {
            host.innerHTML = card('Run details', errorState(err, 'essentials_scheduler_reader'), null, true);
        }
        return;
    }

    const trigger = e.target.closest('[data-msg]');
    if (trigger && !e.target.closest('.actions')) openDrawer(trigger.dataset.msg);
});

document.addEventListener('change', (e) => {
    if (e.target.id === 'aggregateTypeSelect') {
        aggregateState.type = e.target.value;
        aggregateState.generation = null;
        aggregateState.archivedGeneration = null;
        return render('aggregateLookup');
    }
    if (e.target.id === 'includePayloadToggle') {
        aggregateState.includePayload = e.target.checked;
        return render('aggregateLookup');
    }
    if (e.target.id === 'queueSelect') { queueState.queue = e.target.value; render('queues'); }
    if (e.target.id === 'sortSelect') { queueState.sortOrder = e.target.value; render('queues'); }
});

/*
 * Finding a message by entry id when you do not know which queue holds it — the reason
 * GET /durable-queues/messages/{queueEntryId}/queue-name exists. Resolve the owning queue, switch to
 * it, then open the message detail.
 */
document.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' || e.target.id !== 'aggregateIdInput') return;
    aggregateState.logicalId = e.target.value;
    aggregateState.generation = null;
    aggregateState.archivedGeneration = null;
    render('aggregateLookup');
});

document.addEventListener('keydown', async (e) => {
    if (e.key !== 'Enter' || e.target.id !== 'entryLookup') return;
    const id = e.target.value.trim();
    if (!id) return;

    e.target.disabled = true;
    try {
        const owner = await api(`/durable-queues/messages/${encodeURIComponent(id)}/queue-name`);
        if (owner?.queueName && owner.queueName !== queueState.queue) {
            queueState.queue = owner.queueName;
            await render('queues');
        }
        await openDrawer(id);
    } catch (err) {
        openDialog({
            title: err.status === 404 ? 'No such message' : 'Lookup failed',
            danger: false,
            confirmLabel: 'Close',
            body: err.status === 404
                ? `<p>No queue holds a message with entry id <code class="mono">${esc(id)}</code>. It may already have
                   been delivered and removed.</p>`
                : errorState(err, 'essentials_queue_reader')
        });
    } finally {
        const input = document.getElementById('entryLookup');
        if (input) input.disabled = false;
    }
});

document.addEventListener('click', (e) => {
    const tab = e.target.closest('[data-qtab]');
    if (tab) { queueState.tab = tab.dataset.qtab; render('queues'); }
});

document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    /* Innermost layer first: a dialog over the drawer must not close both at once. */
    if (document.getElementById('modal').classList.contains('is-open')) closeDialog();
    else closeDrawer();
});

show('overview');
