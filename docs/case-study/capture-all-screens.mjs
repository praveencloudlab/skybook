/**
 * Case-study capture v3 - every public and authenticated SkyBook screen,
 * at 2x device scale with animations disabled, named to match
 * docs/case-study/frontend/. Lives in frontend/ so 'playwright' resolves.
 *
 *   SKYBOOK_EMAIL=... SKYBOOK_PASSWORD=... node capture_v3.mjs
 */
import { chromium } from 'playwright';
import fs from 'fs';

const BASE = process.env.BASE || 'http://localhost:3000';
const MAILPIT = process.env.MAILPIT || 'http://localhost:8025';
const EMAIL = process.env.SKYBOOK_EMAIL;
const PASSWORD = process.env.SKYBOOK_PASSWORD;
const OUT = process.env.OUT || 'shots-v3';
fs.mkdirSync(OUT, { recursive: true });

const captured = [], skipped = [];
const log = (...a) => console.log(...a);
// Transitions frozen for crisp frames, and the STICKY HEADER pinned static:
// Playwright stitches full-page shots while scrolling, and a sticky header
// repaints mid-image (it once landed in the middle of the check-in shots).
const FREEZE = '*,*::before,*::after{transition:none!important;animation:none!important;caret-color:transparent!important} header{position:static!important}';

const mk = (page) => ({
  shot: async (name, full = false) => {
    await page.waitForTimeout(900);
    await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: full });
    captured.push(name); log(`    captured ${name}`);
  },
  click: async (loc, label, timeout = 7000) => {
    try {
      const el = typeof loc === 'string' ? page.locator(loc).first() : loc.first();
      await el.waitFor({ state: 'visible', timeout });
      await el.scrollIntoViewIfNeeded().catch(() => {});
      await el.click({ timeout: 5000 });
      await page.waitForTimeout(1100);
      log(`      -> ${label}`); return true;
    } catch { log(`      skipped: ${label}`); skipped.push(label); return false; }
  },
});

const section = async (name, fn) => {
  log(`\n== ${name}`);
  try { await fn(); } catch (e) { log(`   !! ${name} failed: ${e.message}`); skipped.push(`${name} (${e.message})`); }
};

const isoInDays = (days) => { const d = new Date(); d.setDate(d.getDate() + days); return d.toISOString().slice(0, 10); };

const newPage = async (ctx) => {
  const page = await ctx.newPage();
  page.setDefaultTimeout(25000);
  await page.addStyleTag?.catch?.(() => {});
  page.on('load', () => page.addStyleTag({ content: FREEZE }).catch(() => {}));
  return page;
};

const run = async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({
    viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 2,
    ignoreHTTPSErrors: true, reducedMotion: 'reduce',
  });
  const page = await newPage(ctx);
  const { shot, click } = mk(page);

  // ---------------- PUBLIC ----------------
  await section('landing', async () => {
    await page.goto(`${BASE}/`, { waitUntil: 'networkidle' });
    await shot('01_landing'); await shot('01b_landing_full', true);
  });

  await section('search form + panels', async () => {
    await page.goto(`${BASE}/search`, { waitUntil: 'networkidle' });
    for (const input of await page.locator('input[type="date"]').all()) {
      await input.fill(isoInDays(21)).catch(() => {});
    }
    await shot('02_search_form'); await shot('02b_search_form_full', true);
    if (await click(page.getByRole('button', { name: /guest|traveller|passenger|adult/i }), 'guests & cabin panel', 4000)) {
      await shot('03_guests_cabin_panel');
      await page.keyboard.press('Escape'); await page.waitForTimeout(500);
    }
    const origin = page.locator('input[placeholder*="rom" i], input[name*="from" i], input[aria-label*="from" i]').first();
    try {
      await origin.click(); await origin.fill(''); await origin.pressSequentially('Lon', { delay: 90 });
      await page.waitForTimeout(1200); await shot('05_airport_typeahead');
      await page.keyboard.press('Escape');
    } catch { skipped.push('airport typeahead'); }
  });

  await section('public results, calendar, fares', async () => {
    await page.goto(`${BASE}/search?from=LHR&to=DXB&date=${isoInDays(21)}&adults=1&children=0&infants=0&cabin=ECONOMY`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(2500);
    await shot('06_results'); await shot('06b_results_full', true);
    await shot('04_fare_calendar');
    if (await click(page.getByRole('button', { name: /^Select$/ }), 'select flight')) {
      await shot('07_fares'); await shot('07b_fares_full', true);
      if (await click(page.getByRole('button', { name: /^Choose$/ }).first(), 'choose fare')) {
        await shot('08_after_fare'); await shot('08b_after_fare_full', true);
        await shot('09_next_step'); await shot('09b_next_step_full', true);
      }
    }
  });

  await section('round trip + multi-city search', async () => {
    await page.goto(`${BASE}/search?from=LHR&to=DXB&date=${isoInDays(21)}&returnDate=${isoInDays(28)}&adults=1&children=0&infants=0&cabin=ECONOMY`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(2500);
    await shot('42_round_trip', true);
    await page.goto(`${BASE}/search`, { waitUntil: 'networkidle' });
    if (await click(page.getByRole('button', { name: /multi.?city/i }).or(page.getByRole('tab', { name: /multi.?city/i })), 'multi-city mode', 5000)) {
      await shot('43_multi_city', true);
    }
  });

  await section('static auth pages', async () => {
    await page.goto(`${BASE}/sign-in`, { waitUntil: 'networkidle' }); await shot('10_sign_in');
    await page.goto(`${BASE}/register`, { waitUntil: 'networkidle' }); await shot('11_register');
    await page.goto(`${BASE}/forgot-password`, { waitUntil: 'networkidle' }); await shot('12_forgot_password');
    await page.goto(`${BASE}/reset-password?token=demo-token`, { waitUntil: 'networkidle' }); await shot('13_reset_password');
    await page.goto(`${BASE}/definitely-not-a-page`, { waitUntil: 'networkidle' }); await shot('14_not_found');
    await page.goto(`${BASE}/bookings`, { waitUntil: 'networkidle' }); await shot('15_bookings_redirect');
  });

  await section('i18n and currency', async () => {
    await page.goto(`${BASE}/`, { waitUntil: 'networkidle' });
    if (await click(page.getByRole('button', { name: /^(EN|English|عربي|AR)\b/i }).or(page.locator('[aria-label*="language" i]')), 'language switcher', 5000)) {
      if (await click(page.getByRole('button', { name: /العربية|Arabic/i }).or(page.getByText(/العربية/)), 'Arabic', 5000)) {
        await page.waitForTimeout(1500); await shot('40_rtl_arabic', true);
        await click(page.getByRole('button', { name: /English/i }).or(page.getByText(/English/)), 'back to English', 5000);
      }
    }
    await page.goto(`${BASE}/search?from=LHR&to=DXB&date=${isoInDays(21)}&adults=1&children=0&infants=0&cabin=ECONOMY`, { waitUntil: 'networkidle' });
    if (await click(page.getByRole('button', { name: /^(GBP|£)\b/i }).or(page.locator('[aria-label*="currency" i]')), 'currency switcher', 5000)) {
      if (await click(page.getByRole('button', { name: /USD|\$/i }).or(page.getByText(/^USD/)), 'USD', 5000)) {
        await page.waitForTimeout(1800); await shot('41_currency_switched', true);
      }
    }
  });

  await section('mobile', async () => {
    const mctx = await browser.newContext({
      viewport: { width: 390, height: 844 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true, reducedMotion: 'reduce',
    });
    const m = await newPage(mctx);
    const ms = mk(m);
    await m.goto(`${BASE}/`, { waitUntil: 'networkidle' }); await ms.shot('20_mobile_landing', true);
    await m.goto(`${BASE}/search`, { waitUntil: 'networkidle' }); await ms.shot('21_mobile_search', true);
    await m.goto(`${BASE}/search?from=LHR&to=DXB&date=${isoInDays(21)}&adults=1&children=0&infants=0&cabin=ECONOMY`, { waitUntil: 'networkidle' });
    await m.waitForTimeout(2500); await ms.shot('44_mobile_results', true);
    await mctx.close();
  });

  // ---------------- SIGN IN ----------------
  await section('unauthorised + rejected sign-in', async () => {
    await page.goto(`${BASE}/bookings`, { waitUntil: 'networkidle' });
    await shot('70_unauthenticated_bookings_redirect', true);
    await page.goto(`${BASE}/admin`, { waitUntil: 'networkidle' });
    await shot('71_unauthenticated_admin_redirect', true);
    await page.goto(`${BASE}/sign-in`, { waitUntil: 'networkidle' });
    await page.locator('input[type="email"]').first().fill(EMAIL);
    await page.locator('input[type="password"]').first().fill('WrongPassword#123');
    await click(page.getByRole('button', { name: /log in|sign in/i }), 'submit bad credentials');
    await shot('72_signin_rejected', true);
    await page.goto(`${BASE}/sign-in`, { waitUntil: 'networkidle' });
    await click(page.getByRole('button', { name: /log in|sign in/i }), 'submit empty form');
    await shot('73_signin_validation');
  });

  log('\nsigning in');
  await page.goto(`${BASE}/sign-in`, { waitUntil: 'networkidle' });
  await page.locator('input[type="email"], input[name="email"]').first().fill(EMAIL);
  await page.locator('input[type="password"]').first().fill(PASSWORD);
  await click(page.getByRole('button', { name: /log in|sign in/i }), 'submit sign-in');
  await page.waitForTimeout(2500);
  // Success is judged by the signed-in header, never the URL (no redirect).
  try {
    await page.getByRole('link', { name: /sign out/i }).or(page.getByRole('button', { name: /sign out/i }))
      .first().waitFor({ state: 'visible', timeout: 8000 });
  } catch { log('sign-in failed - stopping'); await shot('00_signin_failed'); await browser.close(); process.exit(2); }
  log('  signed in');

  const advance = async (label) =>
    (await click(page.getByRole('button', { name: /^continue$/i }), label))
    || (await click(page.getByRole('button', { name: /^next$/i }), label, 3000));

  const fill = async (label, value) => {
    for (const sel of [page.getByLabel(label, { exact: false }),
                       page.locator(`input[name*="${label.split(' ')[0]}" i]`)]) {
      try { await sel.first().fill(value, { timeout: 3000 }); return true; } catch { /* next */ }
    }
    log(`      could not fill ${label}`); return false;
  };

  const bookingFunnel = async (from, to, dayOffset, prefix) => {
    await page.goto(`${BASE}/search?from=${from}&to=${to}`, { waitUntil: 'networkidle' });
    for (const input of await page.locator('input[type="date"]').all()) {
      await input.fill(isoInDays(dayOffset)).catch(() => {});
    }
    await shot(`${prefix}_search_filled`);
    await click('button[type="submit"]', 'run search');
    await page.waitForTimeout(2500);
    await shot(`${prefix}_results`, true);
    if (!(await click(page.getByRole('button', { name: /^Select$/ }), 'select flight'))) return false;
    await shot(`${prefix}_fares`, true);
    if (!(await click(page.getByRole('button', { name: /^Choose$/ }).first(), 'choose fare'))) return false;
    await shot(`${prefix}_guests`, true);
    // Every required field individually (a blanket date sweep once poisoned
    // passport expiry with the DOB). Contact phone is MANDATORY since the
    // contact-card revision - without it Continue never advances.
    await fill('First name', 'Case');
    await fill('Last name', 'Study');
    await fill('Date of birth', '1990-05-04');
    await fill('Passport number', 'X1234567');
    await fill('Passport expiry', isoInDays(1200));
    // Mandatory since the per-passenger email revision ("Passenger email"
    // label - distinct from the contact section's "Email address").
    await fill('Passenger email', 'casestudy.demo@skybook.test');
    await fill('Contact phone', '+447700900123');
    for (const tel of await page.locator('input[type="tel"], input[name*="phone" i]').all()) {
      await tel.fill('+447700900123').catch(() => {});
    }
    await shot(`${prefix}_guests_filled`, true);
    await advance('continue to seats');
    await shot(`${prefix}_seat_map`, true);
    // "Skip - assign me a seat (free)" only selects; Continue advances.
    await click(page.getByRole('button', { name: /skip .*assign|add seats later/i }), 'take a free seat');
    await advance('continue past seats');
    await shot(`${prefix}_bags`, true);
    await advance('continue to payment');
    await shot(`${prefix}_payment`, true);
    return true;
  };

  const openFirstTrip = async () => {
    for (const loc of [page.locator('button.group.w-full'),
                       page.locator('main button').filter({ hasText: /[A-Z]{3}/ })]) {
      if (await click(loc, 'open trip card')) return true;
    }
    return false;
  };

  const payNow = async (prefix) => {
    for (const box of await page.locator('input[type="checkbox"]').all()) {
      await box.check().catch(() => {});
    }
    await shot(`${prefix}_payment_terms_accepted`);
    const paid = await click(page.getByRole('button', { name: /^pay\b|pay now|pay .*\d/i }), 'pay', 12000);
    if (!paid) return false;
    await page.waitForTimeout(6000);
    await shot(`${prefix}_confirmation`, true);
    return true;
  };

  await section('guests validation states', async () => {
    if (await bookingFunnel('LHR', 'DXB', 30, '60')) {
      await payNow('60');
    }
  });

  await section('guests empty-submit validation', async () => {
    await page.goto(`${BASE}/search?from=LHR&to=DXB`, { waitUntil: 'networkidle' });
    for (const input of await page.locator('input[type="date"]').all()) {
      await input.fill(isoInDays(32)).catch(() => {});
    }
    await click('button[type="submit"]', 'run search');
    await page.waitForTimeout(2500);
    if (await click(page.getByRole('button', { name: /^Select$/ }), 'select flight')
        && await click(page.getByRole('button', { name: /^Choose$/ }).first(), 'choose fare')) {
      await shot('30_guests_step'); await shot('30b_guests_step_full', true);
      await advance('submit empty guests');
      await shot('31_guests_validation'); await shot('31b_guests_validation_full', true);
    }
  });

  await section('my trips + filters', async () => {
    await page.goto(`${BASE}/bookings`, { waitUntil: 'networkidle' });
    await shot('50_my_trips', true);
  });

  await section('booking detail, e-ticket, seat change, modify, cancel', async () => {
    const dismiss = async () => { await page.keyboard.press('Escape'); await page.waitForTimeout(800); };
    await page.goto(`${BASE}/bookings`, { waitUntil: 'networkidle' });
    await openFirstTrip();
    await shot('51_booking_detail', true);
    // The e-ticket is a DOWNLOAD (printable.ts saves an HTML file), not a
    // modal - clicking and shooting the page just re-photographed the detail
    // screen. Save the download and photograph the rendered document itself.
    try {
      const dl = page.waitForEvent('download', { timeout: 10000 });
      await page.getByRole('button', { name: /download e-?ticket/i }).first().click();
      const download = await dl;
      const file = (await import('path')).resolve(OUT, 'eticket_download.html');
      await download.saveAs(file);
      const doc = await newPage(ctx);
      await doc.goto('file:///' + file.replace(/\\/g, '/'), { waitUntil: 'load' });
      await doc.waitForTimeout(1200);
      await doc.screenshot({ path: `${OUT}/52_eticket.png`, fullPage: true });
      captured.push('52_eticket'); log('    captured 52_eticket (rendered download)');
      await doc.close();
    } catch { skipped.push('e-ticket download render'); }
    await dismiss();
    if (await click(page.getByRole('button', { name: /change seat/i }), 'seat change dialog', 5000)) {
      await shot('47_seat_change_dialog', true);
      await dismiss();
    }
    await click(page.getByRole('button', { name: /change flight, dates or bags/i }), 'modify booking');
    await shot('53_modify_booking_date_change', true);
    await dismiss();
    await click(page.getByRole('button', { name: /cancel booking/i }), 'cancel booking dialog');
    await shot('55_cancellation_refund_preview', true);
    if (await click(page.getByRole('button', { name: /cancel passengers/i }), 'passenger-level view', 4000)) {
      await shot('54_cancel_passengers', true);
    }
    await dismiss();
  });

  await section('booking funnel (imminent trip, for check-in)', async () => {
    if (await bookingFunnel('DXB', 'MAN', 0, '65')) {
      await payNow('65');
    }
  });

  await section('check-in and boarding pass', async () => {
    await page.goto(`${BASE}/bookings`, { waitUntil: 'networkidle' });
    await openFirstTrip();
    await shot('56_checkin', true);
    await click(page.getByRole('button', { name: /^check[- ]?in$/i }), 'check in');
    await page.waitForTimeout(2500);
    await shot('57_checkin_complete', true);
    // The boarding pass is a DOWNLOAD (printable.ts), not a modal - shooting
    // the page after the click just re-photographed the detail screen.
    // Photograph the rendered document itself, like the e-ticket.
    try {
      const dl = page.waitForEvent('download', { timeout: 10000 });
      await page.getByRole('button', { name: /boarding pass|view pass|get pass/i })
        .or(page.getByRole('link', { name: /boarding pass/i })).first().click();
      const download = await dl;
      const file = (await import('path')).resolve(OUT, 'boardingpass_download.html');
      await download.saveAs(file);
      const doc = await newPage(ctx);
      await doc.goto('file:///' + file.replace(/\\/g, '/'), { waitUntil: 'load' });
      await doc.waitForTimeout(1200);
      await doc.screenshot({ path: `${OUT}/58_boarding_pass.png`, fullPage: true });
      captured.push('58_boarding_pass'); log('    captured 58_boarding_pass (rendered download)');
      await doc.close();
    } catch { skipped.push('boarding pass download render'); }
  });

  await section('profile + price alerts', async () => {
    await page.goto(`${BASE}/profile`, { waitUntil: 'networkidle' });
    await shot('59_profile', true);
    for (const path of ['/alerts', '/price-alerts']) {
      await page.goto(`${BASE}${path}`, { waitUntil: 'networkidle' }).catch(() => {});
      if (!(await page.getByText(/not found|404/i).first().isVisible().catch(() => false))) {
        await shot('45_price_alerts', true);
        break;
      }
    }
  });

  await section('administration console', async () => {
    await page.goto(`${BASE}/admin`, { waitUntil: 'networkidle' });
    await shot('80_admin_overview', true);
    const tabs = [['flights', /^flights$/i], ['bookings', /^bookings$/i],
                  ['payments', /^payments$/i], ['gateops', /^gate ops$/i], ['fleet', /^fleet$/i]];
    for (const [i, [slug, name]] of tabs.entries()) {
      if (await click(page.getByRole('button', { name }).or(page.getByRole('tab', { name })), `admin ${slug}`)) {
        await shot(`8${i + 1}_admin_${slug}`, true);
      }
    }
  });

  await section('expired session', async () => {
    await ctx.clearCookies();
    await page.goto(`${BASE}/bookings`, { waitUntil: 'networkidle' });
    await shot('74_session_expired_redirect', true);
  });

  await section('confirmation email', async () => {
    const mail = await newPage(ctx);
    const ms = mk(mail);
    await mail.goto(MAILPIT, { waitUntil: 'networkidle' });
    await ms.shot('90_mailpit_inbox', true);
    await ms.click(mail.locator('.message, [class*="message"], tr').first(), 'open newest email');
    await ms.shot('91_confirmation_email', true);
    await mail.close();
  });

  await browser.close();
  log(`\ncaptured ${captured.length} screenshot(s) into ${OUT}/`);
  if (skipped.length) { log(`skipped ${skipped.length}:`); skipped.forEach((s) => log(`  - ${s}`)); }
};

run().catch((e) => { console.error('FAILED', e.message); process.exit(1); });
