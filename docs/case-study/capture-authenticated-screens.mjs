/**
 * Captures the signed-in SkyBook screens for the frontend case study.
 *
 * Your password is read from an environment variable and is never written to
 * disk, never printed, and never leaves your machine.
 *
 *   cd D:\projects\skybook\docs\case-study
 *   set SKYBOOK_EMAIL=praveen.somireddy@gmail.com
 *   set SKYBOOK_PASSWORD=your-password
 *   node capture-authenticated-screens.mjs
 *
 * (PowerShell uses  $env:SKYBOOK_EMAIL="..."  instead of  set  .)
 *
 * Screenshots land in docs/case-study/shots-auth/.
 */
import { chromium } from 'playwright';
import fs from 'fs';

const BASE = process.env.BASE || 'https://145.241.236.180.sslip.io';
const EMAIL = process.env.SKYBOOK_EMAIL;
const PASSWORD = process.env.SKYBOOK_PASSWORD;
const OUT = 'shots-auth';

if (!EMAIL || !PASSWORD) {
  console.error('Set SKYBOOK_EMAIL and SKYBOOK_PASSWORD first. See the header of this file.');
  process.exit(1);
}
fs.mkdirSync(OUT, { recursive: true });

const log = (...a) => console.log(...a);
const shot = async (p, n, full = false) => {
  await p.waitForTimeout(1200);
  await p.screenshot({ path: `${OUT}/${n}.png`, fullPage: full });
  log(`  captured ${n}`);
};
const click = async (p, loc, label, t = 8000) => {
  try {
    const el = typeof loc === 'string' ? p.locator(loc).first() : loc.first();
    await el.waitFor({ state: 'visible', timeout: t });
    await el.scrollIntoViewIfNeeded().catch(() => {});
    await el.click({ timeout: 6000 });
    await p.waitForTimeout(1200);
    log(`    -> ${label}`);
    return true;
  } catch { log(`    skipped: ${label}`); return false; }
};

const run = async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({
    viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 2, ignoreHTTPSErrors: true,
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(25000);

  log('signing in');
  await page.goto(`${BASE}/sign-in`, { waitUntil: 'networkidle' });
  await page.locator('input[type="email"], input[name="email"]').first().fill(EMAIL);
  await page.locator('input[type="password"]').first().fill(PASSWORD);
  await click(page, page.getByRole('button', { name: /log in|sign in/i }), 'submit sign-in');
  await page.waitForTimeout(2500);
  if (page.url().includes('/sign-in')) {
    console.error('Sign-in did not complete — check the credentials and try again.');
    await shot(page, '00_signin_failed');
    await browser.close();
    process.exit(2);
  }
  log('  signed in');

  // ---- my trips
  await page.goto(`${BASE}/bookings`, { waitUntil: 'networkidle' });
  await shot(page, '50_my_trips');
  await shot(page, '50b_my_trips_full', true);

  // ---- booking detail (first trip)
  if (await click(page, page.getByRole('button', { name: /view|manage|details|open/i }), 'open first booking')) {
    await shot(page, '51_booking_detail');
    await shot(page, '51b_booking_detail_full', true);
    // check-in panel and boarding pass, if this trip is inside its window
    await click(page, page.getByRole('button', { name: /check[- ]?in/i }), 'check-in');
    await shot(page, '52_checkin');
    await click(page, page.getByRole('button', { name: /boarding pass|view pass/i }), 'boarding pass');
    await shot(page, '53_boarding_pass');
    await shot(page, '53b_boarding_pass_full', true);
  }

  // ---- profile
  await page.goto(`${BASE}/profile`, { waitUntil: 'networkidle' });
  await shot(page, '54_profile');
  await shot(page, '54b_profile_full', true);

  // ---- admin console (only renders for ROLE_ADMIN)
  await page.goto(`${BASE}/admin`, { waitUntil: 'networkidle' });
  await shot(page, '55_admin');
  await shot(page, '55b_admin_full', true);

  // ---- booking funnel past the auth gate: seats, bags, payment
  log('booking funnel');
  await page.goto(`${BASE}/search?from=LHR&to=DXB&date=2026-08-04&adults=1&children=0&infants=0&cabin=ECONOMY`,
    { waitUntil: 'networkidle' });
  if (await click(page, page.getByRole('button', { name: /^Select$/ }), 'select flight')) {
    await click(page, page.getByRole('button', { name: /^Choose$/ }).nth(1), 'choose fare');
    await shot(page, '60_guests');
    await shot(page, '60b_guests_full', true);
    if (await click(page, page.getByRole('button', { name: /continue|next/i }), 'continue to seats')) {
      await shot(page, '61_seat_map');
      await shot(page, '61b_seat_map_full', true);
      if (await click(page, page.getByRole('button', { name: /continue|next|skip/i }), 'continue to bags')) {
        await shot(page, '62_bags');
        if (await click(page, page.getByRole('button', { name: /continue|next/i }), 'continue to payment')) {
          await shot(page, '63_payment');
          await shot(page, '63b_payment_full', true);
        }
      }
    }
  }
  log('NOTE: no payment was submitted and no booking was created.');

  await browser.close();
  log('done — screenshots are in ' + OUT);
};

run().catch((e) => { console.error('FAILED', e.message); process.exit(1); });
