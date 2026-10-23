import { useCallback, useRef, useState } from "react";

import { useAuth } from "../context/AuthContext";
import { useLinkRequestCount } from "../context/LinkRequestCountContext";
import {
  acceptLinkRequest,
  declineLinkRequest,
  getLinkRequestErrorMessage,
  sendLinkRequest,
} from "../lib/linkRequests";
import { linkStatusFromResponse } from "../lib/linkState.ts";
import { isDiscoveryRequired } from "../lib/people";
import type { LinkStatus } from "../types/people";

export type LinkAction = "send" | "accept" | "decline";

/** Persona su cui si agisce. `linkRequestId` serve per accettare e rifiutare (inviare non ne ha bisogno). */
export interface LinkActionTarget {
  readonly userId: string;
  readonly linkRequestId: string | null;
}

export type LinkActionResult =
  /** Il servizio ha risposto: il chiamante aggiorna lo stato che mostra. */
  | { readonly outcome: "done"; readonly linkStatus: LinkStatus; readonly linkRequestId: string }
  /** Chi agisce non compare in Scopri persone: finché non sceglie di comparire il servizio non gli lascia inviare Link. */
  | { readonly outcome: "discovery-required" }
  /** Il servizio ha rifiutato o non ha risposto. Il testo per l'utente è già in `actionErrors`. */
  | { readonly outcome: "failed"; readonly error: unknown }
  /** Un'azione per questa persona è già in corso, o manca l'identificativo della richiesta: nessuna chiamata. */
  | { readonly outcome: "ignored" };

interface LinkActionsOptions {
  /** Dove si trova chi agisce: cambia solo qualche testo d'errore (vedi `getLinkRequestErrorMessage`). */
  readonly errorContext?: "event" | "profile";
}

function without<Value>(record: Readonly<Record<string, Value>>, key: string): Readonly<Record<string, Value>> {
  if (!(key in record)) return record;
  const { [key]: _removed, ...rest } = record;
  return rest;
}

/**
 * Invia, accetta e rifiuta richieste Link da una schermata che mostra persone (Scopri persone, profilo pubblico).
 *
 * Una sola azione alla volta per persona: finché è in corso un'altra chiamata uguale viene ignorata, quindi un doppio clic o Invio + clic
 * non spediscono due richieste. Il hook tiene l'azione in corso e l'ultimo errore *per persona* e non sa nulla della pagina: l'esito
 * torna al chiamante, che decide come aggiornare ciò che mostra.
 */
export function useLinkActions({ errorContext }: LinkActionsOptions = {}) {
  const { authorizedRequest } = useAuth();
  const { refreshIncomingCount } = useLinkRequestCount();
  const locksRef = useRef(new Set<string>());
  const [pendingActions, setPendingActions] = useState<Readonly<Record<string, LinkAction>>>({});
  const [actionErrors, setActionErrors] = useState<Readonly<Record<string, string>>>({});

  const perform = useCallback(async (target: LinkActionTarget, action: LinkAction): Promise<LinkActionResult> => {
    const { userId, linkRequestId } = target;
    if (locksRef.current.has(userId)) return { outcome: "ignored" };
    if (action !== "send" && !linkRequestId) return { outcome: "ignored" };

    locksRef.current.add(userId);
    setPendingActions((current) => ({ ...current, [userId]: action }));
    setActionErrors((current) => without(current, userId));

    try {
      const response = await authorizedRequest((accessToken) => {
        if (action === "send") return sendLinkRequest(accessToken, { recipientId: userId });
        if (action === "accept") return acceptLinkRequest(accessToken, linkRequestId!);
        return declineLinkRequest(accessToken, linkRequestId!);
      });
      // Rispondere a una richiesta cambia il conteggio delle ricevute (badge nella navigazione); inviarne una no.
      if (action !== "send") void refreshIncomingCount(true);
      return { outcome: "done", linkStatus: linkStatusFromResponse(response), linkRequestId: response.requestId };
    } catch (error) {
      if (isDiscoveryRequired(error)) return { outcome: "discovery-required" };
      setActionErrors((current) => ({ ...current, [userId]: getLinkRequestErrorMessage(error, errorContext) }));
      return { outcome: "failed", error };
    } finally {
      locksRef.current.delete(userId);
      setPendingActions((current) => without(current, userId));
    }
  }, [authorizedRequest, errorContext, refreshIncomingCount]);

  return { pendingActions, actionErrors, perform };
}
