import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// Offline app-shell caching is handled by public/service-worker.js.
// API responses are intentionally not cached there, so business data stays live.

export default defineConfig({
  // Browser-history routes such as /public/menu must resolve bundles from the
  // site root. Native hash-router packaging still needs relative file paths.
  base: process.env.VITE_ROUTER_MODE === 'hash' ? './' : '/',
  plugins: [react()],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8000', changeOrigin: true },
    },
  },
});
