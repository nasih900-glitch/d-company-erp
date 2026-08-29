/**
 * Gaming screen — stations + sessions (live + demo).
 *
 *  - List stations (PS5, VR, simulator, projector, shisha, streaming)
 *  - Add station (admin)
 *  - Edit / disable / delete station (admin)
 *  - Start session (live mode hits backend; demo runs a JS timer)
 *  - Stop session (records elapsed × rate; payment remains an explicit POS step)
 *  - Pause session (demo only — hidden in live mode until the backend supports it)
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Play, Square, Pause, PlayCircle, Gamepad2, Glasses, CarFront, Tv,
  Plus, Edit2, Trash2, Loader2, AlertCircle, RefreshCw, Settings, Flame,
  Timer, TimerOff, X, BellRing, BellOff, Bell, Send,
  Ban,
} from 'lucide-react';

import {
  ALARM_REPEAT_MS, fmtClock, notifyAlarm, playAlarmTone,
  alarmPermission, requestAlarmPermission, type AlarmPermission,
} from '@/lib/alarm';
import { LIVE_MODE } from '@/lib/demo';
import { STATIONS, type Station as DemoStation } from '@/lib/demo-data';
import { inr } from '@/lib/inr';
import { hasActivePricingToken, type ApiError } from '@/lib/api';
import { parseRupeesToMinor } from '@/lib/money-input';
import { APP_STORE_REVIEW, isAppStoreAllowedType } from '@/lib/app-store-compliance';
import {
  gaming,
  menu as menuApi,
  shifts,
  type GamingPackageDTO,
  type GamingPosTargetShiftDTO,
  type GamingSessionAddonDTO,
  type MenuItemDTO,
  type StationDTO,
} from '@/lib/erp-api';
import { resolveRequiredOpenShift } from '@/lib/operational-context';
import { GAMING_CENTRE_TERMINAL_POLICY } from '@/lib/product-profile';
import { createOperationKey, isAmbiguousApiError } from '@/lib/retry-drafts';
import { subscribeRealtime } from '@/lib/realtime';
import { useAuth } from '@/modules/auth/AuthContext';
import { ConfirmModal, PromptModal } from '@/components/ui/ConfirmDialog';
import Modal from '@/components/ui/Modal';
import { useNotifications } from '@/components/ui/Notifications';
import { SkeletonCard } from '@/components/ui/Skeleton';
import {
  canManageGamingSessions,
  canOfferGamingReconciliation,
  isGamingActiveBillingModeVerified,
  isGamingSessionOwnedByCurrentShift,
  resolveGamingStopShiftId,
} from './gaming-reconciliation';
import {
  createGamingWriteDispatcher,
  GamingMutationButton,
  GamingWriteOnly,
  type GamingWriteDispatcher,
} from './gaming-write-controls';
import {
  PaidExtensionPersistenceError,
  clearPaidExtensionAttempt,
  inspectPaidExtensionAttemptForSession,
  inspectPaidExtensionAttemptsForTerminal,
  isPaidExtensionLifecycleBlocked,
  paidExtensionPersistenceGuidance,
  paidExtensionRecoveryRequired,
  paidExtensionSubmissionMode,
  replayDurablyPersistedPaidExtension,
  sendDurablyPersistedPaidExtension,
  withPaidExtensionSessionLock,
  type PaidExtensionAttempt,
  type PaidExtensionAttemptContext,
  type PaidExtensionLockManager,
} from './paid-extension-attempt';
import { resolveGamingPosRoute } from './gaming-pos-handoff';
import { runningBillMinor } from './running-bill';
import { sessionTimerMinutesForLocalState } from './session-start-snapshot';
import {
  SessionAddonPickerModal,
  SessionAddonsPanel,
  SessionAddonVoidModal,
} from './GamingAddonControls';
import {
  availableGamingAddonItems,
  createClientLineId,
  gridVisibleGamingSessions,
  isGamingAddonLedgerAuthoritative,
  reconcileAddonVoidAttempt,
  resolveAddonVoidAttempt,
  type GamingAddonDraft,
  type GamingAddonVoidAttempt,
} from './gaming-addons';
import {
  GamingAddonCreatePersistenceError,
  clearGamingAddonCreateAttempt,
  gamingAddonCreatePersistenceGuidance,
  inspectGamingAddonCreateAttempt,
  sendDurablyPersistedGamingAddon,
  withGamingAddonCreateLock,
  type DurableGamingAddonCreateAttempt,
  type GamingAddonCreateLockManager,
  type GamingAddonCreateTerminalScope,
} from './gaming-addon-create-attempt';

const ICON: Record<StationDTO['type'], React.ReactNode> = {
  ps5:       <Gamepad2 size={22}/>,
  vr:        <Glasses size={22}/>,
  simulator: <CarFront size={22}/>,
  projector: <Tv size={22}/>,
  hookah:    <Flame size={22}/>,
  streaming: <Tv size={22}/>,
};
const TYPE_LABEL: Record<StationDTO['type'], string> = {
  ps5: 'PlayStation 5',
  vr: 'VR Pod',
  simulator: 'Racing Simulator',
  projector: 'Projector',
  hookah: 'Shisha',
  streaming: 'Streaming Booth',
};

type LocalSession = {
  station_id: string;
  shift_id?: string | null;
  start_at: number;
  status: 'active' | 'paused' | 'ended';
  pausedMs: number;
  pause_started_at?: number;
  backend_session_id?: string;
  timer_minutes?: number | null;
  timer_ends_at?: number | null;
  ended_minutes?: number;
  // null is materially different from a genuine zero-value session: it means
  // authoritative billing is missing and must never be presented as ₹0.
  ended_amount_minor?: number | null;
  billing_mode?: 'hourly' | 'package' | 'legacy_ambiguous';
  // Immutable server snapshot. Never substitute the station's current
  // catalogue rate after a session has started.
  rate_per_hour_minor?: number | null;
  package_id?: string | null;
  package_variant_snapshot?: string | null;
  package_station_type_snapshot?: string | null;
  extra_controllers?: number;
  // Fixed, locked-in price for a package session — never recomputed from
  // elapsed time (see gaming/router.py stop_session). Undefined/null for an
  // open-ended (non-package) session, which bills off elapsed time instead.
  locked_amount_minor?: number | null;
};

const DURATION_PRESETS = [
  { label: 'No timer', minutes: null },
  { label: '30m', minutes: 30 },
  { label: '1h', minutes: 60 },
  { label: '2h', minutes: 120 },
] as const;

// Sessions only ever loaded once at mount otherwise — on a multi-device
// floor, if another staff member stops/starts/extends a session from a
// different screen, this screen would show stale state (a stopped session
// still ticking as "overtime") until someone happened to reload the page.
// Fallback only — real-time push is the primary mechanism.
const GAMING_SESSIONS_POLL_MS = 120_000;

type PendingExtension = {
  station: StationDTO;
  extension: GamingPackageDTO;
};

type PendingReconciliation = {
  station: StationDTO;
  targetShiftId: string;
};

type PendingPosHandoff = {
  station: StationDTO;
  targets: GamingPosTargetShiftDTO[];
};

type SendFailure = {
  message: string;
  code?: string;
};

type PaidExtensionRecoveryState = {
  attempt: PaidExtensionAttempt | null;
  error: PaidExtensionPersistenceError | null;
};

type AddonModalTarget = {
  station: StationDTO;
  sessionId: string;
};

type AddonVoidTarget = AddonModalTarget & {
  addon: GamingSessionAddonDTO;
};

type CurrentShiftContext =
  | { shiftId: string; error: null }
  | { shiftId: null; error: string };

function extraControllerSurchargeMinor(extraControllers: number, durationMinutes: number): number {
  if (extraControllers <= 0 || durationMinutes <= 0) return 0;
  return extraControllers * Math.max(3_000, Math.ceil(durationMinutes / 60) * 3_000);
}

function notifyTimerExpired(stationName: string) {
  notifyAlarm(
    `⏰ ${stationName} — time's up`,
    'The session timer has ended. Stop the session or extend the timer.',
    `dcompany-timer-${stationName}`,
  );
}

export default function GamingScreen() {
  const notifications = useNotifications();
  const { me, terminalId, terminalReady, terminalOptions } = useAuth();
  const currentTerminal = terminalOptions.find((terminal) => terminal.id === terminalId) ?? null;
  const canStartOnSelectedTerminal = !LIVE_MODE
    || currentTerminal?.purpose === 'gaming'
    || currentTerminal?.purpose === 'hybrid';
  // accessible_modules includes read-only roles, so it is not sufficient for
  // Start/Stop. Use the backend's exact post-override permission; retain the
  // protected operational bypass for compatibility with older /auth/me.
  const canManageStations = canManageGamingSessions({
    liveMode: LIVE_MODE,
    effectivePermissions: me?.effective_permissions,
    protectedAccess: me?.protected_access,
    roles: me?.roles,
  });
  // Add-ons are part of the Gaming session lifecycle. The backend authorises
  // them with gaming.write, including the gaming-supervisor role that does not
  // independently operate POS checkout.
  const canManageSessionAddons = canManageStations;

  function requireGamingWrite(actionTitle: string): GamingWriteDispatcher {
    return createGamingWriteDispatcher(canManageStations, () => {
      notifications.error(
        'Gaming is view-only for this account. Ask an owner to enable the Gaming module for this role.',
        { title: actionTitle },
      );
    }, gaming);
  }
  const [stations, setStations] = useState<StationDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [sessions, setSessions] = useState<Record<string, LocalSession>>({});
  const [addonCatalog, setAddonCatalog] = useState<MenuItemDTO[]>([]);
  const [addonCatalogError, setAddonCatalogError] = useState<string | null>(null);
  const [addonsBySession, setAddonsBySession] = useState<Record<string, GamingSessionAddonDTO[]>>({});
  const [addonLoadErrors, setAddonLoadErrors] = useState<Record<string, string>>({});
  const [addonAuthoritativeLedgerSessions, setAddonAuthoritativeLedgerSessions] = useState<
    ReadonlySet<string>
  >(new Set());
  const [currentShiftId, setCurrentShiftId] = useState<string | null>(null);
  const [shiftContextError, setShiftContextError] = useState<string | null>(null);
  const [, setTick] = useState(0); // force overtime/countdown recompute every second
  const [pendingDuration, setPendingDuration] = useState<Record<string, number | null>>({});
  const [customDurationFor, setCustomDurationFor] = useState<string | null>(null);
  // Member phone attached at session start — carried through to the order
  // created at send-to-pos so loyalty points accrue automatically without
  // the cashier re-entering it at POS checkout (gaming/router.py already
  // wires GamingSession.customer_phone -> order.customer_phone -> points).
  const [sessionPhone, setSessionPhone] = useState<Record<string, string>>({});
  const [packages, setPackages] = useState<GamingPackageDTO[]>([]);
  const [pickerVariant, setPickerVariant] = useState<Record<string, string>>({});
  const [pickerControllers, setPickerControllers] = useState<Record<string, number>>({});
  const [mutedStations, setMutedStations] = useState<Record<string, boolean>>({});
  const [sendingToPos, setSendingToPos] = useState<string | null>(null);
  const [resolvingReconciliation, setResolvingReconciliation] = useState<string | null>(null);
  const [reconciling, setReconciling] = useState<string | null>(null);
  const [extendingSession, setExtendingSession] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState<string | null>(null);
  const [sendErrors, setSendErrors] = useState<Record<string, SendFailure>>({});
  // Resolved asynchronously: on the native tablet build this comes from the
  // OS via Capacitor rather than the WebView's (absent) Notification API.
  const [notifPermission, setNotifPermission] = useState<AlarmPermission>('default');
  const extensionBusyRef = useRef(false);
  useEffect(() => { void alarmPermission().then(setNotifPermission); }, []);

  // Refs so the 1s alarm-check interval (subscribed once) always sees fresh data
  // without re-subscribing every render.
  const sessionsRef = useRef(sessions);
  useEffect(() => { sessionsRef.current = sessions; }, [sessions]);
  const stationsRef = useRef(stations);
  useEffect(() => { stationsRef.current = stations; }, [stations]);
  const mutedRef = useRef(mutedStations);
  useEffect(() => { mutedRef.current = mutedStations; }, [mutedStations]);
  const lastAlarmAtRef = useRef<Record<string, number>>({});

  const [addOpen, setAddOpen] = useState(false);
  const [edit, setEdit] = useState<StationDTO | null>(null);
  const [manageMode, setManageMode] = useState(false);
  const [deleteStationTarget, setDeleteStationTarget] = useState<StationDTO | null>(null);
  const [deleteStationBusy, setDeleteStationBusy] = useState(false);
  const [pendingExtension, setPendingExtension] = useState<PendingExtension | null>(null);
  const [pendingReconciliation, setPendingReconciliation] = useState<PendingReconciliation | null>(null);
  const [pendingPosHandoff, setPendingPosHandoff] = useState<PendingPosHandoff | null>(null);
  const [cancelStationTarget, setCancelStationTarget] = useState<StationDTO | null>(null);
  const [repairStationTarget, setRepairStationTarget] = useState<StationDTO | null>(null);
  const [repairingBilling, setRepairingBilling] = useState<string | null>(null);
  const [paidExtensionReceiptRevision, setPaidExtensionReceiptRevision] = useState(0);
  const repairKeyRef = useRef<string | null>(null);
  const [addonModalTarget, setAddonModalTarget] = useState<AddonModalTarget | null>(null);
  const [addonVoidTarget, setAddonVoidTarget] = useState<AddonVoidTarget | null>(null);
  const [addonCreateAttempt, setAddonCreateAttempt] = useState<DurableGamingAddonCreateAttempt | null>(null);
  const [addonVoidAttempt, setAddonVoidAttempt] = useState<GamingAddonVoidAttempt | null>(null);
  const [addonCreateError, setAddonCreateError] = useState<string | null>(null);
  const [addonVoidError, setAddonVoidError] = useState<string | null>(null);
  const [addonCreatePersistenceError, setAddonCreatePersistenceError] = useState<
    GamingAddonCreatePersistenceError | null
  >(null);
  const [addonCreateReceiptRevision, setAddonCreateReceiptRevision] = useState(0);
  const [addingAddonTo, setAddingAddonTo] = useState<string | null>(null);
  const [voidingAddon, setVoidingAddon] = useState<string | null>(null);
  const addonCreateBusyRef = useRef(false);
  const addonVoidBusyRef = useRef(false);

  const addonCreateTerminalScope = useMemo<GamingAddonCreateTerminalScope | null>(() => {
    if (
      !LIVE_MODE
      || !me?.user_id
      || !me.company_id
      || !me.branch_id
      || !terminalReady
      || !terminalId
    ) return null;
    return {
      actorUserId: me.user_id,
      companyId: me.company_id,
      branchId: me.branch_id,
      terminalId,
    };
  }, [me?.user_id, me?.company_id, me?.branch_id, terminalReady, terminalId]);

  const inspectedAddonCreateAttempt = useMemo(() => {
    void addonCreateReceiptRevision;
    if (!addonCreateTerminalScope) {
      return {
        attempt: null as DurableGamingAddonCreateAttempt | null,
        error: null as GamingAddonCreatePersistenceError | null,
      };
    }
    try {
      return {
        attempt: inspectGamingAddonCreateAttempt({
          storageProvider: () => globalThis.localStorage,
          scope: addonCreateTerminalScope,
        }),
        error: null as GamingAddonCreatePersistenceError | null,
      };
    } catch (cause) {
      return {
        attempt: null as DurableGamingAddonCreateAttempt | null,
        error: cause instanceof GamingAddonCreatePersistenceError
          ? cause
          : new GamingAddonCreatePersistenceError(
              'storage_unavailable',
              'The saved Gaming item receipt could not be inspected safely.',
            ),
      };
    }
  }, [addonCreateTerminalScope, addonCreateReceiptRevision]);

  useEffect(() => {
    if (inspectedAddonCreateAttempt.error) {
      setAddonCreatePersistenceError(inspectedAddonCreateAttempt.error);
      return;
    }
    setAddonCreatePersistenceError(null);
    setAddonCreateAttempt(inspectedAddonCreateAttempt.attempt);
  }, [inspectedAddonCreateAttempt]);

  const clearDurableAddonCreateAttempt = useCallback((
    expectedAttempt: DurableGamingAddonCreateAttempt,
  ) => {
    clearGamingAddonCreateAttempt({
      storageProvider: () => globalThis.localStorage,
      expectedAttempt,
    });
    setAddonCreateAttempt((current) => (
      current?.idempotencyKey === expectedAttempt.idempotencyKey ? null : current
    ));
    setAddonCreatePersistenceError(null);
    setAddonCreateReceiptRevision((revision) => revision + 1);
  }, []);

  // A reload can recover a request whose response was lost. Only an
  // authoritative full-ledger result may reconcile that receipt here; a
  // single mutation response is deliberately insufficient to clear a prior
  // ledger-load failure or to prove that every staged line was reviewed.
  useEffect(() => {
    const attempt = addonCreateAttempt;
    if (
      !attempt
      || !isGamingAddonLedgerAuthoritative(
        attempt.sessionId,
        addonAuthoritativeLedgerSessions,
        addonLoadErrors,
      )
    ) return;
    const ledger = addonsBySession[attempt.sessionId];
    if (!ledger?.some((addon) => addon.client_line_id === attempt.body.client_line_id)) return;
    try {
      clearDurableAddonCreateAttempt(attempt);
    } catch (cause) {
      setAddonCreatePersistenceError(
        cause instanceof GamingAddonCreatePersistenceError
          ? cause
          : new GamingAddonCreatePersistenceError(
              'clear_verification_failed',
              'The confirmed Gaming item receipt could not be cleared safely.',
            ),
      );
    }
  }, [
    addonCreateAttempt,
    addonLoadErrors,
    addonAuthoritativeLedgerSessions,
    addonsBySession,
    clearDurableAddonCreateAttempt,
  ]);

  const inspectPaidExtensionRecovery = useCallback((session: LocalSession): PaidExtensionRecoveryState => {
    if (
      !LIVE_MODE
      || !session.backend_session_id
      || !paidExtensionRecoveryRequired(session.billing_mode)
    ) {
      return { attempt: null, error: null };
    }
    try {
      return {
        attempt: inspectPaidExtensionAttemptForSession({
          storageProvider: () => globalThis.localStorage,
          scope: {
            actorUserId: me?.user_id ?? '',
            companyId: me?.company_id ?? '',
            branchId: me?.branch_id ?? '',
            terminalId: terminalReady ? (terminalId ?? '') : '',
            sessionId: session.backend_session_id,
            shiftId: session.shift_id ?? '',
          },
        }),
        error: null,
      };
    } catch (cause) {
      return {
        attempt: null,
        error: cause instanceof PaidExtensionPersistenceError
          ? cause
          : new PaidExtensionPersistenceError(
              'storage_unavailable',
              'The saved paid-extension recovery receipt could not be inspected safely.',
            ),
      };
    }
  }, [me?.user_id, me?.company_id, me?.branch_id, terminalId, terminalReady]);

  const paidExtensionRecoveryBySession = useMemo(() => {
    void paidExtensionReceiptRevision;
    const recoveries: Record<string, PaidExtensionRecoveryState> = {};
    for (const session of Object.values(sessions)) {
      if (!session.backend_session_id) continue;
      recoveries[session.backend_session_id] = inspectPaidExtensionRecovery(session);
    }
    return recoveries;
    // The revision deliberately invalidates this snapshot after this tab
    // persists or clears a receipt. The remaining dependencies cover account,
    // terminal, shift, and realtime session changes.
  }, [sessions, inspectPaidExtensionRecovery, paidExtensionReceiptRevision]);

  const paidExtensionInventory = useMemo(() => {
    void paidExtensionReceiptRevision;
    if (
      !LIVE_MODE
      || !me?.user_id
      || !me.company_id
      || !me.branch_id
      || !terminalReady
      || !terminalId
    ) {
      return {
        attempts: [] as readonly PaidExtensionAttempt[],
        issueCodes: [] as readonly string[],
        error: null as PaidExtensionPersistenceError | null,
      };
    }
    try {
      const inventory = inspectPaidExtensionAttemptsForTerminal({
        storageProvider: () => globalThis.localStorage,
        scope: {
          actorUserId: me.user_id,
          companyId: me.company_id,
          branchId: me.branch_id,
          terminalId,
        },
      });
      return { ...inventory, error: null as PaidExtensionPersistenceError | null };
    } catch (cause) {
      return {
        attempts: [] as readonly PaidExtensionAttempt[],
        issueCodes: [] as readonly string[],
        error: cause instanceof PaidExtensionPersistenceError
          ? cause
          : new PaidExtensionPersistenceError(
              'storage_unavailable',
              'Saved paid-extension receipts could not be inspected safely.',
            ),
      };
    }
  }, [
    me?.user_id,
    me?.company_id,
    me?.branch_id,
    terminalReady,
    terminalId,
    paidExtensionReceiptRevision,
  ]);

  const orphanPaidExtensionAttempts = useMemo(() => {
    const visibleReceiptSessions = new Set(
      Object.values(paidExtensionRecoveryBySession)
        .map((recovery) => recovery.attempt?.sessionId)
        .filter((sessionId): sessionId is string => Boolean(sessionId)),
    );
    return paidExtensionInventory.attempts.filter(
      (attempt) => !visibleReceiptSessions.has(attempt.sessionId),
    );
  }, [paidExtensionInventory.attempts, paidExtensionRecoveryBySession]);

  async function load() {
    setLoading(true); setError(null);
    try {
      if (LIVE_MODE) {
        const shiftContextPromise: Promise<CurrentShiftContext> = findRequiredShiftId()
          .then((shiftId) => ({ shiftId, error: null }))
          .catch((cause: unknown) => ({
            shiftId: null,
            error: cause instanceof Error ? cause.message : 'The current shift could not be verified.',
          }));
        const [
          stationRows,
          activeSessions,
          pausedSessions,
          endedSessions,
          packageRows,
          shiftContext,
          catalogResult,
        ] = await Promise.all([
          gaming.listStations(),
          gaming.listSessions('active'),
          gaming.listSessions('paused'),
          // Only stopped sessions still awaiting POS matter operationally.
          // Fetch enough to cover every station instead of letting paid
          // history push an older unbilled session out of the default window.
          gaming.listSessions('ended', { unbilledOnly: true, limit: 500 }),
          gaming.listPackages(),
          shiftContextPromise,
          menuApi.items()
            .then((items) => ({ items: availableGamingAddonItems(items), error: null }))
            .catch((cause: unknown) => ({
              items: [] as MenuItemDTO[],
              error: cause instanceof Error
                ? cause.message
                : 'The drinks and snacks catalogue could not be loaded.',
            })),
        ]);
        setCurrentShiftId(shiftContext.shiftId);
        setShiftContextError(shiftContext.error);
        setStations(stationRows.filter((station) => isAppStoreAllowedType(station.type)));
        setPackages(packageRows);
        setAddonCatalog(catalogResult.items);
        setAddonCatalogError(catalogResult.error);
        const visibleSessions = gridVisibleGamingSessions(
          activeSessions,
          pausedSessions,
          endedSessions,
        );
        const visibleSessionIds = new Set(visibleSessions.map((sessionRow) => sessionRow.id));
        const addonResults = await Promise.allSettled(
          visibleSessions.map((sessionRow) => gaming.listSessionAddons(sessionRow.id)),
        );
        const loadedAddons: Record<string, GamingSessionAddonDTO[]> = {};
        const loadErrors: Record<string, string> = {};
        addonResults.forEach((result, index) => {
          const sessionId = visibleSessions[index].id;
          if (result.status === 'fulfilled') {
            loadedAddons[sessionId] = result.value;
          } else {
            loadErrors[sessionId] = result.reason instanceof Error
              ? result.reason.message
              : 'Saved drinks and snacks could not be loaded.';
          }
        });
        setAddonsBySession((previous) => {
          const next: Record<string, GamingSessionAddonDTO[]> = {};
          for (const sessionRow of visibleSessions) {
            next[sessionRow.id] = loadedAddons[sessionRow.id]
              ?? previous[sessionRow.id]
              ?? [];
          }
          return next;
        });
        setAddonLoadErrors(loadErrors);
        setAddonAuthoritativeLedgerSessions(new Set(Object.keys(loadedAddons)));
        setAddonVoidAttempt((attempt) => reconcileAddonVoidAttempt(
          attempt,
          visibleSessionIds,
          loadedAddons,
        ));
        // Rehydrate running sessions, AND any stopped-but-not-yet-sent session,
        // so a page refresh never silently drops an unbilled amount from view.
        setSessions((prev) => {
          const next: Record<string, LocalSession> = {};
          for (const gs of [...activeSessions, ...pausedSessions]) {
            const previous = prev[gs.station_id]?.backend_session_id === gs.id
              ? prev[gs.station_id]
              : undefined;
            // Always apply the server's current financial/timer snapshot. Reusing
            // the whole previous object made realtime refreshes silently ignore a
            // paid extension confirmed from this or another terminal.
            next[gs.station_id] = {
              station_id: gs.station_id,
              shift_id: gs.shift_id,
              start_at: new Date(gs.start_at).getTime(),
              status: gs.status === 'paused' ? 'paused' : 'active',
              pausedMs: Math.max(0, gs.paused_minutes) * 60_000,
              pause_started_at: gs.status === 'paused' ? previous?.pause_started_at : undefined,
              backend_session_id: gs.id,
              timer_minutes: gs.timer_minutes,
              timer_ends_at: gs.timer_ends_at ? new Date(gs.timer_ends_at).getTime() : null,
              billing_mode: gs.billing_mode,
              rate_per_hour_minor: gs.rate_per_hour_minor,
              package_id: gs.package_id,
              package_variant_snapshot: gs.package_variant_snapshot,
              package_station_type_snapshot: gs.package_station_type_snapshot,
              extra_controllers: gs.extra_controllers,
              locked_amount_minor: gs.billing_mode === 'hourly' ? null : gs.amount_minor,
            };
          }
          for (const gs of endedSessions) {
            if (gs.order_id || next[gs.station_id]) continue;
            next[gs.station_id] = {
              station_id: gs.station_id,
              shift_id: gs.shift_id,
              start_at: new Date(gs.start_at).getTime(),
              status: 'ended',
              pausedMs: 0,
              backend_session_id: gs.id,
              billing_mode: gs.billing_mode,
              rate_per_hour_minor: gs.rate_per_hour_minor,
              ended_minutes: gs.billable_minutes ?? 0,
              ended_amount_minor: gs.amount_minor,
            };
          }
          return next;
        });
      } else {
        setStations(STATIONS.map(demoToDTO).filter((station) => isAppStoreAllowedType(station.type)));
      }
    } catch (e) { setError((e as Error).message); }
    finally { setLoading(false); }
  }
  const loadRef = useRef(load);
  useEffect(() => { loadRef.current = load; });
  useEffect(() => { void loadRef.current(); }, []);

  // Real-time push re-syncs with the server the moment another device's
  // stop/start/extend happens, instead of waiting for a timer. load()'s
  // merge logic (see above) already reuses the existing local session
  // object when the backend session id hasn't changed, so this doesn't
  // disrupt the locally ticking countdown for sessions nothing happened to.
  useEffect(() => {
    if (!LIVE_MODE) return;
    const unsubscribe = subscribeRealtime('gaming', () => { void loadRef.current(); });
    const id = setInterval(() => { void loadRef.current(); }, GAMING_SESSIONS_POLL_MS);
    return () => { unsubscribe(); clearInterval(id); };
  }, []);

  // Live ticking timer + alarm check. One interval drives both the countdown
  // re-render and the "has any timer just expired" scan, using refs so it
  // always reads current data without resubscribing every render.
  useEffect(() => {
    const id = setInterval(() => {
      setTick((n) => n + 1);
      const now = Date.now();
      for (const s of Object.values(sessionsRef.current)) {
        if (!s.timer_ends_at || now < s.timer_ends_at) continue;
        if (mutedRef.current[s.station_id]) continue;
        const last = lastAlarmAtRef.current[s.station_id];
        if (last && now - last < ALARM_REPEAT_MS) continue;
        lastAlarmAtRef.current[s.station_id] = now;
        playAlarmTone();
        const station = stationsRef.current.find((st) => st.id === s.station_id);
        notifyTimerExpired(station?.name ?? 'A station');
      }
    }, 1000);
    return () => clearInterval(id);
  }, []);

  function enableAlarmNotifications() {
    void requestAlarmPermission().then(setNotifPermission);
  }

  function toggleMute(stationId: string) {
    setMutedStations((m) => ({ ...m, [stationId]: !m[stationId] }));
  }

  async function findRequiredShiftId(
    station?: StationDTO,
    purpose: 'start' | 'reconcile' = 'start',
  ) {
    const companyId = me?.company_id;
    const branchId = me?.branch_id;
    if (!companyId || !branchId) {
      throw new Error(
        `This account has no branch assigned. Assign one before ${purpose === 'start' ? 'starting' : 'reconciling'} a session.`,
      );
    }
    if (!terminalReady || !terminalId) {
      throw new Error(
        `This device is not ready to ${purpose === 'start' ? 'start' : 'reconcile'} a session. Refresh it; if the problem remains, ask an owner to check the device setup.`,
      );
    }
    try {
      return await resolveRequiredOpenShift({
        scope: { companyId, branchId, terminalId },
        stationBranchId: station?.branch_id,
        listOpenShifts: () => shifts.list(true),
      });
    } catch (error) {
      const message = (error as Error).message;
      if (purpose === 'reconcile' && message.startsWith('No shift is open')) {
        throw new Error(
          'No shift is open. Open a shift from the Shift tab, then retry this reconciliation.',
        );
      }
      throw error;
    }
  }

  async function ensureShiftId(station: StationDTO, purpose: 'start' | 'reconcile' = 'start') {
    try {
      const shiftId = await findRequiredShiftId(station, purpose);
      setCurrentShiftId(shiftId);
      setShiftContextError(null);
      return shiftId;
    } catch (error) {
      setCurrentShiftId(null);
      setShiftContextError((error as Error).message);
      throw error;
    }
  }

  function ownsCurrentShift(session: LocalSession): boolean {
    return isGamingSessionOwnedByCurrentShift({
      liveMode: LIVE_MODE,
      currentShiftId,
      sessionShiftId: session.shift_id,
    });
  }

  function requireCurrentShiftOwnership(session: LocalSession, action: string): boolean {
    if (ownsCurrentShift(session)) return true;
    notifications.error(
      currentShiftId
        ? `This session belongs to a different or no-longer-open shift. Ask a protected owner to review it before you ${action}.`
        : (shiftContextError ?? `Open and verify the current shift before trying to ${action}.`),
      { title: 'Session shift needs review' },
    );
    return false;
  }

  function stopShiftIdFor(session: LocalSession): string | null {
    return resolveGamingStopShiftId({
      liveMode: LIVE_MODE,
      currentShiftId,
      currentShiftConfirmed: Boolean(
        terminalReady && currentShiftId && shiftContextError === null,
      ),
      sessionShiftId: session.shift_id,
      serverSessionKnown: Boolean(session.backend_session_id),
    });
  }

  function requireStopShift(session: LocalSession): string | null {
    const shiftId = stopShiftIdFor(session);
    if (shiftId) return shiftId;
    notifications.error(
      session.shift_id && currentShiftId && session.shift_id !== currentShiftId
        ? 'This session belongs to a different or no-longer-open shift. Ask a protected owner to review it before ending the session.'
        : (shiftContextError
            ?? 'The session shift could not be verified. Refresh Gaming and confirm there is a server-confirmed open shift.'),
      { title: 'Session shift not verified' },
    );
    return null;
  }

  function requireVerifiedActiveBillingMode(session: LocalSession, action: string): boolean {
    if (isGamingActiveBillingModeVerified(session.billing_mode)) return true;
    notifications.error(
      `This legacy session's billing mode cannot be proved. A protected owner must review it before staff ${action}.`,
      { title: 'Billing mode unverified' },
    );
    return false;
  }

  function requirePaidExtensionResolved(session: LocalSession, action: string): boolean {
    // Hourly sessions cannot receive a paid package extension, so a browser
    // storage outage must not trap their otherwise server-authoritative Stop.
    if (!paidExtensionRecoveryRequired(session.billing_mode)) return true;
    const recovery = inspectPaidExtensionRecovery(session);
    if (recovery.error) {
      notifications.error(
        `${paidExtensionPersistenceGuidance(recovery.error)} The session cannot be ${action} until this is resolved.`,
        { title: 'Saved extension needs review' },
      );
      return false;
    }
    if (recovery.attempt) {
      notifications.error(
        `A ${recovery.attempt.packageDurationMinutes}-minute paid extension is still awaiting server confirmation. Retry that exact saved extension before this session can be ${action}.`,
        { title: 'Confirm the saved extension first' },
      );
      return false;
    }
    return true;
  }

  function requirePackageRecoveryStorageForStart(): boolean {
    if (!LIVE_MODE) return true;
    if (
      !me?.user_id
      || !me.company_id
      || !me.branch_id
      || !terminalReady
      || !terminalId
    ) {
      notifications.error(
        'The staff, shop, or device context could not be verified. Refresh Gaming before starting a package session.',
        { title: 'Package session not started' },
      );
      return false;
    }
    try {
      // Fresh handler-level read: the cached banner/disabled state is only
      // guidance and must never authorize the financial lifecycle.
      inspectPaidExtensionAttemptsForTerminal({
        storageProvider: () => globalThis.localStorage,
        scope: {
          actorUserId: me.user_id,
          companyId: me.company_id,
          branchId: me.branch_id,
          terminalId,
        },
      });
      return true;
    } catch (cause) {
      const storageError = cause instanceof PaidExtensionPersistenceError
        ? cause
        : new PaidExtensionPersistenceError(
            'storage_unavailable',
            'Paid-extension recovery storage could not be verified.',
          );
      notifications.error(
        `${paidExtensionPersistenceGuidance(storageError)} Package session was not started, so no bill was created.`,
        { title: 'Package session not started' },
      );
      return false;
    }
  }

  async function startSession(
    st: StationDTO,
    customer = '',
    pkg?: { packageId: string; extraControllers: number },
    phone = '',
  ) {
    const write = requireGamingWrite('Cannot start session');
    if (!write.allowed) return;
    const timerMinutes = pkg ? null : pendingDuration[st.id] ?? null;
    let backendId: string | undefined;
    let authoritativeStartAt = Date.now();
    let authoritativePausedMs = 0;
    let authoritativeClockValid = true;
    let sourceShiftId: string | null = null;
    let timerEndsAt: number | null = timerMinutes ? Date.now() + timerMinutes * 60000 : null;
    let sessionTimerMinutes = timerMinutes;
    let packageId: string | undefined;
    let billingMode: 'hourly' | 'package' | 'legacy_ambiguous' = pkg ? 'package' : 'hourly';
    let packageVariantSnapshot: string | null = null;
    let packageStationTypeSnapshot: string | null = null;
    let extraControllers = 0;
    let lockedAmountMinor: number | null = null;
    let ratePerHourMinor: number | null = st.rate_per_hour_minor;
    if (LIVE_MODE) {
      try {
        if (pkg && !requirePackageRecoveryStorageForStart()) return;
        const shiftId = await ensureShiftId(st);
        sourceShiftId = shiftId;
        const selectedPackage = pkg
          ? packages.find((item) => item.id === pkg.packageId && item.kind === 'base')
          : undefined;
        if (pkg && !selectedPackage) {
          throw new Error('That gaming package is no longer available. Refresh Gaming and choose again.');
        }
        const r = await write.dispatch('startSession', {
          station_id: st.id,
          shift_id: shiftId,
          customer_name: customer || undefined,
          customer_phone: phone.trim() || undefined,
          timer_minutes: timerMinutes ?? undefined,
          package_id: pkg?.packageId,
          extra_controllers: pkg?.extraControllers,
          expected_rate_per_hour_minor: st.rate_per_hour_minor,
          expected_package_price_minor: selectedPackage?.price_minor,
          expected_package_duration_minutes: selectedPackage?.duration_minutes,
          expected_package_variant: selectedPackage?.variant,
        }, `gaming-session-start:${createOperationKey()}`);
        backendId = r.id;
        authoritativeStartAt = new Date(r.start_at).getTime();
        if (!Number.isFinite(authoritativeStartAt)) {
          authoritativeClockValid = false;
          authoritativeStartAt = Date.now();
          notifications.error(
            'The session was accepted, but its server clock could not be read. It remains visible; refresh Gaming and do not start a replacement.',
            { title: 'Session started — refresh required' },
          );
        }
        authoritativePausedMs = Math.max(0, r.paused_minutes) * 60_000;
        sessionTimerMinutes = sessionTimerMinutesForLocalState({
          liveMode: true,
          requestedTimerMinutes: timerMinutes,
          serverTimerMinutes: r.timer_minutes,
        });
        timerEndsAt = r.timer_ends_at ? new Date(r.timer_ends_at).getTime() : null;
        packageId = r.package_id ?? undefined;
        billingMode = r.billing_mode;
        packageVariantSnapshot = r.package_variant_snapshot;
        packageStationTypeSnapshot = r.package_station_type_snapshot;
        extraControllers = r.extra_controllers;
        lockedAmountMinor = billingMode === 'hourly' ? null : r.amount_minor ?? null;
        ratePerHourMinor = authoritativeClockValid ? r.rate_per_hour_minor : null;
      } catch (e) {
        notifications.error((e as Error).message, { title: 'Cannot start session' });
        return;
      }
    }
    setSessions((s) => ({
      ...s,
      [st.id]: {
        station_id: st.id,
        shift_id: sourceShiftId,
        start_at: authoritativeStartAt,
        status: 'active',
        pausedMs: authoritativePausedMs,
        backend_session_id: backendId,
        timer_minutes: sessionTimerMinutes,
        timer_ends_at: timerEndsAt,
        billing_mode: billingMode,
        rate_per_hour_minor: ratePerHourMinor,
        package_id: packageId,
        package_variant_snapshot: packageVariantSnapshot,
        package_station_type_snapshot: packageStationTypeSnapshot,
        extra_controllers: extraControllers,
        locked_amount_minor: lockedAmountMinor,
      },
    }));
    setPendingDuration((p) => ({ ...p, [st.id]: null }));
    setPickerControllers((p) => ({ ...p, [st.id]: 0 }));
    setSessionPhone((p) => ({ ...p, [st.id]: '' }));
    setCustomDurationFor(null);
    notifications.success(`${st.name} session started.`, { title: 'Session running' });
  }

  async function setStationTimer(st: StationDTO, minutes: number | null) {
    const write = requireGamingWrite('Cannot update timer');
    if (!write.allowed) return;
    const s = sessions[st.id];
    if (!s) return;
    if (!requireCurrentShiftOwnership(s, 'change its timer')) return;
    if (!requireVerifiedActiveBillingMode(s, 'change its timer')) return;
    const timerEndsAt = minutes ? s.start_at + minutes * 60000 : null;
    if (LIVE_MODE && s.backend_session_id) {
      try { await write.dispatch('setSessionTimer', s.backend_session_id, minutes); }
      catch (e) {
        notifications.error((e as Error).message, { title: 'Could not update timer' });
        return;
      }
    }
    setSessions((map) => ({
      ...map,
      [st.id]: { ...s, timer_minutes: minutes, timer_ends_at: timerEndsAt },
    }));
    // Re-arm: a changed timer should alarm fresh next time it expires, not immediately.
    delete lastAlarmAtRef.current[st.id];
    setMutedStations((m) => (m[st.id] ? { ...m, [st.id]: false } : m));
  }

  async function extendTimer(st: StationDTO, addMinutes: number) {
    const write = requireGamingWrite('Cannot extend timer');
    if (!write.allowed) return;
    const s = sessions[st.id];
    if (!s) return;
    if (!requireCurrentShiftOwnership(s, 'extend it')) return;
    if (!requireVerifiedActiveBillingMode(s, 'extend it')) return;
    if (addMinutes <= 0 || addMinutes > 1440) return;
    if (LIVE_MODE && s.backend_session_id) {
      setExtendingSession(st.id);
      try {
        const r = await write.dispatch(
          'extendSessionTimer',
          s.backend_session_id,
          s.timer_minutes ?? null,
          addMinutes,
          `gaming-timer:${createOperationKey()}`,
        );
        const timerEndsAt = r.timer_ends_at ? new Date(r.timer_ends_at).getTime() : null;
        setSessions((map) => ({
          ...map,
          [st.id]: {
            ...s,
            timer_minutes: r.timer_minutes,
            timer_ends_at: timerEndsAt,
          },
        }));
        delete lastAlarmAtRef.current[st.id];
        setMutedStations((m) => (m[st.id] ? { ...m, [st.id]: false } : m));
        notifications.success(`${addMinutes} minutes added to ${st.name}.`, {
          title: 'Timer extended',
        });
      } catch (e) {
        notifications.error(
          `${(e as Error).message} Refresh Gaming before trying again.`,
          { title: 'Could not extend timer' },
        );
        void load();
      } finally {
        setExtendingSession(null);
      }
      return;
    }
    const elapsedMinutesNow = Math.max(0, Math.ceil((Date.now() - s.start_at) / 60000));
    const baseMinutes = Math.max(s.timer_minutes ?? 0, elapsedMinutesNow);
    await setStationTimer(st, Math.min(1440, baseMinutes + addMinutes));
  }

  async function submitPaidExtensionAttempt({
    attemptContext,
    station,
    localSession,
    isReplay,
    expectedReplayAttempt,
  }: {
    attemptContext: PaidExtensionAttemptContext;
    station?: StationDTO;
    localSession?: LocalSession;
    isReplay: boolean;
    expectedReplayAttempt?: PaidExtensionAttempt;
  }) {
    const write = requireGamingWrite('Cannot add paid extension');
    if (!write.allowed) return;
    if (extensionBusyRef.current) return;
    extensionBusyRef.current = true;
    let persistedAttempt: PaidExtensionAttempt | null = null;
    const busyKey = station?.id ?? attemptContext.sessionId;
    const subject = station?.name ?? `session ${attemptContext.sessionId.slice(0, 8)}`;
    setExtendingSession(busyKey);
    setPaidExtensionReceiptRevision((revision) => revision + 1);
    const send = async (savedAttempt: PaidExtensionAttempt) => {
      persistedAttempt = savedAttempt;
      return write.dispatch(
        'extendSessionWithPackage',
        savedAttempt.sessionId,
        {
          id: savedAttempt.packageId,
          price_minor: savedAttempt.packagePriceMinor,
          duration_minutes: savedAttempt.packageDurationMinutes,
          variant: savedAttempt.packageVariant,
        },
        {
          timer_minutes: savedAttempt.expectedTimerMinutes,
          amount_minor: savedAttempt.expectedAmountMinor,
        },
        savedAttempt.idempotencyKey,
      );
    };
    const executeLocked = async () => {
      try {
        if (isReplay && !expectedReplayAttempt) {
          throw new PaidExtensionPersistenceError(
            'replay_receipt_missing',
            'The exact saved receipt required for replay is unavailable.',
          );
        }
        const { response: r, attempt } = isReplay
          ? await replayDurablyPersistedPaidExtension({
              storageProvider: () => globalThis.localStorage,
              expectedAttempt: expectedReplayAttempt!,
              send,
            })
          : await sendDurablyPersistedPaidExtension({
              storageProvider: () => globalThis.localStorage,
              context: attemptContext,
              createIdempotencyKey: () => `gaming-extension:${createOperationKey()}`,
              send,
            });
        persistedAttempt = attempt;
        if (station && localSession) {
          setSessions((map) => ({
            ...map,
            [station.id]: {
              ...(map[station.id] ?? localSession),
              timer_minutes: r.timer_minutes,
              timer_ends_at: r.timer_ends_at
                ? new Date(r.timer_ends_at).getTime()
                : (map[station.id] ?? localSession).timer_ends_at,
              locked_amount_minor:
                r.amount_minor ?? (map[station.id] ?? localSession).locked_amount_minor,
            },
          }));
          delete lastAlarmAtRef.current[station.id];
          setMutedStations((muted) =>
            muted[station.id] ? { ...muted, [station.id]: false } : muted,
          );
        }
        setPendingExtension(null);
        try {
          clearPaidExtensionAttempt({
            storageProvider: () => globalThis.localStorage,
            expectedAttempt: attempt,
          });
          notifications.success(
            `${subject} ${isReplay ? 'extension was confirmed' : 'was extended'} by ${attempt.packageDurationMinutes} minutes.`,
            { title: isReplay ? 'Saved extension confirmed' : 'Paid extension added' },
          );
          if (!station) void load();
        } catch {
          notifications.error(
            `${subject} was extended and charged, but this device could not clear its saved recovery receipt. Replaying the saved receipt is safe; do not create a replacement attempt.`,
            { title: 'Extension added · storage needs attention' },
          );
        }
      } catch (error) {
        if (error instanceof PaidExtensionPersistenceError) {
          notifications.error(paidExtensionPersistenceGuidance(error), {
            title: 'Extension not sent',
          });
          if (
            error.code === 'replay_receipt_missing'
            || error.code === 'replay_receipt_changed'
          ) {
            void load();
          }
        } else if (isAmbiguousApiError(error)) {
          notifications.error(
            `${(error as Error).message}. Retry this exact saved extension; the app will reuse its original receipt.`,
            { title: 'Could not confirm the extension' },
          );
        } else if (
          (error as ApiError).code === 'gaming_extension_not_applied'
          && persistedAttempt
        ) {
          let receiptCleared = true;
          try {
            clearPaidExtensionAttempt({
              storageProvider: () => globalThis.localStorage,
              expectedAttempt: persistedAttempt,
            });
          } catch {
            receiptCleared = false;
          }
          setPendingExtension(null);
          notifications.error(
            receiptCleared
              ? `${(error as Error).message} The server proved this saved attempt was not charged. Gaming will refresh before another attempt.`
              : `${(error as Error).message} The server proved this attempt was not charged, but its saved recovery receipt could not be cleared. Fix site/device storage and ask a protected owner to verify it before trying again.`,
            { title: 'Extension not added' },
          );
          void load();
        } else {
          notifications.error(
            `${(error as Error).message}. The server did not prove this saved attempt is uncharged. Do not create a new extension; retry the exact receipt or ask a protected owner to verify the session.`,
            { title: 'Extension result needs verification' },
          );
          void load();
        }
      }
    };

    try {
      await withPaidExtensionSessionLock({
        sessionId: attemptContext.sessionId,
        lockProvider: () => {
          const lockManager = (globalThis.navigator as Navigator & {
            locks?: unknown;
          }).locks;
          return lockManager ? (lockManager as PaidExtensionLockManager) : null;
        },
        action: executeLocked,
      });
    } catch (error) {
      const lockError =
        error instanceof PaidExtensionPersistenceError
          ? error
          : new PaidExtensionPersistenceError(
              'cross_tab_lock_unavailable',
              'The paid-extension cross-tab lock failed.',
            );
      notifications.error(paidExtensionPersistenceGuidance(lockError), {
        title: 'Extension not sent',
      });
    } finally {
      extensionBusyRef.current = false;
      setExtendingSession(null);
      setPaidExtensionReceiptRevision((revision) => revision + 1);
    }
  }

  async function replayOrphanPaidExtension(attempt: PaidExtensionAttempt) {
    const write = requireGamingWrite('Cannot replay paid extension');
    if (!write.allowed) return;
    if (
      !me?.user_id
      || !me.company_id
      || !me.branch_id
      || !terminalReady
      || !terminalId
      || me.user_id !== attempt.actorUserId
      || me.company_id !== attempt.companyId
      || me.branch_id !== attempt.branchId
      || terminalId !== attempt.terminalId
    ) {
      notifications.error(
        'This saved extension belongs to a different employee or terminal scope. Sign in as the original employee on the original terminal, or ask a protected owner to verify it.',
        { title: 'Cannot replay saved extension' },
      );
      return;
    }
    await submitPaidExtensionAttempt({
      attemptContext: attempt,
      isReplay: true,
      expectedReplayAttempt: attempt,
    });
  }

  // A package session is a prepaid, fixed-price slot — extending it is a
  // paid action (buy an extension package), not a free timer bump like
  // extendTimer above (which only applies to open-ended, no-package sessions).
  async function extendPackageSession(st: StationDTO, extensionPackageId: string) {
    const write = requireGamingWrite('Cannot add paid extension');
    if (!write.allowed) return;
    const s = sessions[st.id];
    if (!s?.backend_session_id) return;
    if (extensionBusyRef.current) return;
    if (
      !me?.user_id
      || !me.company_id
      || !me.branch_id
      || !terminalReady
      || !terminalId
      || !s.shift_id
    ) {
      notifications.error(
        'Paid extension was not sent because the staff, shop, device, session, or shift could not be verified. Refresh Gaming and confirm the current shift is open.',
        { title: 'Extension not sent' },
      );
      return;
    }
    const recovery = inspectPaidExtensionRecovery(s);
    if (recovery.error) {
      notifications.error(paidExtensionPersistenceGuidance(recovery.error), {
        title: 'Extension not sent',
      });
      return;
    }
    const submissionMode = paidExtensionSubmissionMode({
      savedAttempt: recovery.attempt,
      requestedPackageId: extensionPackageId,
      ownsCurrentShift: ownsCurrentShift(s),
    });
    if (submissionMode === 'blocked' && recovery.attempt) {
      notifications.error(
        'This session already has a different saved extension awaiting confirmation. Retry the saved extension shown on the station before choosing another package.',
        { title: 'Original extension must be resolved' },
      );
      return;
    }
    if (submissionMode === 'blocked') {
      requireCurrentShiftOwnership(s, 'add paid time');
      return;
    }
    // Exact replay is intentionally allowed after the source shift closes or
    // the session advances: the immutable receipt preserves the original
    // actor/terminal/shift and idempotency key. Only minting a new financial
    // action requires current-shift ownership and a currently verified mode.
    if (submissionMode === 'new') {
      if (!requireCurrentShiftOwnership(s, 'add paid time')) return;
      if (!requireVerifiedActiveBillingMode(s, 'add paid time')) return;
    }
    const extension = packages.find((item) => (
      item.id === extensionPackageId && item.kind === 'extension'
    ));
    if (!recovery.attempt && !extension) {
      notifications.error('That extension is no longer available. Refresh Gaming and choose again.', {
        title: 'Cannot add extension',
      });
      return;
    }
    if (!recovery.attempt && (s.timer_minutes == null || s.locked_amount_minor == null)) {
      notifications.error(
        'The package total is not verified on this screen. Refresh Gaming before adding paid time.',
        { title: 'Cannot confirm extension' },
      );
      return;
    }
    const attemptContext = recovery.attempt ?? {
      actorUserId: me.user_id,
      companyId: me.company_id,
      branchId: me.branch_id,
      terminalId,
      sessionId: s.backend_session_id,
      shiftId: s.shift_id,
      packageId: extension!.id,
      packagePriceMinor: extension!.price_minor,
      packageDurationMinutes: extension!.duration_minutes,
      packageVariant: extension!.variant,
      expectedTimerMinutes: s.timer_minutes!,
      expectedAmountMinor: s.locked_amount_minor!,
    };
    await submitPaidExtensionAttempt({
      attemptContext,
      station: st,
      localSession: s,
      isReplay: submissionMode === 'replay',
      expectedReplayAttempt: submissionMode === 'replay' ? recovery.attempt! : undefined,
    });
  }

  function packagesFor(stationType: string, kind: 'base' | 'extension') {
    return packages.filter((p) => p.station_type === stationType && p.kind === kind);
  }

  function variantsFor(stationType: string) {
    return Array.from(new Set(packagesFor(stationType, 'base').map((p) => p.variant)));
  }

  function pauseSession(st: StationDTO) {
    const write = requireGamingWrite('Cannot pause session');
    if (!write.allowed) return;
    const s = sessions[st.id];
    if (!s || s.status === 'paused') return;
    setSessions((map) => ({
      ...map,
      [st.id]: { ...s, status: 'paused', pause_started_at: Date.now() },
    }));
  }
  function resumeSession(st: StationDTO) {
    const write = requireGamingWrite('Cannot resume session');
    if (!write.allowed) return;
    const s = sessions[st.id];
    if (!s || s.status !== 'paused' || !s.pause_started_at) return;
    setSessions((map) => ({
      ...map,
      [st.id]: {
        ...s, status: 'active',
        pausedMs: s.pausedMs + (Date.now() - s.pause_started_at!),
        pause_started_at: undefined,
      },
    }));
  }
  async function stopSession(st: StationDTO) {
    const s = sessions[st.id];
    if (!s) return;
    const write = requireGamingWrite('Cannot stop session');
    if (!write.allowed) return;
    // A damaged/unreadable durable Add receipt is stronger than an ordinary
    // ledger-load failure: the original POST may have committed, but this
    // browser can no longer prove the exact body/key needed for safe replay.
    // Stop would make an uncommitted Add permanently ineligible on the server,
    // so keep the session running until the retained receipt is reconciled.
    if (s.backend_session_id && addonCreatePersistenceError) {
      notifications.error(
        gamingAddonCreatePersistenceGuidance(addonCreatePersistenceError),
        { title: 'Saved item review required' },
      );
      return;
    }
    if (
      s.backend_session_id
      && (
        addonCreateAttempt?.sessionId === s.backend_session_id
        || addonVoidAttempt?.sessionId === s.backend_session_id
      )
    ) {
      notifications.error(
        'Confirm the pending drink or snack action before ending this session.',
        { title: 'Item confirmation pending' },
      );
      return;
    }
    const resolvedStopShiftId = requireStopShift(s);
    if (!resolvedStopShiftId) return;
    if (!requireVerifiedActiveBillingMode(s, 'stop it')) return;
    if (!requirePaidExtensionResolved(s, 'stopped')) return;
    const elapsedMs = Date.now() - s.start_at - s.pausedMs;
    let elapsedMin = Math.max(1, Math.ceil(elapsedMs / 60000));
    const estimatedAmount = runningBillMinor({
      billingMode: s.billing_mode,
      lockedAmountMinor: s.locked_amount_minor,
      ratePerHourMinor: s.rate_per_hour_minor,
      elapsedMs,
    });
    if (LIVE_MODE && s.backend_session_id) {
      try {
        const ended = await write.dispatch(
          'stopSession',
          s.backend_session_id,
          `gaming-session-stop:${createOperationKey()}`,
        );
        elapsedMin = ended.billable_minutes ?? elapsedMin;
        // A null server amount is a financial repair state, not permission to
        // promote a browser estimate into an authoritative bill.
        const authoritativeAmount = ended.amount_minor;
        delete lastAlarmAtRef.current[st.id];
        setMutedStations((m) => (st.id in m ? { ...m, [st.id]: false } : m));
        // Keep the tile visible in a "stopped" state — staff must explicitly
        // send it to POS, same accountability rule as shift opening.
        setSessions((map) => ({
          ...map,
          [st.id]: {
            ...s,
            // Older APIs omitted shift_id on both Start and Stop. Retain the
            // independently verified current-terminal shift so the resulting
            // Payment Due row can use the normal scoped POS handoff.
            shift_id: ended.shift_id ?? s.shift_id ?? resolvedStopShiftId,
            status: 'ended',
            ended_minutes: elapsedMin,
            ended_amount_minor: authoritativeAmount,
          },
        }));
        notifications.success(
          authoritativeAmount == null
            ? `${st.name} ended after ${elapsedMin} min. Billing is unavailable; a protected owner must review it before POS handoff.`
            : `${st.name} ended after ${elapsedMin} min. Send ${inr(authoritativeAmount)} to POS when ready to bill.`,
          { title: 'Session stopped' },
        );
      }
      catch (e) {
        notifications.error((e as Error).message, { title: 'Could not stop session' });
        return;
      }
      return;
    }
    // Demo mode has no real backend order to send to — keep the old flow.
    notifications.success(
      `Station: ${st.name}\nDuration: ${elapsedMin} min\nSession estimate: ${estimatedAmount == null ? 'Billing unavailable' : inr(estimatedAmount)}`,
      { title: 'Session ended', durationMs: 8_000 },
    );
    setSessions((map) => {
      const next = { ...map };
      delete next[st.id];
      return next;
    });
    delete lastAlarmAtRef.current[st.id];
    setMutedStations((m) => (st.id in m ? { ...m, [st.id]: false } : m));
  }

  async function sendToPos(st: StationDTO) {
    const write = requireGamingWrite('Cannot send session to POS');
    if (!write.allowed) return;
    const s = sessions[st.id];
    if (!s?.backend_session_id) return;
    if (addonCreatePersistenceError) {
      notifications.error(
        gamingAddonCreatePersistenceGuidance(addonCreatePersistenceError),
        { title: 'Saved item review required' },
      );
      return;
    }
    if (
      addonCreateAttempt?.sessionId === s.backend_session_id
      || addonVoidAttempt?.sessionId === s.backend_session_id
    ) {
      notifications.error(
        'Confirm the pending drink or snack action before sending this session to POS.',
        { title: 'Item confirmation pending' },
      );
      return;
    }
    if (
      !isGamingAddonLedgerAuthoritative(
        s.backend_session_id,
        addonAuthoritativeLedgerSessions,
        addonLoadErrors,
      )
    ) {
      notifications.error(
        'Saved drinks and snacks could not be reviewed. Refresh Gaming before sending this session to POS.',
        { title: 'Item review required' },
      );
      return;
    }
    if (!requireCurrentShiftOwnership(s, 'send it to POS')) return;
    if (!requirePaidExtensionResolved(s, 'sent to POS')) return;
    setSendingToPos(st.id);
    setSendErrors((errs) => {
      if (!(st.id in errs)) return errs;
      const next = { ...errs };
      delete next[st.id];
      return next;
    });
    try {
      const posRoute = resolveGamingPosRoute({
        currentTerminalId: terminalId,
        stationBranchId: st.branch_id,
        terminals: terminalOptions ?? [],
        allowCrossTerminalHandoff:
          GAMING_CENTRE_TERMINAL_POLICY.allowCrossTerminalPosHandoff,
      });
      if (posRoute === 'terminal_unverified') {
        throw new Error(
          'This device identity could not be verified. Refresh it; if the problem remains, ask an owner to check the device setup. The gaming bill is still saved and unpaid.',
        );
      }
      if (posRoute === 'profile_conflict') {
        throw new Error(
          'The shared register is not in Combined mode. The gaming bill remains saved and unpaid; ask a protected owner to repair the register setup.',
        );
      }
      if (posRoute === 'handoff') {
        const targets = await gaming.listPosTargetShifts(s.backend_session_id);
        if (targets.length === 0) {
          throw new Error(
            'No POS shift is open on another terminal. Ask the POS cashier to open a shift, then retry. This gaming bill is still saved and unpaid.',
          );
        }
        setPendingPosHandoff({ station: st, targets });
        return;
      }
      await write.dispatch('sendToPos', s.backend_session_id);
      setSessions((map) => {
        const next = { ...map };
        delete next[st.id];
        return next;
      });
      notifications.success(`${st.name} was sent to POS for billing.`, { title: 'Ready in POS' });
    } catch (e) {
      const message = (e as Error).message;
      setSendErrors((errs) => ({
        ...errs,
        [st.id]: { message, code: (e as ApiError).code },
      }));
      notifications.error(message, { title: 'Could not send session to POS' });
    } finally {
      setSendingToPos(null);
    }
  }

  async function confirmPosHandoff(targetShift: GamingPosTargetShiftDTO) {
    const pending = pendingPosHandoff;
    if (!pending) return;
    const write = requireGamingWrite('Cannot send session to POS');
    if (!write.allowed) return;
    const current = sessions[pending.station.id];
    if (!current?.backend_session_id) return;
    if (
      addonCreatePersistenceError
      ||
      addonCreateAttempt?.sessionId === current.backend_session_id
      || addonVoidAttempt?.sessionId === current.backend_session_id
      || !isGamingAddonLedgerAuthoritative(
        current.backend_session_id,
        addonAuthoritativeLedgerSessions,
        addonLoadErrors,
      )
    ) {
      notifications.error(
        'Refresh and confirm the session drinks and snacks before completing this POS handoff.',
        { title: 'Item review required' },
      );
      return;
    }
    if (!requireCurrentShiftOwnership(current, 'send it to POS')) return;
    if (!requirePaidExtensionResolved(current, 'sent to POS')) return;

    setSendingToPos(pending.station.id);
    setSendErrors((errors) => {
      const next = { ...errors };
      delete next[pending.station.id];
      return next;
    });
    try {
      const result = await write.dispatch(
        'handoffToPos',
        current.backend_session_id,
        targetShift.shift_id,
      );
      setSessions((map) => {
        const next = { ...map };
        delete next[pending.station.id];
        return next;
      });
      setPendingPosHandoff(null);
      notifications.success(
        result.already_linked
          ? `${pending.station.name} was already waiting at ${targetShift.terminal_name}; no duplicate bill was created.`
          : `${pending.station.name} is ready to bill at ${targetShift.terminal_name}.`,
        { title: result.already_linked ? 'Already in POS' : 'Sent to POS' },
      );
    } catch (e) {
      const message = (e as Error).message;
      setSendErrors((errors) => ({
        ...errors,
        [pending.station.id]: { message, code: (e as ApiError).code },
      }));
      notifications.error(
        `${message} The gaming bill remains saved and unpaid.`,
        { title: 'Could not hand off to POS' },
      );
    } finally {
      setSendingToPos(null);
    }
  }

  async function prepareReconciliation(st: StationDTO) {
    const write = requireGamingWrite('Cannot reconcile session');
    if (!write.allowed) return;
    if (!me?.audit_access || resolvingReconciliation || reconciling) return;
    const session = sessions[st.id];
    if (!session?.backend_session_id || !requirePaidExtensionResolved(session, 'reconciled')) return;
    if (
      addonCreatePersistenceError
      ||
      addonCreateAttempt?.sessionId === session.backend_session_id
      || addonVoidAttempt?.sessionId === session.backend_session_id
      || !isGamingAddonLedgerAuthoritative(
        session.backend_session_id,
        addonAuthoritativeLedgerSessions,
        addonLoadErrors,
      )
    ) {
      notifications.error(
        'Refresh and confirm the session drinks and snacks before reconciling it to POS.',
        { title: 'Item review required' },
      );
      return;
    }
    setResolvingReconciliation(st.id);
    try {
      const targetShiftId = await ensureShiftId(st, 'reconcile');
      setPendingReconciliation({ station: st, targetShiftId });
    } catch (e) {
      notifications.error((e as Error).message, { title: 'Cannot reconcile session' });
    } finally {
      setResolvingReconciliation(null);
    }
  }

  async function reconcileToPos(
    pending: PendingReconciliation,
    reason: string,
  ) {
    const write = requireGamingWrite('Cannot reconcile session');
    if (!write.allowed) return;
    const current = sessions[pending.station.id];
    if (!current?.backend_session_id || !reason.trim()) return;
    if (
      addonCreatePersistenceError
      ||
      addonCreateAttempt?.sessionId === current.backend_session_id
      || addonVoidAttempt?.sessionId === current.backend_session_id
      || !isGamingAddonLedgerAuthoritative(
        current.backend_session_id,
        addonAuthoritativeLedgerSessions,
        addonLoadErrors,
      )
    ) {
      notifications.error(
        'Refresh and confirm the session drinks and snacks before reconciling it to POS.',
        { title: 'Item review required' },
      );
      return;
    }
    if (!requirePaidExtensionResolved(current, 'reconciled')) return;
    setReconciling(pending.station.id);
    try {
      const result = await write.dispatch(
        'reconcileToPos',
        current.backend_session_id,
        pending.targetShiftId,
        reason.trim(),
      );
      setSessions((all) => {
        const next = { ...all };
        delete next[pending.station.id];
        return next;
      });
      setSendErrors((all) => {
        const next = { ...all };
        delete next[pending.station.id];
        return next;
      });
      setPendingReconciliation(null);
      notifications.success(
        result.already_linked
          ? `${pending.station.name} was already waiting in POS; no duplicate bill was created.`
          : `${pending.station.name} was reconciled to the current open shift and is ready in POS.`,
        { title: result.already_linked ? 'Already in POS' : 'Reconciliation complete' },
      );
    } catch (e) {
      notifications.error((e as Error).message, { title: 'Could not reconcile session' });
    } finally {
      setReconciling(null);
    }
  }

  async function cancelSession(st: StationDTO, reason: string) {
    const write = requireGamingWrite('Cannot cancel session');
    if (!write.allowed) return;
    const current = sessions[st.id];
    if (!current?.backend_session_id) return;
    const activeAddons = addonsBySession[current.backend_session_id]?.filter(
      (addon) => !addon.voided_at,
    ) ?? [];
    if (
      addonCreatePersistenceError
      || !isGamingAddonLedgerAuthoritative(
        current.backend_session_id,
        addonAuthoritativeLedgerSessions,
        addonLoadErrors,
      )
      ||
      addonCreateAttempt?.sessionId === current.backend_session_id
      || addonVoidAttempt?.sessionId === current.backend_session_id
      || activeAddons.length > 0
    ) {
      notifications.error(
        addonCreatePersistenceError
          ? gamingAddonCreatePersistenceGuidance(addonCreatePersistenceError)
          : !isGamingAddonLedgerAuthoritative(
              current.backend_session_id,
              addonAuthoritativeLedgerSessions,
              addonLoadErrors,
            )
            ? 'Saved drinks and snacks could not be reviewed. Refresh Gaming before cancelling this session.'
            : activeAddons.length > 0
          ? 'Void every staged drink or snack with a reason before cancelling this session.'
          : 'Confirm the pending drink or snack action before cancelling this session.',
        { title: 'Session items must be resolved' },
      );
      return;
    }
    if (!requireCurrentShiftOwnership(current, 'cancel it')) return;
    if (!requirePaidExtensionResolved(current, 'cancelled')) return;
    if (!reason.trim()) return;
    setCancelling(st.id);
    setSendErrors((errors) => {
      const next = { ...errors };
      delete next[st.id];
      return next;
    });
    try {
      await write.dispatch('cancelSession', current.backend_session_id, reason.trim());
      setSessions((all) => {
        const next = { ...all };
        delete next[st.id];
        return next;
      });
      setCancelStationTarget(null);
      notifications.success(`${st.name} was cancelled with an audit reason.`, {
        title: 'Session cancelled',
      });
    } catch (e) {
      const message = (e as Error).message;
      setSendErrors((errors) => ({
        ...errors,
        [st.id]: { message, code: (e as ApiError).code },
      }));
      notifications.error(message, { title: 'Could not cancel session' });
    } finally {
      setCancelling(null);
    }
  }

  async function confirmDeleteStation() {
    const write = requireGamingWrite('Cannot delete station');
    if (!write.allowed) return;
    if (!deleteStationTarget || deleteStationBusy) return;
    setDeleteStationBusy(true);
    try {
      await write.dispatch('deleteStation', deleteStationTarget.id);
      const stationCode = deleteStationTarget.code;
      setDeleteStationTarget(null);
      await load();
      notifications.success(`${stationCode} was deleted.`, { title: 'Station deleted' });
    } catch (e) {
      notifications.error((e as Error).message, { title: 'Could not delete station' });
    } finally {
      setDeleteStationBusy(false);
    }
  }

  async function repairMissingBilling(station: StationDTO, amountMinor: number, reason: string) {
    const write = requireGamingWrite('Cannot repair session billing');
    if (!write.allowed) return;
    const session = sessions[station.id];
    if (!me?.audit_access || !session?.backend_session_id || session.ended_amount_minor != null) {
      notifications.error(
        'This session is no longer eligible for protected billing repair. Refresh Gaming.',
        { title: 'Cannot repair billing' },
      );
      return;
    }
    if (!requirePaidExtensionResolved(session, 'repaired')) return;
    const normalizedReason = reason.trim();
    if (normalizedReason.length < 3 || normalizedReason.length > 500) {
      notifications.error('Enter a repair reason between 3 and 500 characters.', {
        title: 'Reason required',
      });
      return;
    }
    const key = repairKeyRef.current ?? `gaming-billing-repair:${createOperationKey()}`;
    repairKeyRef.current = key;
    setRepairingBilling(station.id);
    try {
      const repaired = await write.dispatch(
        'repairSessionBilling',
        session.backend_session_id,
        amountMinor,
        normalizedReason,
        key,
      );
      setSessions((current) => ({
        ...current,
        [station.id]: {
          ...session,
          ended_amount_minor: repaired.amount_minor,
        },
      }));
      repairKeyRef.current = null;
      setRepairStationTarget(null);
      notifications.success(
        `${station.name} billing was repaired to ${inr(repaired.amount_minor ?? amountMinor)}. Review it before sending to POS.`,
        { title: 'Billing repaired' },
      );
    } catch (e) {
      if (isAmbiguousApiError(e)) {
        notifications.error(
          'The repair response was lost. Do not enter another amount; Gaming will refresh to verify the audited result.',
          { title: 'Repair not yet confirmed' },
        );
      } else {
        repairKeyRef.current = null;
        notifications.error((e as Error).message, { title: 'Billing repair refused' });
      }
      await load();
    } finally {
      setRepairingBilling(null);
    }
  }

  function storeSessionAddon(addon: GamingSessionAddonDTO) {
    setAddonsBySession((current) => {
      const existing = current[addon.gaming_session_id] ?? [];
      return {
        ...current,
        [addon.gaming_session_id]: [
          ...existing.filter((candidate) => candidate.id !== addon.id),
          addon,
        ].sort((left, right) => left.created_at.localeCompare(right.created_at)),
      };
    });
  }

  async function refreshSessionAddons(sessionId: string): Promise<GamingSessionAddonDTO[] | null> {
    try {
      const rows = await gaming.listSessionAddons(sessionId);
      setAddonsBySession((current) => ({ ...current, [sessionId]: rows }));
      setAddonLoadErrors((current) => {
        const next = { ...current };
        delete next[sessionId];
        return next;
      });
      setAddonAuthoritativeLedgerSessions((current) => new Set(current).add(sessionId));
      return rows;
    } catch (cause) {
      setAddonLoadErrors((current) => ({
        ...current,
        [sessionId]: cause instanceof Error
          ? cause.message
          : 'Saved drinks and snacks could not be loaded.',
      }));
      setAddonAuthoritativeLedgerSessions((current) => {
        const next = new Set(current);
        next.delete(sessionId);
        return next;
      });
      return null;
    }
  }

  async function refreshAddonCatalog() {
    try {
      setAddonCatalog(availableGamingAddonItems(await menuApi.items()));
      setAddonCatalogError(null);
    } catch (cause) {
      setAddonCatalogError(
        cause instanceof Error ? cause.message : 'The drinks and snacks catalogue could not be loaded.',
      );
    }
  }

  function openAddonPicker(station: StationDTO, sessionId: string) {
    if (addonCreatePersistenceError) {
      notifications.error(
        gamingAddonCreatePersistenceGuidance(addonCreatePersistenceError),
        { title: 'Cannot add item safely' },
      );
      return;
    }
    if (addonCreateAttempt && addonCreateAttempt.sessionId !== sessionId) {
      notifications.error(
        'Confirm the pending item on its original station before adding another item.',
        { title: 'Item confirmation pending' },
      );
      return;
    }
    setAddonCreateError(null);
    setAddonModalTarget({ station, sessionId });
  }

  async function submitSessionAddon(draft: GamingAddonDraft) {
    const target = addonModalTarget;
    if (!target || addonCreateBusyRef.current) return;
    const write = createGamingWriteDispatcher(canManageSessionAddons, () => {
      notifications.error(
        'This account cannot add items to Gaming sessions. Ask an owner to enable Gaming write access.',
        { title: 'Cannot add item' },
      );
    }, gaming);
    if (!write.allowed) return;
    const scope = addonCreateTerminalScope;
    const localSession = sessions[target.station.id];
    if (
      !scope
      || localSession?.backend_session_id !== target.sessionId
      || !localSession.shift_id
    ) {
      const persistenceError = new GamingAddonCreatePersistenceError(
        'current_scope_unverified',
        'The staff, device, Gaming session, or owning shift could not be verified.',
      );
      setAddonCreatePersistenceError(persistenceError);
      setAddonCreateError(gamingAddonCreatePersistenceGuidance(persistenceError));
      return;
    }

    addonCreateBusyRef.current = true;
    setAddingAddonTo(target.sessionId);
    setAddonCreateError(null);
    let attempt: DurableGamingAddonCreateAttempt | null = null;
    try {
      await withGamingAddonCreateLock({
        scope,
        lockProvider: () => (globalThis.navigator as Navigator & {
          locks?: GamingAddonCreateLockManager;
        }).locks,
        action: async () => {
          try {
            const result = await sendDurablyPersistedGamingAddon({
              storageProvider: () => globalThis.localStorage,
              context: {
                ...scope,
                sessionId: target.sessionId,
                shiftId: localSession.shift_id!,
                draft,
              },
              factories: {
                clientLineId: createClientLineId,
                idempotencyKey: () => `gaming-addon-add:${createOperationKey()}`,
              },
              send: async (savedAttempt) => {
                attempt = savedAttempt;
                setAddonCreateAttempt(savedAttempt);
                setAddonCreatePersistenceError(null);
                setAddonCreateReceiptRevision((revision) => revision + 1);
                return write.dispatch(
                  'addSessionAddon',
                  savedAttempt.sessionId,
                  savedAttempt.body,
                  savedAttempt.idempotencyKey,
                );
              },
            });
            attempt = result.attempt;
            storeSessionAddon(result.response);
            clearDurableAddonCreateAttempt(result.attempt);
            setAddonModalTarget(null);
            notifications.success(
              `${result.response.qty} × ${result.response.menu_item_name} was added to ${target.station.name}.`,
              { title: 'Item staged for the combined bill' },
            );
          } catch (cause) {
            if (attempt && isAmbiguousApiError(cause)) {
              const rows = await refreshSessionAddons(target.sessionId);
              const confirmed = rows?.find(
                (addon) => addon.client_line_id === attempt!.body.client_line_id,
              );
              if (confirmed) {
                clearDurableAddonCreateAttempt(attempt);
                setAddonModalTarget(null);
                notifications.success(
                  `${confirmed.qty} × ${confirmed.menu_item_name} was confirmed after refresh.`,
                  { title: 'Item safely recovered' },
                );
              } else {
                setAddonCreateError(
                  'The server response was lost. Retry this exact saved item; its line ID and receipt key will be reused so it cannot be duplicated.',
                );
              }
              return;
            }
            if (cause instanceof GamingAddonCreatePersistenceError) throw cause;
            if (attempt) clearDurableAddonCreateAttempt(attempt);
            setAddonCreateError((cause as Error).message);
            if ((cause as ApiError).status === 409) await refreshAddonCatalog();
          }
        },
      });
    } catch (cause) {
      if (cause instanceof GamingAddonCreatePersistenceError) {
        setAddonCreatePersistenceError(cause);
        setAddonCreateError(gamingAddonCreatePersistenceGuidance(cause));
      } else {
        setAddonCreateError((cause as Error).message);
      }
    } finally {
      addonCreateBusyRef.current = false;
      setAddingAddonTo(null);
    }
  }

  function openAddonVoid(
    station: StationDTO,
    sessionId: string,
    addon: GamingSessionAddonDTO,
  ) {
    if (
      addonVoidAttempt
      && (addonVoidAttempt.sessionId !== sessionId || addonVoidAttempt.addonId !== addon.id)
    ) {
      notifications.error(
        'Confirm the pending item void before voiding another item.',
        { title: 'Void confirmation pending' },
      );
      return;
    }
    setAddonVoidError(null);
    setAddonVoidTarget({ station, sessionId, addon });
  }

  async function submitAddonVoid(reason: string) {
    const target = addonVoidTarget;
    if (!target || addonVoidBusyRef.current) return;
    const write = createGamingWriteDispatcher(canManageSessionAddons, () => {
      notifications.error(
        'This account cannot void Gaming session items. Ask an owner to enable Gaming write access.',
        { title: 'Cannot void item' },
      );
    }, gaming);
    if (!write.allowed) return;

    let attempt: GamingAddonVoidAttempt;
    try {
      attempt = resolveAddonVoidAttempt(
        addonVoidAttempt,
        target.sessionId,
        target.addon.id,
        reason,
        () => `gaming-addon-void:${createOperationKey()}`,
      );
    } catch (cause) {
      setAddonVoidError((cause as Error).message);
      return;
    }

    addonVoidBusyRef.current = true;
    setAddonVoidAttempt(attempt);
    setVoidingAddon(target.addon.id);
    setAddonVoidError(null);
    try {
      const voided = await write.dispatch(
        'voidSessionAddon',
        target.sessionId,
        target.addon.id,
        attempt.reason,
        attempt.idempotencyKey,
      );
      storeSessionAddon(voided);
      setAddonVoidAttempt(null);
      setAddonVoidTarget(null);
      notifications.success(
        `${target.addon.menu_item_name} was removed from the combined bill and kept in the audit trail.`,
        { title: 'Item voided' },
      );
    } catch (cause) {
      if (isAmbiguousApiError(cause)) {
        const rows = await refreshSessionAddons(target.sessionId);
        const confirmed = rows?.find(
          (addon) => addon.id === target.addon.id && addon.voided_at,
        );
        if (confirmed) {
          setAddonVoidAttempt(null);
          setAddonVoidTarget(null);
          notifications.success(
            `${confirmed.menu_item_name} was confirmed voided after refresh.`,
            { title: 'Void safely recovered' },
          );
        } else {
          setAddonVoidError(
            'The server response was lost. Retry this exact saved void; its reason and receipt key will be reused.',
          );
        }
      } else {
        setAddonVoidAttempt(null);
        setAddonVoidError((cause as Error).message);
      }
    } finally {
      addonVoidBusyRef.current = false;
      setVoidingAddon(null);
    }
  }

  const activeCount = useMemo(
    () => Object.values(sessions).filter((s) => s.status === 'active').length,
    [sessions],
  );
  const packageStartRecoveryReady = !LIVE_MODE || Boolean(
    me?.user_id
    && me.company_id
    && me.branch_id
    && terminalReady
    && terminalId
    && !paidExtensionInventory.error
  );
  const overtimeStations = (() => {
    const now = Date.now();
    return Object.values(sessions)
      .filter((s) => s.timer_ends_at && now >= s.timer_ends_at)
      .map((s) => stations.find((st) => st.id === s.station_id)?.name ?? 'Unknown station');
  })();

  return (
    <div>
      <header className="flex items-end justify-between mb-6 flex-wrap gap-4">
        <div>
          <h2 className="text-2xl font-bold">Gaming lounge</h2>
          <p className="text-fg-muted text-sm">
            {stations.length} stations · {activeCount} active session{activeCount === 1 ? '' : 's'}
          </p>
        </div>
        <div className="flex gap-2">
          <button className="btn btn-ghost" onClick={load}><RefreshCw size={14}/></button>
          <GamingWriteOnly allowed={canManageStations}>
            <button className={`btn ${manageMode ? 'btn-primary' : 'btn-ghost'}`}
              onClick={() => setManageMode(!manageMode)}>
              <Settings size={14}/> {manageMode ? 'Done' : 'Manage'}
            </button>
          </GamingWriteOnly>
          <GamingWriteOnly allowed={canManageStations && manageMode}>
            <button className="btn btn-primary" onClick={() => setAddOpen(true)}>
              <Plus size={14}/> New station
            </button>
          </GamingWriteOnly>
        </div>
      </header>

      {error && (
        <div className="card mb-4 border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm flex items-center gap-2">
          <AlertCircle size={14}/> {error}
        </div>
      )}

      {LIVE_MODE && addonCatalogError && (
        <div className="card mb-4 border-accent-gold/40 bg-accent-gold/10 text-sm flex items-start gap-2">
          <AlertCircle size={14} className="mt-0.5 shrink-0 text-accent-gold"/>
          <div>
            <div className="font-semibold text-accent-gold">Drinks and snacks are temporarily unavailable</div>
            <div className="mt-1 text-fg-muted">
              {addonCatalogError} Existing sessions and saved items remain safe. Refresh before adding another item.
            </div>
          </div>
        </div>
      )}

      {LIVE_MODE && addonCreatePersistenceError && (
        <div className="card mb-4 border-accent-bad/40 bg-accent-bad/10 text-sm flex items-start gap-2">
          <AlertCircle size={14} className="mt-0.5 shrink-0 text-accent-bad"/>
          <div>
            <div className="font-semibold text-accent-bad">Saved Gaming item needs review</div>
            <div className="mt-1 text-fg-muted">
              {gamingAddonCreatePersistenceGuidance(addonCreatePersistenceError)} Ending, sending, or cancelling the session is blocked until the recovery receipt can be verified.
            </div>
          </div>
        </div>
      )}

      {!canManageStations && (
        <div className="card mb-4 border-accent-gold/40 bg-accent-gold/10 text-sm flex items-start gap-2">
          <AlertCircle size={14} className="mt-0.5 shrink-0 text-accent-gold"/>
          <div>
            <div className="font-semibold text-accent-gold">Gaming is view-only</div>
            <div className="mt-1 text-fg-muted">
              You can review stations and sessions, but this account does not have gaming.write. Ask an owner to enable the Gaming module for this role.
            </div>
          </div>
        </div>
      )}

      {LIVE_MODE
        && GAMING_CENTRE_TERMINAL_POLICY.allowCrossTerminalPosHandoff
        && currentTerminal?.purpose === 'cafe_pos' && (
        <div className="card mb-4 border-accent-gold/40 bg-accent-gold/10 text-sm flex items-start gap-2">
          <AlertCircle size={14} className="mt-0.5 shrink-0 text-accent-gold"/>
          <div>
            <div className="font-semibold text-accent-gold">Gaming starts are unavailable on this device</div>
            <div className="mt-1 text-fg-muted">
              Sessions are monitored here, but this device is configured for counter sales. Ask an owner to enable Gaming or Combined mode before starting sessions.
            </div>
          </div>
        </div>
      )}

      {paidExtensionInventory.error && (
        <div className="card mb-4 border-accent-bad/40 bg-accent-bad/10 text-sm text-accent-bad flex items-start gap-2">
          <AlertCircle size={14} className="mt-0.5 shrink-0"/>
          <div>
            <div className="font-semibold">Paid-extension recovery storage is unavailable</div>
            <div className="mt-1 text-fg-muted">
              {paidExtensionPersistenceGuidance(paidExtensionInventory.error)} Existing sessions remain protected from financial completion until storage can be verified.
            </div>
          </div>
        </div>
      )}

      {paidExtensionInventory.issueCodes.length > 0 && (
        <div className="card mb-4 border-accent-bad/40 bg-accent-bad/10 text-sm text-accent-bad flex items-start gap-2">
          <AlertCircle size={14} className="mt-0.5 shrink-0"/>
          <div>
            <div className="font-semibold">Saved extension receipt needs protected review</div>
            <div className="mt-1 text-fg-muted">
              This device has {paidExtensionInventory.issueCodes.length} older, damaged, or other-scope paid-extension receipt{paidExtensionInventory.issueCodes.length === 1 ? '' : 's'}. Do not clear browser data or create a replacement charge. Use the original employee and matching shift, or ask a protected owner to verify the affected session.
            </div>
          </div>
        </div>
      )}

      {orphanPaidExtensionAttempts.length > 0 && (
        <div className="card mb-4 border-accent-gold/40 bg-accent-gold/10 text-sm">
          <div className="flex items-start gap-2">
            <RefreshCw size={14} className="mt-0.5 shrink-0 text-accent-gold"/>
            <div className="min-w-0 flex-1">
              <div className="font-semibold text-accent-gold">Saved paid extension awaiting confirmation</div>
              <div className="mt-1 text-fg-muted">
                The session is no longer on this board, but its immutable recovery receipt is still safe. Replay it once to confirm the original server result; no new charge or key will be created.
              </div>
              <div className="mt-3 space-y-2">
                {orphanPaidExtensionAttempts.map((attempt) => (
                  <div key={attempt.sessionId} className="flex items-center justify-between gap-3 rounded-lg border border-accent-gold/30 bg-bg-raised p-2.5 flex-wrap">
                    <div>
                      <div className="font-medium">Session {attempt.sessionId.slice(0, 8)}</div>
                      <div className="text-xs text-fg-muted">
                        Saved {attempt.packageDurationMinutes}-minute extension · original shift retained
                      </div>
                    </div>
                    <GamingMutationButton
                      canManageSessions={canManageStations}
                      className="btn btn-ghost !py-1.5 border-accent-gold/50 text-accent-gold"
                      disabled={extendingSession !== null}
                      onClick={() => { void replayOrphanPaidExtension(attempt); }}
                    >
                      {extendingSession === attempt.sessionId
                        ? <Loader2 size={12} className="animate-spin"/>
                        : <RefreshCw size={12}/>} Replay exact receipt
                    </GamingMutationButton>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      )}

      {overtimeStations.length > 0 && (
        <div className="card mb-4 border-accent-bad/50 bg-accent-bad/10 text-accent-bad text-sm flex items-center gap-2 font-bold">
          <BellRing size={16} className="animate-pulse"/>
          {overtimeStations.length} station{overtimeStations.length === 1 ? '' : 's'} timed out: {overtimeStations.join(', ')}
        </div>
      )}

      {notifPermission === 'default' && (
        <div className="card mb-4 border-accent/40 bg-accent/10 text-sm flex items-center justify-between gap-3 flex-wrap">
          <span className="flex items-center gap-2"><Bell size={14}/> Turn on alerts so staff get notified the moment a session's timer ends.</span>
          <button className="btn btn-ghost !py-1.5" onClick={enableAlarmNotifications}>Enable notifications</button>
        </div>
      )}

      {loading ? (
        <SkeletonCard />
      ) : !stations.length ? (
        <div className="card text-fg-muted text-sm">
          {canManageStations
            ? <>No stations yet. Click <b>Manage → New station</b> to add PS5, VR, simulator, shisha, or streaming stations.</>
            : 'No stations configured yet.'}
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3 md:gap-4">
          {stations.map((st) => {
            const session = sessions[st.id];
            const billingMissing = session?.status === 'ended'
              && session.ended_amount_minor == null;
            const legacyBillingAmbiguous = session?.billing_mode === 'legacy_ambiguous';
            const phone = sessionPhone[st.id] ?? '';
            const elapsedMs = session
              ? (session.status === 'paused' && session.pause_started_at
                  ? session.pause_started_at - session.start_at - session.pausedMs
                  : Date.now() - session.start_at - session.pausedMs)
              : 0;
            const elapsedMin = Math.floor(elapsedMs / 60000);
            // A package session's price is locked in at start (see backend
            // gaming/router.py stop_session) and never grows with elapsed
            // time — showing the elapsed*rate estimate here would be flat
            // wrong, not just approximate.
            const amount = session ? runningBillMinor({
              billingMode: session.billing_mode,
              lockedAmountMinor: session.locked_amount_minor,
              ratePerHourMinor: session.rate_per_hour_minor,
              elapsedMs,
            }) : null;
            const activeBillingUnavailable = Boolean(
              session && session.status !== 'ended' && (
                session.billing_mode === 'legacy_ambiguous'
                || (session.billing_mode === 'package' && session.locked_amount_minor == null)
                || (session.billing_mode === 'hourly' && session.rate_per_hour_minor == null)
              ),
            );
            const sessionOwned = session ? ownsCurrentShift(session) : true;
            const resolvedStopShiftId = session ? stopShiftIdFor(session) : null;
            const paidExtensionRecovery = session?.backend_session_id
              ? paidExtensionRecoveryBySession[session.backend_session_id]
              : undefined;
            const savedPaidExtension = paidExtensionRecovery?.attempt ?? null;
            const paidExtensionRecoveryError = paidExtensionRecovery?.error ?? null;
            const paidExtensionLifecycleBlocked = isPaidExtensionLifecycleBlocked({
              billingMode: session?.billing_mode,
              hasSavedAttempt: Boolean(savedPaidExtension),
              hasRecoveryError: Boolean(paidExtensionRecoveryError),
            });
            const backendSessionId = session?.backend_session_id ?? null;
            const sessionAddons = backendSessionId ? (addonsBySession[backendSessionId] ?? []) : [];
            const addonLoadError = backendSessionId ? (addonLoadErrors[backendSessionId] ?? null) : null;
            const addonListReady = Boolean(
              backendSessionId
              && isGamingAddonLedgerAuthoritative(
                backendSessionId,
                addonAuthoritativeLedgerSessions,
                addonLoadErrors,
              ),
            );
            const activeSessionAddons = sessionAddons.filter((addon) => !addon.voided_at);
            const pendingAddonCreate = Boolean(
              backendSessionId && addonCreateAttempt?.sessionId === backendSessionId,
            );
            const pendingAddonVoidId = backendSessionId && addonVoidAttempt?.sessionId === backendSessionId
              ? addonVoidAttempt.addonId
              : null;
            const addonMutationPending = pendingAddonCreate || Boolean(pendingAddonVoidId);
            const addonMutationBusy = Boolean(
              backendSessionId
              && (addingAddonTo === backendSessionId || (
                pendingAddonVoidId && voidingAddon === pendingAddonVoidId
              )),
            );
            const canMutateSessionAddons = Boolean(
              backendSessionId
              && canManageSessionAddons
              && sessionOwned
              && canStartOnSelectedTerminal
              && addonListReady
              && !addonCreatePersistenceError
              && !addonLoadError,
            );
            const sessionScopeMessage = !canManageStations
              ? 'Gaming is view-only for this account. An owner can enable the Gaming module for this role.'
              : session && !session.shift_id && resolvedStopShiftId
              ? 'This server omitted the session shift. End session is available because the current shift is confirmed; the server will verify it before saving.'
              : currentShiftId
                ? 'This session belongs to a different or no-longer-open shift. It is view-only until a protected owner reviews it.'
                : (shiftContextError ?? 'There is no verified open shift, so existing sessions are view-only.');

            return (
              <div key={st.id} className={`card ${session ? 'border-accent/40' : ''}`}>
                <div className="flex items-start gap-3 mb-3">
                  <div className="p-3 rounded-xl bg-bg-raised text-accent">{ICON[st.type]}</div>
                  <div className="flex-1 min-w-0">
                    <div className="font-bold truncate">{st.name}</div>
                    <div className="text-xs text-fg-muted truncate">
                      {st.code} · {TYPE_LABEL[st.type]} · {inr(st.rate_per_hour_minor)}/hr
                    </div>
                    {!st.is_active && (
                      <span className="chip text-[10px] border-accent-bad/40 text-accent-bad mt-1">
                        Inactive
                      </span>
                    )}
                  </div>
                  {canManageStations && manageMode && (
                    <div className="flex flex-col gap-1">
                      <button className="text-fg-muted hover:text-accent p-1"
                        onClick={() => setEdit(st)}>
                        <Edit2 size={12}/>
                      </button>
                      <button className="text-fg-muted hover:text-accent-bad p-1"
                        onClick={() => setDeleteStationTarget(st)}>
                        <Trash2 size={12}/>
                      </button>
                    </div>
                  )}
                </div>

                {session && (!sessionOwned || !canManageStations) && (
                  <div className="mb-3 rounded-lg border border-accent-gold/30 bg-accent-gold/10 p-2.5 text-xs text-accent-gold flex items-start gap-1.5">
                    <AlertCircle size={12} className="mt-0.5 shrink-0"/>
                    {sessionScopeMessage}
                  </div>
                )}

                {session && paidExtensionLifecycleBlocked && (
                  <div className="mb-3 rounded-lg border border-accent-gold/40 bg-accent-gold/10 p-2.5 text-xs text-accent-gold">
                    <div className="flex items-start gap-1.5">
                      <AlertCircle size={12} className="mt-0.5 shrink-0"/>
                      <div className="min-w-0 flex-1">
                        <div className="font-semibold">Paid extension needs confirmation</div>
                        <div className="mt-1 text-fg-muted">
                          {savedPaidExtension
                            ? `A saved ${savedPaidExtension.packageDurationMinutes}-minute extension must be replayed with its original receipt. Other extensions and session completion are blocked until the server confirms it.`
                            : paidExtensionPersistenceGuidance(paidExtensionRecoveryError!)}
                        </div>
                      </div>
                    </div>
                    {savedPaidExtension && (
                      <GamingMutationButton
                        canManageSessions={canManageStations}
                        className="btn btn-ghost mt-2 w-full !py-1.5 border-accent-gold/50 text-accent-gold"
                        disabled={extendingSession !== null}
                        onClick={() => { void extendPackageSession(st, savedPaidExtension.packageId); }}
                      >
                        {extendingSession === st.id
                          ? <Loader2 size={12} className="animate-spin"/>
                          : <RefreshCw size={12}/>} Retry exact saved extension
                      </GamingMutationButton>
                    )}
                  </div>
                )}

                {LIVE_MODE && session && backendSessionId && (
                  <SessionAddonsPanel
                    addons={sessionAddons}
                    ready={addonListReady}
                    error={addonLoadError}
                    canMutate={canMutateSessionAddons}
                    canAdd={canMutateSessionAddons && session.status !== 'ended'}
                    catalogReady={addonCatalog.length > 0 && !addonCatalogError}
                    pendingCreate={pendingAddonCreate}
                    pendingVoidAddonId={pendingAddonVoidId}
                    busy={addonMutationBusy}
                    onAdd={() => openAddonPicker(st, backendSessionId)}
                    onVoid={(addon) => openAddonVoid(st, backendSessionId, addon)}
                  />
                )}

                {session?.status === 'ended' ? (
                  <>
                    <div className="bg-bg-raised rounded-lg p-3 mb-3">
                      <div className="text-xs text-fg-muted">Session ended</div>
                      <div className="flex justify-between items-baseline mt-1">
                        <div className="text-xl font-bold font-mono">
                          {session.ended_minutes ?? 0} min
                        </div>
                        <div className={`text-right font-mono ${billingMissing
                          ? 'text-sm font-semibold text-accent-bad'
                          : 'text-2xl font-bold text-fg'}`}>
                          {billingMissing ? 'Billing unavailable' : inr(session.ended_amount_minor!)}
                        </div>
                      </div>
                      {billingMissing && (
                        <div className="mt-2 pt-2 border-t border-bg-border text-xs text-accent-bad flex items-start gap-1.5">
                          <AlertCircle size={12} className="mt-0.5 shrink-0"/>
                          {me?.audit_access
                            ? 'Verify the original charge and use Owner repair billing before this session can be sent or cancelled.'
                            : 'A protected owner must verify and repair this missing bill before it can be sent or cancelled.'}
                        </div>
                      )}
                      {legacyBillingAmbiguous && (
                        <div className="mt-2 pt-2 border-t border-bg-border text-xs text-accent-gold flex items-start gap-1.5">
                          <AlertCircle size={12} className="mt-0.5 shrink-0"/>
                          Legacy billing mode could not be proven. The server preserves this locked total and will not infer any historical programme benefits.
                        </div>
                      )}
                      {sendErrors[st.id] && (
                        <div className="mt-2 pt-2 border-t border-bg-border text-xs text-accent-bad flex items-center gap-1.5">
                          <AlertCircle size={12}/> {sendErrors[st.id].message}
                        </div>
                      )}
                    </div>
                    <div className="space-y-2">
                      <div className="grid grid-cols-[1fr_auto] gap-2">
                        <GamingMutationButton
                          canManageSessions={canManageStations}
                          className="btn btn-primary"
                          disabled={!sessionOwned || billingMissing || paidExtensionLifecycleBlocked || Boolean(addonCreatePersistenceError) || addonMutationPending || !addonListReady || Boolean(addonLoadError) || sendingToPos === st.id || cancelling === st.id || reconciling === st.id}
                          onClick={() => sendToPos(st)}>
                          {sendingToPos === st.id
                            ? <Loader2 className="animate-spin" size={14}/>
                            : <Send size={14}/>} Send to POS
                        </GamingMutationButton>
                        <GamingMutationButton
                          canManageSessions={canManageStations}
                          className="btn btn-ghost text-accent-bad"
                          disabled={!sessionOwned || billingMissing || paidExtensionLifecycleBlocked || Boolean(addonCreatePersistenceError) || addonMutationPending || !addonListReady || Boolean(addonLoadError) || activeSessionAddons.length > 0 || sendingToPos === st.id || cancelling === st.id || reconciling === st.id}
                          title={activeSessionAddons.length > 0
                            ? 'Void every staged drink or snack before cancelling this session'
                            : 'Cancel with an audit reason'}
                          onClick={() => setCancelStationTarget(st)}>
                          {cancelling === st.id
                            ? <Loader2 className="animate-spin" size={14}/>
                            : <Ban size={14}/>} Cancel
                        </GamingMutationButton>
                      </div>
                      {(canOfferGamingReconciliation({
                        auditAccess: Boolean(me?.audit_access),
                        rejectionCode: sendErrors[st.id]?.code,
                      }) || Boolean(me?.audit_access && !sessionOwned)) && (
                        <GamingMutationButton
                          canManageSessions={canManageStations}
                          className="btn btn-ghost w-full border-accent-gold/50 text-accent-gold"
                          disabled={Boolean(
                            sendingToPos === st.id
                            || cancelling === st.id
                            || paidExtensionLifecycleBlocked
                            || Boolean(addonCreatePersistenceError)
                            || addonMutationPending
                            || !addonListReady
                            || Boolean(addonLoadError)
                            || resolvingReconciliation
                            || reconciling,
                          )}
                          title="Protected-owner recovery: keep the original shift as history and create the bill on the current open shift"
                          onClick={() => { void prepareReconciliation(st); }}
                        >
                          {resolvingReconciliation === st.id
                            ? <Loader2 className="animate-spin" size={14}/>
                            : <RefreshCw size={14}/>} Reconcile to current shift
                        </GamingMutationButton>
                      )}
                      {billingMissing && me?.audit_access && (
                        <GamingMutationButton
                          canManageSessions={canManageStations}
                          className="btn btn-ghost w-full border-accent-bad/50 text-accent-bad"
                          disabled={paidExtensionLifecycleBlocked || repairingBilling !== null}
                          onClick={() => {
                            repairKeyRef.current = null;
                            setRepairStationTarget(st);
                          }}
                        >
                          {repairingBilling === st.id
                            ? <Loader2 className="animate-spin" size={14}/>
                            : <AlertCircle size={14}/>} Owner repair billing
                        </GamingMutationButton>
                      )}
                    </div>
                  </>
                ) : session ? (
                  <>
                    <div className="bg-bg-raised rounded-lg p-3 mb-3">
                      <div className="flex justify-between items-baseline">
                        <div>
                          <div className="text-xs text-fg-muted">Elapsed</div>
                          <div className="text-2xl font-bold font-mono">
                            {Math.floor(elapsedMin / 60)}:{String(elapsedMin % 60).padStart(2, '0')}
                          </div>
                        </div>
                        <div className="text-right">
                          <div className="text-xs text-fg-muted">
                            {session?.locked_amount_minor != null ? 'Package price' : 'Running bill'}
                          </div>
                          <div className={`font-mono ${activeBillingUnavailable
                            ? 'text-sm font-semibold text-accent-gold'
                            : 'text-2xl font-bold text-accent'}`}>
                            {legacyBillingAmbiguous
                              ? 'Billing mode unverified'
                              : activeBillingUnavailable || amount == null ? 'Billing unavailable' : inr(amount)}
                          </div>
                        </div>
                      </div>
                      {session.status === 'paused' && (
                        <div className="mt-2 text-xs text-accent-gold flex items-center gap-1">
                          <Pause size={11}/> Paused
                        </div>
                      )}

                      {session.timer_ends_at ? (() => {
                        const remainingMs = session.timer_ends_at! - Date.now();
                        const overtime = remainingMs <= 0;
                        const lowTime = !overtime && remainingMs <= 5 * 60000;
                        const clock = fmtClock(Math.abs(Math.round(remainingMs / 1000)));
                        const baseVariant = session.package_variant_snapshot
                          ?? (session.package_id
                            ? packages.find((item) => item.id === session.package_id && item.kind === 'base')?.variant
                            : undefined);
                        const extensionOptions = baseVariant
                          ? packagesFor(st.type, 'extension').filter((item) => item.variant === baseVariant)
                          : [];
                        return (
                          <div className={`mt-2 pt-2 border-t border-bg-border flex items-center justify-between gap-2 flex-wrap ${
                            overtime ? 'text-accent-bad' : lowTime ? 'text-accent-gold' : 'text-fg-muted'
                          }`}>
                            <div className="flex items-center gap-1.5 text-sm font-mono font-bold">
                              <Timer size={13}/> {overtime ? `+${clock} over` : `${clock} left`}
                            </div>
                            <div className="flex items-center gap-1 flex-wrap">
                              {overtime && (
                                <button className="text-fg-muted hover:text-accent p-0.5"
                                  onClick={() => toggleMute(st.id)}
                                  title={mutedStations[st.id] ? 'Unmute alarm' : 'Mute alarm for this station'}>
                                  {mutedStations[st.id] ? <BellOff size={13}/> : <Bell size={13}/>}
                                </button>
                              )}
                              {session.billing_mode === 'legacy_ambiguous' ? (
                                <span className="text-[10px] text-accent-gold">Owner review required</span>
                              ) : session.billing_mode === 'package' ? (
                                extensionOptions.length > 0 ? extensionOptions.map((ext) => (
                                  <GamingMutationButton
                                    canManageSessions={canManageStations}
                                    key={ext.id}
                                    className="chip text-[10px] !border-accent-gold/50 text-accent-gold hover:!border-accent-gold"
                                    disabled={Boolean(
                                      extendingSession !== null
                                      || paidExtensionRecoveryError
                                      || paidExtensionSubmissionMode({
                                        savedAttempt: savedPaidExtension,
                                        requestedPackageId: ext.id,
                                        ownsCurrentShift: sessionOwned,
                                      }) === 'blocked'
                                    )}
                                    onClick={() => {
                                      if (savedPaidExtension?.packageId === ext.id) {
                                        void extendPackageSession(st, ext.id);
                                      } else {
                                        setPendingExtension({ station: st, extension: ext });
                                      }
                                    }}
                                    title={savedPaidExtension?.packageId === ext.id
                                      ? 'Retry this exact saved paid-extension receipt'
                                      : `Paid extension: ${ext.name} · ${inr(ext.price_minor)}`}>
                                    {extendingSession === st.id ? (
                                      <Loader2 size={11} className="animate-spin" />
                                    ) : savedPaidExtension?.packageId === ext.id
                                      ? `Retry +${ext.duration_minutes}m`
                                      : `+${ext.duration_minutes}m · ${inr(ext.price_minor)}`}
                                  </GamingMutationButton>
                                )) : (
                                  <span className="text-[10px] text-fg-muted">No extension for this package</span>
                                )
                              ) : (
                                <>
                                  <GamingMutationButton
                                    canManageSessions={canManageStations}
                                    className="chip text-[10px] hover:border-accent"
                                    disabled={!sessionOwned || paidExtensionLifecycleBlocked || extendingSession !== null}
                                    onClick={() => { void extendTimer(st, 15); }} title="Add 15 minutes">
                                    +15m
                                  </GamingMutationButton>
                                  <GamingMutationButton
                                    canManageSessions={canManageStations}
                                    className="text-fg-muted hover:text-accent-bad p-0.5"
                                    disabled={!sessionOwned || paidExtensionLifecycleBlocked || extendingSession !== null}
                                    onClick={() => setStationTimer(st, null)} title="Clear timer">
                                    <X size={13}/>
                                  </GamingMutationButton>
                                </>
                              )}
                            </div>
                          </div>
                        );
                      })() : (
                        <div className="mt-2 pt-2 border-t border-bg-border flex items-center justify-between gap-2 text-xs text-fg-muted">
                          <span className="flex items-center gap-1"><TimerOff size={12}/> No timer</span>
                          {session.billing_mode === 'legacy_ambiguous' ? (
                            <span className="text-[10px] text-accent-gold">Owner review required</span>
                          ) : (
                            <div className="flex gap-1">
                              {[30, 60, 120].map((m) => (
                                <GamingMutationButton
                                  canManageSessions={canManageStations}
                                  key={m}
                                  className="chip text-[10px] hover:border-accent"
                                  disabled={!sessionOwned || paidExtensionLifecycleBlocked || extendingSession !== null}
                                  onClick={() => { void extendTimer(st, m); }}>
                                  +{m >= 60 ? `${m / 60}h` : `${m}m`}
                                </GamingMutationButton>
                              ))}
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                    <div className="flex gap-2">
                      {!LIVE_MODE && (session.status === 'active' ? (
                        <GamingMutationButton
                          canManageSessions={canManageStations}
                          className="btn btn-ghost flex-1"
                          onClick={() => pauseSession(st)}
                        >
                          <Pause size={14}/> Pause
                        </GamingMutationButton>
                      ) : (
                        <GamingMutationButton
                          canManageSessions={canManageStations}
                          className="btn btn-ghost flex-1"
                          onClick={() => resumeSession(st)}
                        >
                          <PlayCircle size={14}/> Resume
                        </GamingMutationButton>
                      ))}
                      <GamingMutationButton
                        canManageSessions={canManageStations}
                        className="btn btn-primary flex-1 !bg-accent-bad hover:!bg-accent-bad/80"
                        disabled={!resolvedStopShiftId || legacyBillingAmbiguous || paidExtensionLifecycleBlocked || addonMutationPending}
                        onClick={() => stopSession(st)}>
                        <Square size={14}/> End session
                      </GamingMutationButton>
                    </div>
                  </>
                ) : variantsFor(st.type).length > 0 ? (() => {
                  const variants = variantsFor(st.type);
                  const variant = pickerVariant[st.id] ?? variants[0];
                  const tiers = packagesFor(st.type, 'base').filter((p) => p.variant === variant);
                  const controllers = pickerControllers[st.id] ?? 0;
                  const showControllerStepper = st.type === 'ps5' && variant === 'dual';
                  return (
                    <>
                      <input type="tel" placeholder="Member phone (optional) — points accrue automatically"
                        className="input !py-1.5 text-xs w-full mb-2"
                        disabled={!canManageStations || !canStartOnSelectedTerminal}
                        value={phone}
                        onChange={(e) => setSessionPhone((s) => ({ ...s, [st.id]: e.target.value }))}/>
                      {variants.length > 1 && (
                        <div className="flex items-center gap-1.5 mb-2">
                          {variants.map((v) => (
                            <button key={v}
                              disabled={!canManageStations || !canStartOnSelectedTerminal}
                              className={`chip text-[11px] capitalize ${variant === v ? '!border-accent !text-accent' : 'hover:border-accent'}`}
                              onClick={() => {
                                setPickerVariant((s) => ({ ...s, [st.id]: v }));
                                // Extra-controller count only makes sense for
                                // the dual variant's stepper — stop it from
                                // silently surviving a switch to single.
                                setPickerControllers((s) => ({ ...s, [st.id]: 0 }));
                              }}>
                              {v}
                            </button>
                          ))}
                        </div>
                      )}
                      <div className="grid grid-cols-1 gap-1.5 mb-2">
                        {tiers.map((tier) => (
                          <GamingMutationButton
                            canManageSessions={canManageStations}
                            key={tier.id}
                            className="btn btn-ghost !justify-between !py-1.5 text-xs"
                            onClick={() => startSession(st, '', { packageId: tier.id, extraControllers: showControllerStepper ? controllers : 0 }, phone)}
                            disabled={!st.is_active || !packageStartRecoveryReady || !canStartOnSelectedTerminal}
                            title={packageStartRecoveryReady
                              ? canStartOnSelectedTerminal
                                ? `Start ${tier.name}`
                                : 'This device is configured for counter sales. Ask an owner to enable Gaming or Combined mode.'
                              : 'Package sessions require verified paid-extension recovery storage on this device'}>
                            <span>{tier.name}</span>
                            <span className="font-mono font-bold">
                              {inr(tier.price_minor + extraControllerSurchargeMinor(
                                showControllerStepper ? controllers : 0,
                                tier.duration_minutes,
                              ))}
                            </span>
                          </GamingMutationButton>
                        ))}
                      </div>
                      {!packageStartRecoveryReady && (
                        <div className="mb-2 rounded-lg border border-accent-bad/30 bg-accent-bad/10 p-2 text-xs text-accent-bad flex items-start gap-1.5">
                          <AlertCircle size={12} className="mt-0.5 shrink-0"/>
                          Package start is unavailable until this device context and its recovery storage are verified. Hourly sessions remain available.
                        </div>
                      )}
                      {showControllerStepper && (
                        <div className="flex items-center justify-between gap-2 mb-2 text-xs text-fg-muted">
                          <span>Extra controllers (₹30/hr, min ₹30)</span>
                          <div className="flex items-center gap-2">
                            <button className="chip !px-2 text-[11px]"
                              disabled={!canManageStations || !canStartOnSelectedTerminal}
                              onClick={() => setPickerControllers((s) => ({ ...s, [st.id]: Math.max(0, controllers - 1) }))}>
                              −
                            </button>
                            <span className="w-4 text-center font-mono">{controllers}</span>
                            <button className="chip !px-2 text-[11px]"
                              disabled={!canManageStations || !canStartOnSelectedTerminal}
                              onClick={() => setPickerControllers((s) => ({ ...s, [st.id]: Math.min(6, controllers + 1) }))}>
                              +
                            </button>
                          </div>
                        </div>
                      )}
                    </>
                  );
                })() : (
                  <>
                    <input type="tel" placeholder="Member phone (optional) — points accrue automatically"
                      className="input !py-1.5 text-xs w-full mb-2"
                      disabled={!canManageStations || !canStartOnSelectedTerminal}
                      value={phone}
                      onChange={(e) => setSessionPhone((s) => ({ ...s, [st.id]: e.target.value }))}/>
                    <div className="flex items-center gap-1.5 mb-2 flex-wrap">
                      {DURATION_PRESETS.map((p) => (
                        <button key={p.label}
                          disabled={!canManageStations || !canStartOnSelectedTerminal}
                          className={`chip text-[11px] ${(pendingDuration[st.id] ?? null) === p.minutes && customDurationFor !== st.id ? '!border-accent !text-accent' : 'hover:border-accent'}`}
                          onClick={() => { setPendingDuration((s) => ({ ...s, [st.id]: p.minutes })); setCustomDurationFor(null); }}>
                          {p.label}
                        </button>
                      ))}
                      <button
                        disabled={!canManageStations || !canStartOnSelectedTerminal}
                        className={`chip text-[11px] ${customDurationFor === st.id ? '!border-accent !text-accent' : 'hover:border-accent'}`}
                        onClick={() => setCustomDurationFor(customDurationFor === st.id ? null : st.id)}>
                        Custom
                      </button>
                    </div>
                    {customDurationFor === st.id && (
                      <div className="flex items-center gap-2 mb-2">
                        <input type="number" min={1} max={1440} placeholder="minutes"
                          className="input !py-1.5 text-sm flex-1"
                          disabled={!canManageStations || !canStartOnSelectedTerminal}
                          value={pendingDuration[st.id] ?? ''}
                          onChange={(e) => setPendingDuration((s) => ({
                            ...s, [st.id]: e.target.value ? Math.max(1, Math.min(1440, Number(e.target.value))) : null,
                          }))}/>
                        <span className="text-xs text-fg-muted">min</span>
                      </div>
                    )}
                    <GamingMutationButton
                      canManageSessions={canManageStations}
                      className="btn btn-primary w-full"
                      onClick={() => startSession(st, '', undefined, phone)}
                      disabled={!st.is_active || !canStartOnSelectedTerminal}
                      title={canStartOnSelectedTerminal
                        ? undefined
                        : 'This device is configured for counter sales. Ask an owner to enable Gaming or Combined mode.'}>
                      <Play size={14}/> Start session
                      {pendingDuration[st.id] ? ` · ${pendingDuration[st.id]}m` : ''}
                    </GamingMutationButton>
                  </>
                )}
              </div>
            );
          })}
        </div>
      )}

      <GamingWriteOnly allowed={canManageStations}>
        {addonModalTarget && (
          <SessionAddonPickerModal
            key={`${addonModalTarget.sessionId}:${addonCreateAttempt?.idempotencyKey ?? 'new'}`}
            stationName={addonModalTarget.station.name}
            items={addonCatalog}
            attempt={addonCreateAttempt?.sessionId === addonModalTarget.sessionId
              ? addonCreateAttempt
              : null}
            busy={addingAddonTo === addonModalTarget.sessionId}
            requestError={addonCreateError}
            onSubmit={(draft) => { void submitSessionAddon(draft); }}
            onClose={() => {
              if (!addingAddonTo) {
                setAddonModalTarget(null);
                setAddonCreateError(null);
              }
            }}
          />
        )}
        {addonVoidTarget && (
          <SessionAddonVoidModal
            key={`${addonVoidTarget.addon.id}:${addonVoidAttempt?.idempotencyKey ?? 'new'}`}
            addon={addonVoidTarget.addon}
            attempt={addonVoidAttempt?.addonId === addonVoidTarget.addon.id
              ? addonVoidAttempt
              : null}
            busy={voidingAddon === addonVoidTarget.addon.id}
            requestError={addonVoidError}
            onSubmit={(reason) => { void submitAddonVoid(reason); }}
            onClose={() => {
              if (!voidingAddon) {
                setAddonVoidTarget(null);
                setAddonVoidError(null);
              }
            }}
          />
        )}
        {addOpen && (
          <StationForm
            canManageSessions={canManageStations}
            onClose={() => setAddOpen(false)}
            onSuccess={() => {
              setAddOpen(false);
              void load();
              notifications.success('The gaming station was added.', { title: 'Station saved' });
            }}
          />
        )}
        {edit && (
          <StationForm
            canManageSessions={canManageStations}
            station={edit}
            onClose={() => setEdit(null)}
            onSuccess={() => {
              setEdit(null);
              void load();
              notifications.success('The gaming station was updated.', { title: 'Changes saved' });
            }}
          />
        )}
        {pendingExtension && (
          <ConfirmModal
            title="Add paid extension"
            message={(() => {
              const session = sessions[pendingExtension.station.id];
              const surcharge = extraControllerSurchargeMinor(
                session?.extra_controllers ?? 0,
                pendingExtension.extension.duration_minutes,
              );
              const total = pendingExtension.extension.price_minor + surcharge;
              return surcharge > 0
                ? `Add ${pendingExtension.extension.name} for ${inr(total)} (${inr(pendingExtension.extension.price_minor)} package + ${inr(surcharge)} controller surcharge)? This charges the customer's bill.`
                : `Add ${pendingExtension.extension.name} for ${inr(total)}? This charges the customer's bill.`;
            })()}
            confirmLabel="Add extension"
            busy={extendingSession === pendingExtension.station.id}
            onConfirm={() => {
              void extendPackageSession(pendingExtension.station, pendingExtension.extension.id);
            }}
            onCancel={() => { if (!extendingSession) setPendingExtension(null); }}
          />
        )}
        {cancelStationTarget && (
          <PromptModal
            title={`Cancel ${cancelStationTarget.name}`}
            label="Audit reason (required). This removes the stopped session from billing."
            placeholder="Why is this session being cancelled?"
            confirmLabel="Cancel session"
            danger
            busy={cancelling === cancelStationTarget.id}
            onSubmit={(reason) => { void cancelSession(cancelStationTarget, reason); }}
            onCancel={() => { if (!cancelling) setCancelStationTarget(null); }}
          />
        )}
        {pendingReconciliation && (
          <PromptModal
            title={`Reconcile ${pendingReconciliation.station.name} to POS`}
            label="Reason required. The original closed shift remains unchanged; this creates the held bill on the current open shift."
            placeholder="Why did this session miss POS before the shift closed?"
            confirmLabel="Create POS bill"
            busy={reconciling === pendingReconciliation.station.id}
            onSubmit={(reason) => { void reconcileToPos(pendingReconciliation, reason); }}
            onCancel={() => { if (!reconciling) setPendingReconciliation(null); }}
          />
        )}
        {pendingPosHandoff && (
          <PosHandoffModal
            station={pendingPosHandoff.station}
            targets={pendingPosHandoff.targets}
            busy={sendingToPos === pendingPosHandoff.station.id}
            onSelect={(target) => { void confirmPosHandoff(target); }}
            onCancel={() => { if (!sendingToPos) setPendingPosHandoff(null); }}
          />
        )}
        {repairStationTarget && (
          <BillingRepairModal
            station={repairStationTarget}
            busy={repairingBilling === repairStationTarget.id}
            onSubmit={(amountMinor, reason) => {
              void repairMissingBilling(repairStationTarget, amountMinor, reason);
            }}
            onCancel={() => {
              if (!repairingBilling) {
                repairKeyRef.current = null;
                setRepairStationTarget(null);
              }
            }}
          />
        )}
        {deleteStationTarget && (
          <ConfirmModal
            title="Delete gaming station"
            message={`Delete station ${deleteStationTarget.code}? This cannot be undone.`}
            confirmLabel="Delete station"
            danger
            busy={deleteStationBusy}
            onConfirm={() => { void confirmDeleteStation(); }}
            onCancel={() => { if (!deleteStationBusy) setDeleteStationTarget(null); }}
          />
        )}
      </GamingWriteOnly>
    </div>
  );
}

function PosHandoffModal({
  station,
  targets,
  busy,
  onSelect,
  onCancel,
}: {
  station: StationDTO;
  targets: GamingPosTargetShiftDTO[];
  busy: boolean;
  onSelect: (target: GamingPosTargetShiftDTO) => void;
  onCancel: () => void;
}) {
  return (
    <Modal open onClose={onCancel} title={`Send ${station.name} to POS`}>
      <div className="space-y-3">
        <div className="rounded-lg border border-bg-border bg-bg-raised p-3 text-sm text-fg-muted">
          Choose the open cashier shift that will collect this payment. The gaming shift keeps
          the session record; the selected POS shift receives the held bill.
        </div>
        <div className="space-y-2">
          {targets.map((target) => (
            <button
              key={target.shift_id}
              type="button"
              className="flex min-h-14 w-full items-center justify-between gap-3 rounded-lg border border-bg-border bg-bg-surface p-3 text-left transition-colors hover:border-accent/60 disabled:cursor-not-allowed disabled:opacity-60"
              disabled={busy}
              onClick={() => onSelect(target)}
            >
              <span className="min-w-0">
                <span className="block truncate font-semibold">{target.terminal_name}</span>
                <span className="block truncate text-xs text-fg-muted">
                  Shift opened by {target.opened_by_name} at{' '}
                  {new Date(target.opened_at).toLocaleTimeString([], {
                    hour: '2-digit', minute: '2-digit',
                  })}
                </span>
              </span>
              <span className="btn btn-primary pointer-events-none !min-h-[36px] shrink-0 !px-3 !py-1.5 text-xs">
                {busy ? <Loader2 className="animate-spin" size={14}/> : <Send size={14}/>} Send
              </span>
            </button>
          ))}
        </div>
        <div className="flex justify-end pt-1">
          <button type="button" className="btn btn-ghost" disabled={busy} onClick={onCancel}>
            Keep payment pending
          </button>
        </div>
      </div>
    </Modal>
  );
}

function BillingRepairModal({
  station,
  busy,
  onSubmit,
  onCancel,
}: {
  station: StationDTO;
  busy: boolean;
  onSubmit: (amountMinor: number, reason: string) => void;
  onCancel: () => void;
}) {
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const [validation, setValidation] = useState<string | null>(null);

  function submit(event: React.FormEvent) {
    event.preventDefault();
    const amountMinor = parseRupeesToMinor(amount);
    const normalizedReason = reason.trim();
    if (amountMinor === null) {
      setValidation('Enter the verified bill as a non-negative amount with at most two decimals.');
      return;
    }
    if (normalizedReason.length < 3 || normalizedReason.length > 500) {
      setValidation('Enter an audit reason between 3 and 500 characters.');
      return;
    }
    setValidation(null);
    onSubmit(amountMinor, normalizedReason);
  }

  return (
    <Modal open onClose={onCancel} title={`Repair billing · ${station.name}`}>
      <form onSubmit={submit} className="space-y-3">
        <div className="p-3 rounded-lg bg-accent-bad/10 border border-accent-bad/40 text-sm">
          This is a protected financial correction. Verify the original package, duration, and price before entering an amount. The employee, reason, old value, and new value are audited.
        </div>
        <Field label="Verified bill amount (₹)">
          <input
            autoFocus
            type="number"
            inputMode="decimal"
            min={0}
            step="0.01"
            className="input font-mono text-right"
            value={amount}
            onChange={(event) => setAmount(event.target.value)}
            placeholder="0.00"
            disabled={busy}
          />
        </Field>
        <Field label="Audit reason">
          <textarea
            className="input min-h-24 resize-y"
            maxLength={500}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            placeholder="How was the verified amount established?"
            disabled={busy}
          />
        </Field>
        {validation && <ErrorRow text={validation}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" disabled={busy} onClick={onCancel}>
            Keep unchanged
          </button>
          <button type="submit" className="btn btn-primary !bg-accent-bad hover:!bg-accent-bad/80" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <AlertCircle size={14}/>} Repair audited bill
          </button>
        </div>
      </form>
    </Modal>
  );
}

// ---------------------------------------------------------------- StationForm
function StationForm({
  station, canManageSessions, onClose, onSuccess,
}: {
  station?: StationDTO;
  canManageSessions: boolean;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const isEdit = !!station;
  const [form, setForm] = useState({
    code: station?.code ?? '',
    name: station?.name ?? '',
    type: station?.type ?? 'ps5' as StationDTO['type'],
    rate_rupees: station ? (station.rate_per_hour_minor / 100).toFixed(2) : '200',
    is_active: station?.is_active ?? true,
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const write = createGamingWriteDispatcher(canManageSessions, () => {
      setErr('Gaming is view-only for this account. Ask an owner to enable the Gaming module.');
    }, gaming);
    if (!write.allowed) return;
    setBusy(true); setErr(null);
    try {
      const rate_per_hour_minor = parseRupeesToMinor(form.rate_rupees);
      if (rate_per_hour_minor === null) {
        throw new Error('Hourly rate must be a non-negative amount with at most two decimals.');
      }
      if (isEdit) {
        await write.dispatch('updateStation', station!.id, {
          name: form.name, rate_per_hour_minor, is_active: form.is_active,
        });
      } else {
        await write.dispatch('createStation', {
          code: form.code, name: form.name, type: form.type, rate_per_hour_minor,
        });
      }
      onSuccess();
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  const hasPricingUnlock = hasActivePricingToken();

  return (
    <Modal open onClose={onClose} title={isEdit ? `Edit ${station!.name}` : 'New gaming station'}>
      <form onSubmit={submit} className="space-y-3">
        {!hasPricingUnlock && LIVE_MODE && (
          <div className="p-2.5 rounded-lg bg-accent-gold/10 border border-accent-gold/30 text-accent-gold text-xs">
            Station setup changes are pricing locked. Unlock pricing in Settings before creating, editing, or deleting stations.
          </div>
        )}
        <div className="grid grid-cols-2 gap-3">
          <Field label="Code (e.g. PS5-01, VR-02, SIM-01)">
            <input className="input font-mono" required disabled={isEdit}
              value={form.code}
              onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })}/>
          </Field>
          <Field label="Type">
            <select className="input" disabled={isEdit} value={form.type}
              onChange={(e) => setForm({ ...form, type: e.target.value as StationDTO['type'] })}>
              <option value="ps5">PlayStation 5</option>
              <option value="vr">VR Pod</option>
              <option value="simulator">Racing Simulator</option>
              <option value="projector">Projector</option>
              {!APP_STORE_REVIEW && <option value="hookah">Hookah Lounge</option>}
              <option value="streaming">Streaming Booth</option>
            </select>
          </Field>
        </div>
        <Field label="Display name">
          <input className="input" required value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}/>
        </Field>
        <Field label="Rate per hour (₹, incl. 18% GST)">
          <input type="number" min={0} step="0.01" required className="input font-mono text-right"
            value={form.rate_rupees}
            onChange={(e) => setForm({ ...form, rate_rupees: e.target.value })}/>
        </Field>
        {isEdit && (
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={form.is_active}
              onChange={(e) => setForm({ ...form, is_active: e.target.checked })}/>
            Station is active (available for sessions)
          </label>
        )}
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={!canManageSessions || busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : null}
            {isEdit ? 'Save' : 'Create'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="text-xs text-fg-muted">{label}</span>
      <div className="mt-1">{children}</div>
    </label>
  );
}
function ErrorRow({ text }: { text: string }) {
  return (
    <div className="p-2.5 rounded-lg bg-accent-bad/10 border border-accent-bad/40 text-accent-bad text-sm flex items-center gap-2">
      <AlertCircle size={14}/> {text}
    </div>
  );
}
function demoToDTO(s: DemoStation): StationDTO {
  return {
    id: s.id, branch_id: 'demo-branch', code: s.code, name: s.name,
    type: s.type as StationDTO['type'],
    rate_per_hour_minor: 20000, is_active: true,
  };
}
