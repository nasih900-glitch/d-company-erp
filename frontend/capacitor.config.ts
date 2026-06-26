import type { CapacitorConfig } from '@capacitor/cli';

/**
 * D Company ERP — Capacitor (mobile) config.
 *
 * - The React build in `dist/` is bundled into the app, so the UI loads
 *   instantly without a network round-trip.
 * - All API traffic goes to `VITE_API_URL` (set at build time).
 * - `androidScheme: 'https'` ensures cookies and CORS behave like the web.
 */
const config: CapacitorConfig = {
  appId: 'cloud.dcompany.erp',
  appName: 'D Company ERP',
  webDir: 'dist',
  bundledWebRuntime: false,

  server: {
    androidScheme: 'https',
    iosScheme: 'https',
    // Allow http only in dev builds (cleartext)
    cleartext: false,
  },

  android: {
    allowMixedContent: false,
    captureInput: true,
    webContentsDebuggingEnabled: false,
    backgroundColor: '#050403',
  },

  ios: {
    contentInset: 'always',
    scrollEnabled: true,
    backgroundColor: '#050403',
    limitsNavigationsToAppBoundDomains: false,
  },

  plugins: {
    SplashScreen: {
      launchShowDuration: 1500,
      launchAutoHide: true,
      backgroundColor: '#050403',
      androidSplashResourceName: 'splash',
      androidScaleType: 'CENTER_CROP',
      showSpinner: true,
      androidSpinnerStyle: 'large',
      spinnerColor: '#d2b36d',
      splashFullScreen: true,
      splashImmersive: true,
    },
    StatusBar: {
      style: 'dark',
      backgroundColor: '#050403',
      overlay: false,
    },
  },
};

export default config;
