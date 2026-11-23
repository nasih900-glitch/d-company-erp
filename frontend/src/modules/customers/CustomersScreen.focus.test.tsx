// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import CustomersScreen from './CustomersScreen';
const m=vi.hoisted(()=>({me:{user_id:'owner',company_id:'company',branch_id:'branch',effective_permissions:['pos.write']},list:vi.fn(),upsert:vi.fn(),update:vi.fn()}));
vi.mock('@/modules/auth/AuthContext',()=>({useAuth:()=>({me:m.me,demo:false})}));
vi.mock('@/components/ui/Notifications',()=>({useNotifications:()=>({success:vi.fn(),error:vi.fn()})}));
vi.mock('./CustomerPlaytimePanel',()=>({default:()=>null}));
vi.mock('@/lib/erp-api',()=>({customers:{list:m.list,upsert:m.upsert,update:m.update}}));
let root:Root,host:HTMLDivElement,wide=true,nextFrame=0;
const listeners=new Set<()=>void>(),frames=new Map<number,FrameRequestCallback>();
const customer={id:'customer',name:'Existing customer',phone:'9876543210',loyalty_points:0,visit_count:2,total_spent_minor:10000,gaming_rank:'Rookie',lifetime_gaming_points_earned:0,gaming_rank_floor:0,next_gaming_rank:null,last_visit_at:null};
beforeEach(()=>{
  vi.clearAllMocks();(globalThis as unknown as {IS_REACT_ACT_ENVIRONMENT:boolean}).IS_REACT_ACT_ENVIRONMENT=true;wide=true;listeners.clear();frames.clear();m.me={user_id:'owner',company_id:'company',branch_id:'branch',effective_permissions:['pos.write']};m.list.mockResolvedValue([]);
  vi.stubGlobal('matchMedia',()=>({get matches(){return wide;},addEventListener:(_name:string,l:()=>void)=>listeners.add(l),removeEventListener:(_name:string,l:()=>void)=>listeners.delete(l)}));
  vi.spyOn(window,'requestAnimationFrame').mockImplementation(callback=>{const id=++nextFrame;frames.set(id,callback);return id;});vi.spyOn(window,'cancelAnimationFrame').mockImplementation(id=>{frames.delete(id);});
  host=document.createElement('div');document.body.append(host);root=createRoot(host);
});
afterEach(()=>{act(()=>root.unmount());frames.clear();document.body.replaceChildren();vi.restoreAllMocks();vi.unstubAllGlobals();});
function RouteControl(){const navigate=useNavigate();return <button onClick={()=>navigate('/another-route')}>Other route</button>;}
function content(){return <MemoryRouter><RouteControl/><CustomersScreen/></MemoryRouter>;}
async function flush(){await act(async()=>{const pending=[...frames.values()];frames.clear();pending.forEach(f=>f(performance.now()));});}
async function render(){await act(async()=>root.render(content()));await flush();}
function button(text:string){const b=[...document.querySelectorAll<HTMLButtonElement>('button')].find(n=>n.textContent?.trim()===text||n.getAttribute('aria-label')===text);if(!b)throw Error('Missing '+text);return b;}
async function click(text:string){const b=button(text);await act(async()=>{b.focus();b.click();});await flush();return b;}
function field(name:string){const n=document.querySelector<HTMLInputElement | HTMLTextAreaElement>(`[data-customer-field="${name}"]`);if(!n)throw Error('Missing '+name);return n;}
async function fill(name:string,value:string){const n=field(name);await act(async()=>{Object.getOwnPropertyDescriptor(Object.getPrototypeOf(n),'value')!.set!.call(n,value);n.dispatchEvent(new Event('input',{bubbles:true}));});}
async function resize(value:boolean){await act(async()=>{wide=value;listeners.forEach(l=>l());});await flush();}
it('initial Add focuses Phone and explicit inline Close returns its actual invoker',async()=>{
  await render();const opener=await click('Add customer');expect(document.activeElement).toBe(field('phone'));await click('Close');expect(document.activeElement).toBe(opener);expect(m.upsert).not.toHaveBeenCalled();
});
it('a real Name draft and backward selection survive both presentation swaps, and Escape returns original Add',async()=>{
  await render();const opener=await click('Add customer');await fill('name','Unsaved focused Name draft');const old=field('name');old.focus();old.setSelectionRange(2,4,'backward');
  await resize(false);expect(document.querySelector('[role=dialog]')).not.toBeNull();expect(field('name')).not.toBe(old);expect(document.activeElement).toBe(field('name'));expect(field('name').value).toBe('Unsaved focused Name draft');expect([field('name').selectionStart,field('name').selectionEnd,field('name').selectionDirection]).toEqual([2,4,'backward']);
  await resize(true);expect(document.querySelector('[role=dialog]')).toBeNull();expect(document.activeElement).toBe(field('name'));expect([field('name').selectionStart,field('name').selectionEnd,field('name').selectionDirection]).toEqual([2,4,'backward']);
  await resize(false);await act(async()=>document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true})));await flush();expect(document.activeElement).toBe(opener);expect(m.upsert).not.toHaveBeenCalled();
});
it('compact-only entry keeps initial Phone and the existing modal Tab/Shift-Tab trap',async()=>{
  wide=false;await render();const opener=await click('Add customer');expect(document.activeElement).toBe(field('phone'));
  const dialog=document.querySelector('[role=dialog]')!;const first=dialog.querySelector<HTMLButtonElement>('button')!;const last=button('Create');last.focus();
  await act(async()=>document.dispatchEvent(new KeyboardEvent('keydown',{key:'Tab',bubbles:true,cancelable:true})));expect(document.activeElement).toBe(first);
  await act(async()=>document.dispatchEvent(new KeyboardEvent('keydown',{key:'Tab',shiftKey:true,bubbles:true,cancelable:true})));expect(document.activeElement).toBe(last);
  await click('Cancel');expect(document.activeElement).toBe(opener);
});
it('unsupported selection types reflow without inventing a caret or losing the active field',async()=>{
  await render();await click('Add customer');await fill('email','draft@example.test');field('email').focus();expect(field('email').selectionStart).toBeNull();await resize(false);expect(document.activeElement).toBe(field('email'));expect(field('email').value).toBe('draft@example.test');
});
it('a disconnected Edit invoker uses the directory fallback instead of BODY',async()=>{
  m.list.mockResolvedValue([customer]);await render();await click('Edit Existing customer');m.list.mockResolvedValue([]);await click('Refresh customer directory');await click('Close');expect(document.activeElement).toBe(host.querySelector('[tabindex="-1"]'));expect(m.update).not.toHaveBeenCalled();
});
it.each(['owner','route','unmount'] as const)('%s transitions do not return focus to a previous editor invoker',async change=>{
  await render();await click('Add customer');const outside=button('Other route');
  if(change==='owner'){m.me={...m.me,user_id:'another-owner'};await act(async()=>root.render(content()));outside.focus();await act(async()=>button('Close').click());}
  else if(change==='route'){await click('Other route');await act(async()=>button('Close').click());}
  else{outside.focus();await act(async()=>root.render(null));}
  await flush();if(change!=='unmount')expect(document.activeElement).toBe(outside);else expect(document.activeElement).not.toBe(buttonIfPresent('Add customer'));
});
function buttonIfPresent(text:string){return [...document.querySelectorAll('button')].find(n=>n.textContent?.trim()===text);}
it('reflow with no focused editor field adds no stale field restore over normal Modal initial focus',async()=>{
  await render();await click('Add customer');field('name').focus();field('name').setSelectionRange(0,0);button('Refresh customer directory').focus();await resize(false);
  expect(document.activeElement).not.toBe(field('name'));expect(document.querySelector('[role=dialog]')?.contains(document.activeElement)).toBe(true);
});
it('explicit Edit Cancel returns the connected original row action',async()=>{
  m.list.mockResolvedValue([customer]);await render();const opener=await click('Edit Existing customer');await click('Cancel');expect(document.activeElement).toBe(opener);expect(m.update).not.toHaveBeenCalled();
});
it('a queued modal-exit field restore never overrides a newly focused outside control',async()=>{
  await render();await click('Add customer');field('name').focus();field('name').setSelectionRange(1,2);await resize(false);
  await act(async()=>{wide=true;listeners.forEach(l=>l());});const outside=button('Other route');outside.focus();await flush();expect(document.activeElement).toBe(outside);
});
it('a queued explicit-close return is cancelled by a replacement Owner before the frame',async()=>{
  wide=false;await render();await click('Add customer');await act(async()=>button('Cancel').click());m.me={...m.me,user_id:'replacement-owner'};await act(async()=>root.render(content()));const outside=button('Other route');outside.focus();await flush();expect(document.activeElement).toBe(outside);
});
