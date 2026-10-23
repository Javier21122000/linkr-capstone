import { useEffect, useRef } from "react";
import type { KeyboardEvent, ReactNode } from "react";
import { createPortal } from "react-dom";

import { activeTextField } from "../../hooks/useKeyboardInsets";
import { keyboardHeight, revealDelta, shouldUseCompactPanel } from "../../lib/virtualKeyboard";

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
 *
 * Con poco spazio visibile (tastiera aperta su uno schermo piccolo, Display Zoom, orizzontale) intestazione e CTA fisse lasciavano al
 * corpo 60 px o meno: il pannello diventa `data-compact` e scorre per intero (CTA sempre in vista in fondo, vedi quick-create.css).
 * Il campo con il cursore viene portato in vista dentro l'area che scorre, al netto della CTA.
 */
function useVisibleViewport(panelRef: { readonly current: HTMLElement | null }) {
  useEffect(() => {
    const panel = panelRef.current;
    const viewport = window.visualViewport;
    if (!panel) return undefined;

    let frame = 0;
    let settle = 0;

    const revealActiveField = () => {
      const field = activeTextField(panel);
      if (!field) return;
      const compact = panel.dataset.compact === "true";
      const scroller = compact ? panel : panel.querySelector<HTMLElement>(".qc-dialog__body");
      if (!scroller) return;
      const bounds = scroller.getBoundingClientRect();
      // In modalità compatta la CTA sta sopra il fondo dell'area che scorre: il campo non deve finirle sotto.
      const footer = compact ? panel.querySelector<HTMLElement>(".qc-dialog__footer") : null;
      const rect = field.getBoundingClientRect();
      const delta = revealDelta({ fieldTop: rect.top, fieldBottom: rect.bottom, boundTop: bounds.top, boundBottom: bounds.bottom - (footer?.getBoundingClientRect().height ?? 0) });
      if (delta !== 0) scroller.scrollTop += delta;
    };

    const update = () => {
      if (!viewport) return;
      // Con lo zoom a pinch il viewport visivo si restringe senza che ci sia una tastiera: non si tocca nulla.
      if (viewport.scale > 1.01) {
        panel.style.removeProperty("--qc-keyboard");
        panel.style.removeProperty("--qc-viewport-h");
        delete panel.dataset.compact;
        return;
      }
      const keyboard = keyboardHeight({ layoutHeight: window.innerHeight, visualHeight: viewport.height, visualOffsetTop: viewport.offsetTop, scale: viewport.scale });
      panel.style.setProperty("--qc-keyboard", `${keyboard}px`);
      panel.style.setProperty("--qc-viewport-h", `${Math.round(viewport.height)}px`);
      const chrome = (panel.querySelector<HTMLElement>(".qc-dialog__header")?.offsetHeight ?? 0) + (panel.querySelector<HTMLElement>(".qc-dialog__footer")?.offsetHeight ?? 0);
      panel.dataset.compact = shouldUseCompactPanel(viewport.height, chrome) ? "true" : "false";
    };

    const onChange = () => {
      window.cancelAnimationFrame(frame);
      frame = window.requestAnimationFrame(() => { update(); revealActiveField(); });
    };
    const onFocusIn = () => {
      onChange();
      // La tastiera compare dopo il focus: si ricontrolla a animazione finita.
      window.clearTimeout(settle);
      settle = window.setTimeout(() => { update(); revealActiveField(); }, 350);
    };

    update();
    // Cambia la vista (menu, passaggi) o il contenuto: intestazione e CTA possono cambiare altezza.
    const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(onChange);
    observer?.observe(panel);
    viewport?.addEventListener("resize", onChange);
    viewport?.addEventListener("scroll", onChange);
    panel.addEventListener("focusin", onFocusIn);
    return () => {
      window.cancelAnimationFrame(frame);
      window.clearTimeout(settle);
      observer?.disconnect();
      viewport?.removeEventListener("resize", onChange);
      viewport?.removeEventListener("scroll", onChange);
      panel.removeEventListener("focusin", onFocusIn);
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
