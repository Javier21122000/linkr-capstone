import { ArrowLeft, CalendarDays, Clock3, Compass, ExternalLink, MapPin, Pencil, RotateCcw, Trash2 } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Link, useLocation, useParams } from "react-router";

import { CategoryDisc } from "../components/events/CategoryIcon";
import { EventImage } from "../components/events/EventImage";
import { CancelEventDialog } from "../components/events/CancelEventDialog";
import { EventPeopleSection } from "../components/events/EventPeopleSection";
import { EventParticipationPanel } from "../components/events/EventParticipationPanel";
import { SourceBadge } from "../components/events/SourceBadge";
import { ShareEventButton } from "../components/events/ShareEventButton";
import { ViewOnMapLink } from "../components/events/ViewOnMapLink";
import { EmptyEventsIllustration } from "../components/ui/EmptyEventsIllustration";
import { useAuth } from "../context/AuthContext";
import { categoryLabels, formatEventDate, formatEventTimeRange, isEventEnded } from "../lib/eventPresentation";
import { cancelEvent, getCancelEventFailure } from "../lib/eventCreation";
import { getEvent, getEventsErrorMessage, isEventNotFound, isRequestCancelled } from "../lib/events";
import { officialDetailsUrl, sourceLabel } from "../lib/eventSource";
import { hasValidCoordinates } from "../lib/map";
import { createBackState, readBackRequest } from "../lib/navigation";
import type { EventDetail } from "../types/event";
import type { ParticipationResponse } from "../types/participation";

type EventState =
  | { readonly status: "loading" }
  | { readonly status: "success"; readonly event: EventDetail }
  | { readonly status: "error"; readonly message: string; readonly notFound?: boolean };

export function EventDetailPage() {
  const { eventId } = useParams<{ eventId: string }>();
  const { authorizedRequest, status } = useAuth();
  const location = useLocation();
  // Da Admin si torna ad Admin > Eventi; senza contesto (o da Discover) il ritorno resta Discover.
  const back = readBackRequest(location.state);
  const backTo = back?.target.to ?? "/";
  const backLabel = back?.target.label ?? "Discover";
  const [requestKey, setRequestKey] = useState(0);
  const [eventState, setEventState] = useState<EventState>({ status: "loading" });
  const [participation, setParticipation] = useState<ParticipationResponse | null | undefined>(undefined);
  const [participationRevision, setParticipationRevision] = useState(0);
  const cancellingRef = useRef(false);
  const [cancelDialogOpen, setCancelDialogOpen] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);

  function handleParticipationSaved(nextParticipation: ParticipationResponse) {
    setParticipation(nextParticipation);
    setParticipationRevision((current) => current + 1);
  }

  async function handleCancel() {
    if (!eventId || cancellingRef.current || eventState.status !== "success" || !eventState.event.canEdit) return;
    cancellingRef.current = true;
    setCancelling(true);
    setCancelError(null);
    try {
      await authorizedRequest((accessToken) => cancelEvent(accessToken, eventId));
      setEventState({ status: "success", event: { ...eventState.event, status: "CANCELLED", canEdit: false } });
      setCancelDialogOpen(false);
    } catch (error) {
      setCancelError(getCancelEventFailure(error));
    } finally {
      cancellingRef.current = false;
      setCancelling(false);
    }
  }

  useEffect(() => {
    if (!eventId) {
      setEventState({ status: "error", message: "Identificativo evento non valido." });
      setParticipation(undefined);
      return;
    }

    const controller = new AbortController();
    setEventState({ status: "loading" });
    setParticipation(undefined);

    const request = status === "authenticated"
      ? authorizedRequest((accessToken) => getEvent(eventId, accessToken, controller.signal))
      : getEvent(eventId, undefined, controller.signal);

    void request
      .then((event) => setEventState({ status: "success", event }))
      .catch((error: unknown) => {
        if (!isRequestCancelled(error)) {
          setEventState(isEventNotFound(error)
            ? { status: "error", notFound: true, message: "Questo evento non esiste più, è stato annullato oppure il link non è corretto." }
            : { status: "error", message: getEventsErrorMessage(error) });
        }
      });

    return () => controller.abort();
  }, [authorizedRequest, eventId, requestKey, status]);

  return (
    <div className="page-with-mobile-nav">
      {eventState.status !== "success" && (
        <div className="page-shell pt-8 sm:pt-12 lg:pt-16">
          <Link className="inline-flex min-h-11 items-center gap-2 rounded-full font-extrabold text-ink" to={backTo}>
            <ArrowLeft size={19} aria-hidden="true" /> {back ? `Torna ad ${backLabel}` : "Torna a Discover"}
          </Link>

          {eventState.status === "loading" && (
            <div className="mt-7 grid gap-8 lg:grid-cols-[minmax(0,1.15fr)_minmax(20rem,0.85fr)]" aria-busy="true" aria-label="Caricamento dettaglio evento">
              <div className="min-h-[24rem] animate-pulse rounded-[1.75rem] bg-line/70 motion-reduce:animate-none sm:min-h-[36rem]" />
              <div className="space-y-5 py-4" aria-hidden="true">
                <div className="h-4 w-1/3 animate-pulse rounded-full bg-line/70 motion-reduce:animate-none" />
                <div className="h-20 w-full animate-pulse rounded-2xl bg-line/70 motion-reduce:animate-none" />
                <div className="h-28 w-full animate-pulse rounded-2xl bg-line/70 motion-reduce:animate-none" />
              </div>
            </div>
          )}

          {eventState.status === "error" && (
            <div className="surface-card mt-7 px-6 py-12 text-center">
              <EmptyEventsIllustration className="mx-auto mb-5 w-48" />
              <h1 className="text-3xl font-extrabold tracking-[-0.045em] text-ink">Evento non disponibile</h1>
              <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-muted">{eventState.message}</p>
              {eventState.notFound ? (
                <Link className="button-primary mt-6 inline-flex items-center gap-2" to="/">
                  <Compass size={17} aria-hidden="true" /> Scopri altri eventi
                </Link>
              ) : (
                <button className="button-primary mt-6 inline-flex items-center gap-2" type="button" onClick={() => setRequestKey((current) => current + 1)}>
                  <RotateCcw size={17} aria-hidden="true" /> Riprova
                </button>
              )}
            </div>
          )}
        </div>
      )}

      {eventState.status === "success" && (
        <article className="event-detail">
          <figure className="event-hero" data-empty={!eventState.event.imageUrl}>
            <div className="event-hero__media">
              <EventImage
                imageUrl={eventState.event.imageUrl}
                imageAlt={eventState.event.imageAlt}
                category={eventState.event.category}
                className="absolute inset-0 size-full object-cover"
                loading="eager"
              />
            </div>
            <Link className="event-hero__back glass glass--strong" to={backTo} aria-label={`Torna a ${backLabel}`}>
              <ArrowLeft size={19} strokeWidth={2.4} aria-hidden="true" /> {backLabel}
            </Link>
          </figure>

          <div className="event-detail__sheet">
            <div className="flex flex-wrap items-center gap-2">
              <span className="inline-flex items-center gap-1.5 rounded-full bg-surface py-1 pl-1 pr-3 text-xs font-extrabold uppercase tracking-[0.1em] text-ink ring-1 ring-line">
                <CategoryDisc category={eventState.event.category} size={13} className="category-disc--sm" />
                {categoryLabels[eventState.event.category]}
              </span>
              {/* Un evento importato porta il badge della sua fonte al posto di "Official": non è curato da LINKR né creato da una persona. */}
              {sourceLabel(eventState.event.source) ? (
                <SourceBadge source={eventState.event.source} />
              ) : (
                <span className="rounded-full bg-ink px-2.5 py-1 text-[0.7rem] font-extrabold uppercase tracking-[0.12em] text-white">
                  {eventState.event.type === "COMMUNITY" ? "Community" : "Official"}
                </span>
              )}
              {eventState.event.demo && (
                <span className="rounded-full bg-linkr-soft px-2.5 py-1 text-[0.7rem] font-extrabold uppercase tracking-[0.12em] text-linkr-strong">Demo</span>
              )}
              {eventState.event.status === "CANCELLED" ? (
                <span className="rounded-full bg-line px-2.5 py-1 text-[0.7rem] font-extrabold uppercase tracking-[0.12em] text-muted">Annullato</span>
              ) : null}
            </div>
            <h1 className="mt-4 text-[clamp(2.25rem,8vw,4.5rem)] font-extrabold leading-[0.95] tracking-[-0.06em] text-ink">
              {eventState.event.title}
            </h1>

            {eventState.event.creator ? (
              <p className="mt-3 text-sm font-bold text-muted">
                Organizzato da <span className="text-ink">{eventState.event.creator.displayName}</span> · @{eventState.event.creator.username}
              </p>
            ) : null}

            <ul className="event-facts">
              <li className="event-facts__item">
                <span className="event-facts__icon"><CalendarDays size={20} aria-hidden="true" /></span>
                <span className="min-w-0">
                  <span className="block font-extrabold text-ink">{formatEventDate(eventState.event.startsAt)}</span>
                  <span className="mt-0.5 flex items-center gap-1.5 text-sm font-semibold text-muted">
                    <Clock3 size={14} aria-hidden="true" /> {formatEventTimeRange(eventState.event.startsAt, eventState.event.endsAt)}
                  </span>
                </span>
              </li>
              <li className="event-facts__item event-facts__item--wide">
                <span className="event-facts__icon"><MapPin size={20} aria-hidden="true" /></span>
                <span className="min-w-0">
                  <span className="block font-extrabold text-ink">{eventState.event.venueName}</span>
                  <span className="mt-0.5 block text-sm font-semibold text-muted">{eventState.event.address}, {eventState.event.neighbourhood}</span>
                </span>
              </li>
            </ul>

            {/* Un evento annullato o finito non è più su Discover né ha senso invitare qualcuno: l'azione non promette un marker che non c'è. */}
            {eventState.event.status === "PUBLISHED" && !isEventEnded(eventState.event.startsAt, eventState.event.endsAt) ? (
              <div className="mt-4 flex flex-wrap items-start gap-3">
                {hasValidCoordinates(eventState.event) ? <ViewOnMapLink eventId={eventState.event.id} /> : null}
                <ShareEventButton event={eventState.event} />
              </div>
            ) : null}

            {officialDetailsUrl(eventState.event.source) ? (
              <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2">
                {/* Link esterno: nuova scheda senza opener né referrer; il valore è già validato (solo https) da officialDetailsUrl. */}
                <a
                  className="button-secondary inline-flex items-center gap-2"
                  href={officialDetailsUrl(eventState.event.source) ?? undefined}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  <ExternalLink size={17} aria-hidden="true" /> Dettagli ufficiali
                  <span className="sr-only"> (si apre in una nuova scheda, su {sourceLabel(eventState.event.source)})</span>
                </a>
                <span className="text-xs font-semibold leading-5 text-muted">
                  Programma, prezzi e biglietti sulla pagina ufficiale di {sourceLabel(eventState.event.source)}.
                </span>
              </div>
            ) : null}

            <section className="mt-8" aria-labelledby="event-description-title">
              <h2 id="event-description-title" className="section-kicker">Informazioni sull’evento</h2>
              <p className="mt-3 whitespace-pre-line text-base leading-7 text-ink">{eventState.event.description}</p>
              {eventState.event.imageCredit && (
                <p className="mt-4 text-xs leading-5 text-muted">Crediti immagine: {eventState.event.imageCredit}</p>
              )}
            </section>

            {eventState.event.status === "CANCELLED" ? (
              <div className="form-message mt-7" role="status">
                Questo evento è stato annullato. Rimane visibile come storico, ma non può più essere modificato.
              </div>
            ) : null}

            {eventState.event.canEdit ? (
              <div className="mt-7 flex flex-col gap-3 border-t border-line pt-6 sm:flex-row">
                <Link className="button-primary inline-flex items-center gap-2" to={`/events/${encodeURIComponent(eventState.event.id)}/edit`} state={back ? createBackState(back.context, true) : undefined}>
                  <Pencil size={17} aria-hidden="true" /> Modifica evento
                </Link>
                <button className="button-secondary inline-flex items-center gap-2" type="button" onClick={() => { setCancelError(null); setCancelDialogOpen(true); }}>
                  <Trash2 size={17} aria-hidden="true" /> Annulla evento
                </button>
              </div>
            ) : null}

            {eventState.event.status === "PUBLISHED" ? (
              <>
                <EventParticipationPanel
                  eventId={eventState.event.id}
                  startsAt={eventState.event.startsAt}
                  endsAt={eventState.event.endsAt}
                  onParticipationChange={setParticipation}
                  onParticipationSaved={handleParticipationSaved}
                />

                <EventPeopleSection
                  eventId={eventState.event.id}
                  startsAt={eventState.event.startsAt}
                  endsAt={eventState.event.endsAt}
                  participation={participation}
                  participationRevision={participationRevision}
                />
              </>
            ) : null}
          </div>
        </article>
      )}

      {eventState.status === "success" ? (
        <CancelEventDialog
          open={cancelDialogOpen}
          eventTitle={eventState.event.title}
          submitting={cancelling}
          error={cancelError}
          onClose={() => { if (!cancelling) setCancelDialogOpen(false); }}
          onConfirm={() => { void handleCancel(); }}
        />
      ) : null}
    </div>
  );
}
