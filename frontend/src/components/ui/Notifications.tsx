import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import {
  AlertCircle,
  AlertTriangle,
  CheckCircle2,
  Info,
  X,
  type LucideIcon,
} from 'lucide-react';

export type NotificationType = 'success' | 'info' | 'warning' | 'error';

export interface NotificationOptions {
  title?: string;
  /** Critical notifications stay visible until a person dismisses them. */
  critical?: boolean;
  durationMs?: number;
}

export interface NotificationRecord extends NotificationOptions {
  id: string;
  message: string;
  type: NotificationType;
  critical: boolean;
}

interface NotificationController {
  success: (message: string, options?: NotificationOptions) => string;
  info: (message: string, options?: NotificationOptions) => string;
  warning: (message: string, options?: NotificationOptions) => string;
  error: (message: string, options?: NotificationOptions) => string;
  dismiss: (id: string) => void;
  clear: () => void;
}

export const MAX_NOTIFICATIONS = 5;
const DEFAULT_DURATION_MS: Record<NotificationType, number> = {
  success: 4_500,
  info: 5_500,
  warning: 7_000,
  error: 8_000,
};

const NotificationContext = createContext<NotificationController | null>(null);

export function appendBoundedNotification(
  current: readonly NotificationRecord[],
  notification: NotificationRecord,
): NotificationRecord[] {
  return [...current, notification].slice(-MAX_NOTIFICATIONS);
}

export function NotificationProvider({ children }: { children: ReactNode }) {
  const [notifications, setNotifications] = useState<NotificationRecord[]>([]);
  const nextId = useRef(0);

  const dismiss = useCallback((id: string) => {
    setNotifications((current) => current.filter((item) => item.id !== id));
  }, []);

  const clear = useCallback(() => setNotifications([]), []);

  const show = useCallback((
    type: NotificationType,
    message: string,
    options: NotificationOptions = {},
  ) => {
    const id = `notification-${Date.now()}-${nextId.current++}`;
    const critical = options.critical ?? type === 'error';
    const notification: NotificationRecord = {
      ...options,
      id,
      message,
      type,
      critical,
    };
    setNotifications((current) => appendBoundedNotification(current, notification));
    return id;
  }, []);

  const controller = useMemo<NotificationController>(() => ({
    success: (message, options) => show('success', message, options),
    info: (message, options) => show('info', message, options),
    warning: (message, options) => show('warning', message, options),
    error: (message, options) => show('error', message, options),
    dismiss,
    clear,
  }), [clear, dismiss, show]);

  return (
    <NotificationContext.Provider value={controller}>
      {children}
      <NotificationViewport notifications={notifications} onDismiss={dismiss} />
    </NotificationContext.Provider>
  );
}

export function useNotifications(): NotificationController {
  const controller = useContext(NotificationContext);
  if (!controller) {
    throw new Error('useNotifications must be used inside NotificationProvider');
  }
  return controller;
}

const PRESENTATION: Record<NotificationType, {
  Icon: LucideIcon;
  defaultTitle: string;
  borderClass: string;
  iconClass: string;
}> = {
  success: {
    Icon: CheckCircle2,
    defaultTitle: 'Done',
    borderClass: 'border-accent-good/60',
    iconClass: 'text-accent-good',
  },
  info: {
    Icon: Info,
    defaultTitle: 'Notice',
    borderClass: 'border-accent/60',
    iconClass: 'text-accent',
  },
  warning: {
    Icon: AlertTriangle,
    defaultTitle: 'Check this',
    borderClass: 'border-accent-gold/60',
    iconClass: 'text-accent-gold',
  },
  error: {
    Icon: AlertCircle,
    defaultTitle: 'Could not complete that',
    borderClass: 'border-accent-bad/70',
    iconClass: 'text-accent-bad',
  },
};

export function NotificationViewport({
  notifications,
  onDismiss,
}: {
  notifications: readonly NotificationRecord[];
  onDismiss: (id: string) => void;
}) {
  if (!notifications.length) return null;

  return (
    <section
      aria-label="Notifications"
      className="pointer-events-none fixed inset-x-3 top-3 z-[200] flex flex-col items-end gap-2 sm:inset-x-auto sm:right-4 sm:top-4 sm:w-[min(26rem,calc(100vw-2rem))]"
      style={{ paddingTop: 'env(safe-area-inset-top)' }}
    >
      {notifications.map((notification) => (
        <NotificationToast
          key={notification.id}
          notification={notification}
          onDismiss={onDismiss}
        />
      ))}
    </section>
  );
}

function NotificationToast({
  notification,
  onDismiss,
}: {
  notification: NotificationRecord;
  onDismiss: (id: string) => void;
}) {
  const { Icon, defaultTitle, borderClass, iconClass } = PRESENTATION[notification.type];
  const durationMs = notification.durationMs ?? DEFAULT_DURATION_MS[notification.type];

  useEffect(() => {
    if (notification.critical) return;
    const timeout = window.setTimeout(() => onDismiss(notification.id), durationMs);
    return () => window.clearTimeout(timeout);
  }, [durationMs, notification.critical, notification.id, onDismiss]);

  return (
    <div
      className={`pointer-events-auto w-full rounded-2xl border bg-bg-surface/95 p-4 shadow-2xl backdrop-blur ${borderClass}`}
      role={notification.type === 'error' || notification.critical ? 'alert' : 'status'}
      aria-live={notification.type === 'error' || notification.critical ? 'assertive' : 'polite'}
      aria-atomic="true"
      style={{ animation: 'notification-in var(--motion-med) ease-out both' }}
    >
      <div className="flex items-start gap-3">
        <Icon className={`mt-0.5 shrink-0 ${iconClass}`} size={20} aria-hidden="true" />
        <div className="min-w-0 flex-1">
          <p className="font-semibold text-fg">{notification.title || defaultTitle}</p>
          <p className="mt-0.5 whitespace-pre-line break-words text-sm text-fg-muted">
            {notification.message}
          </p>
        </div>
        <button
          type="button"
          className="tap-target -m-2 inline-flex shrink-0 items-center justify-center rounded-xl text-fg-muted transition hover:bg-bg-raised hover:text-fg active:scale-90"
          onClick={() => onDismiss(notification.id)}
          aria-label={`Dismiss ${notification.title || defaultTitle} notification`}
        >
          <X size={18} aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}
