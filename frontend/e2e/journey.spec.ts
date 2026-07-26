import { test, expect } from '@playwright/test';

/**
 * The full passenger journey (FRONTEND_MODULE.md Module 19).
 *
 * <p>Register → search → fare → seat → passenger → pay → confirmation → my trips,
 * driven in a real browser end to end. Three of these steps resolve over Kafka
 * (payment row, booking CONFIRMED), so the timeout is generous - the point is
 * that the real asynchronous platform completes, not that it is instant.
 */
const PASSWORD = 'E2ePassw0rd!x';

test('book a flight end to end', async ({ page }) => {
  test.setTimeout(120_000);
  const email = `e2e-journey-${Date.now()}@example.com`;

  // Register (lands signed in).
  await page.goto('/register');
  await page.getByLabel('Full name').fill('Journey Tester');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByRole('banner').getByRole('button', { name: 'Sign out' })).toBeVisible();

  // Search a known-good route (auto-runs from the deep link).
  await page.goto('/search?from=LHR&to=DXB');
  await page.getByRole('button', { name: 'Select flight' }).first().click();

  // Fare: pick the first fare on offer.
  await expect(page.getByRole('heading', { name: /choose your fare/i })).toBeVisible();
  await page.locator('button', { hasText: 'US$' }).first().click();

  // Seat: skip (free auto-assign), then continue.
  await expect(page.getByRole('heading', { name: /choose your seat/i })).toBeVisible();
  await page.getByRole('button', { name: /assign me a seat/i }).click();
  await page.getByRole('button', { name: 'Continue' }).click();

  // Passenger details (name + contact prefilled from the just-created profile).
  await page.getByLabel('First name').fill('Journey');
  await page.getByLabel('Last name').fill('Tester');
  await page.getByLabel('Date of birth').fill('1990-05-01');
  await page.getByLabel('Passport number').fill('P1234567');
  await page.getByLabel('Passport expiry').fill('2032-01-01');
  await page.getByLabel(/accept the fare rules/i).check();

  // Pay - creates the booking, waits for the payment row (Kafka), authorises +
  // captures, then the booking confirms (Kafka again).
  await page.getByRole('button', { name: /Pay US\$/ }).click();

  // Confirmation: the PNR page appears (the reference shows immediately, before
  // the async confirmation resolves). Generous timeout - real Kafka round-trips.
  await expect(page.getByRole('heading', { name: /your booking reference/i })).toBeVisible({
    timeout: 90_000,
  });
  const pnr = (await page.getByText(/SB[A-Z0-9]{4,}/).first().textContent())?.trim() ?? '';
  expect(pnr).toMatch(/^SB[A-Z0-9]{4,}$/);

  // And it appears under this user's owner-scoped My Trips.
  await page.goto('/bookings');
  await expect(page.getByText(pnr).first()).toBeVisible();
});
