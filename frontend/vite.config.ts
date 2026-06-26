import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// Offline app-shell caching is handled by public/service-worker.js.
// API responses are intentionally not cached there, so business data stays live.

export default defineConfig({
  // Relative base lets the built app work when served from any subpath
  // — local file://, a CDN subdirectory, or an Electron/Tauri/Capacitor shell.
  base: './',
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
