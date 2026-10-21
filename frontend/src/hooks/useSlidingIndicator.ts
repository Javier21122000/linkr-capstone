import { useCallback, useEffect, useLayoutEffect } from "react";
import type { RefObject } from "react";

interface SlidingIndicatorOptions {
  /** Contenitore posizionato (`position: relative/fixed`) delle voci: porta le variabili `--thumb-x/y/w/h` che muovono l'indicatore. */
  readonly trackRef: RefObject<HTMLElement | null>;
  /** Elemento che porta `data-thumb` e `data-animated` (se diverso dal contenitore, es. la barra intorno a una pista che scorre). */
  readonly stateRef?: RefObject<HTMLElement | null>;
  /** Selettore della voce attiva dentro il contenitore, es. `[aria-current="page"]`. */
  readonly activeSelector: string;
  /** Cambia quando cambia la voce attiva: è ciò che avvia lo scorrimento. */
  readonly activeKey: string;
}

/**
 * Indicatore unico che scorre sotto la voce attiva invece di spegnersi e riaccendersi (barra di navigazione, Bottom Navigation, filtri).
 *
 * Misura la voce attiva e scrive quattro variabili CSS; il resto è CSS (`transform` e `width`: nessun layout animato).
 * - `data-thumb="on"` quando la voce è misurabile; senza (pagina nascosta, nessuna voce attiva) l'indicatore svanisce e ogni voce
 *   può mostrare da sola il proprio stato attivo.
 * - `data-animated="true"` dopo il primo paint: l'indicatore compare già al suo posto, senza scivolare dall'angolo.
 * - `data-snap="true"` per un attimo quando riappare dopo essere stato nascosto: si posiziona senza attraversare la barra.
 */
export function useSlidingIndicator({ trackRef, stateRef, activeSelector, activeKey }: SlidingIndicatorOptions) {
  const measure = useCallback(() => {
    const track = trackRef.current;
    const state = (stateRef ?? trackRef).current;
    if (!track || !state) return;

    const active = track.querySelector<HTMLElement>(activeSelector);
    if (!active || active.offsetWidth === 0 || active.offsetHeight === 0) {
      delete state.dataset.thumb;
      return;
    }

    const wasVisible = state.dataset.thumb === "on";
    track.style.setProperty("--thumb-x", `${active.offsetLeft}px`);
    track.style.setProperty("--thumb-y", `${active.offsetTop}px`);
    track.style.setProperty("--thumb-w", `${active.offsetWidth}px`);
    track.style.setProperty("--thumb-h", `${active.offsetHeight}px`);

    if (!wasVisible) {
      state.dataset.snap = "true";
      window.requestAnimationFrame(() => window.requestAnimationFrame(() => { delete state.dataset.snap; }));
    }
    state.dataset.thumb = "on";
  }, [trackRef, stateRef, activeSelector]);

  useLayoutEffect(() => {
    measure();
  }, [measure, activeKey]);

  useEffect(() => {
    const state = (stateRef ?? trackRef).current;
    const frame = window.requestAnimationFrame(() => {
      if (state) state.dataset.animated = "true";
    });
    return () => window.cancelAnimationFrame(frame);
  }, [stateRef, trackRef]);

  // Larghezze che cambiano dopo il primo render: font Manrope caricato, resize, etichette che compaiono ai breakpoint, pagina che torna visibile.
  useEffect(() => {
    const track = trackRef.current;
    if (!track) return undefined;
    const observer = new ResizeObserver(measure);
    observer.observe(track);
    void document.fonts?.ready.then(measure);
    return () => observer.disconnect();
  }, [measure, trackRef]);

  return measure;
}
