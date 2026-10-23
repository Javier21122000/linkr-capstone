import { Check, Share } from "lucide-react";

import { useEventShare } from "../../hooks/useEventShare";
import type { EventSummary } from "../../types/event";

interface ShareEventButtonProps {
  readonly event: Pick<EventSummary, "id" | "title" | "startsAt" | "endsAt" | "venueName" | "neighbourhood">;
}

/**
 * "Condividi" nel dettaglio dell'evento: foglio di condivisione del sistema dove c'è (WhatsApp, Messaggi…), altrimenti copia il link.
 * Senza un indirizzo pubblico (sviluppo in locale) non si mostra: un invito con `localhost` non si aprirebbe a nessuno.
 */
export function ShareEventButton({ event }: ShareEventButtonProps) {
  const { url, feedback, share, dismissFeedback } = useEventShare(event);
  if (!url) return null;

  return (
    <div className="flex min-w-0 flex-col gap-2">
      <button
        className="button-secondary inline-flex items-center gap-2"
        type="button"
        data-share-event
        onClick={() => { dismissFeedback(); void share(); }}
      >
        {feedback === "copied"
          ? <Check size={17} strokeWidth={2.6} aria-hidden="true" />
          : <Share size={17} aria-hidden="true" />}
        {feedback === "copied" ? "Link copiato" : "Condividi"}
      </button>

      {/* Annuncia l'esito ai lettori di schermo senza spostare il focus; esiste sempre, così l'annuncio non dipende dalla prima comparsa. */}
      <span className="sr-only" role="status" aria-live="polite">{feedback === "copied" ? "Link copiato negli appunti" : ""}</span>

      {feedback === "failed" ? (
        <div className="form-message" role="alert">
          <p className="font-extrabold">Non riesco a copiare il link in automatico.</p>
          <p className="mt-1 text-sm font-semibold">Selezionalo e copialo a mano:</p>
          <input
            className="form-input mt-2 w-full"
            type="text"
            readOnly
            value={url}
            aria-label="Link dell'evento"
            onFocus={(focusEvent) => focusEvent.currentTarget.select()}
          />
        </div>
      ) : null}
    </div>
  );
}

interface ShareEventIconButtonProps extends ShareEventButtonProps {
  /** Avviso breve sopra la mappa (lo stesso delle risposte rapide): "Link copiato". */
  readonly onFeedback: (message: string) => void;
}

/** Accesso secondario dall'anteprima sulla mappa: solo l'icona, nella riga social, senza aggiungere altezza alla scheda. */
export function ShareEventIconButton({ event, onFeedback }: ShareEventIconButtonProps) {
  const { url, share } = useEventShare(event);
  if (!url) return null;

  async function handleClick() {
    const outcome = await share();
    if (outcome === "copied") onFeedback("Link copiato");
    else if (outcome === "failed") onFeedback("Non riesco a copiare il link: aprilo dal dettaglio dell'evento.");
  }

  return (
    <button className="event-preview__share" type="button" data-share-event aria-label="Condividi evento" onClick={() => void handleClick()}>
      <Share size={18} aria-hidden="true" />
    </button>
  );
}
