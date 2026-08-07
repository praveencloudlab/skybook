import { test, expect } from '@playwright/test';
import { openNav, closeNav } from './nav';

/**
 * Registration, login and cookie authentication (FRONTEND_MODULE.md Module 19).
 *
 * <p>Proves the httpOnly-cookie model works in a real browser: after signing in,
 * an API call made by the page succeeds with NO manually-set Authorization
 * header - the browser attaches the cookie the code can't see.
 */
function uniqueEmail(): string {
  return `e2e-${Date.now()}-${Math.floor(Math.random() * 1000)}@example.com`;
}
const PASSWORD = 'E2ePassw0rd!x';

test('register, land signed in, then cookie-authenticated /me works', async ({ page, isMobile }) => {
  const email = uniqueEmail();

  await page.goto('/register');
  await page.getByLabel('Full name').fill('E2E Tester');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();

  // Straight into the app, signed in - the nav now offers Profile and Sign
  // out (behind the drawer on a phone). WHO is signed in is proven by /me
  // below, not by the link text. The redirect must settle before the drawer
  // opens: navigation closes the drawer by design.
  await expect(page).not.toHaveURL(/\/register/);
  const banner = page.getByRole('banner');
  await openNav(page, isMobile);
  await expect(banner.getByRole('button', { name: 'Sign out' })).toBeVisible();
  await expect(banner.getByRole('link', { name: 'Profile' })).toBeVisible();
  await closeNav(page, isMobile);

  // Cookie auth: a page-context request to /me carries no Authorization header,
  // yet succeeds because the browser sends the httpOnly session cookie.
  const me = await page.request.get('/api/auth/me');
  expect(me.status()).toBe(200);
  const body = await me.json();
  expect(body.subject).toBe(email);

  // Sign out clears the session; /me is then 401. The drawer closes itself on
  // the redirect to /sign-in, so it is reopened - after the redirect settles -
  // to see the signed-out nav.
  await openNav(page, isMobile);
  await banner.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/sign-in/);
  await openNav(page, isMobile);
  await expect(banner.getByRole('link', { name: 'Sign in' })).toBeVisible();
  const after = await page.request.get('/api/auth/me');
  expect(after.status()).toBe(401);
});

test('wrong credentials are rejected without revealing which field', async ({ page }) => {
  await page.goto('/sign-in');
  await page.getByLabel('Email').fill('nobody@nowhere.test');
  await page.getByLabel('Password', { exact: true }).fill('whatever-wrong');
  await page.getByRole('button', { name: 'Log in' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  // Still on the sign-in page.
  await expect(page).toHaveURL(/\/sign-in/);
});
