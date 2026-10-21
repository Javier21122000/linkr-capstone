import { useEffect, useRef, useState } from "react";

import { prefersReducedMotion } from "../lib/motion";

/**
 * Tiene montato per un attimo l'ultimo valore dopo che è sparito, così un pannello può animare l'uscita invece di svanire di colpo.
 * `leaving` è vero in quell'intervallo: il chiamante lo traduce in `data-leaving` e il CSS fa il resto (solo opacity/transform).
 * Con "riduci animazioni" non c'è attesa: il pannello sparisce subito. Mai un ritardo che blocchi la navigazione o il focus.
 */
export function useLeaving<Value>(value: Value | null, durationMs = 180): { readonly shown: Value | null; readonly leaving: boolean } {
  const [lingering, setLingering] = useState<Value | null>(null);
  const lastRef = useRef<Value | null>(null);

  useEffect(() => {
    if (value !== null) {
      lastRef.current = value;
      setLingering(null);
      return undefined;
    }
    const previous = lastRef.current;
    if (previous === null || prefersReducedMotion()) {
      lastRef.current = null;
      return undefined;
    }
    setLingering(previous);
    lastRef.current = null;
    const timeoutId = window.setTimeout(() => setLingering(null), durationMs);
    return () => window.clearTimeout(timeoutId);
  }, [value, durationMs]);

  return { shown: value ?? lingering, leaving: value === null && lingering !== null };
}
