import { useCallback, useLayoutEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { ownerWorkspaceGeneration } from './owner-workspace';

/** Opt-in inline navigation only: no modal trap or mount/unmount autofocus. */
export function useInlineWorkspaceFocus(open: boolean, scope: string) {
  const location = useLocation();
  const context = JSON.stringify([
    scope, ownerWorkspaceGeneration(), location.key,
    location.pathname, location.search, location.hash,
  ]);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const fallbackRef = useRef<HTMLDivElement>(null);
  const intent = useRef<{ invoker: HTMLElement; context: string } | null>(null);
  const entering = useRef(false);
  const returning = useRef(false);
  const [activation, setActivation] = useState(0);

  const openFrom = useCallback((invoker: HTMLElement) => {
    intent.current = { invoker, context };
    entering.current = true;
    returning.current = false;
    setActivation(value => value + 1);
  }, [context]);
  const closeToInvoker = useCallback(() => {
    entering.current = false;
    returning.current = true;
  }, []);

  useLayoutEffect(() => {
    const saved = intent.current;
    if (!saved || saved.context !== context) {
      intent.current = null;
      entering.current = false;
      returning.current = false;
      return;
    }
    if (open && entering.current && headingRef.current) {
      entering.current = false;
      headingRef.current.focus();
    } else if (!open && returning.current) {
      returning.current = false;
      intent.current = null;
      if (saved.invoker.isConnected) saved.invoker.focus();
      if (document.activeElement !== saved.invoker) fallbackRef.current?.focus();
    }
  }, [open, context, activation]);

  return { headingRef, fallbackRef, openFrom, closeToInvoker };
}
