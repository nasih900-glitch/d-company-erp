import type { AccessCellDTO } from '@/lib/erp-api';

export function accessLevelLabel(level: AccessCellDTO['access_level']): string {
  return level === 'full' ? 'Full' : level === 'partial' ? 'Partial' : 'Blocked';
}

/** Older servers only supply a module switch, which cannot prove full access. */
export function accessCellLevel(cell: AccessCellDTO): NonNullable<AccessCellDTO['access_level']> {
  return cell.access_level ?? (cell.allowed ? 'partial' : 'blocked');
}

export function isAccessCellConfigurable(cell: AccessCellDTO): boolean {
  return cell.role_code !== 'co_owner' && cell.role_code !== 'super_owner' && (cell.configurable ?? true);
}

export function accessCellSummary(cell: AccessCellDTO): string {
  const actions = (permissions: string[]) => permissions.length ? permissions.join(', ') : 'None';
  return [
    `${accessLevelLabel(accessCellLevel(cell))} access`,
    ...(cell.effective_permissions == null ? ['Exact permission evidence unavailable. Refresh against a current server.'] : [
      `Available actions: ${actions(cell.effective_permissions)}`,
      `Unavailable actions: ${actions(cell.unavailable_permissions ?? [])}`,
      `Unavailable even when enabled (permission ceiling): ${actions(cell.ceiling_limited_permissions ?? [])}`,
    ]),
    ...(isAccessCellConfigurable(cell) ? [] : ['Operational owner override: module switches cannot restrict this role.']),
  ].join('\n');
}
