import { useEffect, useRef, useState } from 'react';

import { customers, type CustomerDTO } from '@/lib/erp-api';

export type GamingCustomerStartIdentity = {
  customer_id?: string;
  customer_name?: string;
  customer_phone?: string;
};

export function buildGamingCustomerStartIdentity(
  selected: CustomerDTO | undefined,
  name: string,
  phone: string,
): GamingCustomerStartIdentity {
  if (selected) return { customer_id: selected.id };
  return {
    customer_name: name.trim() || undefined,
    customer_phone: phone.trim() || undefined,
  };
}

export function customerMatchesSearch(customer: CustomerDTO, rawQuery: string): boolean {
  const query = rawQuery.trim().toLocaleLowerCase();
  return query.length > 0 && (
    customer.phone.toLocaleLowerCase().includes(query)
    || (customer.name ?? '').toLocaleLowerCase().includes(query)
  );
}

export function isLatestCustomerSearch(completedGeneration: number, currentGeneration: number): boolean {
  return completedGeneration === currentGeneration;
}

export function clearStartedStationCustomer(
  current: Record<string, CustomerDTO | undefined>,
  stationId: string,
): Record<string, CustomerDTO | undefined> {
  const next = { ...current };
  delete next[stationId];
  return next;
}

type Props = {
  stationId: string;
  disabled: boolean;
  selected?: CustomerDTO;
  name: string;
  phone: string;
  onSelect: (customer: CustomerDTO | undefined) => void;
  onNameChange: (name: string) => void;
  onPhoneChange: (phone: string) => void;
  saveContext?: 'start' | 'join';
};

export function GamingCustomerPicker({
  stationId,
  disabled,
  selected,
  name,
  phone,
  onSelect,
  onNameChange,
  onPhoneChange,
  saveContext = 'start',
}: Props) {
  const [mode, setMode] = useState<'search' | 'add'>('search');
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<CustomerDTO[]>([]);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);
  const generation = useRef(0);

  useEffect(() => {
    const current = ++generation.current;
    const normalized = query.trim();
    if (selected || mode !== 'search' || normalized.length < 2) {
      setResults([]);
      setSearching(false);
      setSearchError(null);
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setSearching(true);
      setSearchError(null);
      void customers.list(normalized, controller.signal).then((rows) => {
        if (!isLatestCustomerSearch(current, generation.current) || controller.signal.aborted) return;
        setResults(rows.filter((row) => customerMatchesSearch(row, normalized)));
      }).catch(() => {
        if (!isLatestCustomerSearch(current, generation.current) || controller.signal.aborted) return;
        setResults([]);
        setSearchError('Saved customers could not be searched. You can still add details for this session.');
      }).finally(() => {
        if (generation.current === current && !controller.signal.aborted) setSearching(false);
      });
    }, 250);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [mode, query, selected]);

  if (selected) {
    return (
      <div className="mb-2 rounded-lg border border-accent/35 bg-accent/5 p-2 text-xs" data-testid={`gaming-customer-${stationId}`}>
        <div className="font-semibold text-fg">{selected.name?.trim() || 'Saved customer'}</div>
        <div className="text-fg-muted">{selected.phone}</div>
        <div className="mt-2 flex gap-2">
          <button type="button" className="btn btn-ghost !py-1 text-xs" disabled={disabled}
            onClick={() => { onSelect(undefined); onNameChange(''); onPhoneChange(''); setMode('search'); setQuery(''); }}>
            Change
          </button>
          <button type="button" className="btn btn-ghost !py-1 text-xs" disabled={disabled}
            onClick={() => { onSelect(undefined); onNameChange(''); onPhoneChange(''); setMode('search'); setQuery(''); }}>
            Clear
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="mb-2 space-y-2" data-testid={`gaming-customer-${stationId}`}>
      <div className="flex gap-2" role="tablist" aria-label="Customer entry mode">
        <button type="button" role="tab" aria-selected={mode === 'search'} className={`chip text-xs ${mode === 'search' ? '!border-accent !text-accent' : ''}`}
          disabled={disabled} onClick={() => {
            setMode('search'); setQuery(''); setResults([]); onNameChange(''); onPhoneChange('');
          }}>Saved customer</button>
        <button type="button" role="tab" aria-selected={mode === 'add'} className={`chip text-xs ${mode === 'add' ? '!border-accent !text-accent' : ''}`}
          disabled={disabled} onClick={() => { setMode('add'); setQuery(''); setResults([]); }}>Add new</button>
      </div>
      {mode === 'search' ? (
        <>
          <input type="search" className="input !py-1.5 text-xs w-full"
            placeholder="Search saved customers by name or phone"
            aria-label="Search saved customers by name or phone"
            disabled={disabled} value={query} onChange={(event) => {
              setResults([]);
              setQuery(event.target.value);
            }}/>
          {searching && <div className="text-[11px] text-fg-muted">Searching…</div>}
          {searchError && <div className="text-[11px] text-accent-bad">{searchError}</div>}
          {!searching && !searchError && query.trim().length >= 2 && results.length === 0 && (
            <div className="text-[11px] text-fg-muted">No saved customer found. Choose Add new to save one {saveContext === 'join' ? 'when the friend joins.' : 'when the session starts.'}</div>
          )}
          {results.length > 0 && (
            <div className="max-h-36 overflow-y-auto rounded-lg border border-bg-border" role="listbox" aria-label="Saved customer results">
              {results.map((customer) => (
                <button key={customer.id} type="button" role="option" aria-selected="false"
                  className="flex w-full items-center justify-between gap-3 border-b border-bg-border px-3 py-2 text-left text-xs last:border-0 hover:bg-bg-raised"
                  disabled={disabled} onClick={() => { onSelect(customer); onNameChange(customer.name ?? ''); onPhoneChange(customer.phone); setResults([]); }}>
                  <span className="font-medium text-fg">{customer.name?.trim() || '— no name —'}</span>
                  <span className="font-mono text-fg-muted">{customer.phone}</span>
                </button>
              ))}
            </div>
          )}
        </>
      ) : (
        <>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            <input type="text" placeholder="Customer name (optional)" className="input !py-1.5 text-xs w-full"
              disabled={disabled} value={name} onChange={(event) => onNameChange(event.target.value)}/>
            <input type="tel" placeholder="Customer phone (optional)" className="input !py-1.5 text-xs w-full"
              disabled={disabled} value={phone} maxLength={20} onChange={(event) => onPhoneChange(event.target.value)}/>
          </div>
          <p className="text-[11px] text-fg-muted">{saveContext === 'join'
            ? 'Name and phone are saved as a customer before this friend joins.'
            : 'A valid new phone is saved through session start for next time. A name alone stays an untracked receipt note.'}</p>
        </>
      )}
    </div>
  );
}
