import { ArrowUpRight, CheckCircle2, Heart, MapPin, X } from "lucide-react";
import { useRef, useState } from "react";
import type { SyntheticEvent } from "react";
import { Link, useLocation, useNavigate } from "react-router";

import { useAuth } from "../../context/AuthContext";
import { useMyParticipations } from "../../context/MyParticipationsContext";
import { categoryColors, categoryLabels, formatEventDate, formatEventTimeRange } from "../../lib/eventPresentation";
import { deleteEventParticipation, getParticipationErrorMessage, updateEventParticipation } from "../../lib/participations";
import { getQuickParticipationAction, getQuickParticipationFeedback } from "../../lib/quickParticipation";
import type { EventSummary } from "../../types/event";
import type { ParticipationStatus } from "../../types/participation";
import { CategoryDisc } from "./CategoryIcon";
import { EventImage } from "./EventImage";
import { EventPreviewPeople } from "./EventPreviewPeople";
import { ParticipationChip } from "./ParticipationIndicator";
import { SourceBadge } from "./SourceBadge";

interface EventPreviewProps {
  readonly event: EventSummary;
  /** Il tuo stato su questo evento, se hai risposto. */
  readonly participation?: ParticipationStatus;
  /** L'anteprima sta uscendo: resta visibile per l'animazione ma non è più interattiva né letta come contenuto. */
  readonly leaving?: boolean;
  readonly onClose: () => void;
  readonly onFeedback: (message: string) => void;
}

export function EventPreview({ event, participation, leaving = false, onClose, onFeedback }: EventPreviewProps) {
  const { status, authorizedRequest } = useAuth();
  const { apply, visibleToOthersByEventId } = useMyParticipations();
  const location = useLocation();
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const mutationRef = useRef(false);

  async function changeParticipation(requested: ParticipationStatus) {
    if (status === "anonymous") {
      navigate("/login", { state: { from: location } });
      return;
    }
    if (mutationRef.current) return;

    const action = getQuickParticipationAction(participation, requested);
    const previousStatus = participation;
    const visibleToOthers = visibleToOthersByEventId.get(event.id) ?? false;
    const optimisticStatus = action.kind === "remove" ? null : action.status;

    mutationRef.current = true;
    setBusy(true);
    setActionError(null);
    apply(event.id, optimisticStatus, visibleToOthers);

    try {
      if (action.kind === "remove") {
        await authorizedRequest((accessToken) => deleteEventParticipation(accessToken, event.id));
        onFeedback(getQuickParticipationFeedback(null));
      } else {
        const saved = await authorizedRequest((accessToken) => updateEventParticipation(accessToken, event.id, {
          status: action.status,
          visibleToOthers,
        }));
        apply(event.id, saved.status, saved.visibleToOthers);
        onFeedback(getQuickParticipationFeedback(saved.status));
      }
    } catch (error) {
      apply(event.id, previousStatus ?? null, visibleToOthers);
      setActionError(getParticipationErrorMessage(error));
    } finally {
      mutationRef.current = false;
      setBusy(false);
    }
  }

  function stopCardInteraction(event: SyntheticEvent) {
    event.stopPropagation();
  }

  return (
    <article
      className="event-preview glass glass--strong content-reveal"
      style={{ "--event-category-color": categoryColors[event.category] } as React.CSSProperties}
      aria-labelledby="selected-event-title"
      aria-hidden={leaving || undefined}
      inert={leaving || undefined}
      data-leaving={leaving || undefined}
      data-map-inset={leaving ? undefined : "bottom"}
    >
      <div className="event-preview__image">
        <EventImage imageUrl={event.imageUrl} imageAlt={event.imageAlt} category={event.category} compact className="size-full object-cover" />
      </div>

      <div className="event-preview__content min-w-0 flex-1">
        <p className="flex items-center gap-1.5 text-xs font-extrabold uppercase tracking-[0.08em] text-muted">
          <CategoryDisc category={event.category} size={12} className="category-disc--sm" />
          {categoryLabels[event.category]}
          <SourceBadge source={event.source} />
          {participation ? <ParticipationChip status={participation} /> : null}
        </p>
        <h2 id="selected-event-title" className="mt-1 truncate text-base font-extrabold tracking-[-0.03em] text-ink sm:text-lg">
          <Link className="event-preview__link" to={`/events/${event.id}`}>
            {event.title}
          </Link>
        </h2>
        <p className="mt-0.5 truncate text-sm font-extrabold text-linkr-strong">
          {formatEventDate(event.startsAt)} · {formatEventTimeRange(event.startsAt, event.endsAt)}
        </p>
        <p className="mt-0.5 flex items-center gap-1 truncate text-[0.8125rem] font-semibold text-muted">
          <MapPin size={13} className="shrink-0" aria-hidden="true" /> {event.venueName} · {event.neighbourhood}
        </p>
      </div>

      <span className="event-preview__go" aria-hidden="true">
        <ArrowUpRight size={19} strokeWidth={2.4} />
      </span>

      <button
        className="event-preview__close"
        type="button"
        onPointerDown={stopCardInteraction}
        onClick={(clickEvent) => {
          clickEvent.stopPropagation();
          onClose();
        }}
        aria-label="Chiudi anteprima evento"
      >
        <span className="event-preview__close-disc glass glass--strong">
          <X size={15} strokeWidth={2.6} aria-hidden="true" />
        </span>
      </button>

      <EventPreviewPeople eventId={event.id} hasParticipation={participation !== undefined} />

      <div className="event-preview__actions" aria-busy={busy} onClick={stopCardInteraction} onPointerDown={stopCardInteraction}>
        <button
          className="event-preview__participation"
          type="button"
          data-kind="INTERESTED"
          data-selected={participation === "INTERESTED"}
          aria-pressed={participation === "INTERESTED"}
          disabled={busy}
          onClick={() => void changeParticipation("INTERESTED")}
        >
          <Heart size={18} fill={participation === "INTERESTED" ? "currentColor" : "none"} aria-hidden="true" />
          Mi interessa
        </button>
        <button
          className="event-preview__participation"
          type="button"
          data-kind="GOING"
          data-selected={participation === "GOING"}
          aria-pressed={participation === "GOING"}
          disabled={busy}
          onClick={() => void changeParticipation("GOING")}
        >
          <CheckCircle2 size={18} aria-hidden="true" />
          Ci sarò
        </button>
      </div>
      {actionError ? <p className="event-preview__error" role="alert">{actionError}</p> : null}
    </article>
  );
}
