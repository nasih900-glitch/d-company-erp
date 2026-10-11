export type GamingBoardScope = {
  userId: string | null | undefined;
  companyId: string | null | undefined;
  branchId: string | null | undefined;
  terminalReady: boolean;
  terminalId: string | null | undefined;
};

export function gamingBoardScopeKey(scope: GamingBoardScope): string {
  return JSON.stringify([
    scope.userId ?? null,
    scope.companyId ?? null,
    scope.branchId ?? null,
    scope.terminalReady,
    scope.terminalId ?? null,
  ]);
}

export function gamingBoardIncompleteMessage(
  reasons: readonly (string | null | false | undefined)[],
): string | null {
  const presentReasons = reasons.filter((reason): reason is string => Boolean(reason));
  return presentReasons.length > 0
    ? `The latest Gaming refresh was incomplete (${presentReasons.join(', ')}).`
    : null;
}
