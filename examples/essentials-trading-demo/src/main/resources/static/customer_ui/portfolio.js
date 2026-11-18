/* Portfolio (design §3.1): cash buckets, valuation, positions and recent activity, re-read every 5 s while visible. */
import { api, accountPath, accountPicker, esc, money, qty, signedMoney, setText, poll, words } from './api.js';

accountPicker();

function renderOverview(o) {
    setText('cash-balance', money(o.cashBalance));
    setText('buying-power', money(o.buyingPower));
    setText('withdrawable', money(o.withdrawable));
    document.getElementById('restricted').hidden = !o.restricted;
    const buckets = [
        ['Settled', o.settledCash], ['Pending in', o.pendingCashIn], ['Pending out', o.pendingCashOut],
        ['Held for orders and withdrawals', o.held], ['Uncleared deposits', o.uncleared], ['Realized P&L this period', o.realizedPnlThisPeriod],
    ];
    document.getElementById('buckets').innerHTML = buckets.map(([label, m]) => `<dt>${esc(label)}</dt><dd>${esc(money(m))}</dd>`).join('');
}

function renderValuation(v, positions) {
    setText('total-value', money(v.totalValue));
    setText('market-value', money(v.marketValue));
    setText('valued-on', v.businessDate ? `Positions valued at the closing prices of ${v.businessDate}.` : 'No closing prices yet - positions are valued once the exchange closes a business day.');
    const valued = new Map((v.positions || []).map(p => [p.instrumentId, p]));
    const body = document.getElementById('positions');
    if (positions.length === 0) {
        body.innerHTML = '<tr><td colspan="8" class="muted">No positions. Buy something on the Trade page.</td></tr>';
        return;
    }
    body.innerHTML = positions.map(p => {
        const value = valued.get(p.instrumentId);
        return `<tr>
            <td><strong>${esc(p.instrumentId)}</strong></td>
            <td class="num">${qty(p.held)}</td>
            <td class="num">${qty(p.settled)}</td>
            <td class="num">${qty(p.pendingIn)} / ${qty(p.pendingOut)}</td>
            <td class="num">${qty(p.reserved)}</td>
            <td class="num">${esc(money(p.averageCost))}</td>
            <td class="num">${esc(money(value?.closingPrice))}${value?.pricedOn ? `<div class="muted small">${esc(value.pricedOn)}</div>` : ''}</td>
            <td class="num">${esc(money(value?.value))}</td>
        </tr>`;
    }).join('');
}

function renderActivity(entries, currency) {
    const body = document.getElementById('activity');
    if (entries.length === 0) {
        body.innerHTML = '<tr><td colspan="4" class="muted">Nothing yet.</td></tr>';
        return;
    }
    body.innerHTML = entries.map(e => {
        const effect = e.cashEffect == null ? '' : signedMoney(e.cashEffect, currency);
        const tone = e.cashEffect == null || Number(e.cashEffect) === 0 ? '' : Number(e.cashEffect) > 0 ? 'good' : 'bad';
        return `<tr>
            <td>${esc(words(e.kind))}</td>
            <td class="mono small">${esc(e.reference)}</td>
            <td>${esc(e.description)}</td>
            <td class="num ${tone}">${esc(effect)}</td>
        </tr>`;
    }).join('');
}

poll(async () => {
    const [overview, valuation, positions, activity] = await Promise.all([
        api(accountPath), api(`${accountPath}/valuation`), api(`${accountPath}/positions`), api(`${accountPath}/activity?limit=25`),
    ]);
    renderOverview(overview);
    renderValuation(valuation, positions);
    renderActivity(activity, overview.cashBalance?.currency);
}, 5000);
