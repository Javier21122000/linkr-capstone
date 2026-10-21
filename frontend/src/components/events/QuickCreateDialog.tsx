import { useEffect, useRef } from "react";
import type { KeyboardEvent, ReactNode } from "react";
import { createPortal } from "react-dom";

interface QuickCreateDialogProps {
  readonly labelledBy: string;
  /** Escape, clic sullo sfondo o pulsante di chiusura: il chiamante decide se chiudere subito o chiedere conferma. */
  readonly onRequestClose: () => void;
  readonly children: ReactNode;
}

const FOCUSABLE = [
  "a[href]",
  "button:not(:disabled)",
  "input:not(:disabled):not([type='hidden'])",
  "select:not(:disabled)",
  "textarea:not(:disabled)",
  "[tabindex]:not([tabindex='-1'])",
].join(",");

/**
 * Con la tastiera virtuale aperta il layout viewport non si restringe (iOS) o non si restringe di default (Chrome Android):
 * un pannello `fixed` in basso finirebbe sotto la tastiera, con la CTA irraggiungibile. Si usa `visualViewport` per
 * alzare il pannello sopra la tastiera e limitarne l'altezza all'area realmente visibile.
 */
function useVisibleViewport(panelRef: { readonly current: HTMLElement | null }) {
  useEffect(() => {
    const panel = panelRef.current;
    const viewport = window.visualViewport;
    if (!panel || !viewport) return undefined;

    const update = () => {
      // Con lo zoom a pinch il viewport visivo si restringe senza che ci sia una tastiera: non si tocca nulla.
      if (viewport.scale > 1.01) {
        panel.style.removeProperty("--qc-keyboard");
        panel.style.removeProperty("--qc-viewport-h");
        return;
      }
      const covered = Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop);
      // Sotto ~120 px non è una tastiera ma la barra del browser che si ritrae.
      panel.style.setProperty("--qc-keyboard", `${covered > 120 ? Math.round(covered) : 0}px`);
      panel.style.setProperty("--qc-viewport-h", `${Math.round(viewport.height)}px`);
    };

    update();
    viewport.addEventListener("resize", update);
    viewport.addEventListener("scroll", update);
    return () => {
      viewport.removeEventListener("resize", update);
      viewport.removeEventListener("scroll", update);
    };
  }, [panelRef]);
}

/**
 * Guscio modale di Quick Create: portale su `body` (sopra mappa e navigazione), focus intrappolato, Escape e clic sullo sfondo.
 * Mobile: bottom sheet sopra la tastiera. Tablet/desktop: pannello ancorato in basso a destra, vicino al pulsante +.
 * Il ripristino del focus sul pulsante + è del chiamante, che sa quando il dialogo si chiude davvero.
 */
export function QuickCreateDialog({ labelledBy, onRequestClose, children }: QuickCreateDialogProps) {
  const panelRef = useRef<HTMLDivElement>(null);
  const pressedBackdropRef = useRef(false);
  useVisibleViewport(panelRef);

  // I contenuti mettono a fuoco il proprio primo campo; se non lo fanno il focus entra comunque nel dialogo.
  useEffect(() => {
    const panel = panelRef.current;
    if (panel && !panel.contains(document.activeElement)) panel.focus({ preventScroll: true });
  }, []);

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const panel = panelRef.current;
    if (!panel || event.defaultPrevented) return;
    // Gli eventi dei dialoghi annidati (la fotocamera è in un portale) risalgono nell'albero React: non sono di questo dialogo.
    if (event.target instanceof Element && event.target.closest("[role='dialog']") !== panel) return;

    if (event.key === "Escape") {
      event.preventDefault();
      onRequestClose();
      return;
    }
    if (event.key !== "Tab") return;

    const focusable = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((element) => element.getClientRects().length > 0);
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (!first || !last) {
      event.preventDefault();
      panel.focus();
      return;
    }
    const active = document.activeElement;
    if (event.shiftKey && (active === first || active === panel)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }

  return createPortal(
    <div
      className="qc-backdrop"
      role="presentation"
      onPointerDown={(event) => { pressedBackdropRef.current = event.target === event.currentTarget; }}
      onClick={(event) => {
        // Chiude solo se il gesto è iniziato E finito sullo sfondo: selezionare testo trascinando fuori non deve chiudere.
        if (pressedBackdropRef.current && event.target === event.currentTarget) onRequestClose();
        pressedBackdropRef.current = false;
      }}
    >
      <div
        ref={panelRef}
        className="qc-dialog glass glass--sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby={labelledBy}
        tabIndex={-1}
        onKeyDown={handleKeyDown}
      >
        <span className="qc-dialog__grabber" aria-hidden="true" />
        {children}
      </div>
    </div>,
    document.body,
  );
}
