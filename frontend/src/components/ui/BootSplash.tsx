import { useEffect, useLayoutEffect, useRef, useState } from "react";

import { useBootReady } from "../../hooks/useBootReady";
import { SPLASH_EXIT_MS, SPLASH_HINT_MS, SPLASH_MAX_MS } from "../../lib/appBoot";
import { bootStore, splashGone } from "../../lib/bootStore";
import { prefersReducedMotion } from "../../lib/motion";
import { isFirstSplashRun, markSplashSeen, SPLASH_RETURN_DELAY_MS, splashExitDelayMs } from "../../lib/splashCycle";
import { LinkrSplash } from "./LinkrSplash";

function deviceStorage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

/**
 * Splash di avvio: compare una sola volta per caricamento della pagina (mai nelle navigazioni interne). Il logo è completo e aperto dal primo
 * fotogramma e si apre e si chiude a cicli. Quando l'app è pronta non si interrompe a metà: lascia finire il ciclo in corso, torna aperto e
 * solo allora sfuma (lib/splashCycle.ts). Al primo avvio sul dispositivo mostra almeno un ciclo intero; dopo, il movimento parte con un piccolo
 * ritardo, così su una connessione veloce lo splash esce subito. Non copre mai un errore: dopo `SPLASH_MAX_MS` cede il posto a ciò che c'è
 * (al più dopo il ciclo in corso), e dopo `SPLASH_HINT_MS` spiega l'attesa.
 */
export function BootSplash() {
  const ready = useBootReady();
  const [slow, setSlow] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [gone, setGone] = useState(false);
  const [firstRun] = useState(() => isFirstSplashRun(deviceStorage()));
  const startDelayMs = firstRun ? 0 : SPLASH_RETURN_DELAY_MS;
  const shownAt = useRef(0);

  useLayoutEffect(() => {
    shownAt.current = performance.now();
  }, []);

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
    const elapsedMs = performance.now() - shownAt.current;
    const waitMs = splashExitDelayMs(elapsedMs, firstRun, startDelayMs);
    let removeId = 0;
    const exitId = window.setTimeout(() => {
      setLeaving(true);
      markSplashSeen(deviceStorage());
      removeId = window.setTimeout(() => setGone(true), SPLASH_EXIT_MS + 30);
    }, waitMs);
    return () => {
      window.clearTimeout(exitId);
      window.clearTimeout(removeId);
    };
  }, [ready, firstRun, startDelayMs]);

  useEffect(() => {
    if (gone) splashGone.raise();
  }, [gone]);

  if (gone) return null;
  return (
    <LinkrSplash
      leaving={leaving}
      startDelayMs={startDelayMs}
      message={slow && !ready ? "Il server si sta avviando: ci vuole qualche secondo in più." : undefined}
    />
  );
}
