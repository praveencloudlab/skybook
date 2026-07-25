import { Component, type ReactNode } from 'react';
import { ErrorPage } from './ErrorPage';

/**
 * Catches uncaught render errors anywhere below it and shows the 500 page
 * (FRONTEND_MODULE.md Module 18) instead of a blank white screen. A class
 * component because error boundaries have no hook equivalent. "Try again" clears
 * the boundary to re-render; if the fault persists, the page's own links lead
 * out.
 */
export class RouteErrorBoundary extends Component<{ children: ReactNode }, { hasError: boolean }> {
  state = { hasError: false };

  static getDerivedStateFromError(): { hasError: true } {
    return { hasError: true };
  }

  componentDidCatch(error: unknown, info: unknown): void {
    // Surface it for debugging; a real deployment would ship this to the
    // centralized logging/tracing pipeline the backend already runs.
    console.error('Uncaught render error', error, info);
  }

  render(): ReactNode {
    if (this.state.hasError) {
      return <ErrorPage code="500" onRetry={() => this.setState({ hasError: false })} />;
    }
    return this.props.children;
  }
}
