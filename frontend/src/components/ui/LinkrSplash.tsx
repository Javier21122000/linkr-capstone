import type { ReactNode } from "react";

interface SplashSymbolProps {
  readonly className?: string;
}

/**
 * SIMBOLO TEMPORANEO dello splash: un anello nero e un puntino corallo ("punto d'incontro"). Quando arriva il logo definitivo basta
 * sostituire il contenuto di questa funzione: lo splash e l'overlay della mappa non cambiano. Se il nuovo logo ha altre parti da animare,
 * si aggiornano di conseguenza le classi `linkr-splash__ring` / `linkr-splash__dot` in styles/splash.css (nome → animazione).
 */
export function SplashSymbol({ className }: SplashSymbolProps) {
  return (
    <svg className={className} viewBox="0 0 64 64" fill="none" aria-hidden="true" focusable="false">
      {/* pathLength=1: lo stesso tratteggio funziona qualunque sia il raggio. L'anello parte dall'alto. */}
      <circle className="linkr-splash__ring" cx="32" cy="32" r="22" strokeWidth="7" strokeLinecap="round" pathLength={1} transform="rotate(-90 32 32)" />
      <circle className="linkr-splash__dot" cx="50" cy="14" r="7" />
    </svg>
  );
}

interface LinkrSplashProps {
  /** `screen`: a tutta pagina, con l'ingresso animato (avvio dell'app). `panel`: copre il solo contenitore, statico (mappa in caricamento). */
  readonly variant?: "screen" | "panel";
  /** In uscita: dissolvenza di 240 ms; il chiamante smonta il componente quando è finita. */
  readonly leaving?: boolean;
  /** Frase sotto il simbolo (opzionale). */
  readonly message?: string;
  /** Azioni sotto la frase, per esempio "Riprova". */
  readonly children?: ReactNode;
}

/**
 * Schermata di caricamento LINKR, riutilizzabile: sfondo bianco caldo, simbolo nero, accento corallo e la scritta LINKR.
 * Annuncia il suo stato ai lettori di schermo; non cattura il focus né blocca la tastiera.
 */
export function LinkrSplash({ variant = "screen", leaving = false, message, children }: LinkrSplashProps) {
  return (
    <div className="linkr-splash" data-variant={variant} data-leaving={leaving ? "" : undefined} role="status">
      <div className="linkr-splash__content">
        <SplashSymbol className="linkr-splash__symbol" />
        <span className="linkr-splash__word" aria-label="LINKR">LINKR</span>
        {message ? <p className="linkr-splash__message">{message}</p> : <span className="sr-only">Caricamento in corso</span>}
        {children ? <div className="linkr-splash__action">{children}</div> : null}
      </div>
    </div>
  );
}
