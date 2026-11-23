export function roleLabel(role: string): string {
  const labels: Record<string, string> = {
    super_owner: 'Owner',
    co_owner: 'Owner',
    owner: 'Owner',
    partner: 'Partner (legacy, configurable)',
    manager: 'Manager',
    cashier: 'Cashier',
    gaming_cashier: 'Gaming & POS staff (legacy)',
    store_staff: 'Store Staff',
    pos_discount: 'Manual POS discounts',
    kitchen: 'Kitchen',
    gaming_supervisor: 'Gaming Supervisor',
    auditor: 'Business Viewer',
    staff: 'Staff',
  };
  return labels[role] ?? role;
}

/** Only use with exact role codes supplied in an authorized management response. */
export function managedRoleLabel(role: string): string {
  if (role === 'co_owner') return 'Owner (operational override)';
  if (role === 'super_owner') return 'Owner (protected audit)';
  return roleLabel(role);
}

export const EVERYDAY_ROLE_CODES = ['owner', 'store_staff', 'auditor'] as const;

const ROLE_PURPOSES: Record<string, string> = {
  owner: 'Business operations, finance, assets and payroll; standard shift accountability. Protected audit access is separate.',
  co_owner: 'Full operational override access, including finance and payroll; no protected audit or Access Control.',
  super_owner: 'Protected audit and Access Control, with full operational override access. Cannot be reassigned from Staff.',
  manager: 'Broad daily management: POS refunds and voids, inventory, gaming, finance writes and staff management. No payroll access by default.',
  store_staff: 'POS sales and manual discounts, Gaming sessions and eligible voids before POS, shop shifts and attendance. Gaming voids require a reason from 30 minutes. No POS voids/refunds, finance, payroll or staff administration.',
  gaming_cashier: 'Legacy role: POS sales and voids, gaming sessions, shop shifts and attendance. No business management; manual discounts require a separate Owner grant.',
  auditor: 'Read-only business, finance, staff and operational records, with report exports. Cannot perform writes, refunds, voids or shift actions.',
  partner: 'Legacy business partner role. Access is configurable; this is not a guaranteed read-only role. Use Business Viewer for immutable read-only access.',
  cashier: 'Legacy POS sales, table orders and shift operations. Access can be configured.',
  kitchen: 'Legacy kitchen display and ticket updates. Access can be configured.',
  gaming_supervisor: 'Legacy gaming sessions, bookings and tournaments. Access can be configured.',
  staff: 'Legacy service staff: table orders, kitchen progress and attendance. Access can be configured.',
};

export function rolePurpose(role: string): string {
  return ROLE_PURPOSES[role] ?? 'Existing role. Review its effective permissions in Access Control.';
}

export function rolesLabel(roles: string[] | undefined): string {
  if (!roles?.length) return 'no role';
  return roles.map(roleLabel).join(', ');
}

export function primaryRoleCode(roles: readonly string[] | null | undefined): string {
  return roles?.find((role) => role !== 'pos_discount') ?? 'cashier';
}
