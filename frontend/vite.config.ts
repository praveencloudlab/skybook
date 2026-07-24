import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

/**
 * FRONTEND_MODULE.md §8, §10.1.
 *
 * The `/api` proxy is what makes the httpOnly session cookie work, and it is not
 * a convenience: it puts the SPA and the API on ONE ORIGIN
 * (http://localhost:5173), which means
 *   - `SameSite=Lax` is a real CSRF control, rather than a setting that would
 *     stop the cookie being sent at all across origins;
 *   - the browser makes no cross-origin requests, so CORS never enters the
 *     picture in development.
 * Without it we would have needed `SameSite=None`, which IS sent on cross-site
 * requests and would have required separate CSRF tokens - strictly worse.
 *
 * The container build mirrors this exactly, with nginx proxying /api to the
 * gateway, so development and production share one origin model instead of two.
 */
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // strictPort so a clash fails loudly instead of silently moving to 5174.
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: process.env.VITE_GATEWAY_URL ?? 'http://localhost:8080',
        changeOrigin: false,
      },
    },
  },
});
