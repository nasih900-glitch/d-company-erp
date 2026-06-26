/**
 * Kerala café demo data — every screen renders from these arrays.
 * All amounts in minor units (paise). All GST rates as decimals.
 *
 * Realistic prices for a mid-tier Kochi café (May 2026).
 */

export const COMPANY = {
  name: 'D Company',
  legal: 'D Company Cafés & Gaming Pvt. Ltd.',
  gstin: '32ABCDE1234F1Z5', // 32 = Kerala
  pan: 'ABCDE1234F',
  fssai: '12345678901234',
  trade_license: 'KMC/2026/CAFE/0042',
  state_code: '32',
  state_name: 'Kerala',
  address: 'No. 12, MG Road, Kochi, Kerala 682011',
  phone: '+91 484 222 1234',
  email: 'hello@dcompany.cloud',
  currency: 'INR',
  currency_symbol: '₹',
};

export type MenuItem = {
  id: string;
  category: string;
  sku: string;
  name: string;
  hsn: string;     // HSN/SAC code
  price: number;   // minor units, tax-inclusive
  rate: number;    // GST rate as decimal (0.05 = 5%)
  type: 'food' | 'drink' | 'dessert';
  emoji: string;
  description?: string;
};

export const MENU: MenuItem[] = [
  // ----- Coffee (Kerala café staples, SAC 996331 restaurant supply, 5% GST) -----
  { id: 'cap',   category: 'Coffee',    sku: 'COF-CAP',  name: 'Cappuccino',          hsn: '996331', price: 18000, rate: 0.05, type: 'drink',   emoji: '☕' },
  { id: 'lat',   category: 'Coffee',    sku: 'COF-LAT',  name: 'Café Latte',          hsn: '996331', price: 19000, rate: 0.05, type: 'drink',   emoji: '☕' },
  { id: 'amer',  category: 'Coffee',    sku: 'COF-AMR',  name: 'Americano',           hsn: '996331', price: 15000, rate: 0.05, type: 'drink',   emoji: '☕' },
  { id: 'esp',   category: 'Coffee',    sku: 'COF-ESP',  name: 'Espresso',            hsn: '996331', price: 12000, rate: 0.05, type: 'drink',   emoji: '☕' },
  { id: 'moc',   category: 'Coffee',    sku: 'COF-MOC',  name: 'Mocha',               hsn: '996331', price: 22000, rate: 0.05, type: 'drink',   emoji: '☕' },
  { id: 'frap',  category: 'Coffee',    sku: 'COF-FRP',  name: 'Frappé (Iced)',       hsn: '996331', price: 24000, rate: 0.05, type: 'drink',   emoji: '🥤' },
  // ----- Tea -----
  { id: 'chai',  category: 'Tea',       sku: 'TEA-CHI',  name: 'Masala Chai',         hsn: '996331', price: 8000,  rate: 0.05, type: 'drink',   emoji: '🍵' },
  { id: 'grnT',  category: 'Tea',       sku: 'TEA-GRN',  name: 'Green Tea',           hsn: '996331', price: 10000, rate: 0.05, type: 'drink',   emoji: '🍵' },
  { id: 'lemt',  category: 'Tea',       sku: 'TEA-LEM',  name: 'Lemon Tea',           hsn: '996331', price: 9000,  rate: 0.05, type: 'drink',   emoji: '🍋' },
  // ----- Food -----
  { id: 'cros',  category: 'Bakery',    sku: 'BAK-CRO',  name: 'Butter Croissant',    hsn: '996331', price: 15000, rate: 0.05, type: 'food',    emoji: '🥐' },
  { id: 'sand',  category: 'Food',      sku: 'FOO-SND',  name: 'Grilled Sandwich',    hsn: '996331', price: 22000, rate: 0.05, type: 'food',    emoji: '🥪' },
  { id: 'fries', category: 'Food',      sku: 'FOO-FRY',  name: 'Loaded Fries',        hsn: '996331', price: 24000, rate: 0.05, type: 'food',    emoji: '🍟' },
  { id: 'pizza', category: 'Food',      sku: 'FOO-PIZ',  name: 'Margherita Pizza',    hsn: '996331', price: 38000, rate: 0.05, type: 'food',    emoji: '🍕' },
  { id: 'paths', category: 'Food',      sku: 'FOO-PAT',  name: 'Kerala Parotta + Curry', hsn: '996331', price: 18000, rate: 0.05, type: 'food', emoji: '🍛' },
  { id: 'buruk', category: 'Food',      sku: 'FOO-BUR',  name: 'Cheeseburger',        hsn: '996331', price: 28000, rate: 0.05, type: 'food',    emoji: '🍔' },
  { id: 'paner', category: 'Food',      sku: 'FOO-PAN',  name: 'Paneer Wrap',         hsn: '996331', price: 24000, rate: 0.05, type: 'food',    emoji: '🌯' },
  // ----- Desserts -----
  { id: 'cake',  category: 'Desserts',  sku: 'DSR-CAK',  name: 'Chocolate Cake',      hsn: '996331', price: 22000, rate: 0.05, type: 'dessert', emoji: '🍰' },
  { id: 'cheese',category: 'Desserts',  sku: 'DSR-CHE',  name: 'Cheesecake',          hsn: '996331', price: 26000, rate: 0.05, type: 'dessert', emoji: '🍰' },
  { id: 'ice',   category: 'Desserts',  sku: 'DSR-ICE',  name: 'Ice Cream (2 scoops)',hsn: '996331', price: 18000, rate: 0.05, type: 'dessert', emoji: '🍨' },
  { id: 'bruwn', category: 'Desserts',  sku: 'DSR-BRW',  name: 'Brownie + Ice Cream', hsn: '996331', price: 24000, rate: 0.05, type: 'dessert', emoji: '🍫' },
  // ----- Cold drinks -----
  { id: 'water', category: 'Beverages', sku: 'BEV-WTR',  name: 'Bottled Water (500ml)',hsn:'220110', price: 4000,  rate: 0.18, type: 'drink',   emoji: '💧' },
  { id: 'cola',  category: 'Beverages', sku: 'BEV-COL',  name: 'Coca-Cola (300ml)',   hsn: '220210', price: 6000,  rate: 0.28, type: 'drink',   emoji: '🥤' },
  { id: 'oj',    category: 'Beverages', sku: 'BEV-OJ',   name: 'Fresh Orange Juice',  hsn: '220290', price: 14000, rate: 0.12, type: 'drink',   emoji: '🧃' },
  { id: 'mshk',  category: 'Beverages', sku: 'BEV-MSH',  name: 'Cold Coffee Shake',   hsn: '996331', price: 16000, rate: 0.05, type: 'drink',   emoji: '🥤' },
];

export const CATEGORIES = Array.from(new Set(MENU.map((m) => m.category)));

// ----- Ingredients (recipe deductions, FIFO batches) -----
export type Ingredient = {
  id: string;
  sku: string;
  name: string;
  unit: 'ml' | 'g' | 'unit';
  current: number;
  threshold: number;
  cost_minor: number; // per unit
};

export const INGREDIENTS: Ingredient[] = [
  { id: 'milk',    sku: 'ING-MLK',  name: 'Milk',                unit: 'ml',   current: 8400,  threshold: 2000, cost_minor: 6 },
  { id: 'beans',   sku: 'ING-BEN',  name: 'Coffee Beans',        unit: 'g',    current: 1850,  threshold: 500,  cost_minor: 180 },
  { id: 'sugar',   sku: 'ING-SUG',  name: 'Sugar',               unit: 'g',    current: 3200,  threshold: 500,  cost_minor: 5 },
  { id: 'flour',   sku: 'ING-FLR',  name: 'Maida (Flour)',       unit: 'g',    current: 12000, threshold: 2000, cost_minor: 5 },
  { id: 'butter',  sku: 'ING-BTR',  name: 'Butter',              unit: 'g',    current: 1800,  threshold: 400,  cost_minor: 60 },
  { id: 'cheese',  sku: 'ING-CHS',  name: 'Mozzarella Cheese',   unit: 'g',    current: 750,   threshold: 500,  cost_minor: 80 },
  { id: 'tom',     sku: 'ING-TOM',  name: 'Tomato',              unit: 'g',    current: 2400,  threshold: 800,  cost_minor: 4 },
  { id: 'pot',     sku: 'ING-POT',  name: 'Potato',              unit: 'g',    current: 6500,  threshold: 1500, cost_minor: 3 },
  { id: 'choc',    sku: 'ING-CHC',  name: 'Cocoa Powder',        unit: 'g',    current: 450,   threshold: 200,  cost_minor: 90 },
  { id: 'icecr',   sku: 'ING-ICR',  name: 'Vanilla Ice Cream',   unit: 'ml',   current: 4200,  threshold: 1000, cost_minor: 20 },
  { id: 'pat',     sku: 'ING-PAT',  name: 'Parotta',             unit: 'unit', current: 28,    threshold: 10,   cost_minor: 800 },
  { id: 'cury',    sku: 'ING-CRY',  name: 'Curry (per portion)', unit: 'unit', current: 24,    threshold: 8,    cost_minor: 1500 },
  { id: 'orng',    sku: 'ING-ORG',  name: 'Orange',              unit: 'unit', current: 60,    threshold: 20,   cost_minor: 1200 },
  { id: 'cola',    sku: 'ING-CLA',  name: 'Coca-Cola bottle 300ml',unit: 'unit', current: 36,  threshold: 12,   cost_minor: 3500 },
  { id: 'water',   sku: 'ING-WTR',  name: 'Water Bottle 500ml',  unit: 'unit', current: 84,    threshold: 24,   cost_minor: 1800 },
];

// ----- Gaming stations -----
export type Station = {
  id: string;
  code: string;
  name: string;
  type: 'ps5' | 'vr' | 'simulator';
  rate_per_hour: number; // minor units
  sac: string;
  rate_tax: number;
};
export const STATIONS: Station[] = [
  { id: 'ps1', code: 'PS5-01', name: 'PS5 Station 1', type: 'ps5', rate_per_hour: 20000, sac: '999692', rate_tax: 0.18 },
  { id: 'ps2', code: 'PS5-02', name: 'PS5 Station 2', type: 'ps5', rate_per_hour: 20000, sac: '999692', rate_tax: 0.18 },
  { id: 'ps3', code: 'PS5-03', name: 'PS5 Station 3', type: 'ps5', rate_per_hour: 20000, sac: '999692', rate_tax: 0.18 },
  { id: 'ps4', code: 'PS5-04', name: 'PS5 Station 4', type: 'ps5', rate_per_hour: 20000, sac: '999692', rate_tax: 0.18 },
  { id: 'vr1', code: 'VR-01',  name: 'VR Pod 1',      type: 'vr',  rate_per_hour: 35000, sac: '999692', rate_tax: 0.18 },
  { id: 'sim1',code: 'SIM-01', name: 'Racing Sim',    type: 'simulator', rate_per_hour: 40000, sac: '999692', rate_tax: 0.18 },
];

// ----- Tables (floor plan) -----
export type TableRec = {
  id: string;
  code: string;
  seats: number;
  status: 'available' | 'occupied' | 'reserved' | 'cleaning';
  x: number;
  y: number;
  reservation?: { guest_name: string; at: string; party: number };
};
export const TABLES: TableRec[] = [
  { id: 't01', code: 'T-01', seats: 2, status: 'available', x: 1, y: 1 },
  { id: 't02', code: 'T-02', seats: 2, status: 'occupied',  x: 2, y: 1 },
  { id: 't03', code: 'T-03', seats: 4, status: 'available', x: 3, y: 1 },
  { id: 't04', code: 'T-04', seats: 4, status: 'occupied',  x: 4, y: 1 },
  { id: 't05', code: 'T-05', seats: 4, status: 'reserved',  x: 1, y: 2, reservation: { guest_name: 'Anish K.', at: '20:30', party: 3 } },
  { id: 't06', code: 'T-06', seats: 4, status: 'available', x: 2, y: 2 },
  { id: 't07', code: 'T-07', seats: 6, status: 'occupied',  x: 3, y: 2 },
  { id: 't08', code: 'T-08', seats: 6, status: 'cleaning',  x: 4, y: 2 },
  { id: 't09', code: 'T-09', seats: 2, status: 'available', x: 1, y: 3 },
  { id: 't10', code: 'T-10', seats: 2, status: 'available', x: 2, y: 3 },
  { id: 't11', code: 'T-11', seats: 8, status: 'reserved',  x: 3, y: 3, reservation: { guest_name: 'Riya M.',  at: '21:00', party: 6 } },
  { id: 't12', code: 'T-12', seats: 8, status: 'available', x: 4, y: 3 },
];

// ----- Staff -----
export type StaffMember = {
  id: string;
  name: string;
  role: 'manager' | 'cashier' | 'kitchen' | 'gaming_supervisor';
  phone: string;
  status: 'on_shift' | 'off_shift' | 'on_break';
  clock_in?: string;
};
export const STAFF: StaffMember[] = [
  { id: 's1', name: 'Anish Kumar',     role: 'manager',           phone: '+91 98470 12345', status: 'on_shift', clock_in: '09:00' },
  { id: 's2', name: 'Priya Menon',     role: 'cashier',           phone: '+91 98470 23456', status: 'on_shift', clock_in: '10:00' },
  { id: 's3', name: 'Vishnu Nair',     role: 'cashier',           phone: '+91 98470 34567', status: 'on_break', clock_in: '14:00' },
  { id: 's4', name: 'Sneha Pillai',    role: 'kitchen',           phone: '+91 98470 45678', status: 'on_shift', clock_in: '08:00' },
  { id: 's5', name: 'Rajesh K.',       role: 'kitchen',           phone: '+91 98470 56789', status: 'on_shift', clock_in: '10:00' },
  { id: 's6', name: 'Arun Thomas',     role: 'gaming_supervisor', phone: '+91 98470 67890', status: 'on_shift', clock_in: '12:00' },
  { id: 's7', name: 'Lakshmi P.',      role: 'cashier',           phone: '+91 98470 78901', status: 'off_shift' },
  { id: 's8', name: 'Manoj V.',        role: 'kitchen',           phone: '+91 98470 89012', status: 'off_shift' },
];

// ----- Sample analytics data (for charts) -----
export const HOURLY_REVENUE = [
  { hour: '09', food: 1200, gaming: 0,    delivery: 800 },
  { hour: '10', food: 2400, gaming: 200,  delivery: 1200 },
  { hour: '11', food: 3100, gaming: 600,  delivery: 1800 },
  { hour: '12', food: 5800, gaming: 1200, delivery: 3400 },
  { hour: '13', food: 6900, gaming: 1600, delivery: 4100 },
  { hour: '14', food: 4200, gaming: 2400, delivery: 2400 },
  { hour: '15', food: 2800, gaming: 3200, delivery: 1800 },
  { hour: '16', food: 3400, gaming: 4400, delivery: 2200 },
  { hour: '17', food: 4900, gaming: 5800, delivery: 3000 },
  { hour: '18', food: 6800, gaming: 7200, delivery: 4400 },
  { hour: '19', food: 8400, gaming: 8800, delivery: 5600 },
  { hour: '20', food: 9200, gaming: 9400, delivery: 4800 },
  { hour: '21', food: 7600, gaming: 8200, delivery: 3200 },
  { hour: '22', food: 4400, gaming: 6400, delivery: 1600 },
];

export const TOP_ITEMS = [
  { name: 'Cappuccino',          qty: 142, revenue: 25560 },
  { name: 'Café Latte',          qty: 98,  revenue: 18620 },
  { name: 'Loaded Fries',        qty: 76,  revenue: 18240 },
  { name: 'Margherita Pizza',    qty: 48,  revenue: 18240 },
  { name: 'Chocolate Cake',      qty: 64,  revenue: 14080 },
  { name: 'Cheeseburger',        qty: 42,  revenue: 11760 },
  { name: 'Masala Chai',         qty: 124, revenue: 9920  },
  { name: 'Parotta + Curry',     qty: 38,  revenue: 6840  },
];

export const TODAY_KPI = {
  revenue_total_minor: 12_84_500,   // ₹12,845.00
  revenue_food_minor:  8_42_300,
  revenue_gaming_minor: 3_18_400,
  revenue_delivery_minor: 1_23_800,
  orders_count: 87,
  avg_ticket_minor: 14_770,         // ₹147.70
  open_sessions: 3,
  open_tables: 5,
  low_stock_items: 2,               // cheese + cocoa
  inventory_value_minor: 84_500_00, // ₹84,500
};

// ----- Sample bills (for the receipts history view) -----
export type RecentOrder = {
  id: string;
  invoice_no: string;
  type: 'dine_in' | 'takeaway' | 'delivery';
  table?: string;
  total_minor: number;
  method: 'cash' | 'upi' | 'card' | 'qr';
  at: string;
  items_count: number;
};
export const RECENT_ORDERS: RecentOrder[] = [
  { id: 'o1', invoice_no: 'D/MN/2026-27/00231', type: 'dine_in',  table: 'T-04', total_minor: 76_000, method: 'upi',  at: '18:42', items_count: 4 },
  { id: 'o2', invoice_no: 'D/MN/2026-27/00230', type: 'takeaway',                total_minor: 38_000, method: 'cash', at: '18:31', items_count: 2 },
  { id: 'o3', invoice_no: 'D/MN/2026-27/00229', type: 'dine_in',  table: 'T-02', total_minor:124_500, method: 'card', at: '18:18', items_count: 6 },
  { id: 'o4', invoice_no: 'D/MN/2026-27/00228', type: 'delivery',                total_minor: 56_000, method: 'upi',  at: '18:09', items_count: 3 },
  { id: 'o5', invoice_no: 'D/MN/2026-27/00227', type: 'dine_in',  table: 'T-07', total_minor:189_000, method: 'card', at: '17:54', items_count: 8 },
  { id: 'o6', invoice_no: 'D/MN/2026-27/00226', type: 'dine_in',  table: 'T-03', total_minor: 42_000, method: 'upi',  at: '17:41', items_count: 2 },
];

// ----- Projector / screening events -----
//
// Tax: SAC 999692 (amusement & recreation) at 18% GST (CGST 9% + SGST 9%).
// Same engine as gaming sessions — see docs/INDIA_TAX_COMPLIANCE.md §6.
export type EventScreening = {
  id: string;
  name: string;
  description: string;
  event_type: 'football' | 'cricket' | 'movie' | 'esports';
  screen: string;
  starts_at: string;     // ISO
  ends_at: string;
  capacity: number;
  sold: number;
  base_ticket_price_minor: number;
  sac_code: string;
  tax_rate: number;
  status: 'scheduled' | 'live' | 'ended';
  emoji: string;
};

export const EVENTS: EventScreening[] = [
  {
    id: 'ev1',
    name: 'Real Madrid vs Bayern Munich',
    description: 'UEFA Champions League — Semi-Final 2nd Leg',
    event_type: 'football',
    screen: 'Main Hall · 120″ Projector',
    starts_at: '2026-05-26T00:30:00+05:30',
    ends_at: '2026-05-26T02:45:00+05:30',
    capacity: 60,
    sold: 42,
    base_ticket_price_minor: 25000,  // ₹250 inclusive
    sac_code: '999692',
    tax_rate: 0.18,
    status: 'scheduled',
    emoji: '⚽',
  },
  {
    id: 'ev2',
    name: 'CSK vs MI — IPL Final',
    description: 'Indian Premier League 2026 Final',
    event_type: 'cricket',
    screen: 'Main Hall · 120″ Projector',
    starts_at: '2026-05-29T19:30:00+05:30',
    ends_at: '2026-05-29T23:30:00+05:30',
    capacity: 80,
    sold: 24,
    base_ticket_price_minor: 35000,  // ₹350 inclusive
    sac_code: '999692',
    tax_rate: 0.18,
    status: 'scheduled',
    emoji: '🏏',
  },
  {
    id: 'ev3',
    name: 'F1 Monaco Grand Prix',
    description: 'Live race + qualifying highlights',
    event_type: 'movie',
    screen: 'Side Room · 65″ TV',
    starts_at: '2026-05-25T17:30:00+05:30',
    ends_at: '2026-05-25T20:00:00+05:30',
    capacity: 25,
    sold: 25,  // Full
    base_ticket_price_minor: 15000,
    sac_code: '999692',
    tax_rate: 0.18,
    status: 'scheduled',
    emoji: '🏎️',
  },
  {
    id: 'ev4',
    name: 'India vs Pakistan — T20',
    description: 'ICC World Cup group stage match',
    event_type: 'cricket',
    screen: 'Main Hall · 120″ Projector',
    starts_at: '2026-06-12T15:00:00+05:30',
    ends_at: '2026-06-12T19:30:00+05:30',
    capacity: 80,
    sold: 8,
    base_ticket_price_minor: 50000,  // ₹500 — premium event
    sac_code: '999692',
    tax_rate: 0.18,
    status: 'scheduled',
    emoji: '🏏',
  },
  {
    id: 'ev5',
    name: 'BGMI Grand Final — South India',
    description: 'Esports tournament screening + meetup',
    event_type: 'esports',
    screen: 'Main Hall · 120″ Projector',
    starts_at: '2026-06-08T18:00:00+05:30',
    ends_at: '2026-06-08T22:00:00+05:30',
    capacity: 60,
    sold: 15,
    base_ticket_price_minor: 20000,
    sac_code: '999692',
    tax_rate: 0.18,
    status: 'scheduled',
    emoji: '🎮',
  },
];

// ----- OCR sample uploads -----
export const OCR_QUEUE = [
  { id: 'oc1', vendor: 'Coffee Day Beverages', date: '18-May-2026', amount_minor: 1_84_500, status: 'needs_review', confidence: 0.92 },
  { id: 'oc2', vendor: 'Saravana Bhavan Wholesale', date: '17-May-2026', amount_minor: 2_46_800, status: 'parsed', confidence: 0.88 },
  { id: 'oc3', vendor: 'Kerala Electricity Board', date: '15-May-2026', amount_minor: 8_42_300, status: 'approved', confidence: 0.95 },
  { id: 'oc4', vendor: 'BSNL', date: '12-May-2026', amount_minor: 78_500, status: 'approved', confidence: 0.97 },
];
