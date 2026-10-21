import { categoryLabels } from "../../lib/eventPresentation";
import { resolveEventImageUrl } from "../../lib/eventImages";
import type { EventCategory } from "../../types/event";
import { CategoryIcon } from "./CategoryIcon";

interface EventImageProps {
  readonly imageUrl?: string | null;
  readonly imageAlt?: string | null;
  readonly category: EventCategory;
  readonly className?: string;
  readonly loading?: "eager" | "lazy";
  /** Miniature: il segnaposto mostra solo l'icona della categoria, senza etichetta. */
  readonly compact?: boolean;
}

export function EventImage({ imageUrl, imageAlt, category, className = "", loading = "lazy", compact = false }: EventImageProps) {
  if (imageUrl) {
    return (
      <img
        src={resolveEventImageUrl(imageUrl)}
        alt={imageAlt ?? ""}
        className={className}
        loading={loading}
        fetchPriority={loading === "eager" ? "high" : "auto"}
      />
    );
  }

  if (compact) {
    return (
      <div
        className={`grid place-items-center bg-peach/60 text-linkr-strong ${className}`}
        role="img"
        aria-label={`Immagine non disponibile, categoria ${categoryLabels[category]}`}
      >
        <CategoryIcon category={category} size={26} strokeWidth={1.9} />
      </div>
    );
  }

  return (
    <div
      className={`grid place-items-center bg-peach/50 text-linkr-strong ${className}`}
      role="img"
      aria-label="Immagine non disponibile"
    >
      <span className="flex flex-col items-center gap-4 px-4 text-center">
        <CategoryIcon category={category} size={56} strokeWidth={1.6} />
        <span className="text-xs font-extrabold uppercase tracking-[0.14em]">{categoryLabels[category]}</span>
      </span>
    </div>
  );
}
