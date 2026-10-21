import { RotateCw } from "lucide-react";

import { BrandMark } from "./BrandMark";

/** Il browser non è riuscito a scaricare il codice di una schermata: rete assente oppure file di una versione precedente non più pubblicato. */
export function isChunkLoadError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error);
  return /dynamically imported module|importing a module script failed|loading chunk|loading css chunk/i.test(message);
}

interface ErrorScreenProps {
  readonly error: unknown;
  /** `true` dentro la struttura dell'app (intestazione e navigazione restano visibili); `false` a pagina intera. */
  readonly inline?: boolean;
}

/**
 * Schermata di errore autonoma: non usa router, contesti né dati dell'app, quindi funziona anche quando a rompersi è proprio quello.
 * "Torna a Discover" è un normale collegamento: ricarica l'applicazione da uno stato pulito.
 */
export function ErrorScreen({ error, inline = false }: ErrorScreenProps) {
  const chunk = isChunkLoadError(error);
  const title = chunk ? "Non riusciamo a caricare questa schermata" : "Qualcosa non ha funzionato";
  const description = chunk
    ? "Controlla la connessione. Se l’app è aperta da molto, potrebbe essere disponibile una nuova versione: ricarica la pagina."
    : "Si è verificato un errore imprevisto. Prova a ricaricare la pagina.";

  return (
    <div
      className={inline ? "page-shell page-with-mobile-nav grid place-items-center py-16" : "grid min-h-dvh place-items-center bg-canvas px-5 py-12 text-ink"}
      role="alert"
    >
      <div className="w-full max-w-xl text-center">
        {inline ? null : <div className="flex justify-center"><BrandMark /></div>}
        <p className={`${inline ? "" : "mt-14 "}text-sm font-extrabold uppercase tracking-[0.16em] text-linkr-strong`}>Errore</p>
        <h1 className="mt-3 text-3xl font-extrabold leading-[1.02] tracking-[-0.05em] text-ink sm:text-5xl">{title}</h1>
        <p className="mx-auto mt-5 max-w-md text-base leading-7 text-muted">{description}</p>
        <div className="mt-8 flex flex-col items-center justify-center gap-3 sm:flex-row">
          <button className="button-primary inline-flex items-center gap-2" type="button" onClick={() => window.location.reload()}>
            <RotateCw size={17} aria-hidden="true" /> Ricarica la pagina
          </button>
          <a className="button-secondary inline-flex items-center" href="/">Torna a Discover</a>
        </div>
      </div>
    </div>
  );
}
