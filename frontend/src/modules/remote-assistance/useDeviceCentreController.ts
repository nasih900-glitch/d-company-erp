import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';

import { useNotifications } from '@/components/ui/Notifications';
import type { ApiError } from '@/lib/api';
import {
  remoteAssistance,
  type RemoteAssistanceGrantDTO,
  type RemoteAssistanceGrantKind,
} from '@/lib/erp-api';
import {
  createRemoteMutationId,
  isRemoteFrameStale,
  type SafeRemoteAssistanceCommand,
} from '@/lib/remote-assistance-policy';
import {
  canConnectRemoteSession,
  canRequestRemoteGrant,
  commandRejectionMessage,
  commandSuccessMessage,
  deviceKeyActionErrorMessage,
  EMPTY_REMOTE_FRAME,
  frameErrorMessage,
  hasActiveDeviceKey,
  isCompletePairingCode,
  isRemoteCommandBusyAction,
  joinRemoteAssistanceState,
  remoteAssistanceErrorMessage,
  type BusyAction,
  type DeviceCentreRow,
  type RemoteFrameViewModel,
} from './remote-assistance-state';
import {
  COMMAND_CONFIRMATION_TIMEOUT_MS,
  isAbortError,
  RemoteCommandConfirmationTimeoutError,
  waitForRemoteCommandResolution,
} from './remote-assistance-command-confirmation';

const STATUS_POLL_MS = 10_000;
const FRAME_POLL_MS = 3_000;
const SESSION_TTL_SECONDS = 15 * 60;
const ONE_TIME_GRANT_TTL_SECONDS = 10 * 60;
const ANYTIME_GRANT_TTL_SECONDS = 24 * 60 * 60;
const TRUST_RECONCILING_FRAME: RemoteFrameViewModel = {
  ...EMPTY_REMOTE_FRAME,
  state: 'privacy',
  message: 'The ERP view is paused while the tablet key state is reconciled.',
};

export type ConfirmAction =
  | { type: 'approve_key'; keyId: string; replacement: boolean }
  | { type: 'revoke_key'; keyId: string }
  | { type: 'request_anytime' }
  | { type: 'end'; sessionId: string }
  | { type: 'revoke'; grantId: string };

export function useDeviceCentreController() {
  const notifications = useNotifications();
  const [rows, setRows] = useState<DeviceCentreRow[] | null>(null);
  const rowsRef = useRef<DeviceCentreRow[] | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pairingError, setPairingError] = useState<string | null>(null);
  const [pairingRetryAvailable, setPairingRetryAvailable] = useState(false);
  const [busyAction, setBusyAction] = useState<BusyAction>(null);
  const [grantKind, setGrantKind] = useState<RemoteAssistanceGrantKind>('one_time');
  const [confirmAction, setConfirmAction] = useState<ConfirmAction | null>(null);
  const [frame, setFrame] = useState<RemoteFrameViewModel>(EMPTY_REMOTE_FRAME);
  const [frameSuspended, setFrameSuspended] = useState(false);
  const requestSequence = useRef(0);
  const statusController = useRef<AbortController | null>(null);
  const frameController = useRef<AbortController | null>(null);
  const commandController = useRef<AbortController | null>(null);
  const frameUrl = useRef<string | null>(null);
  const mutationIds = useRef(new Map<string, string>());
  const grants = useRef(new Map<string, RemoteAssistanceGrantDTO>());
  const pairingIntent = useRef<{
    keyId: string;
    approvalId: string;
    pairingCode: string;
  } | null>(null);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      commandController.current?.abort();
      commandController.current = null;
      pairingIntent.current = null;
    };
  }, []);

  const load = useCallback(async (background = false) => {
    statusController.current?.abort();
    const controller = new AbortController();
    statusController.current = controller;
    const request = ++requestSequence.current;
    if (background || rowsRef.current) setRefreshing(true);
    else setLoading(true);
    setLoadError(null);

    try {
      const [devices, sessions] = await Promise.all([
        remoteAssistance.listDevices(controller.signal),
        remoteAssistance.listSessions({ limit: 100, offset: 0 }, controller.signal),
      ]);
      if (controller.signal.aborted || request !== requestSequence.current) return false;
      const nextRows = joinRemoteAssistanceState(devices.items, sessions.items, grants.current);
      rowsRef.current = nextRows;
      setRows(nextRows);
      setSelectedId((current) => (
        current && nextRows.some((row) => row.device.installation_id === current)
          ? current
          : nextRows[0]?.device.installation_id ?? null
      ));
      setRefreshError(null);
      return true;
    } catch (error) {
      if (controller.signal.aborted || request !== requestSequence.current) return false;
      const message = remoteAssistanceErrorMessage(error);
      if (rowsRef.current) setRefreshError(message);
      else setLoadError(message);
      return false;
    } finally {
      if (request === requestSequence.current) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, []);

  useEffect(() => {
    void load();
    const refreshWhenVisible = () => {
      if (document.visibilityState === 'visible') void load(true);
    };
    const interval = window.setInterval(refreshWhenVisible, STATUS_POLL_MS);
    document.addEventListener('visibilitychange', refreshWhenVisible);
    return () => {
      window.clearInterval(interval);
      document.removeEventListener('visibilitychange', refreshWhenVisible);
      requestSequence.current += 1;
      statusController.current?.abort();
    };
  }, [load]);

  const selectedRow = useMemo(
    () => rows?.find((row) => row.device.installation_id === selectedId) ?? null,
    [rows, selectedId],
  );
  const selectedSessionId = selectedRow?.session?.id ?? null;
  const selectedSessionStatus = selectedRow?.session?.status ?? null;
  const selectedDeviceKeyActive = selectedRow
    ? hasActiveDeviceKey(selectedRow.device)
    : false;
  const selectedPendingKeyId = selectedRow?.device.pending_device_key_id ?? null;

  useEffect(() => {
    if (
      confirmAction?.type === 'approve_key'
      && rows
      && selectedPendingKeyId !== confirmAction.keyId
    ) {
      pairingIntent.current = null;
      setPairingRetryAvailable(false);
      setPairingError(null);
      setConfirmAction(null);
      setActionError(
        'The pending tablet key changed or expired. Review the latest pairing state before retrying.',
      );
    }
  }, [confirmAction, rows, selectedPendingKeyId]);

  useEffect(() => {
    commandController.current?.abort();
  }, [selectedSessionId]);

  useEffect(() => {
    frameController.current?.abort();
    if (frameUrl.current) {
      URL.revokeObjectURL(frameUrl.current);
      frameUrl.current = null;
    }
    setFrame(frameSuspended ? TRUST_RECONCILING_FRAME : EMPTY_REMOTE_FRAME);

    if (
      !selectedSessionId
      || selectedSessionStatus !== 'active'
      || !selectedDeviceKeyActive
      || frameSuspended
    ) {
      return undefined;
    }

    let disposed = false;
    const pollFrame = async () => {
      frameController.current?.abort();
      const controller = new AbortController();
      frameController.current = controller;
      setFrame((current) => (
        current.state === 'inactive' ? { ...EMPTY_REMOTE_FRAME, state: 'loading' } : current
      ));
      try {
        const response = await remoteAssistance.frame(selectedSessionId, controller.signal);
        if (disposed || controller.signal.aborted) return;
        const nextUrl = URL.createObjectURL(response.blob);
        const previousUrl = frameUrl.current;
        frameUrl.current = nextUrl;
        setFrame({
          state: isRemoteFrameStale(response.received_at) ? 'stale' : 'fresh',
          src: nextUrl,
          receivedAt: response.received_at,
          width: response.width,
          height: response.height,
          message: response.received_at
            ? null
            : 'Frame time was not provided, so this image is unverified and stale; do not use it as evidence.',
        });
        if (previousUrl) URL.revokeObjectURL(previousUrl);
      } catch (error) {
        if (disposed || controller.signal.aborted) return;
        const status = (error as ApiError).status;
        setFrame((current) => ({
          ...current,
          state: status === 404 ? 'privacy' : status === 503 ? 'offline' : 'error',
          message: frameErrorMessage(error),
        }));
      }
    };

    void pollFrame();
    const interval = window.setInterval(() => {
      if (document.visibilityState === 'visible') void pollFrame();
    }, FRAME_POLL_MS);
    return () => {
      disposed = true;
      window.clearInterval(interval);
      frameController.current?.abort();
      if (frameUrl.current) {
        URL.revokeObjectURL(frameUrl.current);
        frameUrl.current = null;
      }
    };
  }, [frameSuspended, selectedDeviceKeyActive, selectedSessionId, selectedSessionStatus]);

  const mutationId = useCallback((key: string) => {
    const existing = mutationIds.current.get(key);
    if (existing) return existing;
    const created = createRemoteMutationId();
    mutationIds.current.set(key, created);
    return created;
  }, []);

  const mutationSucceeded = useCallback((key: string) => {
    mutationIds.current.delete(key);
  }, []);

  const interruptCommandConfirmation = useCallback(() => {
    const controller = commandController.current;
    commandController.current = null;
    controller?.abort();
  }, []);

  const clearFrameImmediately = useCallback(() => {
    frameController.current?.abort();
    if (frameUrl.current) {
      URL.revokeObjectURL(frameUrl.current);
      frameUrl.current = null;
    }
    setFrame(TRUST_RECONCILING_FRAME);
  }, []);

  const openPairing = useCallback((keyId: string, replacement: boolean) => {
    if (selectedPendingKeyId !== keyId) {
      setActionError('The pending tablet key changed. Refresh pairing state before approving.');
      return;
    }
    pairingIntent.current = null;
    setActionError(null);
    setPairingError(null);
    setPairingRetryAvailable(false);
    setConfirmAction({ type: 'approve_key', keyId, replacement });
  }, [selectedPendingKeyId]);

  const dismissConfirmAction = useCallback(() => {
    const reconcileUnknownPairing = pairingRetryAvailable;
    pairingIntent.current = null;
    setPairingError(null);
    setPairingRetryAvailable(false);
    setConfirmAction(null);
    if (reconcileUnknownPairing) {
      void load(true).then((reconciled) => {
        if (reconciled) setFrameSuspended(false);
      });
    }
  }, [load, pairingRetryAvailable]);

  const selectDevice = useCallback((installationId: string) => {
    pairingIntent.current = null;
    setPairingError(null);
    setPairingRetryAvailable(false);
    setConfirmAction(null);
    setFrameSuspended(false);
    setSelectedId(installationId);
  }, []);

  const runApproveDeviceKey = useCallback(async (
    keyId: string,
    pairingCode: string | null,
  ) => {
    if (busyAction) return;
    if (selectedPendingKeyId !== keyId) {
      pairingIntent.current = null;
      setPairingRetryAvailable(false);
      setPairingError('The pending tablet key changed or expired. Review device state again.');
      return;
    }
    let intent = pairingIntent.current;
    if (pairingCode !== null) {
      if (!isCompletePairingCode(pairingCode)) {
        setPairingError('Enter the complete 12-character code shown on the physical tablet.');
        return;
      }
      intent = {
        keyId,
        approvalId: createRemoteMutationId(),
        pairingCode,
      };
      pairingIntent.current = intent;
    }
    if (!intent || intent.keyId !== keyId) {
      setPairingError('The pairing attempt is no longer available. Enter the code again.');
      setPairingRetryAvailable(false);
      return;
    }

    setBusyAction('approve_key');
    setPairingError(null);
    setFrameSuspended(true);
    clearFrameImmediately();
    try {
      await remoteAssistance.approveDeviceKey(keyId, {
        approval_id: intent.approvalId,
        pairing_code: intent.pairingCode,
      });
      pairingIntent.current = null;
      setPairingRetryAvailable(false);
      notifications.success(
        selectedDeviceKeyActive
          ? 'The replacement tablet key is verified. Refresh employee consent before reconnecting.'
          : 'The tablet identity is verified. Employee consent can now be requested.',
        { title: selectedDeviceKeyActive ? 'Device key replaced' : 'Tablet paired' },
      );
      setConfirmAction(null);
      const reconciled = await load(true);
      if (reconciled) setFrameSuspended(false);
    } catch (error) {
      const apiError = error as ApiError;
      const retryableUnknown = !apiError.status
        || apiError.status >= 500
        || apiError.code === 'network_error';
      if (!retryableUnknown) pairingIntent.current = null;
      setPairingRetryAvailable(retryableUnknown);
      const message = deviceKeyActionErrorMessage(error, 'approve');
      setPairingError(message);
      notifications.error(message, {
        title: retryableUnknown ? 'Pairing outcome is unknown' : 'Pairing was not approved',
      });
      const reconciled = await load(true);
      if (reconciled) setFrameSuspended(false);
    } finally {
      setBusyAction(null);
    }
  }, [
    busyAction,
    clearFrameImmediately,
    load,
    notifications,
    selectedDeviceKeyActive,
    selectedPendingKeyId,
  ]);

  const runRevokeDeviceKey = useCallback(async (keyId: string) => {
    if (busyAction && !isRemoteCommandBusyAction(busyAction)) return;
    if (
      !selectedRow
      || selectedRow.device.device_key_id !== keyId
      || !hasActiveDeviceKey(selectedRow.device)
    ) {
      setActionError('The active tablet key changed. Refresh device state before revoking.');
      setConfirmAction(null);
      return;
    }
    interruptCommandConfirmation();
    setFrameSuspended(true);
    clearFrameImmediately();
    const key = `key-revoke:${keyId}`;
    setBusyAction('revoke_key');
    setActionError(null);
    try {
      await remoteAssistance.revokeDeviceKey(keyId, mutationId(key));
      mutationSucceeded(key);
      notifications.success(
        'The tablet key was revoked. Remote access and open assistance sessions were stopped.',
        { title: 'Device trust revoked' },
      );
      const reconciled = await load(true);
      if (reconciled) setFrameSuspended(false);
    } catch (error) {
      const apiError = error as ApiError;
      if (apiError.status && apiError.status < 500) mutationSucceeded(key);
      const message = deviceKeyActionErrorMessage(error, 'revoke');
      setActionError(message);
      notifications.error(message, {
        title: 'Device trust may still be active',
        critical: true,
      });
      const reconciled = await load(true);
      if (reconciled) setFrameSuspended(false);
    } finally {
      setBusyAction(null);
      setConfirmAction(null);
    }
  }, [
    busyAction,
    clearFrameImmediately,
    interruptCommandConfirmation,
    load,
    mutationId,
    mutationSucceeded,
    notifications,
    selectedRow,
  ]);

  const runRequestGrant = useCallback(async () => {
    if (!selectedRow || busyAction) return;
    const device = selectedRow.device;
    if (!hasActiveDeviceKey(device)) {
      setActionError('Pairing is required before an employee access request can be sent.');
      return;
    }
    if (!canRequestRemoteGrant(selectedRow)) {
      setActionError(device.is_remote_online
        ? 'This tablet cannot accept a new access request in its current grant or sharing state.'
        : 'The tablet is offline or stale. Wait for a recent remote-support heartbeat before requesting access.');
      return;
    }
    const key = `request:${device.installation_id}:${grantKind}`;
    setBusyAction('request');
    setActionError(null);
    try {
      const result = await remoteAssistance.requestGrant({
        request_id: mutationId(key),
        installation_id: device.installation_id,
        grant_kind: grantKind,
        grant_ttl_seconds: grantKind === 'one_time'
          ? ONE_TIME_GRANT_TTL_SECONDS
          : ANYTIME_GRANT_TTL_SECONDS,
        session_ttl_seconds: SESSION_TTL_SECONDS,
      });
      grants.current.set(device.installation_id, result.grant);
      mutationSucceeded(key);
      notifications.success(
        grantKind === 'one_time'
          ? 'The tablet must approve this one-time assistance request.'
          : 'The tablet must approve anytime access for up to 24 hours or until revoked.',
        { title: 'Approval requested' },
      );
      await load(true);
    } catch (error) {
      const message = remoteAssistanceErrorMessage(error);
      setActionError(message);
      notifications.error(message, { title: 'Request was not sent' });
      await load(true);
    } finally {
      setBusyAction(null);
      setConfirmAction(null);
    }
  }, [busyAction, grantKind, load, mutationId, mutationSucceeded, notifications, selectedRow]);

  const runConnect = useCallback(async () => {
    if (!selectedRow || busyAction) return;
    const { device, grant } = selectedRow;
    let session = selectedRow.session;
    if (!hasActiveDeviceKey(device)) {
      setActionError('Pairing is required before an assistance session can connect.');
      return;
    }
    const grantId = device.current_grant_id ?? grant?.id ?? session?.grant_id;
    if (device.grant_status !== 'active' || !grantId) {
      setActionError('An active employee-approved grant is required before connecting.');
      return;
    }
    if (!canConnectRemoteSession(selectedRow)) {
      setActionError(device.is_remote_online
        ? 'Screen-sharing approval is still required on the tablet before connecting.'
        : 'The tablet is offline or stale. Wait for a recent remote-support heartbeat before connecting.');
      return;
    }
    setBusyAction('connect');
    setActionError(null);
    try {
      if (!session || session.status === 'ended' || session.status === 'expired') {
        const createKey = `session:${device.installation_id}:${grantId}`;
        session = await remoteAssistance.createSession({
          session_id: mutationId(createKey),
          installation_id: device.installation_id,
          grant_id: grantId,
          session_ttl_seconds: SESSION_TTL_SECONDS,
        });
        mutationSucceeded(createKey);
      }
      if (session.status === 'requested') {
        const startKey = `start:${session.id}`;
        session = await remoteAssistance.startSession(session.id, mutationId(startKey));
        mutationSucceeded(startKey);
      }
      notifications.success('The short-lived ERP-only assistance session is active.', {
        title: 'Connected',
      });
      await load(true);
    } catch (error) {
      const message = remoteAssistanceErrorMessage(error);
      setActionError(message);
      notifications.error(message, { title: 'Could not connect' });
      await load(true);
    } finally {
      setBusyAction(null);
    }
  }, [busyAction, load, mutationId, mutationSucceeded, notifications, selectedRow]);

  const runEnd = useCallback(async (sessionId: string) => {
    if (busyAction && !isRemoteCommandBusyAction(busyAction)) return;
    interruptCommandConfirmation();
    const key = `end:${sessionId}`;
    setBusyAction('end');
    setActionError(null);
    try {
      await remoteAssistance.endSession(sessionId, mutationId(key));
      mutationSucceeded(key);
      notifications.success('The assistance session has ended.', { title: 'Session ended' });
      await load(true);
    } catch (error) {
      const message = remoteAssistanceErrorMessage(error);
      setActionError(message);
      notifications.error(message, { title: 'Session may still be active', critical: true });
      await load(true);
    } finally {
      setBusyAction(null);
      setConfirmAction(null);
    }
  }, [busyAction, interruptCommandConfirmation, load, mutationId, mutationSucceeded, notifications]);

  const runRevoke = useCallback(async (grantId: string) => {
    if (busyAction && !isRemoteCommandBusyAction(busyAction)) return;
    interruptCommandConfirmation();
    const key = `revoke:${grantId}`;
    setBusyAction('revoke');
    setActionError(null);
    try {
      const revoked = await remoteAssistance.revokeGrant(grantId, mutationId(key));
      grants.current.set(revoked.installation_id, revoked);
      mutationSucceeded(key);
      notifications.success('Access was revoked and active assistance was stopped.', {
        title: 'Remote access revoked',
      });
      await load(true);
    } catch (error) {
      const message = remoteAssistanceErrorMessage(error);
      setActionError(message);
      notifications.error(message, { title: 'Access may still be active', critical: true });
      await load(true);
    } finally {
      setBusyAction(null);
      setConfirmAction(null);
    }
  }, [busyAction, interruptCommandConfirmation, load, mutationId, mutationSucceeded, notifications]);

  const runCommand = useCallback(async (command: SafeRemoteAssistanceCommand) => {
    const session = selectedRow?.session;
    if (!session || session.status !== 'active' || busyAction) return;
    interruptCommandConfirmation();
    const controller = new AbortController();
    commandController.current = controller;
    const action = command.type === 'navigate' ? 'navigate' : command.type;
    const suffix = command.type === 'navigate' ? `:${command.module}` : '';
    const key = `command:${session.id}:${session.next_sequence}:${command.type}${suffix}`;
    let queuedNoticeId: string | null = null;
    let confirmationTimer: number | null = null;
    let confirmationTimedOut = false;
    setBusyAction(action);
    setActionError(null);
    try {
      let result = await remoteAssistance.sendCommand(
        session.id,
        command,
        session.next_sequence,
        mutationId(key),
        controller.signal,
      );
      if (result.status === 'pending') {
        queuedNoticeId = notifications.info(
          'Waiting up to 9 seconds for the tablet to acknowledge or reject this command.',
          {
            title: 'Command queued',
            durationMs: COMMAND_CONFIRMATION_TIMEOUT_MS + 1_000,
          },
        );
        confirmationTimer = window.setTimeout(() => {
          confirmationTimedOut = true;
          controller.abort();
        }, COMMAND_CONFIRMATION_TIMEOUT_MS);
        result = await waitForRemoteCommandResolution({
          initial: result,
          load: (signal) => remoteAssistance.getCommand(session.id, result.command_id, signal),
          signal: controller.signal,
        });
        if (confirmationTimer !== null) {
          window.clearTimeout(confirmationTimer);
          confirmationTimer = null;
        }
      }
      if (result.status === 'rejected') {
        mutationSucceeded(key);
        const message = commandRejectionMessage(result.rejection_reason_code);
        setActionError(message);
        notifications.error(message, {
          title: 'Command rejected by tablet',
        });
      } else {
        mutationSucceeded(key);
        notifications.success(commandSuccessMessage(command), { title: 'Command acknowledged' });
      }
      await load(true);
    } catch (error) {
      if (isAbortError(error) && !confirmationTimedOut) return;
      const timedOut = confirmationTimedOut
        || error instanceof RemoteCommandConfirmationTimeoutError;
      const message = timedOut
        ? 'The tablet did not acknowledge the queued command within 9 seconds. Its outcome is unknown; refresh device state before retrying.'
        : remoteAssistanceErrorMessage(error);
      setActionError(message);
      notifications.error(message, {
        title: timedOut
          ? 'Command confirmation timed out'
          : 'Command was not confirmed',
      });
      await load(true);
    } finally {
      if (confirmationTimer !== null) window.clearTimeout(confirmationTimer);
      if (queuedNoticeId) notifications.dismiss(queuedNoticeId);
      if (commandController.current === controller) {
        commandController.current = null;
        if (mounted.current) setBusyAction(null);
      }
    }
  }, [busyAction, interruptCommandConfirmation, load, mutationId, mutationSucceeded, notifications, selectedRow?.session]);

  return {
    rows,
    selectedId,
    selectedRow,
    loading,
    refreshing,
    loadError,
    refreshError,
    actionError,
    pairingError,
    pairingRetryAvailable,
    busyAction,
    grantKind,
    confirmAction,
    frame,
    selectDevice,
    refresh: load,
    setGrantKind,
    setConfirmAction,
    openPairing,
    dismissConfirmAction,
    runApproveDeviceKey,
    runRevokeDeviceKey,
    runRequestGrant,
    runConnect,
    runEnd,
    runRevoke,
    runCommand,
  };
}
