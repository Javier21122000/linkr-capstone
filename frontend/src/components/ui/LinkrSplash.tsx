import { useState, type CSSProperties, type ReactNode, type Ref } from "react";

import { SPLASH_CYCLE_MS } from "../../lib/splashCycle";
import { LinkrLockup } from "../brand/LinkrLogo";

interface LinkrSplashProps {
  /** `screen`: a tutta pagina (avvio dell'app). `panel`: copre il solo contenitore, con il loader (mappa in caricamento). Stesso logo e stesso ciclo. */
  readonly variant?: "screen" | "panel";
  /** In uscita: dissolvenza di 240 ms; il chiamante smonta il componente quando è finita. In `screen` il logo si ferma aperto mentre sfuma. */
  readonly leaving?: boolean;
  /** Frase sotto il logo (opzionale). */
  readonly message?: string;
  /** `screen`: attesa a logo fermo e aperto prima del primo ciclo (avvii successivi). */
  readonly startDelayMs?: number;
  /** Azioni sotto la frase, per esempio "Riprova". */
  readonly children?: ReactNode;
  readonly ref?: Ref<HTMLDivElement>;
}

/** Fase comune a tutti i loader: il ciclo di ogni `panel` parte "come se" fosse in corso da sempre, così sostituire un loader con un altro non fa scattare il logo. */
function sharedPhaseMs(): number {
  const now = typeof document !== "undefined" && typeof document.timeline?.currentTime === "number" ? document.timeline.currentTime : performance.now();
  return -(now % SPLASH_CYCLE_MS);
}

/**
 * Schermata di avvio e caricamento LINKR, riutilizzabile: il logo completo (mani e wordmark) che si apre e si chiude (ciclo in splash.css).
 * Annuncia il suo stato ai lettori di schermo; non cattura il focus né blocca la tastiera. Con "riduci animazioni" il logo resta fermo e aperto.
 */
export function LinkrSplash({ variant = "screen", leaving = false, message, startDelayMs = 0, children, ref }: LinkrSplashProps) {
  const [phaseMs] = useState(sharedPhaseMs);
  const delay = variant === "panel" ? phaseMs : startDelayMs;
  const style = { "--linkr-delay": `${Math.round(delay)}ms` } as CSSProperties;
  return (
    <div ref={ref} className="linkr-splash" data-variant={variant} data-leaving={leaving ? "" : undefined} role="status" style={style}>
      <div className="linkr-splash__content">
        <LinkrLockup className="linkr-splash__lockup" />
        <span className="sr-only">LINKR</span>
        {message ? <p className="linkr-splash__message">{message}</p> : <span className="sr-only">Caricamento in corso</span>}
        {children ? <div className="linkr-splash__action">{children}</div> : null}
      </div>
    </div>
  );
}
