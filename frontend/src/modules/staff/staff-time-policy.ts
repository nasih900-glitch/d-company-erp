import type { Me } from '@/modules/auth/AuthContext';
import type { ApiError } from '@/lib/api';
export function canUseStaffTime(me: Pick<Me, 'effective_permissions'> | null | undefined): boolean {
  return me?.effective_permissions?.some(p => ['staff.attendance.write', 'staff.payroll.write'].includes(p)) === true;
}
export function canManageStaffTime(me: Pick<Me, 'roles' | 'effective_permissions'> | null | undefined): boolean {
  return Boolean(me?.roles.some(r => ['owner', 'super_owner', 'co_owner'].includes(r)) && me.effective_permissions?.includes('staff.payroll.write'));
}
export function confirmedWriteRefusal(error: unknown): boolean {
  const {
    status,
    code
  } = (error ?? {}) as ApiError;
  return typeof status === 'number' && status >= 400 && status < 500 && ![408, 429].includes(status) && code !== 'idempotency_in_progress';
}
export function wholeMinutes(value: string, maximum = 1440): number | null {
  if (!/^\d+$/.test(value)) return null;
  const n = Number(value);
  return Number.isSafeInteger(n) && n >= 0 && n <= maximum ? n : null;
}
export function utcInput(iso: string): string {
  const date = new Date(iso);
  if (!Number.isFinite(date.getTime())) throw new Error('The saved timestamp is invalid. Refresh before correcting it.');
  return date.toISOString().slice(0, 19);
}
/** Browser datetime-local is explicitly labelled UTC; never infer its local timezone. */
export function readUtcInput(value: string, original?: string): string {
  if (original && value === utcInput(original)) return original;
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?$/.test(value)) throw new Error('Enter a valid UTC date and time.');
  const normalized = value.length === 16 ? value + ':00' : value;
  const parsed = new Date(normalized + 'Z');
  if (!Number.isFinite(parsed.getTime()) || parsed.toISOString().slice(0, 19) !== normalized) throw new Error('Enter a valid UTC date and time.');
  return parsed.toISOString();
}
export function currentMonth(timezone = 'UTC'): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit'
  }).formatToParts(new Date());
  return `${parts.find(p => p.type === 'year')!.value}-${parts.find(p => p.type === 'month')!.value}`;
}
export function duration(seconds: number | null): string {
  if (seconds === null) return 'Needs review';
  const minutes = Math.floor(seconds / 60);
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}
