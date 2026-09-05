import { useEffect, useMemo } from 'react';

/** Prevent an older REST response from replacing a newer verified snapshot. */
export class LatestRequestGate {
  private generation = 0;

  begin(): () => boolean {
    const generation = ++this.generation;
    return () => generation === this.generation;
  }

  invalidate(): void { this.generation += 1; }
}

export function useLatestRequest(): LatestRequestGate {
  const gate = useMemo(() => new LatestRequestGate(), []);
  useEffect(() => () => gate.invalidate(), [gate]);
  return gate;
}
