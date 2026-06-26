/**
 * Demo mode is permanently OFF.
 * D Company ERP runs against the live FastAPI backend at all times
 * (Postgres, Argon2 auth, real Kerala GST math, real invoice numbers).
 *
 * Legacy demo branches in screens are kept only to satisfy imports;
 * they are dead code because LIVE_MODE === true always.
 */
export const DEMO_MODE = false;
export const LIVE_MODE = true;

// Kept for typing compatibility only; never used because DEMO_MODE === false.
export const DEMO_USER = {
  user_id: '00000000-0000-0000-0000-000000000000',
  email: 'demo@dcompany.local',
  name: 'Owner',
  roles: ['owner'],
  protected_access: true,
  company_id: '00000000-0000-0000-0000-000000000000',
  branch_id: null as string | null,
};
