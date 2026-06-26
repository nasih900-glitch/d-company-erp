import { Navigate, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';

import { useAuth } from './AuthContext';

export default function RequireAuth({ children }: { children: ReactNode }) {
  const { me, loading } = useAuth();
  const loc = useLocation();
  if (loading) return <div className="p-10 text-fg-muted">Loading…</div>;
  if (!me) return <Navigate to="/login" replace state={{ from: loc.pathname }} />;
  return <>{children}</>;
}
