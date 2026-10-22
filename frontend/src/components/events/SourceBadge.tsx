import { sourceLabel } from "../../lib/eventSource";
import type { EventSource } from "../../types/event";

interface SourceBadgeProps {
  readonly source?: EventSource | null;
  readonly className?: string;
}

/** "Ticketmaster" per gli eventi importati; niente per gli altri. Discreto: si distingue da "Community" e da "Demo" senza gridare. */
export function SourceBadge({ source, className = "" }: SourceBadgeProps) {
  const label = sourceLabel(source);
  if (!label) return null;
  return (
    <span className={`source-badge ${className}`.trim()} title={`Evento importato da ${label}`}>
      {label}
    </span>
  );
}
