/*
 * Funding (design §3.3): link the bank account, deposit, withdraw, and the payments with their status. The account
 * and the payments are re-read every 5 s; a payment just requested every second until the gateway has answered (§4).
 */
import { api, accountPath, accountPicker, esc, money, time, words, setText, poll, onSubmit, follow, message, newId } from './api.js';

accountPicker();

const accountId = document.body.dataset.accountId;
const SETTLED_STATES = new Set(['COMPLETED', 'FAILED', 'RETURNED']);
let currency = 'USD';
let payments = [];

async function refreshAccount() {
    const overview = await api(accountPath);
    currency = overview.settledCash?.currency || currency;
    setText('settled-cash', money(overview.settledCash));
    setText('uncleared', money(overview.uncleared));
    setText('withdrawable', money(overview.withdrawable));
}

function renderPayments() {
    const body = document.getElementById('payments');
    if (payments.length === 0) {
        body.innerHTML = '<tr><td colspan="8" class="muted">No payments yet.</td></tr>';
        return;
    }
    body.innerHTML = payments.map(p => {
        const tone = p.status === 'COMPLETED' ? 'good' : p.status === 'FAILED' || p.status === 'RETURNED' ? 'bad' : 'live';
        return `<tr>
            <td class="small">${esc(time(p.requestedAt))}</td>
            <td class="${p.kind === 'DEPOSIT' ? 'good' : 'bad'}">${esc(words(p.kind))}</td>
            <td class="mono small">${esc(p.reference)}</td>
            <td class="num">${esc(money(p.amount))}</td>
            <td><span class="badge ${tone}">${esc(words(p.status))}</span></td>
            <td>${esc(p.valueDate ?? '')}</td>
            <td class="mono small">${esc(p.iban ?? '')}</td>
            <td class="small">${esc(p.reason ?? '')}</td>
        </tr>`;
    }).join('');
    const lastIban = payments.find(p => p.iban)?.iban;
    if (lastIban) setText('bank-known', `Most recently used bank account: ${lastIban}. Link another to change it.`);
}

async function refreshPayments() {
    payments = await api(`${accountPath}/payments`);
    renderPayments();
}

function upsert(payment) {
    const index = payments.findIndex(p => p.reference === payment.reference);
    if (index >= 0) payments[index] = payment; else payments.unshift(payment);
    renderPayments();
}

async function followPayment(reference) {
    const last = await follow(() => api(`${accountPath}/payments/${encodeURIComponent(reference)}`), p => SETTLED_STATES.has(p.status),
                              { intervalMillis: 1000, timeoutMillis: 60000, onEach: upsert });
    if (last && SETTLED_STATES.has(last.status)) {
        message(`${words(last.kind)} of ${money(last.amount)}: ${words(last.status).toLowerCase()}${last.reason ? ` - ${last.reason}` : ''}`,
                last.status === 'COMPLETED' ? 'success' : 'error');
    }
    refreshAccount().catch(() => {});
}

onSubmit(document.getElementById('bank-account'), async data => {
    await api(`${accountPath}/bank-account`, { method: 'PUT', body: { accountId, bankAccount: { iban: data.get('iban'), holderName: data.get('holderName') } } });
    message('Bank account linked', 'success');
});

onSubmit(document.getElementById('deposit'), async data => {
    const depositId = newId('DEP');
    await api(`${accountPath}/deposits`, { method: 'POST', body: { depositId, accountId, amount: { amount: Number(data.get('amount')), currency } } });
    message('Deposit requested from your bank');
    document.getElementById('deposit').reset();
    followPayment(depositId);
});

onSubmit(document.getElementById('withdraw'), async data => {
    const withdrawalId = newId('WDR');
    await api(`${accountPath}/withdrawals`, { method: 'POST', body: { withdrawalId, accountId, amount: { amount: Number(data.get('amount')), currency } } });
    message('Withdrawal requested');
    document.getElementById('withdraw').reset();
    refreshAccount().catch(() => {});
    followPayment(withdrawalId);
});

poll(async () => { await Promise.all([refreshAccount(), refreshPayments()]); }, 5000);
