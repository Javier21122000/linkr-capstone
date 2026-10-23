import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { useAuth } from "../context/AuthContext";
import { isInviteToken } from "../lib/inviteToken";
import { issueInvite } from "../lib/invites";
import { formatEventDate, formatEventTimeRange } from "../lib/eventPresentation";
import { browserShareEnvironment, buildEventShareUrl, buildSharePayload, resolveShareOrigin, shareEvent } from "../lib/eventShare";
import type { ShareOutcome } from "../lib/eventShare";
import type { EventSummary } from "../types/event";

/** Per quanto tempo "Link copiato" resta visibile prima di tornare a "Condividi". */
const FEEDBACK_MS = 2_600;

export type ShareFeedback = "idle" | "copied" | "failed";

/**
 * Token d'invito già ottenuti in questa sessione della pagina, per utente ed evento (mai condivisi tra utenti diversi), e richieste in corso.
 * Il token si prepara PRIMA del tocco: `navigator.share` su Safari iOS deve partire sincrono dentro il gesto, quindi non si può aspettare
 * una risposta del servizio dopo il clic. Se non è ancora pronto si condivide il link semplice, che funziona sempre.
 */
const issuedTokens = new Map<string, string>();
const pendingTokens = new Map<string, Promise<string | null>>();

/**
 * Condivisione di un evento per i componenti: indirizzo pubblico (null = nessuno, non si offre l'azione), esito dell'ultima azione e
 * funzione da chiamare dal gesto dell'utente. L'annullamento del foglio di sistema non produce nessun esito visibile.
 */
export function useEventShare(event: Pick<EventSummary, "id" | "title" | "startsAt" | "endsAt" | "venueName" | "neighbourhood">) {
  const { status, user, authorizedRequest } = useAuth();
  const [feedback, setFeedback] = useState<ShareFeedback>("idle");
  const timerRef = useRef<number | null>(null);
  const userId = status === "authenticated" ? user?.id ?? null : null;
  const cacheKey = userId ? `${userId}:${event.id}` : null;
  const [inviteToken, setInviteToken] = useState<string | null>(() => (cacheKey ? issuedTokens.get(cacheKey) ?? null : null));
  const origin = useMemo(() => resolveShareOrigin(import.meta.env.VITE_PUBLIC_SITE_URL, window.location.origin), []);
  // Il link base (senza invito) è la condizione per offrire la condivisione; con un token firmato si aggiunge `?i=`. Da anonimi: sempre senza.
  const baseUrl = useMemo(() => buildEventShareUrl(origin, event.id), [event.id, origin]);
  const url = useMemo(() => buildEventShareUrl(origin, event.id, inviteToken), [event.id, inviteToken, origin]);

  useEffect(() => {
    setInviteToken(cacheKey ? issuedTokens.get(cacheKey) ?? null : null);
    if (!cacheKey || !baseUrl || issuedTokens.has(cacheKey)) return undefined;

    let cancelled = false;
    let pending = pendingTokens.get(cacheKey);
    if (!pending) {
      pending = authorizedRequest((accessToken) => issueInvite(accessToken, event.id))
        .then((issued) => (isInviteToken(issued.token) ? issued.token : null))
        .catch(() => null)
        .finally(() => pendingTokens.delete(cacheKey));
      pendingTokens.set(cacheKey, pending);
    }
    void pending.then((token) => {
      if (token) issuedTokens.set(cacheKey, token);
      if (!cancelled) setInviteToken(token);
    });
    return () => { cancelled = true; };
  }, [authorizedRequest, baseUrl, cacheKey, event.id]);

  useEffect(() => () => {
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
  }, []);

  const share = useCallback(async (): Promise<ShareOutcome | null> => {
    if (!url) return null;
    const payload = buildSharePayload({
      title: event.title,
      when: `${formatEventDate(event.startsAt)} · ${formatEventTimeRange(event.startsAt, event.endsAt)}`,
      where: `${event.venueName} · ${event.neighbourhood}`,
      url,
    });
    const outcome = await shareEvent(payload, browserShareEnvironment(window.navigator, window.document));
    if (outcome === "copied" || outcome === "failed") {
      setFeedback(outcome);
      if (timerRef.current !== null) window.clearTimeout(timerRef.current);
      // Se la copia non è riuscita il link resta visibile finché l'utente non lo ha copiato a mano: niente timer.
      timerRef.current = outcome === "copied" ? window.setTimeout(() => setFeedback("idle"), FEEDBACK_MS) : null;
    }
    return outcome;
  }, [event.endsAt, event.neighbourhood, event.startsAt, event.title, event.venueName, url]);

  return { url: baseUrl ? url : null, feedback, share, dismissFeedback: useCallback(() => setFeedback("idle"), []) };
}
