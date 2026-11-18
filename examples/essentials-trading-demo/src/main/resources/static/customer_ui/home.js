/* /app: the caller's accounts, and opening one. With an account and no ?open, straight to its portfolio. */
import { api, esc, money, newId, onSubmit, failure, follow, message } from './api.js';

const userId = document.body.dataset.userId;
const choosing = new URLSearchParams(window.location.search).has('open');

async function load() {
    const accounts = await api('/api/accounts');
    if (accounts.length > 0 && !choosing) {
        window.location.replace(`/app/accounts/${encodeURIComponent(accounts[0].accountId)}/portfolio`);
        return;
    }
    document.getElementById('accounts-loading').hidden = true;
    document.getElementById('no-accounts').hidden = accounts.length > 0;
    const list = document.getElementById('accounts');
    list.hidden = accounts.length === 0;
    list.innerHTML = accounts.map(a => `
        <li><a href="/app/accounts/${encodeURIComponent(a.accountId)}/portfolio">${esc(a.accountId)}</a>
            <span class="muted">cash ${esc(money(a.cashBalance))}, buying power ${esc(money(a.buyingPower))}</span></li>`).join('');
}

onSubmit(document.getElementById('open-account'), async () => {
    const accountId = newId();
    await api('/api/accounts', { method: 'POST', body: { accountId, ownerId: userId, currency: 'USD' } });
    message('Account opened - waiting for it to appear…');
    // The account's overview is a projection: it appears a moment after the command returns
    const opened = await follow(() => api(`/api/accounts/${encodeURIComponent(accountId)}`), account => account != null, { intervalMillis: 300, timeoutMillis: 15000 });
    window.location.assign(`/app/accounts/${encodeURIComponent(accountId)}/${opened ? 'funding' : 'portfolio'}`);
});

load().catch(failure);
