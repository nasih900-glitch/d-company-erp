export interface GamingPosNavigationIntent {
  orderId: string;
  sessionId: string;
  shiftId: string;
  terminalId: string;
}

export function gamingPosNavigationRoute(intent: GamingPosNavigationIntent): string {
  const query = new URLSearchParams({
    incoming: intent.orderId,
    session: intent.sessionId,
    shift: intent.shiftId,
    terminal: intent.terminalId,
  });
  return `/pos?${query.toString()}`;
}

export function gamingPosNavigationIntent(search: string): GamingPosNavigationIntent | null {
  const query = new URLSearchParams(search);
  const orderId = query.get('incoming')?.trim();
  const sessionId = query.get('session')?.trim();
  const shiftId = query.get('shift')?.trim();
  const terminalId = query.get('terminal')?.trim();
  if (!orderId || !sessionId || !shiftId || !terminalId) return null;
  return { orderId, sessionId, shiftId, terminalId };
}
