import { useEffect, useState } from "react";

import { useBootReady } from "../../hooks/useBootReady";
import { SPLASH_EXIT_MS, SPLASH_HINT_MS, SPLASH_MAX_MS } from "../../lib/appBoot";
import { bootStore } from "../../lib/bootStore";
import { prefersReducedMotion } from "../../lib/motion";
import { LinkrSplash } from "./LinkrSplash";

/**
 * Splash di avvio: compare una sola volta per caricamento della pagina (mai nelle navigazioni interne) e se ne va appena l'app è pronta,
 * senza ritardi aggiunti. Non copre mai un errore: dopo `SPLASH_MAX_MS` cede il posto a ciò che c'è, e dopo `SPLASH_HINT_MS` spiega l'attesa.
 */
export function BootSplash() {
  const ready = useBootReady();
  const [slow, setSlow] = useState(false);
  const [gone, setGone] = useState(false);

  useEffect(() => {
    const hintId = window.setTimeout(() => setSlow(true), SPLASH_HINT_MS);
    const limitId = window.setTimeout(() => bootStore.mark({ forced: true }), SPLASH_MAX_MS);
    return () => {
      window.clearTimeout(hintId);
      window.clearTimeout(limitId);
    };
  }, []);

  useEffect(() => {
    if (!ready) return undefined;
    if (prefersReducedMotion()) {
      setGone(true);
      return undefined;
    }
    const timeoutId = window.setTimeout(() => setGone(true), SPLASH_EXIT_MS + 30);
    return () => window.clearTimeout(timeoutId);
  }, [ready]);

  if (gone) return null;
  return <LinkrSplash leaving={ready} message={slow && !ready ? "Il server si sta avviando: ci vuole qualche secondo in più." : undefined} />;
}
