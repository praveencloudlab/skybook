import { test, expect } from '@playwright/test';
import { openNav, closeNav } from './nav';

/**
 * The full passenger journey (FRONTEND_MODULE.md Module 19).
 *
 * <p>Register → search → fare → seat → passenger → pay → confirmation → my trips,
 * driven in a real browser end to end. Three of these steps resolve over Kafka
 * (payment row, booking CONFIRMED), so the timeout is generous - the point is
 * that the real asynchronous platform completes, not that it is instant.
 */
const PASSWORD = 'E2ePassw0rd!x';

test('book a flight end to end', async ({ page, isMobile }) => {
  test.setTimeout(120_000);
  const email = `e2e-journey-${Date.now()}@example.com`;

  // Register (lands signed in).
  await page.goto('/register');
  await page.getByLabel('Full name').fill('Journey Tester');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  // Signed in: Sign out is in the nav (behind the drawer on a phone). The
  // post-register redirect must settle first - navigation closes the drawer.
  await expect(page).not.toHaveURL(/\/register/);
  await openNav(page, isMobile);
  await expect(page.getByRole('banner').getByRole('button', { name: 'Sign out' })).toBeVisible();
  await closeNav(page, isMobile);

  // Search a known-good route (auto-runs from the deep link). Reload on a
  // miss: the gateway rate-limits bursts, and back-to-back suite runs can
  // trip it - "give it a moment and try again" is the intended client move.
  await expect(async () => {
    await page.goto('/search?from=LHR&to=DXB');
    await expect(page.getByRole('button', { name: 'Select', exact: true }).first()).toBeVisible({
      timeout: 5_000,
    });
  }).toPass({ timeout: 60_000 });
  await page.getByRole('button', { name: 'Select', exact: true }).first().click();

  // Fare: pick the first fare on offer. Matched on the Choose pill, not the
  // price - the price renders in whatever display currency is active.
  await expect(page.getByRole('heading', { name: /choose your fare/i })).toBeVisible();
  await page.getByRole('button', { name: 'Choose', exact: true }).first().click();

  // Passenger details come BEFORE seats in the carrier flow (guests → seats →
  // bags → payment). Name and contact are prefilled from the new profile.
  await page.getByLabel('First name').fill('Journey');
  await page.getByLabel('Last name').fill('Tester');
  await page.getByLabel('Date of birth').fill('1990-05-01');
  await page.getByLabel('Passport number').fill('P1234567');
  await page.getByLabel('Passport expiry').fill('2032-01-01');
  await page.getByLabel('Email address').fill(email);
  await page.getByLabel('Phone').fill('+44 7700 900123');
  await page.getByRole('button', { name: 'Continue' }).click();

  // Seat: skip - "Add seats later" continues with free auto-assign at check-in.
  await expect(page.getByRole('heading', { name: /seat selection/i })).toBeVisible();
  await page.getByRole('button', { name: 'Add seats later' }).click();

  // Bags: none, continue straight through.
  await expect(page.getByRole('heading', { name: /extra baggage/i, level: 1 })).toBeVisible();
  await page.getByRole('button', { name: 'Continue' }).click();

  // Pay - agree to the fare rules, then the booking is created: draft, wait
  // for the payment row (Kafka), authorise + capture, booking confirms.
  await page.getByRole('checkbox').check();
  await page.getByRole('button', { name: 'Pay now' }).click();

  // Confirmation: the PNR page appears (the reference shows immediately, before
  // the async confirmation resolves). Generous timeout - real Kafka round-trips.
  await expect(page.getByRole('heading', { name: /your booking reference/i })).toBeVisible({
    timeout: 90_000,
  });
  const pnr = (await page.getByText(/SB[A-Z0-9]{4,}/).first().textContent())?.trim() ?? '';
  expect(pnr).toMatch(/^SB[A-Z0-9]{4,}$/);

  // And it appears under this user's owner-scoped My Trips. The list is a
  // projection fed over Kafka, so reload until it catches up rather than
  // trusting one fetch taken moments after payment.
  await expect(async () => {
    await page.goto('/bookings');
    await expect(page.getByText(pnr).first()).toBeVisible({ timeout: 3_000 });
  }).toPass({ timeout: 60_000 });
});
