import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// FRONTEND_MODULE.md §8. Port 5173 is deliberate: the API gateway's CORS
// allow-list already contains http://localhost:5173 (and :3000 for the built
// container), so development needs no backend change. strictPort makes a clash
// fail loudly rather than silently moving to 5174, which CORS would then block -
// a confusing failure to debug from the browser side.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    strictPort: true,
  },
});
