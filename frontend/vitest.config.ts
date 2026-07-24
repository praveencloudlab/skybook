import { defineConfig } from 'vitest/config';

// jsdom, because the units under test are browser-shaped: sessionStorage,
// atob/btoa and fetch. Testing them in node would mean mocking the very APIs
// whose behaviour is the thing worth verifying.
export default defineConfig({
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
  },
});
