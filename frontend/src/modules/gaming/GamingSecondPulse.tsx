import { useEffect, useState, type ReactNode } from 'react';

type GamingSecondPulseProps = {
  children: (now: number) => ReactNode;
  enabled?: boolean;
};

/** Keeps one-second clock updates inside the smallest time-sensitive subtree. */
export function GamingSecondPulse({ children, enabled = true }: GamingSecondPulseProps) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!enabled) return undefined;
    setNow(Date.now());
    const interval = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(interval);
  }, [enabled]);

  return <>{children(now)}</>;
}
