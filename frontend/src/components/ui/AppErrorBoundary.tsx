import { Component } from "react";
import type { ErrorInfo, ReactNode } from "react";

import { ErrorScreen } from "./ErrorScreen";

interface AppErrorBoundaryProps {
  readonly children: ReactNode;
  /** `true` per un riquadro dentro l'app (es. Discover): il resto dell'interfaccia resta utilizzabile. */
  readonly inline?: boolean;
}

interface AppErrorBoundaryState {
  readonly error: unknown;
  readonly failed: boolean;
}

/**
 * Ultima rete di sicurezza per gli errori di rendering fuori dalle rotte (provider, struttura, Discover).
 * Gli errori delle singole pagine li gestisce `errorElement` del router (vedi RouteErrorPage).
 */
export class AppErrorBoundary extends Component<AppErrorBoundaryProps, AppErrorBoundaryState> {
  state: AppErrorBoundaryState = { error: null, failed: false };

  static getDerivedStateFromError(error: unknown): AppErrorBoundaryState {
    return { error, failed: true };
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    console.error("Unhandled render error", error, info.componentStack);
  }

  render() {
    if (this.state.failed) return <ErrorScreen error={this.state.error} inline={this.props.inline} />;
    return this.props.children;
  }
}
