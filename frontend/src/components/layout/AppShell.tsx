import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import {
  Calculator, LayoutGrid, BookOpen, Boxes, Gamepad2,
  Wallet, ScanLine, Users, BarChart3, LogOut, Tv, Settings, Menu, X, FileText,
  ClipboardList, UserCircle, Sparkles, ShieldCheck, ChefHat, CalendarClock,
} from 'lucide-react';

import { useAuth } from '@/modules/auth/AuthContext';
import { rolesLabel } from '@/lib/roles';
import InstallButton from './InstallButton';
import ConnectivityBanner from './ConnectivityBanner';

// `module` matches a key in the backend's MODULE_PERMISSIONS — used to hide
// a tab when the caller's role/company access-control override says no.
// Tabs without a `module` are visible to everyone, same as before this existed.
//
// Grouped so the screens staff open constantly during a shift aren't mixed
// in with occasional setup/admin screens — mirrors the same grouping used
// in the native app's Workspace list. Every route/link below is unchanged,
// only organized into sections.
const NAV_GROUPS = [
  {
    title: 'Daily Operations',
    items: [
      { to: '/pos',        label: 'POS',        Icon: Calculator },
      { to: '/operations', label: 'Operations', Icon: ClipboardList },
      { to: '/tables',     label: 'Tables',     Icon: LayoutGrid },
      { to: '/kitchen',    label: 'Kitchen',    Icon: ChefHat },
      { to: '/gaming',     label: 'Gaming',     Icon: Gamepad2 },
      { to: '/reservations', label: 'Reservations', Icon: CalendarClock },
      { to: '/customers',  label: 'Customers',  Icon: UserCircle },
    ],
  },
  {
    title: 'Catalog & Stock',
    items: [
      { to: '/menu',      label: 'Menu',      Icon: BookOpen },
      { to: '/inventory', label: 'Inventory', Icon: Boxes, module: 'inventory' },
      { to: '/events',    label: 'Events',    Icon: Tv },
    ],
  },
  {
    title: 'Reports & Money',
    items: [
      { to: '/ocr',       label: 'OCR',       Icon: ScanLine },
      { to: '/analytics', label: 'Analytics', Icon: BarChart3, module: 'insights_reports' },
      { to: '/insights',  label: 'Insights',  Icon: Sparkles, module: 'insights_reports' },
      { to: '/reports',   label: 'Reports',   Icon: FileText, module: 'insights_reports' },
      { to: '/finance',   label: 'Finance',   Icon: Wallet },
    ],
  },
  {
    title: 'Setup',
    items: [
      { to: '/staff',    label: 'Staff',     Icon: Users },
      { to: '/audit',    label: 'Audit Log', Icon: ShieldCheck, protectedOnly: true },
      { to: '/settings', label: 'Settings',  Icon: Settings },
    ],
  },
];

const NAV = NAV_GROUPS.flatMap((g) => g.items);

export default function AppShell({ children }: { children?: ReactNode }) {
  const {
    me, logout, demo, terminalReady, terminalOptions, terminalIssue, selectTerminal,
  } = useAuth();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const loc = useLocation();
  // protected_access (co_owner or super_owner) bypasses per-module Access
  // Control gating below. audit_access (super_owner only) additionally
  // gates the Audit Log nav item — a co_owner must not see a tab that the
  // backend will 403 on (see core/permissions.py's admin.audit.read carve-out).
  const isProtectedOwner = Boolean(demo || me?.protected_access);
  const hasAuditAccess = Boolean(demo || me?.audit_access);
  const accessibleModules = me?.accessible_modules;
  const isVisible = useCallback(
    (item: (typeof NAV)[number]) => {
      if ('protectedOnly' in item && !hasAuditAccess) return false;
      if (item.module && !isProtectedOwner && accessibleModules && !accessibleModules.includes(item.module)) {
        return false;
      }
      return true;
    },
    [isProtectedOwner, hasAuditAccess, accessibleModules],
  );
  const navGroups = useMemo(
    () => NAV_GROUPS
      .map((g) => ({ ...g, items: g.items.filter(isVisible) }))
      .filter((g) => g.items.length > 0),
    [isVisible],
  );
  const navItems = useMemo(() => NAV.filter(isVisible), [isVisible]);

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
          className="flex-1 min-h-0 space-y-4 overflow-y-auto -mx-1 px-1"
          style={{ WebkitOverflowScrolling: 'touch' }}
        >
          {navGroups.map((group) => (
            <div key={group.title} className="space-y-1">
              <p className="px-3 pt-1 text-[10px] font-bold uppercase tracking-wider text-fg-muted/70">
                {group.title}
              </p>
              {group.items.map(({ to, label, Icon }) => (
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
            </div>
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
        <ConnectivityBanner />
        {!terminalReady && terminalIssue && (
          <div className="card mb-4 border-accent-gold/40 bg-accent-gold/10 text-sm">
            <div className="font-semibold text-accent-gold">Terminal setup required</div>
            <p className="mt-1 text-fg-muted">{terminalIssue}</p>
            {terminalOptions.length > 1 && (
              <div className="mt-3 flex flex-wrap gap-2">
                {terminalOptions.map((terminal) => (
                  <button
                    key={terminal.id}
                    className="btn btn-ghost"
                    onClick={() => selectTerminal(terminal.id)}
                  >
                    Use {terminal.name}
                  </button>
                ))}
              </div>
            )}
          </div>
        )}
        {children ?? <Outlet />}
      </main>
    </div>
  );
}
