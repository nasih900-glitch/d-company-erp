import { useEffect, useMemo, useState } from 'react';
import {
  AlertCircle, Crown, Gamepad2, IndianRupee, KeyRound, Loader2, Lock,
  RefreshCw, Save, Search, Ticket, Utensils,
} from 'lucide-react';

import { inr } from '@/lib/inr';
import { APP_STORE_REVIEW, isAppStoreAllowedType } from '@/lib/app-store-compliance';
import {
  events,
  gaming,
  menu,
  menuAdmin,
  memberships,
  pricingLock,
  type EventDTO,
  type MembershipTierDTO,
  type MenuItemDTO,
  type StationDTO,
} from '@/lib/erp-api';

type MenuDraft = { price: string; gst: string };
type StationDraft = { rate: string };
type EventDraft = { price: string };
type TierDraft = { monthly: string; annual: string };
type Drafts = {
  menu: Record<string, MenuDraft>;
  stations: Record<string, StationDraft>;
  events: Record<string, EventDraft>;
  tiers: Record<string, TierDraft>;
};

const EMPTY_DRAFTS: Drafts = { menu: {}, stations: {}, events: {}, tiers: {} };

export default function PricingTab() {
  const [unlocked, setUnlocked] = useState(hasValidPricingToken);
  const [password, setPassword] = useState('');
  const [unlocking, setUnlocking] = useState(false);
  const [items, setItems] = useState<MenuItemDTO[]>([]);
  const [stations, setStations] = useState<StationDTO[]>([]);
  const [eventRows, setEventRows] = useState<EventDTO[]>([]);
  const [tiers, setTiers] = useState<MembershipTierDTO[]>([]);
  const [drafts, setDrafts] = useState<Drafts>(EMPTY_DRAFTS);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    setErr(null);
    setNotice(null);
    try {
      const [menuItems, stationRows, eventsList, tierRows] = await Promise.all([
        menu.items(),
        gaming.listStations(),
        events.listAll(true),
        memberships.listTiers(),
      ]);
      const visibleMenuItems = menuItems.filter((item) => isAppStoreAllowedType(item.type));
      const visibleStationRows = stationRows.filter((station) => isAppStoreAllowedType(station.type));
      setItems(visibleMenuItems);
      setStations(visibleStationRows);
      setEventRows(eventsList);
      setTiers(tierRows);
      setDrafts(buildDrafts(visibleMenuItems, visibleStationRows, eventsList, tierRows));
    } catch (e) {
      const message = (e as Error).message;
      setErr(message);
      if (isPricingUnlockError(message)) lockPricing();
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { if (unlocked) load(); }, [unlocked]);

  async function unlock(e: React.FormEvent) {
    e.preventDefault();
    setUnlocking(true);
    setErr(null);
    try {
      const r = await pricingLock.unlock(password);
      localStorage.setItem('pricing_token', r.pricing_token);
      localStorage.setItem('pricing_token_expires_at', String(Date.now() + r.expires_in * 1000));
      setPassword('');
      setUnlocked(true);
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setUnlocking(false);
    }
  }

  function lockPricing() {
    localStorage.removeItem('pricing_token');
    localStorage.removeItem('pricing_token_expires_at');
    setUnlocked(false);
    setItems([]);
    setStations([]);
    setEventRows([]);
    setTiers([]);
    setDrafts(EMPTY_DRAFTS);
    setNotice(null);
  }

  const q = query.trim().toLowerCase();
  const filteredItems = useMemo(
    () => items.filter((item) => matches(q, item.name, item.sku, item.type)),
    [items, q],
  );
  const filteredStations = useMemo(
    () => stations.filter((station) => matches(q, station.name, station.code, station.type)),
    [stations, q],
  );
  const filteredEvents = useMemo(
    () => eventRows.filter((event) => matches(q, event.name, event.screen, event.status)),
    [eventRows, q],
  );
  const filteredTiers = useMemo(
    () => tiers.filter((tier) => matches(q, tier.name, tier.code)),
    [tiers, q],
  );

  function updateMenuDraft(id: string, patch: Partial<MenuDraft>) {
    setDrafts((d) => ({
      ...d,
      menu: { ...d.menu, [id]: { ...(d.menu[id] ?? { price: '', gst: '' }), ...patch } },
    }));
  }

  function updateStationDraft(id: string, patch: Partial<StationDraft>) {
    setDrafts((d) => ({
      ...d,
      stations: { ...d.stations, [id]: { ...(d.stations[id] ?? { rate: '' }), ...patch } },
    }));
  }

  function updateEventDraft(id: string, patch: Partial<EventDraft>) {
    setDrafts((d) => ({
      ...d,
      events: { ...d.events, [id]: { ...(d.events[id] ?? { price: '' }), ...patch } },
    }));
  }

  function updateTierDraft(id: string, patch: Partial<TierDraft>) {
    setDrafts((d) => ({
      ...d,
      tiers: { ...d.tiers, [id]: { ...(d.tiers[id] ?? { monthly: '', annual: '' }), ...patch } },
    }));
  }

  async function saveMenuItem(item: MenuItemDTO) {
    const draft = drafts.menu[item.id];
    if (!draft) return;
    await save(`menu:${item.id}`, async () => {
      const updated = await menuAdmin.updateItem(item.id, {
        base_price_minor: toMinor(draft.price, 'Menu price'),
        tax_rate: toRate(draft.gst, 'GST rate'),
      });
      setItems((rows) => rows.map((row) => row.id === updated.id ? updated : row));
      updateMenuDraft(updated.id, {
        price: toRupees(updated.base_price_minor),
        gst: toPercent(updated.tax_rate),
      });
      return `${updated.name} price saved`;
    });
  }

  async function saveStation(station: StationDTO) {
    const draft = drafts.stations[station.id];
    if (!draft) return;
    await save(`station:${station.id}`, async () => {
      const updated = await gaming.updateStation(station.id, {
        rate_per_hour_minor: toMinor(draft.rate, 'Hourly rate'),
      });
      setStations((rows) => rows.map((row) => row.id === updated.id ? updated : row));
      updateStationDraft(updated.id, { rate: toRupees(updated.rate_per_hour_minor) });
      return `${updated.name} hourly rate saved`;
    });
  }

  async function saveEvent(event: EventDTO) {
    const draft = drafts.events[event.id];
    if (!draft) return;
    await save(`event:${event.id}`, async () => {
      const updated = await events.update(event.id, {
        base_ticket_price_minor: toMinor(draft.price, 'Ticket price'),
      });
      setEventRows((rows) => rows.map((row) => row.id === updated.id ? updated : row));
      updateEventDraft(updated.id, { price: toRupees(updated.base_ticket_price_minor) });
      return `${updated.name} ticket price saved`;
    });
  }

  async function saveTier(tier: MembershipTierDTO) {
    const draft = drafts.tiers[tier.id];
    if (!draft) return;
    await save(`tier:${tier.id}`, async () => {
      const updated = await memberships.updateTier(tier.id, {
        monthly_price_minor: toMinor(draft.monthly, 'Monthly price'),
        annual_price_minor: draft.annual.trim() ? toMinor(draft.annual, 'Annual price') : null,
      });
      setTiers((rows) => rows.map((row) => row.id === updated.id ? updated : row));
      updateTierDraft(updated.id, {
        monthly: toRupees(updated.monthly_price_minor),
        annual: updated.annual_price_minor ? toRupees(updated.annual_price_minor) : '',
      });
      return `${updated.name} membership price saved`;
    });
  }

  async function save(key: string, fn: () => Promise<string>) {
    setSaving(key);
    setErr(null);
    setNotice(null);
    try {
      setNotice(await fn());
    } catch (e) {
      const message = (e as Error).message;
      setErr(message);
      if (isPricingUnlockError(message)) lockPricing();
    } finally {
      setSaving(null);
    }
  }

  if (!unlocked) {
    return (
      <form onSubmit={unlock} className="card max-w-sm space-y-4">
        <div className="flex items-center gap-2 font-semibold">
          <KeyRound size={16} className="text-accent"/> Unlock Pricing
        </div>
        <label className="block">
          <span className="text-xs text-fg-muted">Current password</span>
          <input
            className="input mt-1"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoFocus
          />
        </label>
        {err && (
          <div className="text-accent-bad text-sm flex items-center gap-2">
            <AlertCircle size={14}/> {err}
          </div>
        )}
        <button className="btn btn-primary w-full" disabled={unlocking || !password} type="submit">
          {unlocking ? <Loader2 className="animate-spin" size={14}/> : <Lock size={14}/>}
          {unlocking ? 'Unlocking...' : 'Unlock'}
        </button>
      </form>
    );
  }

  if (loading) {
    return (
      <div className="card flex items-center gap-3 text-fg-muted">
        <Loader2 className="animate-spin" size={16}/> Loading prices...
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-3 items-center justify-between">
        <div className="relative flex-1 min-w-[240px] max-w-xl">
          <Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-fg-muted"/>
          <input
            className="input !pl-9"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search item, SKU, station, event, tier..."
          />
        </div>
        <button className="btn btn-ghost" onClick={load} disabled={loading || !!saving}>
          <RefreshCw size={14}/> Refresh
        </button>
        <button className="btn btn-ghost" onClick={lockPricing} disabled={!!saving}>
          <Lock size={14}/> Lock
        </button>
      </div>

      {err && <Status tone="bad" icon={<AlertCircle size={14}/>}>{err}</Status>}
      {notice && <Status tone="good" icon={<IndianRupee size={14}/>}>{notice}</Status>}

      <PriceSection icon={<Utensils size={16}/>} title="Menu Item Prices" count={filteredItems.length}>
        {!filteredItems.length ? <EmptyLine/> : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm min-w-[760px]">
              <thead className="bg-bg-raised border-b border-bg-border">
                <tr>
                  <th className="text-left p-3">Item</th>
                  <th className="text-left p-3">SKU</th>
                  <th className="text-left p-3">Type</th>
                  <th className="text-right p-3">Current</th>
                  <th className="text-right p-3">Price INR</th>
                  <th className="text-right p-3">GST %</th>
                  <th className="text-right p-3 pr-4">Save</th>
                </tr>
              </thead>
              <tbody>
                {filteredItems.map((item, idx) => {
                  const draft = drafts.menu[item.id] ?? { price: '', gst: '' };
                  const key = `menu:${item.id}`;
                  return (
                    <tr key={item.id} className={idx % 2 ? 'bg-bg-surface/50' : ''}>
                      <td className="p-3 font-medium">{item.name}</td>
                      <td className="p-3 font-mono text-xs text-fg-muted">{item.sku}</td>
                      <td className="p-3 text-xs text-fg-muted">{item.type}</td>
                      <td className="p-3 text-right font-mono">{inr(item.base_price_minor)}</td>
                      <td className="p-3">
                        <MoneyInput value={draft.price}
                          onChange={(value) => updateMenuDraft(item.id, { price: value })}/>
                      </td>
                      <td className="p-3">
                        <PercentInput value={draft.gst}
                          onChange={(value) => updateMenuDraft(item.id, { gst: value })}/>
                      </td>
                      <td className="p-3 pr-4 text-right">
                        <SaveButton busy={saving === key} disabled={!!saving} onClick={() => saveMenuItem(item)}/>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </PriceSection>

      <PriceSection
        icon={<Gamepad2 size={16}/>}
        title={APP_STORE_REVIEW ? 'Gaming Rates' : 'Gaming And Hookah Rates'}
        count={filteredStations.length}
      >
        {!filteredStations.length ? <EmptyLine/> : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm min-w-[620px]">
              <thead className="bg-bg-raised border-b border-bg-border">
                <tr>
                  <th className="text-left p-3">Station</th>
                  <th className="text-left p-3">Code</th>
                  <th className="text-left p-3">Type</th>
                  <th className="text-right p-3">Current</th>
                  <th className="text-right p-3">Hourly INR</th>
                  <th className="text-right p-3 pr-4">Save</th>
                </tr>
              </thead>
              <tbody>
                {filteredStations.map((station, idx) => {
                  const draft = drafts.stations[station.id] ?? { rate: '' };
                  const key = `station:${station.id}`;
                  return (
                    <tr key={station.id} className={idx % 2 ? 'bg-bg-surface/50' : ''}>
                      <td className="p-3 font-medium">{station.name}</td>
                      <td className="p-3 font-mono text-xs text-fg-muted">{station.code}</td>
                      <td className="p-3 text-xs text-fg-muted">{station.type}</td>
                      <td className="p-3 text-right font-mono">{inr(station.rate_per_hour_minor)}/hr</td>
                      <td className="p-3">
                        <MoneyInput value={draft.rate}
                          onChange={(value) => updateStationDraft(station.id, { rate: value })}/>
                      </td>
                      <td className="p-3 pr-4 text-right">
                        <SaveButton busy={saving === key} disabled={!!saving} onClick={() => saveStation(station)}/>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </PriceSection>

      <PriceSection icon={<Ticket size={16}/>} title="Event Ticket Prices" count={filteredEvents.length}>
        {!filteredEvents.length ? <EmptyLine/> : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm min-w-[720px]">
              <thead className="bg-bg-raised border-b border-bg-border">
                <tr>
                  <th className="text-left p-3">Event</th>
                  <th className="text-left p-3">When</th>
                  <th className="text-left p-3">Status</th>
                  <th className="text-right p-3">Current</th>
                  <th className="text-right p-3">Ticket INR</th>
                  <th className="text-right p-3 pr-4">Save</th>
                </tr>
              </thead>
              <tbody>
                {filteredEvents.map((event, idx) => {
                  const draft = drafts.events[event.id] ?? { price: '' };
                  const key = `event:${event.id}`;
                  return (
                    <tr key={event.id} className={idx % 2 ? 'bg-bg-surface/50' : ''}>
                      <td className="p-3 font-medium">{event.name}</td>
                      <td className="p-3 text-xs text-fg-muted">{formatDate(event.starts_at)}</td>
                      <td className="p-3 text-xs text-fg-muted">{event.status}</td>
                      <td className="p-3 text-right font-mono">{inr(event.base_ticket_price_minor)}</td>
                      <td className="p-3">
                        <MoneyInput value={draft.price}
                          onChange={(value) => updateEventDraft(event.id, { price: value })}/>
                      </td>
                      <td className="p-3 pr-4 text-right">
                        <SaveButton busy={saving === key} disabled={!!saving} onClick={() => saveEvent(event)}/>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </PriceSection>

      <PriceSection icon={<Crown size={16}/>} title="Membership Prices" count={filteredTiers.length}>
        {!filteredTiers.length ? <EmptyLine/> : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm min-w-[700px]">
              <thead className="bg-bg-raised border-b border-bg-border">
                <tr>
                  <th className="text-left p-3">Tier</th>
                  <th className="text-left p-3">Code</th>
                  <th className="text-right p-3">Current</th>
                  <th className="text-right p-3">Monthly INR</th>
                  <th className="text-right p-3">Annual INR</th>
                  <th className="text-right p-3 pr-4">Save</th>
                </tr>
              </thead>
              <tbody>
                {filteredTiers.map((tier, idx) => {
                  const draft = drafts.tiers[tier.id] ?? { monthly: '', annual: '' };
                  const key = `tier:${tier.id}`;
                  return (
                    <tr key={tier.id} className={idx % 2 ? 'bg-bg-surface/50' : ''}>
                      <td className="p-3 font-medium">{tier.name}</td>
                      <td className="p-3 font-mono text-xs text-fg-muted">{tier.code}</td>
                      <td className="p-3 text-right font-mono">
                        {inr(tier.monthly_price_minor)}/mo
                        {tier.annual_price_minor ? `, ${inr(tier.annual_price_minor)}/yr` : ''}
                      </td>
                      <td className="p-3">
                        <MoneyInput value={draft.monthly}
                          onChange={(value) => updateTierDraft(tier.id, { monthly: value })}/>
                      </td>
                      <td className="p-3">
                        <MoneyInput value={draft.annual}
                          onChange={(value) => updateTierDraft(tier.id, { annual: value })}
                          placeholder="optional"
                          required={false}/>
                      </td>
                      <td className="p-3 pr-4 text-right">
                        <SaveButton busy={saving === key} disabled={!!saving} onClick={() => saveTier(tier)}/>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </PriceSection>
    </div>
  );
}

function PriceSection({
  icon, title, count, children,
}: { icon: React.ReactNode; title: string; count: number; children: React.ReactNode }) {
  return (
    <section className="card !p-0 overflow-hidden">
      <div className="px-4 py-3 border-b border-bg-border flex items-center justify-between gap-3">
        <div className="flex items-center gap-2 font-semibold">
          <span className="text-accent">{icon}</span>
          {title}
        </div>
        <span className="chip text-xs">{count}</span>
      </div>
      {children}
    </section>
  );
}

function MoneyInput({
  value, onChange, placeholder = '0.00', required = true,
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  required?: boolean;
}) {
  return (
    <input
      type="number"
      min={0}
      step="0.01"
      required={required}
      className="input font-mono text-right !min-h-[36px] !py-1.5"
      value={value}
      placeholder={placeholder}
      onChange={(e) => onChange(e.target.value)}
    />
  );
}

function PercentInput({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  return (
    <input
      type="number"
      min={0}
      max={100}
      step="0.01"
      required
      className="input font-mono text-right !min-h-[36px] !py-1.5"
      value={value}
      onChange={(e) => onChange(e.target.value)}
    />
  );
}

function SaveButton({
  busy, disabled, onClick,
}: { busy: boolean; disabled: boolean; onClick: () => void }) {
  return (
    <button type="button" className="btn btn-primary !min-h-[34px] !py-1.5 !px-3"
      disabled={disabled} onClick={onClick}>
      {busy ? <Loader2 className="animate-spin" size={13}/> : <Save size={13}/>}
      Save
    </button>
  );
}

function Status({
  tone, icon, children,
}: { tone: 'good' | 'bad'; icon: React.ReactNode; children: React.ReactNode }) {
  const cls = tone === 'good'
    ? 'border-accent-good/40 bg-accent-good/10 text-accent-good'
    : 'border-accent-bad/40 bg-accent-bad/10 text-accent-bad';
  return <div className={`card text-sm flex items-center gap-2 ${cls}`}>{icon}{children}</div>;
}

function EmptyLine() {
  return <div className="p-4 text-sm text-fg-muted">No rows match the current search.</div>;
}

function buildDrafts(
  items: MenuItemDTO[],
  stations: StationDTO[],
  eventRows: EventDTO[],
  tiers: MembershipTierDTO[],
): Drafts {
  return {
    menu: Object.fromEntries(items.map((item) => [
      item.id,
      { price: toRupees(item.base_price_minor), gst: toPercent(item.tax_rate) },
    ])),
    stations: Object.fromEntries(stations.map((station) => [
      station.id,
      { rate: toRupees(station.rate_per_hour_minor) },
    ])),
    events: Object.fromEntries(eventRows.map((event) => [
      event.id,
      { price: toRupees(event.base_ticket_price_minor) },
    ])),
    tiers: Object.fromEntries(tiers.map((tier) => [
      tier.id,
      {
        monthly: toRupees(tier.monthly_price_minor),
        annual: tier.annual_price_minor ? toRupees(tier.annual_price_minor) : '',
      },
    ])),
  };
}

function matches(q: string, ...values: Array<string | null | undefined>) {
  if (!q) return true;
  return values.some((value) => (value ?? '').toLowerCase().includes(q));
}

function hasValidPricingToken() {
  const token = localStorage.getItem('pricing_token');
  const expiresAt = Number(localStorage.getItem('pricing_token_expires_at') || '0');
  return Boolean(token && expiresAt > Date.now());
}

function isPricingUnlockError(message: string) {
  return message.toLowerCase().includes('pricing password unlock');
}

function toRupees(minor: number) {
  return (minor / 100).toFixed(2);
}

function toPercent(rate: number) {
  const pct = rate * 100;
  return Number.isInteger(pct) ? String(pct) : pct.toFixed(2);
}

function toMinor(raw: string, label: string) {
  const value = Number.parseFloat(raw);
  if (!Number.isFinite(value) || value < 0) throw new Error(`${label} must be a positive number`);
  return Math.round(value * 100);
}

function toRate(raw: string, label: string) {
  const value = Number.parseFloat(raw);
  if (!Number.isFinite(value) || value < 0 || value > 100) {
    throw new Error(`${label} must be between 0 and 100`);
  }
  return value / 100;
}

function formatDate(iso: string) {
  return new Intl.DateTimeFormat('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(iso));
}
