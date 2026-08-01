/*
 * Captures a reference screenshot of every view, so look-and-feel changes are reviewable as a diff.
 *
 *   npm i playwright-core && npx playwright install chromium
 *   node screenshots/capture.mjs screenshots
 *
 * Note: there is no Google Chrome build for Linux ARM64, so the bundled `chromium` is used rather
 * than the `chrome` channel.
 */
import { chromium } from 'playwright-core';

const url = 'file:///workspace/docs/openapi/admin-ui-mockup/index.html';
const out = process.argv[2] ?? '.';
const views = ['locks', 'queues', 'subscriptions', 'scheduler', 'postgresql', 'cdc', 'states'];

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const errors = [];
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });

await page.goto(url);
await page.waitForTimeout(400);
await page.screenshot({ path: `${out}/overview.png`, fullPage: true });

for (const view of views) {
  await page.click(`[data-view="${view}"]`);
  await page.waitForTimeout(350);
  await page.screenshot({ path: `${out}/${view}.png`, fullPage: true });
}

// Message detail drawer: a readable payload, a payload withheld by role, and a dead letter with a
// large payload plus a stack trace — the three cases whose sizes actually stress the layout.
await page.click('[data-view="queues"]');
await page.waitForTimeout(300);
await page.click('tbody tr:nth-child(1) .link');
await page.waitForTimeout(350);
await page.screenshot({ path: `${out}/drawer-payload.png` });

await page.keyboard.press('Escape');
await page.waitForTimeout(200);
await page.click('#qQueued tbody tr:nth-child(3) .link');
await page.waitForTimeout(350);
await page.screenshot({ path: `${out}/drawer-withheld.png` });

await page.keyboard.press('Escape');
await page.waitForTimeout(200);
await page.click('[data-qtab="dead"]');
await page.waitForTimeout(250);
await page.click('#qDead tbody tr:nth-child(1) .link');
await page.waitForTimeout(350);
await page.screenshot({ path: `${out}/drawer-dead-letter.png` });

// Destructive confirmations: the typed-confirmation case and the delay form.
await page.keyboard.press('Escape');
await page.click('[data-view="states"]');
await page.waitForTimeout(300);
await page.click('[data-section="states"] [data-act="purge"]');
await page.waitForTimeout(300);
await page.screenshot({ path: `${out}/dialog-purge.png` });

await page.keyboard.press('Escape');
await page.waitForTimeout(200);
await page.click('[data-section="states"] [data-act="resurrect"]');
await page.waitForTimeout(300);
await page.screenshot({ path: `${out}/dialog-resurrect.png` });
await page.keyboard.press('Escape');

// Dark mode on the richest surface.
await page.keyboard.press('Escape');
await page.click('[data-view="cdc"]');
await page.click('#themeBtn');
await page.waitForTimeout(300);
await page.screenshot({ path: `${out}/cdc-dark.png`, fullPage: true });

console.log(`captured ${views.length + 7} screenshots · console errors: ${errors.length}`);
if (errors.length) { console.error(errors.join('\n')); process.exitCode = 1; }
await browser.close();
