import { UsersRound } from "lucide-react";
import { useEffect, useState } from "react";
import { Link } from "react-router";

import { useAuth } from "../../context/AuthContext";
import { getEventPeople } from "../../lib/people";
import { PREVIEW_PEOPLE_FETCH_SIZE, previewPeopleLabel, summarizePreviewPeople } from "../../lib/previewPeople";
import type { PreviewPeopleSummary } from "../../lib/previewPeople";
import { UserAvatar } from "../auth/UserAvatar";

type PreviewPeopleState =
  | { readonly status: "loading" }
  | { readonly status: "ready"; readonly summary: PreviewPeopleSummary }
  | { readonly status: "unavailable" };

interface EventPreviewPeopleProps {
  readonly eventId: string;
  /** L'API mostra le persone solo a chi ha dichiarato la propria partecipazione: senza, non si chiede nulla. */
  readonly hasParticipation: boolean;
}

/**
 * Riga social dell'anteprima: avatar sovrapposti e conteggio di chi ha scelto di essere visibile. Si carica solo per l'evento selezionato
 * (l'anteprima si monta per un evento alla volta) e solo se l'utente ha già risposto; in ogni altro caso il testo dice la verità, senza numeri.
 */
export function EventPreviewPeople({ eventId, hasParticipation }: EventPreviewPeopleProps) {
  const { status, authorizedRequest } = useAuth();
  const [state, setState] = useState<PreviewPeopleState>({ status: "loading" });
  const canLoad = status === "authenticated" && hasParticipation;

  useEffect(() => {
    if (!canLoad) return undefined;
    const controller = new AbortController();
    setState({ status: "loading" });
    void authorizedRequest((accessToken) => getEventPeople(accessToken, eventId, { sort: "recent", page: 0, size: PREVIEW_PEOPLE_FETCH_SIZE, signal: controller.signal }))
      .then((page) => setState({ status: "ready", summary: summarizePreviewPeople(page) }))
      .catch(() => {
        if (!controller.signal.aborted) setState({ status: "unavailable" });
      });
    return () => controller.abort();
  }, [authorizedRequest, canLoad, eventId]);

  const summary = canLoad && state.status === "ready" ? state.summary : null;
  const label = summary ? previewPeopleLabel(summary) : "";

  let message: string;
  if (!canLoad) message = status === "authenticated" ? "Rispondi per vedere chi viene" : "Accedi per vedere chi viene";
  else if (state.status === "loading") message = "Cerco chi viene…";
  else if (state.status === "unavailable") message = "Elenco non disponibile ora";
  else message = label || "Nessuno ha ancora scelto di mostrarsi";

  return (
    <div className="event-preview__people" aria-busy={canLoad && state.status === "loading"}>
      {summary && summary.avatars.length > 0 ? (
        <span className="event-preview__faces" aria-hidden="true">
          {summary.avatars.map((person) => (
            <UserAvatar key={person.userId} displayName={person.displayName} avatarUrl={person.avatarUrl} className="size-7 text-[0.6rem]" />
          ))}
        </span>
      ) : (
        <span className="event-preview__faces-empty" aria-hidden="true"><UsersRound size={15} /></span>
      )}
      <span className="event-preview__people-text">{message}</span>
      <Link className="event-preview__people-link" to={`/events/${eventId}`}>Chi viene?</Link>
    </div>
  );
}
