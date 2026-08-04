/**
 * Captures the signed-in SkyBook screens the case study needs.
 *
 * The earlier script stopped at the payment page without paying, so it never
 * produced a booking - and with no booking there is no trip, no e-ticket, no
 * boarding pass, no check-in and nothing to cancel. This one pays, then
 * photographs everything that only exists afterwards.
 *
 *   cd docs/case-study
 *   BASE=http://localhost:3000 \
 *   SKYBOOK_EMAIL=... SKYBOOK_PASSWORD=... \
 *   node capture-all-screens.mjs
 *
 * Point it at a throwaway account on a local stack. It creates and cancels
 * real bookings.
 */
import { chromium } from 'playwright';
import fs from 'fs';

const BASE = process.env.BASE || 'http://localhost:3000';
const MAILPIT = process.env.MAILPIT || 'http://localhost:8025';
const EMAIL = process.env.SKYBOOK_EMAIL;
const PASSWORD = process.env.SKYBOOK_PASSWORD;
const OUT = 'shots-auth';

if (!EMAIL || !PASSWORD) {
  console.error('Set SKYBOOK_EMAIL and SKYBOOK_PASSWORD.');
  process.exit(1);
}
fs.mkdirSync(OUT, { recursive: true });

const captured = [];
const skipped = [];
const log = (...a) => console.log(...a);

const shot = async (p, name, full = false) => {
  await p.waitForTimeout(900);
  await p.screenshot({ path: `${OUT}/${name}.png`, fullPage: full });
  captured.push(name);
  log(`    captured ${name}`);
};

/** Clicks if present. Never throws - a screen that isn't reachable is reported, not fatal. */
const click = async (p, loc, label, timeout = 7000) => {
  try {
    const el = typeof loc === 'string' ? p.locator(loc).first() : loc.first();
    await el.waitFor({ state: 'visible', timeout });
    await el.scrollIntoViewIfNeeded().catch(() => {});
    await el.click({ timeout: 5000 });
    await p.waitForTimeout(1100);
    log(`      -> ${label}`);
    return true;
  } catch {
    log(`      skipped: ${label}`);
    skipped.push(label);
    return false;
  }
};

const section = async (name, fn) => {
  log(`\n== ${name}`);
  try {
    await fn();
  } catch (e) {
    log(`   !! ${name} failed: ${e.message}`);
    skipped.push(`${name} (${e.message})`);
  }
};

const isoInDays = (days) => {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return d.toISOString().slice(0, 10);
};

const signIn = async (page) => {
  await page.goto(`${BASE}/sign-in`, { waitUntil: 'networkidle' });
  await page.locator('input[type="email"], input[name="email"]').first().fill(EMAIL);
  await page.locator('input[type="password"]').first().fill(PASSWORD);
  await click(page, page.getByRole('button', { name: /log in|sign in/i }), 'submit sign-in');
  await page.waitForTimeout(2500);

  // Judged by the header, not by the URL. A successful log in leaves the
  // browser on /sign-in - the session is established and the nav switches to
  // the signed-in links, but there is no redirect. Testing the URL therefore
  // reported failure on a login that had in fact worked.
  try {
    await page.getByRole('link', { name: /sign out/i })
      .or(page.getByRole('button', { name: /sign out/i }))
      .first()
      .waitFor({ state: 'visible', timeout: 8000 });
    return true;
  } catch {
    return false;
  }
};

/**
 * Moves the funnel on one step. Deliberately matches Continue/Next EXACTLY:
 * several steps also carry a "Skip ..." affordance that selects an option
 * rather than advancing, and a loose match clicks that instead and stalls.
 */
const advance = async (page, label) =>
  (await click(page, page.getByRole('button', { name: /^continue$/i }), label))
  || (await click(page, page.getByRole('button', { name: /^next$/i }), label, 3000));

/** Walks search -> results -> fare -> guests -> seats -> bags -> payment, shooting each. */
const bookingFunnel = async (page, from, to, dayOffset, prefix) => {
  await page.goto(`${BASE}/search?from=${from}&to=${to}`, { waitUntil: 'networkidle' });
  for (const input of await page.locator('input[type="date"]').all()) {
    await input.fill(isoInDays(dayOffset)).catch(() => {});
  }
  await shot(page, `${prefix}_search_filled`);
  await click(page, 'button[type="submit"]', 'run search');
  await page.waitForTimeout(2500);
  await shot(page, `${prefix}_results`, true);

  if (!(await click(page, page.getByRole('button', { name: /^Select$/ }), 'select flight'))) return false;
  await shot(page, `${prefix}_fares`, true);
  if (!(await click(page, page.getByRole('button', { name: /^Choose$/ }).first(), 'choose fare'))) return false;

  await shot(page, `${prefix}_guests`, true);

  // Every required field, individually. Filling all the date inputs in one
  // sweep put 1990 into passport expiry as well as date of birth, so the form
  // sat on "Passport has expired" and Continue never advanced - which is why an
  // earlier run photographed the guests page under all the later names.
  const fill = async (label, value) => {
    for (const sel of [page.getByLabel(label, { exact: false }),
                       page.locator(`input[name*="${label.split(' ')[0]}" i]`)]) {
      try {
        await sel.first().fill(value, { timeout: 3000 });
        return true;
      } catch { /* try the next strategy */ }
    }
    log(`      could not fill ${label}`);
    return false;
  };

  await fill('First name', 'Case');
  await fill('Last name', 'Study');
  await fill('Date of birth', '1990-05-04');
  await fill('Passport number', 'X1234567');
  await fill('Passport expiry', isoInDays(1200));
  await shot(page, `${prefix}_guests_filled`, true);

  await advance(page, 'continue to seats');
  await shot(page, `${prefix}_seat_map`, true);

  // "Skip - assign me a seat (free)" only CHOOSES the free option; the step
  // still moves on the Continue button. Matching /skip/ first meant clicking a
  // toggle over and over while the funnel sat on the seat map, so every later
  // screenshot was really this page under another name.
  await click(page, page.getByRole('button', { name: /skip .*assign|add seats later/i }), 'take a free seat');
  await advance(page, 'continue past seats');
  await shot(page, `${prefix}_bags`, true);
  await advance(page, 'continue to payment');
  await shot(page, `${prefix}_payment`, true);
  return true;
};

/**
 * Opens the first trip in the list. The card is a full-width button whose
 * content IS the route and PNR - it carries no 'View' or 'Manage' label, so
 * matching on an accessible name finds nothing and every screen behind it
 * (e-ticket, modify, cancel, boarding pass) goes uncaptured.
 */
const openFirstTrip = async (page) => {
  for (const loc of [page.locator('button.group.w-full'),
                     page.locator('main button').filter({ hasText: /[A-Z]{3}/ })]) {
    if (await click(page, loc, 'open trip card')) return true;
  }
  return false;
};

/** Accepts the terms gate and pays, producing a real booking. */
const payNow = async (page, prefix) => {
  for (const box of await page.locator('input[type="checkbox"]').all()) {
    await box.check().catch(() => {});
  }
  await shot(page, `${prefix}_payment_terms_accepted`);
  const paid = await click(page, page.getByRole('button', { name: /^pay\b|pay now|pay .*\d/i }), 'pay', 12000);
  if (!paid) return false;
  await page.waitForTimeout(6000);
  await shot(page, `${prefix}_confirmation`, true);
  return true;
};

const run = async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({
    viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 2, ignoreHTTPSErrors: true,
  });
  const page = await ctx.newPage();
  page.setDefaultTimeout(25000);

  // ---- unauthenticated states first, while there is no session to lose
  await section('unauthorised access to a protected route', async () => {
    await page.goto(`${BASE}/bookings`, { waitUntil: 'networkidle' });
    await shot(page, '70_unauthenticated_bookings_redirect', true);
    await page.goto(`${BASE}/admin`, { waitUntil: 'networkidle' });
    await shot(page, '71_unauthenticated_admin_redirect', true);
  });

  await section('rejected sign-in', async () => {
    await page.goto(`${BASE}/sign-in`, { waitUntil: 'networkidle' });
    await page.locator('input[type="email"]').first().fill(EMAIL);
    await page.locator('input[type="password"]').first().fill('WrongPassword#123');
    await click(page, page.getByRole('button', { name: /log in|sign in/i }), 'submit bad credentials');
    await shot(page, '72_signin_rejected', true);
  });

  await section('empty-field validation', async () => {
    await page.goto(`${BASE}/sign-in`, { waitUntil: 'networkidle' });
    await click(page, page.getByRole('button', { name: /log in|sign in/i }), 'submit empty form');
    await shot(page, '73_signin_validation');
  });

  log('\nsigning in');
  if (!(await signIn(page))) {
    log('sign-in failed - stopping');
    await shot(page, '00_signin_failed');
    await browser.close();
    process.exit(2);
  }
  log('  signed in');

  // ---- a far-out booking: cancellation and date change act on this one
  let booked = false;
  await section('booking funnel (far-out trip)', async () => {
    if (await bookingFunnel(page, 'LHR', 'DXB', 30, '60')) {
      booked = await payNow(page, '60');
    }
  });

  await section('my trips', async () => {
    await page.goto(`${BASE}/bookings`, { waitUntil: 'networkidle' });
    await shot(page, '50_my_trips', true);
  });

  await section('booking detail, e-ticket, modify and cancel', async () => {
    // Each panel is a modal, and an open one intercepts every later click - the
    // run that lost modify and cancel had simply never dismissed the e-ticket.
    // Escape closes them regardless of how each one labels its close control.
    const dismiss = async () => { await page.keyboard.press('Escape'); await page.waitForTimeout(800); };

    await page.goto(`${BASE}/bookings`, { waitUntil: 'networkidle' });
    await openFirstTrip(page);
    await shot(page, '51_booking_detail', true);

    await click(page, page.getByRole('button', { name: /e-?tickets?/i }), 'e-ticket');
    await shot(page, '52_eticket', true);
    await dismiss();

    // The page's own labels: "Change flight, dates or bags" opens the modify
    // dialog (titled Modify booking), "Cancel booking..." opens the refund
    // dialog, inside which live "Cancel passengers" / "Cancel entire booking".
    await click(page, page.getByRole('button', { name: /change flight, dates or bags/i }), 'modify booking');
    await shot(page, '53_modify_booking_date_change', true);
    await dismiss();

    await click(page, page.getByRole('button', { name: /cancel booking/i }), 'cancel booking dialog');
    await shot(page, '55_cancellation_refund_preview', true);
    if (await click(page, page.getByRole('button', { name: /cancel passengers/i }), 'passenger-level view', 4000)) {
      await shot(page, '54_cancel_passengers', true);
    }
    await dismiss();
  });

  // ---- a near booking so check-in is inside its window
  await section('booking funnel (imminent trip, for check-in)', async () => {
    if (await bookingFunnel(page, 'DXB', 'MAN', 0, '65')) {
      await payNow(page, '65');
    }
  });

  await section('check-in and boarding pass', async () => {
    await page.goto(`${BASE}/bookings`, { waitUntil: 'networkidle' });
    await openFirstTrip(page);
    await shot(page, '56_checkin', true);
    // One click does it - the Check in button acts immediately, no confirm step.
    await click(page, page.getByRole('button', { name: /^check[- ]?in$/i }), 'check in');
    await page.waitForTimeout(2500);
    await shot(page, '57_checkin_complete', true);
    await click(page, page.getByRole('button', { name: /boarding pass|view pass|get pass/i }).or(page.getByRole('link', { name: /boarding pass/i })), 'boarding pass');
    await shot(page, '58_boarding_pass', true);
  });

  await section('profile', async () => {
    await page.goto(`${BASE}/profile`, { waitUntil: 'networkidle' });
    await shot(page, '59_profile', true);
  });

  await section('administration console', async () => {
    await page.goto(`${BASE}/admin`, { waitUntil: 'networkidle' });
    await shot(page, '80_admin_overview', true);
    // The console's real tab set, in its own order.
    const tabs = [['flights', /^flights$/i], ['bookings', /^bookings$/i],
                  ['payments', /^payments$/i], ['gateops', /^gate ops$/i], ['fleet', /^fleet$/i]];
    for (const [i, [slug, name]] of tabs.entries()) {
      if (await click(page, page.getByRole('button', { name }).or(page.getByRole('tab', { name })), `admin ${slug}`)) {
        await shot(page, `8${i + 1}_admin_${slug}`, true);
      }
    }
  });

  await section('expired session', async () => {
    // Dropping the cookie is what an expired token looks like to the SPA.
    await ctx.clearCookies();
    await page.goto(`${BASE}/bookings`, { waitUntil: 'networkidle' });
    await shot(page, '74_session_expired_redirect', true);
  });

  await section('confirmation email', async () => {
    const mail = await ctx.newPage();
    await mail.goto(MAILPIT, { waitUntil: 'networkidle' });
    await shot(mail, '90_mailpit_inbox', true);
    await click(mail, mail.locator('.message, [class*="message"], tr').first(), 'open newest email');
    await shot(mail, '91_confirmation_email', true);
    await mail.close();
  });

  await browser.close();
  log(`\ncaptured ${captured.length} screenshot(s) into ${OUT}/`);
  if (skipped.length) {
    log(`skipped ${skipped.length}:`);
    skipped.forEach((s) => log(`  - ${s}`));
  }
};

run().catch((e) => { console.error('FAILED', e.message); process.exit(1); });
