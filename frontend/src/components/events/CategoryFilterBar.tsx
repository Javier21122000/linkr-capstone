import { useCallback, useEffect, useLayoutEffect, useRef } from "react";

import { useSlidingIndicator } from "../../hooks/useSlidingIndicator";
import { categoryOptions } from "../../lib/eventPresentation";
import { prefersReducedMotion } from "../../lib/motion";
import type { EventCategory } from "../../types/event";
import { FilterChip } from "../ui/FilterChip";

interface CategoryFilterBarProps {
  readonly activeCategory: EventCategory | null;
  readonly onSelect: (category: EventCategory | null) => void;
}

/**
 * Filtri categoria in vetro. L'indicatore della categoria attiva è un unico elemento che scorre tra le voci
 * (`useSlidingIndicator`: solo `transform`/`width`, nessun layout shift) e, se non si riesce a misurarlo, ogni voce
 * attiva mostra comunque il proprio sfondo (vedi `[data-thumb]` in discover.css).
 */
export function CategoryFilterBar({ activeCategory, onSelect }: CategoryFilterBarProps) {
  const barRef = useRef<HTMLDivElement>(null);
  const scrollerRef = useRef<HTMLDivElement>(null);
  const trackRef = useRef<HTMLDivElement>(null);
  const selectedKey = activeCategory ?? "all";

  useSlidingIndicator({ trackRef, stateRef: barRef, activeSelector: '[data-selected="true"]', activeKey: selectedKey });

  const updateScrollHints = useCallback(() => {
    const bar = barRef.current;
    const scroller = scrollerRef.current;
    if (!bar || !scroller) return;
    bar.dataset.scrollStart = String(scroller.scrollLeft > 2);
    bar.dataset.scrollEnd = String(scroller.scrollLeft + scroller.clientWidth < scroller.scrollWidth - 2);
  }, []);

  const revealSelected = useCallback((smooth: boolean) => {
    const scroller = scrollerRef.current;
    const chip = trackRef.current?.querySelector<HTMLElement>('[data-selected="true"]');
    if (!scroller || !chip || scroller.clientWidth === 0) return;

    // Scorre solo la barra (mai gli antenati, come farebbe scrollIntoView) e centra la voce attiva.
    const centered = chip.offsetLeft - (scroller.clientWidth - chip.offsetWidth) / 2;
    const left = Math.max(0, Math.min(scroller.scrollWidth - scroller.clientWidth, centered));
    if (Math.abs(left - scroller.scrollLeft) < 2) return;
    scroller.scrollTo({ left, behavior: smooth && !prefersReducedMotion() ? "smooth" : "auto" });
  }, []);

  const firstRunRef = useRef(true);
  useLayoutEffect(() => {
    revealSelected(!firstRunRef.current);
    firstRunRef.current = false;
    updateScrollHints();
  }, [selectedKey, revealSelected, updateScrollHints]);

  // Sfumature ai bordi: dipendono dalle larghezze, che cambiano con il font, il resize e il ritorno di Discover visibile.
  useEffect(() => {
    const track = trackRef.current;
    const scroller = scrollerRef.current;
    if (!track || !scroller) return undefined;

    const observer = new ResizeObserver(updateScrollHints);
    observer.observe(track);
    observer.observe(scroller);
    void document.fonts?.ready.then(updateScrollHints);
    return () => observer.disconnect();
  }, [updateScrollHints]);

  return (
    <div
      ref={barRef}
      className="discover-filter-bar glass"
      role="group"
      aria-label="Filtra gli eventi per categoria"
      data-map-inset="top"
      data-tour="categories"
    >
      <div ref={scrollerRef} className="discover-filter-bar__scroller scrollbar-none" onScroll={updateScrollHints}>
        <div ref={trackRef} className="discover-filter-bar__track">
          <span className="discover-filter-bar__thumb" aria-hidden="true" />
          {categoryOptions.map((option) => (
            <FilterChip
              key={option.value ?? "all"}
              label={option.label}
              category={option.value ?? undefined}
              selected={activeCategory === option.value}
              onSelect={() => onSelect(option.value)}
            />
          ))}
        </div>
      </div>
    </div>
  );
}
