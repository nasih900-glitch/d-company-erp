// @vitest-environment happy-dom
import { act, StrictMode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import AuditScreen, { AuditDetail } from './AuditScreen';
import { auditActor, safeAuditValue } from './audit-presentation';
import type { BusinessAuditEntryDTO, AuditEventsPageDTO } from '@/lib/erp-api';
import {
  clearOwnerWorkspaces,
  ownerScope,
  readOwnerWorkspace,
  rememberOwnerWorkspace,
} from '@/lib/owner-workspace';
const mocks = vi.hoisted(() => ({
  events: vi.fn(),
  unlock: vi.fn(),
  catalogue: vi.fn(),
  listUsers: vi.fn(),
  realtimeRefresh:undefined as undefined|(()=>Promise<void>),
}));
const me = {
  user_id: 'owner',
  company_id: 'company',
  branch_id: 'branch',
  audit_access: true,
};
vi.mock('@/modules/auth/AuthContext', () => ({ useAuth: () => ({ me }) }));
vi.mock('@/lib/erp-api', () => ({
  audit: mocks,
  staff: { listUsers: mocks.listUsers },
}));
vi.mock('@/hooks/useRealtimeRefresh', () => ({
  useRealtimeRefresh: (options:{refresh:()=>Promise<void>}) => {mocks.realtimeRefresh=options.refresh;},
}));
const row = (id: number): BusinessAuditEntryDTO =>
  ({
    id,
    actor_email: null,
    ip: null,
    user_agent: null,
    terminal_id: null,
    request_id: null,
    client_platform: null,
    client_version_code: null,
    client_action_id: null,
    client_reported_at: null,
    client_was_offline: null,
    synced_at: null,
    reason: null,
    catalogue_version: 1,
    target_user_id: null,
    match_explanation: null,
    created_at: '2026-10-18T12:00:00Z',
    entity_type: 'PaymentTenderCorrection',
    entity_id: 'correction-1',
    action: 'create',
    actor_user_id: 'owner',
    actor_name: 'Current owner',
    actor_kind: 'authenticated',
    actor_label_source: 'current_account_profile',
    action_code: 'POS-PCM',
    action_label: 'Payment method corrected',
    category: 'finance',
    stage: 'committed',
    outcome: 'completed',
    classification_source: 'recorded',
    summary: 'Cash corrected to UPI',
    source_reference: 'SYN-0001',
    business_details: {
      amount_minor: 27000,
      order_id: '00000000-0000-0000-0000-000000000001',
    },
    before: { method: 'cash' },
    after: { method: 'upi' },
    operation_id: 'stable-command',
  }) as BusinessAuditEntryDTO;
const page = (
  entries: BusinessAuditEntryDTO[],
  patch: Partial<AuditEventsPageDTO> = {},
): AuditEventsPageDTO =>
  ({
    entries,
    has_more: true,
    next_cursor: 'older-cursor',
    activity_cursor: 'activity-cursor',
    matching_count: 3,
    loaded_count: entries.length,
    snapshot_max_id: 3,
    shop_timezone: 'Asia/Kolkata',
    date_filter_basis: 'server_recorded_shop_date',
    new_entries_available: false,
    snapshot_expires_in: 600,
    ...patch,
  }) as AuditEventsPageDTO;
let host: HTMLDivElement, root: Root;
const env = globalThis as typeof globalThis & {
  IS_REACT_ACT_ENVIRONMENT?: boolean;
};
beforeEach(() => {
  env.IS_REACT_ACT_ENVIRONMENT = true;
  clearOwnerWorkspaces();
  vi.clearAllMocks();
  mocks.unlock.mockResolvedValue({
    audit_token: 'memory-token',
    expires_in: 600,
  });
  mocks.catalogue.mockResolvedValue({
    actions: [],
    categories: ['finance'],
    outcomes: ['completed'],
  });
  mocks.listUsers.mockResolvedValue([]);
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  document.body.replaceChildren();
  vi.useRealTimers();
  delete env.IS_REACT_ACT_ENVIRONMENT;
});
async function render() {
  await act(async () =>
    root.render(
      <StrictMode>
        <MemoryRouter>
          <AuditScreen />
        </MemoryRouter>
      </StrictMode>,
    ),
  );
}
async function click(text: string) {
  const button = [...host.querySelectorAll('button')].find((node) =>
    node.textContent?.includes(text),
  );
  if (!button) throw new Error('Missing button ' + text);
  await act(async () => button.click());
}
async function fill(selector: string, value: string) {
  const input = host.querySelector<HTMLInputElement>(selector)!;
  await act(async () => {
    Object.getOwnPropertyDescriptor(
      HTMLInputElement.prototype,
      'value',
    )!.set!.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}
async function unlock() {
  await fill('input[type="password"]', 'synthetic-password');
  await act(async () =>
    host
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })),
  );
}
describe('Retained Audit workspace', () => {
  it('checks activity using the same snapshot without replacing older loaded rows or paging', async () => {
    mocks.events
      .mockResolvedValueOnce(page([row(3), row(2)]))
      .mockResolvedValueOnce(
        page([row(1)], { has_more: false, next_cursor: null }),
      )
      .mockResolvedValueOnce(
        page([], {
          new_entries_available: true,
          has_more: false,
          next_cursor: null,
        }),
      );
    await render();
    await unlock();
    await click('Load older activity');
    await click('Check new activity');
    expect(mocks.events.mock.calls[2][0].cursor).toBe('activity-cursor');
    expect(host.textContent).toContain('3 loaded');
    expect(host.textContent).toContain('New matching activity');
    expect(
      readOwnerWorkspace<{ rows: unknown[] }>(ownerScope(me), 'audit')?.rows,
    ).toHaveLength(3);
  });
  it('keeps verified filters and loaded evidence on a failed new search', async () => {
    mocks.events.mockResolvedValueOnce(page([row(3)]));
    await render();
    await unlock();
    await fill('input[list="audit-actions"]', 'different');
    mocks.events.mockRejectedValueOnce(new Error('Network unavailable'));
    const search = host.querySelectorAll('form')[0];
    await act(async () =>
      search.dispatchEvent(
        new Event('submit', { bubbles: true, cancelable: true }),
      ),
    );
    expect(host.textContent).toContain('Your loaded events have been kept');
    const state = readOwnerWorkspace<{
      filters: { q?: string };
      rows: unknown[];
    }>(ownerScope(me), 'audit');
    expect(state?.filters.q).toBeUndefined();
    expect(state?.rows).toHaveLength(1);
  });
  it('restores selected inline detail and loaded pages on source navigation but clears secrets on logout', async () => {
    mocks.events.mockResolvedValueOnce(page([row(3)]));
    await render();
    await unlock();
    await click('Payment method corrected');
    expect(host.querySelector('aside')).not.toBeNull();
    await act(async () => root.render(null));
    await render();
    expect(host.querySelector('aside')).not.toBeNull();
    expect(mocks.events).toHaveBeenCalledTimes(1);
    clearOwnerWorkspaces();
    await act(async () => root.render(null));
    expect(readOwnerWorkspace(ownerScope(me), 'audit')).toBeUndefined();
    await render();
    expect(host.textContent).toContain('Confirm owner access');
  });
  it('shows actor provenance honestly and recursively redacts secrets', () => {
    expect(
      auditActor({ ...row(1), actor_kind: 'otp_approved_requester' }),
    ).toContain('OTP-approved requester');
    expect(
      auditActor({ ...row(1), actor_kind: 'requester_recorded' }),
    ).toContain('Recorded requester');
    expect(
      auditActor({ ...row(1), actor_user_id: null, actor_kind: 'unverified' }),
    ).toBe('Requester not verified');
    expect(
      safeAuditValue({
        items: [{ access_token: 'secret', amount_minor: 100 }],
      }),
    ).toEqual({ items: [{ access_token: '[redacted]', amount_minor: 100 }] });
  });
  it('puts opaque business IDs behind Technical details and separates actor from target account', async () => {
    await act(async () =>
      root.render(
        <MemoryRouter>
          <AuditDetail
            entry={{ ...row(1), target_user_id: 'target-account' }}
            zone="Asia/Kolkata"
            onClose={() => undefined}
          />
        </MemoryRouter>,
      ),
    );
    expect(host.textContent).toContain('Target account (not the actor)');
    expect(host.textContent).toContain('₹270.00');
    const technical = [...host.querySelectorAll('details')].find((node) =>
      node.textContent?.includes('Technical details'),
    );
    expect(technical?.textContent).toContain(
      '00000000-0000-0000-0000-000000000001',
    );
  });
  it('permits a fresh unlock after Lock invalidates an unfinished older-page request', async () => {
    let resolve!: (value: AuditEventsPageDTO) => void;
    mocks.events.mockResolvedValueOnce(page([row(3)]));
    await render();
    await unlock();
    mocks.events.mockImplementationOnce(
      () =>
        new Promise((next) => {
          resolve = next;
        }),
    );
    act(() => {
      [...host.querySelectorAll('button')]
        .find((node) => node.textContent?.includes('Load older activity'))!
        .click();
    });
    await click('Lock');
    await act(async () => resolve(page([row(2)])));
    await fill('input[type="password"]', 'synthetic-password');
    const confirm = [...host.querySelectorAll('button')].find((node) =>
      node.textContent?.includes('Unlock activity'),
    )!;
    expect(confirm.disabled).toBe(false);
    await unlock();
    expect(host.textContent).toContain('1 loaded');
  });
  it('permits a fresh unlock when expiry invalidates a deferred request', async () => {
    vi.useFakeTimers();
    mocks.unlock.mockResolvedValue({
      audit_token: 'short-token',
      expires_in: 1,
    });
    let resolve!: (value: AuditEventsPageDTO) => void;
    mocks.events.mockResolvedValueOnce(page([row(3)]));
    await render();
    await unlock();
    mocks.events.mockImplementationOnce(
      () =>
        new Promise((next) => {
          resolve = next;
        }),
    );
    act(() => {
      [...host.querySelectorAll('button')]
        .find((node) => node.textContent?.includes('Load older activity'))!
        .click();
    });
    await act(async () => vi.advanceTimersByTime(1001));
    await act(async () => resolve(page([row(2)])));
    await fill('input[type="password"]', 'synthetic-password');
    expect(
      [...host.querySelectorAll('button')].find((node) =>
        node.textContent?.includes('Unlock activity'),
      )!.disabled,
    ).toBe(false);
    await unlock();
    expect(host.textContent).toContain('1 loaded');
  });
  it('does not let realtime invalidate a restored-page unlock while catalogue is loading',async()=>{
    rememberOwnerWorkspace(ownerScope(me),'audit',{token:null,expiresAt:0,rows:[row(3)],page:page([row(3)]),filters:{view:'business'},selected:null,scroll:0,catalogue:null,users:[]});
    let resolve!:(value:{actions:never[];categories:string[];outcomes:string[]})=>void;
    mocks.catalogue.mockImplementationOnce(()=>new Promise(next=>{resolve=next;}));
    await render();await fill('input[type="password"]','synthetic-password');
    act(()=>host.querySelector('form')!.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
    await act(async()=>Promise.resolve());await act(async()=>mocks.realtimeRefresh?.());
    expect(mocks.events).not.toHaveBeenCalled();
    await act(async()=>resolve({actions:[],categories:['finance'],outcomes:['completed']}));
    expect([...host.querySelectorAll('option')].some(option=>option.textContent==='Finance')).toBe(true);
    expect(host.textContent).toContain('1 loaded');
  });

  it('presents login snapshots as retained facts and a real role pair as one transition', async () => {
    const observed={...row(10),action:'login_success',action_label:'Signed in',summary:'Sign-in observed',before:null,after:{name:'Retained staff',roles:['staff']}};
    await act(async()=>root.render(<MemoryRouter><AuditDetail entry={observed} zone="Asia/Kolkata" onClose={()=>{}} /></MemoryRouter>));
    expect(host.textContent).not.toContain('Recorded Before → After');
    expect(host.textContent).toContain('Before state was not retained');
    expect(host.textContent).toContain('Retained after action · Name');
    expect(host.textContent).toContain('Retained staff');
    const changed={...observed,action:'staff_access_changed',before:{before_roles:[]},after:{after_roles:['owner']}};
    await act(async()=>root.render(<MemoryRouter><AuditDetail entry={changed} zone="Asia/Kolkata" onClose={()=>{}} /></MemoryRouter>));
    expect(host.textContent).toContain('No roles → Owner');
    expect(host.textContent).not.toContain('Roles before');
    expect(host.textContent).not.toContain('Roles after');
    expect(host.querySelectorAll('summary')[0].textContent).toBe('Recorded Before → After');
  });
  it('focuses selected inline evidence and returns to the exact loaded list position', async () => {
    const previous=Object.getOwnPropertyDescriptor(HTMLElement.prototype,'scrollIntoView');
    const scroll=vi.fn();Object.defineProperty(HTMLElement.prototype,'scrollIntoView',{configurable:true,value:scroll});
    try {
      mocks.events.mockResolvedValueOnce(page([row(3),row(2)]));
      await act(async()=>root.render(<MemoryRouter><main><AuditScreen /></main></MemoryRouter>));await unlock();
      const main=host.querySelector('main')!, list=host.querySelector('table')!.parentElement!;
      main.scrollTop=30;list.scrollTop=84;
      const source=host.querySelector<HTMLButtonElement>('tbody button')!;
      await act(async()=>source.click());
      expect(document.activeElement?.getAttribute('aria-label')).toBe('Selected activity evidence');
      expect(scroll).toHaveBeenCalledWith({block:'nearest'});
      expect(source.getAttribute('aria-expanded')).toBe('true');
      main.scrollTop=1005;
      await click('Return to loaded activity');
      expect(main.scrollTop).toBe(30);expect(list.scrollTop).toBe(84);expect(document.activeElement).toBe(source);
      expect(host.querySelectorAll('tbody tr')).toHaveLength(2);expect(mocks.events).toHaveBeenCalledTimes(1);
    } finally {
      if(previous) Object.defineProperty(HTMLElement.prototype,'scrollIntoView',previous);
      else Reflect.deleteProperty(HTMLElement.prototype,'scrollIntoView');
    }
  });

});
