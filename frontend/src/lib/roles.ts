export function roleLabel(role: string): string {
  const labels: Record<string, string> = {
    super_owner: 'Owner',
    owner: 'Owner',
    partner: 'Partner',
    manager: 'Manager',
    cashier: 'Cashier',
    kitchen: 'Kitchen',
    gaming_supervisor: 'Gaming Supervisor',
    auditor: 'Auditor',
  };
  return labels[role] ?? role;
}

export function rolesLabel(roles: string[] | undefined): string {
  if (!roles?.length) return 'no role';
  return roles.map(roleLabel).join(', ');
}
