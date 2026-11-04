/*
 * Trade (design §3.2): the order ticket beside the instrument's live quote, and the account's orders. Quotes are
 * re-read every second, the orders every 5 s, and an order just placed every 500 ms until it is terminal (§4).
 */
import { api, accountPath, accountPicker, esc, money, qty, time, words, setText, poll, onSubmit, follow, message, newId } from './api.js';

accountPicker();

const TERMINAL = new Set(['FILLED', 'CANCELLED', 'REJECTED', 'EXPIRED']);
const form = document.getElementById('ticket');
const instrumentSelect = form.elements.instrumentId;
let instruments = new Map();
let positions = new Map();
let currency = 'USD';
let orders = [];
const following = new Set();

function selected() {
    return instrumentSelect.value;
}

async function loadInstruments() {
    const list = await api('/api/market-data/instruments');
    instruments = new Map(list.map(i => [i.instrumentId, i]));
    const wanted = new URLSearchParams(window.location.search).get('instrument');
    instrumentSelect.replaceChildren(...list.map(i => {
        const tradable = !i.suspended && i.riskStatus === 'APPROVED';
        const note = i.suspended ? ' (suspended)' : i.riskStatus !== 'APPROVED' ? ` (${words(i.riskStatus)})` : '';
        const option = new Option(`${label(i)}${note}`, i.instrumentId, false, i.instrumentId === wanted);
        option.disabled = !tradable;
        return option;
    }));
    if (!wanted || instrumentSelect.selectedOptions[0]?.disabled) {
        const first = [...instrumentSelect.options].find(o => !o.disabled);
        if (first) instrumentSelect.value = first.value;
    }
    ticketChanged();
}

function ticketChanged() {
    const type = form.elements.type.value;
    const side = form.elements.side.value;
    document.getElementById('limit-price-field').hidden = type !== 'LIMIT';
    form.elements.limitPrice.required = type === 'LIMIT';
    const instrument = instruments.get(selected());
    setText('quote-title', instrument ? label(instrument) : 'Quote');
    setText('sellable', qty(positions.get(selected())?.sellable ?? 0));
    setText('ticket-hint', side === 'SELL'
        ? 'A sell needs sellable shares: held and not reserved by another open sell.'
        : type === 'MARKET' ? 'A market buy holds buying power at the ask plus a margin until it fills.' : 'A limit buy holds the limit price times the quantity until it fills or is cancelled.');
    refreshQuote().catch(() => {});
}

form.addEventListener('change', event => {
    if (['instrumentId', 'type', 'side'].includes(event.target.name)) ticketChanged();
});

async function refreshQuote() {
    const id = selected();
    if (!id) return;
    let quote = null;
    try {
        quote = await api(`/api/market-data/quotes/${encodeURIComponent(id)}`);
    } catch (error) {
        if (error.status !== 404) throw error;
    }
    const sessions = await api('/api/market-data/sessions').catch(() => ({}));
    if (id !== selected()) return;
    showSession(sessions[id]);
    setText('quote-bid', money(quote?.bid));
    setText('quote-ask', money(quote?.ask));
    setText('quote-last', money(quote?.last));
    const state = document.getElementById('quote-state');
    state.className = 'small ' + (quote == null || quote.stale ? 'warn' : 'muted');
    state.textContent = quote == null && sessions[id] === 'CLOSED' ? 'No quote while the market is closed: the exchange quotes again at the open.'
        : quote == null ? 'No quote yet - a market order needs one.'
        : quote.stale ? 'Stale quote: the exchange has not sent a price for a while.'
        : `Live, received ${time(quote.receivedAt)}`;
    if (form.elements.type.value === 'LIMIT' && !form.elements.limitPrice.value && quote) {
        const price = form.elements.side.value === 'BUY' ? quote.ask : quote.bid;
        if (price?.amount != null) form.elements.limitPrice.value = Number(price.amount).toFixed(2);
    }
}

/**
 * The exchange's trading session (09:30-16:00 New York on its clock): outside it the exchange rejects new orders, so
 * the ticket says so and does not offer to place one. No session reported means a market that is always open.
 */
function showSession(session) {
    const note = document.getElementById('market-session');
    const closed = session === 'CLOSED';
    note.hidden = session == null;
    note.className = 'small ' + (closed ? 'warn' : 'good');
    note.textContent = closed ? 'Market closed: the exchange takes orders from the open, 09:30 New York on its clock.' : 'Market open.';
    document.getElementById('place').disabled = closed;
}

async function refreshAccount() {
    const [overview, held] = await Promise.all([api(accountPath), api(`${accountPath}/positions`)]);
    currency = overview.buyingPower?.currency || currency;
    setText('buying-power', money(overview.buyingPower));
    positions = new Map(held.map(p => [p.instrumentId, p]));
    setText('sellable', qty(positions.get(selected())?.sellable ?? 0));
}

function renderOrders() {
    const body = document.getElementById('orders');
    if (orders.length === 0) {
        body.innerHTML = '<tr><td colspan="10" class="muted">No orders yet.</td></tr>';
        return;
    }
    const sorted = [...orders].sort((a, b) => (TERMINAL.has(a.status) - TERMINAL.has(b.status)) || String(b.placedAt).localeCompare(String(a.placedAt)));
    body.innerHTML = sorted.map(o => {
        const cancellable = !TERMINAL.has(o.status) && o.status !== 'CANCEL_PENDING';
        const tone = o.status === 'FILLED' ? 'good' : o.status === 'REJECTED' ? 'bad' : TERMINAL.has(o.status) ? 'neutral' : 'live';
        return `<tr class="${following.has(o.orderId) ? 'following' : ''}">
            <td class="small">${esc(time(o.placedAt))}</td>
            <td><strong>${esc(o.instrumentId)}</strong></td>
            <td class="${o.side === 'BUY' ? 'good' : 'bad'}">${esc(words(o.side))}</td>
            <td>${esc(words(o.type))} <span class="muted small">${esc(o.timeInForce)}</span></td>
            <td class="num">${qty(o.quantity)}</td>
            <td class="num">${esc(o.limitPrice ? money(o.limitPrice) : '')}</td>
            <td class="num">${qty(o.filledQuantity)}</td>
            <td class="num">${esc(o.averageFillPrice ? money(o.averageFillPrice) : '')}</td>
            <td><span class="badge ${tone}">${esc(words(o.status))}</span>${o.reason ? `<div class="muted small">${esc(reasonText(o.reason))}</div>` : ''}</td>
            <td>${cancellable ? `<button type="button" class="small-button" data-cancel="${esc(o.orderId)}">Cancel</button>` : ''}</td>
        </tr>`;
    }).join('');
}

function upsert(order) {
    const index = orders.findIndex(o => o.orderId === order.orderId);
    if (index >= 0) orders[index] = order; else orders.unshift(order);
    renderOrders();
}

/** "AAPL - Apple Inc.", or just "AAPL" when the name is the symbol. */
function label(i) {
    return i.displayName && i.displayName !== i.symbol ? `${i.symbol} - ${i.displayName}` : i.symbol;
}

/** A reason code such as REQUESTED reads as words; free text is shown as it is. */
function reasonText(reason) {
    return /^[A-Z_]+$/.test(reason) ? words(reason).toLowerCase() : reason;
}

async function refreshOrders() {
    orders = await api(`${accountPath}/orders`);
    renderOrders();
}

/** Follows one order every 500 ms until it is terminal, or settles in a resting state for a while. */
async function followOrder(orderId) {
    if (following.has(orderId)) return; // already followed; that follow reports the outcome
    following.add(orderId);
    const path = `${accountPath}/orders/${encodeURIComponent(orderId)}`;
    try {
        const last = await follow(() => api(path), o => TERMINAL.has(o.status), { intervalMillis: 500, timeoutMillis: 20000, onEach: upsert });
        if (last && TERMINAL.has(last.status)) {
            message(`${last.instrumentId} ${words(last.side).toLowerCase()} ${qty(last.quantity)}: ${words(last.status).toLowerCase()}${last.reason ? ` - ${reasonText(last.reason)}` : ''}`,
                    last.status === 'FILLED' ? 'success' : last.status === 'REJECTED' ? 'error' : 'info');
            refreshAccount().catch(() => {});
        }
    } finally {
        following.delete(orderId);
        renderOrders();
    }
}

onSubmit(form, async data => {
    const type = data.get('type');
    const orderId = newId('WEB');
    const body = {
        orderId,
        accountId: document.body.dataset.accountId,
        instrumentId: data.get('instrumentId'),
        side: data.get('side'),
        type,
        quantity: Number(data.get('quantity')),
        limitPrice: type === 'LIMIT' ? { amount: Number(data.get('limitPrice')), currency } : null,
        timeInForce: data.get('timeInForce'),
    };
    await api(`${accountPath}/orders`, { method: 'POST', body });
    message(`Order placed: ${words(body.side).toLowerCase()} ${body.quantity} ${body.instrumentId}`, 'info');
    followOrder(orderId);
});

document.getElementById('orders').addEventListener('click', async event => {
    const orderId = event.target.closest('[data-cancel]')?.dataset.cancel;
    if (!orderId) return;
    event.target.disabled = true;
    try {
        await api(`${accountPath}/orders/${encodeURIComponent(orderId)}/cancellation`, { method: 'POST' });
        message('Cancellation sent to the exchange');
        followOrder(orderId);
    } catch (error) {
        event.target.disabled = false;
        message(error.message, 'error', 10000);
    }
});

loadInstruments().catch(error => message(error.message, 'error', 10000));
poll(refreshQuote, 1000);
poll(async () => { await Promise.all([refreshOrders(), refreshAccount()]); }, 5000);
