import { useCallback, useEffect, useRef, useState } from "react";

import { useAuth } from "../context/AuthContext";
import { getInvite, getInviteLinkErrorMessage, sendInviteLinkRequest } from "../lib/invites";
import type { InviteResponse } from "../types/invite";

type InviteState =
  | { readonly status: "hidden" }
  | { readonly status: "ready"; readonly invite: InviteResponse };

/**
 * Chi ha condiviso l'evento, per chi lo apre con un invito (`?i=<token>`). Solo da autenticato: da anonimo non si chiede nulla e non si mostra
 * nulla. Qualunque esito diverso da "mittente visibile e invito valido" (404 uniforme, errore di rete) nasconde la scheda: l'evento e la
 * registrazione non dipendono mai dall'invito. La richiesta Link parte solo da `connect()`, cioè dal gesto esplicito di chi guarda.
 */
export function useEventInvite(eventId: string, token: string | null) {
  const { status, authorizedRequest } = useAuth();
  const [state, setState] = useState<InviteState>({ status: "hidden" });
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const sendingRef = useRef(false);

  useEffect(() => {
    setState({ status: "hidden" });
    setError(null);
    if (!token || status !== "authenticated") return undefined;

    const controller = new AbortController();
    void authorizedRequest((accessToken) => getInvite(accessToken, token, controller.signal))
      .then((invite) => {
        // Il token è legato a un evento: se non è quello di questa pagina non si mostra nulla.
        if (!controller.signal.aborted && invite.eventId.toLowerCase() === eventId.toLowerCase()) setState({ status: "ready", invite });
      })
      .catch(() => {
        // Nascosto: nessun messaggio, nessun errore visibile.
      });
    return () => controller.abort();
  }, [authorizedRequest, eventId, status, token]);

  const connect = useCallback(async () => {
    if (!token || sendingRef.current || state.status !== "ready") return;
    sendingRef.current = true;
    setSending(true);
    setError(null);
    try {
      const request = await authorizedRequest((accessToken) => sendInviteLinkRequest(accessToken, token));
      setState((current) => (current.status === "ready"
        ? { status: "ready", invite: { ...current.invite, linkStatus: "OUTGOING_PENDING", linkRequestId: request.requestId } }
        : current));
    } catch (failure) {
      setError(getInviteLinkErrorMessage(failure));
      // Se esisteva già una richiesta lo stato mostrato era vecchio: si rilegge quello del servizio.
      void authorizedRequest((accessToken) => getInvite(accessToken, token))
        .then((invite) => setState({ status: "ready", invite }))
        .catch(() => undefined);
    } finally {
      sendingRef.current = false;
      setSending(false);
    }
  }, [authorizedRequest, state.status, token]);

  return { invite: state.status === "ready" ? state.invite : null, sending, error, connect };
}
