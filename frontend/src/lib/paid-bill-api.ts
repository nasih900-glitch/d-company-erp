import type { AxiosRequestConfig } from 'axios';
import { api, BASE_URL, hasSessionCandidate, readAccessToken, readSessionGeneration } from './api';
import { ownerWorkspaceGeneration } from './owner-workspace';
import { readStoredTerminalId } from './operational-context';
import {
  effectivePaidEndpoint, paidAccessIdentity, exactKeys, isRecord, validPaidAcceptance, validPaidPreview, validPaidState, validPaidOutcome,
  type PaidBillAcceptance, type PaidBillOutcome, type PaidBillCommitBody, type PaidBillPreviewBody, type PaidBillScope,
} from './paid-bill-contract';
import type { ShiftDTO, TerminalDTO } from './erp-api';

export function currentPaidEndpoint() { return effectivePaidEndpoint(api.defaults.baseURL ?? BASE_URL, window.location.href); }
export interface PaidBillLease { scope: PaidBillScope; generation: number; identity: string | null; current(): boolean }
export function capturePaidBillLease(scope: PaidBillScope, authorityCurrent: () => boolean): PaidBillLease {
  const generation = readSessionGeneration(), cacheGeneration = ownerWorkspaceGeneration(), identity = paidAccessIdentity(readAccessToken());
  return { scope, generation, identity, current: () => {
    if (!identity || identity === 'unverified' || paidAccessIdentity(readAccessToken()) !== identity || readSessionGeneration() !== generation || ownerWorkspaceGeneration() !== cacheGeneration || readStoredTerminalId() !== scope.terminalId || !hasSessionCandidate() || currentPaidEndpoint() !== scope.endpoint || !authorityCurrent()) return false;
    const token = readAccessToken();
    if (!token) return false;
    try {
      const raw = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      const claims = JSON.parse(atob(raw)) as Record<string, unknown>;
      return claims.type === 'access' && claims.sub === scope.userId && claims.company_id === scope.companyId && (claims.branch_id == null || claims.branch_id === scope.branchId);
    } catch { return false; }
  } };
}
export function requirePaidLease(lease: PaidBillLease) {
  if (!lease.current()) throw Object.assign(new Error('The original signed-in server or workspace changed. The saved action remains intact.'), { code: 'session_changed' });
}
export function paidBillRequestConfig(lease: PaidBillLease, key?: string, grant?: string, confirmationCurrent?: () => void): AxiosRequestConfig & { _sessionGeneration: number } {
  requirePaidLease(lease); confirmationCurrent?.();
  const defaults = api.defaults.transformRequest;
  return {
    _sessionGeneration: lease.generation,
    headers: { ...(key ? { 'Idempotency-Key': key } : {}), ...(grant ? { 'X-Owner-Confirmation': grant } : {}) },
    transformRequest: [(data: unknown) => { requirePaidLease(lease); confirmationCurrent?.(); return data; }, ...(Array.isArray(defaults) ? defaults : defaults ? [defaults] : [])],
  };
}
function invalid(): never { throw new Error('The paid-bill server response could not be verified. Preserve the saved command and refresh in its original workspace.'); }
const PATH = '/pos/paid-bill-amendments';
export const paidBillAmendments = {
  terminals: async (lease: PaidBillLease) => {
    const value = (await api.get<TerminalDTO[]>('/settings/terminals', paidBillRequestConfig(lease))).data;
    requirePaidLease(lease); return value;
  },
  settlementLabels: async (eligibleIds: string[], lease: PaidBillLease): Promise<Record<string, string>> => {
    const [shifts, terminals] = await Promise.all([
      api.get<ShiftDTO[]>('/pos/shifts', { ...paidBillRequestConfig(lease), params: { only_open: true, limit: 200 } }).then(r => r.data),
      paidBillAmendments.terminals(lease),
    ]);
    requirePaidLease(lease);
    if (!Array.isArray(shifts) || !Array.isArray(terminals)) return {};
    const labels: Record<string, string> = {};
    for (const id of eligibleIds) {
      const rows = shifts.filter(s => s && s.id === id && s.branch_id === lease.scope.branchId);
      if (rows.length !== 1) continue;
      const s = rows[0], tills = terminals.filter(t => t && t.id === s.terminal_id && t.branch_id === s.branch_id);
      if (tills.length !== 1 || typeof tills[0].name !== 'string' || !tills[0].name.trim() || typeof s.opened_by_name !== 'string' || !s.opened_by_name.trim() || typeof s.opened_at !== 'string' || !Number.isFinite(Date.parse(s.opened_at))) continue;
      const opened = new Intl.DateTimeFormat('en-IN', { timeZone: 'Asia/Kolkata', dateStyle: 'medium', timeStyle: 'short' }).format(new Date(s.opened_at));
      labels[id] = `${tills[0].name} · ${s.opened_by_name} · ${opened} · ${id.slice(0, 8)}`;
    }
    return labels;
  },
  unlock: async (branchId: string, password: string, lease: PaidBillLease) => {
    const value: unknown = (await api.post(PATH + '/unlock', { branch_id: branchId, password }, paidBillRequestConfig(lease))).data;
    requirePaidLease(lease);
    if (!isRecord(value) || !exactKeys(value, ['token', 'expires_in_seconds', 'purpose']) || value.purpose !== 'paid_bill_amendment' || typeof value.token !== 'string' || value.token.length < 16 || value.token.length > 8192 || value.expires_in_seconds !== 300) invalid();
    return value as { token: string; expires_in_seconds: number; purpose: 'paid_bill_amendment' };
  },
  preview: async (body: PaidBillPreviewBody, lease: PaidBillLease) => {
    const value: unknown = (await api.post(PATH + '/preview', body, paidBillRequestConfig(lease))).data;
    requirePaidLease(lease); if (!validPaidPreview(value)) invalid(); return value;
  },
  commit: async (body: PaidBillCommitBody, key: string, grant: string, lease: PaidBillLease, confirmationCurrent?: () => void) => {
    const value: unknown = (await api.post(PATH + '/commit', body, paidBillRequestConfig(lease, key, grant, confirmationCurrent))).data;
    requirePaidLease(lease); if (!validPaidAcceptance(value)) invalid(); return value;
  },
  state: async (orderId: string, lease: PaidBillLease) => {
    const value: unknown = (await api.get(PATH + '/receipts/' + encodeURIComponent(orderId), paidBillRequestConfig(lease))).data;
    requirePaidLease(lease); if (!validPaidState(value)) invalid(); return value;
  },
  recoverByKey: async (key: string, grant: string, lease: PaidBillLease, confirmationCurrent?: () => void): Promise<PaidBillOutcome> => {
    const value: unknown = (await api.get(PATH + '/actions/outcome', paidBillRequestConfig(lease, key, grant, confirmationCurrent))).data;
    requirePaidLease(lease); if (!validPaidOutcome(value)) invalid(); return value;
  },
  cancel: async (body: PaidBillCommitBody, reason: string, key: string, grant: string, lease: PaidBillLease, confirmationCurrent?: () => void): Promise<PaidBillOutcome> => {
    const value: unknown = (await api.post(PATH + '/actions/cancel', { original_command: body, cancellation_reason: reason }, paidBillRequestConfig(lease, key, grant, confirmationCurrent))).data;
    requirePaidLease(lease); if (!validPaidOutcome(value)) invalid(); return value;
  },
};
export const paidBillCapability = (terminals: TerminalDTO[], scope: PaidBillScope) => Array.isArray(terminals) && terminals.filter(t => t.id === scope.terminalId && t.branch_id === scope.branchId && t.is_active === true && t.paid_bill_amendment_protocol === 1 && t.paid_bill_action_outcome_protocol === 1).length === 1;
export type { PaidBillAcceptance };
