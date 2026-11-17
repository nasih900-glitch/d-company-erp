/** Pure configuration read: importing this never initializes ordinary authentication. */
export function configuredApiBase(): string {
  const configured = import.meta.env.VITE_API_URL?.trim();
  const rawRuntime = typeof window === 'undefined' ? undefined
    : (window as unknown as { __ERP_API_URL__?: string }).__ERP_API_URL__ || undefined;
  const runtime = rawRuntime?.trim();
  return configured || runtime || '/api/v1';
}
