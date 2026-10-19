/** Protected screen state lives only in this authenticated browser lifetime. */
const workspaces = new Map<string, unknown>();
let generation = 0;
export interface OwnerIdentity {
  user_id: string;
  company_id: string;
  branch_id: string | null;
}
export function ownerScope(identity: OwnerIdentity): string {
  return [
    identity.company_id,
    identity.user_id,
    identity.branch_id ?? 'unassigned',
  ].join(':');
}
export function readOwnerWorkspace<T>(
  scope: string,
  name: string,
): T | undefined {
  return workspaces.get(`${scope}:${name}`) as T | undefined;
}
export function rememberOwnerWorkspace<T>(
  scope: string,
  name: string,
  value: T,
): void {
  workspaces.set(`${scope}:${name}`, value);
}
export function clearOwnerWorkspaces(): void {
  generation += 1;
  workspaces.clear();
}
export function ownerWorkspaceGeneration(): number {
  return generation;
}
