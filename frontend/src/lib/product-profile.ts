/**
 * The web product is currently shipped as a focused Gaming Centre ERP.
 *
 * Keep dormant cafe/membership work behind this single profile instead of
 * deleting routes or scattering one-off booleans through the shell. Re-enable
 * a surface here only after its end-to-end workflow is part of the release
 * scope again.
 */

export interface WebFeatureFlags {
  gaming: boolean;
  pos: boolean;
  shifts: boolean;
  stock: boolean;
  help: boolean;
  dashboard: boolean;
  finance: boolean;
  reports: boolean;
  staffAdmin: boolean;
  settings: boolean;
  audit: boolean;
  supportInbox: boolean;
  tables: boolean;
  kitchen: boolean;
  reservations: boolean;
  customers: boolean;
  memberships: boolean;
  restaurantOrderTypes: boolean;
  taxCompliance: boolean;
  menuManagement: boolean;
  publicMenu: boolean;
  events: boolean;
  ocr: boolean;
  refundsWorkspace: boolean;
  advancedInsights: boolean;
}

export type WebFeature = keyof WebFeatureFlags;

export const GAMING_CENTRE_FEATURES: Readonly<WebFeatureFlags> = Object.freeze({
  gaming: true,
  pos: true,
  shifts: true,
  stock: true,
  help: true,
  dashboard: true,
  finance: true,
  reports: true,
  staffAdmin: true,
  settings: true,
  audit: true,
  supportInbox: true,

  // Deferred until the gaming-centre operation is stable in production.
  tables: false,
  kitchen: false,
  reservations: false,
  customers: false,
  memberships: false,
  restaurantOrderTypes: false,
  taxCompliance: false,
  // Drinks/crisps are still a real POS catalog. This is product management,
  // not the deferred table/kitchen restaurant workflow.
  menuManagement: true,
  publicMenu: false,
  events: false,
  ocr: false,
  refundsWorkspace: false,
  advancedInsights: false,
});

export const WEB_PRODUCT_PROFILE = Object.freeze({
  id: 'gaming-centre',
  label: 'Gaming Centre',
  defaultRoute: '/gaming',
  features: GAMING_CENTRE_FEATURES,
});

export type MembershipMoneyKind = 'revenue' | 'refund' | 'discount' | 'allowance';

const MEMBERSHIP_MONEY_LABELS: Readonly<Record<
  MembershipMoneyKind,
  { enabled: string; deferred: string }
>> = Object.freeze({
  revenue: { enabled: 'Memberships', deferred: 'Legacy prepaid programme' },
  refund: { enabled: 'Membership refunds', deferred: 'Legacy prepaid refunds' },
  discount: { enabled: 'Membership discount', deferred: 'Legacy programme discount' },
  allowance: { enabled: 'Membership benefit', deferred: 'Legacy prepaid allowance' },
});

/**
 * Membership workflows are deferred in the Gaming Centre profile, but old
 * financial facts must never disappear from reconciliation or reporting.
 * Zero rows are suppressed; non-zero legacy money remains visible under a
 * neutral accounting label until the feature is intentionally re-enabled.
 */
export function profileMembershipMoneyLabel(
  kind: MembershipMoneyKind,
  amountMinor: number,
  features: Readonly<WebFeatureFlags> = GAMING_CENTRE_FEATURES,
): string | null {
  if (!features.memberships && amountMinor === 0) return null;
  const labels = MEMBERSHIP_MONEY_LABELS[kind];
  return features.memberships ? labels.enabled : labels.deferred;
}

export type DeferredOperationalMoneyKind = 'eventRevenue' | 'deliveryRevenue' | 'taxCollected';

const DEFERRED_OPERATIONAL_MONEY_LABELS: Readonly<Record<
  DeferredOperationalMoneyKind,
  { enabled: string; deferred: string; feature: 'events' | 'restaurantOrderTypes' | 'taxCompliance' }
>> = Object.freeze({
  eventRevenue: {
    enabled: 'Event tickets',
    deferred: 'Legacy ticket revenue',
    feature: 'events',
  },
  deliveryRevenue: {
    enabled: 'Delivery (Zomato/Swiggy)',
    deferred: 'Legacy platform sales',
    feature: 'restaurantOrderTypes',
  },
  taxCollected: {
    enabled: 'GST collected',
    deferred: 'Recorded indirect tax',
    feature: 'taxCompliance',
  },
});

/**
 * Deferred workflows must not leave zero-value scaffolding in the focused
 * product. Historical money remains visible under a neutral label so report
 * totals always remain explainable and reconcilable.
 */
export function profileDeferredMoneyLabel(
  kind: DeferredOperationalMoneyKind,
  amountMinor: number,
  features: Readonly<WebFeatureFlags> = GAMING_CENTRE_FEATURES,
): string | null {
  const labels = DEFERRED_OPERATIONAL_MONEY_LABELS[kind];
  const enabled = features[labels.feature];
  if (!enabled && amountMinor === 0) return null;
  return enabled ? labels.enabled : labels.deferred;
}

export type ProfilePosOrderType = 'dine_in' | 'takeaway' | 'delivery';

interface ProfilePosDraftContext {
  orderType: ProfilePosOrderType;
  hasCheckoutRetry: boolean;
  resumingOrderId?: string;
}

/**
 * The Gaming Centre has one ordinary manual-products workflow: a counter
 * sale. Existing held-order and checkout-recovery journals retain their exact
 * order type because changing an in-flight financial request is unsafe.
 */
export function profilePosOrderType(
  context?: ProfilePosDraftContext,
  features: Readonly<WebFeatureFlags> = GAMING_CENTRE_FEATURES,
): ProfilePosOrderType {
  if (features.restaurantOrderTypes) return context?.orderType ?? 'dine_in';
  if (context?.hasCheckoutRetry || context?.resumingOrderId) return context.orderType;
  return 'takeaway';
}

export type ProfilePosCheckoutSource =
  | { kind: 'incoming'; orderId: string }
  | { kind: 'manual'; orderType: ProfilePosOrderType };

/**
 * Incoming Gaming bills are existing canonical orders and must never be
 * recreated as counter sales. Only a manual cart creates a new POS order.
 */
export function profilePosCheckoutSource(
  resumingOrderId: string | undefined,
  orderType: ProfilePosOrderType,
): ProfilePosCheckoutSource {
  return resumingOrderId
    ? { kind: 'incoming', orderId: resumingOrderId }
    : { kind: 'manual', orderType };
}

export type ProfileNavIcon =
  | 'gaming'
  | 'pos'
  | 'shift'
  | 'stock'
  | 'help'
  | 'dashboard'
  | 'finance'
  | 'reports'
  | 'staff'
  | 'settings'
  | 'audit'
  | 'supportInbox'
  | 'tables'
  | 'kitchen'
  | 'reservations'
  | 'customers'
  | 'memberships'
  | 'menu'
  | 'events'
  | 'ocr'
  | 'refunds'
  | 'insights';

type ProfileAudience =
  | 'staff'
  | 'owner'
  | 'audit'
  | 'system'
  | 'membership'
  | 'refund'
  | 'productManagement';

export interface ProfileNavigationItem {
  id: string;
  label: string;
  icon: ProfileNavIcon;
  feature: WebFeature;
  to?: string;
  action?: 'help';
  module?: string;
  audience?: ProfileAudience;
}

export interface ProfileNavigationGroup {
  title: string;
  items: readonly ProfileNavigationItem[];
}

export interface ProfileAccessContext {
  isOwner: boolean;
  hasAuditAccess: boolean;
  hasSystemAccess: boolean;
  hasMembershipAccess: boolean;
  hasRefundAccess: boolean;
  hasProductManagementAccess: boolean;
  accessibleModules?: readonly string[] | null;
}

interface ProductManagementIdentity {
  roles?: readonly string[] | null;
  effective_permissions?: readonly string[] | null;
}

/**
 * Exact menu.write is authoritative on current servers. The role fallback is
 * only for an older /auth/me response that omitted effective_permissions.
 */
export function canManageGamingCentreProducts(
  identity: ProductManagementIdentity | null | undefined,
  demo = false,
): boolean {
  if (demo) return true;
  if (identity?.effective_permissions) {
    return identity.effective_permissions.includes('menu.write');
  }
  return Boolean(identity?.roles?.some(
    (role) => ['super_owner', 'co_owner', 'owner', 'manager'].includes(role),
  ));
}

/**
 * All existing workspaces remain registered. Disabled groups disappear from
 * normal navigation, while turning their feature back on restores the route
 * entry without rebuilding the shell.
 */
export const PROFILE_NAVIGATION_GROUPS: readonly ProfileNavigationGroup[] = [
  {
    title: 'Gaming Centre',
    items: [
      { id: 'gaming', label: 'Gaming', icon: 'gaming', feature: 'gaming', to: '/gaming', module: 'gaming' },
      { id: 'pos', label: 'POS', icon: 'pos', feature: 'pos', to: '/pos', module: 'pos' },
      { id: 'shift', label: 'Shift', icon: 'shift', feature: 'shifts', to: '/operations', module: 'pos' },
      { id: 'stock', label: 'Stock', icon: 'stock', feature: 'stock', to: '/inventory', module: 'inventory' },
      { id: 'help', label: 'Help', icon: 'help', feature: 'help', action: 'help' },
    ],
  },
  {
    title: 'Owner',
    items: [
      { id: 'dashboard', label: 'Dashboard', icon: 'dashboard', feature: 'dashboard', to: '/analytics', module: 'insights_reports', audience: 'owner' },
      { id: 'finance', label: 'Finance', icon: 'finance', feature: 'finance', to: '/finance', module: 'finance', audience: 'owner' },
      { id: 'reports', label: 'Reports', icon: 'reports', feature: 'reports', to: '/reports', module: 'insights_reports', audience: 'owner' },
      { id: 'staff', label: 'Staff', icon: 'staff', feature: 'staffAdmin', to: '/staff', module: 'staff', audience: 'owner' },
      { id: 'settings', label: 'Settings', icon: 'settings', feature: 'settings', to: '/settings', audience: 'owner' },
      { id: 'products', label: 'Products', icon: 'menu', feature: 'menuManagement', to: '/menu', module: 'menu', audience: 'productManagement' },
    ],
  },
  {
    title: 'Protected Control',
    items: [
      { id: 'audit', label: 'Audit Log', icon: 'audit', feature: 'audit', to: '/audit', audience: 'audit' },
      { id: 'support-inbox', label: 'Support Inbox', icon: 'supportInbox', feature: 'supportInbox', to: '/bug-reports', audience: 'system' },
    ],
  },
  {
    title: 'Cafe & Later',
    items: [
      { id: 'tables', label: 'Tables', icon: 'tables', feature: 'tables', to: '/tables', module: 'tables' },
      { id: 'kitchen', label: 'Kitchen', icon: 'kitchen', feature: 'kitchen', to: '/kitchen', module: 'kitchen' },
      { id: 'reservations', label: 'Reservations', icon: 'reservations', feature: 'reservations', to: '/reservations', module: 'tables' },
      { id: 'customers', label: 'Customers', icon: 'customers', feature: 'customers', to: '/customers', module: 'pos' },
      { id: 'memberships', label: 'Memberships', icon: 'memberships', feature: 'memberships', to: '/memberships', audience: 'membership' },
      { id: 'events', label: 'Events', icon: 'events', feature: 'events', to: '/events' },
      { id: 'ocr', label: 'OCR', icon: 'ocr', feature: 'ocr', to: '/ocr', module: 'ocr' },
      { id: 'refunds', label: 'Refunds', icon: 'refunds', feature: 'refundsWorkspace', to: '/refunds', audience: 'refund' },
      { id: 'insights', label: 'Insights', icon: 'insights', feature: 'advancedInsights', to: '/insights', module: 'insights_reports', audience: 'owner' },
    ],
  },
] as const;

const ROUTE_FEATURE: Readonly<Record<string, WebFeature>> = Object.freeze({
  '/gaming': 'gaming',
  '/pos': 'pos',
  '/operations': 'shifts',
  '/inventory': 'stock',
  '/analytics': 'dashboard',
  '/finance': 'finance',
  '/reports': 'reports',
  '/staff': 'staffAdmin',
  '/settings': 'settings',
  '/audit': 'audit',
  '/bug-reports': 'supportInbox',
  '/tables': 'tables',
  '/kitchen': 'kitchen',
  '/reservations': 'reservations',
  '/customers': 'customers',
  '/memberships': 'memberships',
  '/menu': 'menuManagement',
  '/public/menu': 'publicMenu',
  '/events': 'events',
  '/ocr': 'ocr',
  '/refunds': 'refundsWorkspace',
  '/insights': 'advancedInsights',
});

function normalizedPath(pathname: string): string {
  const withoutHash = pathname.startsWith('#') ? pathname.slice(1) : pathname;
  const pathOnly = withoutHash.split(/[?#]/, 1)[0] || '/';
  return pathOnly.length > 1 ? pathOnly.replace(/\/+$/, '') : pathOnly;
}

export function featureForProfileRoute(pathname: string): WebFeature | null {
  return ROUTE_FEATURE[normalizedPath(pathname)] ?? null;
}

export function isProfileRouteEnabled(
  pathname: string,
  features: Readonly<WebFeatureFlags> = GAMING_CENTRE_FEATURES,
): boolean {
  const feature = featureForProfileRoute(pathname);
  return feature !== null && features[feature];
}

function hasAudienceAccess(item: ProfileNavigationItem, access: ProfileAccessContext): boolean {
  switch (item.audience ?? 'staff') {
    case 'staff': return true;
    case 'owner': return access.isOwner;
    case 'audit': return access.hasAuditAccess;
    case 'system': return access.hasSystemAccess;
    case 'membership': return access.hasMembershipAccess;
    case 'refund': return access.hasRefundAccess;
    case 'productManagement': return access.hasProductManagementAccess;
  }
}

export function isProfileNavigationItemVisible(
  item: ProfileNavigationItem,
  access: ProfileAccessContext,
  features: Readonly<WebFeatureFlags> = GAMING_CENTRE_FEATURES,
): boolean {
  if (!features[item.feature] || !hasAudienceAccess(item, access)) return false;
  if (
    item.module
    && !access.isOwner
    && access.accessibleModules
    && !access.accessibleModules.includes(item.module)
  ) {
    return false;
  }
  return true;
}

export function visibleProfileNavigationGroups(
  access: ProfileAccessContext,
  features: Readonly<WebFeatureFlags> = GAMING_CENTRE_FEATURES,
): ProfileNavigationGroup[] {
  return PROFILE_NAVIGATION_GROUPS
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => isProfileNavigationItemVisible(item, access, features)),
    }))
    .filter((group) => group.items.length > 0);
}
