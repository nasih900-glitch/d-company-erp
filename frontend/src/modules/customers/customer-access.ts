export interface CustomerAccessIdentity {
  effective_permissions?: string[];
  protected_access?: boolean;
}

/** Mirrors the backend's pos.write gate for customer profile mutations. */
export function canWriteCustomers(
  identity: CustomerAccessIdentity | null | undefined,
  demo: boolean,
): boolean {
  if (demo) return true;
  if (identity?.effective_permissions !== undefined) {
    return identity.effective_permissions.includes('pos.write');
  }
  return identity?.protected_access === true;
}

/** Mirrors the backend's settings.manage gate for draft proposal edits. */
export function canEditPlaytimeDraft(
  identity: CustomerAccessIdentity | null | undefined,
  demo: boolean,
): boolean {
  if (demo) return true;
  if (identity?.effective_permissions !== undefined) {
    return identity.effective_permissions.includes('settings.manage');
  }
  return identity?.protected_access === true;
}
