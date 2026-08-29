import type { ButtonHTMLAttributes, ReactNode } from 'react';

type GamingApi = typeof import('@/lib/erp-api').gaming;

export const GAMING_WRITE_OPERATION_NAMES = [
  'createStation',
  'updateStation',
  'deleteStation',
  'startSession',
  'setSessionTimer',
  'extendSessionTimer',
  'extendSessionWithPackage',
  'stopSession',
  'repairSessionBilling',
  'cancelSession',
  'sendToPos',
  'handoffToPos',
  'reconcileToPos',
  'addSessionAddon',
  'voidSessionAddon',
] as const;

type GamingWriteOperationName = (typeof GAMING_WRITE_OPERATION_NAMES)[number];
type GamingWriteOperations = Pick<GamingApi, GamingWriteOperationName>;
type OperationArgs<K extends GamingWriteOperationName> = Parameters<GamingWriteOperations[K]>;
type OperationResult<K extends GamingWriteOperationName> = ReturnType<GamingWriteOperations[K]>;

export type GamingWriteDispatcher =
  | {
      allowed: false;
      dispatch<K extends GamingWriteOperationName>(
        operation: K,
        ...args: OperationArgs<K>
      ): undefined;
    }
  | {
      allowed: true;
      dispatch<K extends GamingWriteOperationName>(
        operation: K,
        ...args: OperationArgs<K>
      ): OperationResult<K>;
    };

/**
 * Capability boundary for every Gaming API write.
 *
 * Callers must branch on `allowed` before continuing the handler. Even if a
 * denied dispatcher is invoked directly (for example, by a stale callback),
 * it returns without touching the supplied API implementation.
 */
export function createGamingWriteDispatcher(
  allowed: boolean,
  onDenied: () => void,
  operations: GamingWriteOperations,
): GamingWriteDispatcher {
  if (!allowed) {
    onDenied();
    return {
      allowed: false,
      dispatch: () => undefined,
    };
  }

  return {
    allowed: true,
    dispatch: (operation, ...args) => Reflect.apply(operations[operation], operations, args),
  } as GamingWriteDispatcher;
}

export function GamingWriteOnly({
  allowed,
  children,
}: {
  allowed: boolean;
  children: ReactNode;
}) {
  return allowed ? <>{children}</> : null;
}

export function GamingMutationButton({
  canManageSessions,
  disabled,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  canManageSessions: boolean;
}) {
  return (
    <button
      type="button"
      {...props}
      disabled={!canManageSessions || disabled}
    />
  );
}
