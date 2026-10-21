import { Check, MapPin } from "lucide-react";
import { useEffect, useRef } from "react";

interface MapPointBarProps {
  /** `null` finché l'utente non ha toccato la mappa. */
  readonly hasPoint: boolean;
  /** Il punto esiste ma cade fuori dall'area in cui LINKR accetta eventi. */
  readonly outsideMilan: boolean;
  readonly onConfirm: () => void;
  readonly onCancel: () => void;
}

/** Barra sulla mappa durante "Scegli un punto": spiega cosa fare e conferma o annulla. Non decide nulla da sola. */
export function MapPointBar({ hasPoint, outsideMilan, onConfirm, onCancel }: MapPointBarProps) {
  const barRef = useRef<HTMLDivElement>(null);

  // Il dialogo si è chiuso per lasciare la mappa libera: il focus passa qui, così le istruzioni vengono lette.
  useEffect(() => {
    barRef.current?.focus({ preventScroll: true });
  }, []);

  const message = !hasPoint
    ? "Tocca la mappa per scegliere il punto."
    : outsideMilan
      ? "Questo punto è fuori Milano. Spostalo in città."
      : "Punto scelto. Puoi trascinarlo per spostarlo.";

  return (
    <div
      ref={barRef}
      className="map-point-bar glass glass--strong content-reveal"
      role="group"
      aria-labelledby="map-point-message"
      tabIndex={-1}
      data-map-inset="bottom"
    >
      <span className="map-point-bar__icon" aria-hidden="true"><MapPin size={18} /></span>
      <p id="map-point-message" className="min-w-0 flex-1 text-sm font-extrabold leading-5 text-ink" aria-live="polite" data-invalid={outsideMilan || undefined}>
        {message}
      </p>
      <div className="map-point-bar__actions">
        <button className="button-secondary inline-flex items-center" type="button" onClick={onCancel}>Annulla</button>
        <button className="button-primary inline-flex items-center gap-1.5" type="button" onClick={onConfirm} disabled={!hasPoint || outsideMilan}>
          <Check size={17} strokeWidth={2.6} aria-hidden="true" /> Usa questo punto
        </button>
      </div>
    </div>
  );
}
