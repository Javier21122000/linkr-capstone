import type { CSSProperties } from "react";

/** Preferenza di sistema "riduci animazioni". Modulo senza dipendenze: i componenti sempre caricati non devono importare MapLibre per leggerla. */
export function prefersReducedMotion(): boolean {
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/** Posizione di una riga nell'ingresso scaglionato di una lista (`.stagger-in` in pages.css): la riga n parte un attimo dopo la n-1. */
export function staggerStyle(index: number): CSSProperties {
  return { "--i": index } as CSSProperties;
}
