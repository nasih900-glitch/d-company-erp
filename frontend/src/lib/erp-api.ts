/**
 * Typed client for the D Company ERP backend.
 *
 * Imported only by screens that need real backend data. Demo-mode
 * screens go through @/lib/demo-data instead.
 *
 * All methods raise a normalized Error with .code (see /lib/api.ts).
 */
import { api } from './api';

export interface MeResponse {
  user_id: string;
  email: string;
  name: string;
  roles: string[];
  protected_access: boolean;
  company_id: string;
  branch_id: string | null;
}

export interface TokenPair {
  access_token: string;
  refresh_token: string;
  expires_in: number;
}

export interface MenuItemDTO {
  id: string;
  category_id: string;
  sku: string;
  name: string;
  type: string;
  base_price_minor: number;
  tax_rate: number;
  is_available: boolean;
}

export interface MenuCategoryDTO {
  id: string;
  name: string;
  sort_order: number;
}

export interface OrderLineDTO {
  menu_item_id: string;
  name: string;
  sku: string;
  hsn_or_sac: string;
  qty: number;
  unit_price_minor: number;
  line_total_minor: number;
  taxable_value_minor: number;
  tax_rate: number;
  cgst_minor: number;
  sgst_minor: number;
  igst_minor: number;
}

export interface OrderDTO {
  id: string;
  invoice_no: string | null;
  fiscal_year: string | null;
  status: string;
  type: string;
  subtotal_minor: number;
  discount_minor: number;
  cgst_minor: number;
  sgst_minor: number;
  igst_minor: number;
  cess_minor: number;
  tax_minor: number;
  round_off_minor: number;
  total_minor: number;
  delivery_via: string | null;
  place_of_supply_state_code: string | null;
  customer_name: string | null;
  customer_phone: string | null;
  customer_gstin: string | null;
  customer_state_code: string | null;
  lines: OrderLineDTO[];
}

export interface CreateOrderRequest {
  type: 'dine_in' | 'takeaway' | 'delivery';
  shift_id: string;
  table_id?: string;
  lines: Array<{ menu_item_id: string; qty: number }>;
  delivery_via?: 'inhouse' | 'zomato' | 'swiggy' | 'ubereats' | 'other_aggregator';
  customer_name?: string;
  customer_phone?: string;
  customer_gstin?: string;
  customer_address?: string;
  customer_state_code?: string;
  place_of_supply_state_code?: string;
}

// ----- Auth -----
export const auth = {
  login: (email: string, password: string) =>
    api.post<TokenPair>('/auth/login', { email, password }).then((r) => r.data),
  me: () => api.get<MeResponse>('/auth/me').then((r) => r.data),
};

// ----- Menu -----
export const menu = {
  categories: () =>
    api.get<MenuCategoryDTO[]>('/menu/categories').then((r) => r.data),
  items: (category_id?: string) =>
    api
      .get<MenuItemDTO[]>('/menu/items', { params: category_id ? { category_id } : {} })
      .then((r) => r.data),
};

// ----- POS -----
export const pos = {
  createOrder: (req: CreateOrderRequest, idempotencyKey: string) =>
    api
      .post<OrderDTO>('/pos/orders', req, {
        headers: { 'Idempotency-Key': idempotencyKey },
      })
      .then((r) => r.data),
  recordPayment: (
    orderId: string,
    body: {
      method: 'cash' | 'card' | 'upi' | 'qr' | 'wallet';
      amount_minor: number;
      tendered_minor?: number;
      ref_external?: string;
    },
    idempotencyKey: string,
  ) =>
    api
      .post<{ id: string; amount_minor: number; order_status: string }>(
        `/pos/orders/${orderId}/payments`,
        body,
        { headers: { 'Idempotency-Key': idempotencyKey } },
      )
      .then((r) => r.data),

  openShift: (opening_float_minor = 0) =>
    api
      .post<{ id: string; status: string }>('/pos/shifts/open', { opening_float_minor })
      .then((r) => r.data),
};

// =============================================================================
// STAFF — users + roles
// =============================================================================
export interface UserDTO {
  id: string;
  email: string;
  name: string;
  phone: string | null;
  status: 'active' | 'suspended';
  roles: string[];
  last_login_at: string | null;
}

export interface RoleDTO {
  code: string;
  name: string;
  description: string | null;
}

export const staff = {
  listUsers: () => api.get<UserDTO[]>('/staff/users').then((r) => r.data),
  getUser: (id: string) => api.get<UserDTO>(`/staff/users/${id}`).then((r) => r.data),
  createUser: (body: {
    email: string; name: string; password: string; phone?: string; role_code: string;
  }) => api.post<UserDTO>('/staff/users', body).then((r) => r.data),
  updateUser: (id: string, body: {
    name?: string; phone?: string; status?: 'active' | 'suspended'; role_code?: string;
  }) => api.patch<UserDTO>(`/staff/users/${id}`, body).then((r) => r.data),
  changePassword: (id: string, new_password: string) =>
    api.post(`/staff/users/${id}/password`, { new_password }),
  deleteUser: (id: string) => api.delete(`/staff/users/${id}`),
  listRoles: () => api.get<RoleDTO[]>('/staff/roles').then((r) => r.data),
  changeMyPassword: (current_password: string, new_password: string) =>
    api.post('/staff/me/password', { current_password, new_password }),
};

// =============================================================================
// MENU — categories + items (CRUD)
// =============================================================================
export const menuAdmin = {
  createCategory: (body: { name: string; sort_order?: number }) =>
    api.post<MenuCategoryDTO>('/menu/categories', body).then((r) => r.data),
  updateCategory: (id: string, body: { name?: string; sort_order?: number }) =>
    api.patch<MenuCategoryDTO>(`/menu/categories/${id}`, body).then((r) => r.data),
  deleteCategory: (id: string) => api.delete(`/menu/categories/${id}`),
  createItem: (body: {
    category_id: string; sku: string; name: string;
    type: 'food' | 'drink' | 'dessert' | 'gaming' | 'event' | 'hookah' | 'streaming';
    base_price_minor: number; tax_rate: number; description?: string;
  }) => api.post<MenuItemDTO>('/menu/items', body).then((r) => r.data),
  updateItem: (id: string, body: Partial<{
    category_id: string; name: string; base_price_minor: number;
    tax_rate: number; description: string; is_available: boolean;
  }>) => api.patch<MenuItemDTO>(`/menu/items/${id}`, body).then((r) => r.data),
  deleteItem: (id: string) => api.delete(`/menu/items/${id}`),
};

// =============================================================================
// INVENTORY — ingredients + suppliers + GRN + adjustments
// =============================================================================
export interface IngredientDTO {
  id: string;
  sku: string;
  name: string;
  base_unit: 'ml' | 'g' | 'unit';
  current_qty: number;
  reorder_threshold: number;
  reorder_qty: number;
  avg_cost_minor: number;
}

export interface SupplierDTO {
  id: string;
  name: string;
  contact: string | null;
  gstin: string | null;
  payment_terms: string | null;
}

export interface BatchDTO {
  id: string;
  ingredient_id: string;
  received_at: string;
  expires_at: string | null;
  qty_on_hand: number;
  cost_per_unit_minor: number;
  lot_code: string | null;
}

export const inventory = {
  listIngredients: () => api.get<IngredientDTO[]>('/inventory/ingredients').then((r) => r.data),
  createIngredient: (body: {
    sku: string; name: string; base_unit: 'ml' | 'g' | 'unit';
    reorder_threshold?: number; reorder_qty?: number;
  }) => api.post<IngredientDTO>('/inventory/ingredients', body).then((r) => r.data),
  updateIngredient: (id: string, body: Partial<{
    name: string; base_unit: 'ml' | 'g' | 'unit';
    reorder_threshold: number; reorder_qty: number;
  }>) => api.patch<IngredientDTO>(`/inventory/ingredients/${id}`, body).then((r) => r.data),
  deleteIngredient: (id: string) => api.delete(`/inventory/ingredients/${id}`),

  listSuppliers: () => api.get<SupplierDTO[]>('/inventory/suppliers').then((r) => r.data),
  createSupplier: (body: {
    name: string; contact?: string; gstin?: string; payment_terms?: string;
  }) => api.post<SupplierDTO>('/inventory/suppliers', body).then((r) => r.data),
  updateSupplier: (id: string, body: Partial<{
    name: string; contact: string; gstin: string; payment_terms: string;
  }>) => api.patch<SupplierDTO>(`/inventory/suppliers/${id}`, body).then((r) => r.data),
  deleteSupplier: (id: string) => api.delete(`/inventory/suppliers/${id}`),

  postGRN: (body: {
    branch_id: string; supplier_id?: string;
    supplier_invoice_no?: string;
    supplier_invoice_amount_minor?: number;
    received_at?: string;
    notes?: string;
    lines: Array<{
      ingredient_id: string; qty: number; unit_cost_minor: number;
      expires_at?: string; lot_code?: string;
    }>;
  }) => api.post<{ ok: boolean; batches_created: number; batch_ids: string[] }>(
    '/inventory/grn', body,
  ).then((r) => r.data),

  postAdjustment: (body: {
    ingredient_id: string; branch_id: string; qty_delta: number;
    type: 'waste' | 'damage' | 'transfer' | 'adjustment';
    note?: string;
  }) => api.post<{ id: string; remaining: number }>('/inventory/adjustments', body).then((r) => r.data),

  listBatches: (ingredient_id?: string) =>
    api.get<BatchDTO[]>('/inventory/batches', { params: ingredient_id ? { ingredient_id } : {} }).then((r) => r.data),
};

// =============================================================================
// FINANCE — expenses + partners + capital + assets
// =============================================================================
export interface ExpenseDTO {
  id: string;
  branch_id: string;
  category_id: string;
  supplier_id: string | null;
  amount_minor: number;
  paid_via: 'cash' | 'card' | 'bank' | 'upi';
  paid_at: string;
  vendor_name: string | null;
  invoice_no: string | null;
  note: string | null;
}

export interface PartnerDTO {
  id: string;
  name: string;
  share_pct: number;
  joined_at: string;
  notes: string | null;
  capital_balance_minor: number;
}

export interface CapitalEntryDTO {
  id: string;
  partner_id: string;
  type: 'invest' | 'withdraw' | 'profit_share';
  amount_minor: number;
  effective_at: string;
  note: string | null;
}

export const finance = {
  listExpenses: (params?: { from_date?: string; to_date?: string }) =>
    api.get<ExpenseDTO[]>('/finance/expenses', { params }).then((r) => r.data),
  createExpense: (body: {
    branch_id: string; category_id: string; supplier_id?: string;
    amount_minor: number; paid_via: 'cash' | 'card' | 'bank' | 'upi';
    paid_at: string; vendor_name?: string; invoice_no?: string; note?: string;
  }) => api.post<ExpenseDTO>('/finance/expenses', body).then((r) => r.data),
  deleteExpense: (id: string) => api.delete(`/finance/expenses/${id}`),

  listPartners: () => api.get<PartnerDTO[]>('/finance/partners').then((r) => r.data),
  createPartner: (body: {
    name: string; share_pct: number; joined_at: string; user_id?: string; notes?: string;
  }) => api.post<PartnerDTO>('/finance/partners', body).then((r) => r.data),
  updatePartner: (id: string, body: Partial<{ name: string; share_pct: number; notes: string }>) =>
    api.patch<PartnerDTO>(`/finance/partners/${id}`, body).then((r) => r.data),

  listCapital: (partner_id: string) =>
    api.get<CapitalEntryDTO[]>(`/finance/partners/${partner_id}/capital`).then((r) => r.data),
  createCapitalEntry: (body: {
    partner_id: string; type: 'invest' | 'withdraw' | 'profit_share';
    amount_minor: number; effective_at: string; note?: string;
  }) => api.post<CapitalEntryDTO>('/finance/capital-entries', body).then((r) => r.data),
};

// =============================================================================
// SETTINGS — company, branches, terminals, expense categories
// =============================================================================
export interface CompanyDTO {
  id: string;
  name: string;
  legal_name: string | null;
  currency: string;
  timezone: string;
  country: string | null;
  gstin: string | null;
  pan: string | null;
  gst_registration_type: string;
  is_composition: boolean;
  e_invoicing_enabled: boolean;
  fiscal_year_start_month: number;
  google_sheets_webhook_url: string | null;
}

export interface BranchDTO {
  id: string;
  name: string;
  code: string | null;
  address: string | null;
  timezone: string | null;
  opens_at: string | null;
  closes_at: string | null;
  state_code: string | null;
  fssai_license_no: string | null;
  trade_license_no: string | null;
  branch_gstin: string | null;
}

export interface TerminalDTO {
  id: string;
  branch_id: string;
  name: string;
  device_id: string | null;
  last_seen_at: string | null;
}

export interface ExpenseCategoryDTO {
  id: string;
  name: string;
  code: string | null;
}

// =============================================================================
// AUDIT — full change history
// =============================================================================
export interface AuditEntryDTO {
  id: number;
  actor_user_id: string | null;
  actor_name: string | null;
  actor_email: string | null;
  action: string;
  entity_type: string;
  entity_id: string;
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
  ip: string | null;
  user_agent: string | null;
  created_at: string;
}
export interface AuditFacetsDTO {
  entity_types: string[];
  actions: string[];
}
export interface AuditUnlockDTO {
  audit_token: string;
  expires_in: number;
}
export interface PricingUnlockDTO {
  pricing_token: string;
  expires_in: number;
}

export const audit = {
  unlock: (password: string) =>
    api.post<AuditUnlockDTO>('/admin/audit/unlock', { password }).then((r) => r.data),
  list: (params?: {
    limit?: number; entity_type?: string; action?: string;
    actor_user_id?: string; entity_id?: string; q?: string; area?: string;
  }, auditToken?: string) =>
    api
      .get<AuditEntryDTO[]>('/admin/audit', {
        params,
        headers: auditToken ? { 'X-Audit-Token': auditToken } : undefined,
      })
      .then((r) => r.data),
  facets: (auditToken?: string) =>
    api
      .get<AuditFacetsDTO>('/admin/audit/facets', {
        headers: auditToken ? { 'X-Audit-Token': auditToken } : undefined,
      })
      .then((r) => r.data),
};

export const pricingLock = {
  unlock: (password: string) =>
    api.post<PricingUnlockDTO>('/admin/pricing/unlock', { password }).then((r) => r.data),
};

// =============================================================================
// ACCOUNTING — Chart of Accounts, Trial Balance, Balance Sheet, GL
// =============================================================================
export interface AccountDTO {
  id: string; code: string; name: string;
  type: string; normal_side: string; is_active: boolean;
}
export interface TBLineDTO {
  account_code: string; account_name: string; account_type: string;
  debit_minor: number; credit_minor: number; balance_minor: number;
}
export interface TrialBalanceDTO {
  as_of: string; lines: TBLineDTO[];
  total_debit_minor: number; total_credit_minor: number; is_balanced: boolean;
}
export interface BSSectionDTO {
  section: string; lines: Array<{ name: string; amount_minor: number }>; total_minor: number;
}
export interface BalanceSheetDTO {
  as_of: string;
  assets: BSSectionDTO; liabilities: BSSectionDTO; equity: BSSectionDTO;
  is_balanced: boolean;
}
export interface GLEntryDTO {
  occurred_at: string; ref_type: string; ref_id: string | null;
  account_code: string; account_name: string;
  debit_minor: number; credit_minor: number; memo: string | null;
}

export const accounting = {
  chartOfAccounts: () => api.get<AccountDTO[]>('/accounting/chart-of-accounts').then((r) => r.data),
  trialBalance: (as_of?: string) =>
    api.get<TrialBalanceDTO>('/accounting/trial-balance', { params: as_of ? { as_of } : {} }).then((r) => r.data),
  balanceSheet: (as_of?: string) =>
    api.get<BalanceSheetDTO>('/accounting/balance-sheet', { params: as_of ? { as_of } : {} }).then((r) => r.data),
  generalLedger: (params?: { from_date?: string; to_date?: string; limit?: number }) =>
    api.get<GLEntryDTO[]>('/accounting/general-ledger', { params }).then((r) => r.data),
};

// =============================================================================
// INSIGHTS — inventory valuation, recipe margin, top items, growth, losses
// =============================================================================
export interface ValuationLineDTO {
  ingredient_id: string; sku: string; name: string;
  base_unit: string; current_qty: number;
  avg_cost_minor: number; valuation_minor: number;
  reorder_threshold: number; is_low_stock: boolean;
}
export interface InventoryValuationDTO {
  as_of: string; lines: ValuationLineDTO[];
  total_valuation_minor: number; low_stock_count: number;
}
export interface RecipeMarginDTO {
  menu_item_id: string; sku: string; name: string; type: string;
  sale_price_minor: number; cost_minor: number;
  margin_minor: number; margin_pct: number;
}
export interface TopItemDTO {
  menu_item_id: string; name: string; type: string;
  qty_sold: number; revenue_minor: number;
}
export interface GrowthPeriodDTO {
  label: string; revenue_minor: number;
  orders_count: number; avg_ticket_minor: number;
}
export interface GrowthDTO {
  current: GrowthPeriodDTO; previous: GrowthPeriodDTO;
  revenue_delta_pct: number; orders_delta_pct: number;
}
export interface HeatmapCellDTO {
  day_of_week: number; hour: number;
  revenue_minor: number; orders_count: number;
}
export interface LossLineDTO {
  ingredient_id: string; sku: string; name: string;
  qty_lost: number; cost_lost_minor: number; movement_count: number;
}
export interface LossesDTO {
  from_date: string; to_date: string;
  waste_minor: number; damage_minor: number; negative_stock_minor: number;
  total_loss_minor: number; lines: LossLineDTO[];
}

export const insights = {
  inventoryValuation: () =>
    api.get<InventoryValuationDTO>('/insights/inventory/valuation').then((r) => r.data),
  recipeMargin: () =>
    api.get<RecipeMarginDTO[]>('/insights/menu/recipe-margin').then((r) => r.data),
  growth: (period: 'mom' | 'yoy' | 'wow' = 'mom') =>
    api.get<GrowthDTO>('/insights/growth', { params: { period } }).then((r) => r.data),
  topItems: (params?: { from_date?: string; to_date?: string; limit?: number }) =>
    api.get<TopItemDTO[]>('/insights/top-items', { params }).then((r) => r.data),
  heatmap: (params?: { from_date?: string; to_date?: string }) =>
    api.get<HeatmapCellDTO[]>('/insights/heatmap', { params }).then((r) => r.data),
  losses: (params?: { from_date?: string; to_date?: string }) =>
    api.get<LossesDTO>('/insights/losses', { params }).then((r) => r.data),
};

// =============================================================================
// CUSTOMERS — phone-based loyalty
// =============================================================================
export interface CustomerDTO {
  id: string;
  name: string | null;
  phone: string;
  email: string | null;
  birthday: string | null;
  visit_count: number;
  total_spent_minor: number;
  loyalty_points: number;
  last_visit_at: string | null;
  notes: string | null;
}

// =============================================================================
// MEMBERSHIPS — D Club tiers + subscriptions
// =============================================================================
export interface MembershipTierDTO {
  id: string;
  code: string;
  name: string;
  monthly_price_minor: number;
  annual_price_minor: number | null;
  food_discount_pct: number;
  gaming_discount_pct: number;
  hookah_discount_pct: number;
  point_multiplier: number;
  free_gaming_minutes_per_week: number;
  free_hookah_per_month: number;
  priority_booking: boolean;
  description: string | null;
  sort_order: number;
}

export interface SubscriptionDTO {
  id: string;
  customer_id: string;
  tier_id: string;
  tier_code: string;
  tier_name: string;
  billing_cycle: 'monthly' | 'annual';
  starts_at: string;
  expires_at: string;
  cancelled_at: string | null;
  auto_renew: boolean;
  amount_paid_minor: number;
  is_active: boolean;
}

export const memberships = {
  listTiers: () => api.get<MembershipTierDTO[]>('/memberships/tiers').then((r) => r.data),
  createTier: (body: Partial<MembershipTierDTO> & { code: string; name: string; monthly_price_minor: number }) =>
    api.post<MembershipTierDTO>('/memberships/tiers', body).then((r) => r.data),
  updateTier: (id: string, body: Partial<MembershipTierDTO>) =>
    api.patch<MembershipTierDTO>(`/memberships/tiers/${id}`, body).then((r) => r.data),
  subscribe: (body: {
    customer_id: string; tier_id: string;
    billing_cycle?: 'monthly' | 'annual';
    paid_via?: 'cash' | 'card' | 'upi' | 'razorpay';
  }) => api.post<SubscriptionDTO>('/memberships/subscribe', body).then((r) => r.data),
  getCustomerSubscription: (customer_id: string) =>
    api.get<SubscriptionDTO | null>(`/memberships/customer/${customer_id}`).then((r) => r.data),
  cancel: (subscription_id: string) =>
    api.post<SubscriptionDTO>(`/memberships/${subscription_id}/cancel`).then((r) => r.data),
};

// =============================================================================
// KITCHEN DISPLAY SYSTEM
// =============================================================================
export interface KitchenLineDTO {
  menu_item_id: string;
  name: string;
  type: string;
  qty: number;
  notes: string | null;
}

export interface KitchenOrderDTO {
  id: string;
  invoice_no: string | null;
  type: string;
  table_code: string | null;
  customer_name: string | null;
  opened_at: string;
  kitchen_state: 'received' | 'preparing' | 'ready' | 'served';
  minutes_waiting: number;
  lines: KitchenLineDTO[];
}

export const kitchen = {
  queue: () => api.get<KitchenOrderDTO[]>('/kitchen/queue').then((r) => r.data),
  setState: (order_id: string, state: 'received' | 'preparing' | 'ready' | 'served') =>
    api.patch<KitchenOrderDTO>(`/kitchen/orders/${order_id}/state`, { state }).then((r) => r.data),
};

export const customers = {
  list: (q?: string) =>
    api.get<CustomerDTO[]>('/customers', { params: q ? { q } : {} }).then((r) => r.data),
  byPhone: (phone: string) =>
    api.get<CustomerDTO | null>(`/customers/by-phone/${encodeURIComponent(phone)}`).then((r) => r.data),
  get: (id: string) => api.get<CustomerDTO>(`/customers/${id}`).then((r) => r.data),
  upsert: (body: { phone: string; name?: string; email?: string; birthday?: string; notes?: string }) =>
    api.post<CustomerDTO>('/customers', body).then((r) => r.data),
  update: (id: string, body: Partial<{ name: string; email: string; birthday: string; notes: string }>) =>
    api.patch<CustomerDTO>(`/customers/${id}`, body).then((r) => r.data),
};

// =============================================================================
// PUBLIC — read-only (no auth) endpoints powering QR-table-ordering pages
// =============================================================================
export interface PublicMenuItemDTO {
  id: string;
  sku: string;
  name: string;
  type: string;
  base_price_minor: number;
  tax_rate: number;
  description: string | null;
  category_id: string;
  category_name: string;
  category_sort: number;
}

export interface PublicMenuDTO {
  company_name: string;
  company_gstin: string | null;
  categories: Array<{ id: string; name: string; sort_order: number }>;
  items: PublicMenuItemDTO[];
}

export const publicApi = {
  menu: () => api.get<PublicMenuDTO>('/public/menu').then((r) => r.data),
};

// =============================================================================
// OCR — receipts upload, verification queue, approve/reject
// =============================================================================
export interface OcrExtractionDTO {
  id: string;
  vendor_name: string | null;
  invoice_no: string | null;
  invoice_date: string | null;
  amount_minor: number | null;
  status: 'parsed' | 'needs_review' | 'approved' | 'rejected' | 'duplicate';
}

export const ocr = {
  listQueue: () => api.get<OcrExtractionDTO[]>('/ocr/queue').then((r) => r.data),
  upload: (file: File, branch_id: string) => {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('branch_id', branch_id);
    fd.append('source', 'manual');
    return api.post('/ocr/uploads', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then((r) => r.data);
  },
  verify: (extraction_id: string, decision: 'approve' | 'reject' | 'edit', notes?: string) =>
    api.post(`/ocr/extractions/${extraction_id}/verify`, { decision, notes }),
};

// =============================================================================
// REPORTS — daily/monthly/quarterly/yearly P&L
// =============================================================================
export interface ReportRevenueDTO {
  food_minor: number;
  gaming_minor: number;
  hookah_minor: number;
  event_tickets_minor: number;
  delivery_aggregator_minor: number;
  other_minor: number;
  total_minor: number;
}
export interface ReportTaxDTO {
  cgst_minor: number;
  sgst_minor: number;
  igst_minor: number;
  cess_minor: number;
  total_minor: number;
}
export interface ReportPaymentsDTO {
  cash_minor: number;
  upi_minor: number;
  card_minor: number;
  qr_minor: number;
  wallet_minor: number;
  other_minor: number;
  total_minor: number;
}
export interface ReportExpenseLineDTO {
  category: string;
  amount_minor: number;
}
export interface ReportDataDTO {
  period: string;
  label: string;
  period_start: string;
  period_end: string;
  fiscal_year: string;
  orders_count: number;
  tickets_count: number;
  avg_ticket_minor: number;
  revenue: ReportRevenueDTO;
  tax_collected: ReportTaxDTO;
  payments_received: ReportPaymentsDTO;
  expenses: ReportExpenseLineDTO[];
  expense_total_minor: number;
  gross_revenue_minor: number;
  net_revenue_minor: number;
  net_profit_minor: number;
}

export const reports = {
  daily: (on_date: string) =>
    api.get<ReportDataDTO>('/reports/daily', { params: { on_date } }).then((r) => r.data),
  monthly: (yyyy_mm: string) =>
    api.get<ReportDataDTO>('/reports/monthly', { params: { yyyy_mm } }).then((r) => r.data),
  quarterly: (fy: string, q: number) =>
    api.get<ReportDataDTO>('/reports/quarterly', { params: { fy, q } }).then((r) => r.data),
  yearly: (fy: string) =>
    api.get<ReportDataDTO>('/reports/yearly', { params: { fy } }).then((r) => r.data),
};

// =============================================================================
// ANALYTICS — dashboard
// =============================================================================
export interface DashboardKPIsDTO {
  date: string;
  revenue_food_minor: number;
  revenue_gaming_minor: number;
  revenue_hookah_minor: number;
  revenue_events_minor: number;
  revenue_total_minor: number;
  orders_count: number;
  tickets_count: number;
  avg_ticket_minor: number;
  inventory_value_minor: number;
  low_stock_items: number;
  open_sessions: number;
  net_profit_minor: number;
}

export const analytics = {
  dashboard: (on_date: string) =>
    api.get<DashboardKPIsDTO>('/analytics/dashboard', { params: { on_date } }).then((r) => r.data),
};

// =============================================================================
// POS — orders + shifts (list)
// =============================================================================
export interface OrderListItemDTO {
  id: string;
  invoice_no: string | null;
  type: string;
  status: string;
  total_minor: number;
  items_count: number;
  customer_name: string | null;
  created_at: string;
}

export interface ShiftDTO {
  id: string;
  status: 'open' | 'closed';
  opened_at: string;
  closed_at: string | null;
  opening_float_minor: number;
  expected_minor: number | null;
  counted_minor: number | null;
  variance_minor: number | null;
}

export const orders = {
  list: (params?: { from_date?: string; to_date?: string; limit?: number }) =>
    api.get<OrderListItemDTO[]>('/pos/orders', { params }).then((r) => r.data),
  get: (id: string) => api.get(`/pos/orders/${id}`).then((r) => r.data),
};

export const shifts = {
  list: (only_open = false) =>
    api.get<ShiftDTO[]>('/pos/shifts', { params: { only_open } }).then((r) => r.data),
  open: (opening_float_minor: number) =>
    api.post<{ id: string; status: string }>('/pos/shifts/open', { opening_float_minor })
      .then((r) => r.data),
  close: (id: string, counted_minor: number) =>
    api.post<{ id: string; status: string; variance_minor: number }>(`/pos/shifts/${id}/close`, { counted_minor })
      .then((r) => r.data),
};

// =============================================================================
// TABLES — floors + tables + reservations
// =============================================================================
export interface FloorDTO {
  id: string;
  branch_id: string;
  name: string;
}

export interface TableDTO {
  id: string;
  floor_id: string;
  code: string;
  seats: number;
  shape: 'rect' | 'round' | 'booth';
  x: number;
  y: number;
  status: 'available' | 'occupied' | 'reserved' | 'cleaning' | 'merged';
}

export const tables = {
  list: () => api.get<TableDTO[]>('/tables').then((r) => r.data),
  listFloors: () => api.get<FloorDTO[]>('/tables/floors').then((r) => r.data),
  createFloor: (body: { name: string; branch_id?: string }) =>
    api.post<FloorDTO>('/tables/floors', body).then((r) => r.data),
  create: (body: {
    floor_id?: string; code: string; seats: number;
    shape?: 'rect' | 'round' | 'booth'; x?: number; y?: number;
  }) => api.post<TableDTO>('/tables', body).then((r) => r.data),
  update: (id: string, body: Partial<{
    code: string; seats: number; shape: 'rect' | 'round' | 'booth'; x: number; y: number;
  }>) => api.patch<TableDTO>(`/tables/${id}`, body).then((r) => r.data),
  updateStatus: (id: string, status: TableDTO['status']) =>
    api.patch<TableDTO>(`/tables/${id}/status`, { status }).then((r) => r.data),
  delete: (id: string) => api.delete(`/tables/${id}`),
};

// =============================================================================
// GAMING — stations + sessions
// =============================================================================
export interface StationDTO {
  id: string;
  code: string;
  name: string;
  type: 'ps5' | 'vr' | 'simulator' | 'projector' | 'hookah' | 'streaming';
  rate_per_hour_minor: number;
  is_active: boolean;
}

export interface GameSessionDTO {
  id: string;
  station_id: string;
  status: 'active' | 'paused' | 'ended';
  start_at: string;
  end_at: string | null;
  billable_minutes: number | null;
  amount_minor: number | null;
}

export const gaming = {
  listStations: () => api.get<StationDTO[]>('/gaming/stations').then((r) => r.data),
  createStation: (body: {
    code: string; name: string;
    type: 'ps5' | 'vr' | 'simulator' | 'projector' | 'hookah' | 'streaming';
    rate_per_hour_minor: number; branch_id?: string; notes?: string;
  }) => api.post<StationDTO>('/gaming/stations', body).then((r) => r.data),
  updateStation: (id: string, body: Partial<{
    name: string; rate_per_hour_minor: number; is_active: boolean; notes: string;
  }>) => api.patch<StationDTO>(`/gaming/stations/${id}`, body).then((r) => r.data),
  deleteStation: (id: string) => api.delete(`/gaming/stations/${id}`),

  startSession: (body: { station_id: string; shift_id: string; customer_name?: string; customer_phone?: string }) =>
    api.post<GameSessionDTO>('/gaming/sessions/start', body).then((r) => r.data),
  stopSession: (id: string) =>
    api.post<GameSessionDTO>(`/gaming/sessions/${id}/stop`).then((r) => r.data),
};

// =============================================================================
// EVENTS — projector screenings, ticket sales, check-in
// =============================================================================
export interface EventDTO {
  id: string;
  name: string;
  description: string | null;
  event_type: 'football' | 'cricket' | 'movie' | 'esports' | 'other';
  screen: string;
  starts_at: string;
  ends_at: string | null;
  capacity: number;
  sold: number;
  remaining: number;
  base_ticket_price_minor: number;
  sac_code: string;
  tax_rate: number;
  status: 'scheduled' | 'live' | 'ended' | 'cancelled';
  poster_url: string | null;
}

export interface EventTicketDTO {
  id: string;
  ticket_no: string;
  event_id: string;
  event_name: string;
  customer_name: string | null;
  customer_phone: string | null;
  seat: string | null;
  price_paid_minor: number;
  status: 'sold' | 'checked_in' | 'cancelled' | 'refunded' | 'no_show';
  checked_in_at: string | null;
}

export const events = {
  listUpcoming: () => api.get<EventDTO[]>('/events/upcoming').then((r) => r.data),
  listAll: (include_past = true) =>
    api.get<EventDTO[]>('/events/all', { params: { include_past } }).then((r) => r.data),
  get: (id: string) => api.get<EventDTO>(`/events/${id}`).then((r) => r.data),
  create: (body: {
    name: string; description?: string;
    event_type: 'football' | 'cricket' | 'movie' | 'esports' | 'other';
    screen?: string; starts_at: string; ends_at?: string;
    capacity: number; base_ticket_price_minor: number;
    poster_url?: string; branch_id?: string;
  }) => api.post<EventDTO>('/events', body).then((r) => r.data),
  update: (id: string, body: Partial<{
    name: string; description: string;
    event_type: 'football' | 'cricket' | 'movie' | 'esports' | 'other';
    screen: string; starts_at: string; ends_at: string;
    capacity: number; base_ticket_price_minor: number;
    poster_url: string;
    status: 'scheduled' | 'live' | 'ended' | 'cancelled';
  }>) => api.patch<EventDTO>(`/events/${id}`, body).then((r) => r.data),
  delete: (id: string) => api.delete(`/events/${id}`),

  sellTickets: (event_id: string, body: {
    customer_name: string; customer_phone?: string;
    seat?: string; qty?: number; note?: string;
  }) => api.post<EventTicketDTO[]>(`/events/${event_id}/tickets`, body).then((r) => r.data),
  listTickets: (event_id: string) =>
    api.get<EventTicketDTO[]>(`/events/${event_id}/tickets`).then((r) => r.data),
  checkIn: (event_id: string, ticket_id: string) =>
    api.post<EventTicketDTO>(`/events/${event_id}/tickets/${ticket_id}/check-in`)
      .then((r) => r.data),
};

export const settings = {
  getCompany: () => api.get<CompanyDTO>('/settings/company').then((r) => r.data),
  updateCompany: (body: Partial<CompanyDTO>) =>
    api.patch<CompanyDTO>('/settings/company', body).then((r) => r.data),

  listBranches: () => api.get<BranchDTO[]>('/settings/branches').then((r) => r.data),
  createBranch: (body: Partial<BranchDTO>) =>
    api.post<BranchDTO>('/settings/branches', body).then((r) => r.data),
  updateBranch: (id: string, body: Partial<BranchDTO>) =>
    api.patch<BranchDTO>(`/settings/branches/${id}`, body).then((r) => r.data),

  listTerminals: (branch_id?: string) =>
    api.get<TerminalDTO[]>('/settings/terminals', { params: branch_id ? { branch_id } : {} }).then((r) => r.data),
  createTerminal: (body: { branch_id: string; name: string; device_id?: string }) =>
    api.post<TerminalDTO>('/settings/terminals', body).then((r) => r.data),
  deleteTerminal: (id: string) => api.delete(`/settings/terminals/${id}`),

  listExpenseCategories: () =>
    api.get<ExpenseCategoryDTO[]>('/settings/expense-categories').then((r) => r.data),
  createExpenseCategory: (body: { name: string; code?: string }) =>
    api.post<ExpenseCategoryDTO>('/settings/expense-categories', body).then((r) => r.data),
};
