import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import App from './App';
import ErrorBoundary from './ErrorBoundary';
import { NotificationProvider } from '@/components/ui/Notifications';
import { AuthProvider } from '@/modules/auth/AuthContext';
import { registerServiceWorker } from '@/registerServiceWorker';
import { initAlarms } from '@/lib/alarm';

const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 30_000, retry: 1, refetchOnWindowFocus: false } } });
registerServiceWorker();
initAlarms();
export default function OrdinaryApplication() {
  return <ErrorBoundary><NotificationProvider><QueryClientProvider client={queryClient}><AuthProvider><App/></AuthProvider></QueryClientProvider></NotificationProvider></ErrorBoundary>;
}
