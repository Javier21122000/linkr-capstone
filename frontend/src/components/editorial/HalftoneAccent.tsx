import type { CSSProperties } from "react";

/**
 * Macchia di retino (puntini di stampa) disegnata in CSS: nessuna immagine. Solo decorazione, dietro al contenuto.
 * `shape`: dove i puntini sfumano. `tone`: colore dell'inchiostro. Posizione e dimensione si danno da fuori con `className`/`style`.
 */
interface HalftoneAccentProps {
  readonly shape?: "fade" | "circle" | "band";
  readonly tone?: "ink" | "coral" | "yellow";
  readonly className?: string;
  readonly style?: CSSProperties;
}

export function HalftoneAccent({ shape = "fade", tone = "ink", className = "", style }: HalftoneAccentProps) {
  return <span className={`ed-deco ed-halftone ed-halftone--${shape} ed-halftone--${tone} ${className}`.trim()} style={style} aria-hidden="true" />;
}
