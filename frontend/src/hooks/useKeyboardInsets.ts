import { useEffect } from "react";
import type { RefObject } from "react";

import { isTextEntryField, keyboardHeight, revealDelta } from "../lib/virtualKeyboard";

/** Il campo con il cursore, se è uno in cui si scrive e sta dentro `root`. */
export function activeTextField(root: HTMLElement): HTMLElement | null {
  const active = document.activeElement;
  if (!(active instanceof HTMLElement) || !root.contains(active)) return null;
  return isTextEntryField(active.tagName, active.getAttribute("type"), active.isContentEditable) ? active : null;
}

/** Altezza coperta dalla tastiera adesso (0 se non c'è o se `visualViewport` non esiste). */
export function currentKeyboardHeight(): number {
  const viewport = window.visualViewport;
  if (!viewport) return 0;
  return keyboardHeight({ layoutHeight: window.innerHeight, visualHeight: viewport.height, visualOffsetTop: viewport.offsetTop, scale: viewport.scale });
}

/**
 * Pagina di un modulo lungo (Crea / Modifica evento): con la tastiera aperta il documento deve poter scorrere fino a mettere sopra di essa
 * anche l'ultimo campo, la CTA non deve galleggiare sopra i campi, e il campo con il cursore resta visibile (tra le pillole dell'header
 * in alto e la tastiera, o la CTA e la navigazione se la tastiera è chiusa).
 *
 * Scrive su `root` `data-keyboard="open"` e `--kb-inset`; il resto è CSS (`.event-form-page` in pages.css). Un solo gruppo di ascoltatori,
 * sul `visualViewport` e sull'elemento: nessun ascoltatore globale su `document`.
 */
/** `active`: la pagina mostra davvero il modulo (non il caricamento o l'errore), quindi `root` esiste. */
export function useFormKeyboardSupport(rootRef: RefObject<HTMLElement | null>, active = true) {
  useEffect(() => {
    const root = rootRef.current;
    if (!active || !root) return undefined;
    const viewport = window.visualViewport;
    let frame = 0;
    let settle = 0;

    const reveal = () => {
      const field = activeTextField(root);
      if (!field) return;
      const rect = field.getBoundingClientRect();
      const keyboard = currentKeyboardHeight();
      const header = document.querySelector<HTMLElement>(".app-header__inner")?.getBoundingClientRect().bottom ?? 0;
      const visualTop = viewport?.offsetTop ?? 0;
      const boundTop = Math.max(header, visualTop);
      let boundBottom: number;
      if (keyboard > 0 && viewport) {
        boundBottom = viewport.offsetTop + viewport.height;
      } else {
        // Tastiera chiusa: la CTA fissa in basso e la navigazione coprono il fondo dello schermo.
        const covers = [root.querySelector<HTMLElement>(".create-event-actions"), document.querySelector<HTMLElement>(".mobile-nav")]
          .map((element) => (element && element.getClientRects().length > 0 ? element.getBoundingClientRect().top : window.innerHeight));
        boundBottom = Math.min(window.innerHeight, ...covers);
      }
      const delta = revealDelta({ fieldTop: rect.top, fieldBottom: rect.bottom, boundTop, boundBottom });
      if (delta !== 0) window.scrollBy({ top: delta, behavior: "instant" });
    };

    const update = () => {
      const keyboard = currentKeyboardHeight();
      if (keyboard > 0) root.dataset.keyboard = "open";
      else delete root.dataset.keyboard;
      root.style.setProperty("--kb-inset", `${keyboard}px`);
    };

    const onViewportChange = () => {
      window.cancelAnimationFrame(frame);
      frame = window.requestAnimationFrame(() => { update(); reveal(); });
    };
    const onFocusIn = () => {
      // La tastiera compare dopo il focus: si ricontrolla subito e a animazione finita.
      onViewportChange();
      window.clearTimeout(settle);
      settle = window.setTimeout(() => { update(); reveal(); }, 350);
    };

    update();
    viewport?.addEventListener("resize", onViewportChange);
    viewport?.addEventListener("scroll", onViewportChange);
    root.addEventListener("focusin", onFocusIn);
    return () => {
      window.cancelAnimationFrame(frame);
      window.clearTimeout(settle);
      viewport?.removeEventListener("resize", onViewportChange);
      viewport?.removeEventListener("scroll", onViewportChange);
      root.removeEventListener("focusin", onFocusIn);
      delete root.dataset.keyboard;
      root.style.removeProperty("--kb-inset");
    };
  }, [active, rootRef]);
}
