/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_URL?: string;
  readonly VITE_APP_VERSION?: string;
  readonly VITE_RELEASE_CHANNEL?: 'stable' | 'beta' | 'dev';
  readonly VITE_DEMO_MODE?: string;
  readonly VITE_ROUTER_MODE?: 'hash' | 'browser';
  readonly VITE_APP_STORE_REVIEW?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
