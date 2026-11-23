import { gamingSessionCancelBody, type GamingSessionCancelReview } from '@/lib/erp-api';

export interface GamingCancellationContext {
  online: boolean;
  status: string;
  hasOrder: boolean;
  isOwner: boolean;
  ownsCurrentShift: boolean;
  hasPendingActions: boolean;
  hasParticipantEvidence: boolean;
  participantVoidReady?: boolean;
  addonLedgerReady: boolean;
  hasActiveAddons: boolean;
  hasUnresolvedExtension: boolean;
}

/** This is guidance only; the server checks scope and consumption under row locks. */
export function gamingCancellationBlock(context: GamingCancellationContext): string | null {
  if (!context.online) return 'Reconnect to cancel with an audit reason. Saved work stays here.';
  if (!['active', 'paused', 'ended'].includes(context.status)) {
    return 'This session changed. Refresh Gaming before voiding.';
  }
  if (context.hasOrder) {
    return 'This session has reached POS. Gaming Void is unavailable until the Owner POS review is added.';
  }
  if (!context.ownsCurrentShift && !context.isOwner) {
    return 'Use the open shift and terminal that started this session.';
  }
  if (context.hasPendingActions) return 'Resolve saved session, transfer or item actions before cancellation. Nothing is discarded.';
  if (context.hasParticipantEvidence && !context.participantVoidReady) {
    return 'Refresh Gaming to verify player attendance and billing before Void.';
  }
  if (context.hasUnresolvedExtension) return 'Resolve the saved paid extension before cancelling.';
  if (!context.addonLedgerReady) return 'Refresh Gaming to verify the drinks and snacks before cancellation.';
  if (context.hasActiveAddons) return 'Void every staged drink or snack with a reason before cancelling.';
  return null;
}

export interface CancellationSessionIdentity {
  id: string;
  station_id: string;
  shift_id?: string | null;
  status: string;
  order_id?: string | null;
  amount_minor?: number | null;
  billable_minutes?: number | null;
  cancel_reason?: string | null;
}

export function matchesCancellationIdentity(
  response: CancellationSessionIdentity,
  expected: { id: string; stationId: string; shiftId?: string | null },
): boolean {
  return response.id === expected.id && response.station_id === expected.stationId
    && (response.shift_id ?? null) === (expected.shiftId ?? null);
}

export function isConfirmedCancellation(
  response: CancellationSessionIdentity,
  expected: { id: string; stationId: string; shiftId?: string | null },
): boolean {
  return matchesCancellationIdentity(response, expected)
    && response.status === 'cancelled'
    && (response.order_id ?? null) === null
    && response.amount_minor === 0 && response.billable_minutes === 0
    && Boolean(response.cancel_reason?.trim());
}

export function removeConfirmedCancelledSession<T extends { backend_session_id?: string }>(
  sessions: Record<string, T>, stationId: string, sessionId: string,
): Record<string, T> {
  if (sessions[stationId]?.backend_session_id !== sessionId) return sessions;
  const next = { ...sessions };
  delete next[stationId];
  return next;
}

export function participantVoidMetadataReady(session: {
  status: string;
  billing_mode?: 'hourly' | 'package' | 'legacy_ambiguous' | null;
  participant_revision?: number;
  billing_revision?: number;
  amount_minor?: number | null;
}): boolean {
  const reviewedAmount = Number.isSafeInteger(session.amount_minor) && (session.amount_minor ?? -1) >= 0;
  const billingEvidenceReady = session.status === 'ended'
    ? reviewedAmount
    : session.status === 'active' || session.status === 'paused'
      ? session.billing_mode === 'package'
        ? reviewedAmount
        : session.billing_mode === 'hourly' && session.amount_minor == null
      : false;
  return billingEvidenceReady && Number.isSafeInteger(session.participant_revision)
    && (session.participant_revision ?? 0) > 0 && Number.isSafeInteger(session.billing_revision)
    && (session.billing_revision ?? -1) >= 0;
}

export function participantVoidReview(sessionId: string, session: {
  status: string;
  billing_mode?: 'hourly' | 'package' | 'legacy_ambiguous' | null;
  participant_revision?: number;
  billing_revision?: number;
  amount_minor?: number | null;
}): GamingSessionCancelReview | undefined {
  if (!participantVoidMetadataReady(session)) return undefined;
  return {
    idempotencyKey: `gaming-session-void:${sessionId}`,
    expected_participant_revision: session.participant_revision!,
    expected_billing_revision: session.billing_revision!,
    ...(session.status === 'ended' || session.billing_mode === 'package'
      ? { expected_amount_minor: session.amount_minor! }
      : {}),
  };
}

export function gamingVoidReview(sessionId: string, session: {
  status: string;
  billing_mode?: 'hourly' | 'package' | 'legacy_ambiguous' | null;
  participant_revision?: number;
  billing_revision?: number;
  amount_minor?: number | null;
}): GamingSessionCancelReview {
  const participant = participantVoidReview(sessionId, session);
  return participant ?? {
    idempotencyKey: `gaming-session-void:${sessionId}`,
    ...(Number.isSafeInteger(session.billing_revision) ? {
      expected_billing_revision: session.billing_revision,
    } : {}),
  };
}

export async function participantVoidRequestHash(
  sessionId: string, reason: string | null, review: GamingSessionCancelReview,
): Promise<string> {
  const prefix = `POST\n/api/v1/gaming/sessions/${sessionId}/cancel\n\n${review.idempotencyKey}\n`;
  const bytes = new TextEncoder().encode(prefix + JSON.stringify(gamingSessionCancelBody(reason, review)));
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}
