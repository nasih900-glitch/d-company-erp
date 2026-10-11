import { useEffect, useState, type ReactNode } from 'react';

type GamingSecondPulseProps = {
  children: (now: number) => ReactNode;
};

/** Keeps one-second clock updates inside the smallest time-sensitive subtree. */
export function GamingSecondPulse({ children }: GamingSecondPulseProps) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const interval = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(interval);
  }, []);

  return <>{children(now)}</>;
}
