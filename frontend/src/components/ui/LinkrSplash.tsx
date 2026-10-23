import type { ReactNode } from "react";

import { LinkrHands, LinkrLockup } from "../brand/LinkrLogo";

interface LinkrSplashProps {
  /** `screen`: a tutta pagina, con l'ingresso animato (avvio dell'app). `panel`: copre il solo contenitore, con il loader a mani (mappa in caricamento). */
  readonly variant?: "screen" | "panel";
  /** In uscita: dissolvenza di 240 ms; il chiamante smonta il componente quando è finita. */
  readonly leaving?: boolean;
  /** Frase sotto il logo (opzionale). */
  readonly message?: string;
  /** Azioni sotto la frase, per esempio "Riprova". */
  readonly children?: ReactNode;
}

/**
 * Schermata di avvio e caricamento LINKR, riutilizzabile. `screen`: sfondo off-white, le due mani entrano dai lati e incorniciano il wordmark.
 * `panel`: le sole mani, che si avvicinano e si allontanano piano (loader). Annuncia il suo stato ai lettori di schermo; non cattura il
 * focus né blocca la tastiera. Le animazioni sono in splash.css e si spengono con "riduci animazioni".
 */
export function LinkrSplash({ variant = "screen", leaving = false, message, children }: LinkrSplashProps) {
  return (
    <div className="linkr-splash" data-variant={variant} data-leaving={leaving ? "" : undefined} role="status">
      <div className="linkr-splash__content">
        {variant === "screen"
          ? <LinkrLockup className="linkr-splash__lockup" />
          : <LinkrHands className="linkr-splash__loader" />}
        <span className="sr-only">LINKR</span>
        {message ? <p className="linkr-splash__message">{message}</p> : <span className="sr-only">Caricamento in corso</span>}
        {children ? <div className="linkr-splash__action">{children}</div> : null}
      </div>
    </div>
  );
}
