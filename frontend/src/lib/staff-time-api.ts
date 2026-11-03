import type { AxiosRequestConfig } from 'axios';
import { api, readSessionGeneration } from './api';
import { readStoredTerminalId } from './operational-context';
export interface StaffTimeScope {
  generation: number;
  terminalId: string | null;
}
export function staffTimeWriteConfig(key: string, scope: StaffTimeScope): AxiosRequestConfig & {
  _sessionGeneration: number;
} {
  const defaults = api.defaults.transformRequest;
  return {
    headers: {
      'Idempotency-Key': key
    },
    _sessionGeneration: scope.generation,
    transformRequest: [(data: unknown) => {
      if (readSessionGeneration() !== scope.generation || readStoredTerminalId() !== scope.terminalId) {
        throw Object.assign(new Error('The signed-in workspace changed. Refresh before continuing.'), {
          code: 'session_changed'
        });
      }
      return data;
    }, ...(Array.isArray(defaults) ? defaults : defaults ? [defaults] : [])]
  };
}
export interface StaffTimePerson {
  id: string;
  name: string;
  status: string;
  departed: boolean;
}
export interface StaffTimePeople {
  items: StaffTimePerson[];
  next_cursor: string | null;
}
export interface StaffAttendance {
  id: string;
  user_id: string;
  branch_id: string;
  clock_in_at: string;
  clock_out_at: string | null;
  break_minutes: number;
  break_confirmed: boolean;
  source: string;
  updated_at: string;
}
export interface StaffSchedule {
  effective_from: string;
  daily_presence_minutes: number;
  daily_break_minutes: number;
  working_weekday_mask: number;
}
export interface StaffWorkPolicy extends StaffSchedule {
  id: string;
  user_id: string;
  revision: number;
  monthly_salary_minor: number;
  overtime_multiplier_basis_points: number | null;
  pay_status: 'draft';
  reason: string;
  created_at: string;
}
export interface StaffTimeSummary {
  user_id: string;
  name: string;
  month: string;
  timezone: string;
  as_of: string;
  entries: StaffAttendance[];
  closed_gross_seconds: number;
  closed_work_seconds: number | null;
  provisional_open_seconds: number;
  review_attendance_ids: string[];
  pay_status: 'draft';
  payable_minor: null;
  overtime_pay_minor: null;
  schedule: StaffSchedule | null;
  policy: StaffWorkPolicy | null;
  policy_covers_full_month: boolean;
  scheduled_days: number | null;
  scheduled_work_minutes: number | null;
  reference_hourly_numerator_minor: number | null;
  reference_hourly_denominator_minutes: number | null;
  pay_estimate?: StaffPayEstimate | null;
}
export interface StaffPayEstimate {
  status: 'available' | 'unavailable';
  reason: string | null;
  basis: 'paid_presence_clock_in_workday';
  attendance_scope: 'company' | 'selected_branch';
  monthly_salary_minor: number | null;
  daily_paid_seconds: number | null;
  scheduled_days: number | null;
  scheduled_paid_seconds: number | null;
  recorded_paid_seconds: number | null;
  ordinary_seconds: number | null;
  extra_seconds: number | null;
  hourly_rate_minor: number | null;
  base_pay_minor: number | null;
  overtime_125_minor: number | null;
  overtime_150_minor: number | null;
  days: {date: string; paid_seconds: number; ordinary_seconds: number; extra_seconds: number}[];
}
export interface StaffPolicyWrite extends StaffSchedule {
  expected_revision: number;
  monthly_salary_minor: number;
  overtime_multiplier_basis_points: null;
  reason: string;
}
export interface StaffTimeCorrection {
  expected_updated_at: string;
  clock_in_at: string;
  clock_out_at: string | null;
  break_minutes: number;
  reason: string;
}
export type StaffTimeAction = {
  kind: 'self-in';
  body: {
    branch_id: string;
  };
} | {
  kind: 'self-out';
  body: {
    attendance_id: string;
    break_minutes: number;
  };
} | {
  kind: 'owner-in';
  userId: string;
  body: {
    branch_id: string;
    reason: string;
  };
} | {
  kind: 'owner-out';
  userId: string;
  body: {
    attendance_id: string;
    break_minutes: number;
    reason: string;
  };
} | {
  kind: 'policy';
  userId: string;
  body: StaffPolicyWrite;
} | {
  kind: 'correction';
  userId: string;
  attendanceId: string;
  body: StaffTimeCorrection;
};
export const staffTime = {
  people: (afterId?: string, signal?: AbortSignal) => api.get<StaffTimePeople>('/staff/time-people', {
    params: {
      limit: 50,
      ...(afterId ? {
        after_id: afterId
      } : {})
    },
    signal
  }).then(r => r.data),
  summary: (month: string, userId?: string, signal?: AbortSignal) => api.get<StaffTimeSummary>('/staff/time-summary', {
    params: {
      month,
      ...(userId ? {
        user_id: userId
      } : {})
    },
    signal
  }).then(r => r.data),
  policies: (userId: string, beforeRevision?: number, signal?: AbortSignal) => api.get<StaffWorkPolicy[]>(`/staff/work-policies/${encodeURIComponent(userId)}`, {
    params: {
      limit: 50,
      ...(beforeRevision ? {
        before_revision: beforeRevision
      } : {})
    },
    signal
  }).then(r => r.data),
  submit: (action: StaffTimeAction, key: string, scope: StaffTimeScope) => {
    const config = staffTimeWriteConfig(key, scope);
    switch (action.kind) {
      case 'self-in':
        return api.post<StaffAttendance>('/staff/my-attendance/clock-in', action.body, config).then(r => r.data);
      case 'self-out':
        return api.post<StaffAttendance>('/staff/my-attendance/clock-out', action.body, config).then(r => r.data);
      case 'owner-in':
        return api.post<StaffAttendance>(`/staff/attendance/${encodeURIComponent(action.userId)}/clock-in`, action.body, config).then(r => r.data);
      case 'owner-out':
        return api.post<StaffAttendance>(`/staff/attendance/${encodeURIComponent(action.userId)}/clock-out`, action.body, config).then(r => r.data);
      case 'policy':
        return api.post<StaffWorkPolicy>(`/staff/work-policies/${encodeURIComponent(action.userId)}`, action.body, config).then(r => r.data);
      case 'correction':
        return api.patch<StaffAttendance>(`/staff/attendance/${encodeURIComponent(action.userId)}/${encodeURIComponent(action.attendanceId)}`, action.body, config).then(r => r.data);
    }
  }
};
