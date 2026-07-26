import { test, expect } from '@playwright/test';

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

test('register, land signed in, then cookie-authenticated /me works', async ({ page }) => {
  const email = uniqueEmail();

  await page.goto('/register');
  await page.getByLabel('Full name').fill('E2E Tester');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();

  // Straight into the app, signed in - the header now links to the profile
  // (the email) and offers Sign out.
  await expect(page.getByRole('banner').getByRole('button', { name: 'Sign out' })).toBeVisible();
  await expect(page.getByRole('banner').getByRole('link', { name: email })).toBeVisible();

  // Cookie auth: a page-context request to /me carries no Authorization header,
  // yet succeeds because the browser sends the httpOnly session cookie.
  const me = await page.request.get('/api/auth/me');
  expect(me.status()).toBe(200);
  const body = await me.json();
  expect(body.subject).toBe(email);

  // Sign out clears the session; /me is then 401.
  await page.getByRole('banner').getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('banner').getByRole('link', { name: 'Log in' })).toBeVisible();
  const after = await page.request.get('/api/auth/me');
  expect(after.status()).toBe(401);
});

test('wrong credentials are rejected without revealing which field', async ({ page }) => {
  await page.goto('/sign-in');
  await page.getByLabel('Email').fill('nobody@nowhere.test');
  await page.getByLabel('Password').fill('whatever-wrong');
  await page.getByRole('button', { name: 'Log in' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  // Still on the sign-in page.
  await expect(page).toHaveURL(/\/sign-in/);
});
