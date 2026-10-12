import { CalendarDays } from "lucide-react";

import { categoryLabels } from "../../lib/eventPresentation";
import type { EventCategory } from "../../types/event";

interface EventImageProps {
  readonly imageUrl?: string | null;
  readonly imageAlt?: string | null;
  readonly category: EventCategory;
  readonly className?: string;
  readonly loading?: "eager" | "lazy";
}

export function EventImage({ imageUrl, imageAlt, category, className = "", loading = "lazy" }: EventImageProps) {
  if (imageUrl) {
    return (
      <img
        src={imageUrl}
        alt={imageAlt ?? ""}
        className={className}
        loading={loading}
        fetchPriority={loading === "eager" ? "high" : "auto"}
      />
    );
  }

  return (
    <div
      className={`grid place-items-center bg-linkr-soft text-linkr-strong ${className}`}
      role="img"
      aria-label="Immagine non disponibile"
    >
      <span className="flex flex-col items-center gap-3 px-4 text-center">
        <CalendarDays size={30} strokeWidth={1.8} aria-hidden="true" />
        <span className="text-xs font-extrabold uppercase tracking-[0.12em]">{categoryLabels[category]}</span>
      </span>
    </div>
  );
}
