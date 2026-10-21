import { useState } from "react";

import { resolveEventImageUrl } from "../../lib/eventImages";
import type { EventSummary } from "../../types/event";
import type { ParticipationStatus } from "../../types/participation";
import { CategoryIcon } from "./CategoryIcon";
import { ParticipationMark } from "./ParticipationIndicator";

interface EventMarkerPinProps {
  readonly event: EventSummary;
  readonly selected: boolean;
  /** Lo stato personale dell'utente autenticato su questo evento, se esiste. Il colore principale del marker resta quello della categoria. */
  readonly participation?: ParticipationStatus;
}

/**
 * Contenuto grafico del marker. Il <button> contenitore è creato da EventMap
 * (MapLibre lo posiziona con `transform`, quindi qui animiamo solo i figli).
 * La fotografia compare solo sul marker selezionato: una sola immagine in più alla volta.
 */
export function EventMarkerPin({ event, selected, participation }: EventMarkerPinProps) {
  const [failedImageUrl, setFailedImageUrl] = useState<string | null>(null);
  const [loadedImageUrl, setLoadedImageUrl] = useState<string | null>(null);
  const photoUrl = selected && event.imageUrl && failedImageUrl !== event.imageUrl ? resolveEventImageUrl(event.imageUrl) : null;

  return (
    <>
      <span className="event-marker__pin">
        <span className="event-marker__bubble">
          <CategoryIcon category={event.category} size={selected ? 21 : 19} strokeWidth={2.3} />
          {photoUrl && (
            <img
              className="event-marker__photo"
              src={photoUrl}
              alt=""
              data-loaded={loadedImageUrl === photoUrl}
              onLoad={() => setLoadedImageUrl(photoUrl)}
              onError={() => setFailedImageUrl(event.imageUrl)}
            />
          )}
        </span>
        {participation ? <ParticipationMark status={participation} className="event-marker__status" /> : null}
      </span>
      <span className="event-marker__label">{event.title}</span>
    </>
  );
}
