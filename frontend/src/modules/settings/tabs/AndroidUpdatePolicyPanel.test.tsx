// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, expect, it, vi } from 'vitest';
import { androidUpdatePolicy, type AndroidUpdatePolicyDTO } from '@/lib/erp-api';
import AndroidUpdatePolicyPanel, { policyWriteBody } from './AndroidUpdatePolicyPanel';

const authority = vi.hoisted(() => ({ user_id: 'owner', company_id: 'company', branch_id: 'branch', release_control_access: true }));
vi.mock('@/modules/auth/AuthContext', () => ({ useAuth: () => ({ me: authority }) }));
(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
const policy: AndroidUpdatePolicyDTO = {mode:'inherit',revision:4,environment_minimum_version_code:54,minimum_supported_version_code:54,required_release_id:null,required_release_fingerprint:null,reason:null,updated_at:null,active_release:null};
let root: ReturnType<typeof createRoot> | undefined;
afterEach(() => { act(() => root?.unmount()); root=undefined; document.body.innerHTML=''; authority.user_id='owner'; authority.release_control_access=true; vi.restoreAllMocks(); });
async function settle() { await act(async () => { for(let i=0;i<12;i++) await Promise.resolve(); }); }
function render() {const host=document.createElement('div'); document.body.append(host);root=createRoot(host);act(() => root!.render(<AndroidUpdatePolicyPanel/>));}
function button(label:string) { const node=[...document.querySelectorAll('button')].find(item=>item.textContent===label);expect(node).toBeTruthy(); return node!; }

it('requires an exact active server fingerprint and reason; Optional never accepts arbitrary metadata', () => {
  expect(policyWriteBody(policy,'required','Reviewed')).toBeNull();
  expect(policyWriteBody(policy,'optional','  Reviewed  ')).toEqual({mode:'optional',expected_revision:4,reason:'Reviewed'});
  expect(policyWriteBody(policy,'optional','  ')).toBeNull();
});
it('explains global effect and safety floor and requires explicit review before PUT', async () => {
  vi.spyOn(androidUpdatePolicy,'get').mockResolvedValue(policy);
  const put=vi.spyOn(androidUpdatePolicy,'set').mockResolvedValue({...policy,mode:'optional',revision:5});
  render();await settle();
  expect(document.body.textContent).toContain('including your own app');
  expect(document.body.textContent).toContain('safety floor at build 54');
  expect(button('Apply update policy').disabled).toBe(true);
  const reason=document.querySelector('textarea')!;
  act(()=> { const setter=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')!.set!;setter.call(reason,'Allow supported builds');reason.dispatchEvent(new Event('input',{bubbles:true})); });
  await settle();
  act(()=>document.querySelector<HTMLInputElement>('input[type=checkbox]')!.click());await settle();
  act(()=>button('Apply update policy').click());await settle();
  expect(put).toHaveBeenCalledExactlyOnceWith({mode:'optional',expected_revision:4,reason:'Allow supported builds'});
  expect(document.body.textContent).toContain('revision 5');
});
it('discards a delayed read after account switch', async () => {
  let finish!: (value:AndroidUpdatePolicyDTO)=>void;
  vi.spyOn(androidUpdatePolicy,'get').mockReturnValue(new Promise(resolve=>{finish=resolve;}));
  render();await settle();
  authority.user_id='other';authority.release_control_access=false;
  act(()=>root!.render(<AndroidUpdatePolicyPanel/>));
  finish(policy);await settle();
  expect(document.querySelector('[aria-label="Android update policy"]')).toBeNull();
  expect(document.body.textContent).not.toContain('revision 4');
});
it('retains the reviewed state on CAS conflict and never automatically retries the write', async () => {
  vi.spyOn(androidUpdatePolicy,'get').mockResolvedValue(policy);
  vi.spyOn(androidUpdatePolicy,'set').mockRejectedValue(new Error('Update policy changed'));
  render();await settle();
  const reason=document.querySelector('textarea')!;
  act(()=>{Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')!.set!.call(reason,'Reviewed policy');reason.dispatchEvent(new Event('input',{bubbles:true}));});await settle();
  act(()=>document.querySelector<HTMLInputElement>('input[type=checkbox]')!.click());await settle();
  act(()=>button('Apply update policy').click());await settle();
  expect(document.body.textContent).toContain('Refresh policy before retrying');
  expect(androidUpdatePolicy.set).toHaveBeenCalledTimes(1);
});
