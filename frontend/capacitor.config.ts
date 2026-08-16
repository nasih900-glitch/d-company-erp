import type { CapacitorConfig } from '@capacitor/cli';

/**
 * D Company ERP — Capacitor (mobile) config.
 *
 * This is the full web app in a native shell, deliberately NOT bundled:
 * `server.url` points straight at production, so the WebView always shows
 * whatever is currently deployed — no separate app build/redistribution
 * needed when the web app changes. The real native till app (android-native/)
 * is the offline-capable, pad-optimised experience; this is "the ERP,
 * as an app icon" for anyone who just wants the full site without a
 * browser tab. Different applicationId (see android/app/build.gradle) so
 * it can be installed alongside the native app without a package collision.
 * All API/WS traffic still goes to the same origin `server.url` points at.
 */
const config: CapacitorConfig = {
  appId: 'cloud.dcompany.erp.web',
  appName: 'D Company ERP',
  webDir: 'dist',
  bundledWebRuntime: false,

  server: {
    url: 'https://dcompany.duckdns.org',
    androidScheme: 'https',
    iosScheme: 'https',
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
      // MUST be uppercase. The Android plugin does
      // `setAppearanceLightStatusBars(!style.equals("DARK"))` — a
      // case-sensitive compare — so a lowercase 'dark' silently falls
      // through to light-appearance icons, i.e. dark icons on our dark
      // bar, which renders the clock and battery all but invisible.
      style: 'DARK',
      backgroundColor: '#050403',
      overlay: false,
    },
  },
};

export default config;
