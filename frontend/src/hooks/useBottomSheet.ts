import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import type { RefObject } from "react";

import {
  DRAG_SLOP_PX,
  dampedOffset,
  releaseVelocity,
  resolveRelease,
  sheetOffsets,
  sheetOwnsListDrag,
} from "../lib/bottomSheet";
import type { Sample, SheetOffsets, SheetSnap, SheetTarget } from "../lib/bottomSheet";
import { prefersReducedMotion } from "../lib/motion";

/** Sotto questa larghezza la lista è una bottom sheet trascinabile; sopra è un pannello laterale e questo hook non fa nulla. */
const MOBILE_SHEET_QUERY = "(max-width: 47.999rem)";
const CLOSE_FALLBACK_MS = 460;

interface BottomSheetOptions {
  readonly enabled: boolean;
  readonly panelRef: RefObject<HTMLElement | null>;
  /** Contenitore che scorre: un gesto che parte lì cede alla sheet solo alle condizioni di `sheetOwnsListDrag`. */
  readonly bodyRef: RefObject<HTMLElement | null>;
  readonly onClose: () => void;
}

interface Drag {
  readonly source: "grip" | "body";
  readonly startX: number;
  readonly startY: number;
  readonly scrollTop: number;
  mode: "pending" | "sheet" | "scroll";
  anchorY: number;
  startOffset: number;
  offset: number;
  lastY: number;
  samples: Sample[];
  moved: boolean;
}

/**
 * Bottom sheet nativa-like per la lista eventi: stato intermedio, espanso e chiuso, trascinamento diretto di maniglia e intestazione,
 * lista che cede alla sheet quando è al primo elemento. Tutto locale al pannello (nessun listener su document/window) e senza librerie.
 *
 * Il pannello ha l'altezza dello stato espanso e si sposta solo con `transform` tramite `--sheet-y` (scritta direttamente sull'elemento:
 * niente render React a ogni movimento, niente layout). Tocco: Touch Events non passivi sul pannello, gli unici che permettono di
 * impedire lo scorrimento nativo della lista quando il gesto è della sheet. Mouse (finestra stretta): Pointer Events sulla maniglia.
 */
export function useBottomSheet({ enabled, panelRef, bodyRef, onClose }: BottomSheetOptions) {
  const [snap, setSnap] = useState<SheetSnap>("half");
  const snapRef = useRef<SheetSnap>("half");
  const offsetsRef = useRef<SheetOffsets>({ full: 0, half: 0, closed: 0 });
  const closingRef = useRef(false);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  const isMobile = useCallback(() => window.matchMedia(MOBILE_SHEET_QUERY).matches, []);

  const writeOffset = useCallback((offset: number) => {
    panelRef.current?.style.setProperty("--sheet-y", `${Math.round(offset * 10) / 10}px`);
  }, [panelRef]);

  /** Misura il pannello e l'area che lo contiene (la mappa) e ricalcola le posizioni; lo spazio nascosto serve alla lista per scorrere fino in fondo. */
  const measure = useCallback(() => {
    const panel = panelRef.current;
    const area = panel?.parentElement;
    if (!panel || !area) return;
    offsetsRef.current = sheetOffsets(panel.offsetHeight, area.clientHeight);
    panel.style.setProperty("--sheet-hidden", snapRef.current === "half" ? `${offsetsRef.current.half}px` : "0px");
  }, [panelRef]);

  /** Porta la sheet allo stato indicato; la transizione la fa il CSS (assente durante il trascinamento e con "riduci animazioni"). */
  const settle = useCallback((target: SheetSnap) => {
    snapRef.current = target;
    setSnap(target);
    measure();
    writeOffset(offsetsRef.current[target]);
  }, [measure, writeOffset]);

  const requestClose = useCallback(() => {
    const panel = panelRef.current;
    if (closingRef.current) return;
    closingRef.current = true;
    if (!panel || !isMobile() || prefersReducedMotion()) {
      onCloseRef.current();
      return;
    }
    // Il Bottom Navigation riappare mentre la sheet scende, non dopo.
    panel.dataset.state = "closing";
    measure();
    writeOffset(offsetsRef.current.closed);
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      panel.removeEventListener("transitionend", handleEnd);
      onCloseRef.current();
    };
    const handleEnd = (event: TransitionEvent) => {
      if (event.target === panel && event.propertyName === "transform") finish();
    };
    panel.addEventListener("transitionend", handleEnd);
    window.setTimeout(finish, CLOSE_FALLBACK_MS);
  }, [isMobile, measure, panelRef, writeOffset]);

  const toggleSnap = useCallback(() => {
    if (!isMobile() || closingRef.current) return;
    settle(snapRef.current === "full" ? "half" : "full");
  }, [isMobile, settle]);

  // Ingresso: la sheet parte fuori schermo e sale allo stato intermedio. Prima del primo paint è già al suo posto di partenza.
  useLayoutEffect(() => {
    const panel = panelRef.current;
    if (!enabled || !panel) return undefined;
    closingRef.current = false;
    snapRef.current = "half";
    setSnap("half");
    measure();
    if (!isMobile()) return undefined;

    if (prefersReducedMotion()) {
      writeOffset(offsetsRef.current.half);
      return undefined;
    }
    writeOffset(offsetsRef.current.closed);
    let second = 0;
    const first = window.requestAnimationFrame(() => {
      second = window.requestAnimationFrame(() => writeOffset(offsetsRef.current.half));
    });
    return () => {
      window.cancelAnimationFrame(first);
      window.cancelAnimationFrame(second);
    };
  }, [enabled, isMobile, measure, panelRef, writeOffset]);

  // Se cambia l'altezza disponibile (rotazione, barra di Safari) la sheet resta nel suo stato, senza animare.
  useEffect(() => {
    const panel = panelRef.current;
    if (!enabled || !panel || typeof ResizeObserver === "undefined") return undefined;
    const observer = new ResizeObserver(() => {
      if (closingRef.current || panel.dataset.dragging || !isMobile()) return;
      measure();
      panel.dataset.resizing = "true";
      writeOffset(offsetsRef.current[snapRef.current]);
      window.requestAnimationFrame(() => { delete panel.dataset.resizing; });
    });
    observer.observe(panel);
    return () => observer.disconnect();
  }, [enabled, isMobile, measure, panelRef, writeOffset]);

  useEffect(() => {
    const panel = panelRef.current;
    if (!enabled || !panel) return undefined;

    let drag: Drag | null = null;
    let swallowClickUntil = 0;

    const currentOffset = () => {
      const transform = window.getComputedStyle(panel).transform;
      return transform && transform !== "none" ? new DOMMatrixReadOnly(transform).m42 : 0;
    };

    const begin = (source: Drag["source"], x: number, y: number) => {
      if (closingRef.current || !isMobile()) return;
      drag = {
        source, startX: x, startY: y, anchorY: y, lastY: y, startOffset: 0, offset: 0,
        scrollTop: bodyRef.current?.scrollTop ?? 0, mode: "pending", samples: [{ y, t: performance.now() }], moved: false,
      };
    };

    const startSheetDrag = (current: Drag, y: number) => {
      current.mode = "sheet";
      current.anchorY = y;
      // Se parte durante una transizione la sheet si ferma dov'è: non salta allo stato di arrivo.
      current.startOffset = currentOffset();
      current.offset = current.startOffset;
      panel.dataset.dragging = "true";
      writeOffset(current.startOffset);
      measure();
    };

    /** Aggiorna il gesto; restituisce true se va impedito lo scorrimento nativo. */
    const move = (x: number, y: number, cancelable: boolean): boolean => {
      const current = drag;
      if (!current) return false;
      const dy = y - current.startY;

      if (current.mode === "pending") {
        if (Math.hypot(x - current.startX, dy) < DRAG_SLOP_PX) return false;
        current.moved = true;
        if (Math.abs(x - current.startX) > Math.abs(dy) && current.source === "body") {
          current.mode = "scroll";
        } else if (current.source === "grip" || sheetOwnsListDrag({ snap: snapRef.current, scrollTop: current.scrollTop, deltaY: dy })) {
          startSheetDrag(current, y);
        } else {
          current.mode = "scroll";
        }
      } else if (current.mode === "scroll" && current.source === "body" && cancelable && y > current.lastY && (bodyRef.current?.scrollTop ?? 1) <= 0) {
        // La lista è tornata al primo elemento e il dito continua verso il basso: da qui in poi è la sheet a seguirlo.
        startSheetDrag(current, y);
      }
      current.lastY = y;

      if (current.mode !== "sheet") return false;
      current.offset = dampedOffset(current.startOffset + (y - current.anchorY), offsetsRef.current);
      current.samples.push({ y, t: performance.now() });
      if (current.samples.length > 8) current.samples.shift();
      writeOffset(current.offset);
      return true;
    };

    const end = () => {
      const current = drag;
      drag = null;
      if (!current || current.mode !== "sheet") return;
      delete panel.dataset.dragging;
      const target: SheetTarget = resolveRelease({
        offset: current.offset,
        velocity: releaseVelocity(current.samples, performance.now()),
        from: snapRef.current,
        offsets: offsetsRef.current,
      });
      if (target === "closed") requestClose();
      else settle(target);
    };

    const handleTouchStart = (event: TouchEvent) => {
      const target = event.target instanceof Element ? event.target : null;
      const touch = event.touches[0];
      if (!target || !touch || event.touches.length !== 1) {
        drag = null;
        return;
      }
      if (target.closest("[data-sheet-grip]")) begin("grip", touch.clientX, touch.clientY);
      else if (bodyRef.current?.contains(target)) begin("body", touch.clientX, touch.clientY);
    };
    const handleTouchMove = (event: TouchEvent) => {
      const touch = event.touches[0];
      if (!touch) return;
      if (move(touch.clientX, touch.clientY, event.cancelable) && event.cancelable) event.preventDefault();
    };
    const handleTouchEnd = () => end();

    const handlePointerDown = (event: PointerEvent) => {
      if (event.pointerType !== "mouse" || event.button !== 0) return;
      if (!(event.target instanceof Element) || !event.target.closest("[data-sheet-grip]")) return;
      begin("grip", event.clientX, event.clientY);
      if (drag) panel.setPointerCapture(event.pointerId);
    };
    const handlePointerMove = (event: PointerEvent) => {
      if (event.pointerType === "mouse") move(event.clientX, event.clientY, false);
    };
    const handlePointerUp = (event: PointerEvent) => {
      if (event.pointerType !== "mouse") return;
      if (drag?.moved) swallowClickUntil = performance.now() + 60;
      end();
    };
    const handleClickCapture = (event: MouseEvent) => {
      if (performance.now() > swallowClickUntil) return;
      event.stopPropagation();
      event.preventDefault();
    };

    // `passive: false` solo su touchmove: è ciò che consente di fermare lo scorrimento della lista quando il gesto è della sheet.
    panel.addEventListener("touchstart", handleTouchStart, { passive: true });
    panel.addEventListener("touchmove", handleTouchMove, { passive: false });
    panel.addEventListener("touchend", handleTouchEnd);
    panel.addEventListener("touchcancel", handleTouchEnd);
    panel.addEventListener("pointerdown", handlePointerDown);
    panel.addEventListener("pointermove", handlePointerMove);
    panel.addEventListener("pointerup", handlePointerUp);
    panel.addEventListener("pointercancel", handlePointerUp);
    panel.addEventListener("click", handleClickCapture, true);
    return () => {
      panel.removeEventListener("touchstart", handleTouchStart);
      panel.removeEventListener("touchmove", handleTouchMove);
      panel.removeEventListener("touchend", handleTouchEnd);
      panel.removeEventListener("touchcancel", handleTouchEnd);
      panel.removeEventListener("pointerdown", handlePointerDown);
      panel.removeEventListener("pointermove", handlePointerMove);
      panel.removeEventListener("pointerup", handlePointerUp);
      panel.removeEventListener("pointercancel", handlePointerUp);
      panel.removeEventListener("click", handleClickCapture, true);
    };
  }, [bodyRef, enabled, isMobile, measure, panelRef, requestClose, settle, writeOffset]);

  return { snap, toggleSnap, requestClose };
}
