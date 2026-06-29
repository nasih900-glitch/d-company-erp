import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import {
  Calculator, LayoutGrid, BookOpen, Boxes, Gamepad2,
  Wallet, ScanLine, Users, BarChart3, LogOut, Tv, Settings, Menu, X, FileText,
  ClipboardList, UserCircle, Sparkles, ShieldCheck,
} from 'lucide-react';

import { useAuth } from '@/modules/auth/AuthContext';
import { rolesLabel } from '@/lib/roles';
import InstallButton from './InstallButton';

const NAV = [
  { to: '/pos',       label: 'POS',        Icon: Calculator },
  { to: '/operations',label: 'Operations', Icon: ClipboardList },
  { to: '/tables',    label: 'Tables',     Icon: LayoutGrid },
  { to: '/menu',      label: 'Menu',       Icon: BookOpen },
  { to: '/inventory', label: 'Inventory',  Icon: Boxes, superOwnerOnly: true },
  { to: '/gaming',    label: 'Gaming',     Icon: Gamepad2 },
  { to: '/events',    label: 'Events',     Icon: Tv },
  { to: '/finance',   label: 'Finance',    Icon: Wallet },
  { to: '/ocr',       label: 'OCR',        Icon: ScanLine },
  { to: '/staff',     label: 'Staff',      Icon: Users, superOwnerOnly: true },
  { to: '/customers', label: 'Customers',  Icon: UserCircle },
  { to: '/analytics', label: 'Analytics',  Icon: BarChart3 },
  { to: '/insights',  label: 'Insights',   Icon: Sparkles },
  { to: '/reports',   label: 'Reports',    Icon: FileText },
  { to: '/audit',     label: 'Audit Log',  Icon: ShieldCheck, superOwnerOnly: true },
  { to: '/settings',  label: 'Settings',   Icon: Settings, superOwnerOnly: true },
];

export default function AppShell({ children }: { children?: ReactNode }) {
  const { me, logout, demo } = useAuth();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const loc = useLocation();
  const isSuperOwner = Boolean(demo || me?.protected_access);
  const navItems = useMemo(
    () => NAV.filter((item) => !item.superOwnerOnly || isSuperOwner),
    [isSuperOwner],
  );

  const current = useMemo(
    () => navItems.find((n) => n.to === loc.pathname),
    [loc.pathname, navItems],
  );

  const initials = useMemo(
    () => (me?.name ?? 'D')
      .split(' ')
      .map((p) => p[0])
      .slice(0, 2)
      .join(''),
    [me?.name],
  );

  // Close the drawer whenever the route changes.
  const onNavigate = () => setDrawerOpen(false);

  useEffect(() => {
    setDrawerOpen(false);
  }, [loc.pathname]);

  useEffect(() => {
    if (!drawerOpen) return undefined;

    const previousOverflow = document.body.style.overflow;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setDrawerOpen(false);
    };

    document.body.style.overflow = 'hidden';
    window.addEventListener('keydown', onKeyDown);

    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [drawerOpen]);

  return (
    <div className="min-h-[100dvh] overflow-x-hidden md:grid md:h-[100dvh] md:grid-cols-[260px_1fr]">
      {/* ===================== MOBILE: top bar ===================== */}
      <header
        className="md:hidden sticky top-0 z-30 bg-bg-surface/95 backdrop-blur border-b border-bg-border flex items-center justify-between px-3 py-3"
        style={{ paddingTop: 'max(0.75rem, env(safe-area-inset-top))' }}
      >
        <div className="flex items-center gap-3">
          <button
            onClick={() => setDrawerOpen(true)}
            aria-label="Open menu"
            className="p-2 -m-2 rounded-lg hover:bg-bg-raised active:scale-95 transition"
          >
            <Menu size={22} />
          </button>
          <img
            src="/brand/den-emblem-gold.png"
            alt=""
            aria-hidden="true"
            className="h-9 w-9 rounded-full object-contain bg-bg/80 ring-1 ring-accent-gold/45"
          />
          <div>
            <div className="text-base font-bold tracking-tight leading-none">D Company</div>
            <div className="text-[10px] text-fg-muted leading-none mt-0.5">
              {current?.label ?? 'ERP'}
            </div>
          </div>
        </div>
        <div className="w-9 h-9 rounded-full bg-gradient-to-br from-accent to-accent-purple grid place-items-center font-bold text-bg text-sm">
          {initials}
        </div>
      </header>

      {/* ===================== SIDEBAR =====================
          Desktop:  always visible, fixed column.
          Mobile:   off-canvas drawer; slides in when drawerOpen=true. */}
      <aside
        className={`
          fixed inset-y-0 left-0 z-50 w-[min(84vw,280px)] bg-bg-surface border-r border-bg-border p-4 flex min-h-0 flex-col
          transition-transform duration-200 ease-out will-change-transform md:w-[260px] md:translate-x-0 md:static md:z-auto md:will-change-auto
          ${drawerOpen ? 'translate-x-0 shadow-2xl' : '-translate-x-full'}
        `}
        style={{ paddingTop: 'max(1rem, env(safe-area-inset-top))' }}
      >
        <div className="flex items-center justify-between gap-3 px-2 pb-4 md:pb-6">
          <div className="flex min-w-0 items-center gap-3">
            <img
              src="/brand/den-emblem-gold.png"
              alt=""
              aria-hidden="true"
              className="h-11 w-11 shrink-0 rounded-full object-contain bg-bg/80 ring-1 ring-accent-gold/50"
            />
            <div className="min-w-0">
              <h1 className="truncate text-xl font-bold tracking-tight">D Company</h1>
              <p className="text-xs text-fg-muted">ERP V1</p>
            </div>
          </div>
          <button
            onClick={() => setDrawerOpen(false)}
            aria-label="Close menu"
            className="md:hidden p-2 -m-2 rounded-lg hover:bg-bg-raised"
          >
            <X size={20} />
          </button>
        </div>

        <nav
          className="flex-1 min-h-0 space-y-1 overflow-y-auto -mx-1 px-1"
          style={{ WebkitOverflowScrolling: 'touch' }}
        >
          {navItems.map(({ to, label, Icon }) => (
            <NavLink
              key={to}
              to={to}
              onClick={onNavigate}
              className={({ isActive }) =>
                `flex items-center gap-3 px-3 py-3 rounded-xl text-sm font-medium ` +
                (isActive
                  ? 'bg-bg-raised text-fg shadow-glow'
                  : 'text-fg-muted hover:text-fg hover:bg-bg-raised/50 active:scale-[0.98]')
              }
            >
              <Icon size={18} />
              {label}
            </NavLink>
          ))}
        </nav>

        <div className="pt-4 border-t border-bg-border">
          {demo && (
            <div className="mb-3 rounded-xl bg-accent-gold/10 border border-accent-gold/30 px-3 py-2 text-xs text-accent-gold">
              <b>Demo mode</b> · changes don't save
            </div>
          )}
          <InstallButton />
          <div className="px-2 py-3">
            <p className="text-sm font-medium">{me?.name}</p>
            <p className="text-xs text-fg-muted">{rolesLabel(me?.roles)}</p>
          </div>
          <button onClick={logout} className="btn btn-ghost w-full mt-2">
            <LogOut size={16} /> Sign out
          </button>
        </div>
      </aside>

      {/* Mobile backdrop when drawer open */}
      {drawerOpen && (
        <div
          onClick={() => setDrawerOpen(false)}
          aria-hidden
          className="md:hidden fixed inset-0 z-40 bg-bg/80 backdrop-blur-sm"
        />
      )}

      {/* ===================== MAIN CONTENT ===================== */}
      <main
        className="app-scroll route-frame px-3 py-4 md:p-6 min-w-0"
        style={{ paddingBottom: 'max(1rem, env(safe-area-inset-bottom))' }}
      >
        {children ?? <Outlet />}
      </main>
    </div>
  );
}
