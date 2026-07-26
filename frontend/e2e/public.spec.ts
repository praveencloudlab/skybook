import { test, expect } from '@playwright/test';

/**
 * Public browsing + protected routes (FRONTEND_MODULE.md Module 19).
 *
 * <p>The headline of the frontend module: search is public, booking is gated.
 * These prove an anonymous visitor can browse and price trips, and that private
 * pages send them to sign in.
 */
test.describe('public browsing', () => {
  test('landing shows the hero and an in-page search', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { name: /where would you like to fly/i })).toBeVisible();
    // Anonymous: the header CTA is Log in, not a signed-in nav.
    await expect(page.getByRole('banner').getByRole('link', { name: 'Log in' })).toBeVisible();
  });

  test('anyone can search flights without logging in', async ({ page }) => {
    await page.goto('/search?from=LHR&to=JFK');
    // Auto-run from the deep link lands on results with at least one flight.
    await expect(page.getByRole('button', { name: 'Select flight' }).first()).toBeVisible();
    // Still anonymous.
    await expect(page.getByRole('banner').getByRole('link', { name: 'Log in' })).toBeVisible();
  });

  test('a protected page redirects an anonymous visitor to sign in', async ({ page }) => {
    await page.goto('/bookings');
    await expect(page).toHaveURL(/\/sign-in/);
  });

  test('unknown routes render the 404 page', async ({ page }) => {
    await page.goto('/no-such-page');
    await expect(page.getByRole('heading', { name: /page not found/i })).toBeVisible();
  });
});
