/**
 * Last-resort safety net for the till. Without a boundary React 18 unmounts
 * the entire tree on any render throw, leaving the cashier on a white screen
 * mid-shift. Anything that escapes lands here instead, with a way back.
 */
import { Component, type ErrorInfo, type ReactNode } from 'react';

interface ErrorBoundaryProps {
  children: ReactNode;
}

interface ErrorBoundaryState {
  error: Error | null;
}

export default class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('Unhandled render error', error, info.componentStack);
  }

  render(): ReactNode {
    const { error } = this.state;
    if (!error) return this.props.children;
    return (
      <div className="min-h-screen bg-bg text-fg flex items-center justify-center p-6">
        <div className="card w-full max-w-md text-center">
          <h1 className="text-xl font-bold">Something went wrong</h1>
          <p className="mt-3 text-sm text-fg-muted">
            The screen stopped loading. Orders already charged are saved on the server —
            reload to carry on billing.
          </p>
          <p className="mt-3 text-xs text-fg-subtle break-words">{error.message}</p>
          <button
            type="button"
            className="btn btn-primary w-full mt-5"
            onClick={() => window.location.reload()}
          >
            Reload
          </button>
        </div>
      </div>
    );
  }
}
