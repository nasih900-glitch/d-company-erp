import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { NavLink, Outlet, useLocation, type To } from 'react-router-dom';
import {
  Calculator, LayoutGrid, BookOpen, Boxes, Gamepad2,
  Wallet, ScanLine, Users, LogOut, Tv, Settings, Menu, X, FileText,
  ClipboardList, UserCircle, Sparkles, ShieldCheck, ChefHat, CalendarClock,
  MessageSquareWarning, RotateCcw,
  CreditCard, HelpCircle, LayoutDashboard,
  TabletSmartphone, MonitorCog, Moon, Sun,
  type LucideIcon,
} from 'lucide-react';

import { useAuth } from '@/modules/auth/AuthContext';
import { hasSupportAccess } from '@/lib/admin-access';
import { rolesLabel } from '@/lib/roles';
import { canAccessRefunds } from '@/modules/refunds/refund-policy';
import { canViewMemberships } from '@/modules/memberships/membership-policy';
import {
  canManageGamingCentreProducts,
  GAMING_CENTRE_TERMINAL_POLICY,
  visibleProfileNavigationGroups,
  type ProfileNavIcon, type ProfileNavigationItem,
} from '@/lib/product-profile';
import {
  applyAppearancePreference, nextAppearancePreference, readAppearancePreference,
  saveAppearancePreference, type AppearancePreference,
} from '@/lib/appearance';
import DCompanyBrandMark from '@/components/brand/DCompanyBrandMark';
import InstallButton from './InstallButton';
import ConnectivityBanner from './ConnectivityBanner';
import SupportLauncher, { openSupportLauncher } from '@/components/support/SupportLauncher';
import { bugReports } from '@/lib/erp-api';
import { subscribeRealtime } from '@/lib/realtime';
import { internalAppRouteOr } from '@/lib/internal-navigation';

const NAV_ICONS: Record<ProfileNavIcon, LucideIcon> = {
  gaming: Gamepad2,
  pos: Calculator,
  shift: ClipboardList,
  stock: Boxes,
  help: HelpCircle,
  dashboard: LayoutDashboard,
  finance: Wallet,
  reports: FileText,
  staff: Users,
  settings: Settings,
  audit: ShieldCheck,
  supportInbox: MessageSquareWarning,
  deviceCentre: TabletSmartphone,
  tables: LayoutGrid,
  kitchen: ChefHat,
  reservations: CalendarClock,
  customers: UserCircle,
  memberships: CreditCard,
  menu: BookOpen,
  events: Tv,
  ocr: ScanLine,
  refunds: RotateCcw,
  insights: Sparkles,
};

function isRouteActive(item: ProfileNavigationItem, pathname: string, search: string): boolean {
  if (!item.to) return false;
  const [path, query = ''] = item.to.split('?');
  if (pathname !== path) return false;
  if (!query) return true;
  const expected = new URLSearchParams(query);
  const actual = new URLSearchParams(search);
  return Array.from(expected).every(([key, value]) => actual.get(key) === value);
}

export function navigationTarget(item: Pick<ProfileNavigationItem, 'to'>): To {
  if (item.to === '/operations?tab=shifts') {
    return { pathname: internalAppRouteOr('/operations'), search: '?tab=shifts' };
  }
  return internalAppRouteOr(item.to ?? '/gaming');
}

function AppearanceIcon({ preference }: { preference: AppearancePreference }) {
  if (preference === 'day') return <Sun size={18}/>;
  if (preference === 'night') return <Moon size={18}/>;
  return <MonitorCog size={18}/>;
}

export default function AppShell({ children }: { children?: ReactNode }) {
  const {
    me, logout, demo, terminalId, terminalReady, terminalOptions, terminalIssue, selectTerminal,
  } = useAuth();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const loc = useLocation();
  const drawerRef = useRef<HTMLElement>(null);
  const drawerTriggerRef = useRef<HTMLButtonElement>(null);
  const mainRef = useRef<HTMLElement>(null);
  const mobileHeaderRef = useRef<HTMLElement>(null);
  const [appearance, setAppearance] = useState<AppearancePreference>(() => readAppearancePreference());
  const [systemNight, setSystemNight] = useState(() => globalThis.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false);
  // protected_access (co_owner or super_owner) bypasses per-module Access
  // Control gating below. audit_access (super_owner only) additionally
  // gates the Audit Log nav item — a co_owner must not see a tab that the
  // backend will 403 on (see core/permissions.py's admin.audit.read carve-out).
  const isProtectedOwner = Boolean(demo || me?.protected_access);
  const hasAuditAccess = Boolean(demo || me?.audit_access);
  const hasSystemAccess = hasSupportAccess(me);
  const hasRefundAccess = Boolean(demo || canAccessRefunds(me));
  const hasMembershipAccess = Boolean(demo || canViewMemberships(me));
  const hasProductManagementAccess = canManageGamingCentreProducts(me, demo);
  const [supportUnread, setSupportUnread] = useState(0);
  const accessibleModules = me?.accessible_modules;
  const navGroups = useMemo(
    () => visibleProfileNavigationGroups({
      isOwner: isProtectedOwner,
      hasAuditAccess,
      hasSystemAccess,
      hasRefundAccess,
      hasMembershipAccess,
      hasProductManagementAccess,
      accessibleModules,
    }),
    [
      accessibleModules,
      hasAuditAccess,
      hasMembershipAccess,
      hasProductManagementAccess,
      hasRefundAccess,
      hasSystemAccess,
      isProtectedOwner,
    ],
  );
  const navItems = useMemo(() => navGroups.flatMap((group) => group.items), [navGroups]);

  const current = useMemo(
    () => navItems.find((item) => isRouteActive(item, loc.pathname, loc.search)),
    [loc.pathname, loc.search, navItems],
  );

  const initials = useMemo(
    () => (me?.name ?? 'D')
      .split(' ')
      .map((p) => p[0])
      .slice(0, 2)
      .join(''),
    [me?.name],
  );
  const selectedTerminal = useMemo(
    () => terminalOptions.find((terminal) => terminal.id === terminalId) ?? null,
    [terminalId, terminalOptions],
  );

  useEffect(() => applyAppearancePreference(appearance), [appearance]);
  useEffect(() => {
    const query = globalThis.matchMedia?.('(prefers-color-scheme: dark)');
    if (!query) return undefined;
    const update = () => setSystemNight(query.matches);
    query.addEventListener('change', update);
    return () => query.removeEventListener('change', update);
  }, []);
  const resolvedNight = appearance === 'night' || (appearance === 'system' && systemNight);

  const cycleAppearance = () => {
    const next = nextAppearancePreference(appearance);
    saveAppearancePreference(next);
    setAppearance(next);
  };
  const toggleDayNight = () => {
    const next: AppearancePreference = resolvedNight ? 'day' : 'night';
    saveAppearancePreference(next);
    setAppearance(next);
  };

  const refreshSupportSummary = useCallback(() => {
    if (!hasSystemAccess || demo) {
      setSupportUnread(0);
      return;
    }
    const controller = new AbortController();
    void bugReports.inboxSummary(controller.signal)
      .then((summary) => setSupportUnread(summary.unread))
      .catch(() => { /* the protected inbox itself shows actionable load errors */ });
    return () => controller.abort();
  }, [demo, hasSystemAccess]);

  useEffect(() => {
    const abort = refreshSupportSummary();
    const unsubscribe = subscribeRealtime('bug_reports', refreshSupportSummary);
    return () => {
      abort?.();
      unsubscribe();
    };
  }, [refreshSupportSummary]);

  // Close the drawer whenever the route changes.
  const onNavigate = () => setDrawerOpen(false);

  useEffect(() => {
    setDrawerOpen(false);
  }, [loc.pathname, loc.search]);

  useEffect(() => {
    if (!drawerOpen) return undefined;

    const previousOverflow = document.body.style.overflow;
    const drawerTrigger = drawerTriggerRef.current;
    const background = [mobileHeaderRef.current, mainRef.current]
      .filter((node): node is HTMLElement => node !== null);
    background.forEach((node) => { node.inert = true; });
    const focusableSelector = 'button:not([disabled]), a[href], select:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])';
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        setDrawerOpen(false);
        return;
      }
      if (event.key !== 'Tab') return;
      const focusable = Array.from(drawerRef.current?.querySelectorAll<HTMLElement>(focusableSelector) ?? []);
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };

    document.body.style.overflow = 'hidden';
    window.addEventListener('keydown', onKeyDown);
    const focusFrame = window.requestAnimationFrame(() => {
      drawerRef.current?.querySelector<HTMLElement>(focusableSelector)?.focus();
    });

    return () => {
      window.cancelAnimationFrame(focusFrame);
      document.body.style.overflow = previousOverflow;
      window.removeEventListener('keydown', onKeyDown);
      background.forEach((node) => { node.inert = false; });
      drawerTrigger?.focus();
    };
  }, [drawerOpen]);

  return (
    <div className="min-h-[100dvh] overflow-x-hidden lg:grid lg:h-[100dvh] lg:grid-cols-[260px_1fr]">
      {/* Compact/mobile + tablet portrait: top bar. Keep the permanent
          navigation rail for landscape/desktop widths where it does not
          crowd the cashier workspace. */}
      <header
        ref={mobileHeaderRef}
        className="lg:hidden sticky top-0 z-30 bg-bg-surface/95 backdrop-blur border-b border-bg-border flex items-center justify-between px-3 py-3"
        style={{ paddingTop: 'max(0.75rem, env(safe-area-inset-top))' }}
      >
        <div className="flex items-center gap-3">
          <button
            ref={drawerTriggerRef}
            onClick={() => setDrawerOpen(true)}
            aria-label="Open menu"
            className="p-2 -m-2 rounded-lg hover:bg-bg-raised active:scale-95 transition"
          >
            <Menu size={22} />
          </button>
          <DCompanyBrandMark className="h-9 w-9"/>
          <div>
            <div className="text-base font-bold tracking-tight leading-none">D Company</div>
            <div className="text-[10px] text-fg-muted leading-none mt-0.5">
              {current?.label ?? 'ERP'}
            </div>
          </div>
        </div>
        <div className="flex items-center gap-1">
          <button type="button" onClick={toggleDayNight}
            className="tap-target grid place-items-center rounded-full text-fg-muted hover:bg-bg-raised hover:text-fg"
            aria-label={`Appearance: ${appearance}. Change appearance`} title={`Appearance: ${appearance}`}>
            <AppearanceIcon preference={appearance}/>
          </button>
          <div className="w-9 h-9 rounded-full bg-fg grid place-items-center font-bold text-bg text-sm">
            {initials}
          </div>
        </div>
      </header>

      {/* ===================== SIDEBAR =====================
          Landscape/desktop: always visible, fixed column.
          Compact/mobile: off-canvas drawer; slides in when drawerOpen=true. */}
      <aside
        ref={drawerRef}
        aria-label="Application navigation"
        className={`
          fixed inset-y-0 left-0 z-50 w-[min(84vw,280px)] bg-bg-surface border-r border-bg-border p-4 flex min-h-0 flex-col
          transition-transform duration-200 ease-out will-change-transform lg:w-[260px] lg:translate-x-0 lg:static lg:z-auto lg:will-change-auto
          ${drawerOpen ? 'translate-x-0 shadow-2xl' : '-translate-x-full'}
        `}
        style={{ paddingTop: 'max(1rem, env(safe-area-inset-top))' }}
      >
        <div className="flex items-center justify-between gap-3 px-2 pb-4 md:pb-6">
          <div className="flex min-w-0 items-center gap-3">
            <DCompanyBrandMark/>
            <div className="min-w-0">
              <h1 className="truncate text-xl font-bold tracking-tight">D Company</h1>
              <p className="text-xs text-fg-muted">ERP V1</p>
            </div>
          </div>
          <button
            onClick={() => setDrawerOpen(false)}
            aria-label="Close menu"
            className="lg:hidden p-2 -m-2 rounded-lg hover:bg-bg-raised"
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
              {group.items.map((item) => {
                const Icon = NAV_ICONS[item.icon];
                const content = (
                  <>
                    <Icon size={18} />
                    {item.label}
                    {item.to === '/bug-reports' && supportUnread > 0 && (
                      <span className="ml-auto grid min-w-5 place-items-center rounded-full bg-accent-bad px-1.5 py-0.5 text-[10px] font-bold text-white">
                        {Math.min(supportUnread, 99)}
                      </span>
                    )}
                  </>
                );
                if (item.action === 'help') {
                  return (
                    <button
                      key={item.id}
                      type="button"
                      onClick={() => {
                        onNavigate();
                        openSupportLauncher();
                      }}
                      className="flex w-full items-center gap-3 rounded-xl px-3 py-3 text-left text-sm font-medium text-fg-muted hover:bg-bg-raised/50 hover:text-fg active:scale-[0.98]"
                    >
                      {content}
                    </button>
                  );
                }
                if (!item.to) return null;
                return (
                  <NavLink
                    key={item.id}
                    to={navigationTarget(item)}
                    onClick={onNavigate}
                    className={() =>
                      `flex items-center gap-3 px-3 py-3 rounded-xl text-sm font-medium ` +
                      (isRouteActive(item, loc.pathname, loc.search)
                        ? 'bg-bg-raised text-fg shadow-glow'
                        : 'text-fg-muted hover:text-fg hover:bg-bg-raised/50 active:scale-[0.98]')
                    }
                  >
                    {content}
                  </NavLink>
                );
              })}
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
          <button type="button" onClick={cycleAppearance}
            className="btn btn-ghost mt-3 w-full !justify-start"
            aria-label={`Appearance: ${appearance}. Change appearance`}>
            <AppearanceIcon preference={appearance}/>
            Appearance · {appearance}
          </button>
          <div className="px-2 py-3">
            <p className="text-sm font-medium">{me?.name}</p>
            <p className="text-xs text-fg-muted">{rolesLabel(me?.roles)}</p>
            {GAMING_CENTRE_TERMINAL_POLICY.showRoutineSelector
              && selectedTerminal && terminalOptions.length > 1 && (
              <p className="mt-1 text-xs text-fg-muted">Terminal · {selectedTerminal.name}</p>
            )}
            {GAMING_CENTRE_TERMINAL_POLICY.showRoutineSelector
              && terminalOptions.length > 1 && (
              <label className="mt-3 block">
                <span className="mb-1 block text-[10px] font-semibold uppercase tracking-wide text-fg-muted">
                  Work terminal
                </span>
                <select
                  className="input !min-h-[42px] !py-2 text-xs"
                  value={terminalId ?? ''}
                  onChange={(event) => selectTerminal(event.target.value)}
                  aria-label="Change work terminal"
                >
                  {terminalOptions.map((terminal) => (
                    <option key={terminal.id} value={terminal.id}>
                      {terminal.name} · {terminal.purpose === 'gaming'
                        ? 'Gaming'
                        : terminal.purpose === 'cafe_pos' ? 'POS' : 'Combined'}
                    </option>
                  ))}
                </select>
                <span className="mt-1 block text-[10px] leading-4 text-fg-muted">
                  Drafts and open-shift selection stay separate for each terminal.
                </span>
              </label>
            )}
          </div>
          <button onClick={logout} className="btn btn-ghost w-full mt-2">
            <LogOut size={16} /> Sign out
          </button>
        </div>
      </aside>

      {/* Compact-layout backdrop when drawer is open. */}
      {drawerOpen && (
        <div
          onClick={() => setDrawerOpen(false)}
          aria-hidden
          className="lg:hidden fixed inset-0 z-40 bg-bg/80 backdrop-blur-sm"
        />
      )}

      {/* ===================== MAIN CONTENT ===================== */}
      <main
        ref={mainRef}
        className="app-scroll route-frame px-3 py-4 md:p-6 min-w-0"
        style={{ paddingBottom: 'max(1rem, env(safe-area-inset-bottom))' }}
      >
        <div className="hidden h-12 items-start justify-end lg:flex">
        <button
          type="button"
          onClick={toggleDayNight}
          className="flex min-h-11 items-center gap-2 rounded-full border border-bg-border bg-bg-surface px-4 text-xs font-bold tracking-wide hover:bg-bg-raised"
          aria-label={`Appearance: ${appearance}. Change between day and night`}
          title={`Appearance: ${appearance}`}
          data-testid="workspace-day-night-toggle"
        >
          <AppearanceIcon preference={appearance}/>
          {resolvedNight ? 'Night mode' : 'Day mode'}
        </button>
        </div>
        <ConnectivityBanner />
        {!terminalReady && terminalIssue && (
          <div className="card mb-4 border-accent-gold/40 bg-accent-gold/10 text-sm">
            <div className="font-semibold text-accent-gold">Shared register setup needs attention</div>
            <p className="mt-1 text-fg-muted">{terminalIssue}</p>
            {GAMING_CENTRE_TERMINAL_POLICY.showRoutineSelector
              && terminalOptions.length > 1 && (
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
      <SupportLauncher inboxUnread={hasSystemAccess ? supportUnread : 0} />
    </div>
  );
}
