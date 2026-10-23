import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { formatEventDate, formatEventTimeRange } from "../lib/eventPresentation";
import { browserShareEnvironment, buildEventShareUrl, buildSharePayload, resolveShareOrigin, shareEvent } from "../lib/eventShare";
import type { ShareOutcome } from "../lib/eventShare";
import type { EventSummary } from "../types/event";

/** Per quanto tempo "Link copiato" resta visibile prima di tornare a "Condividi". */
const FEEDBACK_MS = 2_600;

export type ShareFeedback = "idle" | "copied" | "failed";

/**
 * Condivisione di un evento per i componenti: indirizzo pubblico (null = nessuno, non si offre l'azione), esito dell'ultima azione e
 * funzione da chiamare dal gesto dell'utente. L'annullamento del foglio di sistema non produce nessun esito visibile.
 */
export function useEventShare(event: Pick<EventSummary, "id" | "title" | "startsAt" | "endsAt" | "venueName" | "neighbourhood">) {
  const [feedback, setFeedback] = useState<ShareFeedback>("idle");
  const timerRef = useRef<number | null>(null);
  const url = useMemo(
    () => buildEventShareUrl(resolveShareOrigin(import.meta.env.VITE_PUBLIC_SITE_URL, window.location.origin), event.id),
    [event.id],
  );

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

  return { url, feedback, share, dismissFeedback: useCallback(() => setFeedback("idle"), []) };
}
