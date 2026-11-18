// @vitest-environment happy-dom
import { act, StrictMode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import CompanyTab from './CompanyTab';
const m=vi.hoisted(()=>({ get:vi.fn(),update:vi.fn() }));
vi.mock('@/lib/erp-api',()=>({settings:{getCompany:m.get,updateCompany:m.update}}));
const profile={id:'company',name:'Synthetic shop',legal_name:'Synthetic legal name',currency:'INR',timezone:'Asia/Kolkata',gstin:null,pan:null,gst_registration_type:'unregistered',is_composition:false,e_invoicing_enabled:false,upi_vpa:null};
let root:Root,host:HTMLDivElement;
beforeEach(()=>{vi.clearAllMocks();(globalThis as unknown as {IS_REACT_ACT_ENVIRONMENT:boolean}).IS_REACT_ACT_ENVIRONMENT=true;m.get.mockResolvedValue(profile);m.update.mockResolvedValue(profile);host=document.createElement('div');document.body.append(host);root=createRoot(host);});
afterEach(()=>{act(()=>root.unmount());document.body.replaceChildren();vi.restoreAllMocks();});
async function render(){await act(async()=>root.render(<CompanyTab/>));}
function button(text:string){const b=[...host.querySelectorAll<HTMLButtonElement>('button')].find(n=>n.textContent?.includes(text));if(!b)throw Error('Missing '+text);return b;}
async function click(text:string){await act(async()=>button(text).click());}
it('a live first-read failure is honest, retryable and cannot fabricate an editable company',async()=>{
  m.get.mockRejectedValueOnce(new Error('Connection unavailable'));await render();
  expect(host.querySelector('[role=alert]')?.textContent).toContain('Connection unavailable');expect(host.textContent).not.toContain('demo');expect(host.querySelector('input')).toBeNull();expect(host.textContent).not.toContain('Save changes');
  m.get.mockRejectedValueOnce(new Error('Still unavailable'));await click('Retry company settings');expect(host.textContent).toContain('Still unavailable');expect(m.update).not.toHaveBeenCalled();
  await click('Retry company settings');expect(host.querySelector<HTMLInputElement>('input')?.value).toBe(profile.name);expect(host.textContent).toContain('Save changes');
});
it('the newer read owns the profile when StrictMode replays initial loading',async()=>{
  let resolve!:(v:typeof profile)=>void;m.get.mockReturnValueOnce(new Promise(r=>{resolve=r;}));
  await act(async()=>root.render(<StrictMode><CompanyTab/></StrictMode>));expect(m.get).toHaveBeenCalledTimes(2);
  await act(async()=>resolve({...profile,name:'Obsolete company'}));expect(host.querySelector<HTMLInputElement>('input')?.value).toBe(profile.name);
});
it('a late response after unmount does not revive an editable profile',async()=>{
  let resolve!:(v:typeof profile)=>void;m.get.mockReturnValueOnce(new Promise(r=>{resolve=r;}));await render();await act(async()=>root.render(null));await act(async()=>resolve(profile));expect(host.textContent).toBe('');expect(m.update).not.toHaveBeenCalled();
});
it('normal saving retains the existing exact company update contract',async()=>{
  await render();await click('Save changes');expect(m.update).toHaveBeenCalledTimes(1);expect(m.update).toHaveBeenCalledWith({name:profile.name,legal_name:profile.legal_name,timezone:profile.timezone,gstin:null,pan:null,gst_registration_type:'unregistered',is_composition:false,e_invoicing_enabled:false,upi_vpa:null});expect(host.textContent).toContain('Saved.');
});
