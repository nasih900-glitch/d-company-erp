import Dexie, { type Table } from 'dexie';

/**
 * Local-first DB for offline POS support.
 *
 * - `outbox`: queued mutations that need to sync to the server. Each entry
 *   carries an `idempotency_key` so server-side replays are deduped.
 * - `menu_cache`, `tables_cache`: read snapshots refreshed periodically.
 */
export interface OutboxItem {
  id?: number;
  endpoint: string;
  method: 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body: unknown;
  idempotency_key: string;
  client_seq: number;
  created_at: number;
  attempts: number;
  last_error?: string;
}

export interface MenuItemCached {
  id: string;
  sku: string;
  name: string;
  type: string;
  base_price_minor: number;
  tax_rate: number;
  category_id: string;
  is_available: boolean;
}

class ErpDB extends Dexie {
  outbox!: Table<OutboxItem, number>;
  menu_cache!: Table<MenuItemCached, string>;

  constructor() {
    super('d-company-erp');
    this.version(1).stores({
      outbox: '++id, idempotency_key, created_at',
      menu_cache: 'id, category_id, sku',
    });
  }
}

export const idb = new ErpDB();
