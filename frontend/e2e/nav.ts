import { type Page } from '@playwright/test';

/**
 * Header helpers for viewport-agnostic specs.
 *
 * <p>Below lg the header collapses to a hamburger drawer, so "is X in the
 * nav" first means opening the drawer on a phone. Desktop is a no-op: the
 * same assertions then run against the always-visible header row.
 */
export async function openNav(page: Page, isMobile: boolean | undefined) {
  if (isMobile) {
    await page.getByRole('banner').getByRole('button', { name: 'Open menu' }).click();
  }
}

export async function closeNav(page: Page, isMobile: boolean | undefined) {
  if (isMobile) {
    await page.getByRole('banner').getByRole('button', { name: 'Close menu' }).click();
  }
}
