import { Check, Clock3, Link2, UserPlus } from "lucide-react";
import { useEffect, useRef } from "react";
import { Link } from "react-router";

import type { LinkAction } from "../../hooks/useLinkActions";
import { LINK_NEEDS_SHARED_INTEREST_HINT } from "../../lib/linkState.ts";
import type { ProfileLinkAffordance } from "../../lib/linkState.ts";
import { WriteMessageLink } from "../chat/WriteMessageLink";

interface ProfileLinkActionsProps {
  readonly displayName: string;
  readonly affordance: ProfileLinkAffordance;
  /** Azione in corso: il pulsante resta dov'è (e col fuoco) ma non reagisce, e dice cosa sta succedendo. */
  readonly pendingAction: LinkAction | undefined;
  /** Testo dell'ultimo errore, se c'è. */
  readonly errorMessage: string | undefined;
  /** L'invio è stato rifiutato perché chi agisce non compare in Scopri persone. */
  readonly discoveryRequired: boolean;
  /** Frase per i lettori di schermo dopo un'azione riuscita (il cambio di stato a vista non basta a chi non vede lo schermo). */
  readonly announcement: string;
  readonly onSend: () => void;
  readonly onAccept: () => void;
}

/**
 * Stato Link con la persona e l'azione che ne segue, nel profilo pubblico. Un solo posto per stato e azione: niente badge che
 * ripete ciò che dice il pulsante. Presentazionale: i dati e le chiamate sono della pagina (vedi `useLinkActions`).
 */
export function ProfileLinkActions({ displayName, affordance, pendingAction, errorMessage, discoveryRequired, announcement, onSend, onAccept }: ProfileLinkActionsProps) {
  const rootRef = useRef<HTMLDivElement>(null);
  const previousKindRef = useRef(affordance.kind);
  const pending = pendingAction !== undefined;

  // Il pulsante premuto lascia il posto allo stato nuovo ("Invia Link" → "Richiesta inviata"): il fuoco passerebbe alla pagina
  // e chi usa la tastiera dovrebbe ripartire dall'inizio. Se il fuoco è andato perso, lo si porta sul blocco.
  useEffect(() => {
    if (previousKindRef.current === affordance.kind) return;
    previousKindRef.current = affordance.kind;
    const active = document.activeElement;
    if (!active || active === document.body) rootRef.current?.focus({ preventScroll: true });
  }, [affordance.kind]);

  if (affordance.kind === "self") return null;

  // `aria-disabled` e non `disabled`: un pulsante disabilitato perde il fuoco (Safari) proprio mentre l'azione è in corso.
  const sendNow = () => { if (!pending) onSend(); };
  const acceptNow = () => { if (!pending) onAccept(); };

  return (
    <div className="mt-5">
      <div ref={rootRef} className="flex flex-wrap items-center gap-3 rounded-2xl" role="group" aria-label={`Link con ${displayName}`} aria-busy={pending} tabIndex={-1}>
        {affordance.kind === "send" ? (
          <button className="button-primary inline-flex w-full items-center gap-2 sm:w-auto" type="button" aria-disabled={pending} onClick={sendNow}>
            <UserPlus size={17} aria-hidden="true" /> {pendingAction === "send" ? "Invio…" : "Invia Link"}
          </button>
        ) : null}

        {affordance.kind === "needs-shared-interest" ? (
          <p className="text-sm font-semibold leading-6 text-muted">{LINK_NEEDS_SHARED_INTEREST_HINT}</p>
        ) : null}

        {affordance.kind === "awaiting-answer" ? (
          <span className="status-pill"><Clock3 size={16} aria-hidden="true" /> Richiesta inviata</span>
        ) : null}

        {affordance.kind === "can-accept" || affordance.kind === "received" ? (
          <span className="status-pill">Richiesta ricevuta</span>
        ) : null}
        {affordance.kind === "can-accept" ? (
          <button className="button-primary inline-flex w-full items-center gap-2 sm:w-auto" type="button" aria-disabled={pending} onClick={acceptNow}>
            <Check size={17} aria-hidden="true" /> {pendingAction === "accept" ? "Accetto…" : "Accetta Link"}
          </button>
        ) : null}

        {affordance.kind === "archived" ? <span className="status-pill">Richiesta archiviata</span> : null}

        {affordance.kind === "can-message" || affordance.kind === "connected" ? (
          <span className="status-pill status-pill--success"><Link2 size={16} aria-hidden="true" /> Siete collegati</span>
        ) : null}
        {affordance.kind === "can-message" ? (
          <WriteMessageLink conversationId={affordance.conversationId} className="button-primary w-full sm:w-auto" label="Scrivi un messaggio" />
        ) : null}
      </div>

      {errorMessage ? <p className="mt-3 text-sm font-bold leading-5 text-danger" role="alert">{errorMessage}</p> : null}
      {discoveryRequired ? (
        <p className="form-message mt-3" role="alert">
          Per inviare un Link devi comparire in Scopri persone.{" "}
          <Link className="font-extrabold text-ink underline decoration-linkr/50 underline-offset-4" to="/people">Attiva Scopri persone</Link>
        </p>
      ) : null}

      {/* Esiste fin dal primo rendering: un'area `live` creata insieme al messaggio spesso non viene letta. */}
      <p className="sr-only" role="status" aria-live="polite">{announcement}</p>
    </div>
  );
}
