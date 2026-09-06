export interface GamingMutationGate {
  current: boolean;
}

/** Acquire before an async Gaming mutation can yield or React can rerender. */
export function enterGamingMutation(gate: GamingMutationGate): boolean {
  if (gate.current) return false;
  gate.current = true;
  return true;
}

export function leaveGamingMutation(gate: GamingMutationGate): void {
  gate.current = false;
}
