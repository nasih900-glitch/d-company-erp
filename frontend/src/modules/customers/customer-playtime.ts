import type { PlaytimeLeaderboardItemDTO, PlaytimeProgramDTO } from '@/lib/erp-api';

export function formatPlayMinutes(minutes: number): string {
  const safe = Math.max(0, Math.trunc(minutes));
  const hours = Math.floor(safe / 60);
  const remainder = safe % 60;
  if (!hours) return `${remainder}m`;
  return remainder ? `${hours}h ${remainder}m` : `${hours}h`;
}

export function draftPlaytimeMessage(
  customer: PlaytimeLeaderboardItemDTO,
  program: PlaytimeProgramDTO,
): string {
  const name = customer.name?.trim() || 'Customer';
  const estimatedRewardMinutes = Math.floor(
    Math.max(0, customer.qualifying_paid_minutes) / program.threshold_paid_minutes,
  ) * program.reward_minutes;
  return `Hi ${name}, you have ${formatPlayMinutes(customer.total_played_minutes)} of recorded play. `
    + `Draft estimate: ${formatPlayMinutes(estimatedRewardMinutes)} under the proposed `
    + `${formatPlayMinutes(program.threshold_paid_minutes)} paid = ${formatPlayMinutes(program.reward_minutes)} free plan. `
    + 'Preview only — no reward has been issued and this message has not been sent.';
}
