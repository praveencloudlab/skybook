import { defineConfig, devices } from '@playwright/test';

/**
 * Browser E2E certification (FRONTEND_MODULE.md Module 19).
 *
 * <p>Complements the backend certification by driving the real SPA in a real
 * browser: public search without login, protected-route redirects, cookie
 * authentication (requests succeed with no manually-set Authorization header),
 * and the booking journey. Points at the running app - `E2E_BASE_URL` overrides
 * the default container origin for CI.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:3000',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
