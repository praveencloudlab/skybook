import { test, expect, type Page } from '@playwright/test';

/**
 * Phone-viewport layout checks (runs under the `mobile` project only).
 *
 * <p>The functional specs already run on both projects; what they cannot see
 * is layout. The classic mobile regression is horizontal overflow - one
 * too-wide card and the whole page pans sideways - so every key public screen
 * is loaded at phone width and must fit the viewport exactly. Wide content is
 * allowed to scroll INSIDE its own container, never as the page body.
 */
async function expectNoHorizontalOverflow(page: Page, label: string) {
  const overflow = await page.evaluate(() => {
    const root = document.scrollingElement ?? document.documentElement;
    return root.scrollWidth - root.clientWidth;
  });
  // 1px of slack for subpixel rounding; anything more is a real overflow.
  expect(overflow, `${label} overflows the viewport by ${overflow}px`).toBeLessThanOrEqual(1);
}

test.describe('phone viewport', () => {
  test.skip(({ isMobile }) => !isMobile, 'mobile project only');

  test('key public pages fit the viewport with no sideways pan', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { name: /where would you like to fly/i })).toBeVisible();
    await expectNoHorizontalOverflow(page, 'landing');

    // Reload on a miss - the gateway rate-limits bursts during suite runs.
    await expect(async () => {
      await page.goto('/search?from=LHR&to=JFK');
      await expect(page.getByRole('button', { name: 'Select', exact: true }).first()).toBeVisible({
        timeout: 5_000,
      });
    }).toPass({ timeout: 60_000 });
    await expectNoHorizontalOverflow(page, 'search results');

    await page.goto('/sign-in');
    await expect(page.getByLabel('Email')).toBeVisible();
    await expectNoHorizontalOverflow(page, 'sign-in');

    await page.goto('/register');
    await expect(page.getByLabel('Full name')).toBeVisible();
    await expectNoHorizontalOverflow(page, 'register');

    await page.goto('/check-in');
    await expectNoHorizontalOverflow(page, 'guest check-in');
  });

  test('header navigation works by touch', async ({ page }) => {
    await page.goto('/');
    // tap() goes through the touchscreen, not the mouse - proves the drawer
    // opens and its links are actually reachable on a phone.
    await page.getByRole('banner').getByRole('button', { name: 'Open menu' }).tap();
    await page.getByRole('banner').getByRole('link', { name: 'Check-in' }).tap();
    await expect(page).toHaveURL(/\/check-in/);
    // Navigation must close the drawer behind itself.
    await expect(page.getByRole('banner').getByRole('link', { name: 'Check-in' })).toBeHidden();
  });
});
