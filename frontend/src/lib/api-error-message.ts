/** Safe fallback when a proxy/network failure has no business error message. */
export function apiFailureMessage(serverMessage: unknown, status?: number): string {
  if (typeof serverMessage === 'string' && serverMessage.trim()) return serverMessage;
  if (status === undefined) return 'The server could not be reached. Check the connection and refresh to confirm the latest status.';
  if (status === 401) return 'Your sign-in could not be verified. Sign in again to continue.';
  if (status === 403) return 'Your account cannot perform this action. Ask an owner to check your access.';
  if (status === 404) return 'This record is no longer available. Refresh the screen to see the latest information.';
  if (status === 409) return 'This information changed or conflicts with another action. Refresh the screen and review its current status.';
  if (status === 429) return 'Too many requests were sent at once. Wait a moment, then try again.';
  if (status === 408 || status === 504) return 'The server took too long to respond. Refresh to confirm whether the action completed before trying again.';
  if (status >= 500) return 'The server is temporarily unable to complete this request. Refresh to confirm the latest status before trying again.';
  return 'The request was not accepted. Check the entered details and refresh before trying again.';
}
