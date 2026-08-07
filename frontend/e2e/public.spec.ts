import { test, expect } from '@playwright/test';
import { openNav, closeNav } from './nav';

/**
 * Public browsing + protected routes (FRONTEND_MODULE.md Module 19).
 *
 * <p>The headline of the frontend module: search is public, booking is gated.
 * These prove an anonymous visitor can browse and price trips, and that private
 * pages send them to sign in.
 */
test.describe('public browsing', () => {
  test('landing shows the hero and an in-page search', async ({ page, isMobile }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { name: /where would you like to fly/i })).toBeVisible();
    // Anonymous: the nav CTA is Sign in, not a signed-in menu.
    await openNav(page, isMobile);
    await expect(page.getByRole('banner').getByRole('link', { name: 'Sign in' })).toBeVisible();
    await closeNav(page, isMobile);
  });

  test('anyone can search flights without logging in', async ({ page, isMobile }) => {
    // Auto-run from the deep link lands on results with at least one flight.
    // Reload on a miss - the gateway rate-limits bursts during suite runs.
    await expect(async () => {
      await page.goto('/search?from=LHR&to=JFK');
      await expect(page.getByRole('button', { name: 'Select', exact: true }).first()).toBeVisible({
        timeout: 5_000,
      });
    }).toPass({ timeout: 60_000 });
    // Still anonymous.
    await openNav(page, isMobile);
    await expect(page.getByRole('banner').getByRole('link', { name: 'Sign in' })).toBeVisible();
    await closeNav(page, isMobile);
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
