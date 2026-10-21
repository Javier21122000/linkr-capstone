import { MapPinned } from "lucide-react";
import { Link } from "react-router";

import { createMapFocusState } from "../../lib/navigation";

interface ViewOnMapLinkProps {
  readonly eventId: string;
  /** `button`: azione secondaria a tutta evidenza (dettaglio). `inline`: riga discreta dentro una scheda. */
  readonly variant?: "button" | "inline";
  readonly className?: string;
}

/**
 * "Visualizza sulla mappa": un normale link verso Discover che porta con sé l'evento da mostrare. Discover non crea nessuna mappa nuova:
 * riprende quella già aperta, seleziona l'evento, lo inquadra e mostra l'anteprima (vedi `DiscoverPage`).
 */
export function ViewOnMapLink({ eventId, variant = "button", className = "" }: ViewOnMapLinkProps) {
  return (
    <Link
      to="/"
      state={createMapFocusState(eventId)}
      className={`${variant === "button" ? "button-secondary inline-flex items-center gap-2" : "map-link"} ${className}`.trim()}
    >
      <MapPinned size={variant === "button" ? 17 : 16} aria-hidden="true" /> Visualizza sulla mappa
    </Link>
  );
}
