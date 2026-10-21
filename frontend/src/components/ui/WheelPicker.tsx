import { useCallback, useEffect, useLayoutEffect, useRef } from "react";
import type { KeyboardEvent, PointerEvent } from "react";

import { prefersReducedMotion } from "../../lib/motion";

interface WheelPickerProps {
  /** Nome del valore ("Ore", "Minuti"): lo legge chi usa uno screen reader. */
  readonly label: string;
  /** Una riga per valore, già formattata ("00" … "23"). */
  readonly items: readonly string[];
  /** Riga selezionata (0-based): per ore e minuti coincide con il valore. */
  readonly index: number;
  readonly onChange: (index: number) => void;
  /** Testo letto per il valore corrente, es. "21 ore". */
  readonly valueText?: (index: number) => string;
  /** Salto di PageUp/PageDown. */
  readonly pageStep?: number;
  readonly disabled?: boolean;
  readonly id?: string;
}

const FALLBACK_ITEM_HEIGHT = 44;
/** Un gesto più corto di così è un tocco sulla riga, non un trascinamento. */
const DRAG_THRESHOLD_PX = 4;
/** Il tempo massimo di una scrollata fatta dall'app: finché dura, gli eventi di scorrimento non sono dell'utente. */
const PROGRAMMATIC_SCROLL_MS = 450;
const TYPEAHEAD_RESET_MS = 900;
/** Dove `scrollend` non esiste, lo scorrimento si considera finito dopo questa pausa. */
const SETTLE_FALLBACK_MS = 140;
const SUPPORTS_SCROLL_END = typeof window !== "undefined" && "onscrollend" in window;

/**
 * Ruota verticale in stile iOS, accessibile.
 *
 * È uno scorrimento nativo con `scroll-snap`: inerzia al tocco e rotellina del mouse gratis, niente animazioni calcolate in JS.
 * Per il resto si comporta da `spinbutton` (un solo elemento focalizzabile): frecce, PageUp/PageDown, Home/End, cifre digitate,
 * trascinamento con il mouse e clic su una riga. Le righe sono decorative (`aria-hidden`): il valore lo dice lo spinbutton.
 */
export function WheelPicker({ label, items, index, onChange, valueText, pageStep = 5, disabled = false, id }: WheelPickerProps) {
  const scrollerRef = useRef<HTMLDivElement>(null);
  const itemHeightRef = useRef(FALLBACK_ITEM_HEIGHT);
  const indexRef = useRef(index);
  const onChangeRef = useRef(onChange);
  const programmaticUntilRef = useRef(0);
  const frameRef = useRef(0);
  const settleTimeoutRef = useRef(0);
  /** L'utente ha preso in mano la ruota dopo l'ultimo scorrimento fatto dall'app: solo allora lo stato deve seguire la posizione reale. */
  const userTookOverRef = useRef(false);
  const dragRef = useRef<{ readonly startY: number; readonly startScroll: number; moved: boolean } | null>(null);
  const typedRef = useRef({ text: "", timeoutId: 0 });
  const last = items.length - 1;

  indexRef.current = index;
  onChangeRef.current = onChange;

  const clamp = useCallback((value: number) => Math.max(0, Math.min(last, value)), [last]);

  const scrollToIndex = useCallback((target: number, smooth: boolean) => {
    const scroller = scrollerRef.current;
    if (!scroller) return;
    const animate = smooth && !prefersReducedMotion();
    userTookOverRef.current = false;
    programmaticUntilRef.current = performance.now() + (animate ? PROGRAMMATIC_SCROLL_MS : 60);
    scroller.scrollTo({ top: target * itemHeightRef.current, behavior: animate ? "smooth" : "auto" });
  }, []);

  // Altezza reale di una riga (è in rem: cambia con la dimensione del testo scelta dall'utente) e posizione iniziale senza animazione.
  useLayoutEffect(() => {
    const scroller = scrollerRef.current;
    const first = scroller?.firstElementChild;
    if (!scroller || !(first instanceof HTMLElement)) return;
    itemHeightRef.current = first.offsetHeight || FALLBACK_ITEM_HEIGHT;
    scroller.scrollTop = indexRef.current * itemHeightRef.current;
  }, []);

  // Un valore cambiato da fuori (tastiera, scorciatoia, clic) porta la ruota sulla riga giusta; uno cambiato scorrendo no.
  useEffect(() => {
    const scroller = scrollerRef.current;
    if (!scroller) return;
    const shown = Math.round(scroller.scrollTop / itemHeightRef.current);
    if (shown !== index && dragRef.current === null) scrollToIndex(index, true);
  }, [index, scrollToIndex]);

  useEffect(() => () => {
    window.cancelAnimationFrame(frameRef.current);
    window.clearTimeout(typedRef.current.timeoutId);
    window.clearTimeout(settleTimeoutRef.current);
  }, []);

  // Fine dello scorrimento (anche di uno avviato dall'app e poi preso in mano dall'utente): lo stato segue la posizione reale della ruota.
  const settle = useCallback(() => {
    const scroller = scrollerRef.current;
    // Uno scorrimento avviato dall'app (tasti, scorciatoie) arriva da solo dove deve: riletto a metà strada darebbe un valore sbagliato.
    if (!scroller || dragRef.current !== null || !userTookOverRef.current) return;
    userTookOverRef.current = false;
    programmaticUntilRef.current = 0;
    const next = clamp(Math.round(scroller.scrollTop / itemHeightRef.current));
    if (next !== indexRef.current) onChangeRef.current(next);
  }, [clamp]);

  function handleScroll() {
    if (!SUPPORTS_SCROLL_END) {
      window.clearTimeout(settleTimeoutRef.current);
      settleTimeoutRef.current = window.setTimeout(settle, SETTLE_FALLBACK_MS);
    }
    if (performance.now() < programmaticUntilRef.current) return;
    window.cancelAnimationFrame(frameRef.current);
    frameRef.current = window.requestAnimationFrame(() => {
      const scroller = scrollerRef.current;
      if (!scroller) return;
      const next = clamp(Math.round(scroller.scrollTop / itemHeightRef.current));
      if (next !== indexRef.current) onChangeRef.current(next);
    });
  }

  function select(next: number) {
    const target = clamp(next);
    if (target !== indexRef.current) onChangeRef.current(target);
    else scrollToIndex(target, true);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (disabled || event.altKey || event.ctrlKey || event.metaKey) return;
    let handled = true;
    switch (event.key) {
      case "ArrowUp": select(indexRef.current - 1); break;
      case "ArrowDown": select(indexRef.current + 1); break;
      case "PageUp": select(indexRef.current - pageStep); break;
      case "PageDown": select(indexRef.current + pageStep); break;
      case "Home": select(0); break;
      case "End": select(last); break;
      default: {
        if (!/^\d$/.test(event.key)) {
          handled = false;
          break;
        }
        // Cifre digitate: la prima ("7") vale "07"; la seconda ("1" dopo "2") compone "21". Dopo una pausa si riparte.
        const typed = typedRef.current;
        window.clearTimeout(typed.timeoutId);
        typed.text = (typed.text + event.key).slice(-2);
        let position = items.indexOf(typed.text.padStart(2, "0"));
        if (position < 0) {
          typed.text = event.key;
          position = items.indexOf(`0${event.key}`);
        }
        typed.timeoutId = window.setTimeout(() => { typed.text = ""; }, TYPEAHEAD_RESET_MS);
        if (position >= 0) select(position);
      }
    }
    if (handled) event.preventDefault();
  }

  // Il mouse non scorre trascinando un elemento nativo: si imita il gesto del dito. Il tocco e la penna usano lo scorrimento nativo.
  function handlePointerDown(event: PointerEvent<HTMLDivElement>) {
    const scroller = scrollerRef.current;
    if (disabled || event.pointerType !== "mouse" || event.button !== 0 || !scroller) return;
    // Il puntatore si cattura solo quando il gesto diventa un trascinamento: catturarlo subito toglierebbe il clic alla riga toccata.
    userTookOverRef.current = true;
    programmaticUntilRef.current = 0;
    dragRef.current = { startY: event.clientY, startScroll: scroller.scrollTop, moved: false };
  }

  function handlePointerMove(event: PointerEvent<HTMLDivElement>) {
    const drag = dragRef.current;
    const scroller = scrollerRef.current;
    if (!drag || !scroller) return;
    const distance = event.clientY - drag.startY;
    if (!drag.moved && Math.abs(distance) < DRAG_THRESHOLD_PX) return;
    if (!drag.moved) {
      scroller.dataset.dragging = "true";
      scroller.setPointerCapture(event.pointerId);
    }
    drag.moved = true;
    scroller.scrollTop = drag.startScroll - distance;
  }

  function endDrag(event: PointerEvent<HTMLDivElement>) {
    const drag = dragRef.current;
    const scroller = scrollerRef.current;
    if (!drag || !scroller) return;
    dragRef.current = null;
    if (scroller.hasPointerCapture(event.pointerId)) scroller.releasePointerCapture(event.pointerId);
    delete scroller.dataset.dragging;
    if (drag.moved) {
      // Rilasciato tra due righe: si completa lo scatto sulla più vicina, come farebbe lo snap del dito.
      const target = clamp(Math.round(scroller.scrollTop / itemHeightRef.current));
      if (target !== indexRef.current) onChangeRef.current(target);
      scrollToIndex(target, true);
    }
  }

  // Se l'utente riprende in mano la ruota mentre l'app la sta ancora portando su una riga, lo scorrimento torna suo subito.
  function takeOver() {
    userTookOverRef.current = true;
    programmaticUntilRef.current = 0;
  }

  function handleItemClick(position: number) {
    // Un trascinamento che finisce sopra una riga non è un clic su quella riga.
    if (dragRef.current?.moved) return;
    select(position);
  }

  return (
    <div className="wheel" data-disabled={disabled || undefined}>
      <div
        ref={scrollerRef}
        id={id}
        className="wheel__scroller"
        role="spinbutton"
        tabIndex={disabled ? -1 : 0}
        aria-label={label}
        aria-orientation="vertical"
        aria-valuemin={0}
        aria-valuemax={last}
        aria-valuenow={index}
        aria-valuetext={valueText ? valueText(index) : items[index]}
        aria-disabled={disabled || undefined}
        onScroll={handleScroll}
        onScrollEnd={settle}
        onWheel={takeOver}
        onTouchStart={takeOver}
        onKeyDown={handleKeyDown}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
      >
        {items.map((item, position) => (
          <div
            key={item}
            className="wheel__item"
            data-selected={position === index || undefined}
            aria-hidden="true"
            onClick={() => handleItemClick(position)}
          >
            {item}
          </div>
        ))}
      </div>
    </div>
  );
}
