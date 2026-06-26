import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, HashRouter } from 'react-router-dom';

// HashRouter works under file:// (double-click index.html), Tauri webview,
// and Capacitor without requiring server-side rewrites. BrowserRouter is
// reserved for cloud-hosted web deployments where the server can rewrite
// any path back to index.html.
const Router = import.meta.env.VITE_ROUTER_MODE === 'hash' ? HashRouter : BrowserRouter;
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import App from '@/app/App';
import { AuthProvider } from '@/modules/auth/AuthContext';
import { registerServiceWorker } from '@/registerServiceWorker';
import './styles/index.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, retry: 1, refetchOnWindowFocus: false },
  },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <Router>
        <AuthProvider>
          <App />
        </AuthProvider>
      </Router>
    </QueryClientProvider>
  </React.StrictMode>,
);

registerServiceWorker();
